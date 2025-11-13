package io.elevenlabs

import android.content.Context
import android.util.Log
import io.elevenlabs.audio.AudioManager
import io.elevenlabs.audio.LiveKitAudioManager
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import io.elevenlabs.models.toConversationStatus
import io.elevenlabs.network.BaseConnection
import io.elevenlabs.network.ConversationEventParser
import io.livekit.android.room.Room
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
        },
        onUserTranscript = { transcript ->
            try { config.onUserTranscript?.invoke(transcript) } catch (_: Throwable) {}
        },
        onAgentResponse = { response ->
            try { config.onAgentResponse?.invoke(response) } catch (_: Throwable) {}
        },
        onAgentResponseCorrection = { original, corrected ->
            try { config.onAgentResponseCorrection?.invoke(original, corrected) } catch (_: Throwable) {}
        },
        onAgentToolResponse = { toolName, toolCallId, toolType, isError ->
            try { config.onAgentToolResponse?.invoke(toolName, toolCallId, toolType, isError) } catch (_: Throwable) {}
        },
        onConversationInitiationMetadata = { conversationId, agentOutputFormat, userInputFormat ->
            try { config.onConversationInitiationMetadata?.invoke(conversationId, agentOutputFormat, userInputFormat) } catch (_: Throwable) {}
        },
        onInterruption = { eventId ->
            try { config.onInterruption?.invoke(eventId) } catch (_: Throwable) {}
        },
        onEndCall = {
            endSession()
        }
    )

    // StateFlow backing fields
    private val _status = MutableStateFlow<ConversationStatus>(ConversationStatus.DISCONNECTED)

    // Public observable properties exposed as StateFlow
    override val status: StateFlow<ConversationStatus> = _status
    override val mode: StateFlow<ConversationMode> = eventHandler.conversationMode
    override val isMuted: StateFlow<Boolean> =
        if (audioManager is LiveKitAudioManager) {
            audioManager.muteState
        } else {
            MutableStateFlow(false)
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
                    originalOnConnect?.runCatching { invoke(id) }
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

            // Clean up any resources that were initialized before the failure
            audioManager.runCatching { stopRecording() }.logException("stopRecording")
            audioManager.runCatching { stopPlayback() }.logException("stopPlayback")

            connection.runCatching { disconnect() }.logException("disconnect")

            eventHandler.runCatching { cleanup() }.logException("eventHandler.cleanup")
            audioManager.runCatching { cleanup() }.logException("audioManager.cleanup")

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

    override fun setVolume(volume: Float) {
        audioManager.setVolume(volume)
    }

    override fun getVolume(): Float {
        return audioManager.getVolume()
    }

    override fun registerTool(name: String, tool: ClientTool) {
        toolRegistry.registerTool(name, tool)
    }

    override fun unregisterTool(name: String) {
        toolRegistry.unregisterTool(name)
    }

    private fun Result<*>.logException(functionName: String) {
        onFailure {
            Log.w("ConversationSession", "Failed to run $functionName", it)
        }
    }
}