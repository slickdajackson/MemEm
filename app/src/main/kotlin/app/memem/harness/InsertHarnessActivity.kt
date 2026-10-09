package app.memem.harness

import android.content.ClipData
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import app.memem.R

/** Editor that accepts image/png from the keyboard via commitContent and clipboard paste. */
class InsertHarnessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_harness)
        val field = findViewById<EditText>(R.id.field)
        val image = findViewById<ImageView>(R.id.received)
        val log = findViewById<TextView>(R.id.log)
        ViewCompat.setOnReceiveContentListener(field, arrayOf("image/png", "image/*")) { _, payload ->
            val clip = payload.clip
            var consumed: ClipData? = null
            for (i in 0 until clip.itemCount) {
                val uri: Uri = clip.getItemAt(i).uri ?: continue
                image.setImageURI(uri)
                log.append("Bild empfangen, Quelle ${payload.source}\n")
                consumed = clip
            }
            if (consumed != null) null else payload
        }
    }
}
