package app.memem.engine

import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class Candidate(
    val id: String,
    val boxes: Int,
    val style: String,
    val example: List<String> = emptyList(),
    val name: String = "",
    val examples: List<List<String>> = emptyList(),
) {
    fun knownExamples(): List<List<String>> {
        if (examples.isNotEmpty()) return examples
        return if (example.any { it.isNotBlank() }) listOf(example) else emptyList()
    }
}

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
    similarity: ((String) -> Float)? = null,
): List<Suggestion> {
    val byId = candidates.associateBy { it.id }
    val used = linkedSetOf<String>()
    val out = ArrayList<Suggestion>(wanted)
    val parsed = extractMemes(raw, candidates)
    for (item in parsed) {
        if (out.size >= wanted) break
        val candidate = byId[item.first] ?: continue
        if (!used.add(candidate.id)) continue
        val resolved = resolveLines(item.second, candidate, message, similarity)
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
 * Keeps a rewrite that is filled, not a copy, not a duplicate, and in the message language.
 * Anything else falls back to the literal split. The caller retries that one template once.
 */
fun resolveLines(
    rawLines: List<String>,
    candidate: Candidate,
    message: String,
    similarity: ((String) -> Float)? = null,
): ResolvedLines {
    val cleaned = rawLines.map { preclean(it) }
    val sized = (cleaned + List(candidate.boxes) { "" }).take(candidate.boxes)
    val repaired = repairPhraseEndings(sized)
    val chosen = repaired ?: sized
    val literal = fallbackLines(message, candidate.boxes, candidate.style)
    val problem = rejectReason(message, chosen, candidate, similarity)
    if (problem != null) return ResolvedLines(literal, fromModel = false, reason = problem)
    if (sameCaption(chosen, literal)) return ResolvedLines(literal, fromModel = false, reason = "woertlich")
    return ResolvedLines(chosen.map { capWords(it) }, fromModel = true, reason = "")
}

/** Below zero the hashed caption points away from the message. Paraphrases stay. */
const val MIN_CAPTION_SIMILARITY = 0.0f

fun rejectReason(
    message: String,
    lines: List<String>,
    candidate: Candidate,
    similarity: ((String) -> Float)? = null,
): String? {
    if (lines.size != candidate.boxes) return "sinnlos"
    if (lines.all { it.isBlank() }) return "leer"
    if (lines.any { line -> line.count { it.isLetter() } < 2 }) return "sinnlos"
    if (lines.any { line -> !hasVowel(line) }) return "sinnlos"
    if (duplicateLines(lines)) return "doppelt"
    if (copiesExample(lines, candidate)) return "kopie"
    if (foreignLanguage(message, lines)) return "sprache"
    if (lines.withIndex().any { (index, line) -> truncatedLine(line, index == lines.lastIndex) }) return "abgebrochen"
    if (similarity != null && similarity(lines.joinToString(" ")) < MIN_CAPTION_SIMILARITY) return "fremd"
    return null
}

fun shouldRetry(suggestion: Suggestion): Boolean = !suggestion.fromModel

private fun preclean(raw: String): String {
    var line = raw.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
    line = line.replace(" - ", ", ")
    return line.trim()
}

private fun hasVowel(line: String): Boolean = line.any { it.lowercaseChar() in "aeiouäöüy" }

private fun duplicateLines(lines: List<String>): Boolean {
    val norms = lines.map { normCaptionLine(it) }.filter { it.isNotEmpty() }
    return norms.size >= 2 && norms.toSet().size != norms.size
}

private fun copiesExample(lines: List<String>, candidate: Candidate): Boolean {
    val produced = lines.map { normCaptionLine(it) }.filter { it.isNotEmpty() }
    if (produced.isEmpty()) return false
    val groups = candidate.knownExamples()
    for (group in groups) {
        val example = group.map { normCaptionLine(it) }.filter { it.isNotEmpty() }
        if (example.isNotEmpty() && example == produced) return true
    }
    val singles = groups.flatten().map { normCaptionLine(it) }.filter { it.isNotEmpty() }.toSet()
    return produced.any { line -> line in singles && !isFixedPhrase(line) }
}

private fun truncatedLine(line: String, isFinal: Boolean): Boolean {
    val trimmed = line.trim()
    if (isFixedPhrase(trimmed)) return false
    if (trimmed.endsWith("...") || trimmed.endsWith("…") || trimmed.endsWith("-")) return true
    val words = messageWords(trimmed)
    if (words.size > 8) return true
    if (words.joinToString(" ").length > 80) return true
    return phraseEndingBroken(trimmed, isFinal)
}

fun sameCaption(left: List<String>, right: List<String>): Boolean {
    fun norm(lines: List<String>) =
        lines.map { it.trim().replace(Regex("\\s+"), " ").lowercase() }.filter { it.isNotEmpty() }
    return norm(left) == norm(right)
}

fun fitLines(rawLines: List<String>, candidate: Candidate, message: String): List<String> =
    resolveLines(rawLines, candidate, message).lines

/** Ids are required keys. Each box is z1, z2, ... with minLength 1. No minItems or maxItems. */
fun memeSchema(templates: List<Pair<String, Int>>): String {
    val properties = JSONObject()
    val required = JSONArray()
    templates.forEach { (id, boxes) ->
        required.put(id)
        val fields = JSONObject()
        val fieldRequired = JSONArray()
        val count = boxes.coerceIn(1, 8)
        for (number in 1..count) {
            val key = "z$number"
            fieldRequired.put(key)
            fields.put(key, JSONObject().put("type", "string").put("minLength", 1))
        }
        properties.put(
            id,
            JSONObject()
                .put("type", "object")
                .put("properties", fields)
                .put("required", fieldRequired),
        )
    }
    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", required)
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
            val keyed = keyedMemes(value, candidates)
            if (keyed.isNotEmpty()) return keyed
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

private fun keyedMemes(obj: JSONObject, candidates: List<Candidate>): List<FoundMeme> {
    val out = ArrayList<FoundMeme>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val candidate = candidates.firstOrNull { matchesCandidate(key, it) } ?: continue
        val lines = readLineValue(obj.opt(key))
        if (lines.isNotEmpty()) out += FoundMeme(candidate.id, lines, -1)
    }
    return out
}

private fun readLineValue(value: Any?): List<String> {
    return when (value) {
        is JSONArray -> buildList {
            for (i in 0 until value.length()) {
                val child = value.opt(i)
                if (child is String) {
                    val parts = splitCaption(child)
                    if (parts.isEmpty()) add("") else addAll(parts)
                } else {
                    addAll(readLineValue(child))
                }
            }
        }
        is JSONObject -> {
            val zed = readZFields(value)
            if (zed.isNotEmpty()) {
                zed
            } else {
                val keys = value.keys().asSequence().toList()
                keys.firstNotNullOfOrNull { key ->
                    if (key.lowercase() in LINE_KEYS) readLineValue(value.opt(key)).takeIf { it.isNotEmpty() } else null
                }.orEmpty()
            }
        }
        is String -> splitCaption(value)
        else -> emptyList()
    }
}

private fun readZFields(obj: JSONObject): List<String> {
    val out = ArrayList<String>()
    val keys = obj.keys().asSequence().toList()
    for (number in 1..8) {
        val key = keys.firstOrNull { it.equals("z$number", ignoreCase = true) } ?: continue
        out += readLineValue(obj.opt(key))
    }
    return out
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

fun normCaptionLine(raw: String): String {
    val lowered = raw.lowercase(Locale.GERMAN).replace("ß", "ss")
    val sb = StringBuilder(lowered.length)
    for (ch in lowered) {
        if (ch.isLetterOrDigit() || ch == '\'') sb.append(ch) else sb.append(' ')
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}

private val FIXED_PHRASES = setOf(
    "it's a trap",
    "its a trap",
    "this is fine",
    "alles gut",
    "one does not simply",
    "shut up and take my money",
    "all your base are belong to us",
    "why not both",
    "you sit on a throne of lies",
    "i immediately regret this decision",
    "you're gonna have a bad time",
    "youre gonna have a bad time",
    "it's happening",
    "its happening",
    "feels good",
    "stonks",
    "do it live",
    "i feel like i'm taking crazy pills",
    "i feel like im taking crazy pills",
    "what's in the box",
    "whats in the box",
    "our memes",
    "worst thing ever",
    "baby you've got a stew going",
    "first try",
    "yo dawg",
    "y u no",
    "ain't nobody got time for that",
    "aint nobody got time for that",
    "too damn high",
    "this is sparta",
    "winter is coming",
    "what year is it",
    "we don't do that here",
    "we dont do that here",
    "at least you tried",
    "i should not have said that",
    "you were the chosen one",
    "but that's none of my business",
    "that would be great",
    "i guarantee it",
    "so i got that goin' for me which is nice",
    "i was told there would be cake",
    "probably not a good idea",
).map { normCaptionLine(it) }.toSet()

fun isFixedPhrase(line: String): Boolean {
    val norm = normCaptionLine(line)
    if (norm.isEmpty()) return false
    if (norm in FIXED_PHRASES) return true
    return FIXED_PHRASES.any { phrase -> norm.startsWith("$phrase ") }
}

private val DE_MARKERS = setOf(
    "der", "die", "das", "den", "dem", "des", "und", "nicht", "ich", "ein", "eine", "einer",
    "ist", "sind", "mit", "auf", "fuer", "für", "wir", "du", "am", "im", "zum", "zur", "vom",
    "beim", "ins", "zu", "aber", "oder", "wenn", "dass", "mein", "dein", "sie", "von", "nach",
    "bei", "aus", "wie", "auch", "nur", "noch", "schon", "heute", "morgen", "kein", "keine",
    "doch", "mal", "jetzt", "hier", "dort", "dann", "hat", "haben", "wird", "uns", "euch",
    "ihr", "man", "was", "wer", "wo", "bin", "bist", "seid", "nicht", "mir", "dir", "ihm",
)

private val EN_MARKERS = setOf(
    "the", "and", "you", "this", "that", "with", "for", "not", "what", "have", "just", "your",
    "are", "was", "were", "from", "they", "them", "about", "would", "could", "should", "because",
    "when", "where", "who", "how", "all", "any", "more", "dont", "its", "but", "got", "get",
    "make", "take", "want", "need", "right", "left", "people", "time", "into", "over", "than",
    "then", "there", "been", "shall", "can", "why", "yes", "yeah", "gonna", "wanna",
    "sell", "buy", "boat", "should", "epipens",
)

fun dominantLang(text: String): String {
    if (text.any { it in "äöüÄÖÜß" }) return "de"
    val words = normCaptionLine(text).split(' ').filter { it.length >= 2 }
    if (words.isEmpty()) return "?"
    val german = words.count { it in DE_MARKERS }
    val english = words.count { it in EN_MARKERS }
    if (german > english && german > 0) return "de"
    if (english > german && english > 0) return "en"
    return "?"
}

private fun foreignLanguage(message: String, lines: List<String>): Boolean {
    val messageLang = dominantLang(message)
    if (messageLang == "?") return false
    val rest = lines.filter { !isFixedPhrase(it) }.joinToString(" ")
    if (rest.count { it.isLetter() } < 2) return false
    val captionLang = dominantLang(rest)
    if (captionLang == "?") return false
    return captionLang != messageLang
}
