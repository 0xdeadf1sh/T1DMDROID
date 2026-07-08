package com.t1dm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/**
 * Room v2 (PLAN.private.md Phase 1 / §3.5; Phase 3 adds `prediction` + `server_profile`). Single
 * keep-forever database; every later revision is ALTER-only (see [MigrationRunner]) — the store is
 * never dropped, so `exportSchema` stays on and the generated `schemas/<db>/N.json` back the
 * migration validation.
 *
 * v2 is a purely **additive** migration: two new tables, no column change to any Phase-1 table.
 * v3 (Phase 4) is likewise additive: the curve-engine event stores `logged_dose`, `logged_meal`
 * and `basal_schedule`, no change to any existing table. v4 (Phase 4, journal) adds the `note`
 * table — the durable producer for the outbox's reserved `NOTE` class. v5 (Phase 4, meal builder)
 * adds the glycemic dictionary (`food` + the FTS5 `food_fts` shadow), `saved_meal`/
 * `saved_meal_item`, and `insulin_type` — all additive.
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
        LoggedDoseEntity::class,
        LoggedMealEntity::class,
        BasalScheduleEntity::class,
        NoteEntity::class,
        FoodEntity::class,
        SavedMealEntity::class,
        SavedMealItemEntity::class,
        InsulinTypeEntity::class,
    ],
    version = 5,
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
    abstract fun loggedDoseDao(): LoggedDoseDao
    abstract fun loggedMealDao(): LoggedMealDao
    abstract fun basalScheduleDao(): BasalScheduleDao
    abstract fun noteDao(): NoteDao
    abstract fun foodDao(): FoodDao
    abstract fun savedMealDao(): SavedMealDao
    abstract fun insulinTypeDao(): InsulinTypeDao

    companion object {
        const val NAME = "t1dm.db"

        /**
         * Build the on-disk database. Migrations come exclusively from [MigrationRunner];
         * `fallbackToDestructiveMigration` is deliberately never invoked — a keep-forever store
         * must never wipe on a schema mismatch (PLAN.private.md Phase 1).
         *
         * The [FoodFts] FTS5 virtual table is not a Room entity, so a fresh install (Room's
         * `createAllTables` builds only entity tables) needs it created in `onCreate`; an upgrade
         * gets it from [MigrationRunner.MIGRATION_4_5]. Same DDL both paths ⇒ identical schema.
         */
        fun build(context: Context): AppDatabase =
            MigrationRunner
                .configure(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME))
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(connection: SQLiteConnection) = FoodFts.create(connection)
                })
                // Ship our own SQLite instead of trusting the OEM system build: HyperOS/Android 16
                // compiles SQLite WITHOUT fts5, which crashed the food-search virtual table on DB
                // open. The bundled driver carries a consistent SQLite (with fts5) on every device.
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
