package com.waide.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * File-export helpers shared by the Premium and Saved view models: turn a message's
 * text into a downloadable file on the phone. PDF is rendered on-device; .docx and
 * .xlsx are built as real (zipped) OOXML; HTML and code/text are written directly.
 * Pure Kotlin, no third-party libraries.
 */

internal fun mimeForName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "md" -> "text/markdown"
    "txt", "log" -> "text/plain"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "xml" -> "application/xml"
    "pdf" -> "application/pdf"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    "epub" -> "application/epub+zip"
    else -> "application/octet-stream"   // keeps the exact name/extension (.kt, .py, etc.)
}

/** A filename guess from the first meaningful line of a message. */
internal fun suggestFileName(text: String): String {
    val line = text.lineSequence()
        .map { it.trim().trimStart('#', '*', '-', '>', ' ') }
        .firstOrNull { it.isNotBlank() } ?: "waide-note"
    val cleaned = line.take(40).replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), "-")
    return cleaned.ifBlank { "waide-note" }
}

private fun xmlEsc(s: String): String = s
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun zipBytes(entries: List<Pair<String, String>>): ByteArray {
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
        for ((name, content) in entries) {
            zos.putNextEntry(ZipEntry(name))
            zos.write(content.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
    }
    return baos.toByteArray()
}

// ------------------------------------------------- shared markdown model
private enum class BT { H1, H2, H3, PARA, BULLET, NUMBER, CODE, QUOTE }
private class MdBlock(val type: BT, val text: String, val marker: String = "")

/** One inline run of text with its styles. */
private class Tok(val text: String, val bold: Boolean, val italic: Boolean, val code: Boolean)

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+?)`|\\*(.+?)\\*|_(.+?)_")

private fun inlineTokens(s: String): List<Tok> {
    val toks = ArrayList<Tok>()
    var i = 0
    for (m in INLINE.findAll(s)) {
        if (m.range.first > i) toks.add(Tok(s.substring(i, m.range.first), false, false, false))
        when {
            m.groupValues[1].isNotEmpty() -> toks.add(Tok(m.groupValues[1], true, false, false))
            m.groupValues[2].isNotEmpty() -> toks.add(Tok(m.groupValues[2], false, false, true))
            m.groupValues[3].isNotEmpty() -> toks.add(Tok(m.groupValues[3], false, true, false))
            m.groupValues[4].isNotEmpty() -> toks.add(Tok(m.groupValues[4], false, true, false))
        }
        i = m.range.last + 1
    }
    if (i < s.length) toks.add(Tok(s.substring(i), false, false, false))
    return toks
}

/** Split a message into markdown blocks (headings, paragraphs, lists, code, quotes). */
private fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").split("\n")
    val blocks = ArrayList<MdBlock>()
    val para = StringBuilder()
    fun flush() { if (para.isNotBlank()) blocks.add(MdBlock(BT.PARA, para.toString().trim())); para.setLength(0) }
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd()
        val t = line.trimStart()
        when {
            line.isBlank() -> flush()
            t.startsWith("```") -> {
                flush()
                val sb = StringBuilder(); i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { sb.append(lines[i]).append("\n"); i++ }
                blocks.add(MdBlock(BT.CODE, sb.toString().trimEnd('\n')))
            }
            Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> flush()
            t.startsWith("### ") -> { flush(); blocks.add(MdBlock(BT.H3, t.substring(4))) }
            t.startsWith("## ") -> { flush(); blocks.add(MdBlock(BT.H2, t.substring(3))) }
            t.startsWith("# ") -> { flush(); blocks.add(MdBlock(BT.H1, t.substring(2))) }
            Regex("^[-*+]\\s+").containsMatchIn(t) -> { flush(); blocks.add(MdBlock(BT.BULLET, t.replaceFirst(Regex("^[-*+]\\s+"), ""))) }
            Regex("^\\d+\\.\\s+").containsMatchIn(t) -> {
                flush()
                val num = Regex("^(\\d+)\\.\\s+").find(t)!!.groupValues[1]
                blocks.add(MdBlock(BT.NUMBER, t.replaceFirst(Regex("^\\d+\\.\\s+"), ""), num))
            }
            t.startsWith("> ") -> { flush(); blocks.add(MdBlock(BT.QUOTE, t.substring(2))) }
            else -> { if (para.isNotEmpty()) para.append(" "); para.append(t) }
        }
        i++
    }
    flush()
    return blocks
}

