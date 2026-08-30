package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ROLE_MATCH_READ_LIMIT
import java.time.LocalDate
import java.time.ZoneId

/**
 * THE TWO ADMIN ROSTERS' FILTER AND SORT GRAMMAR — one vocabulary, both screens, and the byte-parallel
 * twin of `frontend/components/admin/rosterFilters.ts`.
 *
 * ── WHY A FILE AND NOT TWO SCREENS' WORTH OF LOCAL STATE ─────────────────────────────────────────
 *
 * The two screens are two TABLES with two different jobs and the words must keep them apart —
 * `AccessRoster` says who may reach the product at all, `DesignerRoster` says who the institution
 * recognises as a designer — but the CONTROLS are the same controls: a search box, some
 * multi-selects, one date range, one order. Written twice they word one thing two ways, and a reader
 * who meets "Last 30 days" on one screen and "Past month" on the other reasonably concludes neither
 * of them means much. The web reached the same conclusion and put its filter row in one shared
 * component (DROPDOWN_DESIGN §4.9); this is that file, on this client.
 *
 * ── AND WHY IT IS A LINE-FOR-LINE MIRROR RATHER THAN AN INDEPENDENT DESIGN ───────────────────────
 *
 * Requirement 20 is parity, and the two clients are talking to ONE route with ONE grammar. A
 * divergence here does not read as two designs — it reads as the filter being broken on whichever
 * client the admin happens to be holding, because the same three ticks would produce two different
 * lists. So the token spellings, the canonical orders, the reserved words, the default state and the
 * sentences are all the web's, and where this file deliberately differs (there are three places, all
 * marked ⚠) the reason is a property of Android and not a preference.
 *
 * ── THE FOUR RULES THIS FILE EXISTS TO KEEP, EACH ONE ALREADY BROKEN ONCE ────────────────────────
 *
 *  (i)   **EMPTY MEANS EVERYTHING, BY ABSENCE.** There is no all-ticked state distinguishable on the
 *        wire from a nothing-ticked one. [rosterQueryParams] over [emptyRosterFilters] produces an
 *        object whose every field is null, and every null is dropped before the request is built —
 *        so the first request either screen makes is byte for byte the request it made before req 30.
 *  (ii)  **SUSPENDED AND REFUSED ROWS STAY LISTED BY DEFAULT.** [emptyRosterFilters] is that rule as
 *        a value. An admin opens these screens BECAUSE somebody cannot sign in, and the row refusing
 *        them is a REJECTED or a SUSPENDED one.
 *  (iii) **EVERY CAP OR TRUNCATION IS STATED, WITH THE NUMBER.** [roleMatchCutNotice],
 *        [accessRoleCutNotice], [institutionCutNotice] and [rosterFilterGrammarNotice] are the four
 *        ways these controls can narrow an answer without it being visible in the rows.
 *  (iv)  **FILTERING IS SERVER-SIDE.** Everything here builds QUERY PARAMETERS. There is no
 *        predicate in this file that takes a row, and there must never be one: a client-side filter
 *        over a server-truncated page answers "no matches" about records that exist.
 */

// ---------------------------------------------------------------------------------------------
// Which roster
// ---------------------------------------------------------------------------------------------

/**
 * The two lists.
 *
 * ONE DISCRIMINATOR RATHER THAN TWO COPIES OF EVERYTHING, exactly as the web's `RosterKind` does.
 * The alternative is two of each function below, which is two chances to word one fact differently
 * and two places to forget rule (i).
 *
 * [noun] is how a sentence in this file names the people on that roster. It is not decoration: the
 * designer roster's cut sentence says "designers" and the allow-list's says "entries", because an
 * allow-list row may be a stranger who has only ever been refused and calling them a designer would
 * be wrong in the one place a reader is being asked to trust the screen.
 */
enum class RosterKind(val noun: String) {
    ACCESS("entries"),
    DESIGNER("designers"),
}

/** `asc` | `desc` — the wire's own two spellings, lower case, as §4.1 writes them. */
enum class RosterDir(val token: String) {
    ASC("asc"),
    DESC("desc"),
}

// ---------------------------------------------------------------------------------------------
// The reserved tokens — three of them, and each one closes the same hole
// ---------------------------------------------------------------------------------------------

/**
 * `admitRole IS NULL` — "admitted at the platform default, the lowest rung" (`schema.prisma:4177-4186`).
 *
 * The access screen already renders that state as its own phrase ("Joins at the default tier",
 * `AccessRosterScreen`'s edit dialog), so it is a value an admin can already SEE and must therefore
 * be able to filter for.
 *
 * WITHOUT THIS OPTION, TICKING ALL EIGHT TIERS SILENTLY EXCLUDES EVERY DEFAULT-TIER ADMISSION — the
 * identical failure `UNASSIGNED_WORKSHOP` was invented for (`record_filters.py:47-53`) and the one
 * `WorkshopScopeSelect`'s "Not linked to a workshop" row closes. A reserved word rather than an empty
 * string, for that module's reason: an empty string is what a blank control sends, and "the admin
 * chose nothing" must never mean "show me only the orphans".
 */
const val ADMIT_ROLE_DEFAULT: String = "default"

/**
 * `DesignerRoster.firstSeenAt IS NULL` on the designer roster's role filter.
 *
 * NAMED FOR WHAT THE COLUMN STORES, AND THE DIFFERENCE IS THE POINT. "Has no account" is not a fact
 * this system holds; what it holds is `firstSeenAt`, *"set the first time an account with this email
 * signs in, so an admin can see which invitations are outstanding rather than guessing"*
 * (`schema.prisma:3962-3964`). Labelling the option "Has never signed in" makes it answerable from
 * one column, needs no second query, and says something true. "No account" would need an unbounded
 * NOT IN over every account the repository has ever had and would STILL be wrong for a provisioned
 * account that has not signed in yet.
 */
const val ROLE_NEVER_SIGNED_IN: String = "never-signed-in"

/**
 * `institution IS NULL` on the designer roster.
 *
 * ⚠ THE ONE COLLISION IN THIS VOCABULARY, WRITTEN DOWN RATHER THAN LEFT TO BE FOUND.
 * `DesignerRoster.institution` is free text, so an institution can in principle be *called* "none",
 * and its served option would then carry the same value as this reserved row. [institutionOptions]
 * de-duplicates so the picker never renders two rows with one value — but the filter for that
 * institution is then unreachable by name, and the server would read the token as the NULL sentinel.
 * This is a property of the wire format §4.1 fixes, not of this file; the honest fixes are a sentinel
 * free text cannot spell, or filtering that institution through the search box, which does reach it
 * (`search` is OR-ed over `institution` on that route). The web carries the identical note.
 */
const val INSTITUTION_NONE: String = "none"

// ---------------------------------------------------------------------------------------------
// The columns each screen can filter and sort by
// ---------------------------------------------------------------------------------------------

/** The four states an allow-list row can be in, exactly as `services/access_roster.py` spells them. */
val ACCESS_STATUS_TOKENS: List<String> = listOf("ACTIVE", "PENDING", "REJECTED", "SUSPENDED")

