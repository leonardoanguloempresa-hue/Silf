package com.silf.app.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silf.app.SilfApplication
import com.silf.app.accessibility.SilfAccessibilityService
import com.silf.app.data.preferences.PreferencesManager
import com.silf.app.data.repository.ChatRepository
import com.silf.app.domain.llm.LlamaCppEngine
import com.silf.app.domain.llm.LlmEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class ChatViewModel(
    private val llmEngine: LlmEngine = LlamaCppEngine.getInstance(),
    private val chatRepository: ChatRepository,
    private val screenSnapshotFlow: StateFlow<String> = SilfAccessibilityService.screenSnapshot,
    private val preferencesManager: PreferencesManager? = null,
    private val context: Context = SilfApplication.instance
) : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _draftText = MutableStateFlow("")
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _showErrorToast = MutableStateFlow<String?>(null)
    val showErrorToast: StateFlow<String?> = _showErrorToast.asStateFlow()

    private val _closeUiEvent = MutableSharedFlow<Unit>()
    val closeUiEvent = _closeUiEvent.asSharedFlow()

    val screenSnapshot: StateFlow<String> = screenSnapshotFlow

    val isVisionActive: StateFlow<Boolean> = screenSnapshotFlow
        .map { it.isNotBlank() && SilfAccessibilityService.isEnabled }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = screenSnapshotFlow.value.isNotBlank() && SilfAccessibilityService.isEnabled
        )

    val endpointUrl: StateFlow<String> =
        preferencesManager?.endpointUrl ?: MutableStateFlow(PreferencesManager.DEFAULT_ENDPOINT_URL)
    val modelName: StateFlow<String> =
        preferencesManager?.modelName ?: MutableStateFlow(PreferencesManager.DEFAULT_MODEL_NAME)

    fun updateEndpointUrl(url: String) {
        preferencesManager?.setEndpointUrl(url)
    }

    fun updateModelName(model: String) {
        preferencesManager?.setModelName(model)
    }

    private var generationJob: Job? = null

    init {
        // Carga el historial persistido desde Room al iniciar
        viewModelScope.launch {
            chatRepository.getAllMessages().collect { entities ->
                // Solo sincronizar si no hay generación activa para no pisar el stream
                if (!_isGenerating.value) {
                    _messages.value = entities.map { entity ->
                        ChatMessage(
                            id = entity.id.toString(),
                            text = entity.text,
                            isUser = entity.isUser
                        )
                    }
                }
            }
        }
    }

    fun onDraftChanged(text: String) { _draftText.value = text }

    fun dismissError() { _showErrorToast.value = null }

    fun onSendMessage() {
        val prompt = _draftText.value.trim()
        if (prompt.isEmpty() || _isGenerating.value) return

        if (!llmEngine.isReady()) {
            _showErrorToast.value = "Por favor carga un modelo GGUF en la sección de Modelos"
            return
        }

        _draftText.value = ""
        _isGenerating.value = true

        val userMessageId = UUID.randomUUID().toString()
        val responseId = UUID.randomUUID().toString()

        // Mostrar de inmediato en UI el mensaje del usuario y el contenedor de la respuesta
        _messages.value = _messages.value +
                ChatMessage(id = userMessageId, text = prompt, isUser = true) +
                ChatMessage(id = responseId, text = "", isUser = false)

        generationJob = viewModelScope.launch {
            // Guardar mensaje del usuario en Room
            chatRepository.insertMessage(text = prompt, isUser = true)

            // Refrescar activamente el snapshot de la pantalla desde SilfAccessibilityService (sin valor en caché)
            val screenSnapshot = withContext(Dispatchers.Main) {
                SilfAccessibilityService.getFreshScreenSnapshot()
            }.trim()
            val userMessage = prompt.trim()

            // Inferencia Sin Estado (Zero-Shot):
            // NUNCA se pasa el historial completo de mensajes al motor LLM.
            // Se construye un prompt fresco que contiene ÚNICAMENTE el System Prompt (con el screenSnapshot más reciente)
            // y el Mensaje del Usuario actual. Esto fuerza al LLM a no repetir respuestas anteriores ni alucinar.
            val systemPrompt = "Eres un analizador de datos UI estructurado. Tu ÚNICA función es leer un árbol de texto y traducir la intención del usuario a una etiqueta estricta. NO eres un asistente conversacional. NO interactúes con el usuario. NUNCA te disculpes. Si el usuario pide tocar algo, devuelve [CLICK: ID]. Si pide deslizar, devuelve [SWIPE: UP] o [SWIPE: DOWN].\n\nÁrbol UI:\n$screenSnapshot"
            val strictPrompt = "<|im_start|>system\n$systemPrompt<|im_end|>\n<|im_start|>user\n$userMessage<|im_end|>\n<|im_start|>assistant\n"

            val builder = StringBuilder()
            val stopTokens = listOf("<|im_end|>", "<|endoftext|>")
            val commandRegex = Regex("""\[(CLICK|SWIPE):\s*([a-zA-Z0-9_]+)\]""", RegexOption.IGNORE_CASE)
            val legacyRegex = Regex("""\[(\d+)\]""")
            var actionExecuted = false

            fun scheduleServiceAction(action: String, target: String) {
                val idInt = target.toIntOrNull()
                if (idInt != null) {
                    SilfAccessibilityService.instance?.scheduleAction(action, idInt)
                } else {
                    SilfAccessibilityService.instance?.scheduleAction(action, target)
                }
            }

            try {
                llmEngine.generateResponseStream(strictPrompt).collect { token ->
                    if (stopTokens.any { token.contains(it) }) {
                        return@collect
                    }
                    var cleanToken = token
                    for (stop in stopTokens) {
                        cleanToken = cleanToken.replace(stop, "")
                    }
                    if (cleanToken.isNotEmpty()) {
                        builder.append(cleanToken)
                        val currentText = builder.toString()

                        // Buscar comando con Regex [(CLICK|SWIPE): ID] ignorando texto extra
                        val match = commandRegex.find(currentText) ?: legacyRegex.find(currentText)
                        if (match != null && !actionExecuted) {
                            actionExecuted = true
                            val action = if (match.groupValues.size >= 3) match.groupValues[1] else "CLICK"
                            val target = if (match.groupValues.size >= 3) match.groupValues[2] else match.groupValues[1]

                            // Delegación: programar la acción en segundo plano en SilfAccessibilityService
                            scheduleServiceAction(action, target)

                            // Autodestrucción: INMEDIATAMENTE DESPUÉS enviar evento para que AssistantActivity ejecute finish()
                            _closeUiEvent.emit(Unit)

                            // Limpiar la respuesta y NO añadirla a la UI si fue un comando ejecutado
                            _messages.value = _messages.value.filterNot { it.id == responseId }

                            // Detener la generación del LLM
                            llmEngine.stopGeneration()
                            return@collect
                        }

                        // Visibilidad de fallos / Streaming: Si aún no se ejecutó acción,
                        // DEBEMOS mostrar el texto en la UI del chat para que el usuario vea si la IA alucina o explica
                        if (!actionExecuted) {
                            _messages.value = _messages.value.map {
                                if (it.id == responseId) it.copy(text = currentText) else it
                            }
                        }
                    }
                }

                // Post-procesamiento al finalizar la generación dentro del bloque try
                if (!actionExecuted) {
                    val fullGenerated = builder.toString().trim()
                    val match = commandRegex.find(fullGenerated) ?: legacyRegex.find(fullGenerated)

                    if (match != null) {
                        actionExecuted = true
                        val action = if (match.groupValues.size >= 3) match.groupValues[1] else "CLICK"
                        val target = if (match.groupValues.size >= 3) match.groupValues[2] else match.groupValues[1]

                        // Delegación y Autodestrucción
                        scheduleServiceAction(action, target)
                        _closeUiEvent.emit(Unit)
                        _messages.value = _messages.value.filterNot { it.id == responseId }
                    } else {
                        // Si el texto generado NO hace match con la Regex, DEBES mostrar el texto en la UI del chat. No lo ocultes.
                        if (fullGenerated.isNotEmpty()) {
                            _messages.value = _messages.value.map {
                                if (it.id == responseId) it.copy(text = fullGenerated) else it
                            }
                            chatRepository.insertMessage(text = fullGenerated, isUser = false)
                        } else {
                            val emptyMsg = "[La IA no generó ninguna respuesta]"
                            _messages.value = _messages.value.map {
                                if (it.id == responseId) it.copy(text = emptyMsg) else it
                            }
                            chatRepository.insertMessage(text = emptyMsg, isUser = false)
                        }
                    }
                }
            } catch (c: CancellationException) {
                // Cancelación esperada al detener generación manualmente
            } catch (t: Throwable) {
                _showErrorToast.value = "Error: ${t.message}"
                _messages.value = _messages.value.map {
                    if (it.id == responseId) it.copy(text = "[Error: ${t.message}]") else it
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun stopGeneration() {
        generationJob?.cancel()
        llmEngine.stopGeneration()
        _isGenerating.value = false
    }

    fun clearHistory() {
        stopGeneration()
        _messages.value = emptyList()
        _draftText.value = ""
        viewModelScope.launch {
            chatRepository.clearHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopGeneration()
    }
}
