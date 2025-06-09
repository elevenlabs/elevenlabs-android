package com.elevenlabs.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.elevenlabs.sample.databinding.ActivityMainBinding
import com.elevenlabs.sdk.ElevenLabsSDK
import com.elevenlabs.sdk.utils.PermissionUtils
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var conversation: ElevenLabsSDK.Conversation? = null
    private var isConversationActive = false

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 1
        private const val TAG = "ElevenLabsSample"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.startButton.setOnClickListener {
            if (isConversationActive) {
                stopConversation()
            } else {
                startConversation()
            }
        }

        binding.sendMessageButton.setOnClickListener {
            val message = binding.messageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                conversation?.sendUserMessage(message)
                binding.messageInput.text.clear()
                addMessage("You: $message")
            }
        }

        // Initial UI state
        updateUI(false)
    }

    private fun checkPermissions() {
        if (!PermissionUtils.hasAllRequiredPermissions(this)) {
            val missingPermissions = PermissionUtils.getMissingPermissions(this)
            ActivityCompat.requestPermissions(
                this,
                missingPermissions.toTypedArray(),
                PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (!allGranted) {
                Toast.makeText(
                    this,
                    "All permissions are required for the SDK to work properly",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startConversation() {
        if (!PermissionUtils.hasAllRequiredPermissions(this)) {
            Toast.makeText(this, "Please grant all required permissions", Toast.LENGTH_SHORT).show()
            return
        }

        val agentId = binding.agentIdInput.text.toString().trim()
        if (agentId.isEmpty()) {
            Toast.makeText(this, "Please enter an Agent ID", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                updateUI(true)
                addMessage("Connecting...")

                val config = ElevenLabsSDK.SessionConfig(agentId = agentId)
                val callbacks = createCallbacks()

                conversation = ElevenLabsSDK.Conversation.startSession(
                    context = this@MainActivity,
                    config = config,
                    callbacks = callbacks
                )

                isConversationActive = true
                Log.d(TAG, "Conversation started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start conversation", e)
                addMessage("Error: ${e.message}")
                updateUI(false)
                isConversationActive = false
            }
        }
    }

    private fun stopConversation() {
        try {
            conversation?.endSession()
            conversation = null
            isConversationActive = false
            updateUI(false)
            addMessage("Conversation ended")
            Log.d(TAG, "Conversation stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping conversation", e)
        }
    }

    private fun createCallbacks(): ElevenLabsSDK.Callbacks {
        return ElevenLabsSDK.Callbacks(
            onConnect = { conversationId ->
                runOnUiThread {
                    addMessage("Connected! Conversation ID: $conversationId")
                    addMessage("You can now speak or type messages")
                }
                Log.d(TAG, "Connected: $conversationId")
            },
            onDisconnect = {
                runOnUiThread {
                    addMessage("Disconnected")
                    updateUI(false)
                    isConversationActive = false
                }
                Log.d(TAG, "Disconnected")
            },
            onMessage = { message, role ->
                runOnUiThread {
                    val roleText = when (role) {
                        ElevenLabsSDK.Role.USER -> "You"
                        ElevenLabsSDK.Role.AI -> "AI"
                    }
                    addMessage("$roleText: $message")
                }
                Log.d(TAG, "Message from ${role.name}: $message")
            },
            onError = { error, details ->
                runOnUiThread {
                    addMessage("Error: $error")
                    if (!isConversationActive) {
                        updateUI(false)
                    }
                }
                Log.e(TAG, "Error: $error, Details: $details")
            },
            onStatusChange = { status ->
                runOnUiThread {
                    binding.statusText.text = "Status: ${status.name}"
                }
                Log.d(TAG, "Status: ${status.name}")
            },
            onModeChange = { mode ->
                runOnUiThread {
                    binding.modeText.text = "Mode: ${mode.name}"
                    val isListening = mode == ElevenLabsSDK.Mode.LISTENING
                    binding.micIndicator.setBackgroundColor(
                        ContextCompat.getColor(
                            this@MainActivity,
                            if (isListening) android.R.color.holo_red_light 
                            else android.R.color.darker_gray
                        )
                    )
                }
                Log.d(TAG, "Mode: ${mode.name}")
            },
            onVolumeUpdate = { volume ->
                runOnUiThread {
                    // Update volume indicator
                    val volumePercent = (volume * 100).toInt()
                    binding.volumeText.text = "Volume: $volumePercent%"
                }
            },
            onMessageCorrection = { original, corrected, role ->
                runOnUiThread {
                    addMessage("Message corrected from '$original' to '$corrected'")
                }
                Log.d(TAG, "Message correction: $original -> $corrected")
            }
        )
    }

    private fun updateUI(isActive: Boolean) {
        binding.startButton.text = if (isActive) "Stop Conversation" else "Start Conversation"
        binding.startButton.isEnabled = true
        binding.sendMessageButton.isEnabled = isActive
        binding.messageInput.isEnabled = isActive
        binding.agentIdInput.isEnabled = !isActive
        
        if (!isActive) {
            binding.statusText.text = "Status: DISCONNECTED"
            binding.modeText.text = "Mode: -"
            binding.volumeText.text = "Volume: 0%"
            binding.micIndicator.setBackgroundColor(
                ContextCompat.getColor(this, android.R.color.darker_gray)
            )
        }
    }

    private fun addMessage(message: String) {
        val currentText = binding.conversationText.text.toString()
        val newText = if (currentText.isEmpty()) {
            message
        } else {
            "$currentText\n$message"
        }
        binding.conversationText.text = newText
        
        // Scroll to bottom
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.widget.ScrollView.FOCUS_DOWN)
        }
    }

    override fun onPause() {
        super.onPause()
        conversation?.stopRecording()
    }

    override fun onResume() {
        super.onResume()
        if (isConversationActive) {
            conversation?.startRecording()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.endSession()
    }
}