/**
 * The designer roster's standing, as ONE enum rather than a set.
 *
 * `isActive` is a boolean, so "both" is the ABSENCE of the parameter and there is no third value to
 * tick. Sending `activeOnly=true` alongside `standing=suspended` is a 422 on the server rather than
 * a silent winner, which is why [rosterQueryParams] emits `standing` and NEVER `activeOnly`: the
 * older spelling stays on the wire for a client that has not been updated, and this one is not that
 * client.
 */
val DESIGNER_STANDING_TOKENS: List<String> = listOf("active", "suspended")

/**
 * ONE DATE RANGE PER REQUEST, NOT FIVE — `dateField` names the column, `dateFrom`/`dateTo` bound it.
 *
 * Requirement 30 lists five dates on the allow-list. Five simultaneous From/To pairs would be five
 * index requirements on tables that had none at all on any date column, a query nobody has asked
 * for, and five stacked widgets where one will do. `dateFrom`/`dateTo` is also the spelling eight
 * existing list routes already use, each paired with one `add_date_range` call.
 */
val ACCESS_DATE_FIELDS: List<String> = listOf("added", "requested", "decided", "joined", "firstSeen")

val DESIGNER_DATE_FIELDS: List<String> = listOf("added", "firstSeen", "revoked")

/**
 * THE EIGHT-TIER LADDER, HIGHEST FIRST — `deps.ROLE_RANK`'s order, and the fourth Kotlin copy of it.
 *
 * ⚠ A COPY, AND IT IS PINNED RATHER THAN TRUSTED. `FieldPermissions.RANKS` and `TaskAdminScreen`'s
 * own `ROLES_BY_RANK` are both private to their files, so this cannot be derived without editing a
 * file this parcel does not own. `RosterFilterWireTest` therefore asserts that this list is sorted
 * strictly descending by [FieldPermissions.rank] and that every token has a real label — which
 * catches both ways it can rot: a tier added to the ladder and not to this list (the tier then has
 * no row and cannot be filtered for, with nothing on screen reading as broken — the failure
 * `TaskAdminScreen:140-149` records having already shipped), and a token here that the ladder does
 * not know (rank 0, sorting below a crowdsource volunteer).
 *
 * IT MUST NEVER BE NARROWED TO THE VIEWER'S OWN TIER. `assignableRoles` on the web filters the ladder
 * to tiers at or below the caller's, which is exactly right for the `admitRole` PICKER on the access
 * screen — you cannot grant a tier above your own — and exactly wrong for a FILTER: an admin must be
 * able to filter for rows carrying a tier they could not grant, or every master-admin row becomes
 * invisible to every admin and the list quietly stops being a complete answer for the person most
 * likely to be auditing it.
 */
val ROSTER_ROLE_LADDER: List<String> = listOf(
    "MASTER_ADMIN",
    "ADMIN",
    "PROFESSOR",
    "INSPECTOR",
    "DESIGNER",
    "RESEARCHER",
    "FIELD_CONTRIBUTOR",
    "CROWDSOURCE_VOLUNTEER",
)

// ---------------------------------------------------------------------------------------------
// Sorting
// ---------------------------------------------------------------------------------------------

/**
 * What a column HOLDS, which is the only thing that decides which way it should read FIRST and how a
 * sort control should describe itself.
 *
 * "Newest first" and "A to Z" are both "the natural first reading" and they are opposite directions,
 * so a control that carried the previous column's direction across gives you Z-to-A the first time
 * you choose Email after choosing a date. [nextSortDir] takes the new column's own default instead.
 */
enum class SortValues { DATE, TEXT, COUNT, ENUM }

/**
 * One sortable column.
 *
 * [nullable] is not bookkeeping. Postgres puts NULLs last on `asc` and FIRST on `desc`, so a nullable
 * column sorted newest-first opens with every row that has no value at all — and on one column that
 * is the whole point: `firstSeen desc` floats every OUTSTANDING INVITATION to the top, which is
 * exactly the view this screen's device-side sort was built to produce (*"an admin opens this screen
 * to answer 'who have I added who has not turned up'"*, `DesignerRosterScreen`'s old `:152-159`) and
 * which now survives as a named, PAGED sort that is correct across pages instead of a reordering of
 * whichever rows happened to arrive. It is flagged so the control can SAY so, because a list opening
 * on ten rows that are blank in the column you just sorted by reads as a broken screen.
 */
data class RosterSortSpec(
    val label: String,
    val defaultDir: RosterDir,
    val values: SortValues,
    val nullable: Boolean,
)

/**
 * The allow-list's sorts. §4.3's table, verbatim.
 *
 * Every one of them is tiebroken by `id` ON THE SERVER (`records.with_id_tiebreak`) and that is not
 * optional: OFFSET PAGING OVER A NON-TOTAL ORDER MISSES ROWS AND REPEATS OTHERS, AND BOTH ARE
 * SILENT. The ties are not hypothetical on this table — the migration that grandfathered the
 * existing accounts onto the roster inserted every one of them with a single `CURRENT_TIMESTAMP`,
 * so four hundred people share one `createdAt`. This client's whole part in that is to send ONE
 * named column and ONE direction and never to re-sort what came back.
 */
val ACCESS_SORTS: Map<String, RosterSortSpec> = linkedMapOf(
    "added" to RosterSortSpec("Added to the list", RosterDir.DESC, SortValues.DATE, nullable = false),
    "email" to RosterSortSpec("Email", RosterDir.ASC, SortValues.TEXT, nullable = false),
    "name" to RosterSortSpec("Name", RosterDir.ASC, SortValues.TEXT, nullable = true),
    "standing" to RosterSortSpec("Standing", RosterDir.ASC, SortValues.ENUM, nullable = false),
    "joined" to RosterSortSpec("Joined the platform", RosterDir.DESC, SortValues.DATE, nullable = true),
    // The queue an admin works oldest-first is this column with `dir=asc`, which is why it is an
    // order with two directions and not a fixed one.
    "requested" to RosterSortSpec("Access requested", RosterDir.DESC, SortValues.DATE, nullable = true),
    "decided" to RosterSortSpec("Decision made", RosterDir.DESC, SortValues.DATE, nullable = true),
    "firstSeen" to RosterSortSpec("First signed in", RosterDir.DESC, SortValues.DATE, nullable = true),
    // "Who is hammering the door." Never a FILTER — `attemptCount` is the one column an
    // unauthenticated caller's retries write, and it is read here only as an order.
    "attempts" to RosterSortSpec("Refused attempts", RosterDir.DESC, SortValues.COUNT, nullable = false),
)

/** The designer roster's sorts. §4.3's table, verbatim. */
val DESIGNER_SORTS: Map<String, RosterSortSpec> = linkedMapOf(
    "added" to RosterSortSpec("Added to the roster", RosterDir.DESC, SortValues.DATE, nullable = false),
    "email" to RosterSortSpec("Email", RosterDir.ASC, SortValues.TEXT, nullable = false),
    "name" to RosterSortSpec("Name", RosterDir.ASC, SortValues.TEXT, nullable = true),
    "institution" to RosterSortSpec("Institution", RosterDir.ASC, SortValues.TEXT, nullable = true),
    "firstSeen" to RosterSortSpec("First signed in", RosterDir.DESC, SortValues.DATE, nullable = true),
    "revoked" to RosterSortSpec("Access revoked", RosterDir.DESC, SortValues.DATE, nullable = true),
)

