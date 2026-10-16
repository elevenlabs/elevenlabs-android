package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationMode.LISTENING
import io.elevenlabs.models.ConversationMode.SPEAKING
import io.elevenlabs.network.BaseConnection
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [ConversationSession.mode] and [ConversationConfig.onModeChange] report the same transitions
 * (https://github.com/elevenlabs/elevenlabs-android/issues/66).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationSessionModeTest {

    private lateinit var connection: BaseConnection
    private val onMessage = slot<(String) -> Unit>()
    private val onAgentSpeaking = slot<(Boolean) -> Unit>()
    private val callbackModes = mutableListOf<ConversationMode>()

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        connection = mockk(relaxed = true)
        every { connection.setOnMessageListener(capture(onMessage)) } just Runs
        every { connection.setOnAgentSpeakingListener(capture(onAgentSpeaking)) } just Runs
        coEvery { connection.connect(any(), any()) } just Runs
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `voice mode returns to listening when the agent stops speaking after a response`() = runTest {
        val session = startSession(textOnly = false)

        onMessage.captured(agentResponse("Hello, this is a short test greeting.", eventId = 1))
        onAgentSpeaking.captured(true)
        onAgentSpeaking.captured(false)

        assertEquals(LISTENING, session.mode.value)
        session.endSession()
    }

    @Test
    fun `voice mode flow and callback report the same transitions without repeats`() = runTest {
        val session = startSession(textOnly = false)
        val flowModes = mutableListOf(session.mode.value)
        val collector = backgroundScope.launchCollecting(session, flowModes)

        onMessage.captured(agentResponse("First turn.", eventId = 1))
        repeat(3) { onAgentSpeaking.captured(true) }
        repeat(3) { onAgentSpeaking.captured(false) }
        onMessage.captured(agentResponse("Second turn.", eventId = 2))
        onAgentSpeaking.captured(true)
        onAgentSpeaking.captured(false)

        assertEquals(listOf(LISTENING, SPEAKING, LISTENING, SPEAKING, LISTENING), flowModes)
        assertEquals(listOf(SPEAKING, LISTENING, SPEAKING, LISTENING), callbackModes)
        collector.cancel()
        session.endSession()
    }

    @Test
    fun `voice mode follows the audio, not a response that arrives after the agent went quiet`() = runTest {
        val session = startSession(textOnly = false)

        onAgentSpeaking.captured(true)
        onAgentSpeaking.captured(false)
        onMessage.captured(agentResponse("Late text.", eventId = 1))

        assertEquals(LISTENING, session.mode.value)
        session.endSession()
    }

    @Test
    fun `interruption switches voice mode to listening`() = runTest {
        val session = startSession(textOnly = false)

        onAgentSpeaking.captured(true)
        onMessage.captured("""{"type":"interruption","interruption_event":{"event_id":3}}""")

        assertEquals(LISTENING, session.mode.value)
        assertEquals(listOf(SPEAKING, LISTENING), callbackModes)
        session.endSession()
    }

    @Test
    fun `text-only mode follows streamed response parts and reports them to the callback`() = runTest {
        val session = startSession(textOnly = true)

        onMessage.captured(responsePart("start", eventId = 1))
        assertEquals(SPEAKING, session.mode.value)
        onMessage.captured(responsePart("stop", eventId = 1))

        assertEquals(LISTENING, session.mode.value)
        assertEquals(listOf(SPEAKING, LISTENING), callbackModes)
        session.endSession()
    }

    @Test
    fun `text-only mode leaves the agent listening after a response that was not streamed`() = runTest {
        val session = startSession(textOnly = true)

        onMessage.captured(agentResponse("Hello, this is a short test greeting.", eventId = 1))

        assertEquals(LISTENING, session.mode.value)
        assertEquals(emptyList<ConversationMode>(), callbackModes)
        session.endSession()
    }

    @Test
    fun `ending the session while the agent speaks returns to listening`() = runTest {
        val session = startSession(textOnly = false)

        onAgentSpeaking.captured(true)
        session.endSession()

        assertEquals(LISTENING, session.mode.value)
        assertEquals(listOf(SPEAKING, LISTENING), callbackModes)
    }

    private suspend fun startSession(textOnly: Boolean): ConversationSession {
        val audioManager = mockk<AudioManager>(relaxed = true)
        val session = ConversationSessionImpl(
            context = mockk(relaxed = true),
            config = ConversationConfig(
                agentId = "agent-id",
                textOnly = textOnly,
                onModeChange = { callbackModes += it }
            ),
            connection = connection,
            audioManager = audioManager,
            toolRegistry = ClientToolRegistry()
        )
        session.start()
        return session
    }

    private fun CoroutineScope.launchCollecting(
        session: ConversationSession,
        into: MutableList<ConversationMode>
    ) = launch(UnconfinedTestDispatcher()) {
        session.mode.collect { if (it != into.last()) into += it }
    }

    private fun agentResponse(text: String, eventId: Int) =
        """{"type":"agent_response","agent_response_event":{"agent_response":"$text","event_id":$eventId}}"""

    private fun responsePart(type: String, eventId: Int) =
        """{"type":"agent_chat_response_part","text_response_part":{"text":"Hi","type":"$type","event_id":$eventId}}"""
}
