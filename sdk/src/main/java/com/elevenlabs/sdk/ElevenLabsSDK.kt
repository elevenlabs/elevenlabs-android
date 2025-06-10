package com.elevenlabs.sdk

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType

/**
 * Convenient singleton access to the ElevenLabs SDK
 * For production use and easy access
 */
object ElevenLabs {
    val sdk: ElevenLabsInterface = ElevenLabsSDKImpl()
    
    // Convenience constants
    const val VERSION = "1.1.3"
}

/**
 * LiveKit-based implementation of ElevenLabsInterface
 */
class ElevenLabsSDKImpl : ElevenLabsInterface {
    
    companion object {
        private const val TAG = "ElevenLabsSDK"
        private const val ELEVENLABS_API_BASE = "https://api.elevenlabs.io"
        private const val LIVEKIT_URL = "wss://livekit.elevenlabs.io"
    }
    
    override suspend fun startSession(
        context: Context,
        config: ElevenLabsInterface.SessionConfig
    ): ElevenLabsInterface.Conversation = withContext(Dispatchers.IO) {
        
        // Get LiveKit token from ElevenLabs backend
        val liveKitToken = getLiveKitToken(config)
        
        // Create and connect to LiveKit room
        val conversation = LiveKitConversation(context, liveKitToken, config)
        conversation.connect()
        
        conversation
    }
    
    override fun arrayBufferToBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    
    override fun base64ToArrayBuffer(base64: String): ByteArray? {
        return try {
            Base64.decode(base64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64", e)
            null
        }
    }
    
    override fun configureAudioSession(context: Context) {
        // Configure audio session for optimal performance
        // This is handled by LiveKit automatically, but we can add any Android-specific optimizations here
        Log.d(TAG, "Audio session configured")
    }
    
    private suspend fun getLiveKitToken(config: ElevenLabsInterface.SessionConfig): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient()
        
        val requestBody = buildJsonObject {
            config.agentId?.let { put("agent_id", it) }
            config.signedUrl?.let { put("signed_url", it) }
        }.toString().toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$ELEVENLABS_API_BASE/v1/convai/livekit/token")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("xi-api-key", getApiKey())
            .build()
        
        val response = client.newCall(request).execute()
        
        if (!response.isSuccessful) {
            throw Exception("Failed to get LiveKit token: ${response.message}")
        }
        
        val responseBody = response.body?.string()
            ?: throw Exception("Empty response body")
        
        val json = Json.parseToJsonElement(responseBody).jsonObject
        json["token"]?.jsonPrimitive?.content
            ?: throw Exception("Token not found in response")
    }
    
    private fun getApiKey(): String {
        // Get API key from environment or configuration
        return System.getenv("ELEVENLABS_API_KEY")
            ?: throw Exception("ELEVENLABS_API_KEY environment variable is not set")
    }
}