package com.financegenie.android.domain

import java.math.BigDecimal
import java.math.BigInteger

object Money {
    const val INPUT_MAX_MINOR = 1_000_000_000_000L

    private val decimalInput = Regex("[0-9]+(?:\\.[0-9]{1,2})?")
    private val encodedInteger = Regex("-?(?:0|[1-9][0-9]*)")
    private val minimumTotal = BigInteger.valueOf(Long.MIN_VALUE)
    private val maximumTotal = BigInteger.valueOf(Long.MAX_VALUE)

    fun parseAmount(text: String, allowZero: Boolean = false): Long {
        val input = text.trim()
        require(input.length in 1..32 && decimalInput.matches(input)) {
            "Enter a plain amount with at most two decimal places"
        }
        val exactMinor = BigDecimal(input).movePointRight(2)
        require(exactMinor <= BigDecimal.valueOf(INPUT_MAX_MINOR)) { "Amount is outside the supported input range" }
        val minor = exactMinor.longValueExact()
        requireInput(minor, allowZero = allowZero)
        return minor
    }

    fun decodeMinor(text: String, allowZero: Boolean = false, signed: Boolean = false): Long {
        require(text.length in 1..20 && text != "-0" && encodedInteger.matches(text)) {
            "Minor units must be a canonical base-10 integer string"
        }
        val minor = requireNotNull(text.toLongOrNull()) { "Minor units are outside the supported range" }
        require(signed || minor >= 0) { "Unsigned minor units cannot be negative" }
        require(allowZero || minor != 0L) { "Amount must be nonzero" }
        return minor
    }

    fun encodeMinor(minor: Long): String = minor.toString()

    fun decimalAmount(minor: Long): String = BigDecimal.valueOf(minor, 2).toPlainString()

    fun sum(values: Iterable<Long>): Long = checkedTotal(
        values.fold(BigInteger.ZERO) { total, value -> total.add(BigInteger.valueOf(value)) },
    )

    fun subtract(left: Long, right: Long): Long = Math.subtractExact(left, right)

    internal fun checkedTotal(value: BigInteger): Long {
        if (value < minimumTotal || value > maximumTotal) {
            throw ArithmeticException("Money total exceeds the signed 64-bit range")
        }
        return value.toLong()
    }

    internal fun requireInput(minor: Long, allowZero: Boolean = false, signed: Boolean = false) {
        require(minor in (if (signed) -INPUT_MAX_MINOR else 0L)..INPUT_MAX_MINOR) {
            "Amount is outside the supported input range"
        }
        require(allowZero || minor != 0L) { "Amount must be nonzero" }
    }
}
