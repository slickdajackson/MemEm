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

private const val SYSTEM =
    "Du schreibst Chat-Memes. Kopiere die Nachricht nicht wörtlich. " +
        "Schreibe sie für jede der 3 Vorlagen so um, wie man sie genau für dieses Meme sagen würde. " +
        "Die Kernaussage bleibt, ohne neue Fakten. Deutsch, außer die Vorlage lebt von festen englischen Phrasen. " +
        "Kurz und pointiert. Pro Vorlage genau so viele Zeilen wie Felder, jede Zeile anders und nicht leer. " +
        "Keine Zeile endet auf Artikel, Präposition, Verschmelzung (am, im, zum) oder Konjunktion. " +
        "Antworte nur als JSON {\"memes\":[{\"template\":\"<id>\",\"lines\":[\"...\"]}]}."

private const val TOKEN_BUDGET = 1000
private const val CHAR_BUDGET = TOKEN_BUDGET * 4
private const val MAX_TEMPLATES = 3

/** One prompt for the three chosen templates. Extra candidates are dropped so the call stays short. */
fun buildPrompt(message: String, candidates: List<Brief>, context: List<String> = emptyList()): BuiltPrompt {
    val selected = candidates.take(MAX_TEMPLATES)
    var exampleTake = 5
    var meaningLimit = 180
    var lineLimit = 72
    var ctx = context.map { clip(it, 120) }.filter { it.isNotEmpty() }.take(4)
    var user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx)
    if (over(user) && meaningLimit > 100) {
        meaningLimit = 100
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx)
    }
    if (over(user) && exampleTake > 3) {
        exampleTake = 3
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx)
    }
    if (over(user) && ctx.isNotEmpty()) {
        ctx = emptyList()
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx)
    }
    if (over(user)) {
        meaningLimit = 60
        lineLimit = 48
        exampleTake = 3
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx)
    }
    return BuiltPrompt(SYSTEM, user)
}

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
        val meaning = clip(brief.meaning, meaningLimit)
        if (meaning.isNotBlank()) body.append(" | ").append(meaning)
        body.append('\n')
        val examples = brief.examples.take(exampleTake).filter { lines -> lines.any { it.isNotBlank() } }
        examples.forEach { lines ->
            body.append("- ")
            body.append(lines.joinToString(" / ") { clip(it, lineLimit).ifBlank { "…" } })
            body.append('\n')
        }
    }
    return body.toString()
}

private fun clip(text: String, limit: Int): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    return if (flat.length <= limit) flat else flat.take(limit).trimEnd()
}
