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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
            synchronized(lock) {
                service = null
                lock.notifyAll()
            }
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
        return reply?.getBoolean("ok") == true
    }

    suspend fun embed(text: String): FloatArray? {
        val data = Bundle().apply { putString("text", text) }
        val reply = request(EngineProto.EMBED, data, 8_000) ?: return null
        if (!reply.getBoolean("ok")) return null
        return reply.getFloatArray("vec")
    }

    suspend fun generate(system: String, user: String, schema: String?): String? {
        val first = generateOnce(system, user, schema)
        if (first != null) return first
        if (!crashed) return null
        awaitService(12_000)
        return generateOnce(system, user, null)
    }

    private suspend fun generateOnce(system: String, user: String, schema: String?): String? {
        val data = Bundle().apply {
            putString("system", system)
            putString("user", user)
            if (schema != null) putString("schema", schema)
            putDouble("temperature", 0.4)
            putInt("maxTokens", 220)
        }
        val reply = request(EngineProto.GENERATE, data, 25_000) ?: return null
        if (!reply.getBoolean("ok")) return null
        return reply.getString("text")
    }

    private suspend fun request(what: Int, data: Bundle, timeoutMs: Long): Bundle? = withContext(Dispatchers.IO) {
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
        } catch (_: Exception) {
            null
        } finally {
            pending.remove(id)
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
