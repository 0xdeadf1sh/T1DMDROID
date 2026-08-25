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

/**
 * The legacy backup envelope; nothing writes it any more. [parse] still restores the files already
 * on disk and [wrap] builds the fixtures the compatibility tests parse. A root with a `config`
 * OBJECT is this envelope; anything else is handed to [SettingsStore.importJson] unchanged.
 */
object ConfigBackup {

    const val FORMAT = "t1dm.backup"
    const val VERSION = 1

    /** Export size guard: nothing else prunes strokes. Past either cap the OLDEST are dropped and
     *  the count is reported. */
    const val MAX_PAINTINGS = 4_000
    const val MAX_POINTS = 250_000

    class Document(val json: String, val note: String?)

    /** [configJson] is null only where the file identifies itself as a backup with no settings
     *  document; the caller then skips [SettingsStore.importJson] instead of tripping its refusal. */
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
        // `importJson` refuses an empty `kv`, so omitting `config` is what makes the drawings-only
        // shape [Parsed] reserves reachable.
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

    /** A malformed painting is skipped and counted; one unreadable blob must not cost the settings. */
    fun parse(text: String): Parsed {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("Not a valid backup file (could not parse JSON).") }

        // Structure, not a version tag: a `config` OBJECT is the envelope. Anything else falls
        // through as raw text, so `SettingsStore.importJson` refuses a foreign file rather than
        // importing a silent no-op. A drawings-only file is known by a positive marker of its own.
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

    /** Newest first: a stroke over a window the user can no longer pan to is the one missed least. */
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
        // A zero-point blob decodes cleanly but `addPaintStroke` refuses it, and reaching that insert
        // would abort the import mid-array, stranding the settings already committed.
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
