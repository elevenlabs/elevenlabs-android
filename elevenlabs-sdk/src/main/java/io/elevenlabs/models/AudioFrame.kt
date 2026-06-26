package io.elevenlabs.models

import java.nio.ByteBuffer

/**
 * One decoded PCM chunk from the agent's remote audio track, delivered immediately before
 * playback. Use this to drive amplitude-reactive UI (e.g. a voice orb) in sample-accurate sync
 * with what the user hears.
 *
 * Delivered via [io.elevenlabs.ConversationConfig.onAudioFrame] on WebRTC's audio thread,
 * typically every 10 ms. Implementations must not block.
 *
 * The [audioData] buffer is a read-only view owned by WebRTC and is only valid for the duration
 * of the callback — copy any bytes that need to outlive the call.
 *
 * @param audioData PCM samples, little-endian. Position/limit are set to the sample range.
 * @param bitsPerSample Sample width — 16 (signed integer) or 32 (float).
 * @param sampleRate Samples per second, e.g. 48000.
 * @param channelCount 1 for mono, 2 for stereo.
 * @param numberOfFrames Sample frames in this buffer (one frame = [channelCount] samples).
 * @param absoluteCaptureTimestampMs Wall-clock capture timestamp as reported by WebRTC.
 */
data class AudioFrame(
    val audioData: ByteBuffer,
    val bitsPerSample: Int,
    val sampleRate: Int,
    val channelCount: Int,
    val numberOfFrames: Int,
    val absoluteCaptureTimestampMs: Long,
)
