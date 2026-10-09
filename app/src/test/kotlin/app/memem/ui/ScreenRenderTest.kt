package app.memem.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asImageBitmap
import app.memem.data.MemeTemplate
import app.memem.engine.FieldSpec
import app.memem.engine.InsertPreference
import app.memem.ime.KeyboardPanel
import app.memem.models.DownloadSnapshot
import app.memem.render.MemeRenderer
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {
    @Test
    fun welcome() = shoot("wizard-willkommen.png") {
        WizardScreen(
            checks = WizardChecks(),
            download = DownloadSnapshot(),
            draft = "",
            onDraft = {},
            onDownload = {},
            onEnableKeyboard = {},
            onPickKeyboard = {},
            onA11y = {},
            onFinish = {},
            onClose = {},
        )
    }

    @Test
    fun models() = shoot("wizard-modelle.png") {
        WizardScreen(
            checks = WizardChecks(),
            download = DownloadSnapshot(active = true, text = "gemma-cpu 2/2: 42%", percent = 42),
            draft = "",
            onDraft = {},
            onDownload = {},
            onEnableKeyboard = {},
            onPickKeyboard = {},
            onA11y = {},
            onFinish = {},
            onClose = {},
            pinnedPage = 1,
        )
    }

    @Test
    fun wizardStepSeven() = shoot("wizard-schritt-7.png", height = 2400) {
        WizardScreen(
            checks = WizardChecks(),
            download = DownloadSnapshot(),
            draft = "Testnachricht",
            onDraft = {},
            onDownload = {},
            onEnableKeyboard = {},
            onPickKeyboard = {},
            onA11y = {},
            onFinish = {},
            onClose = {},
            pinnedPage = 5,
        )
    }

    @Test
    fun settings() = shoot("hauptansicht.png", height = 3600) {
        SettingsScreen(
            state = SetupUi(
                a11y = "Bedienungshilfe aus.",
                qwertz = false,
                insert = InsertPreference.CLIPBOARD,
                models = "Embedding fehlt, Gemma fehlt",
                incomplete = true,
                modelsOn = false,
                keyboardOn = false,
                a11yOn = false,
            ),
            tryDraft = "Testnachricht",
            onWizard = {},
            onKeyboard = {},
            onA11y = {},
            onOverlayPermission = {},
            onOverlay = {},
            onQwertz = {},
            onInsert = {},
            onDownload = {},
            onHarness = {},
            scroll = false,
        )
    }

    @Test
    fun keyboardArt() = shoot("tastatur-qwerty.png", height = 900) {
        KeyboardArt(qwertz = false)
    }

    @Test
    fun docsImages() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val renderer = MemeRenderer(context)
        val drake = renderer.render(template(context, "drake"), listOf("Another meeting today", "Friday, I'm free"))
        val fine = renderer.render(template(context, "fine"), listOf("The server is on fire", "All good"))
        val cmm = renderer.render(template(context, "cmm"), listOf("The deadline was yesterday"))
        check(drawn(drake) && drawn(fine) && drawn(cmm)) { "meme template rendered blank" }
        save(drake, "meme-drake.png", docs = true)
        save(fine, "meme-fine.png", docs = true)
        save(cmm, "meme-cmm.png", docs = true)

        val panel = KeyboardPanel(context, object : KeyboardPanel.Host {
            override fun commit(text: String) = Unit
            override fun delete() = Unit
            override fun enter() = Unit
            override fun switchIme() = Unit
            override fun showImePicker() = Unit
            override fun meme() = Unit
            override fun pick(index: Int) = Unit
        })
        panel.setStatus("The server is on fire")
        panel.showPreviews(listOf(drake, fine, cmm), listOf("KI", "wörtlich", "KI"))
        val width = 1080
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val keyboard = Bitmap.createBitmap(panel.measuredWidth, panel.measuredHeight, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(keyboard))
        save(keyboard, "tastatur.png", docs = true)

        shoot("einrichtung.png", height = 1920, docs = true) {
            WizardScreen(
                checks = WizardChecks(),
                download = DownloadSnapshot(),
                draft = "",
                onDraft = {},
                onDownload = {},
                onEnableKeyboard = {},
                onPickKeyboard = {},
                onA11y = {},
                onFinish = {},
                onClose = {},
                pinnedPage = 0,
            )
        }

        val previews = listOf(drake, fine, cmm).map { it.asImageBitmap() }
        val settings = capture(height = 6400) {
            SettingsScreen(
                state = SetupUi(
                    a11y = "Bedienungshilfe aus.",
                    qwertz = false,
                    insert = InsertPreference.CLIPBOARD,
                    models = "Embedding und Gemma liegen auf dem Gerät.",
                    incomplete = false,
                    modelsOn = true,
                    keyboardOn = true,
                    keyboardEnabled = true,
                    a11yOn = false,
                ),
                tryDraft = "The server is on fire",
                tryStatus = "3 Vorschläge",
                tryPreviews = previews,
                tryMarks = listOf("KI", "wörtlich", "KI"),
                onWizard = {},
                onKeyboard = {},
                onA11y = {},
                onOverlayPermission = {},
                onOverlay = {},
                onQwertz = {},
                onInsert = {},
                onDownload = {},
                onHarness = {},
                scroll = false,
            )
        }
        val trimmed = trimBottom(settings)
        val split = splitAfterTryCards(trimmed)
        println("settings ${trimmed.width}x${trimmed.height} split=$split")
        save(Bitmap.createBitmap(trimmed, 0, 0, trimmed.width, split), "hauptansicht.png", docs = true)
        val settingsTop = (split + 4).coerceAtMost(trimmed.height - 1)
        save(
            Bitmap.createBitmap(trimmed, 0, settingsTop, trimmed.width, trimmed.height - settingsTop),
            "einstellungen.png",
            docs = true,
        )

        val logo = File(repoRoot(), "app/src/main/res/drawable-nodpi/memem_logo.png")
        val docs = File(repoRoot(), "docs/images")
        docs.mkdirs()
        logo.copyTo(File(docs, "logo.png"), overwrite = true)
    }

    @Test
    fun keyboardView() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val panel = KeyboardPanel(context, object : KeyboardPanel.Host {
            override fun commit(text: String) = Unit
            override fun delete() = Unit
            override fun enter() = Unit
            override fun switchIme() = Unit
            override fun showImePicker() = Unit
            override fun meme() = Unit
            override fun pick(index: Int) = Unit
        })
        val width = 1080
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val bitmap = Bitmap.createBitmap(panel.measuredWidth, panel.measuredHeight, Bitmap.Config.ARGB_8888)
        panel.draw(Canvas(bitmap))
        save(bitmap, "tastatur-view.png")
    }

    private fun shoot(
        name: String,
        height: Int = 1920,
        docs: Boolean = false,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        save(capture(height, content), name, docs)
    }

    private fun capture(height: Int, content: @androidx.compose.runtime.Composable () -> Unit): Bitmap {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.get().setContent { content() }
        shadowOf(Looper.getMainLooper()).idle()
        val view = controller.get().window.decorView
        val width = 1080
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun save(bitmap: Bitmap, name: String, docs: Boolean = false) {
        val dirs = mutableListOf(File(repoRoot(), "build/screenshots"))
        if (docs) dirs += File(repoRoot(), "docs/images")
        for (dir in dirs) {
            dir.mkdirs()
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun template(context: android.content.Context, id: String): MemeTemplate {
        val catalog = JSONObject(context.assets.open("catalog.json").bufferedReader().readText())
        val list = catalog.getJSONArray("templates")
        for (i in 0 until list.length()) {
            val obj = list.getJSONObject(i)
            if (obj.getString("id") != id) continue
            val fieldsJson = obj.getJSONArray("fields")
            val fields = buildList {
                for (f in 0 until fieldsJson.length()) {
                    val field = fieldsJson.getJSONObject(f)
                    add(
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
                        ),
                    )
                }
            }
            return MemeTemplate(
                id = id,
                name = obj.optString("name"),
                boxes = obj.optInt("boxes", fields.size),
                width = obj.optInt("width", 720),
                height = obj.optInt("height", 720),
                fields = fields,
                meaningDe = "",
                meaningEn = "",
                examples = emptyList(),
                situationsDe = emptyList(),
                defaultLines = emptyList(),
            )
        }
        error("template missing: $id")
    }

    private fun drawn(bitmap: Bitmap): Boolean {
        val origin = bitmap.getPixel(8, 8)
        val stepX = (bitmap.width / 12).coerceAtLeast(1)
        val stepY = (bitmap.height / 12).coerceAtLeast(1)
        var y = stepY
        while (y < bitmap.height) {
            var x = stepX
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != origin) return true
                x += stepX
            }
            y += stepY
        }
        return false
    }

    private fun trimBottom(bitmap: Bitmap): Bitmap {
        val paper = 0xFFF3EDE0.toInt()
        var last = bitmap.height - 1
        while (last > 0) {
            var ink = false
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, last) != paper) {
                    ink = true
                    break
                }
                x += 6
            }
            if (ink) break
            last--
        }
        val height = (last + 32).coerceAtMost(bitmap.height)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, height)
    }

    private fun splitAfterTryCards(bitmap: Bitmap): Int {
        val paper = 0xFFF3EDE0.toInt()
        fun diverse(y: Int): Boolean {
            val seen = HashSet<Int>()
            var x = 80
            while (x < bitmap.width - 80) {
                seen += bitmap.getPixel(x, y)
                if (seen.size > 40) return true
                x += 4
            }
            return false
        }
        fun paperRow(y: Int): Boolean {
            var x = 0
            while (x < bitmap.width) {
                if (bitmap.getPixel(x, y) != paper) return false
                x += 8
            }
            return true
        }
        val runs = mutableListOf<IntRange>()
        var start = -1
        var end = -1
        var y = 0
        while (y < bitmap.height) {
            if (diverse(y)) {
                if (start < 0) start = y
                end = y
            } else if (start >= 0 && y > end + 48) {
                if (end - start > 80) runs += start..end
                start = -1
                end = -1
            }
            y += 4
        }
        if (start >= 0 && end - start > 80) runs += start..end
        end = runs.lastOrNull()?.last ?: return bitmap.height.coerceAtMost(1600)
        var y2 = end + 4
        var sawContent = false
        while (y2 < bitmap.height - 12) {
            val gap = paperRow(y2)
            if (!gap) sawContent = true
            if (sawContent && gap && paperRow((y2 + 8).coerceAtMost(bitmap.height - 1))) {
                var gapEnd = y2
                while (gapEnd < bitmap.height - 1 && paperRow(gapEnd)) gapEnd += 2
                if (gapEnd - y2 > 24) return (y2 + gapEnd) / 2
            }
            y2 += 4
        }
        return (end + 120).coerceAtMost(bitmap.height)
    }

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        repeat(8) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: return File(System.getProperty("user.dir") ?: ".")
        }
        return File(System.getProperty("user.dir") ?: ".")
    }
}
