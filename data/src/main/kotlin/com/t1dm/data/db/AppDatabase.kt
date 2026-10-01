package com.t1dm.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers

/** Keep-forever: migrations in [MigrationRunner], no destructive fallback. */
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
        LoggedDoseEntity::class,
        LoggedMealEntity::class,
        LoggedExerciseEntity::class,
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
    version = 32,
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
    abstract fun loggedDoseDao(): LoggedDoseDao
    abstract fun loggedMealDao(): LoggedMealDao
    abstract fun loggedExerciseDao(): LoggedExerciseDao
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

        /** Must equal `@Database(version)`; a bump lands on every branch, sharing one db file. */
        const val SCHEMA_VERSION = 31

        /** [FoodFts] isn't a Room entity: fresh installs create it, upgrades via MIGRATION_4_5. */
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
