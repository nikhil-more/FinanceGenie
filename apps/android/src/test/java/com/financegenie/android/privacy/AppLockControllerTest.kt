package com.financegenie.android.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockControllerTest {
    @Test fun coldStartRequiresAuthenticationWhenEnabled() {
        assertTrue(AppLockController(true).hidesContent)
        assertFalse(AppLockController(false).hidesContent)
    }
    @Test fun authenticatedContentIsHiddenWhileBackgroundedAndReturnsBeforeTimeout() {
        val lock = AppLockController(true)
        lock.authenticationSucceeded()
        assertFalse(lock.hidesContent)
        lock.background(100)
        assertTrue(lock.hidesContent)
        lock.foreground(30_099)
        assertFalse(lock.hidesContent)
    }
    @Test fun timeoutBoundaryRequiresFreshAuthentication() {
        val lock = AppLockController(true)
        lock.authenticationSucceeded()
        lock.background(100)
        lock.foreground(30_100)
        assertTrue(lock.hidesContent)
        lock.authenticationSucceeded()
        assertFalse(lock.hidesContent)
    }
    @Test fun repeatedBackgroundCallbacksCannotExtendTheSession() {
        val lock = AppLockController(true)
        lock.authenticationSucceeded()
        lock.background(100)
        lock.background(30_000)
        lock.foreground(30_100)
        assertTrue(lock.hidesContent)
    }
    @Test fun immediateTimeoutLocksOnEveryReturn() {
        val lock = AppLockController(true, 0)
        lock.authenticationSucceeded()
        lock.background(100)
        lock.foreground(100)
        assertTrue(lock.hidesContent)
    }
    @Test fun configurationChangeRetainsSessionButNeverUnlocksAColdSession() {
        val lock = AppLockController(true, 0)
        lock.configurationChanged()
        assertTrue(lock.hidesContent)
        lock.authenticationSucceeded()
        lock.background(100)
        lock.configurationChanged()
        lock.foreground(200)
        assertFalse(lock.hidesContent)
    }
    @Test fun clockRegressionFailsClosed() {
        val lock = AppLockController(true)
        lock.authenticationSucceeded()
        lock.background(100)
        lock.foreground(99)
        assertTrue(lock.hidesContent)
    }
    @Test fun cancellationWithoutSuccessCannotUnlock() {
        val lock = AppLockController(true)
        lock.background(100)
        lock.foreground(200)
        assertTrue(lock.hidesContent)
        lock.lockNow()
        assertTrue(lock.hidesContent)
    }
    @Test fun disablingThenEnablingRequiresANewGrant() {
        val lock = AppLockController(true)
        lock.authenticationSucceeded()
        lock.configure(false, 30_000)
        lock.background(100)
        lock.foreground(1_000_000)
        assertFalse(lock.hidesContent)
        lock.configure(true, 30_000)
        assertTrue(lock.hidesContent)
    }
    @Test fun changingTimeoutDoesNotUnlock() {
        val lock = AppLockController(true)
        lock.configure(true, 300_000)
        assertTrue(lock.hidesContent)
    }
}
