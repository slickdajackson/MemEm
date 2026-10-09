package app.memem.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.memem.models.DownloadSnapshot
import app.memem.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-rUS-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WizardFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val titles = listOf(
        "English or Deutsch",
        "Three memes, no sending",
        "Download once, then local",
        "Turn MemEm on",
        "Choose MemEm",
        "WhatsApp context",
        "Type a line",
    )

    private val openHint = "This step is still open. You can continue and come back later."

    @Test
    fun buttonsCrossEveryPageWhenChecksAreMissing() {
        var finished = 0
        show(WizardChecks(), onFinish = { finished += 1 })
        titles.forEachIndexed { index, title ->
            assertPage(index, title, open = index > 1)
            compose.onNodeWithTag("wizard-forward").performClick()
            compose.waitForIdle()
        }
        assertEquals(1, finished)
    }

    @Test
    fun swipesCrossEveryPageWhenChecksAreMissing() {
        show(WizardChecks())
        titles.forEachIndexed { index, title ->
            assertPage(index, title, open = index > 1)
            if (index == titles.lastIndex) return@forEachIndexed
            compose.onNodeWithTag("wizard-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
            compose.onNodeWithText("STEP ${index + 2} OF 7").assertIsDisplayed()
            compose.onNodeWithText(titles[index + 1]).assertIsDisplayed()
        }
    }

    @Test
    fun doneStepsKeepNextAndTheSwipeAgrees() {
        show(
            WizardChecks(
                models = true,
                keyboardEnabled = true,
                keyboardCurrent = true,
                a11y = true,
            ),
            draft = "hello",
        )
        titles.forEachIndexed { index, title ->
            compose.onNodeWithText("STEP ${index + 1} OF 7").assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
            val label = if (index == titles.lastIndex) "Done, let's go" else "Next"
            compose.onNodeWithText(label, substring = false).assertIsDisplayed()
            if (index == titles.lastIndex) return@forEachIndexed
            compose.onNodeWithTag("wizard-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        compose.onNodeWithText("STEP 7 OF 7").assertIsDisplayed()
        compose.onNodeWithText("Type a line").assertIsDisplayed()
    }

    private fun assertPage(index: Int, title: String, open: Boolean) {
        compose.onNodeWithText("STEP ${index + 1} OF 7").assertIsDisplayed()
        compose.onNodeWithText(title).assertIsDisplayed()
        if (open) {
            compose.onNodeWithText("Continue anyway / Later", substring = false).assertIsDisplayed()
            compose.onNodeWithText(openHint).assertIsDisplayed()
        } else {
            compose.onNodeWithText("Next", substring = false).assertIsDisplayed()
        }
    }

    private fun show(checks: WizardChecks, draft: String = "", onFinish: () -> Unit = {}) {
        compose.setContent {
            WizardScreen(
                checks = checks,
                download = DownloadSnapshot(),
                draft = draft,
                language = AppLanguage.EN,
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "de-rDE-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WizardFlowGermanTest {
    @get:Rule
    val compose = createComposeRule()

    private val titles = listOf(
        "English oder Deutsch",
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
    fun buttonsCrossEveryPageInGerman() {
        var finished = 0
        show(WizardChecks(), onFinish = { finished += 1 })
        titles.forEachIndexed { index, title ->
            compose.onNodeWithText("SCHRITT ${index + 1} VON 7").assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
            if (index > 1) {
                compose.onNodeWithText("Trotzdem weiter / Später", substring = false).assertIsDisplayed()
                compose.onNodeWithText(openHint).assertIsDisplayed()
            } else {
                compose.onNodeWithText("Weiter", substring = false).assertIsDisplayed()
            }
            compose.onNodeWithTag("wizard-forward").performClick()
            compose.waitForIdle()
        }
        assertEquals(1, finished)
    }

    @Test
    fun swipesCrossEveryPageInGerman() {
        show(WizardChecks(models = true, keyboardEnabled = true, keyboardCurrent = true, a11y = true), draft = "hallo")
        titles.forEachIndexed { index, title ->
            compose.onNodeWithText("SCHRITT ${index + 1} VON 7").assertIsDisplayed()
            compose.onNodeWithText(title).assertIsDisplayed()
            val label = if (index == titles.lastIndex) "Fertig, los geht's" else "Weiter"
            compose.onNodeWithText(label, substring = false).assertIsDisplayed()
            if (index == titles.lastIndex) return@forEachIndexed
            compose.onNodeWithTag("wizard-pager").performTouchInput { swipeLeft() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
    }

    private fun show(checks: WizardChecks, draft: String = "", onFinish: () -> Unit = {}) {
        compose.setContent {
            WizardScreen(
                checks = checks,
                download = DownloadSnapshot(),
                draft = draft,
                language = AppLanguage.DE,
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
