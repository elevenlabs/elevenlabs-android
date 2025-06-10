package com.elevenlabs.sdk

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import io.livekit.android.room.Room
import io.livekit.android.room.RoomEvent
import io.livekit.android.room.track.*
import io.livekit.android.room.participant.RemoteParticipant
import kotlinx.coroutines.*
import android.util.Log
import kotlin.math.sqrt
import com.elevenlabs.sdk.utils.AudioUtils
import com.elevenlabs.sdk.utils.PermissionUtils

class AudioManager(
    private val context: Context,
    private val room: Room,
    private val callbacks: Callbacks
) {
    companion object {
        private const val TAG = "AudioManager"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MULTIPLIER = 4
    }
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
    
    private var localAudioTrack: LocalAudioTrack? = null
    private var remoteAudioTrack: RemoteAudioTrack? = null
    private var volumeMonitor: Job? = null
    private var outputVolumeMonitor: Job? = null
    
    // Audio recording components for level monitoring
    private var audioRecord: AudioRecord? = null
    private var audioRecordBuffer: ShortArray? = null
    private var isRecordingLevel = false
    
    private var _volume: Float = 1.0f
    private var _isMicrophoneEnabled: Boolean = true
    
    suspend fun initialize() {
        try {
            // Check all required permissions
            if (!PermissionUtils.hasAudioRecordPermission(context)) {
                throw SecurityException("RECORD_AUDIO permission not granted")
            }
            
            if (!PermissionUtils.hasAudioSettingsPermission(context)) {
                Log.w(TAG, "MODIFY_AUDIO_SETTINGS permission not granted - volume control may be limited")
            }
            
            // Initialize audio level monitoring
            initializeAudioLevelMonitoring()
            
            // Create and publish local audio track
            localAudioTrack = room.localParticipant.createAudioTrack()
            room.localParticipant.publishAudioTrack(localAudioTrack!!)
            
            // Setup event handlers
            setupAudioEventHandlers()
            
            // Start volume monitoring
            startVolumeMonitoring()
            
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize audio", error)
            callbacks.onError("Failed to initialize audio", error)
            throw error
        }
    }
    
    private fun initializeAudioLevelMonitoring() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_MULTIPLIER
            
            if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
                throw IllegalStateException("Failed to get valid buffer size for AudioRecord")
            }
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord failed to initialize")
            }
            
            audioRecordBuffer = ShortArray(bufferSize / 2) // Short is 2 bytes
            
            Log.d(TAG, "Audio level monitoring initialized with buffer size: $bufferSize")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize audio level monitoring", e)
            audioRecord?.release()
            audioRecord = null
            throw e
        }
    }
    
    private fun setupAudioEventHandlers() {
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed -> {
                        val track = event.track
                        val participant = event.participant
                        
                        if (track is RemoteAudioTrack && participant is RemoteParticipant) {
                            remoteAudioTrack = track
                            attachRemoteAudio(track)
                        }
                    }
                    is RoomEvent.TrackMuted -> {
                        if (event.trackPublication.track == remoteAudioTrack) {
                            callbacks.onModeChange(ElevenLabsInterface.Mode.LISTENING)
                        }
                    }
                    is RoomEvent.TrackUnmuted -> {
                        if (event.trackPublication.track == remoteAudioTrack) {
                            callbacks.onModeChange(ElevenLabsInterface.Mode.SPEAKING)
                        }
                    }
                    is RoomEvent.TrackUnsubscribed -> {
                        if (event.track == remoteAudioTrack) {
                            remoteAudioTrack = null
                            stopOutputVolumeMonitoring()
                        }
                    }
                    else -> {
                        // Handle other events as needed
                    }
                }
            }
        }
    }
    
    private fun attachRemoteAudio(track: RemoteAudioTrack) {
        // LiveKit automatically routes audio to speakers/headphones
        startOutputVolumeMonitoring()
    }
    
    private fun startVolumeMonitoring() {
        volumeMonitor = scope.launch(Dispatchers.IO) {
            startAudioLevelRecording()
            
            while (isActive && isRecordingLevel) {
                if (localAudioTrack != null && _isMicrophoneEnabled) {
                    val volume = getInputVolumeLevel()
                    withContext(Dispatchers.Main) {
                        callbacks.onVolumeUpdate(volume)
                    }
                }
                delay(50) // 50ms interval for more responsive updates
            }
        }
    }
    
    private fun startOutputVolumeMonitoring() {
        outputVolumeMonitor = scope.launch {
            while (isActive && remoteAudioTrack != null) {
                val volume = getOutputVolumeLevel()
                callbacks.onOutputVolumeUpdate?.invoke(volume)
                delay(100) // 100ms interval
            }
        }
    }
    
    private fun stopOutputVolumeMonitoring() {
        outputVolumeMonitor?.cancel()
        outputVolumeMonitor = null
    }
    
    private fun startAudioLevelRecording() {
        try {
            audioRecord?.let { record ->
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.startRecording()
                    isRecordingLevel = true
                    Log.d(TAG, "Started audio level recording")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio level recording", e)
            isRecordingLevel = false
        }
    }
    
    private fun stopAudioLevelRecording() {
        try {
            isRecordingLevel = false
            audioRecord?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                    Log.d(TAG, "Stopped audio level recording")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop audio level recording", e)
        }
    }
    
    private suspend fun getInputVolumeLevel(): Float = withContext(Dispatchers.IO) {
        try {
            audioRecord?.let { record ->
                audioRecordBuffer?.let { buffer ->
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val bytesRead = record.read(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            // Calculate RMS amplitude
                            var sum = 0.0
                            for (i in 0 until bytesRead) {
                                val sample = buffer[i].toDouble() / Short.MAX_VALUE
                                sum += sample * sample
                            }
                            val rms = sqrt(sum / bytesRead)
                            
                            // Normalize to 0-1 range with some scaling for better sensitivity
                            return@withContext AudioUtils.normalizeAudioLevel(rms.toFloat() * 3.0f)
                        }
                    }
                }
            }
            return@withContext 0f
        } catch (e: Exception) {
            Log.e(TAG, "Error reading audio level", e)
            return@withContext 0f
        }
    }
    
    private suspend fun getOutputVolumeLevel(): Float {
        return try {
            // Get current system volume levels
            val musicVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val maxMusicVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            
            val voiceCallVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_VOICE_CALL)
            val maxVoiceCallVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_VOICE_CALL)
            
            // Use the appropriate stream based on audio routing
            val currentVolume = if (audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
                voiceCallVolume.toFloat() / maxVoiceCallVolume.toFloat()
            } else {
                musicVolume.toFloat() / maxMusicVolume.toFloat()
            }
            
            // Apply some variation to simulate actual audio output levels
            // In a more sophisticated implementation, this could analyze actual audio output
            val variation = (Math.random() * 0.3 - 0.15).toFloat() // ±15% variation
            (currentVolume + variation).coerceIn(0f, 1f)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting output volume level", e)
            0f
        }
    }
    
    fun setMicrophoneEnabled(enabled: Boolean) {
        _isMicrophoneEnabled = enabled
        localAudioTrack?.let { track ->
            if (enabled) {
                track.unmute()
            } else {
                track.mute()
            }
        }
        
        // Control audio level recording based on microphone state
        if (enabled && !isRecordingLevel) {
            startAudioLevelRecording()
        } else if (!enabled && isRecordingLevel) {
            stopAudioLevelRecording()
        }
    }
    
    val volume: Float
        get() = _volume
    
    fun setVolume(volume: Float) {
        _volume = volume.coerceIn(0f, 1f)
        
        try {
            if (PermissionUtils.hasAudioSettingsPermission(context)) {
                // Set system volume based on our volume setting
                val streamType = if (audioManager.mode == android.media.AudioManager.MODE_IN_COMMUNICATION) {
                    android.media.AudioManager.STREAM_VOICE_CALL
                } else {
                    android.media.AudioManager.STREAM_MUSIC
                }
                
                val maxVolume = audioManager.getStreamMaxVolume(streamType)
                val targetVolume = (maxVolume * _volume).toInt()
                
                audioManager.setStreamVolume(streamType, targetVolume, 0)
                Log.d(TAG, "Set system volume to $targetVolume/$maxVolume (${(_volume * 100).toInt()}%)")
            } else {
                Log.w(TAG, "Cannot set system volume - MODIFY_AUDIO_SETTINGS permission not granted")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }
    
    val isMicrophoneEnabled: Boolean
        get() = _isMicrophoneEnabled
    
    fun close() {
        try {
            stopAudioLevelRecording()
            volumeMonitor?.cancel()
            stopOutputVolumeMonitoring()
            
            audioRecord?.release()
            audioRecord = null
            audioRecordBuffer = null
            
            localAudioTrack?.stop()
            remoteAudioTrack = null
            scope.cancel()
            
            Log.d(TAG, "AudioManager closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing AudioManager", e)
        }
    }
} 