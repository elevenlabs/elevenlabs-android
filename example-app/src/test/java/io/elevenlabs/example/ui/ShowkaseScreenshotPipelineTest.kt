package io.elevenlabs.example.ui

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.airbnb.android.showkase.models.Showkase
import org.junit.Rule
import org.junit.Test

/**
 * Showkase + Paparazzi screenshot pipeline.
 *
 * Discovers every `@ShowkaseComposable` registered on [ExampleShowkaseRoot] via
 * `Showkase.getMetadata()` and records a Paparazzi snapshot for each one.
 *
 * Record: `./gradlew :example-app:recordPaparazziDebug`
 * Verify: `./gradlew :example-app:verifyPaparazziDebug`
 */
class ShowkaseScreenshotPipelineTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
    )

    @Test
    fun snapshotShowkaseComponents() {
        val components = Showkase.getMetadata().componentList
        require(components.isNotEmpty()) {
            "Showkase catalog was empty — KSP did not pick up @ShowkaseComposable previews."
        }

        components.forEach { component ->
            val name = listOfNotNull(
                component.group,
                component.componentName,
                component.styleName,
            ).joinToString("_")
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")

            paparazzi.snapshot(name = name) {
                component.component()
            }
        }
    }
}
