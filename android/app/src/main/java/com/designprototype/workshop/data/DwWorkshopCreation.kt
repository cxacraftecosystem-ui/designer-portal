package com.designprototype.workshop.data

/**
 * WHO MAY START A DESIGN WORKSHOP — and why this phone has to know it without asking.
 *
 * ── THE RULE ──────────────────────────────────────────────────────────────────────────────────
 *
 * A DESIGNER MAY NOT CREATE ONE. Admins and the master admin may. Everything else a designer does
 * inside a workshop is untouched: open one they created or were granted, fill all 22 stages, add
 * artisans, products, processes, tools and interviews, capture photographs and dictation, generate
 * and submit the report. [DW_WORKSHOP_CREATOR_ROLES] is byte-for-byte
 * `deps.DESIGN_WORKSHOP_CREATOR_ROLES` and `permissions.ts::DESIGN_WORKSHOP_CREATOR_ROLES`.
 *
 * The reason, as the server states it: a design workshop is not a record, it is a CONTAINER for a
 * fortnight of them, and it is the unit the ministry indexes, funds and audits. A sanction order
 * authorises a workshop in a named cluster, and the admin holding that order is the person who knows
 * one exists. Left open to designers it produced duplicates of the same real workshop under three
 * spellings of its title, each holding part of one fortnight's fieldwork, with nothing in the product
 * able to merge them.
 *
 * ── WHY THIS FILE EXISTS ON ANDROID AT ALL, WHICH IS THE WHOLE POINT ──────────────────────────
 *
 * `POST /design-workshops` is the load-bearing gate and it always was. This client ALREADY refused a
 * 403 correctly and wrote nothing to the device when it got one — `classifyCreate` in
 * `WorkshopListScreen` has done that since the create-outcome split, and its comment says exactly
 * why: "A local draft would be a promise this app cannot keep."
 *
 * **BUT THAT REFUSAL NEEDS A CONNECTION, AND THE COURTYARD DOES NOT HAVE ONE.** With no signal the
 * create fails as transient, `CreateOutcome.Local` is returned by design, a `local-` draft is minted,
 * and the designer fills twenty-two stages over a fortnight. The first time the phone reaches signal,
 * `POST /design-workshops` answers 403 and it answers 403 for ever. That is a designer discovering at
 * sync that the fortnight in their hand can never be accepted, which is exactly the failure this rule
 * must not cause — and it is worse than the rule is worth.
 *
 * So the decision is made from the CACHED ROLE, before the network is consulted and before anything
 * is written to disk. The phone already holds the signed-in user; a rule it can only evaluate after a
 * round trip is a rule it cannot evaluate when it matters.
 *
 * **THIS IS NOT THE SECURITY BOUNDARY AND MUST NEVER BE REWRITTEN AS THOUGH IT WERE.** The server is.
 * This exists so a designer finds out in the courtyard rather than a fortnight later.
 */

/** Byte-for-byte `deps.DESIGN_WORKSHOP_CREATOR_ROLES`. A SET, like every design-workshop rule here. */
val DW_WORKSHOP_CREATOR_ROLES = setOf("ADMIN", "MASTER_ADMIN")

/**
 * The refusal, in ONE place because it is said on four surfaces on this handset alone — the create
 * dialog, the list header, the empty state, and the sync engine's permanent failure.
 *
 * Byte-for-byte `deps.DESIGN_WORKSHOP_CREATE_REFUSAL` and its web twin. A refusal that names a
 * different next move depending on where you met it is not a rule, it is three rumours.
 *
 * IT NAMES THE NEXT MOVE, which is not decoration. Somebody reading this is standing in a courtyard
 * with participants in front of them; "forbidden" tells them to stop working, and the truth is that
 * everything they came to do still works as soon as an admin has opened the workshop.
 */
const val DW_WORKSHOP_CREATE_REFUSAL =
    "Only admins and the master admin can start a new design & prototype workshop. Ask an admin to " +
        "create it for your cluster and give you access — you can then fill in all 22 stages, add " +
        "artisans, products and photographs, and generate the report exactly as before. Any workshop " +
        "you already have access to is open to you now."

/**
 * May this ROLE start a design workshop?
 *
 * Takes the role string rather than a `UserDto` so the rule can be asked by the data layer, which
 * has no business importing a UI type — and so the tri-state below can pass a role it may not have.
 * `FieldPermissions.canCreateDesignWorkshops` is the typed front door for screens.
 */
