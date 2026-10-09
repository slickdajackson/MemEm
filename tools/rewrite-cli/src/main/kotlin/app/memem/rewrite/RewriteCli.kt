package app.memem.rewrite

import app.memem.engine.CatalogEntry
import app.memem.engine.GEMMA_MAX_OUTPUT_TOKENS
import app.memem.engine.HashEmbedder
import app.memem.engine.IndexPoint
import app.memem.engine.Kind
import app.memem.engine.MemIndex
import app.memem.engine.buildPrompt
import app.memem.engine.halfToFloat
import app.memem.engine.memeBrief
import app.memem.engine.memeCandidate
import app.memem.engine.memeSchema
import app.memem.engine.parseCatalog
import app.memem.engine.FOLLOW_UP_TEMPERATURE
import app.memem.engine.GEMMA_REPETITION_PENALTY
import app.memem.engine.GEMMA_TEMPERATURE
import app.memem.engine.MESSAGE_DISTRACTORS
import app.memem.engine.RELEVANCE_REFERENCES
import app.memem.engine.absorbModelText
import app.memem.engine.cosine
import app.memem.engine.dedupeCaptions
import app.memem.engine.followUpHint
import app.memem.engine.followUpTargets
import app.memem.engine.hasBlankLineRun
import app.memem.engine.orderByRules
import app.memem.engine.outputLanguage
import app.memem.engine.parseSuggestions
import app.memem.engine.planFollowUp
import app.memem.engine.reasonText
import app.memem.engine.relevanceScore
import app.memem.engine.similarityText
import app.memem.engine.searchTemplates
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.RepetitionPenaltyConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Same rewrite path as the app: search, one Gemma call, the shared parser and the same acceptance rule.
 * Latency is the time until the first passing cards are ready. A follow-up, when fewer than three
 * cards passed, still runs afterwards and its cards stay in the report.
 * Embedding on the JVM is optional. Without it the hash index in the repo is used.
 */
