package app.memem.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.memem.data.MemeTemplate
import app.memem.engine.FieldSpec
import app.memem.engine.placeText
import app.memem.engine.visualCenter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemeBoxGoldenTest {
    private val ids = listOf("cmm", "drake", "db", "ds", "buzz", "fry", "slap", "exit", "crow", "doge")

    @Test
    fun catalogBoxesStayWithinThreePercentOfMemegen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = JSONObject(context.assets.open("catalog.json").bufferedReader().readText())
        val templates = catalog.getJSONArray("templates")
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until templates.length()) {
            val obj = templates.getJSONObject(i)
            byId[obj.getString("id")] = obj
        }
        assertEquals(10, ids.size)
        for (id in ids) {
            val obj = byId.getValue(id)
            val width = obj.getInt("width")
            val height = obj.getInt("height")
            val fields = readFields(obj)
            val placed = placeText(fields, List(fields.size) { "ich denke ihr verliert alles" }, width, height, roughMeasurer())
            assertEquals(fields.size, placed.size)
            placed.forEachIndexed { index, item ->
                val field = fields[index]
                val anchorX = (width * field.anchorX).toInt()
                val anchorY = (height * field.anchorY).toInt()
                val boxW = (width * field.scaleX).toInt().coerceAtLeast(1)
                val boxH = (height * field.scaleY).toInt().coerceAtLeast(1)
                val tol = width * 0.03f
                assertTrue("$id[$index] anchor", abs(item.anchorX - anchorX) < tol && abs(item.anchorY - anchorY) < tol)
                assertEquals(boxW, item.boxW)
                assertEquals(boxH, item.boxH)
                val (cx, cy) = visualCenter(item.anchorX, item.anchorY, item.boxW, item.boxH, item.angle)
                val (rx, ry) = referenceCenter(anchorX, anchorY, boxW, boxH, field.angle)
                assertTrue("$id[$index] center x $cx vs $rx", abs(cx - rx) < tol)
                assertTrue("$id[$index] center y $cy vs $ry", abs(cy - ry) < tol)
                assertEquals(field.angle, item.angle)
                assertEquals(field.align, item.align)
                assertEquals(field.font, item.fontKey)
                assertEquals(field.color, item.color)
            }
        }
    }

    @Test
    fun changeMyMindTextFitsAndSitsOnTheSign() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val template = load(context, "cmm")
        val renderer = MemeRenderer(context)
        val line = "ICH DENKE IHR VERLIERT ALLES"
        val placed = placeText(template.fields, listOf(line), template.width, template.height, roughMeasurer()).single()
        val fitted = renderer.fitLineLayout(placed, android.graphics.Typeface.createFromAsset(context.assets, "fonts/TitilliumWeb-SemiBold.ttf"))
        val inkW = (0 until fitted.layout.lineCount).maxOf { fitted.layout.getLineWidth(it) }
        assertTrue("width $inkW box ${placed.boxW}", inkW + fitted.strokePx <= placed.boxW)
        assertTrue("height ${fitted.layout.height} box ${placed.boxH}", fitted.layout.height + fitted.strokePx <= placed.boxH)
        assertTrue(fitted.layout.lineCount <= placed.text.count { it == '\n' } + 1)

        val rendered = renderer.render(template, listOf(line))
        val blank = BitmapFactory.decodeStream(context.assets.open("images/cmm.webp"))
        val (cx, cy, count) = centroid(blank, rendered)
        blank.recycle()
        assertTrue("no ink", count > 40)
        val (expectX, expectY) = visualCenter(placed.anchorX, placed.anchorY, placed.boxW, placed.boxH, placed.angle)
        val tol = template.width * 0.03f
        assertTrue("cx $cx expected $expectX", abs(cx - expectX) < tol)
        assertTrue("cy $cy expected $expectY", abs(cy - expectY) < tol)
        val out = File("/opt/cursor/artifacts/cmm-text.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { rendered.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rendered.recycle()
    }

    private fun centroid(blank: Bitmap, rendered: Bitmap): Triple<Float, Float, Int> {
        var sumX = 0.0
        var sumY = 0.0
        var count = 0
        val width = minOf(blank.width, rendered.width)
        val height = minOf(blank.height, rendered.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = blank.getPixel(x, y)
                val b = rendered.getPixel(x, y)
                if (abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b)) > 48) {
                    sumX += x
                    sumY += y
                    count += 1
                }
            }
        }
        if (count == 0) return Triple(0f, 0f, 0)
        return Triple((sumX / count).toFloat(), (sumY / count).toFloat(), count)
    }

    private fun referenceCenter(anchorX: Int, anchorY: Int, boxW: Int, boxH: Int, angle: Float): Pair<Float, Float> {
        val rad = Math.toRadians(angle.toDouble())
        val grownW = boxW * abs(cos(rad)) + boxH * abs(sin(rad))
        val grownH = boxW * abs(sin(rad)) + boxH * abs(cos(rad))
        return (anchorX + grownW / 2.0).toFloat() to (anchorY + grownH / 2.0).toFloat()
    }

    private fun roughMeasurer() = app.memem.engine.TextMeasurer { text, fontPx, _ ->
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        val width = lines.maxOf { (it.length * fontPx * 0.62f).toInt().coerceAtLeast(1) }
        val height = (lines.size * fontPx * 1.25f).toInt().coerceAtLeast(1)
        width to height
    }

    private fun load(context: android.content.Context, id: String): MemeTemplate {
        val catalog = JSONObject(context.assets.open("catalog.json").bufferedReader().readText())
        val templates = catalog.getJSONArray("templates")
        for (i in 0 until templates.length()) {
            val obj = templates.getJSONObject(i)
            if (obj.getString("id") != id) continue
            val fields = readFields(obj)
            return MemeTemplate(
                id = id,
                name = obj.optString("name"),
                boxes = fields.size,
                width = obj.getInt("width"),
                height = obj.getInt("height"),
                fields = fields,
                meaningDe = "",
                meaningEn = "",
                examples = emptyList(),
                situationsDe = emptyList(),
                defaultLines = emptyList(),
            )
        }
        error("missing $id")
    }

    private fun readFields(obj: JSONObject): List<FieldSpec> {
        val fieldsJson = obj.getJSONArray("fields")
        return List(fieldsJson.length()) { index ->
            val field = fieldsJson.getJSONObject(index)
            FieldSpec(
                style = field.optString("style", "upper"),
                color = field.optString("color", "white"),
                font = field.optString("font", "thick"),
                anchorX = field.optDouble("anchorX", 0.0).toFloat(),
                anchorY = field.optDouble("anchorY", 0.0).toFloat(),
                scaleX = field.optDouble("scaleX", 1.0).toFloat(),
                scaleY = field.optDouble("scaleY", 0.2).toFloat(),
                angle = field.optDouble("angle", 0.0).toFloat(),
                align = field.optString("align", "center"),
            )
        }
    }
}
