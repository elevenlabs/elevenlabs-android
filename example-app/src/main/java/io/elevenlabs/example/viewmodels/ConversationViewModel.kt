package io.elevenlabs.example.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.elevenlabs.ConversationClient
import io.elevenlabs.ConversationSession
import io.elevenlabs.example.models.TextChatMessage
import io.elevenlabs.example.models.UiState
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single ViewModel covering both voice and text-only conversations. The same SDK callbacks feed
 * a shared transcript so a UI can render either modality (voice controls, chat bubbles, or both)
 * from one source of truth.
 */
class ConversationViewModel(application: Application) : AndroidViewModel(application) {

    private var currentSession: ConversationSession? = null

    // Whether the active (or most recent) session was started in text-only mode. Used by retry().
    private var lastTextOnly: Boolean = false

    // UI State
    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    // Session Status
    private val _sessionStatus = MutableLiveData<ConversationStatus>(ConversationStatus.DISCONNECTED)
    val sessionStatus: LiveData<ConversationStatus> = _sessionStatus

    // Error handling
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // Audio permissions
    private val _audioPermissionRequired = MutableLiveData<Boolean>(false)
    val audioPermissionRequired: LiveData<Boolean> = _audioPermissionRequired

    private var hasAudioPermission: Boolean = false

    // Mode (SPEAKING | LISTENING) propagated from SDK callback
    private val _mode = MutableLiveData<ConversationMode>()
    val mode: LiveData<ConversationMode> = _mode

    // Can send feedback state
    private val _canSendFeedback = MutableLiveData<Boolean>(false)
    val canSendFeedback: LiveData<Boolean> = _canSendFeedback

    // Mute state exposed to UI
    private val _isMuted = MutableLiveData<Boolean>(false)
    val isMuted: LiveData<Boolean> = _isMuted

    // Chat transcript — populated for both voice (via onUserTranscript / onAgentResponse) and
    // text-only (via sendUserMessage + onAgentResponse) sessions.
    private val _messages = MutableStateFlow<List<TextChatMessage>>(emptyList())
    val messages: StateFlow<List<TextChatMessage>> = _messages.asStateFlow()

    private val _isAgentTyping = MutableStateFlow(false)
    val isAgentTyping: StateFlow<Boolean> = _isAgentTyping.asStateFlow()

    private var nextMessageId: Long = 1L

