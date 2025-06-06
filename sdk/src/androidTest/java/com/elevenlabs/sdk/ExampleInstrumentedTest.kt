package com.elevenlabs.sdk

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.elevenlabs.sdk.test", appContext.packageName)
    }

    @Test
    fun testAudioSessionConfiguration() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        
        // Test that audio session configuration doesn't crash
        try {
            ElevenLabsSDK.configureAudioSession(appContext)
            // If no exception is thrown, test passes
            assertTrue(true)
        } catch (e: Exception) {
            fail("Audio session configuration should not throw exception: ${e.message}")
        }
    }
} 