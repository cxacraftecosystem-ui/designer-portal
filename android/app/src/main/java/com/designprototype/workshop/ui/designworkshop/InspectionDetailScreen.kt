package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DwFieldStampDto
import com.designprototype.workshop.data.DwValues
import com.designprototype.workshop.data.DwInspectionAttempt
import com.designprototype.workshop.data.DwInspectionDetailDto
import com.designprototype.workshop.data.DwInspectionReading
import com.designprototype.workshop.data.EntityDto
import com.designprototype.workshop.data.FieldDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.StageBucketDto
import com.designprototype.workshop.data.StageDto
import com.designprototype.workshop.data.StageSchemaStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.canInspectDesignWorkshops
import com.designprototype.workshop.data.collections
import com.designprototype.workshop.data.dwCardRowTitle
import com.designprototype.workshop.data.dwInspectionFieldReading
import com.designprototype.workshop.data.dwInspectionIsReadOnly
import com.designprototype.workshop.data.dwInspectionUnansweredCount
import com.designprototype.workshop.data.liveFields
import com.designprototype.workshop.data.singleton
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException

/**
 * ONE DESIGN & PROTOTYPE WORKSHOP, READ BY THE PERSON INSPECTING IT — and by nobody who can change it.
 *
 * ── THE ONE RULE THIS SCREEN EXISTS TO KEEP ──────────────────────────────────────────────────────
 *
 * **NOTHING HERE MAY OFFER A WRITE, AND NOTHING HERE MAY OFFER A CONTROL THE API WOULD 404.** The
 * payload says `readOnly: true` on the wire precisely so that a screen cannot mistake it for the
 * designer's read, and [dwInspectionIsReadOnly] fails CLOSED on a payload that predates the key. The
 * refusal is not cosmetic: every stage-editing route, the report, the photo intake, the codes sheet,
 * the AI layers and the provenance view all stand behind `load_workshop_or_404`, which 404s anybody
 * outside `DESIGN_WORKSHOP_ROLES` before it looks at the row — and an INSPECTOR is outside it by
 * construction. A "tap the stage to open it" affordance on this screen would be nine 404s waiting to
 * be found by an inspector who assumes the app is broken. See `DwInspectionDestination`, which
 * enumerates them so that a tenth cannot be added and quietly linked from here.
 *
 * ── WHY IT IS NOT `StageScreen` WITH `enabled = false` ───────────────────────────────────────────
 *
 * Three reasons, argued in full on `dwInspectionFieldReading`, and none of them is tidiness: the
 * designer's controls MOUNT things that 404 (media capture, the reference picker, dictation, the
 * sketch panel, an embedded record form); they cannot resolve the media anyway, so every tile would
 * render its "could not be read" state — indistinguishable from a photograph that failed to load,
 * and not what happened; and they write into a [com.designprototype.workshop.data.WorkshopDraft],
 * which is the store `WorkshopSync` PUSHES. An inspector must never acquire one.
 *
 * What IS reused is every function that decides what a value MEANS — `DwValues`, the rich-text
 * summariser, the enum table, the reference hint and `dwCardRowTitle` — so an inspector and the
 * designer being inspected read one interpretation of the same bytes, plus [DwFieldStampDto.attribution]
 * verbatim, so they read one authorship sentence too.
 *
 * ── THE STAGE LIST COMES FROM THE REGISTRY, NOT FROM THE PAYLOAD'S KEYS ──────────────────────────
 *
 * Iterating `detail.stages.keys` would list only the stages somebody has touched. **AN EMPTY STAGE
 * IS A FINDING ON AN INSPECTION SCREEN**, not a row to omit — "stage 14 was never started" is
 * exactly the sort of thing this surface exists to surface — so the walk is over the 22 stages the
 * registry declares, and a stage with nothing in it renders as a stage with nothing in it. The
 * registry is read from [StageSchemaStore], which is network-free and falls back to the copy bundled
 * in the APK, so this half of the screen works even while the read of the workshop itself is still
 * in flight.
 *
 * ── AND THE SCORE COMES FROM `detail.completeness`, NEVER FROM `StageBucketDto.completeness` ─────
 *
 * That optional key exists on the type and is EMPTY on this payload: only the single-stage route
 * attaches one, and an inspector cannot reach that route. Reading it would print "Nothing recorded"
 * beside every stage of a finished workshop. This screen also does NOT compute a score of its own
 * with `computeWorkshopCompleteness`, which is the obvious-looking alternative: that function scores
 * a DRAFT under a custom definition this device does not hold, and a second arithmetic on the same
 * workshop is how a designer and their inspector come to disagree about what is outstanding.
 */
