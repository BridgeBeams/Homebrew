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

data class ConvState(
    val locked: Boolean = true,
    val authBusy: Boolean = false,
    val authError: String? = null,
    val loginInfo: LoginInfo? = null,
    // list
    val conversations: List<ConvSummary> = emptyList(),
    val loadingList: Boolean = false,
    // open conversation
    val openId: String? = null,
    val openTitle: String = "",
    val messages: List<ConvMsg> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    // models
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val error: String? = null,
    // hide / unhide
    val unhideTarget: String? = null,   // a conversation awaiting the PIN to reveal
    val unhideError: String? = null,
    // keyword search
    val query: String = "",
    val searching: Boolean = false,
    val results: List<ConvHit> = emptyList(),
    // image / vision (the image reader is chosen in Settings, shared with the Server tab)
    val pendingImageB64: String? = null,
    val visionModel: String = "",
    // conversation files folder
    val files: List<ConvFile> = emptyList(),
    val filesOpen: Boolean = false,
    val loadingFiles: Boolean = false,
    val filesMsg: String? = null,
    val fileThumbs: Map<String, String> = emptyMap(),   // fileId -> image base64 (for thumbnails)
    // compaction
    val compactedCount: Int = 0,
    val compactedAt: Double = 0.0,
    val compactingNotice: Boolean = false
)

// The Agents side of the private area. Shares the same PIN session as conversations.
data class AgentState(
    val agents: List<AgentSummary> = emptyList(),
    val loadingList: Boolean = false,
    val openId: String? = null,
    val openName: String = "",
    val openJob: String = "",
    val openReadPremium: Boolean = false,
    val messages: List<AgentMsg> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val unhideTarget: String? = null,   // an agent awaiting the PIN to reveal
    val unhideError: String? = null
)

/**
 * The private, PIN-locked Conversations tab. Nothing is cached on the phone: the
 * token lives only in memory, and lock() wipes everything, so leaving the tab or
 * backgrounding the app forces the PIN again.
 */
class ConversationsViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val _state = MutableStateFlow(ConvState())
    val state: StateFlow<ConvState> = _state.asStateFlow()

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private var token: String = ""
    private var genJob: Job? = null
    private var agentGenJob: Job? = null

    private fun base(): String {
        val servers = store.loadServers()
        val active = store.getActiveServerId().ifBlank { servers.firstOrNull()?.id ?: "" }
        val url = servers.firstOrNull { it.id == active }?.url ?: ""
        return ConversationsClient.baseFrom(url)
    }

    fun lock() {
        genJob?.cancel()
        agentGenJob?.cancel()
        token = ""
        _state.value = ConvState()   // back to locked, everything cleared
        _agentState.value = AgentState()
    }

    fun unlock(pin: String) {
        if (_state.value.authBusy) return
        val b = base()
        if (b.isBlank()) {
            _state.value = _state.value.copy(authError = "No machine set. Add one in Settings.")
            return
        }
        _state.value = _state.value.copy(authBusy = true, authError = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.auth(b, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess { info ->
                    token = info.token
                    _state.value = _state.value.copy(locked = false, authBusy = false, authError = null,
                        loginInfo = info, visionModel = store.getVisionModel(),
                        selectedModel = store.getServerModel())
                    refreshList()
                    refreshModels()
                    refreshAgents()
                }.onFailure {
                    _state.value = _state.value.copy(authBusy = false, authError = it.message ?: "Login failed")
                }
            }
        }
    }

    fun changePin(oldPin: String, newPin: String, onDone: (String?) -> Unit) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.changePin(b, token, oldPin, newPin) }
            withContext(Dispatchers.Main) { onDone(r.exceptionOrNull()?.message) }
        }
    }

    fun refreshList() {
        if (token.isBlank()) return
        val b = base()
        _state.value = _state.value.copy(loadingList = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.list(b, token) }
            withContext(Dispatchers.Main) {
                r.onSuccess { _state.value = _state.value.copy(conversations = it, loadingList = false, error = null) }
                 .onFailure { _state.value = _state.value.copy(loadingList = false, error = it.message) }
            }
        }
    }

    private var searchJob: Job? = null

    /** Called as the user types. Debounced; blank query clears results. */
    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.value = _state.value.copy(results = emptyList(), searching = false)
            return
        }
        if (token.isBlank()) return
        val b = base()
        _state.value = _state.value.copy(searching = true)
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(250)   // wait for typing to settle
            val r = runCatching { ConversationsClient.search(b, token, q.trim()) }
            withContext(Dispatchers.Main) {
                // ignore if the query changed while we were fetching
                if (_state.value.query == q) {
                    r.onSuccess { _state.value = _state.value.copy(results = it, searching = false) }
                     .onFailure { _state.value = _state.value.copy(searching = false, error = it.message) }
                }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.value = _state.value.copy(query = "", results = emptyList(), searching = false)
    }

    fun refreshModels() {
        if (token.isBlank()) return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.models(b, token) }
            withContext(Dispatchers.Main) {
                r.onSuccess { list ->
                    // The answering model is chosen in Settings and shared with the Server tab.
                    val saved = store.getServerModel()
                    val pick = when {
                        saved.isNotBlank() && list.contains(saved) -> saved
                        _state.value.selectedModel.isNotBlank() && list.contains(_state.value.selectedModel) -> _state.value.selectedModel
                        list.isNotEmpty() -> list.first()
                        else -> ""
                    }
                    _state.value = _state.value.copy(models = list, selectedModel = pick)
                }
            }
        }
    }

    fun openConversation(id: String) {
        val b = base()
        _state.value = _state.value.copy(openId = id, messages = emptyList(), streaming = null, busy = true, error = null,
            files = emptyList(), filesOpen = false, fileThumbs = emptyMap(), compactingNotice = false)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.get(b, token, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d ->
                    _state.value = _state.value.copy(openId = id, openTitle = d.title, messages = d.messages, busy = false,
                        compactedCount = d.compactedCount, compactedAt = d.compactedAt, compactingNotice = false)
                }.onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
            }
        }
    }

    private fun reloadOpen(id: String) {
        val b = base()
        val prevCompactedAt = _state.value.compactedAt
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.get(b, token, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d -> _state.value = _state.value.copy(openTitle = d.title, messages = d.messages,
                    compactedCount = d.compactedCount, compactedAt = d.compactedAt,
                    compactingNotice = d.compactedAt > prevCompactedAt) }
            }
        }
    }

    fun dismissCompactingNotice() { _state.value = _state.value.copy(compactingNotice = false) }

    // ---------------- conversation files folder ----------------
    fun openFiles() { _state.value = _state.value.copy(filesOpen = true, filesMsg = null); refreshFiles() }
    fun closeFiles() { _state.value = _state.value.copy(filesOpen = false) }
    fun dismissFilesMsg() { _state.value = _state.value.copy(filesMsg = null) }

    fun refreshFiles() {
        val id = _state.value.openId ?: return
        val b = base()
        _state.value = _state.value.copy(loadingFiles = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.files(b, token, id) }
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
                    val data = runCatching { ConversationsClient.fileData(b, token, id, f.id).data }.getOrNull()
                    if (!data.isNullOrBlank()) withContext(Dispatchers.Main) {
                        _state.value = _state.value.copy(fileThumbs = _state.value.fileThumbs + (f.id to data))
                    }
                }
            }
        }
    }

    /** Re-attach a stored image so it is sent again (Saved Chats only stores images). */
    fun reattachStoredFile(fid: String) {
        val id = _state.value.openId ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.fileData(b, token, id, fid) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    if (it.kind == "image" && it.data.isNotBlank())
                        _state.value = _state.value.copy(pendingImageB64 = it.data, filesOpen = false, filesMsg = null)
                    else _state.value = _state.value.copy(filesMsg = "Only images can be re-attached here.")
                }.onFailure { _state.value = _state.value.copy(filesMsg = it.message) }
            }
        }
    }

    fun downloadStoredFile(fid: String) {
        val id = _state.value.openId ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching {
                val f = ConversationsClient.fileData(b, token, id, fid)
                saveToDownloads(f.name, f.mime, android.util.Base64.decode(f.data, android.util.Base64.DEFAULT))
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
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                ?: throw IllegalStateException("could not create file")
            ctx.contentResolver.openOutputStream(uri).use { it?.write(bytes) }
        } else {
            val dir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs(); java.io.File(dir, safe).writeBytes(bytes)
        }
        return safe
    }

    fun closeConversation() {
        genJob?.cancel()
        _state.value = _state.value.copy(openId = null, messages = emptyList(), streaming = null, busy = false,
            files = emptyList(), filesOpen = false, filesMsg = null, fileThumbs = emptyMap(),
            compactedCount = 0, compactedAt = 0.0, compactingNotice = false)
        refreshList()
    }

    fun newConversation() {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.create(b, token, null) }
            withContext(Dispatchers.Main) {
                r.onSuccess { id ->
                    _state.value = _state.value.copy(openId = id, openTitle = "New conversation",
                        messages = emptyList(), streaming = null, busy = false, error = null,
                        files = emptyList(), filesOpen = false, fileThumbs = emptyMap(),
                        compactedCount = 0, compactedAt = 0.0, compactingNotice = false)
                }.onFailure { _state.value = _state.value.copy(error = it.message) }
            }
        }
    }

    fun send(text: String) {
        val id = _state.value.openId ?: return
        val model = _state.value.selectedModel
        val img = _state.value.pendingImageB64
        val prompt = text.trim()
        if (_state.value.busy) return
        if (prompt.isEmpty() && img == null) return
        if (model.isBlank()) { _state.value = _state.value.copy(error = "Pick a model first.") ; return }
        if (img != null && _state.value.visionModel.isBlank()) {
            _state.value = _state.value.copy(
                error = "No image reader set. Choose one in Settings > Server models.")
            return
        }
        val vision = _state.value.visionModel
        val b = base()
        val localUser = ConvMsg("local-${System.currentTimeMillis()}", "user",
            prompt.ifBlank { "What's in this image?" }, imageB64 = img, hasVision = img != null)
        _state.value = _state.value.copy(messages = _state.value.messages + localUser,
            pendingImageB64 = null, streaming = "", busy = true, error = null)
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var acc = ""
                ConversationsClient.chatStream(b, token, id, model, prompt, 8192, img, vision) { piece ->
                    acc += piece
                    _state.value = _state.value.copy(streaming = acc)
                }
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(streaming = null, busy = false)
                    reloadOpen(id)   // pull canonical message ids from the server
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

    fun attachImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val b64 = runCatching { uriToBase64(uri) }.getOrNull()
            withContext(Dispatchers.Main) {
                _state.value = if (b64 == null)
                    _state.value.copy(error = "Couldn't read that image.")
                else _state.value.copy(pendingImageB64 = b64, error = null)
            }
        }
    }

    fun clearImage() { _state.value = _state.value.copy(pendingImageB64 = null) }

    private fun uriToBase64(uri: Uri): String {
        val ctx = getApplication<Application>()
        val bmp = ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalStateException("decode failed")
        val m = maxOf(bmp.width, bmp.height)
        val scaled = if (m <= 1024) bmp else {
            val s = 1024f / m
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    fun renameConversation(id: String, title: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ConversationsClient.rename(b, token, id, title) }
            withContext(Dispatchers.Main) {
                if (_state.value.openId == id) _state.value = _state.value.copy(openTitle = title)
                refreshList()
            }
        }
    }

    fun deleteConversation(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ConversationsClient.deleteConv(b, token, id) }
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ConversationsClient.deleteMsg(b, token, id, mid) }
        }
    }

    fun stopGeneration() {
        genJob?.cancel()
        _state.value = _state.value.copy(busy = false, streaming = null)
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    // ---------------- hide / unhide ----------------
    fun hideConversation(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ConversationsClient.hide(b, token, id) }
            withContext(Dispatchers.Main) { refreshList() }
        }
    }

    fun requestUnhide(id: String) {
        _state.value = _state.value.copy(unhideTarget = id, unhideError = null)
    }

    fun cancelUnhide() {
        _state.value = _state.value.copy(unhideTarget = null, unhideError = null)
    }

    fun submitUnhide(pin: String) {
        val id = _state.value.unhideTarget ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.unhide(b, token, id, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    _state.value = _state.value.copy(unhideTarget = null, unhideError = null)
                    refreshList()
                }.onFailure {
                    _state.value = _state.value.copy(unhideError = it.message ?: "wrong pin")
                }
            }
        }
    }

    // Callable from Settings even while locked (the service checks the PIN itself).
    fun unhideAll(pin: String, onDone: (String?) -> Unit) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.unhideAll(b, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    if (!_state.value.locked) refreshList()
                    onDone(null)
                }.onFailure { onDone(it.message ?: "wrong pin") }
            }
        }
    }

    // ================================================================= AGENTS
    // Same PIN session (token) as conversations. Agents read across your saved
    // conversations (hidden excluded, server-side) and keep their own memory.

    fun refreshAgents() {
        if (token.isBlank()) return
        val b = base()
        _agentState.value = _agentState.value.copy(loadingList = true)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { AgentsClient.list(b, token) }
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
            val r = runCatching { AgentsClient.get(b, token, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d ->
                    _agentState.value = _agentState.value.copy(
                        openId = id, openName = d.name, openJob = d.job, openReadPremium = d.readPremium,
                        messages = d.messages, busy = false)
                }.onFailure { _agentState.value = _agentState.value.copy(busy = false, error = it.message) }
            }
        }
    }

    private fun reloadOpenAgent(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { AgentsClient.get(b, token, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d -> _agentState.value = _agentState.value.copy(
                    openName = d.name, openJob = d.job, openReadPremium = d.readPremium, messages = d.messages) }
            }
        }
    }

    fun closeAgent() {
        agentGenJob?.cancel()
        _agentState.value = _agentState.value.copy(openId = null, messages = emptyList(), streaming = null, busy = false)
        refreshAgents()
    }

    fun createAgent(name: String, job: String, readPremium: Boolean) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { AgentsClient.create(b, token, name, job, readPremium) }
            withContext(Dispatchers.Main) {
                r.onSuccess { id ->
                    _agentState.value = _agentState.value.copy(
                        openId = id, openName = name, openJob = job, openReadPremium = readPremium,
                        messages = emptyList(), streaming = null, busy = false, error = null)
                }.onFailure { _agentState.value = _agentState.value.copy(error = it.message) }
            }
        }
    }

    fun editAgent(id: String, name: String, job: String, readPremium: Boolean) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AgentsClient.edit(b, token, id, name, job, readPremium) }
            withContext(Dispatchers.Main) {
                if (_agentState.value.openId == id)
                    _agentState.value = _agentState.value.copy(openName = name, openJob = job, openReadPremium = readPremium)
                refreshAgents()
            }
        }
    }

    fun deleteAgent(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AgentsClient.deleteAgent(b, token, id) }
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
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AgentsClient.deleteMsg(b, token, id, mid) }
        }
    }

    fun sendToAgent(text: String) {
        val id = _agentState.value.openId ?: return
        val model = store.getServerModel()
        val prompt = text.trim()
        if (_agentState.value.busy || prompt.isEmpty()) return
        if (model.isBlank()) {
            _agentState.value = _agentState.value.copy(error = "Pick an answering model in Settings first.")
            return
        }
        val b = base()
        val localUser = AgentMsg("local-${System.currentTimeMillis()}", "user", prompt)
        _agentState.value = _agentState.value.copy(
            messages = _agentState.value.messages + localUser, streaming = "", busy = true, error = null)
        GenerationService.begin(getApplication())
        agentGenJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var acc = ""
                AgentsClient.chatStream(b, token, id, model, prompt, 8192) { piece ->
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
            runCatching { AgentsClient.hide(b, token, id) }
            withContext(Dispatchers.Main) { refreshAgents() }
        }
    }

    fun requestUnhideAgent(id: String) { _agentState.value = _agentState.value.copy(unhideTarget = id, unhideError = null) }
    fun cancelUnhideAgent() { _agentState.value = _agentState.value.copy(unhideTarget = null, unhideError = null) }

    fun submitUnhideAgent(pin: String) {
        val id = _agentState.value.unhideTarget ?: return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { AgentsClient.unhide(b, token, id, pin) }
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
            val r = runCatching { AgentsClient.unhideAll(b, pin) }
            withContext(Dispatchers.Main) {
                r.onSuccess {
                    if (!_state.value.locked) refreshAgents()
                    onDone(null)
                }.onFailure { onDone(it.message ?: "wrong pin") }
            }
        }
    }
}
