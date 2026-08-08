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

data class Message(
    val role: String,      // "user" or "assistant"
    val text: String
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

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ModelStore(app)

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<WaideSettings> = _settings.asStateFlow()

    private val _models = MutableStateFlow<List<GgufFile>>(emptyList())
    val models: StateFlow<List<GgufFile>> = _models.asStateFlow()

    private var genJob: Job? = null

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) {
            _models.value = ModelStore.scan(getApplication())
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

        genJob = viewModelScope.launch(Dispatchers.Default) {
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
