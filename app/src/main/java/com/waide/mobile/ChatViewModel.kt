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
    val text: String,
    val id: String = java.util.UUID.randomUUID().toString(),
    val remembered: Boolean = false
)

data class ChatState(
    val messages: List<Message> = emptyList(),
    val streaming: String? = null,
    val busy: Boolean = false,
    val modelName: String = "",
    val modelLoading: Boolean = false,
    val tokensPerSecond: Float = 0f,
    val contextUsed: Int = 0,
    val error: String? = null,
    val memoryOn: Boolean = false
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

    init {
        // Restore any remembered conversation from a previous session.
        val (on, remembered) = store.loadMemory()
        if (remembered.isNotEmpty() || on) {
            _state.value = _state.value.copy(messages = remembered, memoryOn = on)
        }
    }

    // Persist the remembered slice to disk.
    private fun persistMemory() {
        val remembered = _state.value.messages.filter { it.remembered }
        store.saveMemory(_state.value.memoryOn, remembered)
    }

    // Brain toggle: from here forward, new messages are remembered.
    fun toggleMemory() {
        _state.value = _state.value.copy(memoryOn = !_state.value.memoryOn)
        persistMemory()
    }

    // Long-press action: flip a single message in or out of memory.
    fun setRemembered(id: String, remembered: Boolean) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map {
                if (it.id == id) it.copy(remembered = remembered) else it
            }
        )
        persistMemory()
    }

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
                // Carry remembered messages across the model swap: replay them into
                // the fresh engine so the new model answers without missing a beat.
                val kept = _state.value.messages.filter { it.remembered }
                for (m in kept) {
                    LlamaBridge.nativePrimeMessage(m.role, m.text)
                }
                _settings.value = s.copy(lastModelPath = file.path)
                store.save(_settings.value)
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(
                        modelLoading = false,
                        modelName = file.name,
                        messages = kept,          // drop un-remembered on swap
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

    // "New chat" clears the scratch conversation but keeps whatever the brain is
    // holding: remembered messages are re-primed so they carry into the fresh chat.
    fun newChat() {
        if (_state.value.busy) return
        val kept = _state.value.messages.filter { it.remembered }
        viewModelScope.launch(Dispatchers.Default) {
            LlamaBridge.nativeResetChat()
            val s = _settings.value
            if (s.systemPrompt.isNotBlank()) LlamaBridge.nativeSetSystemPrompt(s.systemPrompt)
            for (m in kept) LlamaBridge.nativePrimeMessage(m.role, m.text)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(
                    messages = kept,
                    streaming = null,
                    contextUsed = 0,
                    tokensPerSecond = 0f
                )
            }
        }
    }

    // Wipe the brain entirely (screen + disk).
    fun clearMemory() {
        if (_state.value.busy) return
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { it.copy(remembered = false) }
        )
        store.saveMemory(_state.value.memoryOn, emptyList())
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.busy || _state.value.modelName.isEmpty()) return

        val remember = _state.value.memoryOn
        _state.value = _state.value.copy(
            messages = _state.value.messages + Message("user", prompt, remembered = remember),
            streaming = "",
            busy = true,
            tokensPerSecond = 0f,
            error = null
        )
        if (remember) persistMemory()

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
                    messages = _state.value.messages + Message("assistant", reply.ifBlank { "(no reply)" }, remembered = remember),
                    streaming = null,
                    busy = false,
                    contextUsed = used
                )
                if (remember) persistMemory()
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
