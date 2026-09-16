package com.designprototype.workshop.ui

import com.designprototype.workshop.data.ArtisanDto
import java.util.Locale

/*
 * THE RULES BEHIND THE TOOL FORM'S TWO MULTI-SELECTS, LIFTED OUT OF THE COMPOSABLE.
 *
 * ── WHY THIS FILE EXISTS, AND WHY IT IS NOT IN `MainActivity.kt` ───────────────────────────────
 *
 * Every function here decides something that DESTROYS A LINK when it is wrong: which artisans a
 * craft deselection drops, what order the artisan sheet lists rows in, whether the English name is
 * still following the toolkit name. There is no `ui-test-junit4` and no Robolectric in
 * `app/build.gradle.kts`, so a judgement written inside a `@Composable` cannot be asserted at
 * all — the JVM suite cannot render a form to look at it. Pulled out, these are pinned by
 * `RecordPickersTest` in plain JUnit, which is the same trade `SearchableSelect.dwShouldSearch`,
 * `RecordMeasureField.dwRecordMeasureTargets` and every other decision in this app that matters
 * more than its pixels has already made.
 *
 * The cross-surface contract asked for the deselection rule to sit beside the form in
 * `MainActivity.kt`, on the grounds that this repository has no `ui/RecordPickers.kt` to put it in.
 * It has one now, at the path the sibling repository already uses for the same function, for the
 * reason that contract itself gives one section earlier about `DimensionUnits.kt`: `ui/` is where a
 * unit test can reach it. The two repositories' layouts converge rather than diverge.
 *
 * ── THE ORDERING RULES ARE A CROSS-LANGUAGE CONTRACT, NOT A PREFERENCE ────────────────────────
 *
 * The browser sorts the same artisans with the same five-part key, and the two must agree on a
 * Devanagari or Gujarati craft name as exactly as they agree on "Bagru". So: `lowercase()` — the
 * NO-ARGUMENT overload, which is `Locale.ROOT` and matches ECMAScript's locale-independent
 * `toLowerCase`, notably on the Turkish dotted I — and then `compareTo`, which is UTF-16 code-unit
 * order, exactly like JavaScript's `<`. FORBIDDEN here, in both directions:
 * `java.text.Collator`, `String.CASE_INSENSITIVE_ORDER`, `compareTo(other, ignoreCase = true)` and
 * `lowercase(Locale.getDefault())`. Every one is either ICU-version-dependent, locale-dependent, or
 * char-by-char-with-both-cases, and each would make the two clients order the same data differently.
 */

/**
 * Whether "English name" should still follow "Toolkit name" on a form opened over [englishName] and
 * [toolkitName] — the ARMED/DIVORCED decision, made ONCE at form construction and never again.
 *
 * ── THE ONE-WAY DOOR ──────────────────────────────────────────────────────────────────────────
 *
 * Armed, every write to the toolkit name — a keystroke, a carry-context apply, a prefill — copies
 * itself into the English box. The moment the designer touches the English box themselves the
 * mirroring stops for the rest of the session and never re-arms, INCLUDING when what they did was
 * empty it: clearing a box by hand is a statement about that box, and refilling it from the toolkit
 * name on the next keystroke would be the form arguing with the person using it.
 *
 * ── WHY AN EDIT WITH A DIFFERENT ENGLISH NAME OPENS DIVORCED ─────────────────────────────────
 *
 * Because the alternative silently rewrites saved data. A record whose English name genuinely
 * differs from its toolkit name is the normal case for a tool that HAS a translated name, and a form
 * that armed itself over it would overwrite that translation on the first correction to the toolkit
 * spelling — under a 200, with no warning, on a government record. An empty English name, or one
 * that is byte-for-byte the toolkit name, carries no such statement, so those stay armed.
 *
 * RAW EQUALITY, no trim and no case fold. `titleCase` is applied by the SERVER (`clean_data`'s
 * `TITLE_CASE_FIELDS` holds both columns), so two values that differ only in case are two values
 * this client was handed differently and has no business declaring equal. The mirror copies the raw
 * typed string for the same reason, and that is what makes the two stored values identical:
 * `titleCase(x) == titleCase(x)` for any `x`, so normalising here would be a second implementation
 * of a server rule that could only ever drift from it.
 */
fun englishNameMirrorArmed(toolkitName: String?, englishName: String?): Boolean {
    val english = englishName.orEmpty()
    if (english.isBlank()) return true
    return english == toolkitName.orEmpty()
}

