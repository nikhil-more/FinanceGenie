package com.financegenie.android

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.financegenie.android.privacy.*
import com.financegenie.android.recovery.*
import com.financegenie.android.ui.*
import com.financegenie.android.ui.theme.FinanceGenieTheme
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import kotlinx.coroutines.runBlocking

class RecoveryUiRule : ExternalResource() {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FinanceGenieApplication
    private val namespace = "recovery-ui-tests-only"
    lateinit var profiles: LocalProfileStore
    lateinit var privacy: PrivacyPreferences
    override fun before() {
        profiles = LocalProfileStore(app, uiClock, namespace)
        val preferences = app.getSharedPreferences(namespace, Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        privacy = PrivacyPreferences(preferences)
        runBlocking { seedRecovery(profiles.session.value.repository) }
        app.replaceTestContainer(object : AppContainer {
            override val profiles = this@RecoveryUiRule.profiles
            override val repository get() = profiles.session.value.repository
            override val privacy = this@RecoveryUiRule.privacy
            override val clock = uiClock
        })
    }
    override fun after() {
        app.replaceTestContainer(null); profiles.close()
        app.databaseList().filter { it.startsWith(namespace) }.forEach { app.deleteDatabase(it) }
        AtomicFile(File(app.filesDir, "$namespace-active-profile")).delete()
        check(app.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear().commit())
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, MainActivity>.recoveryModel() = ViewModelProvider(activity)[LedgerViewModel::class.java]
private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.openRecovery() {
    waitTag("tab_settings"); onNodeWithTag("tab_settings").performClick()
    waitUntil(10_000) { onAllNodes(hasTestTag("manage_recovery")).fetchSemanticsNodes().isNotEmpty() || onAllNodes(hasTestTag("recovery_screen")).fetchSemanticsNodes().isNotEmpty() }
    if (onAllNodes(hasTestTag("recovery_screen")).fetchSemanticsNodes().isEmpty()) onNodeWithTag("manage_recovery").performScrollTo().performClick()
    waitTag("recovery_screen")
}

class RecoveryUiTest {
    @get:Rule(order = 0) val data = RecoveryUiRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private fun source() = runBlocking { data.profiles.backup() }
    private fun bytes() = ByteArrayOutputStream().also { BackupCodec.write(source(), it, uiClock) }.toByteArray()

    @Test fun previewRotationCancelAndRealProfileSwitchDiscardTheOldViewModel() {
        val bytes = bytes(); val original = source().ledger
        compose.openRecovery()
        val oldModel = compose.recoveryModel()
        compose.runOnUiThread { oldModel.recovery!!.read { bytes.inputStream() } }
        compose.waitTag("recovery_validated")
        compose.activityRule.scenario.recreate()
        compose.waitTag("recovery_validated")
        compose.onNodeWithTag("recovery_cancel").performScrollTo().performClick()
        assertEquals(original, runBlocking { data.profiles.session.value.repository.read() })
        compose.runOnUiThread { compose.recoveryModel().recovery!!.read { bytes.inputStream() } }
        compose.waitTag("recovery_validated")
        compose.onNodeWithTag("recovery_restore").performScrollTo().performClick()
        compose.waitUntil(20_000) { runBlocking { data.profiles.session.value.repository.read().settings.localId != original.settings.localId } }
        compose.waitTag("add_transaction")
        assertNotSame(oldModel, compose.recoveryModel())
        assertEquals(1_000, runBlocking { data.profiles.session.value.repository.read().transactions.size })
        assertTrue(compose.recoveryModel().recovery!!.state.value.preview == null)
    }

    @Test fun disclosuresCancelAndStaleDeleteNeverDeleteTheLedger() {
        compose.openRecovery()
        val original = source().ledger
        compose.onNodeWithTag("recovery_backup").performClick()
        compose.onNodeWithText("Save a readable file?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnUiThread { compose.recoveryModel().recovery!!.read { "{invalid".byteInputStream() } }
        compose.waitTag("recovery_message")
        assertEquals(original, runBlocking { data.profiles.session.value.repository.read() })
        compose.onNodeWithTag("recovery_delete").performScrollTo().performClick()
        compose.waitTag("recovery_confirm")
        runBlocking { data.profiles.session.value.repository.saveCategory(com.financegenie.android.domain.LedgerCategory("later", "Later")) }
        compose.onNodeWithTag("recovery_confirm").performClick()
        compose.waitUntil(10_000) { compose.recoveryModel().recovery!!.state.value.message == R.string.recovery_stale }
        assertEquals(1_000, runBlocking { data.profiles.session.value.repository.read().transactions.size })
    }
}

class RecoveryPickerUiTest {
    @get:Rule(order = 0) val data = RecoveryUiRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    @Test fun saveJsonAndCsvThenPreviewOnlyTheCreatedSyntheticBackup() {
        val baseName = InstrumentationRegistry.getArguments().getString("recoveryPickerBaseName")
        Assume.assumeTrue("Run the recovery device driver", baseName != null)
        require(baseName!!.matches(Regex("FinanceGenie-step8-synthetic-[a-f0-9]+")))
        compose.openRecovery()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun click(selector: BySelector, timeout: Long = 8_000) {
            repeat(5) {
                try { val node = device.wait(Until.findObject(selector), timeout); assertNotNull(node); node.click(); return }
                catch (_: StaleObjectException) { device.waitForIdle(5_000) }
            }
            fail("Synthetic picker control unavailable")
        }
        fun downloads() {
            if (device.wait(Until.hasObject(By.desc("Show roots")), 3_000)) click(By.desc("Show roots"))
            click(By.text("Downloads"))
        }
        fun save(tag: String, extension: String) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            compose.onNodeWithTag("recovery_confirm").performClick()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10_000))
            downloads()
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), 5_000).text = "$baseName.$extension"
            click(By.text(java.util.regex.Pattern.compile("save", java.util.regex.Pattern.CASE_INSENSITIVE)))
            compose.waitUntil(15_000) { compose.recoveryModel().recovery!!.state.value.message == R.string.recovery_exported }
        }
        save("recovery_backup", "json")
        save("recovery_csv", "csv")
        compose.onNodeWithTag("recovery_choose_restore").performScrollTo().performClick()
        downloads(); click(By.text("$baseName.json"), 15_000)
        compose.waitTag("recovery_validated")
        val restored = compose.recoveryModel().recovery!!.state.value.preview!!
        val source = runBlocking { data.profiles.backup() }
        assertEquals(source.copy(createdAtMillis = restored.createdAtMillis).ordered(), restored.ordered())
        compose.onNodeWithTag("recovery_cancel").performScrollTo().performClick()
    }
}

