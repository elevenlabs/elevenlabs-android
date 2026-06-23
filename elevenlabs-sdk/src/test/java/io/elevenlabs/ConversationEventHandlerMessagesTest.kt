package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.models.MessageRole
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reconciliation of [ConversationEventHandler.messages]: a matching event id updates in place,
 * finalized records always append (even out of order), and a user partial never outlives its turn.
 */
class ConversationEventHandlerMessagesTest {

    private lateinit var audioManager: AudioManager
    private lateinit var toolRegistry: ClientToolRegistry
    private lateinit var handler: ConversationEventHandler

    @Before
    fun setup() {
        audioManager = mockk(relaxed = true)
        toolRegistry = ClientToolRegistry()
        handler = ConversationEventHandler(
            audioManager = audioManager,
            toolRegistry = toolRegistry,
            messageCallback = { }
        )
    }

    @After
    fun teardown() {
        toolRegistry.cleanup()
    }

    // --- Agent streaming ---

    @Test
    fun `streaming agent parts accumulate into one message and finalize on stop`() = runTest {
        handler.handleIncomingEvent(part("start", "Hel", 7))
        handler.handleIncomingEvent(part("delta", "lo ", 7))
        handler.handleIncomingEvent(part("delta", "world", 7))

        var message = handler.messages.value.single()
        assertEquals("Hello world", message.content)
        assertTrue(message.isPartial)
        val streamingId = message.id

        handler.handleIncomingEvent(part("stop", "!", 7))

        message = handler.messages.value.single()
        assertEquals("Hello world!", message.content)
        assertFalse(message.isPartial)
        assertEquals("message identity must be stable across updates", streamingId, message.id)
    }

    @Test
    fun `a streaming part is ignored once the agent message is finalized`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "final answer", eventId = 1))

        handler.handleIncomingEvent(part("delta", " late", 1))

        val message = handler.messages.value.single()
        assertEquals("final answer", message.content)
        assertFalse(message.isPartial)
    }

    @Test
    fun `agent_response finalizes a still-streaming partial in place`() = runTest {
        handler.handleIncomingEvent(part("start", "Hi", 3))

        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "Hi, how can I help?", eventId = 3))

        val message = handler.messages.value.single()
        assertEquals("Hi, how can I help?", message.content)
        assertFalse(message.isPartial)
    }

    // --- Agent finalized records arrive out of order ---

    @Test
    fun `an out-of-order correction updates the right agent message without duplicating`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "ten", eventId = 10))
        // A lower id arrives late, so the list is no longer sorted by event id.
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "four", eventId = 4))

        // The matcher must scan past id 4 to find id 10 rather than appending a duplicate.
        handler.handleIncomingEvent(
            ConversationEvent.AgentResponseCorrection(
                originalAgentResponse = "ten",
                correctedAgentResponse = "ten fixed",
                eventId = 10
            )
        )

        val messages = handler.messages.value
        assertEquals(listOf("ten fixed", "four"), messages.map { it.content })
        assertEquals(listOf(10, 4), messages.map { it.eventId })
    }

    @Test
    fun `an out-of-order user_transcript updates the right message without duplicating`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "twenty", eventId = 20))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "ten", eventId = 10))

        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "twenty fixed", eventId = 20))

        val messages = handler.messages.value
        assertEquals(listOf("twenty fixed", "ten"), messages.map { it.content })
        assertEquals(listOf(20, 10), messages.map { it.eventId })
    }

    // --- User tentative / partial handling ---

    @Test
    fun `consecutive tentatives collapse into a single partial`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Set up.", eventId = 245))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Set up a test.", eventId = 249))

        val message = handler.messages.value.single()
        assertEquals("Set up a test.", message.content)
        assertEquals(249, message.eventId)
        assertTrue(message.isPartial)
    }

    @Test
    fun `a tentative is gated by the highest event id, not the latest message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "q1", eventId = 20))
        // A lower final id and a null-id typed message follow; neither lowers the high-water mark.
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "q2", eventId = 10))
        handler.sendUserMessage("typed")

        // id 15 is below the highest recorded id (20), so the tentative must stay suppressed.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "stale", eventId = 15))

        val messages = handler.messages.value
        assertEquals(listOf("q1", "q2", "typed"), messages.map { it.content })
        assertTrue(messages.none { it.isPartial })
    }

    @Test
    fun `user_transcript finalizes the in-progress partial`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello the", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "hello there", eventId = 5))

        val message = handler.messages.value.single()
        assertEquals("hello there", message.content)
        assertFalse(message.isPartial)
    }

    @Test
    fun `user_transcript for a new turn appends and clears a stray partial`() = runTest {
        // The id-468 partial never finalized; the next turn's final must drop it, not leave a bubble.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Any questions?", eventId = 468))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "I have one.", eventId = 475))

        val message = handler.messages.value.single()
        assertEquals("I have one.", message.content)
        assertEquals(475, message.eventId)
        assertFalse(message.isPartial)
    }

    // --- Cross-role, local sends, end to end ---

    @Test
    fun `user and agent messages with the same event id stay distinct`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "agent", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "user", eventId = 5))

        val messages = handler.messages.value
        assertEquals(2, messages.size)
        assertEquals(MessageRole.AGENT, messages[0].role)
        assertEquals(MessageRole.USER, messages[1].role)
    }

    @Test
    fun `sendUserMessage appends a local user message without an event id`() {
        handler.sendUserMessage("Book a flight")

        val message = handler.messages.value.single()
        assertEquals(MessageRole.USER, message.role)
        assertEquals("Book a flight", message.content)
        assertNull(message.eventId)
        assertFalse(message.isPartial)
    }

    @Test
    fun `a full voice turn reconciles streamed and finalized text into ordered messages`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "what's the", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "what's the weather?", eventId = 1))
        handler.handleIncomingEvent(part("start", "It's", 2))
        handler.handleIncomingEvent(part("delta", " sunny", 2))
        handler.handleIncomingEvent(part("stop", ".", 2))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "It's sunny.", eventId = 2))

        val messages = handler.messages.value
        assertEquals(listOf("what's the weather?", "It's sunny."), messages.map { it.content })
        assertEquals(listOf(MessageRole.USER, MessageRole.AGENT), messages.map { it.role })
        assertTrue("no partial bubbles should remain", messages.none { it.isPartial })
    }

    private fun part(partType: String, text: String, eventId: Int) =
        ConversationEvent.AgentChatResponsePart(partType = partType, text = text, eventId = eventId)
}
