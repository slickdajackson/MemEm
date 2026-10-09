package app.memem.engine

import java.io.File
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionTest {
    private fun p(id: String, score: Double) = ScoredPoint(id, score, listOf("x"))

    @Test
    fun ranksByBestPointAndIgnoresDuplicates() {
        // Golden values from reference/memechat/tests/test_units.py
        val hits = fuse(
            mapOf(
                Kind.TEXT to listOf(p("a", 0.9), p("a", 0.8), p("b", 0.7)),
                Kind.IMAGE to listOf(p("b", 0.5), p("a", 0.4)),
            ),
            mapOf(Kind.TEXT to 1.0, Kind.IMAGE to 1.0),
            k = 60,
        )
        val byId = hits.associateBy { it.templateId }
        assertEquals(1, byId.getValue("a").kinds.getValue(Kind.TEXT).rank)
        assertEquals(2, byId.getValue("b").kinds.getValue(Kind.TEXT).rank)
        assertEquals(1.0 / 61 + 1.0 / 62, byId.getValue("a").score, 1e-9)
        assertEquals(1.0 / 62 + 1.0 / 61, byId.getValue("b").score, 1e-9)
    }

    @Test
    fun zeroWeightDoesNotChangeWinner() {
        val hits = fuse(
            mapOf(Kind.TEXT to listOf(p("a", 0.9)), Kind.IMAGE to listOf(p("b", 0.9))),
            mapOf(Kind.TEXT to 1.0, Kind.IMAGE to 0.0),
            k = 60,
        )
        assertEquals("a", hits.first().templateId)
    }

    @Test
    fun dropsTemplatesWithMoreThanFourBoxes() {
        val hits = listOf(TemplateHit("wide").apply { score = 2.0 }, TemplateHit("ok").apply { score = 1.0 })
        val picked = selectTemplates(hits, mapOf("wide" to 6, "ok" to 2), limit = 8)
        assertEquals(listOf("ok"), picked.map { it.templateId })
    }
}

class HashEmbedderTest {
    @Test
    fun matchesPythonGolden() {
        val text = javaClass.getResourceAsStream("/hash_golden.json")!!.readBytes().toString(StandardCharsets.UTF_8)
        val json = JSONObject(text)
        val n = json.getInt("n")
        val idfJson = json.getJSONObject("idf")
        val idf = buildMap {
            for (key in idfJson.keys()) put(key, idfJson.getDouble(key).toFloat())
        }
        val expected = json.getJSONObject("vectors").getJSONArray("query")
        val actual = HashEmbedder.embed("shut up and take my money", idf, n)
        assertEquals(expected.length(), actual.size)
        for (i in 0 until expected.length()) {
            assertEquals(expected.getDouble(i), actual[i].toDouble(), 2e-4)
        }
    }
}

class HalfTest {
    @Test
    fun knownValues() {
        assertEquals(1f, halfToFloat(0x3C00), 0f)
        assertEquals(0.5f, halfToFloat(0x3800), 0f)
        assertEquals(-2f, halfToFloat(0xC000), 0f)
    }
}

class GemmaResponseTest {
    private val candidates = listOf(
        Candidate("a", 3, "upper", listOf("eins", "zwei")),
        Candidate("b", 2, "upper"),
        Candidate("c", 1, "none", listOf("beispiel")),
    )

    @Test
    fun cleansPadsAndFillsToThree() {
        val raw = """noise {"memes":[{"template":"a","lines":["  \"Hi\"  ","x - y"]}]} tail"""
        val out = parseSuggestions(raw, candidates, "heute noch fertig")
        assertEquals(3, out.size)
        assertEquals(listOf("HEUTE", "NOCH", "FERTIG"), out[0].lines)
        assertFalse(out[0].fromModel)
        assertEquals("b", out[1].templateId)
        assertEquals(listOf("HEUTE NOCH", "FERTIG"), out[1].lines)
        assertEquals("c", out[2].templateId)
        assertFalse(out[1].fromModel)
    }

    @Test
    fun replacesUnknownIds() {
        val raw = """{"memes":[{"template":"nope","lines":["a"]},{"template":"b","lines":["nur das"]}]}"""
        val message = "hallo welt das ist ein langer satz mit vielen worten extra"
        val out = parseSuggestions(raw, candidates, message)
        assertEquals("b", out[0].templateId)
        assertEquals(listOf("HALLO WELT DAS IST", "EIN LANGER SATZ MIT VIELEN WORTEN EXTRA"), out[0].lines)
        assertTrue(carriesCoreStatement(message, out[0].lines))
        assertEquals(3, out.size)
        assertFalse(out.map { it.templateId }.contains("nope"))
    }

    @Test
    fun keepsGemmaLinesWhenContentWordsStayInOrder() {
        val raw = """{"memes":[{"template":"b","lines":["Ihr werdet am Ende","alles verlieren"]}]}"""
        val message = "ihr werdet am ende alles verlieren"
        val out = parseSuggestions(raw, listOf(Candidate("b", 2, "upper")), message)
        assertEquals(listOf("IHR WERDET AM ENDE", "ALLES VERLIEREN"), out[0].lines)
        assertFalse(out[0].fromModel)
        assertEquals("woertlich", out[0].reason)
    }

    @Test
    fun paraphraseWithoutSharedWordsStays() {
        val raw = """{"memes":[{"template":"b","lines":["Pizza ist da","Hunger"]}]}"""
        val message = "ihr werdet am ende alles verlieren"
        val out = parseSuggestions(raw, listOf(Candidate("b", 2, "upper")), message)
        assertEquals(listOf("Pizza ist da", "Hunger"), out[0].lines)
        assertTrue(out[0].fromModel)
        assertEquals("", out[0].reason)
    }

    @Test
    fun emptyLinesFallBackToTheLiteralSentence() {
        val raw = """{"memes":[{"template":"b","lines":["",""]}]}"""
        val message = "ihr werdet am ende alles verlieren"
        val out = parseSuggestions(raw, listOf(Candidate("b", 2, "upper")), message)
        assertEquals(listOf("IHR WERDET AM ENDE", "ALLES VERLIEREN"), out[0].lines)
        assertFalse(out[0].fromModel)
        assertEquals("leer", out[0].reason)
    }

    @Test
    fun parserAcceptsNrAndShortFieldNames() {
        val raw = """Text {"items":[{"Nr":"drake","m":["Meeting heute","Meeting am Freitag"]}]} Ende"""
        val message = "Meetings am Freitag sind mir lieber als welche heute"
        val out = parseSuggestions(raw, listOf(Candidate("drake", 2, "upper", name = "Drakeposting")), message)
        assertEquals(listOf("Meeting heute", "Meeting am Freitag"), out[0].lines)
        assertTrue(out[0].fromModel)
    }

    @Test
    fun parserAcceptsPlainTextAndNumericIndex() {
        val prose = "fine:\nQualm im Rack\nUnd keiner merkt es"
        val message = "Der Server brennt und niemand merkt es"
        val fromText = parseSuggestions(prose, listOf(Candidate("fine", 2, "upper", name = "This is Fine")), message)
        assertEquals(listOf("Qualm im Rack", "Und keiner merkt es"), fromText[0].lines)
        assertTrue(fromText[0].fromModel)
        val indexed = """{"memes":[{"Nr":1,"text":"Ananas bleibt eine These"}]}"""
        val fromIndex = parseSuggestions(indexed, listOf(Candidate("cmm", 1, "upper")), "Ananas gehört nicht auf die Pizza")
        assertEquals(listOf("Ananas bleibt eine These"), fromIndex[0].lines)
        assertTrue(fromIndex[0].fromModel)
    }