fun main(args: Array<String>) {
    val options = parseArgs(args)
    val gemma = File(options.getValue("gemma"))
    val sentences = File(options.getValue("sentences"))
    val assets = File(options["assets"] ?: "app/src/main/assets")
    if (!gemma.isFile) {
        System.err.println("Gemma-Datei fehlt: ${gemma.path}")
        kotlin.system.exitProcess(2)
    }
    if (!sentences.isFile) {
        System.err.println("Satzdatei fehlt: ${sentences.path}")
        kotlin.system.exitProcess(2)
    }
    val catalog = parseCatalog(
        File(assets, "catalog.json").readText(),
        File(assets, "caption-examples.json").takeIf { it.isFile }?.readText().orEmpty(),
        File(assets, "caption-examples-en.json").takeIf { it.isFile }?.readText().orEmpty(),
    )
    val byId = catalog.associateBy { it.id }
    val cache = File(System.getProperty("java.io.tmpdir"), "memem-rewrite")
    cache.mkdirs()
    val search = Searcher(assets, options["embed"], catalog, cache)
    val engine = Engine(
        EngineConfig(
            modelPath = gemma.absolutePath,
            backend = Backend.CPU(threadCount = 4),
            cacheDir = cache.absolutePath,
            maxNumTokens = 2048,
        ),
    )
    engine.initialize()
    val lines = sentences.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    val reports = JSONArray()
    val markdown = StringBuilder()
    try {
        for (sentence in lines) {
            val started = System.nanoTime()
            val rankedIds = search.top(sentence, 8)
            val picked = rankedIds.take(3).mapNotNull { byId[it] }
            val chosen = if (picked.size >= 3) picked else search.fallback(sentence).take(3)
            val lang = outputLanguage(sentence)
            val briefs = chosen.map { entry ->
                memeBrief(entry.id, entry.name, entry.boxes, entry.meaningFor(lang), entry.examplesFor(lang), lang)
            }
            val prompt = buildPrompt(sentence, briefs)
            val schema = memeSchema(chosen.map { it.id to it.boxes })
            val raw = generate(engine, prompt.system, prompt.user, schema)
            val judged = search.judges(sentence)
            val names = catalog.map { it.name }
            val candidates = chosen.map { memeCandidate(it.id, it.boxes, it.style, it.name, it.examplesFor(lang)) }
            val suggestions = parseSuggestions(
                raw.text.orEmpty(),
                candidates,
                sentence,
                wanted = 3,
                failure = raw.error ?: "keine antwort",
                similarity = judged.margin,
                language = lang,
                copyCosine = judged.cosine,
                otherNames = names,
            ).toMutableList()
            val latency = (System.nanoTime() - started) / 1_000_000
            val rawParts = ArrayList<String>()
            raw.text?.let { rawParts += it }
            val plan = planFollowUp(suggestions, raw.text.orEmpty(), alreadyFollowedUp = false)
            val reserveEntries = rankedIds.mapNotNull { byId[it] }
                .filter { entry -> chosen.none { it.id == entry.id } }
                .take(2)
            val judgedCandidates = candidates.toMutableList()
            if (plan != null) {
                val targets = followUpTargets(suggestions, plan, reserveEntries.map { it.id })
                val subset = targets.mapNotNull { target ->
                    chosen.firstOrNull { it.id == target.templateId }
                        ?: reserveEntries.firstOrNull { it.id == target.templateId }
                }
                if (subset.isNotEmpty()) {
                    val reasons = targets.map { target ->
                        target.templateId to suggestions[target.index].reason
                    }
                    val againPrompt = buildPrompt(
                        sentence,
                        subset.map { entry ->
                            memeBrief(entry.id, entry.name, entry.boxes, entry.meaningFor(lang), entry.examplesFor(lang), lang)
                        },
                        hint = followUpHint(reasons, lang),
                    )
                    val againSchema = if (plan.withoutSchema) null else memeSchema(subset.map { it.id to it.boxes })
                    val again = generate(engine, againPrompt.system, againPrompt.user, againSchema, FOLLOW_UP_TEMPERATURE)
                    again.text?.let { rawParts += it }
                    val fresh = subset.map { memeCandidate(it.id, it.boxes, it.style, it.name, it.examplesFor(lang)) }
                    judgedCandidates += fresh.filter { candidate -> judgedCandidates.none { it.id == candidate.id } }
                    val updated = parseSuggestions(
                        again.text.orEmpty(),
                        fresh,
                        sentence,
                        wanted = fresh.size.coerceAtLeast(1),
                        failure = again.error ?: "keine antwort",
                        similarity = judged.margin,
                        language = lang,
                        copyCosine = judged.cosine,
                        otherNames = names,
                    )
                    for (one in updated) {
                        val target = targets.firstOrNull { it.templateId == one.templateId } ?: continue
                        val current = suggestions[target.index]
                        if (target.replaced) {
                            if (one.fromModel) suggestions[target.index] = one
                        } else if (one.fromModel || current.reason == "fehlt") {
                            suggestions[target.index] = one
                        }
                    }
                }
            }
            val ordered = orderByRules(
                dedupeCaptions(suggestions, judgedCandidates, sentence).filter { it.fromModel },
                sentence,
            )
            suggestions.clear()
            suggestions.addAll(ordered)
            val combined = ModelText(rawParts.joinToString("\n---\n").ifBlank { null }, raw.error)
            val report = reportJson(sentence, latency, search.mode, combined, suggestions, lang)
            reports.put(report)
            markdown.append(reportMarkdown(sentence, latency, search.mode, combined, suggestions))
        }
    } finally {
        engine.close()
        search.close()
    }
    val json = JSONObject().put("sentences", reports).toString(2)
    println(markdown)
    println(json)
    options["out"]?.let { path ->
        val dest = File(path)
        dest.parentFile?.mkdirs()
        dest.writeText(markdown.toString() + "\n" + json + "\n")
    }
}

