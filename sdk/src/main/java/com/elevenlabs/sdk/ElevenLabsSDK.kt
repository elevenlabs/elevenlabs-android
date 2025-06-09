package com.elevenlabs.sdk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.util.Base64
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.*
import kotlinx.coroutines.flow.Flow

/**
 * Public interface for ElevenLabs Android SDK
 * This interface can be easily mocked for testing
 */
interface ElevenLabsSDK {
    
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
     * Conversation interface for managing real-time conversations
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
}

/**
 * Convenient singleton access to the ElevenLabs SDK
 * For production use and easy access
 */
object ElevenLabs {
    val sdk: ElevenLabsSDK = ElevenLabsSDKImpl()
    
    // Convenience constants
    const val VERSION = "1.1.3"
}

// MARK: - Data Classes and Enums

enum class Language(val value: String) {
    EN("en"), JA("ja"), ZH("zh"), DE("de"), HI("hi"), 
    FR("fr"), KO("ko"), PT("pt"), IT("it"), ES("es"), 
    ID("id"), NL("nl"), TR("tr"), PL("pl"), SV("sv"), 
    BG("bg"), RO("ro"), AR("ar"), CS("cs"), EL("el"), 
    FI("fi"), MS("ms"), DA("da"), TA("ta"), UK("uk"), 
    RU("ru"), HU("hu"), NO("no"), VI("vi")
}

@Serializable
data class AgentPrompt(
    val prompt: String? = null
)

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
sealed class LlmExtraBodyValue {
    @Serializable
    data class StringValue(val value: String) : LlmExtraBodyValue()
    
    @Serializable
    data class NumberValue(val value: Double) : LlmExtraBodyValue()
    
    @Serializable
    data class BooleanValue(val value: Boolean) : LlmExtraBodyValue()
    
    @Serializable
    object NullValue : LlmExtraBodyValue()
    
    @Serializable
    data class ArrayValue(val value: List<LlmExtraBodyValue>) : LlmExtraBodyValue()
    
    @Serializable
    data class DictionaryValue(val value: Map<String, LlmExtraBodyValue>) : LlmExtraBodyValue()
    
    val jsonValue: Any
        get() = when (this) {
            is StringValue -> value
            is NumberValue -> value
            is BooleanValue -> value
            is NullValue -> JsonNull
            is ArrayValue -> value.map { it.jsonValue }
            is DictionaryValue -> value.mapValues { it.value.jsonValue }
        }
}

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

enum class Role { USER, AI }
enum class Mode { SPEAKING, LISTENING }
enum class Status { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }

// MARK: - Client Tools

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

// MARK: - Session Config

data class SessionConfig(
    val signedUrl: String? = null,
    val agentId: String? = null,
    val overrides: ConversationConfigOverride? = null,
    val customLlmExtraBody: Map<String, LlmExtraBodyValue>? = null,
    val clientTools: ClientTools = ClientTools(),
    val dynamicVariables: Map<String, DynamicVariableValue>? = null
) {
    init {
        require(signedUrl != null || agentId != null) {
            "Either signedUrl or agentId must be provided"
        }
    }
}

// MARK: - Audio Processing

class AudioConcatProcessor {
    private val buffers = ConcurrentLinkedQueue<ByteArray>()
    private var cursor = 0
    private var currentBuffer: ByteArray? = null
    private var wasInterrupted = false
    private var finished = false
    
    var onProcess: ((Boolean) -> Unit)? = null
    
    fun process(outputs: Array<FloatArray>) {
        var isFinished = false
        val outputBuffer = outputs[0]
        var outputIndex = 0
        
        while (outputIndex < outputBuffer.size) {
            if (currentBuffer == null) {
                if (buffers.isEmpty()) {
                    isFinished = true
                    break
                }
                currentBuffer = buffers.poll()
                cursor = 0
            }
            
            currentBuffer?.let { buffer ->
                val remainingSamples = buffer.size / 2 - cursor
                val samplesToWrite = minOf(remainingSamples, outputBuffer.size - outputIndex)
                
                val byteBuffer = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                
                for (i in 0 until samplesToWrite) {
                    val sample = byteBuffer.getShort((cursor + i) * 2)
                    outputBuffer[outputIndex] = sample / 32768.0f
                    outputIndex++
                }
                
                cursor += samplesToWrite
                
                if (cursor >= buffer.size / 2) {
                    currentBuffer = null
                }
            }
        }
        
        if (finished != isFinished) {
            finished = isFinished
            onProcess?.invoke(isFinished)
        }
    }
    
