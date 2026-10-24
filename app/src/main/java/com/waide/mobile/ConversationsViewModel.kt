package com.waide.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var token: String = ""
    private var genJob: Job? = null

    private fun base(): String {
        val servers = store.loadServers()
        val active = store.getActiveServerId().ifBlank { servers.firstOrNull()?.id ?: "" }
        val url = servers.firstOrNull { it.id == active }?.url ?: ""
        return ConversationsClient.baseFrom(url)
    }

    fun lock() {
        genJob?.cancel()
        token = ""
        _state.value = ConvState()   // back to locked, everything cleared
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
                    _state.value = _state.value.copy(locked = false, authBusy = false, authError = null, loginInfo = info)
                    refreshList()
                    refreshModels()
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

    fun refreshModels() {
        if (token.isBlank()) return
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.models(b, token) }
            withContext(Dispatchers.Main) {
                r.onSuccess { list ->
                    val saved = store.getConvModel()
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

    fun selectModel(m: String) {
        store.setConvModel(m)
        _state.value = _state.value.copy(selectedModel = m)
    }

    fun openConversation(id: String) {
        val b = base()
        _state.value = _state.value.copy(openId = id, messages = emptyList(), streaming = null, busy = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.get(b, token, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { (title, msgs) ->
                    _state.value = _state.value.copy(openId = id, openTitle = title, messages = msgs, busy = false)
                }.onFailure { _state.value = _state.value.copy(busy = false, error = it.message) }
            }
        }
    }

    private fun reloadOpen(id: String) {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.get(b, token, id) }
            withContext(Dispatchers.Main) {
                r.onSuccess { (title, msgs) -> _state.value = _state.value.copy(openTitle = title, messages = msgs) }
            }
        }
    }

    fun closeConversation() {
        genJob?.cancel()
        _state.value = _state.value.copy(openId = null, messages = emptyList(), streaming = null, busy = false)
        refreshList()
    }

    fun newConversation() {
        val b = base()
        viewModelScope.launch(Dispatchers.IO) {
            val r = runCatching { ConversationsClient.create(b, token, null) }
            withContext(Dispatchers.Main) {
                r.onSuccess { id ->
                    _state.value = _state.value.copy(openId = id, openTitle = "New conversation",
                        messages = emptyList(), streaming = null, busy = false, error = null)
                }.onFailure { _state.value = _state.value.copy(error = it.message) }
            }
        }
    }

    fun send(text: String) {
        val id = _state.value.openId ?: return
        val model = _state.value.selectedModel
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy) return
        if (model.isBlank()) { _state.value = _state.value.copy(error = "Pick a model first.") ; return }
        val b = base()
        val localUser = ConvMsg("local-${System.currentTimeMillis()}", "user", prompt)
        _state.value = _state.value.copy(messages = _state.value.messages + localUser, streaming = "", busy = true, error = null)
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var acc = ""
                ConversationsClient.chatStream(b, token, id, model, prompt, 8192) { piece ->
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
}
