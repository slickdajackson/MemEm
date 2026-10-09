package app.memem.engine

import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf("Hi", "x, y", ""), out[0].lines)
        assertTrue(out[0].fromModel)
        assertEquals("b", out[1].templateId)
        assertEquals("c", out[2].templateId)
        assertFalse(out[1].fromModel)
    }

    @Test
    fun replacesUnknownIds() {
        val raw = """{"memes":[{"template":"nope","lines":["a"]},{"template":"b","lines":["nur das"]}]}"""
        val out = parseSuggestions(raw, candidates, "hallo welt das ist ein langer satz mit vielen worten extra")
        assertEquals("b", out[0].templateId)
        assertEquals("nur das", out[0].lines[0])
        assertEquals("", out[0].lines[1])
        assertEquals(3, out.size)
        assertFalse(out.map { it.templateId }.contains("nope"))
    }

    @Test
    fun schemaHasEnumWithoutItemCounts() {
        val schema = memeSchema(listOf("drake", "fry"))
        assertTrue(schema.contains("drake"))
        assertTrue(schemaAvoidsCountConstraints(schema))
    }

    @Test
    fun capsWordsAndUppercasesUmlauts() {
        assertEquals("GRÖSSE STRASSE", stylize("größe straße", "upper"))
        val lines = fallbackLines("eins zwei drei vier fünf sechs sieben acht neun zehn", 1, "none")
        assertEquals("eins zwei drei vier fünf sechs sieben acht", lines.single())
    }
}

class PromptBuilderTest {
    @Test
    fun staysWithinTokenBudget() {
        val briefs = (1..8).map { i ->
            Brief(
                id = "id$i",
                name = "Name $i",
                boxes = 2,
                meaning = "bedeutung ".repeat(40) + "äöüß",
                examples = listOf(
                    listOf("alpha beta gamma delta epsilon zeta eta theta iota", "kappa"),
                    listOf("one two three four five six seven eight nine", "ten"),
                ),
            )
        }
        val prompt = buildPrompt("Kannst du das heute noch schaffen, bitte ohne Extrawege?", briefs)
        assertTrue("tokens=${prompt.approxTokens} chars=${prompt.characters}", prompt.approxTokens <= 700)
        briefs.forEach { assertTrue(prompt.user.contains(it.id)) }
        assertTrue(prompt.system.contains("JSON"))
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
        assertEquals("Kalam-Regular.ttf", fontFile("comic"))
        assertEquals("NotoSans-Bold.ttf", fontFile("unknown"))
    }
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
