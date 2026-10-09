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
import app.memem.engine.TextMeasurer
import app.memem.engine.fontFile
import app.memem.engine.placeText
import java.io.File
import java.io.FileOutputStream

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
            val layer = Bitmap.createBitmap(item.boxW, item.boxH, Bitmap.Config.ARGB_8888)
            val layerCanvas = Canvas(layer)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = face(item.fontKey)
                textSize = item.fontPx.toFloat()
                color = parseColor(item.color)
            }
            val alignment = when (item.align) {
                "left" -> Layout.Alignment.ALIGN_NORMAL
                "right" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_CENTER
            }
            val layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, paint, item.boxW)
                .setAlignment(alignment)
                .setIncludePad(false)
                .build()
            val top = ((item.boxH - layout.height) / 2f).coerceAtLeast(0f)
            layerCanvas.save()
            layerCanvas.translate(0f, top)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = item.strokePx.toFloat().coerceAtLeast(1f)
            paint.color = if (item.color.equals("black", ignoreCase = true)) Color.WHITE else Color.BLACK
            layout.draw(layerCanvas)
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            paint.color = parseColor(item.color)
            layout.draw(layerCanvas)
            layerCanvas.restore()
            canvas.save()
            canvas.translate(item.anchorX.toFloat(), item.anchorY.toFloat())
            if (item.angle != 0f) canvas.rotate(-item.angle, item.boxW / 2f, item.boxH / 2f)
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

    private fun measurer() = TextMeasurer { text, fontPx, fontKey ->
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = face(fontKey)
            textSize = fontPx.toFloat()
        }
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        val width = lines.maxOf { paint.measureText(it) }.toInt().coerceAtLeast(1)
        val height = (lines.size * paint.fontSpacing).toInt().coerceAtLeast(1)
        width to height
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
