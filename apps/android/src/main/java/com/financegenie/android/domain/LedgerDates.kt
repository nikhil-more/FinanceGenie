package com.financegenie.android.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object LedgerDates {
    val reportingZone: ZoneId = ZoneId.of("Asia/Kolkata")

    fun today(clock: Clock): LocalDate = LocalDate.now(clock.withZone(reportingZone))

    fun effectiveDate(instant: Instant): LocalDate = instant.atZone(reportingZone).toLocalDate()
}

data class DateRange(val startInclusive: LocalDate, val endExclusive: LocalDate) {
    init {
        require(startInclusive < endExclusive) { "Date range must have a positive length" }
    }

    operator fun contains(date: LocalDate): Boolean = date >= startInclusive && date < endExclusive

    companion object {
        fun month(month: YearMonth): DateRange = DateRange(
            startInclusive = month.atDay(1),
            endExclusive = month.plusMonths(1).atDay(1),
        )
    }
}
