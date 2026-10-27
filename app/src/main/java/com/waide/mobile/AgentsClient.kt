package com.waide.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the agents side of the private service on your PC (same port 11435 as the
 * conversations service, same PIN token). Agents read across your saved conversations
 * (hidden ones excluded, enforced on the server) and keep their own memory thread.
 */
data class AgentSummary(
    val id: String, val name: String, val job: String,
    val updated: Double, val count: Int, val hidden: Boolean = false,
    val readPremium: Boolean = false
)
data class AgentMsg(val id: String, val role: String, val text: String)
data class AgentDetail(
    val name: String, val job: String, val messages: List<AgentMsg>,
    val readPremium: Boolean = false
)

object AgentsClient {

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

    fun list(base: String, token: String): List<AgentSummary> {
        val c = open("$base/agents", "GET", token)
        if (c.responseCode != 200) fail(c)
        val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            AgentSummary(o.getString("id"), o.optString("name", "Agent"), o.optString("job", ""),
                o.optDouble("updated", 0.0), o.optInt("count", 0), o.optBoolean("hidden", false),
                o.optBoolean("read_premium", false))
        }
    }

    fun create(base: String, token: String, name: String, job: String, readPremium: Boolean): String {
        val c = open("$base/agents", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("name", name).put("job", job).put("read_premium", readPremium).toString())
        }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getString("id")
    }

    fun get(base: String, token: String, id: String): AgentDetail {
        val c = open("$base/agents/$id", "GET", token)
        if (c.responseCode != 200) fail(c)
        val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val msgs = ArrayList<AgentMsg>()
        o.optJSONArray("messages")?.let {
            for (i in 0 until it.length()) {
                val m = it.getJSONObject(i)
                msgs.add(AgentMsg(m.optString("id"), m.optString("role"), m.optString("text")))
            }
        }
        return AgentDetail(o.optString("name", "Agent"), o.optString("job", ""), msgs,
            o.optBoolean("read_premium", false))
    }

    fun edit(base: String, token: String, id: String, name: String, job: String, readPremium: Boolean) {
        val c = open("$base/agents/$id/edit", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use {
            it.write(JSONObject().put("name", name).put("job", job).put("read_premium", readPremium).toString())
        }
        if (c.responseCode != 200) fail(c)
    }

    fun deleteAgent(base: String, token: String, id: String) {
        val c = open("$base/agents/$id", "DELETE", token)
        if (c.responseCode != 200) fail(c)
    }

    fun deleteMsg(base: String, token: String, id: String, mid: String) {
        val c = open("$base/agents/$id/messages/$mid", "DELETE", token)
        if (c.responseCode != 200) fail(c)
    }

    fun hide(base: String, token: String, id: String) {
        val c = open("$base/agents/$id/hide", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write("{}") }
        if (c.responseCode != 200) fail(c)
    }

    fun unhide(base: String, token: String, id: String, pin: String) {
        val c = open("$base/agents/$id/unhide", "POST", token)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
    }

    fun unhideAll(base: String, pin: String): Int {
        val c = open("$base/agents/unhide-all", "POST", null)
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(JSONObject().put("pin", pin).toString()) }
        if (c.responseCode != 200) fail(c)
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optInt("unhidden", 0)
    }

    /** Streams the agent's reply as plain text chunks. Returns the full reply. */
    fun chatStream(
        base: String, token: String, id: String,
        model: String, content: String, numCtx: Int,
        onToken: (String) -> Unit
    ): String {
        val c = open("$base/agents/$id/chat", "POST", token)
        c.doOutput = true
        c.readTimeout = 0 // streaming (and the first reply also waits on indexing)
        c.setRequestProperty("Content-Type", "application/json")
        val body = JSONObject().put("model", model).put("content", content).put("num_ctx", numCtx)
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