fun canCreateDesignWorkshops(role: String?): Boolean = role in DW_WORKSHOP_CREATOR_ROLES

/**
 * May a session in this state MINT a workshop that exists only on this device?
 *
 * ── THREE STATES, AND "NOT TOLD YET" IS ALLOWED ───────────────────────────────────────────────
 *
 * Exactly as ownership has three: nobody has said ([known] false), signed out (known, no role), and
 * signed in as somebody. Refusing while nobody has said would block an ADMIN in the second between
 * the app opening and the cached user being read — a refusal for a rule that does not apply to them,
 * which is the sort of false negative that teaches people to ignore refusals altogether.
 *
 * Nothing gets through that window that matters: the create dialog is not offered unless
 * `FieldPermissions.canCreateDesignWorkshops` says so against a real cached user, the sync pass
 * refuses to create while the session may not, and `POST /design-workshops` is the gate that is
 * actually load-bearing.
 *
 * SIGNED OUT IS REFUSED, and that is the state the tri-state is worth having for: signing out does
 * not clear the draft store, so without this a signed-out handset could still write a workshop into
 * `filesDir` that no account owns and no sync pass will ever be able to send.
 *
 * Pure, so the decision is pinned by a JVM test rather than by a device with a courtyard around it.
 */
fun mayMintLocalWorkshop(known: Boolean, role: String?): Boolean {
    if (!known) return true
    return canCreateDesignWorkshops(role)
}

/**
 * Is this draft a workshop that exists ONLY on this device — one that needs a server workshop before
 * any of it can be sent?
 *
 * The question the list asks of every row to decide whether to offer "Move into a workshop", and the
 * question the sync pass asks before it declines to create one. Written as a named function even
 * though it is one comparison, because the two callers must agree and because
 * [WorkshopDraft.remoteId] is not the only id in play — `workshopId` itself is a server id whenever
 * the create landed, which is why [remoteWorkshopIdOf] exists and why this asks IT rather than the
 * field.
 */
fun localDraftNeedsAWorkshop(draft: WorkshopDraft): Boolean = remoteWorkshopIdOf(draft) == null

/**
 * The server id this draft is pointed at, or null while it exists only here.
 *
 * BOTH PLACES ARE CONSULTED, and missing the second is a live bug rather than a nicety: a workshop
 * created online is keyed by the server's own id, so `remoteId` may be null while `workshopId` is
 * already a real record. Asking only `remoteId` would classify that as an orphan draft, offer to
 * "move it into a workshop" it is already in, and — in the sync pass — decline a create that has
 * nothing to create. `WorkshopSync.remoteIdOf` computes exactly this and this is the public twin;
 * they must not disagree.
 */
fun remoteWorkshopIdOf(draft: WorkshopDraft): String? =
    draft.remoteId?.takeIf { it.isNotBlank() }
        ?: draft.workshopId.takeIf { it.isNotBlank() && !isLocalOnlyWorkshop(it) }

/**
 * Must this sync pass DECLINE to bring a workshop into existence, rather than POST it?
 *
 * PURE AND NAMED BECAUSE THIS EXACT DECISION HAS ALREADY BEEN GOT WRONG ONCE — on the web, in the
 * change that introduced it, and the wrong version is not visible by reading it. Written as "refuse
 * whenever this session may not create", it ALSO refused the draft whose create had already landed
 * before the rule shipped: a real workshop on the server that this device merely never saw the answer
 * for. That draft needs no create at all; the pass only has to write the id back. Refusing it would
 * strand an existing workshop behind a permanent failure and tell the designer to go and ask an admin
 * for a workshop they already have.
 *
 * So both facts are named and neither can be left out by accident.
 *
 * @param alreadyOnServer the draft is already pointed at a server workshop — there is nothing to
 *   create, so nothing to refuse, whoever is signed in.
 * @param sessionMayCreate [mayMintLocalWorkshop] for the signed-in session.
 */
fun createMustBeDeclined(alreadyOnServer: Boolean, sessionMayCreate: Boolean): Boolean {
    if (alreadyOnServer) return false
    return !sessionMayCreate
}