// ------------------------------------------------- PDF (markdown-rendered)
private fun inlineSpannable(s: String): CharSequence {
    val out = SpannableStringBuilder()
    for (tk in inlineTokens(s)) {
        val start = out.length
        out.append(tk.text)
        if (tk.bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, 0)
        if (tk.italic) out.setSpan(StyleSpan(Typeface.ITALIC), start, out.length, 0)
        if (tk.code) out.setSpan(TypefaceSpan("monospace"), start, out.length, 0)
    }
    return out
}

private class PdfStyle(val paint: TextPaint, val indent: Int, val above: Int, val mult: Float, val body: CharSequence) {
    operator fun component1() = paint
    operator fun component2() = indent
    operator fun component3() = above
    operator fun component4() = mult
    operator fun component5() = body
}

internal fun buildTextPdf(text: String): ByteArray {
    val pageW = 612; val pageH = 792; val margin = 54
    val contentW = pageW - 2 * margin
    val bottom = pageH - margin
    val ink = Color.rgb(0x1a, 0x1a, 0x1a)
    val sans = Typeface.SANS_SERIF
    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val italic = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)
    fun paint(size: Float, tf: Typeface, color: Int) =
        TextPaint().apply { this.color = color; textSize = size; isAntiAlias = true; typeface = tf }
    val pBody = paint(11.5f, sans, ink)
    val pH1 = paint(21f, bold, ink)
    val pH2 = paint(16.5f, bold, ink)
    val pH3 = paint(13.5f, bold, ink)
    val pCode = paint(10f, Typeface.MONOSPACE, Color.rgb(0x33, 0x33, 0x33))
    val pQuote = paint(11.5f, italic, Color.rgb(0x55, 0x55, 0x55))

    val blocks = if (text.isBlank()) listOf(MdBlock(BT.PARA, " ")) else parseMarkdown(text)

    val doc = PdfDocument()
    var pageNum = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
    var canvas = page.canvas
    var y = margin
    fun newPage() {
        doc.finishPage(page); pageNum++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        canvas = page.canvas; y = margin
    }

    for (b in blocks) {
        val (paint, indent, above, mult, body) = when (b.type) {
            BT.H1 -> PdfStyle(pH1, 0, 16, 1.1f, inlineSpannable(b.text))
            BT.H2 -> PdfStyle(pH2, 0, 13, 1.1f, inlineSpannable(b.text))
            BT.H3 -> PdfStyle(pH3, 0, 11, 1.1f, inlineSpannable(b.text))
            BT.CODE -> PdfStyle(pCode, 14, 8, 1.2f, b.text)
            BT.QUOTE -> PdfStyle(pQuote, 16, 7, 1.3f, inlineSpannable(b.text))
            BT.BULLET -> PdfStyle(pBody, 16, 3, 1.3f, SpannableStringBuilder("•  ").append(inlineSpannable(b.text)))
            BT.NUMBER -> PdfStyle(pBody, 16, 3, 1.3f, SpannableStringBuilder("${b.marker}.  ").append(inlineSpannable(b.text)))
            BT.PARA -> PdfStyle(pBody, 0, 7, 1.3f, inlineSpannable(b.text))
        }
        y += above
        val w = contentW - indent
        @Suppress("DEPRECATION")
        val layout = StaticLayout(body, paint, w, Layout.Alignment.ALIGN_NORMAL, mult, 0f, false)
        var lineStart = 0
        while (lineStart < layout.lineCount) {
            val top = layout.getLineTop(lineStart)
            val firstH = layout.getLineBottom(lineStart) - top
            if (y + firstH > bottom && y > margin) newPage()
            val avail = bottom - y
            var lineEnd = lineStart
            while (lineEnd < layout.lineCount && layout.getLineBottom(lineEnd) - top <= avail) lineEnd++
            if (lineEnd == lineStart) lineEnd = lineStart + 1
            val sliceBottom = layout.getLineBottom(lineEnd - 1)
            canvas.save()
            canvas.translate((margin + indent).toFloat(), (y - top).toFloat())
            canvas.clipRect(0f, top.toFloat(), w.toFloat(), sliceBottom.toFloat())
            layout.draw(canvas)
            canvas.restore()
            y += sliceBottom - top
            lineStart = lineEnd
            if (lineStart < layout.lineCount) newPage()
        }
    }
    doc.finishPage(page)
    val baos = ByteArrayOutputStream()
    doc.writeTo(baos)
    doc.close()
    return baos.toByteArray()
}

