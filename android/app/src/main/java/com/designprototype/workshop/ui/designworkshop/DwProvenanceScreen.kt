package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_PROVENANCE_ALL_MATCH
import com.designprototype.workshop.data.DW_PROVENANCE_COLUMN_CANONICAL
import com.designprototype.workshop.data.DW_PROVENANCE_COLUMN_FIELD
import com.designprototype.workshop.data.DW_PROVENANCE_COLUMN_STORED
import com.designprototype.workshop.data.DW_PROVENANCE_LOADING
import com.designprototype.workshop.data.DW_PROVENANCE_LOAD_FAILED
import com.designprototype.workshop.data.DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS
import com.designprototype.workshop.data.DW_PROVENANCE_NOT_AN_ERROR_PREFIX
import com.designprototype.workshop.data.DW_PROVENANCE_NOT_AN_ERROR_SUFFIX
import com.designprototype.workshop.data.DW_PROVENANCE_TITLE
import com.designprototype.workshop.data.DwProvenanceEntryDto
import com.designprototype.workshop.data.DwProvenanceReportDto
import com.designprototype.workshop.data.SchemaResponse
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopDraftStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.dwCanonicalText
import com.designprototype.workshop.data.dwComparisonText
import com.designprototype.workshop.data.dwDivergedFields
import com.designprototype.workshop.data.dwDivergenceHeadline
import com.designprototype.workshop.data.dwDivergenceTally
import com.designprototype.workshop.data.dwProvenanceEntryTitle
import com.designprototype.workshop.data.isLocalOnlyWorkshop
import com.designprototype.workshop.ui.FieldPermissions
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt. Every file
// in this feature imports it, or its headings are quietly set in the body face.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field
import retrofit2.HttpException

/**
 * **THE ADMIN AUTHORSHIP & DIVERGENCE VIEW FOR ONE WORKSHOP, ON THE HANDSET.**
 *
 * The phone's counterpart of `frontend/app/(protected)/design-workshops/[id]/provenance/page.tsx`,
 * and it exists because an admin auditing from a phone previously could not reach this at all: the
 * server has served it since `workshop_provenance` landed and the browser has rendered it, while the
 * handset — the surface the fieldwork is actually captured on — had no client for it.
 *
 * ── THE ONE THING THIS SCREEN SHOWS THAT NOTHING ELSE CAN ────────────────────────────────────────
 *
 * Every designer on a workshop already sees the per-field stamps: they ride on the ordinary stage
 * read and render under each box on [StageScreen], through the very same [DwFieldStampDto
 * .attribution]. This screen adds the half nobody else can see — for every value COPIED from a shared
 * canonical record, what that record says TODAY, beside what this workshop stored.
 *
 * That comparison is derivable nowhere else. Once hydrated, a value is an ordinary string in `data`:
 * a hydrated village and a typed village are the same bytes, DELIBERATELY, so that a workshop keeps
 * what the designer saw on the day and a later correction to a shared record cannot silently rewrite
 * a document already delivered to a ministry. Only the `reference` stamp — which names the record and
 * the column — plus a live read of that record can say "this workshop says Barpali and the artisan
 * record now says Bargarh".
 *
 * ── DIVERGENCE IS NOT AN ERROR, AND NOTHING ON THIS SCREEN IMPLIES IT IS ─────────────────────────
 *
 * A workshop is a DATED OBSERVATION. An artisan who moved village after the workshop makes every row
 * that names them differ, and every one of those rows is CORRECT. So there are no warning colours
 * here, no error tint, no "fix" control, no count of "problems" and no exclamation: two columns and a
 * neutral word. The failure this screen exists to prevent is an admin being unable to explain why a
 * submitted report and the live directory disagree — not a designer being told off. Anybody adding
 * amber to this file should read that sentence again first; the palette is the argument.
 *
 * ── ADMIN ONLY, AND HIDDEN RATHER THAN GREYED ────────────────────────────────────────────────────
 *
 * `workshop_provenance` raises 403 for anyone below ADMIN, so {ADMIN, MASTER_ADMIN} and nobody else —
 * not a PROFESSOR and not the workshop's own designers, because this call crosses OUT of the workshop
 * into the shared record tables and reports one account's data beside another's.
 *
 * The entry point on [StageIndexScreen] is therefore HIDDEN for everyone else, which is a deliberate
 * departure from the viewer-roster row two lines above it. That row shows a non-admin a SENTENCE,
 * because the designer has a live question the app must answer — "how do I get my colleague into this
 * record". A designer has no such question here: they have lost nothing, because every per-field
 * stamp still renders under their own boxes on every one of the 22 stages. A greyed or explained
 * control would advertise a capability they cannot get and do not need, which is how the create
 * control is handled for the same reason.
 *
 * The gate is re-derived HERE as well, from the cached account, and no request is issued when the
 * answer is already known: the composition that drew the button is not the rule, and asking the
 * server to refuse something this client knows it may not have would put an unexplained 403 in the
 * error channel every time such an account arrived by any route.
 *
 * ── THE EMPTY REPORT IS A FIRST-CLASS RENDERING ──────────────────────────────────────────────────
 *
 * No divergence is the COMMON case. An admin wants that answered in one line rather than after
 * scrolling twenty-two stages, so the tally leads and a workshop with nothing to compare says so in a
 * sentence. An empty table would read like a failed load, which is the one thing this screen must
 * never be mistaken for.
 *
 * ── THE WORDING IS THE BROWSER'S, CHARACTER FOR CHARACTER ────────────────────────────────────────
 *
 * Every sentence on this screen is a constant in `data/DwProvenanceReport.kt`, copied from the web
 * page and pinned by `DwProvenanceReportTest`. A designer or admin who reads one sentence on a laptop
 * and another on the handset has been told two things by one product — and this is the screen where
 * that matters most, because the sentence in question is "this is not an error".
 */
