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
import app.memem.engine.memeSchema
import app.memem.engine.parseSuggestions
import app.memem.engine.searchTemplates
import app.memem.engine.searchText
import app.memem.llm.RemoteLlmEngine
import app.memem.models.ModelCatalog
import app.memem.render.MemeRenderer
import app.memem.settings.Prefs
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
    private val prefs = Prefs(app)
    val remote = RemoteLlmEngine(app)
    private var litert: MemIndex? = null
    private var hash: MemIndex? = null

    fun preload() {
        val gemma = if (prefs.gpu) ModelCatalog.gemmaGpu else ModelCatalog.gemmaCpu
        val gemmaFile = ModelCatalog.file(app, gemma)
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
                        gpu = prefs.gpu,
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
        val chosen = hits.mapNotNull { assets.templates[it.templateId] }.take(8)
        val previewTemplates = chosen.take(3).ifEmpty { assets.templates.values.take(3).toList() }
        val previews = renderOptions(message, previewTemplates.map { it to fallbackLines(message, it.boxes, it.style) }, fromModel = false)
        onPreview(previews)
        val gemmaStarted = System.nanoTime()
        val modelText = generate(message, chosen, context)
        val gemmaMs = ms(gemmaStarted)
        val renderStarted = System.nanoTime()
        val candidates = chosen.map {
            Candidate(it.id, it.boxes, it.style, it.examples.firstOrNull().orEmpty())
        }
        val suggestions = if (candidates.isEmpty()) {
            emptyList()
        } else {
            parseSuggestions(modelText ?: "", candidates, message, wanted = 3)
        }
        val finalists = suggestions.mapNotNull { suggestion ->
            val template = assets.templates[suggestion.templateId] ?: return@mapNotNull null
            template to suggestion.lines
        }
        val rendered = if (finalists.size >= 3) {
            renderOptions(message, finalists.take(3), fromModel = modelText != null)
        } else {
            previews
        }
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
                "gemma" to (modelText != null),
                "renderMs" to renderMs,
                "totalMs" to ms(started),
                "templates" to rendered.joinToString(",") { it.template.id },
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

    private suspend fun generate(message: String, templates: List<MemeTemplate>, context: List<String>): String? {
        if (templates.isEmpty()) return null
        val gemma = if (prefs.gpu) ModelCatalog.gemmaGpu else ModelCatalog.gemmaCpu
        if (!ModelCatalog.ready(app, gemma)) return null
        val briefs = templates.map { template ->
            Brief(
                id = template.id,
                name = template.name,
                boxes = template.boxes,
                meaning = template.meaning,
                examples = (template.examples.take(2) + listOf(template.situationsDe.take(1))).filter { it.isNotEmpty() },
            )
        }
        val prompt = buildPrompt(message, briefs, context)
        val schema = memeSchema(templates.map { it.id })
        return remote.generate(prompt.system, prompt.user, schema)
    }

    private fun renderOptions(
        message: String,
        rows: List<Pair<MemeTemplate, List<String>>>,
        fromModel: Boolean,
    ): List<MemeOption> {
        val dir = File(app.filesDir, "memes")
        return rows.map { (template, lines) ->
            val bitmap = renderer.render(template, lines)
            val file = File(dir, "${template.id}-${System.nanoTime()}.png")
            renderer.writePng(bitmap, file)
            MemeOption(template, lines, bitmap, file, fromModel)
        }
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
