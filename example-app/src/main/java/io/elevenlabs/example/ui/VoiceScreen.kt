package io.elevenlabs.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus

private val ConnectedGreen = Color(0xFF22C55E)
private val ConnectingAmber = Color(0xFFF59E0B)
private val DisconnectedGray = Color(0xFF9CA3AF)
private val ErrorRed = Color(0xFFEF4444)

/**
 * Voice-mode controls: status, mute, volume, feedback, mode indicator, and a composer for sending
 * contextual updates or text user messages over the active session. Connecting / disconnecting is
 * driven by the host (start screen + header), not from this surface.
 */
@Composable
fun VoiceScreen(
    status: ConversationStatus,
    mode: ConversationMode?,
    isMuted: Boolean,
    canSendFeedback: Boolean,
    onToggleMute: () -> Unit,
    onSetVolume: (Float) -> Unit,
    onThumbsUp: () -> Unit,
    onThumbsDown: () -> Unit,
    onSendContextual: (String) -> Unit,
    onSendUserMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected = status == ConversationStatus.CONNECTED
    var input by rememberSaveable { mutableStateOf("") }
    var volume by rememberSaveable { mutableStateOf(1f) }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("status") {
            StatusCard(status = status)
        }

        if (isConnected) {
            item("mode") {
                ModeIndicator(mode = mode)
            }

            item("mute") {
                OutlinedButton(
                    onClick = onToggleMute,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = if (isMuted) "Unmute" else "Mute")
                }
            }

            item("volume") {
                Column {
                    Text(text = "Volume", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = volume,
                        onValueChange = {
                            volume = it
                            onSetVolume(it)
                        },
                        valueRange = 0f..1f,
                    )
                }
            }

            item("feedback") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onThumbsUp,
                        enabled = canSendFeedback,
                        modifier = Modifier.weight(1f),
                    ) { Text("👍") }
                    OutlinedButton(
                        onClick = onThumbsDown,
                        enabled = canSendFeedback,
                        modifier = Modifier.weight(1f),
                    ) { Text("👎") }
                }
            }

            item("composer") {
                MessageComposer(
                    value = input,
                    onValueChange = { input = it },
                    onSendUser = {
                        if (input.isNotBlank()) {
                            onSendUserMessage(input)
                            input = ""
                        }
                    },
                    onSendContextual = {
                        if (input.isNotBlank()) {
                            onSendContextual(input)
                            input = ""
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: ConversationStatus) {
    val (label, color) = when (status) {
        ConversationStatus.CONNECTED -> "Connected to ElevenLabs" to ConnectedGreen
        ConversationStatus.CONNECTING -> "Connecting…" to ConnectingAmber
        ConversationStatus.DISCONNECTED -> "Disconnected" to DisconnectedGray
        ConversationStatus.DISCONNECTING -> "Disconnecting…" to DisconnectedGray
        ConversationStatus.ERROR -> "Connection error" to ErrorRed
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Status: $label", color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModeIndicator(mode: ConversationMode?) {
    val (label, color) = when (mode) {
        ConversationMode.SPEAKING -> "Speaking" to ConnectedGreen
        ConversationMode.LISTENING -> "Listening" to DisconnectedGray
        null -> "Idle" to DisconnectedGray
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.size(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MessageComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSendUser: () -> Unit,
    onSendContextual: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(12.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            singleLine = true,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = "Type message…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    inner()
                }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSendContextual, modifier = Modifier.weight(1f)) {
                Text("Send contextual")
            }
            Button(onClick = onSendUser, modifier = Modifier.weight(1f)) {
                Text("Send user msg")
            }
        }
    }
}

@Preview(showBackground = true, name = "Connected — listening")
@Composable
private fun VoiceScreenPreviewListening() {
    AppTheme {
        VoiceScreen(
            status = ConversationStatus.CONNECTED,
            mode = ConversationMode.LISTENING,
            isMuted = false,
            canSendFeedback = true,
            onToggleMute = {},
            onSetVolume = {},
            onThumbsUp = {},
            onThumbsDown = {},
            onSendContextual = {},
            onSendUserMessage = {},
        )
    }
}

@Preview(showBackground = true, name = "Connected — speaking, muted")
@Composable
private fun VoiceScreenPreviewSpeakingMuted() {
    AppTheme {
        VoiceScreen(
            status = ConversationStatus.CONNECTED,
            mode = ConversationMode.SPEAKING,
            isMuted = true,
            canSendFeedback = false,
            onToggleMute = {},
            onSetVolume = {},
            onThumbsUp = {},
            onThumbsDown = {},
            onSendContextual = {},
            onSendUserMessage = {},
        )
    }
}

@Preview(showBackground = true, name = "Connecting")
@Composable
private fun VoiceScreenPreviewConnecting() {
    AppTheme {
        VoiceScreen(
            status = ConversationStatus.CONNECTING,
            mode = null,
            isMuted = false,
            canSendFeedback = false,
            onToggleMute = {},
            onSetVolume = {},
            onThumbsUp = {},
            onThumbsDown = {},
            onSendContextual = {},
            onSendUserMessage = {},
        )
    }
}

