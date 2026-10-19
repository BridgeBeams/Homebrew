package com.waide.mobile

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    val systemPrompt: String = ""
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
        systemPrompt = prefs.getString("system", "") ?: ""
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
            .apply()
    }

    // ---- brain memory: the remembered slice persists so it survives restarts ----
    fun saveMemory(on: Boolean, remembered: List<Message>) {
        val arr = JSONArray()
        remembered.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id); put("role", m.role); put("text", m.text)
            })
        }
        prefs.edit()
            .putBoolean("mem_on", on)
            .putString("mem_msgs", arr.toString())
            .apply()
    }

    fun loadMemory(): Pair<Boolean, List<Message>> {
        val on = prefs.getBoolean("mem_on", false)
        val raw = prefs.getString("mem_msgs", "") ?: ""
        val out = mutableListOf<Message>()
        if (raw.isNotBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out += Message(
                        role = o.getString("role"),
                        text = o.getString("text"),
                        id = o.optString("id", "r$i"),
                        remembered = true
                    )
                }
            }
        }
        return on to out
    }

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
