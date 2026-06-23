package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.models.MessageRole
import io.elevenlabs.network.OutgoingEvent
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the reconciled transcript exposed via [ConversationSession.messages]:
 * upsert-by-event-id, in-place updates, streaming accumulation, partial/final flags,
 * optimistic local sends, and arrival ordering without cross-role merging.
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

    @Test
    fun `agent_response appends a final agent message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "Hello there", eventId = 1))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals(MessageRole.AGENT, messages[0].role)
        assertEquals("Hello there", messages[0].content)
        assertEquals(1, messages[0].eventId)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `streaming parts accumulate into one partial message and finalize on stop`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "start", text = "Hel", eventId = 7))
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "delta", text = "lo ", eventId = 7))
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "delta", text = "world", eventId = 7))

        var messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hello world", messages[0].content)
        assertTrue("message should be partial while streaming", messages[0].isPartial)
        val idDuringStream = messages[0].id

        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "stop", text = "!", eventId = 7))

        messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hello world!", messages[0].content)
        assertFalse("message should be final after stop", messages[0].isPartial)
        assertEquals("id must be preserved across updates", idDuringStream, messages[0].id)
    }

    @Test
    fun `final agent_response finalizes a still-streaming partial in place`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "start", text = "Hi", eventId = 3))

        // The full response for the same (not newer) event id arrives while the streamed message is
        // still partial: it updates the existing message in place and finalizes it.
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "Hi, how can I help?", eventId = 3))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hi, how can I help?", messages[0].content)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `agent_response overwrites a finalized streamed message for the same event id`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "start", text = "Hi", eventId = 3))
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "stop", text = "", eventId = 3))

        // agent_response is canonical for its turn: a later one with the same event id replaces the
        // stored content in place, even after the streamed message was finalized.
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "Hi, how can I help?", eventId = 3))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Hi, how can I help?", messages[0].content)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `consecutive tentative user transcripts collapse into one partial`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hel", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello the", eventId = 5))

        val messages = handler.messages.value
        assertEquals("consecutive tentatives must collapse to one bubble, not duplicate", 1, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("hello the", messages[0].content)
        assertTrue(messages[0].isPartial)
    }

    @Test
    fun `final user_transcript replaces the in-progress partial with a single final message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hel", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello the", eventId = 5))

        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "hello there", eventId = 5))

        val messages = handler.messages.value
        assertEquals("the partial must be replaced, not duplicated", 1, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("hello there", messages[0].content)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `a late tentative transcript does not overwrite a finalized one`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello the", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "hello there", eventId = 5))

        // A stray tentative for the same event id arriving after finalization must be ignored.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "hello th", eventId = 5))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("hello there", messages[0].content)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `a newer tentative supersedes a stray partial with a different event id`() = runTest {
        // A tentative that never finalizes, followed by a newer tentative (higher event id): the
        // stray must be superseded in place, not left behind as a second user bubble.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Set up.", eventId = 245))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Set up a good test.", eventId = 249))

        var messages = handler.messages.value
        assertEquals("a newer tentative must replace the stray partial, not append", 1, messages.size)
        assertEquals("Set up a good test.", messages[0].content)
        assertTrue(messages[0].isPartial)

        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "Set up a good test.", eventId = 249))

        messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("Set up a good test.", messages[0].content)
        assertEquals(249, messages[0].eventId)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `a final transcript supersedes a stray partial with a different event id`() = runTest {
        // A tentative with one id followed directly by a final with a higher id (no intermediate
        // tentative) must collapse to a single final message.
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "Any questions?", eventId = 468))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "I have a question. Sorry.", eventId = 475))

        val messages = handler.messages.value
        assertEquals("final must drop the stray partial, not append a second bubble", 1, messages.size)
        assertEquals("I have a question. Sorry.", messages[0].content)
        assertEquals(475, messages[0].eventId)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `a stray partial is dropped when an agent message intervenes`() = runTest {
        // A tentative that never finalized, then an agent reply, then a new user transcript: the
        // stray partial is dropped and the new transcript is appended after the agent (arrival order).
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "stray", eventId = 10))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "agent reply", eventId = 11))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "real question", eventId = 12))

        val messages = handler.messages.value
        assertEquals("stray partial must be dropped, not kept", 2, messages.size)
        assertEquals(MessageRole.AGENT, messages[0].role)
        assertEquals("agent reply", messages[0].content)
        assertEquals(MessageRole.USER, messages[1].role)
        assertEquals("real question", messages[1].content)
        assertFalse(messages[1].isPartial)
    }

    @Test
    fun `a stale agent event with a lower id is ignored`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "current", eventId = 10))
        // An out-of-order event id older than the high-water mark, matching no existing message, is dropped.
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "stale", eventId = 4))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("current", messages[0].content)
    }

    @Test
    fun `agent_response_correction replaces content in place`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "It is sunny", eventId = 9))

        handler.handleIncomingEvent(
            ConversationEvent.AgentResponseCorrection(
                originalAgentResponse = "It is sunny",
                correctedAgentResponse = "It is raining",
                eventId = 9
            )
        )

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("It is raining", messages[0].content)
    }

    @Test
    fun `sendUserMessage optimistically appends a final local user message`() {
        handler.sendUserMessage("Book a flight")

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals("Book a flight", messages[0].content)
        assertEquals(null, messages[0].eventId)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `user and agent messages sharing an event id stay distinct and in arrival order`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "agent reply", eventId = 5))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "user said this", eventId = 5))

        val messages = handler.messages.value
        assertEquals(2, messages.size)
        assertEquals(MessageRole.AGENT, messages[0].role)
        assertEquals(MessageRole.USER, messages[1].role)
    }

    @Test
    fun `agent_response overwrites a finalized message for the same event id`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "first final", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "revised final", eventId = 1))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("revised final", messages[0].content)
        assertEquals(1, messages[0].eventId)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `agent_response_correction with an unknown event id appends`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "kept as-is", eventId = 100))
        handler.handleIncomingEvent(
            ConversationEvent.AgentResponseCorrection(
                originalAgentResponse = "x",
                correctedAgentResponse = "y",
                eventId = 999
            )
        )

        val messages = handler.messages.value
        assertEquals(2, messages.size)
        assertEquals("kept as-is", messages[0].content)
        assertEquals(100, messages[0].eventId)
        assertEquals("y", messages[1].content)
        assertEquals(999, messages[1].eventId)
    }

    @Test
    fun `user_transcript overwrites a finalized message for the same event id`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "first final", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "revised final", eventId = 1))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("revised final", messages[0].content)
        assertEquals(1, messages[0].eventId)
        assertFalse(messages[0].isPartial)
    }

    @Test
    fun `a stale user transcript with a lower id is ignored`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "current", eventId = 20))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "stale", eventId = 10))

        val messages = handler.messages.value
        assertEquals("a stale, unmatched transcript must be ignored", 1, messages.size)
        assertEquals("current", messages[0].content)
        assertEquals(20, messages[0].eventId)
    }

    // region Message store consistency rules
    //
    // These exercise the guarantees the store maintains for any event sequence:
    // 1. Absolute order follows the arrival of finalized transcripts/responses (append-only).
    // 2. At most one in-progress (partial) user transcript exists at a time.
    // 3. User messages stay ordered by event id with no duplicates.
    // 4. Agent messages stay ordered by event id with no duplicates.
    // 5. A partial never overwrites a finalized message; older, unmatched event ids are ignored.

    @Test
    fun `rule 1 - final order follows arrival of finalized messages`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "u1", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "a1", eventId = 2))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "u2", eventId = 3))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "a2", eventId = 4))

        val messages = handler.messages.value
        assertEquals(listOf("u1", "a1", "u2", "a2"), messages.map { it.content })
        assertEquals(
            listOf(MessageRole.USER, MessageRole.AGENT, MessageRole.USER, MessageRole.AGENT),
            messages.map { it.role }
        )
    }

    @Test
    fun `rule 2 - at most one partial user transcript while an agent streams`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "u partial", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "start", text = "a", eventId = 2))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "u partial revised", eventId = 3))

        val messages = handler.messages.value
        val partialUsers = messages.filter { it.role == MessageRole.USER && it.isPartial }
        assertEquals(1, partialUsers.size)
        assertEquals("u partial revised", partialUsers[0].content)
        assertEquals(3, partialUsers[0].eventId)
        // The concurrently-streaming agent partial is untouched.
        assertEquals(1, messages.count { it.role == MessageRole.AGENT && it.isPartial })
    }

    @Test
    fun `rule 3 - user transcripts remain ordered and unique by event id`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "p", eventId = 10))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "first", eventId = 10))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "second", eventId = 20))
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "third", eventId = 30))

        val userIds = handler.messages.value.filter { it.role == MessageRole.USER }.mapNotNull { it.eventId }
        assertEquals(listOf(10, 20, 30), userIds)
        assertEquals("no duplicate user event ids", userIds.toSet().size, userIds.size)
    }

    @Test
    fun `rule 4 - agent responses remain ordered and unique by event id`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "start", text = "h", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "stop", text = "i", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "hi", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "next", eventId = 2))

        val agentIds = handler.messages.value.filter { it.role == MessageRole.AGENT }.mapNotNull { it.eventId }
        assertEquals(listOf(1, 2), agentIds)
        assertEquals("no duplicate agent event ids", agentIds.toSet().size, agentIds.size)
    }

    @Test
    fun `rule 5 - a late streaming part does not overwrite a finalized agent message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.AgentResponse(agentResponse = "final answer", eventId = 1))
        assertFalse(handler.messages.value.last().isPartial)

        handler.handleIncomingEvent(ConversationEvent.AgentChatResponsePart(partType = "delta", text = " late", eventId = 1))

        val messages = handler.messages.value
        assertEquals(1, messages.size)
        assertEquals("partial must not overwrite finalized content", "final answer", messages[0].content)
        assertFalse("partial must not downgrade a finalized message", messages[0].isPartial)
    }

    @Test
    fun `rule 5 - a new tentative does not overwrite a finalized user message`() = runTest {
        handler.handleIncomingEvent(ConversationEvent.UserTranscript(userTranscript = "committed", eventId = 1))
        handler.handleIncomingEvent(ConversationEvent.TentativeUserTranscript(userTranscript = "typing", eventId = 2))

        val messages = handler.messages.value
        assertEquals(2, messages.size)
        assertEquals("committed", messages[0].content)
        assertFalse(messages[0].isPartial)
        assertEquals("typing", messages[1].content)
        assertTrue(messages[1].isPartial)
    }
    // endregion
}
