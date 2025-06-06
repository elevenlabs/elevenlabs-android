# ElevenLabs SDK Consumer ProGuard Rules
# These rules will be applied to apps using the ElevenLabs SDK

# Keep all public ElevenLabs SDK classes
-keep public class com.elevenlabs.sdk.** { *; }

# Keep enum values
-keepclassmembers enum com.elevenlabs.sdk.** {
    **[] $VALUES;
    public *;
}

# Keep serialization attributes
-keepattributes Signature
-keepattributes *Annotation*

# Keep kotlinx serialization for ElevenLabs classes
-keep @kotlinx.serialization.Serializable class com.elevenlabs.sdk.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep callback interfaces
-keep interface com.elevenlabs.sdk.ElevenLabsSDK$ClientToolHandler { *; }

# Keep OkHttp classes needed by the SDK
-dontwarn okhttp3.**
-dontwarn okio.**
