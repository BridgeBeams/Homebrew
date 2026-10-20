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

data class ServerState(
    val messages: List<Message> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val models: List<String> = emptyList(),
    val selectedModel: String = "",
    val loadingModels: Boolean = false,
    val connected: Boolean = false,
    val error: String? = null
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

    private var genJob: Job? = null

    private fun serverUrl(): String = store.load().serverUrl

    fun refreshModels() {
        _state.value = _state.value.copy(loadingModels = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { ServerClient.listModels(serverUrl()) }
            withContext(Dispatchers.Main) {
                result.onSuccess { list ->
                    val pick = when {
                        _state.value.selectedModel.isNotBlank() && list.contains(_state.value.selectedModel) -> _state.value.selectedModel
                        list.isNotEmpty() -> list.first()
                        else -> ""
                    }
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
        _state.value = _state.value.copy(selectedModel = name)
    }

    fun clearChat() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(messages = emptyList(), streaming = null, error = null)
    }

    fun dismissError() { _state.value = _state.value.copy(error = null) }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy) return
        val model = _state.value.selectedModel
        if (model.isBlank()) {
            _state.value = _state.value.copy(error = "Pick a model first (tap Refresh if the list is empty).")
            return
        }
        val outgoing = _state.value.messages + Message("user", prompt)
        _state.value = _state.value.copy(messages = outgoing, streaming = "", busy = true, error = null)

        val numCtx = store.load().contextSize
        GenerationService.begin(getApplication())
        genJob = viewModelScope.launch(Dispatchers.IO) {
          try {
            var acc = ""
            val reply = ServerClient.chatStream(serverUrl(), model, outgoing, numCtx) { piece ->
                acc += piece
                _state.value = _state.value.copy(streaming = acc)
            }
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    messages = _state.value.messages + Message("assistant", reply.ifBlank { "(no reply)" }),
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

    fun stopGeneration() {
        genJob?.cancel()
        _state.value = _state.value.copy(busy = false, streaming = null)
    }
}
