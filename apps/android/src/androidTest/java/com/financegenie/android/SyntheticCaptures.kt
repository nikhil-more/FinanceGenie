package com.financegenie.android

import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Supplemental fixture images; all semantic/interaction assertions still run. */
internal fun SemanticsNodeInteraction.saveSyntheticCapture(name: String) {
    require(name.matches(Regex("step[0-9]+-[a-zA-Z0-9-]+\\.png")))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val file = File(instrumentation.targetContext.cacheDir, name)
    val bitmap = try {
        captureToImage().asAndroidBitmap()
    } catch (error: IllegalArgumentException) {
        if (Build.VERSION.SDK_INT >= 28 || error.message != "Cannot currently capture dialogs on API lower than 28!") throw error
        // This is a Compose capture limitation, not a skipped accessibility test.
        file.delete()
        File(file.parentFile, "$name.unavailable.txt").writeText("Compose dialog image capture requires API 28; interaction assertions continue.\n")
        instrumentation.sendStatus(2, Bundle().apply { putString("financegenie_capture_unavailable", name) })
        return
    }
    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
