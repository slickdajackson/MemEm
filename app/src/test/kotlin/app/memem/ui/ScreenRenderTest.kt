package app.memem.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.memem.engine.InsertPreference
import app.memem.ime.KeyboardPanel
import app.memem.models.DownloadSnapshot
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
            onAppInfo = {},
            onAutostart = {},
            onBattery = {},
            onAckAutostart = {},
            onFinish = {},
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
            onAppInfo = {},
            onAutostart = {},
            onBattery = {},
            onAckAutostart = {},
                onFinish = {},
                pinnedPage = 1,
            )
    }

    @Test
    fun settings() = shoot("einstellungen.png", height = 2400) {
        SettingsScreen(
            state = SetupUi(
                a11y = "Bedienungshilfe aus.",
                qwertz = false,
                insert = InsertPreference.CLIPBOARD,
                models = "Embedding fehlt, Gemma fehlt",
                hyperos = "Autostart an. Akku auf Keine Einschränkungen.",
            ),
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

    private fun shoot(name: String, height: Int = 1920, content: @androidx.compose.runtime.Composable () -> Unit) {
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
        save(bitmap, name)
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = File("/opt/cursor/artifacts")
        dir.mkdirs()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
