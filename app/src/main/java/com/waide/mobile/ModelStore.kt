package com.waide.mobile

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// One server (machine) the app can talk to over Tailscale.
data class ServerEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val url: String
)

data class GgufFile(
    val name: String,
    val path: String,
    val sizeBytes: Long
) {
    val sizeLabel: String
        get() {
            val gb = sizeBytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) String.format("%.1f GB", gb)
            else String.format("%.0f MB", sizeBytes / 1024.0 / 1024.0)
        }
}

data class WaideSettings(
    val lastModelPath: String = "",
    val contextSize: Int = 4096,
    val threads: Int = 4,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val systemPrompt: String = "",
    val serverUrl: String = ""      // e.g. http://100.x.y.z:11434  (your PC over Tailscale)
)

class ModelStore(context: Context) {

    private val prefs = context.getSharedPreferences("waide", Context.MODE_PRIVATE)

    fun load(): WaideSettings = WaideSettings(
        lastModelPath = prefs.getString("model", "") ?: "",
        contextSize = prefs.getInt("ctx", 4096),
        threads = prefs.getInt("threads", 4),
        temperature = prefs.getFloat("temp", 0.7f),
        topP = prefs.getFloat("topP", 0.9f),
        minP = prefs.getFloat("minP", 0.05f),
        systemPrompt = prefs.getString("system", "") ?: "",
        serverUrl = prefs.getString("serverUrl", "") ?: ""
    )

    fun save(s: WaideSettings) {
        prefs.edit()
            .putString("model", s.lastModelPath)
            .putInt("ctx", s.contextSize)
            .putInt("threads", s.threads)
            .putFloat("temp", s.temperature)
            .putFloat("topP", s.topP)
            .putFloat("minP", s.minP)
            .putString("system", s.systemPrompt)
            .putString("serverUrl", s.serverUrl)
            .apply()
    }

    // ---------------- servers (machines) ----------------
    // The list of machines the app can reach. On first run, any single legacy
    // serverUrl is migrated into one entry so nothing is lost.
    fun loadServers(): List<ServerEntry> {
        val raw = prefs.getString("servers", "") ?: ""
        val out = mutableListOf<ServerEntry>()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += ServerEntry(o.optString("id", "s$i"), o.optString("name", "Server"), o.optString("url"))
                }
            }
        }
        if (out.isEmpty()) {
            val legacy = prefs.getString("serverUrl", "") ?: ""
            if (legacy.isNotBlank()) {
                val e = ServerEntry(name = "My PC", url = legacy)
                out += e
                saveServers(out)
                setActiveServerId(e.id)
            }
        }
        return out
    }

    fun saveServers(list: List<ServerEntry>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url))
        }
        prefs.edit().putString("servers", arr.toString()).apply()
    }

    fun getActiveServerId(): String = prefs.getString("activeServer", "") ?: ""
    fun setActiveServerId(id: String) { prefs.edit().putString("activeServer", id).apply() }

    // The vision model used to read attached images (remembered across restarts).
    fun getVisionModel(): String = prefs.getString("visionModel", "") ?: ""
    fun setVisionModel(name: String) { prefs.edit().putString("visionModel", name).apply() }

    // The one answering model for the whole server side (amnesia Server tab AND the
    // private Conversations tab share it). Set from Settings. Falls back to the old
    // per-tab key so an existing install keeps its previous choice.
    fun getServerModel(): String {
        val m = prefs.getString("serverModel", "") ?: ""
        return if (m.isNotBlank()) m else (prefs.getString("convModel", "") ?: "")
    }
    fun setServerModel(name: String) { prefs.edit().putString("serverModel", name).apply() }

    // Last model used on the private Conversations side (kept for migration only).
    fun getConvModel(): String = prefs.getString("convModel", "") ?: ""
    fun setConvModel(name: String) { prefs.edit().putString("convModel", name).apply() }

    companion object {
        /**
         * Looks for .gguf files in the folders a phone actually puts downloads in,
         * plus one level of subfolders so a "models" folder is picked up too.
         */
        fun scan(context: Context): List<GgufFile> {
            val roots = mutableListOf<File>()
            roots += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            roots += Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            roots += File(Environment.getExternalStorageDirectory(), "Models")
            roots += File(Environment.getExternalStorageDirectory(), "gguf")
            context.getExternalFilesDir(null)?.let { roots += it }

            val found = LinkedHashMap<String, GgufFile>()

            fun consider(f: File) {
                if (f.isFile && f.name.endsWith(".gguf", ignoreCase = true) && f.canRead()) {
                    found[f.absolutePath] = GgufFile(f.name, f.absolutePath, f.length())
                }
            }

            for (root in roots) {
                if (!root.exists() || !root.isDirectory) continue
                root.listFiles()?.forEach { child ->
                    consider(child)
                    if (child.isDirectory) {
                        child.listFiles()?.forEach { consider(it) }
                    }
                }
            }
            return found.values.sortedBy { it.name.lowercase() }
        }
    }
}
