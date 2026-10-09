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

    /** Null until the user or the first launch picks English or Deutsch. */
    var languageTag: String?
        get() = when (prefs.getString(KEY_LANGUAGE, null)) {
            AppLanguage.DE -> AppLanguage.DE
            AppLanguage.EN -> AppLanguage.EN
            else -> null
        }
        set(value) {
            if (value == null) prefs.edit().remove(KEY_LANGUAGE).apply() else setLanguage(value)
        }

    fun storedQwertz(): Boolean = prefs.getBoolean(KEY_QWERTZ, false)

    fun setLanguage(tag: String) {
        val normalized = if (tag == AppLanguage.DE) AppLanguage.DE else AppLanguage.EN
        prefs.edit()
            .putString(KEY_LANGUAGE, normalized)
            .putBoolean(KEY_QWERTZ, normalized == AppLanguage.DE)
            .apply()
    }

    var qwertz: Boolean
        get() = when (languageTag) {
            AppLanguage.DE -> true
            AppLanguage.EN -> false
            else -> storedQwertz()
        }
        set(value) {
            setLanguage(if (value) AppLanguage.DE else AppLanguage.EN)
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

    private companion object {
        const val KEY_INSERT = "insert"
        const val KEY_LANGUAGE = "language"
        const val KEY_QWERTZ = "qwertz"
        const val KEY_QUALITY = "quality_e4b"
        const val KEY_OVERLAY = "overlay"
        const val KEY_WIZARD = "wizard_done"
        const val KEY_WIZARD_CLOSED = "wizard_closed"
    }
}