@Composable
fun InspectionDetailScreen(
    repository: WorkshopRepository,
    /**
     * The SERVER's workshop id, and never a draft-store id.
     *
     * The one screen in this feature family that takes one directly. Every design-workshop screen a
     * DESIGNER reaches carries the draft store's id — a `local-…` id for a workshop started in a
     * courtyard — and resolves `remoteId` itself. There is no draft here and there must never be
     * one: this account did not create the workshop, cannot edit it, and holds nothing about it on
     * this phone. The id comes from [InspectionListScreen], which is the only list this account can
     * reach.
     */
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewer = remember(repository) { repository.cachedUser() }
    // Re-derived from the cached account, exactly as the list screen does: a nav entry is not a
    // guard, and nothing is REQUESTED when the answer is already known.
    val mayInspect = remember(viewer) { viewer != null && canInspectDesignWorkshops(viewer.role) }

    var schema by remember { mutableStateOf<SchemaResponse?>(null) }
    var detail by remember(workshopId) { mutableStateOf<DwInspectionDetailDto?>(null) }
    var loading by remember(workshopId) { mutableStateOf(mayInspect) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    var notOpen by remember(workshopId) { mutableStateOf(false) }

    LaunchedEffect(workshopId, reload, mayInspect) {
        if (!mayInspect) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        notOpen = false
        // The registry FIRST, and off the device: it never fails for want of a connection, so the
        // headings and the field labels are in hand whatever the read below does.
        schema = runCatching { StageSchemaStore.load(appContext) }.getOrNull()
        runCatching { repository.workshopUnderInspection(workshopId) }
            .onSuccess { detail = it }
            .onFailure { error ->
                val status = (error as? HttpException)?.code()
                // A 404 HERE IS DELIBERATELY NOT DIAGNOSED FURTHER, and that is the server's design
                // rather than this screen giving up. `load_inspectable_workshop_or_404` answers the
                // SAME "Record not found" for all four of: no such id, a soft-deleted workshop, an
                // account that is not an inspector, and an inspector with no row on this workshop —
                // because a 403 would confirm the id exists to exactly the people it is turning
                // away. Guessing which of the four it was would be this client inventing a fact the
                // server refused to give it.
                if (status == 404) notOpen = true else loadError = error.inspectionFailure(DwInspectionAttempt.READ)
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!mayInspect) {
            InspectionNotice(
                "The inspection surface belongs to the Inspector / Reviewer tier, and is scoped to " +
                    "the workshops an admin has assigned to that account. Designers and admins read " +
                    "design & prototype workshops on Design workshops instead; an admin chooses who " +
                    "inspects a workshop from that workshop's own stage index.",
                warning = true
            )
            return@Column
        }

        if (notOpen) {
            InspectionNotice(
                "This workshop is not open to you. Either it is not assigned to you to inspect, or " +
                    "it has been deleted since your list was loaded. An admin assigns inspections " +
                    "from a workshop's own stage index.",
                warning = true
            )
            return@Column
        }

        loadError?.let { InspectionNotice(it, warning = false) }

        val record = detail
        if (record == null) {
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text("Loading…", color = MaterialTheme.field.muted, fontSize = 13.sp)
                }
            } else {
                OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try again")
                }
            }
            return@Column
        }

        Text(
            record.title.ifBlank { "Untitled workshop" },
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
        val subtitle = listOfNotNull(
            record.craftName?.takeIf { it.isNotBlank() },
            record.clusterName?.takeIf { it.isNotBlank() },
            listOfNotNull(
                record.district?.takeIf { it.isNotBlank() },
                record.state?.takeIf { it.isNotBlank() },
            ).joinToString(", ").takeIf { it.isNotBlank() },
            record.workshopCode?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = MaterialTheme.field.body, fontSize = 13.sp)
        }

        // ── THE READ-ONLY STRIP, AND IT IS DRAWN FROM THE WIRE'S OWN FLAG ────────────────────────
        //
        // Not from the route this screen happens to call. `dwInspectionIsReadOnly` is what reads the
        // flag, it fails closed on a payload that predates it, and it is the one place the answer
        // changes the day this screen is shared with the designer's read.
        if (dwInspectionIsReadOnly(record.readOnly)) {
            InspectionNotice(
                "Read-only. This is an inspection: every stage below is shown as the designers " +
                    "recorded it, with who wrote each field, and nothing here can be edited, " +
                    "submitted or deleted. Photographs, recordings and attachments are not carried " +
                    "on an inspection read.",
                warning = true
            )
        }

        val registry = schema
        if (registry == null || registry.stages.isEmpty()) {
            // The registry is read from the device and falls back to the copy bundled in the APK, so
            // this is close to unreachable — but "close to" is not "never", and drawing an empty
            // page would read as a workshop with no stages rather than as a build with no registry.
            InspectionNotice(
                "This phone could not read the field registry, so the stages cannot be laid out. " +
                    "The workshop itself is fine — nothing here has been changed and nothing is " +
                    "missing from the repository.",
                warning = false
            )
            return@Column
        }

        HorizontalDivider()

        registry.stages.sortedBy { it.number }.forEach { stage ->
            InspectionStage(
                schema = registry,
                stage = stage,
                bucket = record.stages[stage.key],
                // THE SCORE FROM THE TOP-LEVEL MAP, never `bucket.completeness` — see the KDoc.
                requiredFilled = record.completeness[stage.key]?.requiredFilled,
                requiredTotal = record.completeness[stage.key]?.requiredTotal,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * One of the 22 stages, whether or not anybody has touched it.
 *
 * The singleton first, then each repeating entity in declaration order, then the designer's own
 * custom answers — the same reading order the stage form uses, so an inspector and the designer walk
 * one document.
 */
@Composable
private fun InspectionStage(
    schema: SchemaResponse,
    stage: StageDto,
    bucket: StageBucketDto?,
    requiredFilled: Int?,
    requiredTotal: Int?,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "${stage.number}. ${stage.title}",
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        // The server's own numbers, printed only when the server sent them. A stage the payload
        // carries no score for says nothing rather than "0 of 0".
        if (requiredTotal != null && requiredFilled != null && requiredTotal > 0) {
            Text(
                "$requiredFilled of $requiredTotal required fields answered.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        val singletonEntity = stage.singleton
        val singletonRow = bucket?.singleton.orEmpty()
        val collectionRows = stage.collections.map { entity ->
            entity to bucket?.collections?.get(entity.key).orEmpty()
        }
        val customCount = bucket?.custom.orEmpty().count { (_, value) -> DwValues.isFilled(value) }

        val singletonHasWork = singletonEntity != null &&
            singletonEntity.liveFields.any { DwValues.isFilled(singletonRow[it.key]) }
        val anythingAtAll = singletonHasWork ||
            collectionRows.any { it.second.isNotEmpty() } ||
            customCount > 0

        if (!anythingAtAll) {
            // "NOTHING RECORDED" IS A FINDING, and the sentence says which kind. A stage the source
            // document marks as one a workshop may legitimately skip is not the same as a stage
            // somebody forgot, and an inspector needs to be able to tell them apart without going to
            // find the registry themselves.
            Text(
                "Nothing has been recorded on this stage." +
                    if (stage.optionalStage) {
                        " The source document marks it as one a workshop may legitimately skip."
                    } else {
                        ""
                    },
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )
            HorizontalDivider()
            return@Column
        }

        if (singletonEntity != null && singletonHasWork) {
            InspectionRecord(
                schema = schema,
                entity = singletonEntity,
                heading = singletonEntity.title.ifBlank { singletonEntity.name },
                row = singletonRow,
                stamps = bucket?.provenance?.singleton.orEmpty(),
            )
        }

        collectionRows.forEach { (entity, rows) ->
            if (rows.isEmpty()) return@forEach
            Text(
                "${entity.title.ifBlank { entity.name }} · ${rows.size} " +
                    if (rows.size == 1) "record" else "records",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            rows.forEachIndexed { index, row ->
                InspectionRecord(
                    schema = schema,
                    entity = entity,
                    // Titled with the SAME rule the designer's own collection list titles it with,
                    // so a row an inspector asks about is a row the designer can find by the name
                    // they know it by.
                    heading = dwCardRowTitle(entity, row, index),
                    row = row,
                    // Keyed BY ENTRY ID and never by position: the readers of this payload sort
                    // their rows differently, and a positional lookup would show one participant's
                    // edits against another participant's name.
                    stamps = bucket?.provenance?.forRow(entity.key, row.entryId()).orEmpty(),
                )
            }
        }

        if (customCount > 0) {
            // COUNTED, NOT LABELLED, and the sentence says why rather than leaving a gap.
            // `GET /design-workshops/{id}/custom-sections` sits behind `load_workshop_or_404` and is
            // a 404 for an inspector, so the QUESTIONS cannot be read at all. Printing the raw
            // designer-defined keys as if they were labels would put made-up question text on an
            // inspection.
            Text(
                "$customCount ${if (customCount == 1) "answer" else "answers"} to " +
                    (if (customCount == 1) "a question" else "questions") +
                    " this workshop's designer added to this stage " +
                    (if (customCount == 1) "is" else "are") + " recorded. The questions themselves " +
                    "are read through a route an inspection does not reach, so the answers are not " +
                    "shown without them.",
                color = MaterialTheme.field.muted,
                fontSize = 12.sp
            )
        }

        HorizontalDivider()
    }
}

/**
 * One record — the stage's own answers, or one row of a repeating list — with every value it holds
 * and who wrote it.
 *
 * **UNANSWERED FIELDS ARE COUNTED AND NOT DRAWN.** Forty empty rows would bury the answers that
 * exist, and omitting them silently would tell an inspector nothing about the gaps — which is most
 * of what an inspection is looking for. So the count is printed, with the total beside it, and the
 * inspector can see at a glance that a participant row answered six of nineteen.
 */
@Composable
private fun InspectionRecord(
    schema: SchemaResponse,
    entity: EntityDto,
    heading: String,
    row: Map<String, JsonElement>,
    stamps: Map<String, DwFieldStampDto>,
) {
    val fields = entity.liveFields
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (heading.isNotBlank()) {
                Text(
                    heading,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            fields.forEach { fieldSpec ->
                when (val reading = dwInspectionFieldReading(schema, entity, fieldSpec, row)) {
                    is DwInspectionReading.Empty -> Unit
                    is DwInspectionReading.Text ->
                        InspectionValue(fieldSpec, reading.text, stamps[fieldSpec.key], media = false)
                    is DwInspectionReading.Media -> InspectionValue(
                        fieldSpec,
                        // "N files recorded here" AND THE REASON THEY ARE NOT SHOWN, in one
                        // sentence. A field with nothing in it is `Empty` and is counted as
                        // unanswered instead, so "no photograph" and "a photograph this read does
                        // not carry" never collapse into one line.
                        "${reading.count} ${if (reading.count == 1) "file" else "files"} recorded " +
                            "here. An inspection read does not carry photographs, recordings or " +
                            "attachments.",
                        stamps[fieldSpec.key],
                        media = true,
                    )
                }
            }
            val unanswered = dwInspectionUnansweredCount(schema, entity, fields, row)
            if (unanswered > 0) {
                Text(
                    "$unanswered of ${fields.size} " +
                        (if (fields.size == 1) "field" else "fields") + " here " +
                        (if (unanswered == 1) "is" else "are") + " unanswered and " +
                        (if (unanswered == 1) "is" else "are") + " not listed above.",
                    color = MaterialTheme.field.muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * One label, one value, and the authorship sentence under it.
 *
 * [DwFieldStampDto.attribution] is used VERBATIM rather than reworded, so an inspector and the
 * designer being inspected read one sentence about one field. It answers null where there is nothing
 * honest to say — an unattributed value on a row written before the column existed — and null is
 * rendered as silence, never as "Unknown", for the reason that function gives: a label reading
 * "Unknown" on every row trains a reader to stop looking at it.
 */
@Composable
private fun InspectionValue(
    field: FieldDto,
    text: String,
    stamp: DwFieldStampDto?,
    media: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            field.label.ifBlank { field.key },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text,
            // The MEDIA line is a statement about this read rather than a value the designer typed,
            // so it is set in the muted colour that every other explanatory sentence on this screen
            // uses — and the words say so too, because the colour alone is not legible to everybody.
            color = if (media) MaterialTheme.field.muted else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp
        )
        stamp?.attribution()?.let {
            Text(
                it,
                color = MaterialTheme.field.muted,
                fontSize = 10.sp,
                modifier = Modifier
                    .background(MaterialTheme.field.surface50, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/**
 * The server's own id for this row, as `_stages_payload` writes it into the object.
 *
 * The bookkeeping keys (`_entryId`, `_ordinal`, `_clientKey`) travel INSIDE the row rather than
 * beside it, and this is the only one this screen reads: it is what addresses the row's stamps in
 * [com.designprototype.workshop.data.DwStageProvenanceDto.forRow]. Null for a row with no server id,
 * which on this surface cannot happen — every row here came off the server — but which the
 * provenance lookup already answers "no stamps" to rather than crashing.
 */
private fun Map<String, JsonElement>.entryId(): String? =
    (this["_entryId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