    fun startConversation(activityContext: Context, textOnly: Boolean = true) {
        if (currentSession != null && _uiState.value != UiState.Idle && _uiState.value !is UiState.Error) {
            Log.d(TAG, "Session already active or connecting.")
            return
        }

        lastTextOnly = textOnly
        resetTranscript()

        _uiState.value = UiState.Connecting
        _sessionStatus.value = ConversationStatus.CONNECTING

        viewModelScope.launch {
            try {
                val config = io.elevenlabs.ConversationConfig(
                    agentId = "agent_6401kpqeaxxpf4asyaggk74qqqmz", // Replace with your agent ID
                    conversationToken = null,
                    userId = "demo-user",
                    textOnly = textOnly,
                    overrides = null,
                    customLlmExtraBody = null,
                    dynamicVariables = null,
                    clientTools = mapOf(
                        "logMessage" to object : io.elevenlabs.ClientTool {
                            override suspend fun execute(parameters: Map<String, Any>): io.elevenlabs.ClientToolResult? {
                                val message = parameters["message"] as? String
                                    ?: return io.elevenlabs.ClientToolResult.failure("Missing 'message' parameter")
                                val level = parameters["level"] as? String ?: "INFO"

                                Log.d("ExampleApp", "[$level] Client Tool Log: $message")
                                return io.elevenlabs.ClientToolResult.success("Message logged successfully")
                            }
                        }
                    ),
                    onConnect = { conversationId ->
                        Log.d(TAG, "Connected id=$conversationId")
                    },
                    onDisconnect = { reason ->
                        Log.d(TAG, "onDisconnect: $reason")
                        _isAgentTyping.value = false
                    },
                    onMessage = { source, message ->
                        // Receive messages from the server. Can be quite noisy hence commented out
                        // Log.d(TAG, "onMessage [$source]: $message")
                    },
                    onModeChange = { mode: ConversationMode ->
                        _mode.postValue(mode)
                    },
                    onStatusChange = { status ->
                        Log.d(TAG, "onStatusChange: $status")
                    },
                    onCanSendFeedbackChange = { canSendFeedback ->
                        _canSendFeedback.postValue(canSendFeedback)
                        Log.d(TAG, "onCanSendFeedbackChange: $canSendFeedback")
                    },
                    onUnhandledClientToolCall = { toolCall ->
                        Log.d(TAG, "onUnhandledClientToolCall: $toolCall")
                    },
                    onVadScore = { score ->
                        // VAD score is used to determine if the user is speaking.
                        // Can be used to trigger UI changes or audio processing decisions.
                        // Log commented out as it's quite noisy
                        // Log.d(TAG, "onVadScore: $score")
                    },
                    onAudioLevelChanged = { level ->
                        // Agent audio level (volume)
                        // Commented out as it's quite noisy
                        // Log.d(TAG, "audioLevel: $level")
                    },
                    onUserTranscript = { transcript ->
                        Log.d(TAG, "onUserTranscript: $transcript")
                        appendUserMessage(transcript)
                    },
                    onAudioAlignment = { alignment ->
                        Log.d(TAG, "onAudioAlignment: $alignment")
                    },
                    onAgentResponse = { response ->
                        Log.d(TAG, "onAgentResponse: $response")
                        appendAgentText(response)
                    },
                    onAgentResponseMetadata = { metadata ->
                        Log.d(TAG, "onAgentResponseMetadata: $metadata")
                    },
                    onAgentResponseCorrection = { originalResponse, correctedResponse ->
                        Log.d(TAG, "onAgentResponseCorrection: original='$originalResponse', corrected='$correctedResponse'")
                    },
                    onAgentToolResponse = { toolName, toolCallId, toolType, isError ->
                        Log.d(TAG, "onAgentToolResponse: tool=$toolName, callId=$toolCallId, type=$toolType, isError=$isError")
                    },
                    onConversationInitiationMetadata = { conversationId, agentOutputFormat, userInputFormat ->
                        Log.d(TAG, "onConversationInitiationMetadata: id=$conversationId, agentOut=$agentOutputFormat, userIn=$userInputFormat")
                    },
                    onInterruption = { eventId ->
                        Log.d(TAG, "onInterruption: eventId=$eventId")
                    },
                    onError = { code, message ->
                        Log.e(TAG, "onError: Server error ($code): ${message ?: "unknown"}")
                        _errorMessage.postValue("Server error ($code): ${message ?: "unknown"}")
                    }
                )

                val session = ConversationClient.startSession(config, activityContext)

                currentSession = session

                // Collect session status flow
                viewModelScope.launch {
                    session.status.collect { status ->
                        _sessionStatus.postValue(status)
                        _uiState.postValue(when (status) {
                            ConversationStatus.CONNECTED -> UiState.Connected
                            ConversationStatus.CONNECTING -> UiState.Connecting
                            ConversationStatus.DISCONNECTED -> UiState.Idle
                            ConversationStatus.DISCONNECTING -> UiState.Disconnecting
                            ConversationStatus.ERROR -> UiState.Error
                        })

                        if (status == ConversationStatus.ERROR) {
                            _errorMessage.postValue("Connection failed. Please try again.")
                        }
                    }
                }

                // Collect mute state flow
                viewModelScope.launch {
                    session.isMuted.collect { muted ->
                        _isMuted.postValue(muted)
                    }
                }

                // Collect audio level flow (commented out as it's quite noisy)
                // viewModelScope.launch {
                //     session.audioLevel.collect { level ->
                //         Log.d(TAG, "audioLevel: $level")
                //     }
                // }

                Log.d(TAG, "Session created and started successfully (textOnly=$textOnly)")

            } catch (e: Exception) {
                Log.e(TAG, "Error starting conversation: ${e.message}", e)
                _errorMessage.postValue("Failed to start conversation: ${e.localizedMessage ?: e.message}")
                _uiState.postValue(UiState.Error)
                _sessionStatus.postValue(ConversationStatus.ERROR)
            }
        }
    }