    fun handleMessage(message: Map<String, Any>) {
        when (message["type"] as? String) {
            "buffer" -> {
                (message["buffer"] as? ByteArray)?.let { buffer ->
                    wasInterrupted = false
                    buffers.offer(buffer)
                }
            }
            "interrupt" -> {
                wasInterrupted = true
            }
            "clearInterrupted" -> {
                if (wasInterrupted) {
                    wasInterrupted = false
                    buffers.clear()
                    currentBuffer = null
                }
            }
        }
    }
}

// MARK: - Connection

class Connection private constructor(
    private val webSocket: WebSocket,
    val conversationId: String,
    val sampleRate: Int
) {
    companion object {
        suspend fun create(config: SessionConfig): Connection = withContext(Dispatchers.IO) {
            val origin = System.getenv("ELEVENLABS_CONVAI_SERVER_ORIGIN") ?: DEFAULT_API_ORIGIN
            val pathname = System.getenv("ELEVENLABS_CONVAI_SERVER_PATHNAME") ?: DEFAULT_API_PATHNAME
            
            val urlString = when {
                config.signedUrl != null -> config.signedUrl
                config.agentId != null -> "$origin$pathname${config.agentId}"
                else -> throw ElevenLabsError.InvalidConfiguration
            }
            
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(urlString)
                .build()
            
            val connectionResult = CompletableDeferred<Connection>()
            
            val webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // Send initialization event
                    val initEvent = buildJsonObject {
                        put("type", "conversation_initiation_client_data")
                        
                        config.overrides?.let { overrides ->
                            put("conversation_config_override", JsonObject(mapOf(
                                // Convert overrides to JSON format
                            )))
                        }
                        
                        config.customLlmExtraBody?.let { customBody ->
                            put("custom_llm_extra_body", JsonObject(customBody.mapValues { 
                                // Convert LlmExtraBodyValue to JsonElement
                                JsonPrimitive("placeholder") // Simplified for brevity
                            }))
                        }
                        
                        config.dynamicVariables?.let { dynamicVars ->
                            put("dynamic_variables", JsonObject(dynamicVars.mapValues {
                                // Convert DynamicVariableValue to JsonElement
                                JsonPrimitive("placeholder") // Simplified for brevity
                            }))
                        }
                    }
                    
                    webSocket.send(initEvent.toString())
                }
                
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = Json.parseToJsonElement(text).jsonObject
                        val type = json["type"]?.jsonPrimitive?.content
                        
                        if (type == "conversation_initiation_metadata") {
                            val metadata = json["conversation_initiation_metadata_event"]?.jsonObject
                            val conversationId = metadata?.get("conversation_id")?.jsonPrimitive?.content
                            val audioFormat = metadata?.get("agent_output_audio_format")?.jsonPrimitive?.content
                            
                            if (conversationId != null && audioFormat != null) {
                                val sampleRate = audioFormat.replace("pcm_", "").toIntOrNull() ?: 16000
                                connectionResult.complete(Connection(webSocket, conversationId, sampleRate))
                            } else {
                                connectionResult.completeExceptionally(ElevenLabsError.InvalidInitialMessageFormat)
                            }
                        }
                    } catch (e: Exception) {
                        connectionResult.completeExceptionally(e)
                    }
                }
                
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connectionResult.completeExceptionally(t)
                }
            })
            
            connectionResult.await()
        }
    }
    
    fun close() {
        webSocket.close(1000, "Session ended")
    }
    
    fun send(message: String) {
        webSocket.send(message)
    }
    
    fun setListener(listener: WebSocketListener) {
        // Note: OkHttp doesn't allow changing listeners after creation
        // This would need to be handled differently in a real implementation
    }
}

// MARK: - Audio Input

