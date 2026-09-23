package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.network.BaseConnection
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Verifies a throwing user-provided callback is logged, not swallowed, and doesn't crash the session or break the event stream. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionCallbackErrorHandlingTest {

    private lateinit var audioManager: AudioManager
    private lateinit var toolRegistry: ClientToolRegistry
    private lateinit var connection: BaseConnection
    private val onMessage = slot<(String) -> Unit>()

    @Before
    fun setup() {
        audioManager = mockk(relaxed = true)
        toolRegistry = ClientToolRegistry()
        connection = mockk<BaseConnection>(relaxed = true)
        every { connection.setOnMessageListener(capture(onMessage)) } just Runs
        coEvery { connection.connect(any(), any()) } just Runs
    }

    @After
    fun teardown() {
        toolRegistry.cleanup()
    }

    @Suppress("DEPRECATION")
    private suspend fun startSession(config: ConversationConfig): ConversationSessionImpl {
        val session = ConversationSessionImpl(
            context = mockk(relaxed = true),
            config = config,
            connection = connection,
            audioManager = audioManager,
            toolRegistry = toolRegistry
        )
        session.start()
        return session
    }

    private fun deliver(json: String) {
        onMessage.captured(json)
    }

    @Test
    fun `throwing onVadScore is logged and does not crash the session or break the event stream`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var agentResponses = 0
            val session = startSession(
                ConversationConfig(
                    agentId = "agent-id",
                    textOnly = true,
                    onVadScore = { throw RuntimeException("vad boom") },
                    onAgentResponse = { agentResponses++ }
                )
            )

            deliver("""{"type":"vad_score","vad_score_event":{"vad_score":0.5}}""")
            deliver("""{"type":"agent_response","agent_response_event":{"agent_response":"hi","event_id":1}}""")
            assertEquals(1, agentResponses)

            session.endSession()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `throwing onAgentResponse is logged and does not break later callbacks`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var agentResponses = 0
            var vadCalls = 0
            val session = startSession(
                ConversationConfig(
                    agentId = "agent-id",
                    textOnly = true,
                    onAgentResponse = { throw IllegalStateException("agent boom") },
                    onVadScore = { vadCalls++ }
                )
            )

            deliver("""{"type":"agent_response","agent_response_event":{"agent_response":"hi","event_id":1}}""")
            assertEquals(0, agentResponses)

            deliver("""{"type":"vad_score","vad_score_event":{"vad_score":0.9}}""")
            assertEquals(1, vadCalls)

            session.endSession()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `throwing onUnhandledClientToolCall is logged and does not crash the session`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var unhandledCalls = 0
            var vadCalls = 0
            val session = startSession(
                ConversationConfig(
                    agentId = "agent-id",
                    textOnly = true,
                    onUnhandledClientToolCall = { event ->
                        unhandledCalls++
                        throw RuntimeException("unhandled boom for ${event.toolName}")
                    },
                    onVadScore = { vadCalls++ }
                )
            )

            deliver(
                """{"type":"client_tool_call","client_tool_call":{"tool_name":"missing","tool_call_id":"call-1","parameters":{},"expects_response":false}}"""
            )
            assertEquals(1, unhandledCalls)

            deliver("""{"type":"vad_score","vad_score_event":{"vad_score":0.2}}""")
            assertEquals(1, vadCalls)

            session.endSession()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `throwing onAgentResponse then a second agent_response still invokes the callback`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val responses = mutableListOf<String>()
            val session = startSession(
                ConversationConfig(
                    agentId = "agent-id",
                    textOnly = true,
                    onAgentResponse = { response ->
                        if (response == "boom") throw RuntimeException("first boom")
                        responses.add(response)
                    }
                )
            )

            deliver("""{"type":"agent_response","agent_response_event":{"agent_response":"boom","event_id":1}}""")
            assertTrue("first (throwing) response must not be recorded", responses.isEmpty())

            deliver("""{"type":"agent_response","agent_response_event":{"agent_response":"ok","event_id":2}}""")
            assertEquals(listOf("ok"), responses)

            session.endSession()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `throwing onAgentToolRequest is logged and does not crash the session or break the event stream`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            var toolRequests = 0
            var vadCalls = 0
            val session = startSession(
                ConversationConfig(
                    agentId = "agent-id",
                    textOnly = true,
                    onAgentToolRequest = { request ->
                        toolRequests++
                        throw RuntimeException("tool request boom for ${request.toolName}")
                    },
                    onVadScore = { vadCalls++ }
                )
            )

            deliver(
                """{"type":"agent_tool_request","agent_tool_request":{"tool_name":"search","tool_call_id":"call-1","tool_type":"client","event_id":38}}"""
            )
            assertEquals(1, toolRequests)

            deliver("""{"type":"vad_score","vad_score_event":{"vad_score":0.7}}""")
            assertEquals(1, vadCalls)

            session.endSession()
        } finally {
            Dispatchers.resetMain()
        }
    }
}
