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
     * @param message Description of the error that occurred
     */
    data class Error(val exception: Exception) : DisconnectionDetails()

    /**
     * Disconnection initiated by the agent
     */
    data object Agent : DisconnectionDetails()

    /**
     * Disconnection initiated by the user
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
}

