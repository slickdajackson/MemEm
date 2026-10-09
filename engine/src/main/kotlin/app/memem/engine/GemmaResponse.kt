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
    language: String = "",
): List<Suggestion> {
    val lang = language.ifBlank { outputLanguage(message) }
    val byId = candidates.associateBy { it.id }
    val used = linkedSetOf<String>()
    val out = ArrayList<Suggestion>(wanted)
    val parsed = extractMemes(raw, candidates)
    for (item in parsed) {
        if (out.size >= wanted) break
        val candidate = byId[item.first] ?: continue
        if (!used.add(candidate.id)) continue
        val resolved = resolveLines(item.second, candidate, message, similarity, lang)
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
 * Anything else falls back to the literal split.
 */
fun resolveLines(
    rawLines: List<String>,
    candidate: Candidate,
    message: String,
    similarity: ((String) -> Float)? = null,
    language: String = "",
): ResolvedLines {
    val cleaned = rawLines.map { preclean(it) }
    val sized = (cleaned + List(candidate.boxes) { "" }).take(candidate.boxes)
    val repaired = repairPhraseEndings(sized)
    val chosen = repaired ?: sized
    val literal = fallbackLines(message, candidate.boxes, candidate.style)
    val problem = rejectReason(message, chosen, candidate, similarity, language)
    if (problem != null) return ResolvedLines(literal, fromModel = false, reason = problem)
    if (sameCaption(chosen, literal)) return ResolvedLines(literal, fromModel = false, reason = "woertlich")
    return ResolvedLines(chosen.map { capWords(it) }, fromModel = true, reason = "")
}

/**
 * Hash cosine floor from the 0.1.8 sentences.
 * On-topic lines such as "Stau auf der A8" score about 0.67, a close paraphrase about 0.17.
 * Unrelated lines such as "Barista Kaffee" score about 0.01. 0.12 sits in the requested band.
 * A caption that still carries a content word or a known synonym is kept even below the floor,
 * because a pure paraphrase such as "Sichere Niederlage" hashes near 0.03.
 */
const val MIN_CAPTION_SIMILARITY = 0.12f

fun rejectReason(
    message: String,
    lines: List<String>,
    candidate: Candidate,
    similarity: ((String) -> Float)? = null,
    language: String = "",
): String? {
    val lang = language.ifBlank { outputLanguage(message) }
    val labels = fixedLabels(candidate)
    if (lines.size != candidate.boxes) return "sinnlos"
    if (lines.all { it.isBlank() }) return "leer"
    if (lines.any { line -> line.count { it.isLetter() } < 2 }) return "sinnlos"
    if (lines.any { line -> !hasVowel(line) }) return "sinnlos"
    if (duplicateLines(lines)) return "doppelt"
    if (copiesExample(lines, candidate, labels)) return "kopie"
    if (foreignLanguage(lines, lang, labels)) return "sprache"
    if (overlapsMessage(lines, message, labels)) return "woertlich"
    if (lines.withIndex().any { (index, line) -> truncatedLine(line, index == lines.lastIndex) }) return "abgebrochen"
    if (
        similarity != null &&
        similarity(lines.joinToString(" ")) < MIN_CAPTION_SIMILARITY &&
        !carriesCoreStatement(message, lines)
    ) {
        return "fremd"
    }
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

/** A line that is the same in every example is a fixed label, not a copied joke. */
fun fixedLabels(candidate: Candidate): Set<String> {
    val groups = candidate.knownExamples()
        .map { group -> group.map { normCaptionLine(it) }.filter { it.isNotEmpty() }.toSet() }
        .filter { it.isNotEmpty() }
    if (groups.size < 2) return emptySet()
    return groups.reduce { left, right -> left.intersect(right) }
}

private fun copiesExample(lines: List<String>, candidate: Candidate, labels: Set<String>): Boolean {
    val produced = lines.map { normCaptionLine(it) }.filter { it.isNotEmpty() }
    if (produced.isEmpty()) return false
    val groups = candidate.knownExamples()
    for (group in groups) {
        val example = group.map { normCaptionLine(it) }.filter { it.isNotEmpty() }
        if (example.isNotEmpty() && example == produced) return true
    }
    val singles = groups.flatten().map { normCaptionLine(it) }.filter { it.isNotEmpty() }.toSet()
    return produced.any { line -> line in singles && !isFixedPhrase(line) && line !in labels }
}

fun overlapsMessage(lines: List<String>, message: String, labels: Set<String> = emptySet()): Boolean {
    return lines.any { line -> lineOverlapsMessage(line, message, labels) }
}

private fun lineOverlapsMessage(line: String, message: String, labels: Set<String>): Boolean {
    if (isFixedPhrase(line)) return false
    val norm = normCaptionLine(line)
    if (norm.isEmpty() || norm in labels) return false
    val lineWords = norm.split(' ').filter { it.length >= 2 }
    if (lineWords.size < 3) return false
    val messageWords = normCaptionLine(message).split(' ').filter { it.length >= 2 }.toSet()
    if (messageWords.isEmpty()) return false
    val shared = lineWords.count { it in messageWords }
    return shared.toFloat() / lineWords.size >= 0.8f
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

/** Ids are required keys. Each box is z1, z2, ... No minItems or maxItems. */
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
            fields.put(
                key,
                JSONObject()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 60),
            )
        }
        properties.put(
            id,
            JSONObject()
                .put("type", "object")
                .put("properties", fields)
                .put("required", fieldRequired)
                .put("additionalProperties", false),
        )
    }
    return JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", required)
        .put("additionalProperties", false)
        .toString()
}

