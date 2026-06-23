package io.elevenlabs.example.models

/**
 * Lightweight chat message used by the text chat surface, mapped from the SDK's reconciled
 * transcript. [id] is the SDK message id, stable across partial/final updates so it works as a
 * list key.
 */
data class TextChatMessage(
    val id: String,
    val content: String,
    val isFromUser: Boolean,
)
