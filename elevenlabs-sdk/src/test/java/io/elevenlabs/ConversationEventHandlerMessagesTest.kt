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
 * Behavior of the reconciled transcript exposed via [ConversationEventHandler.messages].
 *
 * Incoming transcripts/responses are matched per role by event id: a matching id updates the
 * existing message in place, a newer id appends, and a stale unmatched id is ignored. An
 * in-progress user partial never survives once its turn is finalized.
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

    // --- Agent streaming (agent_chat_response_part) ---

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

    // --- Agent response / correction ---

    @Test
    fun `agent_response finalizes a streaming partial in place`() = runTest {
        handler.handleIncomingEvent(part("start", "Hi", 3))

        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "Hi, how can I help?", eventId = 3))

        val message = handler.messages.value.single()
        assertEquals("Hi, how can I help?", message.content)
        assertFalse(message.isPartial)
    }

    @Test
    fun `agent_response_correction replaces the matching agent message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "It is sunny", eventId = 9))

        handler.handleIncomingEvent(
            ConversationEvent.AgentResponseCorrection(
                originalAgentResponse = "It is sunny",
                correctedAgentResponse = "It is raining",
                eventId = 9
            )
        )

        val message = handler.messages.value.single()
        assertEquals("It is raining", message.content)
        assertFalse(message.isPartial)
    }

    @Test
    fun `agent_response with a newer event id appends a new message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "first", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "second", eventId = 2))

        val messages = handler.messages.value
        assertEquals(listOf("first", "second"), messages.map { it.content })
        assertEquals(listOf(1, 2), messages.map { it.eventId })
    }

    @Test
    fun `an out-of-order agent_response with a stale event id is ignored`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "current", eventId = 10))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "stale", eventId = 4))

        val message = handler.messages.value.single()
        assertEquals("current", message.content)
        assertEquals(10, message.eventId)
    }

    // --- User tentative (tentative_user_transcript) ---

    @Test
    fun `tentative user transcripts for one turn collapse into a single partial`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hel", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello the", eventId = 5))

        val message = handler.messages.value.single()
        assertEquals(MessageRole.USER, message.role)
        assertEquals("hello the", message.content)
        assertTrue(message.isPartial)
    }

    @Test
    fun `a newer tentative replaces a stray partial instead of duplicating it`() = runTest {
        // A tentative that never finalized, then a newer one: the stale partial must not linger.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Set up.", eventId = 245))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Set up a test.", eventId = 249))

        val message = handler.messages.value.single()
        assertEquals("Set up a test.", message.content)
        assertEquals(249, message.eventId)
        assertTrue(message.isPartial)
    }

    @Test
    fun `a tentative is ignored once the turn is finalized`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "hello there", eventId = 5))
        // A stray tentative arriving after the final must not reopen a partial bubble.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello th", eventId = 5))

        val message = handler.messages.value.single()
        assertEquals("hello there", message.content)
        assertFalse(message.isPartial)
    }

    // --- User transcript (final) ---

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
        // The id-468 partial never finalized; the next turn's final (id 475) must drop it.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Any questions?", eventId = 468))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "I have one.", eventId = 475))

        val message = handler.messages.value.single()
        assertEquals("I have one.", message.content)
        assertEquals(475, message.eventId)
        assertFalse(message.isPartial)
    }

    @Test
    fun `an out-of-order user_transcript with a stale event id is ignored`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "current", eventId = 20))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "stale", eventId = 10))

        val message = handler.messages.value.single()
        assertEquals("current", message.content)
        assertEquals(20, message.eventId)
    }

    // --- Cross-role, local sends, ordering ---

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
    fun `a locally typed message does not let a later stale transcript reappear`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "first", eventId = 10))
        // The typed message carries no event id, so it must not lower the role's high-water mark.
        handler.sendUserMessage("typed")
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "stale", eventId = 5))

        val messages = handler.messages.value
        assertEquals(listOf("first", "typed"), messages.map { it.content })
        assertEquals(listOf(10, null), messages.map { it.eventId })
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
        assertEquals(2, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("what's the weather?", messages[0].content)
        assertEquals(MessageRole.AGENT, messages[1].role)
        assertEquals("It's sunny.", messages[1].content)
        assertTrue("no partial bubbles should remain", messages.none { it.isPartial })
    }

    private fun part(partType: String, text: String, eventId: Int) =
        ConversationEvent.AgentChatResponsePart(partType = partType, text = text, eventId = eventId)
}