@Composable
fun DwProvenanceScreen(
    repository: WorkshopRepository,
    /**
     * The DRAFT STORE's id, like every other screen in this feature — which for a workshop started in
     * a courtyard is a `local-…` id no server has ever seen. The server id is resolved here from the
     * draft's `remoteId`, exactly as [WorkshopViewersScreen] does it.
     */
    workshopId: String,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val viewer = remember(repository) { repository.cachedUser() }
    val mayAudit = remember(viewer) { mayReadWorkshopProvenance(viewer) }

    var loading by remember(workshopId) { mutableStateOf(mayAudit) }
    var reload by remember(workshopId) { mutableIntStateOf(0) }
    var report by remember(workshopId) { mutableStateOf<DwProvenanceReportDto?>(null) }
    /**
     * The registry, held only so stage and entity KEYS can be shown as the words a person recognises.
     *
     * Null is an ordinary state and never an error: a failed registry load is not a failed screen,
     * because [dwProvenanceEntryTitle] falls back to the keys, which are readable. It is loaded
     * separately and its failure is swallowed for exactly that reason — the same split the web page
     * makes, where the registry `.catch(() => null)`s and never surfaces over the comparison itself.
     */
    var registry by remember(workshopId) { mutableStateOf<SchemaResponse?>(null) }
    var loadError by remember(workshopId) { mutableStateOf<String?>(null) }
    /**
     * This workshop exists on the SERVER — a different question from whether the server could be
     * reached just now. A workshop that has never left this phone has copied nothing from a shared
     * record through the server, so there is no comparison to make and saying so is better than
     * showing an empty report that looks like "nothing has moved".
     */
    var onServer by remember(workshopId) { mutableStateOf(true) }

    LaunchedEffect(workshopId, reload, mayAudit) {
        // NOT REQUESTED AT ALL for a non-admin, matching [WorkshopViewersScreen] and
        // [DesignerRosterScreen]. Issuing the call and swallowing the 403 would ask the server to
        // refuse something this client already knows it may not have.
        if (!mayAudit) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        loadError = null

        val remoteId = WorkshopDraftStore.load(appContext, workshopId)?.remoteId
            ?: workshopId.takeUnless { isLocalOnlyWorkshop(it) }
        if (remoteId == null) {
            onServer = false
            loading = false
            return@LaunchedEffect
        }
        onServer = true

        // The registry first and separately, so that its failure cannot take the comparison with it.
        // `designWorkshopSchema` never throws on a network failure — it answers from `filesDir` or
        // from the bundled asset — but it can still throw on a build packaged without that asset, and
        // a heading rendered from keys is far better than a screen that refuses to draw.
        runCatching { repository.designWorkshopSchema(appContext) }
            .onSuccess { registry = it }

        runCatching { repository.designWorkshopProvenance(remoteId) }
            .onSuccess {
                report = it
                loadError = null
            }
            .onFailure { error ->
                report = null
                loadError = error.provenanceFailure()
            }
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            DW_PROVENANCE_TITLE,
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )

        // ── The gate, before anything is drawn ───────────────────────────────────────────────────
        //
        // Reached only by an account that got here some other way than the (hidden) button, so it is
        // a sentence and not a form: it names the rule and the fact that costs a designer nothing.
        if (!mayAudit) {
            Text(
                "Comparing a workshop against the shared records is an admin view: it reports one " +
                    "account's data beside another's, which is the line drawn everywhere else in " +
                    "this app. Nothing is missing from your own work — who set each field is still " +
                    "shown under every box on every stage.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )
            return@Column
        }

        if (!onServer) {
            Text(
                "This workshop has not been sent to the server yet, so it has copied nothing from " +
                    "the shared records and there is nothing to compare. Send it from the workshop " +
                    "list first.",
                color = MaterialTheme.field.muted,
                fontSize = 13.sp
            )
            return@Column
        }

        if (loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(DW_PROVENANCE_LOADING, color = MaterialTheme.field.muted, fontSize = 13.sp)
            }
            return@Column
        }

        val served = report
        if (served == null) {
            // THE ONLY RED ON THIS SCREEN, and it is about the REQUEST rather than about the data.
            // A failed read is a failure; a divergence is not, and the two must never share a colour
            // here or the palette starts saying the thing the whole screen is written to deny.
            Text(
                loadError ?: DW_PROVENANCE_LOAD_FAILED,
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            return@Column
        }

        val tally = remember(served) { dwDivergenceTally(served) }

        // ── The summary, which leads ─────────────────────────────────────────────────────────────
        ElevatedCard(
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    dwDivergenceHeadline(tally),
                    display = true,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp
                )
                Text(
                    // The browser's `<strong>not an error</strong>`, carried across as weight rather
                    // than as a colour: an emphasis that arrived as amber would say the opposite of
                    // the sentence it is emphasising.
                    buildAnnotatedString {
                        append(DW_PROVENANCE_NOT_AN_ERROR_PREFIX)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(DW_PROVENANCE_NOT_AN_ERROR_EMPHASIS)
                        }
                        append(DW_PROVENANCE_NOT_AN_ERROR_SUFFIX)
                    },
                    color = MaterialTheme.field.muted,
                    fontSize = 13.sp
                )
                if (tally.nothingHasMoved) {
                    Text(DW_PROVENANCE_ALL_MATCH, color = MaterialTheme.field.muted, fontSize = 13.sp)
                }
            }
        }

        // ── One card per record that has something to show ───────────────────────────────────────
        //
        // Nothing at all when the tally is zero: the sentence above has already answered the
        // question, and an empty list under it would read as a table that failed to load.
        if (!tally.nothingHasMoved) {
            served.entries.forEach { entry ->
                DwDivergenceCard(entry = entry, registry = registry)
            }
        }
    }
}

