package app.memem.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import app.memem.R
import app.memem.engine.Board
import app.memem.engine.KeyDef
import app.memem.engine.KeyFace
import app.memem.engine.KeyRole
import app.memem.engine.Script
import app.memem.engine.ShiftState
import app.memem.engine.afterLetter
import app.memem.engine.keyboardRows
import app.memem.engine.shiftTap
import app.memem.engine.shownLong
import app.memem.engine.shownText
import app.memem.engine.spaceLabel
import app.memem.ui.MemPalette
import app.memem.ui.drawSticker
import kotlin.math.min

/**
 * Gboard-like QWERTY (optional QWERTZ). Meme strip sits above the keys.
 * Shift and backspace are yellow. ?123 and enter are pills. Digits sit on the top row.
 */
class KeyboardPanel(context: Context, private val host: Host) : View(context) {
    interface Host {
        fun commit(text: String)
        fun delete()
        fun enter()
        fun switchIme()
        fun showImePicker()
        fun meme()
        fun pick(index: Int)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handler = Handler(Looper.getMainLooper())
    private val logo = BitmapFactory.decodeResource(resources, R.drawable.memem_logo)
    private val emojis = listOf("😀", "😂", "❤️", "👍", "🙏", "🔥", "😭", "✨", "💀", "🤣")

    private var script = Script.QWERTY
    private var board = Board.LETTERS
    private var shift = ShiftState.OFF
    private var statusText = ""
    private var previews: List<Bitmap?> = listOf(null, null, null)

    private val hits = ArrayList<Hit>()
    private var memeRect = RectF()
    private val previewRects = ArrayList<RectF>()
    private var active: Hit? = null
    private var longFired = false
    private var emojiOpen = false
    private var emojiIndex = 0
    private var bubble: String? = null
    private var bubbleAt = 0L

    private val longPress = Runnable { onLongPress() }
    private val repeatStart = Runnable {
        longFired = true
        handler.post(repeat)
    }
    private val repeat = object : Runnable {
        override fun run() {
            host.delete()
            handler.postDelayed(this, 45)
        }
    }
    private val clearBubble = Runnable {
        bubble = null
        invalidate()
    }

    fun setStatus(text: String) {
        statusText = text
        requestLayout()
        invalidate()
    }

    fun showPreviews(bitmaps: List<Bitmap>) {
        previews = List(3) { bitmaps.getOrNull(it) }
        invalidate()
    }

    fun setQwertz(enabled: Boolean) {
        script = if (enabled) Script.QWERTZ else Script.QWERTY
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val status = if (statusText.isBlank()) 0 else dp(18)
        val height = dp(8) + dp(76) + status + dp(6) + 4 * (dp(50) + dp(4) + dp(5)) + dp(10)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(MemPalette.PAPER)
        layoutHits()
        drawMemeRow(canvas)
        if (statusText.isNotBlank()) {
            paint.typeface = Typeface.MONOSPACE
            paint.textSize = sp(11f)
            paint.color = MemPalette.INK
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(statusText, dp(10).toFloat(), memeRect.bottom + dp(16), paint)
        }
        for (hit in hits) drawKey(canvas, hit)
        bubble?.let { drawBubble(canvas, it) }
        if (emojiOpen) drawEmojiStrip(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitAt(event.x, event.y)
                active = hit
                longFired = false
                emojiOpen = false
                if (hit == null) return true
                feedback()
                if (hit.kind == Kind.DELETE) {
                    host.delete()
                    handler.postDelayed(repeatStart, 380)
                } else if (hit.kind != Kind.MEME && hit.kind != Kind.PREVIEW) {
                    handler.postDelayed(longPress, 380)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (emojiOpen) {
                    emojiIndex = emojiIndexAt(event.x)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                stopHold()
                val hit = active
                if (emojiOpen) {
                    host.commit(emojis[emojiIndex])
                    emojiOpen = false
                } else if (!longFired && hit != null) {
                    tap(hit)
                }
                active = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                stopHold()
                emojiOpen = false
                active = null
                invalidate()
            }
        }
        return true
    }

    private fun onLongPress() {
        val hit = active ?: return
        if (hit.kind != Kind.KEY) return
        val key = hit.key ?: return
        when (key.role) {
            KeyRole.EMOJI -> {
                longFired = true
                emojiOpen = true
                emojiIndex = 0
            }
            KeyRole.SPACE -> {
                longFired = true
                host.switchIme()
            }
            KeyRole.GLOBE -> {
                longFired = true
                host.showImePicker()
            }
            else -> {
                val alt = shownLong(key, shift) ?: return
                longFired = true
                host.commit(alt)
                showBubble(alt)
            }
        }
        invalidate()
    }

    private fun isPressed(hit: Hit): Boolean {
        val current = active ?: return false
        if (current.kind != hit.kind) return false
        if (hit.kind == Kind.PREVIEW) return current.index == hit.index
        if (hit.kind != Kind.KEY && hit.kind != Kind.DELETE) return hit.kind == Kind.MEME
        return current.key?.role == hit.key?.role && current.key?.text == hit.key?.text
    }

    private fun hitMatches(current: Hit?, kind: Kind, index: Int): Boolean {
        return current?.kind == kind && current.index == index
    }

    private fun tap(hit: Hit) {
        when (hit.kind) {
            Kind.MEME -> host.meme()
            Kind.PREVIEW -> host.pick(hit.index)
            Kind.KEY -> tapKey(hit.key ?: return)
            Kind.DELETE -> Unit
        }
    }

    private fun tapKey(key: KeyDef) {
        when (key.role) {
            KeyRole.CHAR -> {
                host.commit(shownText(key, shift))
                shift = afterLetter(shift)
            }
            KeyRole.EMOJI -> host.commit(",")
            KeyRole.SPACE -> host.commit(" ")
            KeyRole.ENTER -> host.enter()
            KeyRole.DELETE -> Unit
            KeyRole.SHIFT -> shift = shiftTap(shift)
            KeyRole.GLOBE -> host.switchIme()
            KeyRole.MODE -> {
                board = if (board == Board.LETTERS) Board.NUMBERS else Board.LETTERS
                if (board != Board.LETTERS) shift = ShiftState.OFF
            }
            KeyRole.MORE -> board = if (board == Board.NUMBERS) Board.SYMBOLS else Board.NUMBERS
        }
    }

    private fun stopHold() {
        handler.removeCallbacks(longPress)
        handler.removeCallbacks(repeatStart)
        handler.removeCallbacks(repeat)
    }

    private fun showBubble(text: String) {
        bubble = text
        bubbleAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(clearBubble)
        handler.postDelayed(clearBubble, 420)
    }

    private fun feedback() {
        playSoundEffect(SoundEffectConstants.CLICK)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val audio = context.getSystemService(AudioManager::class.java)
        audio?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.6f)
    }