private data class ModelText(val text: String?, val error: String?)

private data class CaptionSignals(
    val margin: ((String) -> Float)?,
    val cosine: ((String) -> Float)?,
)

private fun generate(
    engine: Engine,
    system: String,
    user: String,
    schema: String?,
    temperature: Double = GEMMA_TEMPERATURE,
    maxTokens: Int = GEMMA_MAX_OUTPUT_TOKENS,
    retryWithoutSchema: Boolean = true,
): ModelText {
    val first = generateOnce(engine, system, user, schema, temperature, maxTokens)
    if (first.text != null) return first
    if (!retryWithoutSchema || first.error == "timeout" || schema.isNullOrBlank()) return first
    val second = generateOnce(engine, system, user, null, temperature, maxTokens)
    if (second.text != null) return second
    val error = listOfNotNull(first.error, second.error).distinct().joinToString("; ")
    return ModelText(null, error.ifBlank { "keine antwort" })
}

private fun generateOnce(
    engine: Engine,
    system: String,
    user: String,
    schema: String?,
    temperature: Double,
    maxTokens: Int,
): ModelText {
    return try {
        val useSchema = !schema.isNullOrBlank()
        val config = ConversationConfig(
            systemInstruction = Contents.of(system),
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = temperature),
            maxOutputToken = maxTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
            enableResponseFormat = useSchema,
        )
        engine.createConversation(config).use { conversation ->
            val done = CompletableFuture<String>()
            val buffer = StringBuilder()
            val format = if (useSchema) ResponseFormat.json(schema) else null
            conversation.sendMessageAsync(
                com.google.ai.edge.litertlm.Message.user(user),
                object : MessageCallback {
                    override fun onMessage(message: com.google.ai.edge.litertlm.Message) {
                        val next = absorbModelText(buffer.toString(), message.toString())
                        buffer.clear()
                        buffer.append(next)
                        if (hasBlankLineRun(buffer.toString())) conversation.cancelProcess()
                    }

                    override fun onDone() {
                        done.complete(buffer.toString())
                    }

                    override fun onError(throwable: Throwable) {
                        if (buffer.isNotEmpty()) done.complete(buffer.toString()) else done.completeExceptionally(throwable)
                    }
                },
                maxOutputToken = maxTokens,
                repetitionPenaltyConfig = RepetitionPenaltyConfig(GEMMA_REPETITION_PENALTY, null, 0.3f, 32),
                responseFormat = format,
            )
            val text = done.get(90, TimeUnit.SECONDS)
            if (text.isBlank()) ModelText(null, "leere antwort") else ModelText(text, null)
        }
    } catch (error: Throwable) {
        ModelText(null, error.javaClass.simpleName + ": " + (error.message ?: "").take(240))
    }
}

private fun reportJson(
    sentence: String,
    latencyMs: Long,
    mode: String,
    raw: ModelText,
    suggestions: List<app.memem.engine.Suggestion>,
    language: String,
): JSONObject {
    val cards = JSONArray()
    suggestions.forEach { item ->
        cards.put(
            JSONObject()
                .put("template", item.templateId)
                .put("lines", JSONArray(item.lines))
                .put("source", if (item.fromModel) "AI" else "literal")
                .put("reason", item.reason)
                .put("why", if (item.fromModel) "" else reasonText(item.reason, language))
                .put("detail", item.detail),
        )
    }
    return JSONObject()
        .put("text", sentence)
        .put("latencyMs", latencyMs)
        .put("search", mode)
        .put("raw", raw.text?.take(800) ?: "")
        .put("error", raw.error ?: "")
        .put("cards", cards)
}

