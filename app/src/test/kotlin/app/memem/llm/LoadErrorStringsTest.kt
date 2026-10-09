package app.memem.llm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.memem.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS")
class LoadErrorStringsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun englishNamesTheKeyboardsAndTheLoadErrors() {
        assertEquals("English (QWERTY)", context.getString(R.string.subtype_en))
        assertEquals("Deutsch (QWERTZ)", context.getString(R.string.subtype_de))
        assertEquals("Not loaded", context.getString(R.string.error_not_loaded))
        assertEquals("Gemma is not loaded", context.getString(R.string.error_gemma_not_loaded))
        assertEquals("Embedding is not loaded", context.getString(R.string.error_embedding_not_loaded))
    }

    @Test
    @Config(qualifiers = "de-rDE")
    fun germanKeepsTheKeyboardNamesAndTheOldLoadErrors() {
        assertEquals("English (QWERTY)", context.getString(R.string.subtype_en))
        assertEquals("Deutsch (QWERTZ)", context.getString(R.string.subtype_de))
        assertEquals("nicht geladen", context.getString(R.string.error_not_loaded))
        assertEquals("gemma nicht geladen", context.getString(R.string.error_gemma_not_loaded))
        assertEquals("embedding nicht geladen", context.getString(R.string.error_embedding_not_loaded))
    }
}
