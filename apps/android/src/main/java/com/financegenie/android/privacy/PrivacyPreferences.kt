package com.financegenie.android.privacy

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PrivacySettings(
    val appLockEnabled: Boolean = false,
    val protectScreen: Boolean = true,
    val timeoutSeconds: Int = 30,
)

class PrivacyPreferences(private val preferences: SharedPreferences) {
    private val mutable = MutableStateFlow(read())
    val state: StateFlow<PrivacySettings> = mutable.asStateFlow()

    fun setAppLock(enabled: Boolean): Boolean = save(
        preferences.edit().putBoolean("app_lock", enabled),
        state.value.copy(appLockEnabled = enabled),
    )

    fun setScreenProtection(enabled: Boolean): Boolean = save(
        preferences.edit().putBoolean("protect_screen", enabled),
        state.value.copy(protectScreen = enabled),
    )

    fun setTimeout(seconds: Int): Boolean {
        require(seconds in setOf(0, 30, 60, 300))
        return save(preferences.edit().putInt("timeout_seconds", seconds), state.value.copy(timeoutSeconds = seconds))
    }

    // These tiny, infrequent security changes must reach disk before the UI reports success.
    private fun save(editor: SharedPreferences.Editor, settings: PrivacySettings): Boolean {
        if (!editor.commit()) return false
        mutable.value = settings
        return true
    }

    private fun read(): PrivacySettings = PrivacySettings(
        appLockEnabled = preferences.getBoolean("app_lock", false),
        protectScreen = preferences.getBoolean("protect_screen", true),
        timeoutSeconds = preferences.getInt("timeout_seconds", 30).takeIf { it in setOf(0, 30, 60, 300) } ?: 30,
    )
}
