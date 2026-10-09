package app.memem.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CompoundButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.memem.R
import app.memem.a11y.MememAccessibilityService
import app.memem.engine.InsertPreference
import app.memem.harness.InsertHarnessActivity
import app.memem.models.ModelCatalog
import app.memem.models.ModelDownloadService
import app.memem.overlay.OverlayService

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        findViewById<Button>(R.id.enable_keyboard).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.enable_a11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.overlay_permission).setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
        val insert = findViewById<RadioGroup>(R.id.insert_mode)
        when (prefs.insertPreference) {
            InsertPreference.COMMIT_CONTENT -> insert.check(R.id.insert_commit)
            InsertPreference.AUTO -> insert.check(R.id.insert_auto)
            InsertPreference.CLIPBOARD -> insert.check(R.id.insert_clipboard)
        }
        insert.setOnCheckedChangeListener { _, id ->
            prefs.insertPreference = when (id) {
                R.id.insert_commit -> InsertPreference.COMMIT_CONTENT
                R.id.insert_auto -> InsertPreference.AUTO
                else -> InsertPreference.CLIPBOARD
            }
        }
        val backend = findViewById<RadioGroup>(R.id.backend)
        backend.check(if (prefs.gpu) R.id.backend_gpu else R.id.backend_cpu)
        backend.setOnCheckedChangeListener { _, id -> prefs.gpu = id == R.id.backend_gpu }
        findViewById<Button>(R.id.download).setOnClickListener { startDownload(gpu = false) }
        findViewById<Button>(R.id.download_gpu).setOnClickListener { startDownload(gpu = true) }
        findViewById<Button>(R.id.open_harness).setOnClickListener {
            startActivity(Intent(this, InsertHarnessActivity::class.java))
        }
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) refreshStatus()
    }

    private fun startDownload(gpu: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val intent = Intent(this, ModelDownloadService::class.java).putExtra(ModelDownloadService.EXTRA_GPU, gpu)
        ContextCompat.startForegroundService(this, intent)
        findViewById<TextView>(R.id.model_status).text = "Download läuft. Fortschritt steht in der Benachrichtigung."
    }

    private fun refreshStatus() {
        val embed = if (ModelCatalog.ready(this, ModelCatalog.embed)) "da" else "fehlt"
        val cpu = if (ModelCatalog.ready(this, ModelCatalog.gemmaCpu)) "da" else "fehlt"
        val gpu = if (ModelCatalog.ready(this, ModelCatalog.gemmaGpu)) "da" else "fehlt"
        findViewById<TextView>(R.id.model_status).text = "Embedding $embed, Gemma CPU $cpu, Gemma GPU $gpu"
        val a11y = when {
            MememAccessibilityService.instance != null -> "Bedienungshilfe aktiv, liest nur WhatsApp."
            MememAccessibilityService.enabled(this) -> "Bedienungshilfe eingeschaltet, Dienst gerade nicht verbunden."
            else -> "Bedienungshilfe aus. Auf HyperOS zuerst eingeschränkte Einstellungen zulassen."
        }
        findViewById<TextView>(R.id.a11y_status).text = a11y
        val overlay = findViewById<CompoundButton>(R.id.overlay_switch)
        overlay.setOnCheckedChangeListener(null)
        overlay.isChecked = prefs.overlay
        overlay.setOnCheckedChangeListener { _, checked ->
            prefs.overlay = checked
            if (!checked) {
                OverlayService.stop(this)
            } else if (Settings.canDrawOverlays(this)) {
                OverlayService.start(this)
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (prefs.overlay && Settings.canDrawOverlays(this)) OverlayService.start(this)
        if (!prefs.overlay) OverlayService.stop(this)
    }
}
