package app.memem.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import app.memem.data.MemeTemplate
import app.memem.engine.PlacedText
import app.memem.engine.TextMeasurer
import app.memem.engine.expandedSize
import app.memem.engine.fontFile
import app.memem.engine.placeText
import app.memem.engine.strokeWidth
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

internal data class FittedLine(
    val paint: TextPaint,
    val layout: StaticLayout,
    val textSize: Int,
    val strokePx: Int,
)

class MemeRenderer(context: Context) {
    private val app = context.applicationContext
    private val faces = HashMap<String, Typeface>()

    fun render(template: MemeTemplate, lines: List<String>): Bitmap {
        val background = BitmapFactory.decodeStream(app.assets.open("images/${template.id}.webp"))
            ?: Bitmap.createBitmap(template.width, template.height, Bitmap.Config.ARGB_8888)
        val bitmap = background.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val placed = placeText(template.fields, lines, bitmap.width, bitmap.height, measurer())
        for (item in placed) {
            if (item.text.isBlank()) continue
            val fitted = fitLineLayout(item, face(item.fontKey))
            val layer = Bitmap.createBitmap(item.boxW, item.boxH, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layer)
            val top = (item.boxH - fitted.layout.height) / 2f
            layerCanvas.save()
            layerCanvas.translate(0f, top)
            val paint = fitted.paint
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = fitted.strokePx.toFloat().coerceAtLeast(1f)
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = if (item.color.equals("black", ignoreCase = true)) 0x80FFFFFF.toInt() else Color.BLACK
            fitted.layout.draw(layerCanvas)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            paint.color = parseColor(item.color)
            fitted.layout.draw(layerCanvas)
            layerCanvas.restore()
            val (grownW, grownH) = expandedSize(item.boxW, item.boxH, item.angle)
            canvas.save()
            canvas.translate(item.anchorX + grownW / 2f, item.anchorY + grownH / 2f)
            canvas.rotate(-item.angle)
            canvas.translate(-item.boxW / 2f, -item.boxH / 2f)
            canvas.drawBitmap(layer, 0f, 0f, null)
            canvas.restore()
            layer.recycle()
        }
        if (background != bitmap) background.recycle()
        return bitmap
    }

    fun writePng(bitmap: Bitmap, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
    }

    internal fun fitLineLayout(item: PlacedText, typeface: Typeface): FittedLine {
        val alignment = when (item.align) {
            "left" -> Layout.Alignment.ALIGN_NORMAL
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        val limitW = (item.boxW - item.boxW / 35).coerceAtLeast(1)
        val limitH = (item.boxH - item.boxH / 10).coerceAtLeast(1)
        var size = item.fontPx.coerceAtLeast(7)
        var best: FittedLine? = null
        while (size >= 7) {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.typeface = typeface
                textSize = size.toFloat()
                color = parseColor(item.color)
            }
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, item.boxW)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            val stroke = strokeWidth(size, item.color)
            val width = (0 until layout.lineCount).maxOf { layout.getLineWidth(it) }.toInt() + stroke
            val height = layout.height + stroke
            val fitted = FittedLine(paint, layout, size, stroke)
            best = fitted
            if (width <= limitW && height <= limitH && layout.lineCount <= item.text.count { it == '\n' } + 1) {
                return fitted
            }
            size -= 1
        }
        return best ?: FittedLine(
            TextPaint().apply { textSize = 7f },
            StaticLayout.Builder.obtain("", 0, 0, TextPaint(), item.boxW).build(),
            7,
            1,
        )
    }

    private fun measurer() = TextMeasurer { text, fontPx, fontKey ->
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face(fontKey)
            textSize = fontPx.toFloat()
        }
        val stroke = strokeWidth(fontPx, "white")
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        var width = 1
        var height = stroke
        for (line in lines) {
            val layout = StaticLayout.Builder.obtain(line, 0, line.length, paint, 100_000)
                .setIncludePad(false)
                .setMaxLines(1)
                .build()
            width = max(width, layout.getLineWidth(0).toInt() + stroke)
            height += layout.height
        }
        width to height.coerceAtLeast(1)
    }

    private fun face(fontKey: String): Typeface {
        val file = fontFile(fontKey)
        return faces.getOrPut(file) {
            Typeface.createFromAsset(app.assets, "fonts/$file")
        }
    }

    private fun parseColor(raw: String): Int = try {
        Color.parseColor(raw)
    } catch (_: Exception) {
        Color.WHITE
    }
}
