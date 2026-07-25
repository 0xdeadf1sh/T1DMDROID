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
 * Room v2 (Phase 1 / §3.5; Phase 3 adds `prediction` + `server_profile`). Single
 * keep-forever database; every later revision is ALTER-only (see [MigrationRunner]) — the store is
 * never dropped, so `exportSchema` stays on and the generated `schemas/<db>/N.json` back the
 * migration validation.
 *
 * v2 is a purely **additive** migration: two new tables, no column change to any Phase-1 table.
 * v3 (Phase 4) is likewise additive: the curve-engine event stores `logged_dose`, `logged_meal`
 * and `basal_schedule`, no change to any existing table. v4 (Phase 4, journal) adds the `note`
 * table — the durable producer for the outbox's reserved `NOTE` class. v5 (Phase 4, meal builder)
 * adds the glycemic dictionary (`food` + the FTS5 `food_fts` shadow), `saved_meal`/
 * `saved_meal_item`, and `insulin_type` — all additive. v6 (Phase 7C) is a **data-only** re-seed
 * (no schema change) folding the grown `FoodSeed` catalogue into an already-seeded install; see
 * [MigrationRunner.MIGRATION_5_6]. v7 (app-authoritative redesign) adds a `clientId` column
 * (+ UNIQUE index) to `logged_meal`/`logged_dose` — the stable phone-minted id the server keys
 * meal/dose upserts on and the app re-hydrates by — and leaves the retired
 * `sample.carbsG/bolusU/basalU` dose projections dead in place; see [MigrationRunner.MIGRATION_6_7].
 * v8 (graph annotation layer) adds `bg_paint_stroke`, the freehand drawings the user paints over the
 * BG panel — one new table, no existing table touched; see [MigrationRunner.MIGRATION_7_8].
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
        PaintStrokeEntity::class,
    ],
    version = 8,
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
    abstract fun paintStrokeDao(): PaintStrokeDao

    companion object {
        const val NAME = "t1dm.db"

        /** The current keep-forever schema version (must equal the `@Database(version = …)` above).
         *  A full app reset ([T1dmRepository.wipeAllData]) row-wipes at THIS version — never a drop. */
        const val SCHEMA_VERSION = 8

        /**
         * Build the on-disk database. Migrations come exclusively from [MigrationRunner];
         * `fallbackToDestructiveMigration` is deliberately never invoked — a keep-forever store
         * must never wipe on a schema mismatch (Phase 1).
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
