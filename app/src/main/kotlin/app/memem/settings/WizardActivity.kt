package app.memem.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.memem.a11y.MememAccessibilityService
import app.memem.models.DownloadProgress
import app.memem.models.ModelDownloadService
import app.memem.ui.WizardChecks
import app.memem.ui.WizardScreen

class WizardActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private val checks = mutableStateOf(WizardChecks())
    private val draft = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContent {
            val download by DownloadProgress.flow.collectAsState()
            val live by checks
            WizardScreen(
                checks = live,
                download = download,
                draft = draft.value,
                onDraft = { draft.value = it },
                onDownload = { startDownload() },
                onEnableKeyboard = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                onPickKeyboard = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
                onA11y = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onAppInfo = { SystemLinks.appDetails(this) },
                onAutostart = { SystemLinks.autostart(this) },
                onBattery = { SystemLinks.battery(this) },
                onAckAutostart = {
                    prefs.autostartAck = true
                    refresh()
                },
                onFinish = {
                    prefs.wizardDone = true
                    prefs.wizardClosed = true
                    finish()
                },
                onClose = {
                    prefs.wizardClosed = true
                    finish()
                },
            )
        }
    }

    override fun onStart() {
        super.onStart()
        DownloadProgress.wizardVisible = true
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) refresh()
    }

    override fun onStop() {
        DownloadProgress.wizardVisible = false
        super.onStop()
    }

    private fun refresh() {
        checks.value = WizardChecks(
            models = SetupProbe.modelsReady(this),
            keyboardEnabled = SetupProbe.keyboardEnabled(this),
            keyboardCurrent = SetupProbe.keyboardSelected(this),
            a11y = MememAccessibilityService.enabled(this),
            battery = SetupProbe.batteryFree(this),
            autostartAck = prefs.autostartAck,
        )
    }

    private fun startDownload() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        ContextCompat.startForegroundService(this, Intent(this, ModelDownloadService::class.java))
    }
}
