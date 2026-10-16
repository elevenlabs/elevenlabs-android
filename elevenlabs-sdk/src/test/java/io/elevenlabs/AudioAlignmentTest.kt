package io.elevenlabs

import io.elevenlabs.audio.AudioManager
import io.elevenlabs.models.AudioAlignment
import io.elevenlabs.models.ConversationEvent
import io.elevenlabs.network.ConversationEventParser
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Character alignment of the agent's audio, as sent on the "audio" (WebRTC data channel and WebSocket)
 * and "audio_alignment" events, is exposed as a typed [AudioAlignment].
 */
class AudioAlignmentTest {

    private val soft = AudioAlignment(
        eventId = 56,
        chars = listOf("S", "o", "f", "t"),
        charStartTimesMs = listOf(0, 80, 160, 240),
        charDurationsMs = listOf(80, 80, 80, 160)
    )

    private val alignmentJson =
        """{"chars":["S","o","f","t"],"char_start_times_ms":[0,80,160,240],"char_durations_ms":[80,80,80,160]}"""

    @Test
    fun `alignment-only audio event is parsed into a typed alignment`() {
        val event = ConversationEventParser.parseIncomingEvent(
            """{"type":"audio","audio_event":{"event_id":56,"alignment":$alignmentJson,"is_final":false}}"""
        )

        assertEquals(soft, (event as ConversationEvent.AudioAlignment).typed)
    }

    @Test
    fun `audio_alignment event with fractional times is parsed into a typed alignment`() {
        val event = ConversationEventParser.parseIncomingEvent(
            """{"type":"audio_alignment","audio_alignment_event":{"event_id":56,"alignment":""" +
                """{"chars":["S","o","f","t"],"char_start_times_ms":[0.0,80.0,160.0,240.0],"char_durations_ms":[80.0,80.0,80.0,160.0]}}}"""
        )

        assertEquals(soft, (event as ConversationEvent.AudioAlignment).typed)
    }

    @Test
    fun `audio event that also carries audio keeps its alignment`() {
        val event = ConversationEventParser.parseIncomingEvent(
            """{"type":"audio","audio_event":{"event_id":56,"audio_base_64":"AAAA","alignment":$alignmentJson}}"""
        )

        event as ConversationEvent.Audio
        assertEquals("AAAA", event.audioBase64)
        assertEquals(soft, event.alignment)
    }

    @Test
    fun `malformed alignment yields no typed alignment`() {
        val mismatched = ConversationEventParser.parseIncomingEvent(
            """{"type":"audio","audio_event":{"event_id":1,"alignment":""" +
                """{"chars":["a","b"],"char_start_times_ms":[0],"char_durations_ms":[10,10]}}}"""
        )
        val empty = ConversationEventParser.parseIncomingEvent(
            """{"type":"audio","audio_event":{"event_id":1,"alignment":""" +
                """{"chars":[],"char_start_times_ms":[],"char_durations_ms":[]}}}"""
        )

        assertNull((mismatched as ConversationEvent.AudioAlignment).typed)
        assertNull((empty as ConversationEvent.AudioAlignment).typed)
    }

    @Test
    fun `handler reports typed alignment from both event shapes and keeps the map callback`() = runTest {
        val typed = mutableListOf<AudioAlignment>()
        val maps = mutableListOf<Map<String, Any>>()
        val handler = ConversationEventHandler(
            audioManager = mockk<AudioManager>(relaxed = true),
            toolRegistry = ClientToolRegistry(),
            messageCallback = { },
            onAudioAlignment = { maps += it },
            onAudioAlignmentEvent = { typed += it }
        )

        handler.handleIncomingEvent(
            ConversationEventParser.parseIncomingEvent(
                """{"type":"audio","audio_event":{"event_id":56,"alignment":$alignmentJson}}"""
            )!!
        )
        handler.handleIncomingEvent(
            ConversationEventParser.parseIncomingEvent(
                """{"type":"audio","audio_event":{"event_id":56,"audio_base_64":"AAAA","alignment":$alignmentJson}}"""
            )!!
        )

        assertEquals(listOf(soft, soft), typed)
        assertEquals(1, maps.size)
        assertTrue(maps.single().containsKey("alignment"))
    }

    @Test
    fun `a throwing typed callback does not break event handling`() = runTest {
        val handler = ConversationEventHandler(
            audioManager = mockk<AudioManager>(relaxed = true),
            toolRegistry = ClientToolRegistry(),
            messageCallback = { },
            onAudioAlignmentEvent = { throw IllegalStateException("boom") }
        )

        handler.handleIncomingEvent(
            ConversationEventParser.parseIncomingEvent(
                """{"type":"audio","audio_event":{"event_id":56,"alignment":$alignmentJson}}"""
            )!!
        )
        handler.handleIncomingEvent(ConversationEvent.Interruption(eventId = 57))
    }
}
