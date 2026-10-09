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

    /** Off by default. E2B stays the model until this is on and the E4B file is on disk. */
    var qualityE4b: Boolean
        get() = prefs.getBoolean(KEY_QUALITY, false)
        set(value) {
            prefs.edit().putBoolean(KEY_QUALITY, value).apply()
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

    /** Set when the user finishes or closes the wizard, so it does not return on every launch. */
    var wizardClosed: Boolean
        get() = prefs.getBoolean(KEY_WIZARD_CLOSED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WIZARD_CLOSED, value).apply()
        }

    var autostartAck: Boolean
        get() = prefs.getBoolean(KEY_AUTOSTART, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTOSTART, value).apply()
        }

    private companion object {
        const val KEY_INSERT = "insert"
        const val KEY_QWERTZ = "qwertz"
        const val KEY_QUALITY = "quality_e4b"
        const val KEY_OVERLAY = "overlay"
        const val KEY_WIZARD = "wizard_done"
        const val KEY_WIZARD_CLOSED = "wizard_closed"
        const val KEY_AUTOSTART = "autostart_ack"
    }
}
