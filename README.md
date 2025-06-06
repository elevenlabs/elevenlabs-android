# ElevenLabs Android SDK

The ElevenLabs Android SDK provides a convenient way to integrate ElevenLabs' conversational AI capabilities into your Android applications.

## Features

- Real-time voice conversations
- Client-side tool calling
- Multi-language support
- Audio session management
- Android-optimized audio processing
- ProGuard support

## Requirements

- Android API level 24 (Android 7.0) or higher
- Kotlin 1.9.0+
- Internet connection for real-time conversations

## Installation

### Gradle

Add the following to your app's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.elevenlabs:android-sdk:1.1.3")
}
```

### Permissions

Add the following permissions to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

## Quick Start

### 1. Request Permissions

First, ensure you have the necessary permissions:

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request audio permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }
}
```

### 2. Configure Session

Create a session configuration with your agent ID:

```kotlin
val config = ElevenLabsSDK.SessionConfig(
    agentId = "your-agent-id"
)
```

Or use a signed URL:

```kotlin
val config = ElevenLabsSDK.SessionConfig(
    signedUrl = "wss://your-signed-url"
)
```

### 3. Set Up Callbacks

Define callbacks to handle conversation events:

```kotlin
val callbacks = ElevenLabsSDK.Callbacks(
    onConnect = { conversationId ->
        Log.d("ElevenLabs", "Connected: $conversationId")
    },
    onDisconnect = {
        Log.d("ElevenLabs", "Disconnected")
    },
    onMessage = { message, role ->
        Log.d("ElevenLabs", "Message from ${role.name}: $message")
    },
    onError = { error, details ->
        Log.e("ElevenLabs", "Error: $error")
    },
    onStatusChange = { status ->
        Log.d("ElevenLabs", "Status: ${status.name}")
    },
    onModeChange = { mode ->
        Log.d("ElevenLabs", "Mode: ${mode.name}")
    },
    onVolumeUpdate = { volume ->
        // Update UI with volume level (0.0 to 1.0)
    }
)
```

### 4. Start Conversation

```kotlin
class MainActivity : AppCompatActivity() {
    private var conversation: ElevenLabsSDK.Conversation? = null

    private fun startConversation() {
        lifecycleScope.launch {
            try {
                conversation = ElevenLabsSDK.Conversation.startSession(
                    context = this@MainActivity,
                    config = config,
                    callbacks = callbacks
                )
            } catch (e: Exception) {
                Log.e("ElevenLabs", "Failed to start conversation", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.endSession()
    }
}
```

## Advanced Usage

### Custom Configuration

```kotlin
val overrides = ElevenLabsSDK.ConversationConfigOverride(
    agent = ElevenLabsSDK.AgentConfig(
        prompt = ElevenLabsSDK.AgentPrompt("You are a helpful assistant"),
        firstMessage = "Hello! How can I help you today?",
        language = ElevenLabsSDK.Language.EN
    ),
    tts = ElevenLabsSDK.TTSConfig(voiceId = "your-voice-id")
)

val config = ElevenLabsSDK.SessionConfig(
    agentId = "your-agent-id",
    overrides = overrides
)
```

### Client Tools

Implement custom client tools for the AI agent to call:

```kotlin
val clientTools = ElevenLabsSDK.ClientTools()

clientTools.register("get_weather") { parameters ->
    val location = parameters["location"] as? String ?: "Unknown"
    // Implement your weather API call here
    "The weather in $location is sunny and 25°C"
}

val config = ElevenLabsSDK.SessionConfig(
    agentId = "your-agent-id",
    clientTools = clientTools
)
```

### Dynamic Variables

Pass dynamic variables to customize agent behavior:

```kotlin
val dynamicVariables = mapOf(
    "user_name" to ElevenLabsSDK.DynamicVariableValue.StringValue("John"),
    "user_level" to ElevenLabsSDK.DynamicVariableValue.IntValue(5),
    "premium_user" to ElevenLabsSDK.DynamicVariableValue.BooleanValue(true)
)

val config = ElevenLabsSDK.SessionConfig(
    agentId = "your-agent-id",
    dynamicVariables = dynamicVariables
)
```

### Manual Controls

```kotlin
// Send contextual updates
conversation?.sendContextualUpdate("User is looking at the product page")

// Send user messages
conversation?.sendUserMessage("Tell me about this product")

// Control recording
conversation?.stopRecording()
conversation?.startRecording()

// Adjust volume
conversation?.conversationVolume = 0.8f

// Send user activity signals
conversation?.sendUserActivity()
```

## Error Handling

The SDK provides specific error types for different scenarios:

```kotlin
val callbacks = ElevenLabsSDK.Callbacks(
    onError = { error, details ->
        when (details) {
            is ElevenLabsSDK.ElevenLabsError.InvalidConfiguration -> {
                // Handle configuration errors
            }
            is ElevenLabsSDK.ElevenLabsError.RecordAudioPermissionNotGranted -> {
                // Request audio permission
            }
            is ElevenLabsSDK.ElevenLabsError.AudioSessionConfigurationFailed -> {
                // Handle audio setup issues
            }
            else -> {
                Log.e("ElevenLabs", "Unexpected error: $error")
            }
        }
    }
)
```

## Utility Functions

The SDK includes utility functions for common tasks:

```kotlin
// Permission checking
val hasPermissions = PermissionUtils.hasAllRequiredPermissions(context)
val missingPermissions = PermissionUtils.getMissingPermissions(context)

// Audio utilities
val rms = AudioUtils.calculateRMS(audioData)
val normalizedLevel = AudioUtils.normalizeAudioLevel(rms)
val floatArray = AudioUtils.pcm16ToFloat(pcmData)
```

## Lifecycle Management

Properly manage the conversation lifecycle:

```kotlin
class ConversationActivity : AppCompatActivity() {
    private var conversation: ElevenLabsSDK.Conversation? = null

    override fun onPause() {
        super.onPause()
        conversation?.stopRecording()
    }

    override fun onResume() {
        super.onResume()
        conversation?.startRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.endSession()
    }
}
```

## ProGuard

The SDK includes ProGuard rules to ensure proper obfuscation. No additional configuration is needed.

## Sample App

Check out the `sample` module for a complete example implementation.

## API Reference

### ElevenLabsSDK

The main SDK class providing static utility methods and configuration.

#### Methods

- `arrayBufferToBase64(data: ByteArray): String` - Convert byte array to base64
- `base64ToArrayBuffer(base64: String): ByteArray?` - Convert base64 to byte array
- `configureAudioSession(context: Context)` - Configure audio for optimal performance

### Conversation

The main conversation class for managing real-time conversations.

#### Methods

- `startSession(context: Context, config: SessionConfig, callbacks: Callbacks): Conversation` - Start a new conversation
- `endSession()` - End the current conversation
- `sendContextualUpdate(text: String)` - Send contextual information
- `sendUserMessage(text: String?)` - Send a user message
- `sendUserActivity()` - Signal user activity
- `startRecording()` / `stopRecording()` - Control recording state

#### Properties

- `mode: StateFlow<Mode>` - Current conversation mode (SPEAKING/LISTENING)
- `status: StateFlow<Status>` - Connection status
- `volume: StateFlow<Float>` - Current volume level
- `conversationVolume: Float` - Get/set conversation volume

## Support

For support and documentation, visit [ElevenLabs Documentation](https://elevenlabs.io/docs).

## License

This SDK is provided under the ElevenLabs License Agreement.
