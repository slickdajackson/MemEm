package app.memem.engine

enum class InsertPreference { CLIPBOARD, COMMIT_CONTENT, AUTO }

enum class InsertStep { CLIPBOARD, COMMIT_CONTENT, SHARE }

/**
 * Clipboard is the default. Commit-content is the second path. Share is only the last fallback.
 * AUTO tries commit-content first when the editor advertises image/png.
 */
fun insertPlan(preference: InsertPreference, fieldAcceptsPng: Boolean): List<InsertStep> {
    return when (preference) {
        InsertPreference.CLIPBOARD -> listOf(
            InsertStep.CLIPBOARD,
            InsertStep.COMMIT_CONTENT,
            InsertStep.SHARE,
        )
        InsertPreference.COMMIT_CONTENT -> if (fieldAcceptsPng) {
            listOf(InsertStep.COMMIT_CONTENT, InsertStep.CLIPBOARD, InsertStep.SHARE)
        } else {
            listOf(InsertStep.CLIPBOARD, InsertStep.SHARE)
        }
        InsertPreference.AUTO -> if (fieldAcceptsPng) {
            listOf(InsertStep.COMMIT_CONTENT, InsertStep.CLIPBOARD, InsertStep.SHARE)
        } else {
            listOf(InsertStep.CLIPBOARD, InsertStep.COMMIT_CONTENT, InsertStep.SHARE)
        }
    }
}

fun fieldAcceptsPng(mimeTypes: Array<String>?): Boolean {
    if (mimeTypes == null) return false
    return mimeTypes.any { mime ->
        val m = mime.lowercase()
        m == "image/png" || m == "image/*" || m == "*/*"
    }
}
