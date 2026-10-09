package app.memem.debug

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Local protocol on disk. Logcat stays free of message text. */
class DebugLog(context: Context) {
    private val file = File(context.applicationContext.filesDir, "debug/pipeline.jsonl")

    fun event(fields: Map<String, Any?>) {
        val obj = JSONObject()
        obj.put("t", System.currentTimeMillis())
        for ((key, value) in fields) {
            if (value != null) obj.put(key, value)
        }
        synchronized(this) {
            file.parentFile?.mkdirs()
            file.appendText(obj.toString() + "\n")
        }
    }
}