    @Test
    fun fiveMessagesAreRewrittenForTheTemplate() {
        val cases = listOf(
            Rewrite(
                "ihr werdet am ende alles verlieren",
                Candidate("fine", 2, "upper"),
                listOf("Sichere Niederlage", "Kein Grund zur Panik"),
            ),
            Rewrite(
                "Meetings am Freitag sind mir lieber als welche heute",
                Candidate("drake", 2, "upper"),
                listOf("Meeting heute", "Meeting am Freitag"),
            ),
            Rewrite(
                "Ananas gehört einfach nicht auf die Pizza",
                Candidate("cmm", 1, "upper"),
                listOf("Ananas auf Pizza ist falsch"),
            ),
            Rewrite(
                "Ich sollte den Bericht fertig machen, schaue aber dauernd aufs Handy",
                Candidate("db", 3, "upper"),
                listOf("der Bericht", "mein Fokus", "das Handy"),
            ),
            Rewrite(
                "Der Server brennt und niemand merkt es",
                Candidate("fine", 2, "upper"),
                listOf("Qualm im Rack", "Und keiner merkt es"),
            ),
        )
        assertEquals(5, cases.size)
        for (item in cases) {
            val raw = """{"memes":[{"template":"${item.candidate.id}","lines":[${item.lines.joinToString(",") { "\"$it\"" }}]}]}"""
            val out = parseSuggestions(raw, listOf(item.candidate), item.message).single()
            val literal = fallbackLines(item.message, item.candidate.boxes, item.candidate.style)
            assertEquals(item.message, item.lines, out.lines)
            assertTrue(item.message, out.fromModel)
            assertEquals(item.message, item.candidate.boxes, out.lines.size)
            assertNotEquals(item.message, joined(literal), joined(out.lines))
            assertTrue(item.message, carriesCoreStatement(item.message, out.lines))
            assertTrue(item.message, usableRewrite(item.message, out.lines, item.candidate.boxes))
            assertFalse(item.message, out.lines.any { phraseEndingBroken(it, isFinal = false) && it != out.lines.last() })
        }
    }

    @Test
    fun repairsALineThatEndsOnAm() {
        val message = "ihr werdet am ende alles verlieren"
        val raw = """{"memes":[{"template":"fine","lines":["Wir sind am","Ende und verlieren"]}]}"""
        val out = parseSuggestions(raw, listOf(Candidate("fine", 2, "upper")), message).single()
        assertEquals(listOf("Wir sind", "am Ende und verlieren"), out.lines)
        assertTrue(out.fromModel)
        assertFalse(phraseEndingBroken(out.lines[0], isFinal = false))
    }

    @Test
    fun identicalLinesFallBack() {
        val message = "ihr werdet am ende alles verlieren"
        val raw = """{"memes":[{"template":"fine","lines":["Alles vorbei","Alles vorbei"]}]}"""
        val out = parseSuggestions(raw, listOf(Candidate("fine", 2, "upper")), message).single()
        assertEquals(listOf("IHR WERDET AM ENDE", "ALLES VERLIEREN"), out.lines)
        assertFalse(out.fromModel)
        assertEquals("doppelt", out.reason)
    }

    @Test
    fun rejectsEnglishCopiesAndExampleCopies() {
        val message = "ihr werdet am ende alles verlieren"
        val english = """{"fine":{"z1":"I SHOULD SELL","z2":"EPIPENS"}}"""
        val sold = parseSuggestions(english, listOf(Candidate("fine", 2, "upper")), message).single()
        assertFalse(sold.fromModel)
        assertEquals("sprache", sold.reason)
        val copied = parseSuggestions(
            """{"boat":{"z1":"Ich sollte ein Boot kaufen","z2":"Morgen reicht"}}""",
            listOf(
                Candidate(
                    "boat",
                    2,
                    "upper",
                    examples = listOf(listOf("Ich sollte ein Boot kaufen", "Morgen reicht")),
                ),
            ),
            message,
        ).single()
        assertFalse(copied.fromModel)
        assertEquals("kopie", copied.reason)
    }

