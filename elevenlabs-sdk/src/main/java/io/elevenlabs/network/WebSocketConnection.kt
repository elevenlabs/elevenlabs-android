package io.elevenlabs.network

import android.util.Log
import io.elevenlabs.ConversationConfig
import io.elevenlabs.ConversationOverridesBuilder
import io.elevenlabs.models.DisconnectionDetails
import io.elevenlabs.models.toConversationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * WebSocket transport for ElevenLabs Conversational AI.
 *
 * Used for text-only sessions where LiveKit/WebRTC would tear down the room
 * after a few seconds because no audio/video tracks are published. Speaks the
 * same JSON event protocol as the existing data channel; serialization and
 * parsing are reused from [ConversationEventParser].
 */
class WebSocketConnection(
    private val client: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BaseConnection() {

    private val _connectionState = MutableStateFlow(ConnectionState.IDLE)
    override val connectionState: ConnectionState
        get() = _connectionState.value

    private var messageListener: ((String) -> Unit)? = null
    private var connectionStateListener: ((ConnectionState) -> Unit)? = null

    private var webSocket: WebSocket? = null
    private var latestConfig: ConversationConfig? = null

    private val messageChannel = Channel<String>(Channel.UNLIMITED)
    private var messageJob: Job? = null

    @Volatile
    private var disconnectCallbackInvoked = false

    @Volatile
    private var conversationIdNotified = false

    override suspend fun connect(token: String, serverUrl: String, config: ConversationConfig) {
        if (connectionState != ConnectionState.IDLE && connectionState != ConnectionState.DISCONNECTED) {
            throw IllegalStateException("Already connected or connecting")
        }

        try {
            updateConnectionState(ConnectionState.CONNECTING)
            latestConfig = config
            disconnectCallbackInvoked = false
            conversationIdNotified = false

            val url = buildWebSocketUrl(serverUrl, token, config.agentId)
            Log.d("WebSocketConnection", "Connecting to $url")

            val request = Request.Builder().url(url).build()
            webSocket = client.newWebSocket(request, listener)

            startMessageProcessing()
        } catch (e: Exception) {
            invokeOnDisconnect(DisconnectionDetails.Error(e))
            updateConnectionState(ConnectionState.ERROR)
            throw RuntimeException("Failed to open WebSocket", e)
        }
    }

    override fun disconnect(details: DisconnectionDetails?) {
        var disconnectDetails = details ?: DisconnectionDetails.User

        try {
            messageJob?.cancel()
            messageJob = null

            webSocket?.close(NORMAL_CLOSURE, "client closed")
            webSocket = null

            disconnectCallbackInvoked = false
            updateConnectionState(ConnectionState.IDLE)
            Log.d("WebSocketConnection", "Disconnected and reset to IDLE state")
        } catch (e: Exception) {
            Log.d("WebSocketConnection", "Error during disconnect: ${e.message}")
            disconnectDetails = DisconnectionDetails.Error(e)
            updateConnectionState(ConnectionState.ERROR)
        } finally {
            invokeOnDisconnect(disconnectDetails)
        }
    }

    override fun sendMessage(message: Any) {
        if (!connectionState.isActive) {
            throw IllegalStateException("Not connected")
        }
        val ws = webSocket ?: throw IllegalStateException("WebSocket not initialized")

        val text = when (message) {
            is String -> message
            else -> ConversationEventParser.serializeOutgoingEvent(message as OutgoingEvent)
        }

        val sent = ws.send(text)
        if (!sent) {
            Log.d("WebSocketConnection", "WebSocket.send returned false (queue full or closed)")
        }
    }

    override fun setOnMessageListener(listener: (String) -> Unit) {
        this.messageListener = listener
    }

    override fun setOnConnectionStateListener(listener: (ConnectionState) -> Unit) {
        this.connectionStateListener = listener
    }

    fun cleanup() {
        disconnect()
        scope.cancel()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("WebSocketConnection", "WebSocket opened")
            updateConnectionState(ConnectionState.CONNECTED)

            try {
                val cfg = latestConfig ?: return
                val payload = ConversationOverridesBuilder.constructOverrides(cfg).toString()
                webSocket.send(payload)
            } catch (e: Exception) {
                Log.d("WebSocketConnection", "Failed to send initiation payload: ${e.message}")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleIncomingText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            handleIncomingText(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("WebSocketConnection", "WebSocket closing: code=$code reason=$reason")
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d("WebSocketConnection", "WebSocket closed: code=$code reason=$reason")
            // Normal closure (1000) is treated as a user-initiated end. Anything else
            // (going away, abnormal, server unreachable, etc.) is reported as an error
            // because we cannot reliably tell from the close code alone whether the
            // agent ended the conversation gracefully.
            val details = if (code == NORMAL_CLOSURE) {
                DisconnectionDetails.User
            } else {
                DisconnectionDetails.Error(RuntimeException("WebSocket closed: $code $reason"))
            }
            updateConnectionState(ConnectionState.DISCONNECTED)
            invokeOnDisconnect(details)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("WebSocketConnection", "WebSocket failure: ${t.message}", t)
            updateConnectionState(ConnectionState.ERROR)
            val cause = if (t is Exception) t else RuntimeException(t)
            invokeOnDisconnect(DisconnectionDetails.Error(cause))
        }
    }

    private fun handleIncomingText(text: String) {
        messageChannel.trySend(text)

        try {
            latestConfig?.onMessage?.invoke("ai", text)
        } catch (t: Throwable) {
            Log.d("WebSocketConnection", "onMessage callback threw: ${t.message}")
        }

        if (!conversationIdNotified) {
            tryNotifyConversationId(text)
        }
    }

    private fun tryNotifyConversationId(text: String) {
        try {
            val obj = JSONObject(text)
            if (obj.optString("type") != "conversation_initiation_metadata") return

            val meta = obj.optJSONObject("conversation_initiation_metadata") ?: obj
            val id = meta.optString("conversation_id", "")
            if (id.isEmpty()) return

            conversationIdNotified = true
            try {
                latestConfig?.onConnect?.invoke(id)
            } catch (t: Throwable) {
                Log.d("WebSocketConnection", "onConnect callback threw: ${t.message}")
            }
        } catch (_: Exception) {
            // Parser will surface malformed payloads downstream.
        }
    }

    private fun startMessageProcessing() {
        messageJob = scope.launch {
            messageChannel.consumeAsFlow().collect { msg ->
                messageListener?.invoke(msg)
            }
        }
    }

    private fun updateConnectionState(newState: ConnectionState) {
        if (_connectionState.value != newState) {
            _connectionState.value = newState
            connectionStateListener?.invoke(newState)
            try {
                latestConfig?.onStatusChange?.invoke(newState.toConversationStatus())
            } catch (_: Throwable) {
            }
        }
    }

    private fun invokeOnDisconnect(details: DisconnectionDetails) {
        if (disconnectCallbackInvoked) return
        disconnectCallbackInvoked = true
        try {
            latestConfig?.onDisconnect?.invoke(details)
        } catch (t: Throwable) {
            Log.d("WebSocketConnection", "onDisconnect callback threw: ${t.message}")
        }
    }

    companion object {
        private const val WS_PATH = "/v1/convai/conversation"
        private const val NORMAL_CLOSURE = 1000

        internal fun buildWebSocketUrl(serverUrl: String, token: String, agentId: String?): String {
            val base = serverUrl.trimEnd('/')
            val params = mutableListOf<String>()
            agentId?.takeIf { it.isNotBlank() }?.let { params += "agent_id=$it" }
            if (token.isNotBlank()) params += "conversation_signature=$token"
            require(params.isNotEmpty()) {
                "WebSocket connection requires either agentId or a conversation signature"
            }
            return "$base$WS_PATH?${params.joinToString("&")}"
        }
    }
}
