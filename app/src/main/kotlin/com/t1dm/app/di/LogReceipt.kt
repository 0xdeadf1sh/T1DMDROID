package com.t1dm.app.di

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class LoggedEventKind { MEAL, DOSE }

/** [tsMs] grid-snap time; [clientId] phone-minted (§3.2); [outboxId] null = nothing enqueued. */
data class LogHandle(
    val kind: LoggedEventKind,
    val rowId: Long,
    val clientId: String,
    val tsMs: Long,
    val outboxId: Long?,
    val dedupKey: String?,
    val label: String,
    val caveats: List<String> = emptyList(),
)

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

internal fun hhmm(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    HHMM.format(Instant.ofEpochMilli(ms).atZone(zone))

fun logReceipt(handle: LogHandle, zone: ZoneId = ZoneId.systemDefault()): String =
    "Logged ${handle.label} at ${hhmm(handle.tsMs, zone)}"

fun undoReceipt(handle: LogHandle): String =
    (listOf("Removed ${handle.label}.") + handle.caveats).joinToString(" ")
