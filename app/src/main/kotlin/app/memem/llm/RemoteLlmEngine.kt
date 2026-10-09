package app.memem.llm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import app.memem.R
import app.memem.engine.GEMMA_MAX_OUTPUT_TOKENS
import app.memem.engine.GEMMA_TEMPERATURE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class RemoteLlmEngine(context: Context) {
    private val app = context.applicationContext
    private val lock = Object()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val ids = AtomicInteger()
    private val clientThread = HandlerThread("memem-llm-client").apply { start() }
    private val clientMessenger = Messenger(object : Handler(clientThread.looper) {
        override fun handleMessage(msg: Message) {
            pending.remove(msg.arg1)?.complete(msg.data ?: Bundle())
        }
    })

    @Volatile private var service: Messenger? = null
    @Volatile private var bound = false
    @Volatile var crashed = false
    @Volatile var modelReady = false
    @Volatile var loadedPath: String? = null
    private val gate = Mutex()

    private val death = IBinder.DeathRecipient {
        crashed = true
        synchronized(lock) {
            service = null
            lock.notifyAll()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            try {
                binder.linkToDeath(death, 0)
            } catch (_: Exception) {
            }
            synchronized(lock) {
                service = Messenger(binder)
                crashed = false
                lock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            crashed = true
            modelReady = false
            bound = false
            synchronized(lock) {
                service = null
                lock.notifyAll()
            }
            bind()
        }
    }

    fun bind() {
        if (bound) return
        val intent = Intent(app, LlmService::class.java)
        bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        if (!bound) return
        try {
            app.unbindService(connection)
        } catch (_: Exception) {
        }
        bound = false
        synchronized(lock) {
            service = null
            lock.notifyAll()
        }
    }

    suspend fun load(gemmaPath: String, embedPath: String?): Boolean {
        val data = Bundle().apply {
            putString("gemma", gemmaPath)
            putString("embed", embedPath)
        }
        val reply = request(EngineProto.LOAD, data, 180_000)
        val ok = reply?.getBoolean("ok") == true
        if (ok) {
            modelReady = true
            loadedPath = gemmaPath
        }
        return ok
    }

    /** Waits out an in-flight load, then loads if the :llm process does not already hold this file. */
    suspend fun ensureLoaded(gemmaPath: String, embedPath: String?): String? {
        if (modelReady && loadedPath == gemmaPath) {
            val ping = request(EngineProto.PING, Bundle(), 8_000)
            if (ping?.getBoolean("ok") == true) return null
            modelReady = false
        }
        if (load(gemmaPath, embedPath)) return null
        modelReady = false
        return app.getString(R.string.error_not_loaded)
    }

    suspend fun embed(text: String): FloatArray? {
        val data = Bundle().apply { putString("text", text) }
        val reply = request(EngineProto.EMBED, data, 8_000) ?: return null
        if (!reply.getBoolean("ok")) return null
        return reply.getFloatArray("vec")
    }

    suspend fun generate(
        system: String,
        user: String,
        schema: String?,
        temperature: Double = GEMMA_TEMPERATURE,
        maxTokens: Int = GEMMA_MAX_OUTPUT_TOKENS,
        retryWithoutSchema: Boolean = true,
    ): GenerateOutcome {
        val first = generateOnce(system, user, schema, temperature, maxTokens)
        if (first.text != null) return first.copy(loaded = modelReady)
        if (!retryWithoutSchema || first.error == "timeout" || schema.isNullOrBlank()) return first.copy(loaded = modelReady)
        val second = generateOnce(system, user, null, temperature, maxTokens)
        val error = listOfNotNull(first.error, second.error).distinct().joinToString("; ").ifBlank { null }
        return second.copy(error = if (second.text != null) first.error else error, loaded = modelReady)
    }

    private suspend fun generateOnce(
        system: String,
        user: String,
        schema: String?,
        temperature: Double,
        maxTokens: Int,
    ): GenerateOutcome {
        val data = Bundle().apply {
            putString("system", system)
            putString("user", user)
            if (schema != null) putString("schema", schema)
            putDouble("temperature", temperature)
            putInt("maxTokens", maxTokens)
        }
        val reply = try {
            request(EngineProto.GENERATE, data, 90_000)
        } catch (_: GemmaTimeout) {
            return GenerateOutcome(null, "timeout", modelReady, schema != null)
        } ?: return GenerateOutcome(
            null,
            if (crashed) "prozess tot" else "binder fehlt",
            modelReady,
            schema != null,
        )
        if (!reply.getBoolean("ok")) {
            val error = reply.getString("error")?.take(240)?.ifBlank { null } ?: "fehler"
            return GenerateOutcome(null, error, modelReady, schema != null)
        }
        val text = reply.getString("text")
        if (text.isNullOrBlank()) return GenerateOutcome(null, "leere antwort", modelReady, schema != null)
        return GenerateOutcome(text, null, modelReady, schema != null)
    }

    private suspend fun request(what: Int, data: Bundle, timeoutMs: Long): Bundle? = gate.withLock {
        withContext(Dispatchers.IO) {
            bind()
            val messenger = awaitService(15_000) ?: return@withContext null
            val id = ids.incrementAndGet()
            val future = CompletableFuture<Bundle>()
            pending[id] = future
            val msg = Message.obtain(null, what)
            msg.arg1 = id
            msg.data = data
            msg.replyTo = clientMessenger
            try {
                messenger.send(msg)
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                throw GemmaTimeout()
            } catch (_: Exception) {
                null
            } finally {
                pending.remove(id)
            }
        }
    }

    private fun awaitService(timeoutMs: Long): Messenger? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (service == null && System.currentTimeMillis() < deadline) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                lock.wait(left.coerceAtMost(500))
            }
            return service
        }
    }
}

private class GemmaTimeout : RuntimeException()

data class GenerateOutcome(
    val text: String?,
    val error: String?,
    val loaded: Boolean,
    val schemaUsed: Boolean,
)
