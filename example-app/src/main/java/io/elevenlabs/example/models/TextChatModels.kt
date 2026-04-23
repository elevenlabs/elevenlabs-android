package io.elevenlabs.example.models

import io.elevenlabs.models.ConversationStatus

/**
 * Lightweight chat message used by the text chat surface. Populated for both voice and text-only
 * sessions so the same transcript view can render either modality.
 */
data class TextChatMessage(
    val id: Long,
    val content: String,
    val isFromUser: Boolean,
)

/**
 * Snapshot of state consumed by the Compose chat screen. Built in the Activity from individual
 * ViewModel flows so the screen stays a pure render of immutable state.
 */
data class TextChatState(
    val status: ConversationStatus = ConversationStatus.DISCONNECTED,
    val messages: List<TextChatMessage> = emptyList(),
    val isAgentTyping: Boolean = false,
    val errorMessage: String? = null,
)
