package app.memem.engine

data class Brief(
    val id: String,
    val name: String,
    val boxes: Int,
    val meaning: String,
    val examples: List<List<String>>,
)

data class BuiltPrompt(val system: String, val user: String) {
    val characters: Int = system.length + user.length
    val approxTokens: Int = characters / 4
}

private const val SYSTEM =
    "Du schreibst Chat-Memes. Waehle genau 3 verschiedene Vorlagen aus den Kandidaten und schreibe die Zeilen. " +
        "Wortreihenfolge der Nachricht bleibt. Zeilen von oben nach unten ergeben den Satz, ohne Woerter zu verschraenken. " +
        "Keine Zeile endet auf Artikel oder Praeposition. " +
        "Jede Zeile hoechstens 8 Woerter. Keine erfundenen Fakten. Die Witzstruktur der Vorlage bleibt. " +
        "Antworte nur als JSON {\"memes\":[{\"template\":\"<id>\",\"lines\":[\"...\"]}]}."

private const val TOKEN_BUDGET = 700
private const val CHAR_BUDGET = TOKEN_BUDGET * 4

/** Shrinks candidate text until the prompt stays near 700 tokens. Context is dropped first. */
fun buildPrompt(message: String, candidates: List<Brief>, context: List<String> = emptyList()): BuiltPrompt {
    var meanings = candidates.map { it.meaning }
    var examples = candidates.map { it.examples.take(2) }
    var ctx = context.map { clip(it, 160) }.filter { it.isNotEmpty() }.take(8)
    var user = renderUser(message, candidates, meanings, examples, ctx)
    if (SYSTEM.length + user.length > CHAR_BUDGET) {
        ctx = emptyList()
        user = renderUser(message, candidates, meanings, examples, ctx)
    }
    if (SYSTEM.length + user.length > CHAR_BUDGET) {
        examples = examples.map { it.take(1) }
        user = renderUser(message, candidates, meanings, examples, ctx)
    }
    if (SYSTEM.length + user.length > CHAR_BUDGET) {
        meanings = meanings.map { clip(it, 90) }
        user = renderUser(message, candidates, meanings, examples, ctx)
    }
    if (SYSTEM.length + user.length > CHAR_BUDGET) {
        examples = List(candidates.size) { emptyList() }
        meanings = meanings.map { clip(it, 60) }
        user = renderUser(message, candidates, meanings, examples, ctx)
    }
    return BuiltPrompt(SYSTEM, user)
}

private fun renderUser(
    message: String,
    candidates: List<Brief>,
    meanings: List<String>,
    examples: List<List<List<String>>>,
    context: List<String>,
): String {
    val body = StringBuilder()
    body.append("Nachricht: ").append(message.trim()).append('\n')
    if (context.isNotEmpty()) {
        body.append("Kontext, letzte Nachrichten, nur zur Wahl der Vorlage:\n")
        context.forEach { line -> body.append("- ").append(line).append('\n') }
    }
    body.append("Kandidaten:\n")
    candidates.forEachIndexed { index, brief ->
        body.append(index + 1).append(". ").append(brief.id)
        body.append(" | ").append(brief.name)
        body.append(" | ").append(brief.boxes).append(" Felder")
        val meaning = meanings[index]
        if (meaning.isNotBlank()) body.append(" | ").append(meaning)
        val ex = examples[index]
        if (ex.isNotEmpty()) {
            body.append(" | Bsp: ")
            body.append(ex.joinToString("; ") { lines -> lines.joinToString(" / ") })
        }
        body.append('\n')
    }
    return body.toString()
}

private fun clip(text: String, limit: Int): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= limit) flat else flat.take(limit).trimEnd()
}
