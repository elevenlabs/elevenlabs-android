package io.elevenlabs.models

/**
 * Details about why a conversation session was disconnected.
 *
 * This sealed class represents the different reasons a conversation can end:
 * - [Error]: An error occurred during the conversation
 * - [Agent]: The agent ended the conversation
 * - [User]: The user ended the conversation
 */
sealed class DisconnectionDetails {
    /**
     * Disconnection due to an error
     * @param exception Description of the error that occurred
     * @param closeCode The close code the server sent, when the server closing the
     * WebSocket connection with a code other than a normal closure caused the error
     */
    data class Error(
        val exception: Exception,
        override val closeCode: Int? = null
    ) : DisconnectionDetails()

    /**
     * Disconnection initiated by the remote side rather than by a local call to
     * `disconnect()`/`endSession()`.
     *
     * For text-only (WebSocket) sessions this also covers cases where the SDK can't tell
     * *why* the remote side closed the connection, e.g. the agent gracefully ending the
     * conversation vs. the server enforcing an idle/inactivity timeout - both close the
     * socket normally without the client having asked for it, so both surface as [Agent].
     *
     * @param closeCode The close code the server sent when it closed the WebSocket
     * connection, or `null` when the session didn't end with the server closing it (the
     * agent's `end_call` tool, or a voice session)
     */
    data class Agent(override val closeCode: Int? = null) : DisconnectionDetails()

    /**
     * Disconnection initiated by the user, i.e. as a direct result of a local call to
     * `disconnect()`/`endSession()`. Never fired for a connection that timed out or was
     * closed by the remote side on its own.
     */
    data object User : DisconnectionDetails()

    /**
     * Get the reason as a string identifier
     */
    val reason: String
        get() = when (this) {
            is Error -> "error"
            is Agent -> "agent"
            is User -> "user"
        }

    /**
     * The close code the server sent when it closed the session's WebSocket connection,
     * e.g. `1000` for a normal closure. `null` when the session didn't end with the server
     * closing the WebSocket connection, including every voice (WebRTC) session.
     */
    open val closeCode: Int?
        get() = null
}
