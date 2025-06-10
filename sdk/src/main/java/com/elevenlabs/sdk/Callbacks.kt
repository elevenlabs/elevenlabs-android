package com.elevenlabs.sdk

data class Callbacks(
    val onConnect: (String) -> Unit = { },
    val onDisconnect: () -> Unit = { },
    val onMessage: (String, ElevenLabsInterface.Role) -> Unit = { _, _ -> },
    val onError: (String, Any?) -> Unit = { _, _ -> },
    val onStatusChange: (ElevenLabsInterface.Status) -> Unit = { },
    val onModeChange: (ElevenLabsInterface.Mode) -> Unit = { },
    val onVolumeUpdate: (Float) -> Unit = { },
    val onOutputVolumeUpdate: ((Float) -> Unit)? = null,
    val onMessageCorrection: ((String, String, ElevenLabsInterface.Role) -> Unit)? = null,
    
    // LiveKit specific callbacks
    val onConnectionQualityChanged: ((io.livekit.android.room.ConnectionQuality) -> Unit)? = null,
    val onReconnecting: (() -> Unit)? = null,
    val onReconnected: (() -> Unit)? = null
) 