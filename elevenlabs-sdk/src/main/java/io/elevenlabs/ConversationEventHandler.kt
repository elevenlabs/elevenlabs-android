package io.elevenlabs

import android.util.Log
import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.*
import io.elevenlabs.network.OutgoingEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Event processing pipeline for real-time conversation
 *
 * This class handles type-safe event routing using sealed classes, async event processing
 * with coroutines, state synchronization with UI layer, and error handling with recovery mechanisms.
 */
class ConversationEventHandler(
    private val audioManager: AudioManager,
    private val toolRegistry: ClientToolRegistry,
    private val messageCallback: (OutgoingEvent) -> Unit,
    private val onCanSendFeedbackChange: ((Boolean) -> Unit)? = null,
    private val onUnhandledClientToolCall: ((ConversationEvent.ClientToolCall) -> Unit)? = null,
    private val onVadScore: ((Float) -> Unit)? = null
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State management
    private val _conversationMode = MutableStateFlow(ConversationMode.LISTENING)
    val conversationMode: StateFlow<ConversationMode> = _conversationMode

    // Keep track of the last agent event ID for feedback
    private var _lastAgentEventId: Int? = null

    // Keep track of the last event ID we sent feedback for to prevent duplicates
    private var _lastFeedbackSentForEventIdInt: Int? = null

    /**
     * Handle incoming conversation events
     *
     * @param event The conversation event to process
     */
    suspend fun handleIncomingEvent(event: ConversationEvent) {
        try {
            when (event) {
                is ConversationEvent.AgentResponse -> handleAgentResponse(event)
                is ConversationEvent.UserTranscript -> handleUserTranscript(event)
                is ConversationEvent.ClientToolCall -> handleClientToolCall(event)
                is ConversationEvent.VadScore -> handleVadScore(event)
                is ConversationEvent.Ping -> handlePing(event)
                is ConversationEvent.AgentResponseCorrection -> handleAgentResponseCorrection(event)
                is ConversationEvent.AgentToolResponse -> handleAgentToolResponse(event)
                is ConversationEvent.Audio -> handleAudio(event)
                is ConversationEvent.ConversationInitiationMetadata -> handleConversationInitiationMetadata(event)
                is ConversationEvent.Interruption -> handleInterruption(event)

            }
        } catch (e: Exception) {
            Log.d("ConvEventHandler", "Error handling conversation event: ${e.message}")
            // Continue processing other events even if one fails
        }
    }

    /**
     * Handle agent response events
     */
    private suspend fun handleAgentResponse(event: ConversationEvent.AgentResponse) {
        // Update conversation mode to speaking
        _conversationMode.value = ConversationMode.SPEAKING

        // Enable feedback on agent reply (event id is handled elsewhere)
        onCanSendFeedbackChange?.invoke(true)

        // If this is a voice conversation, ensure audio playback is active
        if (!audioManager.isPlaying()) {
            try {
                audioManager.startPlayback()
            } catch (e: Exception) {
            Log.d("ConvEventHandler", "Failed to start audio playback: ${e.message}")
            }
        }

        Log.d("ConvEventHandler", "Agent response: ${event.agentResponse}")
    }

    /**
     * Handle user transcript events
     */
    private suspend fun handleUserTranscript(event: ConversationEvent.UserTranscript) {
        Log.d("ConvEventHandler", "User transcript: ${event.userTranscript}")
    }

    private fun handleAgentResponseCorrection(event: ConversationEvent.AgentResponseCorrection) {
        Log.d("ConvEventHandler", "Agent response correction: original='${event.originalAgentResponse}' corrected='${event.correctedAgentResponse}'")
    }

    private fun handleAgentToolResponse(event: ConversationEvent.AgentToolResponse) {
        Log.d("ConvEventHandler", "Agent tool response: name=${event.toolName}, id=${event.toolCallId}, type=${event.toolType}, isError=${event.isError}")
    }

    private fun handleAudio(event: ConversationEvent.Audio) {
        Log.d("ConvEventHandler", "Audio event: id=${event.eventId}, bytes=${event.audioBase64.length}")
    }

    private fun handleConversationInitiationMetadata(event: ConversationEvent.ConversationInitiationMetadata) {
        Log.d("ConvEventHandler", "Conversation init: id=${event.conversationId}, agentOut=${event.agentOutputAudioFormat}, userIn=${event.userInputAudioFormat}")
    }

    private fun handleInterruption(event: ConversationEvent.Interruption) {
        // Switch to listening when agent is interrupted; disable feedback availability
        _conversationMode.value = ConversationMode.LISTENING
        onCanSendFeedbackChange?.invoke(false)
        Log.d("ConvEventHandler", "Interruption: eventId=${event.eventId}")
    }

    /**
     * Handle client tool call events
     */
    private suspend fun handleClientToolCall(event: ConversationEvent.ClientToolCall) {
        scope.launch {
            val toolExists = toolRegistry.isToolRegistered(event.toolName)
            if (!toolExists) {
                // Notify app layer about unhandled tool call
                try { onUnhandledClientToolCall?.invoke(event) } catch (_: Throwable) {}
            }

            val result = try {
                if (!toolExists) {
                    ClientToolResult.failure("Tool '${event.toolName}' not registered on client")
                } else {
                    toolRegistry.executeTool(event.toolName, event.parameters)
                }
            } catch (e: Exception) {
                ClientToolResult.failure("Tool execution failed: ${e.message}")
            }

            // Send result back to agent if response is expected and result is not null
            if (event.expectsResponse && result != null) {
                val toolResultEvent = OutgoingEvent.ClientToolResult(
                    toolCallId = event.toolCallId,
                    result = mapOf<String, Any>(
                        "success" to result.success,
                        "result" to result.result,
                        "error" to (result.error ?: "")
                    ),
                    isError = !result.success
                )

                messageCallback(toolResultEvent)
            }
            Log.d("ConvEventHandler", "Tool executed: ${event.toolName} -> ${if (result == null) "NO_RESPONSE" else if (result.success) "SUCCESS" else "FAILED"}")
        }
    }

    /**
     * Handle ping events
     */
    private fun handlePing(event: ConversationEvent.Ping) {
        Log.d("ConvEventHandler", "Ping received: eventId=${event.eventId}, pingMs=${event.pingMs}")
        // Reply with pong using same event id
        scope.launch {
            try {
                val pong = OutgoingEvent.Pong(eventId = event.eventId)
                messageCallback(pong)
            } catch (e: Exception) {
                Log.d("ConvEventHandler", "Failed to send pong: ${e.message}")
            }
        }
    }

    /**
     * Handle VAD (Voice Activity Detection) score events
     */
    private fun handleVadScore(event: ConversationEvent.VadScore) {
        // Invoke the onVadScore callback if provided
        try {
            onVadScore?.invoke(event.score)
        } catch (e: Exception) {
            Log.d("ConvEventHandler", "Error in onVadScore callback: ${e.message}")
        }
    }

    /**
     * Send a user message
     *
     * @param content Message content to send
     */
    fun sendUserMessage(content: String) {
        val event = OutgoingEvent.UserMessage(text = content)
        messageCallback(event)
    }

    /**
     * Send feedback for the last agent response
     *
     * @param isPositive true for positive feedback, false for negative
     */
    fun sendFeedback(isPositive: Boolean) {
        val lastEventId = _lastAgentEventId
        val lastFeedbackSentForEventId = _lastFeedbackSentForEventIdInt

        if (lastEventId != null) {
            // Check if we've already sent feedback for this event or a newer one
            if (lastFeedbackSentForEventId != null && lastEventId <= lastFeedbackSentForEventId) {
                Log.d("ConvEventHandler", "Feedback already sent for event ID $lastEventId (last feedback sent for: $lastFeedbackSentForEventId)")
                return
            }

            try {
                val event = OutgoingEvent.Feedback(
                    score = if (isPositive) "like" else "dislike",
                    eventId = lastEventId
                )
                messageCallback(event)
                Log.d("ConvEventHandler", "Sent ${if (isPositive) "positive" else "negative"} feedback for event ID: $lastEventId")

                // Track that we sent feedback for this event ID
                _lastFeedbackSentForEventIdInt = lastEventId
                onCanSendFeedbackChange?.invoke(false)
            } catch (e: Exception) {
                Log.d("ConvEventHandler", "Error sending feedback: ${e.message}")
            }
        } else {
            Log.d("ConvEventHandler", "No agent response to provide feedback for")
        }
    }

    /**
     * Send contextual update without triggering a response
     *
     * @param content Context information to send
     */
    fun sendContextualUpdate(content: String) {
        val event = OutgoingEvent.ContextualUpdate(text = content)
        messageCallback(event)
        Log.d("ConvEventHandler", "Sent contextual update: $content")
    }

    /**
     * Notify agent of user activity (e.g., typing)
     */
    fun sendUserActivity() {
        try {
            val event = OutgoingEvent.UserActivity()
            messageCallback(event)
            Log.d("ConvEventHandler", "Sent user activity")
        } catch (e: Exception) {
            Log.d("ConvEventHandler", "Failed to send user activity: ${e.message}")
        }
    }

    /**
     * Get the current conversation mode
     */
    fun getCurrentMode(): ConversationMode = _conversationMode.value

    // No message list getter; UI should use server transcripts

    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
        _lastAgentEventId = null
        _lastFeedbackSentForEventIdInt = null
    }
}