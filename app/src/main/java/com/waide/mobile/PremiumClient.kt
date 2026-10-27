package com.waide.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Premium AI: paid cloud models through OpenRouter. The key lives on your PC server;
 * the phone never holds it. These endpoints are open over Tailscale (no PIN to use),
 * except setting the key and unhiding, which take the PIN. All AI processing happens
 * in OpenRouter's cloud, not on the phone or PC.
 */
data class PremiumModel(val id: String, val name: String, val context: Int)
data class PremiumAttach(val kind: String, val name: String, val mime: String, val data: String? = null)
/** One file stored in a conversation's files folder (name + when it was added). */
data class ConvFile(
    val id: String, val name: String, val mime: String,
    val kind: String, val size: Long, val added: Double
)
data class PremiumSummary(
    val id: String, val title: String, val model: String,
    val updated: Double, val count: Int, val hidden: Boolean = false,
    val mode: String = "single", val models: List<String> = emptyList()
)
data class PremiumMsg(
    val id: String, val role: String, val text: String,
    val model: String = "", val attachments: List<PremiumAttach> = emptyList()
)
data class PremiumDetail(
    val title: String, val model: String, val messages: List<PremiumMsg>,
    val mode: String = "single", val models: List<String> = emptyList(),
    val compactedCount: Int = 0, val compactedAt: Double = 0.0
)
// Premium agents: a persona that reads ONLY your premium conversations, answered on a cloud model.
data class PremiumAgentSummary(
    val id: String, val name: String, val job: String, val model: String,
    val updated: Double, val count: Int, val hidden: Boolean = false
)
data class PremiumAgentMsg(val id: String, val role: String, val text: String, val model: String = "")
data class PremiumAgentDetail(
    val name: String, val job: String, val model: String, val messages: List<PremiumAgentMsg>,
    val compactedCount: Int = 0, val compactedAt: Double = 0.0
)

object PremiumClient {

    private fun open(url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 20000
        return c
    }

    private fun fail(c: HttpURLConnection): Nothing {
        val body = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
        val detail = runCatching { JSONObject(body ?: "").optString("detail") }.getOrNull()
        throw IllegalStateException(if (!detail.isNullOrBlank()) detail else "error ${c.responseCode}")
    }

    fun status(base: String): Boolean {
        val c = open("$base/premium/status", "GET")
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optBoolean("has_key", false)
    }

