package io.elevenlabs

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import io.elevenlabs.audio.AudioManager
import io.elevenlabs.audio.LiveKitAudioManager
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import io.elevenlabs.models.toConversationStatus
import io.elevenlabs.network.ConnectionState
import io.elevenlabs.network.BaseConnection
import io.elevenlabs.network.ConversationEventParser
import io.elevenlabs.network.WebRTCConnection
import io.livekit.android.room.Room
import kotlinx.coroutines.*

/**
 * Complete implementation of ConversationSession
 *
 * This implementation integrates all components: network layer, audio management,
 * event handling, and client tools to provide a full conversation experience.
 */
internal class ConversationSessionImpl(
    private val context: Context,
    private val config: ConversationConfig,
    private val room: Room,
    private val connection: BaseConnection,
    private val audioManager: AudioManager,
    private val toolRegistry: ClientToolRegistry
) : ConversationSession {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var conversationId: String? = null

    // Event handler for processing conversation events
    private val eventHandler = ConversationEventHandler(
        audioManager = audioManager,
        toolRegistry = toolRegistry,
        messageCallback = { event ->
            // Send outgoing events through the connection
            connection.sendMessage(event)
        },
        onCanSendFeedbackChange = { canSend ->
            try { config.onCanSendFeedbackChange?.invoke(canSend) } catch (_: Throwable) {}
        },
        onUnhandledClientToolCall = { call ->
            try { config.onUnhandledClientToolCall?.invoke(call) } catch (_: Throwable) {}
        },
        onVadScore = { score ->
            try { config.onVadScore?.invoke(score) } catch (_: Throwable) {}
        }
    )

    // LiveData backing fields
    private val _status = MutableLiveData<ConversationStatus>(ConversationStatus.DISCONNECTED)

    // Public observable properties using StateFlow -> LiveData conversion
    override val status: LiveData<ConversationStatus> = _status
    override val mode: LiveData<ConversationMode> = eventHandler.conversationMode.asLiveData()
    override val isMuted: LiveData<Boolean> =
        if (audioManager is LiveKitAudioManager) {
            audioManager.muteState.asLiveData()
        } else {
            MutableLiveData(false)
        }

        override suspend fun start() {
        try {
            _status.value = ConversationStatus.CONNECTING

            // Set up connection event listener
            connection.setOnMessageListener { messageJson ->
                scope.launch {
                    val event = ConversationEventParser.parseIncomingEvent(messageJson)
                    event?.let { eventHandler.handleIncomingEvent(it) }
                }
            }

            // Set up connection state listener
            connection.setOnConnectionStateListener { connectionState ->
                _status.value = connectionState.toConversationStatus()
            }

            // Start the connection
            val serverUrl = config.websocketUrl
            val token = config.conversationToken ?: ""
            Log.d("ConversationSession", "Starting connection to $serverUrl")
            // Wrap onConnect to capture conversationId while preserving user's callback
            val originalOnConnect = config.onConnect
            val wrappedConfig = config.copy(
                onConnect = { id ->
                    conversationId = id
                    try { originalOnConnect?.invoke(id) } catch (_: Throwable) {}
                }
            )

            connection.connect(token, serverUrl, wrappedConfig)

            // Ensure audio starts only after room is connected (addresses LK permission ordering)
            if (!config.textOnly) {
                if (audioManager.hasAudioPermission()) {
                    // small delay to ensure LK internal state is ready
                    // delay(150)
                    audioManager.startRecording()
                    audioManager.startPlayback()
                } else {
                    Log.d("ConversationSession", "Audio permission not granted - text-only mode")
                }
            }
        } catch (e: Exception) {
            _status.value = ConversationStatus.ERROR
            throw RuntimeException("Failed to start conversation session", e)
        }
    }

    override suspend fun endSession() {
        try {
            _status.value = ConversationStatus.DISCONNECTING

            // Stop audio
            audioManager.stopRecording()
            audioManager.stopPlayback()

            // Disconnect from network
            connection.disconnect()

            // Clean up resources
            eventHandler.cleanup()
            audioManager.cleanup()

            _status.value = ConversationStatus.DISCONNECTED
            conversationId = null

        } catch (e: Exception) {
            _status.value = ConversationStatus.ERROR
            Log.d("ConversationSession", "Error ending conversation session: ${e.message}")
        } finally {
            scope.cancel()
        }
    }

    override fun sendUserMessage(message: String) {
        eventHandler.sendUserMessage(message)
    }

    override fun sendFeedback(isPositive: Boolean) {
        eventHandler.sendFeedback(isPositive)
    }

    override fun sendContextualUpdate(update: String) {
        eventHandler.sendContextualUpdate(update)
    }

    override fun sendUserActivity() {
        eventHandler.sendUserActivity()
    }

    override fun getId(): String? = conversationId

    override suspend fun toggleMute() {
        val currentMuted = audioManager.isMuted()
        audioManager.setMicMuted(!currentMuted)
    }

    override suspend fun setMicMuted(muted: Boolean) {
        audioManager.setMicMuted(muted)
    }

    override fun registerTool(name: String, tool: ClientTool) {
        toolRegistry.registerTool(name, tool)
    }

    override fun unregisterTool(name: String) {
        toolRegistry.unregisterTool(name)
    }

}