# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep all public APIs
-keep public class com.elevenlabs.sdk.** { *; }

# Keep serialization classes
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep kotlinx serialization
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,RuntimeVisibleTypeAnnotations,AnnotationDefault
-keep,allowshrinking,allowoptimization class * extends kotlinx.serialization.KSerializer
-keep,allowshrinking,allowoptimization class * implements kotlinx.serialization.KSerializer

# Keep enums
-keepclassmembers enum * {
    **[] $VALUES;
    public *;
}

# Keep WebSocket related classes
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep audio-related classes
-keep class android.media.** { *; }

# Keep callback interfaces
-keep interface com.elevenlabs.sdk.ElevenLabsSDK$ClientToolHandler { *; }

# Keep data classes for serialization
-keep @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Suppress warnings
-dontwarn okio.**
-dontwarn okhttp3.**
-dontwarn javax.annotation.**
