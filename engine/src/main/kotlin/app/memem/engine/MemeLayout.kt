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
        val anchorX = (imageW * field.anchorX).toInt()
        val anchorY = (imageH * field.anchorY).toInt()
        val maxFont = (imageH / if (field.angle != 0f) 4 else 9).coerceAtLeast(8)
        val fitW = (boxW - boxW / 35).coerceAtLeast(1)
        val fitH = (boxH - boxH / 10).coerceAtLeast(1)
        val wrapped = chooseLines(styled, field.font, boxW, fitW, fitH, maxFont, measurer)
        val fontPx = fitFont(wrapped, field.font, fitW, fitH, maxFont, measurer)
        val stroke = strokeWidth(fontPx, field.color)
        PlacedText(
            text = wrapped,
            fontPx = fontPx,
            anchorX = anchorX,
            anchorY = anchorY,
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

/**
 * Size of the bitmap memegen gets from rotate(expand=True).
 * The paste stays at the anchor, so the box center moves by half that growth.
 */
fun expandedSize(boxW: Int, boxH: Int, angle: Float): Pair<Float, Float> {
    if (angle == 0f) return boxW.toFloat() to boxH.toFloat()
    val rad = Math.toRadians(angle.toDouble())
    val c = kotlin.math.abs(kotlin.math.cos(rad))
    val s = kotlin.math.abs(kotlin.math.sin(rad))
    return (boxW * c + boxH * s).toFloat() to (boxW * s + boxH * c).toFloat()
}

/** Center of the text box after memegen pastes the expanded rotation at the anchor. */
fun visualCenter(anchorX: Int, anchorY: Int, boxW: Int, boxH: Int, angle: Float): Pair<Float, Float> {
    val (width, height) = expandedSize(boxW, boxH, angle)
    return anchorX + width / 2f to anchorY + height / 2f
}

fun strokeWidth(fontPx: Int, color: String): Int {
    if (color.equals("black", ignoreCase = true)) return 1
    val base = (fontPx / 12).coerceIn(1, 3)
    return if (color.contains('#')) base.coerceAtLeast(1) else base
}

fun fitFont(
    text: String,
    fontKey: String,
    fitW: Int,
    fitH: Int,
    maxFont: Int,
    measurer: TextMeasurer,
): Int {
    if (text.isBlank()) return maxFont.coerceIn(7, fitH.coerceAtLeast(7))
    var low = 7
    var high = maxFont.coerceAtLeast(7)
    var best = low
    while (low <= high) {
        val mid = (low + high) / 2
        if (textFits(text, mid, fontKey, fitW, fitH, measurer)) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

fun textFits(
    text: String,
    fontPx: Int,
    fontKey: String,
    fitW: Int,
    fitH: Int,
    measurer: TextMeasurer,
): Boolean {
    val lines = text.split("\n")
    for (line in lines) {
        if (line.isEmpty()) continue
        if (measurer.measure(line, fontPx, fontKey).first > fitW) return false
    }
    return measurer.measure(text, fontPx, fontKey).second <= fitH
}

/**
 * Memegen tries one, two, and three lines and keeps the break that can use the larger face,
 * as long as the wrapped block still fills most of the box width.
 */
fun chooseLines(
    text: String,
    fontKey: String,
    boxW: Int,
    fitW: Int,
    fitH: Int,
    maxFont: Int,
    measurer: TextMeasurer,
): String {
    val clean = text.trim()
    if (clean.isEmpty() || !clean.contains(' ')) return clean
    val one = clean
    val two = splitTwo(clean)
    val three = splitThree(clean)
    val size1 = fitFont(one, fontKey, fitW, fitH, maxFont, measurer)
    val size2 = fitFont(two, fontKey, fitW, fitH, maxFont, measurer)
    val size3 = fitFont(three, fontKey, fitW, fitH, maxFont, measurer)
    if (size1 == size2 && size2 <= 7) return two
    if (size1 >= size2) return one
    val wide = (boxW * 0.60f).toInt()
    if (three != one && measurer.measure(three, size3, fontKey).first >= wide) return three
    if (two != one && measurer.measure(two, size2, fontKey).first >= wide) return two
    return one
}

fun splitTwo(line: String): String {
    if (line.length < 4) return line
    val midpoint = line.length / 2 - 1
    val span = (line.length / 4).coerceAtLeast(1)
    for (offset in 0 until span) {
        for (index in intArrayOf(midpoint - offset, midpoint + offset)) {
            if (index in line.indices && line[index] == ' ') {
                return line.substring(0, index).trim() + "\n" + line.substring(index).trim()
            }
        }
    }
    return line
}

fun splitThree(line: String): String {
    val maxLen = line.length / 3.0
    val words = line.split(" ")
    val lines = arrayOf("", "", "")
    var index = 0
    for (word in words) {
        if (word.isEmpty()) continue
        val nextLen = lines[index].length + word.length * 0.7
        if (nextLen > maxLen && index < 2) index += 1
        lines[index] = lines[index] + word + " "
    }
    return lines.joinToString("\n") { it.trim() }.trim()
}

fun fontFile(name: String): String = when (name.lowercase()) {
    "thick", "titilliumweb" -> "TitilliumWeb-Black.ttf"
    "thin", "titilliumweb-thin" -> "TitilliumWeb-SemiBold.ttf"
    "impact" -> "Anton-Regular.ttf"
    "comic", "kalam" -> "Kalam-Regular.ttf"
    else -> "NotoSans-Bold.ttf"
}
