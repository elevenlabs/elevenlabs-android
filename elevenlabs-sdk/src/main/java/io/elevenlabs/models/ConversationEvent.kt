package io.elevenlabs.models

/**
 * Sealed class representing all possible conversation events
 *
 * This provides type-safe event handling for real-time conversation communication
 * between the client and ElevenLabs agents.
 */
sealed class ConversationEvent {

    /**
     * Audio alignment data event (structure can vary; exposed as a map)
     */
    data class AudioAlignment(
        val alignment: Map<String, Any>
    ) : ConversationEvent()

    /**
     * Agent response metadata (e.g., additional timing/semantic info)
     * Structure varies; exposed as a map for flexibility.
     */
    data class AgentResponseMetadata(
        val metadata: Map<String, Any>
    ) : ConversationEvent()

    /**
     * Streaming agent chat response parts
     * type lifecycle: start -> delta... -> stop
     *
     * @param partType The part's position in the lifecycle ("start" | "delta" | "stop")
     * @param text The text chunk for this part
     * @param eventId Server event id used to correlate parts of the same streamed message
     */
    data class AgentChatResponsePart(
        val partType: String,
        val text: String,
        val eventId: Int? = null
    ) : ConversationEvent()

    /**
     * Tentative (partial) user transcript sent during recognition
     */
    data class TentativeUserTranscript(
        val userTranscript: String,
        val eventId: Int?
    ) : ConversationEvent()

    /**
     * Tentative (partial) agent response text
     */
    data class TentativeAgentResponse(
        val tentativeAgentResponse: String
    ) : ConversationEvent()

    /**
     * Event representing the metadata for the conversation
     *
     * @param conversationId The unique identifier for the conversation
     * @param agentOutputAudioFormat The audio format for the agent's output
     * @param userInputAudioFormat The audio format for the user's input
     */
    data class ConversationInitiationMetadata(
        val conversationId: String,
        val agentOutputAudioFormat: String,
        val userInputAudioFormat: String,
    ) : ConversationEvent()

    /**
     * Event representing audio data
     *
     * @param eventId The unique identifier for the event
     * @param audioBase64 The base64 encoded audio data
     */
    data class Audio(
        val eventId: Int,
        val audioBase64: String,
    ) : ConversationEvent()

    /**
     * Event representing a response from the agent
     *
     * @param agentResponse The agent's response text
     * @param eventId Server event id used to correlate this message in the transcript
     */
    data class AgentResponse(
        val agentResponse: String,
        val eventId: Int? = null,
    ) : ConversationEvent()

    /**
     * Event representing a correction to the agent's response after interruption
     *
     * @param originalAgentResponse The original agent response text
     * @param correctedAgentResponse The corrected agent response text
     * @param eventId Server event id used to correlate this message in the transcript
     */
    data class AgentResponseCorrection(
        val originalAgentResponse: String,
        val correctedAgentResponse: String,
        val eventId: Int? = null,
    ) : ConversationEvent()

    /**
     * Event representing user speech transcription
     *
     * @param userTranscript The transcribed user speech
     * @param eventId Server event id used to correlate this message in the transcript
     */
    data class UserTranscript(
        val userTranscript: String,
        val eventId: Int? = null,
    ) : ConversationEvent()

    /**
     * Event representing a tool call from the agent
     *
     * This is the event that actually carries the tool parameters and triggers
     * client-side tool execution. It is distinct from [AgentToolRequest], which
     * is only a notification that the agent is requesting a tool of any type
     * (client, webhook, or mcp) and carries no parameters.
     *
     * @param toolName Name of the tool to execute
     * @param parameters Parameters to pass to the tool
     * @param toolCallId Unique identifier for this tool call
     * @param expectsResponse Whether the agent expects a response
     */
    data class ClientToolCall(
        val toolName: String,
        val parameters: Map<String, Any>,
        val toolCallId: String,
        val expectsResponse: Boolean = false,
    ) : ConversationEvent()

    /**
     * Notification that the agent is requesting a tool of any type (client,
     * webhook, or mcp).
     *
     * Unlike [ClientToolCall], this event carries no parameters and does not
     * request client-side execution — it only signals that a tool call has been
     * initiated. The actual execution request for client tools arrives
     * separately as a [ClientToolCall]. Treating both events as a single
     * `ClientToolCall` (as the server may emit both) causes the client tool to
     * fire twice for one invocation, the first time with empty parameters.
     *
     * @param toolName Name of the tool being requested
     * @param toolCallId Unique identifier for this tool call
     * @param toolType Type of tool ("client", "webhook", or "mcp")
     * @param eventId Server event id for this request
     */
    data class AgentToolRequest(
        val toolName: String,
        val toolCallId: String,
        val toolType: String,
        val eventId: Int? = null,
    ) : ConversationEvent()

    /**
     * Event representing a response from an agent tool call
     *
     * @param toolName The name of the tool that responded
     * @param toolCallId The unique identifier for the tool call
     * @param toolType The type of tool that responded
     * @param isError Whether the tool call resulted in an error
     */
    data class AgentToolResponse(
        val toolName: String,
        val toolCallId: String,
        val toolType: String,
        val isError: Boolean,
    ) : ConversationEvent()

    /**
     * Event representing voice activity detection score
     *
     * @param score VAD score (0.0 to 1.0, higher means more likely speech)
     */
    data class VadScore(
        val score: Float,
    ) : ConversationEvent()

    /**
     * Event representing a ping from the agent
     *
     * @param eventId The unique identifier for the event
     * @param pingMs The time in milliseconds since the last ping
     */
    data class Ping(
        val eventId: Int,
        val pingMs: Long?
    ) : ConversationEvent()

    /**
     * Event representing an interruption of agent speech
     * Matches payload: {"interruption_event":{"event_id":119},"type":"interruption"}
     */
    data class Interruption(
        val eventId: Int
    ) : ConversationEvent()

    /**
     * Event representing a server error
     *
     * @param code The error code from the server
     * @param message Optional error message describing the error
     */
    data class ServerError(
        val code: Int,
        val message: String?
    ) : ConversationEvent()

}

/**
 * Lifecycle position of a streamed agent chat response part.
 */
enum class AgentResponsePartType {
    START,
    DELTA,
    STOP;

    companion object {
        /** Maps a raw value ("start" | "delta" | "stop") to its enum, or null if unrecognized. */
        fun fromString(value: String): AgentResponsePartType? = when (value) {
            "start" -> START
            "delta" -> DELTA
            "stop" -> STOP
            else -> null
        }
    }
}