/**
 * The linked artisans a CRAFT DESELECTION must drop: those this form knows practise only crafts that
 * have just been REMOVED.
 *
 * ── THE SPIRIT OF THE SINGLE-SELECT RULE IT REPLACES ──────────────────────────────────────────
 *
 * The tool form used to hold one craft, and changing it cleared the artisan outright — the artisan
 * belonged to the old craft and could not belong to the new one. With several crafts at once that
 * reasoning still holds for ONE artisan at a time: an artisan whose craft has JUST BEEN UNTICKED has
 * nothing keeping them on the list, and an artisan of a craft that IS still ticked has everything.
 *
 * ── WHY [removedCraftIds] IS A PARAMETER RATHER THAN SOMETHING THIS FUNCTION INFERS ───────────
 *
 * Because "not covered by the new selection" and "orphaned by this change" are different sets, and
 * until 2026-09-16 this function was handed only the first of them. It took `nextCraftIds`,
 * `artisanIds` and the roster, and dropped every artisan whose known craft was absent from
 * `nextCraftIds` — a RECONCILIATION over the whole selection, where its own heading promises a diff
 * against the change. So it fired on craft changes that had removed nothing at all:
 *
 *  · TICKING AN EXTRA CRAFT dropped every linked artisan whose craft was not in the new list. Those
 *    are precisely the links the assignment screen makes — `POST /tools/{id}/artisans` writes
 *    `ToolArtisan` rows without touching `ToolCraft`, and that screen exists to link one tool to
 *    artisans across the same or DIFFERENT crafts — which is why the tool form's own pool keeps them
 *    on screen with `|| it.id in artisanIds`. A designer who added a craft lost an assignment.
 *  · UNTICKING AN UNRELATED CRAFT dropped the same artisans for the same reason, which is a straight
 *    contradiction of "drop exactly the artisans belonging only to the craft that went".
 *
 * Neither is visible while it happens: the form always sends the whole list and `_write_links` is
 * `delete_many` + `create_many`, so the chip that quietly disappeared is a deleted join row.
 *
 * The removed crafts are passed IN rather than guarded against at the one call site, because the
 * sentence at the top of this block is the contract and a function that cannot see a deselection
 * cannot keep it. With nothing removed the answer is empty by construction, and a test says so.
 *
 * ── AND THE TWO CASES THAT ARE KEPT RATHER THAN DROPPED, WHICH ARE THE POINT ──────────────────
 *
 * An artisan this form cannot SEE is never dropped. "Not on the list" and "not of that craft" are
 * different observations: the register is one server page deep, so an artisan of a still-ticked
 * craft can easily be absent from it, and reading absence as disqualification would delete a link
 * the record legitimately holds — silently, on a save about something else. Neither is an artisan
 * whose record carries NO craft at all: nothing says they belong to the removed one.
 *
 * Returns the ids to DROP rather than the surviving selection, so the caller can say what it
 * removed. Order follows [artisanIds], which is the order the wire keeps.
 *
 * @param nextCraftIds the whole selection as it stands after the change.
 * @param removedCraftIds the crafts this change UNTICKED — the previous selection minus
 *   [nextCraftIds]. Empty for a pure addition, and then nothing is dropped.
 */
fun craftsChangeClearsArtisans(
    nextCraftIds: List<String>,
    removedCraftIds: List<String>,
    artisanIds: List<String>,
    artisans: List<ArtisanDto>,
): List<String> {
    // NOTHING WAS DESELECTED, SO NOTHING IS ORPHANED. Written as its own line rather than left to
    // fall out of the predicate below, because it is the whole of the defect this signature closed
    // and a reader skimming the filter should not have to re-derive it.
    if (removedCraftIds.isEmpty()) return emptyList()
    return artisanIds.filter { id ->
        val known = artisans.firstOrNull { it.id == id } ?: return@filter false
        val craftId = known.craftId
        if (craftId.isNullOrBlank()) return@filter false
        // BOTH CLAUSES, AND EACH REFUSES A DIFFERENT WRONG ANSWER. The first is "this change is what
        // orphaned them": an artisan of a craft nobody touched is none of this change's business.
        // The second is "and nothing else covers them": one sheet answer can untick a craft and tick
        // another that covers the same artisan, and an artisan the surviving selection still covers
        // has not been orphaned by anything.
        craftId in removedCraftIds && craftId !in nextCraftIds
    }
}

