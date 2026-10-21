package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

import com.designprototype.workshop.data.UserDto

/*
 * Design Workshop — one navigation model for the phone, mirroring the web.
 *
 * WHAT THE WEB DOES (frontend/components/DynamicIslandNav.tsx): a floating "dynamic island" pill
 * pinned to the top of the viewport. Dashboard and Walkthrough sit loose in the bar; everything else
 * hangs off four hover dropdowns — Record, Browse, Admin, Account — and a full sheet repeats the
 * same filtered list for keyboard users. Each destination carries its OWN role predicate, and an
 * entry that fails it is NOT RENDERED (never rendered disabled), so the menu can only ever offer
 * what the API would actually serve.
 *
 * WHAT THIS DOES, AND WHAT WAS ADAPTED. The information architecture is copied exactly: the same
 * entries, the same four groups, the same labels, the same ordering, the same predicates, the same
 * "entitlement first, admin view second" rule. What is NOT copied is the floating pill itself.
 * It is a POINTER interface — its dropdowns open on hover and close on mouse-leave, and it costs a
 * permanent strip of vertical space. On a phone there is no hover, and the app already has a
 * right-anchored ModalNavigationDrawer that the whole app reaches through one thumb-friendly icon.
 * So the pill's CONTENT moves into that drawer, rendered as the same four labelled groups with the
 * two standalone roots pinned above them — which is precisely what the web's own full sheet is,
 * except grouped rather than a flat two-column grid. Nothing is added, removed, or renamed.
 *
 * Three entries deliberately differ from what the Android drawer used to show; see
 * [NavDestination] for the per-entry reasoning.
 */

// ---------------------------------------------------------------------------------------------
// Capability rules — the Kotlin mirror of frontend/lib/permissions.ts and backend/app/core/deps.py
// ---------------------------------------------------------------------------------------------

/**
 * The ELEVEN-tier ladder and the capability predicates the nav gates on.
 *
 * It said "six-tier" through the whole life of DESIGNER and until INSPECTOR landed on 2026-08-27,
 * and "eight-tier" until the three directorate tiers landed on 2026-09-13.
 * Nothing counts a comment; `backend/tests/test_role_ladder_parity.py` counts the two maps below.
 *
 * Wrapped in an object rather than left as top-level functions on purpose: `MainActivity.kt` already
 * declares file-private `roleRank`/`isAdminUser`/`canCreateRecords` helpers, and top-level twins here
 * would read like a second, competing source of truth. These MUST agree with the server in both
 * directions — a rule stricter than the backend hides a screen from somebody entitled to it, a looser
 * one lets them fill in a form and eat a 403 on save.
 */
object FieldPermissions {
    const val RANK_CROWDSOURCE_VOLUNTEER = 10
    const val RANK_FIELD_CONTRIBUTOR = 20
    const val RANK_RESEARCHER = 30

    /**
     * 35 — in the gap the original tens deliberately left, and NOT a renumbering of the tiers around
     * it.
     *
     * A designer runs a workshop and signs the report; a researcher documents what they find. The
     * server put the tier at 35 (`deps.ROLE_RANK`) and the web at 35 (`lib/permissions.ts`) for that
     * reason, and this constant exists so the phone agrees rather than approximates. Renumbering
     * instead — pushing PROFESSOR to 45, say — would silently change the meaning of every stored
     * comparison in this file at once, and the first symptom would be a professor who can no longer
     * open a screen they opened yesterday.
     */
    const val RANK_DESIGNER = 35

    /**
     * 37 — the INSPECTOR tier, added 2026-08-27. Inspects and reviews a designer's work without
     * running workshops.
     *
     * THE MIDDLE OF THE FREE BAND, not the bottom of it. 36-39 were all unused; 37 leaves a gap on
     * both sides so a later tier can be inserted either between designer and inspector or between
     * inspector and professor without renumbering — the same reasoning that put [RANK_DESIGNER] at
     * 35. `deps.ROLE_RANK` and `lib/permissions.ts` both say 37, and this constant exists so the
     * phone agrees rather than approximates.
     *
     * WHAT IT CHANGES ON THIS SCREEN, AND WHAT IT DOES NOT. Nothing about design workshops:
     * [canRunDesignWorkshops] is set membership and INSPECTOR is not in the set, deliberately — an
     * inspector does not sign a report. What 37 DOES do is put an inspector above a designer in the
     * review ladder, which is the reason the tier exists at all.
     */
    const val RANK_INSPECTOR = 37
    const val RANK_PROFESSOR = 40

    /**
     * 42 — the first DIRECTORATE tier, added 2026-09-13, and the first rank ever inserted ABOVE
     * PROFESSOR on this ladder.
     *
     * WHY THAT MATTERS HERE RATHER THAN BEING ONE MORE NUMBER. Every earlier insert
     * ([RANK_DESIGNER] 35, [RANK_INSPECTOR] 37) went in below 40, so it bought review authority and
     * nothing else. 42 clears every `>= RANK_PROFESSOR` predicate in this object at once —
     * [canManageCrafts], [canManageWorkshops], [canManageUsers] — and `MainActivity`'s
     * `canSetRecordStatus`. The server's `deps.ROLE_RANK` carries the full argument for the number;
     * this constant exists so the phone agrees rather than approximates.
     */
    const val RANK_ASSISTANT_DIRECTOR = 42

    /** 45 — the middle directorate tier. See [RANK_ASSISTANT_DIRECTOR]. */
    const val RANK_REGIONAL_DIRECTOR = 45

    /**
     * 48 — the senior directorate tier, and NOT AN ADMIN.
     *
     * [isAdmin] below is `rank(user.role) >= RANK_ADMIN`, i.e. 50, so this tier fails it — which
     * matches the server, where `is_admin` is set membership on MASTER_ADMIN and ADMIN and this
     * token is in neither. The two agree by arithmetic here and by construction there; a tier added
     * above 50 would break that agreement silently, and this drawer would offer an admin-only
     * destination to somebody the API refuses.
     *
     * ACCOUNT PROVISIONING IS A SEPARATE QUESTION, and since 2026-10-09 this tier passes it: the
     * server's `ACCOUNT_PROVISIONER_ROLES` lets it create accounts at or below its own tier, and set
     * temporary passwords, issue password links and correct names and emails on accounts below it.
     * Nothing here tests for it —
     * ministry and admin features are web-only by design — so [isAdmin] stays the right gate for
     * every admin surface this client has.
     */
    const val RANK_MINISTRY_ADMIN = 48

    const val RANK_ADMIN = 50
    const val RANK_MASTER_ADMIN = 60

    private val RANKS = mapOf(
        "CROWDSOURCE_VOLUNTEER" to RANK_CROWDSOURCE_VOLUNTEER,
        "FIELD_CONTRIBUTOR" to RANK_FIELD_CONTRIBUTOR,
        "RESEARCHER" to RANK_RESEARCHER,
        "DESIGNER" to RANK_DESIGNER,
        "INSPECTOR" to RANK_INSPECTOR,
        "PROFESSOR" to RANK_PROFESSOR,
        "ASSISTANT_DIRECTOR" to RANK_ASSISTANT_DIRECTOR,
        "REGIONAL_DIRECTOR" to RANK_REGIONAL_DIRECTOR,
        "MINISTRY_ADMIN" to RANK_MINISTRY_ADMIN,
        "ADMIN" to RANK_ADMIN,
        "MASTER_ADMIN" to RANK_MASTER_ADMIN
    )

