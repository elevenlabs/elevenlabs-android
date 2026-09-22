package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.models.OutgoingEvent
import io.mockk.mockk
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression coverage for issue #76: `agent_tool_request` and `client_tool_call`
 * must be handled as distinct events. An `agent_tool_request` is a notification
 * only — it must NOT execute a registered client tool and must NOT send a
 * `client_tool_result`, otherwise a single invocation fires the tool twice
 * (the first time with empty parameters).
 */
class ConversationEventHandlerAgentToolRequestTest {

    private lateinit var audioManager: AudioManager
    private lateinit var toolRegistry: ClientToolRegistry
    private val outgoingEvents = mutableListOf<OutgoingEvent>()

    @Before
    fun setup() {
        audioManager = mockk(relaxed = true)
        toolRegistry = ClientToolRegistry()
        outgoingEvents.clear()
    }

    @After
    fun teardown() {
        toolRegistry.cleanup()
    }

    private fun handler(
        onAgentToolRequest: ((ConversationEvent.AgentToolRequest) -> Unit)? = null,
        onUnhandledClientToolCall: ((ConversationEvent.ClientToolCall) -> Unit)? = null
    ): ConversationEventHandler = ConversationEventHandler(
        audioManager = audioManager,
        toolRegistry = toolRegistry,
        messageCallback = { outgoingEvents += it },
        onAgentToolRequest = onAgentToolRequest,
        onUnhandledClientToolCall = onUnhandledClientToolCall
    )

    @Test
    fun `agent_tool_request notifies the callback without executing the registered tool`() = runTest {
        var executed = 0
        toolRegistry.registerTool("launchFlightSearchV2", object : ClientTool {
            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                executed++
                return ClientToolResult.success("ok")
            }
        })

        var notified: ConversationEvent.AgentToolRequest? = null
        val handler = handler(onAgentToolRequest = { notified = it })

        handler.handleIncomingEvent(
            ConversationEvent.AgentToolRequest(
                toolName = "launchFlightSearchV2",
                toolCallId = "call_1",
                toolType = "client",
                eventId = 38
            )
        )
        // The handler launches on its own scope; give it a turn to run.
        advanceUntilIdle()

        assertEquals("launchFlightSearchV2", notified?.toolName)
        assertEquals("call_1", notified?.toolCallId)
        assertEquals("client", notified?.toolType)
        assertEquals(38, notified?.eventId)
        assertEquals("registered tool must not be executed for an agent_tool_request", 0, executed)
        assertTrue("no client_tool_result should be sent for an agent_tool_request", outgoingEvents.none { it is OutgoingEvent.ClientToolResult })
    }

    @Test
    fun `client_tool_call still executes the registered tool and sends a result`() = runTest {
        var executed = 0
        toolRegistry.registerTool("doThing", object : ClientTool {
            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                executed++
                return ClientToolResult.success("done")
            }
        })

        val handler = handler()

        handler.handleIncomingEvent(
            ConversationEvent.ClientToolCall(
                toolName = "doThing",
                parameters = mapOf("x" to 1),
                toolCallId = "call_2",
                expectsResponse = true
            )
        )
        advanceUntilIdle()

        assertEquals("client_tool_call must still execute the registered tool", 1, executed)
        assertTrue(outgoingEvents.any { it is OutgoingEvent.ClientToolResult })
    }

    @Test
    fun `both events enabled fire the tool exactly once`() = runTest {
        // Mirrors the production scenario from issue #76: the server emits both
        // agent_tool_request and client_tool_call for the same invocation.
        var executed = 0
        toolRegistry.registerTool("search", object : ClientTool {
            override suspend fun execute(parameters: Map<String, Any>): ClientToolResult? {
                executed++
                return ClientToolResult.success("ok")
            }
        })

        val handler = handler(onAgentToolRequest = { /* notification only */ })

        handler.handleIncomingEvent(
            ConversationEvent.AgentToolRequest(
                toolName = "search",
                toolCallId = "shared_id",
                toolType = "client",
                eventId = 1
            )
        )
        handler.handleIncomingEvent(
            ConversationEvent.ClientToolCall(
                toolName = "search",
                parameters = mapOf("q" to "shoes"),
                toolCallId = "shared_id",
                expectsResponse = true
            )
        )
        advanceUntilIdle()

        assertEquals("tool must run exactly once, not twice", 1, executed)
        val results = outgoingEvents.filterIsInstance<OutgoingEvent.ClientToolResult>()
        assertEquals(1, results.size)
        assertEquals("shared_id", results.single().toolCallId)
    }
}
