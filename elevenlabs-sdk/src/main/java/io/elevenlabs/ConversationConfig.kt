package io.elevenlabs

import io.elevenlabs.models.ConversationEvent.ClientToolCall

/**
 * Configuration class for conversation sessions
 *
 * This class defines the parameters needed to start a conversation with an ElevenLabs agent.
 * It supports two distinct authentication modes:
 *
 * **Public Agents** (no authentication required):
 * - Use `agentId` only
 * - SDK automatically generates conversation token
 * - No API key needed
 *
 * **Private Agents** (backend authentication required):
 * - Use `conversationToken` only (generated on your backend with API key)
 * - API key should never be in client-side applications
 *
 * @param agentId Agent identifier for public agents (mutually exclusive with conversationToken)
 * @param conversationToken Pre-generated token for private agents (mutually exclusive with agentId)
 * @param userId Optional user identifier for conversation tracking
 * @param textOnly Whether to use text-only mode (true) or voice mode (false, default)
 * @param audioInputSampleRate Sample rate for audio recording in Hz (default: 48000 for high quality)
 */
data class ConversationConfig(
    val agentId: String? = null,
    val conversationToken: String? = null,
    val userId: String? = null,
    val textOnly: Boolean = false,
    val audioInputSampleRate: Int = 48000,
    val overrides: Overrides? = null,
    val customLlmExtraBody: Map<String, Any>? = null,
    val dynamicVariables: Map<String, Any>? = null,
    val clientTools: Map<String, ClientTool> = emptyMap(),
    val onConnect: ((conversationId: String) -> Unit)? = null,
    val onMessage: ((source: String, message: String) -> Unit)? = null,
    val onModeChange: ((mode: String) -> Unit)? = null,
    val onStatusChange: ((status: String) -> Unit)? = null,
    val onCanSendFeedbackChange: ((canSend: Boolean) -> Unit)? = null,
    val onUnhandledClientToolCall: ((ClientToolCall) -> Unit)? = null,
    val onVadScore: ((score: Float) -> Unit)? = null

) {
    init {
        // Validation: agentId should not be empty if provided
        agentId?.let { id ->
            require(id.isNotBlank()) {
                "agentId cannot be blank"
            }
        }

        // Validation: conversationToken should not be empty if provided
        conversationToken?.let { token ->
            require(token.isNotBlank()) {
                "conversationToken cannot be blank"
            }
        }

        // Validation: audioSampleRate should be a valid audio sample rate
        require(audioInputSampleRate > 0) {
            "audioSampleRate must be positive"
        }
        require(audioInputSampleRate in listOf(8000, 16000, 22050, 44100, 48000)) {
            "audioSampleRate must be a standard sample rate (8000, 16000, 22050, 44100, 48000 Hz)"
        }
    }

    /**
     * Returns true if this configuration is for a private agent
     */
    val isPrivateAgent: Boolean
        get() = conversationToken != null
}

data class Overrides(
    val agent: AgentOverrides? = null,
    val tts: TtsOverrides? = null,
    val conversation: ConversationOverrides? = null,
    val client: ClientOverrides? = null
)

data class AgentOverrides(
    val prompt: PromptOverrides? = null,
    val firstMessage: String? = null,
    val language: Language? = null
)

data class PromptOverrides(
    val prompt: String? = null
)

data class TtsOverrides(
    val voiceId: String? = null
)

data class ConversationOverrides(
    val textOnly: Boolean? = null
)

data class ClientOverrides(
    val source: String? = null,
    val version: String? = null
)

/**
 * Supported languages
 */
enum class Language(val code: String) {
    EN("en"),
    JA("ja"),
    ZH("zh"),
    DE("de"),
    HI("hi"),
    FR("fr"),
    KO("ko"),
    PT("pt"),
    PT_BR("pt-br"),
    IT("it"),
    ES("es"),
    ID("id"),
    NL("nl"),
    TR("tr"),
    PL("pl"),
    SV("sv"),
    BG("bg"),
    RO("ro"),
    AR("ar"),
    CS("cs"),
    EL("el"),
    FI("fi"),
    MS("ms"),
    DA("da"),
    TA("ta"),
    UK("uk"),
    RU("ru"),
    HU("hu"),
    HR("hr"),
    SK("sk"),
    NO("no"),
    VI("vi"),
    TL("tl");

    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code == code }
    }
}