package com.t1dm.core.model

/** Stored by ordinal: append only. */
enum class CgmLogKind { TX, RX, GATT, DEC, LOG }

/** Stored by ordinal: append only. */
enum class CgmLogLevel { D, I, W, E }

/** What a line is about; NONE is never folded. Stored by ordinal: append only. */
enum class CgmLogTopic { NONE, GLUCOSE, RSSI, HISTORY, STATUS, CLINICAL, ADVERT }

/** One line of one sensor's log. Identity equality: the viewer keys folds on the instance. */
class CgmLogEntry(
    val wallMs: Long,
    /** `elapsedRealtimeNanos`; comparable only within one boot. */
    val monoNs: Long,
    val kind: CgmLogKind,
    val level: CgmLogLevel,
    val topic: CgmLogTopic,
    /** Characteristic, opcode or channel name; null when none applies. */
    val channel: String?,
    val text: String,
    /** Null: no payload, or one withheld as key material. */
    val bytes: ByteArray?,
    /** mg/dL on a GLUCOSE DEC line, dBm on an RSSI line; null otherwise. */
    val value: Int?,
    /** First line of an exchange; later lines of the same topic join it until the next. */
    val opens: Boolean,
    /** DEC only: [monoNs] of the received chunk this line decodes; null when none. */
    val rxNs: Long? = null,
)
