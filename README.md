# ElevenLabs Android SDK

The ElevenLabs Android SDK provides a convenient way to integrate ElevenLabs' conversational AI capabilities into your Android applications.

## Features

- Real-time voice conversations
- Client-side tool calling
- Multi-language support
- Audio session management
- Android-optimized audio processing
- ProGuard support
- **Easy to mock for testing**

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

### 3. Observe Conversation State

The SDK uses Kotlin Flows for reactive state management:

```kotlin
// Observe conversation events using coroutines
lifecycleScope.launch {
    // Observe connection status
    conversation.status.collect { status ->
        Log.d("ElevenLabs", "Status: ${status.name}")
    }
}

lifecycleScope.launch {
    // Observe conversation mode (SPEAKING/LISTENING)
    conversation.mode.collect { mode ->
        Log.d("ElevenLabs", "Mode: ${mode.name}")
    }
}

lifecycleScope.launch {
    // Observe volume changes
    conversation.volume.collect { volume ->
        // Update UI with volume level (0.0 to 1.0)
        updateVolumeUI(volume)
    }
}

lifecycleScope.launch {
    // Observe messages
    conversation.messages.collect { (message, role) ->
        Log.d("ElevenLabs", "Message from ${role.name}: $message")
    }
}

lifecycleScope.launch {
    // Observe errors
    conversation.errors.collect { (error, details) ->
        Log.e("ElevenLabs", "Error: $error")
    }
}
```

### 4. Start Conversation

```kotlin
class MainActivity : AppCompatActivity() {
    private var conversation: ElevenLabsSDK.Conversation? = null

    private fun startConversation() {
        lifecycleScope.launch {
            try {
                conversation = ElevenLabsSDK.startSession(
                    context = this@MainActivity,
                    config = config
                )
                
                // Start observing conversation state
                observeConversationState()
            } catch (e: Exception) {
                Log.e("ElevenLabs", "Failed to start conversation", e)
            }
        }
    }
    
    private fun observeConversationState() {
        conversation?.let { conv ->
            lifecycleScope.launch {
                conv.status.collect { status ->
                    when (status) {
                        ElevenLabsSDK.Status.CONNECTED -> {
                            Log.d("ElevenLabs", "Connected: ${conv.getId()}")
                        }
                        ElevenLabsSDK.Status.DISCONNECTED -> {
                            Log.d("ElevenLabs", "Disconnected")
                        }
                        else -> {
                            Log.d("ElevenLabs", "Status: ${status.name}")
                        }
                    }
                }
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

### Dependency Injection

The interface-based architecture makes dependency injection easy:

```kotlin
class ConversationRepository(
    private val sdk: ElevenLabsInterface
) {
    suspend fun startConversation(
        context: Context,
        config: ElevenLabsInterface.SessionConfig
    ): ElevenLabsInterface.Conversation {
        return sdk.startSession(context, config)
    }
}

// In your DI module (e.g., Dagger, Hilt, Koin)
@Provides
fun provideElevenLabsSDK(): ElevenLabsInterface = ElevenLabsSDK
```

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

## Testing

The SDK's interface-based architecture makes it incredibly easy to mock for testing:

### Unit Testing with Mocks

```kotlin
class ConversationViewModelTest {
    
    @Mock
    lateinit var mockSDK: ElevenLabsInterface
    
    @Mock
    lateinit var mockConversation: ElevenLabsInterface.Conversation
    
    @Test
    fun testStartConversation() = runTest {
        // Setup mock
        coEvery { 
            mockSDK.startSession(any(), any()) 
        } returns mockConversation
        
        every { mockConversation.getId() } returns "test-id"
        
        // Test your ViewModel that depends on ElevenLabsInterface
        val viewModel = ConversationViewModel(mockSDK)
        viewModel.startConversation(context, config)
        
        // Verify
        coVerify { mockSDK.startSession(context, config) }
        assertEquals("test-id", viewModel.conversationId)
    }
}
```

### Integration Testing

```kotlin
class MockElevenLabsSDK : ElevenLabsInterface {
    override suspend fun startSession(
        context: Context, 
        config: ElevenLabsInterface.SessionConfig
    ): ElevenLabsInterface.Conversation {
        return MockConversation()
    }
    
    override fun arrayBufferToBase64(data: ByteArray): String = "mock-base64"
    override fun base64ToArrayBuffer(base64: String): ByteArray? = byteArrayOf()
    override fun configureAudioSession(context: Context) { /* Mock implementation */ }
}

// Use in integration tests
val testSDK: ElevenLabsInterface = MockElevenLabsSDK()
```

### Creating Mock Conversations

```kotlin
class MockConversation : ElevenLabsInterface.Conversation {
    private val _mode = MutableStateFlow(ElevenLabsInterface.Mode.LISTENING)
    private val _status = MutableStateFlow(ElevenLabsInterface.Status.CONNECTED)
    private val _volume = MutableStateFlow(1.0f)
    private val _messages = MutableSharedFlow<Pair<String, ElevenLabsInterface.Role>>()
    private val _errors = MutableSharedFlow<Pair<String, Any?>>()
    
