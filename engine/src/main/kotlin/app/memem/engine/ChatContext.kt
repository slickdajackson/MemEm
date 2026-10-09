package app.memem.engine

import org.json.JSONObject

data class ScreenLine(
    val text: String,
    val viewId: String? = null,
    val top: Int = 0,
    val editable: Boolean = false,
    val description: String? = null,
)

data class InputCandidate(
    val viewId: String?,
    val centerY: Int,
    val editable: Boolean,
    val visible: Boolean,
)

data class WhatsAppProfile(
    val packageName: String,
    val messageTextIds: List<String>,
    val messageInputIds: List<String>,
    val sendButtonIds: List<String>,
    val messageInputBottomFraction: Double,
    val timeRegex: Regex,
    val dateLabelRegexes: List<Regex>,
    val chatStartPatterns: List<Regex>,
    val readMoreTexts: Set<String>,
    val statusIconDescriptions: List<String>,
)

fun defaultWhatsAppProfile(): WhatsAppProfile = WhatsAppProfile(
    packageName = "com.whatsapp",
    messageTextIds = listOf("com.whatsapp:id/message_text"),
    messageInputIds = listOf("com.whatsapp:id/entry"),
    sendButtonIds = listOf("com.whatsapp:id/send"),
    messageInputBottomFraction = 0.65,
    timeRegex = Regex("^\\d{1,2}[:.]\\d{2}(\\s?([AaPp]\\.?[Mm]\\.?))?$"),
    dateLabelRegexes = listOf(
        Regex("(?i)^(heute|gestern|today|yesterday)$"),
        Regex("(?i)^(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag)$"),
    ),
    chatStartPatterns = listOf(
        Regex("(?i)ende-zu-ende"),
        Regex("(?i)end-to-end"),
        Regex("(?i)verschl[uü]sselt"),
    ),
    readMoreTexts = setOf("mehr lesen", "read more"),
    statusIconDescriptions = listOf("Gelesen", "Zugestellt", "Gesendet", "Read", "Delivered", "Sent"),
)

fun parseWhatsAppProfile(json: String): WhatsAppProfile {
    val fallback = defaultWhatsAppProfile()
    val root = JSONObject(json)
    val ids = root.optJSONObject("knownIds") ?: JSONObject()
    val heuristics = root.optJSONObject("heuristics") ?: JSONObject()
    val dates = heuristics.stringList("dateLabelRegexes").mapNotNull { regexOrNull(it) }
    val starts = heuristics.stringList("chatStartPatterns").mapNotNull { regexOrNull(it) }
    val readMore = heuristics.stringList("readMoreTexts").map { it.lowercase() }.toSet()
    val status = heuristics.stringList("statusIconDescriptions")
    return WhatsAppProfile(
        packageName = root.optString("packageName", fallback.packageName).ifBlank { fallback.packageName },
        messageTextIds = ids.stringList("messageText").ifEmpty { fallback.messageTextIds },
        messageInputIds = ids.stringList("messageInput").ifEmpty { fallback.messageInputIds },
        sendButtonIds = ids.stringList("sendButton").ifEmpty { fallback.sendButtonIds },
        messageInputBottomFraction = heuristics.optDouble("messageInputBottomFraction", fallback.messageInputBottomFraction),
        timeRegex = regexOrNull(heuristics.optString("timeRegex")) ?: fallback.timeRegex,
        dateLabelRegexes = dates.ifEmpty { fallback.dateLabelRegexes },
        chatStartPatterns = starts.ifEmpty { fallback.chatStartPatterns },
        readMoreTexts = readMore.ifEmpty { fallback.readMoreTexts },
        statusIconDescriptions = status.ifEmpty { fallback.statusIconDescriptions },
    )
}

/** Visible chat lines, newest last. Drops the draft, times, dates and status labels. */
fun recentMessages(lines: List<ScreenLine>, profile: WhatsAppProfile = defaultWhatsAppProfile(), limit: Int = 8): List<String> {
    val cleaned = lines.map { line ->
        line.copy(text = line.text.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "").trim())
    }.filter { it.text.isNotEmpty() && !it.editable }
    val byId = cleaned.filter { it.viewId != null && it.viewId in profile.messageTextIds }
    val pool = (if (byId.isNotEmpty()) byId else cleaned).filter { !isNoise(it, profile) }
    return pool.sortedBy { it.top }.map { it.text }.takeLast(limit)
}

fun pickInputIndex(candidates: List<InputCandidate>, screenHeight: Int, profile: WhatsAppProfile = defaultWhatsAppProfile()): Int? {
    val editable = candidates.withIndex().filter { it.value.visible && it.value.editable }
    val byId = editable.filter { it.value.viewId in profile.messageInputIds }
    if (byId.isNotEmpty()) return byId.maxBy { it.value.centerY }.index
    val height = screenHeight.coerceAtLeast(1)
    val bottom = editable.filter { it.value.centerY > height * profile.messageInputBottomFraction }
    return bottom.maxByOrNull { it.value.centerY }?.index
}

/** Typed text stays first. Context is capped so the embedding query stays short. */
fun searchText(typed: String, context: List<String>, maxChars: Int = 480): String {
    val head = typed.trim()
    val extra = context.map { it.trim() }.filter { it.isNotEmpty() && it != head }
    if (extra.isEmpty()) return head
    val tail = extra.joinToString(" | ").take(maxChars)
    return if (head.isEmpty()) tail else head + "\n" + tail
}

/**
 * Clipboard is always prepared first. The IME paste runs next.
 * Accessibility paste runs only when the IME paste did not report success.
 * There is no send step.
 */
fun pasteSteps(imeReportedSuccess: Boolean, accessibilityConnected: Boolean): List<String> {
    val steps = mutableListOf("clipboard", "ime")
    if (!imeReportedSuccess && accessibilityConnected) steps += "a11y"
    return steps
}

private fun isNoise(line: ScreenLine, profile: WhatsAppProfile): Boolean {
    val text = line.text.trim()
    if (profile.timeRegex.matches(text)) return true
    if (profile.dateLabelRegexes.any { it.matches(text) }) return true
    if (profile.chatStartPatterns.any { it.containsMatchIn(text) }) return true
    if (text.lowercase() in profile.readMoreTexts) return true
    if (profile.statusIconDescriptions.any { text.equals(it, ignoreCase = true) }) return true
    val description = line.description
    if (description != null && text.length < 24 &&
        profile.statusIconDescriptions.any { description.contains(it, ignoreCase = true) }
    ) {
        return true
    }
    return false
}

private fun regexOrNull(source: String): Regex? {
    if (source.isBlank()) return null
    return try {
        Regex(source)
    } catch (_: Exception) {
        null
    }
}

private fun JSONObject.stringList(name: String): List<String> {
    val array = optJSONArray(name) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val value = array.optString(index)
            if (value.isNotBlank()) add(value)
        }
    }
}
