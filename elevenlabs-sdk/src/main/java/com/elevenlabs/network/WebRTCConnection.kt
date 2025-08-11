package com.elevenlabs.network

import android.content.Context
import android.util.Log
import com.elevenlabs.ConversationConfig
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.RemoteAudioTrack
import io.livekit.android.room.track.Track
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import com.elevenlabs.ConversationOverridesBuilder


/**
 * WebRTC implementation using LiveKit for real-time communication
 *
 * This class manages room-based architecture for connections, data channels
 * for event messaging, audio tracks for voice communication, and automatic
 * reconnection handling.
 */
class WebRTCConnection(
    private val context: Context,
    private val room: Room,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : BaseConnection() {

    private var localParticipant: LocalParticipant? = null
    private var latestConfig: ConversationConfig? = null

    private var _connectionState = MutableStateFlow(ConnectionState.IDLE)
    override val connectionState: ConnectionState
        get() = _connectionState.value

    private var messageListener: ((String) -> Unit)? = null
    private var connectionStateListener: ((ConnectionState) -> Unit)? = null

    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    private var messageJob: Job? = null

    /**
     * Connect to LiveKit room using the provided token and server URL
     */
    override suspend fun connect(token: String, serverUrl: String, config: ConversationConfig) {
        if (connectionState != ConnectionState.IDLE && connectionState != ConnectionState.DISCONNECTED) {
            throw IllegalStateException("Already connected or connecting")
        }

        try {
            updateConnectionState(ConnectionState.CONNECTING)
            latestConfig = config

            // Use provided shared room
            localParticipant = room.localParticipant

            // Set up event handlers before connecting
            setupRoomEventHandlers()

            // Connect to the room
            Log.d("WebRTCConnection", "Connecting to LiveKit url: $serverUrl")
            room.connect(
                url = serverUrl,
                token = token
            )

            // Start message processing
            startMessageProcessing()

            updateConnectionState(ConnectionState.CONNECTED)
            Log.d("WebRTCConnection", "Connected. roomSid=${room.sid}, name=${room.name}")

            // Send initiation overrides payload after connect
            try {
                val payload = ConversationOverridesBuilder
                    .constructOverrides(config)
                    .toString()
                sendMessage(payload)
            } catch (e: Exception) {
                Log.d("WebRTCConnection", "failed to send overrides - ${e.message}")
            }

        } catch (e: Exception) {
            updateConnectionState(ConnectionState.ERROR)
            throw RuntimeException("Failed to connect to LiveKit room", e)
        }
    }

    /**
     * Disconnect from the room and clean up resources
     */
    override fun disconnect() {
        try {
            updateConnectionState(ConnectionState.DISCONNECTED)

            // Stop message processing
            messageJob?.cancel()
            messageJob = null

            // Disconnect from room
            room.disconnect()
            localParticipant = null

            updateConnectionState(ConnectionState.DISCONNECTED)

        } catch (e: Exception) {
            updateConnectionState(ConnectionState.ERROR)
        }
    }

    /**
     * Send a message through the data channel
     */
    override fun sendMessage(message: Any) {
        if (!isConnected) {
            throw IllegalStateException("Not connected")
        }

        val messageString = when (message) {
            is String -> message
            else -> ConversationEventParser.serializeOutgoingEvent(message as OutgoingEvent)
        }

        val payload = messageString.toByteArray()
        scope.launch {
            try {
                val result = room.localParticipant.publishData(payload)
                if (result.isFailure) {
                    Log.d("WebRTCConnection", "publishData failed - ${result.exceptionOrNull()?.message}")
                }
            } catch (e: Exception) {
                Log.e("WebRTCConnection", "publishData error - ${e.message}")
            }
        }
    }

    /**
     * Set listener for incoming messages
     */
    override fun setOnMessageListener(listener: (String) -> Unit) {
        this.messageListener = listener
    }

    /**
     * Set listener for connection state changes
     */
    override fun setOnConnectionStateListener(listener: (ConnectionState) -> Unit) {
        this.connectionStateListener = listener
    }

    /**
     * Set up LiveKit room event handlers
     */
    private fun setupRoomEventHandlers() {
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Connected -> {
                        Log.d("WebRTCConnection", "LiveKit room connected (sid=${room.sid})")
                        // invoke user callback if provided with extracted conversation id
                        try {
                            val roomName = room.name ?: ""
                            val match = Regex("conv_[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE).find(roomName)
                            val conversationId = match?.value ?: roomName
                            latestConfig?.onConnect?.invoke(conversationId)
                        } catch (t: Throwable) {
                            Log.d("WebRTCConnection", "onConnect callback threw: ${t.message}")
                        }
                    }

                    is RoomEvent.Disconnected -> {
                        Log.d("WebRTCConnection", "LiveKit room disconnected: ${event.reason}")
                        if (connectionState == ConnectionState.CONNECTED) {
                            updateConnectionState(ConnectionState.DISCONNECTED)
                        }
                    }

                    is RoomEvent.Reconnecting -> {
                        Log.d("WebRTCConnection", "LiveKit room reconnecting")
                        updateConnectionState(ConnectionState.RECONNECTING)
                    }

                    is RoomEvent.Reconnected -> {
                        Log.d("WebRTCConnection", "LiveKit room reconnected")
                        updateConnectionState(ConnectionState.CONNECTED)
                    }

                    is RoomEvent.ParticipantConnected -> {
                        Log.d("WebRTCConnection", "Participant connected: ${event.participant.sid}")
                        handleParticipantConnected(event.participant)
                    }

                    is RoomEvent.ParticipantDisconnected -> {
                        Log.d("WebRTCConnection", "Participant disconnected: ${event.participant.sid}")
                        handleParticipantDisconnected(event.participant)
                    }

                    is RoomEvent.TrackSubscribed -> {
                        Log.d("WebRTCConnection", "Audio track subscribed from ${event.participant.sid}")
                        handleTrackSubscribed(event.track, event.participant)
                    }

                    is RoomEvent.DataReceived -> {
                        event.data?.let { handleDataReceived(it, event.participant) }
                    }

                    else -> {
                        // Handle other events as needed
                    }
                }
            }
        }
    }

    /**
     * Start processing incoming messages
     */
    private fun startMessageProcessing() {
        messageJob = scope.launch {
            messageChannel.consumeAsFlow().collect { message ->
                messageListener?.invoke(message)
            }
        }
    }

    /**
     * Handle participant connected event
     */
    private fun handleParticipantConnected(participant: RemoteParticipant) {
        Log.d("WebRTCConnection", "Participant connected: ${participant.sid}")
        // Set up participant-specific handling if needed
    }

    /**
     * Handle participant disconnected event
     */
    private fun handleParticipantDisconnected(participant: RemoteParticipant) {
        Log.d("WebRTCConnection", "Participant disconnected: ${participant.sid}")
        // Clean up participant-specific resources if needed
    }

    /**
     * Handle track subscribed event
     */
    private fun handleTrackSubscribed(track: Track, participant: Participant) {
        when (track) {
            is RemoteAudioTrack -> {
                Log.d("WebRTCConnection", "Audio track subscribed from ${participant.sid}")
                // Audio tracks are automatically played by LiveKit
            }
            else -> {
                Log.d("WebRTCConnection", "Other track type subscribed: ${track.javaClass.simpleName}")
            }
        }
    }

    /**
     * Handle data received event
     */
    private fun handleDataReceived(data: ByteArray, participant: Participant?) {
        try {
            val message = String(data)
            // Send message to processing queue
            messageChannel.trySend(message)
            // Invoke user onMessage callback with source classification
            try {
                val source = when (participant) {
                    is RemoteParticipant -> "ai"
                    is LocalParticipant -> "user"
                    else -> "ai"
                }
                latestConfig?.onMessage?.invoke(source, message)

                // Toggle mode based on source
                val mode = if (participant?.isSpeaking == true) "speaking" else "listening"
                latestConfig?.onModeChange?.invoke(mode)
            } catch (t: Throwable) {
                Log.d("WebRTCConnection", "onMessage callback threw: ${t.message}")
            }
        } catch (e: Exception) {
            Log.d("WebRTCConnection", "Failed to process received data: ${e.message}")
        }
    }

    /**
     * Update connection state and notify listeners
     */
    private fun updateConnectionState(newState: ConnectionState) {
        if (_connectionState.value != newState) {
            _connectionState.value = newState
            connectionStateListener?.invoke(newState)
        }
    }

    /**
     * Get local audio track for microphone input
     * TODO: Implement proper audio track access when LiveKit API is clarified
     */
    fun getLocalAudioTrack(): LocalAudioTrack? {
        return null // Placeholder implementation
    }

    /**
     * Get remote audio tracks for agent speech
     * TODO: Implement proper remote audio track access when LiveKit API is clarified
     */
    fun getRemoteAudioTracks(): List<RemoteAudioTrack> {
        return emptyList() // Placeholder implementation
    }

    /**
     * Clean up resources when the connection is no longer needed
     */
    fun cleanup() {
        disconnect()
        scope.cancel()
    }
}