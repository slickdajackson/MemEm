package app.memem.engine

import java.util.Locale

fun stylize(text: String, style: String): String {
    val cleaned = text.trim().replace(Regex("\\s+"), " ")
    return when (style) {
        "upper" -> cleaned.uppercase(Locale.ROOT)
        "lower" -> cleaned.lowercase(Locale.ROOT)
        else -> cleaned
    }
}

private val CONJUNCTIONS = setOf(
    "und", "oder", "aber", "denn", "weil", "dass", "daß", "wenn", "als", "wie",
    "sowie", "doch", "sondern", "während", "bevor", "nachdem", "obwohl", "damit",
    "ob", "falls", "sobald", "bis", "beziehungsweise", "bzw", "dann",
)

private val FUNCTION_WORDS = setOf(
    "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "einer", "einem", "einen", "eines",
    "am", "an", "auf", "aus", "bei", "mit", "nach", "von", "zu", "zum", "zur", "vom",
    "im", "in", "für", "fuer", "gegen", "über", "ueber", "unter", "vor", "hinter",
    "neben", "zwischen", "durch", "pro",
    "und", "oder", "aber", "denn", "weil", "dass", "daß", "wenn", "sowie", "doch",
    "sondern", "obwohl", "damit", "ob", "falls",
    "nicht", "auch", "nur", "so", "sehr", "schon", "noch", "mal", "nun",
)

/** Split the user's own words across the boxes without reordering or interleaving them. */
fun fallbackLines(message: String, boxCount: Int, style: String): List<String> {
    val count = boxCount.coerceAtLeast(1)
    val words = messageWords(message)
    if (words.isEmpty()) return List(count) { "" }
    return splitInOrder(words, count).map { chunk ->
        capWords(stylize(chunk.joinToString(" "), style))
    }
}

/**
 * Joined lines must still contain every content word of [message] in the same order.
 * Function words may be dropped. A reordered or interleaved line fails.
 */
fun preservesMessageOrder(message: String, lines: List<String>): Boolean {
    val needed = contentWords(message).ifEmpty { normalizedWords(message) }
    if (needed.isEmpty()) return lines.all { it.isBlank() }
    val hay = normalizedWords(lines.joinToString(" "))
    var index = 0
    for (word in hay) {
        if (index < needed.size && word == needed[index]) index += 1
    }
    return index == needed.size
}

fun messageWords(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

fun contentWords(text: String): List<String> =
    normalizedWords(text).filter { it.length > 1 && it !in FUNCTION_WORDS }

fun normalizedWords(text: String): List<String> =
    messageWords(text).map { normalizeWord(it) }.filter { it.isNotEmpty() }

fun splitInOrder(words: List<String>, parts: Int): List<List<String>> {
    if (parts <= 1) return listOf(words)
    if (words.isEmpty()) return List(parts) { emptyList() }
    if (words.size == 1) return listOf(words) + List(parts - 1) { emptyList() }
    if (words.size <= parts) return listOf(listOf(words.first())) + splitInOrder(words.drop(1), parts - 1)
    val goal = (words.size / parts).coerceIn(1, words.size - 1)
    val cut = naturalCut(words, goal)
    return listOf(words.subList(0, cut)) + splitInOrder(words.subList(cut, words.size), parts - 1)
}

private fun naturalCut(words: List<String>, target: Int): Int {
    val goal = target.coerceIn(1, words.size - 1)
    val window = (words.size * 0.34).toInt().coerceAtLeast(1)
    var best = goal
    var bestScore = 0
    for (index in 1 until words.size) {
        val distance = kotlin.math.abs(index - goal)
        if (distance > window) continue
        val kind = when {
            endsWithPunct(words[index - 1]) -> 3
            isConjunction(words[index]) -> 2
            else -> 0
        }
        if (kind == 0) continue
        val score = kind * 100 - distance
        if (score > bestScore) {
            bestScore = score
            best = index
        }
    }
    return best
}

private fun endsWithPunct(word: String): Boolean {
    val mark = word.lastOrNull() ?: return false
    return mark == '.' || mark == '!' || mark == '?' || mark == ';' || mark == ':' || mark == ',' || mark == '…'
}

private fun isConjunction(word: String): Boolean = normalizeWord(word) in CONJUNCTIONS

private fun normalizeWord(raw: String): String =
    raw.lowercase(Locale.ROOT).replace("ß", "ss").replace(Regex("[^\\p{L}\\p{N}]"), "")

fun capWords(line: String, maxWords: Int = 8, maxChars: Int = 80): String {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(maxWords)
    return words.joinToString(" ").take(maxChars).trim()
}

fun cleanLine(raw: String): String {
    var line = raw.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
    line = line.replace(" - ", ", ")
    return capWords(line)
}
