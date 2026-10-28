package com.waide.mobile

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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

// ---------------------------------------------------------------- PDF
internal fun buildTextPdf(text: String): ByteArray {
    val pageW = 612; val pageH = 792; val margin = 36
    val contentW = pageW - 2 * margin
    val contentH = pageH - 2 * margin
    val tp = TextPaint().apply { color = Color.BLACK; textSize = 11f; isAntiAlias = true }
    val content: CharSequence = if (text.isBlank()) " " else text
    @Suppress("DEPRECATION")
    val layout = StaticLayout(content, tp, contentW, Layout.Alignment.ALIGN_NORMAL, 1.15f, 0f, false)
    val doc = PdfDocument()
    var startLine = 0
    var pageNum = 1
    while (startLine < layout.lineCount) {
        val pageTop = layout.getLineTop(startLine)
        var endLine = startLine
        while (endLine < layout.lineCount && layout.getLineBottom(endLine) - pageTop <= contentH) endLine++
        if (endLine == startLine) endLine = startLine + 1
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create()
        val page = doc.startPage(info)
        val c = page.canvas
        c.save()
        c.translate(margin.toFloat(), (margin - pageTop).toFloat())
        c.clipRect(0f, pageTop.toFloat(), contentW.toFloat(), layout.getLineBottom(endLine - 1).toFloat())
        layout.draw(c)
        c.restore()
        doc.finishPage(page)
        startLine = endLine
        pageNum++
    }
    if (layout.lineCount == 0) doc.finishPage(doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()))
    val baos = ByteArrayOutputStream()
    doc.writeTo(baos)
    doc.close()
    return baos.toByteArray()
}

// ---------------------------------------------------------------- DOCX
internal fun buildDocx(text: String): ByteArray {
    val body = (if (text.isBlank()) listOf("") else text.split("\n")).joinToString("") { line ->
        "<w:p><w:r><w:t xml:space=\"preserve\">${xmlEsc(line)}</w:t></w:r></w:p>"
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
