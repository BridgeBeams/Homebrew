package com.waide.mobile

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Shared file-export pipeline used by every chat tab (Local, Server, Premium, Saved):
 * render a single message or a whole conversation to any format and drop it in Downloads.
 * Rendering prefers the PC server's professional renderer and falls back to the on-device
 * renderer when the server isn't reachable.
 */
object Exporter {

    /** Map a filename's extension to a render format, or "" for plain text / code. */
    fun fmtFor(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "pdf"
        "docx" -> "docx"
        "xlsx" -> "xlsx"
        "pptx" -> "pptx"
        "epub" -> "epub"
        "html", "htm" -> "html"
        else -> ""
    }

    /** Render [text] to [fmt] bytes: PC server first (if [base] is set), else on-device. */
    fun renderBytes(base: String, text: String, fmt: String): ByteArray =
        if (fmt.isNotEmpty())
            (if (base.isNotBlank()) ConversationsClient.render(base, text, fmt) else null)
                ?: localDocBytes(fmt, text)
        else text.toByteArray(Charsets.UTF_8)

    /** Write [bytes] into the phone's Downloads folder as [name]; returns the saved name. */
    fun saveToDownloads(app: Application, name: String, mime: String, bytes: ByteArray): String {
        val safe = name.ifBlank { "waide-file" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safe)
                put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            }
            val uri = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("could not create file")
            app.contentResolver.openOutputStream(uri).use { it?.write(bytes) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, safe).writeBytes(bytes)
        }
        return safe
    }

    /** Render [text] by the extension in [filename] and save it. Returns the saved name. */
    fun saveText(app: Application, base: String, text: String, filename: String): String {
        val name = filename.trim().ifBlank { "waide-note.md" }
        val bytes = renderBytes(base, text, fmtFor(name))
        return saveToDownloads(app, name, mimeForName(name), bytes)
    }

    /** The heading label for one turn: "You" for the user; for an assistant, "wAide · <model>"
     *  when the model is known (so an Ask-All export shows which model gave each reply), else
     *  plain "wAide". The model id is shortened to its slug (anthropic/claude-fable-5.1 ->
     *  claude-fable-5.1). */
    fun speaker(role: String, model: String): String = when {
        role == "user" -> "You"
        model.isBlank() -> "wAide"
        else -> "wAide · " + model.substringAfterLast('/')
    }

    /** Join a whole conversation into one Markdown transcript. Each turn is (speaker label, text),
     *  where the label is already resolved by the caller (see [speaker]). */
    fun transcript(turns: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        for ((who, text) in turns) {
            if (text.isBlank()) continue
            sb.append("## ").append(who).append("\n\n")
            sb.append(text.trim()).append("\n\n")
        }
        return sb.toString().trim()
    }
}
