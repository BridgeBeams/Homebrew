package com.waide.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the private Conversations service on your PC (port 11435 over Tailscale).
 * Everything except the PIN check needs the token returned by auth(). Standard
 * HttpURLConnection, no third-party libraries, same as ServerClient.
 */
data class ConvSummary(val id: String, val title: String, val updated: Double, val count: Int,
                       val hidden: Boolean = false, val pinned: Boolean = false, val project: String = "")
/** A project: a named folder that groups conversations. */
data class Project(val id: String, val name: String)
data class ConvHit(val id: String, val title: String, val updated: Double, val count: Int, val snippet: String)
data class ConvMsg(
    val id: String,
    val role: String,
    val text: String,
    val imageB64: String? = null,   // thumbnail of an attached photo, if any
    val hasVision: Boolean = false, // this turn had an image the vision model read
    val model: String = "",         // the model that produced this assistant turn (for export labels)
    val fileName: String = ""       // name of a document attached to this turn, if any
)
data class LoginInfo(
    val token: String,
    val lastLogin: Double?,
    val failedAttempts: List<Double>,
    val failedCount: Int,
    val mustChange: Boolean = false   // logged in with a one-time reset code; set a new pin now
)
/** A conversation's detail, including how much of it is folded into the summary. */
data class ConvDetail(
    val title: String,
    val messages: List<ConvMsg>,
    val compactedCount: Int = 0,
    val compactedAt: Double = 0.0
)
/** One stored file's bytes, pulled back out of the files folder. */
data class ConvFileData(val name: String, val mime: String, val kind: String, val data: String)

object ConversationsClient {

    /** Derive the conversations base (port 11435) from a machine's Ollama URL. */
    fun baseFrom(ollamaUrl: String): String {
        var u = ollamaUrl.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        u = u.trimEnd('/')
        u = Regex(":\\d+$").replace(u, "")   // drop any trailing :port
        return "$u:11435"
    }

    private fun open(url: String, method: String, token: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 15000
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun fail(c: HttpURLConnection): Nothing {
        val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val detail = runCatching { JSONObject(body ?: "").optString("detail") }.getOrNull()
        throw IllegalStateException(if (!detail.isNullOrBlank()) detail else "error ${c.responseCode}")
    }

    fun auth(base: String, pin: String): LoginInfo {
        val c = open("$base/auth", "POST", null)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val fa = ArrayList<Double>()
        o.optJSONArray("failed_attempts")?.let { for (i in 0 until it.length()) fa.add(it.optDouble(i)) }
        return LoginInfo(
            token = o.getString("token"),
            lastLogin = if (o.isNull("last_login")) null else o.optDouble("last_login"),
            failedAttempts = fa,
            failedCount = o.optInt("failed_count", 0),
            mustChange = o.optBoolean("must_change", false)
        )
    }

    fun changePin(base: String, token: String, oldPin: String, newPin: String) {
        val c = open("$base/auth/change", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("old_pin", oldPin).put("new_pin", newPin).toString())
        }
        if (c.responseCode != 200) fail(c)
    }

