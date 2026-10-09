package app.memem.engine

import java.util.Locale

fun stylize(text: String, style: String): String {
    val cleaned = text.trim().replace(Regex("\\s+"), " ")
    return when (style) {
        "upper" -> cleaned.uppercase(Locale.ROOT)
        "lower" -> cleaned.lowercase(Locale.ROOT)
        else -> cleaned
    }
}

private val CONJUNCTIONS = setOf(
    "und", "oder", "aber", "denn", "weil", "dass", "daß", "wenn", "als", "wie",
    "sowie", "doch", "sondern", "während", "bevor", "nachdem", "obwohl", "damit",
    "ob", "falls", "sobald", "bis", "beziehungsweise", "bzw", "dann",
)

private val FUNCTION_WORDS = setOf(
    "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "einer", "einem", "einen", "eines",
    "am", "an", "auf", "aus", "bei", "mit", "nach", "von", "zu", "zum", "zur", "vom",
    "im", "in", "für", "fuer", "gegen", "über", "ueber", "unter", "vor", "hinter",
    "neben", "zwischen", "durch", "pro",
    "und", "oder", "aber", "denn", "weil", "dass", "daß", "wenn", "sowie", "doch",
    "sondern", "obwohl", "damit", "ob", "falls",
    "nicht", "auch", "nur", "so", "sehr", "schon", "noch", "mal", "nun",
)

private val ARTICLES = setOf(
    "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "einer", "einem", "einen", "eines",
)

private val DETERMINERS = setOf(
    "kein", "keine", "keiner", "keinem", "keinen", "keines",
    "alle", "aller", "allem", "allen",
    "dieser", "diese", "dieses", "diesem", "diesen",
    "jede", "jeder", "jedem", "jeden", "jedes",
    "jene", "jener", "jenem", "jenen", "jenes",
    "manche", "mancher", "manchem", "manchen", "manches",
    "welche", "welcher", "welchem", "welchen", "welches",
    "solche", "solcher", "solchem", "solchen", "solches",
    "einige", "einiger", "einigem", "einigen", "einiges",
)

private val PREPOSITIONS = setOf(
    "an", "auf", "aus", "bei", "mit", "nach", "von", "zu", "in",
    "für", "fuer", "gegen", "über", "ueber", "unter", "vor", "hinter",
    "neben", "zwischen", "durch", "ohne", "um", "seit", "ab",
    "außer", "ausser", "bis", "wider", "gegenüber", "gegenueber",
    "trotz", "wegen", "während", "waehrend", "statt", "binnen",
    "laut", "per", "pro", "gen", "je",
)

private val CONTRACTIONS = setOf(
    "am", "im", "zum", "zur", "vom", "beim", "ins", "ans", "aufs",
    "übers", "uebers", "unters", "vors", "hinters", "durchs",
    "fürs", "fuers", "ums",
)

private val PERSONAL_PRONOUNS = setOf(
    "ich", "du", "er", "sie", "es", "wir", "ihr",
    "mich", "dich", "sich", "uns", "euch",
    "mir", "dir", "ihm", "ihn", "ihnen", "man",
)

private val POSSESSIVES = setOf(
    "mein", "meine", "meiner", "meinem", "meinen", "meines",
    "dein", "deine", "deiner", "deinem", "deinen", "deines",
    "sein", "seine", "seiner", "seinem", "seinen", "seines",
    "ihr", "ihre", "ihrer", "ihrem", "ihren", "ihres",
    "unser", "unsere", "unserer", "unserem", "unseren", "unseres",
    "euer", "eure", "eurer", "eurem", "euren", "eures",
)

/** Conjunctions that must not close a line. "dann" may, it is a conjunctive adverb. */
private val DANGLING_CONJUNCTIONS = CONJUNCTIONS - setOf("dann")

private val DANGLING_ENDINGS =
    ARTICLES + DETERMINERS + PREPOSITIONS + CONTRACTIONS +
        DANGLING_CONJUNCTIONS + PERSONAL_PRONOUNS + POSSESSIVES

private val PHRASE_STARTERS = ARTICLES + DETERMINERS + PREPOSITIONS + CONTRACTIONS + POSSESSIVES

private val TIME_ADVERBS = setOf(
    "heute", "gestern", "morgen", "jetzt", "bald", "später", "spaeter", "früh", "frueh",
    "danach", "vorher", "damals", "nun", "sofort", "gleich", "stets", "immer",
    "nie", "niemals", "abends", "morgens", "nachts", "mittags", "gerade", "eben",
    "inzwischen", "zuletzt", "demnächst", "demnaechst",
)

private val TIME_NOUNS = setOf(
    "ende", "anfang", "beginn", "schluss", "moment", "augenblick",
    "morgen", "abend", "mittag", "nacht", "vormittag", "nachmittag", "früh", "frueh",
    "jahr", "monat", "woche", "tag", "stunde", "minute", "sekunde", "zeit",
    "zukunft", "vergangenheit", "gegenwart", "wochenende", "feierabend",
    "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonntag",
    "frühling", "fruehling", "sommer", "herbst", "winter",
    "januar", "februar", "märz", "maerz", "april", "mai", "juni", "juli",
    "august", "september", "oktober", "november", "dezember",
)

private val AUXILIARIES = setOf(
    "habe", "hast", "hat", "haben", "habt",
    "hatte", "hattest", "hatten", "hattet",
    "hätte", "haette", "hättest", "haettest", "hätten", "haetten", "hättet", "haettet",
    "bin", "bist", "ist", "sind", "seid",
    "war", "warst", "waren", "wart",
    "werde", "wirst", "wird", "werden", "werdet",
    "würde", "wuerde", "würdest", "wuerdest", "würden", "wuerden", "würdet", "wuerdet",
    "sei", "seien", "gewesen",
)