private fun reportMarkdown(
    sentence: String,
    latencyMs: Long,
    mode: String,
    raw: ModelText,
    suggestions: List<app.memem.engine.Suggestion>,
): String {
    val body = StringBuilder()
    body.append("## ").append(sentence).append('\n')
    body.append("Suche: ").append(mode).append(", Latenz ").append(latencyMs).append(" ms\n")
    if (!raw.error.isNullOrBlank()) body.append("Fehler: ").append(raw.error).append('\n')
    suggestions.forEach { item ->
        val source = if (item.fromModel) "AI" else "literal"
        body.append("- ").append(item.templateId).append(" (").append(source).append(")")
        if (item.reason.isNotBlank()) body.append(", Grund: ").append(item.reason)
        body.append('\n')
        item.lines.forEach { line -> body.append("  - ").append(line).append('\n') }
    }
    body.append("Roh: ").append(raw.text?.take(800).orEmpty()).append("\n\n")
    return body.toString()
}

private class Searcher(
    assets: File,
    embedPath: String?,
    private val catalog: List<CatalogEntry>,
    cache: File,
) {
    var mode: String = "stichworte"
        private set
    private var embedder: EmbeddingEngine? = null
    private var litert: MemIndex? = null
    private var hash: MemIndex? = null
    private var queryPrefix: String = ""
    private var idf: Map<String, Float> = emptyMap()
    private var idfDocs: Int = 1
    private var referenceVecs: List<FloatArray>? = null
    private var distractorVecs: List<FloatArray>? = null

    init {
        val points = File(assets, "index/points.json")
        val idfFile = File(assets, "index/idf.json")
        if (points.isFile && idfFile.isFile) {
            val meta = JSONObject(points.readText())
            queryPrefix = meta.optString("queryPrefix")
            val parsed = readPoints(meta)
            hash = MemIndex(
                meta.optInt("dim", 768),
                "hash-v1",
                "",
                parsed,
                readVectors(File(assets, "index/" + meta.optString("hashFile", "vectors-hash.f16"))),
            )
            val idfJson = JSONObject(idfFile.readText())
            val table = idfJson.getJSONObject("idf")
            idf = buildMap {
                val keys = table.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, table.getDouble(key).toFloat())
                }
            }
            idfDocs = idfJson.optInt("n", 1)
            mode = "hash"
            if (!embedPath.isNullOrBlank() && File(embedPath).isFile) {
                try {
                    val engine = EmbeddingEngine(
                        EmbeddingEngineConfig(
                            modelPath = File(embedPath).absolutePath,
                            backend = Backend.CPU(threadCount = 4),
                            cacheDir = cache.absolutePath,
                            maxInputLength = 256,
                        ),
                    )
                    engine.initialize()
                    try {
                        engine.computeEmbedding(
                            listOf(InputData.Text("MemEm")),
                            EmbeddingOptions(normalize = true, outputSize = 768),
                        )
                    } catch (_: Exception) {
                    }
                    embedder = engine
                    litert = MemIndex(
                        meta.optInt("dim", 768),
                        meta.optString("space"),
                        queryPrefix,
                        parsed,
                        readVectors(File(assets, "index/vectors.f16")),
                    )
                    mode = "litert"
                } catch (error: Throwable) {
                    System.err.println(
                        "Embedding auf der JVM nicht geladen (${error.javaClass.simpleName}: ${error.message}). Suche mit Hash-Index.",
                    )
                }
            }
        }
    }

    fun top(sentence: String, limit: Int): List<String> {
        val boxes = catalog.associate { it.id to it.boxes }
        val index = if (mode == "litert") litert else hash
        if (index != null) {
            val vector = if (mode == "litert") {
                embedder!!.computeEmbedding(
                    listOf(InputData.Text(queryPrefix + sentence)),
                    EmbeddingOptions(normalize = true, outputSize = index.dim),
                ).embedding
            } else {
                HashEmbedder.embed(sentence, idf, idfDocs)
            }
            if (vector.size == index.dim) {
                return searchTemplates(index, vector, boxes, limit).map { it.templateId }
            }
        }
        mode = "stichworte"
        return fallback(sentence).take(limit).map { it.id }
    }

    fun judges(message: String): CaptionSignals {
        val engine = embedder ?: return CaptionSignals(null, null)
        val left = embedPair(engine, message) ?: return CaptionSignals(null, null)
        val refs = cachedRefs(engine)
        val distractors = cachedDistractors(engine)
        val cache = HashMap<String, FloatArray?>()
        fun vec(text: String) = cache.getOrPut(text) { embedPair(engine, text) }
        return CaptionSignals(
            margin = { text ->
                val caption = vec(text) ?: return@CaptionSignals 1f
                relevanceScore(left, caption, refs, distractors)
            },
            cosine = { text ->
                val caption = vec(text) ?: return@CaptionSignals 0f
                cosine(left, caption)
            },
        )
    }

    private fun cachedRefs(engine: EmbeddingEngine): List<FloatArray> {
        referenceVecs?.let { return it }
        val vecs = RELEVANCE_REFERENCES.mapNotNull { embedPair(engine, it) }
        if (vecs.size == RELEVANCE_REFERENCES.size) referenceVecs = vecs
        return vecs
    }

    private fun cachedDistractors(engine: EmbeddingEngine): List<FloatArray> {
        distractorVecs?.let { return it }
        val vecs = MESSAGE_DISTRACTORS.mapNotNull { embedPair(engine, it) }
        if (vecs.size == MESSAGE_DISTRACTORS.size) distractorVecs = vecs
        return vecs
    }

    private fun embedPair(engine: EmbeddingEngine, text: String): FloatArray? {
        return try {
            engine.computeEmbedding(
                listOf(InputData.Text(similarityText(text))),
                EmbeddingOptions(normalize = true, outputSize = 768),
            ).embedding
        } catch (_: Exception) {
            null
        }
    }

    fun fallback(sentence: String): List<CatalogEntry> {
        val words = sentence.lowercase().split(Regex("\\W+")).filter { it.length >= 4 }.toSet()
        val ranked = catalog.map { entry ->
            val hay = (entry.name + " " + entry.meaningDe + " " + entry.meaningEn).lowercase()
            entry to words.count { hay.contains(it) }
        }.sortedByDescending { it.second }
        val hits = ranked.filter { it.second > 0 }.map { it.first }
        val preferred = listOf("fine", "drake", "cmm", "db", "ds", "fry").mapNotNull { id -> catalog.find { it.id == id } }
        return (hits + preferred + catalog).distinctBy { it.id }
    }

    fun close() {
        try {
            embedder?.close()
        } catch (_: Exception) {
        }
    }
}

