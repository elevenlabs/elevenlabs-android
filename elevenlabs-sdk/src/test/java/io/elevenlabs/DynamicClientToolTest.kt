package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.network.OutgoingEvent
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for dynamic client tool handling functionality
 *
 * This test suite verifies:
 * 1. sendToolResult() method correctly sends tool results
 * 2. Tool result event structure and serialization
 * 3. Support for string result values
 */
class DynamicClientToolTest {

    private lateinit var audioManager: AudioManager
    private lateinit var toolRegistry: ClientToolRegistry
    private lateinit var messageCallback: (OutgoingEvent) -> Unit
    private lateinit var capturedEvents: MutableList<OutgoingEvent>

    @Before
    fun setup() {
        audioManager = mockk(relaxed = true)
        toolRegistry = ClientToolRegistry()
        capturedEvents = mutableListOf()
        messageCallback = { event ->
            capturedEvents.add(event)
        }
    }

    @After
    fun teardown() {
        toolRegistry.cleanup()
    }

    @Test
    fun `sendToolResult sends correct event with success result`() {
        val eventHandler = ConversationEventHandler(
            audioManager = audioManager,
            toolRegistry = toolRegistry,
            messageCallback = messageCallback
        )

        val result = "The temperature is 25 degrees"

        eventHandler.sendToolResult(
            toolCallId = "tool-123",
            result = result,
            isError = false
        )

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0] as OutgoingEvent.ClientToolResult
        assertEquals("tool-123", event.toolCallId)
        assertEquals(result, event.result)
        assertFalse(event.isError)
    }

    @Test
    fun `sendToolResult sends correct event with error result`() {
        val eventHandler = ConversationEventHandler(
            audioManager = audioManager,
            toolRegistry = toolRegistry,
            messageCallback = messageCallback
        )

        val errorResult = "Something went wrong"

        eventHandler.sendToolResult(
            toolCallId = "tool-456",
            result = errorResult,
            isError = true
        )

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0] as OutgoingEvent.ClientToolResult
        assertEquals("tool-456", event.toolCallId)
        assertEquals(errorResult, event.result)
        assertTrue(event.isError)
    }

    @Test
    fun `sendToolResult with JSON string result`() {
        val eventHandler = ConversationEventHandler(
            audioManager = audioManager,
            toolRegistry = toolRegistry,
            messageCallback = messageCallback
        )

        // When you need to send complex data, serialize it as a JSON string
        val jsonResult = """{"status":"completed","items":[{"id":1,"name":"Item 1"},{"id":2,"name":"Item 2"}],"count":2}"""

        eventHandler.sendToolResult(
            toolCallId = "complex-789",
            result = jsonResult,
            isError = false
        )

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0] as OutgoingEvent.ClientToolResult
        assertEquals("complex-789", event.toolCallId)
        assertEquals(jsonResult, event.result)
        assertFalse(event.isError)
    }
}
