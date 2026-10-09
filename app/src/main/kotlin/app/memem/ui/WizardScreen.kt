package app.memem.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memem.R
import app.memem.models.DownloadSnapshot
import app.memem.settings.AppLanguage
import kotlinx.coroutines.launch

data class WizardChecks(
    val models: Boolean = false,
    val keyboardEnabled: Boolean = false,
    val keyboardCurrent: Boolean = false,
    val a11y: Boolean = false,
)

private const val PAGES = 7

@Composable
fun WizardScreen(
    checks: WizardChecks,
    download: DownloadSnapshot,
    draft: String,
    onDraft: (String) -> Unit,
    onDownload: () -> Unit,
    onEnableKeyboard: () -> Unit,
    onPickKeyboard: () -> Unit,
    onA11y: () -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit,
    language: String = AppLanguage.EN,
    onLanguage: (String) -> Unit = {},
    initialPage: Int = 0,
    pinnedPage: Int? = null,
) {
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, PAGES - 1)) { PAGES }
    val scope = rememberCoroutineScope()
    val openHint = stringResource(R.string.open_hint)
    fun done(page: Int): Boolean = when (page) {
        0, 1 -> true
        2 -> checks.models
        3 -> checks.keyboardEnabled
        4 -> checks.keyboardCurrent
        5 -> checks.a11y
        else -> draft.isNotBlank()
    }
    fun go(page: Int) {
        scope.launch { pager.animateScrollToPage(page.coerceIn(0, PAGES - 1)) }
    }
    val shown = pinnedPage ?: pager.currentPage
    BackHandler {
        if (shown <= 0) onClose() else go(shown - 1)
    }
    Column(Modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MEMEM", fontFamily = Display, fontSize = 28.sp, color = Ink)
                MonoLabel(stringResource(R.string.wizard_step, shown + 1, PAGES))
            }
            StickerBox(fill = Cream, radius = 12.dp, shadow = 3.dp, onClick = onClose) {
                Text(
                    stringResource(R.string.close),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Ink,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
        if (pinnedPage != null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                StepPage(
                    pinnedPage, checks, download, draft, language, onDraft, onDownload, onEnableKeyboard,
                    onPickKeyboard, onA11y, onLanguage, onFinish, ::done, ::go, openHint,
                )
            }
        } else {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("wizard-pager"),
            ) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    StepPage(
                        page, checks, download, draft, language, onDraft, onDownload, onEnableKeyboard,
                        onPickKeyboard, onA11y, onLanguage, onFinish, ::done, ::go, openHint,
                    )
                }
            }
        }
        Dots(pinnedPage ?: pager.currentPage, List(PAGES) { done(it) })
    }
}

