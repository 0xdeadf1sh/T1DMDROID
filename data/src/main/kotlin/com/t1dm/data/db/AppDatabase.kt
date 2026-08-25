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
 * Keep-forever store: hand-written migrations in [MigrationRunner], `fallbackToDestructiveMigration`
 * never invoked. Revisions are additive — new tables and nullable columns, never a drop.
 */
@Database(
    entities = [
        CgmSourceEntity::class,
        CgmReadingEntity::class,
        CgmRawSampleEntity::class,
        CgmSensorSecretEntity::class,
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
        EventTombstoneEntity::class,
    ],
    version = 26,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cgmSourceDao(): CgmSourceDao
    abstract fun cgmReadingDao(): CgmReadingDao
    abstract fun cgmRawSampleDao(): CgmRawSampleDao
    abstract fun cgmSensorSecretDao(): CgmSensorSecretDao
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
    abstract fun eventTombstoneDao(): EventTombstoneDao

    companion object {
        const val NAME = "t1dm.db"

        /**
         * Must equal `@Database(version = …)` above. A bump lands on EVERY branch: both share one
         * database file, there is no destructive fallback, and an enum name one branch writes throws
         * in the other's `valueOf` on the READ, nowhere near the migration.
         */
        const val SCHEMA_VERSION = 26

        /** [FoodFts] is not a Room entity, so a fresh install creates it here; an upgrade gets it
         *  from [MigrationRunner.MIGRATION_4_5]. Same DDL both paths. */
        fun build(context: Context): AppDatabase =
            MigrationRunner
                .configure(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME))
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(connection: SQLiteConnection) = FoodFts.create(connection)
                })
                // HyperOS/Android 16 compiles the system SQLite without fts5.
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
