package app.memem.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.memem.engine.InputCandidate
import app.memem.engine.ScreenLine
import app.memem.engine.WhatsAppProfile
import app.memem.engine.defaultWhatsAppProfile
import app.memem.engine.parseWhatsAppProfile
import app.memem.engine.pickInputIndex
import app.memem.engine.recentMessages
import java.util.ArrayDeque

/**
 * Reads the open WhatsApp window and pastes into its input field.
 * Taken from ChatLens ChatAccessibilityService and ReplyInserter: only the target package,
 * known field ids with a bottom-of-screen fallback, ACTION_SET_TEXT and ACTION_PASTE.
 * There is no send click and no gesture navigation.
 */
class MememAccessibilityService : AccessibilityService() {
    private val profile: WhatsAppProfile by lazy { loadProfile() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastPackage = event?.packageName?.toString()
    }

    override fun onInterrupt() {}

    fun recentTexts(): List<String> {
        val root = liveRoot() ?: return emptyList()
        return try {
            val lines = ArrayList<ScreenLine>()
            walk(root) { node ->
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@walk
                val rect = Rect()
                node.getBoundsInScreen(rect)
                lines.add(
                    ScreenLine(
                        text = text,
                        viewId = node.viewIdResourceName,
                        top = rect.top,
                        editable = node.isEditable,
                        description = node.contentDescription?.toString(),
                    ),
                )
            }
            recentMessages(lines, profile)
        } finally {
            root.recycle()
        }
    }

    fun draftText(): String {
        val root = liveRoot() ?: return ""
        return try {
            val field = findInput(root) ?: return ""
            try {
                field.text?.toString().orEmpty()
            } finally {
                field.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * Clears the draft, focuses the field and pastes the clipboard image.
     * When [restoreOnFailure] is set, the previous draft is written back if the paste reports failure.
     */
    fun pasteImage(restoreOnFailure: Boolean): Boolean {
        val root = liveRoot() ?: return false
        return try {
            val field = findInput(root) ?: return false
            try {
                val previous = field.text?.toString().orEmpty()
                if (previous.isNotEmpty()) {
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs(""))
                }
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (!ok && restoreOnFailure && previous.isNotEmpty()) {
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, textArgs(previous))
                }
                ok
            } finally {
                field.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val copies = ArrayList<AccessibilityNodeInfo>()
        val candidates = ArrayList<InputCandidate>()
        walk(root) { node ->
            if (!node.isEditable || !node.isVisibleToUser) return@walk
            val rect = Rect()
            node.getBoundsInScreen(rect)
            copies.add(AccessibilityNodeInfo.obtain(node))
            candidates.add(
                InputCandidate(
                    viewId = node.viewIdResourceName,
                    centerY = (rect.top + rect.bottom) / 2,
                    editable = true,
                    visible = true,
                ),
            )
        }
        val index = pickInputIndex(candidates, resources.displayMetrics.heightPixels, profile)
        copies.forEachIndexed { i, node -> if (i != index) node.recycle() }
        return if (index == null) null else copies[index]
    }

    /**
     * Active window only when it is WhatsApp. If the active window is MemEm (keyboard or overlay),
     * look for the WhatsApp application window underneath. Foreign apps yield null.
     */
    private fun liveRoot(): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        val pkg = active?.packageName?.toString()
        if (active != null && pkg == profile.packageName) return active
        if (active != null && pkg != packageName) {
            active.recycle()
            return null
        }
        active?.recycle()
        val listed = runCatching { windows }.getOrNull().orEmpty()
        for (window in listed) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = window.root ?: continue
            if (root.packageName?.toString() == profile.packageName) return root
            root.recycle()
        }
        return null
    }

    private fun loadProfile(): WhatsAppProfile {
        return try {
            assets.open("profiles/whatsapp.json").bufferedReader().use { parseWhatsAppProfile(it.readText()) }
        } catch (_: Exception) {
            defaultWhatsAppProfile()
        }
    }

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<Frame>()
        stack.add(Frame(root, owned = false, depth = 0))
        var seen = 0
        while (stack.isNotEmpty() && seen < 2500) {
            val frame = stack.removeLast()
            seen += 1
            visit(frame.node)
            if (frame.depth < 50) {
                for (index in frame.node.childCount - 1 downTo 0) {
                    val child = frame.node.getChild(index) ?: continue
                    stack.add(Frame(child, owned = true, depth = frame.depth + 1))
                }
            }
            if (frame.owned) frame.node.recycle()
        }
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (frame.owned) frame.node.recycle()
        }
    }

    private data class Frame(val node: AccessibilityNodeInfo, val owned: Boolean, val depth: Int)

    companion object {
        @Volatile
        var instance: MememAccessibilityService? = null
            private set

        @Volatile
        var lastPackage: String? = null
            private set

        fun enabled(context: Context): Boolean {
            val setting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val name = ComponentName(context, MememAccessibilityService::class.java).flattenToString()
            return setting.split(':').any { it.equals(name, ignoreCase = true) }
        }

        private fun textArgs(value: String): Bundle =
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
    }
}