/**
 * The server's default order on BOTH routes, and therefore the one pair this client leaves off the
 * wire entirely.
 *
 * Everything else is sent as a PAIR — including `dir=asc`, which the route would otherwise default
 * to `desc` and hand back Z-to-A for a column whose natural reading is A-to-Z.
 */
const val ROSTER_DEFAULT_SORT: String = "added"
val ROSTER_DEFAULT_DIR: RosterDir = RosterDir.DESC

fun rosterSorts(kind: RosterKind): Map<String, RosterSortSpec> =
    if (kind == RosterKind.ACCESS) ACCESS_SORTS else DESIGNER_SORTS

/**
 * The spec for a column, or null where that token does not belong to that roster.
 *
 * Null is a REACHABLE state and not a type hole: `attempts` is a perfectly good access sort and a
 * meaningless designer one, and a state restored from anywhere must land on that screen's default
 * rather than send the server a token it will 422.
 */
fun rosterSortSpec(kind: RosterKind, column: String): RosterSortSpec? = rosterSorts(kind)[column]

private val DIRECTION_PHRASE: Map<SortValues, Map<RosterDir, String>> = mapOf(
    SortValues.DATE to mapOf(RosterDir.DESC to "newest first", RosterDir.ASC to "oldest first"),
    SortValues.TEXT to mapOf(RosterDir.ASC to "A to Z", RosterDir.DESC to "Z to A"),
    SortValues.COUNT to mapOf(RosterDir.DESC to "most first", RosterDir.ASC to "fewest first"),
    SortValues.ENUM to mapOf(
        RosterDir.ASC to "in standing order",
        RosterDir.DESC to "in reverse standing order",
    ),
)

/** What a direction MEANS on this kind of column, in words a person can act on. */
fun sortDirectionPhrase(values: SortValues, dir: RosterDir): String =
    DIRECTION_PHRASE.getValue(values).getValue(dir)

/**
 * The direction choosing this column would produce.
 *
 * Choosing the column that is already sorted FLIPS it. Choosing any other takes THAT column's own
 * default rather than carrying the current direction across, because "newest first" and "A to Z" are
 * opposite directions and both are the natural first reading of their own column: inheriting `desc`
 * from a date onto an email gives Z-to-A, which nobody asked for.
 */
fun nextSortDir(kind: RosterKind, filters: RosterFilters, column: String): RosterDir {
    val spec = rosterSortSpec(kind, column) ?: return filters.dir
    if (filters.sort == column) {
        return if (filters.dir == RosterDir.ASC) RosterDir.DESC else RosterDir.ASC
    }
    return spec.defaultDir
}

/**
 * The filters with this column sorted. An unknown column is returned unchanged.
 *
 * ⚠ THE CALLER MUST RESET ITS PAGER. A sort change re-orders the whole list, so the rows at
 * `OFFSET 40` are not the rows that were there a moment ago; staying on page 3 lands the reader
 * somewhere arbitrary in a list they have just re-ordered. This function cannot do it itself because
 * the page number is the SCREEN's state, not the filter's — the same division the web draws.
 */
fun nextRosterSort(kind: RosterKind, filters: RosterFilters, column: String): RosterFilters {
    rosterSortSpec(kind, column) ?: return filters
    return filters.copy(sort = column, dir = nextSortDir(kind, filters, column))
}

/**
 * How one row of the sort picker reads — what choosing it will DO, not what the list currently is.
 *
 * The nullable arm is said on the control that produces it, because a list opening on ten rows that
 * are blank in the column just chosen reads as a broken screen rather than as the answer to "who has
 * never turned up".
 */
fun sortRowHint(kind: RosterKind, filters: RosterFilters, column: String): String? {
    val spec = rosterSortSpec(kind, column) ?: return null
    val dir = nextSortDir(kind, filters, column)
    val action = sortDirectionPhrase(spec.values, dir)
    return if (spec.nullable && dir == RosterDir.DESC) {
        "$action — rows with no date come first"
    } else {
        action
    }
}

// ---------------------------------------------------------------------------------------------
// The date presets
// ---------------------------------------------------------------------------------------------

/**
 * The date presets, resolved to concrete instants at REQUEST time.
 *
 * [id] is the WEB's `RangeId` spelling and not this enum's name, so that a filter state written down
 * by either client reads the same. The labels are `SearchScreen`'s `SearchRange` labels byte for
 * byte, because an admin meeting "Last 30 days" on the search screen and "Past month" here would
 * reasonably wonder whether they mean the same window.
 *
 * RESOLVED AGAINST `today` AT REQUEST TIME, never at pick time, so *"a screen left open overnight
 * does not keep searching yesterday"* (`SearchFilters.resolveDateRange`'s own words). Caching the
 * resolved pair is the bug that sentence exists to prevent.
 */
enum class RosterRange(val id: String, val label: String) {
    ANY("any", "Any time"),
    TODAY("today", "Today"),
    LAST_7_DAYS("7d", "Last 7 days"),
    LAST_30_DAYS("30d", "Last 30 days"),
    LAST_90_DAYS("90d", "Last 90 days"),
    THIS_MONTH("month", "This month"),
    THIS_YEAR("year", "This year"),
    CUSTOM("custom", "Custom range"),
}

/**
 * The preset as the concrete `dateFrom`/`dateTo` instants the route takes, or `null to null`.
 *
 * Both bounds are built in the DEVICE's own zone and serialised as instants, and the end of a chosen
 * day is 23:59:59 because the API compares with `lte`: a bare start-of-day bound would drop every
 * row created on the last day of the range, which reads as an off-by-one nobody can see.
 *
 * The same arithmetic as `SearchFilters.resolveDateRange`, re-derived rather than imported because
 * that function's helpers are file-private to `SearchScreen.kt` and this parcel does not own that
 * file. `RosterFilterWireTest` pins the two against each other so the copy cannot drift.
 */
fun resolveRosterRange(
    range: RosterRange,
    from: LocalDate?,
    to: LocalDate?,
    today: LocalDate = LocalDate.now(),
): Pair<String?, String?> {
    val endOfToday = rosterEndOfDay(today)
    return when (range) {
        RosterRange.ANY -> null to null
        RosterRange.TODAY -> rosterStartOfDay(today) to endOfToday
        // Inclusive of today, so "last 7 days" really is seven days and not eight.
        RosterRange.LAST_7_DAYS -> rosterStartOfDay(today.minusDays(6)) to endOfToday
        RosterRange.LAST_30_DAYS -> rosterStartOfDay(today.minusDays(29)) to endOfToday
        RosterRange.LAST_90_DAYS -> rosterStartOfDay(today.minusDays(89)) to endOfToday
        RosterRange.THIS_MONTH -> rosterStartOfDay(today.withDayOfMonth(1)) to endOfToday
        RosterRange.THIS_YEAR -> rosterStartOfDay(today.withDayOfYear(1)) to endOfToday
        RosterRange.CUSTOM -> from?.let(::rosterStartOfDay) to to?.let(::rosterEndOfDay)
    }
}

private fun rosterStartOfDay(date: LocalDate): String =
    date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private fun rosterEndOfDay(date: LocalDate): String =
    date.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toString()