data class FollowUp(val withoutSchema: Boolean, val templateIds: List<String>)

/**
 * One follow-up at most. Broken JSON retries without a schema.
 * If at least one card already passed, the rejected cards stay literal and nothing waits.
 * If every card failed, the rejected templates go out together in one call.
 */
fun planFollowUp(suggestions: List<Suggestion>, raw: String, alreadyFollowedUp: Boolean): FollowUp? {
    if (alreadyFollowedUp || suggestions.isEmpty()) return null
    if (suggestions.any { it.fromModel }) return null
    val missing = suggestions.filter { it.reason == "fehlt" }
    val broken = raw.isNotBlank() && (jsonUnusable(raw) || missing.isNotEmpty())
    if (broken) {
        val ids = if (missing.isNotEmpty()) missing.map { it.templateId } else suggestions.map { it.templateId }
        return FollowUp(withoutSchema = true, templateIds = ids)
    }
    val rejected = suggestions.filter { !it.fromModel }
    if (rejected.isEmpty()) return null
    return FollowUp(withoutSchema = false, templateIds = rejected.map { it.templateId })
}

fun jsonUnusable(raw: String): Boolean {
    if (raw.isBlank()) return false
    val blobs = jsonBlobs(repairModelJson(raw)).ifEmpty { jsonBlobs(raw) }
    if (blobs.isEmpty()) return raw.contains('{') || raw.contains('[')
    return blobs.none { blob ->
        try {
            JSONTokener(blob).nextValue()
            true
        } catch (_: Exception) {
            false
        }
    }
}

fun repairModelJson(raw: String): String {
    if (jsonValueParses(raw)) return raw
    var text = raw
        .replace(",\"}}", "}")
        .replace("\"}}", "\"}")
        .replace("}}}", "\"}")
    if (!jsonValueParses(text)) text = balanceJson(text)
    return text
}

private fun jsonValueParses(raw: String): Boolean {
    if (raw.isBlank()) return false
    return try {
        JSONTokener(raw).nextValue()
        true
    } catch (_: Exception) {
        false
    }
}

/** Closes a string that runs into trailing braces, then balances the object. */
private fun balanceJson(raw: String): String {
    val out = StringBuilder()
    var depth = 0
    var inString = false
    var escaped = false
    var index = 0
    while (index < raw.length) {
        val ch = raw[index]
        if (inString) {
            if (escaped) {
                out.append(ch)
                escaped = false
                index += 1
                continue
            }
            if (ch == '\\') {
                out.append(ch)
                escaped = true
                index += 1
                continue
            }
            if (ch == '"') {
                out.append(ch)
                inString = false
                index += 1
                continue
            }
            val rest = raw.substring(index)
            if ((ch == '}' || ch == ']') && rest.all { it == '}' || it == ']' || it.isWhitespace() }) {
                out.append('"')
                inString = false
                continue
            }
            out.append(ch)
            index += 1
            continue
        }
        when (ch) {
            '"' -> {
                inString = true
                out.append(ch)
            }
            '{' -> {
                depth += 1
                out.append(ch)
            }
            '}' -> {
                if (depth > 0) {
                    depth -= 1
                    out.append(ch)
                }
            }
            else -> out.append(ch)
        }
        index += 1
    }
    if (inString) out.append('"')
    repeat(depth.coerceAtMost(4)) { out.append('}') }
    return out.toString()
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
    val direct = readMemes(raw, candidates)
    if (direct.isNotEmpty()) return direct
    val repaired = repairModelJson(raw)
    if (repaired == raw) return emptyList()
    return readMemes(repaired, candidates)
}

