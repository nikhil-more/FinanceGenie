package com.financegenie.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.data.local.FinanceGenieDatabase
import com.financegenie.android.data.local.RoomLedgerRepository
import com.financegenie.android.privacy.PrivacyPreferences
import java.time.Clock

interface AppContainer {
    val repository: LedgerRepository
    val privacy: PrivacyPreferences
    val clock: Clock
}

open class FinanceGenieApplication : Application() {
    private val productionContainer: AppContainer by lazy { LocalAppContainer(this) }
    private var testContainer: AppContainer? = null
    open val container: AppContainer get() = testContainer ?: productionContainer

    internal fun replaceTestContainer(value: AppContainer?) {
        check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Test replacement requires a debug build" }
        testContainer = value
    }
}

private class LocalAppContainer(context: Context) : AppContainer {
    override val clock: Clock = Clock.systemUTC()
    private val database by lazy { FinanceGenieDatabase.open(context, clock = clock) }
    override val repository: LedgerRepository by lazy { RoomLedgerRepository(database, clock) }
    override val privacy = PrivacyPreferences(context.getSharedPreferences("privacy", Context.MODE_PRIVATE))
}