/**
 * The ids a link multi-select OPENS with: THE RECORD'S OWN SCALAR FIRST, then its stored link rows.
 *
 * ── WHY THE SCALAR LEADS, AND WHY THIS IS NOT "the links, falling back to the column" ─────────
 *
 * WHAT THE TOOL FORM DID UNTIL 2026-09-16, because it is the instruction being reversed: it read
 * `craftLinks`/`artisanLinks` and fell back to `craftId`/`artisanId` only when the list came back
 * empty, on the argument that *"Reading the scalar FIRST would throw away the second and third
 * selected craft of every multi-craft tool."* Reading the scalar INSTEAD OF the links would; reading
 * it first and then appending them throws nothing away, and the old shape had a defect this one has
 * not.
 *
 * The server derives `tool.craftId := craftIds[0]` and `tool.artisanId := artisanIds[0]` from
 * whatever this form sends, and `_write_links` REPLACES the whole link set on a PATCH that carries
 * the list. So the seed decides what element 0 is, and element 0 decides whether merely opening a
 * record and pressing Save changes which artisan it names.
 *
 * That is not hypothetical on the artisan side. `ToolArtisan` predates this multi-select: its rows
 * are written by `POST /tools/{id}/artisans` — the "assign a tool to multiple artisans" panel —
 * which never includes the tool's OWN `artisanId`, and `unassign_tool_artisan` deletes a row without
 * touching it either. So a tool documented under A and assigned to B and C arrives with
 * `artisanLinks = [B, C]` and `artisanId = A`. Seeded from the links alone this form opened with B
 * and C ticked, A nowhere and the "Artisan name" box still reading A's name — and the first save set
 * `artisanId := B` while `artisanName`/`place`, which the server does NOT derive, went on naming A.
 * A record that names one person in its id and another in its text, written by opening it to fix a
 * typo in Remarks. The browser seeds scalar-first and did not, so the two clients produced
 * contradictory records from one starting state; they now seed identically.
 *
 * The craft side is milder and gets the same treatment anyway: the migration backfills `ToolCraft`
 * from `craftId`, and `_order_links` sorts `craftLinks` by each name's position in the stored
 * `craftName`, whose first name is that craft's — but a craft RENAMED since the save is no longer
 * findable in that string and is appended LAST, which makes `craftLinks[0]` some other craft.
 * Leading with the scalar costs nothing while the invariant holds and repairs the record when it
 * does not.
 *
 * ── AND THE LINKS ARE NOT RE-SORTED ──────────────────────────────────────────────────────────
 *
 * After the scalar they keep the order the server stated — `craftLinks` in `craftName` order,
 * `artisanLinks` oldest first — and both are part of what a save writes back, so re-sorting here
 * would rewrite the record by merely opening it. Duplicates collapse keeping FIRST occurrence (the
 * scalar's place), which is the same `dict.fromkeys` rule the route applies on the way in, so what
 * this form holds is what the server will store. Blanks are dropped.
 *
 * [fallback] alone is what a create carries — a carried craft, a handoff prefill — and what a row
 * saved before the join table existed legitimately has.
 */
fun initialLinkIds(linked: List<String>?, fallback: String?): List<String> =
    (listOfNotNull(fallback) + linked.orEmpty()).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * The new ordered selection, from the whole set a picker sheet hands back.
 *
 * ── WHY ORDER IS WORTH A FUNCTION ─────────────────────────────────────────────────────────────
 *
 * `craftIds` is an ORDERED list on the wire: the server writes `craftId` from element 0 and joins
 * `craftName` in exactly this sequence. [SearchableMultiSelectField] answers with a `Set`, which has
 * none, so the order has to be reconstructed here or a designer's first-ticked craft would become
 * whichever one the set happened to iterate first. Survivors keep their places; newcomers are
 * appended in the OPTION list's own order, which is deterministic even when the answer arrives all
 * at once from "Select all N shown".
 *
 * ── AND WHY AN ID THE SHEET COULD NOT SEE IS KEPT ────────────────────────────────────────────
 *
 * [optionIds] is everything the sheet was able to offer. A selected id that is not among them was
 * never drawn, so it could not have been unticked, so the sheet's answer says NOTHING about it —
 * and dropping it would be this function deleting a link on the strength of a question nobody was
 * asked. The tool form gives such an id an off-page row of its own ([offPageLinkRow]) so the case is
 * usually unreachable; this is the belt under that brace, and it is the same rule
 * `offPageWorkshopRow` exists to enforce one control along.
 */
fun mergeSelection(current: List<String>, next: Set<String>, optionIds: List<String>): List<String> {
    val offerable = optionIds.toSet()
    val kept = current.filter { it in next || it !in offerable }
    return kept + optionIds.filter { it in next && it !in current }
}

/**
 * The craft an artisan is being listed under, for the sort below and the row's own hint.
 *
 * Three sources in order, and the third is a real answer rather than a failure: the artisan's own
 * embedded craft when the list carried one, then the ticked crafts this form can name, then nothing.
 * [selectedCraftNames] is the SELECTED crafts and never the whole register — an artisan of an
 * unticked craft cannot be on this list at all, so a name looked up outside the selection would only
 * ever be the wrong one.
 */
fun craftNameForArtisan(artisan: ArtisanDto, selectedCraftNames: Map<String, String>): String {
    val embedded = artisan.craft?.name.orEmpty()
    if (embedded.isNotBlank()) return embedded
    val byId = artisan.craftId?.let { selectedCraftNames[it] }.orEmpty()
    if (byId.isNotBlank()) return byId
    return ""
}

