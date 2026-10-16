package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.network.BaseConnection
import io.elevenlabs.network.ConversationEventParser
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Server events from the agent protocol that the Swift and JS SDKs handle:
 * agent_response_complete, guardrail_triggered and client_error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerEventsTest {

    private fun parse(json: String) = ConversationEventParser.parseIncomingEvent(json)

    @Test
    fun `agent_response_complete is parsed with its event id`() {
        assertEquals(
            ConversationEvent.AgentResponseComplete(eventId = 12),
            parse("""{"type":"agent_response_complete","agent_response_complete_event":{"event_id":12}}""")
        )
    }

    @Test
    fun `guardrail_triggered is parsed with the guardrail name when present`() {
        assertEquals(
            ConversationEvent.GuardrailTriggered(guardrailName = "no_medical_advice"),
            parse("""{"type":"guardrail_triggered","guardrail_triggered_event":{"guardrail_name":"no_medical_advice"}}""")
        )
        assertEquals(
            ConversationEvent.GuardrailTriggered(guardrailName = null),
            parse("""{"type":"guardrail_triggered"}""")
        )
    }

    @Test
    fun `client_error is parsed like a server error`() {
        assertEquals(
            ConversationEvent.ServerError(code = 1008, message = "Too many requests"),
            parse("""{"type":"client_error","error_event":{"code":1008,"error_name":"rate_limited","message":"Too many requests"}}""")
        )
        assertEquals(
            ConversationEvent.ServerError(code = 1011, message = "internal_error"),
            parse("""{"type":"client_error","error_event":{"code":1011,"error_name":"internal_error"}}""")
        )
    }

    @Test
    fun `session reports the new events through its config callbacks`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val completed = mutableListOf<Int>()
            val guardrails = mutableListOf<String?>()
            val errors = mutableListOf<Pair<Int, String?>>()
            val connection = mockk<BaseConnection>(relaxed = true)
            val onMessage = slot<(String) -> Unit>()
            every { connection.setOnMessageListener(capture(onMessage)) } just Runs
            coEvery { connection.connect(any(), any()) } just Runs
            val session = ConversationSessionImpl(
                context = mockk(relaxed = true),
                config = ConversationConfig(
                    agentId = "agent-id",
                    textOnly = true,
                    onAgentResponseComplete = { completed += it },
                    onGuardrailTriggered = { guardrails += it },
                    onError = { code, message -> errors += code to message }
                ),
                connection = connection,
                audioManager = mockk<AudioManager>(relaxed = true),
                toolRegistry = ClientToolRegistry()
            )
            session.start()

            onMessage.captured("""{"type":"agent_response_complete","agent_response_complete_event":{"event_id":12}}""")
            onMessage.captured("""{"type":"guardrail_triggered","guardrail_triggered_event":{"guardrail_name":"pii"}}""")
            onMessage.captured("""{"type":"client_error","error_event":{"code":1008,"error_name":"rate_limited","message":"Slow down"}}""")

            assertEquals(listOf(12), completed)
            assertEquals(listOf<String?>("pii"), guardrails)
            assertEquals(listOf(1008 to "Slow down"), errors)
            session.endSession()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a throwing callback does not stop later events`() = runTest {
        val completed = mutableListOf<Int>()
        val handler = ConversationEventHandler(
            audioManager = mockk(relaxed = true),
            toolRegistry = ClientToolRegistry(),
            messageCallback = { },
            onGuardrailTriggered = { throw IllegalStateException("boom") },
            onAgentResponseComplete = { completed += it }
        )

        handler.handleIncomingEvent(ConversationEvent.GuardrailTriggered("pii"))
        handler.handleIncomingEvent(ConversationEvent.AgentResponseComplete(3))

        assertEquals(listOf(3), completed)
    }
}
