package com.elevenlabs.sdk

import io.livekit.android.room.Room
import io.livekit.android.room.RoomEvent
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import android.util.Log
import java.nio.charset.StandardCharsets

class DataChannelManager(
    private val room: Room,
    private val callbacks: Callbacks,
    private val clientTools: ElevenLabsInterface.ClientTools?
) {
    companion object {
        private const val TAG = "DataChannelManager"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        setupDataChannelHandling()
    }
    
    private fun setupDataChannelHandling() {
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.DataReceived -> {
                        try {
                            val messageString = String(event.data, StandardCharsets.UTF_8)
                            val message = Json.parseToJsonElement(messageString).jsonObject
                            handleIncomingMessage(message)
                        } catch (error: Exception) {
                            Log.e(TAG, "Failed to parse data channel message", error)
                            callbacks.onError("Failed to parse incoming message", error)
                        }
                    }
                    else -> {
                        // Handle other events as needed
                    }
                }
            }
        }
    }
    
    suspend fun sendConversationInitiation(config: ElevenLabsInterface.SessionConfig) {
        val initMessage = buildJsonObject {
            put("type", "conversation_initiation_client_data")
            
            config.dynamicVariables?.let { dynamicVars ->
                put("dynamic_variables", JsonObject(dynamicVars.mapValues { entry ->
                    when (val value = entry.value) {
                        is ElevenLabsInterface.DynamicVariableValue.StringValue -> JsonPrimitive(value.value)
                        is ElevenLabsInterface.DynamicVariableValue.NumberValue -> JsonPrimitive(value.value)
                        is ElevenLabsInterface.DynamicVariableValue.BooleanValue -> JsonPrimitive(value.value)
                        is ElevenLabsInterface.DynamicVariableValue.IntValue -> JsonPrimitive(value.value)
                    }
                }))
            }
            
            config.overrides?.let { overrides ->
                put("conversation_config_override", Json.encodeToJsonElement(overrides))
            }
            
            config.customLlmExtraBody?.let { customLlmExtraBody ->
                put("custom_llm_extra_body", customLlmExtraBody)
            }
        }
        
        sendMessage(initMessage)
    }
    
    suspend fun sendMessage(message: JsonObject) {
        try {
            val data = message.toString().toByteArray(StandardCharsets.UTF_8)
            room.localParticipant?.publishData(data, reliable = true)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to send data channel message", error)
            callbacks.onError("Failed to send message", error)
        }
    }
    
    private suspend fun handleIncomingMessage(message: JsonObject) {
        val type = message["type"]?.jsonPrimitive?.content
        
        when (type) {
            "conversation_initiation_metadata" -> handleConversationInitiation(message)
            "agent_response" -> handleAgentResponse(message)
            "user_transcript" -> handleUserTranscript(message)
            "agent_response_correction" -> handleAgentResponseCorrection(message)
            "client_tool_call" -> handleClientToolCall(message)
            "interruption" -> handleInterruption(message)
            "ping" -> handlePing(message)
            else -> {
                if (type !in listOf("internal_tentative_agent_response", "internal_vad_score", "internal_turn_probability")) {
                    Log.d(TAG, "Unknown message type: $type")
                }
            }
        }
    }
    
    private fun handleConversationInitiation(message: JsonObject) {
        val metadata = message["conversation_initiation_metadata_event"]?.jsonObject
        val conversationId = metadata?.get("conversation_id")?.jsonPrimitive?.content
        
        conversationId?.let { callbacks.onConnect(it) }
    }
    
    private fun handleAgentResponse(message: JsonObject) {
        val event = message["agent_response_event"]?.jsonObject
        val response = event?.get("agent_response")?.jsonPrimitive?.content
        
        response?.let { callbacks.onMessage(it, ElevenLabsInterface.Role.AI) }
    }
    
    private fun handleUserTranscript(message: JsonObject) {
        val event = message["user_transcription_event"]?.jsonObject
        val transcript = event?.get("user_transcript")?.jsonPrimitive?.content
        
        transcript?.let { callbacks.onMessage(it, ElevenLabsInterface.Role.USER) }
    }
    
    private fun handleAgentResponseCorrection(message: JsonObject) {
        val event = message["agent_response_correction_event"]?.jsonObject
        val original = event?.get("original_agent_response")?.jsonPrimitive?.content
        val corrected = event?.get("corrected_agent_response")?.jsonPrimitive?.content
        
        if (original != null && corrected != null) {
            callbacks.onMessageCorrection?.invoke(original, corrected, ElevenLabsInterface.Role.AI)
        }
    }
    
    private suspend fun handleClientToolCall(message: JsonObject) {
        val toolCall = message["client_tool_call"]?.jsonObject
        if (toolCall == null || clientTools == null) {
            Log.w(TAG, "Received client tool call but no tools available")
            return
        }
        
        val toolName = toolCall["tool_name"]?.jsonPrimitive?.content
        val toolCallId = toolCall["tool_call_id"]?.jsonPrimitive?.content
        val parameters = toolCall["parameters"]?.jsonObject
        
        if (toolName != null && toolCallId != null && parameters != null) {
            try {
                val result = clientTools.handle(toolName, parameters.toMap())
                
                sendMessage(buildJsonObject {
                    put("type", "client_tool_result")
                    put("tool_call_id", toolCallId)
                    put("result", result ?: "")
                    put("is_error", false)
                })
            } catch (error: Exception) {
                Log.e(TAG, "Client tool execution failed", error)
                
                sendMessage(buildJsonObject {
                    put("type", "client_tool_result")
                    put("tool_call_id", toolCallId)
                    put("result", error.message ?: "Unknown error")
                    put("is_error", true)
                })
            }
        }
    }
    
    private fun handleInterruption(message: JsonObject) {
        // Handle interruption - notify mode change
        callbacks.onModeChange(ElevenLabsInterface.Mode.LISTENING)
    }
    
    private suspend fun handlePing(message: JsonObject) {
        val eventId = message["ping_event"]?.jsonObject?.get("event_id")?.jsonPrimitive?.int
        
        eventId?.let {
            sendMessage(buildJsonObject {
                put("type", "pong")
                put("event_id", it)
            })
        }
    }
}

// Extension function to convert JsonObject to Map
private fun JsonObject.toMap(): Map<String, Any> {
    val map = mutableMapOf<String, Any>()
    for ((key, value) in this) {
        when (value) {
            is JsonPrimitive -> {
                when {
                    value.isString -> map[key] = value.content
                    value.intOrNull != null -> map[key] = value.int
                    value.doubleOrNull != null -> map[key] = value.double
                    value.booleanOrNull != null -> map[key] = value.boolean
                    else -> map[key] = value.content
                }
            }
            is JsonObject -> map[key] = value.toMap()
            is JsonArray -> map[key] = value.map { 
                when (it) {
                    is JsonPrimitive -> {
                        when {
                            it.isString -> it.content
                            it.intOrNull != null -> it.int
                            it.doubleOrNull != null -> it.double
                            it.booleanOrNull != null -> it.boolean
                            else -> it.content
                        }
                    }
                    is JsonObject -> it.toMap()
                    else -> it.toString()
                }
            }
            else -> map[key] = value.toString()
        }
    }
    return map
} 