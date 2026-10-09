package app.memem.engine

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Candidate(
    val id: String,
    val boxes: Int,
    val style: String,
    val example: List<String> = emptyList(),
    val name: String = "",
)

data class Suggestion(
    val templateId: String,
    val lines: List<String>,
    val fromModel: Boolean,
    val reason: String = "",
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
    failure: String = "",
): List<Suggestion> {
    val byId = candidates.associateBy { it.id }
    val used = linkedSetOf<String>()
    val out = ArrayList<Suggestion>(wanted)
    val parsed = extractMemes(raw, candidates)
    for (item in parsed) {
        if (out.size >= wanted) break
        val candidate = byId[item.first] ?: continue
        if (!used.add(candidate.id)) continue
        val resolved = resolveLines(item.second, candidate, message)
        out.add(Suggestion(candidate.id, resolved.lines, resolved.fromModel, resolved.reason))
    }
    val missingReason = if (raw.isBlank()) failure.ifBlank { "keine antwort" } else "fehlt"
    for (candidate in candidates) {
        if (out.size >= wanted) break
        if (!used.add(candidate.id)) continue
        out.add(
            Suggestion(
                candidate.id,
                fallbackLines(message, candidate.boxes, candidate.style),
                fromModel = false,
                reason = missingReason,
            ),
        )
    }
    return out
}

data class ResolvedLines(val lines: List<String>, val fromModel: Boolean, val reason: String = "")

/**
 * Keeps a filled rewrite, including a paraphrase that shares no word with the message.
 * Only an empty or letterless caption falls back to the literal split.
 */
fun resolveLines(rawLines: List<String>, candidate: Candidate, message: String): ResolvedLines {
    val cleaned = rawLines.map { cleanLine(it) }
    val sized = (cleaned + List(candidate.boxes) { "" }).take(candidate.boxes)
    val repaired = repairPhraseEndings(sized)
    val chosen = when {
        repaired != null && usableRewrite(message, repaired, candidate.boxes) -> repaired
        usableRewrite(message, sized, candidate.boxes) -> sized
        else -> null
    }
    val literal = fallbackLines(message, candidate.boxes, candidate.style)
    if (chosen == null) {
        val reason = if (sized.all { it.isBlank() }) "leer" else "sinnlos"
        return ResolvedLines(literal, fromModel = false, reason = reason)
    }
    if (sameCaption(chosen, literal)) {
        return ResolvedLines(literal, fromModel = false, reason = "woertlich")
    }
    return ResolvedLines(chosen, fromModel = true, reason = "")
}

fun sameCaption(left: List<String>, right: List<String>): Boolean {
    fun norm(lines: List<String>) =
        lines.map { it.trim().replace(Regex("\\s+"), " ").lowercase() }.filter { it.isNotEmpty() }
    return norm(left) == norm(right)
}

fun fitLines(rawLines: List<String>, candidate: Candidate, message: String): List<String> =
    resolveLines(rawLines, candidate, message).lines

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

private val ID_KEYS = setOf(
    "template", "template_id", "templateid", "id", "meme", "meme_id", "vorlage", "name", "nr", "n", "key", "m",
)
private val LINE_KEYS = setOf(
    "lines", "line", "text", "texts", "caption", "captions", "zeilen", "boxes", "fields", "output", "l", "z", "t", "m",
)
private val INDEX_KEYS = setOf("nr", "n", "index", "i")

private data class FoundMeme(val id: String, val lines: List<String>, val index: Int)

private fun extractMemes(raw: String, candidates: List<Candidate>): List<Pair<String, List<String>>> {
    val fromJson = jsonBlobs(raw).firstNotNullOfOrNull { blob ->
        val value = try {
            JSONTokener(blob).nextValue()
        } catch (_: Exception) {
            null
        } ?: return@firstNotNullOfOrNull null
        val found = collectMemes(value, candidates)
        found.takeIf { it.isNotEmpty() }
    }.orEmpty()
    val assigned = assignMemes(fromJson, candidates)
    if (assigned.isNotEmpty()) return assigned
    return extractTextMemes(raw, candidates)
}

private fun jsonBlobs(raw: String): List<String> {
    val stripped = raw.replace(Regex("```(?:json)?"), "")
    val blobs = ArrayList<String>()
    var i = 0
    while (i < stripped.length) {
        val open = stripped[i]
        if (open != '{' && open != '[') {
            i += 1
            continue
        }
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escaped = false
        var end = -1
        for (j in i until stripped.length) {
            val ch = stripped[j]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> inString = true
                open -> depth += 1
                close -> {
                    depth -= 1
                    if (depth == 0) {
                        end = j
                        break
                    }
                }
            }
        }
        if (end > i) blobs += stripped.substring(i, end + 1)
        i = if (end > i) end + 1 else i + 1
    }
    return blobs
}

