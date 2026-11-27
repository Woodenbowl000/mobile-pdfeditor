package com.mobilepdf.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mobilepdf.editor.annot.Annot
import com.mobilepdf.editor.annot.MarkKind
import com.mobilepdf.editor.annot.ShapeKind
import com.mobilepdf.editor.annot.bounds
import com.mobilepdf.editor.annot.hit
import com.mobilepdf.editor.annot.moved
import com.mobilepdf.editor.annot.scaled
import com.mobilepdf.editor.data.Draft
import com.mobilepdf.editor.data.DraftStore
import com.mobilepdf.editor.pdf.PageText
import com.mobilepdf.editor.pdf.PdfOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.mobilepdf.editor.pdf.Compare.Kind as K

class LogicTest {
    @Test fun ranges() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(4), listOf(7, 8, 9)), PdfOps.parseRanges("1-3, 5; 8-", 10))
        assertEquals(listOf(listOf(0, 1)), PdfOps.parseRanges("-2", 10))
        for (bad in listOf("", "0", "4-2", "11", "a")) {
            assertTrue(bad, runCatching { PdfOps.parseRanges(bad, 10) }.isFailure)
        }
    }

    private fun line(text: String, top: Float): Pair<String, List<Rect?>> =
        text to text.mapIndexed { i, c -> if (c == ' ') null else Rect(0.1f + i * 0.02f, top, 0.118f + i * 0.02f, top + 0.02f) }

    private fun page(): PageText {
        val (a, ba) = line("Total due today", 0.1f)
        val (b, bb) = line("Invoice total due", 0.3f)
        return PageText("$a\n$b", ba + listOf(null) + bb)
    }

    @Test fun findIsCaseInsensitiveAndSplitsLines() {
        val p = page()
        assertEquals(2, p.find("total due", matchCase = false).size)
        assertEquals(1, p.find("Total due", matchCase = true).size)
        assertTrue(p.find("nothing", false).isEmpty())
        // a match spanning a line break gives one rect per line
        assertEquals(2, p.find("today\nInvoice", false).single().size)
    }

    @Test fun selectBetweenTwoTouchesCoversWholeLines() {
        val r = page().select(Offset(0.1f, 0.11f), Offset(0.2f, 0.31f))
        assertEquals(2, r.size)
        assertTrue(r[0].top < r[1].top)
    }

    @Test fun annotationGeometry() {
        val ink = Annot.Ink(1, 0, 0.01f, false, listOf(Offset(0.1f, 0.1f), Offset(0.5f, 0.1f)))
        assertTrue(ink.hit(Offset(0.3f, 0.101f), 0.01f, 1.4f))
        assertFalse(ink.hit(Offset(0.3f, 0.5f), 0.01f, 1.4f))
        val moved = ink.moved(Offset(0.1f, 0.2f)) as Annot.Ink
        assertEquals(Offset(0.2f, 0.3f), moved.pts[0])

        val box = Annot.Shape(2, 0, 0.004f, ShapeKind.RECT, Offset(0.2f, 0.2f), Offset(0.4f, 0.4f))
        val big = box.scaled(2f, 1.4f) as Annot.Shape
        assertEquals(0.1f, big.a.x, 1e-5f)
        assertEquals(0.5f, big.b.x, 1e-5f)
        assertEquals(box.bounds(1.4f).center.x, big.bounds(1.4f).center.x, 1e-5f)
    }

    @Test fun deviceTierDetection() {
        fun t(ram: Long, mc: Int, cores: Int = 8, sdk: Int = 34, go: Boolean = false) =
            com.mobilepdf.editor.pdf.DeviceBudget.classify(ram, mc, cores, sdk, go)
        val low = com.mobilepdf.editor.pdf.Tier.LOW
        val mid = com.mobilepdf.editor.pdf.Tier.MID
        val high = com.mobilepdf.editor.pdf.Tier.HIGH
        assertEquals(low, t(980, 96, 4, 24))          // the 1 GB Android 7 test emulator
        assertEquals(low, t(1850, 192, 4, 30, go = true)) // 2 GB Android Go
        assertEquals(low, t(1850, 256, 8))            // 2 GB phone, whatever its CPU
        assertEquals(low, t(2800, 192, 8, 25))        // 3 GB on Android 7: bitmaps in the Java heap
        assertEquals(mid, t(3600, 256, 8))            // 4 GB
        assertEquals(mid, t(7400, 512, 4))            // lots of RAM, weak CPU
        assertEquals(high, t(7400, 512, 8))           // e.g. an 8 GB Galaxy A15
    }

    /** A table row in one font: cells far apart are separate runs; ordinary word spaces are not. */
    @Test fun tableCellsAreSeparateRuns() {
        val em = 0.02f // font size as a fraction of page width
        val text = StringBuilder(); val boxes = ArrayList<Rect?>(); val sizes = ArrayList<Float>(); val fonts = ArrayList<String?>()
        var x = 0.05f
        fun word(w: String, gapBefore: Float) {
            if (text.isNotEmpty()) { text.append(' '); boxes += null; sizes += 0f; fonts += null }
            x += gapBefore
            for (c in w) { text.append(c); boxes += Rect(x, 0.1f, x + 0.5f * em, 0.12f); sizes += em; fonts += "Helvetica"; x += 0.5f * em }
        }
        word("Blue", 0f); word("pen", 0.25f * em)  // one cell: normal word space
        word("12", 3f * em)                       // next cell
        word("4.50", 3f * em)                     // next cell
        val runs = com.mobilepdf.editor.pdf.PageText(text.toString(), boxes, sizes, fonts).runs
        assertEquals(listOf("Blue pen", "12", "4.50"), runs.map { it.text })
    }

    /** Find & redact patterns: the checks that cut false positives (PdfCraft patterns.rs). */
    @Test fun redactPatterns() {
        fun hits(p: com.mobilepdf.editor.pdf.RedactPattern, s: String) = p.regex.findAll(s).map { it.value }.filter(p::accept).toList()
        assertEquals(listOf("ali.khan@example.com"), hits(com.mobilepdf.editor.pdf.RedactPattern.EMAIL, "Mail ali.khan@example.com today"))
        assertEquals(listOf("4111 1111 1111 1111"), hits(com.mobilepdf.editor.pdf.RedactPattern.CARD, "Card 4111 1111 1111 1111, ref 4111 1111 1111 1112"))
        assertEquals(listOf("GB82 WEST 1234 5698 7654 32"), hits(com.mobilepdf.editor.pdf.RedactPattern.IBAN, "IBAN GB82 WEST 1234 5698 7654 32 ok"))
        assertTrue(hits(com.mobilepdf.editor.pdf.RedactPattern.PHONE, "Call +92 300 1234567 now").isNotEmpty())
        assertTrue("a year is not a phone number", hits(com.mobilepdf.editor.pdf.RedactPattern.PHONE, "In 2024 we grew").isEmpty())
        assertEquals(listOf("12/03/2024", "5 March 2024"), hits(com.mobilepdf.editor.pdf.RedactPattern.DATE, "From 12/03/2024 to 5 March 2024"))
        assertEquals(listOf("123-45-6789"), hits(com.mobilepdf.editor.pdf.RedactPattern.SSN, "SSN 123-45-6789."))
    }

    /** Form calculations: the built-ins forms use, run natively (no JavaScript engine). */
    @Test fun formCalculations() {
        val C = com.mobilepdf.editor.pdf.FormCalc
        assertEquals(com.mobilepdf.editor.pdf.Calc("SUM", listOf("Line1", "Line2")), C.parseCalc("AFSimple_Calculate(\"SUM\", new Array (\"Line1\", \"Line2\"));"))
        assertEquals(listOf("A", "B", "C"), C.parseCalc("AFSimple_Calculate(\"AVG\", \"A, B, C\")")!!.fields)
        assertEquals("Qty * Price", C.parseCalc("/** BVCALC Qty * Price EVCALC **/ event.value = AFMakeNumber(getField(\"Qty\").value)*AFMakeNumber(getField(\"Price\").value)")!!.expr)
        val money = C.parseFormat("AFNumber_Format(2, 0, 0, 0, \"$\", true);")!!
        assertEquals("$1,234.50", C.format(1234.5, money))
        assertEquals("-$3.00", C.format(-3.0, money))
        assertEquals("1.234,50 €", C.format(1234.5, com.mobilepdf.editor.pdf.NumFormat(2, sepStyle = 2, currency = " €", prepend = false)))
        assertEquals("12.5%", C.format(0.125, C.parseFormat("AFPercent_Format(1, 0)")))
        assertEquals(1234.5, C.number("$1,234.50", money), 1e-9)
        assertEquals(1234.5, C.number("1.234,50", com.mobilepdf.editor.pdf.NumFormat(2, sepStyle = 2)), 1e-9)
        assertEquals(-12.0, C.number("(12)"), 1e-9)
        assertEquals(14.0, C.eval("A * (B + 2) - 1") { mapOf("A" to 3.0, "B" to 3.0)[it] ?: 0.0 }, 1e-9)
        assertEquals(6.0, C.eval("Unit\\ price * 2") { if (it == "Unit price") 3.0 else 0.0 }, 1e-9)

        // an invoice: line totals = qty * price, subtotal = their sum, in the form's calculation order
        fun field(name: String, calc: com.mobilepdf.editor.pdf.Calc? = null, order: Int = Int.MAX_VALUE, fmt: com.mobilepdf.editor.pdf.NumFormat? = null) =
            com.mobilepdf.editor.pdf.FormField(name, name, com.mobilepdf.editor.pdf.FieldType.TEXT, "", emptyList(), false, false, emptyList(), calc, order, fmt)
        val fields = listOf(
            field("Qty.0"), field("Price.0", fmt = money), field("Qty.1"), field("Price.1", fmt = money),
            field("Subtotal", com.mobilepdf.editor.pdf.Calc("SUM", listOf("Line")), order = 2, fmt = money),
            field("Line.0", com.mobilepdf.editor.pdf.Calc("EXPR", expr = "Qty.0 * Price.0"), order = 0, fmt = money),
            field("Line.1", com.mobilepdf.editor.pdf.Calc("EXPR", expr = "Qty.1 * Price.1"), order = 1, fmt = money),
        )
        val values = mapOf("Qty.0" to "2", "Price.0" to "$10.00", "Qty.1" to "3", "Price.1" to "$1.50")
        val out = C.recompute(fields) { values[it.name] ?: it.value }
        assertEquals("$20.00", out["Line.0"]); assertEquals("$4.50", out["Line.1"])
        assertEquals("parent name sums its children, after them", "$24.50", out["Subtotal"])
    }

    /** Compare: a line diff that keeps the shared lines in order. */
    @Test fun compareDiff() {
        val d = com.mobilepdf.editor.pdf.Compare.diff(listOf("Title", "Price 100", "Thanks"), listOf("Title", "Price 120", "Thanks", "PS"))
        assertEquals(listOf(K.SAME, K.REMOVED, K.ADDED, K.SAME, K.ADDED), d.map { it.kind })
        assertEquals(listOf("Price 100", "Price 120", "PS"), d.filter { it.kind != K.SAME }.map { it.text })
    }

    /** The DER writer/reader behind digital signatures. */
    @Test fun derEncoding() {
        val D = com.mobilepdf.editor.pdf.Der
        assertEquals("06092a864886f70d010702", D.oid("1.2.840.113549.1.7.2").joinToString("") { "%02x".format(it) })
        val n = D.parse(D.seq(D.oid("2.16.840.1.101.3.4.2.1"), D.NULL)).first
        assertEquals("2.16.840.1.101.3.4.2.1", n.children[0].oidString())
        val long = ByteArray(300) { 7 }
        assertEquals(300, D.parse(D.octets(long)).first.value.size)
        // DER SET OF is sorted
        assertEquals(listOf(1, 2), D.parse(D.set(D.int(2), D.int(1))).first.children.map { it.value[0].toInt() })
    }

    /** Review clouds: bumps start on the box edge and bulge outwards, never inwards. */
    @Test fun cloudOutline() {
        val aspect = 1.41f
        val pts = com.mobilepdf.editor.annot.cloudOutline(Offset(0.2f, 0.2f), Offset(0.6f, 0.4f), aspect, 0.006f)
        assertTrue(pts.size > 40)
        val r = 0.006f * 2.5f
        for (p in pts) {
            val insideX = p.x > 0.2f + 1e-4f && p.x < 0.6f - 1e-4f
            val insideY = p.y > 0.2f + 1e-4f && p.y < 0.4f - 1e-4f
            assertFalse("no point inside the box: $p", insideX && insideY)
            assertTrue("within a bump of the box: $p", p.x in 0.2f - r * 1.5f..0.6f + r * 1.5f && p.y in 0.2f - r * 1.5f / aspect..0.4f + r * 1.5f / aspect)
        }
    }

    /** Urdu: exact strings, messages with names/numbers, and English untouched when Urdu is off. */
    @Test fun urduTranslation() {
        val I = com.mobilepdf.editor.ui.I18n
        try {
            I.lang = "ur"
            assertEquals("محفوظ کریں", I.tr("Save"))
            assertEquals("Ali کے دستخط · درست", I.tr("Signed by Ali · Valid"))
            assertEquals("صفحہ 3 پڑھا جا رہا ہے…", I.tr("Reading page 3…"))
            assertEquals("Some unknown sentence", I.tr("Some unknown sentence"))
            I.lang = "en"
            assertEquals("Save", I.tr("Save"))
        } finally { I.lang = "system" }
    }

    @Test fun fieldLabels() {
        assertEquals("Full name", com.mobilepdf.editor.pdf.Forms.humanize("fullName"))
        assertEquals("Date of birth", com.mobilepdf.editor.pdf.Forms.humanize("date_of_birth"))
        assertEquals("Zip code", com.mobilepdf.editor.pdf.Forms.humanize("ZIP-code"))
    }

    @Test fun draftRoundTrip() {
        val annots = mapOf(
            0 to listOf(
                Annot.Ink(1, 0xFFFF0000.toInt(), 0.01f, true, listOf(Offset(0.1f, 0.2f), Offset(0.3f, 0.4f))),
                Annot.Shape(2, 1, 0.005f, ShapeKind.ARROW, Offset(0.1f, 0.1f), Offset(0.2f, 0.2f)),
                Annot.Mark(3, 2, MarkKind.STRIKE, listOf(Rect(0.1f, 0.1f, 0.2f, 0.12f))),
            ),
            4 to listOf(
                Annot.Text(4, 3, Offset(0.5f, 0.5f), "Hi\nthere", 0.04f),
                Annot.Text(7, 3, Offset(0.2f, 0.5f), "Styled", 0.03f,
                    com.mobilepdf.editor.annot.FontChoice("serif", true, true, "Calibri-Bold", 2, null)),
                Annot.Text(8, 3, Offset(0.2f, 0.6f), "Uploaded", 0.03f, com.mobilepdf.editor.annot.FontChoice(file = "/x/fonts/My.ttf")),
                Annot.Seal(9, 0xFF2E7D32.toInt(), Rect(0.3f, 0.4f, 0.6f, 0.46f), "APPROVED"),
                Annot.Note(10, 0xFFFFD54F.toInt(), Offset(0.7f, 0.1f), "Check this"),
                Annot.Redact(11, 0, listOf(Rect(0.1f, 0.1f, 0.3f, 0.12f)), "Sara"),
                Annot.Stamp(5, 4, Rect(0.3f, 0.3f, 0.7f, 0.4f), listOf(listOf(Offset(0f, 0f), Offset(1f, 1f)))),
                Annot.Replace(6, -16777216, Rect(0.1f, 0.2f, 0.6f, 0.22f), "Total due: 120", 0.03f, "Total due: 100"),
            ),
        )
        val d = Draft(annots, mapOf("name" to "Ali", "agree" to "true"), "/x/w1.pdf", 42L)
        assertEquals(d, DraftStore.decode(org.json.JSONObject(DraftStore.encode(d).toString())))
    }
}
