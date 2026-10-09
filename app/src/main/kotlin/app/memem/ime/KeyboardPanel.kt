package app.memem.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.memem.R

class KeyboardPanel(context: Context, private val host: Host) : LinearLayout(context) {
    interface Host {
        fun commit(text: String)
        fun delete()
        fun enter()
        fun switchKeyboard()
        fun meme()
        fun pick(index: Int)
    }

    private val status: TextView
    private val previews = ArrayList<ImageView>(3)
    private var shifted = false
    private val letterButtons = ArrayList<Button>()

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.parseColor("#1C1C1E"))
        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.parseColor("#111113"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        strip.addView(actionButton(context.getString(R.string.meme)) { host.meme() }, LayoutParams(dp(72), dp(88)))
        repeat(3) { index ->
            val image = ImageView(context).apply {
                setBackgroundColor(Color.parseColor("#2A2A2C"))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener { host.pick(index) }
                contentDescription = "Vorschlag ${index + 1}"
            }
            previews.add(image)
            val lp = LayoutParams(0, dp(88), 1f)
            lp.marginStart = dp(6)
            strip.addView(image, lp)
        }
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        status = TextView(context).apply {
            setTextColor(Color.parseColor("#F5C518"))
            textSize = 12f
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val rows = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "ß"),
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p", "ü"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ö", "ä"),
            listOf("y", "x", "c", "v", "b", "n", "m", ",", ".", "-"),
        )
        for (row in rows) {
            val line = rowLayout()
            for (key in row) {
                val button = keyButton(label(key)) { host.commit(label(key)) }
                letterButtons.add(button)
                line.addView(button, LayoutParams(0, dp(46), 1f))
            }
            addView(line)
        }
        val bottom = rowLayout()
        bottom.addView(keyButton("ABC") { host.switchKeyboard() }, LayoutParams(0, dp(46), 1.3f))
        bottom.addView(keyButton("⇧") {
            shifted = !shifted
            refreshLabels()
        }, LayoutParams(0, dp(46), 1.2f))
        bottom.addView(keyButton("Leer") { host.commit(" ") }, LayoutParams(0, dp(46), 4f))
        bottom.addView(keyButton("⌫") { host.delete() }, LayoutParams(0, dp(46), 1.4f))
        bottom.addView(keyButton("⏎") { host.enter() }, LayoutParams(0, dp(46), 1.4f))
        addView(bottom)
    }

    fun setStatus(text: String) {
        status.text = text
    }

    fun showPreviews(bitmaps: List<Bitmap>) {
        previews.forEachIndexed { index, view ->
            view.setImageBitmap(bitmaps.getOrNull(index))
        }
    }

    private fun refreshLabels() {
        val rows = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "ß"),
            listOf("q", "w", "e", "r", "t", "z", "u", "i", "o", "p", "ü"),
            listOf("a", "s", "d", "f", "g", "h", "j", "k", "l", "ö", "ä"),
            listOf("y", "x", "c", "v", "b", "n", "m", ",", ".", "-"),
        ).flatten()
        letterButtons.forEachIndexed { index, button ->
            val raw = rows[index]
            button.text = label(raw)
            button.setOnClickListener { host.commit(label(raw)) }
        }
    }

    private fun label(raw: String): String {
        if (!shifted || raw.length != 1 || !raw[0].isLetter()) return raw
        return raw.uppercase()
    }

    private fun rowLayout() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        setPadding(dp(2), dp(2), dp(2), dp(2))
    }

    private fun keyButton(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        isAllCaps = false
        setBackgroundColor(Color.parseColor("#3A3A3C"))
        setPadding(0, 0, 0, 0)
        setOnClickListener { onClick() }
    }

    private fun actionButton(text: String, onClick: () -> Unit) = Button(context).apply {
        this.text = text
        setTextColor(Color.parseColor("#111113"))
        setBackgroundColor(Color.parseColor("#F5C518"))
        isAllCaps = false
        textSize = 14f
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
