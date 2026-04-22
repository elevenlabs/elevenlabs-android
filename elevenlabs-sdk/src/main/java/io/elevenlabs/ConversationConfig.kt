package io.elevenlabs

import io.elevenlabs.models.ConversationEvent.ClientToolCall
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import io.elevenlabs.models.DisconnectionDetails

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
 * @param apiEndpoint Base URL for ElevenLabs API (default: "https://api.elevenlabs.io")
 * @param websocketUrl WebSocket URL for LiveKit WebRTC connection (default: "wss://livekit.rtc.elevenlabs.io")
 * @param environment Optional environment name for the agent (defaults to "production" on the server)
 */
data class ConversationConfig(
    val agentId: String? = null,
    val conversationToken: String? = null,
    val userId: String? = null,
    val textOnly: Boolean = false,
    val audioInputSampleRate: Int = 48000,
    val apiEndpoint: String = "https://api.elevenlabs.io",
    val websocketUrl: String = "wss://livekit.rtc.elevenlabs.io",
    val environment: String? = null,
    val overrides: Overrides? = null,
    val customLlmExtraBody: Map<String, Any>? = null,
    val dynamicVariables: Map<String, Any>? = null,
    val clientTools: Map<String, ClientTool> = emptyMap(),
    val onConnect: ((conversationId: String) -> Unit)? = null,
    val onMessage: ((source: String, message: String) -> Unit)? = null,
    val onModeChange: ((mode: ConversationMode) -> Unit)? = null,
    val onStatusChange: ((status: ConversationStatus) -> Unit)? = null,
    val onCanSendFeedbackChange: ((canSend: Boolean) -> Unit)? = null,
    val onUnhandledClientToolCall: ((ClientToolCall) -> Unit)? = null,
    val onVadScore: ((score: Float) -> Unit)? = null,
    val onAudioLevelChanged: ((level: Float) -> Unit)? = null,
    val onAudioAlignment: ((alignment: Map<String, Any>) -> Unit)? = null,
    val onAgentResponseMetadata: ((metadata: Map<String, Any>) -> Unit)? = null,
    val onUserTranscript: ((userTranscript: String) -> Unit)? = null,
    val onAgentResponse: ((agentResponse: String) -> Unit)? = null,
    val onAgentResponseCorrection: ((originalResponse: String, correctedResponse: String) -> Unit)? = null,
    val onAgentToolResponse: ((toolName: String, toolCallId: String, toolType: String, isError: Boolean) -> Unit)? = null,
    val onConversationInitiationMetadata: ((conversationId: String, agentOutputFormat: String, userInputFormat: String) -> Unit)? = null,
    val onInterruption: ((eventId: Int) -> Unit)? = null,
    val onDisconnect: ((details: DisconnectionDetails) -> Unit)? = null,
    val onError: ((code: Int, message: String?) -> Unit)? = null

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

        // Validation: audioInputSampleRate should be a valid audio sample rate
        require(audioInputSampleRate in listOf(8000, 16000, 22050, 44100, 48000)) {
            "audioInputSampleRate must be a standard sample rate (8000, 16000, 22050, 44100, 48000 Hz)"
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
 * Supported languages for ElevenLabs conversational agents.
 *
 * Source: https://help.elevenlabs.io/hc/en-us/articles/13313366263441-What-languages-do-you-support
 */
enum class Language(val code: String) {
    AF("af"),
    AR("ar"),
    AS("as"),
    AZ("az"),
    BE("be"),
    BG("bg"),
    BN("bn"),
    BS("bs"),
    CA("ca"),
    CEB("ceb"),
    CS("cs"),
    CY("cy"),
    DA("da"),
    DE("de"),
    EL("el"),
    EN("en"),
    ES("es"),
    ET("et"),
    FA("fa"),
    FI("fi"),
    FR("fr"),
    GA("ga"),
    GL("gl"),
    GU("gu"),
    HA("ha"),
    HE("he"),
    HI("hi"),
    HR("hr"),
    HU("hu"),
    HY("hy"),
    ID("id"),
    IS("is"),
    IT("it"),
    JA("ja"),
    JV("jv"),
    KA("ka"),
    KK("kk"),
    KN("kn"),
    KO("ko"),
    KY("ky"),
    LB("lb"),
    LN("ln"),
    LT("lt"),
    LV("lv"),
    MK("mk"),
    ML("ml"),
    MR("mr"),
    MS("ms"),
    NE("ne"),
    NL("nl"),
    NO("no"),
    NY("ny"),
    PA("pa"),
    PL("pl"),
    PS("ps"),
    PT("pt"),
    PT_BR("pt-br"),
    RO("ro"),
    RU("ru"),
    SD("sd"),
    SK("sk"),
    SL("sl"),
    SO("so"),
    SR("sr"),
    SV("sv"),
    SW("sw"),
    TA("ta"),
    TE("te"),
    TH("th"),
    TL("tl"),
    TR("tr"),
    UK("uk"),
    UR("ur"),
    VI("vi"),
    ZH("zh");

    companion object {
        fun fromCode(code: String): Language? = entries.find { it.code == code }
    }
}