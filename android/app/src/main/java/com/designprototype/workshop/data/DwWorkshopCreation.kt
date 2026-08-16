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