    fun endConversation() {
        viewModelScope.launch {
            try {
                _uiState.value = UiState.Disconnecting
                _sessionStatus.value = ConversationStatus.DISCONNECTING

                currentSession?.endSession()
                currentSession = null
                _isAgentTyping.value = false

                _uiState.value = UiState.Idle
                _sessionStatus.value = ConversationStatus.DISCONNECTED

                Log.d(TAG, "Session ended successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error ending conversation: ${e.message}", e)
                _errorMessage.postValue("Failed to end conversation: ${e.localizedMessage ?: e.message}")
                _uiState.postValue(UiState.Error)
            }
        }
    }

    /**
     * Tear down the current session and start a fresh one with the same modality. Used by the
     * text chat error state's "Retry" action.
     */
    fun retry(activityContext: Context) {
        viewModelScope.launch {
            currentSession?.endSession()
            currentSession = null
            _errorMessage.postValue(null)
            startConversation(activityContext, lastTextOnly)
        }
    }

    fun onAudioPermissionResult(isGranted: Boolean) {
        hasAudioPermission = isGranted
        _audioPermissionRequired.value = !isGranted

        if (!isGranted) {
            Log.d(TAG, "Audio permission not granted - will use text-only mode")
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun sendFeedback(isPositive: Boolean) {
        currentSession?.sendFeedback(isPositive)
        Log.d(TAG, "Sent ${if (isPositive) "positive" else "negative"} feedback")
    }

    fun sendContextualUpdate(text: String) {
        try {
            currentSession?.sendContextualUpdate(text)
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to send contextual update: ${t.message}")
        }
    }

    fun sendUserMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val session = currentSession
        if (session == null) {
            _errorMessage.postValue("Not connected")
            return
        }
        try {
            session.sendUserMessage(trimmed)
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to send user message: ${t.message}")
            _errorMessage.postValue(t.localizedMessage ?: "Send failed")
            return
        }
        appendUserMessage(trimmed)
        _isAgentTyping.value = true
    }

    fun sendUserActivity() {
        try {
            currentSession?.sendUserActivity()
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to send user activity: ${t.message}")
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            try {
                currentSession?.toggleMute()
            } catch (t: Throwable) {
                Log.d(TAG, "Failed to toggle mute: ${t.message}")
            }
        }
    }

    fun setVolume(volume: Float) {
        try {
            currentSession?.setVolume(volume)
            Log.d(TAG, "Volume set to: $volume")
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to set volume: ${t.message}")
        }
    }

    private fun resetTranscript() {
        _messages.value = emptyList()
        _isAgentTyping.value = false
        nextMessageId = 1L
    }

    private fun appendUserMessage(text: String) {
        if (text.isBlank()) return
        _messages.update { it + TextChatMessage(nextMessageId++, text, isFromUser = true) }
    }

    /**
     * Append agent text to the latest agent bubble (streaming deltas) or start a new bubble if the
     * previous message was from the user.
     */
    private fun appendAgentText(text: String) {
        if (text.isEmpty()) return
        _messages.update { current ->
            val msgs = current.toMutableList()
            val last = msgs.lastOrNull()
            if (last != null && !last.isFromUser) {
                msgs[msgs.lastIndex] = last.copy(content = last.content + text)
            } else {
                msgs += TextChatMessage(nextMessageId++, text, isFromUser = false)
            }
            msgs
        }
        _isAgentTyping.value = false
    }

    override fun onCleared() {
        super.onCleared()
        endConversation()
    }

    companion object {
        private const val TAG = "ConversationViewModel"
    }
}
