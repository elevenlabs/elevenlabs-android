package io.elevenlabs.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.elevenlabs.example.ui.AppTheme
import io.elevenlabs.example.ui.StartScreen
import io.elevenlabs.example.ui.TextChatScreen
import io.elevenlabs.example.ui.VoiceScreen
import io.elevenlabs.example.viewmodels.ConversationViewModel
import io.elevenlabs.models.ConversationStatus

private const val PREFS_NAME = "elevenlabs_permissions"
private const val PREF_KEY_AUDIO_PERMISSION = "audio_permission_working"

/**
 * Single-activity Compose host. Routes between two states:
 *  - [StartScreen]: pre-connection landing with the modality toggle and Connect button.
 *  - In-session: the screen for the chosen modality — [VoiceScreen] or [TextChatScreen]. The
 *    modality is locked at connect time; ending the session returns to the start screen.
 *
 * All state lives on a single shared [ConversationViewModel].
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ConversationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(viewModel = viewModel)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(viewModel: ConversationViewModel) {
    val context = LocalContext.current

    // User-controlled modality (set on the start screen). Frozen for the duration of a session.
    var textOnlyMode by rememberSaveable { mutableStateOf(false) }

    val status by viewModel.sessionStatus.observeAsState(ConversationStatus.DISCONNECTED)
    val mode by viewModel.mode.observeAsState()
    val isMuted by viewModel.isMuted.observeAsState(false)
    val canSendFeedback by viewModel.canSendFeedback.observeAsState(false)
    val errorMessage by viewModel.errorMessage.observeAsState()
    val mutedSpeechEvent by viewModel.mutedSpeechEvent.observeAsState()
    val messages by viewModel.messages.collectAsState()
    val isAgentTyping by viewModel.isAgentTyping.collectAsState()

    val effectiveStatus = status ?: ConversationStatus.DISCONNECTED

    // Permission launcher for voice mode. Persists a "working" flag (workaround for a LiveKit
    // 2.13.0+ bug that occasionally fails to detect a fresh permission grant on the first call).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            context.getSharedPreferences(PREFS_NAME, ComponentActivity.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_AUDIO_PERMISSION, true)
                .apply()
            viewModel.startConversation(context, textOnly = false)
        } else {
            Toast.makeText(
                context,
                "Microphone permission is required for voice conversations",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Surface backend errors as toasts at the app level rather than per-screen.
    LaunchedEffect(errorMessage) {
        val msg = errorMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        viewModel.clearError()
    }

    // Auto-dismiss the muted-speech banner after 4s if the user doesn't tap it.
    LaunchedEffect(mutedSpeechEvent) {
        if (mutedSpeechEvent != null) {
            delay(4_000L)
            viewModel.clearMutedSpeechEvent()
        }
    }

    // Stay on the start screen until the session is fully CONNECTED. DISCONNECTING is treated as
    // in-session so the controls don't yank out from under the user mid-teardown.
    val showStart = effectiveStatus != ConversationStatus.CONNECTED &&
        effectiveStatus != ConversationStatus.DISCONNECTING

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            showStart -> StartScreen(
                textOnlyMode = textOnlyMode,
                onToggleTextOnly = { textOnlyMode = it },
                status = effectiveStatus,
                onConnect = {
                    if (textOnlyMode) {
                        viewModel.startConversation(context, textOnly = true)
                    } else if (hasWorkingMicPermission(context)) {
                        viewModel.startConversation(context, textOnly = false)
                    } else {
                        Log.d("MainActivity", "Requesting RECORD_AUDIO permission for voice mode")
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            )

            textOnlyMode -> TextChatScreen(
                status = effectiveStatus,
                messages = messages,
                isAgentTyping = isAgentTyping,
                errorMessage = errorMessage,
                onSend = { viewModel.sendUserMessage(it) },
                onRetry = { viewModel.retry(context) },
                onDisconnect = { viewModel.endConversation() },
            )

            else -> VoiceScreen(
                status = effectiveStatus,
                mode = mode,
                isMuted = isMuted ?: false,
                canSendFeedback = canSendFeedback ?: false,
                onDisconnect = { viewModel.endConversation() },
                onToggleMute = { viewModel.toggleMute() },
                onSetVolume = { viewModel.setVolume(it) },
                onThumbsUp = { viewModel.sendFeedback(true) },
                onThumbsDown = { viewModel.sendFeedback(false) },
                onSendContextual = { viewModel.sendContextualUpdate(it) },
                onSendUserMessage = { viewModel.sendUserMessage(it) },
            )
        }

        // Tappable "you appear to be speaking while muted" banner. Surfaced as a top overlay so it
        // floats above the active screen rather than living inside any single one.
        if (mutedSpeechEvent != null && (isMuted ?: false) &&
            effectiveStatus == ConversationStatus.CONNECTED && !textOnlyMode) {
            MutedSpeechBanner(
                modifier = Modifier.align(Alignment.TopCenter),
                onTap = {
                    viewModel.toggleMute()
                    viewModel.clearMutedSpeechEvent()
                },
            )
        }
    }
}

private val MutedSpeechBannerColor = Color(0xFFF59E0B)

@Composable
private fun MutedSpeechBanner(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(MutedSpeechBannerColor)
            .clickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "You appear to be speaking. Tap to unmute.",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private fun hasWorkingMicPermission(context: android.content.Context): Boolean {
    val systemPermission = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val workingFlag = context.getSharedPreferences(PREFS_NAME, ComponentActivity.MODE_PRIVATE)
        .getBoolean(PREF_KEY_AUDIO_PERMISSION, false)
    return systemPermission && workingFlag
}
