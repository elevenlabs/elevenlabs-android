package io.elevenlabs.audio

import android.os.Handler
import android.os.Looper
import io.elevenlabs.MutedSpeechEvent
import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Capture post-processor used to implement software microphone mute.
 *
 * When muted, the microphone track stays open but outbound samples are zeroed
 * before transmission. The RMS of the captured audio is measured so that
 * "you're speaking while muted" events can be delivered via [onMutedSpeech].
 *
 * A 4-up / 3-down hangover latch suppresses single-buffer noise spikes, and
 * notifications are throttled to at most one every [mutedSpeechThrottleMs]
 * milliseconds while the user keeps talking above [mutedSpeechThresholdInDb].
 *
 * When unmuted, [processAudio] returns immediately without modifying the
 * buffer.
 */
class SoftwareMuteProcessor internal constructor(
    private val onMutedSpeech: ((MutedSpeechEvent) -> Unit)?,
    private val mutedSpeechThresholdInDb: Float = DEFAULT_THRESHOLD_DB,
    private val mutedSpeechThrottleMs: Long = DEFAULT_THROTTLE_MS,
    private val dispatchMutedSpeech: (Runnable) -> Unit = { runnable ->
        Handler(Looper.getMainLooper()).post(runnable)
    },
) : AudioProcessorInterface {

    companion object {
        const val DEFAULT_THRESHOLD_DB: Float = -35f
        const val DEFAULT_THROTTLE_MS: Long = 3_000L

        private const val BUFFERS_ABOVE_TO_CONFIRM: Int = 4
        private const val BUFFERS_BELOW_TO_CLEAR: Int = 3
        private const val MIN_RMS: Float = 1e-6f

        // LiveKit's capture post-processor delivers int16-scaled floats
        // (range ±32768); normalise into [-1, 1] before computing dB.
        private const val INT16_NORMALIZER: Double = 32768.0
    }

    @Volatile
    private var muted: Boolean = false

    private val lock = Any()
    private var lastNotificationTimeMs: Long = 0L
    private var consecutiveAboveCount: Int = 0
    private var consecutiveBelowCount: Int = 0
    private var hangoverLatched: Boolean = false

    /** Thread-safe. Resets the hangover latch when the mute state changes. */
    fun setMuted(newValue: Boolean) {
        synchronized(lock) {
            if (muted != newValue) {
                consecutiveAboveCount = 0
                consecutiveBelowCount = 0
                hangoverLatched = false
            }
            muted = newValue
        }
    }

    fun isMuted(): Boolean = muted

    override fun getName(): String = "ElevenLabsSoftwareMuteProcessor"

    override fun isEnabled(): Boolean = true

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) {}

    override fun resetAudioProcessing(newRate: Int) {}

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!muted) return

        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val count = floatBuffer.remaining()
        if (count == 0) return

        val startPos = floatBuffer.position()
        val db = computeDb(floatBuffer, startPos, numBands, numFrames, count)
        val levelActive = db > mutedSpeechThresholdInDb

        var shouldFire = false
        var fireLevel = 0f
        synchronized(lock) {
            if (levelActive) {
                consecutiveBelowCount = 0
                consecutiveAboveCount += 1
                if (consecutiveAboveCount >= BUFFERS_ABOVE_TO_CONFIRM) {
                    hangoverLatched = true
                }
            } else {
                consecutiveAboveCount = 0
                consecutiveBelowCount += 1
                if (consecutiveBelowCount >= BUFFERS_BELOW_TO_CLEAR) {
                    hangoverLatched = false
                    consecutiveBelowCount = 0
                }
            }

            if (hangoverLatched && levelActive) {
                val now = System.currentTimeMillis()
                if (now - lastNotificationTimeMs > mutedSpeechThrottleMs) {
                    lastNotificationTimeMs = now
                    shouldFire = true
                    fireLevel = db
                }
            }
        }

        if (shouldFire) {
            val level = fireLevel
            dispatchMutedSpeech {
                try {
                    onMutedSpeech?.invoke(MutedSpeechEvent(audioLevel = level))
                } catch (_: Throwable) {
                    // Swallow to keep the capture pipeline alive.
                }
            }
        }

        for (i in 0 until count) {
            floatBuffer.put(startPos + i, 0f)
        }
    }

    private fun computeDb(
        floatBuffer: FloatBuffer,
        startPos: Int,
        numBands: Int,
        numFrames: Int,
        floatCount: Int,
    ): Float {
        if (numBands > 0 && numFrames > 0 && floatCount == numBands * numFrames) {
            var totalRms = 0.0
            for (c in 0 until numBands) {
                var sumSquares = 0.0
                for (f in 0 until numFrames) {
                    val normalized =
                        floatBuffer.get(startPos + f * numBands + c) / INT16_NORMALIZER
                    sumSquares += normalized * normalized
                }
                totalRms += sqrt(sumSquares / numFrames)
            }
            val avgRms = (totalRms / numBands).toFloat()
            return 20f * log10(max(avgRms, MIN_RMS))
        }

        var sumSquares = 0.0
        for (i in 0 until floatCount) {
            val normalized = floatBuffer.get(startPos + i) / INT16_NORMALIZER
            sumSquares += normalized * normalized
        }
        val rms = sqrt(sumSquares / floatCount).toFloat()
        return 20f * log10(max(rms, MIN_RMS))
    }
}