@Composable
private fun StepPage(
    page: Int,
    checks: WizardChecks,
    download: DownloadSnapshot,
    draft: String,
    language: String,
    onDraft: (String) -> Unit,
    onDownload: () -> Unit,
    onEnableKeyboard: () -> Unit,
    onPickKeyboard: () -> Unit,
    onA11y: () -> Unit,
    onLanguage: (String) -> Unit,
    onFinish: () -> Unit,
    done: (Int) -> Boolean,
    go: (Int) -> Unit,
    openHint: String,
) {
    when (page) {
        0 -> LanguageStep(language, onLanguage)
        1 -> WelcomeStep()
        2 -> ModelsStep(checks.models, download, onDownload)
        3 -> EnableStep(checks.keyboardEnabled, onEnableKeyboard)
        4 -> PickStep(checks.keyboardCurrent, onPickKeyboard)
        5 -> A11yStep(checks.a11y, onA11y)
        else -> TryStep(draft, onDraft)
    }
    Spacer(Modifier.height(8.dp))
    if (!done(page)) MonoLabel(openHint)
    if (page == PAGES - 1) {
        Box(Modifier.testTag("wizard-forward")) {
            val label = stringResource(if (done(page)) R.string.finish else R.string.next_anyway)
            StickerButton(label, Yellow, onClick = onFinish)
        }
        StickerButton(stringResource(R.string.back), Cream) { go(page - 1) }
        MonoLabel(stringResource(R.string.close_note))
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (page > 0) {
                Box(Modifier.weight(1f)) {
                    StickerButton(stringResource(R.string.back), Cream) { go(page - 1) }
                }
            }
            Box(Modifier.weight(1f).testTag("wizard-forward")) {
                val label = stringResource(if (done(page)) R.string.next else R.string.next_anyway)
                StickerButton(label, Yellow) { go(page + 1) }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun LanguageStep(language: String, onLanguage: (String) -> Unit) {
    StepCard(stringResource(R.string.lang_kicker), stringResource(R.string.lang_title), done = true) {
        Body(stringResource(R.string.lang_body))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                Choice(stringResource(R.string.lang_en), language != AppLanguage.DE) { onLanguage(AppLanguage.EN) }
            }
            Box(Modifier.weight(1f)) {
                Choice(stringResource(R.string.lang_de), language == AppLanguage.DE) { onLanguage(AppLanguage.DE) }
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    StepCard(stringResource(R.string.welcome_kicker), stringResource(R.string.welcome_title), done = true) {
        Image(
            painterResource(R.drawable.memem_logo),
            contentDescription = "MemEm",
            modifier = Modifier.size(140.dp).align(Alignment.CenterHorizontally),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(8.dp))
        Body(stringResource(R.string.welcome_body))
    }
}

@Composable
private fun ModelsStep(ready: Boolean, download: DownloadSnapshot, onDownload: () -> Unit) {
    StepCard(stringResource(R.string.models_kicker), stringResource(R.string.models_title), ready) {
        Body(stringResource(R.string.models_body))
        if (!ready) {
            StickerButton(
                stringResource(if (download.active) R.string.download_running else R.string.download),
                Yellow,
                onClick = onDownload,
            )
        }
        if (download.text.isNotBlank()) MonoLabel(download.text)
        if (download.active || (download.percent in 1..99)) {
            ProgressBar(download.percent)
        }
        if (download.failed) MonoLabel(stringResource(R.string.download_resume_hint))
        if (ready) MonoLabel(stringResource(R.string.models_both_ready))
    }
}

@Composable
private fun EnableStep(ready: Boolean, onOpen: () -> Unit) {
    StepCard(stringResource(R.string.keyboard_kicker), stringResource(R.string.keyboard_title), ready) {
        Body(stringResource(R.string.keyboard_body))
        StickerButton(stringResource(R.string.open_ime_settings), Yellow, onClick = onOpen)
    }
}

@Composable
private fun PickStep(ready: Boolean, onOpen: () -> Unit) {
    StepCard(stringResource(R.string.active_kicker), stringResource(R.string.active_title), ready) {
        Body(stringResource(R.string.active_body))
        StickerButton(stringResource(R.string.open_picker), Yellow, onClick = onOpen)
    }
}

@Composable
private fun A11yStep(ready: Boolean, onA11y: () -> Unit) {
    StepCard(stringResource(R.string.a11y_kicker), stringResource(R.string.a11y_title), ready) {
        Body(stringResource(R.string.a11y_body))
        StickerButton(stringResource(R.string.open_a11y), Blue, light = true, onClick = onA11y)
        MonoLabel(stringResource(if (ready) R.string.a11y_on_short else R.string.a11y_skip_hint))
    }
}

@Composable
private fun TryStep(draft: String, onDraft: (String) -> Unit) {
    StepCard(stringResource(R.string.try_kicker), stringResource(R.string.try_title), draft.isNotBlank()) {
        Body(stringResource(R.string.try_body))
        Body(stringResource(R.string.try_globe))
        StickerBox(fill = Paper, shadow = 3.dp) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                textStyle = TextStyle(color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold),
                cursorBrush = SolidColor(Ink),
                modifier = Modifier.fillMaxWidth().padding(12.dp).height(72.dp),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                stringResource(R.string.test_hint),
                                color = Ink.copy(alpha = 0.45f),
                                fontSize = 18.sp,
                            )
                        }
                        inner()
                    }
                },
            )
        }
    }
}

@Composable
private fun StepCard(kicker: String, title: String, done: Boolean, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Spacer(Modifier.height(8.dp))
    StickerCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(kicker)
            if (done) {
                StickerBox(fill = Yellow, radius = 10.dp, shadow = 2.dp, border = 2.dp) {
                    Text(
                        "OK",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontFamily = Mono,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        Headline(title)
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ProgressBar(percent: Int) {
    val fraction = (percent.coerceIn(0, 100)) / 100f
    StickerBox(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), fill = Paper, radius = 10.dp, shadow = 3.dp, border = 2.dp) {
        Box(Modifier.fillMaxWidth().height(16.dp)) {
            Box(Modifier.fillMaxWidth(fraction).height(16.dp).background(Yellow))
        }
    }
}

@Composable
private fun Dots(index: Int, done: List<Boolean>) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
        done.forEachIndexed { i, ready ->
            val fill = when {
                i == index -> Yellow
                ready -> Ink
                else -> Cream
            }
            StickerBox(
                modifier = Modifier.padding(horizontal = 4.dp),
                fill = fill,
                radius = 8.dp,
                shadow = 2.dp,
                border = 2.dp,
            ) {
                Box(Modifier.size(if (i == index) 14.dp else 10.dp))
            }
        }
    }
}
