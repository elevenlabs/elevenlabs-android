package io.elevenlabs

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientToolRegistryTest {

    @Test
    fun `a function tool's own timeout is reported as a function failure`() = runTest {
        val registry = ClientToolRegistryBuilder()
            .addTool("tool") { withTimeout(1) { awaitCancellation() } }
            .build()

        val result = registry.executeTool("tool", emptyMap())

        assertTrue(result?.error?.startsWith("Function execution failed") == true)
        registry.cleanup()
    }
}
