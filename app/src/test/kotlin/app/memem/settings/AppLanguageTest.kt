package app.memem.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLanguageTest {
    @Test
    fun germanTagsStayGermanAndEverythingElseIsEnglish() {
        assertEquals(AppLanguage.DE, AppLanguage.tagForLanguage("de"))
        assertEquals(AppLanguage.DE, AppLanguage.tagForLanguage("de_DE"))
        assertEquals(AppLanguage.DE, AppLanguage.tagForLanguage("de-AT"))
        assertEquals(AppLanguage.EN, AppLanguage.tagForLanguage("en"))
        assertEquals(AppLanguage.EN, AppLanguage.tagForLanguage("en-US"))
        assertEquals(AppLanguage.EN, AppLanguage.tagForLanguage("fr"))
        assertEquals(AppLanguage.EN, AppLanguage.tagForLanguage(null))
    }

    @Test
    fun choosingALanguageSetsTheKeyboard() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = Prefs(context)
        prefs.setLanguage(AppLanguage.DE)
        assertEquals(AppLanguage.DE, prefs.languageTag)
        assertTrue(prefs.qwertz)
        prefs.setLanguage(AppLanguage.EN)
        assertEquals(AppLanguage.EN, prefs.languageTag)
        assertFalse(prefs.qwertz)
    }

    @Test
    fun anOlderQwertzSwitchBecomesGerman() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("memem", Context.MODE_PRIVATE)
            .edit()
            .remove("language")
            .putBoolean("qwertz", true)
            .commit()
        AppLanguage.ensure(context)
        val prefs = Prefs(context)
        assertEquals(AppLanguage.DE, prefs.languageTag)
        assertTrue(prefs.qwertz)
    }
}
