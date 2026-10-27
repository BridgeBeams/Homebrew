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
    val openModel: String = "",            // the model this conversation uses
    val messages: List<PremiumMsg> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
    // attachments waiting to send
    val pendingAttachments: List<PremiumAttach> = emptyList(),
    // hide / unhide
    val unhideTarget: String? = null,
    val unhideError: String? = null
)

/**
 * Premium AI tab: paid cloud models via OpenRouter, keyed and stored on your PC server.
 * Open to use (no PIN); hiding a conversation needs no PIN, revealing one does.
 */
class PremiumViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)
    private val _state = MutableStateFlow(PremiumState(selectedModel = store.getPremiumModel()))
    val state: StateFlow<PremiumState> = _state.asStateFlow()

    private var genJob: Job? = null

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
        _state.value = _state.value.copy(openId = id, messages = emptyList(), streaming = null, busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.get(b, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d ->
                    _state.value = _state.value.copy(openId = id, openTitle = d.title,
                        openModel = d.model.ifBlank { _state.value.selectedModel }, messages = d.messages, busy = false)
                }.onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
            }
        }
    }

    private fun reloadOpen(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { PremiumClient.get(b, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { d -> _state.value = _state.value.copy(openTitle = d.title, openModel = d.model.ifBlank { _state.value.openModel }, messages = d.messages) }
            }
        }
    }

    fun closeConversation() {
        genJob?.cancel()
        _state.value = _state.value.copy(openId = null, messages = emptyList(), streaming = null, busy = false, pendingAttachments = emptyList())
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
                        messages = emptyList(), streaming = null, busy = false, pendingAttachments = emptyList(), error = null)
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

    fun stopGeneration() {
        genJob?.cancel()
        _state.value = _state.value.copy(busy = false, streaming = null)
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

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
}
