package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.models.OutgoingEvent
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A client tool answer must not crash the host app when the connection is closed, either
 * because the session ended while the tool ran or because the socket dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationEventHandlerClientToolTest {

    private lateinit var toolRegistry: ClientToolRegistry
    private val outgoingEvents = mutableListOf<OutgoingEvent>()
    private var sendAttempts = 0

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        toolRegistry = ClientToolRegistry()
    }

    @After
    fun teardown() {
        toolRegistry.cleanup()
        Dispatchers.resetMain()
    }

    private fun handler(messageCallback: (OutgoingEvent) -> Unit = { outgoingEvents += it }) =
        ConversationEventHandler(
            audioManager = mockk(relaxed = true),
            toolRegistry = toolRegistry,
            messageCallback = messageCallback
        )

    private val closedConnection: (OutgoingEvent) -> Unit = {
        sendAttempts++
        throw IllegalStateException("Not connected")
    }

    private fun tool(block: suspend () -> ClientToolResult?) = object : ClientTool {
        override suspend fun execute(parameters: Map<String, Any>) = block()
    }

    private fun toolCall(name: String = "tool") = ConversationEvent.ClientToolCall(
        toolName = name,
        toolCallId = "call_1",
        parameters = emptyMap(),
        expectsResponse = true
    )

    @Test
    fun `tool result is sent while the session is active`() = runTest {
        toolRegistry.registerTool("tool", tool { ClientToolResult.success("ok") })

        handler().handleIncomingEvent(toolCall())

        assertEquals("ok", (outgoingEvents.single() as OutgoingEvent.ClientToolResult).result)
    }

    @Test
    fun `tool result on a closed connection is dropped instead of thrown`() = runTest {
        toolRegistry.registerTool("tool", tool { ClientToolResult.success("ok") })

        handler(closedConnection).handleIncomingEvent(toolCall())

        assertEquals(1, sendAttempts)
    }

    @Test
    fun `failure for an unregistered tool on a closed connection is dropped instead of thrown`() = runTest {
        handler(closedConnection).handleIncomingEvent(toolCall("missing"))

        assertEquals(1, sendAttempts)
    }

    @Test
    fun `ending the session during a tool sends nothing`() = runTest {
        var started = false
        toolRegistry.registerTool("tool", tool { started = true; awaitCancellation() })
        val handler = handler()

        handler.handleIncomingEvent(toolCall())
        handler.cleanup()

        assertTrue(started)
        assertTrue(outgoingEvents.isEmpty())
    }

    @Test
    fun `ending the session during a function tool sends nothing`() = runTest {
        var started = false
        toolRegistry = ClientToolRegistryBuilder()
            .addTool("tool") { started = true; awaitCancellation() }
            .build()
        val handler = handler()

        handler.handleIncomingEvent(toolCall())
        handler.cleanup()

        assertTrue(started)
        assertTrue(outgoingEvents.isEmpty())
    }

    @Test
    fun `a tool that throws its own CancellationException still gets a failure answer`() = runTest {
        toolRegistry.registerTool("tool", tool { throw CancellationException("cancelled by the tool") })

        handler().handleIncomingEvent(toolCall())

        assertTrue((outgoingEvents.single() as OutgoingEvent.ClientToolResult).isError)
    }
}
