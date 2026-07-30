package io.elevenlabs

import io.elevenlabs.models.ConversationStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class WebSocketConnectionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val serverSockets = ConcurrentLinkedQueue<WebSocket>()
    private val connections = ConcurrentLinkedQueue<WebSocketConnection>()

    /** Every message the SDK logged, used to wait for asynchronous listener callbacks. */
    private val logMessages = ConcurrentLinkedQueue<String>()
    private var transportKilled = false

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any<String>()) } answers { logMessages.add(secondArg()); 0 }
        every { android.util.Log.e(any(), any<String>()) } answers { logMessages.add(secondArg()); 0 }
        every { android.util.Log.e(any(), any<String>(), any()) } answers { logMessages.add(secondArg()); 0 }
    }

    @After
    fun tearDown() {
        connections.forEach { runCatching { it.cleanup() } }
        // Close server-side sockets so MockWebServer's task queue can drain.
        serverSockets.forEach { runCatching { it.close(1000, "test teardown") } }
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
        if (!transportKilled) runCatching { server.shutdown() }
        unmockkAll()
    }

    /**
     * Returns the MockWebServer base URL with the http:// scheme intact. WebSocketConnection
     * is responsible for swapping to ws://, so we exercise that path in every test.
     */
    private fun apiBaseUrl(): String = server.url("/").toString().removeSuffix("/")

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
        onClosing: ((WebSocket, Int, String) -> Unit)? = null,
    ) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                serverSockets.add(webSocket)
                onOpen?.invoke(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                onMessage?.invoke(webSocket, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                onClosing?.invoke(webSocket, code, reason)
            }
        }))
    }

    /**
     * Drops the live TCP connection without a WebSocket close handshake, which is what
     * makes okhttp's reader thread fail with EOFException instead of calling onClosed.
     */
    private fun killTransport() {
        transportKilled = true
        runCatching { server.shutdown() }
    }

    /** Polls [condition] until it holds or [timeoutMs] elapses. */
    private fun awaitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun loggedMessageContaining(fragment: String): Boolean =
        logMessages.any { it.contains(fragment) }

    @Test
    fun `buildWebSocketUrl converts https endpoint to wss`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "https://api.elevenlabs.io",
            signedUrl = null,
            agentId = "agent-123"
        )
        assertEquals("wss://api.elevenlabs.io/v1/convai/conversation?agent_id=agent-123", url)
    }

    @Test
    fun `buildWebSocketUrl converts http endpoint to ws`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "http://localhost:8080",
            signedUrl = null,
            agentId = "agent-123"
        )
        assertEquals("ws://localhost:8080/v1/convai/conversation?agent_id=agent-123", url)
    }

    @Test
    fun `buildWebSocketUrl preserves wss scheme`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "wss://api.eu.residency.elevenlabs.io",
            signedUrl = null,
            agentId = "agent-123"
        )
        assertEquals(
            "wss://api.eu.residency.elevenlabs.io/v1/convai/conversation?agent_id=agent-123",
            url
        )
    }

    @Test
    fun `buildWebSocketUrl rejects a signedUrl that is not a ws URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSocketConnection.buildWebSocketUrl(
                "https://api.elevenlabs.io",
                signedUrl = "raw-signature-not-a-url",
                agentId = "agent-123"
            )
        }
    }

    @Test
    fun `buildWebSocketUrl trims trailing slashes`() {
        val url = WebSocketConnection.buildWebSocketUrl(
            "https://api.elevenlabs.io/",
            signedUrl = null,
            agentId = "agent-123"
        )
        assertEquals("wss://api.elevenlabs.io/v1/convai/conversation?agent_id=agent-123", url)
    }

    @Test
    fun `buildWebSocketUrl returns signedUrl verbatim when wss`() {
        val signed = "wss://api.elevenlabs.io/v1/convai/conversation?agent_id=X&conversation_signature=SIG"
        val url = WebSocketConnection.buildWebSocketUrl(
            "https://api.elevenlabs.io",
            signedUrl = signed,
            agentId = null
        )
        assertEquals(signed, url)
    }

    @Test
    fun `buildWebSocketUrl returns signedUrl verbatim when ws`() {
        val signed = "ws://localhost:1234/v1/convai/conversation?agent_id=X&conversation_signature=SIG"
        val url = WebSocketConnection.buildWebSocketUrl(
            "http://localhost:1234",
            signedUrl = signed,
            agentId = null
        )
        assertEquals(signed, url)
    }

    @Test
    fun `buildWebSocketUrl requires agentId when no signedUrl is provided`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebSocketConnection.buildWebSocketUrl(
                "https://api.elevenlabs.io",
                signedUrl = null,
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
            connection.connect(apiBaseUrl(), ConversationConfig(agentId = "agent-xyz"))
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
            connection.connect(apiBaseUrl(), ConversationConfig(agentId = "agent-xyz"))
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
        runBlocking { connection.connect(apiBaseUrl(), config) }
        assertTrue(ready.await(3, TimeUnit.SECONDS))

        // Production server nests under conversation_initiation_metadata_event.
        serverSocket.get().send(
            """{"type":"conversation_initiation_metadata","conversation_initiation_metadata_event":{"conversation_id":"conv_42","agent_output_audio_format":"pcm_16000","user_input_audio_format":"pcm_16000"}}"""
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

        runBlocking { connection.connect(apiBaseUrl(), config) }
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
        runBlocking { connection.connect(apiBaseUrl(), config) }
        assertTrue(ready.await(3, TimeUnit.SECONDS))

        serverSocket.get().close(1011, "internal error")

        assertTrue("onDisconnect fired", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue(
            "expected Error, got ${captured.get()}",
            captured.get() is DisconnectionDetails.Error
        )
    }

    /**
     * Regression test for https://github.com/elevenlabs/elevenlabs-android/issues/67.
     *
     * disconnect() only starts the close handshake. If the peer drops the TCP connection
     * before its close frame arrives, okhttp's reader thread reports EOFException through
     * onFailure - long after we already reported a clean, user-initiated disconnect. That
     * late failure must not be published as ERROR.
     */
    @Test
    fun `client-initiated disconnect stays IDLE when the transport fails afterwards`() {
        val ready = CountDownLatch(1)
        enqueueServerWs(onOpen = { ready.countDown() })

        val observedStates = ConcurrentLinkedQueue<ConnectionState>()
        val observedStatuses = ConcurrentLinkedQueue<ConversationStatus>()
        val disconnectCount = AtomicInteger(0)
        val capturedDetails = AtomicReference<DisconnectionDetails>()
        val config = ConversationConfig(
            agentId = "agent-xyz",
            onStatusChange = { status -> observedStatuses.add(status) },
            onDisconnect = { details ->
                disconnectCount.incrementAndGet()
                capturedDetails.compareAndSet(null, details)
            }
        )

        val connection = newConnection()
        connection.setOnConnectionStateListener { state -> observedStates.add(state) }

        runBlocking { connection.connect(apiBaseUrl(), config) }
        assertTrue("server saw open", ready.await(3, TimeUnit.SECONDS))
        assertTrue("client reached CONNECTED", awaitUntil {
            connection.connectionState == ConnectionState.CONNECTED
        })

        connection.disconnect()
        killTransport()

        assertTrue(
            "expected okhttp to surface a transport failure; logs=$logMessages",
            awaitUntil { loggedMessageContaining("WebSocket failure") }
        )

        assertEquals(ConnectionState.IDLE, connection.connectionState)
        assertEquals(
            listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.IDLE),
            observedStates.toList()
        )
        assertFalse(
            "ERROR status reported after a user-initiated disconnect: $observedStatuses",
            observedStatuses.contains(ConversationStatus.ERROR)
        )
        assertEquals("onDisconnect fired more than once", 1, disconnectCount.get())
        assertTrue(
            "expected User, got ${capturedDetails.get()}",
            capturedDetails.get() is DisconnectionDetails.User
        )
    }

    /**
     * The happy-path variant of the above: the peer does echo our close frame, so okhttp
     * calls onClosed. disconnect() already reset the state to IDLE, so that late callback
     * must not push another transition either.
     */
    @Test
    fun `client-initiated disconnect ignores the peer close frame`() {
        val ready = CountDownLatch(1)
        enqueueServerWs(
            onOpen = { ready.countDown() },
            // Complete the handshake so the client observes a clean close after disconnect().
            onClosing = { ws, code, reason -> ws.close(code, reason) }
        )

        val observedStates = ConcurrentLinkedQueue<ConnectionState>()
        val disconnectCount = AtomicInteger(0)
        val capturedDetails = AtomicReference<DisconnectionDetails>()
        val config = ConversationConfig(
            agentId = "agent-xyz",
            onDisconnect = { details ->
                disconnectCount.incrementAndGet()
                capturedDetails.compareAndSet(null, details)
            }
        )

        val connection = newConnection()
        connection.setOnConnectionStateListener { state -> observedStates.add(state) }

        runBlocking { connection.connect(apiBaseUrl(), config) }
        assertTrue("server saw open", ready.await(3, TimeUnit.SECONDS))
        assertTrue("client reached CONNECTED", awaitUntil {
            connection.connectionState == ConnectionState.CONNECTED
        })

        connection.disconnect()

        assertTrue(
            "expected okhttp to surface the peer close; logs=$logMessages",
            awaitUntil { loggedMessageContaining("WebSocket closed") }
        )

        assertEquals(ConnectionState.IDLE, connection.connectionState)
        assertEquals(
            listOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.IDLE),
            observedStates.toList()
        )
        assertEquals("onDisconnect fired more than once", 1, disconnectCount.get())
        assertTrue(
            "expected User, got ${capturedDetails.get()}",
            capturedDetails.get() is DisconnectionDetails.User
        )
    }

    /**
     * disconnect() releases the listener that owns the socket and connect() installs a new
     * one, so a connection reused for a second session must still report that session's
     * failures rather than staying permanently silent.
     */
    @Test
    fun `reconnecting after a client-initiated disconnect re-arms error reporting`() {
        enqueueServerWs()
        val connection = newConnection()
        runBlocking { connection.connect(apiBaseUrl(), ConversationConfig(agentId = "agent-xyz")) }
        assertTrue("client reached CONNECTED", awaitUntil {
            connection.connectionState == ConnectionState.CONNECTED
        })

        connection.disconnect()
        assertEquals(ConnectionState.IDLE, connection.connectionState)

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
        runBlocking { connection.connect(apiBaseUrl(), config) }
        assertTrue("server saw the second open", ready.await(3, TimeUnit.SECONDS))

        serverSocket.get().close(1011, "internal error")

        assertTrue("onDisconnect fired for the second session", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue("expected Error, got ${captured.get()}", captured.get() is DisconnectionDetails.Error)
        assertEquals(ConnectionState.DISCONNECTED, connection.connectionState)
    }

    /**
     * The socket from an ended session usually outlives it - the peer never has to echo our
     * close frame - so its callbacks can still arrive once a replacement connection is live.
     * They must not touch the new session's state or reach its callbacks.
     */
    @Test
    fun `a stale socket from a previous session cannot disturb the current one`() {
        val firstReady = CountDownLatch(1)
        val firstServerSocket = AtomicReference<WebSocket>()
        enqueueServerWs(onOpen = { ws ->
            firstServerSocket.set(ws)
            firstReady.countDown()
        })

        val connection = newConnection()
        val observedStates = ConcurrentLinkedQueue<ConnectionState>()
        connection.setOnConnectionStateListener { state -> observedStates.add(state) }

        runBlocking { connection.connect(apiBaseUrl(), ConversationConfig(agentId = "agent-xyz")) }
        assertTrue("server saw the first open", firstReady.await(3, TimeUnit.SECONDS))
        assertTrue("client reached CONNECTED", awaitUntil {
            connection.connectionState == ConnectionState.CONNECTED
        })

        connection.disconnect()

        // Second session on the same instance, with its own callbacks.
        val secondReady = CountDownLatch(1)
        enqueueServerWs(onOpen = { secondReady.countDown() })
        val secondSessionDisconnects = AtomicInteger(0)
        val secondConfig = ConversationConfig(
            agentId = "agent-xyz",
            onDisconnect = { secondSessionDisconnects.incrementAndGet() }
        )
        runBlocking { connection.connect(apiBaseUrl(), secondConfig) }
        assertTrue("server saw the second open", secondReady.await(3, TimeUnit.SECONDS))
        assertTrue("client reached CONNECTED again", awaitUntil {
            connection.connectionState == ConnectionState.CONNECTED
        })

        // Abnormally close the *first* socket only; the second one stays untouched.
        firstServerSocket.get().close(1011, "internal error")

        assertTrue(
            "expected the stale socket's close to be delivered; logs=$logMessages",
            awaitUntil { loggedMessageContaining("WebSocket closed") }
        )

        assertEquals(ConnectionState.CONNECTED, connection.connectionState)
        assertEquals(
            listOf(
                ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.IDLE,
                ConnectionState.CONNECTING, ConnectionState.CONNECTED
            ),
            observedStates.toList()
        )
        assertEquals(
            "the stale socket reported a disconnect to the live session",
            0,
            secondSessionDisconnects.get()
        )
    }

    /**
     * ConversationSessionImpl.start() calls disconnect() when start-up fails, which can land
     * while the handshake is still in flight. The late onOpen must not revive the connection.
     */
    @Test
    fun `onOpen after disconnect does not resurrect the connection`() {
        // Nothing is enqueued yet, so MockWebServer's dispatcher parks the upgrade request
        // until we hand it a response - after disconnect() has already run.
        val connection = newConnection()
        val observedStates = ConcurrentLinkedQueue<ConnectionState>()
        connection.setOnConnectionStateListener { state -> observedStates.add(state) }

        runBlocking { connection.connect(apiBaseUrl(), ConversationConfig(agentId = "agent-xyz")) }
        assertEquals(ConnectionState.CONNECTING, connection.connectionState)

        connection.disconnect()
        assertEquals(ConnectionState.IDLE, connection.connectionState)

        // Now let the handshake complete.
        enqueueServerWs()

        assertTrue(
            "expected the late open to be delivered; logs=$logMessages",
            awaitUntil { loggedMessageContaining("WebSocket opened") }
        )

        assertEquals(ConnectionState.IDLE, connection.connectionState)
        assertEquals(
            listOf(ConnectionState.CONNECTING, ConnectionState.IDLE),
            observedStates.toList()
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
        runBlocking { connection.connect(apiBaseUrl(), config) }

        assertTrue("onDisconnect fired", disconnected.await(3, TimeUnit.SECONDS))
        assertTrue(captured.get() is DisconnectionDetails.Error)
        assertEquals(ConnectionState.ERROR, connection.connectionState)
    }
}
