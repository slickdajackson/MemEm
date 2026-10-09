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

    var qwertz: Boolean
        get() = prefs.getBoolean(KEY_QWERTZ, false)
        set(value) {
            prefs.edit().putBoolean(KEY_QWERTZ, value).apply()
        }

    var overlay: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY, value).apply()
        }

    var wizardDone: Boolean
        get() = prefs.getBoolean(KEY_WIZARD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WIZARD, value).apply()
        }

    var autostartAck: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()
        }

    private companion object {
        const val KEY_INSERT = "insert"
        const val KEY_QWERTZ = "qwertz"
        const val KEY_OVERLAY = "overlay"
        const val KEY_WIZARD = "wizard_done"
        const val KEY_AUTOSTART = "autostart_ack"
    }
}
