package com.designprototype.workshop.data

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * **WHAT THIS DEVICE FOUND WRONG WITH A PHOTOGRAPH IT NEVERTHELESS IMPORTED, KEPT UNTIL SOMEBODY
 * RECORDS IT.**
 *
 * The Kotlin twin of `frontend/components/media/qualityFlagLog.ts`, `localStorage` swapped for
 * `SharedPreferences` and nothing else changed.
 *
 * ── THE GAP THIS CLOSES ───────────────────────────────────────────────────────────────────────
 *
 * Stage 21's `mediaQualityFlag` table is where a workshop records that a stored photograph has
 * something wrong with it, and its `autoDetected` column exists precisely to mark the ones the app
 * raised. Nothing has ever written it. The app raised its findings on the capture card, they lived
 * in one composable's state, and the row was to be retyped by hand hours later from memory — which
 * in practice means it was not typed at all.
 *
 * A finding cannot become a row at the moment it is raised: the row needs a `mediaId`, which is a
 * required BASIC field, and at the shutter there is no id. And it cannot be held in composition
 * state, because the capture happens on stage 4 in a courtyard and the archive table is filled in on
 * stage 21 at a desk, days later, after the app has been killed twenty times. So it has to be
 * written down somewhere that outlives the screen, keyed by the workshop.
 *
 * ── WHY SharedPreferences AND NOT THE DRAFT, WHICH WOULD BE THE BETTER HOME ───────────────────
 *
 * The right home is [WorkshopDraftStore]'s draft: already per-workshop, already durable across
 * process death, already synced, already holding every stage's data — a finding written there would
 * ride the same road as the photograph it is about. It is not used here for the same reason the web
 * gives about its own draft store: writing stage 21's rows from stage 4's capture would be a
 * cross-stage side effect on a document a designer never asked to change, and the draft is the thing
 * that syncs. This store is deliberately the smaller, dumber thing — a note pinned to the workshop,
 * which a screen offers and a person commits.
 *
 * ── IT IS AN AID AND NEVER A RECORD, WHICH IS WHY EVERY FAILURE HERE IS SILENT ────────────────
 *
 * Losing this log loses nothing a designer typed. The photographs are imported, the flags can still
 * be entered by hand exactly as they are today, and the only cost is the convenience. That is why
 * every read and write below swallows its exception: a convenience must never be able to break the
 * thing it is convenient for, and an exception thrown out of a capture card's import handler would
 * take that import's own success reporting down with it.
 *
 * ── AND NOTHING ON SCREEN CLAIMS A FLAG WAS FILED ─────────────────────────────────────────────
 *
 * As of 2026-08-28 this store is WRITTEN and not yet READ, on this client and in the browser alike.
 * The screen that would offer these rows is stage 21's collection editor — `StageScreen`'s
 * collection list here, `components/designworkshop/EntityForm.tsx` there — and neither offers them
 * yet. So no sentence anywhere in the capture card says a flag was recorded, because it has not
 * been. The moment a claim like that appears on a screen it must be checked against this paragraph.
 */
object DwQualityFlagLog {

    private const val STORE = "dw_quality_findings"

    /**
     * How many findings one workshop's log may hold.
     *
     * A workshop with two 25-photograph motif galleries and a hundred other images cannot plausibly
     * raise more than a few dozen findings that SURVIVE the gate — [DwPhotoGate] refuses blur and
     * low resolution outright, so what lands here is near-duplicates. 200 is far past any real
     * workshop and still small enough that the serialised log cannot approach a preferences file
     * anybody would notice. Oldest entries are dropped rather than newest refused: a fresh finding
     * is the one somebody is about to act on.
     */
    const val MAX_LOGGED_FINDINGS = 200

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Read one workshop's findings, oldest first.
     *
     * ALWAYS A LIST — a corrupt, absent or half-shaped entry is "no finding", never an error,
     * because there is nothing a caller could do about the difference. Validated member by member
     * rather than decoded into a data class: this string was last written by whatever build the
     * designer was running a fortnight ago, and a row with a flag token this build has never heard
     * of reaching a stage form would be a worse failure than an empty log.
     */
    fun read(context: Context, workshopId: String): List<DwPhotoGate.CapturedFinding> {
        if (workshopId.isBlank()) return emptyList()
        return runCatching {
            val raw = context
                .getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .getString(workshopId, null)
                ?: return emptyList()
            val parsed = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            parsed.mapNotNull { element -> (element as? JsonObject)?.let(::findingOrNull) }
        }.getOrDefault(emptyList())
    }

