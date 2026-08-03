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
 * The LEGACY backup envelope: settings plus the BG panel's drawings, as one uncompressed JSON
 * document.
 *
 * **Nothing writes this format any more.** The Backup panel supersedes it with
 * `com.t1dm.data.backup.Archive`, which carries the whole record rather than configuration alone.
 * [parse] stays live and load-bearing — it is what lets every file already on the user's disk still
 * restore, reached from `AppContainer.restoreArchive` when the chosen file turns out not to be an
 * archive. [wrap] survives only to build the fixtures its own compatibility tests parse: the
 * guarantee under test is about documents written by past builds, so the test needs a way to
 * produce one.
 *
 * ```json
 * { "format": "t1dm.backup", "version": 1,
 *   "config":    { "format": "t1dm.config", "version": 1, "exportedAtMs": …, "kv": { … } },
 *   "paintings": [ { "createdAtMs": …, "tool": "marker", "colorArgb": …, "widthDp": …, "points": "…" } ] }
 * ```
 *
 * **Backward compatibility is the whole design.** [SettingsStore.importJson] validates its `format` tag
 * at the ROOT of whatever it is handed, so wrapping the old document would have broken every backup
 * file already on disk. Instead this layer only ever *chooses the document*: a root carrying a `config`
 * OBJECT is the new envelope and the inner object is handed on verbatim; anything else is handed on
 * unchanged, so a legacy flat file and a foreign one alike are still adjudicated by that same `format`
 * tag. `SettingsStore` therefore needed no change at all, and — because the detection is on structure
 * rather than on a version number — a file written by a build that never heard of drawings still
 * restores, byte for byte, through the same code path.
 *
 * Deliberately `kotlinx.serialization`'s `JsonElement` rather than `org.json`, which the settings
 * document uses: `org.json` on the host JVM is a *different implementation* from Android's (its
 * `optString`/`opt*` null semantics differ), so a unit test pinning this compatibility contract against
 * it would be testing something other than what ships. `kotlinx.serialization` is the same code in both
 * places, which is exactly what a compatibility test needs — and it is already in the APK via `:sync`.
 *
 * Nothing here is a security seam in either direction: only allowlisted config keys are ever applied
 * (that filter stays in [SettingsStore]), and a painting is inert display-only geometry that no
 * calculator, model channel, alarm or §3.6 rail reads.
 */
object ConfigBackup {

    const val FORMAT = "t1dm.backup"
    const val VERSION = 1

    /**
     * The export size guard. A backup is written through SAF into a file the user may well mail to
     * themselves, and the drawings are the only unbounded thing in it — nothing prunes strokes, and a
     * year of daily annotation is a plausible way to reach a document no text editor will open.
     *
     * Past either cap the OLDEST strokes are dropped and the count is reported, rather than the export
     * failing or silently truncating: a partial backup of recent work beats no backup at all, and the
     * caller states plainly what was left out.
     */
    const val MAX_PAINTINGS = 4_000
    const val MAX_POINTS = 250_000

    /** A finished export: the document to write, plus anything the user should be told about it. */
    class Document(val json: String, val note: String?)

    /**
     * A parsed import. [configJson] is null only when the file IDENTIFIES ITSELF as a backup that
     * carries no settings document (a drawings-only file) — the caller then skips
     * [SettingsStore.importJson] entirely rather than tripping its "contained no recognised settings"
     * guard on a file that never claimed to have any. A file that identifies itself as nothing in
     * particular is emphatically not that case and still goes to the settings importer to be refused.
     */
    class Parsed(
        val configJson: String?,
        val paintings: List<PaintStroke>,
        val skippedPaintings: Int,
    )

    /** Build the envelope around an already-rendered settings document. */
    fun wrap(configJson: String, paintings: List<PaintStroke>): Document {
        val config = runCatching { json.parseToJsonElement(configJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("Could not render the settings for export.") }
        val kept = capped(paintings)
        val omitted = paintings.size - kept.size
        // A settings document with no keys is not one `importJson` will accept — it refuses an empty
        // allowlist outright — and emitting the `config` object regardless made the drawings-only
        // shape [Parsed] reserves unreachable: a fresh install with drawings and no settings edits
        // exported a file that this build could not then restore.
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

    /**
     * Choose the settings document and decode the drawings, accepting BOTH the wrapped shape and the
     * legacy flat one. A malformed individual painting is skipped and counted rather than failing the
     * whole restore — one unreadable blob must not cost the user their settings.
     */
    fun parse(text: String): Parsed {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw IllegalArgumentException("Not a valid backup file (could not parse JSON).") }

        // Structure, not a version tag: a `config` OBJECT is the envelope. Everything else falls
        // through to the raw text so `SettingsStore.importJson` adjudicates the `format` tag exactly as
        // it did before this layer existed — the legacy flat document restores, and a foreign JSON the
        // user mis-picked in the SAF chooser is REFUSED there rather than importing as a silent no-op
        // that the caller can only render as success. The drawings-only file is the one case that
        // legitimately has no settings document, so it is recognised by a POSITIVE marker of its own
        // rather than by having nothing else recognisable about it.
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

    // ── the paintings block ────────────────────────────────────────────────────────────────────

    /**
     * Keep the NEWEST strokes that fit both caps. Newest rather than oldest because the drawings are
     * annotations on recent data: a stroke over a window the user can no longer pan to is the one they
     * will miss least.
     */
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
                    // The SAME versioned little-endian codec the `bg_paint_stroke.points` column uses,
                    // base64'd. Re-encoding the polyline as JSON numbers would triple the file for no
                    // gain, and would give the backup a second geometry format to keep in step.
                    put("points", Base64.getEncoder().encodeToString(PaintStrokeBlob.encode(s.tsMs, s.yFrac)))
                },
            )
        }
    }

    private fun decodePainting(o: JsonObject): PaintStroke {
        val points = PaintStrokeBlob.decode(Base64.getDecoder().decode(o.str("points")))
        // A blob declaring ZERO points decodes cleanly but is not a drawing, and the store refuses it
        // (`addPaintStroke` requires at least one point). Rejecting it here is what keeps the skipped
        // count honest and the restore whole: reaching the insert with one would abort the import
        // mid-array, stranding the settings already committed and dropping every later drawing.
        if (points.tsMs.isEmpty()) throw IllegalArgumentException("painting carries no points")
        return PaintStroke(
            // id 0: the store mints a fresh row id — an imported stroke is a NEW row, never a claim on
            // an id this device may already have given to something else.
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
