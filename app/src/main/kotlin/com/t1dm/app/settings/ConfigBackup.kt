package com.t1dm.app.settings

import com.t1dm.core.model.PaintStroke
import com.t1dm.data.db.PaintStrokeBlob
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

/** Legacy envelope: [parse] restores old files, [wrap] builds fixtures; `config` ⇒ envelope. */
object ConfigBackup {

    const val FORMAT = "t1dm.backup"
    const val VERSION = 1

    /** Export size guard (nothing else prunes); past either cap, OLDEST drop, count reported. */
    const val MAX_PAINTINGS = 4_000
    const val MAX_POINTS = 250_000

    class Document(val json: String, val note: String?)

    /** [configJson] null only for a no-settings file; caller skips [SettingsStore.importJson]. */
    class Parsed(
        val configJson: String?,
        val paintings: List<PaintStroke>,
        val skippedPaintings: Int,
    )

    fun wrap(configJson: String, paintings: List<PaintStroke>): Document {
        val config = runCatching { json.parseToJsonElement(configJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("Could not render the settings for export.") }
        val kept = capped(paintings)
        val omitted = paintings.size - kept.size
        // `importJson` refuses empty `kv`; omitting `config` makes drawings-only reachable.
        val hasSettings = (config["kv"] as? JsonObject)?.isNotEmpty() == true
        val doc = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            if (hasSettings) put("config", config)
            put("paintings", encodePaintings(kept))
        }
        val note = when {
            omitted > 0 -> "$omitted older drawing${plural(omitted)} left out"
            else -> null
        }
        return Document(json.encodeToString(JsonObject.serializer(), doc), note)
    }

    /** A malformed painting is skipped and counted; one bad blob must not cost the settings. */
    fun parse(text: String): Parsed {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("Not a valid backup file (could not parse JSON).") }

        // Structure not version: `config` OBJECT ⇒ envelope, else raw; importJson refuses foreign.
        val wrapped = root["config"] as? JsonObject
        val paintings = root["paintings"] as? JsonArray
        val configJson = when {
            wrapped != null -> json.encodeToString(JsonObject.serializer(), wrapped)
            (root["format"] as? JsonPrimitive)?.contentOrNull == FORMAT || paintings != null -> null
            else -> text
        }

        val array = paintings ?: JsonArray(emptyList())
        var skipped = 0
        val strokes = ArrayList<PaintStroke>(array.size)
        for (element in array) {
            val s = runCatching { decodePainting(element.jsonObject) }.getOrNull()
            if (s == null) skipped++ else strokes.add(s)
        }
        return Parsed(configJson, strokes, skipped)
    }

    /** Newest first: dropping an unreachable-window stroke is missed least. */
    internal fun capped(paintings: List<PaintStroke>): List<PaintStroke> {
        val newestFirst = paintings.sortedByDescending { it.createdAtMs }
        val kept = ArrayList<PaintStroke>(minOf(paintings.size, MAX_PAINTINGS))
        var points = 0
        for (s in newestFirst) {
            if (kept.size >= MAX_PAINTINGS || points + s.size > MAX_POINTS) break
            kept.add(s)
            points += s.size
        }
        return kept.sortedWith(compareBy({ it.createdAtMs }, { it.id }))
    }

    private fun encodePaintings(strokes: List<PaintStroke>): JsonArray = buildJsonArray {
        for (s in strokes) {
            add(
                buildJsonObject {
                    put("createdAtMs", s.createdAtMs)
                    put("tool", s.tool)
                    put("colorArgb", s.colorArgb)
                    put("widthDp", s.widthDp)
                    // The same codec as the `bg_paint_stroke.points` column, base64'd.
                    put("points", Base64.getEncoder().encodeToString(PaintStrokeBlob.encode(s.tsMs, s.yFrac)))
                },
            )
        }
    }

    private fun decodePainting(o: JsonObject): PaintStroke {
        val points = PaintStrokeBlob.decode(Base64.getDecoder().decode(o.str("points")))
        // Zero-point blob decodes but `addPaintStroke` refuses it, aborting mid-import, stranding.
        if (points.tsMs.isEmpty()) throw IllegalArgumentException("painting carries no points")
        return PaintStroke(
            // id 0: the store mints a fresh row id.
            id = 0L,
            createdAtMs = o["createdAtMs"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("painting has no createdAtMs"),
            tool = o.str("tool"),
            colorArgb = o["colorArgb"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("painting has no colorArgb"),
            widthDp = o["widthDp"]?.jsonPrimitive?.floatOrNull
                ?: throw IllegalArgumentException("painting has no widthDp"),
            tsMs = points.tsMs,
            yFrac = points.yFrac,
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull ?: throw IllegalArgumentException("painting has no $key")

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private val json = kotlinx.serialization.json.Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }
}
