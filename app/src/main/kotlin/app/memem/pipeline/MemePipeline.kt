package app.memem.pipeline

import android.content.Context
import android.graphics.Bitmap
import app.memem.data.MemAssets
import app.memem.data.MemeTemplate
import app.memem.debug.DebugLog
import app.memem.engine.HashEmbedder
import app.memem.engine.MemIndex
import app.memem.engine.buildPrompt
import app.memem.engine.fallbackLines
import app.memem.engine.memeBrief
import app.memem.engine.memeCandidate
import app.memem.engine.memeSchema
import app.memem.engine.FOLLOW_UP_TEMPERATURE
import app.memem.engine.GEMMA_TEMPERATURE
import app.memem.engine.MESSAGE_DISTRACTORS
import app.memem.engine.RELEVANCE_REFERENCES
import app.memem.engine.Candidate
import app.memem.engine.FollowUp
import app.memem.engine.cosine
import app.memem.engine.dedupeCaptions
import app.memem.engine.fallbackLocale
import app.memem.engine.followUpHint
import app.memem.engine.followUpTargets
import app.memem.engine.orderByRules
import app.memem.engine.outputLanguage
import app.memem.engine.parseSuggestions
import app.memem.engine.planFollowUp
import app.memem.engine.reasonText
import app.memem.engine.relevanceScore
import app.memem.engine.similarityText
import app.memem.settings.Prefs
import app.memem.engine.Suggestion
import app.memem.engine.searchTemplates
import app.memem.engine.searchText
import app.memem.llm.GenerateOutcome
import app.memem.llm.RemoteLlmEngine
import app.memem.models.ModelCatalog
import app.memem.render.MemeRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking

data class MemeOption(
    val template: MemeTemplate,
    val lines: List<String>,
    val bitmap: Bitmap,
    val file: File,
    val fromModel: Boolean,
    val reason: String = "",
)

fun memeMark(fromModel: Boolean, reason: String): String = when {
    fromModel -> "KI"
    reason == "ersatz" -> "Ersatz"
    else -> "wörtlich"
}

class MemePipeline(context: Context) {
    private val app = context.applicationContext
    private val assets = MemAssets(app)
    private val renderer = MemeRenderer(app)
    private val log = DebugLog(app)
    val remote = RemoteLlmEngine(app)
    private var litert: MemIndex? = null
    private var hash: MemIndex? = null
    private var referenceVecs: List<FloatArray>? = null
    private var distractorVecs: List<FloatArray>? = null
    private val followScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val suggestGeneration = AtomicInteger(0)
    private var followJob: Job? = null

