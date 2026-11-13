package com.t1dm.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.t1dm.core.model.TwinScan
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Phone-local, never archived: derived from the user's face. [outputsJson] is `{key: number}`. */
@Entity(tableName = "twin_scan", indices = [Index("atMs")])
data class TwinScanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val source: String,
    val outputsJson: String,
)

/** The user's manual edits to the live twin; one row per [com.t1dm.core.model.TwinTrait.key]. */
@Entity(tableName = "twin_override")
data class TwinOverrideEntity(
    @PrimaryKey val key: String,
    val value: Double,
    val updatedAtMs: Long,
)

/** Phone-local, never archived: the face texture, one row ([id] 0), lossy WebP with alpha. */
@Entity(tableName = "twin_face")
class TwinFaceEntity(
    @PrimaryKey val id: Int = 0,
    val atMs: Long,
    val webp: ByteArray,
)

@Dao
interface TwinFaceDao {
    @Upsert suspend fun upsert(row: TwinFaceEntity)

    @Query("SELECT webp FROM twin_face WHERE id = 0")
    fun observe(): Flow<ByteArray?>

    @Query("DELETE FROM twin_face")
    suspend fun deleteAll()
}

@Dao
interface TwinScanDao {
    @Insert suspend fun insert(row: TwinScanEntity): Long

    @Query("SELECT * FROM twin_scan ORDER BY atMs, id")
    fun observeAll(): Flow<List<TwinScanEntity>>

    @Query("SELECT * FROM twin_scan ORDER BY atMs DESC, id DESC LIMIT 1")
    suspend fun latest(): TwinScanEntity?

    @Query("DELETE FROM twin_scan")
    suspend fun deleteAll()
}

@Dao
interface TwinOverrideDao {
    @Upsert suspend fun upsert(row: TwinOverrideEntity)

    @Query("SELECT * FROM twin_override")
    fun observeAll(): Flow<List<TwinOverrideEntity>>

    @Query("DELETE FROM twin_override WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM twin_override")
    suspend fun deleteAll()
}

fun encodeTwinOutputs(outputs: Map<String, Double>): String =
    JsonObject(outputs.filterValues { it.isFinite() }.mapValues { JsonPrimitive(it.value) }).toString()

/** A malformed row decodes empty rather than throwing: one bad scan must not hide the rest. */
fun decodeTwinOutputs(json: String): Map<String, Double> = runCatching {
    Json.parseToJsonElement(json).jsonObject.mapNotNull { (k, v) ->
        v.jsonPrimitive.doubleOrNull?.let { k to it }
    }.toMap()
}.getOrDefault(emptyMap())

fun TwinScanEntity.toTwinScan(): TwinScan = TwinScan(id, atMs, source, decodeTwinOutputs(outputsJson))
