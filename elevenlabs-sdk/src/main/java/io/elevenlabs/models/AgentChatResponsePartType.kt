package io.elevenlabs.models

/**
 * Type of agent chat response part for text streaming
 *
 * Used in text-only conversations to indicate the streaming state of the LLM response.
 */
enum class AgentChatResponsePartType {
    /**
     * Indicates the start of a new agent text response stream
     */
    START,

    /**
     * Contains a chunk of text from the ongoing agent response stream
     */
    DELTA,

    /**
     * Indicates the end of the agent text response stream
     */
    STOP;

    companion object {
        /**
         * Parse a string into an AgentChatResponsePartType
         *
         * @param value The string value to parse
         * @return The corresponding enum value, or null if invalid
         */
        fun fromString(value: String?): AgentChatResponsePartType? {
            return when (value?.lowercase()) {
                "start" -> START
                "delta" -> DELTA
                "stop" -> STOP
                else -> null
            }
        }
    }

    /**
     * Convert to lowercase string for serialization
     */
    fun toJsonString(): String = name.lowercase()
}