    /**
     * Byte-for-byte the server's `ROLE_LABELS` (and the web's `ROLE_LABELS`).
     *
     * A role missing from this table falls back to [label]'s `role.orEmpty()`, which renders the raw
     * `UPPER_SNAKE` token — so a designer's drawer would have read "· DESIGNER" beside their name
     * while every other tier read as English. That is the visible half; the invisible half is that
     * the same omission from [RANKS] ranks the role 0, which is BELOW a crowdsource volunteer, and
     * the menu then hides every destination from the one tier the feature was built for.
     */
    private val LABELS = mapOf(
        "CROWDSOURCE_VOLUNTEER" to "Crowdsource Volunteer",
        "FIELD_CONTRIBUTOR" to "Field Contributor",
        "RESEARCHER" to "Researcher",
        "DESIGNER" to "Designer",
        // Both words: the stored token is INSPECTOR because `canReview` already owns "review" in
        // its relational sense. Byte for byte the server's ROLE_LABELS["INSPECTOR"].
        "INSPECTOR" to "Inspector / Reviewer",
        "PROFESSOR" to "Professor",
        "ASSISTANT_DIRECTOR" to "Assistant Director",
        "REGIONAL_DIRECTOR" to "Regional Director",
        "MINISTRY_ADMIN" to "Ministry Admin",
        "ADMIN" to "Admin",
        "MASTER_ADMIN" to "Master Admin"
    )

    fun rank(role: String?): Int = RANKS[role] ?: 0
    fun label(role: String?): String = LABELS[role] ?: role.orEmpty()

    /**
     * `is_admin` — admin and master admin.
     *
     * A FLOOR WHERE THE SERVER IS A SET. `deps.is_admin` is membership of {MASTER_ADMIN, ADMIN};
     * this is `rank >= 50`. The two agree for every tier that exists, because the three directorate
     * tiers added 2026-09-13 are 42/45/48 and all below 50 — MINISTRY_ADMIN included, whose token
     * says admin and which is not one. A tier added ABOVE 50 would make these two disagree and would
     * hand it the whole admin surface on this client alone. If that day comes, this becomes a set.
     */
    fun isAdmin(user: UserDto): Boolean = rank(user.role) >= RANK_ADMIN

    /** `is_master_admin`. */
    fun isMasterAdmin(user: UserDto): Boolean = user.role == "MASTER_ADMIN"

    /**
     * `deps.CONTENT_ADMIN_ROLES` (owner's ruling, 2026-10-20): ADMIN, MASTER_ADMIN and MINISTRY_ADMIN
     * add, edit and DELETE every record, media file and other managed content type. Every other role
     * adds and edits and never deletes. SET MEMBERSHIP, never rank >= 50 — and not [isAdmin], which
     * keeps its two members for accounts and governance. The web's `CONTENT_ADMIN_ROLES` and the
     * documentation-portal's twin carry the same three; `test_role_ladder_parity.py` holds them.
     */
    val CONTENT_ADMIN_ROLES: Set<String> = setOf("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")

    fun isContentAdmin(user: UserDto?): Boolean = user?.role?.let { it in CONTENT_ADMIN_ROLES } == true

    /**
     * `require_record_creator` — OPENING an artisan, product, process, tool or interview. Researcher
     * and above, matching `can_create_records` and the web's `canCreateRecords`.
     *
     * The two tiers below POPULATE records instead of opening them, and none of what they do is
     * gated by this: uploading media, answering an existing interview and commenting all stay open.
     * So hiding a "Record …" entry from them never hides a contribution path.
     */
    fun canCreateRecords(user: UserDto): Boolean = rank(user.role) >= RANK_RESEARCHER