// ---------------------------------------------------------------------------------------------
// The state the two screens hold
// ---------------------------------------------------------------------------------------------

/**
 * Everything both roster screens can be narrowed or ordered by, in one value.
 *
 * ONE SHAPE FOR BOTH ROSTERS, with the fields that do not apply to a screen left at their empty
 * value. A sealed hierarchy would buy type safety over three fields and cost a second copy of the
 * serialiser, the clear-all and the sort logic — which are the parts that can actually be wrong.
 * [rosterQueryParams] guarantees the other half of it: a key that does not belong to a route is never
 * sent to it, whatever this object happens to hold.
 *
 * IMMUTABLE, AND HELD AS ONE VALUE, so a debounce can compare "what is set now" against "what was
 * last asked" in a single equality check and a page read can take a frozen snapshot instead of
 * drifting with a half-typed box — the same reason `SearchFilters` is one value.
 */
data class RosterFilters(
    /**
     * THE APPLIED TERM — what actually went into the last request, not the keystroke in the box.
     *
     * The bar keeps the draft and pushes it here on a debounce, so this field is always safe to
     * render a "nobody matches X" sentence against: it can never name a term the server has not been
     * asked about.
     */
    val search: String = "",
    /** ACCESS ONLY. An empty set is EVERY status — including refused and suspended. Rule (ii). */
    val status: Set<String> = emptySet(),
    /** DESIGNER ONLY. `""` is BOTH standings, by absence. */
    val standing: String = "",
    /**
     * Both rosters. Empty is every tier. May contain the reserved ninth token — [ADMIT_ROLE_DEFAULT]
     * on the allow-list, [ROLE_NEVER_SIGNED_IN] on the designer roster — so the two are NOT
     * interchangeable and a state carrying one is not read on the other.
     */
    val roles: Set<String> = emptySet(),
    /** DESIGNER ONLY. Empty is every institution, rows with none included. May contain [INSTITUTION_NONE]. */
    val institutions: Set<String> = emptySet(),
    /**
     * Which date column the range below bounds. Defaults to `added` and NARROWS NOTHING ON ITS OWN —
     * with no bound resolved, neither this nor the range reaches the wire, which is what makes it
     * safe under rule (ii) to have a column pre-selected at all.
     */
    val dateField: String = "added",
    /** The preset, resolved to instants at REQUEST time. [RosterRange.ANY] is no date filter at all. */
    val range: RosterRange = RosterRange.ANY,
    /** Read only when [range] is [RosterRange.CUSTOM]; either bound may stand alone. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sort: String = ROSTER_DEFAULT_SORT,
    val dir: RosterDir = ROSTER_DEFAULT_DIR,
)

/**
 * THE DEFAULT STATE OF BOTH SCREENS, AND IT NARROWS NOTHING.
 *
 * This is rule (ii) as a value: every filter at its empty state, so the first page an admin sees
 * holds refused rows, suspended rows, every tier and every institution.
 * `rosterQueryParams(kind, emptyRosterFilters(kind))` has a null in every field, so the request built
 * from it is the request these screens made before requirement 30 — no `status` key at all, which is
 * the server's spelling of *every* status.
 *
 * The date column is READ OFF THE PICKER'S OWN FIRST ROW rather than written out a second time here,
 * so the pre-selected column cannot drift from the one at the top of the list the reader is looking
 * at.
 */
fun emptyRosterFilters(kind: RosterKind): RosterFilters = RosterFilters(
    dateField = dateFieldOptions(kind).firstOrNull()?.value ?: "added",
)

// ---------------------------------------------------------------------------------------------
// The labels — §4.8, written once
// ---------------------------------------------------------------------------------------------

/**
 * EVERY VISIBLE LABEL AND EVERY SPOKEN NAME IN THE FILTER SHEET, and they are the same strings.
 *
 * WHY THEY ARE THE SAME STRINGS. `SearchableSelectField` renders its `label` above the trigger AND
 * folds it into the trigger's `contentDescription`, so the two cannot disagree there — but a chip, a
 * button and an `OutlinedTextField` each name themselves differently, and it is trivially easy to
 * ship a control that SHOWS one word and ANNOUNCES another. A voice-control user who says the words
 * they can see does not then reach the control. One constant per label, used for both, makes the
 * mismatch unspellable. The web carries the identical table for the identical reason.
 *
 * WHY THE SEARCH BOXES GET A SENTENCE AND NOT A WORD. Each box searches THREE columns and which
 * three is the thing a reader cannot guess: the allow-list's reaches an admin's private note, and
 * the designer roster's reaches the institution. Naming the columns is the difference between a box
 * you trust and a box you try twice.
 */
object RosterLabels {
    /**
     * THE SHORT NAME OF THE BOX. The sentence naming its columns is [ACCESS_SEARCH] /
     * [DESIGNER_SEARCH] below, drawn as the field's SUPPORTING TEXT.
     *
     * TWO STRINGS FOR ONE CONTROL, AND THE WEB SPLITS IT THE SAME WAY: `RosterFilterBar.tsx` passes
     * the long sentence as `ariaLabel` and a shorter one as the placeholder, because the sentence is
     * the part a reader cannot guess and the box is not wide enough to carry it as a name.
     *
     * WHAT DIFFERS IS WHICH TWO SLOTS, and that is this platform's doing rather than a preference.
     * An `OutlinedTextField` shares its row with the Add button and has roughly 250dp, so a
     * 45-character floating label ellipsises at the default font scale and is unreadable at 2× —
     * which would leave a voice-control user unable to say the words they can see. Supporting text
     * WRAPS, stays visible once something has been typed (a placeholder does not), and Compose folds
     * it into what the field announces. So both halves are visible AND both are spoken, which is the
     * property that actually matters: nothing here is announced that is not on screen, and nothing on
     * screen goes unannounced.
     */
    const val SEARCH = "Search"
    const val ACCESS_SEARCH = "Search the allow-list by email, name or note"
    const val DESIGNER_SEARCH = "Search the roster by email, name or institution"
    const val ACCESS_STATUS = "Standing"
    const val ACCESS_ROLES = "Tier they join at"
    const val DESIGNER_STANDING = "Standing"
    const val DESIGNER_ROLES = "Tier of the linked account"
    const val DESIGNER_INSTITUTIONS = "Institution"
    const val DATE_FIELD = "Which date"
    const val DATE_RANGE = "Date range"
    const val DATE_PERIOD = "Period"
    const val DATE_FROM = "From"
    const val DATE_TO = "To"
    const val SORT = "Order"
    const val CLEAR_ALL = "Clear every filter"
    const val FILTERS = "Filters"
}

/**
 * THE HINT UNDER THE DESIGNER ROSTER'S ROLE PICKER — it says what the filter MEANS, which is not what
 * its label implies.
 *
 * `DesignerRoster` has no role column and no user relation; the join is by lower-cased email. And a
 * roster row whose account is an ADMIN is not gated by this roster at all (`services/designers.py:82`),
 * so "role = Admin" over this list answers "which empanelled addresses belong to admins", NOT "which
 * admins may sign in". An admin who reads it the second way draws a conclusion about their own access
 * control from a list that does not govern it.
 */
const val DESIGNER_ROLE_HINT: String =
    "Matched by email. An admin's row is not gated by this roster, so filtering for Admin lists " +
        "empanelled addresses that belong to admins."

/**
 * THE ONE LINE THE ACCESS FILTER SHEET CARRIES ABOUT A CONTROL THAT IS NOT THERE.
 *
 * `AccessRoster` has no institution column (verified column by column, `schema.prisma:4169-4226`), and
 * adding the filter by joining to `DesignerRoster.institution` on email would narrow the allow-list to
 * *the subset that is also empanelled as a designer* while presenting itself as an institution filter
 * — silently hiding exactly the pending strangers this screen exists to decide about. So the filter is
 * not offered, and the absence is explained rather than left to look like an oversight somebody should
 * "fix".
 */
const val ACCESS_INSTITUTION_NOTE: String =
    "Institution is not recorded on the allow-list — it is a designer-roster field. Filter by it on " +
        "the designer roster."

// ---------------------------------------------------------------------------------------------
// The option vocabularies
// ---------------------------------------------------------------------------------------------

/**
 * The allow-list's standing filter rows, as a MULTI-select over the four states.
 *
 * ── THE LABELS ARE THE ROW BADGE'S WORDS, NOT THE OLD SINGLE-SELECT'S ───────────────────────────
 * The chips this replaces read "May sign in", "Waiting", "Refused", "Suspended" — and the card's own
 * [AccessBadge] prints "May sign in", "Waiting for a decision", "Refused", "Suspended". One
 * vocabulary now, so an admin reading a row and an admin ticking a filter use the same words.
 *
 * ── THERE IS NO "HIDE SUSPENDED" ROW, AND THAT IS DELIBERATE ────────────────────────────────────
 * Rejected twice over. First, rule (ii): a "hide suspended" defaulted ON would put this screen's
 * most-needed row out of view for the exact admin who came to find it. Second, and the reason it is
 * absent rather than merely defaulted off: it is a SECOND SPELLING of ticking the other three, and a
 * filter with two spellings for one state cannot tell a default from a deliberate choice — rule (i).
 */
val ACCESS_STATUS_OPTIONS: List<SelectOption> = listOf(
    SelectOption("ACTIVE", "May sign in"),
    SelectOption("PENDING", "Waiting for a decision"),
    SelectOption("REJECTED", "Refused"),
    SelectOption("SUSPENDED", "Suspended"),
)

/**
 * The designer roster's standing, as a single-select whose FIRST row is the widest one.
 *
 * `""` first and selected by default, carrying the wording both clients have used all along, because
 * the ABSENT parameter is what the server reads as "both standings". The third row is the one this
 * screen could not previously ask for at all: "only the suspended ones" is the query an admin runs
 * when somebody says they have lost access.
 */
val DESIGNER_STANDING_OPTIONS: List<SelectOption> = listOf(
    SelectOption("", "Everyone ever empanelled"),
    SelectOption("active", "Only those who may sign in"),
    SelectOption("suspended", "Only those suspended"),
)

/**
 * THE EIGHT-TIER LADDER PLUS ONE RESERVED ROW, highest tier first.
 *
 * The reserved row is LAST and is named as what it IS rather than as a tier, because it is not one —
 * it is the absence of one. Same placement and same reason as `WorkshopScopeSelect`'s "Not linked to
 * a workshop" row: it has to be tickable or ticking every tier silently drops a whole class of row,
 * but it does not belong among the tiers in the reading order.
 *
 * AND IT IS NOT SEARCHABLE. Nine rows, one above `SEARCH_THRESHOLD` — so left to the option count
 * this control would grow a filter box today and lose it the day a tier is removed. It is a closed
 * vocabulary a reader takes in at a glance, which is the case the threshold exists to separate from
 * a corpus, so the caller passes `searchable = false` outright. The web passes `searchable={false}`
 * and cites the same number.
 */
fun roleOptions(kind: RosterKind): List<SelectOption> {
    val ladder = ROSTER_ROLE_LADDER.map { SelectOption(it, FieldPermissions.label(it)) }
    val reserved = if (kind == RosterKind.ACCESS) {
        SelectOption(
            ADMIT_ROLE_DEFAULT,
            "At the default joining tier",
            "No tier was named when this address was admitted",
        )
    } else {
        SelectOption(
            ROLE_NEVER_SIGNED_IN,
            "Has never signed in",
            "Empanelled, but no account has used this address yet",
        )
    }
    return ladder + reserved
}

/**
 * The served institution vocabulary plus the reserved "no institution" row.
 *
 * De-duplicated by value — see [INSTITUTION_NONE] for the free-text collision that makes that
 * necessary, and for why the de-duplication is a containment rather than a fix. Blank names are
 * dropped rather than rendered as a row nobody can tick.
 */
fun institutionOptions(names: List<String>): List<SelectOption> {
    val seen = mutableSetOf(INSTITUTION_NONE)
    val rows = mutableListOf<SelectOption>()
    names.forEach { raw ->
        val value = raw.trim()
        if (value.isEmpty() || !seen.add(value)) return@forEach
        rows.add(SelectOption(value, value))
    }
    rows.add(SelectOption(INSTITUTION_NONE, "No institution recorded"))
    return rows
}

/** The date columns each roster can bound, in the order they are offered. §4.1's two enums. */
fun dateFieldOptions(kind: RosterKind): List<SelectOption> = if (kind == RosterKind.ACCESS) {
    listOf(
        SelectOption("added", "Added to the list"),
        SelectOption("requested", "Access requested"),
        SelectOption("decided", "Decision made"),
        // `joinedAt` is written ONCE: somebody admitted in 2024, suspended, and restored this morning
        // still joined in 2024. It is not "last approved" and must never be labelled as it.
        SelectOption("joined", "Joined the platform"),
        SelectOption("firstSeen", "First signed in"),
    )
} else {
    listOf(
        SelectOption("added", "Added to the roster"),
        SelectOption("firstSeen", "First signed in"),
        SelectOption("revoked", "Access revoked"),
    )
}

/** The presets as rows. `RosterRange.ANY` is a real choice in this list, so there is no blank above it. */
val RANGE_OPTIONS: List<SelectOption> =
    RosterRange.entries.map { SelectOption(it.id, it.label) }

/**
 * The sort rows for one roster, each carrying what choosing it will DO as its hint.
 *
 * A SINGLE-SELECT AND NOT TAPPABLE HEADERS, because these are card lists and not tables — §4.9's
 * ruling. The web's `aria-sort` on a `<th>` has no counterpart on a screen with no `<th>`.
 */
fun sortOptions(kind: RosterKind, filters: RosterFilters): List<SelectOption> =
    rosterSorts(kind).map { (column, spec) ->
        SelectOption(column, spec.label, sortRowHint(kind, filters, column))
    }

// ---------------------------------------------------------------------------------------------
// The wire
// ---------------------------------------------------------------------------------------------

/**
 * A comma-joined token list in a CANONICAL order, or null when nothing is chosen.
 *
 * ── NULL AND NOT `""` ───────────────────────────────────────────────────────────────────────────
 * Retrofit omits a null `@Query` entirely and sends an EMPTY one as `?roles=`. The server reads both
 * as "do not filter" (`resolve_workshop_ids:65-67`), but only one of them is what "there is no such
 * filter" looks like everywhere else in this app, and only one of them keeps the default request
 * byte-identical to the request these screens made before requirement 30. Rule (i) lives on this line.
 *
 * ── COMMA-JOINED AND NOT REPEATED, AND ON THIS CLIENT THAT IS A SAFETY PROPERTY ─────────────────
 * §4.1 accepts both spellings deliberately — *"the web and Android build query strings differently,
 * and a filter that quietly covered everything because it was spelled the other way would look
 * exactly like the filter not working"* — so either is correct against a server that has §4.1. But
 * against a server that does NOT, the two spellings fail in opposite ways, and only one of them fails
 * loudly. Today `GET /access/roster` declares `status` as a single `str | None`; a REPEATED parameter
 * would be silently reduced to its LAST value by Starlette, so ticking "Waiting" and "Suspended"
 * would return only the suspended rows under a control showing two ticks — a wrong answer dressed as
 * a correct one, which is the exact failure this whole document is about. Comma-joined earns a 422
 * naming the valid values instead, which is a refusal a reader can see. So: one value, always.
 *
 * The order is canonical so the same three ticks cannot produce two different query strings depending
 * on which one the admin ticked first. A token this client does not recognise still goes on the wire,
 * at the end and sorted, because it is not this client's to judge: the server answers an unknown one
 * with a 422 naming the valid values, where dropping it here would silently answer a NARROWER question
 * than the control asked and look exactly like the filter working.
 */
internal fun tokenList(values: Set<String>, order: List<String>): String? {
    val chosen = values.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (chosen.isEmpty()) return null
    val ordered = order.filter { it in chosen }
    val extras = chosen.filterNot { it in ordered }.sorted()
    return (ordered + extras).joinToString(",")
}

/** The institution tokens, alphabetical with the reserved "no institution" row last. */
internal fun institutionList(values: Set<String>): String? {
    val chosen = values.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    if (chosen.isEmpty()) return null
    val named = chosen.filterNot { it == INSTITUTION_NONE }.sorted()
    val tail = if (INSTITUTION_NONE in chosen) listOf(INSTITUTION_NONE) else emptyList()
    return (named + tail).joinToString(",")
}

/** The canonical order of the role tokens for a roster: the ladder, then that roster's reserved row. */
internal fun roleOrder(kind: RosterKind): List<String> =
    ROSTER_ROLE_LADDER + (if (kind == RosterKind.ACCESS) ADMIT_ROLE_DEFAULT else ROLE_NEVER_SIGNED_IN)

/**
 * Every key either roster route takes, all nullable. Null is ABSENT, which is what the server reads
 * as "do not filter" — rule (i).
 *
 * `page` and `pageSize` are deliberately NOT here: the pager is the screen's state, not the filter's,
 * and a filter value that carried a page number could be restored onto the wrong page of a list it
 * had just re-filtered.
 */
data class RosterQueryParams(
    val search: String? = null,
    /** Access only. */
    val status: String? = null,
    /** Designer only. */
    val standing: String? = null,
    val roles: String? = null,
    /** Designer only. */
    val institutions: String? = null,
    val dateField: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    val sort: String? = null,
    val dir: String? = null,
) {
    /**
     * The §4.1 keys this request actually carries — the ones a server predating that section will
     * DROP IN SILENCE rather than refuse.
     *
     * `search` is absent on purpose and so is a LONE `status`: both routes have accepted those since
     * before requirement 30, so every deployment applies them. A COMMA-JOINED `status` is not old
     * grammar and is listed — see the comment on the first line below. `standing` is listed too: the
     * older spelling of that question is `activeOnly`, and this client no longer sends it (see
     * [rosterQueryParams]).
     *
     * The names are CONTROLS as an administrator sees them ("standing", "roles", "date range",
     * "order"), not wire keys, because [rosterFilterGrammarNotice] and [rosterFilterRefusalHint] put
     * them in a sentence that asks somebody to go and change one.
     */
    val newGrammarKeys: List<String>
        get() = buildList {
            // A LONE `status` IS OLD GRAMMAR AND A COMMA-JOINED ONE IS NOT. `?status=PENDING` has been
            // on this wire since the screen was written; `?status=PENDING,SUSPENDED` is §4.1, and
            // against a route that still declares one `str` it is a 422 rather than a silent pass —
            // so the chips are at risk in exactly the case where two or more are ticked, and the
            // control an admin has to look at is the one labelled "Standing".
            if (status?.contains(',') == true) add("standing")
            if (standing != null) add("standing")
            if (roles != null) add("roles")
            if (institutions != null) add("institutions")
            if (dateFrom != null || dateTo != null) add("date range")
            if (sort != null) add("order")
        }.distinct()
}

/**
 * THE FILTERS AS QUERY KEYS — every active filter ANDs into ONE request rather than being applied in
 * passes, because a second pass is a second request and two requests over one list is how a screen
 * ends up showing the intersection of two different moments.
 *
 * ── `today` IS A PARAMETER AND THE DEFAULT IS THE POINT ─────────────────────────────────────────
 * Presets resolve to concrete instants HERE, on the device, because only the device knows the reader's
 * clock — and they resolve at REQUEST time. Calling this once and remembering the result is the bug
 * that wording exists to prevent. The parameter is there so a test can pin a clock.
 *
 * ── WHY A KEY THAT DOES NOT BELONG TO A ROUTE IS NEVER SENT TO IT ───────────────────────────────
 * `status` on the designer roster and `institutions` on the allow-list are not harmless extras: an
 * undeclared parameter is either ignored (so the admin's filter silently did nothing) or refused, and
 * the first of those is indistinguishable from the filter being broken. The [kind] gate is what makes
 * one value safe to hold for two screens.
 *
 * ── AND WHY `activeOnly` IS NEVER EMITTED ───────────────────────────────────────────────────────
 * The designer route keeps `activeOnly` exactly as it is for a client that has not been updated, and
 * `standing` is the new spelling of the same question. Sending both is a 422 rather than a silent
 * winner, so this client sends only `standing` — and `WorkshopRepository.designerRoster` stopped
 * sending `activeOnly` in the same change that introduced this, or every request would 422 the moment
 * "Only those suspended" was chosen.
 */
fun rosterQueryParams(
    kind: RosterKind,
    filters: RosterFilters,
    today: LocalDate = LocalDate.now(),
): RosterQueryParams {
    val access = kind == RosterKind.ACCESS
    val (dateFrom, dateTo) = resolveRosterRange(filters.range, filters.from, filters.to, today)
    // A range with no bound is not a filter. "Custom range" with both boxes empty resolves to nothing,
    // and sending a bare `dateField` for it would put a key on the wire that narrows nothing and reads,
    // in a log, as a filter that was applied.
    val dated = dateFrom != null || dateTo != null
    val ordered = filters.sort == ROSTER_DEFAULT_SORT && filters.dir == ROSTER_DEFAULT_DIR
    return RosterQueryParams(
        search = filters.search.trim().ifBlank { null },
        status = if (access) tokenList(filters.status, ACCESS_STATUS_TOKENS) else null,
        standing = if (access) null else filters.standing.ifBlank { null },
        roles = tokenList(filters.roles, roleOrder(kind)),
        institutions = if (access) null else institutionList(filters.institutions),
        dateField = if (dated) filters.dateField else null,
        dateFrom = if (dated) dateFrom else null,
        dateTo = if (dated) dateTo else null,
        // The server's own default pair is left off entirely, so the default state of these screens
        // produces the request they made before requirement 30. Anything else is sent as a PAIR:
        // `dir` alone would be read against a column this client did not name, and `sort` alone would
        // be defaulted to `desc` by the route — which is Z-to-A on `email`.
        sort = if (ordered) null else filters.sort,
        dir = if (ordered) null else filters.dir.token,
    )
}

// ---------------------------------------------------------------------------------------------
// Clear-all
// ---------------------------------------------------------------------------------------------

/**
 * Is anything NARROWING this list?
 *
 * Read off the CONTROLS and not off the wire, because this is what decides whether "Clear every
 * filter" is on screen: a reader who has set the period to "Custom range" and typed no dates has
 * visibly changed a control and must be able to put it back, even though that state sends no date
 * keys.
 *
 * THE ORDER IS NOT A FILTER AND IS NOT COUNTED. Counting it would put a button on screen that, when
 * pressed, changed nothing a reader could see — because [clearRosterFilters] deliberately keeps it.
 */
fun hasActiveRosterFilters(kind: RosterKind, filters: RosterFilters): Boolean {
    val access = kind == RosterKind.ACCESS
    return filters.search.isNotBlank() ||
        (access && filters.status.isNotEmpty()) ||
        (!access && filters.standing.isNotBlank()) ||
        filters.roles.isNotEmpty() ||
        (!access && filters.institutions.isNotEmpty()) ||
        filters.range != RosterRange.ANY
}

/**
 * How many of them, for the badge on the Filters button.
 *
 * The standing chips are deliberately NOT counted on EITHER roster: they are on screen whether the
 * sheet is open or shut, and a badge counting something already visible reads as a second,
 * disagreeing filter. The same call `SearchFilters.sheetFilterCount` makes.
 */
fun sheetFilterCount(kind: RosterKind, filters: RosterFilters): Int {
    val access = kind == RosterKind.ACCESS
    return listOf(
        filters.roles.isNotEmpty(),
        !access && filters.institutions.isNotEmpty(),
        filters.range != RosterRange.ANY,
    ).count { it }
}

/**
 * One allow-list standing chip tapped, and the whole of rule (i) on the four of them.
 *
 * ── TICKING THE FOURTH COLLAPSES TO NONE, AND THAT IS THE RULE ENFORCED RATHER THAN STATED ───────
 *
 * `AccessRoster.status` is a non-null enum with exactly these four values, so "all four ticked" and
 * "none ticked" return the same rows. If both states existed the control would have two spellings
 * for one question — and there would then be no way to tell a default apart from a deliberate
 * choice, nor to say what a filter somebody described to a colleague actually meant. On a web
 * multi-select the all-ticked state is unreachable without a "select all" button, which is why the
 * primitive publishes `bulk = false`; on a row of chips it is four taps away, so it is collapsed the
 * moment it is reached.
 *
 * A PURE FUNCTION AND NOT A TERNARY IN THE COMPOSABLE, because the collapse is a rule and the only
 * way to exercise a decision buried in a chip's `onClick` is to look at a screen. `RosterFilterWireTest`
 * pins it.
 */
fun toggledAccessStatus(selected: Set<String>, token: String): Set<String> {
    val next = if (token in selected) selected - token else selected + token
    return if (next.size >= ACCESS_STATUS_TOKENS.size) emptySet() else next
}

/**
 * Every filter back to its empty state — WITH THE ORDER LEFT EXACTLY AS IT IS.
 *
 * The button says "Clear every filter", and an order is not a filter: it narrows nothing and hides
 * nobody. An admin who has sorted by "first signed in" to find outstanding invitations and then
 * clears a search is still asking that question; throwing their order away as well is a second,
 * unasked-for change dressed up as tidying.
 */
fun clearRosterFilters(kind: RosterKind, filters: RosterFilters): RosterFilters =
    emptyRosterFilters(kind).copy(sort = filters.sort, dir = filters.dir)

// ---------------------------------------------------------------------------------------------
// The four cuts these controls can cause, and not one of them is visible in the rows
// ---------------------------------------------------------------------------------------------

/**
 * THE DESIGNER ROLE FILTER READ A BOUNDED NUMBER OF ACCOUNTS, SO SOME MATCHING DESIGNERS ARE MISSING
 * FROM EVERY PAGE OF THIS ANSWER.
 *
 * `DesignerRoster` has no role column and no user relation, so filtering by tier means reading the
 * ACCOUNTS that hold those tiers and folding their emails into the roster query's WHERE. That read is
 * bounded — an unbounded one is not a thing to ship — and when it is cut the consequence is not a
 * short page: it is a MATCHING DESIGNER VANISHING from the list, on every page, for every filter
 * naming those roles, exactly as though they had never been empanelled.
 *
 * ── WHY NOT `flagCutNotice`'s WORDING, WHICH IS WHAT §4.4 SPECIFIES ─────────────────────────────
 * Because both of its arms give advice that cannot work here. They are *"narrow the search above"*
 * and *"search for a name above to reach them"* — but the cut happened UPSTREAM of the search, in the
 * account read. Narrowing the roster search shrinks the roster query and does not put a single unread
 * account back, so the missing designers stay missing and the reader has been sent to do something
 * that cannot help. That is the shape of defect this whole cluster of rules exists to close. The
 * sentence is worded here instead, with the move that DOES work: name fewer tiers, and fewer accounts
 * have to be read. The web reached the identical conclusion independently and its
 * `roleMatchCutNotice` is this string.
 *
 * ── THE NUMBER IS OPTIONAL AND SAYING NOTHING IS NOT ────────────────────────────────────────────
 * The wire carries a boolean; the limit is the server's constant, mirrored as [ROLE_MATCH_READ_LIMIT].
 * Where it is known it is printed, and where it is not the fact is stated without it — `queueCutNotice`
 * makes exactly this trade in these words: *"the honest fallback is the fact WITHOUT the numbers.
 * Saying nothing at all would be the one unacceptable answer."*
 *
 * `null` is silence, and that is [com.designprototype.workshop.data.RosterPageDto]'s three-state read:
 * an older deployment does not send the key at all, and "we were told nothing" must not be rendered as
 * "we checked, and there is no cut".
 */
fun roleMatchCutNotice(truncated: Boolean?, limit: Int? = ROLE_MATCH_READ_LIMIT): String? {
    if (truncated != true) return null
    val bound = if (limit != null && limit > 0) {
        "more than $limit accounts"
    } else {
        "more accounts than this filter reads in one pass"
    }
    return "Some designers holding the selected tiers are missing from this list. Matching a tier " +
        "means reading the accounts that hold it, and $bound do — the ones past that point were " +
        "not read, so their roster rows cannot appear on any page of this filter. Choosing fewer " +
        "tiers reads fewer accounts and gives a complete answer."
}

/**
 * THE SAME FLAG ON THE ALLOW-LIST, WHERE THE MECHANISM IS DIFFERENT — a sentence that should never be
 * reached, and which exists because "should never" is not a reason to be silent.
 *
 * `AccessRoster.admitRole` IS a column, so matching a tier there needs no second read and nothing can
 * fall off the end of one; §4.4 documents the flag as always false on this route. [roleMatchCutNotice]
 * is exact for the designer roster and WRONG about the mechanism here — it explains that matching a
 * tier means reading the accounts that hold it, which is true of `DesignerRoster` and false of
 * `AccessRoster` — and it says "designers", which these rows are not. A reader given a mechanism that
 * does not apply cannot act on it.
 *
 * So this says the two facts that survive without knowing the mechanism: entries are missing from
 * every page of this filter, and choosing fewer tiers is the move. No number, because this client has
 * not read one, and never print a cap you did not read.
 */
fun accessRoleCutNotice(truncated: Boolean?): String? {
    if (truncated != true) return null
    return "The server could not match the chosen tiers completely, so some entries are missing " +
        "from every page of this filter — not only from this one. Choosing fewer tiers narrows " +
        "what has to be matched and gives a complete answer; clearing the tier filter lists everyone."
}

/**
 * THE INSTITUTION VOCABULARY IS CAPPED BY THE SERVER, AND THE CAP HAS TO BE ON SCREEN.
 *
 * `GET /designers/roster/institutions` reads one row past its cap and reports `truncated`, so the flag
 * is EXACT rather than inferred. Past that point an institution simply has no row in the picker: an
 * admin looking for it finds nothing, and "not in the list" reads as "nobody is from there" — absence
 * read as non-existence.
 *
 * THE NUMBER IS [offered] AND NOT A CONSTANT, because never print a cap you did not read. The
 * endpoint's `take` is the server's to change, and a stated cap that is not the enforced cap is worse
 * than no sentence at all — so the number printed is the count of names this control was actually
 * handed, which is a fact this client can see. The flag decides whether there is anything to say; the
 * count only decides the wording.
 *
 * The move it names is the one that works: typing the institution into the ROSTER search box, because
 * `search` is OR-ed over `institution` on that route and reaches the whole table.
 */
fun institutionCutNotice(truncated: Boolean?, offered: Int): String? {
    if (truncated != true) return null
    if (offered <= 0) {
        return "There are more institutions than this list can hold, so some cannot be ticked here. " +
            "Type the institution into the search box above instead — it is searched on the server."
    }
    return "Only the first $offered institutions are offered here and there are more, so an " +
        "institution past that point cannot be ticked. Type its name into the search box above " +
        "instead — it is searched on the server, over the whole roster."
}

/**
 * THE SERVER DID NOT UNDERSTAND THIS FILTER, SO THE LIST BELOW IS NOT NARROWED BY IT — the fourth cut,
 * and the only one that is a property of the ROLLOUT rather than of the data.
 *
 * ── THE FAILURE, EXACTLY ────────────────────────────────────────────────────────────────────────
 * FastAPI drops an undeclared query parameter in SILENCE. It does not 422 it and it does not log it.
 * So against a deployment that predates §4.1, `?roles=DESIGNER&sort=name&dir=asc` is answered with the
 * whole roster in `createdAt desc` order and a 200 — while three controls on the handset say
 * otherwise. That is R3 arriving through a version skew: the control is not saying which case it is
 * in, and the admin concludes from an unnarrowed list that everybody holds that tier, or from a
 * missing person that they were never empanelled. It is the same lie as a silently empty picker, told
 * in the other direction.
 *
 * ── HOW THIS CLIENT KNOWS, AND THE LIMIT OF THAT KNOWLEDGE ──────────────────────────────────────
 * There is no capability endpoint in this API and nothing to probe. What there IS is §4.4's
 * `roleMatchTruncated`, which both routes are specified to return UNCONDITIONALLY — so its presence
 * on an answer is the one observable difference between a server that has the filter grammar and one
 * that does not. [understood] is that: `true` when the last successful answer carried the key.
 *
 * The inference is honest but not free, and the limit is stated rather than hidden: a deployment that
 * shipped §4.4's flag while omitting some other §4.1 parameter would satisfy it and still ignore a
 * filter. The failure is then the silent one again, for that parameter only. A `filtersApplied` echo
 * on the envelope would settle it outright and is worth asking the route for; until then this is the
 * strongest signal on the wire, and it is strictly better than assuming.
 *
 * ── WHY THE CONTROLS ARE STILL DRAWN AND STILL SENT ─────────────────────────────────────────────
 * Because the alternative is worse in both directions. Hiding them until an answer arrives means the
 * screen changes shape under the reader on every open, and a reader cannot learn a control that comes
 * and goes; refusing to send them means the day the server is upgraded the handset is still asking
 * the old question. So the parameters go every time, and this sentence is what keeps the answer
 * honest in the meantime.
 *
 * @param understood null before any answer has arrived — say nothing rather than accuse a server that
 *   has not spoken yet.
 * @param sent the §4.1-only keys this request actually carried; empty means nothing was at risk, and
 *   the sentence is not printed. `search` and `status` are never in it — both routes have accepted
 *   those since before requirement 30.
 */
fun rosterFilterGrammarNotice(understood: Boolean?, sent: List<String>): String? {
    if (understood != false || sent.isEmpty()) return null
    return "This server has not been updated to filter or order the roster, so the " +
        "${namedControls(sent)} you set were not applied — the list below is the whole roster in " +
        "its usual order, not the answer to what you asked. Nothing is missing from it. Read it as " +
        "unfiltered, or ask for the server to be updated."
}

/**
 * THE SAME SKEW, BUT THE REQUEST WAS REFUSED RATHER THAN QUIETLY WIDENED — and it is a REACHABLE
 * state today, not a hypothetical.
 *
 * FastAPI drops a parameter it does not declare, but it does NOT drop a value it cannot parse. A
 * route that still declares `status` as one `str` answers `?status=PENDING,SUSPENDED` with a 422
 * reading *"Unknown access status 'PENDING,SUSPENDED'. One of: ACTIVE, PENDING, REJECTED,
 * SUSPENDED."* — a refusal naming four values the admin can see they ticked two of. Left alone, the
 * screen prints that verbatim under "The list could not be loaded" and an administrator reasonably
 * concludes the product is broken rather than that their server is behind their handset.
 *
 * So the failed-read state carries this line whenever new grammar was in the request and this client
 * has never seen a §4.1 answer. It does not REPLACE the server's words — those stay, because the
 * refusal may have been about something else entirely — it says what the likely cause is and names
 * the move that gets a list back. `understood == true` suppresses it: a server that has answered with
 * the flag once understands the grammar, so a later failure is an ordinary one.
 */
fun rosterFilterRefusalHint(understood: Boolean?, sent: List<String>): String? {
    if (understood == true || sent.isEmpty()) return null
    return "If this server has not been updated for the roster filters, the ${namedControls(sent)} " +
        "you set are what it refused rather than anything about the people on the list. Clearing " +
        "them lists everybody again."
}

/** "standing", "standing and roles", "standing, roles and order" — one comma rule, said once. */
private fun namedControls(sent: List<String>): String = when (sent.size) {
    0 -> ""
    1 -> sent[0]
    2 -> "${sent[0]} and ${sent[1]}"
    else -> sent.dropLast(1).joinToString(", ") + " and " + sent.last()
}
