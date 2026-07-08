package com.t1dm.data.db

import androidx.room.TypeConverter
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.Precision
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/**
 * Enum ⇄ String Room converters (PLAN.private.md Phase 1 "Enum @TypeConverters"). Names, not
 * ordinals, so reordering an enum can never silently reinterpret persisted rows. Registered
 * per-entity now; the @Database implementer re-declares @TypeConverters(Converters::class) at
 * the database level.
 */
class Converters {
    @TypeConverter fun provenanceToString(v: ReadingProvenance?): String? = v?.name
    @TypeConverter fun stringToProvenance(v: String?): ReadingProvenance? =
        v?.let(ReadingProvenance::valueOf)

    @TypeConverter fun flagToString(v: ReadingFlag?): String? = v?.name
    @TypeConverter fun stringToFlag(v: String?): ReadingFlag? = v?.let(ReadingFlag::valueOf)

    @TypeConverter fun doseKindToString(v: DoseKind?): String? = v?.name
    @TypeConverter fun stringToDoseKind(v: String?): DoseKind? = v?.let(DoseKind::valueOf)

    @TypeConverter fun outboxKindToString(v: OutboxKind?): String? = v?.name
    @TypeConverter fun stringToOutboxKind(v: String?): OutboxKind? = v?.let(OutboxKind::valueOf)

    @TypeConverter fun outboxStateToString(v: OutboxState?): String? = v?.name
    @TypeConverter fun stringToOutboxState(v: String?): OutboxState? = v?.let(OutboxState::valueOf)

    @TypeConverter fun forecastStatusToString(v: ForecastStatus?): String? = v?.name
    @TypeConverter fun stringToForecastStatus(v: String?): ForecastStatus? =
        v?.let(ForecastStatus::valueOf)

    @TypeConverter fun backendIdToString(v: BackendId?): String? = v?.name
    @TypeConverter fun stringToBackendId(v: String?): BackendId? = v?.let(BackendId::valueOf)

    @TypeConverter fun precisionToString(v: Precision?): String? = v?.name
    @TypeConverter fun stringToPrecision(v: String?): Precision? = v?.let(Precision::valueOf)
}
