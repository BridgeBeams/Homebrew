package com.waide.mobile

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Message(
    val role: String,              // "user" or "assistant"
    val text: String,
    val imageB64: String? = null,  // set on a Server-tab turn that had a photo attached (thumbnail)
    val visionNote: String? = null // the vision model's readout, folded into this turn for the model
)

data class ChatState(
    val messages: List<Message> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val modelName: String = "",
    val modelLoading: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val contextUsed: Int = 0,
    val error: String? = null
)

// How well a given model file is expected to run on THIS phone, from its size vs RAM.
enum class Fit { SMOOTH, TIGHT, TOOBIG, UNKNOWN }

// One downloadable .gguf option within a repo, with a phone-specific recommendation.
data class HfFileOption(
    val path: String,        // full repo path, used to build the download URL
    val label: String,       // display name (the file's basename)
    val sizeBytes: Long,
    val fit: Fit
) {
    val sizeLabel: String
        get() {
            if (sizeBytes <= 0) return "size unknown"
            val gb = sizeBytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) String.format("%.1f GB", gb)
            else String.format("%.0f MB", sizeBytes / 1024.0 / 1024.0)
        }
}

// The "find and download a model" browser on the Local Models screen.
data class LocalModelBrowse(
    val searching: Boolean = false,
    val results: List<HfModel> = emptyList(),
    val quantRepo: String? = null,
    val loadingQuants: Boolean = false,
    val quants: List<HfFileOption> = emptyList(),
    val downloading: Boolean = false,
    val downloadStatus: String? = null,
    val error: String? = null
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<WaideSettings> = _settings.asStateFlow()

    private val _models = MutableStateFlow<List<GgufFile>>(emptyList())
    val models: StateFlow<List<GgufFile>> = _models.asStateFlow()

    private val _browse = MutableStateFlow(LocalModelBrowse())
    val browse: StateFlow<LocalModelBrowse> = _browse.asStateFlow()

    private var genJob: Job? = null
    private var searchJob: Job? = null
    private var downloadJob: Job? = null
    @Volatile private var cancelDownload = false

    // Physical RAM on this device; used to judge which models will run smoothly.
    private val totalRam: Long by lazy {
        runCatching {
            val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem
        }.getOrDefault(0L)
    }

    // A loaded model needs roughly its file size in RAM plus room for the OS and context.
    // These thresholds are deliberately conservative so a "smooth" pick really is smooth.
    private fun fitFor(size: Long): Fit = when {
        totalRam <= 0 || size <= 0 -> Fit.UNKNOWN
        size <= totalRam * 45 / 100 -> Fit.SMOOTH
        size <= totalRam * 70 / 100 -> Fit.TIGHT
        else -> Fit.TOOBIG
    }

    // ---------------- find & download models from Hugging Face ----------------
    fun searchLocalHf(query: String) {
        val q = query.trim()
        if (q.isBlank() || _browse.value.searching) return
        searchJob?.cancel()
        _browse.value = _browse.value.copy(searching = true, error = null, results = emptyList())
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ServerClient.searchHf(q) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _browse.value = _browse.value.copy(searching = false, results = it) }
                 .onFailure { _browse.value = _browse.value.copy(searching = false, error = it.message ?: "Search failed.") }
            }
        }
    }

    fun openLocalQuants(repoId: String) {
        _browse.value = _browse.value.copy(quantRepo = repoId, loadingQuants = true, quants = emptyList(), error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ServerClient.listHfFiles(repoId) }
            withContext(Dispatchers.Main) {
                r.onSuccess { raw ->
                    val opts = raw.map {
                        HfFileOption(it.path, it.path.substringAfterLast('/'), it.sizeBytes, fitFor(it.sizeBytes))
                    }
                    _browse.value = _browse.value.copy(loadingQuants = false, quants = opts)
                }.onFailure {
                    _browse.value = _browse.value.copy(loadingQuants = false, quantRepo = null,
                        error = it.message ?: "Could not read that repo.")
                }
            }
        }
    }

    fun closeLocalQuants() {
        _browse.value = _browse.value.copy(quantRepo = null, quants = emptyList(), loadingQuants = false)
    }

    fun closeLocalSearch() {
        searchJob?.cancel()
        _browse.value = _browse.value.copy(searching = false, results = emptyList())
    }

    fun cancelLocalDownload() { cancelDownload = true }

    fun dismissBrowseError() { _browse.value = _browse.value.copy(error = null) }

    // Download a chosen file into the app's model folder (no storage permission needed),
    // then rescan so it shows up in the installed list. Runs under the foreground service
    // so a long download survives the screen locking.
    fun downloadLocalModel(repoId: String, file: HfFileOption) {
        if (_browse.value.downloading) return
        val dir = getApplication<Application>().getExternalFilesDir(null)
            ?: getApplication<Application>().filesDir
        val outFile = java.io.File(dir, file.label)
        cancelDownload = false
        _browse.value = _browse.value.copy(downloading = true, downloadStatus = "Starting...", quantRepo = null, error = null)
        GenerationService.begin(getApplication())
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                ServerClient.downloadTo(repoId, file.path, outFile, isCancelled = { cancelDownload }) { done, total ->
                    val status = if (total > 0) {
                        val pct = (100 * done / total).toInt()
                        "${file.label}  $pct%  (${gb(done)}/${gb(total)})"
                    } else "${file.label}  ${gb(done)}"
                    _browse.value = _browse.value.copy(downloadStatus = status)
                }
                val list = ModelStore.scan(getApplication())
                withContext(Dispatchers.Main) {
                    _models.value = list
                    // Clear the search once a download lands, so the user sees the installed list.
                    _browse.value = _browse.value.copy(
                        downloading = false, downloadStatus = null, results = emptyList(), quants = emptyList())
                }
            } catch (e: Exception) {
                val msg = if (cancelDownload) "Download cancelled." else (e.message ?: "Download failed.")
                withContext(Dispatchers.Main) {
                    _browse.value = _browse.value.copy(downloading = false, downloadStatus = null, error = msg)
                }
            } finally {
                GenerationService.end(getApplication())
            }
        }
    }

    private fun gb(b: Long): String {
        val g = b / 1024.0 / 1024.0 / 1024.0
        return if (g >= 1.0) String.format("%.1f GB", g) else String.format("%.0f MB", b / 1024.0 / 1024.0)
    }

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _models.value = ModelStore.scan(getApplication())
        }
    }

    // Delete a local .gguf file from storage, then rescan. If it's the loaded one,
    // unload it from memory first so the app isn't pointing at a deleted file.
    fun deleteLocalModel(file: GgufFile) {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.IO) {
            if (_state.value.modelName == file.name) {
                LlamaBridge.nativeUnload()
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(modelName = "", messages = emptyList(), contextUsed = 0)
                }
            }
            val ok = runCatching { java.io.File(file.path).delete() }.getOrDefault(false)
            val list = ModelStore.scan(getApplication())
            withContext(Dispatchers.Main) {
                _models.value = list
                if (!ok) _state.value = _state.value.copy(error = "Could not delete ${file.name}.")
            }
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    fun loadModel(file: GgufFile) {
        if (_state.value.modelLoading || _state.value.busy) return
        _state.value = _state.value.copy(modelLoading = true, error = null)
        viewModelScope.launch(Dispatchers.Default) {
            val s = _settings.value
            val ok = LlamaBridge.nativeLoad(file.path, s.contextSize, s.threads)
            if (ok) {
                LlamaBridge.nativeSetSampling(s.temperature, s.topP, s.minP)
                if (s.systemPrompt.isNotBlank()) {
                    LlamaBridge.nativeSetSystemPrompt(s.systemPrompt)
                }
                _settings.value = s.copy(lastModelPath = file.path)
                store.save(_settings.value)
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        modelLoading = false,
                        modelName = file.name,
                        messages = emptyList(),
                        streaming = null,
                        contextUsed = 0
                    )
                }
            } else {
                val err = LlamaBridge.nativeLastError().ifBlank {
                    "That model would not load."
                }
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(modelLoading = false, error = err)
                }
            }
        }
    }

    fun unloadModel() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.Default) {
            LlamaBridge.nativeUnload()
            withContext(Dispatchers.Main) {
                _state.value = ChatState()
            }
        }
    }

    fun newChat() {
        if (_state.value.busy) return
        viewModelScope.launch(Dispatchers.Default) {
            LlamaBridge.nativeResetChat()
            val s = _settings.value
            if (s.systemPrompt.isNotBlank()) LlamaBridge.nativeSetSystemPrompt(s.systemPrompt)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    messages = emptyList(),
                    streaming = null,
                    contextUsed = 0,
                    tokensPerSecond = 0f
                )
            }
        }
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy || _state.value.modelName.isEmpty()) return

        _state.value = _state.value.copy(
            messages = _state.value.messages + Message("user", prompt),
            streaming = "",
            busy = true,
            tokensPerSecond = 0f,
            error = null
        )

        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.Default) {
          try {
            val rc = LlamaBridge.nativeBeginTurn(prompt)
            if (rc != 0) {
                val err = LlamaBridge.nativeLastError()
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        busy = false, streaming = null, error = err
                    )
                }
                return@launch
            }

            val sb = StringBuilder()
            var count = 0
            var firstTokenAt = 0L
            var lastPush = 0L

            while (true) {
                val piece = LlamaBridge.nativeNextToken() ?: break
                if (count == 0) firstTokenAt = System.nanoTime()
                sb.append(piece)
                count++

                val now = System.currentTimeMillis()
                if (now - lastPush > 60) {
                    lastPush = now
                    val elapsed = (System.nanoTime() - firstTokenAt) / 1_000_000_000.0
                    val tps = if (elapsed > 0.2) (count / elapsed).toFloat() else 0f
                    val snapshot = sb.toString()
                    withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(
                            streaming = snapshot,
                            tokensPerSecond = tps
                        )
                    }
                }
            }

            val reply = sb.toString()
            val used = LlamaBridge.nativeContextUsed()
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    messages = _state.value.messages + Message("assistant", reply.ifBlank { "(no reply)" }),
                    streaming = null,
                    busy = false,
                    contextUsed = used
                )
            }
          } finally {
            GenerationService.end(getApplication())
          }
        }
    }

    fun stopGeneration() {
        if (!_state.value.busy) return
        viewModelScope.launch(Dispatchers.Default) {
            LlamaBridge.nativeStop()
        }
    }

    fun updateSettings(s: WaideSettings) {
        _settings.value = s
        store.save(s)
        if (_state.value.modelName.isNotEmpty() && !_state.value.busy) {
            viewModelScope.launch(Dispatchers.Default) {
                LlamaBridge.nativeSetSampling(s.temperature, s.topP, s.minP)
            }
        }
    }
}