/** Split the user's own words across the boxes without reordering or breaking a phrase. */
fun fallbackLines(message: String, boxCount: Int, style: String): List<String> {
    val count = boxCount.coerceAtLeast(1)
    val words = messageWords(message)
    if (words.isEmpty()) return List(count) { "" }
    return splitInOrder(words, count).map { chunk ->
        capWords(stylize(chunk.joinToString(" "), style))
    }
}

/**
 * Joined lines must still contain every content word of [message] in the same order.
 * Function words may be dropped. A reordered or interleaved line fails.
 */
fun preservesMessageOrder(message: String, lines: List<String>): Boolean {
    val needed = contentWords(message).ifEmpty { normalizedWords(message) }
    if (needed.isEmpty()) return lines.all { it.isBlank() }
    val hay = normalizedWords(lines.joinToString(" "))
    var index = 0
    for (word in hay) {
        if (index < needed.size && word == needed[index]) index += 1
    }
    return index == needed.size
}

/** True when [line] ends on an article, preposition, conjunction, pronoun, or similar, without punctuation. */
fun endsOnOpenFunctionWord(line: String): Boolean {
    val last = messageWords(line).lastOrNull() ?: return false
    return !canEndLine(last)
}

fun messageWords(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

fun contentWords(text: String): List<String> =
    normalizedWords(text).filter { it.length > 1 && it !in FUNCTION_WORDS }

fun normalizedWords(text: String): List<String> =
    messageWords(text).map { normalizeWord(it) }.filter { it.isNotEmpty() }

fun splitInOrder(words: List<String>, parts: Int): List<List<String>> {
    if (parts <= 1) return listOf(words)
    if (words.isEmpty()) return List(parts) { emptyList() }
    if (words.size == 1) return listOf(words) + List(parts - 1) { emptyList() }
    if (words.size <= parts && canEndLine(words.first())) {
        return listOf(listOf(words.first())) + splitInOrder(words.drop(1), parts - 1)
    }
    val goal = (words.size / parts).coerceIn(1, words.size - 1)
    val cut = bestLegalCut(words, goal) ?: return listOf(words) + List(parts - 1) { emptyList() }
    return listOf(words.subList(0, cut)) + splitInOrder(words.subList(cut, words.size), parts - 1)
}

private fun bestLegalCut(words: List<String>, target: Int): Int? {
    val goal = target.coerceIn(1, words.size - 1)
    var best: Int? = null
    var bestScore = Int.MIN_VALUE
    for (cut in 1 until words.size) {
        if (!canEndLine(words[cut - 1])) continue
        val score = boundaryKind(words, cut) * 100 - kotlin.math.abs(cut - goal)
        if (best == null || score > bestScore) {
            best = cut
            bestScore = score
        }
    }
    return best
}

/** Higher is better: punctuation, then conjunction, then time or verb bracket, then a new phrase, then any legal cut. */
private fun boundaryKind(words: List<String>, cut: Int): Int {
    val next = normalizeWord(words[cut])
    return when {
        endsWithPunct(words[cut - 1]) -> 4
        next in CONJUNCTIONS -> 3
        endsCompletedTimePhrase(words, cut) || closesVerbBracket(words, cut) -> 2
        next in PHRASE_STARTERS -> 1
        else -> 0
    }
}

private fun canEndLine(word: String): Boolean {
    if (endsWithPunct(word)) return true
    return normalizeWord(word) !in DANGLING_ENDINGS
}

private fun endsCompletedTimePhrase(words: List<String>, cut: Int): Boolean {
    val end = normalizeWord(words[cut - 1])
    val prev = if (cut >= 2) normalizeWord(words[cut - 2]) else ""
    val next = if (cut < words.size) normalizeWord(words[cut]) else ""
    if (next.isNotEmpty() && continuesTimePhrase(end, next)) return false
    if (end in TIME_ADVERBS) return true
    if (end == "noch" && prev in TIME_ADVERBS) return true
    return end in TIME_NOUNS && isTimeIntroducer(prev)
}

private fun continuesTimePhrase(end: String, next: String): Boolean {
    if ((end in TIME_ADVERBS || end in TIME_NOUNS) && (next == "noch" || next == "früh" || next == "frueh")) {
        return true
    }
    if (end in TIME_ADVERBS && next in TIME_ADVERBS) return true
    return isTimeIntroducer(end) && next in TIME_NOUNS
}

private fun isTimeIntroducer(word: String): Boolean =
    word in PREPOSITIONS || word in ARTICLES || word in CONTRACTIONS || word in DETERMINERS

/** A ge- participle closes a verb bracket only when an auxiliary already stands in the same line. */
private fun closesVerbBracket(words: List<String>, cut: Int): Boolean {
    val end = normalizeWord(words[cut - 1])
    if (end.length < 5 || !end.startsWith("ge")) return false
    for (index in 0 until cut - 1) {
        if (normalizeWord(words[index]) in AUXILIARIES) return true
    }
    return false
}

private fun endsWithPunct(word: String): Boolean {
    val mark = word.lastOrNull() ?: return false
    return mark == '.' || mark == '!' || mark == '?' || mark == ';' || mark == ':' || mark == ',' || mark == '…'
}

private fun normalizeWord(raw: String): String =
    raw.lowercase(Locale.ROOT).replace("ß", "ss").replace(Regex("[^\\p{L}\\p{N}]"), "")

fun capWords(line: String, maxWords: Int = 8, maxChars: Int = 80): String {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(maxWords)
    return words.joinToString(" ").take(maxChars).trim()
}

fun cleanLine(raw: String): String {
    var line = raw.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
    line = line.replace(" - ", ", ")
    return capWords(line)
}
