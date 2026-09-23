package io.elevenlabs

import android.util.Log
import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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
    private val onAgentToolRequest: ((ConversationEvent.AgentToolRequest) -> Unit)? = null,
    private val onVadScore: ((Float) -> Unit)? = null,
    private val onAudioAlignment: ((Map<String, Any>) -> Unit)? = null,
    private val onAgentResponseMetadata: ((Map<String, Any>) -> Unit)? = null,
    private val onUserTranscript: ((String) -> Unit)? = null,
    private val onAgentResponse: ((String) -> Unit)? = null,
    private val onAgentResponseCorrection: ((String, String) -> Unit)? = null,
    private val onUserTranscriptEvent: ((String, Int?) -> Unit)? = null,
    private val onTentativeUserTranscriptEvent: ((String, Int?) -> Unit)? = null,
    private val onAgentResponseEvent: ((String, Int?) -> Unit)? = null,
    private val onAgentResponsePartEvent: ((AgentResponsePartType, String, Int?) -> Unit)? = null,
    private val onAgentResponseCorrectionEvent: ((String, Int?) -> Unit)? = null,
    private val onAgentToolResponse: ((String, String, String, Boolean) -> Unit)? = null,
    private val onConversationInitiationMetadata: ((String, String, String) -> Unit)? = null,
    private val onInterruption: ((Int) -> Unit)? = null,
    private val onEndCall: (suspend () -> Unit)? = null,
    private val onError: ((Int, String?) -> Unit)? = null
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // State management
    private val _conversationMode = MutableStateFlow(ConversationMode.LISTENING)
    val conversationMode: StateFlow<ConversationMode> = _conversationMode

    // Reconciled conversation transcript exposed by the session.
    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages

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
                is ConversationEvent.AgentChatResponsePart -> handleAgentChatResponsePart(event)
                is ConversationEvent.UserTranscript -> handleUserTranscript(event)
                is ConversationEvent.TentativeUserTranscript -> handleTentativeUserTranscript(event)
                is ConversationEvent.TentativeAgentResponse -> handleTentativeAgentResponse(event)
                is ConversationEvent.ClientToolCall -> handleClientToolCall(event)
                is ConversationEvent.AgentToolRequest -> handleAgentToolRequest(event)
                is ConversationEvent.VadScore -> handleVadScore(event)
                is ConversationEvent.AudioAlignment -> handleAudioAlignment(event)
                is ConversationEvent.AgentResponseMetadata -> handleAgentResponseMetadata(event)
                is ConversationEvent.Ping -> handlePing(event)
                is ConversationEvent.AgentResponseCorrection -> handleAgentResponseCorrection(event)
                is ConversationEvent.AgentToolResponse -> handleAgentToolResponse(event)
                is ConversationEvent.Audio -> handleAudio(event)
                is ConversationEvent.ConversationInitiationMetadata -> handleConversationInitiationMetadata(event)
                is ConversationEvent.Interruption -> handleInterruption(event)
                is ConversationEvent.ServerError -> handleServerError(event)

            }
        } catch (e: Exception) {
            Log.d("ConvEventHandler", "Error handling conversation event: ${e.message}")
            // Continue processing other events even if one fails
        }
    }

    /**
     * Handle streaming agent chat response parts
     */
    private suspend fun handleAgentChatResponsePart(event: ConversationEvent.AgentChatResponsePart) {
        appendAgentResponsePart(text = event.text, eventId = event.eventId, isStop = event.partType == "stop")

        AgentResponsePartType.fromString(event.partType)?.let { partType ->
            onAgentResponsePartEvent?.invoke(partType, event.text, event.eventId)
        }

        when (event.partType) {
            "start" -> {
                _conversationMode.value = ConversationMode.SPEAKING
                if (!audioManager.isPlaying()) {
                    try { audioManager.startPlayback() } catch (_: Throwable) {}
                }
            }
            "delta" -> {
                if (event.text.isNotEmpty()) {
                    onAgentResponse?.invoke(event.text)
                }
            }
            "stop" -> {
                _conversationMode.value = ConversationMode.LISTENING
            }
        }
    }

    /**
     * Handle tentative user transcripts (partial recognition)
     */
    private suspend fun handleTentativeUserTranscript(event: ConversationEvent.TentativeUserTranscript) {
        applyTentativeUserTranscript(content = event.userTranscript, eventId = event.eventId)
        onUserTranscript?.invoke(event.userTranscript)
        onTentativeUserTranscriptEvent?.invoke(event.userTranscript, event.eventId)
    }

    /**
     * Handle tentative agent responses (partial)
     */
    private fun handleTentativeAgentResponse(event: ConversationEvent.TentativeAgentResponse) {
        onAgentResponse?.invoke(event.tentativeAgentResponse)
    }

    /**
     * Handle audio alignment events
     */
    private fun handleAudioAlignment(event: ConversationEvent.AudioAlignment) {
        onAudioAlignment?.invoke(event.alignment)
    }

    /**
     * Handle agent response metadata events
     */
    private fun handleAgentResponseMetadata(event: ConversationEvent.AgentResponseMetadata) {
        onAgentResponseMetadata?.invoke(event.metadata)
    }

    /**
     * Handle agent response events
     */
    private suspend fun handleAgentResponse(event: ConversationEvent.AgentResponse) {
        applyAgentResponse(content = event.agentResponse, eventId = event.eventId)

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

        onAgentResponse?.invoke(event.agentResponse)
        onAgentResponseEvent?.invoke(event.agentResponse, event.eventId)
    }

    /**
     * Handle user transcript events
     */
    private suspend fun handleUserTranscript(event: ConversationEvent.UserTranscript) {
        applyUserTranscript(content = event.userTranscript, eventId = event.eventId)
        onUserTranscript?.invoke(event.userTranscript)
        onUserTranscriptEvent?.invoke(event.userTranscript, event.eventId)
    }

    private fun handleAgentResponseCorrection(event: ConversationEvent.AgentResponseCorrection) {
        applyAgentResponse(content = event.correctedAgentResponse, eventId = event.eventId)
        onAgentResponseCorrection?.invoke(event.originalAgentResponse, event.correctedAgentResponse)
        onAgentResponseCorrectionEvent?.invoke(event.correctedAgentResponse, event.eventId)
    }

    private fun handleAgentToolResponse(event: ConversationEvent.AgentToolResponse) {
        onAgentToolResponse?.invoke(event.toolName, event.toolCallId, event.toolType, event.isError)

        if (event.toolName == "end_call") {

            scope.launch {
                try {
                    onEndCall?.invoke()
                } catch (e: Exception) {
                    Log.e("ConvEventHandler", "Error ending call: ${e.message}", e)
                }
            }
        }
    }

    private fun handleAudio(event: ConversationEvent.Audio) {
        Log.d("ConvEventHandler", "Audio event: id=${event.eventId}, bytes=${event.audioBase64.length}")
    }

    private fun handleConversationInitiationMetadata(event: ConversationEvent.ConversationInitiationMetadata) {
        onConversationInitiationMetadata?.invoke(event.conversationId, event.agentOutputAudioFormat, event.userInputAudioFormat)
    }

    private fun handleInterruption(event: ConversationEvent.Interruption) {
        // Switch to listening when agent is interrupted; disable feedback availability
        _conversationMode.value = ConversationMode.LISTENING
        onCanSendFeedbackChange?.invoke(false)

        onInterruption?.invoke(event.eventId)
    }

    /**
     * Handle server error events
     */
    private fun handleServerError(event: ConversationEvent.ServerError) {
        Log.e("ConvEventHandler", "Server error (${event.code}): ${event.message ?: "unknown"}")
        onError?.invoke(event.code, event.message)
    }

    /**
     * Handle client tool call events
     */
    private suspend fun handleClientToolCall(event: ConversationEvent.ClientToolCall) {
        scope.launch {
            val toolExists = toolRegistry.isToolRegistered(event.toolName)
            if (!toolExists) {
                // Notify app layer about unhandled tool call
                onUnhandledClientToolCall?.invoke(event)

                // If no callback is registered and agent expects a response, send failure to prevent hanging
                if (onUnhandledClientToolCall == null && event.expectsResponse) {
                    val failureEvent = OutgoingEvent.ClientToolResult(
                        toolCallId = event.toolCallId,
                        result = "Tool '${event.toolName}' not registered and no handler provided",
                        isError = true
                    )
                    messageCallback(failureEvent)
                    Log.d("ConvEventHandler", "Tool '${event.toolName}' not registered - sent automatic failure response")
                } else {
                    Log.d("ConvEventHandler", "Tool '${event.toolName}' not registered - waiting for manual response via sendToolResult()")
                }
                return@launch
            }

            val result = try {
                toolRegistry.executeTool(event.toolName, event.parameters)
            } catch (e: Exception) {
                ClientToolResult.failure("Tool execution failed: ${e.message}")
            }

            // Send result back to agent if response is expected and result is not null
            if (event.expectsResponse && result != null) {
                // Send the result string directly - backend expects a string, not a wrapped object
                val resultString = if (result.success) {
                    result.result
                } else {
                    result.error ?: "Tool execution failed"
                }
                
                val toolResultEvent = OutgoingEvent.ClientToolResult(
                    toolCallId = event.toolCallId,
                    result = resultString,
                    isError = !result.success
                )

                messageCallback(toolResultEvent)
            }
            Log.d("ConvEventHandler", "Tool executed: ${event.toolName} -> ${if (result == null) "NO_RESPONSE" else if (result.success) "SUCCESS" else "FAILED"}")
        }
    }

    /**
     * Handle agent tool request events.
     *
     * This is a notification that the agent is requesting a tool of any type
     * (client, webhook, or mcp). It does NOT carry parameters and does not
     * request client-side execution — the actual execution request for client
     * tools arrives separately as a [ConversationEvent.ClientToolCall]. We only
     * forward the notification to the app layer; never execute the tool here,
     * otherwise a single invocation would fire twice (once with empty params).
     */
    private fun handleAgentToolRequest(event: ConversationEvent.AgentToolRequest) {
        Log.d("ConvEventHandler", "Agent tool request: ${event.toolName} (${event.toolType})")
        onAgentToolRequest?.invoke(event)
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
        onVadScore?.invoke(event.score)
    }

    /**
     * Send a user message
     *
     * @param content Message content to send
     */
    fun sendUserMessage(content: String) {
        val event = OutgoingEvent.UserMessage(text = content)
        messageCallback(event)
        appendLocalMessage(role = MessageRole.USER, content = content)
    }

    /**
     * Send the result of a client tool execution back to the agent
     *
     * @param toolCallId The unique identifier for the tool call
     * @param result The result string to send back to the agent
     * @param isError Whether the tool execution resulted in an error
     */
    fun sendToolResult(toolCallId: String, result: String, isError: Boolean = false) {
        val toolResultEvent = OutgoingEvent.ClientToolResult(
            toolCallId = toolCallId,
            result = result,
            isError = isError
        )
        messageCallback(toolResultEvent)
        Log.d("ConvEventHandler", "Sent tool result for call ID: $toolCallId (${if (isError) "ERROR" else "SUCCESS"})")
    }

    /**
     * Send feedback for the last agent response
     *
     * @param isPositive true for positive feedback, false for negative
     */
    fun sendFeedback(isPositive: Boolean) {
        val lastEventId = _messages.value.lastOrNull { it.role == MessageRole.AGENT }?.eventId
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

    /** Accumulates a streaming `agent_chat_response_part` into its turn's message. */
    private fun appendAgentResponsePart(text: String, eventId: Int?, isStop: Boolean) {
        _messages.update { current ->
            val idx = current.messageIndex(MessageRole.AGENT, eventId)
            when {
                idx != null -> {
                    val existing = current[idx]
                    // Don't apply streaming updates to a finalized message.
                    if (!existing.isPartial) current
                    else current.toMutableList().also {
                        it[idx] = existing.copy(content = existing.content + text, eventId = eventId, isPartial = !isStop)
                    }
                }
                // With no match, only start a bubble for a new turn; ignore streaming updates with stale event_ids.
                current.isNewerThanHighestEventId(MessageRole.AGENT, eventId) ->
                    current + Message(role = MessageRole.AGENT, content = text, eventId = eventId, isPartial = !isStop)
                else -> current
            }
        }
    }

    /** Applies the canonical finalized `agent_response` / `agent_response_correction` for a turn. */
    private fun applyAgentResponse(content: String, eventId: Int?) {
        _messages.update { current ->
            val idx = current.messageIndex(MessageRole.AGENT, eventId)
            if (idx != null) {
                current.toMutableList().also {
                    it[idx] = current[idx].copy(content = content, eventId = eventId, isPartial = false)
                }
            } else {
                current + Message(role = MessageRole.AGENT, content = content, eventId = eventId, isPartial = false)
            }
        }
    }

    /** Applies a finalized `user_transcript`. */
    private fun applyUserTranscript(content: String, eventId: Int?) {
        _messages.update { current ->
            val idx = current.messageIndex(MessageRole.USER, eventId)
            val reconciled = if (idx != null) {
                current.toMutableList().also {
                    it[idx] = current[idx].copy(content = content, eventId = eventId, isPartial = false)
                }
            } else {
                current + Message(role = MessageRole.USER, content = content, eventId = eventId, isPartial = false)
            }
            // Make sure there are no orphaned tentative user transcripts.
            reconciled.filterNot { it.role == MessageRole.USER && it.isPartial }
        }
    }

    /** Applies an in-progress `tentative_user_transcript`. */
    private fun applyTentativeUserTranscript(content: String, eventId: Int?) {
        _messages.update { current ->
            // Make sure there are no orphaned tentative user transcripts.
            val withoutPartials = current.filterNot { it.role == MessageRole.USER && it.isPartial }
            if (withoutPartials.isNewerThanHighestEventId(MessageRole.USER, eventId)) {
                withoutPartials + Message(role = MessageRole.USER, content = content, eventId = eventId, isPartial = true)
            } else {
                // Ignore tentative user transcripts with stale event ids.
                withoutPartials
            }
        }
    }

    /** Index of [role]'s message with exactly [eventId], or null (null ids never match). */
    private fun List<Message>.messageIndex(role: MessageRole, eventId: Int?): Int? {
        if (eventId == null) return null
        // Ids are unique per role but unsorted (finals can arrive late), so scan fully; tail-first
        // keeps the common "touch the latest message" case cheap.
        return indexOfLast { it.role == role && it.eventId == eventId }.takeIf { it >= 0 }
    }

    /** Whether [eventId] is newer than the highest event id [role] has recorded. */
    private fun List<Message>.isNewerThanHighestEventId(role: MessageRole, eventId: Int?): Boolean {
        if (eventId == null) return true
        // Take the max, not the last: finals can append out of order, and null-id local (typed)
        // messages carry no order, so they're skipped.
        val highestEventId = mapNotNull { if (it.role == role) it.eventId else null }.maxOrNull() ?: return true
        return eventId > highestEventId
    }

    /** Appends a finalized local message (no event id), e.g. text the user sends. */
    private fun appendLocalMessage(role: MessageRole, content: String) {
        _messages.update { it + Message(role = role, content = content, eventId = null, isPartial = false) }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        scope.cancel()
        _lastFeedbackSentForEventIdInt = null
    }
}