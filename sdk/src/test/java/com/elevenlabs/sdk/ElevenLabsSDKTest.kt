package com.elevenlabs.sdk

import org.junit.Test
import org.junit.Assert.*
import io.mockk.*

/**
 * Unit tests for ElevenLabsSDK
 * Shows how the interface makes mocking easy
 */
class ElevenLabsSDKTest {

    @Test
    fun testArrayBufferToBase64() {
        val testData = byteArrayOf(72, 101, 108, 108, 111) // "Hello" in bytes
        val base64Result = ElevenLabsSDK.arrayBufferToBase64(testData)
        assertEquals("SGVsbG8=", base64Result)
    }

    @Test
    fun testBase64ToArrayBuffer() {
        val base64String = "SGVsbG8="
        val result = ElevenLabsSDK.base64ToArrayBuffer(base64String)
        assertNotNull(result)
        val expected = byteArrayOf(72, 101, 108, 108, 111) // "Hello" in bytes
        assertArrayEquals(expected, result)
    }

    @Test
    fun testInvalidBase64() {
        val invalidBase64 = "invalid_base64_string!"
        val result = ElevenLabsSDK.base64ToArrayBuffer(invalidBase64)
        assertNull(result)
    }

    @Test
    fun testLanguageEnumValues() {
        assertEquals("en", ElevenLabsSDK.Language.EN.value)
        assertEquals("es", ElevenLabsSDK.Language.ES.value)
        assertEquals("fr", ElevenLabsSDK.Language.FR.value)
    }

    @Test
    fun testSessionConfigRequiresAgentIdOrSignedUrl() {
        try {
            ElevenLabsSDK.SessionConfig()
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Either signedUrl or agentId must be provided") == true)
        }
    }

    @Test
    fun testSessionConfigWithAgentId() {
        val config = ElevenLabsSDK.SessionConfig(agentId = "test-agent-id")
        assertEquals("test-agent-id", config.agentId)
        assertNull(config.signedUrl)
    }

    @Test
    fun testSessionConfigWithSignedUrl() {
        val config = ElevenLabsSDK.SessionConfig(signedUrl = "wss://example.com/test")
        assertEquals("wss://example.com/test", config.signedUrl)
        assertNull(config.agentId)
    }

    @Test
    fun testClientToolsRegistration() {
        val tools = ElevenLabsSDK.ClientTools()
        val testHandler: ElevenLabsSDK.ClientToolHandler = { params ->
            "Result: ${params["input"]}"
        }
        
        tools.register("test_tool", testHandler)
        assertTrue(true)
    }

    @Test
    fun testRoleEnum() {
        assertEquals(2, ElevenLabsSDK.Role.values().size)
        assertTrue(ElevenLabsSDK.Role.values().contains(ElevenLabsSDK.Role.USER))
        assertTrue(ElevenLabsSDK.Role.values().contains(ElevenLabsSDK.Role.AI))
    }

    @Test
    fun testModeEnum() {
        assertEquals(2, ElevenLabsSDK.Mode.values().size)
        assertTrue(ElevenLabsSDK.Mode.values().contains(ElevenLabsSDK.Mode.SPEAKING))
        assertTrue(ElevenLabsSDK.Mode.values().contains(ElevenLabsSDK.Mode.LISTENING))
    }

    @Test
    fun testStatusEnum() {
        assertEquals(4, ElevenLabsSDK.Status.values().size)
        assertTrue(ElevenLabsSDK.Status.values().contains(ElevenLabsSDK.Status.CONNECTING))
        assertTrue(ElevenLabsSDK.Status.values().contains(ElevenLabsSDK.Status.CONNECTED))
        assertTrue(ElevenLabsSDK.Status.values().contains(ElevenLabsSDK.Status.DISCONNECTING))
        assertTrue(ElevenLabsSDK.Status.values().contains(ElevenLabsSDK.Status.DISCONNECTED))
    }

    // Example of how easy it is to mock now
    @Test
    fun testMockingExample() {
        val mockSDK = mockk<ElevenLabsInterface>()
        val mockConversation = mockk<ElevenLabsInterface.Conversation>()
        
        // Mock behavior
        coEvery { mockSDK.startSession(any(), any()) } returns mockConversation
        every { mockConversation.getId() } returns "mock-id"
        
        // Test
        assertEquals("mock-id", mockConversation.getId())
    }
} 
