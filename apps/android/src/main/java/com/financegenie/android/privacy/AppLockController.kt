package com.financegenie.android.privacy

/** In-memory session state; no credential or unlock grant survives process death. */
class AppLockController(enabled: Boolean, timeoutMillis: Long = 30_000) {
    private var enabled = enabled
    private var timeoutMillis = timeoutMillis
    private var authenticated = !enabled
    private var backgroundAt: Long? = null

    val hidesContent: Boolean get() = enabled && (!authenticated || backgroundAt != null)

    fun configure(enabled: Boolean, timeoutMillis: Long) {
        require(timeoutMillis >= 0)
        if (this.enabled != enabled) authenticated = !enabled
        this.enabled = enabled
        this.timeoutMillis = timeoutMillis
    }

    fun background(nowMillis: Long) {
        if (backgroundAt == null) backgroundAt = nowMillis
    }

    fun foreground(nowMillis: Long) {
        backgroundAt?.let { start ->
            if (nowMillis < start || nowMillis - start >= timeoutMillis) authenticated = !enabled
        }
        backgroundAt = null
    }

    fun authenticationSucceeded() {
        authenticated = true
        backgroundAt = null
    }

    fun configurationChanged() {
        backgroundAt = null
    }

    fun lockNow() {
        if (enabled) authenticated = false
    }
}
