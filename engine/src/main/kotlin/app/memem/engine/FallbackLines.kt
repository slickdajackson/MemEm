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

private val SYNONYM_GROUPS = listOf(
    setOf("verlier", "verlor", "niederlag", "pleite", "scheiter"),
    setOf("fertig", "erledig", "geschaff"),
    setOf("problem", "aerger", "ärger", "schwier"),
    setOf("lieber", "bevorzug"),
    setOf("meeting", "besprech", "termin"),
    setOf("handy", "telefon"),
    setOf("bericht", "report"),
    setOf("server", "rechner"),
    setOf("brenn", "feuer", "brand"),
    setOf("verpass", "verspaet", "verspät"),
    setOf("kino", "film"),
    setOf("pruef", "prüfung", "bestanden", "geschafft", "klausur", "exam"),
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

/** Split the user's own words across the boxes. Never drop a word and never leave a field empty. */
fun fallbackLines(message: String, boxCount: Int, style: String): List<String> {
    val count = boxCount.coerceAtLeast(1)
    val words = messageWords(message)
    if (words.isEmpty()) return List(count) { "" }
    return splitInOrder(words, count).map { chunk ->
        stylize(chunk.joinToString(" "), style)
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

/**
 * A rewrite is usable when it still carries the message: at least one supporting content word
 * or a synonym of one. Word order may change. An empty message has no core word to miss.
 */
fun carriesCoreStatement(message: String, lines: List<String>): Boolean {
    val needed = coreWords(message)
    if (needed.isEmpty()) return lines.any { it.isNotBlank() }
    val hay = normalizedWords(lines.joinToString(" "))
    return needed.any { word -> hay.any { related(word, it) } }
}

fun coreWords(text: String): List<String> =
    normalizedWords(text).filter { word ->
        word.length >= 3 && word !in FUNCTION_WORDS && word !in DANGLING_ENDINGS && word !in AUXILIARIES
    }

/** True when [line] ends on a function word. The last line of a caption may end on a pronoun. */
fun phraseEndingBroken(line: String, isFinal: Boolean): Boolean {
    val last = messageWords(line).lastOrNull() ?: return false
    if (endsWithPunct(last)) return false
    val norm = normalizeWord(last)
    if (norm !in DANGLING_ENDINGS) return false
    return !(isFinal && norm in PERSONAL_PRONOUNS)
}

/**
 * Moves a dangling article, preposition or conjunction onto the next line, or pulls the next word up.
 * Returns null when a line would have to stay empty or still end inside a phrase.
 */
fun repairPhraseEndings(lines: List<String>): List<String>? {
    if (lines.isEmpty()) return emptyList()
    val buckets = lines.map { messageWords(it).toMutableList() }.toMutableList()
    for (index in 0 until buckets.lastIndex) {
        while (buckets[index].size > 1 && !canEndLine(buckets[index].last())) {
            buckets[index + 1].add(0, buckets[index].removeAt(buckets[index].lastIndex))
        }
        var guard = 0
        while (
            buckets[index].isNotEmpty() &&
            !canEndLine(buckets[index].last()) &&
            buckets[index + 1].size > 1 &&
            guard < 8
        ) {
            buckets[index].add(buckets[index + 1].removeAt(0))
            guard += 1
        }
        if (buckets[index].isEmpty() || !canEndLine(buckets[index].last())) return null
    }
    if (buckets.last().isEmpty() || phraseEndingBroken(buckets.last().joinToString(" "), isFinal = true)) {
        return null
    }
    return buckets.map { it.joinToString(" ") }
}

fun usableRewrite(message: String, lines: List<String>, boxes: Int): Boolean {
    return rejectReason(message, lines, Candidate("_", boxes, "none")) == null
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
    if (words.size < parts) return List(parts) { listOf(words[it % words.size]) }
    val phrase = tryPhraseSplit(words, parts)
    if (phrase != null && phrase.all { it.isNotEmpty() } && phrase.sumOf { it.size } == words.size) return phrase
    return evenSplit(words, parts)
}

private fun tryPhraseSplit(words: List<String>, parts: Int): List<List<String>>? {
    if (parts <= 1) return listOf(words)
    if (words.size < parts) return null
    if (words.size == parts) {
        if (words.dropLast(1).all { canEndLine(it) }) return words.map { listOf(it) }
        return null
    }
    val goal = (words.size / parts).coerceIn(1, words.size - 1)
    val cut = bestLegalCut(words, goal) ?: return null
    val tail = tryPhraseSplit(words.subList(cut, words.size), parts - 1) ?: return null
    val head = words.subList(0, cut)
    if (head.isEmpty() || tail.any { it.isEmpty() }) return null
    return listOf(head) + tail
}

private fun evenSplit(words: List<String>, parts: Int): List<List<String>> {
    val base = words.size / parts
    val extra = words.size % parts
    val out = ArrayList<List<String>>(parts)
    var index = 0
    for (part in 0 until parts) {
        val count = base + if (part < extra) 1 else 0
        out += words.subList(index, index + count)
        index += count
    }
    return out
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

private fun related(left: String, right: String): Boolean {
    if (left == right) return true
    if (left.length >= 4 && right.startsWith(left)) return true
    if (right.length >= 4 && left.startsWith(right)) return true
    val group = SYNONYM_GROUPS.firstOrNull { synonyms -> synonyms.any { stemHits(left, it) } } ?: return false
    return group.any { stemHits(right, it) }
}

private fun stemHits(word: String, stem: String): Boolean {
    if (word.startsWith(stem)) return true
    return stem.startsWith(word) && word.length >= 4
}

fun capWords(line: String, maxWords: Int = 8, maxChars: Int = 80): String {
    val words = line.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(maxWords)
    val joined = words.joinToString(" ")
    if (joined.length <= maxChars) return joined
    val cut = joined.lastIndexOf(' ', maxChars.coerceAtMost(joined.length))
    return if (cut > 0) joined.substring(0, cut).trim() else joined.take(maxChars).trim()
}

fun cleanLine(raw: String): String {
    var line = raw.replace(Regex("\\s+"), " ").trim().trim('"', '“', '”')
    line = line.replace(" - ", ", ")
    return capWords(line)
}
