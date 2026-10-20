package com.waide.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to your own Ollama server (the shop PC over Tailscale). Plain HTTP, no
 * third party. All calls are blocking and must be run on an IO dispatcher.
 *
 * Amnesia: nothing is stored here. The full message list is sent each turn from
 * memory; when the Server tab's chat is cleared, it's gone.
 */
object ServerClient {

    private fun normalize(base: String): String {
        var b = base.trim()
        if (b.isEmpty()) return b
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
        return b.trimEnd('/')
    }

    /** GET {base}/api/tags -> list of model names available on the server. */
    fun listModels(base: String): List<String> {
        val b = normalize(base)
        if (b.isEmpty()) throw IllegalStateException("No server address set. Add it in Settings.")
        val conn = (URL("$b/api/tags").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 6000
            readTimeout = 6000
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Server returned ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                arr.getJSONObject(i).optString("name").takeIf { it.isNotBlank() }?.let { out.add(it) }
            }
            return out.sorted()
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST {base}/api/chat (streaming). Calls onToken for each chunk of text.
     * Returns the full reply. Throws on network/HTTP error.
     */
    fun chatStream(
        base: String,
        model: String,
        messages: List<Message>,
        numCtx: Int,
        onToken: (String) -> Unit
    ): String {
        val b = normalize(base)
        if (b.isEmpty()) throw IllegalStateException("No server address set. Add it in Settings.")

        val msgArr = JSONArray()
        for (m in messages) {
            msgArr.put(JSONObject().put("role", m.role).put("content", m.text))
        }
        val payload = JSONObject()
            .put("model", model)
            .put("messages", msgArr)
            .put("stream", true)
            .put("options", JSONObject().put("num_ctx", numCtx))
            .toString()

        val conn = (URL("$b/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 0 // streaming; no read timeout
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload); it.flush() }
            if (conn.responseCode != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                throw IllegalStateException("Server returned ${conn.responseCode}${if (err != null) ": $err" else ""}")
            }
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    o.optJSONObject("message")?.optString("content")?.let {
                        if (it.isNotEmpty()) { sb.append(it); onToken(it) }
                    }
                    if (o.optBoolean("done", false)) break
                }
            }
            return sb.toString()
        } finally {
            conn.disconnect()
        }
    }
}
