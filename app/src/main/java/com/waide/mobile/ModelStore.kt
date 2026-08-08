package com.waide.mobile

import android.content.Context
import android.os.Environment
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
