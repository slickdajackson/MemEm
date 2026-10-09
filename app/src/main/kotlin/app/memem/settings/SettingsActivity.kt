package app.memem.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
import app.memem.pipeline.MemeOption
import app.memem.pipeline.MemePipeline
import app.memem.ui.SettingsScreen
import app.memem.ui.SetupUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var pipeline: MemePipeline
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var generation by mutableIntStateOf(0)
    private val tryDraft = mutableStateOf("")
    private val tryStatus = mutableStateOf("")
    private val tryPreviews = mutableStateOf<List<ImageBitmap>>(emptyList())
    private var tryJob: Job? = null
    private var tryOptions: List<MemeOption> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pipeline = MemePipeline(this)
        if (savedInstanceState == null && !prefs.wizardDone && !prefs.wizardClosed) {
            startActivity(Intent(this, WizardActivity::class.java))
        }
        setContent {
            val tick = generation
            val download by DownloadProgress.flow.collectAsState()
            val draft by tryDraft
            val status by tryStatus
            val previews by tryPreviews
            val state = readState(download.text).let { if (tick >= 0) it else it }
            SettingsScreen(
                state = state,
                onWizard = { startActivity(Intent(this, WizardActivity::class.java)) },
                tryDraft = draft,
                onTryDraft = { tryDraft.value = it },
                onTryMeme = { runTry() },
                tryStatus = status,
                tryPreviews = previews,
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
        if (::pipeline.isInitialized) pipeline.preload()
    }

    override fun onDestroy() {
        tryJob?.cancel()
        scope.cancel()
        if (::pipeline.isInitialized) pipeline.remote.unbind()
        tryOptions.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        super.onDestroy()
    }

    private fun runTry() {
        val text = tryDraft.value.trim()
        if (text.isEmpty()) {
            tryStatus.value = getString(R.string.need_text)
            return
        }
        tryStatus.value = getString(R.string.searching)
        tryJob?.cancel()
        val contextLines = MememAccessibilityService.instance?.recentTexts().orEmpty()
        tryJob = scope.launch {
            val result = withContext(Dispatchers.Default) {
                pipeline.suggest(text, contextLines) { preview ->
                    scope.launch { showTry(preview) }
                }
            }
            showTry(result)
            tryStatus.value = if (result.size >= 3) "Drei Karten" else getString(R.string.models_missing)
        }
    }

    private fun showTry(next: List<MemeOption>) {
        val previous = tryOptions
        tryOptions = next
        tryPreviews.value = next.map { option ->
            option.bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
        }
        previous.filter { old -> next.none { it.bitmap === old.bitmap } }.forEach { option ->
            if (!option.bitmap.isRecycled) option.bitmap.recycle()
        }
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
        val modelsOn = ModelCatalog.ready(this, ModelCatalog.embed) && ModelCatalog.ready(this, ModelCatalog.gemmaCpu)
        val keyboardEnabled = SetupProbe.keyboardEnabled(this)
        val keyboardOn = SetupProbe.keyboardSelected(this)
        val a11yOn = MememAccessibilityService.enabled(this)
        return SetupUi(
            a11y = a11y,
            overlay = prefs.overlay,
            qwertz = prefs.qwertz,
            insert = prefs.insertPreference,
            models = "Embedding $embed, Gemma $cpu",
            download = download,
            hyperos = getString(R.string.hyperos_hint),
            modelsOn = modelsOn,
            keyboardOn = keyboardOn,
            keyboardEnabled = keyboardEnabled,
            a11yOn = a11yOn,
            incomplete = !modelsOn || !keyboardEnabled || !keyboardOn,
        )
    }
}
