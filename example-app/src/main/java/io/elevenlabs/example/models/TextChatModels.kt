package io.elevenlabs.example.models

/**
 * Lightweight chat message used by the text chat surface. Populated for both voice and text-only
 * sessions so the same transcript view can render either modality.
 */
data class TextChatMessage(
    val id: Long,
    val content: String,
    val isFromUser: Boolean,
)
