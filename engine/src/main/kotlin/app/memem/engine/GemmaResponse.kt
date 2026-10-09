package app.memem.engine

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Candidate(
    val id: String,
    val boxes: Int,
    val style: String,
    val example: List<String> = emptyList(),
)

data class Suggestion(
    val templateId: String,
    val lines: List<String>,
    val fromModel: Boolean,
)

/**
 * Reads Gemma's JSON and always returns [wanted] suggestions.
 * Counts are checked here, not with minItems/maxItems in the schema (that combination
 * aborts the Gemma engine before the first token).
 */
fun parseSuggestions(
    raw: String,
    candidates: List<Candidate>,
    message: String,
    wanted: Int = 3,
): List<Suggestion> {
    val byId = candidates.associateBy { it.id }
    val used = linkedSetOf<String>()
    val out = ArrayList<Suggestion>(wanted)
    val parsed = extractMemes(raw)
    for (item in parsed) {
        if (out.size >= wanted) break
        val id = item.first
        val candidate = byId[id] ?: continue
        if (!used.add(id)) continue
        out.add(Suggestion(id, fitLines(item.second, candidate, message), fromModel = true))
    }
    for (candidate in candidates) {
        if (out.size >= wanted) break
        if (!used.add(candidate.id)) continue
        val lines = if (candidate.example.any { it.isNotBlank() }) {
            fitLines(candidate.example, candidate, message)
        } else {
            fallbackLines(message, candidate.boxes, candidate.style)
        }
        out.add(Suggestion(candidate.id, lines, fromModel = false))
    }
    return out
}

fun fitLines(rawLines: List<String>, candidate: Candidate, message: String): List<String> {
    val cleaned = rawLines.map { cleanLine(it) }.toMutableList()
    while (cleaned.size < candidate.boxes) cleaned.add("")
    val trimmed = cleaned.take(candidate.boxes).toMutableList()
    if (trimmed.all { it.isBlank() }) {
        return fallbackLines(message, candidate.boxes, candidate.style)
    }
    return trimmed
}

fun memeSchema(ids: List<String>): String {
    val enums = JSONArray()
    ids.forEach { enums.put(it) }
    val template = JSONObject()
        .put("type", "string")
        .put("enum", enums)
    val lines = JSONObject()
        .put("type", "array")
        .put("items", JSONObject().put("type", "string"))
    val meme = JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("template", template).put("lines", lines))
        .put("required", JSONArray().put("template").put("lines"))
    val memes = JSONObject()
        .put("type", "array")
        .put("items", meme)
    return JSONObject()
        .put("type", "object")
        .put("properties", JSONObject().put("memes", memes))
        .put("required", JSONArray().put("memes"))
        .toString()
}

fun schemaAvoidsCountConstraints(schema: String): Boolean {
    return !schema.contains("minItems") && !schema.contains("maxItems")
}

private fun extractMemes(raw: String): List<Pair<String, List<String>>> {
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    return try {
        val obj = JSONTokener(raw.substring(start, end + 1)).nextValue() as? JSONObject ?: return emptyList()
        val arr = obj.optJSONArray("memes") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optString("template", "")
                val linesJson = item.optJSONArray("lines")
                val lines = buildList {
                    if (linesJson != null) {
                        for (j in 0 until linesJson.length()) add(linesJson.optString(j, ""))
                    }
                }
                if (id.isNotBlank()) add(id to lines)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
