package app.memem.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

object MemPalette {
    const val PAPER = 0xFFF3EDE0.toInt()
    const val CREAM = 0xFFFBF6EA.toInt()
    const val INK = 0xFF111111.toInt()
    const val YELLOW = 0xFFFFCC00.toInt()
    const val BLUE = 0xFF1E88FF.toInt()
    const val PURPLE = 0xFF8B5CF6.toInt()
    const val HINT = 0xFF6B6256.toInt()
}

fun drawSticker(
    canvas: Canvas,
    face: RectF,
    fill: Int,
    radius: Float,
    border: Float,
    shadow: Float,
    pressed: Boolean,
    paint: Paint,
) {
    val body = if (pressed) {
        RectF(face.left + shadow, face.top + shadow, face.right + shadow, face.bottom + shadow)
    } else {
        face
    }
    paint.style = Paint.Style.FILL
    if (!pressed) {
        paint.color = MemPalette.INK
        canvas.drawRoundRect(
            RectF(face.left + shadow, face.top + shadow, face.right + shadow, face.bottom + shadow),
            radius,
            radius,
            paint,
        )
    }
    paint.color = fill
    canvas.drawRoundRect(body, radius, radius, paint)
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = border
    paint.color = MemPalette.INK
    canvas.drawRoundRect(body, radius, radius, paint)
    paint.style = Paint.Style.FILL
}
