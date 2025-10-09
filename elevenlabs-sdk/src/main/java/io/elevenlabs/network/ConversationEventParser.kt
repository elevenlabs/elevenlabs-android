package io.elevenlabs.network

import android.util.Log
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import com.google.gson.*
import com.google.gson.annotations.SerializedName

/**
 * JSON event processing for real-time conversation protocol
 *
 * This object handles parsing incoming events from ElevenLabs servers and
 * serializing outgoing user actions and responses. It provides robust
 * error handling for malformed messages.
 */
object ConversationEventParser {

    private val gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    /**
     * Parse an incoming JSON event string into a ConversationEvent
     *
     * @param json The JSON string to parse
     * @return ConversationEvent instance or null if parsing fails
     */
    fun parseIncomingEvent(json: String): ConversationEvent? {
        return try {
            val jsonObject = JsonParser.parseString(json).asJsonObject
            val eventType = getEventType(jsonObject)

            when (eventType) {
                "conversation_initiation_metadata" -> parseConversationInitiationMetadata(jsonObject)
                "audio" -> parseAudio(jsonObject)
                "agent_response" -> parseAgentResponse(jsonObject)
                "agent_response_correction" -> parseAgentResponseCorrection(jsonObject)
                "user_transcript" -> parseUserTranscript(jsonObject)
                "client_tool_call" -> parseClientToolCall(jsonObject)
                "agent_tool_response" -> parseAgentToolResponse(jsonObject)
                "vad_score" -> parseVadScore(jsonObject)
                "interruption" -> parseInterruption(jsonObject)
                "ping" -> parsePing(jsonObject)
                else -> {
                    handleParsingError(json, IllegalArgumentException("Unknown event type: $eventType"))
                    null
                }
            }
        } catch (e: Exception) {
            handleParsingError(json, e)
            null
        }
    }

    /**
     * Parse ping event
     * Matches payload: {"ping_event":{"event_id":3,"ping_ms":null},"type":"ping"}
     */
    private fun parsePing(jsonObject: JsonObject): ConversationEvent.Ping {
        val ping = jsonObject.getAsJsonObject("ping_event")
        val eventId = ping?.get("event_id")?.asInt ?: 0
        val pingMs = ping?.get("ping_ms")?.let { if (it.isJsonNull) null else it.asLong }
        return ConversationEvent.Ping(eventId = eventId, pingMs = pingMs)
    }

    /**
     * Serialize an outgoing event to JSON string
     *
     * @param event The event to serialize
     * @return JSON string representation of the event
     */
    fun serializeOutgoingEvent(event: OutgoingEvent): String {
        return gson.toJson(event)
    }

    /**
     * Extract the event type from a JSON object
     */
    private fun getEventType(jsonObject: JsonObject): String? {
        return jsonObject.get("type")?.asString
    }

    /**
     * Parse agent response event
     */
    private fun parseAgentResponse(jsonObject: JsonObject): ConversationEvent.AgentResponse {
        val obj = jsonObject.getAsJsonObject("agent_response_event")
        val content = obj?.get("agent_response")?.asString ?: ""
        return ConversationEvent.AgentResponse(agentResponse = content)
    }

    /**
     * Parse user transcript event
     */
    private fun parseUserTranscript(jsonObject: JsonObject): ConversationEvent.UserTranscript {
        val obj = jsonObject.getAsJsonObject("user_transcription_event")
        val content = obj?.get("user_transcript")?.asString ?: ""
        return ConversationEvent.UserTranscript(userTranscript = content)
    }

    /**
     * Parse client tool call event
     */
    private fun parseClientToolCall(jsonObject: JsonObject): ConversationEvent.ClientToolCall {
        // Payloads can be either flat or nested under "client_tool_call"
        val obj = jsonObject.getAsJsonObject("client_tool_call") ?: jsonObject

        val parametersJson = obj.get("parameters")?.asJsonObject
        val parameters = mutableMapOf<String, Any>()

        parametersJson?.entrySet()?.forEach { entry ->
            parameters[entry.key] = when {
                entry.value.isJsonPrimitive -> {
                    val primitive = entry.value.asJsonPrimitive
                    when {
                        primitive.isString -> primitive.asString
                        primitive.isNumber -> primitive.asNumber
                        primitive.isBoolean -> primitive.asBoolean
                        else -> primitive.asString
                    }
                }
                entry.value.isJsonArray -> gson.fromJson(entry.value, List::class.java)
                entry.value.isJsonObject -> gson.fromJson(entry.value, Map::class.java)
                else -> entry.value.toString()
            }
        }

        return ConversationEvent.ClientToolCall(
            toolName = obj.get("tool_name")?.asString ?: "",
            parameters = parameters,
            toolCallId = obj.get("tool_call_id")?.asString ?: "",
            expectsResponse = obj.get("expects_response")?.asBoolean == true,
        )
    }