private fun readMemes(raw: String, candidates: List<Candidate>): List<Pair<String, List<String>>> {
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
    "sell", "buy", "boat", "epipens", "is",
    "exam", "passed", "sweat", "terrible", "weather", "stuck", "spree", "shopping", "vanished",
    "again", "monday", "thumbs", "hundred", "twenty",
)

private val FR_MARKERS = setOf(
    "les", "des", "une", "est", "pas", "dans", "pour", "avec", "vous", "nous", "mais", "cette",
    "comme", "sur", "qui", "dans", "elle", "ils", "aux", "chez",
)
private val ES_MARKERS = setOf(
    "los", "las", "una", "por", "para", "con", "esta", "como", "pero", "muy", "hola", "porque",
    "está", "esta", "también", "tambien",
)
private val IT_MARKERS = setOf(
    "che", "non", "per", "una", "con", "sono", "come", "questo", "della", "anche", "perché",
)
private val NL_MARKERS = setOf(
    "het", "een", "van", "niet", "voor", "zijn", "ook", "maar", "naar", "deze",
)
private val PL_MARKERS = setOf(
    "nie", "się", "sie", "jest", "jak", "ale", "dla", "czy", "tego", "już", "juz",
)
private val PT_MARKERS = setOf(
    "não", "nao", "uma", "para", "com", "está", "esta", "como", "mais", "você", "voce",
)
private val TR_MARKERS = setOf(
    "bir", "için", "icin", "bu", "değil", "degil", "çok", "cok", "ama", "ile", "ben",
)

private val MARKERS = mapOf(
    "de" to DE_MARKERS,
    "en" to EN_MARKERS,
    "fr" to FR_MARKERS,
    "es" to ES_MARKERS,
    "it" to IT_MARKERS,
    "nl" to NL_MARKERS,
    "pl" to PL_MARKERS,
    "pt" to PT_MARKERS,
    "tr" to TR_MARKERS,
)

fun outputLanguage(message: String, context: List<String> = emptyList()): String {
    val fromMessage = dominantLang(message)
    if (fromMessage != "?") return fromMessage
    return dominantLang(context.joinToString(" "))
}

fun languageName(code: String): String = when (code) {
    "de" -> "German"
    "en" -> "English"
    "fr" -> "French"
    "es" -> "Spanish"
    "it" -> "Italian"
    "nl" -> "Dutch"
    "pl" -> "Polish"
    "pt" -> "Portuguese"
    "tr" -> "Turkish"
    else -> "the same language as the message"
}

fun dominantLang(text: String): String {
    if (text.isBlank()) return "?"
    val scores = HashMap<String, Int>()
    if (text.any { it in "äöüÄÖÜß" }) scores["de"] = 3
    val words = normCaptionLine(text).split(' ').filter { it.length >= 2 }
    for (word in words) {
        for ((lang, markers) in MARKERS) {
            if (word in markers) scores[lang] = (scores[lang] ?: 0) + 1
        }
        if (word.length >= 5 && GERMAN_SUFFIXES.any { word.endsWith(it) }) {
            scores["de"] = (scores["de"] ?: 0) + 1
        }
    }
    if (scores.isEmpty()) return "?"
    val best = scores.maxBy { it.value }
    val tied = scores.filter { it.value == best.value }.keys
    if (tied.size != 1 || best.value <= 0) return "?"
    return best.key
}

private val GERMAN_SUFFIXES = listOf("ung", "chen", "lich", "heit", "keit")

fun hasLanguageSignal(text: String, language: String): Boolean {
    if (language == "de" && text.any { it in "äöüÄÖÜß" }) return true
    val markers = MARKERS[language] ?: return false
    val words = normCaptionLine(text).split(' ').filter { it.length >= 2 }
    if (words.any { it in markers }) return true
    if (language == "de") {
        return words.any { word -> word.length >= 5 && GERMAN_SUFFIXES.any { word.endsWith(it) } }
    }
    return false
}

private fun foreignLanguage(lines: List<String>, language: String, labels: Set<String>): Boolean {
    if (language == "?") return false
    val rest = lines.filter { line ->
        val norm = normCaptionLine(line)
        norm.isNotEmpty() && !isFixedPhrase(line) && norm !in labels
    }
    val text = rest.joinToString(" ")
    if (text.count { it.isLetter() } < 2) return false
    val captionLang = dominantLang(text)
    if (captionLang == language) return false
    if (captionLang != "?") return true
    return !hasLanguageSignal(text, language)
}
