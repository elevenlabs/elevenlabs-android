package io.elevenlabs.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.elevenlabs.example.R
import io.elevenlabs.models.ConversationStatus

/**
 * Landing surface shown while no session is active. Choose modality with the toggle, then tap
 * Connect — the host wires the appropriate transport (voice + LiveKit, or text-only WebSocket).
 */
@Composable
fun StartScreen(
    textOnlyMode: Boolean,
    onToggleTextOnly: (Boolean) -> Unit,
    status: ConversationStatus,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnecting = status == ConversationStatus.CONNECTING
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
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

        Spacer(Modifier.height(40.dp))


        Button(
            onClick = onConnect,
            enabled = !isConnecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = if (isConnecting) "Connecting…" else "Connect")
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Use Text-Only Mode",
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = textOnlyMode, onCheckedChange = onToggleTextOnly)
        }

    }
}

@Preview(showBackground = true, name = "Disconnected — voice")
@Composable
private fun StartScreenPreviewDisconnected() {
    AppTheme {
        StartScreen(
            textOnlyMode = false,
            onToggleTextOnly = {},
            status = ConversationStatus.DISCONNECTED,
            onConnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Disconnected — text-only")
@Composable
private fun StartScreenPreviewTextOnly() {
    AppTheme {
        StartScreen(
            textOnlyMode = true,
            onToggleTextOnly = {},
            status = ConversationStatus.DISCONNECTED,
            onConnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Connecting")
@Composable
private fun StartScreenPreviewConnecting() {
    AppTheme {
        StartScreen(
            textOnlyMode = false,
            onToggleTextOnly = {},
            status = ConversationStatus.CONNECTING,
            onConnect = {},
        )
    }
}
