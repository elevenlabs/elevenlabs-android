package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.DisconnectionDetails
import io.elevenlabs.network.BaseConnection
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionDisconnectTest {

    private lateinit var connection: BaseConnection
    private val onMessage = slot<(String) -> Unit>()

    @Before
    fun setup() {
        // The session and its event handler both dispatch onto Dispatchers.Main.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        connection = mockk(relaxed = true)
        every { connection.setOnMessageListener(capture(onMessage)) } just Runs
        coEvery { connection.connect(any(), any()) } just Runs
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun startSession(): ConversationSessionImpl =
        ConversationSessionImpl(
            context = mockk(relaxed = true),
            config = ConversationConfig(agentId = "agent-id", textOnly = true),
            connection = connection,
            audioManager = mockk<AudioManager>(relaxed = true),
            toolRegistry = ClientToolRegistry()
        ).also { it.start() }

    @Test
    fun `agent end_call ends the session as the agent`() = runTest {
        startSession()

        onMessage.captured(
            """{"type":"agent_tool_response","agent_tool_response":{"tool_name":"end_call","tool_call_id":"call-1","tool_type":"system","is_error":false}}"""
        )

        verify(exactly = 1) { connection.disconnect(DisconnectionDetails.Agent) }
        verify(exactly = 0) { connection.disconnect(DisconnectionDetails.User) }
    }

    @Test
    fun `endSession ends the session as the user`() = runTest {
        val session = startSession()

        session.endSession()

        verify(exactly = 1) { connection.disconnect(DisconnectionDetails.User) }
        verify(exactly = 0) { connection.disconnect(DisconnectionDetails.Agent) }
    }
}
