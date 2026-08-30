package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ---------------------------------------------------------------------------
 * HOW LONG SOMEBODY HAS BEEN DOING THIS: YEARS AND MONTHS, TWO COLUMNS, ONE CONTROL.
 *
 * TWO RECORDS ASK THIS QUESTION AND THEY MUST ASK IT THE SAME WAY. `Artisan.experienceYears` /
 * `Artisan.experienceMonths` and `DesignerProfile.experienceYears` / `experienceMonths` are four
 * different columns with two different year ceilings (90 and 70), and until this file the two forms
 * drew two different controls with two different labels — "Experience (years)" on the artisan form
 * and "Years of experience" on the profile — which is a divergence a reader meets and has to
 * resolve for themselves. The ceiling is the only thing that legitimately differs, so it is the
 * parameter and everything else is shared.
 *
 * ── AND ONLY ONE OF THE TWO CALLERS IS HERE YET ────────────────────────────
 *
 * ONLY THE DESIGNER PROFILE CALLS THIS TODAY. The artisan form still draws its own single
 * free-typed number box labelled "Experience (years)", with no months picker at all, in
 * `MainActivity.kt` — a file owned by a different workflow on the day this shipped, so it could not
 * be changed from here. The paragraph above says what this control is FOR; this one says how far it
 * has actually got, because a reader who took the two together would go looking for an artisan call
 * site that does not exist, and `maxYears = 90` below reads like a promise that one does.
 *
 * WHAT THE GAP COSTS, NAMED. [ArtisanDetailDto.experienceMonths] already arrives on this handset
 * and the key is already declared on [ArtisanCreateRequest], which is the body this app sends for a
 * correction as well as for a create — nothing sets it. So a months value typed on the web is
 * stored, is sent to this phone, and is drawn nowhere: a researcher editing that artisan on a
 * handset cannot see it, cannot correct it, and cannot tell it apart from an artisan nobody asked.
 *
 * IT ERASES NOTHING, AND NOBODY SHOULD "FIX" IT BY MAKING IT DO SO. `experienceMonths` is
 * deliberately absent from `WorkshopRepository.ARTISAN_CLEARABLE_COLUMNS`, and `ApiClient.json`
 * leaves `explicitNulls` and `encodeDefaults` off, so the key is OMITTED from every artisan PATCH
 * this app sends and the stored months survive an edit made from a screen that cannot show them.
 * Adding the column to that clearable list before the picker exists would turn a blind spot into a
 * data loss — the server's own `_CLEARABLE_COLUMNS` accepts that null and clears the column, which
 * is exactly the retraction path the years already rely on and the months are not ready for.
 *
 * FINISHING IT IS ONE CALL SITE. Replace that box with [ExperienceFields] at `maxYears = 90`,
 * carrying the `help` sentence that says a "practising since" date outranks both boxes; seed
 * `months` from the record the form is editing, exactly as `experienceYears` is seeded; and send it
 * back on the save. The column, the API bound and the wire models are all already there, and
 * `frontend/components/forms/ArtisanForm.tsx` is the shape to match.
 *
 * ── WHY A REMAINDER AND NOT A TOTAL ────────────────────────────────────────
 *
 * The months box is bounded 0..11 on the column (`CHECK (experienceMonths BETWEEN 0 AND 11)`), in
 * the API schema, and here. TWELVE IS NOT A BIGGER MONTH — it is a year the box beside it already
 * holds. Nothing anywhere adds the two together: the server stores what was chosen and the read-back
 * returns it, so "14 years" is legible on a report without arithmetic that nobody could check.
 *
 * ── WHY BLANK, 0 AND ABSENT ARE THREE THINGS ───────────────────────────────
 *
 * The old control was a free-typed number box, and an empty one was indistinguishable from an
 * unanswered one only by luck. Here the "no selection" row is drawn first and labelled
 * [NOT_STATED], so a reader can SEE that "not stated" and "0" are two different answers — and they
 * are: null means nobody was ever asked, 0 means no odd months, which somebody chose. The server
 * keeps them apart (an absent key leaves the stored value alone, an explicit null clears it, a 0
 * stores 0), so a control that collapsed them would put an answer on record that nobody gave. An
 * UNTOUCHED months control writes "", which becomes null, and never 0.
 *
 * ── WHY THE COUNT DECIDES THE SURFACE HERE ─────────────────────────────────
 *
 * Neither picker passes `searchable`. Both are constant vocabularies compiled into this file, which
 * is exactly what [SEARCH_THRESHOLD] was measured for, and DROPDOWN_DESIGN.md 3.6 leaves that case
 * alone: only a list BACKED BY RECORDS has an answer that moves under the reader. Neither list can
 * ever be empty, so neither carries one of 3.5's sentences and neither ever stands down.
 * ---------------------------------------------------------------------------
 */

/**
 * The row that means "nobody has answered this".
 *
 * One constant for both pickers and for the sentence beneath them, because the sentence NAMES the
 * row. A label that drifted from the row it describes would be a form telling a reader to look for
 * something that is not on it.
 */
const val NOT_STATED: String = "Not stated"

