package app.memem.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.memem.R
import app.memem.a11y.MememAccessibilityService
import app.memem.engine.InsertPreference
import app.memem.harness.InsertHarnessActivity
import app.memem.models.DownloadProgress
import app.memem.models.ModelCatalog
import app.memem.models.ModelDownloadService
import app.memem.overlay.OverlayService
import app.memem.ui.SettingsScreen
import app.memem.ui.SetupUi

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private var generation by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (savedInstanceState == null && !prefs.wizardDone) {
            startActivity(Intent(this, WizardActivity::class.java))
        }
        setContent {
            val tick = generation
            val download by DownloadProgress.flow.collectAsState()
            val state = readState(download.text).let { if (tick >= 0) it else it }
            SettingsScreen(
                state = state,
                onWizard = { startActivity(Intent(this, WizardActivity::class.java)) },
                onKeyboard = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                onA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onOverlayPermission = {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                },
                onOverlay = { checked ->
                    prefs.overlay = checked
                    if (!checked) {
                        OverlayService.stop(this)
                    } else if (Settings.canDrawOverlays(this)) {
                        OverlayService.start(this)
                    } else {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                    }
                    generation += 1
                },
                onQwertz = {
                    prefs.qwertz = it
                    generation += 1
                },
                onInsert = {
                    prefs.insertPreference = it
                    generation += 1
                },
                onDownload = { startDownload() },
                onHarness = { startActivity(Intent(this, InsertHarnessActivity::class.java)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::prefs.isInitialized) return
        generation += 1
        if (prefs.overlay && Settings.canDrawOverlays(this)) OverlayService.start(this)
        if (!prefs.overlay) OverlayService.stop(this)
    }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ContextCompat.startForegroundService(this, Intent(this, ModelDownloadService::class.java))
        generation += 1
    }

    private fun readState(download: String): SetupUi {
        val embed = if (ModelCatalog.ready(this, ModelCatalog.embed)) "da" else "fehlt"
        val cpu = if (ModelCatalog.ready(this, ModelCatalog.gemmaCpu)) "da" else "fehlt"
        val a11y = when {
            MememAccessibilityService.instance != null -> "Bedienungshilfe aktiv, liest nur WhatsApp."
            MememAccessibilityService.enabled(this) -> "Bedienungshilfe eingeschaltet, Dienst gerade nicht verbunden."
            else -> "Bedienungshilfe aus. Auf HyperOS zuerst eingeschränkte Einstellungen zulassen."
        }
        return SetupUi(
            a11y = a11y,
            overlay = prefs.overlay,
            qwertz = prefs.qwertz,
            insert = prefs.insertPreference,
            models = "Embedding $embed, Gemma $cpu",
            download = download,
            hyperos = getString(R.string.hyperos_hint),
        )
    }
}
