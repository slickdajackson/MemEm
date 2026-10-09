package app.memem.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class ImeInsetsTest {
    @Test
    fun threeButtonNavUsesTheBar() {
        assertEquals(144, keyboardBottomInset(navigationBottom = 144, systemGestureBottom = 0, mandatoryGestureBottom = 0))
    }

    @Test
    fun gestureNavClearsTheHandle() {
        assertEquals(126, keyboardBottomInset(navigationBottom = 63, systemGestureBottom = 126, mandatoryGestureBottom = 84))
    }

    @Test
    fun noBarsMeansNoPadding() {
        assertEquals(0, keyboardBottomInset(0, 0, 0))
    }
}