/**
 * A closed list of whole numbers, with [current] kept at the front when it falls outside [range].
 *
 * THE KEPT-AT-THE-FRONT RULE IS THE STATE BOX'S, for the same reason: a stored value the list does
 * not offer must not be silently dropped. A profile holding 80 years under a 0..70 picker would show
 * [NOT_STATED] over it, which reads as unanswered and invites somebody to answer it again,
 * differently — and the record would then have lost a number nobody meant to change. Shown, the
 * save's own bound check is what refuses it, in words, in the box.
 *
 * Values are carried AS TEXT, matching the form models on both screens, so that "" — the unanswered
 * state — has a representation at all. An Int-backed picker would have to spell "not stated" as a
 * sentinel number, and every sentinel is eventually stored as though it were an answer.
 */
internal fun experienceOptions(range: IntRange, current: String): List<SelectOption> {
    val rows = range.map { SelectOption(it.toString(), it.toString()) }
    val kept = current.trim()
    return if (kept.isNotEmpty() && rows.none { it.value == kept }) {
        listOf(SelectOption(kept, kept)) + rows
    } else {
        rows
    }
}

/**
 * The years somebody has practised, and the odd months on top of them — two pickers, one answer.
 *
 * @param maxYears the ceiling of the years list: 90 for an artisan, 70 for a designer. Both mirror
 *   the bound the API enforces and the stage registry declares, so this form cannot offer a number
 *   the workshop it feeds would then refuse on a row it filled in from this very record.
 * @param label the group heading. It is drawn as a real TalkBack heading rather than as a caption:
 *   the two controls carry their own labels and a screen reader announces each of them, but neither
 *   says what the PAIR is, so somebody arriving on "Months, button" would have no way to know it
 *   belongs with the years above it.
 * @param help the sentence under the pair. Defaults to the one that explains [NOT_STATED]. The
 *   artisan form is the caller this exists for and it has not arrived yet (see the header): on that
 *   form a "practising since" date outranks both boxes, so the reader has to be told which of the
 *   three answers is actually printed, and the default sentence here does not say that.
 * @param error a refusal, drawn under the pair in the error colour.
 */
@Composable
fun ExperienceFields(
    years: String,
    months: String,
    enabled: Boolean,
    maxYears: Int,
    onYearsChange: (String) -> Unit,
    onMonthsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Experience",
    help: String? = DEFAULT_EXPERIENCE_HELP,
    error: String? = null,
) {
    val yearOptions = remember(years, maxYears) { experienceOptions(0..maxYears, years) }
    val monthOptions = remember(months) { experienceOptions(0..MAX_EXPERIENCE_MONTHS, months) }
    /*
     * "ON ONE LINE" IS RIGHT AT ORDINARY SIZES AND WRONG AT THE ACCESSIBILITY ONES.
     *
     * Two triggers side by side on a 360dp handset at twice the text size are two columns of
     * clipped, ellipsised labels — and the label is the only thing that says which of the two a
     * reader is looking at, so clipping it is not a cosmetic loss. Past 1.3x they stack. The
     * information, the reading order and the tab order are unchanged and nothing is hidden, which
     * is the whole test: a layout may re-flow at a font scale, it may not drop anything.
     */
    val stacked = LocalDensity.current.fontScale > 1.3f

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier.fillMaxWidth()) {
        Text(
            label,
            color = MaterialTheme.field.muted,
            fontSize = 12.sp,
            modifier = Modifier.semantics { heading() }
        )
        val yearsField = @Composable { fieldModifier: Modifier ->
            SearchableSelectField(
                label = "Years",
                options = yearOptions,
                selectedValue = years,
                enabled = enabled,
                placeholder = NOT_STATED,
                onSelect = onYearsChange,
                modifier = fieldModifier
            )
        }
        val monthsField = @Composable { fieldModifier: Modifier ->
            SearchableSelectField(
                label = "Months",
                options = monthOptions,
                selectedValue = months,
                enabled = enabled,
                placeholder = NOT_STATED,
                onSelect = onMonthsChange,
                modifier = fieldModifier
            )
        }
        if (stacked) {
            yearsField(Modifier.fillMaxWidth())
            monthsField(Modifier.fillMaxWidth())
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                yearsField(Modifier.weight(1f))
                monthsField(Modifier.weight(1f))
            }
        }
        help?.let { Text(it, color = MaterialTheme.field.muted, fontSize = 11.sp) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
    }
}

/** The months ceiling. Eleven, not twelve — see the note at the top of this file. */
const val MAX_EXPERIENCE_MONTHS: Int = 11

/**
 * The sentence under the pair, when the caller has nothing more specific to say.
 *
 * It exists to make [NOT_STATED] mean something. A blank row in a picker is otherwise read as "I
 * have not got to that one yet", and the whole point of the row is that leaving it blank IS an
 * answer, and a different one from 0.
 */
// Concatenated rather than interpolated: a `const val` initializer has to be a compile-time
// constant, and `+` over two constants provably is.
const val DEFAULT_EXPERIENCE_HELP: String =
    "Whole years, and the odd months on top of them. Leave either at \"" + NOT_STATED + "\" if " +
        "you would rather not say — that is a different answer from 0, and both are kept."
