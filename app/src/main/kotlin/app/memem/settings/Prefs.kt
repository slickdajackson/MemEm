package app.memem.settings

import android.content.Context
import app.memem.engine.InsertPreference

class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("memem", Context.MODE_PRIVATE)

    var insertPreference: InsertPreference
        get() = when (prefs.getString(KEY_INSERT, "clipboard")) {
            "commit" -> InsertPreference.COMMIT_CONTENT
            "auto" -> InsertPreference.AUTO
            else -> InsertPreference.CLIPBOARD
        }
        set(value) {
            val raw = when (value) {
                InsertPreference.COMMIT_CONTENT -> "commit"
                InsertPreference.AUTO -> "auto"
                InsertPreference.CLIPBOARD -> "clipboard"
            }
            prefs.edit().putString(KEY_INSERT, raw).apply()
        }

    var gpu: Boolean
        get() = prefs.getBoolean(KEY_GPU, false)
        set(value) {
            prefs.edit().putBoolean(KEY_GPU, value).apply()
        }

    var overlay: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY, value).apply()
        }

    private companion object {
        const val KEY_INSERT = "insert"
        const val KEY_GPU = "gpu"
        const val KEY_OVERLAY = "overlay"
    }
}
