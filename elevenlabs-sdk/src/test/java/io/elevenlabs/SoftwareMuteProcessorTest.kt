package io.elevenlabs

import io.elevenlabs.audio.SoftwareMuteProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.math.PI

class SoftwareMuteProcessorTest {

    private val numBands = 3
    private val numFrames = 480

    private fun createProcessor(
        onMutedSpeech: ((MutedSpeechEvent) -> Unit)?,
        mutedSpeechThresholdInDb: Float = SoftwareMuteProcessor.DEFAULT_THRESHOLD_DB,
        mutedSpeechThrottleMs: Long = SoftwareMuteProcessor.DEFAULT_THROTTLE_MS,
    ) = SoftwareMuteProcessor(
        onMutedSpeech = onMutedSpeech,
        mutedSpeechThresholdInDb = mutedSpeechThresholdInDb,
        mutedSpeechThrottleMs = mutedSpeechThrottleMs,
        dispatchMutedSpeech = { it.run() },
    )

    private fun makeBuffer(samples: FloatArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putFloat(it) }
        buffer.rewind()
        return buffer
    }

    private fun silence(count: Int): FloatArray = FloatArray(count) { 0f }

    // Post-capture floats from LiveKit are int16-scaled (±32768), so tones
    // are generated at int16 amplitudes and divided by 32768 inside the
    // processor before dB is computed.
    private fun loudTone(count: Int, amplitudeInt16: Float = 16_000f): FloatArray =
        FloatArray(count) { i -> (amplitudeInt16 * sin(2.0 * PI * 440.0 * i / 48000.0)).toFloat() }

    private fun interleavedFromMono(mono: FloatArray, numBands: Int): FloatArray {
        val numFrames = mono.size
        return FloatArray(numBands * numFrames) { i ->
            mono[i / numBands]
        }
    }

    @Test
    fun `interleaved multi-channel buffer uses per-channel RMS average`() {
        var fired: Float? = null
        val processor = createProcessor(
            onMutedSpeech = { fired = it.audioLevel },
            mutedSpeechThrottleMs = 0L,
        )
        processor.setMuted(true)
        val interleaved = interleavedFromMono(loudTone(numFrames), numBands)
        repeat(6) {
            processor.processAudio(numBands, numFrames, makeBuffer(interleaved))
        }
        assertNotNull(fired)
        assertTrue(fired!! > -35f)
    }

    @Test
    fun `does not fire when unmuted`() {
        var fired: Float? = null
        val processor = createProcessor(onMutedSpeech = { fired = it.audioLevel })

        processor.setMuted(false)
        repeat(10) {
            processor.processAudio(numBands, numFrames, makeBuffer(loudTone(numFrames)))
        }

        assertNull("Should never fire when not muted", fired)
    }

    @Test
    fun `does not fire on silence while muted`() {
        var fired: Float? = null
        val processor = createProcessor(
            onMutedSpeech = { fired = it.audioLevel },
            mutedSpeechThrottleMs = 0L
        )

        processor.setMuted(true)
        repeat(10) {
            processor.processAudio(numBands, numFrames, makeBuffer(silence(numFrames)))
        }

        assertNull("Should not fire on silence", fired)
    }

    @Test
    fun `fires on sustained loud audio while muted`() {
        var fired: Float? = null
        val processor = createProcessor(
            onMutedSpeech = { fired = it.audioLevel },
            mutedSpeechThrottleMs = 0L
        )

        processor.setMuted(true)
        repeat(6) {
            processor.processAudio(numBands, numFrames, makeBuffer(loudTone(numFrames)))
        }

        assertNotNull("Should fire after hangover latch", fired)
        assertTrue("Reported level should be above threshold", fired!! > -35f)
    }

    @Test
    fun `does not fire on single loud buffer with default hangover`() {
        var fired: Float? = null
        val processor = createProcessor(
            onMutedSpeech = { fired = it.audioLevel },
            mutedSpeechThrottleMs = 0L
        )

        processor.setMuted(true)
        processor.processAudio(numBands, numFrames, makeBuffer(loudTone(numFrames)))

        assertNull("Single loud buffer should not trip the latch", fired)
    }

    @Test
    fun `zeroes buffer when muted`() {
        val processor = createProcessor(onMutedSpeech = null)
        processor.setMuted(true)

        val buffer = makeBuffer(loudTone(numFrames))
        processor.processAudio(numBands, numFrames, buffer)

        buffer.rewind()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = buffer.asFloatBuffer()
        while (out.hasRemaining()) {
            assertEquals(0f, out.get(), 0f)
        }
    }

    @Test
    fun `does not modify buffer when unmuted`() {
        val processor = createProcessor(onMutedSpeech = null)
        processor.setMuted(false)

        val original = loudTone(numFrames)
        val buffer = makeBuffer(original)
        processor.processAudio(numBands, numFrames, buffer)

        buffer.rewind()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val out = buffer.asFloatBuffer()
        original.forEach { expected ->
            assertEquals(expected, out.get(), 1e-6f)
        }
    }

    @Test
    fun `throttle suppresses rapid repeated events`() {
        var fireCount = 0
        val processor = createProcessor(
            onMutedSpeech = { fireCount += 1 },
            mutedSpeechThrottleMs = 10_000L,
        )

        processor.setMuted(true)
        repeat(30) {
            processor.processAudio(numBands, numFrames, makeBuffer(loudTone(numFrames)))
        }

        assertEquals("Throttle should coalesce to a single event", 1, fireCount)
    }
}
