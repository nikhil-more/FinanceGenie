package com.financegenie.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert

// API 26's ICU inserts a non-breaking space after INR's symbol. Keep every
// sign, digit, grouping separator and decimal exact across supported versions.
private fun String.withoutCurrencySpace() = replace("₹\u00a0", "₹").replace("₹\u202f", "₹")

internal fun hasMoneyText(expected: String): SemanticsMatcher {
    require('₹' in expected)
    return SemanticsMatcher("Exact money text $expected (platform currency spacing allowed)") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text.withoutCurrencySpace() } ==
            listOf(expected.withoutCurrencySpace())
    }
}

internal fun SemanticsNodeInteraction.assertMoneyEquals(expected: String) = assert(hasMoneyText(expected))
