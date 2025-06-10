package com.elevenlabs.sdk

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import io.livekit.android.room.*
import io.livekit.android.room.track.*
import io.livekit.android.room.participant.*
import io.livekit.android.util.flow
import kotlinx.serialization.json.*
import android.util.Log

class LiveKitConversation(
    private val context: Context,
    private val token: String,
    private val config: ElevenLabsInterface.SessionConfig,
    private val callbacks: Callbacks = Callbacks(),
    private val clientTools: ElevenLabsInterface.ClientTools? = null
) : ElevenLabsInterface.Conversation {
    
    companion object {
        private const val TAG = "LiveKitConversation"
        private const val LIVEKIT_URL = "wss://livekit.elevenlabs.io"
    }
    
    private val room = Room()
    private val conversationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var dataChannelManager: DataChannelManager? = null
    private var audioManager: AudioManager? = null
    
    // State flows
    private val _mode = MutableStateFlow(ElevenLabsInterface.Mode.LISTENING)
    private val _status = MutableStateFlow(ElevenLabsInterface.Status.CONNECTING)
    private val _volume = MutableStateFlow(1.0f)
    private val _messages = MutableStateFlow(Pair("", ElevenLabsInterface.Role.USER))
    private val _errors = MutableStateFlow(Pair("", null as Any?))
    
    override val mode: Flow<ElevenLabsInterface.Mode> = _mode.asStateFlow()
    override val status: Flow<ElevenLabsInterface.Status> = _status.asStateFlow()
    override val volume: Flow<Float> = _volume.asStateFlow()
    override val messages: Flow<Pair<String, ElevenLabsInterface.Role>> = _messages.asStateFlow()
    override val errors: Flow<Pair<String, Any?>> = _errors.asStateFlow()
    
    override var conversationVolume: Float
        get() = _volume.value
        set(value) {
            _volume.value = value.coerceIn(0f, 1f)
            audioManager?.setVolume(value)
        }
    
    suspend fun connect() {
        try {
            updateStatus(ElevenLabsInterface.Status.CONNECTING)
            
            // Setup event handlers
            setupRoomEventHandlers()
            
            // Connect to LiveKit room
            room.connect(LIVEKIT_URL, token)
            
            // Initialize managers
            dataChannelManager = DataChannelManager(room, callbacks, clientTools)
            audioManager = AudioManager(context, room, callbacks)
            
            // Initialize audio
            audioManager?.initialize()
            
            // Send conversation initiation
            dataChannelManager?.sendConversationInitiation(config)
            
        } catch (error: Exception) {
            Log.e(TAG, "Failed to connect", error)
            updateStatus(ElevenLabsInterface.Status.DISCONNECTED)
            _errors.value = Pair("Failed to connect to conversation", error)
            throw error
        }
    }
    
    private fun setupRoomEventHandlers() {
        conversationScope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        updateStatus(ElevenLabsInterface.Status.CONNECTED)
                    }
                    is RoomEvent.Disconnected -> {
                        updateStatus(ElevenLabsInterface.Status.DISCONNECTED)
                        callbacks.onDisconnect()
                    }
                    is RoomEvent.Reconnecting -> {
                        callbacks.onReconnecting?.invoke()
                    }
                    is RoomEvent.Reconnected -> {
                        callbacks.onReconnected?.invoke()
                    }
                    is RoomEvent.ConnectionQualityChanged -> {
                        callbacks.onConnectionQualityChanged?.invoke(event.quality)
                    }
                    else -> {
                        // Handle other events as needed
                    }
                }
            }
        }
    }
    
    override fun sendContextualUpdate(text: String) {
        dataChannelManager?.sendMessage(buildJsonObject {
            put("type", "contextual_update")
            put("text", text)
        })
    }
    
    override fun sendUserMessage(text: String?) {
        val message = buildJsonObject {
            put("type", "user_message")
            text?.let { put("text", it) }
        }
        dataChannelManager?.sendMessage(message)
    }
    
    override fun sendUserActivity() {
        dataChannelManager?.sendMessage(buildJsonObject {
            put("type", "user_activity")
        })
    }
    
    override fun endSession() {
        if (_status.value == ElevenLabsInterface.Status.CONNECTED) {
            updateStatus(ElevenLabsInterface.Status.DISCONNECTING)
            audioManager?.close()
            room.disconnect()
            conversationScope.cancel()
            updateStatus(ElevenLabsInterface.Status.DISCONNECTED)
        }
    }
    
    override fun getId(): String = room.name ?: "unknown"
    
    override fun startRecording() {
        audioManager?.setMicrophoneEnabled(true)
    }
    
    override fun stopRecording() {
        audioManager?.setMicrophoneEnabled(false)
    }
    
    private fun updateStatus(newStatus: ElevenLabsInterface.Status) {
        if (_status.value != newStatus) {
            _status.value = newStatus
            callbacks.onStatusChange(newStatus)
        }
    }
    
    private fun updateMode(newMode: ElevenLabsInterface.Mode) {
        if (_mode.value != newMode) {
            _mode.value = newMode
            callbacks.onModeChange(newMode)
        }
    }
    
    internal fun updateMessages(message: String, role: ElevenLabsInterface.Role) {
        _messages.value = Pair(message, role)
    }
    
    internal fun updateErrors(error: String, details: Any?) {
        _errors.value = Pair(error, details)
    }
    
    internal fun getModeUpdater() = ::updateMode
} 