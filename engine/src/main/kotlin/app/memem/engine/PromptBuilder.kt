package app.memem.engine

data class Brief(
    val id: String,
    val name: String,
    val boxes: Int,
    val meaning: String,
    val examples: List<List<String>>,
    val roles: String = "",
)

data class BuiltPrompt(val system: String, val user: String) {
    val characters: Int = system.length + user.length
    val approxTokens: Int = characters / 4
}

const val GEMMA_MAX_OUTPUT_TOKENS = 160

private const val SYSTEM =
    "Du schreibst Chat-Memes. Formuliere NICHT wörtlich, schreibe im Stil der Beispiele um. " +
        "Kopiere die Nachricht nicht und kopiere keine Beispielzeile. " +
        "Schreibe sie für jede genannte Vorlage so um, wie man sie genau für dieses Meme sagen würde. " +
        "Die Kernaussage bleibt, ohne neue Fakten. Die Ausgabesprache ist die Sprache der Nachricht. " +
        "Englisch nur, wenn die Vorlage eine feste englische Phrase braucht, etwa It's a trap! " +
        "Kurz und pointiert. Pro Vorlage genau die Felder z1, z2 und so weiter, eine Zeile je Feld, jede Zeile anders und nicht leer. " +
        "Keine Zeile endet auf Artikel, Präposition, Verschmelzung (am, im, zum) oder Konjunktion. " +
        "Antworte nur als JSON. Schlüssel sind die Vorlagen-Ids, Werte sind Objekte mit z1, z2, ..."

fun retryHint(reason: String): String {
    val why = reason.ifBlank { "unbrauchbar" }
    return "Die letzte Fassung dieser Vorlage war unbrauchbar ($why). " +
        "Schreibe nur diese eine Vorlage neu, in der Sprache der Nachricht, ohne eine Beispielzeile zu kopieren."
}

private const val TOKEN_BUDGET = 1000
private const val CHAR_BUDGET = TOKEN_BUDGET * 4
private const val MAX_TEMPLATES = 3

/** One prompt for the three chosen templates. Extra candidates are dropped so the call stays short. */
fun buildPrompt(
    message: String,
    candidates: List<Brief>,
    context: List<String> = emptyList(),
    hint: String = "",
): BuiltPrompt {
    val selected = candidates.take(MAX_TEMPLATES)
    var exampleTake = 5
    var meaningLimit = 180
    var lineLimit = 72
    var ctx = context.map { clip(it, 120) }.filter { it.isNotEmpty() }.take(4)
    var user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint)
    if (over(user) && meaningLimit > 100) {
        meaningLimit = 100
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint)
    }
    if (over(user) && exampleTake > 3) {
        exampleTake = 3
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint)
    }
    if (over(user) && ctx.isNotEmpty()) {
        ctx = emptyList()
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint)
    }
    if (over(user)) {
        meaningLimit = 60
        lineLimit = 48
        exampleTake = 3
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint)
    }
    return BuiltPrompt(SYSTEM, user)
}

fun memeBrief(id: String, name: String, boxes: Int, meaning: String, examples: List<List<String>>): Brief =
    Brief(id, name, boxes, meaning, examples.take(5), fieldRoles(id, boxes))

fun memeCandidate(id: String, boxes: Int, style: String, name: String, examples: List<List<String>>): Candidate =
    Candidate(
        id = id,
        boxes = boxes,
        style = style,
        example = examples.firstOrNull().orEmpty(),
        name = name,
        examples = examples.take(5),
    )

fun fieldRoles(id: String, boxes: Int): String {
    val role = when (id) {
        "drake" -> "oben abgelehnt, unten bevorzugt"
        "cmm" -> "eine steile These"
        "fine" -> "oben die Lage, unten die ruhige Reaktion, oft Alles gut"
        "db" -> "links das Vernachlässigte, Mitte die Person, rechts die Ablenkung"
        "ds" -> "zwei schwere Optionen und die Reaktion"
        else -> "Rollen wie in den Beispielen"
    }
    return "$boxes Felder: $role"
}

private fun over(user: String) = SYSTEM.length + user.length > CHAR_BUDGET

private fun renderUser(
    message: String,
    candidates: List<Brief>,
    exampleTake: Int,
    meaningLimit: Int,
    lineLimit: Int,
    context: List<String>,
    hint: String,
): String {
    val body = StringBuilder()
    body.append("Nachricht: ").append(message.trim()).append('\n')
    if (context.isNotEmpty()) {
        body.append("Kontext, letzte Nachrichten, nur zum Verstehen, nicht übernehmen:\n")
        context.forEach { line -> body.append("- ").append(line).append('\n') }
    }
    body.append("Vorlagen:\n")
    candidates.forEachIndexed { index, brief ->
        body.append(index + 1).append(". ").append(brief.id)
        body.append(" | ").append(brief.name)
        val roles = brief.roles.ifBlank { fieldRoles(brief.id, brief.boxes) }
        body.append(" | ").append(roles)
        val meaning = clipSentence(brief.meaning, meaningLimit)
        if (meaning.isNotBlank()) body.append(" | ").append(meaning)
        body.append('\n')
        val examples = brief.examples.take(exampleTake).filter { lines -> lines.any { it.isNotBlank() } }
        examples.forEach { lines ->
            body.append("- ")
            body.append(lines.joinToString(" / ") { clip(it, lineLimit).ifBlank { "…" } })
            body.append('\n')
        }
    }
    body.append("Formuliere NICHT wörtlich, schreibe im Stil der Beispiele um.\n")
    body.append("Ausgabesprache ist die Sprache der Nachricht. Kopiere keine Beispielzeile.\n")
    if (hint.isNotBlank()) body.append(hint.trim()).append('\n')
    body.append("Antworte exakt in dieser Form, mit den echten Ids und z1, z2, ...:\n")
    body.append(exampleJson(candidates))
    body.append('\n')
    return body.toString()
}

private fun exampleJson(candidates: List<Brief>): String {
    val body = candidates.joinToString(",") { brief ->
        val fields = (1..brief.boxes.coerceAtLeast(1)).joinToString(",") { number -> "\"z$number\":\"...\"" }
        "\"${brief.id}\":{$fields}"
    }
    return "{$body}"
}

private fun clip(text: String, limit: Int): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    if (flat.length <= limit) return flat
    val window = flat.take(limit)
    val space = window.lastIndexOf(' ')
    return if (space >= 8) window.substring(0, space).trim() else window.trim()
}

/** Cuts a template description at the last sentence end inside the limit, else at a word. */
private fun clipSentence(text: String, limit: Int): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    if (flat.length <= limit) return flat
    val window = flat.take(limit)
    val sentence = listOf(window.lastIndexOf('.'), window.lastIndexOf('!'), window.lastIndexOf('?')).max()
    if (sentence >= 24) return window.substring(0, sentence + 1).trim()
    val space = window.lastIndexOf(' ')
    if (space >= 8) return window.substring(0, space).trim()
    return window.trim()
}