/**
 * The permanent, actionable failure a declined create records — never a retry, never silence.
 *
 * A SENTENCE AND NOT A STATUS. The pass cannot fix this and neither can waiting, so recording "the
 * server could not be reached" would be a lie that keeps a designer checking their signal for a
 * fortnight. It names what to do instead, and it names the control on this very screen that does it.
 */
const val DW_WORKSHOP_CREATE_DECLINED_BY_APP =
    DW_WORKSHOP_CREATE_REFUSAL +
        " Nothing on this phone has been deleted or changed: every stage, photograph and recording " +
        "you captured is still here. Once an admin has created the workshop, use “Move into a " +
        "workshop” on this row and the whole fortnight goes up into it on the next pass."

// ---------------------------------------------------------------------------------------------
// WHO THE WORKSHOP IS FOR — several people, and one name on the report
// ---------------------------------------------------------------------------------------------
//
// ── ONE ASK, TWO DIFFERENT QUESTIONS, AND CONFLATING THEM IS THE WHOLE TRAP ───────────────────
//
// "The designer this workshop is for" turned out to be two questions wearing one field:
//
//   WHO MAY OPEN IT — several. A real design workshop is a fortnight worked by two designers
//   alongside a master craftsperson and a reviewing officer, and every one of them has to read the
//   same 22 stages. This is a SECURITY BOUNDARY and not a convenience: a design workshop is
//   visible only to its creator, to admins, and to whoever holds a `DesignWorkshopViewer` row —
//   enforced in the QUERY on the list (`visible_to_clause`) and in the loader on the single read,
//   which refuses with a 404 identical to a nonexistent id so the refusal cannot say whether the
//   workshop is there. A DESIGNER cannot create a workshop at all, so `createdById` never matches
//   for them: the workshops a designer can see are exactly the ones they hold a row on, and
//   nothing else. Naming somebody at create is therefore the whole of how they get in — the create
//   route writes one viewer row per name, in the same call.
//
//   WHOSE NAME IS ON IT — exactly one. Stage 1 and stage 3 declare a SINGLE designer block: one
//   `designerName`, one `designerProfile`, one signature. `seed_designer_prefill` copies ONE
//   `DesignerProfile` in, and `report_meta` feeds the promoted `designerName` into the .docx's
//   `dc:creator`, a single-author field the file format cannot express as a list. So the set has a
//   LEAD, and the lead is a separate answer from the membership.
//
// [dwNamedDesignerTeam] is where the two are separated, and it is pure so the separation can be
// asserted without a phone in somebody's hand. `named_designer_team` in
// `backend/app/services/design_workshops.py` is the same rule on the server and the two must
// agree; `namedDesignerTeam` in `frontend/lib/designWorkshops.ts` is the browser's.
//
// ── THE LEAD IS NEVER THE ACCOUNT THAT PRESSED CREATE ─────────────────────────────────────────
//
// Every account that can reach the create dialog is an ADMIN or the master admin, and with nobody
// named the server seeds the CREATOR's profile — which for an admin opening a workshop on a
// colleague's behalf is the wrong person's name on a ministry document. That is the defect
// `designerUserId` exists to end, so where a body names designers but no lead, the server promotes
// the FIRST NAMED and never the creator. These functions promote the same one, so the sentence the
// admin reads on the form and the row the server writes cannot disagree.

/**
 * The most accounts one create may name as this workshop's designers.
 *
 * **NOT A NUMBER CHOSEN HERE.** It is [DW_VIEWER_LIMIT] — the server's
 * `MAX_DESIGN_WORKSHOP_VIEWERS` — because `POST /design-workshops` and
 * `PUT /design-workshops/{id}/viewers` write the SAME table, and the create route imports its cap
 * from that schema rather than picking a second one. A create that accepted a set the "Designers on
 * a workshop" screen would refuse is one list with two rules, and the admin meets the disagreement
 * as a 422 about a shape after building a selection by hand.
 *
 * The browser spells it as its own literal (`MAX_NAMED_DESIGNERS` in
 * `frontend/lib/designWorkshops.ts`) with a test asserting the value, because nothing links the two
 * numbers there at compile time. Here they are one constant and cannot drift.
 *
 * Nobody reaches it by working: a real workshop is run by four people.
 */
const val DW_MAX_NAMED_DESIGNERS: Int = DW_VIEWER_LIMIT

