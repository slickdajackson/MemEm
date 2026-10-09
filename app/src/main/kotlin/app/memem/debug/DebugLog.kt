package app.memem.debug

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
            val kept = file.readLines().takeLast(40)
            file.writeText(kept.joinToString("\n").let { if (it.isEmpty()) it else it + "\n" })
        }
    }

    fun recentText(limit: Int = 10): String {
        if (!file.isFile) return ""
        val lines = synchronized(this) { file.readLines() }
        return lines.mapNotNull { formatEvent(it) }
            .filter { it.isNotBlank() }
            .takeLast(limit)
            .asReversed()
            .joinToString("\n\n")
    }

    private fun formatEvent(line: String): String? {
        val obj = try {
            JSONObject(line)
        } catch (_: Exception) {
            return null
        }
        if (obj.optString("kind") != "suggest") return null
        val stamp = Instant.ofEpochMilli(obj.optLong("t")).atZone(ZoneId.systemDefault()).format(CLOCK)
        val loaded = if (obj.optBoolean("gemmaLoaded")) "geladen" else "nicht geladen"
        val latency = obj.optLong("gemmaMs")
        val error = obj.optString("error").ifBlank { obj.optString("gemmaError") }
        val reasons = obj.optString("reasons")
        val raw = obj.optString("raw")
        val text = obj.optString("text")
        return buildString {
            append(stamp)
            append("\nText: ")
            append(text)
            append("\nGemma: ")
            append(loaded)
            append("\nLatenz: ")
            append(latency)
            append(" ms")
            if (error.isNotBlank()) {
                append("\nFehler: ")
                append(error)
            }
            if (reasons.isNotBlank()) {
                append("\nKarten: ")
                append(reasons)
            }
            if (raw.isNotBlank()) {
                append("\nRoh: ")
                append(raw)
            }
        }
    }

    private companion object {
        val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