private fun readPoints(meta: JSONObject): List<IndexPoint> {
    val array = meta.getJSONArray("points")
    return buildList {
        for (index in 0 until array.length()) {
            val point = array.getJSONObject(index)
            val lines = point.optJSONArray("lines")
            add(
                IndexPoint(
                    templateId = point.getString("id"),
                    kind = when (point.optString("kind")) {
                        "image" -> Kind.IMAGE
                        "image_text" -> Kind.IMAGE_TEXT
                        else -> Kind.TEXT
                    },
                    lines = buildList {
                        if (lines != null) {
                            for (line in 0 until lines.length()) add(lines.optString(line))
                        }
                    },
                ),
            )
        }
    }
}

private fun readVectors(file: File): FloatArray {
    val bytes = file.readBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val count = bytes.size / 2
    return FloatArray(count) { halfToFloat(buffer.short.toInt()) }
}

private fun parseArgs(args: Array<String>): Map<String, String> {
    val out = linkedMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val key = args[index]
        if (key.startsWith("--") && index + 1 < args.size) {
            out[key.removePrefix("--")] = args[index + 1]
            index += 2
        } else {
            System.err.println("Unbekannt: $key")
            index += 1
        }
    }
    if ("gemma" !in out || "sentences" !in out) {
        System.err.println(
            "Aufruf: --gemma DATEI.litertlm --embed DATEI.litertlm --sentences saetze.txt [--assets app/src/main/assets] [--out bericht.md]",
        )
        kotlin.system.exitProcess(2)
    }
    return out
}
