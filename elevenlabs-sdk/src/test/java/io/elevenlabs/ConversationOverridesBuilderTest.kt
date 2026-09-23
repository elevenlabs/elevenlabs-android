package io.elevenlabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationOverridesBuilderTest {

    private fun build(overrides: Overrides) =
        ConversationOverridesBuilder.constructOverrides(
            ConversationConfig(agentId = "agent-1", overrides = overrides)
        )

    private fun tts(overrides: Overrides) =
        build(overrides).getJSONObject("conversation_config_override").getJSONObject("tts")

    @Test
    fun `voice_id is serialized under conversation_config_override tts`() {
        val json = tts(Overrides(tts = TtsOverrides(voiceId = "voice-1")))
        assertEquals("voice-1", json.getString("voice_id"))
    }

    @Test
    fun `speed is serialized`() {
        val json = tts(Overrides(tts = TtsOverrides(speed = 1.15)))
        assertEquals(1.15, json.getDouble("speed"), 0.0001)
    }

    @Test
    fun `stability and similarity_boost are serialized with snake_case keys`() {
        val json = tts(Overrides(tts = TtsOverrides(stability = 0.7, similarityBoost = 0.9)))
        assertEquals(0.7, json.getDouble("stability"), 0.0001)
        assertEquals(0.9, json.getDouble("similarity_boost"), 0.0001)
    }

    @Test
    fun `all tts fields serialize together`() {
        val json = tts(
            Overrides(
                tts = TtsOverrides(
                    voiceId = "voice-1",
                    speed = 0.9,
                    stability = 0.5,
                    similarityBoost = 0.8
                )
            )
        )
        assertEquals("voice-1", json.getString("voice_id"))
        assertEquals(0.9, json.getDouble("speed"), 0.0001)
        assertEquals(0.5, json.getDouble("stability"), 0.0001)
        assertEquals(0.8, json.getDouble("similarity_boost"), 0.0001)
    }

    @Test
    fun `null tts fields are omitted, not sent as null`() {
        // The platform asks callers to omit fields they do not want to override rather than send
        // nulls, so a partially-populated TtsOverrides must not widen into explicit nulls.
        val json = tts(Overrides(tts = TtsOverrides(speed = 1.1)))
        assertEquals(1, json.length())
        assertTrue(json.has("speed"))
        assertFalse(json.has("voice_id"))
        assertFalse(json.has("stability"))
        assertFalse(json.has("similarity_boost"))
    }

    @Test
    fun `an entirely empty TtsOverrides emits no tts object at all`() {
        val root = build(Overrides(tts = TtsOverrides()))
        assertFalse(
            root.optJSONObject("conversation_config_override")?.has("tts") ?: false
        )
    }

    @Test
    fun `agent overrides still serialize alongside tts`() {
        val root = build(
            Overrides(
                agent = AgentOverrides(
                    prompt = PromptOverrides("you are a tutor"),
                    firstMessage = "hello"
                ),
                tts = TtsOverrides(voiceId = "voice-1", speed = 1.2)
            )
        )
        val override = root.getJSONObject("conversation_config_override")
        val agent = override.getJSONObject("agent")
        assertEquals("you are a tutor", agent.getJSONObject("prompt").getString("prompt"))
        assertEquals("hello", agent.getString("first_message"))
        assertEquals(1.2, override.getJSONObject("tts").getDouble("speed"), 0.0001)
    }
}