// ---------------------------------------------------------------- DOCX
/** Inline runs for a line of markdown. sz is half-points (23 = 11.5pt). */
private fun docxRuns(s: String, sz: Int, bold: Boolean, italic: Boolean, mono: Boolean): String {
    val toks = inlineTokens(s)
    if (toks.isEmpty()) return "<w:r><w:rPr><w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr><w:t xml:space=\"preserve\"></w:t></w:r>"
    val sb = StringBuilder()
    for (tk in toks) {
        sb.append("<w:r><w:rPr>")
        if (bold || tk.bold) sb.append("<w:b/>")
        if (italic || tk.italic) sb.append("<w:i/>")
        if (mono || tk.code) sb.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
        sb.append("<w:sz w:val=\"$sz\"/><w:szCs w:val=\"$sz\"/></w:rPr>")
        sb.append("<w:t xml:space=\"preserve\">").append(xmlEsc(tk.text)).append("</w:t></w:r>")
    }
    return sb.toString()
}

private fun docxPara(runs: String, before: Int, after: Int, indent: Int): String {
    val ind = if (indent > 0) "<w:ind w:left=\"$indent\"/>" else ""
    return "<w:p><w:pPr><w:spacing w:before=\"$before\" w:after=\"$after\"/>$ind</w:pPr>$runs</w:p>"
}

private fun bulletPrefix(s: String): String =
    "<w:r><w:rPr><w:sz w:val=\"23\"/><w:szCs w:val=\"23\"/></w:rPr><w:t xml:space=\"preserve\">${xmlEsc(s)}</w:t></w:r>"

internal fun buildDocx(text: String): ByteArray {
    val blocks = if (text.isBlank()) listOf(MdBlock(BT.PARA, "")) else parseMarkdown(text)
    val body = buildString {
        for (b in blocks) when (b.type) {
            BT.H1 -> append(docxPara(docxRuns(b.text, 42, true, false, false), 240, 120, 0))
            BT.H2 -> append(docxPara(docxRuns(b.text, 33, true, false, false), 200, 100, 0))
            BT.H3 -> append(docxPara(docxRuns(b.text, 27, true, false, false), 160, 80, 0))
            BT.CODE -> for (ln in b.text.split("\n")) append(docxPara(docxRuns(ln, 20, false, false, true), 0, 0, 360))
            BT.QUOTE -> append(docxPara(docxRuns(b.text, 23, false, true, false), 40, 80, 360))
            BT.BULLET -> append(docxPara(bulletPrefix("•  ") + docxRuns(b.text, 23, false, false, false), 0, 40, 360))
            BT.NUMBER -> append(docxPara(bulletPrefix("${b.marker}.  ") + docxRuns(b.text, 23, false, false, false), 0, 40, 360))
            BT.PARA -> append(docxPara(docxRuns(b.text, 23, false, false, false), 0, 100, 0))
        }
    }
    val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body<w:sectPr/></w:body></w:document>"""
    val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""
    val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""
    return zipBytes(listOf(
        "[Content_Types].xml" to contentTypes,
        "_rels/.rels" to rels,
        "word/document.xml" to document
    ))
}

// ---------------------------------------------------------------- XLSX
private fun colLetter(i: Int): String {
    var n = i; val sb = StringBuilder()
    while (true) { sb.insert(0, ('A' + (n % 26))); n = n / 26 - 1; if (n < 0) break }
    return sb.toString()
}

/** Pull a grid out of the message: a markdown table if present, else CSV/TSV lines, else one cell per line. */
private fun parseRows(text: String): List<List<String>> {
    val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val tableLines = lines.filter { it.startsWith("|") }
    return if (tableLines.size >= 2) {
        tableLines
            .filterNot { it.replace("|", "").replace("-", "").replace(":", "").replace(" ", "").isEmpty() }
            .map { it.trim().trim('|').split("|").map { c -> c.trim() } }
    } else {
        lines.map { line ->
            when {
                line.contains("\t") -> line.split("\t")
                line.contains(",") -> line.split(",").map { it.trim() }
                else -> listOf(line)
            }
        }
    }
}

internal fun buildXlsx(text: String): ByteArray {
    val rows = parseRows(text).ifEmpty { listOf(listOf("")) }
    val sb = StringBuilder()
    rows.forEachIndexed { ri, row ->
        sb.append("<row r=\"${ri + 1}\">")
        row.forEachIndexed { ci, cell ->
            sb.append("<c r=\"${colLetter(ci)}${ri + 1}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xmlEsc(cell)}</t></is></c>")
        }
        sb.append("</row>")
    }
    val sheet = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$sb</sheetData></worksheet>"""
    val workbook = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    val wbRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
    val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""
    val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
    return zipBytes(listOf(
        "[Content_Types].xml" to contentTypes,
        "_rels/.rels" to rels,
        "xl/workbook.xml" to workbook,
        "xl/_rels/workbook.xml.rels" to wbRels,
        "xl/worksheets/sheet1.xml" to sheet
    ))
}

