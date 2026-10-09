package app.memem.ui

import android.net.Uri
import android.widget.EditText
import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import app.memem.R
import app.memem.engine.Board
import app.memem.engine.KeyFace
import app.memem.engine.KeyRole
import app.memem.engine.Script
import app.memem.engine.keyboardRows
import app.memem.engine.spaceLabel
import app.memem.engine.InsertPreference

internal val Paper = Color(MemPalette.PAPER)
internal val Cream = Color(MemPalette.CREAM)
internal val Ink = Color(MemPalette.INK)
internal val Yellow = Color(MemPalette.YELLOW)
internal val Blue = Color(MemPalette.BLUE)
internal val Purple = Color(MemPalette.PURPLE)
internal val KeyWhite = Color(MemPalette.KEY)
internal val KeyYellow = Color(MemPalette.KEY_YELLOW)
internal val KeyLine = Color(MemPalette.KEY_LINE)
internal val Display = FontFamily(Font(R.font.anton))
internal val Mono = FontFamily.Monospace

data class SetupUi(
    val a11y: String = "",
    val overlay: Boolean = false,
    val qwertz: Boolean = false,
    val language: String = "en",
    val qualityE4b: Boolean = false,
    val insert: InsertPreference = InsertPreference.CLIPBOARD,
    val models: String = "",
    val download: String = "",
    val modelsOn: Boolean = false,
    val keyboardOn: Boolean = false,
    val keyboardEnabled: Boolean = false,
    val a11yOn: Boolean = false,
    val incomplete: Boolean = false,
)