/**
 * One entry's diverged fields as a small table, or nothing at all when it has none.
 *
 * DRAWS NOTHING RATHER THAN AN EMPTY CARD, exactly as the web's `DivergenceCard` returns null. The
 * caller walks every entry in the report — twenty-two stages' worth, most of which will have nothing
 * to compare — because the alternative is filtering the list in two places and having the tally and
 * the cards eventually disagree about what counts.
 */
@Composable
private fun DwDivergenceCard(entry: DwProvenanceEntryDto, registry: SchemaResponse?) {
    val moved = remember(entry) { dwDivergedFields(entry) }
    if (moved.isEmpty()) return

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.field.surface50),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                dwProvenanceEntryTitle(entry, registry),
                display = true,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            )

            // ── The three column headings ────────────────────────────────────────────────────────
            //
            // A ROW OF LABELS AND NOT A `Table`, because Compose has none and because the two value
            // columns must be able to wrap: a village name is short and a do's-and-don'ts paragraph
            // is not, and a fixed-width grid would clip the half of this screen that carries the
            // actual comparison. The weights are the web's proportions.
            Row(modifier = Modifier.fillMaxWidth()) {
                ColumnHeading(DW_PROVENANCE_COLUMN_FIELD, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ColumnHeading(DW_PROVENANCE_COLUMN_STORED, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                ColumnHeading(DW_PROVENANCE_COLUMN_CANONICAL, Modifier.weight(1f))
            }

            moved.forEach { (fieldKey, comparison) ->
                HorizontalDivider(color = MaterialTheme.field.hairline)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fieldKey, color = MaterialTheme.field.body, fontSize = 13.sp)
                        // THE STAMP UNDER THE FIELD NAME, in the same words the stage form uses —
                        // literally the same function, `DwFieldStampDto.attribution()`, which is
                        // already asserted equal to the web's `fieldProvenanceLine`. An admin
                        // comparing two columns needs to know WHICH record the left one came out of,
                        // and that is exactly what a reference stamp says. Null renders nothing: an
                        // unattributed field says nothing rather than saying "Unknown".
                        entry.fields[fieldKey]?.attribution()?.let { line ->
                            Text(line, color = MaterialTheme.field.placeholder, fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dwComparisonText(comparison.stored),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    // WHICH SENTENCE THIS CELL SHOWS IS [dwCanonicalText]'S DECISION, NOT THIS
                    // FILE'S. A deleted canonical record is not an error and is the most interesting
                    // state on this screen — precisely the case reference hydration exists for, where
                    // this workshop still holds what the designer saw and is now the only copy — but
                    // choosing that sentence is one `if` that no JVM test can reach while it lives in
                    // a composable, and getting it wrong is exactly the defect the browser shipped.
                    // So the words come from the pure layer and only the COLOUR is decided here:
                    // muted rather than red, because the palette must not say the thing the copy
                    // spends a paragraph denying.
                    Text(
                        dwCanonicalText(comparison),
                        color = if (comparison.recordDeleted) {
                            MaterialTheme.field.muted
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnHeading(label: String, modifier: Modifier) {
    Text(
        label,
        color = MaterialTheme.field.muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
    )
}

// --------------------------------------------------------------------------------------
// The rule, and reading a failure once
// --------------------------------------------------------------------------------------

/**
 * `is_admin` — ADMIN and MASTER ADMIN and nobody else, which is what `workshop_provenance` enforces.
 *
 * A FUNCTION rather than a remembered Boolean, the same shape as [mayAdministerViewers], so the
 * screen's chrome, the decision not to issue the request, and the entry point on [StageIndexScreen]
 * are provably one rule instead of three readings of it that can drift by a clause.
 *
 * DELIBERATELY NOT `UserDto.canViewProvenance`. That column is a real grant on this handset and it
 * gates a DIFFERENT feature — the created-by and per-field edit history on the View Data screen, for
 * the record tables — and the server honours it there. `workshop_provenance` does not consult it at
 * all: it tests `is_admin` and nothing else. ORing it in here would put this screen in front of a
 * grantee the API refuses, which is the one thing a client-side gate exists not to do.
 *
 * DELIBERATELY NOT `canRunDesignWorkshops` either: that admits a DESIGNER, and a designer may not
 * read this even for a workshop they created.
 */
internal fun mayReadWorkshopProvenance(user: UserDto?): Boolean =
    user != null && FieldPermissions.isAdmin(user)

/**
 * The sentence for a failed read, built from the status and the server's own words.
 *
 * Reading the error body CONSUMES it, so `apiErrorMessage` may be called exactly once per failure —
 * which is why this is one function rather than a status check followed by a message lookup at the
 * call site. It falls back to `HttpException.message`, the literal "HTTP 403 Forbidden", when the
 * body carries no `detail`; that is a status line and not a sentence, so it is filtered out here
 * rather than shown to an administrator — the same filter [WorkshopViewersScreen] applies.
 *
 * The 403 arm is written for the person who would meet it. This client does not issue the request
 * unless it believes the account may have it, so a 403 here means the account was demoted between
 * the cached user being read and the request being answered — a real state, and one whose next move
 * is not "try again".
 */
private fun Throwable.provenanceFailure(): String {
    val status = (this as? HttpException)?.code()
    val served = apiErrorMessage("").takeIf { it.isNotBlank() && !it.startsWith("HTTP ") }
    return when (status) {
        // The server's own refusal is a good sentence and already names the next move ("Ask a master
        // admin if you need it for an audit."), so it is preferred over anything invented here.
        403 -> served
            ?: "Comparing a workshop against the shared records is an admin view. Ask a master " +
                "admin if you need it for an audit."
        // The workshop is gone, or this account cannot see it. `load_workshop_or_404` deliberately
        // admits admins to SOFT-DELETED workshops — an audit of who wrote what is most needed on a
        // record somebody deleted — so a 404 reaching an admin means the row is not there at all.
        404 -> "This workshop is not on the server. Nothing has been deleted from this phone."
        // NOT AN HTTP ANSWER AT ALL: no signal, a dropped socket, a body this build cannot parse.
        // Nothing was refused and nothing was found missing — the request never got an answer — so
        // the sentence says what is actually known, which is that the phone could not reach the
        // repository. Naming it as a missing workshop would send an admin looking for a deletion
        // that did not happen.
        null -> "This phone could not reach the repository, so there is nothing to compare against " +
            "yet. The comparison is a live read of the shared records and cannot be held offline."
        else -> served ?: DW_PROVENANCE_LOAD_FAILED
    }
}
