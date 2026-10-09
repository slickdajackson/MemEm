package app.memem.data

import android.content.Context
import app.memem.engine.FieldSpec
import app.memem.engine.IndexPoint
import app.memem.engine.Kind
import app.memem.engine.MemIndex
import app.memem.engine.halfToFloat
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MemeTemplate(
    val id: String,
    val name: String,
    val boxes: Int,
    val width: Int,
    val height: Int,
    val fields: List<FieldSpec>,
    val meaningDe: String,
    val meaningEn: String,
    val examples: List<List<String>>,
    val situationsDe: List<String>,
    val defaultLines: List<String>,
) {
    val style: String get() = fields.firstOrNull()?.style ?: "upper"
    val meaning: String get() = meaningDe.ifBlank { meaningEn }
}

class MemAssets(context: Context) {
    private val app = context.applicationContext
    val templates: Map<String, MemeTemplate>
    val boxes: Map<String, Int>
    private val meta: JSONObject
    private val idf: Map<String, Float>
    private val idfDocs: Int
    private val points: List<IndexPoint>

    init {
        val catalog = JSONObject(app.assets.open("catalog.json").bufferedReader().readText())
        val captionExamples = loadCaptionExamples()
        val list = catalog.getJSONArray("templates")
        val loaded = LinkedHashMap<String, MemeTemplate>(list.length())
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            val fieldsJson = obj.getJSONArray("fields")
            val fields = buildList {
                for (f in 0 until fieldsJson.length()) {
                    val field = fieldsJson.getJSONObject(f)
                    add(
                        FieldSpec(
                            style = field.optString("style", "upper"),
                            color = field.optString("color", "white"),
                            font = field.optString("font", "thick"),
                            anchorX = field.optDouble("anchorX", 0.0).toFloat(),
                            anchorY = field.optDouble("anchorY", 0.0).toFloat(),
                            scaleX = field.optDouble("scaleX", 1.0).toFloat(),
                            scaleY = field.optDouble("scaleY", 0.2).toFloat(),
                            angle = field.optDouble("angle", 0.0).toFloat(),
                            align = field.optString("align", "center"),
                        ),
                    )
                }
            }
            val template = MemeTemplate(
                id = obj.getString("id"),
                name = obj.optString("name"),
                boxes = obj.optInt("boxes", fields.size),
                width = obj.optInt("width", 720),
                height = obj.optInt("height", 720),
                fields = fields,
                meaningDe = obj.optString("meaningDe"),
                meaningEn = obj.optString("meaningEn"),
                examples = captionExamples[obj.getString("id")] ?: readLines(obj.optJSONArray("examples")),
                situationsDe = readStrings(obj.optJSONArray("situationsDe")),
                defaultLines = readStrings(obj.optJSONArray("defaultLines")),
            )
            loaded[template.id] = template
        }
        templates = loaded
        boxes = loaded.mapValues { it.value.boxes }
        meta = JSONObject(app.assets.open("index/points.json").bufferedReader().readText())
        val idfJson = JSONObject(app.assets.open("index/idf.json").bufferedReader().readText())
        idfDocs = idfJson.optInt("n", 1)
        val table = idfJson.getJSONObject("idf")
        val map = HashMap<String, Float>(table.length())
        val keys = table.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = table.getDouble(key).toFloat()
        }
        idf = map
        val arr = meta.getJSONArray("points")
        points = buildList {
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                add(
                    IndexPoint(
                        templateId = p.getString("id"),
                        kind = when (p.getString("kind")) {
                            "image" -> Kind.IMAGE
                            "image_text" -> Kind.IMAGE_TEXT
                            else -> Kind.TEXT
                        },
                        lines = readStrings(p.optJSONArray("lines")),
                    ),
                )
            }
        }
    }

    val space: String get() = meta.optString("space", "hash-v1")
    val dim: Int get() = meta.optInt("dim", 768)
    val queryPrefix: String get() = meta.optString("queryPrefix", "task: search query | text: ")

    fun hashIndex(): MemIndex = MemIndex(dim, "hash-v1", "", points, readVectors(hashName()))

    fun litertIndex(): MemIndex = MemIndex(dim, space, queryPrefix, points, readVectors("vectors.f16"))

    fun idf(): Map<String, Float> = idf
    fun idfDocs(): Int = idfDocs

    private fun hashName(): String {
        val named = meta.optString("hashFile")
        return if (named.isNotBlank()) named else "vectors.f16"
    }

    private fun readVectors(name: String): FloatArray {
        val bytes = app.assets.open("index/$name").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val count = bytes.size / 2
        val out = FloatArray(count)
        for (i in 0 until count) out[i] = halfToFloat(buffer.short.toInt())
        return out
    }

    private fun readStrings(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) add(array.optString(i))
        }
    }

    private fun loadCaptionExamples(): Map<String, List<List<String>>> {
        return try {
            val root = JSONObject(app.assets.open("caption-examples.json").bufferedReader().readText())
            buildMap {
                val keys = root.keys()
                while (keys.hasNext()) {
                    val id = keys.next()
                    val groups = root.optJSONArray(id) ?: continue
                    val lines = readLines(groups).filter { group -> group.any { it.isNotBlank() } }.take(5)
                    if (lines.isNotEmpty()) put(id, lines)
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun readLines(array: org.json.JSONArray?): List<List<String>> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) add(readStrings(array.optJSONArray(i)))
        }
    }
}