    @Test
    fun slashStaysInsideTheField() {
        val kept = parseSuggestions(
            """{"fine":{"z1":"Qualm im Rack / das Regal brennt","z2":"Keiner schaut hin"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "Der Server brennt und niemand merkt es",
        ).single()
        assertEquals(listOf("Qualm im Rack / das Regal brennt", "Keiner schaut hin"), kept.lines)
        assertTrue(kept.fromModel)
        val extra = parseSuggestions(
            """{"fine":{"z1":"Qualm im Rack","z2":"Keiner da","z3":"Noch eine Zeile"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "Der Server brennt und niemand merkt es",
        ).single()
        assertEquals(2, extra.lines.size)
        assertEquals(listOf("Qualm im Rack", "Keiner da"), extra.lines)
        val split = parseSuggestions(
            """{"fine":{"z1":"Ich / Im Bett bleiben"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "das wetter ist so schlecht ich bleib im bett",
        ).single()
        assertFalse(split.fromModel)
        assertEquals(2, split.lines.size)
    }

    @Test
    fun negativeSimilarityFallsBack() {
        val raw = """{"memes":[{"template":"b","lines":["Pizza ist da","Hunger"]}]}"""
        val message = "ihr werdet am ende alles verlieren"
        val out = parseSuggestions(
            raw,
            listOf(Candidate("b", 2, "upper")),
            message,
            similarity = { -0.2f },
        ).single()
        assertFalse(out.fromModel)
        assertEquals("fremd", out.reason)
    }

    @Test
    fun schemaRequiresIdsAndNamedLines() {
        val schema = memeSchema(listOf("drake" to 2, "fry" to 2))
        assertTrue(schema.contains("\"drake\""))
        assertTrue(schema.contains("\"z1\""))
        assertTrue(schema.contains("\"z2\""))
        assertTrue(schema.contains("minLength"))
        assertTrue(schema.contains("maxLength"))
        assertTrue(schema.contains("\"additionalProperties\":false"))
        assertTrue(schema.contains("\"required\""))
        assertTrue(schemaAvoidsCountConstraints(schema))
    }

    @Test
    fun captionLanguageFollowsTheMessage() {
        val german = "wer kommt heute abend mit ins kino"
        val englishCard = """{"fine":{"z1":"Exam passed","z2":"No sweat"}}"""
        val slipped = parseSuggestions(englishCard, listOf(Candidate("fine", 2, "upper")), german).single()
        assertEquals("sprache", slipped.reason)
        val monday = parseSuggestions(
            """{"fine":{"z1":"Monday","z2":"Again"}}""",
            listOf(Candidate("fine", 2, "upper")),
            german,
        ).single()
        assertEquals("sprache", monday.reason)
        val unknown = parseSuggestions(
            """{"fine":{"z1":"Xyzqq","z2":"Plonk"}}""",
            listOf(Candidate("fine", 2, "upper")),
            german,
        ).single()
        assertTrue(unknown.fromModel)
        val setup = parseSuggestions(
            """{"officespace":{"z1":"Work on the weekend","z2":"That would be great"}}""",
            listOf(Candidate("officespace", 2, "upper")),
            "my boss wants me to work on the weekend",
        ).single()
        assertTrue(setup.fromModel)
        val shortEnglish = parseSuggestions(
            """{"officespace":{"z1":"Weekend shift","z2":"That would be great"}}""",
            listOf(Candidate("officespace", 2, "upper")),
            "my boss wants me to work on the weekend",
        ).single()
        assertTrue(shortEnglish.fromModel)
        val stau = parseSuggestions(
            """{"toohigh":{"z1":"Stau","z2":"Too damn high"}}""",
            listOf(Candidate("toohigh", 2, "upper")),
            "schon wieder stau auf der a8",
        ).single()
        assertTrue(stau.fromModel)
        val borrowed = parseSuggestions(
            """{"fine":{"z1":"Alles wird verloren","z2":"It's a trap!"}}""",
            listOf(Candidate("fine", 2, "upper")),
            german,
        ).single()
        assertEquals("kopie", borrowed.reason)
        val english = "I finally passed the exam"
        val kept = parseSuggestions(englishCard, listOf(Candidate("fine", 2, "upper")), english).single()
        assertTrue(kept.fromModel)
        assertEquals(listOf("Exam passed", "No sweat"), kept.lines)
        val germanCard = parseSuggestions(
            """{"fine":{"z1":"Prüfung geschafft","z2":"Kein Stress"}}""",
            listOf(Candidate("fine", 2, "upper")),
            english,
        ).single()
        assertEquals("sprache", germanCard.reason)
        val trap = parseSuggestions(
            """{"ackbar":{"z1":"It's a trap!","z2":"Wir verlieren"}}""",
            listOf(Candidate("ackbar", 2, "upper")),
            german,
        ).single()
        assertTrue(trap.fromModel)
        assertEquals("de", outputLanguage("ok", listOf("Wir gehen heute ins Kino")))
        assertEquals("en", outputLanguage("ok", listOf("We are going to the cinema tonight")))
        assertEquals("en", outputLanguage(english))
        assertEquals("en", outputLanguage("diet starts tomorrow I promise"))
        assertEquals("de", outputLanguage("ok", emptyList(), "de"))
        assertEquals("de", fallbackLocale("en", qwertz = true))
        assertEquals("en", fallbackLocale("en-US", qwertz = false))
        assertEquals("fr", outputLanguage("Nous allons au cinema avec vous"))
    }

    @Test
    fun fixedLabelIsNotACopyAndOverlapRejectsALine() {
        val message = "ihr werdet am ende alles verlieren"
        val exit = Candidate(
            "exit",
            2,
            "upper",
            examples = listOf(listOf("Raus hier", "Ich"), listOf("Nie wieder", "Ich")),
        )
        assertEquals(setOf("ich"), fixedLabels(exit))
        val labeled = parseSuggestions(
            """{"exit":{"z1":"Weg jetzt","z2":"Ich"}}""",
            listOf(exit),
            message,
        ).single()
        assertTrue(labeled.fromModel)
        assertEquals("", labeled.reason)
        val copied = parseSuggestions(
            """{"fine":{"z1":"Wer kommt heute Abend","z2":"mit ins Kino"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "wer kommt heute abend mit ins kino",
        ).single()
        assertFalse(copied.fromModel)
        assertEquals("woertlich", copied.reason)
        val short = parseSuggestions(
            """{"cmm":{"z1":"ins Kino"}}""",
            listOf(Candidate("cmm", 1, "upper")),
            "wer kommt heute abend mit ins kino",
        ).single()
        assertFalse(short.fromModel)
        assertEquals("woertlich", short.reason)
        val shopping = parseSuggestions(
            """{"money":{"z1":"Nur kurz einkaufen","z2":"200 Euro weg"}}""",
            listOf(Candidate("money", 2, "upper")),
            "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
        ).single()
        assertTrue(shopping.fromModel)
        val traffic = parseSuggestions(
            """{"toohigh":{"z1":"Stau wieder auf der A8","z2":"Too damn high"}}""",
            listOf(Candidate("toohigh", 2, "upper")),
            "schon wieder stau auf der a8",
        ).single()
        assertEquals("woertlich", traffic.reason)
        val coffee = parseSuggestions(
            """{"hipster":{"z1":"Wer hat den letzten Kaffee getrunken","z2":"Der Barista"}}""",
            listOf(Candidate("hipster", 2, "upper")),
            "wer hat den letzten kaffee getrunken",
        ).single()
        assertEquals("woertlich", coffee.reason)
        val nearCopy = parseSuggestions(
            """{"fine":{"z1":"Geschafft beim ersten Anlauf","z2":"Ruhig bleiben"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "hab die prüfung bestanden",
            copyCosine = { 0.98f },
        ).single()
        assertEquals("woertlich", nearCopy.reason)
        val atThreshold = parseSuggestions(
            """{"fine":{"z1":"Geschafft beim ersten Anlauf","z2":"Ruhig bleiben"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "hab die prüfung bestanden",
            copyCosine = { 0.97f },
        ).single()
        assertTrue(atThreshold.fromModel)
        val examSetup = parseSuggestions(
            """{"firsttry":{"z1":"Prüfung bestanden","z2":"Erfolg ist gesichert"}}""",
            listOf(Candidate("firsttry", 2, "upper")),
            "hab die prüfung bestanden",
            copyCosine = { text -> if (text.contains("Erfolg")) 0.955f else 0.99f },
        ).single()
        assertTrue(examSetup.fromModel)
        val longShop = parseSuggestions(
            """{"money":{"z1":"Ich wollte nur kurz einkaufen","z2":"Zwei hundert Euro weg"}}""",
            listOf(Candidate("money", 2, "upper")),
            "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
        ).single()
        assertEquals("woertlich", longShop.reason)
        val weekendSleep = parseSuggestions(
            """{"officespace":{"z1":"My boss wants weekend work","z2":"Weekend is for sleeping"}}""",
            listOf(Candidate("officespace", 2, "upper")),
            "my boss wants me to work on the weekend",
        ).single()
        assertEquals("woertlich", weekendSleep.reason)
        val stauSlice = parseSuggestions(
            """{"fine":{"z1":"Stau auf der A8","z2":"Keine Bewegung möglich"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "schon wieder stau auf der a8",
        ).single()
        assertEquals("woertlich", stauSlice.reason)
        val kinoQuestion = parseSuggestions(
            """{"fine":{"z1":"Was ist im Kino","z2":"Wer kommt heute Abend mit"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "wer kommt heute abend mit ins kino",
        ).single()
        assertEquals("woertlich", kinoQuestion.reason)
        val mixed = parseSuggestions(
            """{"fine":{"z1":"Prüfung geschafft","z2":"Mission accomplished"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "hab die prüfung bestanden",
        ).single()
        assertTrue(mixed.fromModel)
        val named = parseSuggestions(
            """{"success":{"z1":"Prüfung geschafft","z2":"Success Kid"}}""",
            listOf(Candidate("success", 2, "upper", name = "Success Kid")),
            "hab die prüfung bestanden",
        ).single()
        assertTrue(named.fromModel)
        val otherName = parseSuggestions(
            """{"fine":{"z1":"Prüfung geschafft","z2":"Success Kid"}}""",
            listOf(Candidate("fine", 2, "upper", name = "This is Fine")),
            "hab die prüfung bestanden",
            otherNames = listOf("Success Kid"),
        ).single()
        assertEquals("name", otherName.reason)
        val ownCatch = parseSuggestions(
            """{"gone":{"z1":"Die 20 Euro sind weg","z2":"And It's Gone"}}""",
            listOf(Candidate("gone", 2, "upper", name = "And It's Gone")),
            "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
        ).single()
        assertTrue(ownCatch.fromModel)
        val germanBudget = parseSuggestions(
            """{"gone":{"z1":"Geld verschwunden","z2":"Budget leer geworden"}}""",
            listOf(Candidate("gone", 2, "upper", name = "And It's Gone")),
            "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
        ).single()
        assertTrue(germanBudget.fromModel)
    }

    @Test
    fun followUpWaitsOnlyWhenNothingPassed() {
        val good = Suggestion("fine", listOf("Qualm", "Ruhe"), true)
        val bad = Suggestion("drake", listOf("x"), false, "sprache")
        val onePassed = planFollowUp(listOf(good, bad), """{"fine":{"z1":"Qualm","z2":"Ruhe"}}""", false)
        assertEquals(listOf("drake"), onePassed!!.templateIds)
        assertEquals(false, onePassed.withoutSchema)
        val alsoGood = Suggestion("exit", listOf("Bitte", "Gehen"), true)
        val twoPassed = planFollowUp(listOf(good, alsoGood, bad), """{"fine":{"z1":"Qualm"}}""", false)
        assertEquals(listOf("drake"), twoPassed!!.templateIds)
        val third = Suggestion("cmm", listOf("These"), true)
        assertNull(planFollowUp(listOf(good, alsoGood, third), """{"fine":{"z1":"Qualm"}}""", false))
        val fillSlot = planFollowUp(
            listOf(good, alsoGood, Suggestion("ds", listOf("A"), false, "fehlt")),
            """{"fine":{"z1":"Qualm"},"exit":{"z1":"Bitte"}}""",
            false,
        )
        assertEquals(listOf("ds"), fillSlot!!.templateIds)
        val targets = followUpTargets(listOf(good, alsoGood, Suggestion("ds", listOf("A"), false, "fehlt")), fillSlot, listOf("fry", "cmm"))
        assertEquals("fry", targets.single().templateId)
        assertEquals(true, targets.single().replaced)
        val rejected = planFollowUp(
            listOf(
                Suggestion("fine", listOf("A"), false, "sprache"),
                Suggestion("drake", listOf("B"), false, "fremd"),
            ),
            """{"fine":{"z1":"Exam passed"},"drake":{"z1":"Monday"}}""",
            false,
        )
        assertEquals(false, rejected!!.withoutSchema)
        assertEquals(listOf("fine", "drake"), rejected.templateIds)
        val broken = planFollowUp(
            listOf(
                Suggestion("fine", listOf("A"), false, "fehlt"),
                Suggestion("drake", listOf("B"), false, "fehlt"),
            ),
            """{"fine":{"z2":"No one around}}}""",
            false,
        )
        assertEquals(true, broken!!.withoutSchema)
        assertEquals(listOf("fine", "drake"), broken.templateIds)
        val missingOne = planFollowUp(
            listOf(good, Suggestion("drake", listOf("A"), false, "fehlt")),
            """{"fine":{"z1":"Qualm","z2":"Ruhe"}}""",
            false,
        )
        assertEquals(false, missingOne!!.withoutSchema)
        assertEquals(listOf("drake"), missingOne.templateIds)
        assertNull(planFollowUp(listOf(bad), "x", true))
        assertNull(planFollowUp(emptyList(), "x", false))
    }

    @Test
    fun repairsTrailingBracesAndKeepsAnExamParaphrase() {
        val raw = """{"fine":{"z1":"Keiner da","z2":"Die Kanne bleibt leer}}}"""
        val fixed = repairModelJson(raw)
        assertEquals("Keiner da", JSONObject(fixed).getJSONObject("fine").getString("z1"))
        assertEquals("Die Kanne bleibt leer", JSONObject(fixed).getJSONObject("fine").getString("z2"))
        val comma = repairModelJson("""{"cmm":{"z1":"Einen neuen Kaffee","}}""")
        assertEquals("Einen neuen Kaffee", JSONObject(comma).getJSONObject("cmm").getString("z1"))
        val valid = """{"fine":{"z1":{"nested":"x"}}}"""
        assertEquals(valid, repairModelJson(valid))
        val out = parseSuggestions(raw, listOf(Candidate("fine", 2, "upper")), "wer hat den letzten kaffee getrunken").single()
        assertEquals(listOf("Keiner da", "Die Kanne bleibt leer"), out.lines)
        assertTrue(out.fromModel)
        val junk = parseSuggestions(
            """{"fine":{"z1":"Diet begins soon","z2":"Cake can wait}}"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "diet starts tomorrow I promise",
        ).single()
        assertEquals(listOf("Diet begins soon", "Cake can wait"), junk.lines)
        assertTrue(junk.fromModel)
        val stated = parseSuggestions(
            """{"fine":{"z1":"Alles ist gut?","z2":"Der geht weg?"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals(listOf("Alles ist gut", "Der geht weg"), stated.lines)
        assertTrue(stated.fromModel)
        val asked = parseSuggestions(
            """{"cmm":{"z1":"Was ist hier los?"}}""",
            listOf(Candidate("cmm", 1, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals(listOf("Was ist hier los?"), asked.lines)
        val typo = parseSuggestions(
            """{"regret":{"z1":"Diese Ausgaben sind schlim"}}""",
            listOf(Candidate("regret", 1, "upper")),
            "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
        ).single()
        assertEquals("tippfehler", typo.reason)
        val cloupen = parseSuggestions(
            """{"fine":{"z1":"Versprechen ist alles","z2":"Die Umsetzung zählt cloupen"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "ab morgen mache ich diät",
        ).single()
        assertEquals("tippfehler", cloupen.reason)
        val anyway = parseSuggestions(
            """{"fine":{"z1":"Ich gehe abtrotzdem","z2":"Der Plan steht"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals("tippfehler", anyway.reason)
        val disappointed = parseSuggestions(
            """{"fine":{"z1":"Ich bin entäuscht","z2":"Der Plan scheitert"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals("tippfehler", disappointed.reason)
        val article = parseSuggestions(
            """{"fine":{"z1":"Die Zug hat Verspätung","z2":"Schon wieder"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals("tippfehler", article.reason)
        val accusative = parseSuggestions(
            """{"fine":{"z1":"Ich nehme den Zug","z2":"Schon wieder"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertTrue(accusative.fromModel)
        val oneLine = parseSuggestions(
            """{"exit":{"z1":"Traffic gridlock"}}""",
            listOf(Candidate("exit", 3, "upper")),
            "traffic jam again",
        ).single()
        assertEquals("leer", oneLine.reason)
        val midPhrase = parseSuggestions(
            """{"fine":{"z1":"Ja wenn der Plan","z2":"wäre toll"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals("abgebrochen", midPhrase.reason)
        val joined = parseSuggestions(
            """{"fine":{"z1":"Ja wenn der Plan","z2":"wäre toll Aber sonst egal"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der server brennt und niemand merkt es",
        ).single()
        assertEquals(listOf("Ja wenn der Plan wäre toll", "Aber sonst egal"), joined.lines)
        assertTrue(joined.fromModel)
        val low = parseSuggestions(
            """{"fine":{"z1":"Geschafft beim ersten Anlauf"}}""",
            listOf(Candidate("fine", 1, "upper")),
            "hab die prüfung bestanden",
            similarity = { -0.05f },
        ).single()
        assertFalse(low.fromModel)
        assertEquals("fremd", low.reason)
        val slight = parseSuggestions(
            """{"fine":{"z1":"Die Bohrmaschine läuft schon lange","z2":"Meine Geduld ist erschöpft"}}""",
            listOf(Candidate("fine", 2, "upper")),
            "der nachbar bohrt seit sieben uhr morgens",
            similarity = { -0.02f },
        ).single()
        assertTrue(slight.fromModel)
        val near = parseSuggestions(
            """{"fine":{"z1":"Geschafft beim ersten Anlauf"}}""",
            listOf(Candidate("fine", 1, "upper")),
            "hab die prüfung bestanden",
            similarity = { MIN_RELEVANCE_MARGIN + 0.05f },
        ).single()
        assertTrue(near.fromModel)
        assertTrue(carriesCoreStatement("hab die prüfung bestanden", near.lines))
    }

    @Test
    fun capsWordsAndUppercasesUmlauts() {
        assertEquals("GRÖSSE STRASSE", stylize("größe straße", "upper"))
        val lines = fallbackLines("eins zwei drei vier fünf sechs sieben acht neun zehn", 1, "none")
        assertEquals("eins zwei drei vier fünf sechs sieben acht neun zehn", lines.single())
    }

    @Test
    fun tenGermanSentencesSplitInOrder() {
        val cases = listOf(
            Case("ihr werdet am ende alles verlieren", 2, "upper", listOf("IHR WERDET AM ENDE", "ALLES VERLIEREN")),
            Case("Ich komme später, aber ich bringe Kuchen", 2, "upper", listOf("ICH KOMME SPÄTER,", "ABER ICH BRINGE KUCHEN")),
            Case("Wir gehen ins Kino und danach essen wir", 2, "none", listOf("Wir gehen ins Kino", "und danach essen wir")),
            Case("Kommst du heute mit, weil das Wetter hält?", 2, "upper", listOf("KOMMST DU HEUTE MIT,", "WEIL DAS WETTER HÄLT?")),
            Case("Kein Problem", 2, "upper", listOf("KEIN", "PROBLEM")),
            Case("Hilfe", 2, "upper", listOf("HILFE", "HILFE")),
            Case(
                "Der Zug hat Verspätung und wir verpassen den Anschluss",
                3,
                "upper",
                listOf("DER ZUG HAT VERSPÄTUNG", "UND WIR VERPASSEN", "DEN ANSCHLUSS"),
            ),
            Case("Alles bleibt, wie es ist.", 2, "upper", listOf("ALLES BLEIBT,", "WIE ES IST.")),
            Case("Bitte schick mir die Unterlagen bis Freitag", 2, "none", listOf("Bitte schick mir die Unterlagen", "bis Freitag")),
            Case(
                "Morgen früh packen wir die Koffer, dann fahren wir los",
                2,
                "upper",
                listOf("MORGEN FRÜH PACKEN WIR DIE KOFFER,", "DANN FAHREN WIR LOS"),
            ),
        )
        assertEquals(10, cases.size)
        for (item in cases) {
            val lines = fallbackLines(item.message, item.boxes, item.style)
            assertEquals(item.message, item.expected, lines)
            assertEquals(item.boxes, lines.size)
            assertTrue(preservesMessageOrder(item.message, lines))
            for (index in lines.indices) {
                if (lines[index].isBlank()) continue
                val lastContent = lines.drop(index + 1).all { it.isBlank() }
                val alone = messageWords(lines[index]).size == 1
                if (!lastContent && !alone) {
                    assertFalse(item.message, endsOnOpenFunctionWord(lines[index]))
                }
            }
            val joined = lines.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
            val source = if (item.style == "upper") stylize(item.message, "upper") else item.message.trim()
            if (messageWords(item.message).size >= item.boxes) {
                assertEquals(item.message, source, joined)
            } else {
                assertTrue(item.message, lines.all { it.isNotBlank() })
            }
        }
    }

    private data class Case(val message: String, val boxes: Int, val style: String, val expected: List<String>)

    private data class Rewrite(val message: String, val candidate: Candidate, val lines: List<String>)

    private fun joined(lines: List<String>) =
        lines.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim().lowercase()
}

class CaptionExampleTest {
    @Test
    fun everyTemplateHasTwoOrThreeGermanCaptions() {
        val root = listOf(File("app/src/main/assets"), File("../app/src/main/assets"))
            .first { File(it, "catalog.json").isFile }
        val catalog = JSONObject(File(root, "catalog.json").readText())
        val examples = JSONObject(File(root, "caption-examples.json").readText())
        val templates = catalog.getJSONArray("templates")
        val banned = listOf("biden", "obama", "hitler", "imgflip", "upvote")
        assertEquals(templates.length(), examples.length())
        for (index in 0 until templates.length()) {
            val obj = templates.getJSONObject(index)
            val id = obj.getString("id")
            val boxes = obj.getInt("boxes")
            val groups = examples.getJSONArray(id)
            assertTrue(id, groups.length() in 2..3)
            for (group in 0 until groups.length()) {
                val lines = groups.getJSONArray(group)
                assertEquals(id, boxes, lines.length())
                for (lineIndex in 0 until lines.length()) {
                    val line = lines.getString(lineIndex)
                    assertTrue(id, line.isNotBlank())
                    val lower = line.lowercase()
                    assertTrue(id + " " + line, banned.none { it in lower })
                    if (dominantLang(line) == "en") assertTrue(id + " " + line, isCatchphrase(line, id))
                    assertFalse(id + " " + line, exampleMatchesTestSentence(line))
                }
            }
        }
    }

    @Test
    fun everyTemplateHasCuratedEnglishCaptions() {
        val root = listOf(File("app/src/main/assets"), File("../app/src/main/assets"))
            .first { File(it, "catalog.json").isFile }
        val catalog = JSONObject(File(root, "catalog.json").readText())
        val examples = JSONObject(File(root, "caption-examples-en.json").readText())
        val templates = catalog.getJSONArray("templates")
        val banned = listOf("biden", "obama", "hitler", "imgflip", "upvote", "trump", "putin", "fuck", "shit")
        assertEquals(templates.length(), examples.length())
        for (index in 0 until templates.length()) {
            val obj = templates.getJSONObject(index)
            val id = obj.getString("id")
            val boxes = obj.getInt("boxes")
            val meaningEn = obj.optString("meaningEn")
            assertTrue(id, meaningEn.isNotBlank())
            val groups = examples.getJSONArray(id)
            assertTrue(id, groups.length() in 2..3)
            for (group in 0 until groups.length()) {
                val lines = groups.getJSONArray(group)
                assertEquals(id, boxes, lines.length())
                for (lineIndex in 0 until lines.length()) {
                    val line = lines.getString(lineIndex)
                    assertTrue(id, line.isNotBlank())
                    val lower = line.lowercase()
                    assertTrue(id + " " + line, banned.none { it in lower })
                    if (!isCatchphrase(line, id)) {
                        assertTrue(id + " " + line, dominantLang(line) != "de")
                        assertTrue(id + " " + line, dominantLang(line) != "es")
                    }
                    assertFalse(id + " " + line, exampleMatchesTestSentence(line))
                }
            }
        }
    }
}

private val TEST_SENTENCES = listOf(
    "schon wieder stau auf der a8",
    "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
    "hab die pruefung bestanden",
    "hab die prüfung bestanden",
    "wer kommt heute abend mit ins kino",
    "ihr werdet am ende alles verlieren",
    "das wetter ist so schlecht ich bleib im bett",
    "wer hat den letzten kaffee getrunken",
    "mein chef will dass ich am wochenende arbeite",
    "my boss wants me to work on the weekend",
    "who drank the last coffee",
    "traffic jam again",
    "passed my exam",
    "diet starts tomorrow i promise",
)

private fun exampleMatchesTestSentence(line: String): Boolean {
    val norm = normCaptionLine(line)
    if (norm.split(' ').size < 3) return false
    return TEST_SENTENCES.any { sentence ->
        val message = normCaptionLine(sentence)
        norm.length >= 12 && norm in message
    }
}

class PromptBuilderTest {
    @Test
    fun oneCallKeepsThreeTemplatesAndFiveExamples() {
        val briefs = (1..8).map { i ->
            Brief(
                id = "id$i",
                name = "Name $i",
                boxes = 2,
                meaning = "bedeutung ".repeat(40) + "äöüß",
                roles = fieldRoles(if (i == 1) "drake" else "id$i", 2),
                examples = (1..5).map { n -> listOf("abgelehnt $i $n", "bevorzugt $i $n") },
            )
        }
        val prompt = buildPrompt("Kannst du das heute noch schaffen, bitte ohne Extrawege?", briefs, listOf("Salat war gestern"))
        assertTrue("tokens=${prompt.approxTokens} chars=${prompt.characters}", prompt.approxTokens <= 1000)
        assertTrue(prompt.user.contains("id1"))
        assertTrue(prompt.user.contains("id3"))
        assertFalse(prompt.user.contains("id4"))
        assertTrue(prompt.user.contains("abgelehnt 1 1"))
        assertTrue(prompt.user.contains("abgelehnt 1 5"))
        assertTrue(prompt.user.contains("oben abgelehnt"))
        assertTrue(prompt.user.contains("Salat war gestern"))
        assertTrue(prompt.user.contains("Context"))
        assertTrue(prompt.system.contains("JSON"))
        assertTrue(prompt.system.contains("caption language matches the message"))
        assertTrue(prompt.system.contains("catchphrase"))
        assertTrue(prompt.system.contains("slash"))
        assertFalse(prompt.system.contains("It's a trap"))
        assertEquals(
            "Die Zeile wiederholt die Nachricht. Schreib eine neue Einleitung und eine eigene Pointe.",
            reasonText("woertlich", "de"),
        )
        assertTrue(reasonText("sprache", "en").startsWith("Wrong language"))
        assertTrue(retryHint("woertlich", "de").startsWith("Die letzten Zeilen"))
        assertTrue(followUpHint(listOf("fine" to "woertlich"), "de").contains(REPHRASE_HINT))
        assertTrue(FOLLOW_UP_TEMPERATURE in 0.5..0.6)
        assertTrue(MESSAGE_DISTRACTORS.size >= 30)
        val banned = TEST_SENTENCES.map { normCaptionLine(it) }.toSet()
        assertTrue(MESSAGE_DISTRACTORS.none { normCaptionLine(it) in banned })
        val same = parseSuggestions(
            """{"fine":{"z1":"Montag","z2":"Kein Bock"},"drake":{"z1":"Montag","z2":"Kein Bock"}}""",
            listOf(Candidate("fine", 2, "upper"), Candidate("drake", 2, "upper")),
            "der server brennt und niemand merkt es",
        )
        assertTrue(same[0].fromModel)
        assertEquals("doppelt", same[1].reason)
        val flooded = """{"exit":{"z1":"Bitte gehen","z2":"Tuer bleibt"},"fine":{"z1":"Qualm""" + "\n\n\n\n"
        val repaired = repairModelJson(flooded)
        assertTrue(repaired, repaired.contains(""""z2":"Tuer bleibt""""))
        val saved = parseSuggestions(flooded, listOf(Candidate("exit", 2, "upper"), Candidate("fine", 2, "upper")), "der server qualmt laut")
        assertEquals("exit", saved[0].templateId)
        assertEquals(listOf("Bitte gehen", "Tuer bleibt"), saved[0].lines)
        assertTrue(saved[0].fromModel)
        val ordered = orderByRules(
            listOf(
                Suggestion("drake", listOf("schon wieder stau auf der a8", "nochmal"), true),
                Suggestion("fine", listOf("Autobahn steht", "Zu teuer das"), true),
                Suggestion("exit", listOf("RAUS", "HIER"), false, "fehlt"),
            ),
            "schon wieder stau auf der a8",
        )
        assertEquals(listOf("fine", "drake", "exit"), ordered.map { it.templateId })
        val wish = parseSuggestions(
            """{"sohappy":{"z1":"Wochenende frei wünsch","z2":"ich mir Aber Chef sagt nein"}}""",
            listOf(Candidate("sohappy", 2, "upper", name = "I Would Be So Happy")),
            "mein chef will dass ich am wochenende arbeite",
        ).single()
        assertEquals(listOf("Wochenende frei wünsch ich mir", "Aber Chef sagt nein"), wish.lines)
        assertTrue(wish.fromModel)
        val goneSplit = parseSuggestions(
            """{"gone":{"z1":"Alles wird weg","z2":"sein Enttäuschung"}}""",
            listOf(Candidate("gone", 2, "upper", name = "And It's Gone")),
            "ihr werdet am ende alles verlieren",
        ).single()
        assertEquals(listOf("Alles wird weg sein", "Enttäuschung"), goneSplit.lines)
        assertTrue(goneSplit.fromModel)
        val keptOnly = listOf(
            Suggestion("fine", listOf("Qualm", "Ruhe"), true),
            Suggestion("drake", listOf("X", "Y"), false, "fehlt"),
        ).filter { it.fromModel }
        assertEquals(listOf("fine"), keptOnly.map { it.templateId })
        val typos = orderByRules(
            listOf(
                Suggestion("regret", listOf("Diese Ausgaben sind schlim"), true),
                Suggestion("fine", listOf("Geld ist weg", "Kein Rest"), true),
            ),
            "ich wollte nur kurz einkaufen und hab jetzt 200 euro ausgegeben",
        )
        assertEquals(listOf("fine", "regret"), typos.map { it.templateId })
        assertTrue(hasBrokenWord("Diese Ausgaben sind schlim"))
        assertTrue(hasBrokenWord("Müdeheit"))
        assertFalse(hasBrokenWord("hab nur kurz geschaut"))
        val message = floatArrayOf(1f, 0f)
        val close = relevanceMargin(message, floatArrayOf(0.9f, 0.1f), listOf(floatArrayOf(0f, 1f)))
        val far = relevanceMargin(message, floatArrayOf(0.2f, 0.8f), listOf(floatArrayOf(0f, 1f)))
        assertTrue(close > MIN_RELEVANCE_MARGIN)
        assertTrue(far < MIN_RELEVANCE_MARGIN)
        val ownRank = rankAgainstMessages(message, floatArrayOf(1f, 0f), listOf(floatArrayOf(0f, 1f)))
        val otherRank = rankAgainstMessages(message, floatArrayOf(0f, 1f), listOf(floatArrayOf(0f, 1f)))
        assertTrue(ownRank > 0f)
        assertTrue(otherRank < 0f)
        val combined = relevanceScore(
            message,
            floatArrayOf(0f, 1f),
            listOf(floatArrayOf(0f, 1f)),
            listOf(floatArrayOf(0f, 1f)),
        )
        assertTrue(combined < MIN_RELEVANCE_MARGIN)
        assertTrue(prompt.user.contains("Do not copy the message."))
        assertTrue(prompt.user.contains("Write in German."))
        assertTrue(prompt.user.contains("\"id1\":{\"z1\":\"<deutsche Zeile>\""))
        assertFalse(prompt.system.contains("Wortreihenfolge"))
        val english = buildPrompt("I finally passed the exam", briefs.take(1))
        assertTrue(english.user.contains("Write in English."))
        assertTrue(english.user.contains("<english line>"))
        val fromContext = buildPrompt("ok", briefs.take(1), listOf("Wir gehen heute ins Kino"))
        assertTrue(fromContext.user.contains("Write in German."))
        assertTrue(fromContext.user.contains("<deutsche Zeile>"))
    }

    @Test
    fun keepsShortContextAndClipsItWhenTheChatIsLong() {
        val brief = Brief("drake", "Drake", 2, "Vergleich", listOf(listOf("oben", "unten")), roles = fieldRoles("drake", 2))
        val withContext = buildPrompt("Pizza", listOf(brief), listOf("Salat war gestern"))
        assertTrue(withContext.user.contains("Salat war gestern"))
        assertTrue(withContext.user.contains("Context"))
        assertTrue(withContext.user.contains("oben abgelehnt, unten bevorzugt"))
        val briefs = (1..3).map { i ->
            Brief(
                id = "id$i",
                name = "Name $i",
                boxes = 2,
                meaning = "bedeutung ".repeat(400),
                examples = (1..5).map { listOf("alpha ".repeat(40), "kappa ".repeat(40)) },
            )
        }
        val huge = List(40) { "wort ".repeat(80) }
        val tight = buildPrompt("hi", briefs, huge)
        assertTrue("tokens=${tight.approxTokens}", tight.approxTokens <= 1000)
        assertTrue(tight.user.contains("Context"))
        assertFalse(tight.user.contains("wort ".repeat(40)))
        assertTrue(tight.user.contains("id1"))
        assertFalse(tight.user.contains("id4"))
    }

    @Test
    fun clipsMeaningAtSentenceEnd() {
        val meaning = "Dieser erste Satz bleibt vollständig. " + "mittenimwort ".repeat(30)
        val brief = Brief("drake", "Drake", 2, meaning, listOf(listOf("oben", "unten")), roles = fieldRoles("drake", 2))
        val prompt = buildPrompt("Hallo", listOf(brief))
        assertTrue(prompt.user.contains("Dieser erste Satz bleibt vollständig."))
        assertFalse(prompt.user.contains("mittenimwort"))
    }
}

class MemeLayoutTest {
    private val measurer = TextMeasurer { text, fontPx, _ ->
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        val width = lines.maxOf { it.length } * fontPx
        val height = lines.size * fontPx
        width to height
    }

    @Test
    fun shrinksFontAndWrapsOnWords() {
        val fields = listOf(FieldSpec(scaleX = 1f, scaleY = 0.25f))
        val placed = placeText(fields, listOf("Größe und Straße bleiben lesbar im Feld"), 400, 200, measurer)
        val line = placed.single()
        assertTrue(line.text.contains("GRÖSSE"))
        assertTrue(line.text.contains("STRASSE"))
        assertEquals(400, line.boxW)
        assertEquals(50, line.boxH)
        assertTrue(line.strokePx in 1..3)
        val (w, h) = measurer.measure(line.text, line.fontPx, line.fontKey)
        assertTrue("w=$w box=${line.boxW}", w <= line.boxW)
        assertTrue("h=$h box=${line.boxH}", h <= line.boxH)
    }

    @Test
    fun nearestTemplateWins() {
        val index = MemIndex(
            dim = 2,
            space = "hash-v1",
            queryPrefix = "",
            points = listOf(
                IndexPoint("a", Kind.TEXT, emptyList()),
                IndexPoint("b", Kind.TEXT, emptyList()),
            ),
            vectors = floatArrayOf(1f, 0f, 0f, 1f),
        )
        val hits = searchTemplates(index, floatArrayOf(1f, 0f), mapOf("a" to 2, "b" to 2))
        assertEquals("a", hits.first().templateId)
    }

    @Test
    fun anchorFollowsConfig() {
        val fields = listOf(FieldSpec(anchorX = 0.5f, anchorY = 0.8f, angle = 10f, font = "impact"))
        val placed = placeText(fields, listOf("Hi"), 200, 100, measurer).single()
        assertEquals(100, placed.anchorX)
        assertEquals(80, placed.anchorY)
        assertEquals(10f, placed.angle)
        assertEquals("Anton-Regular.ttf", fontFile("impact"))
        assertEquals("TitilliumWeb-Black.ttf", fontFile("thick"))
        assertEquals("TitilliumWeb-SemiBold.ttf", fontFile("thin"))
        assertEquals("Kalam-Regular.ttf", fontFile("comic"))
        assertEquals("NotoSans-Bold.ttf", fontFile("unknown"))
    }
}

class TextBoxGoldenTest {
    private val measurer = TextMeasurer { text, fontPx, _ ->
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        val width = lines.maxOf { (it.length * fontPx * 0.62f).toInt().coerceAtLeast(1) }
        val height = (lines.size * fontPx * 1.25f).toInt().coerceAtLeast(1)
        width to height
    }

    @Test
    fun tenTemplatesMatchMemegenBoxWithinThreePercent() {
        val samples = listOf(
            Sample("cmm", 720, 572, FieldSpec(anchorX = 0.33f, anchorY = 0.33f, scaleX = 0.46f, scaleY = 0.25f, angle = 23f, font = "thin", color = "black", align = "center")),
            Sample("drake", 446, 698, FieldSpec(anchorX = 0f, anchorY = 0f, scaleX = 1f, scaleY = 0.2f, font = "thick", color = "white")),
            Sample("drake", 446, 698, FieldSpec(anchorX = 0f, anchorY = 0.8f, scaleX = 1f, scaleY = 0.2f, font = "thick", color = "white")),
            Sample("db", 720, 480, FieldSpec(style = "default", anchorX = 0.12f, anchorY = 0.7f, scaleX = 0.325f, scaleY = 0.1f, font = "thick", color = "white")),
            Sample("db", 720, 480, FieldSpec(style = "default", anchorX = 0.55f, anchorY = 0.45f, scaleX = 0.175f, scaleY = 0.1f, font = "thick", color = "white")),
            Sample("db", 720, 480, FieldSpec(style = "default", anchorX = 0.74f, anchorY = 0.66f, scaleX = 0.2f, scaleY = 0.1f, font = "thick", color = "white")),
            Sample("ds", 458, 684, FieldSpec(style = "none", anchorX = 0.085f, anchorY = 0.085f, scaleX = 0.32f, scaleY = 0.095f, angle = 12.5f, font = "thin", color = "black")),
            Sample("ds", 458, 684, FieldSpec(style = "none", anchorX = 0.455f, anchorY = 0.055f, scaleX = 0.23f, scaleY = 0.09f, angle = 10.5f, font = "thin", color = "black")),
            Sample("ds", 458, 684, FieldSpec(anchorX = 0.04f, anchorY = 0.82f, scaleX = 0.92f, scaleY = 0.15f, font = "thick", color = "white")),
            Sample("buzz", 500, 380, FieldSpec(anchorY = 0.8f, scaleY = 0.2f)),
            Sample("fry", 603, 452, FieldSpec(anchorY = 0f, scaleY = 0.2f)),
            Sample("slap", 646, 398, FieldSpec(anchorX = 0.18f, anchorY = 0.13f, scaleX = 0.35f, scaleY = 0.3f, angle = 44f)),
            Sample("exit", 720, 644, FieldSpec(style = "default", anchorX = 0.46f, anchorY = 0.85f, scaleX = 0.32f, scaleY = 0.1f, angle = 9f, font = "thin", color = "white")),
            Sample("crow", 700, 703, FieldSpec(anchorX = 0.615f, anchorY = 0.72f, scaleX = 0.3f, scaleY = 0.2f, angle = 12f, font = "comic", color = "black")),
            Sample("doge", 620, 620, FieldSpec(anchorY = 0.8f, scaleY = 0.2f)),
        )
        val ids = samples.map { it.id }.toSet()
        assertTrue(ids.containsAll(listOf("cmm", "drake", "db", "ds")))
        assertTrue(ids.size >= 10)
        for (sample in samples) {
            val placed = placeText(listOf(sample.field), listOf("ich denke ihr verliert alles"), sample.width, sample.height, measurer).single()
            val ref = memegenReference(sample.width, sample.height, sample.field)
            val tol = sample.width * 0.03f
            assertTrue("${sample.id} anchor x", kotlin.math.abs(placed.anchorX - ref.anchorX) < tol)
            assertTrue("${sample.id} anchor y", kotlin.math.abs(placed.anchorY - ref.anchorY) < tol)
            assertEquals(ref.boxW, placed.boxW)
            assertEquals(ref.boxH, placed.boxH)
            assertEquals(sample.field.angle, placed.angle)
            assertEquals(sample.field.align, placed.align)
            assertEquals(sample.field.color, placed.color)
            assertEquals(sample.field.font, placed.fontKey)
            val (cx, cy) = visualCenter(placed.anchorX, placed.anchorY, placed.boxW, placed.boxH, placed.angle)
            assertTrue("${sample.id} cx $cx vs ${ref.centerX}", kotlin.math.abs(cx - ref.centerX) < tol)
            assertTrue("${sample.id} cy $cy vs ${ref.centerY}", kotlin.math.abs(cy - ref.centerY) < tol)
            val (w, h) = measurer.measure(placed.text, placed.fontPx, placed.fontKey)
            assertTrue("${sample.id} text wider than box $w>${placed.boxW}", w <= placed.boxW)
            assertTrue("${sample.id} text taller than box $h>${placed.boxH}", h <= placed.boxH)
        }
    }

    private fun memegenReference(imageW: Int, imageH: Int, field: FieldSpec): Ref {
        val anchorX = (imageW * field.anchorX).toInt()
        val anchorY = (imageH * field.anchorY).toInt()
        val boxW = (imageW * field.scaleX).toInt().coerceAtLeast(1)
        val boxH = (imageH * field.scaleY).toInt().coerceAtLeast(1)
        val rad = Math.toRadians(field.angle.toDouble())
        val cos = kotlin.math.abs(kotlin.math.cos(rad))
        val sin = kotlin.math.abs(kotlin.math.sin(rad))
        val grownW = boxW * cos + boxH * sin
        val grownH = boxW * sin + boxH * cos
        return Ref(anchorX, anchorY, boxW, boxH, (anchorX + grownW / 2.0).toFloat(), (anchorY + grownH / 2.0).toFloat())
    }

    private data class Sample(val id: String, val width: Int, val height: Int, val field: FieldSpec)
    private data class Ref(val anchorX: Int, val anchorY: Int, val boxW: Int, val boxH: Int, val centerX: Float, val centerY: Float)
}

class InsertPlanTest {
    @Test
    fun clipboardIsDefaultThenCommitThenShare() {
        assertEquals(
            listOf(InsertStep.CLIPBOARD, InsertStep.COMMIT_CONTENT, InsertStep.SHARE),
            insertPlan(InsertPreference.CLIPBOARD, fieldAcceptsPng = true),
        )
    }

    @Test
    fun autoUsesCommitWhenTheFieldAcceptsPng() {
        assertEquals(InsertStep.COMMIT_CONTENT, insertPlan(InsertPreference.AUTO, true).first())
        assertEquals(InsertStep.CLIPBOARD, insertPlan(InsertPreference.AUTO, false).first())
        assertEquals(InsertStep.SHARE, insertPlan(InsertPreference.COMMIT_CONTENT, false).last())
        assertFalse(insertPlan(InsertPreference.COMMIT_CONTENT, false).contains(InsertStep.COMMIT_CONTENT))
        assertTrue(fieldAcceptsPng(arrayOf("image/png")))
        assertFalse(fieldAcceptsPng(arrayOf("text/plain")))
    }
}

class ChatContextTest {
    @Test
    fun keepsMessageTextAndDropsChrome() {
        val lines = listOf(
            ScreenLine("14:35", top = 10),
            ScreenLine("Heute", top = 20),
            ScreenLine("Nachrichten sind Ende-zu-Ende verschlüsselt", top = 30),
            ScreenLine("Hallo du", viewId = "com.whatsapp:id/message_text", top = 100),
            ScreenLine("Was geht", viewId = "com.whatsapp:id/message_text", top = 200),
            ScreenLine("Gelesen", top = 210),
            ScreenLine("mein entwurf", editable = true, top = 900),
        )
        assertEquals(listOf("Hallo du", "Was geht"), recentMessages(lines))
    }

    @Test
    fun fallsBackWhenIdsAreMissing() {
        val lines = listOf(
            ScreenLine("09:01", top = 1),
            ScreenLine("Treffen wir uns später", top = 40),
        )
        assertEquals(listOf("Treffen wir uns später"), recentMessages(lines))
    }

    @Test
    fun picksTheWhatsAppEntryField() {
        val profile = defaultWhatsAppProfile()
        val candidates = listOf(
            InputCandidate("other", 100, editable = true, visible = true),
            InputCandidate("com.whatsapp:id/entry", 800, editable = true, visible = true),
            InputCandidate("com.whatsapp:id/entry", 100, editable = false, visible = true),
        )
        assertEquals(1, pickInputIndex(candidates, 1000, profile))
        val bottom = listOf(
            InputCandidate("android:id/title", 200, editable = true, visible = true),
            InputCandidate(null, 900, editable = true, visible = true),
        )
        assertEquals(1, pickInputIndex(bottom, 1000, profile))
    }

    @Test
    fun searchTextKeepsTheTypedLineFirst() {
        val text = searchText("Pizza", listOf("Salat war gestern", "Pizza"))
        assertTrue(text.startsWith("Pizza"))
        assertTrue(text.contains("Salat war gestern"))
        assertFalse(text.contains("Pizza\nPizza"))
        val capped = searchText("kurz", List(50) { "x".repeat(40) })
        assertTrue(capped.length < 600)
    }

    @Test
    fun accessibilityPasteRunsOnlyAfterAFailedImePaste() {
        assertEquals(listOf("clipboard", "ime"), pasteSteps(imeReportedSuccess = true, accessibilityConnected = true))
        assertEquals(listOf("clipboard", "ime", "a11y"), pasteSteps(false, true))
        assertEquals(listOf("clipboard", "ime"), pasteSteps(false, false))
        assertFalse(pasteSteps(false, true).any { it.contains("send") })
    }

    @Test
    fun profileParserReadsWhatsAppIds() {
        val json = """
            {"packageName":"com.whatsapp","knownIds":{"messageText":["com.whatsapp:id/message_text"],"messageInput":["com.whatsapp:id/entry"],"sendButton":["com.whatsapp:id/send"]},"heuristics":{"messageInputBottomFraction":0.7}}
        """.trimIndent()
        val profile = parseWhatsAppProfile(json)
        assertEquals("com.whatsapp:id/entry", profile.messageInputIds.single())
        assertEquals("com.whatsapp:id/send", profile.sendButtonIds.single())
        assertEquals(0.7, profile.messageInputBottomFraction, 0.001)
        assertTrue(profile.sendButtonIds.isNotEmpty())
    }
}
