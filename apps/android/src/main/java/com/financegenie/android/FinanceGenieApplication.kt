package com.financegenie.android

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import com.financegenie.android.data.LedgerRepository
import com.financegenie.android.privacy.PrivacyPreferences
import com.financegenie.android.recovery.LocalProfileStore
import java.time.Clock

interface AppContainer {
    val repository: LedgerRepository
    val privacy: PrivacyPreferences
    val clock: Clock
    val profiles: LocalProfileStore? get() = null
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
    override val profiles by lazy { LocalProfileStore(context, clock) }
    override val repository: LedgerRepository get() = profiles.session.value.repository
    override val privacy = PrivacyPreferences(context.getSharedPreferences("privacy", Context.MODE_PRIVATE))
}
