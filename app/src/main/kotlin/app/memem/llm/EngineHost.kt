package app.memem.llm

import android.os.Bundle
import android.os.Message
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import app.memem.engine.GEMMA_MAX_OUTPUT_TOKENS
import java.io.File

/**
 * Owns the native engines inside the :llm process. A native abort kills only that process.
 */
class EngineHost(private val cacheDir: File) {
    private var gemma: Engine? = null
    private var embedder: EmbeddingEngine? = null
    private var loadedGemma: String? = null

    fun handle(msg: Message) {
        val reply = Bundle()
        try {
            when (msg.what) {
                EngineProto.LOAD -> {
                    load(
                        gemmaPath = msg.data.getString("gemma").orEmpty(),
                        embedPath = msg.data.getString("embed"),
                    )
                    reply.putBoolean("ok", true)
                }
                EngineProto.EMBED -> {
                    reply.putFloatArray("vec", embed(msg.data.getString("text").orEmpty()))
                    reply.putBoolean("ok", true)
                }
                EngineProto.GENERATE -> {
                    reply.putString(
                        "text",
                        generate(
                            system = msg.data.getString("system").orEmpty(),
                            user = msg.data.getString("user").orEmpty(),
                            schema = msg.data.getString("schema"),
                            temperature = msg.data.getDouble("temperature", 0.4),
                            maxTokens = msg.data.getInt("maxTokens", GEMMA_MAX_OUTPUT_TOKENS),
                        ),
                    )
                    reply.putBoolean("ok", true)
                }
                EngineProto.UNLOAD -> {
                    close()
                    reply.putBoolean("ok", true)
                }
                EngineProto.PING -> reply.putBoolean("ok", gemma?.isInitialized() == true)
                else -> reply.putString("error", "unknown")
            }
        } catch (t: Throwable) {
            reply.putBoolean("ok", false)
            reply.putString("error", t.javaClass.simpleName + ": " + (t.message ?: ""))
        }
        val out = Message.obtain(null, msg.what)
        out.arg1 = msg.arg1
        out.data = reply
        try {
            msg.replyTo?.send(out)
        } catch (_: Exception) {
        }
    }

    fun close() {
        try {
            gemma?.close()
        } catch (_: Exception) {
        }
        try {
            embedder?.close()
        } catch (_: Exception) {
        }
        gemma = null
        embedder = null
        loadedGemma = null
    }

    private fun load(gemmaPath: String, embedPath: String?) {
        cacheDir.mkdirs()
        if (gemma?.isInitialized() != true || loadedGemma != gemmaPath) {
            try {
                gemma?.close()
            } catch (_: Exception) {
            }
            val backend = Backend.CPU(threadCount = 4)
            val engine = Engine(
                EngineConfig(
                    modelPath = gemmaPath,
                    backend = backend,
                    cacheDir = cacheDir.absolutePath,
                    maxNumTokens = 2048,
                ),
            )
            engine.initialize()
            gemma = engine
            loadedGemma = gemmaPath
        }
        if (!embedPath.isNullOrBlank() && embedder?.isInitialized() != true) {
            val emb = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = embedPath,
                    backend = Backend.CPU(threadCount = 4),
                    cacheDir = cacheDir.absolutePath,
                    maxInputLength = 256,
                ),
            )
            emb.initialize()
            warmupEmbed(emb)
            embedder = emb
        }
    }

    /** The first embedding of a fresh engine drifts. Discard it before any real query. */
    private fun warmupEmbed(engine: EmbeddingEngine) {
        try {
            engine.computeEmbedding(
                listOf(InputData.Text("MemEm")),
                EmbeddingOptions(normalize = true, outputSize = 768),
            )
        } catch (_: Exception) {
        }
    }

    private fun embed(text: String): FloatArray {
        val engine = embedder ?: error("embedding nicht geladen")
        return engine.computeEmbedding(
            listOf(InputData.Text(text)),
            EmbeddingOptions(normalize = true, outputSize = 768),
        ).embedding
    }

    private fun generate(
        system: String,
        user: String,
        schema: String?,
        temperature: Double,
        maxTokens: Int,
    ): String {
        val engine = gemma ?: error("gemma nicht geladen")
        val useSchema = !schema.isNullOrBlank()
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature),
            maxOutputToken = maxTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
            enableResponseFormat = useSchema,
        )
        engine.createConversation(config).use { conversation ->
            val response = conversation.sendMessage(
                com.google.ai.edge.litertlm.Message.user(user),
                maxOutputToken = maxTokens,
                responseFormat = if (useSchema) ResponseFormat.json(schema!!) else null,
            )
            return response.toString()
        }
    }
}
