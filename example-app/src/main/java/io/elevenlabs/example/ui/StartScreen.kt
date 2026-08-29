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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.airbnb.android.showkase.annotation.ShowkaseComposable
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
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
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
                color = MaterialTheme.colorScheme.onBackground,
            )

            Spacer(Modifier.height(40.dp))

            Button(
                onClick = onConnect,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
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
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Switch(
                    checked = textOnlyMode,
                    onCheckedChange = onToggleTextOnly,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Disconnected — voice")
@ShowkaseComposable(name = "Start — voice", group = "Echo / StartScreen")
@Composable
internal fun StartScreenPreviewDisconnected() {
    EchoTheme {
        StartScreen(
            textOnlyMode = false,
            onToggleTextOnly = {},
            status = ConversationStatus.DISCONNECTED,
            onConnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Disconnected — text-only")
@ShowkaseComposable(name = "Start — text-only", group = "Echo / StartScreen")
@Composable
internal fun StartScreenPreviewTextOnly() {
    EchoTheme {
        StartScreen(
            textOnlyMode = true,
            onToggleTextOnly = {},
            status = ConversationStatus.DISCONNECTED,
            onConnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Connecting")
@ShowkaseComposable(name = "Start — connecting", group = "Echo / StartScreen")
@Composable
internal fun StartScreenPreviewConnecting() {
    EchoTheme {
        StartScreen(
            textOnlyMode = false,
            onToggleTextOnly = {},
            status = ConversationStatus.CONNECTING,
            onConnect = {},
        )
    }
}
