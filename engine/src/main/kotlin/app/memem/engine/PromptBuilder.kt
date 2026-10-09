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
const val GEMMA_TEMPERATURE = 0.4
const val FOLLOW_UP_TEMPERATURE = 0.8
const val GEMMA_REPETITION_PENALTY = 1.2f

/** Streaming chunks are sometimes the full text so far, sometimes only the new piece. */
fun absorbModelText(previous: String, incoming: String): String {
    if (incoming.isEmpty()) return previous
    if (previous.isEmpty() || incoming.startsWith(previous)) return incoming
    if (previous.endsWith(incoming)) return previous
    return previous + incoming
}

private const val SYSTEM =
    "You write chat memes. Do not copy the message. Rewrite it in the style of the examples. " +
        "Keep the core meaning and do not add facts. The caption language matches the message. " +
        "A template's own catchphrase may stay in its original language. Do not borrow a catchphrase from a different template. " +
        "Short and pointed. For each template, fields z1, z2, and so on, one line per field, each line different and not empty. " +
        "No line ends on an article, a preposition, or a conjunction. " +
        "Do not put a slash in a field. One field is one line. " +
        "Reply with JSON only. Keys are the template ids. Values are objects with z1, z2, ..."

fun retryHint(reason: String, language: String = ""): String = retryHint(listOf(reason), language)

fun retryHint(reasons: List<String>, language: String = ""): String {
    val lang = language.ifBlank { "?" }
    val why = reasons.map { it.ifBlank { "unusable" } }.distinct().joinToString(", ")
    return if (language == "de") {
        "Die letzten Zeilen wurden verworfen ($why). Schreibe auf ${languageNameDe(lang)}. " +
            "Kopiere weder die Nachricht noch eine Beispielzeile."
    } else {
        "The last lines were rejected ($why). Write in ${languageName(lang)}. " +
            "Do not copy the message or an example line."
    }
}

fun reasonText(reason: String, language: String): String {
    val german = language == "de"
    return when (reason) {
        "woertlich" -> if (german) {
            "Die Zeile wiederholt die Nachricht. Schreib eine neue Einleitung und eine eigene Pointe."
        } else {
            "The line repeats the message. Write a new setup and keep an original punchline."
        }
        "sprache" -> if (german) {
            "Falsche Sprache. Schreibe auf ${languageNameDe(language)}."
        } else {
            "Wrong language. Write in ${languageName(language)}."
        }
        "kopie" -> if (german) {
            "Das kopiert ein Beispiel oder die Catchphrase einer anderen Vorlage. Schreib eine neue Zeile."
        } else {
            "That copies an example or another template's catchphrase. Write a new line."
        }
        "fremd" -> if (german) {
            "Das passt nicht zur Nachricht. Behalte die Bedeutung."
        } else {
            "That does not fit the message. Keep the meaning."
        }
        "doppelt" -> if (german) {
            "Dieselbe Caption steht schon auf einer Karte, oder die Zeilen wiederholen sich. Schreib etwas anderes."
        } else {
            "That caption is already on another card, or the lines repeat. Write something else."
        }
        "leer" -> if (german) {
            "Eine Zeile war leer. Fülle jedes Feld."
        } else {
            "A line was empty. Fill every field."
        }
        "sinnlos" -> if (german) {
            "Eine Zeile war unbrauchbar. Schreib einen echten Satz."
        } else {
            "A line was unusable. Write a real phrase."
        }
        "abgebrochen" -> if (german) {
            "Eine Zeile ist abgeschnitten. Beende den Satz."
        } else {
            "A line was cut off. Finish the phrase."
        }
        "fehlt" -> if (german) {
            "Die Vorlage fehlt. Antworte mit ihren Zeilen."
        } else {
            "The template was missing. Reply with its lines."
        }
        "name" -> if (german) {
            "Der Vorlagenname steht in der Caption. Schreib eine eigene Zeile."
        } else {
            "The template name is in the caption. Write a new line."
        }
        else -> if (german) {
            "Die Zeilen wurden verworfen. Schreibe auf ${languageNameDe(language)}."
        } else {
            "The lines were rejected. Write in ${languageName(language)}."
        }
    }
}

fun languageNameDe(code: String): String = when (code) {
    "de" -> "Deutsch"
    "en" -> "Englisch"
    "fr" -> "Französisch"
    "es" -> "Spanisch"
    "it" -> "Italienisch"
    "nl" -> "Niederländisch"
    "pl" -> "Polnisch"
    "pt" -> "Portugiesisch"
    "tr" -> "Türkisch"
    else -> "der Sprache der Nachricht"
}

/** One concrete reason per template, used only when every card failed. */
fun followUpHint(items: List<Pair<String, String>>, language: String): String {
    if (items.isEmpty()) return retryHint(emptyList(), language)
    return items.joinToString(" ") { (id, reason) -> "$id: ${reasonText(reason, language)}" }
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
    locale: String = "",
): BuiltPrompt {
    val selected = candidates.take(MAX_TEMPLATES)
    var exampleTake = 5
    var meaningLimit = 180
    var lineLimit = 72
    var ctx = context.map { clip(it, 120) }.filter { it.isNotEmpty() }.take(4)
    val language = outputLanguage(message, ctx, locale)
    var user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint, language)
    if (over(user) && meaningLimit > 100) {
        meaningLimit = 100
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint, language)
    }
    if (over(user) && exampleTake > 3) {
        exampleTake = 3
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint, language)
    }
    if (over(user) && ctx.isNotEmpty()) {
        ctx = emptyList()
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint, language)
    }
    if (over(user)) {
        meaningLimit = 60
        lineLimit = 48
        exampleTake = 3
        user = renderUser(message, selected, exampleTake, meaningLimit, lineLimit, ctx, hint, language)
    }
    return BuiltPrompt(SYSTEM, user)
}

