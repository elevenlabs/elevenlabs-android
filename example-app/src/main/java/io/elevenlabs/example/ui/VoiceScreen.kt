package io.elevenlabs.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.elevenlabs.example.R
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus

private val ConnectedGreen = Color(0xFF22C55E)
private val ConnectingAmber = Color(0xFFF59E0B)
private val DisconnectedGray = Color(0xFF9CA3AF)
private val ErrorRed = Color(0xFFEF4444)

/**
 * Voice-mode controls: status, mute, volume, feedback, mode indicator, and a composer for sending
 * contextual updates or text user messages over the active session. Owns its own Disconnect
 * action — there is no shared header.
 */
@Composable
fun VoiceScreen(
    status: ConversationStatus,
    mode: ConversationMode?,
    isMuted: Boolean,
    canSendFeedback: Boolean,
    onDisconnect: () -> Unit,
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, alignment = Alignment.CenterVertically),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(id = R.drawable.elevenlabs_logo),
                contentDescription = "ElevenLabs Logo",
                modifier = Modifier
                    .height(48.dp)
                    .fillMaxWidth(0.7f),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Android Example App",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(24.dp))

        StatusCard(status)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Disconnect")
        }

        if (isConnected) {
            ModeIndicator(mode = mode)

            OutlinedButton(
                onClick = onToggleMute,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = if (isMuted) "Unmute" else "Mute")
            }

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
            .fillMaxWidth(),
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.height(IntrinsicSize.Max),
        ) {
            Button(
                onClick = onSendContextual,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text("Send contextual message", textAlign = TextAlign.Center)
            }
            Button(
                onClick = onSendUser,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                Text("Send user message", textAlign = TextAlign.Center)
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
            onDisconnect = {},
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
            onDisconnect = {},
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
            onDisconnect = {},
            onToggleMute = {},
            onSetVolume = {},
            onThumbsUp = {},
            onThumbsDown = {},
            onSendContextual = {},
            onSendUserMessage = {},
        )
    }
}

