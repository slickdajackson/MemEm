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
import java.io.File

data class InsertOutcome(
    val step: InsertStep?,
    val reportedSuccess: Boolean,
    val clipboardHint: Boolean,
)

class Inserter(private val context: Context) {
    fun insert(
        input: InputConnection,
        editor: EditorInfo,
        file: File,
        preference: InsertPreference,
    ): InsertOutcome {
        val original = readAll(input)
        val accepts = fieldAcceptsPng(editor.contentMimeTypes)
        val plan = insertPlan(preference, accepts)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        grant(editor.packageName, uri)
        grant("com.whatsapp", uri)
        var clipSet = false
        var pasteOk = false
        var cleared = false
        for (step in plan) {
            if (!cleared) {
                clearField(input, original)
                cleared = true
            }
            val ok = when (step) {
                InsertStep.CLIPBOARD -> {
                    clipSet = putClipboard(uri)
                    pasteOk = clipSet && input.performContextMenuAction(android.R.id.paste)
                    pasteOk
                }
                InsertStep.COMMIT_CONTENT -> commit(input, editor, uri)
                InsertStep.SHARE -> share(uri)
            }
            if (ok) {
                val hint = clipSet && !pasteOk && step != InsertStep.COMMIT_CONTENT
                return InsertOutcome(step, reportedSuccess = true, clipboardHint = hint)
            }
        }
        if (cleared && original.isNotEmpty()) input.commitText(original, 1)
        return InsertOutcome(null, reportedSuccess = false, clipboardHint = clipSet)
    }

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
