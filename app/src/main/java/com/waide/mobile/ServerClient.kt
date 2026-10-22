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
// A Hugging Face search hit, and one downloadable quant within a repo.
data class HfModel(val id: String, val updated: String, val downloads: Long)
data class HfQuant(val fileName: String, val tag: String)

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

    // ---------------- Hugging Face model search ----------------
    // The phone queries Hugging Face directly (public API, no login). Results are
    // GGUF repos, newest first, so nothing stale floats to the top.
    fun searchHf(query: String, limit: Int = 30): List<HfModel> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://huggingface.co/api/models?search=$q&filter=gguf" +
            "&sort=lastModified&direction=-1&limit=$limit"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", "waide-app")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Hugging Face returned ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val out = ArrayList<HfModel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("id")
                if (id.isNotBlank()) {
                    out.add(HfModel(id, o.optString("lastModified").take(10), o.optLong("downloads", 0)))
                }
            }
            return out
        } finally { conn.disconnect() }
    }

    // List the downloadable quants (Q4_K_M, Q5_K_M, ...) inside one repo.
    fun listHfQuants(repoId: String): List<HfQuant> {
        val url = "https://huggingface.co/api/models/$repoId"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8000; readTimeout = 12000
            setRequestProperty("User-Agent", "waide-app")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Hugging Face returned ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val sib = JSONObject(body).optJSONArray("siblings") ?: JSONArray()
            val re = Regex("(?i)(IQ\\d+[A-Z0-9_]*|Q\\d+[A-Z0-9_]*|F16|F32|BF16)")
            val out = LinkedHashMap<String, HfQuant>()
            for (i in 0 until sib.length()) {
                val fn = sib.getJSONObject(i).optString("rfilename")
                if (!fn.endsWith(".gguf", ignoreCase = true)) continue
                // skip multi-part extra shards; keep the first part only
                if (Regex("(?i)-0000[2-9]-of-").containsMatchIn(fn)) continue
                val base = fn.substringAfterLast('/')
                val tag = re.find(base)?.value?.uppercase() ?: base.removeSuffix(".gguf").substringAfterLast('.')
                if (tag.isNotBlank()) out.putIfAbsent(tag, HfQuant(base, tag))
            }
            return out.values.toList()
        } finally { conn.disconnect() }
    }

    /** DELETE {base}/api/delete — remove a model from the server. */
    fun deleteModel(base: String, name: String) {
        val b = normalize(base)
        if (b.isEmpty()) throw IllegalStateException("No server address set.")
        val conn = (URL("$b/api/delete").openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 20000
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("name", name).put("model", name).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body); it.flush() }
            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                throw IllegalStateException("Delete failed ($code)${if (err != null) ": $err" else ""}")
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST {base}/api/pull (streaming) — download a model onto the server by name,
     * e.g. "qwen2.5:7b" or "hf.co/TheBloke/MythoMax-L2-13B-GGUF:Q4_K_M".
     * Calls onProgress with a human-readable status/percent line.
     */
    fun pullModel(base: String, name: String, onProgress: (String) -> Unit) {
        val b = normalize(base)
        if (b.isEmpty()) throw IllegalStateException("No server address set.")
        val conn = (URL("$b/api/pull").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 0
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().put("name", name).put("model", name).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body); it.flush() }
            if (conn.responseCode != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                throw IllegalStateException("Pull failed (${conn.responseCode})${if (err != null) ": $err" else ""}")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    if (o.has("error")) throw IllegalStateException(o.optString("error"))
                    val status = o.optString("status")
                    val completed = o.optLong("completed", -1)
                    val total = o.optLong("total", -1)
                    val msg = if (completed >= 0 && total > 0) "$status  ${100 * completed / total}%" else status
                    if (msg.isNotBlank()) onProgress(msg)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * POST {base}/api/chat (non-streaming) to a VISION model with one image.
     * Returns the model's text readout of the image. Throws on network/HTTP error.
     * The image must be raw base64 (no data: prefix), which is what Ollama expects.
     */
    fun describeImage(base: String, model: String, imageB64: String, instruction: String): String {
        val b = normalize(base)
        if (b.isEmpty()) throw IllegalStateException("No server address set.")

        val msg = JSONObject()
            .put("role", "user")
            .put("content", instruction)
            .put("images", JSONArray().put(imageB64))
        val payload = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(msg))
            .put("stream", false)
            .put("options", JSONObject().put("num_ctx", 4096))
            .toString()

        val conn = (URL("$b/api/chat").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 180000 // vision can be slow on a 7B over the tunnel
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload); it.flush() }
            if (conn.responseCode != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                throw IllegalStateException("Vision model returned ${conn.responseCode}${if (err != null) ": $err" else ""}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optJSONObject("message")?.optString("content")?.trim().orEmpty()
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
