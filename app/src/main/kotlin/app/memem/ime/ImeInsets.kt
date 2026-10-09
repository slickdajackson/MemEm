package app.memem.ime

/** Bottom space the keyboard must clear. Covers the 3-button bar and the gesture handle. */
fun keyboardBottomInset(navigationBottom: Int, systemGestureBottom: Int, mandatoryGestureBottom: Int): Int {
    return maxOf(navigationBottom, systemGestureBottom, mandatoryGestureBottom).coerceAtLeast(0)
}
