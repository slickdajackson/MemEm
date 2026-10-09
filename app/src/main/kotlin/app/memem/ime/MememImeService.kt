package app.memem.ime

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import app.memem.R
import app.memem.a11y.MememAccessibilityService
import app.memem.debug.DebugLog
import app.memem.engine.InsertStep
import app.memem.insert.Inserter
import app.memem.models.ModelCatalog
import app.memem.pipeline.MemeOption
import app.memem.pipeline.MemePipeline
import app.memem.settings.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MememImeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var pipeline: MemePipeline
    private lateinit var panel: KeyboardPanel
    private val inserter by lazy { Inserter(this) }
    private val prefs by lazy { Prefs(this) }
    private val log by lazy { DebugLog(this) }
    private var options: List<MemeOption> = emptyList()
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        pipeline = MemePipeline(this)
    }

    override fun onCreateInputView(): View {
        panel = KeyboardPanel(this, object : KeyboardPanel.Host {
            override fun commit(text: String) {
                currentInputConnection?.commitText(text, 1)
            }

            override fun delete() {
                currentInputConnection?.deleteSurroundingText(1, 0)
            }

            override fun enter() {
                currentInputConnection?.commitText("\n", 1)
            }

            override fun switchKeyboard() {
                val imm = getSystemService(InputMethodManager::class.java)
                val token = window?.window?.attributes?.token ?: return
                imm.switchToNextInputMethod(token, false)
            }

            override fun meme() {
                runMeme()
            }

            override fun pick(index: Int) {
                insertOption(index)
            }
        })
        return panel
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        pipeline.preload()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        if (::pipeline.isInitialized) pipeline.remote.unbind()
        recycle(options)
        super.onDestroy()
    }

    private fun runMeme() {
        val text = readField()
        if (text.isBlank()) {
            panel.setStatus(getString(R.string.need_text))
            return
        }
        panel.setStatus(getString(R.string.searching))
        job?.cancel()
        val contextLines = MememAccessibilityService.instance?.recentTexts().orEmpty()
        job = scope.launch {
            val result = withContext(Dispatchers.Default) {
                pipeline.suggest(text, contextLines) { preview ->
                    main.post { show(preview) }
                }
            }
            show(result)
            val gemma = if (prefs.gpu) ModelCatalog.gemmaGpu else ModelCatalog.gemmaCpu
            panel.setStatus(
                if (ModelCatalog.ready(this@MememImeService, gemma)) "" else getString(R.string.models_missing),
            )
        }
    }

    private fun show(next: List<MemeOption>) {
        if (!::panel.isInitialized) return
        val previous = options
        options = next
        panel.showPreviews(next.map { it.bitmap })
        previous.filter { old -> next.none { it.bitmap === old.bitmap } }.forEach { recycleOne(it) }
    }

    private fun insertOption(index: Int) {
        val option = options.getOrNull(index) ?: return
        val input = currentInputConnection ?: return
        val editor = currentInputEditorInfo ?: return
        val outcome = inserter.insert(input, editor, option.file, prefs.insertPreference) {
            MememAccessibilityService.instance?.pasteImage(restoreOnFailure = false) == true
        }
        log.event(
            mapOf(
                "kind" to "insert",
                "via" to "ime",
                "step" to outcome.step?.name,
                "ok" to outcome.reportedSuccess,
                "hint" to outcome.clipboardHint,
                "channel" to outcome.pasteChannel,
                "template" to option.template.id,
            ),
        )
        panel.setStatus(
            when {
                outcome.pasteChannel == "a11y" && outcome.reportedSuccess -> getString(R.string.a11y_pasted)
                outcome.clipboardHint -> getString(R.string.clipboard_hint)
                outcome.step == InsertStep.SHARE && outcome.reportedSuccess -> getString(R.string.shared)
                outcome.reportedSuccess -> getString(R.string.inserted)
                else -> getString(R.string.insert_failed)
            },
        )
    }

    private fun readField(): String {
        val input = currentInputConnection ?: return ""
        val request = ExtractedTextRequest().apply { hintMaxChars = 4000 }
        val extracted = input.getExtractedText(request, 0)
        if (!extracted?.text.isNullOrEmpty()) return extracted.text.toString()
        return input.getTextBeforeCursor(4000, 0)?.toString().orEmpty() +
            input.getTextAfterCursor(4000, 0)?.toString().orEmpty()
    }

    private fun recycle(list: List<MemeOption>) {
        list.forEach { recycleOne(it) }
    }

    private fun recycleOne(option: MemeOption) {
        if (!option.bitmap.isRecycled) option.bitmap.recycle()
    }
}