/**
 * WHO MAY OPEN IT, and WHOSE NAME IS ON IT — the answer to both, resolved together.
 *
 * @property lead the account whose `DesignerProfile` is seeded into stage 1 and stage 3 and whose
 *   name the report carries, or null when nobody was named at all. Always the first of [team].
 * @property team everybody who gets a viewer row, lead first. Empty means "not decided yet", which
 *   is a real and common answer — a workshop is opened in a room on day one and the admin may
 *   genuinely not know yet who will run it.
 */
data class DwNamedDesigners(val lead: String?, val team: List<String>)

/**
 * Resolve the picker's state into the two answers above.
 *
 * ── THE TWO RULES, AND THE CASE THAT MAKES THEM DIFFERENT ─────────────────────────────────────
 *
 * 1. **With designers ticked, the lead must be one of them.** An admin who names a lead and then
 *    UNTICKS them has REMOVED that designer, and the workshop is visible only to the people on it;
 *    re-adding them because [lead] still holds their id would put somebody back on a workshop after
 *    the admin took them off, which is the one direction an access control must never drift. The
 *    first ticked is promoted instead.
 * 2. **With nothing ticked, a lead standing alone IS the team.** Not a hypothesis: a draft written
 *    by a build that predates the multi-select carries a lead and an empty list, and reading rule 1
 *    over it would drop the designer that fortnight was opened for on the day the create finally
 *    went out.
 *
 * Blanks are absent, duplicates collapse, and the ticked order is otherwise preserved.
 *
 * **EMPTINESS IS PYTHON'S**, through [dwNamedDesignerId] — the server folds every id with
 * `strip()`, and Python calls U+00A0 and U+202F whitespace while `Char.isWhitespace` does not. One
 * fold, in one place, for the wire and the disk alike.
 */
fun dwNamedDesignerTeam(chosen: List<String>?, lead: String?): DwNamedDesigners {
    val ticked = LinkedHashSet<String>()
    chosen.orEmpty().forEach { raw -> dwNamedDesignerId(raw)?.let { ticked.add(it) } }
    val preferred = dwNamedDesignerId(lead)
    if (ticked.isEmpty()) {
        return if (preferred == null) {
            DwNamedDesigners(lead = null, team = emptyList())
        } else {
            DwNamedDesigners(lead = preferred, team = listOf(preferred))
        }
    }
    val resolved = preferred?.takeIf { it in ticked } ?: ticked.first()
    return DwNamedDesigners(
        lead = resolved,
        team = listOf(resolved) + ticked.filter { it != resolved },
    )
}

/**
 * The two designer keys of a create body, already folded — or neither.
 *
 * @property designerUserId the LEAD, or null. Unchanged in meaning since the singular picker.
 * @property designerUserIds the whole team, lead first — or null, which is what keeps this key OFF
 *   THE WIRE. `ApiClient.json` sets `explicitNulls = false`, so a null property is omitted from the
 *   body entirely rather than sent as `null`.
 */
data class DwDesignerCreateFields(
    val designerUserId: String?,
    val designerUserIds: List<String>?,
)

/**
 * THE ONE PLACE THAT DECIDES WHAT GOES ON THE WIRE, and the middle answer is why it exists.
 *
 *   NOBODY NAMED  → neither key. The server then behaves exactly as it did before either field
 *                   existed and stage 1 carries whoever created the workshop.
 *   ONE DESIGNER  → `designerUserId` ALONE — byte-for-byte the body this handset has been sending
 *                   since the singular picker shipped.
 *   SEVERAL       → both, lead first. Only here does the new key travel.
 *
 * ── WHY THE ONE-DESIGNER ANSWER IS THE LOAD-BEARING ONE ───────────────────────────────────────
 *
 * `DesignWorkshopCreateBody` is an `APIModel`, which is `extra="forbid"`. An API deployed before
 * `designerUserIds` existed answers **422 `extra_forbidden` to a body that merely CARRIES the
 * key**, whatever is in it — and this app ships separately from the API, so a handset updates when
 * it next sees wifi while the server updates when somebody deploys it. That skew is a live state,
 * not a theoretical one.
 *
 * And on this client it is not a refused request, it is a lost fortnight. A 4xx is never queued,
 * and `WorkshopSync`'s create arm reads a 422 as a REFUSAL: an ordinary offline create — a workshop
 * started in a courtyard with one designer on it — would come back permanently refused for a key
 * the admin never asked for. So the key is sent only when there is genuinely a second designer,
 * which is to say only when the admin has asked for something a server that has never heard of the
 * field could not do anyway.
 *
 * An empty list is NEVER sent. `[]` reads on the wire as "I considered this and the answer is
 * none", which is a different sentence from silence and one the server would have to interpret.
 */
