package com.financegenie.android.data.local

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

internal class LedgerConverters {
    @TypeConverter fun encodeUuid(value: UUID?): String? = value?.toString()
    @TypeConverter fun decodeUuid(value: String?): UUID? = value?.let(UUID::fromString)
    @TypeConverter fun encodeDate(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun decodeDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun encodeMonth(value: YearMonth?): String? = value?.toString()
    @TypeConverter fun decodeMonth(value: String?): YearMonth? = value?.let(YearMonth::parse)
    @TypeConverter fun encodeInstant(value: Instant?): String? = value?.toString()
    @TypeConverter fun decodeInstant(value: String?): Instant? = value?.let(Instant::parse)
}
