package com.t1dm.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.t1dm.core.model.SensorPlacement
import kotlinx.coroutines.flow.Flow

/** Phone-local, never archived; one row per CGM source id. */
@Entity(tableName = "sensor_placement")
data class SensorPlacementEntity(
    @PrimaryKey val sensorId: String,
    val meshHash: Int,
    val tri: Int,
    val u: Float,
    val v: Float,
    val placedAtMs: Long,
)

@Dao
interface SensorPlacementDao {
    @Upsert suspend fun upsert(row: SensorPlacementEntity)

    @Query("SELECT * FROM sensor_placement")
    fun observeAll(): Flow<List<SensorPlacementEntity>>

    @Query("DELETE FROM sensor_placement WHERE sensorId = :sensorId")
    suspend fun delete(sensorId: String)

    @Query("DELETE FROM sensor_placement")
    suspend fun deleteAll()
}

fun SensorPlacementEntity.toSensorPlacement(): SensorPlacement = SensorPlacement(sensorId, meshHash, tri, u, v)
