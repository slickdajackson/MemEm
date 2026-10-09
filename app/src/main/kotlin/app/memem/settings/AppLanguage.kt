package app.memem.settings

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

object AppLanguage {
    const val EN = "en"
    const val DE = "de"

    fun tagForLanguage(language: String?): String {
        val normalized = language?.lowercase()?.replace('_', '-').orEmpty()
        return if (normalized.startsWith(DE)) DE else EN
    }

    fun phoneTag(): String {
        val locales = Resources.getSystem().configuration.locales
        val language = if (locales.isEmpty) "" else locales[0].language
        return tagForLanguage(language)
    }

    fun subtypeIsGerman(subtype: InputMethodSubtype?): Boolean {
        if (subtype == null) return false
        val tag = if (Build.VERSION.SDK_INT >= 24) subtype.languageTag else subtype.locale
        return tagForLanguage(tag.ifBlank { subtype.locale }) == DE
    }

    /** First launch follows the phone. A saved choice, or an older QWERTZ switch, wins after that. */
    fun ensure(context: Context) {
        val prefs = Prefs(context)
        val tag = prefs.languageTag ?: if (prefs.storedQwertz()) DE else phoneTag()
        apply(context, tag)
    }

    fun apply(context: Context, tag: String) {
        val normalized = tagForLanguage(tag)
        Prefs(context).setLanguage(normalized)
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.isEmpty || current[0]?.language != normalized) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(normalized))
        }
    }

    /** Both MemEm subtypes stay available, so the globe can toggle English and Deutsch. */
    fun enableBothSubtypes(context: Context) {
        if (Build.VERSION.SDK_INT < 34) return
        val imm = context.getSystemService(InputMethodManager::class.java) ?: return
        val info = imm.inputMethodList.firstOrNull { it.packageName == context.packageName } ?: return
        if (info.subtypeCount < 2) return
        val hashes = IntArray(info.subtypeCount) { info.getSubtypeAt(it).hashCode() }
        runCatching { imm.setExplicitlyEnabledInputMethodSubtypes(info.id, hashes) }
    }
}