    private fun layoutHits() {
        hits.clear()
        previewRects.clear()
        val pad = dp(6).toFloat()
        val gap = dp(5).toFloat()
        val shadow = dp(4).toFloat()
        var y = pad
        val logo = dp(40).toFloat()
        memeRect = RectF(pad, y, pad + dp(68), y + dp(68))
        hits.add(Hit(Kind.MEME, memeRect, null, -1))
        val left = memeRect.right + gap + logo
        val slot = (width - left - pad - gap * 2) / 3f
        for (index in 0 until 3) {
            val x = left + index * (slot + gap)
            val rect = RectF(x, y, x + slot - shadow, y + dp(68))
            previewRects.add(rect)
            hits.add(Hit(Kind.PREVIEW, rect, null, index))
        }
        y += dp(76)
        if (statusText.isNotBlank()) y += dp(18)
        y += dp(4)
        val keyH = dp(50).toFloat()
        val rows = keyboardRows(board, script)
        for (row in rows) {
            val visible = row.keys
            val units = visible.sumOf { it.weight.toDouble() }.toFloat() + row.sideInset * 2f
            val inner = width - pad * 2
            val gaps = gap * (visible.size - 1)
            val unit = (inner - gaps) / units
            var x = pad + row.sideInset * unit
            for (key in visible) {
                val w = unit * key.weight
                val rect = RectF(x, y, x + w - shadow, y + keyH)
                val kind = if (key.role == KeyRole.DELETE) Kind.DELETE else Kind.KEY
                hits.add(Hit(kind, rect, key, -1))
                x += w + gap
            }
            y += keyH + shadow + gap
        }
    }