@Composable
fun SettingsScreen(
    state: SetupUi,
    onWizard: () -> Unit,
    onKeyboard: () -> Unit,
    onA11y: () -> Unit,
    onOverlayPermission: () -> Unit,
    onOverlay: (Boolean) -> Unit,
    onLanguage: (String) -> Unit = {},
    onQuality: (Boolean) -> Unit = {},
    onInsert: (InsertPreference) -> Unit,
    onDownload: () -> Unit,
    onHarness: () -> Unit,
    tryDraft: String = "",
    onTryDraft: (String) -> Unit = {},
    onTryMeme: () -> Unit = {},
    tryStatus: String = "",
    tryPreviews: List<ImageBitmap> = emptyList(),
    tryMarks: List<String> = emptyList(),
    scroll: Boolean = true,
) {
    val base = Modifier.background(Paper).padding(16.dp)
    val modifier = if (scroll) base.fillMaxSize().verticalScroll(rememberScrollState()) else base.fillMaxWidth()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (state.incomplete) {
            StickerCard {
                MonoLabel(stringResource(R.string.incomplete_kicker))
                Headline(stringResource(R.string.incomplete_title))
                androidx.compose.material3.Text(
                    stringResource(R.string.incomplete_body),
                    color = Ink,
                    fontSize = 15.sp,
                )
                StickerButton(stringResource(R.string.continue_setup), Yellow, onClick = onWizard)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painterResource(R.drawable.memem_logo),
                contentDescription = "MemEm",
                modifier = Modifier.size(108.dp),
                contentScale = ContentScale.Fit,
            )
            Column(Modifier.padding(start = 8.dp)) {
                androidx.compose.material3.Text(
                    "MemEm",
                    fontFamily = Display,
                    fontSize = 42.sp,
                    color = Ink,
                )
                MonoLabel(stringResource(R.string.version_sideload))
            }
        }
        androidx.compose.material3.Text(
            stringResource(R.string.tagline),
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            color = Ink,
        )
        StickerCard {
            MonoLabel(stringResource(R.string.status_kicker))
            Headline(stringResource(R.string.status_title))
            StatusLine(stringResource(R.string.status_models), state.modelsOn)
            StatusLine(
                stringResource(R.string.status_keyboard),
                state.keyboardOn,
                when {
                    state.keyboardOn -> stringResource(R.string.keyboard_active)
                    state.keyboardEnabled -> stringResource(R.string.keyboard_enabled_not_current)
                    else -> stringResource(R.string.status_off)
                },
            )
            StatusLine(
                stringResource(R.string.status_a11y),
                state.a11yOn,
                stringResource(if (state.a11yOn) R.string.status_on else R.string.status_off),
            )
        }
        StickerCard {
            MonoLabel(stringResource(R.string.try_kicker))
            Headline(stringResource(R.string.try_cards_title))
            androidx.compose.material3.Text(
                stringResource(R.string.try_cards_body),
                color = Ink,
                fontSize = 15.sp,
            )
            StickerBox(fill = Paper, shadow = 3.dp) {
                androidx.compose.foundation.text.BasicTextField(
                    value = tryDraft,
                    onValueChange = onTryDraft,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = Ink,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink),
                    modifier = Modifier.fillMaxWidth().padding(12.dp).height(64.dp),
                    decorationBox = { inner ->
                        Box {
                            if (tryDraft.isEmpty()) {
                                androidx.compose.material3.Text(
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
            StickerButton(stringResource(R.string.meme), Yellow, onClick = onTryMeme)
            if (tryStatus.isNotBlank()) MonoLabel(tryStatus)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { index ->
                    StickerBox(Modifier.weight(1f), fill = Cream, radius = 12.dp, shadow = 3.dp) {
                        val image = tryPreviews.getOrNull(index)
                        Box(Modifier.fillMaxWidth().height(96.dp)) {
                            if (image != null) {
                                Image(
                                    image,
                                    contentDescription = stringResource(R.string.meme_cd, index + 1),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    MonoLabel("0${index + 1}")
                                }
                            }
                            val mark = tryMarks.getOrNull(index).orEmpty()
                            if (mark.isNotBlank()) {
                                Box(Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                                    MonoLabel(mark)
                                }
                            }
                        }
                    }
                }
            }
        }
        StickerButton(stringResource(R.string.wizard_again), Yellow, onClick = onWizard)
        StickerCard {
            MonoLabel(stringResource(R.string.setup_kicker))
            Headline(stringResource(R.string.setup_title))
            StickerButton(stringResource(R.string.activate_keyboard), Yellow, onClick = onKeyboard)
            StickerButton(stringResource(R.string.open_a11y), Blue, light = true, onClick = onA11y)
            MonoLabel(state.a11y)
            Spacer(Modifier.height(8.dp))
            StickerToggle(stringResource(R.string.overlay_toggle), state.overlay, onOverlay)
            StickerButton(stringResource(R.string.overlay_permission), Purple, light = true, onClick = onOverlayPermission)
        }
        StickerCard {
            MonoLabel(stringResource(R.string.layout_kicker))
            Headline(stringResource(R.string.layout_title))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource(R.string.lang_en), state.language != "de") { onLanguage("en") }
                Choice(stringResource(R.string.lang_de), state.language == "de") { onLanguage("de") }
            }
            MonoLabel(stringResource(if (state.language == "de" || state.qwertz) R.string.layout_de_hint else R.string.layout_en_hint))
            MonoLabel(stringResource(R.string.layout_switch_hint))
        }
        StickerCard {
            MonoLabel(stringResource(R.string.insert_kicker))
            Headline(stringResource(R.string.insert_title))
            Choice(stringResource(R.string.insert_clipboard), state.insert == InsertPreference.CLIPBOARD) {
                onInsert(InsertPreference.CLIPBOARD)
            }
            Choice(stringResource(R.string.insert_commit), state.insert == InsertPreference.COMMIT_CONTENT) {
                onInsert(InsertPreference.COMMIT_CONTENT)
            }
            Choice(stringResource(R.string.insert_auto), state.insert == InsertPreference.AUTO) {
                onInsert(InsertPreference.AUTO)
            }
        }
        StickerCard {
            MonoLabel(stringResource(R.string.models_kicker))
            Headline(stringResource(R.string.models_settings_title))
            StickerButton(stringResource(R.string.load_models), Yellow, onClick = onDownload)
            StickerToggle(stringResource(R.string.quality_e4b), state.qualityE4b, onQuality)
            MonoLabel(stringResource(R.string.quality_hint))
            MonoLabel(stringResource(R.string.quality_sizes))
            MonoLabel(state.models)
            if (state.download.isNotBlank()) MonoLabel(state.download)
        }
        StickerButton(stringResource(R.string.open_harness), Yellow, onClick = onHarness)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun KeyboardArt(qwertz: Boolean, modifier: Modifier = Modifier) {
    val script = if (qwertz) Script.QWERTZ else Script.QWERTY
    val rows = keyboardRows(Board.LETTERS, script)
    Column(
        modifier.background(Paper).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FlatKey(fill = KeyYellow, radius = 12.dp) {
                androidx.compose.material3.Text(
                    "Meme",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                )
            }
            Image(
                painterResource(R.drawable.memem_logo),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            repeat(3) { index ->
                FlatKey(Modifier.weight(1f), fill = KeyWhite, radius = 10.dp) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        MonoLabel("0${index + 1}")
                    }
                }
            }
        }
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (row.sideInset > 0f) Spacer(Modifier.weight(row.sideInset))
                row.keys.forEach { key ->
                    val label = when (key.role) {
                        KeyRole.SHIFT -> "⇧"
                        KeyRole.DELETE -> "⌫"
                        KeyRole.SPACE -> spaceLabel(script)
                        KeyRole.GLOBE -> "🌐"
                        KeyRole.EMOJI -> ","
                        KeyRole.ENTER -> "⏎"
                        else -> key.text
                    }
                    val fill = if (key.face == KeyFace.YELLOW) KeyYellow else KeyWhite
                    FlatKey(
                        Modifier.weight(key.weight),
                        fill = fill,
                        radius = if (key.pill) 20.dp else 6.dp,
                    ) {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.Text(
                                label,
                                color = Ink,
                                fontWeight = FontWeight.Bold,
                                fontSize = if (label.length > 2) 11.sp else 16.sp,
                                fontFamily = if (key.role == KeyRole.SPACE) Mono else FontFamily.Default,
                            )
                        }
                    }
                }
                if (row.sideInset > 0f) Spacer(Modifier.weight(row.sideInset))
            }
        }
    }
}

