package app.memem.engine

data class FieldSpec(
    val style: String = "upper",
    val color: String = "white",
    val font: String = "thick",
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 0.2f,
    val angle: Float = 0f,
    val align: String = "center",
)

data class PlacedText(
    val text: String,
    val fontPx: Int,
    val anchorX: Int,
    val anchorY: Int,
    val boxW: Int,
    val boxH: Int,
    val angle: Float,
    val align: String,
    val color: String,
    val fontKey: String,
    val strokePx: Int,
)

fun interface TextMeasurer {
    /** Width and height of [text], which may contain newlines. */
    fun measure(text: String, fontPx: Int, fontKey: String): Pair<Int, Int>
}

fun placeText(
    fields: List<FieldSpec>,
    lines: List<String>,
    imageW: Int,
    imageH: Int,
    measurer: TextMeasurer,
): List<PlacedText> {
    return fields.mapIndexed { index, field ->
        val raw = lines.getOrElse(index) { "" }
        val styled = stylize(raw, field.style)
        val boxW = (imageW * field.scaleX).toInt().coerceAtLeast(1)
        val boxH = (imageH * field.scaleY).toInt().coerceAtLeast(1)
        val maxFont = (imageH / if (field.angle != 0f) 4 else 9).coerceAtLeast(8)
        val fontPx = fitFont(styled, field.font, boxW, boxH, maxFont, measurer)
        val wrapped = wrapWords(styled, fontPx, field.font, boxW, measurer)
        val stroke = strokeWidth(fontPx, field.color)
        PlacedText(
            text = wrapped,
            fontPx = fontPx,
            anchorX = (imageW * field.anchorX).toInt(),
            anchorY = (imageH * field.anchorY).toInt(),
            boxW = boxW,
            boxH = boxH,
            angle = field.angle,
            align = field.align,
            color = field.color,
            fontKey = field.font,
            strokePx = stroke,
        )
    }
}

fun strokeWidth(fontPx: Int, color: String): Int {
    if (color.equals("black", ignoreCase = true)) return 1
    val base = (fontPx / 12).coerceIn(1, 3)
    return if (color.contains('#')) base.coerceAtLeast(1) else base
}

fun fitFont(
    text: String,
    fontKey: String,
    boxW: Int,
    boxH: Int,
    maxFont: Int,
    measurer: TextMeasurer,
): Int {
    if (text.isBlank()) return maxFont.coerceAtMost(boxH)
    var low = 7
    var high = maxFont.coerceAtLeast(7)
    var best = low
    while (low <= high) {
        val mid = (low + high) / 2
        val wrapped = wrapWords(text, mid, fontKey, boxW, measurer)
        val (w, h) = measurer.measure(wrapped, mid, fontKey)
        if (w <= boxW && h <= boxH) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

fun wrapWords(text: String, fontPx: Int, fontKey: String, maxWidth: Int, measurer: TextMeasurer): String {
    val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return ""
    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val trial = if (current.isEmpty()) word else "$current $word"
        val width = measurer.measure(trial, fontPx, fontKey).first
        if (width <= maxWidth || current.isEmpty()) {
            current = trial
        } else {
            lines.add(current)
            current = word
        }
    }
    if (current.isNotEmpty()) lines.add(current)
    return lines.joinToString("\n")
}

fun fontFile(name: String): String = when (name.lowercase()) {
    "thick", "titilliumweb" -> "TitilliumWeb-Black.ttf"
    "impact" -> "Anton-Regular.ttf"
    "comic", "kalam" -> "Kalam-Regular.ttf"
    else -> "NotoSans-Bold.ttf"
}
