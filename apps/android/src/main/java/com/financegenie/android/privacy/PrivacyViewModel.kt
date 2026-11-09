package com.financegenie.android.privacy

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.financegenie.android.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PrivacyUiState(
    val settings: PrivacySettings,
    val hidesContent: Boolean,
    val authenticating: Boolean = false,
    @StringRes val error: Int? = null,
)

class PrivacyViewModel(private val preferences: PrivacyPreferences) : ViewModel() {
    private val controller = AppLockController(preferences.state.value.appLockEnabled)
    private var enablingLock = false
    private val mutable = MutableStateFlow(PrivacyUiState(preferences.state.value, controller.hidesContent))
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch { preferences.state.collect { refresh() } }
    }

    fun background(nowMillis: Long) {
        controller.background(nowMillis)
        refresh()
    }

    fun foreground(nowMillis: Long) {
        controller.foreground(nowMillis)
        refresh()
    }

    fun configurationChanged() {
        controller.configurationChanged()
        refresh()
    }

    fun beginAuthentication(enableLock: Boolean): Boolean {
        if (state.value.authenticating) return false
        enablingLock = enableLock
        mutable.value = state.value.copy(authenticating = true, error = null)
        return true
    }

    fun authenticationFinished(success: Boolean) {
        val saved = !success || !enablingLock || preferences.setAppLock(true)
        if (success) {
            controller.configure(preferences.state.value.appLockEnabled, preferences.state.value.timeoutSeconds * 1_000L)
            controller.authenticationSucceeded()
        }
        enablingLock = false
        refresh(authenticating = false, error = when {
            !saved -> R.string.error_privacy_save
            !success -> R.string.error_auth_cancelled
            else -> null
        })
    }

    fun authenticationUnavailable() {
        enablingLock = false
        refresh(authenticating = false, error = R.string.error_device_lock_needed)
    }

    fun disableLock() {
        if (state.value.hidesContent) return
        refresh(error = if (preferences.setAppLock(false)) null else R.string.error_privacy_save)
    }

    fun lockNow() {
        controller.lockNow()
        refresh()
    }

    fun protectScreen(enabled: Boolean) {
        refresh(error = if (preferences.setScreenProtection(enabled)) null else R.string.error_privacy_save)
    }

    fun timeout(seconds: Int) {
        refresh(error = if (preferences.setTimeout(seconds)) null else R.string.error_privacy_save)
    }

    private fun refresh(authenticating: Boolean = state.value.authenticating, @StringRes error: Int? = state.value.error) {
        val settings = preferences.state.value
        controller.configure(settings.appLockEnabled, settings.timeoutSeconds * 1_000L)
        mutable.value = PrivacyUiState(settings, controller.hidesContent, authenticating, error)
    }
}
