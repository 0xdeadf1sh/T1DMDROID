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
 * The single keep-forever store (Phase 1 / §3.5).
 *
 * Every revision past v1 is a hand-written migration in [MigrationRunner], and
 * `fallbackToDestructiveMigration` is never invoked — the database is migrated, never dropped, so
 * `exportSchema` stays on and the generated `schemas/<db>/N.json` back the migration validation.
 *
 * Revisions are additive in principle: new tables and nullable columns, never a drop. The
 * departures are recorded where they happened, on the migrations themselves — the one subtractive
 * step, which took a withdrawn surface's table and its undecodable outbox rows together, and the two
 * `sample` rebuilds, one shedding columns Room compares exactly and one changing a column's declared
 * type. What each version did and why belongs there; a second list here is a list that falls out of
 * step.
 */
@Database(
    entities = [
        CgmSourceEntity::class,
        CgmReadingEntity::class,
        CgmRawSampleEntity::class,
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
        FoodEntity::class,
        SavedMealEntity::class,
        SavedMealItemEntity::class,
        InsulinTypeEntity::class,
        PaintStrokeEntity::class,
        ConformalDeltaEntity::class,
        LoraEntity::class,
        BgInfillEntity::class,
        ExerciseSessionEntity::class,
        ExerciseFixEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cgmSourceDao(): CgmSourceDao
    abstract fun cgmReadingDao(): CgmReadingDao
    abstract fun cgmRawSampleDao(): CgmRawSampleDao
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
    abstract fun foodDao(): FoodDao
    abstract fun savedMealDao(): SavedMealDao
    abstract fun insulinTypeDao(): InsulinTypeDao
    abstract fun paintStrokeDao(): PaintStrokeDao
    abstract fun conformalDeltaDao(): ConformalDeltaDao
    abstract fun loraDao(): LoraDao
    abstract fun bgInfillDao(): BgInfillDao
    abstract fun exerciseSessionDao(): ExerciseSessionDao
    abstract fun exerciseFixDao(): ExerciseFixDao

    companion object {
        const val NAME = "t1dm.db"

        /**
         * The current keep-forever schema version (must equal the `@Database(version = …)` above).
         * A full app reset ([T1dmRepository.wipeAllData]) row-wipes at THIS version — never a drop.
         *
         * **A version bump lands on EVERY branch, whichever branch's work motivated it.** Nothing in
         * the schema names a sensor family, so a bump can look like it belongs to the driver that
         * needed it — but the two branches build against one database file on one phone, there is no
         * destructive fallback (see the builder below), and a build whose `@Database(version =)` is
         * behind the file it opens throws on launch.
         *
         * Version 19 is such a bump, and on this branch it adds nothing: the storage it introduces
         * belongs to a path only the local-only branch carries. It is still counted, so that version
         * 20 (`lora`, `bg_infill`) means the same thing on both — see [MigrationRunner.MIGRATION_18_19].
         */
        const val SCHEMA_VERSION = 20

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
