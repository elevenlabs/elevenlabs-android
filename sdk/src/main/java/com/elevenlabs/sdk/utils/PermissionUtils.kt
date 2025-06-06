package com.elevenlabs.sdk.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Utility functions for handling permissions
 */
object PermissionUtils {
    
    /**
     * Check if RECORD_AUDIO permission is granted
     */
    fun hasAudioRecordPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if INTERNET permission is granted
     */
    fun hasInternetPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.INTERNET
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if MODIFY_AUDIO_SETTINGS permission is granted
     */
    fun hasAudioSettingsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check all required permissions for the SDK
     */
    fun hasAllRequiredPermissions(context: Context): Boolean {
        return hasAudioRecordPermission(context) &&
                hasInternetPermission(context) &&
                hasAudioSettingsPermission(context)
    }
    
    /**
     * Get list of missing permissions
     */
    fun getMissingPermissions(context: Context): List<String> {
        val missingPermissions = mutableListOf<String>()
        
        if (!hasAudioRecordPermission(context)) {
            missingPermissions.add(Manifest.permission.RECORD_AUDIO)
        }
        
        if (!hasInternetPermission(context)) {
            missingPermissions.add(Manifest.permission.INTERNET)
        }
        
        if (!hasAudioSettingsPermission(context)) {
            missingPermissions.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        }
        
        return missingPermissions
    }
    
    /**
     * Get all required permissions for the SDK
     */
    fun getRequiredPermissions(): Array<String> {
        return arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
    }
} 