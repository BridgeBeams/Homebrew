package com.waide.mobile

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
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

data class PremiumState(
    // setup
    val hasKey: Boolean = false,
    val checkedKey: Boolean = false,
    // models
    val models: List<PremiumModel> = emptyList(),
    val loadingModels: Boolean = false,
    val selectedModel: String = "",        // default model for new chats
    // conversations
    val conversations: List<PremiumSummary> = emptyList(),
    val loadingList: Boolean = false,
    val openId: String? = null,
    val openTitle: String = "",
    val openModel: String = "",            // the model this conversation uses (single mode)
    val messages: List<PremiumMsg> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    // favorites (float to top of the picker everywhere)
    val favorites: Set<String> = emptySet(),
    // ask-all (multi-model) state
    val openMode: String = "single",       // "single" or "multi"
    val openModels: List<String> = emptyList(),   // the models this conversation fans out to
    val multiStreaming: Map<Int, String> = emptyMap(), // column index -> text streaming now
    val multiDone: Set<Int> = emptySet(),  // columns that have finished this turn
    val askAllPicker: Boolean = false,     // the "pick up to 3" dialog is open
    // attachments waiting to send
    val pendingAttachments: List<PremiumAttach> = emptyList(),
    // conversation files folder
    val files: List<ConvFile> = emptyList(),
    val filesOpen: Boolean = false,
    val loadingFiles: Boolean = false,
    val filesMsg: String? = null,
    val fileThumbs: Map<String, String> = emptyMap(),   // fileId -> image base64 (for thumbnails)
    // compaction: how many leading turns are folded into the summary, and a transient notice
    val compactedCount: Int = 0,
    val compactedAt: Double = 0.0,
    val compactingNotice: Boolean = false,
    // hide / unhide
    val unhideTarget: String? = null,
    val unhideError: String? = null
)

/** Premium agents: personas that read ONLY premium conversations, answered on a cloud model. */
data class PremiumAgentState(
    val agents: List<PremiumAgentSummary> = emptyList(),
    val loadingList: Boolean = false,
    val openId: String? = null,
    val openName: String = "",
    val openJob: String = "",
    val openModel: String = "",
    val messages: List<PremiumAgentMsg> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val unhideTarget: String? = null,
    val unhideError: String? = null
)

/**
 * Premium AI tab: paid cloud models via OpenRouter, keyed and stored on your PC server.
 * Open to use (no PIN); hiding a conversation needs no PIN, revealing one does.
 */
class PremiumViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val _state = MutableStateFlow(PremiumState(
        selectedModel = store.getPremiumModel(),
        favorites = store.getFavoriteModels()
    ))
    val state: StateFlow<PremiumState> = _state.asStateFlow()

    private var genJob: Job? = null

    private val _agentState = MutableStateFlow(PremiumAgentState())
    val agentState: StateFlow<PremiumAgentState> = _agentState.asStateFlow()
    private var agentGenJob: Job? = null

    private fun base(): String {
        val servers = store.loadServers()
        val active = store.getActiveServerId().ifBlank { servers.firstOrNull()?.id ?: "" }
        val url = servers.firstOrNull { it.id == active }?.url ?: ""
        return ConversationsClient.baseFrom(url)
    }

    fun refreshStatus() {
        val b = base()
        if (b.isBlank()) { _state.value = _state.value.copy(checkedKey = true, error = "No machine set. Add one in Settings."); return }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.status(b) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _state.value = _state.value.copy(hasKey = it, checkedKey = true, error = null) }
                 .onFailure { _state.value = _state.value.copy(checkedKey = true, error = it.message) }
            }
        }
        refreshList()
    }

    // Callable from Settings. Sets the OpenRouter key (PIN required, checked on server).
    fun setKey(pin: String, key: String, onDone: (String?) -> Unit) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.setKey(b, pin, key) }
            withContext(Dispatchers.Main) {
                if (r.isSuccess) { _state.value = _state.value.copy(hasKey = true) }
                onDone(r.exceptionOrNull()?.message)
            }
        }
    }

    fun refreshModels() {
        val b = base()
        _state.value = _state.value.copy(loadingModels = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.models(b) }
            withContext(Dispatchers.Main) {
                r.onSuccess { list ->
                    val saved = store.getPremiumModel()
                    val pick = when {
                        saved.isNotBlank() && list.any { it.id == saved } -> saved
                        _state.value.selectedModel.isNotBlank() -> _state.value.selectedModel
                        else -> list.firstOrNull()?.id ?: ""
                    }
                    _state.value = _state.value.copy(models = list, loadingModels = false, selectedModel = pick, error = null)
                }.onFailure { _state.value = _state.value.copy(loadingModels = false, error = it.message) }
            }
        }
    }

    fun setDefaultModel(id: String) {
        store.setPremiumModel(id)
        _state.value = _state.value.copy(selectedModel = id)
    }

    // Change the model used by the currently-open conversation.
    fun setOpenModel(id: String) {
        _state.value = _state.value.copy(openModel = id)
    }

    fun refreshList() {
        val b = base()
        _state.value = _state.value.copy(loadingList = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.list(b) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _state.value = _state.value.copy(conversations = it, loadingList = false) }
                 .onFailure { _state.value = _state.value.copy(loadingList = false, error = it.message) }
            }
        }
    }

    fun openConversation(id: String) {
        val b = base()
        _state.value = _state.value.copy(openId = id, messages = emptyList(), streaming = null,
            multiStreaming = emptyMap(), multiDone = emptySet(), busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.get(b, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d ->
                    _state.value = _state.value.copy(openId = id, openTitle = d.title,
                        openModel = d.model.ifBlank { _state.value.selectedModel },
                        openMode = d.mode, openModels = d.models, messages = d.messages, busy = false,
                        files = emptyList(), filesOpen = false, fileThumbs = emptyMap(),
                        compactedCount = d.compactedCount, compactedAt = d.compactedAt,
                        compactingNotice = false)
                }.onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
            }
        }
    }

    private fun reloadOpen(id: String) {
        val b = base()
        val prevCompactedAt = _state.value.compactedAt
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.get(b, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d -> _state.value = _state.value.copy(openTitle = d.title,
                    openModel = d.model.ifBlank { _state.value.openModel },
                    openMode = d.mode, openModels = d.models, messages = d.messages,
                    compactedCount = d.compactedCount, compactedAt = d.compactedAt,
                    compactingNotice = d.compactedAt > prevCompactedAt) }
            }
        }
    }

    fun dismissCompactingNotice() { _state.value = _state.value.copy(compactingNotice = false) }

    fun closeConversation() {
        genJob?.cancel()
        _state.value = _state.value.copy(openId = null, messages = emptyList(), streaming = null,
            multiStreaming = emptyMap(), multiDone = emptySet(), openMode = "single", openModels = emptyList(),
            busy = false, pendingAttachments = emptyList(),
            files = emptyList(), filesOpen = false, filesMsg = null, fileThumbs = emptyMap(),
            compactedCount = 0, compactedAt = 0.0, compactingNotice = false)
        refreshList()
    }

    fun newConversation() {
        val b = base()
        val model = _state.value.selectedModel
        if (model.isBlank()) { _state.value = _state.value.copy(error = "Pick a default model first."); return }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.create(b, null, model) }
            withContext(Dispatchers.Main) {
                r.onSuccess { id ->
                    _state.value = _state.value.copy(openId = id, openTitle = "New chat", openModel = model,
                        openMode = "single", openModels = emptyList(),
                        messages = emptyList(), streaming = null, multiStreaming = emptyMap(), multiDone = emptySet(),
                        busy = false, pendingAttachments = emptyList(), error = null,
                        files = emptyList(), filesOpen = false, fileThumbs = emptyMap(),
                        compactedCount = 0, compactedAt = 0.0, compactingNotice = false)
                }.onFailure { _state.value = _state.value.copy(error = it.message) }
            }
        }
    }

    // ---------------- favorites ----------------
    fun toggleFavorite(id: String) {
        val cur = _state.value.favorites.toMutableSet()
        if (!cur.add(id)) cur.remove(id)
        store.setFavoriteModels(cur)
        _state.value = _state.value.copy(favorites = cur)
    }

    // ---------------- ask all (multi-model) ----------------
    fun openAskAllPicker() { _state.value = _state.value.copy(askAllPicker = true) }
    fun dismissAskAllPicker() { _state.value = _state.value.copy(askAllPicker = false) }

    /** Create a new multi-model conversation that fans each question out to all [models]. */
    fun startAskAll(models: List<String>) {
        val b = base()
        val clean = models.distinct().filter { it.isNotBlank() }.take(3)
        if (clean.isEmpty()) { _state.value = _state.value.copy(error = "Pick at least one model."); return }
        _state.value = _state.value.copy(askAllPicker = false)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.createMulti(b, "Ask all", clean) }
            withContext(Dispatchers.Main) {
                r.onSuccess { id ->
                    _state.value = _state.value.copy(openId = id, openTitle = "Ask all",
                        openModel = clean.first(), openMode = "multi", openModels = clean,
                        messages = emptyList(), streaming = null, multiStreaming = emptyMap(), multiDone = emptySet(),
                        busy = false, pendingAttachments = emptyList(), error = null,
                        files = emptyList(), filesOpen = false, fileThumbs = emptyMap(),
                        compactedCount = 0, compactedAt = 0.0, compactingNotice = false)
                }.onFailure { _state.value = _state.value.copy(error = it.message) }
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PremiumClient.rename(b, id, title) }
            withContext(Dispatchers.Main) {
                if (_state.value.openId == id) _state.value = _state.value.copy(openTitle = title)
                refreshList()
            }
        }
    }

    fun deleteConversation(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PremiumClient.deleteConv(b, id) }
            withContext(Dispatchers.Main) {
                if (_state.value.openId == id) _state.value = _state.value.copy(openId = null, messages = emptyList())
                refreshList()
            }
        }
    }

    fun deleteMessage(mid: String) {
        val id = _state.value.openId ?: return
        val b = base()
        _state.value = _state.value.copy(messages = _state.value.messages.filter { it.id != mid })
        viewModelScope.launch(Dispatchers.IO) { runCatching { PremiumClient.deleteMsg(b, id, mid) } }
    }

    fun send(text: String) {
        if (_state.value.openMode == "multi") { sendMulti(text); return }
        val id = _state.value.openId ?: return
        val model = _state.value.openModel.ifBlank { _state.value.selectedModel }
        val prompt = text.trim()
        val atts = _state.value.pendingAttachments
        if (_state.value.busy) return
        if (prompt.isEmpty() && atts.isEmpty()) return
        if (model.isBlank()) { _state.value = _state.value.copy(error = "Pick a model first."); return }
        val b = base()
        // optimistic local echo (images show immediately; files show as a chip)
        val localAtts = atts.map { if (it.kind == "image") it else it.copy(data = null) }
        val localUser = PremiumMsg("local-${System.currentTimeMillis()}", "user",
            prompt, model, localAtts)
        _state.value = _state.value.copy(messages = _state.value.messages + localUser,
            pendingAttachments = emptyList(), streaming = "", busy = true, error = null)
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var acc = ""
                PremiumClient.chatStream(b, id, model, prompt, atts) { piece ->
                    acc += piece
                    _state.value = _state.value.copy(streaming = acc)
                }
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(streaming = null, busy = false)
                    reloadOpen(id)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(busy = false, streaming = null, error = e.message ?: "Server error")
                }
            } finally {
                GenerationService.end(getApplication())
            }
        }
    }

    private fun sendMulti(text: String) {
        val id = _state.value.openId ?: return
        val models = _state.value.openModels
        val prompt = text.trim()
        val atts = _state.value.pendingAttachments
        if (_state.value.busy) return
        if (prompt.isEmpty() && atts.isEmpty()) return
        if (models.isEmpty()) { _state.value = _state.value.copy(error = "This chat has no models."); return }
        val b = base()
        // optimistic local echo of the user's turn
        val localAtts = atts.map { if (it.kind == "image") it else it.copy(data = null) }
        val localUser = PremiumMsg("local-${System.currentTimeMillis()}", "user", prompt, "", localAtts)
        val startStreaming = models.indices.associateWith { "" }
        _state.value = _state.value.copy(messages = _state.value.messages + localUser,
            pendingAttachments = emptyList(), multiStreaming = startStreaming, multiDone = emptySet(),
            busy = true, error = null)
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val acc = HashMap<Int, String>()
                models.indices.forEach { acc[it] = "" }
                PremiumClient.multiChatStream(b, id, prompt, atts) { idx, piece, done ->
                    if (piece.isNotEmpty()) acc[idx] = (acc[idx] ?: "") + piece
                    val snapshot = HashMap(acc)
                    _state.value = _state.value.copy(
                        multiStreaming = snapshot,
                        multiDone = if (done) _state.value.multiDone + idx else _state.value.multiDone)
                }
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(multiStreaming = emptyMap(), multiDone = emptySet(), busy = false)
                    reloadOpen(id)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(busy = false, multiStreaming = emptyMap(), multiDone = emptySet(),
                        error = e.message ?: "Server error")
                    reloadOpen(id)
                }
            } finally {
                GenerationService.end(getApplication())
            }
        }
    }

    fun stopGeneration() {
        genJob?.cancel()
        _state.value = _state.value.copy(busy = false, streaming = null, multiStreaming = emptyMap(), multiDone = emptySet())
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    // ---------------- conversation files folder ----------------
    fun openFiles() {
        _state.value = _state.value.copy(filesOpen = true, filesMsg = null)
        refreshFiles()
    }
    fun closeFiles() { _state.value = _state.value.copy(filesOpen = false) }
    fun dismissFilesMsg() { _state.value = _state.value.copy(filesMsg = null) }

    fun refreshFiles() {
        val id = _state.value.openId ?: return
        val b = base()
        _state.value = _state.value.copy(loadingFiles = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.files(b, id) }
            val list = r.getOrNull()
            withContext(Dispatchers.Main) {
                if (list != null) _state.value = _state.value.copy(files = list, loadingFiles = false)
                else _state.value = _state.value.copy(loadingFiles = false, filesMsg = r.exceptionOrNull()?.message)
            }
            // Pull each image's bytes so the folder can show a real thumbnail.
            if (list != null) {
                for (f in list) {
                    if (f.kind != "image" || _state.value.fileThumbs.containsKey(f.id)) continue
                    if (_state.value.openId != id) break
                    val data = runCatching { PremiumClient.fileData(b, id, f.id).data }.getOrNull()
                    if (!data.isNullOrBlank()) withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(fileThumbs = _state.value.fileThumbs + (f.id to data))
                    }
                }
            }
        }
    }

    /** Pull a stored file back out of the folder and queue it to send again. */
    fun reattachStoredFile(fid: String) {
        val id = _state.value.openId ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.fileData(b, id, fid) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    _state.value = _state.value.copy(
                        pendingAttachments = _state.value.pendingAttachments + it,
                        filesOpen = false, filesMsg = null)
                }.onFailure { _state.value = _state.value.copy(filesMsg = it.message) }
            }
        }
    }

    /** Save a stored file back onto the phone (Downloads). */
    fun downloadStoredFile(fid: String) {
        val id = _state.value.openId ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                val att = PremiumClient.fileData(b, id, fid)
                saveToDownloads(att.name, att.mime, Base64.decode(att.data ?: "", Base64.DEFAULT))
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    filesMsg = if (r.isSuccess) "Saved \"${r.getOrNull()}\" to Downloads."
                               else (r.exceptionOrNull()?.message ?: "Couldn't save file."))
            }
        }
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): String {
        val ctx = getApplication<Application>()
        val safe = name.ifBlank { "waide-file" }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val cv = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            }
            val uri = ctx.contentResolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("could not create file")
            ctx.contentResolver.openOutputStream(uri).use { it?.write(bytes) }
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, safe).writeBytes(bytes)
        }
        return safe
    }

    // ---------------- attachments ----------------
    fun attachImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val b64 = runCatching { imageToB64(uri) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (b64 == null) _state.value = _state.value.copy(error = "Couldn't read that image.")
                else _state.value = _state.value.copy(
                    pendingAttachments = _state.value.pendingAttachments +
                        PremiumAttach("image", "image.jpg", "image/jpeg", b64), error = null)
            }
        }
    }

    fun attachFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatching { fileToAttach(uri) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (res == null) _state.value = _state.value.copy(error = "Couldn't read that file (too big, max ~20MB?).")
                else _state.value = _state.value.copy(
                    pendingAttachments = _state.value.pendingAttachments + res, error = null)
            }
        }
    }

    fun clearAttachment(index: Int) {
        val list = _state.value.pendingAttachments.toMutableList()
        if (index in list.indices) list.removeAt(index)
        _state.value = _state.value.copy(pendingAttachments = list)
    }

    private fun imageToB64(uri: Uri): String {
        val ctx = getApplication<Application>()
        val bmp = ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("decode failed")
        val m = maxOf(bmp.width, bmp.height)
        val scaled = if (m <= 1536) bmp else {
            val s = 1536f / m
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun fileToAttach(uri: Uri): PremiumAttach {
        val ctx = getApplication<Application>()
        val cr = ctx.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        var name = "file"
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cur.getString(idx) ?: "file"
                }
            }
        }
        val bytes = cr.openInputStream(uri).use { it?.readBytes() } ?: throw IllegalStateException("read failed")
        if (bytes.size > 20_000_000) throw IllegalStateException("too big")
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val kind = if (mime.startsWith("image/")) "image" else "file"
        return PremiumAttach(kind, name, mime, b64)
    }

    // ---------------- hide / unhide ----------------
    fun hideConversation(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PremiumClient.hide(b, id) }
            withContext(Dispatchers.Main) { refreshList() }
        }
    }

    fun requestUnhide(id: String) { _state.value = _state.value.copy(unhideTarget = id, unhideError = null) }
    fun cancelUnhide() { _state.value = _state.value.copy(unhideTarget = null, unhideError = null) }

    fun submitUnhide(pin: String) {
        val id = _state.value.unhideTarget ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.unhide(b, id, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    _state.value = _state.value.copy(unhideTarget = null, unhideError = null)
                    refreshList()
                }.onFailure { _state.value = _state.value.copy(unhideError = it.message ?: "wrong pin") }
            }
        }
    }

    fun unhideAll(pin: String, onDone: (String?) -> Unit) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.unhideAll(b, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess { refreshList(); onDone(null) }
                 .onFailure { onDone(it.message ?: "wrong pin") }
            }
        }
    }

    // ---------------- premium agents (walled off: read premium conversations only) ----------------
    fun refreshAgents() {
        val b = base()
        if (b.isBlank()) return
        _agentState.value = _agentState.value.copy(loadingList = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.listAgents(b) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _agentState.value = _agentState.value.copy(agents = it, loadingList = false, error = null) }
                 .onFailure { _agentState.value = _agentState.value.copy(loadingList = false, error = it.message) }
            }
        }
    }

    fun openAgent(id: String) {
        val b = base()
        _agentState.value = _agentState.value.copy(openId = id, messages = emptyList(), streaming = null, busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.getAgent(b, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d ->
                    _agentState.value = _agentState.value.copy(openId = id, openName = d.name, openJob = d.job,
                        openModel = d.model.ifBlank { _state.value.selectedModel }, messages = d.messages, busy = false)
                }.onFailure { _agentState.value = _agentState.value.copy(busy = false, error = it.message) }
            }
        }
    }

    private fun reloadOpenAgent(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.getAgent(b, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d -> _agentState.value = _agentState.value.copy(openName = d.name, openJob = d.job,
                    openModel = d.model.ifBlank { _agentState.value.openModel }, messages = d.messages) }
            }
        }
    }

    fun closeAgent() {
        agentGenJob?.cancel()
        _agentState.value = _agentState.value.copy(openId = null, messages = emptyList(), streaming = null, busy = false)
        refreshAgents()
    }

    fun newAgent(name: String, job: String, model: String) {
        val b = base()
        val m = model.ifBlank { _state.value.selectedModel }
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.createAgent(b, name, job, m) }
            withContext(Dispatchers.Main) {
                r.onSuccess { id ->
                    _agentState.value = _agentState.value.copy(openId = id, openName = name, openJob = job,
                        openModel = m, messages = emptyList(), streaming = null, busy = false, error = null)
                }.onFailure { _agentState.value = _agentState.value.copy(error = it.message) }
            }
        }
    }

    fun editAgent(id: String, name: String, job: String, model: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PremiumClient.editAgent(b, id, name, job, model) }
            withContext(Dispatchers.Main) {
                if (_agentState.value.openId == id)
                    _agentState.value = _agentState.value.copy(openName = name, openJob = job,
                        openModel = model.ifBlank { _agentState.value.openModel })
                refreshAgents()
            }
        }
    }

    fun deleteAgent(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PremiumClient.deleteAgent(b, id) }
            withContext(Dispatchers.Main) {
                if (_agentState.value.openId == id) _agentState.value = _agentState.value.copy(openId = null, messages = emptyList())
                refreshAgents()
            }
        }
    }

    fun deleteAgentMsg(mid: String) {
        val id = _agentState.value.openId ?: return
        val b = base()
        _agentState.value = _agentState.value.copy(messages = _agentState.value.messages.filter { it.id != mid })
        viewModelScope.launch(Dispatchers.IO) { runCatching { PremiumClient.deleteAgentMsg(b, id, mid) } }
    }

    fun setAgentModel(id: String) { _agentState.value = _agentState.value.copy(openModel = id) }

    fun sendToAgent(text: String) {
        val id = _agentState.value.openId ?: return
        val model = _agentState.value.openModel.ifBlank { _state.value.selectedModel }
        val prompt = text.trim()
        if (_agentState.value.busy || prompt.isEmpty()) return
        if (model.isBlank()) { _agentState.value = _agentState.value.copy(error = "Pick a model for this agent first."); return }
        val b = base()
        val localUser = PremiumAgentMsg("local-${System.currentTimeMillis()}", "user", prompt, model)
        _agentState.value = _agentState.value.copy(messages = _agentState.value.messages + localUser,
            streaming = "", busy = true, error = null)
        GenerationService.begin(getApplication())
        agentGenJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var acc = ""
                PremiumClient.agentChatStream(b, id, model, prompt) { piece ->
                    acc += piece
                    _agentState.value = _agentState.value.copy(streaming = acc)
                }
                withContext(Dispatchers.Main) {
                    _agentState.value = _agentState.value.copy(streaming = null, busy = false)
                    reloadOpenAgent(id)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _agentState.value = _agentState.value.copy(busy = false, streaming = null, error = e.message ?: "Server error")
                }
            } finally {
                GenerationService.end(getApplication())
            }
        }
    }

    fun stopAgent() {
        agentGenJob?.cancel()
        _agentState.value = _agentState.value.copy(busy = false, streaming = null)
    }

    fun hideAgent(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { PremiumClient.hideAgent(b, id) }
            withContext(Dispatchers.Main) { refreshAgents() }
        }
    }

    fun requestUnhideAgent(id: String) { _agentState.value = _agentState.value.copy(unhideTarget = id, unhideError = null) }
    fun cancelUnhideAgent() { _agentState.value = _agentState.value.copy(unhideTarget = null, unhideError = null) }

    fun submitUnhideAgent(pin: String) {
        val id = _agentState.value.unhideTarget ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.unhideAgent(b, id, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    _agentState.value = _agentState.value.copy(unhideTarget = null, unhideError = null)
                    refreshAgents()
                }.onFailure { _agentState.value = _agentState.value.copy(unhideError = it.message ?: "wrong pin") }
            }
        }
    }

    fun unhideAllAgents(pin: String, onDone: (String?) -> Unit) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.unhideAllAgents(b, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess { refreshAgents(); onDone(null) }
                 .onFailure { onDone(it.message ?: "wrong pin") }
            }
        }
    }
}