@Composable
fun HarnessScreen() {
    var log by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<Uri?>(null) }
    Column(
        Modifier.background(Paper).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Headline(stringResource(R.string.harness_title))
        MonoLabel(stringResource(R.string.harness_body))
        val waiting = stringResource(R.string.harness_waiting)
        StickerBox(fill = Cream) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                factory = { context ->
                    EditText(context).apply {
                        hint = context.getString(R.string.harness_hint)
                        textSize = 18f
                        setTextColor(MemPalette.INK)
                        setHintTextColor(MemPalette.HINT)
                        background = null
                        ViewCompat.setOnReceiveContentListener(this, arrayOf("image/png", "image/*")) { _, payload ->
                            val clip = payload.clip
                            var consumed = false
                            for (i in 0 until clip.itemCount) {
                                val uri = clip.getItemAt(i).uri ?: continue
                                image = uri
                                log = context.getString(R.string.harness_got, payload.source)
                                consumed = true
                            }
                            if (consumed) null else payload
                        }
                    }
                },
            )
        }
        StickerBox(fill = Cream) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().height(180.dp).padding(8.dp),
                factory = { ImageView(it) },
                update = { view -> view.setImageURI(image) },
            )
        }
        MonoLabel(log.ifEmpty { waiting })
    }
}

@Composable
private fun StatusLine(
    label: String,
    on: Boolean,
    detail: String = stringResource(if (on) R.string.status_here else R.string.status_missing),
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(label, fontWeight = FontWeight.Bold, color = Ink)
        StickerBox(fill = if (on) Yellow else Cream, radius = 10.dp, shadow = 2.dp, border = 2.dp) {
            androidx.compose.material3.Text(
                detail,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontFamily = Mono,
                fontSize = 12.sp,
                color = Ink,
            )
        }
    }
}

@Composable
internal fun Headline(text: String) {
    androidx.compose.material3.Text(text, fontFamily = Display, fontSize = 28.sp, color = Ink)
    Spacer(Modifier.height(8.dp))
}

@Composable
internal fun MonoLabel(text: String) {
    androidx.compose.material3.Text(
        text,
        fontFamily = Mono,
        fontSize = 12.sp,
        color = Ink,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
internal fun StickerCard(content: @Composable ColumnScope.() -> Unit) {
    StickerBox(fill = Cream) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
internal fun StickerButton(text: String, fill: Color, light: Boolean = false, onClick: () -> Unit) {
    StickerBox(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), fill = fill, onClick = onClick) {
        androidx.compose.material3.Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            color = if (light) Color.White else Ink,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    StickerBox(
        modifier = Modifier.padding(top = 6.dp),
        fill = if (selected) Yellow else Cream,
        shadow = 3.dp,
        onClick = onClick,
    ) {
        androidx.compose.material3.Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = Ink,
            fontFamily = Mono,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun StickerToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        androidx.compose.material3.Text(label, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
        StickerBox(fill = if (checked) Yellow else Cream, shadow = 3.dp, radius = 14.dp, onClick = { onChange(!checked) }) {
            Box(Modifier.size(if (checked) 28.dp else 22.dp).padding(6.dp).background(Ink, RoundedCornerShape(6.dp)))
        }
    }
}

@Composable
private fun FlatKey(
    modifier: Modifier = Modifier,
    fill: Color,
    radius: Dp = 6.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Box(
        modifier.clip(shape).background(fill).border(1.dp, KeyLine, shape),
        content = content,
    )
}

@Composable
internal fun StickerBox(
    modifier: Modifier = Modifier,
    fill: Color = Cream,
    radius: Dp = 16.dp,
    border: Dp = 3.dp,
    shadow: Dp = 5.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    val click = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    }
    Box(modifier.then(click).padding(end = shadow, bottom = shadow)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .clip(shape)
                .background(Ink),
        )
        Box(
            Modifier
                .border(border, Ink, shape)
                .background(fill, shape),
            content = content,
        )
    }
}

@Composable
fun OverlayPreviewCard() {
    Column(Modifier.background(Paper).padding(16.dp)) {
        StickerBox(fill = Yellow, radius = 28.dp, shadow = 4.dp) {
            Image(
                painterResource(R.drawable.memem_logo),
                contentDescription = stringResource(R.string.overlay_toggle),
                modifier = Modifier.size(56.dp).padding(4.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        StickerCard {
            MonoLabel("WHATSAPP")
            Headline("Meme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) {
                    StickerBox(shadow = 3.dp, radius = 12.dp) {
                        Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                            MonoLabel("0${it + 1}")
                        }
                    }
                }
            }
            StickerButton(stringResource(R.string.close), Cream) {}
        }
    }
}
