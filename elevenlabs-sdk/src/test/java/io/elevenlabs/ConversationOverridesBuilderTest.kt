package io.elevenlabs

import org.junit.Assert.*
import org.junit.Test

class ConversationOverridesBuilderTest {

    private fun textOnlyIn(config: ConversationConfig): Boolean =
        ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override")
            .getJSONObject("conversation")
            .getBoolean("text_only")

    @Test
    fun `top-level textOnly is sent to the server without an override`() {
        val config = ConversationConfig(agentId = "agent", textOnly = true)

        assertTrue(textOnlyIn(config))
    }

    @Test
    fun `text_only false is sent for voice sessions`() {
        val config = ConversationConfig(agentId = "agent")

        assertFalse(textOnlyIn(config))
    }

    @Test
    fun `conversation override is used when top-level textOnly is not set`() {
        val config = ConversationConfig(
            agentId = "agent",
            overrides = Overrides(conversation = ConversationOverrides(textOnly = true))
        )

        assertTrue(textOnlyIn(config))
    }

    @Test
    fun `top-level textOnly wins over a conflicting override`() {
        val config = ConversationConfig(
            agentId = "agent",
            textOnly = true,
            overrides = Overrides(conversation = ConversationOverrides(textOnly = false))
        )

        assertTrue(textOnlyIn(config))
    }

    @Test
    fun `other overrides are kept alongside text_only`() {
        val config = ConversationConfig(
            agentId = "agent",
            textOnly = true,
            overrides = Overrides(
                agent = AgentOverrides(firstMessage = "Hi"),
                tts = TtsOverrides(voiceId = "voice")
            )
        )

        val override = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override")
        assertEquals("Hi", override.getJSONObject("agent").getString("first_message"))
        assertEquals("voice", override.getJSONObject("tts").getString("voice_id"))
        assertTrue(override.getJSONObject("conversation").getBoolean("text_only"))
    }

    @Test
    fun `resolveTextOnly selects the text transport from the conversation override`() {
        val config = ConversationConfig(
            agentId = "agent",
            overrides = Overrides(conversation = ConversationOverrides(textOnly = true))
        )

        assertTrue(ConversationClientImpl.resolveTextOnly(config).textOnly)
    }

    @Test
    fun `resolveTextOnly keeps voice transport by default`() {
        val config = ConversationConfig(agentId = "agent")

        assertFalse(ConversationClientImpl.resolveTextOnly(config).textOnly)
    }

    @Test
    fun `tts speed override is sent`() {
        val config = ConversationConfig(agentId = "agent", overrides = Overrides(tts = TtsOverrides(speed = 1.2)))

        val tts = ConversationOverridesBuilder.constructOverrides(config)
            .getJSONObject("conversation_config_override").getJSONObject("tts")
        assertEquals(1.2, tts.getDouble("speed"), 0.0)
        assertFalse(tts.has("voice_id"))
    }
}