    private fun drawMemeRow(canvas: Canvas) {
        val memePressed = active?.kind == Kind.MEME
        drawSticker(canvas, memeRect, MemPalette.YELLOW, dp(14).toFloat(), dp(2.5f).toFloat(), dp(4).toFloat(), memePressed, paint)
        val label = "Meme"
        paint.color = MemPalette.INK
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textSize = sp(14f)
        paint.textAlign = Paint.Align.CENTER
        val body = bodyOf(memeRect, memePressed)
        canvas.drawText(label, body.centerX(), body.centerY() + sp(5f), paint)
        val logoSize = dp(36).toFloat()
        val logoLeft = memeRect.right + dp(6)
        val logoTop = memeRect.centerY() - logoSize / 2f
        if (logo != null) {
            val dst = RectF(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize)
            canvas.drawBitmap(logo, null, dst, paint)
        }
        previewRects.forEachIndexed { index, rect ->
            val pressed = active?.kind == Kind.PREVIEW && active?.index == index && hitMatches(active, Kind.PREVIEW, index)
            drawSticker(canvas, rect, MemPalette.CREAM, dp(12).toFloat(), dp(2.5f).toFloat(), dp(4).toFloat(), pressed, paint)
            val face = bodyOf(rect, pressed)
            val bitmap = previews.getOrNull(index)
            if (bitmap != null && !bitmap.isRecycled) {
                canvas.save()
                canvas.clipRect(face)
                canvas.drawBitmap(bitmap, null, inset(face, dp(3).toFloat()), paint)
                canvas.restore()
            } else {
                paint.color = MemPalette.HINT
                paint.typeface = Typeface.MONOSPACE
                paint.textSize = sp(12f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("0${index + 1}", face.centerX(), face.centerY() + sp(4f), paint)
            }
        }
    }

    private fun drawKey(canvas: Canvas, hit: Hit) {
        val key = hit.key ?: return
        val pressed = isPressed(hit) && !emojiOpen
        val fill = when {
            key.role == KeyRole.SHIFT && shift != ShiftState.OFF -> MemPalette.INK
            key.face == KeyFace.YELLOW -> MemPalette.YELLOW
            key.face == KeyFace.BLUE -> MemPalette.BLUE
            else -> MemPalette.CREAM
        }
        val radius = if (key.pill) hit.rect.height() / 2f else dp(12).toFloat()
        drawSticker(canvas, hit.rect, fill, radius, dp(2.5f).toFloat(), dp(4).toFloat(), pressed, paint)
        val face = bodyOf(hit.rect, pressed)
        val ink = if (fill == MemPalette.INK || fill == MemPalette.BLUE) 0xFFFFFFFF.toInt() else MemPalette.INK
        when (key.role) {
            KeyRole.SHIFT -> drawShift(canvas, face, ink, shift == ShiftState.LOCK)
            KeyRole.DELETE -> drawDelete(canvas, face, ink)
            KeyRole.ENTER -> drawEnter(canvas, face, ink)
            KeyRole.GLOBE -> drawGlobe(canvas, face, ink)
            KeyRole.EMOJI -> drawEmojiKey(canvas, face, ink)
            KeyRole.SPACE -> {
                paint.color = ink
                paint.typeface = Typeface.MONOSPACE
                paint.textSize = sp(15f)
                paint.textAlign = Paint.Align.CENTER
                paint.isFakeBoldText = true
                canvas.drawText(spaceLabel(script), face.centerX(), face.centerY() + sp(5f), paint)
                paint.isFakeBoldText = false
            }
            else -> {
                val label = if (key.role == KeyRole.CHAR) shownText(key, shift) else key.text
                paint.color = ink
                paint.typeface = Typeface.DEFAULT_BOLD
                paint.textSize = sp(if (label.length > 2) 13f else 20f)
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, face.centerX(), face.centerY() + sp(7f), paint)
                val hint = key.hint
                if (hint != null) {
                    paint.color = MemPalette.HINT
                    paint.typeface = Typeface.DEFAULT
                    paint.textSize = sp(9f)
                    paint.textAlign = Paint.Align.RIGHT
                    canvas.drawText(hint, face.right - dp(5), face.top + sp(12f), paint)
                }
            }
        }
    }

    private fun drawBubble(canvas: Canvas, text: String) {
        val hit = active ?: return
        val w = dp(42).toFloat()
        val h = dp(40).toFloat()
        val rect = RectF(hit.rect.centerX() - w / 2f, hit.rect.top - h - dp(6), hit.rect.centerX() + w / 2f, hit.rect.top - dp(6))
        drawSticker(canvas, rect, MemPalette.YELLOW, dp(10).toFloat(), dp(2.5f).toFloat(), dp(3).toFloat(), false, paint)
        paint.color = MemPalette.INK
        paint.textSize = sp(18f)
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, rect.centerX(), rect.centerY() + sp(6f), paint)
    }

    private fun drawEmojiStrip(canvas: Canvas) {
        val anchor = active?.rect ?: return
        val cell = dp(40).toFloat()
        val width = cell * emojis.size + dp(8)
        var left = anchor.centerX() - width / 2f
        left = left.coerceIn(dp(4).toFloat(), (getWidth() - width - dp(4)).coerceAtLeast(dp(4).toFloat()))
        val top = (anchor.top - dp(52)).coerceAtLeast(dp(4).toFloat())
        val strip = RectF(left, top, left + width, top + dp(46))
        drawSticker(canvas, strip, MemPalette.CREAM, dp(12).toFloat(), dp(2.5f).toFloat(), dp(3).toFloat(), false, paint)
        paint.textSize = sp(18f)
        paint.textAlign = Paint.Align.CENTER
        emojis.forEachIndexed { index, emoji ->
            val cx = strip.left + dp(4) + cell * index + cell / 2f
            if (index == emojiIndex) {
                paint.color = MemPalette.YELLOW
                canvas.drawCircle(cx, strip.centerY(), dp(16).toFloat(), paint)
            }
            paint.color = MemPalette.INK
            canvas.drawText(emoji, cx, strip.centerY() + sp(6f), paint)
        }
    }