class RecoveryFontUiTest {
    @get:Rule(order = 0) val data = RecoveryUiRule()
    @get:Rule(order = 1) val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun clear() { compose.runOnUiThread { store.clear() } }
    @Test fun twoHundredPercentDarkRecoveryAndScrollableDisclosureAreUsable() {
        lateinit var ledger: LedgerViewModel
        lateinit var privacy: PrivacyViewModel
        compose.runOnUiThread {
            val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                    LedgerViewModel::class.java -> LedgerViewModel(data.profiles.session.value.repository, uiClock, data.profiles)
                    else -> PrivacyViewModel(data.privacy)
                } as T
            })
            ledger = provider[LedgerViewModel::class.java]; privacy = provider[PrivacyViewModel::class.java]
        }
        compose.setContent {
            val state by privacy.state.collectAsStateWithLifecycle()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                FinanceGenieTheme(darkTheme = true) { FinanceGenieApp(ledger, state, privacy, {}) }
            }
        }
        compose.openRecovery()
        compose.onNodeWithTag("recovery_backup").performScrollTo().performClick()
        compose.onNodeWithTag("recovery_confirm").assertIsDisplayed()
        compose.onNode(isDialog()).saveSyntheticCapture("step8-font200-dark-recovery.png")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("recovery_delete").performScrollTo().assertIsDisplayed()
    }
}
