package io.elevenlabs

import io.elevenlabs.models.AgentChatResponsePartType
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.network.ConversationEventParser
import org.junit.Assert.*
import org.junit.Test

class ConversationEventParserTest {

    @Test
    fun `parseAgentResponse parses agent_response event correctly`() {
        val json = """{"type":"agent_response","agent_response_event":{"agent_response":"Hello there!"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentResponse)
        val agentResponse = event as ConversationEvent.AgentResponse
        assertEquals("Hello there!", agentResponse.agentResponse)
    }

    @Test
    fun `parseAgentChatResponsePart parses START type correctly`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"","type":"start"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("", chatResponsePart.text)
        assertEquals(AgentChatResponsePartType.START, chatResponsePart.type)
    }

    @Test
    fun `parseAgentChatResponsePart parses DELTA type with text correctly`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"Hello","type":"delta"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("Hello", chatResponsePart.text)
        assertEquals(AgentChatResponsePartType.DELTA, chatResponsePart.type)
    }

    @Test
    fun `parseAgentChatResponsePart parses STOP type correctly`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"","type":"stop"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("", chatResponsePart.text)
        assertEquals(AgentChatResponsePartType.STOP, chatResponsePart.type)
    }

    @Test
    fun `parseAgentChatResponsePart handles multiple DELTA chunks`() {
        val chunks = listOf(
            """{"type":"agent_chat_response_part","text_response_part":{"text":"Hello ","type":"delta"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"world","type":"delta"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"!","type":"delta"}}"""
        )

        val texts = chunks.mapNotNull { json ->
            val event = ConversationEventParser.parseIncomingEvent(json)
            (event as? ConversationEvent.AgentChatResponsePart)?.text
        }

        assertEquals(listOf("Hello ", "world", "!"), texts)
        assertEquals("Hello world!", texts.joinToString(""))
    }

    @Test
    fun `parseAgentChatResponsePart handles missing type field defaults to DELTA`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"Hello"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("Hello", chatResponsePart.text)
        assertEquals(AgentChatResponsePartType.DELTA, chatResponsePart.type)
    }

    @Test
    fun `parseAgentChatResponsePart handles invalid type defaults to DELTA`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"Hello","type":"invalid"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("Hello", chatResponsePart.text)
        assertEquals(AgentChatResponsePartType.DELTA, chatResponsePart.type)
    }

    @Test
    fun `parseAgentChatResponsePart handles empty text_response_part object`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("", chatResponsePart.text)
        assertEquals(AgentChatResponsePartType.DELTA, chatResponsePart.type)
    }

    @Test
    fun `parseAgentChatResponsePart handles special characters in text`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"Hello \"world\" \n\t!","type":"delta"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("Hello \"world\" \n\t!", chatResponsePart.text)
    }

    @Test
    fun `parseAgentChatResponsePart handles unicode characters`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":{"text":"你好世界 🌍","type":"delta"}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val chatResponsePart = event as ConversationEvent.AgentChatResponsePart
        assertEquals("你好世界 🌍", chatResponsePart.text)
    }

    @Test
    fun `parseIncomingEvent returns null for malformed JSON`() {
        val json = """{"type":"agent_chat_response_part","text_response_part":"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNull(event)
    }

    @Test
    fun `parseIncomingEvent returns null for unknown event type`() {
        val json = """{"type":"unknown_event_type","data":{}}"""
        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNull(event)
    }

    @Test
    fun `full streaming sequence START-DELTA-STOP works correctly`() {
        val sequence = listOf(
            """{"type":"agent_chat_response_part","text_response_part":{"text":"","type":"start"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"The ","type":"delta"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"quick ","type":"delta"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"brown ","type":"delta"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"fox","type":"delta"}}""",
            """{"type":"agent_chat_response_part","text_response_part":{"text":"","type":"stop"}}"""
        )

        val events = sequence.map { ConversationEventParser.parseIncomingEvent(it) }

        // All events should parse successfully
        assertTrue(events.all { it is ConversationEvent.AgentChatResponsePart })

        val parts = events.map { it as ConversationEvent.AgentChatResponsePart }

        // Verify sequence
        assertEquals(AgentChatResponsePartType.START, parts[0].type)
        assertEquals("", parts[0].text)

        assertEquals(AgentChatResponsePartType.DELTA, parts[1].type)
        assertEquals("The ", parts[1].text)

        assertEquals(AgentChatResponsePartType.DELTA, parts[2].type)
        assertEquals("quick ", parts[2].text)

        assertEquals(AgentChatResponsePartType.DELTA, parts[3].type)
        assertEquals("brown ", parts[3].text)

        assertEquals(AgentChatResponsePartType.DELTA, parts[4].type)
        assertEquals("fox", parts[4].text)

        assertEquals(AgentChatResponsePartType.STOP, parts[5].type)
        assertEquals("", parts[5].text)

        // Verify complete text
        val completeText = parts
            .filter { it.type == AgentChatResponsePartType.DELTA }
            .joinToString("") { it.text }
        assertEquals("The quick brown fox", completeText)
    }

    @Test
    fun `AgentChatResponsePartType fromString handles all cases`() {
        assertEquals(AgentChatResponsePartType.START, AgentChatResponsePartType.fromString("start"))
        assertEquals(AgentChatResponsePartType.DELTA, AgentChatResponsePartType.fromString("delta"))
        assertEquals(AgentChatResponsePartType.STOP, AgentChatResponsePartType.fromString("stop"))

        // Case insensitive
        assertEquals(AgentChatResponsePartType.START, AgentChatResponsePartType.fromString("START"))
        assertEquals(AgentChatResponsePartType.DELTA, AgentChatResponsePartType.fromString("DELTA"))
        assertEquals(AgentChatResponsePartType.STOP, AgentChatResponsePartType.fromString("STOP"))

        // Invalid values
        assertNull(AgentChatResponsePartType.fromString("invalid"))
        assertNull(AgentChatResponsePartType.fromString(null))
        assertNull(AgentChatResponsePartType.fromString(""))
    }

    @Test
    fun `AgentChatResponsePartType toJsonString converts correctly`() {
        assertEquals("start", AgentChatResponsePartType.START.toJsonString())
        assertEquals("delta", AgentChatResponsePartType.DELTA.toJsonString())
        assertEquals("stop", AgentChatResponsePartType.STOP.toJsonString())
    }
}