    fun preload() {
        val gemmaFile = ModelCatalog.file(app, activeGemma())
        if (!gemmaFile.isFile) return
        val embedFile = ModelCatalog.file(app, ModelCatalog.embed)
        remote.bind()
        // Fire and forget. The keyboard stays usable with the hash index until this returns.
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    remote.load(
                        gemmaPath = gemmaFile.absolutePath,
                        embedPath = embedFile.takeIf { it.isFile && assets.space.startsWith("litert") }?.absolutePath,
                    )
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    suspend fun suggest(
        message: String,
        context: List<String> = emptyList(),
        onPreview: (List<MemeOption>) -> Unit,
    ): List<MemeOption> {
        followJob?.cancel()
        val generationId = suggestGeneration.incrementAndGet()
        val started = System.nanoTime()
        val embedStarted = System.nanoTime()
        val embedded = embedQuery(searchText(message, context))
        val embedMs = ms(embedStarted)
        val searchStarted = System.nanoTime()
        val hits = searchTemplates(embedded.second, embedded.first, assets.boxes, limit = 8)
        val searchMs = ms(searchStarted)
        val ordered = hits.mapNotNull { assets.templates[it.templateId] }
        val picked = ordered.take(3).ifEmpty { assets.templates.values.take(3).toList() }
        val literal = renderOptions(
            message,
            picked.map { it to fallbackLines(message, it.boxes, it.style) },
            fromModel = false,
        )
        onPreview(literal)
        val locale = fallbackLocale(java.util.Locale.getDefault().language, Prefs(app).qwertz)
        val lang = outputLanguage(message, context, locale)
        val gemmaStarted = System.nanoTime()
        val outcome = generate(message, picked, context, locale = locale)
        val gemmaMs = ms(gemmaStarted)
        val candidates = picked.map { memeCandidate(it.id, it.boxes, it.style, it.name, it.examplesFor(lang)) }
        val names = assets.templates.values.map { it.name }
        val score = signals(message)
        val suggestions = if (candidates.isEmpty()) {
            mutableListOf()
        } else {
            parseSuggestions(
                outcome.text.orEmpty(),
                candidates,
                message,
                wanted = 3,
                failure = outcome.error ?: "keine antwort",
                similarity = score.margin,
                language = lang,
                copyCosine = score.cosine,
                otherNames = names,
            ).toMutableList()
        }
        val plan = planFollowUp(suggestions, outcome.text.orEmpty(), alreadyFollowedUp = false)
        val merged = dedupeCaptions(suggestions.toList(), candidates, message)
        val ranked = orderByRules(merged.filter { it.fromModel }, message)
        val renderStarted = System.nanoTime()
        val rendered = ranked.mapNotNull { suggestion ->
            val template = assets.templates[suggestion.templateId] ?: return@mapNotNull null
            renderOne(template, suggestion.lines, suggestion.fromModel, suggestion.reason)
        }
        onPreview(rendered)
        val renderMs = ms(renderStarted)
        val followLabel = when {
            plan == null -> ""
            plan.withoutSchema -> "background-noschema"
            else -> "background"
        }
        log.event(
            mapOf(
                "kind" to "suggest",
                "chars" to message.length,
                "text" to message,
                "contextN" to context.size,
                "context" to context.joinToString(" | "),
                "embedMs" to embedMs,
                "embedSpace" to if (embedded.third) "litert" else "hash",
                "searchMs" to searchMs,
                "gemmaMs" to gemmaMs,
                "gemmaLoaded" to outcome.loaded,
                "gemmaError" to outcome.error,
                "lang" to lang,
                "followUp" to followLabel,
                "raw" to outcome.text?.take(500),
                "reasons" to ranked.joinToString(" | ") { item ->
                    val mark = if (item.fromModel) "KI" else item.reason.ifBlank { "woertlich" }
                    "${item.templateId}:$mark"
                },
                "rejected" to merged.filter { !it.fromModel }.joinToString(" | ") { item ->
                    "${item.templateId}:${item.reason}:${reasonText(item.reason, lang)}:${item.detail}"
                },
                "renderMs" to renderMs,
                "totalMs" to ms(started),
                "templates" to rendered.joinToString(",") { it.template.id },
                "rewritten" to rendered.count { it.fromModel },
            ),
        )
        sweep(rendered.map { it.file.name }.toSet())
        if (plan != null && generationId == suggestGeneration.get()) {
            val reservePool = ordered.filter { it.id !in picked.map { pickedOne -> pickedOne.id } }.take(2)
            followJob = followScope.launch {
                try {
                    val appended = appendFollowUp(
                        message = message,
                        context = context,
                        locale = locale,
                        lang = lang,
                        names = names,
                        score = score,
                        suggestions = suggestions,
                        candidates = candidates,
                        plan = plan,
                        picked = picked,
                        reservePool = reservePool,
                        shown = rendered,
                        generationId = generationId,
                    )
                    ensureActive()
                    if (appended != null && generationId == suggestGeneration.get()) onPreview(appended)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
        }
        return rendered
    }

    /**
     * Second Gemma call for the cards that failed. The first cards stay in place.
     * New cards are appended, and the combined list stays at three.
     */
    private suspend fun appendFollowUp(
        message: String,
        context: List<String>,
        locale: String,
        lang: String,
        names: List<String>,
        score: CaptionSignals,
        suggestions: MutableList<Suggestion>,
        candidates: List<Candidate>,
        plan: FollowUp,
        picked: List<MemeTemplate>,
        reservePool: List<MemeTemplate>,
        shown: List<MemeOption>,
        generationId: Int,
    ): List<MemeOption>? {
        val followStarted = System.nanoTime()
        val retries = ArrayList<String>()
        val judgedCandidates = candidates.toMutableList()
        val targets = followUpTargets(suggestions, plan, reservePool.map { it.id })
        val subset = targets.mapNotNull { target ->
            picked.firstOrNull { it.id == target.templateId } ?: reservePool.firstOrNull { it.id == target.templateId }
        }
        if (subset.isNotEmpty() && generationId == suggestGeneration.get()) {
            val reasons = targets.map { target ->
                target.templateId to suggestions[target.index].reason
            }
            val again = generate(
                message,
                subset,
                context,
                followUpHint(reasons, lang),
                withSchema = !plan.withoutSchema,
                temperature = FOLLOW_UP_TEMPERATURE,
                locale = locale,
            )
            val fresh = subset.map { memeCandidate(it.id, it.boxes, it.style, it.name, it.examplesFor(lang)) }
            judgedCandidates += fresh.filter { candidate -> judgedCandidates.none { it.id == candidate.id } }
            val updated = parseSuggestions(
                again.text.orEmpty(),
                fresh,
                message,
                wanted = fresh.size.coerceAtLeast(1),
                failure = again.error ?: "keine antwort",
                similarity = score.margin,
                language = lang,
                copyCosine = score.cosine,
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
                val mark = if (suggestions[target.index].fromModel) "KI" else suggestions[target.index].reason.ifBlank { "woertlich" }
                retries += "${current.templateId}->${one.templateId}:${current.reason}->$mark"
            }
        }
        if (generationId != suggestGeneration.get()) return null
        val shownIds = shown.map { it.template.id }.toSet()
        val merged = dedupeCaptions(suggestions, judgedCandidates, message)
        val extras = orderByRules(
            merged.filter { it.fromModel && it.templateId !in shownIds },
            message,
        ).mapNotNull { suggestion ->
            val template = assets.templates[suggestion.templateId] ?: return@mapNotNull null
            renderOne(template, suggestion.lines, suggestion.fromModel, suggestion.reason)
        }
        val room = (3 - shown.size).coerceAtLeast(0)
        val kept = extras.take(room)
        extras.drop(room).forEach { option ->
            if (!option.bitmap.isRecycled) option.bitmap.recycle()
            option.file.delete()
        }
        if (generationId != suggestGeneration.get()) {
            kept.forEach { option ->
                if (!option.bitmap.isRecycled) option.bitmap.recycle()
                option.file.delete()
            }
            return null
        }
        val combined = shown + kept
        if (kept.isEmpty()) return null
        log.event(
            mapOf(
                "kind" to "follow",
                "text" to message,
                "lang" to lang,
                "followUp" to if (plan.withoutSchema) "background-noschema" else "background",
                "retries" to retries.joinToString(" | "),
                "reasons" to combined.joinToString(" | ") { option ->
                    val mark = if (option.fromModel) "KI" else option.reason.ifBlank { "woertlich" }
                    "${option.template.id}:$mark"
                },
                "followMs" to ms(followStarted),
                "templates" to combined.joinToString(",") { it.template.id },
                "rewritten" to combined.count { it.fromModel },
            ),
        )
        sweep(combined.map { it.file.name }.toSet())
        return combined
    }

    private suspend fun embedQuery(message: String): Triple<FloatArray, MemIndex, Boolean> {
        val canLitert = assets.space.startsWith("litert") && ModelCatalog.ready(app, ModelCatalog.embed)
        if (canLitert) {
            val vec = remote.embed(assets.queryPrefix + message)
            if (vec != null && vec.size == assets.dim) {
                val index = litert ?: assets.litertIndex().also { litert = it }
                return Triple(vec, index, true)
            }
        }
        val index = hash ?: assets.hashIndex().also { hash = it }
        val vec = HashEmbedder.embed(message, assets.idf(), assets.idfDocs())
        return Triple(vec, index, false)
    }

    private data class CaptionSignals(
        val margin: ((String) -> Float)?,
        val cosine: ((String) -> Float)?,
    )

    private fun signals(message: String): CaptionSignals {
        val embedReady = assets.space.startsWith("litert") && ModelCatalog.ready(app, ModelCatalog.embed)
        if (!embedReady) return CaptionSignals(null, null)
        val messageVec = embedCaption(message) ?: return CaptionSignals(null, null)
        val refs = referenceVectors()
        val distractors = distractorVectors()
        val cache = HashMap<String, FloatArray?>()
        fun vec(text: String): FloatArray? = cache.getOrPut(text) { embedCaption(text) }
        return CaptionSignals(
            margin = { text ->
                val caption = vec(text) ?: return@CaptionSignals 1f
                relevanceScore(messageVec, caption, refs, distractors)
            },
            cosine = { text ->
                val caption = vec(text) ?: return@CaptionSignals 0f
                cosine(messageVec, caption)
            },
        )
    }

    private fun referenceVectors(): List<FloatArray> {
        referenceVecs?.let { return it }
        val vecs = RELEVANCE_REFERENCES.mapNotNull { embedCaption(it) }
        if (vecs.size == RELEVANCE_REFERENCES.size) referenceVecs = vecs
        return vecs
    }

    private fun distractorVectors(): List<FloatArray> {
        distractorVecs?.let { return it }
        val vecs = MESSAGE_DISTRACTORS.mapNotNull { embedCaption(it) }
        if (vecs.size == MESSAGE_DISTRACTORS.size) distractorVecs = vecs
        return vecs
    }

    private fun embedCaption(text: String): FloatArray? {
        return try {
            runBlocking(Dispatchers.IO) { remote.embed(similarityText(text)) }
        } catch (_: Exception) {
            null
        }
    }

    private fun activeGemma() = when {
        Prefs(app).qualityE4b && ModelCatalog.ready(app, ModelCatalog.gemmaE4b) -> ModelCatalog.gemmaE4b
        else -> ModelCatalog.gemmaCpu
    }

    private suspend fun generate(
        message: String,
        templates: List<MemeTemplate>,
        context: List<String>,
        hint: String = "",
        withSchema: Boolean = true,
        temperature: Double = GEMMA_TEMPERATURE,
        locale: String = "",
    ): GenerateOutcome {
        if (templates.isEmpty()) return GenerateOutcome(null, "keine vorlage", remote.modelReady, false)
        val spec = activeGemma()
        val model = ModelCatalog.file(app, spec)
        if (!ModelCatalog.ready(app, spec)) {
            return GenerateOutcome(null, "modell fehlt", false, false)
        }
        val embed = ModelCatalog.file(app, ModelCatalog.embed)
        val loadError = remote.ensureLoaded(
            model.absolutePath,
            embed.takeIf { it.isFile && assets.space.startsWith("litert") }?.absolutePath,
        )
        if (loadError != null) return GenerateOutcome(null, loadError, false, false)
        val lang = outputLanguage(message, context, locale)
        val chosen = templates.take(3)
        val briefs = chosen.map { template ->
            memeBrief(
                template.id,
                template.name,
                template.boxes,
                template.meaningFor(lang),
                template.examplesFor(lang),
                lang,
            )
        }
        val prompt = buildPrompt(message, briefs, context, hint, locale)
        val schema = if (withSchema) memeSchema(chosen.map { it.id to it.boxes }) else null
        return remote.generate(prompt.system, prompt.user, schema, temperature)
    }

    private fun renderOne(
        template: MemeTemplate,
        lines: List<String>,
        fromModel: Boolean,
        reason: String = "",
    ): MemeOption {
        val dir = File(app.filesDir, "memes")
        val bitmap = renderer.render(template, lines)
        val file = File(dir, "${template.id}-${System.nanoTime()}.png")
        renderer.writePng(bitmap, file)
        return MemeOption(template, lines, bitmap, file, fromModel, reason)
    }

    private fun renderOptions(
        message: String,
        rows: List<Pair<MemeTemplate, List<String>>>,
        fromModel: Boolean,
    ): List<MemeOption> {
        return rows.map { (template, lines) -> renderOne(template, lines, fromModel) }
    }

    private fun sweep(keep: Set<String>) {
        val dir = File(app.filesDir, "memes")
        val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
        dir.listFiles()?.forEach { file ->
            if (file.name in keep) return@forEach
            if (file.lastModified() < cutoff || file.name !in keep) {
                if (file.lastModified() < cutoff) file.delete()
            }
        }
        // Previous previews that are not the current set go away on the next suggestion,
        // except files younger than a day that were the last insert. Keep the newest 8.
        val extras = dir.listFiles()?.filter { it.name !in keep }?.sortedByDescending { it.lastModified() }.orEmpty()
        extras.drop(4).forEach { it.delete() }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
}