    private fun parseAgentResponseCorrection(jsonObject: JsonObject): ConversationEvent.AgentResponseCorrection {
        val obj = jsonObject.getAsJsonObject("agent_response_correction_event") ?: jsonObject
        val original = obj.get("original_agent_response")?.asString ?: ""
        val corrected = obj.get("corrected_agent_response")?.asString ?: ""
        return ConversationEvent.AgentResponseCorrection(originalAgentResponse = original, correctedAgentResponse = corrected)
    }

    private fun parseAgentToolResponse(jsonObject: JsonObject): ConversationEvent.AgentToolResponse {
        val obj = jsonObject.getAsJsonObject("agent_tool_response") ?: jsonObject
        return ConversationEvent.AgentToolResponse(
            toolName = obj.get("tool_name")?.asString ?: "",
            toolCallId = obj.get("tool_call_id")?.asString ?: "",
            toolType = obj.get("tool_type")?.asString ?: "",
            isError = obj.get("is_error")?.asBoolean ?: false
        )
    }

    private fun parseAudio(jsonObject: JsonObject): ConversationEvent.Audio {
        val obj = jsonObject.getAsJsonObject("audio_event") ?: jsonObject
        return ConversationEvent.Audio(
            eventId = obj.get("event_id")?.asInt ?: 0,
            audioBase64 = obj.get("audio_base64")?.asString ?: ""
        )
    }

    private fun parseConversationInitiationMetadata(jsonObject: JsonObject): ConversationEvent.ConversationInitiationMetadata {
        val obj = jsonObject.getAsJsonObject("conversation_initiation_metadata") ?: jsonObject
        return ConversationEvent.ConversationInitiationMetadata(
            conversationId = obj.get("conversation_id")?.asString ?: "",
            agentOutputAudioFormat = obj.get("agent_output_audio_format")?.asString ?: "",
            userInputAudioFormat = obj.get("user_input_audio_format")?.asString ?: ""
        )
    }

    private fun logAgentToolResponse(jsonObject: JsonObject) {
        try {
            Log.d("ConversationEventParser", "Agent tool response: ${jsonObject}")
        } catch (_: Exception) { }
    }

    /**
     * Parse VAD score event
     * Matches payload: {"type":"vad_score","vad_score_event":{"vad_score":0.95}}
     */
    private fun parseVadScore(jsonObject: JsonObject): ConversationEvent.VadScore {
        val obj = jsonObject.getAsJsonObject("vad_score_event") ?: jsonObject
        val score = obj.get("vad_score")?.asFloat ?: 0.0f

        return ConversationEvent.VadScore(
            score = score,
        )
    }

    private fun parseInterruption(jsonObject: JsonObject): ConversationEvent.Interruption {
        val obj = jsonObject.getAsJsonObject("interruption_event")
        val id = obj?.get("event_id")?.asInt ?: 0
        return ConversationEvent.Interruption(eventId = id)
    }

    /**
     * Handle parsing errors
     */
    private fun handleParsingError(json: String, error: Exception) {
        Log.d("ConversationEventParser", "Failed to parse conversation event: ${error.message}")
        Log.d("ConversationEventParser", "JSON: $json")
    }
}

/**
 * Base class for outgoing events that can be sent to the server
 */
sealed class OutgoingEvent {
    abstract val type: String

    /**
     * User message event
     */
    data class UserMessage(
        val text: String,
    ) : OutgoingEvent() {
        override val type = "user_message"
    }

    class UserActivity : OutgoingEvent() {
        override val type = "user_activity"
    }

    /**
     * Feedback event
     */
    data class Feedback(
        val score: String, // "like" or "dislike"
        @SerializedName("event_id")
        val eventId: Int
    ) : OutgoingEvent() {
        override val type = "feedback"
    }

    /**
     * Contextual update event
     */
    data class ContextualUpdate(
        val text: String,
    ) : OutgoingEvent() {
        override val type = "contextual_update"
    }

    /**
     * Tool result event
     */
    data class ClientToolResult(
        @SerializedName("tool_call_id")
        val toolCallId: String,
        val result: Map<String, Any>,
        @SerializedName("is_error")
        val isError: Boolean = false,
    ) : OutgoingEvent() {
        override val type = "client_tool_result"
    }

    /**
     * Pong reply for ping
     */
    data class Pong(
        @SerializedName("event_id")
        val eventId: Int
    ) : OutgoingEvent() {
        override val type: String = "pong"
    }
}