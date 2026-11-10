package com.financegenie.benchmarks

import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvFixtureSetup {
    @Test fun prepareOnlyTheSyntheticBenchmarkDatabase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val target = "com.financegenie.android.benchmark"
        device.executeShellCommand("am force-stop $target")
        instrumentation.context.startActivity(Intent().setComponent(ComponentName(target, "com.financegenie.android.BenchmarkSetupActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        assertTrue("10,000-record synthetic fixture must be ready", device.wait(Until.hasObject(By.text("FinanceGenie benchmark ready: 10000")), 120_000))
    }
}
