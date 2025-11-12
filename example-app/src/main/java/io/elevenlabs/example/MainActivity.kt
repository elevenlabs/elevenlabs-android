package io.elevenlabs.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import androidx.core.content.ContextCompat
import io.elevenlabs.example.models.UiState
import io.elevenlabs.example.viewmodels.ConversationViewModel
import io.elevenlabs.models.ConversationMode
import io.elevenlabs.models.ConversationStatus
import androidx.appcompat.app.AlertDialog
import android.widget.Toast

/**
 * Simple example app demonstrating ElevenLabs Conversational AI SDK
 *
 * Shows basic connection/disconnection functionality with status display
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: ConversationViewModel by viewModels()

    // UI Components
    private lateinit var statusText: TextView
    private lateinit var modeLabel: TextView
    private lateinit var modeDot: android.view.View
    private lateinit var modeContainer: android.widget.LinearLayout
    private lateinit var feedbackContainer: android.widget.LinearLayout
    private lateinit var thumbsUpButton: Button
    private lateinit var thumbsDownButton: Button
    private lateinit var contextualEditText: EditText
    private lateinit var contextualSendButton: Button
    private lateinit var userSendButton: Button
    private lateinit var contextualButtonsRow: android.widget.LinearLayout
    private lateinit var muteButton: Button
    private lateinit var muteContainer: android.widget.LinearLayout
    private lateinit var volumeSeekBar: android.widget.SeekBar
    private lateinit var volumeContainer: android.widget.LinearLayout

    // No broadcast receiver; we use ViewModel.mode
    private lateinit var connectButton: Button

    // Permission request launcher for RECORD_AUDIO only
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val prefs = getSharedPreferences("elevenlabs_permissions", MODE_PRIVATE)
            prefs.edit().putBoolean("audio_permission_working", true).apply()
            Log.d("MainActivity", "Permission granted and marked as working")

            // Permission granted, start conversation
            viewModel.startConversation(this@MainActivity)
        } else {
            showError("Microphone permission is required for voice conversations")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupUI()
        setupObservers()
    }

    private fun setupUI() {
        statusText = findViewById(R.id.statusText)
        modeLabel = findViewById(R.id.modeLabel)
        modeDot = findViewById(R.id.modeDot)
        modeContainer = findViewById(R.id.modeContainer)
        feedbackContainer = findViewById(R.id.feedbackContainer)
        thumbsUpButton = findViewById(R.id.thumbsUpButton)
        thumbsDownButton = findViewById(R.id.thumbsDownButton)
        connectButton = findViewById(R.id.connectButton)
        contextualEditText = findViewById(R.id.contextualEditText)
        contextualButtonsRow = findViewById(R.id.contextualButtonsRow)
        contextualSendButton = findViewById(R.id.contextualSendButton)
        userSendButton = findViewById(R.id.userSendButton)
        muteButton = findViewById(R.id.muteButton)
        muteContainer = findViewById(R.id.muteContainer)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        volumeContainer = findViewById(R.id.volumeContainer)

        connectButton.isEnabled = true
        connectButton.setOnClickListener {
            Log.d("MainActivity", "Connect button clicked")
            val status = viewModel.sessionStatus.value
            if (status == io.elevenlabs.models.ConversationStatus.CONNECTED) {
                endConversation()
            } else {
                startConversation()
            }
        }

        // Feedback buttons
        thumbsUpButton.setOnClickListener {
            Log.d("MainActivity", "Thumbs up clicked")
            // Disable both buttons immediately to prevent multiple clicks
            thumbsUpButton.isEnabled = false
            thumbsDownButton.isEnabled = false
            viewModel.sendFeedback(true)
        }

        thumbsDownButton.setOnClickListener {
            Log.d("MainActivity", "Thumbs down clicked")
            // Disable both buttons immediately to prevent multiple clicks
            thumbsUpButton.isEnabled = false
            thumbsDownButton.isEnabled = false
            viewModel.sendFeedback(false)
        }

        // Contextual update send button
        contextualSendButton.setOnClickListener {
            val text = contextualEditText.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                viewModel.sendContextualUpdate(text)
                contextualEditText.text?.clear()
            }
        }

        // User message send button
        userSendButton.setOnClickListener {
            val text = contextualEditText.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                viewModel.sendUserMessage(text)
                contextualEditText.text?.clear()
            }
        }

        // Mute/unmute toggle
        muteButton.setOnClickListener {
            viewModel.toggleMute()
        }

        // Reflect mute state label based on current value when available
        viewModel.isMuted.observe(this) { muted ->
            muteButton.text = if (muted == true) "Unmute" else "Mute"
        }

        // Volume control
        volumeSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val volume = progress / 100.0f
                    viewModel.setVolume(volume)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Send user activity when typing
        contextualEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.sendUserActivity()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupObservers() {
        viewModel.uiState.observe(this) { state ->
            updateUIForState(state)
        }

        viewModel.sessionStatus.observe(this) { status ->
            updateStatusDisplay(status)
        }

        // Mode changes: update indicator
        viewModel.mode.observe(this) { mode ->
            mode?.let { updateModeUI(it) }
        }

        viewModel.errorMessage.observe(this) { error ->
            error?.let {
                // If we get a permission-related error, reset the working flag
                if (it.contains("permission", ignoreCase = true) || it.contains("insufficient", ignoreCase = true)) {
                    Log.d("MainActivity", "Permission error detected, resetting working flag")
                    val prefs = getSharedPreferences("elevenlabs_permissions", MODE_PRIVATE)
                    prefs.edit().putBoolean("audio_permission_working", false).apply()
                }
                showError(it)
                viewModel.clearError()
            }
        }

        // Feedback state changes: enable/disable feedback buttons
        viewModel.canSendFeedback.observe(this) { canSend ->
            updateFeedbackUI(canSend)
        }
    }

    private fun updateUIForState(state: UiState) {
        when (state) {
            is UiState.Idle -> {
                connectButton.isEnabled = true
                connectButton.text = "Connect"
            }
            is UiState.Connecting -> {
                connectButton.isEnabled = false
                connectButton.text = "Connecting..."
            }
            is UiState.Connected -> {
                connectButton.isEnabled = true
                connectButton.text = "Disconnect"
            }
            is UiState.Disconnecting -> {
                connectButton.isEnabled = false
                connectButton.text = "Disconnecting..."
            }
            is UiState.Error -> {
                connectButton.isEnabled = true
                connectButton.text = "Connect"
            }
        }
    }

    private fun updateStatusDisplay(status: ConversationStatus) {
        val statusColor = when (status) {
            ConversationStatus.CONNECTED -> ContextCompat.getColor(this, R.color.status_connected)
            ConversationStatus.CONNECTING -> ContextCompat.getColor(this, R.color.status_connecting)
            ConversationStatus.DISCONNECTED -> ContextCompat.getColor(this, R.color.status_disconnected)
            ConversationStatus.ERROR -> ContextCompat.getColor(this, R.color.status_error)
            ConversationStatus.DISCONNECTING -> ContextCompat.getColor(this, R.color.status_disconnected)
        }

        val statusMessage = when (status) {
            ConversationStatus.CONNECTED -> "Status: Connected to ElevenLabs"
            ConversationStatus.CONNECTING -> "Status: Connecting..."
            ConversationStatus.DISCONNECTED -> "Status: Disconnected"
            ConversationStatus.ERROR -> "Status: Connection Error"
            ConversationStatus.DISCONNECTING -> "Status: Disconnecting..."
        }

        statusText.text = statusMessage
        statusText.setTextColor(statusColor)

        // Show/hide mode container with connection
        modeContainer.visibility = if (status == ConversationStatus.CONNECTED) android.view.View.VISIBLE else android.view.View.GONE

        // Show/hide feedback container with connection
        val isConnected = status == ConversationStatus.CONNECTED
        feedbackContainer.visibility = if (isConnected) android.view.View.VISIBLE else android.view.View.GONE

        // Show/hide contextual UI and enable send only when connected
        contextualEditText.visibility = if (isConnected) android.view.View.VISIBLE else android.view.View.GONE
        contextualButtonsRow.visibility = if (isConnected) android.view.View.VISIBLE else android.view.View.GONE
        contextualSendButton.isEnabled = isConnected
        userSendButton.isEnabled = isConnected
        muteContainer.visibility = if (isConnected) android.view.View.VISIBLE else android.view.View.GONE
        muteButton.isEnabled = isConnected
        volumeContainer.visibility = if (isConnected) android.view.View.VISIBLE else android.view.View.GONE
        volumeSeekBar.isEnabled = isConnected
    }

    private fun updateModeUI(mode: ConversationMode) {
        modeLabel.text = if (mode == ConversationMode.SPEAKING) "Speaking" else "Listening"
        val color = if (mode == ConversationMode.SPEAKING) R.color.status_connected else R.color.status_disconnected
        modeDot.background = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
            paint.color = ContextCompat.getColor(this@MainActivity, color)
        }
    }

    private fun updateFeedbackUI(canSend: Boolean) {
        thumbsUpButton.isEnabled = canSend
        thumbsDownButton.isEnabled = canSend
        Log.d("MainActivity", "Feedback buttons enabled: $canSend")
    }

    private fun startConversation() {
        // Check permissions before starting conversation
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
            return
        }

        // Use demo configuration for simplicity
        viewModel.startConversation(this@MainActivity)
    }

    private fun endConversation() {
        viewModel.endConversation()
    }

            private fun hasRequiredPermissions(): Boolean {
        // WORKAROUND: LiveKit 2.13.0+ bug - always request permission to ensure it works properly
        // Even if permission appears granted, LiveKit may not recognize it due to the bug
        val systemPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.d("MainActivity", "System permission check - hasPermission: $systemPermission, context: $this")

        // For LiveKit 2.13.0+ bug workaround, we need to check if this is a fresh permission grant
        val prefs = getSharedPreferences("elevenlabs_permissions", MODE_PRIVATE)
        val hasWorkingPermission = prefs.getBoolean("audio_permission_working", false)

        Log.d("MainActivity", "Working permission flag: $hasWorkingPermission")

        // If system says we have permission but our flag says it's not working, force re-request
        return systemPermission && hasWorkingPermission
    }

    private fun requestRequiredPermissions() {
        // WORKAROUND: LiveKit 2.13.0+ bug - always re-request permission to ensure it works
        Log.d("MainActivity", "Force requesting permission due to LiveKit 2.13.0+ bug")
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }



    override fun onDestroy() {
        super.onDestroy()
        // No broadcast receiver to unregister
        viewModel.endConversation()
    }
}