fun memeBrief(
    id: String,
    name: String,
    boxes: Int,
    meaning: String,
    examples: List<List<String>>,
    language: String = "de",
): Brief = Brief(id, name, boxes, meaning, examples.take(5), fieldRoles(id, boxes, language))

fun memeCandidate(id: String, boxes: Int, style: String, name: String, examples: List<List<String>>): Candidate =
    Candidate(
        id = id,
        boxes = boxes,
        style = style,
        example = examples.firstOrNull().orEmpty(),
        name = name,
        examples = examples.take(5),
    )

fun fieldRoles(id: String, boxes: Int, language: String = "de"): String {
    val german = language == "de"
    val role = when (id) {
        "drake" -> if (german) "oben abgelehnt, unten bevorzugt" else "top rejected, bottom preferred"
        "cmm" -> if (german) "eine steile These" else "one bold claim"
        "fine" -> if (german) "oben die Lage, unten die ruhige Reaktion, oft Alles gut" else "top the situation, bottom the calm reaction, often this is fine"
        "db" -> if (german) "links das Vernachlässigte, Mitte die Person, rechts die Ablenkung" else "left the neglected, middle the person, right the distraction"
        "ds" -> if (german) "zwei schwere Optionen und die Reaktion" else "two hard options and the reaction"
        else -> if (german) "Rollen wie in den Beispielen" else "roles as in the examples"
    }
    val label = if (german) "Felder" else "fields"
    return "$boxes $label: $role"
}

fun examplesForLanguage(
    german: List<List<String>>,
    english: List<List<String>>,
    language: String,
): List<List<String>> = when (language) {
    "de" -> german.ifEmpty { english }
    "en" -> english.ifEmpty { german }
    else -> english.ifEmpty { german }
}

fun selectExampleGroups(groups: List<List<String>>, boxes: Int): List<List<String>> {
    val banned = listOf("biden", "obama", "hitler", "imgflip", "upvote", "trump")
    return groups.filter { lines ->
        lines.size == boxes &&
            lines.any { it.isNotBlank() } &&
            banned.none { word -> lines.joinToString(" ").lowercase().contains(word) }
    }.take(3)
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
    language: String,
): String {
    val body = StringBuilder()
    body.append("Message: ").append(message.trim()).append('\n')
    if (context.isNotEmpty()) {
        body.append("Context, last messages, for understanding only, do not copy them:\n")
        context.forEach { line -> body.append("- ").append(line).append('\n') }
    }
    body.append("Templates:\n")
    candidates.forEachIndexed { index, brief ->
        body.append(index + 1).append(". ").append(brief.id)
        body.append(" | ").append(brief.name)
        val roles = brief.roles.ifBlank { fieldRoles(brief.id, brief.boxes, language) }
        body.append(" | ").append(roles)
        val meaning = clipSentence(brief.meaning, meaningLimit)
        if (meaning.isNotBlank()) body.append(" | ").append(meaning)
        body.append('\n')
        val examples = brief.examples.take(exampleTake).filter { lines -> lines.all { it.isNotBlank() } }
        examples.forEach { lines ->
            body.append("- ")
            body.append(lines.joinToString(" | ") { clip(it, lineLimit) })
            body.append('\n')
        }
    }
    body.append("Template names may be English. The caption language still follows the instruction below.\n")
    body.append("Do not copy the message. Rewrite it in the style of the examples. Do not use a slash.\n")
    if (hint.isNotBlank()) body.append(hint.trim()).append('\n')
    body.append("Write in ").append(languageName(language)).append(".\n")
    body.append("Reply in exactly this shape, with the real ids and z1, z2, ...:\n")
    body.append(exampleJson(candidates, language))
    body.append('\n')
    return body.toString()
}

private fun placeholder(language: String): String = when (language) {
    "de" -> "<deutsche Zeile>"
    "en" -> "<english line>"
    else -> "<line>"
}

private fun exampleJson(candidates: List<Brief>, language: String): String {
    val slot = placeholder(language)
    val body = candidates.joinToString(",") { brief ->
        val fields = (1..brief.boxes.coerceAtLeast(1)).joinToString(",") { number -> "\"z$number\":\"$slot\"" }
        "\"${brief.id}\":{$fields}"
    }
    return "{$body}"
}

const val QUALITY_MAX_TOKENS = 48
const val QUALITY_TEMPERATURE = 0.2

/** A short second call. g is grammar, p is the punchline, each from 1 to 5. */
fun qualityPrompt(cards: List<Pair<String, List<String>>>, language: String): BuiltPrompt {
    val german = language == "de"
    val system = if (german) {
        "Du bewertest Meme-Zeilen. g ist Grammatik von 1 bis 5. p ist die Pointe von 1 bis 5. Antworte nur mit JSON."
    } else {
        "You score meme captions. g is grammar from 1 to 5. p is the punchline from 1 to 5. Reply with JSON only."
    }
    val user = StringBuilder()
    cards.forEach { (id, lines) ->
        user.append(id).append(": ").append(lines.joinToString(" | ")).append('\n')
    }
    val shape = cards.joinToString(",") { (id, _) -> "\"$id\":{\"g\":\"3\",\"p\":\"4\"}" }
    user.append(if (german) "Bewerte nur diese Karten.\n" else "Score only these cards.\n")
    user.append('{').append(shape).append('}')
    return BuiltPrompt(system, user.toString())
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
