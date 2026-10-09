package app.memem.harness

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import app.memem.ui.HarnessScreen

/** Editor that accepts image/png from the keyboard via commitContent and clipboard paste. */
class InsertHarnessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HarnessScreen() }
    }
}
