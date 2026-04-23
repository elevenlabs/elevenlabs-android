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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.elevenlabs.example.models.TextChatState
import io.elevenlabs.example.ui.AppTheme
import io.elevenlabs.example.ui.StartScreen
import io.elevenlabs.example.ui.TextChatScreen
import io.elevenlabs.example.ui.VoiceScreen
import io.elevenlabs.example.viewmodels.ConversationViewModel
import io.elevenlabs.models.ConversationStatus

private const val PREFS_NAME = "elevenlabs_permissions"
private const val PREF_KEY_AUDIO_PERMISSION = "audio_permission_working"

private enum class InSessionView { VOICE, TEXT }

/**
 * Single-activity Compose host. Routes between three states:
 *  - [StartScreen]: pre-connection landing with the modality toggle and Connect button.
 *  - Text-only in-session: just [TextChatScreen]; no view-switching available.
 *  - Mixed (voice) in-session: header with a "Switch view" button toggling between
 *    [VoiceScreen] and [TextChatScreen]; the same session powers both views.
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
    // Within a non-text-only session, which view the user is currently looking at.
    var inSessionView by rememberSaveable { mutableStateOf(InSessionView.VOICE) }

    val status by viewModel.sessionStatus.observeAsState(ConversationStatus.DISCONNECTED)
    val mode by viewModel.mode.observeAsState()
    val isMuted by viewModel.isMuted.observeAsState(false)
    val canSendFeedback by viewModel.canSendFeedback.observeAsState(false)
    val errorMessage by viewModel.errorMessage.observeAsState()
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
                        inSessionView = InSessionView.TEXT
                        viewModel.startConversation(context, textOnly = true)
                    } else {
                        inSessionView = InSessionView.VOICE
                        if (hasWorkingMicPermission(context)) {
                            viewModel.startConversation(context, textOnly = false)
                        } else {
                            Log.d("MainActivity", "Requesting RECORD_AUDIO permission for voice mode")
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                },
            )

            textOnlyMode -> Column(modifier = Modifier.fillMaxSize()) {
                SessionHeader(
                    showSwitchButton = false,
                    currentView = InSessionView.TEXT,
                    onSwitchView = {},
                    onDisconnect = { viewModel.endConversation() },
                )
                TextScreenHost(
                    status = effectiveStatus,
                    messages = messages,
                    isAgentTyping = isAgentTyping,
                    errorMessage = errorMessage,
                    onSend = { viewModel.sendUserMessage(it) },
                    onRetry = { viewModel.retry(context) },
                )
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                SessionHeader(
                    showSwitchButton = true,
                    currentView = inSessionView,
                    onSwitchView = {
                        inSessionView = if (inSessionView == InSessionView.VOICE) {
                            InSessionView.TEXT
                        } else {
                            InSessionView.VOICE
                        }
                    },
                    onDisconnect = { viewModel.endConversation() },
                )
                when (inSessionView) {
                    InSessionView.VOICE -> VoiceScreen(
                        status = effectiveStatus,
                        mode = mode,
                        isMuted = isMuted ?: false,
                        canSendFeedback = canSendFeedback ?: false,
                        onToggleMute = { viewModel.toggleMute() },
                        onSetVolume = { viewModel.setVolume(it) },
                        onThumbsUp = { viewModel.sendFeedback(true) },
                        onThumbsDown = { viewModel.sendFeedback(false) },
                        onSendContextual = { viewModel.sendContextualUpdate(it) },
                        onSendUserMessage = { viewModel.sendUserMessage(it) },
                    )

                    InSessionView.TEXT -> TextScreenHost(
                        status = effectiveStatus,
                        messages = messages,
                        isAgentTyping = isAgentTyping,
                        errorMessage = errorMessage,
                        onSend = { viewModel.sendUserMessage(it) },
                        onRetry = { viewModel.retry(context) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionHeader(
    showSwitchButton: Boolean,
    currentView: InSessionView,
    onSwitchView: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Image(
                painter = painterResource(id = R.drawable.elevenlabs_logo),
                contentDescription = "ElevenLabs Logo",
                modifier = Modifier.height(24.dp),
            )
            Text(
                text = "Android Example App",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showSwitchButton) {
                OutlinedButton(onClick = onSwitchView) {
                    Text(
                        text = when (currentView) {
                            InSessionView.VOICE -> "Switch to text"
                            InSessionView.TEXT -> "Switch to voice"
                        },
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            Button(onClick = onDisconnect) {
                Text("Disconnect")
            }
        }
    }
}

@Composable
private fun TextScreenHost(
    status: ConversationStatus,
    messages: List<io.elevenlabs.example.models.TextChatMessage>,
    isAgentTyping: Boolean,
    errorMessage: String?,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val state = remember(status, messages, isAgentTyping, errorMessage) {
        TextChatState(
            status = status,
            messages = messages,
            isAgentTyping = isAgentTyping,
            errorMessage = errorMessage,
        )
    }
    TextChatScreen(
        state = state,
        onSend = onSend,
        onRetry = onRetry,
    )
}

private fun hasWorkingMicPermission(context: android.content.Context): Boolean {
    val systemPermission = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
    val workingFlag = context.getSharedPreferences(PREFS_NAME, ComponentActivity.MODE_PRIVATE)
        .getBoolean(PREF_KEY_AUDIO_PERMISSION, false)
    return systemPermission && workingFlag
}
