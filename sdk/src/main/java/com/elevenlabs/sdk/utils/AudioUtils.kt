package com.elevenlabs.sdk.utils

import android.content.Context
import android.media.AudioManager
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Utility functions for audio processing
 */
object AudioUtils {
    private const val TAG = "AudioUtils"
    
    /**
     * Calculate RMS (Root Mean Square) value from audio data
     */
    fun calculateRMS(audioData: ByteArray): Float {
        if (audioData.isEmpty()) return 0f
        
        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until audioData.size / 2) {
            val sample = shortBuffer.getShort(i * 2).toFloat() / Short.MAX_VALUE
            sum += sample * sample
        }
        
        return sqrt(sum / (audioData.size / 2)).toFloat()
    }
    
    /**
     * Convert audio volume to decibels
     */
    fun volumeToDecibels(volume: Float): Float {
        return if (volume > 0) {
            20 * kotlin.math.log10(volume)
        } else {
            -80f // Minimum dB value
        }
    }
    
    /**
     * Normalize audio level to 0-1 range with improved sensitivity
     */
    fun normalizeAudioLevel(rms: Float): Float {
        // Convert RMS to decibels
        val dB = if (rms > 0) {
            20 * kotlin.math.log10(rms)
        } else {
            -80f // Minimum dB value
        }
        
        // Map typical microphone input range (-60dB to -10dB) to 0-1
        val minDb = -60f
        val maxDb = -10f
        
        val normalized = (dB - minDb) / (maxDb - minDb)
        return kotlin.math.max(0f, kotlin.math.min(1f, normalized))
    }
    
    /**
     * Check if audio recording is available
     */
    fun isAudioRecordingAvailable(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return audioManager != null
    }
    
    /**
     * Configure audio for optimal voice communication
     */
    fun configureAudioForVoice(context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Set mode for voice communication
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            // Enable speakerphone for better experience
            audioManager.isSpeakerphoneOn = true
            
            Log.d(TAG, "Audio configured for voice communication")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure audio", e)
        }
    }
    
    /**
     * Convert 16-bit PCM samples to float array
     */
    fun pcm16ToFloat(pcmData: ByteArray): FloatArray {
        val floatArray = FloatArray(pcmData.size / 2)
        val byteBuffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in floatArray.indices) {
            val sample = byteBuffer.getShort(i * 2)
            floatArray[i] = sample / 32768.0f
        }
        
        return floatArray
    }
    
    /**
     * Convert float array to 16-bit PCM samples
     */
    fun floatToPcm16(floatData: FloatArray): ByteArray {
        val byteArray = ByteArray(floatData.size * 2)
        val byteBuffer = ByteBuffer.wrap(byteArray).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in floatData.indices) {
            val sample = (floatData[i] * 32767).toInt().coerceIn(-32768, 32767)
            byteBuffer.putShort(i * 2, sample.toShort())
        }
        
        return byteArray
    }
} 