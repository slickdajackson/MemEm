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
        assertEquals(listOf("HEUTE", "NOCH", "FERTIG"), out[0].lines)
        assertTrue(out[0].fromModel)
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
        assertTrue(preservesMessageOrder(message, out[0].lines))
        assertEquals(3, out.size)
        assertFalse(out.map { it.templateId }.contains("nope"))
    }

    @Test
    fun keepsGemmaLinesWhenContentWordsStayInOrder() {
        val raw = """{"memes":[{"template":"b","lines":["Ihr werdet am Ende","alles verlieren"]}]}"""
        val message = "ihr werdet am ende alles verlieren"
        val out = parseSuggestions(raw, listOf(Candidate("b", 2, "upper")), message)
        assertEquals(listOf("Ihr werdet am Ende", "alles verlieren"), out[0].lines)
        assertTrue(preservesMessageOrder(message, out[0].lines))
    }

    @Test
    fun interleavedGemmaLinesFallBackToTheLiteralSentence() {
        val raw = """{"memes":[{"template":"b","lines":["IHR AM ALLES","WERDET ENDE VERLIEREN"]}]}"""
        val message = "ihr werdet am ende alles verlieren"
        val out = parseSuggestions(raw, listOf(Candidate("b", 2, "upper")), message)
        assertEquals(listOf("IHR WERDET AM ENDE", "ALLES VERLIEREN"), out[0].lines)
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

    @Test
    fun tenGermanSentencesSplitInOrder() {
        val cases = listOf(
            Case("ihr werdet am ende alles verlieren", 2, "upper", listOf("IHR WERDET AM ENDE", "ALLES VERLIEREN")),
            Case("Ich komme später, aber ich bringe Kuchen", 2, "upper", listOf("ICH KOMME SPÄTER,", "ABER ICH BRINGE KUCHEN")),
            Case("Wir gehen ins Kino und danach essen wir", 2, "none", listOf("Wir gehen ins Kino", "und danach essen wir")),
            Case("Kommst du heute mit, weil das Wetter hält?", 2, "upper", listOf("KOMMST DU HEUTE MIT,", "WEIL DAS WETTER HÄLT?")),
            Case("Kein Problem", 2, "upper", listOf("KEIN PROBLEM", "")),
            Case("Hilfe", 2, "upper", listOf("HILFE", "")),
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
                if (!lastContent) {
                    assertFalse(item.message, endsOnOpenFunctionWord(lines[index]))
                }
            }
            val joined = lines.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
            val source = if (item.style == "upper") stylize(item.message, "upper") else item.message.trim()
            assertEquals(item.message, source, joined)
        }
    }

    private data class Case(val message: String, val boxes: Int, val style: String, val expected: List<String>)
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

    @Test
    fun keepsShortContextAndDropsItWhenTheBudgetIsTight() {
        val brief = Brief("drake", "Drake", 2, "Vergleich", listOf(listOf("oben", "unten")))
        val withContext = buildPrompt("Pizza", listOf(brief), listOf("Salat war gestern"))
        assertTrue(withContext.user.contains("Salat war gestern"))
        assertTrue(withContext.user.contains("Kontext"))
        val briefs = (1..8).map { i ->
            Brief(
                id = "id$i",
                name = "Name $i",
                boxes = 2,
                meaning = "bedeutung ".repeat(40),
                examples = listOf(listOf("alpha beta gamma delta epsilon zeta eta theta iota", "kappa")),
            )
        }
        val huge = List(40) { "wort ".repeat(80) }
        val tight = buildPrompt("hi", briefs, huge)
        assertTrue(tight.approxTokens <= 700)
        assertFalse(tight.user.contains("Kontext"))
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