/** [s] folded for comparison: trimmed, then `Locale.ROOT`-lowercased. See the file header. */
private fun fold(s: String): String = s.trim().lowercase(Locale.ROOT)

/**
 * The tool form's artisan roster, BY CRAFT NAME A→Z and, within each craft, by artisan name A→Z.
 *
 * The comparison key is five parts deep and every part earns its place:
 *
 *   1. `unknown` — an artisan whose craft this form cannot name sorts LAST, never first. A row with
 *      no heading at the top of a list reads as the most important one.
 *   2. `craftKey` — the folded craft name. This is the grouping the designer asked for.
 *   3. `nameKey` — the folded artisan name, within the craft.
 *   4. `name` — the raw name, so two artisans whose names differ only in case have a stable order
 *      rather than whichever the sort happened to produce.
 *   5. `id` — a cuid, unique, which makes the order TOTAL. That is what lets this be read the same
 *      way twice, and it is why it does not matter that `sortedWith` is stable and
 *      `Array.prototype.sort` need not be.
 *
 * THE HANDSET HAS NO GROUP HEADINGS — [SelectOption] carries `value`, `label` and `hint` and no
 * group, on both handsets — so the craft goes into the hint, FIRST, and the sort does the grouping
 * visually. `SelectOption.matches` searches the hint as well as the label, so typing a craft name
 * filters the sheet to that craft, which is the affordance a heading would otherwise have provided.
 */
fun toolArtisanOptions(artisans: List<ArtisanDto>, selectedCraftNames: Map<String, String>): List<SelectOption> =
    artisans
        // The craft name is resolved ONCE per artisan and carried through the sort, rather than
        // recomputed inside each comparison. Not for speed — this list is one server page — but
        // because a key that is computed twice is a key that can be computed two ways.
        .map { it to craftNameForArtisan(it, selectedCraftNames) }
        .sortedWith(
            compareBy<Pair<ArtisanDto, String>> { (_, craft) -> if (craft.isBlank()) 1 else 0 }
                .thenBy { (_, craft) -> fold(craft) }
                .thenBy { (artisan, _) -> fold(artisan.name) }
                .thenBy { (artisan, _) -> artisan.name }
                .thenBy { (artisan, _) -> artisan.id }
        )
        .map { (artisan, craftName) ->
            SelectOption(
                value = artisan.id,
                label = artisan.name,
                // The craft leads, so a glance down the sheet reads as the grouping the sort made.
                // A place with no craft in front of it keeps the shape the single-select had, and an
                // artisan with neither gets null rather than an empty hint — the sheet draws a hint
                // line for a blank string and an empty second line under a name is a row that looks
                // broken rather than one that has nothing to add.
                hint = listOf(craftName, artisan.place)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { null },
            )
        }

/**
 * A row for a craft or an artisan this record is LINKED TO that the register could not list.
 *
 * ── WHY THE ROW EXISTS AT ALL ─────────────────────────────────────────────────────────────────
 *
 * `crafts` and `artisans` reach this form as one server page — the newest hundred — so a tool linked
 * to an artisan recorded last season opens with that link held in state and NOTHING on screen
 * standing for it. Without a row the designer sees "2 of 47 selected" over one chip, cannot untick
 * what they cannot see, and the first save through [mergeSelection]'s offerable check is the only
 * thing standing between them and a silently deleted link. This is `offPageWorkshopRow`'s argument,
 * one control along, and the remedy is the same: draw a row that says what it is.
 *
 * ── AND WHY THIS ONE CAN USUALLY SAY THE NAME, WHERE THE WORKSHOP ROW CANNOT ─────────────────
 *
 * `offPageWorkshopRow` refuses to name its workshop because nothing on the record carries the title
 * and this client will not spend a request behind a picker to fetch one. A tool's OWN payload does
 * carry the names: `craftLinks[].craft.name` and `artisanLinks[].artisan.name` arrive with the
 * record, at no extra cost, from the same read that filled the form. So [name] is passed when the
 * record knew it and the row is honest and useful; it falls back to the anonymous sentence only for
 * a link the record itself could not name — a craft deleted since the save, or a payload from a
 * server older than the link tables.
 *
 * @param noun what the designer calls this control's rows: "craft", "artisan".
 */
fun offPageLinkRow(id: String, name: String?, noun: String): SelectOption = SelectOption(
    value = id,
    label = name?.takeIf { it.isNotBlank() } ?: "The $noun already on this record",
    hint = if (name.isNullOrBlank()) {
        "Linked earlier · this device could not list it just now, so its name is not shown"
    } else {
        "Linked earlier · not in the list this device could load"
    },
)
