package app.memem.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.memem.models.DownloadSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WizardFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val titles = listOf(
        "Drei Memes, kein Senden",
        "Einmal laden, dann lokal",
        "MemEm einschalten",
        "MemEm auswählen",
        "WhatsApp Kontext",
        "Kurz tippen",
    )

    private val openHint =
        "Dieser Schritt ist noch offen. Weiter geht trotzdem, du kannst ihn später nachholen."

    @Test
    fun buttonsCrossEveryPageWhenChecksAreMissing() {
        var finished = 0
        show(WizardChecks(), onFinish = { finished += 1 })
        titles.forEachIndexed { index, title ->
            assertPage(index, title, open = index > 0)
            compose.onNodeWithTag("wizard-forward").performClick()
            compose.waitForIdle()
        }
        assertEquals(1, finished)
    }

    @Test
    fun swipesCrossEveryPageWhenChecksAreMissing() {
        show(WizardChecks())
        titles.forEachIndexed { index, title ->
            assertPage(index, title, open = index > 0)
            if (index == titles.lastIndex) return@forEachIndexed
            compose.onNodeWithTag("wizard-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onNodeWithText("SCHRITT ${index + 2} VON 6").assertIsDisplayed()
            compose.onNodeWithText(titles[index + 1]).assertIsDisplayed()
        }
    }

    @Test
    fun doneStepsKeepWeiterAndTheSwipeAgrees() {
        show(
            WizardChecks(
                models = true,
                keyboardEnabled = true,
                keyboardCurrent = true,
                a11y = true,
            ),
            draft = "hallo",
        )
        titles.forEachIndexed { index, title ->
            compose.onNodeWithText("SCHRITT ${index + 1} VON 6").assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
            val label = if (index == titles.lastIndex) "Fertig, los geht's" else "Weiter"
            compose.onNodeWithText(label, substring = false).assertIsDisplayed()
            if (index == titles.lastIndex) return@forEachIndexed
            compose.onNodeWithTag("wizard-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        compose.onNodeWithText("SCHRITT 6 VON 6").assertIsDisplayed()
        compose.onNodeWithText("Kurz tippen").assertIsDisplayed()
    }

    private fun assertPage(index: Int, title: String, open: Boolean) {
        compose.onNodeWithText("SCHRITT ${index + 1} VON 6").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        if (open) {
            compose.onNodeWithText("Trotzdem weiter / Später", substring = false).assertIsDisplayed()
            compose.onNodeWithText(openHint).assertIsDisplayed()
        } else {
            compose.onNodeWithText("Weiter", substring = false).assertIsDisplayed()
        }
    }

    private fun show(checks: WizardChecks, draft: String = "", onFinish: () -> Unit = {}) {
        compose.setContent {
            WizardScreen(
                checks = checks,
                download = DownloadSnapshot(),
                draft = draft,
                onDraft = {},
                onDownload = {},
                onEnableKeyboard = {},
                onPickKeyboard = {},
                onA11y = {},
                onFinish = onFinish,
                onClose = {},
            )
        }
        compose.waitForIdle()
    }
}