fun dwDesignerCreateFields(chosen: List<String>?, lead: String?): DwDesignerCreateFields {
    val resolved = dwNamedDesignerTeam(chosen, lead)
    return when {
        resolved.team.isEmpty() -> DwDesignerCreateFields(null, null)
        resolved.team.size == 1 -> DwDesignerCreateFields(resolved.lead, null)
        else -> DwDesignerCreateFields(resolved.lead, resolved.team)
    }
}

/**
 * Fold the sheet's answer back into an ORDERED selection.
 *
 * ── WHY A SET IS NOT ENOUGH, ON THIS FIELD SPECIFICALLY ───────────────────────────────────────
 *
 * `SearchableMultiSelectField` hands back a `Set<String>`, and a Set promises no order. That is
 * fine for the fields it was built for — "artisans of selected crafts" is a bag — and it is not
 * fine here, because with no explicit lead the FIRST of the team is the designer whose profile is
 * copied into stage 1 and whose name reaches a ministry document. Whose name that is must not be
 * decided by an iteration order nobody can see.
 *
 * So the screen keeps its own list and this is what maintains it:
 *
 *   - ids already chosen keep the position they had — re-opening the sheet and ticking a fifth
 *     name must not renumber the four already there, and the lead least of all;
 *   - newly ticked ids are appended in [offered] order, which is the order they are DRAWN in (the
 *     server's `name` then `id`, never re-sorted here) — the only order the admin can actually see;
 *   - anything ticked that is not on offer is appended last rather than dropped, because a silent
 *     absence on this field is a designer who cannot open the workshop.
 *
 * Nothing is capped here. [DW_MAX_NAMED_DESIGNERS] is refused on screen with the count, exactly as
 * `WorkshopViewersScreen` refuses an over-long save against the same table — trimming would drop
 * designers the admin ticked and could not see go.
 */
fun dwOrderedDesignerPicks(
    previous: List<String>,
    picked: Set<String>,
    offered: List<String>,
): List<String> {
    val kept = previous.filter { it in picked }
    val already = HashSet(kept)
    val added = offered.filter { it in picked && it !in already }
    already.addAll(added)
    return kept + added + picked.filter { it !in already }
}

/**
 * The picked id the wire and the disk agree on — the trimmed value, or null for "nobody named".
 *
 * ── WHY NULL AND NOT "" ───────────────────────────────────────────────────────────────────────
 *
 * The picker's empty selection means "not decided yet". A body carrying the key with nothing in it
 * reads on the wire as an answer given; the server would fold it itself — `(payload.designerUserId
 * or "").strip() or None` — but the same value is also written to [WorkshopDraft.designerUserId] on
 * the disk, where "" and null would be two spellings of one state for every later pass to disagree
 * about. `ApiClient.json` leaves a null off the wire entirely, so a workshop with nobody named
 * posts the same bytes it posted before this field existed.
 *
 * ── EMPTINESS IS PYTHON'S, NOT KOTLIN'S ───────────────────────────────────────────────────────
 *
 * The server strips with Python's `str.strip()`, which calls the no-break space U+00A0 and the
 * narrow no-break space U+202F whitespace; `Char.isWhitespace` deliberately does not. A value that
 * means "nobody" up there and "somebody" down here is exactly the disagreement this field exists to
 * end, and it is the same choice `dwViewerSearchTerm` makes one screen over. [DwPy.strip] is the
 * shared spelling.
 *
 * MOVED HERE FROM `ui/designworkshop/WorkshopListScreen.kt` when the picker became a multi-select:
 * it decides what reaches the body and the draft, which is a data decision, and
 * [dwNamedDesignerTeam] — which lives in the data layer and cannot import a screen — has to fold
 * every id in the list the same single way.
 */
fun dwNamedDesignerId(picked: String?): String? = DwPy.strip(picked.orEmpty()).ifEmpty { null }

