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
 * 3. Support for complex nested result structures
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

        val result = mapOf(
            "data" to "test data",
            "count" to 42
        )

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

        val errorResult = mapOf(
            "error" to "Something went wrong"
        )

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
    fun `sendToolResult with complex nested result structure`() {
        val eventHandler = ConversationEventHandler(
            audioManager = audioManager,
            toolRegistry = toolRegistry,
            messageCallback = messageCallback
        )

        val complexResult = mapOf(
            "status" to "completed",
            "data" to mapOf(
                "items" to listOf(
                    mapOf("id" to 1, "name" to "Item 1"),
                    mapOf("id" to 2, "name" to "Item 2")
                ),
                "count" to 2
            ),
            "metadata" to mapOf(
                "timestamp" to 1234567890,
                "version" to "1.0.0"
            )
        )

        eventHandler.sendToolResult(
            toolCallId = "complex-789",
            result = complexResult,
            isError = false
        )

        assertEquals(1, capturedEvents.size)
        val event = capturedEvents[0] as OutgoingEvent.ClientToolResult
        assertEquals("complex-789", event.toolCallId)
        assertEquals(complexResult, event.result)
        assertFalse(event.isError)
    }
}
