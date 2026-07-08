package com.t1dm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room v2 (PLAN.private.md Phase 1 / §3.5; Phase 3 adds `prediction` + `server_profile`). Single
 * keep-forever database; every later revision is ALTER-only (see [MigrationRunner]) — the store is
 * never dropped, so `exportSchema` stays on and the generated `schemas/<db>/N.json` back the
 * migration validation.
 *
 * v2 is a purely **additive** migration: two new tables, no column change to any Phase-1 table.
 */
@Database(
    entities = [
        CgmSourceEntity::class,
        CgmReadingEntity::class,
        SampleEntity::class,
        DoseEventEntity::class,
        CgmAdvertRawEntity::class,
        OutboxEntity::class,
        KvEntity::class,
        HwTelemetryEntity::class,
        PredictionEntity::class,
        ServerProfileEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cgmSourceDao(): CgmSourceDao
    abstract fun cgmReadingDao(): CgmReadingDao
    abstract fun sampleDao(): SampleDao
    abstract fun doseEventDao(): DoseEventDao
    abstract fun cgmAdvertRawDao(): CgmAdvertRawDao
    abstract fun outboxDao(): OutboxDao
    abstract fun kvDao(): KvDao
    abstract fun hwTelemetryDao(): HwTelemetryDao
    abstract fun predictionDao(): PredictionDao
    abstract fun serverProfileDao(): ServerProfileDao

    companion object {
        const val NAME = "t1dm.db"

        /**
         * Build the on-disk database. Migrations come exclusively from [MigrationRunner];
         * `fallbackToDestructiveMigration` is deliberately never invoked — a keep-forever store
         * must never wipe on a schema mismatch (PLAN.private.md Phase 1).
         */
        fun build(context: Context): AppDatabase =
            MigrationRunner
                .configure(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME))
                .build()
    }
}
