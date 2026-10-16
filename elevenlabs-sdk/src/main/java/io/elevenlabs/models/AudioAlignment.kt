package io.elevenlabs.models

/**
 * Character-level timing of a chunk of the agent's speech.
 *
 * The server sends one alignment per audio chunk, usually ahead of playback. Times are relative to the
 * start of the chunk; chunks of the same response play back to back.
 *
 * @property eventId Event id of the agent response the chunk belongs to (matches `onAgentResponseEvent`)
 * @property chars The characters spoken in the chunk, in order
 * @property charStartTimesMs Start time of each character, in milliseconds from the start of the chunk
 * @property charDurationsMs Duration of each character, in milliseconds
 */
data class AudioAlignment(
    val eventId: Int?,
    val chars: List<String>,
    val charStartTimesMs: List<Int>,
    val charDurationsMs: List<Int>,
)
