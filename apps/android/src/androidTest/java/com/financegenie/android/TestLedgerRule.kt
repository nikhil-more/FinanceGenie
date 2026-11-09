package com.financegenie.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.RoomLedgerRepository
import com.financegenie.android.privacy.PrivacyPreferences
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Runs before ActivityScenario: UI tests can never open or clear the user's ledger. */
class TestLedgerRule(
    private val lockEnabled: Boolean = false,
    private val seed: suspend (LedgerRepository) -> Unit = {},
    private val decorate: (LedgerRepository) -> LedgerRepository = { it },
    private val clock: Clock = Clock.fixed(Instant.parse("2026-11-30T12:00:00Z"), ZoneOffset.UTC),
) : ExternalResource() {
    private lateinit var database: FinanceGenieDatabase
    lateinit var repository: LedgerRepository
        private set
    lateinit var privacy: PrivacyPreferences
        private set
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FinanceGenieApplication

    override fun before() {
        database = FinanceGenieDatabase.inMemory(app, clock)
        repository = decorate(RoomLedgerRepository(database, clock))
        val preferences = app.getSharedPreferences("ui-tests-only", Context.MODE_PRIVATE)
        check(preferences.edit().clear().commit())
        privacy = PrivacyPreferences(preferences)
        check(privacy.setAppLock(lockEnabled))
        runBlocking { repository.read(); seed(repository) }
        app.replaceTestContainer(object : AppContainer {
            override val repository = this@TestLedgerRule.repository
            override val privacy = this@TestLedgerRule.privacy
            override val clock = this@TestLedgerRule.clock
        })
    }

    override fun after() {
        app.replaceTestContainer(null)
        database.close()
        // Only this dedicated test preferences file is ever removed.
        check(app.getSharedPreferences("ui-tests-only", Context.MODE_PRIVATE).edit().clear().commit())
    }
}
