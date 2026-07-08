package com.t1dm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Room v1 (PLAN.private.md Phase 1 / §3.5). Single keep-forever database; every later revision is
 * ALTER-only (see [MigrationRunner]) — the store is never dropped, so `exportSchema` stays on and
 * the generated `schemas/<db>/N.json` back the migration validation.
 *
 * The eight entities and their DAOs are the frozen Phase-1 surface; this class only wires them.
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
    ],
    version = 1,
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