    private fun emojiIndexAt(x: Float): Int {
        val anchor = active?.rect ?: return 0
        val cell = dp(40).toFloat()
        val width = cell * emojis.size + dp(8)
        var left = anchor.centerX() - width / 2f
        left = left.coerceIn(dp(4).toFloat(), (getWidth() - width - dp(4)).coerceAtLeast(dp(4).toFloat()))
        val index = ((x - left - dp(4)) / cell).toInt()
        return index.coerceIn(0, emojis.lastIndex)
    }

    private fun drawShift(canvas: Canvas, face: RectF, color: Int, locked: Boolean) {
        val cx = face.centerX()
        val cy = face.centerY()
        val w = min(face.width(), face.height()) * 0.16f
        val path = Path()
        path.moveTo(cx, cy - w * 1.5f)
        path.lineTo(cx + w * 1.15f, cy - w * 0.1f)
        path.lineTo(cx + w * 0.48f, cy - w * 0.1f)
        path.lineTo(cx + w * 0.48f, cy + w * 1.05f)
        path.lineTo(cx - w * 0.48f, cy + w * 1.05f)
        path.lineTo(cx - w * 0.48f, cy - w * 0.1f)
        path.lineTo(cx - w * 1.15f, cy - w * 0.1f)
        path.close()
        paint.color = color
        paint.style = Paint.Style.FILL
        canvas.drawPath(path, paint)
        if (locked) {
            canvas.drawRoundRect(cx - w * 0.48f, cy + w * 1.25f, cx + w * 0.48f, cy + w * 1.48f, 2f, 2f, paint)
        }
    }

    private fun drawDelete(canvas: Canvas, face: RectF, color: Int) {
        val w = min(face.width(), face.height()) * 0.34f
        val h = w * 0.72f
        val cx = face.centerX()
        val cy = face.centerY()
        val path = Path()
        path.moveTo(cx - w * 0.15f, cy - h)
        path.lineTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h)
        path.lineTo(cx - w * 0.15f, cy + h)
        path.lineTo(cx - w, cy)
        path.close()
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f).toFloat()
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
        val x = w * 0.28f
        canvas.drawLine(cx - x * 0.2f, cy - x, cx + x, cy + x * 0.7f, paint)
        canvas.drawLine(cx - x * 0.2f, cy + x * 0.7f, cx + x, cy - x, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEnter(canvas: Canvas, face: RectF, color: Int) {
        val s = min(face.width(), face.height()) * 0.22f
        val cx = face.centerX()
        val cy = face.centerY()
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.2f).toFloat()
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        val path = Path()
        path.moveTo(cx + s, cy - s)
        path.lineTo(cx + s, cy + s * 0.55f)
        path.lineTo(cx - s, cy + s * 0.55f)
        canvas.drawPath(path, paint)
        canvas.drawLine(cx - s, cy + s * 0.55f, cx - s * 0.35f, cy + s * 0.05f, paint)
        canvas.drawLine(cx - s, cy + s * 0.55f, cx - s * 0.35f, cy + s, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawGlobe(canvas: Canvas, face: RectF, color: Int) {
        val r = min(face.width(), face.height()) * 0.22f
        paint.color = color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1.8f).toFloat()
        canvas.drawCircle(face.centerX(), face.centerY(), r, paint)
        canvas.drawOval(face.centerX() - r * 0.45f, face.centerY() - r, face.centerX() + r * 0.45f, face.centerY() + r, paint)
        canvas.drawLine(face.centerX() - r, face.centerY(), face.centerX() + r, face.centerY(), paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawEmojiKey(canvas: Canvas, face: RectF, color: Int) {
        paint.color = color
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.DEFAULT
        paint.textSize = sp(16f)
        canvas.drawText("☺", face.centerX(), face.centerY() + sp(2f), paint)
        paint.textSize = sp(11f)
        paint.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(",", face.centerX(), face.bottom - dp(6), paint)
    }

    private fun bodyOf(rect: RectF, pressed: Boolean): RectF {
        val shadow = dp(4).toFloat()
        return if (pressed) RectF(rect.left + shadow, rect.top + shadow, rect.right + shadow, rect.bottom + shadow) else rect
    }

    private fun inset(rect: RectF, pad: Float) = RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)

    private fun hitAt(x: Float, y: Float): Hit? = hits.lastOrNull { it.rect.contains(x, y) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float) = value * resources.displayMetrics.density

    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private enum class Kind { KEY, DELETE, MEME, PREVIEW }

    private data class Hit(val kind: Kind, val rect: RectF, val key: KeyDef?, val index: Int)
}
