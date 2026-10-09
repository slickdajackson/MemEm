package app.memem.insert

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import app.memem.engine.InsertPreference
import app.memem.engine.InsertStep
import app.memem.engine.fieldAcceptsPng
import app.memem.engine.insertPlan
import app.memem.engine.pasteSteps
import java.io.File

data class InsertOutcome(
    val step: InsertStep?,
    val reportedSuccess: Boolean,
    val clipboardHint: Boolean,
    val pasteChannel: String? = null,
)

class Inserter(private val context: Context) {
    fun insert(
        input: InputConnection,
        editor: EditorInfo,
        file: File,
        preference: InsertPreference,
        accessibilityPaste: (() -> Boolean)? = null,
    ): InsertOutcome {
        val original = readAll(input)
        val accepts = fieldAcceptsPng(editor.contentMimeTypes)
        val plan = insertPlan(preference, accepts)
        val uri = contentUri(file)
        grant(editor.packageName, uri)
        grant("com.whatsapp", uri)
        var clipSet = false
        var pasteOk = false
        var channel: String? = null
        var cleared = false
        for (step in plan) {
            if (!cleared) {
                clearField(input, original)
                cleared = true
            }
            val ok = when (step) {
                InsertStep.CLIPBOARD -> {
                    clipSet = putClipboard(uri)
                    val imeOk = clipSet && input.performContextMenuAction(android.R.id.paste)
                    val steps = pasteSteps(imeOk, accessibilityPaste != null)
                    if (imeOk) {
                        pasteOk = true
                        channel = "ime"
                        true
                    } else if (clipSet && steps.contains("a11y") && accessibilityPaste!!()) {
                        pasteOk = true
                        channel = "a11y"
                        true
                    } else {
                        false
                    }
                }
                InsertStep.COMMIT_CONTENT -> commit(input, editor, uri)
                InsertStep.SHARE -> share(uri)
            }
            if (ok) {
                val hint = clipSet && !pasteOk && step != InsertStep.COMMIT_CONTENT
                return InsertOutcome(step, reportedSuccess = true, clipboardHint = hint, pasteChannel = channel)
            }
        }
        if (cleared && original.isNotEmpty()) input.commitText(original, 1)
        return InsertOutcome(null, reportedSuccess = false, clipboardHint = clipSet, pasteChannel = channel)
    }

    /** Overlay path: no input connection. Clipboard, then accessibility paste, then share. */
    fun insertViaAccessibility(file: File, paste: () -> Boolean): InsertOutcome {
        val uri = contentUri(file)
        grant("com.whatsapp", uri)
        val clipSet = putClipboard(uri)
        if (clipSet && paste()) {
            return InsertOutcome(InsertStep.CLIPBOARD, reportedSuccess = true, clipboardHint = false, pasteChannel = "a11y")
        }
        if (share(uri)) {
            return InsertOutcome(InsertStep.SHARE, reportedSuccess = true, clipboardHint = false, pasteChannel = null)
        }
        return InsertOutcome(null, reportedSuccess = false, clipboardHint = clipSet, pasteChannel = null)
    }

    private fun contentUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun putClipboard(uri: Uri): Boolean {
        val clip = ClipData.newUri(context.contentResolver, "MemEm", uri)
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
        manager.setPrimaryClip(clip)
        return true
    }

    private fun commit(input: InputConnection, editor: EditorInfo, uri: Uri): Boolean {
        val info = InputContentInfoCompat(uri, ClipData.newUri(context.contentResolver, "MemEm", uri).description, null)
        return try {
            InputConnectionCompat.commitContent(
                input,
                editor,
                info,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null,
            )
        } catch (_: Exception) {
            false
        }
    }

    private fun share(uri: Uri): Boolean {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "MemEm", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.whatsapp")
        }
        return try {
            context.startActivity(send)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun grant(pkg: String?, uri: Uri) {
        if (pkg.isNullOrBlank()) return
        try {
            context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun readAll(input: InputConnection): String {
        val before = input.getTextBeforeCursor(4000, 0)?.toString().orEmpty()
        val after = input.getTextAfterCursor(4000, 0)?.toString().orEmpty()
        return before + after
    }

    private fun clearField(input: InputConnection, original: String) {
        if (original.isEmpty()) return
        val before = input.getTextBeforeCursor(4000, 0)?.length ?: original.length
        val after = input.getTextAfterCursor(4000, 0)?.length ?: 0
        input.deleteSurroundingText(before, after)
    }
}
