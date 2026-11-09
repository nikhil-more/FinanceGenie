package com.financegenie.android.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.financegenie.android.domain.LEDGER_CURRENCY
import com.financegenie.android.domain.LedgerDates
import com.financegenie.android.domain.UNCATEGORIZED_ID
import java.time.Clock
import java.util.UUID

@Database(
    entities = [
        SettingsEntity::class, AccountEntity::class, CategoryEntity::class,
        TransactionEntity::class, BudgetEntity::class, ObservationEntity::class,
        ChangeGroupEntity::class, ChangeItemEntity::class,
        ImportBatchEntity::class, SourceEvidenceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(LedgerConverters::class)
abstract class FinanceGenieDatabase : RoomDatabase() {
    internal abstract fun ledgerDao(): LedgerDao

    companion object {
        fun open(
            context: Context,
            name: String = "financegenie-ledger.db",
            clock: Clock = Clock.systemUTC(),
        ): FinanceGenieDatabase {
            require(Regex("[A-Za-z0-9._-]+\\.db").matches(name)) { "Database name must be a plain .db filename" }
            return Room.databaseBuilder(context.applicationContext, FinanceGenieDatabase::class.java, name)
                .addCallback(defaults(clock))
                .build()
        }

        internal fun inMemory(context: Context, clock: Clock): FinanceGenieDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, FinanceGenieDatabase::class.java)
                .addCallback(defaults(clock))
                .build()

        private fun defaults(clock: Clock): Callback = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT INTO ledger_settings (id, local_id, currency, reporting_zone, revision) VALUES (1, ?, ?, ?, 0)",
                    arrayOf(UUID.randomUUID().toString(), LEDGER_CURRENCY, LedgerDates.reportingZone.id),
                )
                db.execSQL(
                    "INSERT INTO categories (id, name, is_archived, meta_revision, meta_createdAtMillis, meta_updatedAtMillis) VALUES (?, ?, 0, 1, ?, ?)",
                    arrayOf(UNCATEGORIZED_ID, "Uncategorized", clock.millis(), clock.millis()),
                )
            }
        }
    }
}
