package com.elevenlabs.sdk

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.*
import kotlinx.serialization.json.*

/**
 * Interface for ElevenLabs SDK operations
 * This interface allows for easy mocking in tests
 */
interface ElevenLabsInterface {
    
    /**
     * Start a new conversation session
     */
    suspend fun startSession(
        context: Context,
        config: SessionConfig
    ): Conversation
    
    /**
     * Convert byte array to base64 string
     */
    fun arrayBufferToBase64(data: ByteArray): String
    
    /**
     * Convert base64 string to byte array
     */
    fun base64ToArrayBuffer(base64: String): ByteArray?
    
    /**
     * Configure audio session for optimal performance
     */
    fun configureAudioSession(context: Context)
    
    /**
     * Conversation interface for better testability
     */
    interface Conversation {
        
        // State flows
        val mode: Flow<Mode>
        val status: Flow<Status>
        val volume: Flow<Float>
        val messages: Flow<Pair<String, Role>>
        val errors: Flow<Pair<String, Any?>>
        
        // Control methods
        fun sendContextualUpdate(text: String)
        fun sendUserMessage(text: String? = null)
        fun sendUserActivity()
        fun endSession()
        fun getId(): String
        fun startRecording()
        fun stopRecording()
        
        // Properties
        var conversationVolume: Float
    }
    
    // Data classes and enums
    enum class Language(val value: String) {
        EN("en"), JA("ja"), ZH("zh"), DE("de"), HI("hi"), 
        FR("fr"), KO("ko"), PT("pt"), IT("it"), ES("es"), 
        ID("id"), NL("nl"), TR("tr"), PL("pl"), SV("sv"), 
        BG("bg"), RO("ro"), AR("ar"), CS("cs"), EL("el"), 
        FI("fi"), MS("ms"), DA("da"), TA("ta"), UK("uk"), 
        RU("ru"), HU("hu"), NO("no"), VI("vi")
    }
    
    @Serializable
    data class AgentPrompt(val prompt: String? = null)
    
    @Serializable
    data class TTSConfig(
        @SerialName("voice_id") val voiceId: String? = null
    )
    
    @Serializable
    data class AgentConfig(
        val prompt: AgentPrompt? = null,
        @SerialName("first_message") val firstMessage: String? = null,
        val language: Language? = null
    )
    
    @Serializable
    data class ConversationConfigOverride(
        val agent: AgentConfig? = null,
        val tts: TTSConfig? = null
    )
    
    @Serializable
    sealed class DynamicVariableValue {
        @Serializable
        data class StringValue(val value: String) : DynamicVariableValue()
        
        @Serializable
        data class NumberValue(val value: Double) : DynamicVariableValue()
        
        @Serializable
        data class BooleanValue(val value: Boolean) : DynamicVariableValue()
        
        @Serializable
        data class IntValue(val value: Int) : DynamicVariableValue()
        
        val jsonValue: Any
            get() = when (this) {
                is StringValue -> value
                is NumberValue -> value
                is BooleanValue -> value
                is IntValue -> value
            }
    }
    
    data class SessionConfig(
        val signedUrl: String? = null,
        val agentId: String? = null,
        val overrides: ConversationConfigOverride? = null,
        val clientTools: ClientTools = ClientTools(),
        val dynamicVariables: Map<String, DynamicVariableValue>? = null
    ) {
        init {
            require(signedUrl != null || agentId != null) {
                "Either signedUrl or agentId must be provided"
            }
        }
    }
    
    enum class Role { USER, AI }
    enum class Mode { SPEAKING, LISTENING }
    enum class Status { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }
    
    sealed class ElevenLabsError(message: String) : Exception(message) {
        object InvalidConfiguration : ElevenLabsError("Invalid configuration provided")
        object InvalidURL : ElevenLabsError("The provided URL is invalid")
        object InvalidInitialMessageFormat : ElevenLabsError("The initial message format is invalid")
        object UnexpectedBinaryMessage : ElevenLabsError("Received an unexpected binary message")
        object UnknownMessageType : ElevenLabsError("Received an unknown message type")
        object FailedToCreateAudioFormat : ElevenLabsError("Failed to create the audio format")
        object FailedToCreateAudioComponent : ElevenLabsError("Failed to create audio component")
        object FailedToCreateAudioComponentInstance : ElevenLabsError("Failed to create audio component instance")
        object RecordAudioPermissionNotGranted : ElevenLabsError("RECORD_AUDIO permission not granted")
        object AudioSessionConfigurationFailed : ElevenLabsError("Failed to configure audio session")
    }
    
    // Client Tools
    typealias ClientToolHandler = suspend (Map<String, Any>) -> String?
    
    class ClientTools {
        private val tools = mutableMapOf<String, ClientToolHandler>()
        
        fun register(name: String, handler: ClientToolHandler) {
            tools[name] = handler
        }
        
        suspend fun handle(name: String, parameters: Map<String, Any>): String? {
            return tools[name]?.invoke(parameters)
                ?: throw ClientToolError.HandlerNotFound(name)
        }
    }
    
    sealed class ClientToolError(message: String) : Exception(message) {
        class HandlerNotFound(name: String) : ClientToolError("Handler not found: $name")
        class InvalidParameters : ClientToolError("Invalid parameters")
        class ExecutionFailed(message: String) : ClientToolError("Execution failed: $message")
    }
} 