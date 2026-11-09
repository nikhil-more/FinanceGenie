package com.financegenie.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun parsesPaiseAndMaximumExactly() {
        assertEquals(1L, Money.parseAmount("0.01"))
        assertEquals(24_950L, Money.parseAmount("249.50"))
        assertEquals(100L, Money.parseAmount(" 1 "))
        assertEquals(Money.INPUT_MAX_MINOR, Money.parseAmount("10000000000.00"))
    }

    @Test
    fun rejectsInvalidOrExcessPrecisionInput() {
        listOf("", "0", "0.00", "-1", "+1", "1e2", "1.001", "1.", ".50", "₹1", "1,000", "NaN", "１２")
            .forEach { input -> assertThrows(input, IllegalArgumentException::class.java) { Money.parseAmount(input) } }
    }

    @Test
    fun rejectsValuesAboveCeilingWithoutRoundingOrOverflow() {
        listOf("10000000000.01", "9999999999999999999999999.99").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { Money.parseAmount(input) }
        }
    }

    @Test
    fun zeroRequiresExplicitBudgetAllowance() {
        assertEquals(0L, Money.parseAmount("0.00", allowZero = true))
    }

    @Test
    fun serializedMinorUnitsAreCanonicalAndWithinSigned64Bits() {
        assertEquals(1L, Money.decodeMinor("1"))
        assertEquals(-1L, Money.decodeMinor("-1", signed = true))
        assertEquals(0L, Money.decodeMinor("0", allowZero = true))
        assertEquals(-Money.INPUT_MAX_MINOR, Money.decodeMinor("-1000000000000", signed = true))
        assertEquals(Long.MAX_VALUE, Money.decodeMinor(Money.encodeMinor(Long.MAX_VALUE)))
        assertEquals(Long.MIN_VALUE, Money.decodeMinor(Money.encodeMinor(Long.MIN_VALUE), signed = true))
        listOf("1.0", "01", "+1", "-0", " 1", "1e2", "9223372036854775808").forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { Money.decodeMinor(input, signed = true) }
        }
        assertThrows(IllegalArgumentException::class.java) { Money.decodeMinor("-1") }
    }

    @Test
    fun formattingAndTransportDoNotUseFloatingPoint() {
        assertEquals("0.01", Money.decimalAmount(1))
        assertEquals("-0.01", Money.decimalAmount(-1))
        assertEquals("92233720368547758.07", Money.decimalAmount(Long.MAX_VALUE))
        assertEquals("-92233720368547758.08", Money.decimalAmount(Long.MIN_VALUE))
        assertEquals("-1", Money.encodeMinor(-1))
    }

    @Test
    fun sumsAreOrderIndependentAndOverflowIsExplicit() {
        assertEquals(Long.MAX_VALUE, Money.sum(listOf(Long.MAX_VALUE, 1, -1)))
        assertEquals(Long.MAX_VALUE, Money.sum(listOf(-1, 1, Long.MAX_VALUE)))
        assertEquals(Long.MIN_VALUE, Money.sum(listOf(Long.MIN_VALUE, -1, 1)))
        assertThrows(ArithmeticException::class.java) { Money.sum(listOf(Long.MAX_VALUE, 1)) }
        assertThrows(ArithmeticException::class.java) { Money.sum(listOf(Long.MIN_VALUE, -1)) }
        assertThrows(ArithmeticException::class.java) { Money.subtract(Long.MIN_VALUE, 1) }
    }
}
