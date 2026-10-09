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
    private val tryMarks = mutableStateOf<List<String>>(emptyList())
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
            val marks by tryMarks
            val state = readState(download.text).let { if (tick >= 0) it else it }
            SettingsScreen(
                state = state,
                onWizard = { startActivity(Intent(this, WizardActivity::class.java)) },
                tryDraft = draft,
                onTryDraft = { tryDraft.value = it },
                onTryMeme = { runTry() },
                tryStatus = status,
                tryPreviews = previews,
                tryMarks = marks,
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
                onQuality = { checked ->
                    prefs.qualityE4b = checked
                    if (checked && !ModelCatalog.ready(this, ModelCatalog.gemmaE4b)) {
                        startDownload(e4b = true)
                    }
                    if (::pipeline.isInitialized) pipeline.preload()
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
            generation += 1
            tryStatus.value = if (result.any { it.fromModel }) "KI-Fassung" else "Wörtliche Fassung"
        }
    }

    private fun showTry(next: List<MemeOption>) {
        val previous = tryOptions
        tryOptions = next
        tryPreviews.value = next.map { option ->
            option.bitmap.copy(Bitmap.Config.ARGB_8888, false).asImageBitmap()
        }
            tryMarks.value = next.map { app.memem.pipeline.memeMark(it.fromModel, it.reason) }
        previous.filter { old -> next.none { it.bitmap === old.bitmap } }.forEach { option ->
            if (!option.bitmap.isRecycled) option.bitmap.recycle()
        }
    }

    private fun startDownload(e4b: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        val intent = Intent(this, ModelDownloadService::class.java)
        if (e4b) intent.putExtra(ModelDownloadService.EXTRA_WHICH, "e4b")
        ContextCompat.startForegroundService(this, intent)
        generation += 1
    }

    private fun readState(download: String): SetupUi {
        val embed = if (ModelCatalog.ready(this, ModelCatalog.embed)) "da" else "fehlt"
        val cpu = if (ModelCatalog.ready(this, ModelCatalog.gemmaCpu)) "da" else "fehlt"
        val e4b = when {
            !prefs.qualityE4b -> "aus"
            ModelCatalog.ready(this, ModelCatalog.gemmaE4b) -> "da"
            else -> "fehlt, E2B bleibt"
        }
        val a11y = when {
            MememAccessibilityService.instance != null -> "Bedienungshilfe aktiv, liest nur WhatsApp."
            MememAccessibilityService.enabled(this) -> "Bedienungshilfe eingeschaltet, Dienst gerade nicht verbunden."
            else -> "Bedienungshilfe aus."
        }
        val modelsOn = ModelCatalog.ready(this, ModelCatalog.embed) && ModelCatalog.ready(this, ModelCatalog.gemmaCpu)
        val keyboardEnabled = SetupProbe.keyboardEnabled(this)
        val keyboardOn = SetupProbe.keyboardSelected(this)
        val a11yOn = MememAccessibilityService.enabled(this)
        return SetupUi(
            a11y = a11y,
            overlay = prefs.overlay,
            qwertz = prefs.qwertz,
            qualityE4b = prefs.qualityE4b,
            insert = prefs.insertPreference,
            models = "Embedding $embed, E2B $cpu, E4B $e4b",
            download = download,
            modelsOn = modelsOn,
            keyboardOn = keyboardOn,
            keyboardEnabled = keyboardEnabled,
            a11yOn = a11yOn,
            incomplete = !modelsOn || !keyboardEnabled || !keyboardOn,
        )
    }
}
