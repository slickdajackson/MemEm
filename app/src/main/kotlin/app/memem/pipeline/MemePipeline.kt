package app.memem.pipeline

import android.content.Context
import android.graphics.Bitmap
import app.memem.data.MemAssets
import app.memem.data.MemeTemplate
import app.memem.debug.DebugLog
import app.memem.engine.Brief
import app.memem.engine.Candidate
import app.memem.engine.HashEmbedder
import app.memem.engine.MemIndex
import app.memem.engine.buildPrompt
import app.memem.engine.fallbackLines
import app.memem.engine.fieldRoles
import app.memem.engine.memeSchema
import app.memem.engine.parseSuggestions
import app.memem.engine.searchTemplates
import app.memem.engine.searchText
import app.memem.llm.GenerateOutcome
import app.memem.llm.RemoteLlmEngine
import app.memem.models.ModelCatalog
import app.memem.render.MemeRenderer
import java.io.File

data class MemeOption(
    val template: MemeTemplate,
    val lines: List<String>,
    val bitmap: Bitmap,
    val file: File,
    val fromModel: Boolean,
)

class MemePipeline(context: Context) {
    private val app = context.applicationContext
    private val assets = MemAssets(app)
    private val renderer = MemeRenderer(app)
    private val log = DebugLog(app)
    val remote = RemoteLlmEngine(app)
    private var litert: MemIndex? = null
    private var hash: MemIndex? = null

    fun preload() {
        val gemmaFile = ModelCatalog.file(app, ModelCatalog.gemmaCpu)
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
        val started = System.nanoTime()
        val embedStarted = System.nanoTime()
        val embedded = embedQuery(searchText(message, context))
        val embedMs = ms(embedStarted)
        val searchStarted = System.nanoTime()
        val hits = searchTemplates(embedded.second, embedded.first, assets.boxes, limit = 8)
        val searchMs = ms(searchStarted)
        val picked = hits.mapNotNull { assets.templates[it.templateId] }.take(3)
            .ifEmpty { assets.templates.values.take(3).toList() }
        val literal = renderOptions(
            message,
            picked.map { it to fallbackLines(message, it.boxes, it.style) },
            fromModel = false,
        )
        onPreview(literal)
        val gemmaStarted = System.nanoTime()
        val outcome = generate(message, picked, context)
        val gemmaMs = ms(gemmaStarted)
        val renderStarted = System.nanoTime()
        val candidates = picked.map {
            Candidate(it.id, it.boxes, it.style, it.examples.firstOrNull().orEmpty(), it.name)
        }
        val suggestions = if (candidates.isEmpty()) {
            emptyList()
        } else {
            parseSuggestions(
                outcome.text.orEmpty(),
                candidates,
                message,
                wanted = 3,
                failure = outcome.error ?: "keine antwort",
            )
        }
        val rendered = suggestions.mapNotNull { suggestion ->
            val template = assets.templates[suggestion.templateId] ?: return@mapNotNull null
            renderOne(template, suggestion.lines, suggestion.fromModel)
        }.ifEmpty { literal }
        onPreview(rendered)
        val renderMs = ms(renderStarted)
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
                "raw" to outcome.text?.take(500),
                "reasons" to suggestions.joinToString(" | ") { item ->
                    val mark = if (item.fromModel) "KI" else item.reason.ifBlank { "woertlich" }
                    "${item.templateId}:$mark"
                },
                "renderMs" to renderMs,
                "totalMs" to ms(started),
                "templates" to rendered.joinToString(",") { it.template.id },
                "rewritten" to rendered.count { it.fromModel },
            ),
        )
        sweep(rendered.map { it.file.name }.toSet())
        return rendered
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

    private suspend fun generate(message: String, templates: List<MemeTemplate>, context: List<String>): GenerateOutcome {
        if (templates.isEmpty()) return GenerateOutcome(null, "keine vorlage", remote.modelReady, false)
        val model = ModelCatalog.file(app, ModelCatalog.gemmaCpu)
        if (!ModelCatalog.ready(app, ModelCatalog.gemmaCpu)) {
            return GenerateOutcome(null, "modell fehlt", false, false)
        }
        val embed = ModelCatalog.file(app, ModelCatalog.embed)
        val loadError = remote.ensureLoaded(
            model.absolutePath,
            embed.takeIf { it.isFile && assets.space.startsWith("litert") }?.absolutePath,
        )
        if (loadError != null) return GenerateOutcome(null, loadError, false, false)
        val chosen = templates.take(3)
        val briefs = chosen.map { template ->
            Brief(
                id = template.id,
                name = template.name,
                boxes = template.boxes,
                meaning = template.meaning,
                examples = template.examples.take(5),
                roles = fieldRoles(template.id, template.boxes),
            )
        }
        val prompt = buildPrompt(message, briefs, context)
        val schema = memeSchema(chosen.map { it.id })
        return remote.generate(prompt.system, prompt.user, schema)
    }

    private fun renderOne(template: MemeTemplate, lines: List<String>, fromModel: Boolean): MemeOption {
        val dir = File(app.filesDir, "memes")
        val bitmap = renderer.render(template, lines)
        val file = File(dir, "${template.id}-${System.nanoTime()}.png")
        renderer.writePng(bitmap, file)
        return MemeOption(template, lines, bitmap, file, fromModel)
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