class AudioInput private constructor(
    private val context: Context,
    private val audioRecord: AudioRecord,
    private val sampleRate: Int
) {
    private var isRecording = AtomicBoolean(false)
    private var recordCallback: ((ByteArray, Float) -> Unit)? = null
    private val recordingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        fun create(context: Context, sampleRate: Int): AudioInput {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("RECORD_AUDIO permission not granted")
            }
            
            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            return AudioInput(context, audioRecord, sampleRate)
        }
    }
    
    fun setRecordCallback(callback: (ByteArray, Float) -> Unit) {
        this.recordCallback = callback
    }
    
    fun startRecording() {
        if (isRecording.compareAndSet(false, true)) {
            audioRecord.startRecording()
            
            recordingScope.launch {
                val buffer = ByteArray(BUFFER_SIZE * 2) // 16-bit samples
                
                while (isRecording.get()) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val audioData = buffer.copyOf(bytesRead)
                        val rms = calculateRMS(audioData)
                        recordCallback?.invoke(audioData, rms)
                    }
                }
            }
        }
    }
    
    fun stopRecording() {
        if (isRecording.compareAndSet(true, false)) {
            audioRecord.stop()
        }
    }
    
    fun close() {
        stopRecording()
        recordingScope.cancel()
        audioRecord.release()
    }
    
    private fun calculateRMS(audioData: ByteArray): Float {
        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until audioData.size / 2) {
            val sample = shortBuffer.getShort(i * 2).toFloat() / Short.MAX_VALUE
            sum += sample * sample
        }
        
        return sqrt(sum / (audioData.size / 2)).toFloat()
    }
}

// MARK: - Audio Output

class AudioOutput private constructor(
    private val audioTrack: AudioTrack,
    private val sampleRate: Int
) {
    private val audioBuffers = ConcurrentLinkedQueue<ByteArray>()
    private val playbackScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isPlaying = AtomicBoolean(false)
    private var volume = AtomicReference(1.0f)
    
    companion object {
        fun create(sampleRate: Int): AudioOutput {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            val audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
            
            return AudioOutput(audioTrack, sampleRate)
        }
    }
    
    fun startPlaying() {
        if (isPlaying.compareAndSet(false, true)) {
            audioTrack.play()
            
            playbackScope.launch {
                while (isPlaying.get()) {
                    val buffer = audioBuffers.poll()
                    if (buffer != null) {
                        audioTrack.write(buffer, 0, buffer.size)
                    } else {
                        delay(10) // Small delay to prevent busy waiting
                    }
                }
            }
        }
    }
    
    fun stopPlaying() {
        if (isPlaying.compareAndSet(true, false)) {
            audioTrack.stop()
        }
    }
    
    fun addAudioBuffer(buffer: ByteArray) {
        audioBuffers.offer(buffer)
    }
    
    fun clearBuffers() {
        audioBuffers.clear()
    }
    
    fun setVolume(volume: Float) {
        this.volume.set(volume)
        audioTrack.setStereoVolume(volume, volume)
    }
    
    fun getVolume(): Float = volume.get()
    
    fun close() {
        stopPlaying()
        playbackScope.cancel()
        audioTrack.release()
    }
}

// MARK: - Callbacks

data class Callbacks(
    val onConnect: (String) -> Unit = { },
    val onDisconnect: () -> Unit = { },
    val onMessage: (String, Role) -> Unit = { _, _ -> },
    val onError: (String, Any?) -> Unit = { _, _ -> },
    val onStatusChange: (Status) -> Unit = { },
    val onModeChange: (Mode) -> Unit = { },
    val onVolumeUpdate: (Float) -> Unit = { },
    val onMessageCorrection: (String, String, Role) -> Unit = { _, _, _ -> }
)

// MARK: - Conversation

