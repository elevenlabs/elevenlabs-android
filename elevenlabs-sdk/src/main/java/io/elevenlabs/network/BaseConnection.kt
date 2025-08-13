package io.elevenlabs.network

import io.elevenlabs.ConversationConfig

/**
 * Abstract base class for network connections
 *
 * This provides a common interface for different transport types (WebRTC, WebSocket)
 * while supporting event-driven architecture with observable state.
 */
abstract class BaseConnection {

    /**
     * Establish a connection using the provided token and server URL
     *
     * @param token Authentication token for the connection
     * @param serverUrl URL of the server to connect to
     * @param config Conversation configuration
     * @throws RuntimeException if connection fails
     */
    abstract suspend fun connect(token: String, serverUrl: String, config: ConversationConfig)

    /**
     * Disconnect from the server and clean up resources
     */
    abstract fun disconnect()

    /**
     * Send a message through the connection
     *
     * @param message The message to send (will be serialized as needed)
     * @throws IllegalStateException if not connected
     */
    abstract fun sendMessage(message: Any)

    /**
     * Set a listener for incoming messages
     *
     * @param listener Callback that receives incoming message strings
     */
    abstract fun setOnMessageListener(listener: (String) -> Unit)

    /**
     * Set a listener for connection state changes
     *
     * @param listener Callback that receives connection state updates
     */
    abstract fun setOnConnectionStateListener(listener: (ConnectionState) -> Unit)

    /**
     * Get the current connection state
     */
    abstract val connectionState: ConnectionState

    /**
     * Returns true if the connection is currently active
     */
    val isConnected: Boolean
        get() = connectionState.isConnected
}