private fun collectMemes(value: Any?, candidates: List<Candidate>): List<FoundMeme> {
    return when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) addAll(collectMemes(value.opt(i), candidates))
        }
        is JSONObject -> {
            val nested = ArrayList<FoundMeme>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val child = value.opt(keys.next())
                if (child is JSONArray && (0 until child.length()).any { child.opt(it) is JSONObject }) {
                    nested += collectMemes(child, candidates)
                }
            }
            if (nested.isNotEmpty()) nested else listOfNotNull(memeFrom(value, candidates))
        }
        else -> emptyList()
    }
}

private fun memeFrom(obj: JSONObject, candidates: List<Candidate>): FoundMeme? {
    var id = ""
    var index = -1
    var lines = emptyList<String>()
    val keys = obj.keys().asSequence().toList()
    for (key in keys) {
        val norm = key.lowercase()
        val value = obj.opt(key)
        if (norm in INDEX_KEYS && index < 0) {
            index = when (value) {
                is Number -> value.toInt()
                is String -> value.toIntOrNull() ?: -1
                else -> -1
            }
        }
        if (norm in ID_KEYS && value is String && value.isNotBlank() && id.isBlank() && value.toIntOrNull() == null) {
            id = value.trim()
        }
        if (norm in LINE_KEYS && lines.isEmpty()) lines = readLineValue(value)
    }
    if (id.isBlank()) {
        id = keys.firstNotNullOfOrNull { key ->
            val text = obj.optString(key, "").trim()
            text.takeIf { candidates.any { candidate -> matchesCandidate(text, candidate) } }
        }.orEmpty()
    }
    if (lines.isEmpty()) {
        lines = keys.firstNotNullOfOrNull { key ->
            readLineValue(obj.opt(key)).takeIf { it.isNotEmpty() }
        }.orEmpty()
    }
    if (id.isBlank() && index < 0 && lines.isEmpty()) return null
    return FoundMeme(id, lines, index)
}

private fun readLineValue(value: Any?): List<String> {
    return when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) {
                val child = value.opt(i)
                if (child is String) add(child)
                else if (child is JSONObject) addAll(readLineValue(child.opt("text") ?: child.opt("line")))
            }
        }
        is String -> splitCaption(value)
        else -> emptyList()
    }
}

private fun splitCaption(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val parts = when {
        trimmed.contains("\n") -> trimmed.split('\n')
        trimmed.contains(" / ") -> trimmed.split(" / ")
        else -> listOf(trimmed)
    }
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

private fun assignMemes(found: List<FoundMeme>, candidates: List<Candidate>): List<Pair<String, List<String>>> {
    val used = linkedSetOf<String>()
    val out = ArrayList<Pair<String, List<String>>>()
    for (item in found) {
        val id = resolveId(item, candidates, used) ?: continue
        if (!used.add(id)) continue
        if (item.lines.isEmpty()) continue
        out += id to item.lines
    }
    if (out.isEmpty() && found.any { it.lines.isNotEmpty() }) {
        found.filter { it.lines.isNotEmpty() }.forEachIndexed { index, item ->
            val candidate = candidates.getOrNull(index) ?: return@forEachIndexed
            if (used.add(candidate.id)) out += candidate.id to item.lines
        }
    }
    return out
}

private fun resolveId(item: FoundMeme, candidates: List<Candidate>, used: Set<String>): String? {
    val named = candidates.firstOrNull { matchesCandidate(item.id, it) && it.id !in used }
    if (named != null) return named.id
    if (item.index in 1..candidates.size) {
        val candidate = candidates[item.index - 1]
        if (candidate.id !in used) return candidate.id
    }
    if (item.index in candidates.indices) {
        val candidate = candidates[item.index]
        if (candidate.id !in used) return candidate.id
    }
    return null
}

private fun matchesCandidate(token: String, candidate: Candidate): Boolean {
    val text = token.trim()
    if (text.isEmpty()) return false
    return text.equals(candidate.id, ignoreCase = true) ||
        (candidate.name.isNotBlank() && text.equals(candidate.name, ignoreCase = true))
}

private fun extractTextMemes(raw: String, candidates: List<Candidate>): List<Pair<String, List<String>>> {
    val rows = raw.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
    val out = ArrayList<Pair<String, List<String>>>()
    var index = 0
    while (index < rows.size) {
        val candidate = candidates.firstOrNull { lineNames(rows[index], it) } ?: run {
            index += 1
            continue
        }
        val collected = ArrayList<String>()
        val rest = rows[index].substringAfter(":", "").trim()
        if (rest.isNotEmpty() && candidates.none { lineNames(rest, it) }) collected += splitCaption(rest)
        index += 1
        while (index < rows.size && collected.size < candidate.boxes && candidates.none { lineNames(rows[index], it) }) {
            collected += splitCaption(rows[index])
            index += 1
        }
        if (collected.isNotEmpty()) out += candidate.id to collected
    }
    return out
}

private fun lineNames(line: String, candidate: Candidate): Boolean {
    val head = line.substringBefore(":").trim().trimStart('#', '-', '*', ' ').trim()
    return matchesCandidate(head, candidate) || matchesCandidate(line, candidate)
}
