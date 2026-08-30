package com.designprototype.workshop.data

import kotlinx.serialization.Serializable

/**
 * THE TWO ROSTER LISTS' ANSWERS — `page_payload(...)` plus the one field it has no room for.
 *
 * ── WHY NOT [PageResponse], WHICH IS THE SAME FIVE KEYS ──────────────────────────────────────────
 *
 * Because `services/pagination.page_payload` builds a fixed envelope
 * (`{items,total,page,pageSize,pages}`) that every list route in this API shares, and
 * DROPDOWN_DESIGN §4.4 needed one more fact on exactly two of them — so both roster routes answer
 * `page_payload(...) | {"roleMatchTruncated": bool}`. Adding the key to [PageResponse] would put it
 * on thirty other lists that never send it, where its absence would then be indistinguishable from
 * its being false. Two facts, two types.
 *
 * ── [roleMatchTruncated] IS NULLABLE, AND THE NULL IS THE WHOLE OF IT ────────────────────────────
 *
 * Three states, not two, and they are three different things to say to an administrator:
 *
 *  - `true`  — the server matched the chosen tiers against a BOUNDED read of the accounts table and
 *              ran out of budget. Rows that match are missing from EVERY page of this filter. The
 *              screen must say so with the number (`ROLE_MATCH_READ_LIMIT`); a role filter that
 *              silently misses people is worse than no role filter, because an admin concludes the
 *              person was never empanelled.
 *  - `false` — the server understood the filter grammar and nothing was cut. Silence is correct.
 *  - `null`  — THE KEY WAS NOT ON THE WIRE AT ALL. On a deployment that predates §4.1 this is what
 *              arrives, and it means something much larger than "no truncation": that server does
 *              not know `roles`, `institutions`, `dateField`, `sort` or `dir` either, and FastAPI
 *              DROPS an undeclared query parameter in silence rather than refusing it. So the admin
 *              would be looking at an unnarrowed, unordered list under controls that say otherwise —
 *              the exact failure R3 is about, arriving through a rollout rather than through a bug.
 *              `rosterFilterGrammarNotice` turns that null into a sentence. See
 *              `ui/RosterFilters.kt`.
 *
 * `cappedList.flagCutNotice:196-199` on the web takes the same three-state reading of the same key
 * for the same reason: *"a field the server has not shipped yet arrives as `undefined` and must read
 * as 'nothing to say' rather than as a cut."* This client goes one step further and reads it as
 * "nothing was filtered", because unlike the web it has no second signal to fall back on.
 *
 * EVERY FIELD IS DEFAULTED, for [DesignerRosterDto]'s reason: a handset updates over the air and may
 * be older or newer than the API it is talking to, and one non-defaulted field the server has not
 * started sending yet makes kotlinx throw `MissingFieldException` and takes the whole roster screen
 * down over a column nobody was reading.
 */
@Serializable
data class RosterPageDto<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 0,
    val pages: Int = 0,
    /** See the class note: `null` is "this server said nothing", NOT "nothing was cut". */
    val roleMatchTruncated: Boolean? = null,
)

/**
 * `GET /designers/roster/institutions` — the vocabulary behind the institution filter.
 *
 * `DesignerRoster.institution` is FREE TEXT (`schema.prisma:3954`), so an exact-match filter over it
 * is only usable behind a picker of the values that actually exist. The names come from the server
 * and never from the page of rows on screen: a picker assembled from the fifteen rows this handset
 * happens to be holding can only ever offer the institutions those fifteen rows carried, and an
 * admin filtering for one that is two pages down would find no row for it and read that as "nobody
 * is from there" — rule (iv)'s failure wearing a picker.
 *
 * [truncated] is read one row past the cap on the server (`tasks.py:1243-1245`'s manner), so it is
 * EXACT rather than inferred from a length. Nullable for [RosterPageDto.roleMatchTruncated]'s
 * reason: absent is "this deployment told us nothing", which is not the same fact as `false`.
 */
@Serializable
data class RosterInstitutionsDto(
    val items: List<String> = emptyList(),
    val total: Int = 0,
    val truncated: Boolean? = null,
)

/**
 * How many accounts the designer roster's role filter reads before it stops — `ROLE_MATCH_READ_LIMIT`
 * in `api/routes/designers.py`, mirrored here so the screen can print the NUMBER.
 *
 * A DIFFERENT QUANTITY FROM A PAGE SIZE. `DesignerRoster` has no role column and no user relation
 * (verified column by column at `schema.prisma:3945-3973`), so "filter by tier" means reading the
 * ACCOUNTS that hold those tiers and folding their emails into the roster query's WHERE. An account
 * that falls off the end of that read does not shorten a page — it makes a matching DESIGNER VANISH
 * from every page of the filter, as though they had never been empanelled.
 *
 * Mirrored for `DESIGNER_DIRECTORY_CAP`'s reason and with its caveat: a stated cap that is not the
 * enforced cap is worse than no sentence at all, so if the server's constant moves, this moves in
 * the same commit. The flag on the wire decides whether there is anything to say; this number only
 * decides the wording, and the sentence has an arm that works without it.
 */
const val ROLE_MATCH_READ_LIMIT: Int = 50_000
