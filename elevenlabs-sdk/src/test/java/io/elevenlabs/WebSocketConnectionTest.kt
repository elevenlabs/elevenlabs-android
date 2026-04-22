package io.elevenlabs

import io.elevenlabs.models.DisconnectionDetails
import io.elevenlabs.network.ConnectionState
import io.elevenlabs.network.WebSocketConnection
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class WebSocketConnectionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val serverSockets = ConcurrentLinkedQueue<WebSocket>()
    private val connections = ConcurrentLinkedQueue<WebSocketConnection>()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>()) } returns 0
        every { android.util.Log.e(any(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.cleanup() } }
        // Close server-side sockets so MockWebServer's task queue can drain.
        serverSockets.forEach { runCatching { it.close(1000, "test teardown") } }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        server.shutdown()
        unmockkAll()
    }

    private fun wsBaseUrl(): String =
        server.url("/").toString()
            .removeSuffix("/")
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://")

    /** Tracks created connections so tearDown can clean them up. */
    private fun newConnection(): WebSocketConnection =
        WebSocketConnection(client = client).also { connections.add(it) }

    /**
     * Enqueue a WebSocket upgrade that records the server-side socket and lets the
     * caller hook into onOpen / onMessage. Server sockets are tracked so tearDown
     * can close them deterministically.
     */
    private fun enqueueServerWs(
        onOpen: ((WebSocket) -> Unit)? = null,
        onMessage: ((WebSocket, String) -> Unit)? = null,
    ) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
                onOpen?.invoke(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage?.invoke(webSocket, text)
            }
        }))
    }

    @Test
    fun `buildWebSocketUrl emits agent_id for public agents`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "wss://api.elevenlabs.io",
            token = "",
            agentId = "agent-123"
        )
        assertEquals("wss://api.elevenlabs.io/v1/convai/conversation?agent_id=agent-123", url)
    }

    @Test
    fun `buildWebSocketUrl emits agent_id and signature for private agents`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "wss://api.elevenlabs.io",
            token = "sig-abc",
            agentId = "agent-123"
        )
        assertEquals(
            "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=agent-123&conversation_signature=sig-abc",
            url
        )
    }

    @Test
    fun `buildWebSocketUrl emits signature alone when agent_id is missing`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "wss://api.elevenlabs.io",
            token = "sig-abc",
            agentId = null
        )
        assertEquals(
            "wss://api.elevenlabs.io/v1/convai/conversation?conversation_signature=sig-abc",
            url
        )
    }

    @Test
    fun `buildWebSocketUrl trims trailing slashes`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "wss://api.elevenlabs.io/",
            token = "",
            agentId = "agent-123"
        )
        assertEquals("wss://api.elevenlabs.io/v1/convai/conversation?agent_id=agent-123", url)
    }

    @Test
    fun `buildWebSocketUrl requires agentId or signature`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSocketConnection.buildWebSocketUrl(
                "wss://api.elevenlabs.io",
                token = "",
                agentId = null
            )
        }
    }

    @Test
    fun `connect opens WebSocket and sends initiation payload on open`() {
        val firstFrame = AtomicReference<String>()
        val opened = CountDownLatch(1)
        val initiationReceived = CountDownLatch(1)

        enqueueServerWs(
            onOpen = { opened.countDown() },
            onMessage = { _, text ->
                if (firstFrame.compareAndSet(null, text)) initiationReceived.countDown()
            }
        )

        val connection = newConnection()
        runBlocking {
            connection.connect("", wsBaseUrl(), ConversationConfig(agentId = "agent-xyz"))
        }

        assertTrue("server saw open", opened.await(3, TimeUnit.SECONDS))
        assertTrue("server received initiation", initiationReceived.await(3, TimeUnit.SECONDS))

        val payload = firstFrame.get()
        assertNotNull(payload)
        assertTrue(
            "first frame should be conversation_initiation_client_data, got: $payload",
            payload.contains("\"type\":\"conversation_initiation_client_data\"")
        )
    }

    @Test
    fun `incoming messages are forwarded to message listener`() {
        val ready = CountDownLatch(1)
        val serverSocket = AtomicReference<WebSocket>()
        enqueueServerWs(onOpen = { ws ->
            serverSocket.set(ws)
            ready.countDown()
        })

        val received = CountDownLatch(1)
        val payload = AtomicReference<String>()
        val connection = newConnection()
        connection.setOnMessageListener { msg ->
            if (payload.compareAndSet(null, msg)) received.countDown()
        }

        runBlocking {
            connection.connect("", wsBaseUrl(), ConversationConfig(agentId = "agent-xyz"))
        }
        assertTrue(ready.await(3, TimeUnit.SECONDS))

        serverSocket.get().send("""{"type":"agent_response","agent_response_event":{"agent_response":"hi"}}""")

        assertTrue("listener saw message", received.await(3, TimeUnit.SECONDS))
        assertTrue(payload.get().contains("agent_response"))
    }

    @Test
    fun `onConnect fires with conversation id from initiation metadata`() {
        val ready = CountDownLatch(1)
        val serverSocket = AtomicReference<WebSocket>()
        enqueueServerWs(onOpen = { ws ->
            serverSocket.set(ws)
            ready.countDown()
        })

        val onConnectFired = CountDownLatch(1)
        val capturedId = AtomicReference<String>()
        val config = ConversationConfig(
            agentId = "agent-xyz",
            onConnect = { id ->
                if (capturedId.compareAndSet(null, id)) onConnectFired.countDown()
            }
        )
        val connection = newConnection()
        runBlocking { connection.connect("", wsBaseUrl(), config) }
        assertTrue(ready.await(3, TimeUnit.SECONDS))

        serverSocket.get().send(
            """{"type":"conversation_initiation_metadata","conversation_initiation_metadata":{"conversation_id":"conv_42","agent_output_audio_format":"pcm_16000","user_input_audio_format":"pcm_16000"}}"""
        )

        assertTrue("onConnect fired", onConnectFired.await(3, TimeUnit.SECONDS))
        assertEquals("conv_42", capturedId.get())
    }

    @Test
    fun `server-initiated normal close maps to User and DISCONNECTED state`() {
        val ready = CountDownLatch(1)
        val serverSocket = AtomicReference<WebSocket>()
        enqueueServerWs(onOpen = { ws ->
            serverSocket.set(ws)
            ready.countDown()
        })

        val disconnected = CountDownLatch(1)
        val capturedDetails = AtomicReference<DisconnectionDetails>()
        val capturedState = AtomicReference<ConnectionState>()
        val config = ConversationConfig(
            agentId = "agent-xyz",
            onDisconnect = { details ->
                if (capturedDetails.compareAndSet(null, details)) disconnected.countDown()
            }
        )

        val connection = newConnection()
        connection.setOnConnectionStateListener { state ->
            if (state == ConnectionState.DISCONNECTED) capturedState.set(state)
        }

        runBlocking { connection.connect("", wsBaseUrl(), config) }
        assertTrue(ready.await(3, TimeUnit.SECONDS))

        serverSocket.get().close(1000, "bye")

        assertTrue("onDisconnect fired", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue(capturedDetails.get() is DisconnectionDetails.User)
        assertEquals(ConnectionState.DISCONNECTED, capturedState.get())
    }

    @Test
    fun `server-initiated abnormal close maps to Error`() {
        val ready = CountDownLatch(1)
        val serverSocket = AtomicReference<WebSocket>()
        enqueueServerWs(onOpen = { ws ->
            serverSocket.set(ws)
            ready.countDown()
        })

        val disconnected = CountDownLatch(1)
        val captured = AtomicReference<DisconnectionDetails>()
        val config = ConversationConfig(
            agentId = "agent-xyz",
            onDisconnect = { details ->
                if (captured.compareAndSet(null, details)) disconnected.countDown()
            }
        )

        val connection = newConnection()
        runBlocking { connection.connect("", wsBaseUrl(), config) }
        assertTrue(ready.await(3, TimeUnit.SECONDS))

        serverSocket.get().close(1011, "internal error")

        assertTrue("onDisconnect fired", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue(
            "expected Error, got ${captured.get()}",
            captured.get() is DisconnectionDetails.Error
        )
    }

    @Test
    fun `transport failure maps to Error and ERROR state`() {
        // Enqueue a non-upgrade response so the WebSocket handshake fails.
        server.enqueue(MockResponse().setResponseCode(500))

        val disconnected = CountDownLatch(1)
        val captured = AtomicReference<DisconnectionDetails>()
        val config = ConversationConfig(
            agentId = "agent-xyz",
            onDisconnect = { details ->
                if (captured.compareAndSet(null, details)) disconnected.countDown()
            }
        )

        val connection = newConnection()
        runBlocking { connection.connect("", wsBaseUrl(), config) }

        assertTrue("onDisconnect fired", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue(captured.get() is DisconnectionDetails.Error)
        assertEquals(ConnectionState.ERROR, connection.connectionState)
    }
}