// ---------------------------------------------------------------- HTML
internal fun buildHtml(text: String): String {
    val t = text.trim()
    if (t.startsWith("<!doctype", true) || t.startsWith("<html", true)) return text
    return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>wAide</title></head>" +
        "<body><pre style=\"white-space:pre-wrap;font-family:system-ui,sans-serif\">${xmlEsc(text)}</pre></body></html>"
}

// ---------------------------------------------------------------- smart code/text
private val LANG_EXT = mapOf(
    "python" to "py", "py" to "py", "kotlin" to "kt", "kt" to "kt", "kts" to "kts",
    "java" to "java", "javascript" to "js", "js" to "js", "typescript" to "ts", "ts" to "ts",
    "c" to "c", "cpp" to "cpp", "c++" to "cpp", "cs" to "cs", "csharp" to "cs",
    "go" to "go", "rust" to "rs", "rs" to "rs", "ruby" to "rb", "rb" to "rb",
    "php" to "php", "swift" to "swift", "sql" to "sql", "bash" to "sh", "sh" to "sh",
    "shell" to "sh", "yaml" to "yml", "yml" to "yml", "json" to "json", "xml" to "xml",
    "html" to "html", "css" to "css", "gradle" to "gradle", "markdown" to "md", "md" to "md",
    "toml" to "toml"
)

/** On-device fallback bytes for a document format when the PC server isn't reachable.
 *  PowerPoint and EPUB have no on-device builder, so they need the server. */
internal fun localDocBytes(fmt: String, text: String): ByteArray = when (fmt) {
    "pdf" -> buildTextPdf(text)
    "docx" -> buildDocx(text)
    "xlsx" -> buildXlsx(text)
    "html" -> buildHtml(text).toByteArray(Charsets.UTF_8)
    else -> throw IllegalStateException("This format needs the PC server. Start the wAide server and try again.")
}

/** Best (extension, content) for a code/text file from this message. If the message is
 *  essentially one fenced code block, returns just that code with the language's extension. */
internal fun smartTextFile(text: String): Pair<String, String> {
    val fence = Regex("```([A-Za-z0-9+#.]*)\\s*\\n([\\s\\S]*?)```").find(text)
    if (fence != null) {
        val lang = fence.groupValues[1].lowercase()
        val code = fence.groupValues[2]
        val outside = text.replace(fence.value, "").trim()
        if (outside.length < 40) return (LANG_EXT[lang] ?: "txt") to code
    }
    val t = text.trim()
    return when {
        t.startsWith("<!doctype", true) || t.startsWith("<html", true) -> "html" to text
        t.startsWith("{") || t.startsWith("[") -> "json" to text
        else -> "md" to text
    }
}
