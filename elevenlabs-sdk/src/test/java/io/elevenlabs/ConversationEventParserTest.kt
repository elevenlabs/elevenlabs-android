package io.elevenlabs

import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.network.ConversationEventParser
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for ConversationEventParser, specifically for client tool call parsing
 * and expects_response default behavior.
 */
class ConversationEventParserTest {

    // ==================== client_tool_call event type tests ====================

    @Test
    fun `client_tool_call with expects_response true should return true`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "TestTool",
                    "tool_call_id": "test_123",
                    "parameters": {},
                    "expects_response": true
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals("TestTool", toolCall.toolName)
        assertEquals("test_123", toolCall.toolCallId)
        assertTrue(toolCall.expectsResponse)
    }

    @Test
    fun `client_tool_call with expects_response false should return false`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "TestTool",
                    "tool_call_id": "test_123",
                    "parameters": {},
                    "expects_response": false
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertFalse(toolCall.expectsResponse)
    }

    @Test
    fun `client_tool_call without expects_response field should default to true`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "ResolveElement",
                    "tool_call_id": "resolve_456",
                    "parameters": {"elementId": "xyz"},
                    "event_id": 17
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals("ResolveElement", toolCall.toolName)
        assertEquals("resolve_456", toolCall.toolCallId)
        assertTrue("expects_response should default to true when missing", toolCall.expectsResponse)
    }

    @Test
    fun `client_tool_call with null expects_response should default to true`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "TestTool",
                    "tool_call_id": "test_123",
                    "parameters": {},
                    "expects_response": null
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertTrue("expects_response should default to true when null", toolCall.expectsResponse)
    }

    // ==================== agent_tool_request event type tests ====================

    @Test
    fun `agent_tool_request with tool_type client should default expects_response to true`() {
        val json = """
            {
                "type": "agent_tool_request",
                "agent_tool_request": {
                    "tool_name": "launchFlightSearchV2",
                    "tool_call_id": "launchFlightSearchV2_fb9db75a2e074a6a960a6e1ce4190330",
                    "tool_type": "client",
                    "event_id": 38
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals("launchFlightSearchV2", toolCall.toolName)
        assertEquals("launchFlightSearchV2_fb9db75a2e074a6a960a6e1ce4190330", toolCall.toolCallId)
        assertTrue("expects_response should default to true for agent_tool_request", toolCall.expectsResponse)
    }

    @Test
    fun `agent_tool_request with explicit expects_response false should return false`() {
        val json = """
            {
                "type": "agent_tool_request",
                "agent_tool_request": {
                    "tool_name": "FireAndForgetTool",
                    "tool_call_id": "fire_123",
                    "tool_type": "client",
                    "expects_response": false
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertFalse("expects_response should be false when explicitly set", toolCall.expectsResponse)
    }

    @Test
    fun `agent_tool_request without tool_type should still default expects_response to true`() {
        val json = """
            {
                "type": "agent_tool_request",
                "agent_tool_request": {
                    "tool_name": "SomeTool",
                    "tool_call_id": "some_123",
                    "event_id": 42
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertTrue("expects_response should default to true even without tool_type", toolCall.expectsResponse)
    }

    // ==================== Parameter parsing tests ====================

    @Test
    fun `client_tool_call should parse string parameters correctly`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "TestTool",
                    "tool_call_id": "test_123",
                    "parameters": {
                        "origin": "DEL",
                        "destination": "BOM",
                        "date": "11042026"
                    }
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals("DEL", toolCall.parameters["origin"])
        assertEquals("BOM", toolCall.parameters["destination"])
        assertEquals("11042026", toolCall.parameters["date"])
    }

    @Test
    fun `client_tool_call should parse numeric parameters correctly`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "TestTool",
                    "tool_call_id": "test_123",
                    "parameters": {
                        "adults": 2,
                        "children": 1,
                        "maxPrice": 50000
                    }
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals(2, (toolCall.parameters["adults"] as Number).toInt())
        assertEquals(1, (toolCall.parameters["children"] as Number).toInt())
        assertEquals(50000, (toolCall.parameters["maxPrice"] as Number).toInt())
    }

    @Test
    fun `client_tool_call should parse boolean parameters correctly`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "TestTool",
                    "tool_call_id": "test_123",
                    "parameters": {
                        "isRoundTrip": true,
                        "directFlightsOnly": false
                    }
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals(true, toolCall.parameters["isRoundTrip"])
        assertEquals(false, toolCall.parameters["directFlightsOnly"])
    }

    @Test
    fun `client_tool_call with empty parameters should work`() {
        val json = """
            {
                "type": "client_tool_call",
                "client_tool_call": {
                    "tool_name": "NoParamTool",
                    "tool_call_id": "noparam_123",
                    "parameters": {}
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNotNull(event)
        assertTrue(event is ConversationEvent.ClientToolCall)
        val toolCall = event as ConversationEvent.ClientToolCall
        assertEquals("NoParamTool", toolCall.toolName)
        assertTrue(toolCall.parameters.isEmpty())
    }

    // ==================== Edge case tests ====================

    @Test
    fun `unknown event type should return null`() {
        val json = """
            {
                "type": "unknown_event_type",
                "data": {}
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNull(event)
    }

    @Test
    fun `malformed JSON should return null`() {
        val json = "{ this is not valid json }"

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertNull(event)
    }
}

