package io.elevenlabs

import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * Represents an active conversation session with an ElevenLabs agent
 *
 * This class provides reactive access to conversation state and enables
 * real-time communication with AI agents through text and voice.
 *
 * The session follows an event-driven architecture with observable state
 * that can be used for UI binding in Android applications.
 */
interface ConversationSession {

    // Observable Properties for reactive UI updates

    /**
     * Current connection status of the conversation session
     */
    val status: StateFlow<ConversationStatus>

    /**
     * Current conversation mode (listening/speaking)
     */
    val mode: StateFlow<ConversationMode>

    // Message history is intentionally not tracked; rely on server transcripts

    /**
     * Whether the microphone is currently muted
     */
    val isMuted: StateFlow<Boolean>

    // Session Control Methods

    /**
     * Start the conversation session
     * This initiates the connection and begins listening for events
     */
    suspend fun start()

    /**
     * End the conversation session
     * This closes the connection and cleans up resources
     */
    suspend fun endSession()

    /**
     * Send a text message to the agent
     *
     * @param message The message content to send
     */
    fun sendUserMessage(message: String)

    /**
     * Send feedback for the last agent response
     *
     * @param isPositive true for positive feedback, false for negative
     */
    fun sendFeedback(isPositive: Boolean)

    /**
     * Send contextual information without triggering a response
     *
     * @param update Context information to send to the agent
     */
    fun sendContextualUpdate(update: String)

    /**
     * Get the current conversation ID (e.g., conv_...)
     * Returns null until the session is connected and the ID is known.
     */
    fun getId(): String?

    /**
     * Notify the agent that the user is actively typing
     */
    fun sendUserActivity()

    // Audio Control Methods

    /**
     * Toggle microphone mute state
     */
    suspend fun toggleMute()

    /**
     * Set microphone mute state
     *
     * @param muted true to mute, false to unmute
     */
    suspend fun setMicMuted(muted: Boolean)

    // Tool Integration

    /**
     * Register a client tool that can be called by the agent
     *
     * @param name Tool identifier that the agent will use
     * @param tool Implementation of the tool functionality
     */
    fun registerTool(name: String, tool: ClientTool)

    /**
     * Unregister a previously registered client tool
     *
     * @param name Tool identifier to remove
     */
    fun unregisterTool(name: String)
}