/**
 * Move a workshop that exists only on this device into one that exists on the server.
 *
 * ── THE OTHER HALF OF THE RULE, AND THE REASON SHIPPING IT COSTS NOBODY A FORTNIGHT ───────────
 *
 * The day this rule ships, handsets are already holding workshops started under the OLD rule and not
 * yet synced — a courtyard's worth of stages with no server record. Three answers were possible and
 * two of them are unacceptable:
 *
 *   DELETE THEM. Never. It is somebody's fieldwork, and this store's whole discipline is that
 *   nothing is thrown away — see the quarantine path for a draft written by a future build.
 *
 *   LET THEM SYNC ANYWAY, by exempting drafts created before the rule. That is a permission any
 *   device can grant itself by backdating a file, and it leaves the ministry's index filling with
 *   designer-created workshops for as long as one old handset stays in a drawer.
 *
 *   ADOPT THEM, which is this. The workshop is real and the work in it is real; what it lacks is a
 *   server record it is allowed to have. An admin creates the workshop — which they were always
 *   going to have to do — and the designer points the draft at it. Every stage, photograph and
 *   deletion then syncs into that workshop by the ordinary path.
 *
 * ── THE THREE THINGS THIS CLEARS ARE THE WHOLE CORRECTNESS ARGUMENT ───────────────────────────
 *
 * **[StageDraft.stageSeen], on every stage.** That flag is TRUE on a local-only workshop by its own
 * documented rule — "the workshop has no server record at all, so there is nothing on the server this
 * draft could be missing" — and adoption is the moment that premise expires. It expires HARDER than
 * on a create: the target workshop was made by somebody else, and `POST /design-workshops` has
 * already seeded `workshopSetup` and `workshopPlan` singletons into it. Left standing, the flag makes
 * the first save claim authority — `merge` omitted and `replaceCollections` claimed — and `save_stage`
 * replaces each singleton's `data` wholesale, destroying the seeded designer block in place, under a
 * 200.
 *
 * **[StageDraft.emptiedEntities] and [StageDraft.deletedRowKeys].** These are the dangerous half.
 * They record rows the designer deleted, and they are the ONLY mechanism by which a deletion reaches
 * the server. On a never-synced draft every one of those deletions was of a row that has only ever
 * existed on THIS DEVICE. Carried into an adoption they arm a sweep against a workshop this phone has
 * never read, and `save_stage` deletes rows in the target belonging to whoever has been working in
 * it. Clearing them is not tidiness; it is the difference between adopting a workshop and emptying
 * one.
 *
 * **The stage SIGNATURES and the create failure.** A signature is "the body the server last accepted
 * for this stage", and the server in question was a different workshop — or no workshop at all.
 * Kept, `isFullySynced` would report a freshly adopted fortnight as already backed up and the pass
 * would send none of it. This is the failure that loses the fieldwork silently, which is why it is
 * cleared here rather than left to the pass to notice.
 *
 * ── WHAT IS DELIBERATELY *NOT* CLEARED ────────────────────────────────────────────────────────
 *
 * The values, the custom answers, the notes, the media, the completion marks, `createdAt` and the
 * title. Those are the fieldwork and the whole point of adopting rather than deleting. `updatedAt`
 * moves, because re-pointing a draft is an act somebody performed and the list sorts by it.
 *
 * PURE, so both paragraphs above can be asserted with no filesystem, no API and no Postgres row.
 */
fun adoptedIntoWorkshop(draft: WorkshopDraft, remoteId: String, now: String): WorkshopDraft =
    draft.copy(
        remoteId = remoteId,
        updatedAt = now,
        stages = draft.stages.mapValues { (_, stage) ->
            stage.copy(
                stageSeen = false,
                emptiedEntities = emptyList(),
                deletedRowKeys = emptyList(),
            )
        },
        sync = draft.sync.copy(
            // The refusal that sent the designer here has been acted on and is no longer true.
            createFailure = null,
            createFailedAt = null,
            createSkewRun = null,
            lastError = null,
            // Never synced INTO THIS WORKSHOP, whatever happened before. See the paragraph above.
            lastSuccessAt = null,
            stages = emptyMap(),
        ),
    )

