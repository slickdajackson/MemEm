package app.memem.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memem.R
import app.memem.models.DownloadSnapshot
import kotlinx.coroutines.launch

data class WizardChecks(
    val models: Boolean = false,
    val keyboardEnabled: Boolean = false,
    val keyboardCurrent: Boolean = false,
    val a11y: Boolean = false,
    val battery: Boolean = false,
    val autostartAck: Boolean = false,
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
    onAppInfo: () -> Unit,
    onAutostart: () -> Unit,
    onBattery: () -> Unit,
    onAckAutostart: () -> Unit,
    onFinish: () -> Unit,
    initialPage: Int = 0,
    pinnedPage: Int? = null,
) {
    val pager = rememberPagerState(initialPage = initialPage.coerceIn(0, PAGES - 1)) { PAGES }
    val scope = rememberCoroutineScope()
    var gate by remember { mutableIntStateOf(initialPage.coerceIn(0, PAGES - 1)) }
    fun done(page: Int): Boolean = when (page) {
        0 -> true
        1 -> checks.models
        2 -> checks.keyboardEnabled
        3 -> checks.keyboardCurrent
        4 -> checks.a11y
        5 -> checks.battery && checks.autostartAck
        else -> draft.isNotBlank()
    }
    fun required(page: Int) = page in 1..3
    LaunchedEffect(pager, checks, draft) {
        snapshotFlow { pager.settledPage }.collect { page ->
            if (page <= gate) {
                gate = page
                return@collect
            }
            val stop = (gate until page).firstOrNull { required(it) && !done(it) }
            if (stop != null) {
                pager.scrollToPage(stop)
                gate = stop
            } else {
                gate = page
            }
        }
    }
    fun go(page: Int) {
        scope.launch { pager.animateScrollToPage(page) }
    }
    Column(Modifier.fillMaxSize().background(Paper).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("MEMEM", fontFamily = Display, fontSize = 28.sp, color = Ink)
            MonoLabel("SCHRITT ${(pinnedPage ?: pager.currentPage) + 1} VON $PAGES")
        }
        if (pinnedPage != null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                StepPage(
                    pinnedPage, checks, download, draft, onDraft, onDownload, onEnableKeyboard,
                    onPickKeyboard, onA11y, onAppInfo, onAutostart, onBattery, onAckAutostart, onFinish, ::done, ::required, ::go,
                )
            }
        } else {
            HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    StepPage(
                        page, checks, download, draft, onDraft, onDownload, onEnableKeyboard,
                        onPickKeyboard, onA11y, onAppInfo, onAutostart, onBattery, onAckAutostart, onFinish, ::done, ::required, ::go,
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
    onDraft: (String) -> Unit,
    onDownload: () -> Unit,
    onEnableKeyboard: () -> Unit,
    onPickKeyboard: () -> Unit,
    onA11y: () -> Unit,
    onAppInfo: () -> Unit,
    onAutostart: () -> Unit,
    onBattery: () -> Unit,
    onAckAutostart: () -> Unit,
    onFinish: () -> Unit,
    done: (Int) -> Boolean,
    required: (Int) -> Boolean,
    go: (Int) -> Unit,
) {
    when (page) {
        0 -> WelcomeStep()
        1 -> ModelsStep(checks.models, download, onDownload)
        2 -> EnableStep(checks.keyboardEnabled, onEnableKeyboard)
        3 -> PickStep(checks.keyboardCurrent, onPickKeyboard)
        4 -> A11yStep(checks.a11y, onA11y, onAppInfo)
        5 -> HyperStep(checks, onAutostart, onBattery, onAckAutostart)
        else -> TryStep(draft, onDraft)
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (page > 0) {
            Box(Modifier.weight(1f)) {
                StickerButton("Zurück", Cream) { go(page - 1) }
            }
        }
        val forwardLabel = when {
            page == PAGES - 1 -> "Fertig"
            !done(page) && !required(page) -> "Überspringen"
            else -> "Weiter"
        }
        val allow = when {
            page == PAGES - 1 -> draft.isNotBlank() && checks.models && checks.keyboardEnabled && checks.keyboardCurrent
            required(page) -> done(page)
            else -> true
        }
        if (allow) {
            Box(Modifier.weight(1f)) {
                StickerButton(forwardLabel, Yellow) {
                    if (page == PAGES - 1) onFinish() else go(page + 1)
                }
            }
        }
    }
    if (required(page) && !done(page)) {
        MonoLabel("Weiter gibt es, sobald der Haken da ist.")
    }
    if (page == PAGES - 1 && draft.isNotBlank() && !(checks.models && checks.keyboardEnabled && checks.keyboardCurrent)) {
        MonoLabel("Erst Modelle, Tastatur einschalten und MemEm wählen.")
        StickerButton("Zum offenen Schritt", Blue, light = true) {
            val target = listOf(1, 2, 3).first { !done(it) }
            go(target)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun WelcomeStep() {
    StepCard("WILLKOMMEN", "Drei Memes, kein Senden", done = true) {
        Image(
            painterResource(R.drawable.memem_logo),
            contentDescription = "MemEm",
            modifier = Modifier.size(140.dp).align(Alignment.CenterHorizontally),
            contentScale = ContentScale.Fit,
        )
        Spacer(Modifier.height(8.dp))
        Body(
            "MemEm ist eine Tastatur. Du tippst, drückst Meme, und drei Bilder erscheinen. Eins davon landet im Feld. Gesendet wird nie.",
        )
    }
}

@Composable
private fun ModelsStep(ready: Boolean, download: DownloadSnapshot, onDownload: () -> Unit) {
    StepCard("MODELLE", "Einmal laden, dann lokal", ready) {
        Body("Embedding etwa 157 MB. Gemma etwa 2,6 GB. Am besten im WLAN. Der Download läuft im Hintergrund weiter, auch wenn du die App verlässt.")
        if (!ready) StickerButton(if (download.active) "Läuft schon" else "Herunterladen", Yellow, onClick = onDownload)
        if (download.text.isNotBlank()) MonoLabel(download.text)
        if (download.active || (download.percent in 1..99)) {
            ProgressBar(download.percent)
        }
        if (download.failed) MonoLabel("Teildatei bleibt liegen. Nochmal tippen setzt fort.")
        if (ready) MonoLabel("Beide Dateien sind da. Gemma und Embedding laufen auf der CPU.")
    }
}

@Composable
private fun EnableStep(ready: Boolean, onOpen: () -> Unit) {
    StepCard("TASTATUR", "MemEm einschalten", ready) {
        Body("Der Knopf öffnet Einstellungen, Bildschirmtastaturen. Schalte MemEm ein. Beim Zurückkommen setzt sich der Haken von allein.")
        StickerButton("Bildschirmtastaturen öffnen", Yellow, onClick = onOpen)
    }
}

@Composable
private fun PickStep(ready: Boolean, onOpen: () -> Unit) {
    StepCard("AKTIV", "MemEm auswählen", ready) {
        Body("Wähle MemEm als aktive Tastatur. Derselbe Dialog kommt später bei einem langen Druck auf den Globus.")
        StickerButton("Tastaturauswahl öffnen", Yellow, onClick = onOpen)
    }
}

@Composable
private fun A11yStep(ready: Boolean, onA11y: () -> Unit, onAppInfo: () -> Unit) {
    StepCard("OPTIONAL", "WhatsApp Kontext", ready) {
        Body("Die Bedienungshilfe liest den offenen WhatsApp-Chat für die Suche und fügt das Bild ein, wenn die Tastatur das nicht schafft. Auf HyperOS zuerst eingeschränkte Einstellungen zulassen: App-Infos, Drei-Punkte-Menü.")
        StickerButton("Bedienungshilfe öffnen", Blue, light = true, onClick = onA11y)
        StickerButton("App-Infos für HyperOS", Purple, light = true, onClick = onAppInfo)
        MonoLabel(if (ready) "Bedienungshilfe ist an." else "Ohne diesen Schritt geht die Tastatur trotzdem. Kontext und das extra Einfügen fehlen dann.")
    }
}

@Composable
private fun HyperStep(checks: WizardChecks, onAutostart: () -> Unit, onBattery: () -> Unit, onAck: () -> Unit) {
    val ready = checks.battery && checks.autostartAck
    StepCard("OPTIONAL", "HyperOS wach halten", ready) {
        Body("Xiaomi beendet sonst Download, den schwebenden Punkt und Gemma. Autostart an. Akku auf Keine Einschränkungen.")
        StickerButton("Autostart öffnen", Yellow, onClick = onAutostart)
        StickerButton("Akku öffnen", Yellow, onClick = onBattery)
        StickerButton(if (checks.autostartAck) "Autostart bestätigt" else "Autostart ist an", Cream, onClick = onAck)
        MonoLabel(if (checks.battery) "Akku: keine Einschränkung erkannt." else "Akku: Einschränkung noch da, oder das System meldet sie nicht.")
    }
}

@Composable
private fun TryStep(draft: String, onDraft: (String) -> Unit) {
    StepCard("PROBIEREN", "Kurz tippen", draft.isNotBlank()) {
        Body("Tippe eine Testnachricht. In WhatsApp tippst du den Text und drückst Meme. Darüber erscheinen drei Karten.")
        Body("Globus kurz: vorherige Tastatur. Globus lang: Auswahl aller Tastaturen. Leertaste lang wechselt ebenfalls, ohne den Dialog.")
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
                            Text("Testnachricht", color = Ink.copy(alpha = 0.45f), fontSize = 18.sp)
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
