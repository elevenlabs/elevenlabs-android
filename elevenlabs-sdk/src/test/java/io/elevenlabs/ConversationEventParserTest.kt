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
    fun `agent_tool_request parses into a notification AgentToolRequest, not a ClientToolCall`() {
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
        assertTrue("agent_tool_request must NOT map to ClientToolCall (see issue #76)", event !is ConversationEvent.ClientToolCall)
        assertTrue(event is ConversationEvent.AgentToolRequest)
        val request = event as ConversationEvent.AgentToolRequest
        assertEquals("launchFlightSearchV2", request.toolName)
        assertEquals("launchFlightSearchV2_fb9db75a2e074a6a960a6e1ce4190330", request.toolCallId)
        assertEquals("client", request.toolType)
        assertEquals(38, request.eventId)
    }

    @Test
    fun `agent_tool_request with missing tool_type defaults to empty string`() {
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
        assertTrue(event is ConversationEvent.AgentToolRequest)
        val request = event as ConversationEvent.AgentToolRequest
        assertEquals("SomeTool", request.toolName)
        assertEquals("some_123", request.toolCallId)
        assertEquals("", request.toolType)
        assertEquals(42, request.eventId)
    }

    @Test
    fun `agent_tool_request never carries parameters and never triggers a client tool call`() {
        // Regression guard for issue #76: a stray "parameters" field on an
        // agent_tool_request must not turn it into a ClientToolCall, and must
        // not be exposed as execution parameters.
        val json = """
            {
                "type": "agent_tool_request",
                "agent_tool_request": {
                    "tool_name": "FireAndForgetTool",
                    "tool_call_id": "fire_123",
                    "tool_type": "client",
                    "event_id": 7
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertTrue(event is ConversationEvent.AgentToolRequest)
        assertTrue(event !is ConversationEvent.ClientToolCall)
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

    // ==================== Transcript event_id tests ====================

    @Test
    fun `agent_response should parse event_id`() {
        val json = """
            {
                "type": "agent_response",
                "agent_response_event": {
                    "agent_response": "Hello",
                    "event_id": 12
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertTrue(event is ConversationEvent.AgentResponse)
        val agentResponse = event as ConversationEvent.AgentResponse
        assertEquals("Hello", agentResponse.agentResponse)
        assertEquals(12, agentResponse.eventId)
    }

    @Test
    fun `user_transcript should parse event_id`() {
        val json = """
            {
                "type": "user_transcript",
                "user_transcription_event": {
                    "user_transcript": "hello world",
                    "event_id": 8
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertTrue(event is ConversationEvent.UserTranscript)
        val userTranscript = event as ConversationEvent.UserTranscript
        assertEquals("hello world", userTranscript.userTranscript)
        assertEquals(8, userTranscript.eventId)
    }

    @Test
    fun `agent_chat_response_part should parse event_id and type`() {
        val json = """
            {
                "type": "agent_chat_response_part",
                "text_response_part": {
                    "text": "Hi",
                    "type": "delta",
                    "event_id": 3
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertTrue(event is ConversationEvent.AgentChatResponsePart)
        val part = event as ConversationEvent.AgentChatResponsePart
        assertEquals("Hi", part.text)
        assertEquals("delta", part.partType)
        assertEquals(3, part.eventId)
    }

    @Test
    fun `agent_response_correction should parse event_id`() {
        val json = """
            {
                "type": "agent_response_correction",
                "agent_response_correction_event": {
                    "original_agent_response": "sunny",
                    "corrected_agent_response": "raining",
                    "event_id": 21
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertTrue(event is ConversationEvent.AgentResponseCorrection)
        val correction = event as ConversationEvent.AgentResponseCorrection
        assertEquals("raining", correction.correctedAgentResponse)
        assertEquals(21, correction.eventId)
    }

    @Test
    fun `agent_response without event_id yields null event_id`() {
        val json = """
            {
                "type": "agent_response",
                "agent_response_event": {
                    "agent_response": "Hello"
                }
            }
        """.trimIndent()

        val event = ConversationEventParser.parseIncomingEvent(json)

        assertTrue(event is ConversationEvent.AgentResponse)
        assertEquals(null, (event as ConversationEvent.AgentResponse).eventId)
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

