package app.memem.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardLayoutTest {
    @Test
    fun qwertyPutsYOnTopAndLabelsSpaceEn() {
        val rows = keyboardRows(Board.LETTERS, Script.QWERTY)
        val top = rows[0].keys.map { it.text }
        assertEquals(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"), top)
        assertEquals("1", rows[0].keys.first().hint)
        assertEquals("0", rows[0].keys.last().hint)
        assertEquals("a", rows[1].keys.first().text)
        assertEquals("z", rows[2].keys[1].text)
        assertEquals("EN", spaceLabel(Script.QWERTY))
    }

    @Test
    fun qwertzSwapsOnlyYAndZ() {
        val rows = keyboardRows(Board.LETTERS, Script.QWERTZ)
        assertEquals("z", rows[0].keys[5].text)
        assertEquals("y", rows[2].keys[1].text)
        assertEquals("q", rows[0].keys[0].text)
        assertEquals("DE", spaceLabel(Script.QWERTZ))
    }

    @Test
    fun umlautsFollowShiftAndShiftCycles() {
        val a = keyboardRows(Board.LETTERS, Script.QWERTY)[1].keys[0]
        assertEquals("a", a.text)
        assertEquals("ä", shownLong(a, ShiftState.OFF))
        assertEquals("Ä", shownLong(a, ShiftState.ONCE))
        assertEquals("A", shownText(a, ShiftState.ONCE))
        val s = keyboardRows(Board.LETTERS, Script.QWERTY)[1].keys[1]
        assertEquals("ß", shownLong(s, ShiftState.OFF))
        assertEquals(ShiftState.ONCE, shiftTap(ShiftState.OFF))
        assertEquals(ShiftState.LOCK, shiftTap(ShiftState.ONCE))
        assertEquals(ShiftState.OFF, shiftTap(ShiftState.LOCK))
        assertEquals(ShiftState.OFF, afterLetter(ShiftState.ONCE))
        assertEquals(ShiftState.LOCK, afterLetter(ShiftState.LOCK))
    }

    @Test
    fun bottomRowMatchesGboardOrder() {
        val bottom = keyboardRows(Board.LETTERS, Script.QWERTY).last().keys
        assertEquals(
            listOf(KeyRole.MODE, KeyRole.EMOJI, KeyRole.GLOBE, KeyRole.SPACE, KeyRole.CHAR, KeyRole.ENTER),
            bottom.map { it.role },
        )
        assertEquals("?123", bottom[0].text)
        assertEquals(",", bottom[1].text)
        assertEquals(".", bottom[4].text)
    }
}