    /**
     * Append findings, de-duplicated by file and flag, keeping the NEWEST of any collision.
     *
     * The de-duplication is by `mediaId:flag` and not by media id alone, because one photograph can
     * legitimately carry two different faults and they are two different rows in the archive. The
     * newest wins so that a re-measurement — a stage reopened, a collection row re-expanded —
     * refreshes the reading in the note rather than leaving yesterday's number attached to today's
     * file. [DwPhotoGate.mediaQualityFlagRows] keeps the newest for the same reason and in the same
     * words; two functions in one feature disagreeing about which of two measurements is the true
     * one is a difference nobody sees and everybody eventually trips over.
     *
     * Returns what the log holds afterwards, so a caller that wants to react need not read back.
     */
    fun record(
        context: Context,
        workshopId: String,
        findings: List<DwPhotoGate.CapturedFinding>,
    ): List<DwPhotoGate.CapturedFinding> {
        if (workshopId.isBlank() || findings.isEmpty()) return read(context, workshopId)
        val byKey = LinkedHashMap<String, DwPhotoGate.CapturedFinding>()
        for (entry in read(context, workshopId)) byKey["${entry.mediaId}:${entry.flag}"] = entry
        for (entry in findings) byKey["${entry.mediaId}:${entry.flag}"] = entry
        // OLDEST DROPPED, NOT NEWEST REFUSED — see [MAX_LOGGED_FINDINGS]. LinkedHashMap keeps a
        // re-put key in its ORIGINAL position, so an updated finding does not jump the queue and the
        // trim therefore drops by age of first sighting, which is the order a reader expects.
        val next = byKey.values.toList().takeLast(MAX_LOGGED_FINDINGS)
        runCatching {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
                .edit()
                .putString(workshopId, encode(next))
                .apply()
        }
        return next
    }

    /**
     * Forget one workshop's log.
     *
     * Called when the workshop's local draft is deleted, so a log cannot outlive the photographs it
     * is about — a flag naming a media id nothing holds any more is the row this feature exists to
     * avoid, arriving by the back door.
     */
    fun clear(context: Context, workshopId: String) {
        if (workshopId.isBlank()) return
        runCatching {
            context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit().remove(workshopId).apply()
        }
    }

    private fun encode(findings: List<DwPhotoGate.CapturedFinding>): String =
        buildJsonArray {
            findings.forEach { finding ->
                add(
                    buildJsonObject {
                        put("mediaId", JsonPrimitive(finding.mediaId))
                        put("fileName", JsonPrimitive(finding.fileName))
                        put("flag", JsonPrimitive(finding.flag.name))
                        put("severity", JsonPrimitive(finding.severity.name))
                        put("note", JsonPrimitive(finding.note))
                        put("raisedAt", JsonPrimitive(finding.raisedAt))
                    }
                )
            }
        }.toString()

    /**
     * One stored object, or null.
     *
     * AN UNRECOGNISED `flag` OR `severity` IS DROPPED RATHER THAN COERCED. Both are registry
     * vocabularies that can gain members between builds, and mapping an unknown token onto the first
     * enum constant would file a photograph under BLUR because an older phone read a word it did not
     * know. Dropping loses a convenience; coercing invents an observation.
     */
    private fun findingOrNull(row: JsonObject): DwPhotoGate.CapturedFinding? {
        val mediaId = row.text("mediaId") ?: return null
        if (mediaId.isBlank()) return null
        val flag = QualityFlag.entries.firstOrNull { it.name == row.text("flag") } ?: return null
        val severity = QualitySeverity.entries.firstOrNull { it.name == row.text("severity") } ?: return null
        return DwPhotoGate.CapturedFinding(
            mediaId = mediaId,
            fileName = row.text("fileName").orEmpty(),
            flag = flag,
            severity = severity,
            note = row.text("note").orEmpty(),
            raisedAt = row.text("raisedAt").orEmpty(),
        )
    }

    private fun JsonObject.text(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()
}
