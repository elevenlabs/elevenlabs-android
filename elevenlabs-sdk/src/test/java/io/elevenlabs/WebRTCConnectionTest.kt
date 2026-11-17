package io.elevenlabs

import android.content.Context
import io.elevenlabs.models.DisconnectionDetails
import io.elevenlabs.network.WebRTCConnection
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.EventListenable
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Drives [WebRTCConnection] through a mocked LiveKit [Room] whose events the test emits, in
 * the order LiveKit's own Room posts them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebRTCConnectionTest {

    private val roomEvents = MutableSharedFlow<RoomEvent>(extraBufferCapacity = 16)
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private val disconnects = CopyOnWriteArrayList<DisconnectionDetails>()
    private lateinit var room: Room

    @Before
    fun setup() {
        room = mockk(relaxed = true)
        every { room.events } returns object : EventListenable<RoomEvent> {
            override val events: SharedFlow<RoomEvent> = roomEvents
        }
        every { room.state } returns Room.State.CONNECTED
        every { room.name } returns "room_conv_123"
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun connect(): WebRTCConnection {
        val connection = WebRTCConnection(mockk<Context>(relaxed = true), room, scope)
        val config = ConversationConfig(
            agentId = "agent-xyz",
            conversationToken = "token",
            onDisconnect = { details -> disconnects.add(details) }
        )
        runBlocking { connection.connect("wss://livekit.test", config) }
        return connection
    }

    private fun emit(event: RoomEvent) = runBlocking { roomEvents.emit(event) }

    private fun roomDisconnected(reason: DisconnectReason) = RoomEvent.Disconnected(room, null, reason)

    private fun agentLeft() = RoomEvent.ParticipantDisconnected(
        room,
        mockk<RemoteParticipant>(relaxed = true) {
            every { identity } returns Participant.Identity("agent_123")
            every { sid } returns Participant.Sid("PA_agent")
        }
    )

    @Test
    fun `a room the server closes ends the session once as the agent`() {
        val connection = connect()

        emit(roomDisconnected(DisconnectReason.ROOM_DELETED))
        connection.disconnect()

        assertEquals(listOf<DisconnectionDetails>(DisconnectionDetails.Agent), disconnects.toList())
    }

    @Test
    fun `a room lost to a failure ends the session once as an error`() {
        val connection = connect()

        emit(roomDisconnected(DisconnectReason.SIGNAL_CLOSE))
        connection.disconnect()

        assertEquals(1, disconnects.size)
        assertTrue(
            "expected Error, got ${disconnects.single()}",
            disconnects.single() is DisconnectionDetails.Error
        )
    }

    @Test
    fun `the agent leaving a connected room ends the session once as the agent`() {
        connect()

        emit(agentLeft())
        emit(roomDisconnected(DisconnectReason.ROOM_DELETED))

        assertEquals(listOf<DisconnectionDetails>(DisconnectionDetails.Agent), disconnects.toList())
    }

    @Test
    fun `the agent dropping out of a failing room is reported as an error`() {
        connect()

        // Room.handleDisconnect marks the room DISCONNECTED, drops every remote participant
        // and only then posts RoomEvent.Disconnected with the reason.
        every { room.state } returns Room.State.DISCONNECTED
        emit(agentLeft())
        emit(roomDisconnected(DisconnectReason.SIGNAL_CLOSE))

        assertEquals(1, disconnects.size)
        assertTrue(
            "expected Error, got ${disconnects.single()}",
            disconnects.single() is DisconnectionDetails.Error
        )
    }

    @Test
    fun `the agent dropping out during a full reconnect is reported as an error`() {
        connect()

        every { room.state } returns Room.State.RECONNECTING
        emit(agentLeft())

        assertEquals(1, disconnects.size)
        assertTrue(
            "expected Error, got ${disconnects.single()}",
            disconnects.single() is DisconnectionDetails.Error
        )
    }

    @Test
    fun `the agent dropping out of a room the server deletes is reported as the agent`() {
        connect()

        every { room.state } returns Room.State.DISCONNECTED
        emit(agentLeft())
        emit(roomDisconnected(DisconnectReason.ROOM_DELETED))

        assertEquals(listOf<DisconnectionDetails>(DisconnectionDetails.Agent), disconnects.toList())
    }

    @Test
    fun `repeated disconnect calls notify the application once`() {
        val connection = connect()

        connection.disconnect()
        connection.disconnect()
        connection.disconnect()

        assertEquals(listOf<DisconnectionDetails>(DisconnectionDetails.User), disconnects.toList())
    }

    @Test
    fun `a new session on the same connection is notified again`() {
        val connection = connect()
        connection.disconnect()

        runBlocking {
            connection.connect(
                "wss://livekit.test",
                ConversationConfig(
                    agentId = "agent-xyz",
                    conversationToken = "token",
                    onDisconnect = { details -> disconnects.add(details) }
                )
            )
        }
        connection.disconnect()

        assertEquals(
            listOf<DisconnectionDetails>(DisconnectionDetails.User, DisconnectionDetails.User),
            disconnects.toList()
        )
    }
}