/**
 * THE ONE SENTENCE ABOUT THE DESTINATION LIST, or null when it is the whole answer.
 *
 * ── WHY A LIST OF WORKSHOPS NEEDS A CAVEAT AT ALL, AND WHY IT NEEDS ONE MORE NOW ──────────────
 *
 * "Move into a workshop" is one-way and unrepeatable ([localDraftNeedsAWorkshop] guards it), so the
 * picker beneath this sentence is the single most consequential control on the list screen: the
 * wrong choice files a fortnight of one cluster's fieldwork inside another cluster's record and
 * nothing in this app can move it back. A destination list that is quietly a PREFIX is therefore
 * rule 10 at its most expensive — a designer who cannot find the workshop an admin made an hour ago
 * concludes the admin never made it, and the honest reading of an absence is never "it does not
 * exist".
 *
 * There are three ways the list can be short and they are three different facts with three
 * different next moves, which is why this is a decision and not a layout:
 *
 *   NO CONNECTION — it holds only what this phone happens to have opened before. The workshop an
 *   admin created today may simply not be on the device yet.
 *   NARROWED BY THE SEARCH BOX — the workshops screen filters its rows server-side, and this picker
 *   is fed from those rows, so a term typed to FIND the local draft also hides every workshop that
 *   does not match it. Nothing on this dialog shows that box, which is what makes it worth a
 *   sentence: the cause is on the screen underneath.
 *   THE WALK STOPPED SHORT — `DesignWorkshopPageWalk` bounds itself at [DW_LIST_MAX_PAGES], and a
 *   connection that dropped mid-walk ends it too, so the rows gathered can be fewer than the server
 *   says this account may see.
 *
 * ORDERED WORST FIRST AND ONLY ONE IS EVER SHOWN. Offline leads because it is the only one under
 * which the list is not the server's answer at all. The search comes next because it is the most
 * recent thing the designer did and the fastest to undo, and because a narrowed request makes a
 * truncated walk far less likely in the first place.
 *
 * Pure, so all four states are pinned by a JVM test rather than by somebody with a phone, a
 * courtyard and a repository of 500 workshops.
 */
fun dwAdoptCandidateNotice(
    offline: Boolean,
    searched: Boolean,
    listTruncated: Boolean,
): String? = when {
    offline ->
        "The server could not be reached, so this list holds only the workshops this phone already " +
            "knows about. A workshop created for you today may not be here until you open this " +
            "list with a connection."
    searched ->
        "This list is narrowed by what you typed in the search box on the workshops screen. If the " +
            "workshop you are moving this into is not here, close this, clear that box, and open " +
            "it again."
    listTruncated ->
        "There are more workshops than this screen could read in one go, so this list may not hold " +
            "all of them. Close this, search the workshops screen for the one you want, and open " +
            "it again."
    else -> null
}

/**
 * There is no workshop to move this into — said differently depending on what that means.
 *
 * ── THE ANSWER CHANGED WHEN THE LIST BECAME SCOPED, AND SO DID THE NEXT MOVE ──────────────────
 *
 * A design workshop is visible only to the designers NAMED ON IT, to admins, and to its creator. So
 * "no workshops here" is no longer only "none exist for you"; it is very often "the admin created
 * it and did not tick your name", and the two need the same sentence because from here they are
 * indistinguishable and the move is the same one: get named on it. The workshop CODE is the second
 * door and it is already built — an admin who has the workshop open can hand out a join card, and
 * redeeming one writes the same viewer row the create would have written.
 *
 * OFFLINE IS ITS OWN ANSWER because the claim is different: nothing has been read, so this says
 * nothing at all about whether a workshop exists. Telling a designer to go and ask an admin for a
 * workshop the admin already made is how a person walks up a hill for nothing.
 */
fun dwAdoptNoCandidatesMessage(offline: Boolean): String = if (offline) {
    "There are no workshops on this phone to move it into, and the server could not be reached — " +
        "so this list may not be the whole story. Ask an admin to create the workshop, then open " +
        "this list once with a connection and try again."
} else {
    "No workshop on the server is open to this account yet. A design workshop is visible only to " +
        "the designers named on it, so ask an admin to create one for your cluster and name you " +
        "as one of its designers — or to send you its join card, which lets you in yourself from " +
        "“Workshop access”. It appears here and this draft can then be moved into it. Nothing on " +
        "this phone is at risk in the meantime."
}