class Conversation private constructor(
    private val context: Context,
    private val connection: Connection,
    private val audioInput: AudioInput,
    private val audioOutput: AudioOutput,
    private val callbacks: Callbacks,
    private val clientTools: ClientTools?
) {
    private val _mode = MutableStateFlow(Mode.LISTENING)
    private val _status = MutableStateFlow(Status.CONNECTING)
    private val _volume = MutableStateFlow(1.0f)
    private val lastInterruptTimestamp = AtomicInteger(0)
    private val isProcessingInput = AtomicBoolean(true)
    
    val mode: StateFlow<Mode> = _mode
    val status: StateFlow<Status> = _status
    val volume: StateFlow<Float> = _volume
    
    private val conversationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val messageHandler = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        suspend fun startSession(
            context: Context,
            config: SessionConfig,
            callbacks: Callbacks = Callbacks(),
            clientTools: ClientTools? = null
        ): Conversation = withContext(Dispatchers.IO) {
            
            // Configure audio session
            configureAudioSession(context)
            
            // Create connection
            val connection = Connection.create(config)
            
            // Create audio components
            val audioInput = AudioInput.create(context, INPUT_SAMPLE_RATE)
            val audioOutput = AudioOutput.create(connection.sampleRate)
            
            // Create conversation instance
            val conversation = Conversation(
                context, connection, audioInput, audioOutput, callbacks, clientTools
            )
            
            // Setup and start
            conversation.setupWebSocket()
            conversation.setupAudioProcessing()
            conversation.startAudioSession()
            
            conversation
        }
    }
    
    private fun setupWebSocket() {
        callbacks.onConnect(connection.conversationId)
        updateStatus(Status.CONNECTED)
        
        // Setup WebSocket message handling
        connection.setListener(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleWebSocketMessage(text)
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error", t)
                callbacks.onError("WebSocket error", t)
                endSession()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                callbacks.onDisconnect()
                updateStatus(Status.DISCONNECTED)
            }
        })
    }
    
    private fun setupAudioProcessing() {
        audioInput.setRecordCallback { buffer, rms ->
            if (isProcessingInput.get()) {
                // Convert to base64 and send
                val base64String = arrayBufferToBase64(buffer)
                val message = buildJsonObject {
                    put("type", "user_audio_chunk")
                    put("user_audio_chunk", base64String)
                }
                connection.send(message.toString())
                
                // Update volume
                callbacks.onVolumeUpdate(rms)
            }
        }
    }
    
    private fun startAudioSession() {
        audioInput.startRecording()
        audioOutput.startPlaying()
    }
    
    private fun handleWebSocketMessage(text: String) {
        messageHandler.launch {
            try {
                val json = Json.parseToJsonElement(text).jsonObject
                val type = json["type"]?.jsonPrimitive?.content
                
                when (type) {
                    "client_tool_call" -> handleClientToolCall(json)
                    "interruption" -> handleInterruptionEvent(json)
                    "agent_response" -> handleAgentResponseEvent(json)
                    "agent_response_correction" -> handleAgentResponseCorrectionEvent(json)
                    "user_transcript" -> handleUserTranscriptEvent(json)
                    "audio" -> handleAudioEvent(json)
                    "ping" -> handlePingEvent(json)
                    else -> {
                        if (type !in listOf("internal_tentative_agent_response", "internal_vad_score", "internal_turn_probability")) {
                            callbacks.onError("Unknown message type", json)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling WebSocket message", e)
                callbacks.onError("Message handling error", e)
            }
        }
    }
    
    private suspend fun handleClientToolCall(json: JsonObject) {
        try {
            val toolCall = json["client_tool_call"]?.jsonObject
            val toolName = toolCall?.get("tool_name")?.jsonPrimitive?.content
            val toolCallId = toolCall?.get("tool_call_id")?.jsonPrimitive?.content
            val parameters = toolCall?.get("parameters")?.jsonObject
            
            if (toolName != null && toolCallId != null && parameters != null) {
                val result = clientTools?.handle(toolName, parameters.toMap()) ?: ""
                
                val response = buildJsonObject {
                    put("type", "client_tool_result")
                    put("tool_call_id", toolCallId)
                    put("result", result)
                    put("is_error", false)
                }
                connection.send(response.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client tool call", e)
        }
    }
    
    private fun handleInterruptionEvent(json: JsonObject) {
        val event = json["interruption_event"]?.jsonObject
        val eventId = event?.get("event_id")?.jsonPrimitive?.int
        
        if (eventId != null) {
            lastInterruptTimestamp.set(eventId)
            fadeOutAudio()
            clearAudioBuffers()
            stopPlayback()
        }
    }
    
    private fun handleAgentResponseEvent(json: JsonObject) {
        val event = json["agent_response_event"]?.jsonObject
        val response = event?.get("agent_response")?.jsonPrimitive?.content
        
        if (response != null) {
            callbacks.onMessage(response, Role.AI)
        }
    }
    
    private fun handleAgentResponseCorrectionEvent(json: JsonObject) {
        val event = json["agent_response_correction_event"]?.jsonObject
        val original = event?.get("original_agent_response")?.jsonPrimitive?.content
        val corrected = event?.get("corrected_agent_response")?.jsonPrimitive?.content
        
        if (original != null && corrected != null) {
            callbacks.onMessageCorrection(original, corrected, Role.AI)
        }
    }
    
    private fun handleUserTranscriptEvent(json: JsonObject) {
        val event = json["user_transcription_event"]?.jsonObject
        val transcript = event?.get("user_transcript")?.jsonPrimitive?.content
        
        if (transcript != null) {
            callbacks.onMessage(transcript, Role.USER)
        }
    }
    
    private fun handleAudioEvent(json: JsonObject) {
        val event = json["audio_event"]?.jsonObject
        val audioBase64 = event?.get("audio_base_64")?.jsonPrimitive?.content
        val eventId = event?.get("event_id")?.jsonPrimitive?.int
        
        if (audioBase64 != null && eventId != null && lastInterruptTimestamp.get() <= eventId) {
            addAudioBase64Chunk(audioBase64)
            updateMode(Mode.SPEAKING)
        }
    }
    
    private fun handlePingEvent(json: JsonObject) {
        val event = json["ping_event"]?.jsonObject
        val eventId = event?.get("event_id")?.jsonPrimitive?.int
        
        if (eventId != null) {
            val response = buildJsonObject {
                put("type", "pong")
                put("event_id", eventId)
            }
            connection.send(response.toString())
        }
    }
    
    private fun addAudioBase64Chunk(chunk: String) {
        val data = base64ToArrayBuffer(chunk)
        if (data != null) {
            audioOutput.addAudioBuffer(data)
        }
    }
    
    private fun fadeOutAudio() {
        updateMode(Mode.LISTENING)
        
        conversationScope.launch {
            val originalVolume = audioOutput.getVolume()
            audioOutput.setVolume(0.0001f)
            
            delay(FADE_OUT_DURATION)
            audioOutput.setVolume(originalVolume)
            clearAudioBuffers()
        }
    }
    
    private fun updateMode(newMode: Mode) {
        if (_mode.value != newMode) {
            _mode.value = newMode
            callbacks.onModeChange(newMode)
        }
    }
    
    private fun updateStatus(newStatus: Status) {
        if (_status.value != newStatus) {
            _status.value = newStatus
            callbacks.onStatusChange(newStatus)
        }
    }
    
    private fun clearAudioBuffers() {
        audioOutput.clearBuffers()
    }
    
    private fun stopPlayback() {
        audioOutput.stopPlaying()
    }
    
    // Public methods
    
    fun sendContextualUpdate(text: String) {
        val event = buildJsonObject {
            put("type", "contextual_update")
            put("text", text)
        }
        connection.send(event.toString())
    }
    
    fun sendUserMessage(text: String? = null) {
        val event = buildJsonObject {
            put("type", "user_message")
            if (text != null) put("text", text)
        }
        connection.send(event.toString())
    }
    
    fun sendUserActivity() {
        val event = buildJsonObject {
            put("type", "user_activity")
        }
        connection.send(event.toString())
    }
    
    fun endSession() {
        if (_status.value == Status.CONNECTED) {
            updateStatus(Status.DISCONNECTING)
            connection.close()
            audioInput.close()
            audioOutput.close()
            conversationScope.cancel()
            messageHandler.cancel()
            updateStatus(Status.DISCONNECTED)
        }
    }
    
    fun getId(): String = connection.conversationId
    
    fun getInputVolume(): Float = 0f // Placeholder
    
    fun getOutputVolume(): Float = audioOutput.getVolume()
    
    fun startRecording() {
        isProcessingInput.set(true)
    }
    
    fun stopRecording() {
        isProcessingInput.set(false)
    }
    
    var conversationVolume: Float
        get() = _volume.value
        set(value) {
            _volume.value = value
            audioOutput.setVolume(value)
        }
}

// MARK: - Errors

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

// MARK: - Extension Functions

/**
 * Convert JsonObject to Map
 */
fun JsonObject.toMap(): Map<String, Any> {
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