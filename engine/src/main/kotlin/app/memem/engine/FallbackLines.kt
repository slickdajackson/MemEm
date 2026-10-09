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

/** Split the user's own words across the boxes so the message survives without Gemma. */
fun fallbackLines(message: String, boxCount: Int, style: String): List<String> {
    val count = boxCount.coerceAtLeast(1)
    val words = message.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return List(count) { "" }
    val chunks = List(count) { mutableListOf<String>() }
    words.forEachIndexed { index, word -> chunks[index % count].add(word) }
    return chunks.map { capWords(stylize(it.joinToString(" "), style)) }
}

fun capWords(line: String, maxWords: Int = 8, maxChars: Int = 80): String {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(maxWords)
    return words.joinToString(" ").take(maxChars).trim()
}

fun cleanLine(raw: String): String {
    var line = raw.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
    line = line.replace(" - ", ", ")
    return capWords(line)
}