    /** Forgot password: the master code grants a reset token (it never unlocks chats).
     *  Returns the reset token to use with resetComplete. */
    fun resetRequest(base: String, master: String): String {
        val c = open("$base/auth/reset-request", "POST", null)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("master", master).toString()) }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("token")
    }

    /** With a reset token, set a new unlock password (no old password needed). */
    fun resetComplete(base: String, token: String, newPin: String) {
        val c = open("$base/auth/reset-complete", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("new_pin", newPin).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun models(base: String, token: String): List<String> {
        val c = open("$base/models", "GET", token)
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map { arr.getString(it) }
    }

    fun list(base: String, token: String): List<ConvSummary> {
        val c = open("$base/conversations", "GET", token)
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ConvSummary(o.getString("id"), o.optString("title", "Untitled"),
                o.optDouble("updated", 0.0), o.optInt("count", 0), o.optBoolean("hidden", false),
                o.optBoolean("pinned", false), o.optString("project", ""))
        }
    }

    /** Keyword search over titles and message bodies (hidden conversations excluded server-side). */
    fun search(base: String, token: String, q: String): List<ConvHit> {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val c = open("$base/conversations/search?q=$enc", "GET", token)
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ConvHit(o.getString("id"), o.optString("title", "Untitled"),
                o.optDouble("updated", 0.0), o.optInt("count", 0), o.optString("snippet", ""))
        }
    }

    fun hide(base: String, token: String, id: String) {
        val c = open("$base/conversations/$id/hide", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write("{}") }
        if (c.responseCode != 200) fail(c)
    }

    fun unhide(base: String, token: String, id: String, pin: String) {
        val c = open("$base/conversations/$id/unhide", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    /** Unhide every hidden conversation. Needs only the PIN (works from Settings). */
    fun unhideAll(base: String, pin: String): Int {
        val c = open("$base/conversations/unhide-all", "POST", null)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("unhidden", 0)
    }

    fun create(base: String, token: String, title: String?, project: String? = null): String {
        val c = open("$base/conversations", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().apply {
                if (title != null) put("title", title)
                if (!project.isNullOrBlank()) put("project", project)
            }.toString())
        }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    fun pin(base: String, token: String, id: String, pinned: Boolean) {
        val c = open("$base/conversations/$id/pin", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pinned", pinned).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    /** Move one conversation into a project ("" = unfiled). */
    fun setProject(base: String, token: String, id: String, project: String) {
        val c = open("$base/conversations/$id/project", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("project", project).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    // ---- projects ----
    fun projects(base: String, token: String): List<Project> {
        val c = open("$base/projects", "GET", token)
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it); Project(o.getString("id"), o.optString("name", "Project"))
        }
    }

    fun createProject(base: String, token: String, name: String): String {
        val c = open("$base/projects", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("name", name).toString()) }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    fun renameProject(base: String, token: String, id: String, name: String) {
        val c = open("$base/projects/$id/rename", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("name", name).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun deleteProject(base: String, token: String, id: String) {
        val c = open("$base/projects/$id", "DELETE", token)
        if (c.responseCode != 200) fail(c)
    }

    /** Add many conversations to a project ("" = unfile) in one call. */
    fun assignBulk(base: String, token: String, ids: List<String>, project: String) {
        val c = open("$base/projects/assign", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val arr = JSONArray(); ids.forEach { arr.put(it) }
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("ids", arr).put("project", project).toString())
        }
        if (c.responseCode != 200) fail(c)
    }

    fun get(base: String, token: String, id: String): ConvDetail {
        val c = open("$base/conversations/$id", "GET", token)
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val msgs = ArrayList<ConvMsg>()
        o.optJSONArray("messages")?.let {
            for (i in 0 until it.length()) {
                val m = it.getJSONObject(i)
                val img = m.optString("image")
                val vn = m.optString("vision_note")
                msgs.add(ConvMsg(m.optString("id"), m.optString("role"), m.optString("text"),
                    if (img.isBlank()) null else img, vn.isNotBlank(), m.optString("model"),
                    m.optString("file_name")))
            }
        }
        return ConvDetail(o.optString("title", "Untitled"), msgs,
            o.optInt("compacted_count", 0), o.optDouble("compacted_at", 0.0))
    }

    /** List files stored in this conversation's files folder. */
    fun files(base: String, token: String, id: String): List<ConvFile> {
        val c = open("$base/conversations/$id/files", "GET", token)
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ConvFile(o.getString("id"), o.optString("name", "file"), o.optString("mime", ""),
                o.optString("kind", "file"), o.optLong("size", 0), o.optDouble("added", 0.0))
        }
    }

    /** Fetch one stored file's bytes (base64) so it can be re-sent or saved. */
    fun fileData(base: String, token: String, id: String, fid: String): ConvFileData {
        val c = open("$base/conversations/$id/files/$fid", "GET", token)
        c.readTimeout = 60000
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return ConvFileData(o.optString("name", "file"), o.optString("mime", "application/octet-stream"),
            o.optString("kind", "file"), o.optString("data"))
    }

    fun rename(base: String, token: String, id: String, title: String) {
        val c = open("$base/conversations/$id/rename", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("title", title).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun deleteConv(base: String, token: String, id: String) {
        val c = open("$base/conversations/$id", "DELETE", token)
        if (c.responseCode != 200) fail(c)
    }

    /** Ask the PC server to render text into a professional document (pdf/docx/xlsx/pptx/html).
     *  Returns the file bytes, or null if the server is unreachable or can't render it. */
    fun render(base: String, text: String, fmt: String): ByteArray? {
        return try {
            val c = open("$base/render", "POST", null)
            c.doOutput = true
            c.readTimeout = 60000
            c.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
                it.write(JSONObject().put("text", text).put("format", fmt).toString())
            }
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            android.util.Base64.decode(o.optString("data"), android.util.Base64.DEFAULT)
        } catch (e: Exception) { null }
    }

    fun deleteMsg(base: String, token: String, id: String, mid: String) {
        val c = open("$base/conversations/$id/messages/$mid", "DELETE", token)
        if (c.responseCode != 200) fail(c)
    }

    /** Streams the assistant reply as plain text chunks. Returns the full reply. */
    fun chatStream(
        base: String, token: String, id: String,
        model: String, content: String, numCtx: Int,
        imageB64: String?, visionModel: String?,
        fileName: String? = null, fileMime: String? = null, fileData: String? = null,
        onToken: (String) -> Unit
    ): String {
        val c = open("$base/conversations/$id/chat", "POST", token)
        c.doOutput = true
        c.readTimeout = 0 // streaming
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("model", model).put("content", content).put("num_ctx", numCtx)
        if (imageB64 != null) body.put("image", imageB64)
        if (!visionModel.isNullOrBlank()) body.put("vision_model", visionModel)
        if (!fileData.isNullOrBlank()) body.put("file", JSONObject()
            .put("name", fileName ?: "file")
            .put("mime", fileMime ?: "application/octet-stream")
            .put("data", fileData))
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        if (c.responseCode != 200) fail(c)
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
            val buf = CharArray(512)
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                if (n > 0) {
                    val piece = String(buf, 0, n)
                    sb.append(piece)
                    onToken(piece)
                }
            }
        }
        return sb.toString()
    }
}
