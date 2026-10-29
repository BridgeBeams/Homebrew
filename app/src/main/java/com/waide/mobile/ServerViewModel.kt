package com.waide.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class ServerState(
    val messages: List<Message> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val loadingModels: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null,
    val managingBusy: Boolean = false,       // a pull/delete is in flight
    val pullStatus: String? = null,          // live download progress text
    // Hugging Face search
    val searching: Boolean = false,
    val searchResults: List<HfModel> = emptyList(),
    val quantRepo: String? = null,           // repo whose quant list is open
    val loadingQuants: Boolean = false,
    val quants: List<HfQuant> = emptyList(),
    // machines
    val servers: List<ServerEntry> = emptyList(),
    val activeServerId: String = "",
    // server context window (session only; resets to the default on a cold start)
    val ctx: Int = 8192,
    val bumpTo: Int? = null,            // non-null -> ask to raise ctx to this value
    val pendingPrompt: String? = null,  // prompt held while the user decides
    // image / vision (the + button on the Server tab)
    val visionModel: String = "",        // model used to read images; "" = not chosen yet
    val pendingImageB64: String? = null, // image attached and waiting to be sent
    val visionBusy: Boolean = false,     // the vision model is reading the image right now
    val visionPickerOpen: Boolean = false,
    val filesMsg: String? = null         // "Saved ... to Downloads" feedback after an export
)

/**
 * The Server tab. Anonymous, amnesia remote chat against your own Ollama box.
 * Nothing is persisted here: messages live in memory and vanish when cleared or
 * when the app is closed. The model list is fetched live from the server.
 */
class ServerViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state.asStateFlow()

    // ---------------- file export (Download as PDF/Word/Excel/... and whole-chat save) ----------------
    fun dismissFilesMsg() { _state.value = _state.value.copy(filesMsg = null) }

    /** Save any text to Downloads; the extension in [filename] decides the format. Prefers the
     *  PC server's renderer, falls back to the on-device renderer when it isn't reachable. */
    fun saveText(text: String, filename: String) {
        val app = getApplication<Application>()
        val b = ConversationsClient.baseFrom(serverUrl())
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { Exporter.saveText(app, b, text, filename) }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    filesMsg = if (r.isSuccess) "Saved \"${r.getOrNull()}\" to Downloads."
                               else (r.exceptionOrNull()?.message ?: "Couldn't save file."))
            }
        }
    }

    /** Save the whole current conversation as one document. */
    fun saveConversation(filename: String) {
        val turns = _state.value.messages.map { Exporter.speaker(it.role, it.model) to it.text }
        if (turns.isEmpty()) { _state.value = _state.value.copy(filesMsg = "Nothing to save yet."); return }
        saveText(Exporter.transcript(turns), filename)
    }

    private var genJob: Job? = null

    init {
        val servers = store.loadServers()
        val active = store.getActiveServerId().ifBlank { servers.firstOrNull()?.id ?: "" }
        _state.value = _state.value.copy(
            servers = servers, activeServerId = active,
            selectedModel = store.getServerModel(), visionModel = store.getVisionModel()
        )
        // Auto-load the server's models on first open so the user doesn't have to tap refresh.
        if (serverUrl().isNotBlank()) refreshModels()
    }

    private fun activeServer(): ServerEntry? =
        _state.value.servers.firstOrNull { it.id == _state.value.activeServerId }
            ?: _state.value.servers.firstOrNull()

    private fun serverUrl(): String = activeServer()?.url ?: ""

    // ---------------- machines ----------------
    fun selectServer(id: String) {
        if (id == _state.value.activeServerId) return
        store.setActiveServerId(id)
        _state.value = _state.value.copy(
            activeServerId = id, models = emptyList(), selectedModel = "",
            messages = emptyList(), streaming = null, connected = false, error = null
        )
        refreshModels()
    }

    fun addServer(name: String, url: String) {
        val n = name.trim().ifBlank { "Server" }
        val u = url.trim()
        if (u.isBlank()) return
        val e = ServerEntry(name = n, url = u)
        val list = _state.value.servers + e
        store.saveServers(list)
        val makeActive = _state.value.activeServerId.isBlank()
        if (makeActive) store.setActiveServerId(e.id)
        _state.value = _state.value.copy(
            servers = list,
            activeServerId = if (makeActive) e.id else _state.value.activeServerId
        )
    }

    fun removeServer(id: String) {
        val list = _state.value.servers.filter { it.id != id }
        store.saveServers(list)
        var active = _state.value.activeServerId
        if (active == id) {
            active = list.firstOrNull()?.id ?: ""
            store.setActiveServerId(active)
        }
        _state.value = _state.value.copy(servers = list, activeServerId = active,
            models = emptyList(), selectedModel = "", connected = false)
    }

    fun refreshModels() {
        _state.value = _state.value.copy(loadingModels = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { Net.retryOnConn { ServerClient.listModels(serverUrl()) } }
            withContext(Dispatchers.Main) {
                result.onSuccess { list ->
                    val saved = store.getServerModel()
                    val pick = when {
                        saved.isNotBlank() && list.contains(saved) -> saved
                        _state.value.selectedModel.isNotBlank() && list.contains(_state.value.selectedModel) -> _state.value.selectedModel
                        list.isNotEmpty() -> list.first()
                        else -> ""
                    }
                    if (pick.isNotBlank()) store.setServerModel(pick)
                    _state.value = _state.value.copy(
                        models = list, selectedModel = pick,
                        loadingModels = false, connected = true, error = null
                    )
                }.onFailure {
                    _state.value = _state.value.copy(
                        loadingModels = false, connected = false,
                        error = it.message ?: "Could not reach the server."
                    )
                }
            }
        }
    }

    fun selectModel(name: String) {
        store.setServerModel(name)
        _state.value = _state.value.copy(selectedModel = name)
    }

    // Remove a model from the server, then refresh the list.
    fun deleteServerModel(name: String) {
        if (_state.value.managingBusy) return
        _state.value = _state.value.copy(managingBusy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ServerClient.deleteModel(serverUrl(), name) }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(managingBusy = false)
                r.onFailure { _state.value = _state.value.copy(error = it.message ?: "Delete failed.") }
                refreshModels()
            }
        }
    }

    // ---------------- Hugging Face search ----------------
    fun searchHf(query: String) {
        val q = query.trim()
        if (q.isBlank() || _state.value.searching) return
        _state.value = _state.value.copy(searching = true, error = null, searchResults = emptyList())
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ServerClient.searchHf(q) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _state.value = _state.value.copy(searching = false, searchResults = it) }
                 .onFailure { _state.value = _state.value.copy(searching = false, error = it.message ?: "Search failed.") }
            }
        }
    }

    fun openQuants(repoId: String) {
        _state.value = _state.value.copy(quantRepo = repoId, loadingQuants = true, quants = emptyList(), error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ServerClient.listHfQuants(repoId) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _state.value = _state.value.copy(loadingQuants = false, quants = it) }
                 .onFailure { _state.value = _state.value.copy(loadingQuants = false, quantRepo = null, error = it.message ?: "Could not read that repo.") }
            }
        }
    }

    fun closeQuants() {
        _state.value = _state.value.copy(quantRepo = null, quants = emptyList(), loadingQuants = false)
    }

    // Pull a chosen Hugging Face repo+quant onto the server.
    fun pullHf(repoId: String, tag: String) {
        closeQuants()
        pullServerModel("hf.co/$repoId:$tag")
    }

    // Download a model onto the server by name, with live progress. The foreground
    // service keeps it going if the screen locks during a long download.
    fun pullServerModel(name: String) {
        val n = name.trim()
        if (n.isBlank() || _state.value.managingBusy) return
        _state.value = _state.value.copy(managingBusy = true, pullStatus = "Starting...", error = null)
        GenerationService.begin(getApplication())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ServerClient.pullModel(serverUrl(), n) { p ->
                    _state.value = _state.value.copy(pullStatus = p)
                }
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(managingBusy = false, pullStatus = null)
                    refreshModels()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(managingBusy = false, pullStatus = null,
                        error = e.message ?: "Pull failed.")
                }
            } finally {
                GenerationService.end(getApplication())
            }
        }
    }

    fun clearChat() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(messages = emptyList(), streaming = null, error = null,
            bumpTo = null, pendingPrompt = null, pendingImageB64 = null)
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    /**
     * Last-resort guard so the sent thread never overflows the window. The full thread
     * stays on screen; only what fits the given character budget is actually sent. The
     * newest message is always kept, so even one very large paste still goes through.
     * In normal use the bump prompt raises the window first, so this rarely trims.
     */
    private fun trimToBudget(msgs: List<Message>, maxChars: Int): List<Message> {
        if (msgs.isEmpty()) return msgs
        var total = 0
        val kept = ArrayDeque<Message>()
        for (m in msgs.asReversed()) {
            val len = m.text.length + 16
            if (kept.isNotEmpty() && total + len > maxChars) break
            kept.addFirst(m)
            total += len
        }
        return kept.toList()
    }

    // Set the server window for this session (from the Settings slider). Not saved,
    // so it returns to the default the next time the app is opened from closed.
    fun setCtx(v: Int) {
        val snapped = (v.coerceIn(2048, MAX_CTX) / 2048) * 2048
        _state.value = _state.value.copy(ctx = snapped.coerceAtLeast(2048))
    }

    // ---------------- image / vision ----------------
    fun openVisionPicker()  { _state.value = _state.value.copy(visionPickerOpen = true) }
    fun closeVisionPicker() { _state.value = _state.value.copy(visionPickerOpen = false) }

    // Remember the chosen image reader across restarts.
    fun setVisionModel(name: String) {
        store.setVisionModel(name)
        _state.value = _state.value.copy(visionModel = name, visionPickerOpen = false)
    }

    // A photo was picked: downscale and base64-encode it off the main thread.
    fun attachImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val b64 = runCatching { uriToBase64(uri) }.getOrNull()
            withContext(Dispatchers.Main) {
                _state.value = if (b64 == null)
                    _state.value.copy(error = "Couldn't read that image.")
                else
                    _state.value.copy(pendingImageB64 = b64, error = null)
            }
        }
    }

    fun clearImage() { _state.value = _state.value.copy(pendingImageB64 = null) }

    private fun uriToBase64(uri: Uri): String {
        val ctx = getApplication<Application>()
        val bmp = ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("decode failed")
        val scaled = scaleDown(bmp, 1024)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(bmp.width, bmp.height)
        if (m <= maxDim) return bmp
        val s = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
    }

    private fun visionInstruction(prompt: String): String =
        "You are reading an image for another assistant that cannot see it. " +
        "Transcribe ALL visible text exactly, including headings, numbers, labels, and fine print. " +
        "Then briefly describe any photos, objects, people, charts, and the layout. " +
        "The user will ask: \"" + prompt + "\" so be sure to capture anything relevant. " +
        "Do not answer the question yourself; only report what is in the image."

    fun send(text: String) {
        if (_state.value.busy) return
        val img = _state.value.pendingImageB64
        var prompt = text.trim()
        if (prompt.isEmpty() && img == null) return
        if (_state.value.selectedModel.isBlank()) {
            _state.value = _state.value.copy(error = "Pick a model first (tap Refresh if the list is empty).")
            return
        }
        // Image attached: hand it to the vision model first, then let the main model answer.
        if (img != null) {
            if (_state.value.visionModel.isBlank()) {
                _state.value = _state.value.copy(
                    error = "No image reader set. Choose one in Settings > Server models.")
                return
            }
            if (prompt.isEmpty()) prompt = "What's in this image?"
            sendWithImage(prompt, img)
            return
        }
        // Text only: would this prompt plus the running thread overflow the current
        // window? If so, ask before leaning harder on the GPU instead of silently trimming.
        val projected = _state.value.messages + Message("user", prompt)
        val needed = estimateTokens(projected) + REPLY_RESERVE
        val cur = _state.value.ctx
        if (needed > cur && cur < MAX_CTX) {
            val desired = roundUp2048(needed).coerceAtMost(MAX_CTX)
            if (desired > cur) {
                _state.value = _state.value.copy(bumpTo = desired, pendingPrompt = prompt)
                return
            }
        }
        runSend(prompt, cur)
    }

    // Two-model flow: the vision model reads the image into text, that readout is kept
    // in the thread as a hidden note, and the main model answers using it. Follow-up
    // questions still work because the note stays in the conversation.
    private fun sendWithImage(prompt: String, imageB64: String) {
        val visionModel = _state.value.visionModel
        val base = _state.value.messages
        val userMsg = Message("user", prompt, imageB64 = imageB64)
        _state.value = _state.value.copy(
            messages = base + userMsg, pendingImageB64 = null,
            streaming = "", busy = true, visionBusy = true, error = null
        )
        val numCtx = _state.value.ctx
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
          try {
            val readout = runCatching {
                ServerClient.describeImage(serverUrl(), visionModel, imageB64, visionInstruction(prompt))
            }.getOrNull()

            // Hard failures (error, timeout, empty) -> clean "Error reading image."
            if (readout.isNullOrBlank()) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        messages = _state.value.messages + Message("assistant", "Error reading image."),
                        streaming = null, busy = false, visionBusy = false
                    )
                }
                return@launch
            }

            // Attach the readout to the user's own turn (hidden). It travels with that
            // message, so the model keeps the image context for later questions, and there
            // is no mid-thread system message (some chat templates reject that).
            val withNote = userMsg.copy(visionNote = readout)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(messages = base + withNote, visionBusy = false)
            }

            val budgetChars = (numCtx - REPLY_RESERVE).coerceAtLeast(1024) * 4
            val toSend = trimToBudget(toModel(base + withNote), budgetChars)

            var acc = ""
            var genImage: String? = null
            val usedModel = _state.value.selectedModel
            val reply = ServerClient.chatStream(serverUrl(), usedModel, toSend, numCtx,
                onImage = { if (genImage == null) genImage = it }) { piece ->
                acc += piece
                _state.value = _state.value.copy(streaming = acc)
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    messages = _state.value.messages + Message("assistant",
                        reply.ifBlank { if (genImage != null) "" else "(no reply)" }, imageB64 = genImage,
                        model = usedModel),
                    streaming = null, busy = false
                )
            }
          } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(busy = false, streaming = null, visionBusy = false,
                    error = e.message ?: "Server error.")
            }
          } finally {
            GenerationService.end(getApplication())
          }
        }
    }

    // User agreed to the larger window: raise it for this conversation and send.
    fun confirmBump() {
        val target = _state.value.bumpTo ?: return
        val prompt = _state.value.pendingPrompt ?: return
        _state.value = _state.value.copy(ctx = target, bumpTo = null, pendingPrompt = null)
        runSend(prompt, target)
    }

    // User declined: send at the current window (older messages get trimmed to fit).
    fun declineBump() {
        val prompt = _state.value.pendingPrompt ?: return
        val cur = _state.value.ctx
        _state.value = _state.value.copy(bumpTo = null, pendingPrompt = null)
        runSend(prompt, cur)
    }

    private fun runSend(prompt: String, numCtx: Int) {
        val model = _state.value.selectedModel
        if (model.isBlank()) return
        val outgoing = _state.value.messages + Message("user", prompt)
        _state.value = _state.value.copy(messages = outgoing, streaming = "", busy = true, error = null)

        val budgetChars = (numCtx - REPLY_RESERVE).coerceAtLeast(1024) * 4
        val toSend = trimToBudget(toModel(outgoing), budgetChars)
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
          try {
            var acc = ""
            var genImage: String? = null
            val reply = ServerClient.chatStream(serverUrl(), model, toSend, numCtx,
                onImage = { if (genImage == null) genImage = it }) { piece ->
                acc += piece
                _state.value = _state.value.copy(streaming = acc)
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    messages = _state.value.messages + Message("assistant",
                        reply.ifBlank { if (genImage != null) "" else "(no reply)" }, imageB64 = genImage,
                        model = model),
                    streaming = null, busy = false
                )
            }
          } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(busy = false, streaming = null,
                    error = e.message ?: "Server error.")
            }
          } finally {
            GenerationService.end(getApplication())
          }
        }
    }

    // Fold each image turn's hidden readout into its text so the main model sees the
    // image as plain text on its own turn. No system role is used (some chat templates
    // require any system message to be first), so follow-up questions keep working.
    private fun toModel(msgs: List<Message>): List<Message> = msgs.map { m ->
        if (m.visionNote != null)
            m.copy(
                text = "[An image was attached. A vision model read it as:]\n" + m.visionNote +
                       "\n\n[The user's message about the image:]\n" + m.text,
                visionNote = null, imageB64 = null
            )
        else m
    }

    // Rough token count for the whole thread: ~4 characters per token, plus a little
    // per-message overhead. Only used to decide whether to ask for a bigger window.
    private fun estimateTokens(msgs: List<Message>): Int {
        var chars = 0
        for (m in msgs) chars += m.text.length + (m.visionNote?.length ?: 0) + 8
        return chars / 4
    }

    private fun roundUp2048(n: Int): Int =
        (((n + 2047) / 2048) * 2048).coerceAtLeast(2048)

    fun stopGeneration() {
        genJob?.cancel()
        _state.value = _state.value.copy(busy = false, streaming = null)
    }

    companion object {
        const val DEFAULT_CTX = 8192
        const val MAX_CTX = 65536      // 8x the default
        const val REPLY_RESERVE = 1024 // tokens held back for the model's answer
    }
}
