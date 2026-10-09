package app.memem.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import app.memem.R
import app.memem.ui.MemPalette
import app.memem.ui.drawSticker

/** Floating entry. Yellow sticker with the cut-out logo. Dragging is handled by the service. */
class LogoDotView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val logo = BitmapFactory.decodeResource(resources, R.drawable.memem_logo)

    override fun onDraw(canvas: Canvas) {
        val shadow = width * 0.08f
        val face = RectF(shadow, shadow, width - shadow * 2f, height - shadow * 2f)
        drawSticker(canvas, face, MemPalette.YELLOW, face.width() / 2f, width * 0.045f, shadow, false, paint)
        if (logo != null) {
            val pad = face.width() * 0.12f
            canvas.drawBitmap(logo, null, RectF(face.left + pad, face.top + pad, face.right - pad, face.bottom - pad), paint)
        }
    }
}

/** Cream card above WhatsApp. Three previews, no send button. */
class OverlayPanelView(context: Context) : View(context) {
    var status: String = ""
        set(value) {
            field = value
            invalidate()
        }
    var previews: List<Bitmap?> = listOf(null, null, null)
        set(value) {
            field = value
            invalidate()
        }
    var onMeme: () -> Unit = {}
    var onPick: (Int) -> Unit = {}
    var onClose: () -> Unit = {}

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val meme = RectF()
    private val slots = Array(3) { RectF() }
    private val close = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).takeIf { it > 0 } ?: (300 * resources.displayMetrics.density).toInt()
        val height = (248 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val pad = 12 * d
        val shadow = 4 * d
        canvas.drawColor(MemPalette.PAPER)
        meme.set(pad, pad, pad + 92 * d, pad + 48 * d)
        drawSticker(canvas, meme, MemPalette.YELLOW, 12 * d, 2.5f * d, shadow, false, paint)
        paint.color = MemPalette.INK
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 16 * d
        canvas.drawText("Meme", meme.centerX(), meme.centerY() + 6 * d, paint)
        val slot = (width - pad * 2 - 8 * d * 2) / 3f
        val top = meme.bottom + 14 * d
        for (index in 0 until 3) {
            val left = pad + index * (slot + 8 * d)
            slots[index].set(left, top, left + slot - shadow, top + 72 * d)
            drawSticker(canvas, slots[index], MemPalette.CREAM, 12 * d, 2.5f * d, shadow, false, paint)
            val bitmap = previews.getOrNull(index)
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.drawBitmap(bitmap, null, inset(slots[index], 4 * d), paint)
            } else {
                paint.color = MemPalette.INK
                paint.typeface = Typeface.MONOSPACE
                paint.textSize = 12 * d
                canvas.drawText("0${index + 1}", slots[index].centerX(), slots[index].centerY(), paint)
            }
        }
        close.set(pad, slots[0].bottom + 12 * d, width - pad - shadow, slots[0].bottom + 48 * d)
        drawSticker(canvas, close, MemPalette.CREAM, 12 * d, 2.5f * d, shadow, false, paint)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = 14 * d
        paint.color = MemPalette.INK
        canvas.drawText("Schließen", close.centerX(), close.centerY() + 5 * d, paint)
        if (status.isNotBlank()) {
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = Typeface.MONOSPACE
            paint.textSize = 11 * d
            canvas.drawText(status, pad, close.bottom + 16 * d, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        when {
            meme.contains(x, y) -> onMeme()
            close.contains(x, y) -> onClose()
            else -> slots.forEachIndexed { index, rect -> if (rect.contains(x, y)) onPick(index) }
        }
        return true
    }

    private fun inset(rect: RectF, pad: Float) = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
}
