package com.financegenie.android

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.financegenie.android.privacy.PrivacyViewModel
import com.financegenie.android.ui.FinanceGenieApp
import com.financegenie.android.ui.LedgerViewModel
import com.financegenie.android.ui.LockedScreen
import com.financegenie.android.ui.theme.FinanceGenieTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as FinanceGenieApplication).container
    private val privacyModel by lazy { retained { PrivacyViewModel(container.privacy) } }
    private val ledgerModel by lazy { retained { LedgerViewModel(container.repository, container.clock) } }
    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        privacyModel.authenticationFinished(it.resultCode == RESULT_OK)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateScreenPolicy(container.privacy.state.value.protectScreen || container.privacy.state.value.appLockEnabled)
        enableEdgeToEdge()
        setContent {
            val privacy by privacyModel.state.collectAsStateWithLifecycle()
            val savedContent = rememberSaveableStateHolder()
            val shouldProtect = privacy.settings.protectScreen || privacy.hidesContent
            SideEffect { updateScreenPolicy(shouldProtect) }
            FinanceGenieTheme {
                if (privacy.hidesContent) LockedScreen(privacy) { authenticate(false) }
                else savedContent.SaveableStateProvider("ledger") {
                    FinanceGenieApp(ledgerModel, privacy, privacyModel, ::authenticate)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        privacyModel.foreground(SystemClock.elapsedRealtime())
    }

    override fun onPause() {
        if (container.privacy.state.value.appLockEnabled) updateScreenPolicy(true)
        privacyModel.background(SystemClock.elapsedRealtime())
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) privacyModel.configurationChanged()
    }

    // The platform owns and verifies the device credential; the app never receives it.
    @Suppress("DEPRECATION")
    private fun authenticate(enableLock: Boolean) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val intent = if (keyguard.isDeviceSecure) keyguard.createConfirmDeviceCredentialIntent(
            getString(R.string.unlock_title), getString(R.string.unlock_description),
        ) else null
        if (intent == null) {
            privacyModel.authenticationUnavailable()
            return
        }
        if (!privacyModel.beginAuthentication(enableLock)) return
        try {
            credential.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            privacyModel.authenticationUnavailable()
        }
    }

    private fun updateScreenPolicy(secure: Boolean) {
        if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(!secure && !container.privacy.state.value.appLockEnabled)
        }
    }

    private inline fun <reified T : ViewModel> retained(crossinline create: () -> T): T =
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
        })[T::class.java]
}
