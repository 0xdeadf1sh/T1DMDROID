package com.t1dm.data.db

import androidx.room.TypeConverter
import com.t1dm.core.model.BackendId
import com.t1dm.core.model.ForecastStatus
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance

/** Names, not ordinals: reordering an enum cannot reinterpret persisted rows. */
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
    /** Total: unpruned backend column; a dropped-backend row must not crash the whole query. */
    @TypeConverter fun stringToBackendId(v: String?): BackendId? =
        v?.let { runCatching { BackendId.valueOf(it) }.getOrDefault(BackendId.UNKNOWN) }
}
