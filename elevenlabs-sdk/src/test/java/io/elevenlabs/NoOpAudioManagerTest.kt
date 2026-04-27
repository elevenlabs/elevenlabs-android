package io.elevenlabs

import io.elevenlabs.audio.NoOpAudioManager
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpAudioManagerTest {

    @Test
    fun `defaults are sane`() {
        val manager = NoOpAudioManager()

        assertTrue(manager.hasAudioPermission())
        assertFalse(manager.isRecording())
        assertFalse(manager.isPlaying())
        assertFalse(manager.isMuted())
        assertEquals(1.0f, manager.getVolume(), 0.0f)
    }

    @Test
    fun `recording and playback methods are inert`() = runTest {
        val manager = NoOpAudioManager()

        manager.startRecording()
        manager.startPlayback()

        assertFalse(manager.isRecording())
        assertFalse(manager.isPlaying())

        manager.stopRecording()
        manager.stopPlayback()
    }

    @Test
    fun `setVolume clamps to 0_0 - 1_0`() {
        val manager = NoOpAudioManager()

        manager.setVolume(2.0f)
        assertEquals(1.0f, manager.getVolume(), 0.0f)

        manager.setVolume(-0.5f)
        assertEquals(0.0f, manager.getVolume(), 0.0f)

        manager.setVolume(0.42f)
        assertEquals(0.42f, manager.getVolume(), 0.0001f)
    }

    @Test
    fun `setMicMuted does not change mute state`() = runTest {
        val manager = NoOpAudioManager()

        manager.setMicMuted(true)
        assertFalse(manager.isMuted())
    }

    @Test
    fun `cleanup does not throw`() {
        NoOpAudioManager().cleanup()
    }
}