    fun setKey(base: String, pin: String, key: String) {
        val c = open("$base/premium/key", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("pin", pin).put("key", key).toString())
        }
        if (c.responseCode != 200) fail(c)
    }

    fun models(base: String): List<PremiumModel> {
        val c = open("$base/premium/models", "GET")
        c.readTimeout = 25000
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PremiumModel(o.getString("id"), o.optString("name", o.getString("id")), o.optInt("context", 0))
        }
    }

    private fun strList(o: JSONObject, key: String): List<String> {
        val a = o.optJSONArray(key) ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    fun list(base: String): List<PremiumSummary> {
        val c = open("$base/premium/conversations", "GET")
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PremiumSummary(o.getString("id"), o.optString("title", "Untitled"), o.optString("model", ""),
                o.optDouble("updated", 0.0), o.optInt("count", 0), o.optBoolean("hidden", false),
                o.optString("mode", "single"), strList(o, "models"))
        }
    }

    fun create(base: String, title: String?, model: String): String {
        val c = open("$base/premium/conversations", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().apply { if (title != null) put("title", title); put("model", model) }.toString())
        }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    /** Create an Ask-all (multi-model) conversation. */
    fun createMulti(base: String, title: String?, models: List<String>): String {
        val c = open("$base/premium/conversations", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val arr = JSONArray(); models.forEach { arr.put(it) }
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().apply { if (title != null) put("title", title); put("models", arr) }.toString())
        }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    fun get(base: String, id: String): PremiumDetail {
        val c = open("$base/premium/conversations/$id", "GET")
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val msgs = ArrayList<PremiumMsg>()
        o.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val atts = ArrayList<PremiumAttach>()
                m.optJSONArray("attachments")?.let { aa ->
                    for (j in 0 until aa.length()) {
                        val a = aa.getJSONObject(j)
                        val data = a.optString("data")
                        atts.add(PremiumAttach(a.optString("kind"), a.optString("name"),
                            a.optString("mime"), if (data.isBlank()) null else data))
                    }
                }
                msgs.add(PremiumMsg(m.optString("id"), m.optString("role"), m.optString("text"),
                    m.optString("model"), atts))
            }
        }
        return PremiumDetail(o.optString("title", "Untitled"), o.optString("model", ""), msgs,
            o.optString("mode", "single"), strList(o, "models"),
            o.optInt("compacted_count", 0), o.optDouble("compacted_at", 0.0))
    }

    /** List the files stored in this conversation's files folder. */
    fun files(base: String, id: String): List<ConvFile> {
        val c = open("$base/premium/conversations/$id/files", "GET")
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ConvFile(o.getString("id"), o.optString("name", "file"), o.optString("mime", ""),
                o.optString("kind", "file"), o.optLong("size", 0), o.optDouble("added", 0.0))
        }
    }

    /** Fetch one stored file's bytes (as a reattachable PremiumAttach carrying base64). */
    fun fileData(base: String, id: String, fid: String): PremiumAttach {
        val c = open("$base/premium/conversations/$id/files/$fid", "GET")
        c.readTimeout = 60000
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        return PremiumAttach(o.optString("kind", "file"), o.optString("name", "file"),
            o.optString("mime", "application/octet-stream"), o.optString("data"))
    }

    fun rename(base: String, id: String, title: String) {
        val c = open("$base/premium/conversations/$id/rename", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("title", title).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun deleteConv(base: String, id: String) {
        val c = open("$base/premium/conversations/$id", "DELETE")
        if (c.responseCode != 200) fail(c)
    }

    fun deleteMsg(base: String, id: String, mid: String) {
        val c = open("$base/premium/conversations/$id/messages/$mid", "DELETE")
        if (c.responseCode != 200) fail(c)
    }

    fun hide(base: String, id: String) {
        val c = open("$base/premium/conversations/$id/hide", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write("{}") }
        if (c.responseCode != 200) fail(c)
    }

    fun unhide(base: String, id: String, pin: String) {
        val c = open("$base/premium/conversations/$id/unhide", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun unhideAll(base: String, pin: String): Int {
        val c = open("$base/premium/conversations/unhide-all", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("unhidden", 0)
    }

    /** Streams the model's reply as plain text chunks. Returns the full reply. */
    fun chatStream(
        base: String, id: String, model: String, content: String,
        attachments: List<PremiumAttach>, onToken: (String) -> Unit
    ): String {
        val c = open("$base/premium/conversations/$id/chat", "POST")
        c.doOutput = true
        c.readTimeout = 0 // streaming
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("model", model).put("content", content)
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            attachments.forEach { a ->
                arr.put(JSONObject().put("kind", a.kind).put("name", a.name)
                    .put("mime", a.mime).put("data", a.data ?: ""))
            }
            body.put("attachments", arr)
        }
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

    /**
     * Ask-all: one question fanned out to every model in the conversation. The server
     * streams newline-delimited JSON frames, one per token: {"m":idx,"d":"token"} as
     * text arrives, {"m":idx,"done":true} when a column finishes. onFrame(idx, piece, done)
     * is called for each. idx is the column (position in the conversation's models list).
     */
    fun multiChatStream(
        base: String, id: String, content: String,
        attachments: List<PremiumAttach>, onFrame: (Int, String, Boolean) -> Unit
    ) {
        val c = open("$base/premium/conversations/$id/multichat", "POST")
        c.doOutput = true
        c.readTimeout = 0 // streaming
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("content", content)
        if (attachments.isNotEmpty()) {
            val arr = JSONArray()
            attachments.forEach { a ->
                arr.put(JSONObject().put("kind", a.kind).put("name", a.name)
                    .put("mime", a.mime).put("data", a.data ?: ""))
            }
            body.put("attachments", arr)
        }
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        if (c.responseCode != 200) fail(c)
        BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                runCatching {
                    val o = JSONObject(line)
                    val idx = o.optInt("m", 0)
                    val done = o.optBoolean("done", false)
                    val piece = o.optString("d", "")
                    onFrame(idx, piece, done)
                }
            }
        }
    }

    // ---------------- premium agents ----------------
    fun listAgents(base: String): List<PremiumAgentSummary> {
        val c = open("$base/premium/agents", "GET")
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            PremiumAgentSummary(o.getString("id"), o.optString("name", "Agent"), o.optString("job", ""),
                o.optString("model", ""), o.optDouble("updated", 0.0), o.optInt("count", 0),
                o.optBoolean("hidden", false))
        }
    }

    fun createAgent(base: String, name: String, job: String, model: String): String {
        val c = open("$base/premium/agents", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("name", name).put("job", job).put("model", model).toString())
        }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    fun getAgent(base: String, id: String): PremiumAgentDetail {
        val c = open("$base/premium/agents/$id", "GET")
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val msgs = ArrayList<PremiumAgentMsg>()
        o.optJSONArray("messages")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                msgs.add(PremiumAgentMsg(m.optString("id"), m.optString("role"), m.optString("text"),
                    m.optString("model")))
            }
        }
        return PremiumAgentDetail(o.optString("name", "Agent"), o.optString("job", ""),
            o.optString("model", ""), msgs, o.optInt("compacted_count", 0), o.optDouble("compacted_at", 0.0))
    }

    fun editAgent(base: String, id: String, name: String, job: String, model: String) {
        val c = open("$base/premium/agents/$id/edit", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("name", name).put("job", job).put("model", model).toString())
        }
        if (c.responseCode != 200) fail(c)
    }

    fun deleteAgent(base: String, id: String) {
        val c = open("$base/premium/agents/$id", "DELETE")
        if (c.responseCode != 200) fail(c)
    }

    fun deleteAgentMsg(base: String, id: String, mid: String) {
        val c = open("$base/premium/agents/$id/messages/$mid", "DELETE")
        if (c.responseCode != 200) fail(c)
    }

    fun hideAgent(base: String, id: String) {
        val c = open("$base/premium/agents/$id/hide", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write("{}") }
        if (c.responseCode != 200) fail(c)
    }

    fun unhideAgent(base: String, id: String, pin: String) {
        val c = open("$base/premium/agents/$id/unhide", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun unhideAllAgents(base: String, pin: String): Int {
        val c = open("$base/premium/agents/unhide-all", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("unhidden", 0)
    }

    /** Streams the agent's reply as plain text chunks. Returns the full reply. */
    fun agentChatStream(base: String, id: String, model: String, content: String, onToken: (String) -> Unit): String {
        val c = open("$base/premium/agents/$id/chat", "POST")
        c.doOutput = true
        c.readTimeout = 0 // streaming
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("model", model).put("content", content).toString())
        }
        if (c.responseCode != 200) fail(c)
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
            val buf = CharArray(512)
            while (true) {
                val n = reader.read(buf)
                if (n == -1) break
                if (n > 0) { val p = String(buf, 0, n); sb.append(p); onToken(p) }
            }
        }
        return sb.toString()
    }
}