    override val mode: Flow<ElevenLabsInterface.Mode> = _mode.asStateFlow()
    override val status: Flow<ElevenLabsInterface.Status> = _status.asStateFlow()
    override val volume: Flow<Float> = _volume.asStateFlow()
    override val messages: Flow<Pair<String, ElevenLabsInterface.Role>> = _messages.asSharedFlow()
    override val errors: Flow<Pair<String, Any?>> = _errors.asSharedFlow()
    
    override var conversationVolume: Float = 1.0f
    
    override fun sendContextualUpdate(text: String) { /* Mock implementation */ }
    override fun sendUserMessage(text: String?) { /* Mock implementation */ }
    override fun sendUserActivity() { /* Mock implementation */ }
    override fun endSession() { /* Mock implementation */ }
    override fun getId(): String = "mock-conversation-id"
    override fun startRecording() { /* Mock implementation */ }
    override fun stopRecording() { /* Mock implementation */ }
}
```

### Example Test Setup

```kotlin
class ConversationRepositoryTest {
    
    @Mock
    lateinit var mockSDK: ElevenLabsInterface
    
    @Mock 
    lateinit var mockConversation: ElevenLabsInterface.Conversation
    
    @Mock
    lateinit var mockContext: Context
    
    private lateinit var repository: ConversationRepository
    
    @Before
    fun setup() {
        MockKAnnotations.init(this)
        repository = ConversationRepository(mockSDK)
    }
    
    @Test
    fun `startConversation should return conversation`() = runTest {
        // Given
        val config = ElevenLabsInterface.SessionConfig(agentId = "test-agent")
        coEvery { mockSDK.startSession(mockContext, config) } returns mockConversation
        every { mockConversation.getId() } returns "test-conversation-id"
        
        // When
        val result = repository.startConversation(mockContext, config)
        
        // Then
        assertEquals("test-conversation-id", result.getId())
        coVerify { mockSDK.startSession(mockContext, config) }
    }
}
```

## Error Handling

The SDK provides specific error types for different scenarios using Flow:

```kotlin
lifecycleScope.launch {
    conversation.errors.collect { (error, details) ->
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
}
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

## API Reference

### ElevenLabsSDK Object

The main SDK object that implements ElevenLabsInterface.

#### Methods

- `startSession(context: Context, config: SessionConfig): Conversation` - Start a new conversation session
- `arrayBufferToBase64(data: ByteArray): String` - Convert byte array to base64
- `base64ToArrayBuffer(base64: String): ByteArray?` - Convert base64 to byte array
- `configureAudioSession(context: Context)` - Configure audio for optimal performance

### ElevenLabsInterface

The interface that defines the SDK contract - implement this for mocking in tests.

### Conversation Interface

The main conversation interface for managing real-time conversations using Flow.

#### Flow Properties

- `mode: Flow<Mode>` - Current conversation mode (SPEAKING/LISTENING)
- `status: Flow<Status>` - Connection status
- `volume: Flow<Float>` - Current volume level
- `messages: Flow<Pair<String, Role>>` - Message stream with role information
- `errors: Flow<Pair<String, Any?>>` - Error stream with details

#### Methods

- `endSession()` - End the current conversation
- `sendContextualUpdate(text: String)` - Send contextual information
- `sendUserMessage(text: String?)` - Send a user message
- `sendUserActivity()` - Signal user activity
- `startRecording()` / `stopRecording()` - Control recording state
- `getId(): String` - Get conversation ID

#### Properties

- `conversationVolume: Float` - Get/set conversation volume

## Migration Guide

### For Normal Usage (No Breaking Changes)

```kotlin
// Works exactly the same as before
val conversation = ElevenLabsSDK.startSession(context, config)
```

### For Testing (New Improved Approach)

**Before:**
```kotlin
// Old approach - harder to mock
val conversation = ElevenLabsSDK.startSession(context, config)
```

**After:**
```kotlin
// New approach - easy to mock
class MyClass(private val sdk: ElevenLabsInterface) {
    suspend fun start() {
        val conversation = sdk.startSession(context, config)
    }
}

// In tests
val mockSDK = mockk<ElevenLabsInterface>()
```

## Translation Overrides

You can customize the SDK's text strings by overriding the default translations. Create your own `strings.xml` file in your app's `res/values/` directory and override any of the strings defined in the SDK.

## ProGuard

The SDK includes ProGuard rules to ensure proper obfuscation. No additional configuration is needed.

## Sample App

Check out the `sample` module for a complete example implementation.

## Support

For support and documentation, visit [ElevenLabs Documentation](https://elevenlabs.io/docs).

## License

This SDK is provided under the ElevenLabs License Agreement.