    /**
     * `require_craft_manager` — Professor and above, RANK ALONE.
     *
     * The `canManageCrafts` column is deliberately not consulted. The server stopped reading it
     * (`can_manage_crafts`, deps.py) because a per-user grant that lifted a researcher over the
     * taxonomy was invisible in the role column; ORing it in here would put "Add craft" in the menu
     * of somebody the API refuses, which is the one thing this table exists not to do.
     */
    fun canManageCrafts(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    /**
     * EDIT a workshop — `require_workshop_manager`, Professor and above, rank alone; see
     * [canManageCrafts].
     *
     * IT COVERED CREATING ONE TOO UNTIL 2026-09-16. `POST /workshops` is now
     * `require_workshop_opener` (a MINISTRY_ADMIN floor) because designers participate in workshops
     * and the ministry and admin tiers open them; `UserDto.canOpenAWorkshop()` in `MainActivity.kt`
     * is that predicate. This one is the edit gate, and it is deliberately looser — a professor
     * correcting the date of a workshop somebody else opened is ordinary repository work.
     */
    fun canManageWorkshops(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    /** `require_professor` on GET/PATCH /users. */
    fun canManageUsers(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    /**
     * Who may run a design & prototype workshop. Named explicitly rather than derived from the rank
     * ladder, and that is the whole point — see [canRunDesignWorkshops].
     *
     * Byte-for-byte `deps.DESIGN_WORKSHOP_ROLES` and the web's own set.
     */
    private val DESIGN_WORKSHOP_ROLES = setOf(
        "DESIGNER",
        // The three directorate tiers, added 2026-09-14 on the owner's ruling. They already read
        // every workshop; this is the WRITE. STILL A SET AND NOT A FLOOR — PROFESSOR sits between
        // them and DESIGNER and is deliberately still out.
        //
        // INSPECTOR IS DELIBERATELY ABSENT: asked for alongside these three and excluded, because an
        // inspector in the write set would author the stages it later reviews.
        "MINISTRY_ADMIN",
        "REGIONAL_DIRECTOR",
        "ASSISTANT_DIRECTOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    /**
     * `can_run_design_workshops` — create and edit a design & prototype workshop, generate its
     * report, and own a designer profile.
     *
     * THE ONE CAPABILITY IN THIS OBJECT THAT IS NOT A RANK THRESHOLD, and it is deliberate. Every
     * other predicate here reads "this tier and above", because the ladder is inclusive: a professor
     * can do everything a researcher can. This one is a SET — Designer, Admin, Master Admin — which
     * means a PROFESSOR cannot run one even though they outrank a designer. A design workshop is a
     * fortnight of a named designer's work that ends in a document submitted to a ministry under
     * their name, and being senior to a designer is not the same thing as being one.
     *
     * IT WAS WRITTEN HERE AS `rank(user.role) >= RANK_DESIGNER`, which is the same answer for six of
     * the ELEVEN roles in [RANKS] and the wrong one for FIVE of them: INSPECTOR (37), PROFESSOR
     * (40) and the three directorate tiers (42/45/48), all of which clear the 35 threshold and are
     * all outside [DESIGN_WORKSHOP_ROLES]. Counted
     * 2026-08-27 by evaluating `rank(role) >= RANK_DESIGNER` and `role in DESIGN_WORKSHOP_ROLES`
     * over every key of [RANKS]; it said "six of the seven" while the ladder was seven tiers and
     * PROFESSOR was the only wrong cell. A professor signing in on the phone was
     * offered "Questionnaires" and "My designer profile" in the drawer, and both 403 the instant they
     * open; the profile screen went further and enabled the EDIT form, so a professor could fill in
     * the display name, designation, institution and biography a report is signed with, press save,
     * and be refused. The web hides both. A non-monotonic rule is far easier to let drift than a
     * threshold, which is exactly why the server names its set out loud — and why the ladder must not
     * be used to approximate it.
     *
     * Deliberately NOT [canCreateRecords] either, in the other direction: a RESEARCHER outranks
     * nothing here and the API refuses every design-workshop write they attempt.
     */
    fun canRunDesignWorkshops(user: UserDto): Boolean = user.role in DESIGN_WORKSHOP_ROLES

    /**
     * Who may READ design-workshop stage data through a RESEARCH surface — the `designWorkshops`
     * bucket of `GET /search`, and the design-workshop tables in View Data.
     *
     * Byte-for-byte `deps.DESIGN_WORKSHOP_DATA_VIEW_ROLES` and the web's own set
     * (`lib/permissions.ts`). The owner's ruling of 2026-08-30, verbatim: *"professor can view data
     * for design workshops as well, admins and master admins can download and view it too."*
     *
     * ── IT IS ALMOST THE OPPOSITE SET TO [DESIGN_WORKSHOP_ROLES], AND THAT IS NOT A CONTRADICTION ─
     *
     * This one INCLUDES professor and EXCLUDES designer; that one does the reverse. They are
     * different acts. Running a workshop is WRITING inside somebody's fortnight of work. This is
     * READING a table of what a corpus of workshops recorded. A designer is not here because a
     * designer reaches their own workshops through a per-record grant, whereas this predicate opens
     * EVERY workshop in the repository to a research reader — the two doors are different sizes.
     * `docs/DECISION-design-workshop-data-in-view-data.md` §2.2 carries the argument in full.
     *
     * A SET AND NOT `rank(user.role) >= RANK_PROFESSOR`, for the reason §2.3 gives: the tier
     * immediately below professor is INSPECTOR (37), somebody who inspects ONE workshop under a
     * grant, and a floor would hand them every workshop in the repository the day a rank is
     * renumbered. The owner named three roles, not a threshold.
     *
     * WHAT IT GATES HERE IS THE CHIP, NOT THE ANSWER. The server drops the bucket for a caller
     * without this and names it in `typesRefused`; this predicate only decides whether the handset
     * OFFERS the tick box, so a researcher is not given a control whose every use is refused.
     */
    fun canViewDesignWorkshopData(user: UserDto): Boolean =
        user.role in DESIGN_WORKSHOP_DATA_VIEW_ROLES

    /**
     * Who may READ design-workshop stage data on a research surface. See [canViewDesignWorkshopData].
     *
     * Byte-for-byte `deps.DESIGN_WORKSHOP_DATA_VIEW_ROLES`.
     */
    private val DESIGN_WORKSHOP_DATA_VIEW_ROLES =
        setOf(
            "PROFESSOR",
            // The three directorate tiers joined on 2026-09-13. Still a SET and not a floor: the
            // tier just below professor is INSPECTOR (37), who reaches one workshop under a grant.
            "ASSISTANT_DIRECTOR",
            "REGIONAL_DIRECTOR",
            "MINISTRY_ADMIN",
            "ADMIN",
            "MASTER_ADMIN",
        )

    /**
     * `can_create_design_workshops` — START a new design & prototype workshop. ADMIN AND ABOVE ONLY.
     *
     * ── THIS IS NOT [canRunDesignWorkshops] AND THE DIFFERENCE IS ONE ROLE ───────────────────────
     *
     * A DESIGNER passes that predicate and fails this one. They may do everything INSIDE a workshop —
     * all 22 stages, the artisans, the products, the photographs, the dictation, the report — and may
     * no longer open one. The reason is on [com.designprototype.workshop.data.canCreateDesignWorkshops]
     * in full; the short of it is that a workshop is the unit a ministry indexes and funds, and
     * creating one is an administrative act rather than a capture act.
     *
     * DELIBERATELY NOT USED TO GATE THE DESIGN-WORKSHOP DESTINATION. Narrowing the drawer entry to
     * this would lock a designer out of their own fieldwork in order to enforce a rule about a
     * button. The web made the same call and says so beside its route guard.
     *
     * The rule itself lives in the DATA layer, not here, because the offline draft store has to ask
     * it too and must not import a UI type to do so. This is the typed front door for screens.
     */
    fun canCreateDesignWorkshops(user: UserDto): Boolean =
        com.designprototype.workshop.data.canCreateDesignWorkshops(user.role)

    /**
     * `assert_inspection_surface` — READ a design & prototype workshop in order to inspect and
     * review it. **ON THIS HANDSET, THE INSPECTOR / REVIEWER TIER AND NOBODY ELSE — ADMINS INCLUDED.**
     *
     * ── THIS IS THE ONE PREDICATE IN THIS OBJECT WHOSE REFUSAL IS NOT MONOTONIC IN RANK ──────────
     *
     * [canRunDesignWorkshops] is already a set rather than a floor, but its set still contains both
     * admin tiers, so every one of these predicates has so far had the property that a
     * MASTER_ADMIN passes whatever a lesser tier passes. This one breaks that outright: a
     * MASTER_ADMIN at rank 60 is REFUSED where an INSPECTOR at 37 is admitted.
     *
     * AND IT IS NARROWER THAN THE SERVER, ON PURPOSE. Until 2026-10-09 the server refused an admin
     * here by name; the owner's ruling of that date lets a Ministry Admin, an admin and the master
     * admin be APPOINTED to inspect a workshop, so `assert_inspection_surface` now admits those tiers
     * too, each scoped by the inspection rows it holds. This handset was not taught that ruling —
     * ministry and admin work is web-only (`docs/DECISION-ministry-surfaces-web-only.md`) — so an
     * admin appointed to inspect reads that workshop on the web or through Design workshops, and this
     * predicate stays the tier set: the fail-closed direction.
     *
     * So reading the ladder for this row gives the wrong answer every time, and `rank(user.role) >=
     * RANK_INSPECTOR` would be wrong for SIX of the eleven tiers in [RANKS] — PROFESSOR,
     * ASSISTANT_DIRECTOR, REGIONAL_DIRECTOR, MINISTRY_ADMIN, ADMIN and MASTER_ADMIN all clear 37 and
     * are all refused here. Getting it wrong that way would offer the menu entry to every admin in the
     * repository, onto a surface this handset leaves to the web — and the professor and the directorate
     * posts onto a 403. The web hit the same fork (and since 2026-10-09 answers it with the server's
     * wider set), and `docs/PERMISSIONS.md` §5 says so explicitly because §2's ladder gives the wrong
     * answer for this row.
     *
     * WHAT AN ADMIN GETS ON THIS HANDSET INSTEAD is the screen that appoints inspectors, which hangs
     * off a workshop's stage index — and the refusal an inspector-less account meets names it.
     *
     * The rule itself lives in the DATA layer, matching [canCreateDesignWorkshops], because the
     * inspection screens have to ask it from a place that must not import a UI type. This is the
     * typed front door for the nav, and it adds nothing.
     */
    fun canInspectDesignWorkshops(user: UserDto): Boolean =
        com.designprototype.workshop.data.canInspectDesignWorkshops(user.role)

    /**
     * `can_manage_designer_roster` — add, suspend and restore designers on the roster that gates
     * their sign-in. Admin and above.
     *
     * READ as well as write, which is why it is `isAdmin` and not something looser. The roster is a
     * list of named individuals and their institutional standing, so it is not a thing a peer should
     * be able to browse — and the server refuses the GET on the same rule, so a looser predicate here
     * would only put a menu entry in front of a 403.
     */
    fun canManageDesignerRoster(user: UserDto): Boolean = isAdmin(user)

    /**
     * `can_manage_access_roster` — decide who may sign in to this application AT ALL, and work the
     * queue of people asking to. Admin and above.
     *
     * THE SAME TIER AS THE DESIGNER ROSTER, for a stronger version of the same reason: this list is
     * every address that may reach the product, so whoever can edit it can lock everybody else out,
     * including each other.
     *
     * READ IS GATED WITH WRITE. The pending queue is a list of people who tried to get in — somebody's
     * colleagues, applicants and former staff — so browsing it is administrative work as much as
     * deciding it is, and the server refuses the GET on the same rule.
     *
     * A SEPARATE FUNCTION FROM [canManageDesignerRoster] although both are `isAdmin` today: they
     * mirror two different server predicates over two different tables, and collapsing them would
     * mean that the day either server gate moves, the other client surface moves with it in silence.
     */
    fun canManageAccessRoster(user: UserDto): Boolean = isAdmin(user)

    /** `require_reviewer` — anyone with somebody beneath them on the ladder, or a grant. */
    fun canReview(user: UserDto): Boolean =
        rank(user.role) >= RANK_FIELD_CONTRIBUTOR || user.canReview

    /** `require_dataset_downloader` — Professor and above, or an explicit grant. */
    fun canDownloadDataset(user: UserDto): Boolean =
        rank(user.role) >= RANK_PROFESSOR || user.canDownloadDataset
}

// ---------------------------------------------------------------------------------------------
// The model
// ---------------------------------------------------------------------------------------------

/**
 * The web's `NAV_GROUPS`, in render order. "Account" sits last because it holds what belongs to the
 * PERSON rather than to the repository — their own settings and their feedback.
 */
enum class NavGroup(val label: String) {
    RECORD("Record"),
    BROWSE("Browse"),
    ADMIN("Admin"),
    ACCOUNT("Account")
}

/**
 * Every destination the menu can reach. `MainActivity` owns the routing table (it holds the private
 * `EntryMode`/`Screen` types), so this enum is the contract between the two.
 *
 * Four entries read differently from the Android drawer that preceded them, all four to match the
 * web rather than to invent anything:
 *
 *  - [BROWSE_RECORDS] is the web's `/search`. The Android drawer called it "Search"; the web calls
 *    the same capability "Browse records", so that is the label now — it maps to `EntryMode.SEARCH`.
 *  - [VIEW_DATA] is the web's `/data`, the whole repository as a directory tree, which on Android is
 *    `EntryMode.DATA_BROWSER` (the drawer used to label that one "Data Browser").
 *  - [REVIEW] is the web's `/review`, a screen of its own (`Screen.ReviewQueue`): an Inspector /
 *    Reviewer's workshops waiting for a decision, then the record queue every reviewer shares. The
 *    record browser (`EntryMode.VIEW_DATA`) still carries the same record queue card for a reviewer
 *    who arrives there, and is absent from this list under its own name (its `actionTitle` is the
 *    string "Browse records", which would otherwise collide head-on with [BROWSE_RECORDS] above).
 *  - "Request workshop access" is gone from the menu, because the web has no such nav entry — it
 *    lives inside the Workshops page and the admin hub. Its dashboard tile is untouched, so the
 *    screen is still one tap from the dashboard.
 */
enum class NavDestination {
    DASHBOARD,
    WALKTHROUGH,
    RECORD_ARTISAN,
    RECORD_PRODUCT,
    DOCUMENT_PROCESS,
    RECORD_TOOL,
    TAKE_INTERVIEW,
    UPLOAD_MEDIA,
    ADD_CRAFT,
    RECORD_WORKSHOP,
    /**
     * The Design & Prototype Workshop record — 22 stages, 43 entities, every field registry-declared.
     *
     * Its own destination rather than a mode of [RECORD_WORKSHOP], because the two are different
     * records that happen to share a word. "Record workshop" creates a `Workshop` — the event an
     * artisan attended, which other records link to. This opens the 22-stage design document, which
     * has its own schema, its own offline draft store and its own report. Folding them together would
     * put a designer one tap from the wrong form and give the menu one label for two things.
     */
    DESIGN_WORKSHOPS,
    /**
     * Sketches and prototypes WITH NO WORKSHOP IN HAND — the chooser, which is the half of this
     * feature the handset was missing.
     *
     * ── WHAT WAS AND WAS NOT MISSING, BECAUSE THE DISTINCTION MATTERS ────────────────────────────
     *
     * The FEATURE has been on this handset since the sketch wave: `DwSketchRectifyField`,
     * `DwSketchPlate` and `DwSketchRectify` are all here, `FieldRenderer.dwOffersSketchRectify`
     * mounts the panel, and `ReportFigures` counts the plates. What did not exist was any way to
     * REACH it without first opening a workshop and walking to stage 11 — no `NavDestination`, no
     * menu row, no card. The web grew that chooser and the handset did not, so a designer who knew
     * the feature existed on their laptop could not find it on their phone even though their phone
     * could do the work.
     *
     * ── WHY IT IS A CHOOSER AND NOT A SECOND SKETCH SCREEN ──────────────────────────────────────
     *
     * A sketch belongs to a stage of a workshop: `DwSketch` rows live under stage 11 and prototypes
     * under 13, and that is where their answers, their plates and their report figures are filed. So
     * this destination picks the workshop and hands over to the stage that already owns the work.
     * Building a parallel place to add a sketch is exactly what `InlineRecordDialog`'s header refuses
     * for the `Dw…` models — "a second, parallel way to add a prototype" — and it would give one
     * feature two stores.
     *
     * LABEL IS THE WEB'S, VERBATIM: "Sketches & prototypes". That row's comment on the web notes it
     * was the owner's wording with no Android counterpart to match, and asks that when the handset
     * grows the screen the two be brought into step. This is that moment; the web's string wins
     * because it is the one a designer has already been reading.
     */
    SKETCHES_AND_PROTOTYPES,
    /**
     * DESIGN REVIEW — the review half of Sketches & Prototypes, and the last substantial thing this
     * handset had none of.
     *
     * ── WHAT WAS MISSING, WHICH WAS THE WHOLE FEATURE AND NOT AN ENTRY POINT ─────────────────────
     *
     * Unlike [SKETCHES_AND_PROTOTYPES], where the WORK was already on the handset and only the
     * chooser was absent, there was no ratings code anywhere under `app/src/main` at all: no DTO for
     * the three `/design-ratings` endpoints, no repository method, no screen. The frontend contract's
     * §16 recorded exactly that — "Design review really is web-only" — and asked that the two be
     * brought into step when the handset grew it. This is that.
     *
     * ── WHY IT IS ITS OWN DESTINATION AND NOT A SCREEN INSIDE ONE WORKSHOP ───────────────────────
     *
     * A permission fact rather than a layout one, and it is the same one that made the web build a
     * page instead of a tab. `load_workshop_or_404` admits the workshop's creator, an admin and the
     * holder of a viewer grant, and answers everybody else with 404 — and every one of the 22 stage
     * SAVE routes is gated by that same helper. A POOL reviewer is by definition somebody it turns
     * away, so reaching the round through the workshop would have meant teaching that helper about
     * POOL and handing every designer in the country write access to every finished workshop's
     * fieldwork. `/design-ratings` is a second, narrow door: it yields the rateable rows and their
     * scores and nothing else about the workshop.
     *
     * BROWSE, matching the web's group for the same row: what a designer opens this for is finding
     * and judging work that already exists, and the recording of a new piece happens on the stage
     * this screen hands over to.
     *
     * LABEL IS THE WEB'S, VERBATIM: "Design review". It is also the dashboard TILE's label on the
     * web, which that grid's own comment explains — neither of these two destinations is an
     * `EntryMode`, so there is no second string for the tile and the row to differ by.
     */
    DESIGN_REVIEW,
    /**
     * WORKSHOPS TO INSPECT — the Inspector / Reviewer tier's whole surface on this handset.
     *
     * ── WHY IT IS ITS OWN DESTINATION AND NOT A MODE OF [DESIGN_WORKSHOPS] ───────────────────────
     *
     * A permission fact, and a sharper one than the fact that separated [DESIGN_REVIEW]. That row's
     * argument is that `load_workshop_or_404` turns a POOL reviewer away, so the round needed a
     * second, narrow door. Here the account cannot pass ANY door in the `/design-workshops` family:
     * INSPECTOR is not in `DESIGN_WORKSHOP_ROLES`, which is a frozenset and not a rank floor, so
     * every route in that family refuses an inspector exactly as it refuses a professor. Their
     * access comes ONLY from a row in the fifth scope, `DesignWorkshopInspector`, served on its own
     * prefix. Folding this into the designer's list would mean widening a loader that also carries
     * all 22 stage WRITES — which is precisely the "fix" the server's own module is built to prevent.
     *
     * ── AND IT IS OFFERED TO A SET OF ONE, WHICH IS WHY THE GATE IS NOT A RANK ───────────────────
     *
     * [FieldPermissions.canInspectDesignWorkshops] — INSPECTOR alone. An ADMIN and a MASTER_ADMIN
     * are REFUSED this surface by name, which makes this the only row in [FIELD_NAV_ITEMS] a master
     * admin cannot reach. Read the predicate's own note before touching it.
     *
     * BROWSE, matching the web's group for the same row: what an inspector opens this for is finding
     * and reading work that already exists. Nothing on the far side of it records anything.
     *
     * LABEL IS THE WEB'S, VERBATIM: "Workshops to inspect".
     */
    DESIGN_WORKSHOP_INSPECTIONS,
    /**
     * The questionnaires a designer AUTHORED THEMSELVES — `/api/questionnaires`, plural.
     *
     * Its own destination and not a mode of [TAKE_INTERVIEW], for the same reason [DESIGN_WORKSHOPS]
     * is not a mode of [RECORD_WORKSHOP]: two different records that share a word. "Take interview"
     * answers the ONE global artisan questionnaire every researcher answers, which nobody may add a
     * question to; this opens a form somebody built last Tuesday in the .xlsx pro-forma, which its
     * author may reword. Folding them together would put a designer one tap from the wrong form and
     * give the menu one label for two things.
     *
     * RECORD rather than BROWSE, because what a designer opens it for is recording a sitting.
     *
     * NOTE FOR WHOEVER SHIPS THE WEB HALF: there is no `/questionnaires` page on the web yet, so this
     * label has no counterpart to match. When one lands, both clients must say the same words — see
     * the contract on [NavEntry.label].
     */
    CUSTOM_QUESTIONNAIRES,
    /**
     * The signed-in designer's own `DesignerProfile` — the twenty values every report they generate
     * prints under their name.
     *
     * In ACCOUNT rather than RECORD, and that placement is the honest one: this edits the PERSON, not
     * the repository. It sits beside Settings for the same reason the web puts it there — a designer
     * looking for "where do I change the name on my reports" looks under their own account, not under
     * the list of things they can create.
     */
    DESIGNER_PROFILE,
    /**
     * The institution's roster of recognised designers — the list that decides who may sign in at
     * all.
     *
     * Admin chrome, and admin-gated, for a reason stronger than the usual one: this is not a screen
     * that merely shows more when you are an admin, it is a list of named individuals and their
     * institutional standing, and `GET /designers/roster` refuses everyone below Admin outright.
     */
    DESIGNER_ROSTER,
    MY_ACTIVITY,
    TASKS,
    BROWSE_RECORDS,
    /**
     * SCAN A CODE — the destination whose whole job is reading a card, a tag or a screenshot.
     *
     * ── WHY IT EXISTS WHEN [BROWSE_RECORDS] ALREADY MOUNTS THE SCANNER ───────────────────────────
     *
     * `RecordCodeLookup.kt`'s header argues, correctly, that a scan is a search whose query happens
     * to be exact, and that hosting it on the search screen meant "no new destination has to be
     * invented in the navigation on one client and mirrored on the other". That is an argument about
     * where the CONTROL belongs and it still holds — `RecordCodeLookupPanel` stays exactly where it
     * is on `SearchScreen`, and nothing here replaces it.
     *
     * What the argument did not weigh is the ROUTE, and the owner reported the cost of that on
     * 2026-08-28: scanning was *"buried underneath a lot of pages"*. It was. To read a tag a designer
     * had to open the drawer, find "Browse records" — a row named after reading a LIST, which is not
     * what somebody holding a printed card is doing — and then notice a panel above the search box.
     * On this client that is worse than on the web, because Search carries `onDashboard = false` and
     * so has no tile at all: the drawer was the only way in. Three deliberate steps, none of them
     * named after the action, to reach the one control whose entire purpose is to save typing.
     *
     * A destination is the cheapest fix and it takes nothing away: both scanners stay, and this adds
     * a door named after what the designer is doing, in the menu AND on the dashboard.
     *
     * ── BROWSE, AND DIRECTLY UNDER "Browse records" ──────────────────────────────────────────────
     *
     * Beside the row it used to hide behind, in the same group the web's own entry sits in. The two
     * clients' grids are held to each other tile for tile by `DashboardTileParityTest` and
     * `frontend/e2e/dashboard-tile-parity-unit.spec.ts`, so this could not be added on one client
     * without the other in the same change — which is the point.
     *
     * UNGATED, matching [BROWSE_RECORDS]. Every endpoint behind the lookup takes a signed-in caller
     * and scopes its answer per viewer on the server, and `require_record` raises **404** — never
     * 403 — for a record the caller may not have, precisely so that a code cannot be used to
     * enumerate the repository one photograph at a time. A predicate here would be a client-side
     * rule the API does not have.
     */
    SCAN_CODE,
    /**
     * The web's `/map` — the repository read as geography. The third way of reading the whole corpus
     * and it sits next to the other two: `/search` reads it as a list, `/data` as a folder tree,
     * `/map` as a place. Ungated, because `GET /map/points` asks for nothing but a login: reading the
     * repository is open to every signed-in account (`records.viewable_where`), and what the pins may
     * NOT carry is bounded inside that route rather than at this entry.
     */
    MAP,
    VIEW_DATA,
    /**
     * The web's `/questionnaire/consolidated`. BROWSE and not RECORD: an interview is stored once per
     * exact set of artisans, so one artisan's answers are scattered over several entries and this is
     * the only surface that reads them back as one document. "Take interview" writes; this only reads.
     */
    CONSOLIDATED_QUESTIONNAIRE,
    SHARE_DATA_ACCESS,
    ASSIGN_TOOLS,
    REVIEW,
    SETTINGS_HUB,
    MANAGE_USERS,
    /**
     * The PLATFORM allow-list — who may sign in at all — and the queue of people waiting to be let
     * in. Not [DESIGNER_ROSTER], which is the narrower question of who is empanelled as a designer.
     */
    ACCESS_ROSTER,
    /** The in-app activity list (C25), kept offline; the web's /notifications. */
    NOTIFICATIONS,
    SETTINGS,
    GIVE_FEEDBACK,
    /**
     * THE TERMS AND CONDITIONS — the agreement the sign-in box accepts, readable again afterwards.
     *
     * ── WHY THE MENU NEEDS A ROW FOR SOMETHING THE DOOR ALREADY LINKS TO ─────────────────────────
     *
     * On the web the terms are a public URL: a person who has signed in reaches them by typing it,
     * from a bookmark, or from the sign-in screen they can always go back to. A handset has none of
     * those. Without this row the ONLY route to the agreement would be the underlined phrase on the
     * sign-in screen — which is to say, a designer could read what they agreed to right up until the
     * moment they agreed to it and never again. That is the whole justification; it is not symmetry
     * for its own sake.
     *
     * ── ACCOUNT, AND UNGATED ─────────────────────────────────────────────────────────────────────
     *
     * Account, beside Settings and feedback, because the agreement belongs to the PERSON and not to
     * the repository. `everyone`, because every tier signed the same terms and there is no endpoint
     * behind the nine clauses at all — they are constants in this binary. Clause 10 does call
     * `GET /usage/consent/notice`, and a phone that cannot reach it still gets the other nine, which
     * is exactly what [TermsScreen] is built to do.
     *
     * ── IT IS A DIALOG, NOT A `Screen` ───────────────────────────────────────────────────────────
     *
     * Routed like [WALKTHROUGH]: it draws OVER whatever the reader was on and takes nothing away, so
     * it is exempt from the unsaved-changes guard and needs no `goBack` arm. The deeper reason is the
     * sign-in door — the same composable has to open before there is a token or a `Screen` at all, so
     * a route would work from the menu and from nowhere else.
     */
    TERMS
}

data class NavEntry(
    val destination: NavDestination,
    /** The EXACT web label. Both clients speak one language; never reword one side only. */
    val label: String,
    val icon: ImageVector,
    /** `null` = a standalone entry above the groups (the web renders these loose in the bar). */
    val group: NavGroup?,
    /**
     * The entitlement this destination needs. When it returns false the entry is NOT RENDERED —
     * never rendered disabled — so the menu only ever offers what the API would actually allow.
     */
    val can: (UserDto) -> Boolean,
    /** The backend dependency `can` mirrors (backend/app/core/deps.py); keep the two in step. */
    val gate: String,
    /** Admin-tier chrome: admins additionally need admin view ON. Never widens [can]. */
    val adminSurface: Boolean = false
)

private val everyone: (UserDto) -> Boolean = { true }

/**
 * The single source of truth for navigation, item-for-item and in the same order as the web's
 * `NAV_ITEMS`. One list drives the whole menu, so a hidden entry cannot reappear somewhere else.
 */
val FIELD_NAV_ITEMS: List<NavEntry> = listOf(
    NavEntry(NavDestination.DASHBOARD, "Dashboard", Icons.Filled.Dashboard, null, everyone, "get_current_user"),
    // Onboarding, deliberately ungated: the Walkthrough teaches the documentation process itself, so
    // it has to reach the people who have not earned any capability yet — a crowdsource volunteer on
    // their first day needs it MORE than an admin does.
    NavEntry(NavDestination.WALKTHROUGH, "Walkthrough", Icons.Filled.Explore, null, everyone, "none (static page)"),

    // Record — every entry here CREATES something, so it follows the create dependency, not the list
    // one. Hiding an entry therefore never hides the DATA behind it: "Browse records" and "View Data"
    // remain the read route to the same records.
    NavEntry(NavDestination.RECORD_ARTISAN, "Record artisan", Icons.Filled.Person, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_PRODUCT, "Record product", Icons.Filled.Inventory2, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.DOCUMENT_PROCESS, "Document process", Icons.Filled.AccountTree, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_TOOL, "Record tool", Icons.Filled.Build, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    // Answering an interview is open to everyone — volunteers contribute answers and media.
    NavEntry(NavDestination.TAKE_INTERVIEW, "Take interview", Icons.Filled.Quiz, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.UPLOAD_MEDIA, "Upload media", Icons.Filled.PermMedia, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.ADD_CRAFT, "Add craft", Icons.Filled.Brush, NavGroup.RECORD, FieldPermissions::canManageCrafts, "require_craft_manager"),
    NavEntry(NavDestination.RECORD_WORKSHOP, "Record workshop", Icons.Filled.Groups, NavGroup.RECORD, FieldPermissions::canManageWorkshops, "require_workshop_manager"),
    // `can_run_design_workshops`, and the comment that used to sit here said the opposite: that
    // POST /design-workshops gates on the create-records dependency and that "a researcher running a
    // cluster workshop is exactly who fills these 22 stages in". The server disagrees and always has
    // — the create answers a RESEARCHER with
    //   403 "Running a design workshop requires Designer access or above."
    // What that bought was the worst shape a permission mistake can take: the entry rendered, Start
    // worked, the 22 stages accepted a fortnight of capture and photographs because the draft is
    // local, and EVERY sync attempt was refused, for ever, with the work stranded on the handset. The
    // web hides the entry and gates the URL behind an explicit "Designer access required" page.
    NavEntry(NavDestination.DESIGN_WORKSHOPS, "Design workshops", Icons.Filled.DesignServices, NavGroup.RECORD, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops"),
    // DECLARED BEFORE Sketches & prototypes, deliberately, because that is the web's order INSIDE
    // the Browse group ("Share data access", then "Design review", then "Sketches & prototypes") and
    // the order within a group is what a person actually reads. The two rows sit adjacent on both
    // clients, which is the point: they are the two halves of one feature.
    //
    // `Icons.Filled.Star` IS THE WEB'S OWN GLYPH FOR THIS ROW and appears nowhere else in this list,
    // so the one-glyph-per-meaning rule holds. The web's PAGE header draws a globe instead, and where
    // a page and its menu row disagree the menu is what a tile or a second client follows — the
    // dashboard grid on the web says exactly that.
    //
    // The gate names the route this row actually reaches, which is NOT the workshop loader:
    // `load_ratable_workshop_or_404`'s first line is `if not can_run_design_workshops(user): raise
    // not_found`, so this predicate is a mirror of the API's own and not a narrowing of it. The
    // screen re-states the same refusal for somebody who arrives with a workshop id in hand.
    NavEntry(NavDestination.DESIGN_REVIEW, "Design review", Icons.Filled.Star, NavGroup.BROWSE, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops (load_ratable_workshop_or_404 on GET /design-ratings/rounds/{round}; get_current_user + visible_to_clause on the picker's list)"),
    // BROWSE and not RECORD, matching the web's group for the same row: what a designer opens this
    // for is finding the sketch work they or a colleague already did, and the recording of a new one
    // happens inside the stage this hands over to.
    NavEntry(NavDestination.SKETCHES_AND_PROTOTYPES, "Sketches & prototypes", Icons.Filled.Brush, NavGroup.BROWSE, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops (load_workshop_or_404 on the chosen workshop; get_current_user + visible_to_clause on the picker's list)"),
    // WORKSHOPS TO INSPECT — the fifth scope, and THE ONE ROW IN THIS LIST A MASTER ADMIN CANNOT
    // REACH. On this handset the predicate is set membership on {INSPECTOR}: the server has also
    // admitted an appointed admin since 2026-10-09, and this handset leaves that to the web (see
    // `FieldPermissions.canInspectDesignWorkshops`). So it is deliberately NOT a rank floor:
    // `>= RANK_INSPECTOR` would be the wrong answer for SIX of the eleven tiers (professor, the three
    // directorate tiers, admin and master admin all clear 37 and are all refused here) and would put
    // this entry in every admin's menu. What an admin gets instead is the appointment screen off a
    // workshop's stage index.
    //
    // `Icons.Filled.FindInPage` appears nowhere else in this list, so the one-glyph-per-meaning rule
    // holds: Visibility belongs to Review, Star to Design review, Brush to Craft and to Sketches.
    //
    // NOT `adminSurface`. That flag hides admin chrome from an admin browsing as an ordinary user,
    // and there is no admin here to hide it from — the predicate has already refused them.
    //
    // LABEL IS THE WEB'S, VERBATIM. The web wrote these strings first because there was no handset
    // surface to copy from when it shipped, which inverts §1 of the frontend contract for this one
    // wave; the words are the web's and this row is where the two are pinned together, exactly as
    // the Sketches & prototypes row above records the same trade in the other direction.
    NavEntry(NavDestination.DESIGN_WORKSHOP_INSPECTIONS, "Workshops to inspect", Icons.Filled.FindInPage, NavGroup.BROWSE, FieldPermissions::canInspectDesignWorkshops, "assert_inspection_surface (INSPECTION_ROLES = {INSPECTOR}; GET /design-workshop-inspections and /{id})"),
    // `can_run_design_workshops` and NOT `canCreateRecords`, unlike the row above it: every route
    // under /api/questionnaires runs `_require_designer` first, READS included. Gating this on the
    // looser predicate would put the entry in a researcher's menu and answer them with a 403 on the
    // list request — before they had seen a single questionnaire.
    NavEntry(NavDestination.CUSTOM_QUESTIONNAIRES, "Questionnaires", Icons.AutoMirrored.Filled.ListAlt, NavGroup.RECORD, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops"),

    // Browse
    NavEntry(NavDestination.MY_ACTIVITY, "My Activity", Icons.Filled.Timeline, NavGroup.BROWSE, everyone, "get_current_user"),
    // Everyone can be a task assignee; the "assign" half is gated inside the screen.
    NavEntry(NavDestination.TASKS, "Tasks", Icons.AutoMirrored.Filled.Assignment, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.BROWSE_RECORDS, "Browse records", Icons.Filled.Search, NavGroup.BROWSE, everyone, "get_current_user"),
    // Directly under the row that used to be the only way in. Label is the web's, verbatim — both
    // clients' menus and both dashboards say "Scan a code", so a designer moving between the phone
    // and the laptop mid-workshop reads one name. See [NavDestination.SCAN_CODE] for why a
    // destination was added rather than the panel moved.
    NavEntry(NavDestination.SCAN_CODE, "Scan a code", Icons.Filled.QrCodeScanner, NavGroup.BROWSE, everyone, "get_current_user"),
    // The third way of reading the whole corpus, in the web's own position in this group: /search reads
    // it as a list, /data as a folder tree, /map as a place. Ungated to match /search, because
    // GET /map/points asks for nothing but a login — reading the repository is open to every signed-in
    // account. A volunteer and a professor open this screen and see the SAME numbers, which is the point
    // of pooling the fieldwork and not a gap in this predicate. What stays earned is taking data out.
    NavEntry(NavDestination.MAP, "Map", Icons.Filled.Map, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.VIEW_DATA, "View Data", Icons.Filled.Storage, NavGroup.BROWSE, FieldPermissions::canDownloadDataset, "require_dataset_downloader"),
    // Browse rather than Record, for the reason on [NavDestination.CONSOLIDATED_QUESTIONNAIRE].
    // GET /questionnaire/artisans/{id}/consolidated asks for nothing but a login.
    NavEntry(NavDestination.CONSOLIDATED_QUESTIONNAIRE, "Consolidated questionnaire", Icons.Filled.Layers, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.SHARE_DATA_ACCESS, "Share data access", Icons.Filled.Share, NavGroup.BROWSE, everyone, "get_current_user"),
    // Linking a tool to an artisan needs a tool or an artisan of your own — both need record creation.
    // The endpoint only requires a login and then checks ownership per artisan, so this is the closest
    // STATIC mirror of a dynamic rule: nobody below Researcher can own either side. Same predicate as
    // the web's own "Assign tools to artisans" entry, which is why it moved with `canCreateRecords`.
    NavEntry(NavDestination.ASSIGN_TOOLS, "Assign tools to artisans", Icons.Filled.Handyman, NavGroup.BROWSE, FieldPermissions::canCreateRecords, "get_current_user + owner/EDIT-grant/admin per artisan"),
    // NOT adminSurface, matching the web: reviewing is a Field Contributor capability an admin merely
    // also holds, so "browse as an ordinary user" must not take the link away while the screen stays open.
    NavEntry(NavDestination.REVIEW, "Review", Icons.Filled.Visibility, NavGroup.BROWSE, FieldPermissions::canReview, "require_reviewer"),

    // Admin — capability holders below admin (professors, grantees) keep these permanently; admins,
    // who own the toggle, see them only while admin view is ON.
    NavEntry(NavDestination.SETTINGS_HUB, "Settings hub", Icons.Filled.Tune, NavGroup.ADMIN, FieldPermissions::isAdmin, "require_admin", adminSurface = true),
    NavEntry(NavDestination.MANAGE_USERS, "Manage users", Icons.Filled.ManageAccounts, NavGroup.ADMIN, FieldPermissions::canManageUsers, "require_professor", adminSurface = true),
    // The sign-in gate for the DESIGNER tier. `adminSurface` like its neighbours, so an admin
    // browsing as an ordinary user does not carry a roster of named individuals around with them —
    // but the predicate is what decides, and it is the same `is_admin` the GET enforces.
    NavEntry(NavDestination.DESIGNER_ROSTER, "Designer roster", Icons.Filled.Badge, NavGroup.ADMIN, FieldPermissions::canManageDesignerRoster, "can_manage_designer_roster", adminSurface = true),
    // The sign-in gate for EVERYBODY, and the queue of people waiting for a decision. Above the
    // designer roster in the reading order an admin needs — "may this person reach the app at all"
    // comes before "is this person empanelled as a designer" — and directly in the menu because the
    // pending count is drawn on this entry: a notification an admin has to go looking for through a
    // hub is not a notification. The web reaches the same screen from its settings-hub tile and
    // badges the hub entry instead; the NUMBER is what must match across the two clients, not the
    // route to it. `adminSurface` like its neighbours, so an admin browsing as an ordinary user is
    // not carrying a list of named strangers around with them.
    NavEntry(NavDestination.ACCESS_ROSTER, "Who may sign in", Icons.Filled.LockPerson, NavGroup.ADMIN, FieldPermissions::canManageAccessRoster, "require_access_manager", adminSurface = true),

    // Account — personal, so nothing here is role-gated. On Android "Settings" is the Appearance &
    // accessibility screen: the web's /settings is two columns in one page, and the master admin's
    // global column is the Android admin hub's own tool. Gating this would leave every non-admin with
    // NO route to their own accessibility switches, and PUT /preferences/me asks for nothing but a login.
    // Gated on the DESIGNER rank rather than left open, because a `DesignerProfile` is only ever
    // printed on a design-workshop report and only a designer can generate one. Offering the screen
    // to a crowdsource volunteer would be offering them a form whose twenty answers nothing in the
    // app would ever read back — and `PUT /designers/me/profile` refuses them anyway.
    NavEntry(NavDestination.DESIGNER_PROFILE, "My designer profile", Icons.Filled.Badge, NavGroup.ACCOUNT, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops"),
    // The activity list (C25): every notice the server sends, mail or no mail. The web's bell opens
    // the same list; the unread count is this row's badge.
    NavEntry(NavDestination.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications, NavGroup.ACCOUNT, everyone, "get_current_user (GET /notifications)"),
    NavEntry(NavDestination.SETTINGS, "Settings", Icons.Filled.Settings, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /preferences/me)"),
    NavEntry(NavDestination.GIVE_FEEDBACK, "Give app feedback", Icons.Filled.RateReview, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /feedback/me)"),
    // The agreement the sign-in box accepts. `everyone` and no gate at all: the nine clauses are
    // constants in this binary, and clause 10's `GET /usage/consent/notice` is served to anybody who
    // asks — it is the text a person is asked to agree to BEFORE they have a token. See
    // [NavDestination.TERMS] for why the menu needs this row when the door already links to it.
    NavEntry(NavDestination.TERMS, "Terms and conditions", Icons.Filled.Gavel, NavGroup.ACCOUNT, everyone, "none (static clauses; GET /usage/consent/notice for clause 10, unauthenticated)")
)

/**
 * Entitlement first, admin view second: [NavEntry.can] is consulted before the toggle is even looked
 * at, so switching admin view ON can never surface a destination the API would 403 — it only hides
 * admin chrome from an admin browsing as an ordinary user.
 */
fun isNavItemVisible(item: NavEntry, user: UserDto?, adminMode: Boolean): Boolean {
    if (user == null || !item.can(user)) return false
    if (item.adminSurface && FieldPermissions.isAdmin(user)) return adminMode
    return true
}

/** The visible entries for [user], in web order. */
fun visibleNavItems(user: UserDto?, adminMode: Boolean): List<NavEntry> =
    FIELD_NAV_ITEMS.filter { isNavItemVisible(it, user, adminMode) }

// The island bar itself — the phone's bottom navigation, its five-slot cap and its "More" sheet —
// lives in IslandBottomNav.kt, and renders from the same [visibleNavItems] the drawer below does.

// ---------------------------------------------------------------------------------------------
// The drawer
// ---------------------------------------------------------------------------------------------

/**
 * Drop-in `drawerContent` for the app's `ModalNavigationDrawer`. Renders exactly
 * [visibleNavItems] — the standalone roots first, then each non-empty group behind its own heading —
 * followed by the admin-view toggle and the master admin's update action.
 *
 * @param currentDestination highlights the entry the user is on, the drawer's equivalent of the web's
 *   `aria-current="page"` purple state.
 */
@Composable
fun AppNavigationDrawerContent(
    user: UserDto,
    adminMode: Boolean,
    onNavigate: (NavDestination) -> Unit,
    onToggleAdminView: () -> Unit,
    onLogout: () -> Unit,
    currentDestination: NavDestination? = null,
    pushingUpdate: Boolean = false,
    onPushUpdate: () -> Unit = {},
    /**
     * HOW MANY PEOPLE ARE WAITING TO BE LET INTO THE APPLICATION — the badge on "Who may sign in".
     *
     * THE FEATURE'S WHOLE NOTIFICATION CHANNEL. The requirement asks that admins be told when
     * somebody is turned away, and this codebase has no email sender, no push transport and no job
     * runner to build one from — so the notification is a number on the chrome an admin already
     * opens, with the queue one tap behind it.
     *
     * PASSED IN RATHER THAN FETCHED HERE, and that is not merely style: the drawer is recomposed
     * whenever it opens, and a fetch inside it would be a request per open. The value rides the
     * app-wide 45-second loop that is already running to drain the outbox (see `MainActivity`), so
     * the count costs no timer, no wake-up and no second clock that could drift away from the one
     * the screen itself reads.
     *
     * Zero draws NOTHING, which also covers "we could not ask": a confident "0" over a queue that
     * failed to load is a lie an admin would act on by not opening it.
     */
    pendingAccessCount: Int = 0,
    /** How many of this inspector's workshops wait for their review — the badge on "Workshops to
     *  inspect" (2026-10-18). Same loop, same rule: zero draws nothing. */
    awaitingInspectionCount: Int = 0,
    /** Unread notices in the activity list (C25); the badge on "Notifications". */
    unreadNotifications: Int = 0
) {
    val items = visibleNavItems(user, adminMode)
    val rootItems = items.filter { it.group == null }
    val groups = NavGroup.entries
        .map { group -> group to items.filter { it.group == group } }
        .filter { (_, entries) -> entries.isNotEmpty() }
    val isAdmin = FieldPermissions.isAdmin(user)

    ModalDrawerSheet(
        // Width capped and inner edge rounded, and announced as a pane: see AppMenuDrawer.kt.
        modifier = Modifier
            .widthIn(max = appMenuDrawerMaxWidth())
            .semantics { paneTitle = "Menu" },
        drawerShape = APP_MENU_DRAWER_SHAPE
    ) {
        // Brand block — the island's left end: the mark in its cream tile, the wordmark in the
        // display face, and who you are signed in as.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            WorkshopLogo(modifier = Modifier.size(36.dp), cornerRadius = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Design Workshop",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "${user.name} · ${FieldPermissions.label(user.role)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.muted
                )
            }
            OutlinedButton(
                onClick = onLogout,
                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Logout", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        // The menu is long for an admin, so the entries scroll while the brand block and the
        // footer actions stay pinned.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            rootItems.forEach { entry ->
                NavRow(entry, entry.destination == currentDestination, onNavigate, navBadge(entry, pendingAccessCount, awaitingInspectionCount, unreadNotifications))
            }
            groups.forEach { (group, entries) ->
                NavGroupHeading(group.label)
                entries.forEach { entry ->
                    NavRow(entry, entry.destination == currentDestination, onNavigate, navBadge(entry, pendingAccessCount, awaitingInspectionCount, unreadNotifications))
                }
            }
            Spacer(Modifier.padding(bottom = 8.dp))
        }

        if (isAdmin || FieldPermissions.isMasterAdmin(user)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
        // Offered to the content admins only — and it can merely hide admin chrome, never unlock it.
        if (FieldPermissions.isContentAdmin(user)) {
            // A real switch (owner's rule, 2026-10-17): its position and TalkBack's "On"/"Off" state
            // say which, so the label never carries an "ON"/"OFF" word. The web's island draws the
            // same `ToggleSwitch` labelled "Admin view".
            AppToggle(
                label = "Admin view",
                checked = adminMode,
                onCheckedChange = { onToggleAdminView() },
                description = "Shows the admin pages and controls. Off only hides them; it never takes access away.",
                modifier = Modifier
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                    .padding(horizontal = 16.dp)
            )
        }
        if (FieldPermissions.isMasterAdmin(user)) {
            NavigationDrawerItem(
                label = {
                    Text(
                        if (pushingUpdate) "Publishing update…" else "Push update to all",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                selected = false,
                icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                onClick = { if (!pushingUpdate) onPushUpdate() },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }
}

/** Group heading — the web renders these as the dropdown TRIGGERS; on a sheet they are labels. */
@Composable
private fun NavGroupHeading(label: String) {
    Text(
        text = label.uppercase(),
        style = FieldTextStyles.FieldLabel,
        color = MaterialTheme.field.muted,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

/**
 * The number this entry wears, or null for none.
 *
 * A FUNCTION OF THE ENTRY, hoisted out of the drawer and `internal`, so `AppNavigationBadgeTest` can
 * assert the two rules that actually matter: the count lands on "Who may sign in" and on nothing
 * else, and a zero draws nothing at all. Both are the kind of thing that is obviously right while
 * you are writing it and silently wrong six months later, when a second badge arrives and the
 * condition is copied one entry down.
 */
internal fun navBadge(entry: NavEntry, pendingAccessCount: Int, awaitingInspectionCount: Int = 0, unreadNotifications: Int = 0): String? =
    when {
        entry.destination == NavDestination.ACCESS_ROSTER && pendingAccessCount > 0 -> pendingAccessCount.toString()
        entry.destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS && awaitingInspectionCount > 0 ->
            awaitingInspectionCount.toString()
        // The activity list's unread count (C25), capped like the web's pill.
        entry.destination == NavDestination.NOTIFICATIONS && unreadNotifications > 0 ->
            if (unreadNotifications > 99) "99+" else unreadNotifications.toString()
        else -> null
    }

@Composable
private fun NavRow(
    entry: NavEntry,
    selected: Boolean,
    onNavigate: (NavDestination) -> Unit,
    badge: String? = null
) {
    NavigationDrawerItem(
        label = { Text(entry.label, style = MaterialTheme.typography.labelLarge) },
        selected = selected,
        icon = { Icon(entry.icon, contentDescription = null) },
        // Material's own badge slot rather than something drawn into the label: it is announced as a
        // separate node, so a screen reader says the destination and then the number instead of
        // running "Who may sign in 3" together as one name.
        badge = badge?.let { count -> { Text(count, style = MaterialTheme.typography.labelLarge) } },
        onClick = { onNavigate(entry.destination) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

/**
 * True only for a content admin ([FieldPermissions.isContentAdmin]) with admin view on — the handset's
 * `adminMode` ([contentAdminChrome]). Admin-view chrome only: no delete reads it (owner, 2026-10-21).
 */
val LocalContentAdminChrome = staticCompositionLocalOf { false }

/**
 * True for a content admin ([mayDelete]), WHETHER OR NOT admin view is on (owner, 2026-10-21). Every
 * remove/delete control reads it, so a role outside the set is never offered a delete the server
 * refuses, and a content admin is never made to switch admin view on to find one.
 */
val LocalMayDelete = staticCompositionLocalOf { false }
