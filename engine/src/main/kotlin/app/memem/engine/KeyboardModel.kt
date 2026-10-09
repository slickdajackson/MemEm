package app.memem.engine

import java.util.Locale

enum class Script { QWERTY, QWERTZ }

enum class Board { LETTERS, NUMBERS, SYMBOLS }

enum class ShiftState { OFF, ONCE, LOCK }

enum class KeyRole { CHAR, SHIFT, DELETE, MODE, EMOJI, GLOBE, SPACE, ENTER, MORE }

enum class KeyFace { CREAM, YELLOW, BLUE }

data class KeyDef(
    val text: String,
    val insert: String = text,
    val hint: String? = null,
    val longInsert: String? = null,
    val role: KeyRole = KeyRole.CHAR,
    val weight: Float = 1f,
    val pill: Boolean = false,
    val face: KeyFace = KeyFace.CREAM,
)

data class KeyRow(val keys: List<KeyDef>, val sideInset: Float = 0f)

fun shiftTap(state: ShiftState): ShiftState = when (state) {
    ShiftState.OFF -> ShiftState.ONCE
    ShiftState.ONCE -> ShiftState.LOCK
    ShiftState.LOCK -> ShiftState.OFF
}

fun afterLetter(state: ShiftState): ShiftState = if (state == ShiftState.ONCE) ShiftState.OFF else state

fun spaceLabel(script: Script): String = if (script == Script.QWERTY) "EN" else "DE"

fun shownText(key: KeyDef, shift: ShiftState): String {
    if (key.role != KeyRole.CHAR || shift == ShiftState.OFF) return key.text
    return shiftLetters(key.insert)
}

fun shownLong(key: KeyDef, shift: ShiftState): String? {
    val raw = key.longInsert ?: return null
    if (shift == ShiftState.OFF) return raw
    return shiftLetters(raw)
}

fun keyboardRows(board: Board, script: Script): List<KeyRow> = when (board) {
    Board.LETTERS -> letterRows(script)
    Board.NUMBERS -> numberRows()
    Board.SYMBOLS -> symbolRows()
}

private fun letterRows(script: Script): List<KeyRow> {
    val top = "qwertyuiop"
    val mid = "asdfghjkl"
    val bot = "zxcvbnm"
    val hints = "1234567890"
    return listOf(
        KeyRow(top.mapIndexed { index, ch -> letterKey(mapLetter(ch, script), hints[index].toString()) }),
        KeyRow(mid.map { ch -> letterKey(mapLetter(ch, script), null) }, sideInset = 0.5f),
        KeyRow(
            listOf(shiftKey()) + bot.map { ch -> letterKey(mapLetter(ch, script), null) } + deleteKey(),
        ),
        bottomRow("?123"),
    )
}

private fun numberRows(): List<KeyRow> = listOf(
    KeyRow("1234567890".map { charKey(it.toString()) }),
    KeyRow(listOf("@", "#", "€", "&", "_", "-", "+", "(", ")", "/").map { charKey(it) }),
    KeyRow(
        listOf(moreKey("#+=")) + listOf("*", "\"", "'", ":", ";", "!", "?").map { charKey(it) } + deleteKey(),
    ),
    bottomRow("ABC"),
)

private fun symbolRows(): List<KeyRow> = listOf(
    KeyRow(listOf("~", "`", "|", "•", "^", "°", "=", "{", "}", "\\").map { charKey(it) }),
    KeyRow(listOf("£", "¥", "€", "$", "¢", "<", ">", "[", "]", "_").map { charKey(it) }),
    KeyRow(
        listOf(moreKey("123")) + listOf("/", "-", "+", "\"", "'", "¿", "¡").map { charKey(it) } + deleteKey(),
    ),
    bottomRow("ABC"),
)

private fun bottomRow(modeLabel: String) = KeyRow(
    listOf(
        KeyDef(modeLabel, role = KeyRole.MODE, weight = 1.45f, pill = true, face = KeyFace.YELLOW),
        KeyDef(",", role = KeyRole.EMOJI, weight = 1.05f),
        KeyDef("🌐", role = KeyRole.GLOBE, weight = 1.05f),
        KeyDef(" ", role = KeyRole.SPACE, weight = 3.9f),
        KeyDef(".", role = KeyRole.CHAR, weight = 1.05f),
        KeyDef("⏎", role = KeyRole.ENTER, weight = 1.5f, pill = true, face = KeyFace.YELLOW),
    ),
)

private fun letterKey(ch: Char, hint: String?): KeyDef {
    val base = ch.toString()
    return KeyDef(text = base, insert = base, hint = hint, longInsert = umlaut(base))
}

private fun charKey(text: String) = KeyDef(text = text, insert = text)

private fun shiftKey() = KeyDef("shift", role = KeyRole.SHIFT, weight = 1.55f, face = KeyFace.YELLOW)

private fun deleteKey() = KeyDef("delete", role = KeyRole.DELETE, weight = 1.55f, face = KeyFace.YELLOW)

private fun moreKey(label: String) = KeyDef(label, role = KeyRole.MORE, weight = 1.55f, face = KeyFace.YELLOW)

private fun mapLetter(ch: Char, script: Script): Char {
    if (script == Script.QWERTY) return ch
    return when (ch) {
        'y' -> 'z'
        'z' -> 'y'
        else -> ch
    }
}

private fun umlaut(letter: String): String? = when (letter) {
    "a" -> "ä"
    "o" -> "ö"
    "u" -> "ü"
    "s" -> "ß"
    else -> null
}

private fun shiftLetters(raw: String): String {
    if (raw.length == 1 && raw[0].isLetter()) return raw.uppercase(Locale.GERMAN)
    return raw
}
