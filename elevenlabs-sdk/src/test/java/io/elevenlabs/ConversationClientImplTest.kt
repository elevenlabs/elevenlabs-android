package io.elevenlabs

import android.content.Context
import io.elevenlabs.network.TokenService
import io.elevenlabs.network.TokenResponse
import io.livekit.android.room.Room
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class ConversationClientImplTest {

    private lateinit var mockContext: Context

    @Before
    fun setup() {
        mockContext = mockk(relaxed = true)

        // Clear all mocks before each test
        clearAllMocks()

        // Mock Android Log to prevent "Method d in android.util.Log not mocked" errors
        mockkStatic(android.util.Log::class)
        every { android.util.Log.d(any(), any()) } returns 0

        // Mock Android Looper to prevent "Method getMainLooper in android.os.Looper not mocked" errors
        mockkStatic(android.os.Looper::class)
        val mockLooper = mockk<android.os.Looper>(relaxed = true)
        every { android.os.Looper.getMainLooper() } returns mockLooper

        // Mock Android Handler for LiveData main thread posting
        mockkConstructor(android.os.Handler::class)
        every { anyConstructed<android.os.Handler>().post(any()) } returns true
        every { anyConstructed<android.os.Handler>().postDelayed(any(), any()) } returns true



        // Mock Context.getSystemService to return proper mock AudioManager
        val mockAudioManager = mockk<android.media.AudioManager>(relaxed = true)
        every { mockContext.getSystemService(android.content.Context.AUDIO_SERVICE) } returns mockAudioManager
    }

    @After
    fun tearDown() {
        // Clean up mocks after each test
        unmockkAll()
    }

    @Test
    fun `validateConfig throws exception for private agent without token`() {
        // Constructor validation catches blank conversationToken first
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ConversationConfig(
                conversationToken = "",
                agentId = null
            )
        }

        assertEquals("conversationToken cannot be blank", exception.message)
    }

    @Test
    fun `validateConfig throws exception for private agent with null token`() {
        val config = ConversationConfig(
            conversationToken = null,
            agentId = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                ConversationClientImpl.startSession(config, mockContext)
            }
        }

        assertEquals("Public agent requires a valid agent ID", exception.message)
    }

    @Test
    fun `validateConfig throws exception for public agent without agentId`() {
        val config = ConversationConfig(
            agentId = null,
            conversationToken = null
        )

        val exception = assertThrows(IllegalArgumentException::class.java) {
            runTest {
                ConversationClientImpl.startSession(config, mockContext)
            }
        }

        assertEquals("Public agent requires a valid agent ID", exception.message)
    }

    @Test
    fun `validateConfig throws exception for public agent with empty agentId`() {
        // Constructor validation catches blank agentId first
        val exception = assertThrows(IllegalArgumentException::class.java) {
            ConversationConfig(
                agentId = "",
                conversationToken = null
            )
        }

        assertEquals("agentId cannot be blank", exception.message)
    }

    @Test
    fun `startSession works with valid private agent config`() = runTest {
        // Mock all the dependencies we need
        val mockRoom = mockk<Room>(relaxed = true)

        mockkObject(io.livekit.android.LiveKit)
        every {
            io.livekit.android.LiveKit.create(
                appContext = any(),
                overrides = any()
            )
        } returns mockRoom
        every { io.livekit.android.LiveKit.create(any()) } returns mockRoom
        every { io.livekit.android.LiveKit.create(any(), any()) } returns mockRoom

        // Mock the constructor and its methods
        mockkConstructor(ConversationSessionImpl::class)
        coEvery { anyConstructed<ConversationSessionImpl>().start() } just Runs

        mockkConstructor(ConversationEventHandler::class)
        mockkConstructor(io.elevenlabs.network.WebRTCConnection::class)
        mockkConstructor(io.elevenlabs.audio.AudioSessionManager::class)
        mockkConstructor(io.elevenlabs.audio.LiveKitAudioManager::class)
        mockkConstructor(ClientToolRegistry::class)

        every { anyConstructed<io.elevenlabs.audio.AudioSessionManager>().configureForVoiceCall() } just Runs

        val config = ConversationConfig(
            conversationToken = "valid-token",
            agentId = null
        )

        val result = ConversationClientImpl.startSession(config, mockContext)

        assertNotNull(result)
        verify {
            io.livekit.android.LiveKit.create(
                appContext = mockContext,
                overrides = any()
            )
        }
    }

    // @Test
    // fun `startSession fetches token for public agent`() = runTest {
    //     // Mock all dependencies
    //     mockkObject(io.livekit.android.LiveKit)
    //     mockkConstructor(TokenService::class)

    //     // Additional Android mocking for this test
    //     mockkStatic(android.os.Looper::class)
    //     val mockLooper = mockk<android.os.Looper>(relaxed = true)
    //     every { android.os.Looper.getMainLooper() } returns mockLooper

    //     // Mock Handler constructor
    //     mockkConstructor(android.os.Handler::class)
    //     every { anyConstructed<android.os.Handler>().post(any()) } returns true

    //     // Mock the constructor and its methods
    //     mockkConstructor(ConversationSessionImpl::class)
    //     coEvery { anyConstructed<ConversationSessionImpl>().start() } just Runs

    //     mockkConstructor(ConversationEventHandler::class)
    //     mockkConstructor(io.elevenlabs.network.WebRTCConnection::class)
    //     mockkConstructor(io.elevenlabs.audio.AudioSessionManager::class)
    //     mockkConstructor(io.elevenlabs.audio.LiveKitAudioManager::class)
    //     mockkConstructor(ClientToolRegistry::class)

    //     val mockRoom = mockk<Room>(relaxed = true)
    //     val mockTokenResponse = TokenResponse(
    //         token = "generated-token",
    //     )

    //     every { io.livekit.android.LiveKit.create(any()) } returns mockRoom
    //     coEvery { anyConstructed<TokenService>().fetchPublicAgentToken(any(), any(), any()) } returns mockTokenResponse
    //     every { anyConstructed<io.elevenlabs.audio.AudioSessionManager>().configureForVoiceCall() } just Runs

    //     val config = ConversationConfig(
    //         agentId = "test-agent-id",
    //         conversationToken = null,
    //         overrides = Overrides(
    //             client = ClientOverrides(
    //                 version = "0.1.0"  // Provide version to avoid BuildConfig access
    //             )
    //         )
    //     )

    //     val result = ConversationClientImpl.startSession(config, mockContext)

    //     assertNotNull(result)
    //     coVerify {
    //         anyConstructed<TokenService>().fetchPublicAgentToken(
    //             "test-agent-id",
    //             "android_sdk",
    //             "0.1.0" // Use constant for unit tests since BuildConfig.SDK_VERSION isn't available
    //         )
    //     }
    // }

    @Test
    fun `builder creates ConversationSessionBuilder`() {
        val builder = ConversationClientImpl.builder(mockContext)

        assertNotNull(builder)
        assertTrue(builder is ConversationSessionBuilder)
    }
}
