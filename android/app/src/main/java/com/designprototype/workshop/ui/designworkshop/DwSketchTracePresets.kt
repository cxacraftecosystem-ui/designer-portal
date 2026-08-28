package com.designprototype.workshop.ui.designworkshop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
// The two-typeface `Text`, shadowing androidx.compose.material3.Text — see FieldText.kt.
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.field

/**
 * **THE TWO PRESET PICKERS, AND THE SUGGESTION THE PORTAL PAYS FOR AND THROWS AWAY.**
 *
 * ── WHY THESE ARE THE FIRST TWO CONTROLS ON THE SCREEN ────────────────────────────────────────
 *
 * A style is a COMPLETE PARAMETER TREE, not a diff (`engine/styles.ts:19-21`: "a user who switches
 * styles expects the second one to look like itself rather than like a blend of the two"). Nothing
 * else in this feature moves thirty-one values in one tap, which on a phone is the difference between
 * a usable surface and a wall of sliders. A subject is a MODIFIER ON A STYLE, not a second style list
 * (`subjects.ts:21-23`) — it nudges denoise, blob area and engine choice for the MATERIAL while
 * leaving the look the style chose intact — and the material is the one fact the designer standing in
 * the room knows and the engine cannot.
 *
 * ── ALL TWENTY STYLES SHIP, INCLUDING THE TEN THAT MAKE NO SENSE HERE ─────────────────────────
 *
 * Ten of the twenty target a cutting machine, a plotter or an embroidery hoop — `stencil`,
 * `silhouette`, `laser-cut`, `embroidery`, `craft-pattern`, `colouring-book`, `single-stroke`,
 * `tattoo-outline`, `comic`, `woodcut` — and stage 11's destination is `sketch.lineArtFile`, "An SVG
 * or vector export, if one was produced", bound for a report. It is tempting to ship ten.
 *
 * **Do not.** Three reasons, in increasing order of how badly it would go: a designer photographing a
 * block print really does want `woodcut`; the ids are BINDING (`styles.ts:15-17` — they are written
 * into `TraceParams.styleId` and therefore into anything persisted), so a shortened list on one
 * client cannot open the other client's saved trace; and a filtered copy of somebody else's register
 * is a second register that drifts. This repository has shipped that bug twice, and the frontend
 * skill file's own dashboard-tile list carried eleven rows of twenty for months, where "the honest
 * reading of a missing tile was 'this tile is not expected'".
 *
 * What changes on a handset is ORDER and SEARCHABILITY, not membership — see [dwTraceStyleOptions].
 *
 * ── THE LISTS ARE READ FROM THE ENGINE, NEVER TRANSCRIBED ─────────────────────────────────────
 *
 * [DwTraceRuntime.presets] returns `styles.ALL` and `subjects.ALL` as the engine holds them. There is
 * no Kotlin table of style names in this repository and there must not be one.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * Turning the engine's tables into picker rows
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The twenty styles as picker rows, with **the group name inside the label**.
 *
 * "Line art · Clean line", not a "Line art" header above a "Clean line" row, and the difference is
 * the search box. `SearchableSelectField` opens the searchable sheet at eight options and matches on
 * label, hint and value with whitespace-split terms (`SearchableSelect.kt:151-159`), so a group
 * folded into the label means a designer can type "tech" and get the three technical styles — which a
 * sticky group header cannot do. The engine's own `groups()` order is preserved because [styles]
 * arrives in it.
 *
 * The upstream's own [DwTracePreset.description] becomes the row's trailing hint, so the sentence
 * that explains a style is visible while choosing rather than only after.
 */
fun dwTraceStyleOptions(styles: List<DwTracePreset>): List<SelectOption> = styles.map { style ->
    SelectOption(
        value = style.id,
        label = if (style.group.isBlank()) style.name else "${style.group} · ${style.name}",
        hint = style.description,
    )
}

/**
 * The subjects as picker rows. A flat list, so there is no group to fold in.
 *
 * The COUNT is the runtime's, not this file's: ten through the TypeScript engine, twelve through the
 * vendored Kotlin one — see `DwTraceKotlinPresets.kt`, which owns that divergence and the sentence a
 * designer reads about it. This maps whatever arrives.
 */
fun dwTraceSubjectOptions(subjects: List<DwTracePreset>): List<SelectOption> = subjects.map { subject ->
    SelectOption(value = subject.id, label = subject.name, hint = subject.description)
}

/** A preset's own name, or the raw id when the engine's table has no such row. */
fun dwTracePresetName(presets: List<DwTracePreset>, id: String): String =
    presets.firstOrNull { it.id == id }?.name ?: id

/* ────────────────────────────────────────────────────────────────────────────
 * The two controls
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "Style" — the control that sets every other control at once.
 *
 * @param onPick called with the chosen id. The panel applies the preset through the ENGINE's own
 *   `styles.byId(id).params`, never by merging a Kotlin copy of it.
 */
@Composable
fun DwTraceStylePicker(
    styles: List<DwTracePreset>,
    selectedId: String,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Style",
            options = dwTraceStyleOptions(styles),
            selectedValue = selectedId,
            placeholder = "Choose a style",
            // A trace always has a style — `preset()` forces `styleId` to the preset's own id so it
            // cannot lie about which style it is (`styles.ts:46-54`) — so "nothing selected" is not a
            // state the engine can be in, and offering it would be offering a value nothing accepts.
            includeNone = false,
            enabled = enabled,
        ) { picked -> if (picked.isNotBlank()) onPick(picked) }
        Text(
            styles.firstOrNull { it.id == selectedId }?.description
                ?: "A style sets every control at once. Pick one, then adjust.",
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/**
 * "What this is a drawing of" — the subject adjustment.
 *
 * ── NO "NONE" ROW, AND THAT IS THE ENGINE'S SHAPE RATHER THAN AN OMISSION ─────────────────────
 *
 * `adjust` is a one-way modifier applied to the tree that is there, and it is documented idempotent
 * (`subjects.ts:41-43`) — which is exactly what makes re-picking the same subject safe and what makes
 * "un-picking" one meaningless. A designer who wants the style back picks the style again, which is
 * one tap and is honest about what it does. Offering an "undo the subject" row would be offering an
 * operation the engine does not have.
 *
 * The panel opens with this seeded from the record's own `sketch.category` — see
 * [DW_TRACE_SUBJECT_FOR_CATEGORY]. Seeded, VISIBLE and changeable, never silently applied.
 */
@Composable
fun DwTraceSubjectPicker(
    subjects: List<DwTracePreset>,
    selectedId: String,
    enabled: Boolean,
    /** True when this selection came from the record's category rather than from a designer's tap. */
    seededFromRecord: Boolean,
    onPick: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "What this is a drawing of",
            options = dwTraceSubjectOptions(subjects),
            selectedValue = selectedId,
            placeholder = "Choose a material",
            includeNone = false,
            enabled = enabled,
        ) { picked -> if (picked.isNotBlank()) onPick(picked) }
        Text(
            buildString {
                append(
                    subjects.firstOrNull { it.id == selectedId }?.description
                        ?: "Adjusts the settings for the material, and leaves the style's look alone.",
                )
                if (seededFromRecord) {
                    // NAMED, not hidden. `params.ts:70` calls this affordance "a named suggestion with
                    // a one-tap override", and a pre-selection nobody is told about is not a
                    // suggestion — it is a decision somebody else made in the designer's name.
                    append(" Chosen from this sketch's category on the record; change it if it is wrong.")
                }
            },
            color = MaterialTheme.field.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
}

/* ────────────────────────────────────────────────────────────────────────────
 * The suggestion nobody renders
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * "The engine read this photograph and suggests X" — one row, already paid for.
 *
 * ── WHY THIS EXISTS HERE AND NOWHERE ELSE IN THE PRODUCT ──────────────────────────────────────
 *
 * `SerializedProfile.suggestion` is a `styles` preset id, is **never empty on a full trace**
 * (`engine/classify.ts:82-83`), and crosses the worker boundary on every one of them
 * (`worker/protocol.ts:57-66`). The portal computes it, ships it, and renders it nowhere:
 * `grep -n suggestion frontend/components/sketches/upload/SketchTraceField.tsx` returns nothing,
 * verified **2026-08-27**. So the classification has already been run and paid for on both clients and
 * one of them throws it away.
 *
 * It is worth a row on a handset more than on a laptop, because the alternative on a phone is
 * scrolling a twenty-item sheet. And it is the affordance `params.ts:69-71` describes as the original
 * contract for this whole feature: **a named suggestion with a one-tap override.**
 *
 * ── IT PROPOSES AND NEVER APPLIES ─────────────────────────────────────────────────────────────
 *
 * The button is the application. Nothing happens on arrival, exactly as [dwGuessSheetCorners]'s
 * outline is drawn but does not move the handles until "Use these corners" is pressed. Applying a
 * style is destructive — it replaces every setting — so a suggestion that applied itself would
 * silently discard a designer's tuning at the moment their trace finished.
 *
 * Drawn only when there is something to say: blank on a preview (previews do not classify), and
 * absent when the suggestion is the style already chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DwTraceStyleSuggestion(
    styles: List<DwTracePreset>,
    suggestedStyleId: String,
    currentStyleId: String,
    enabled: Boolean,
    onApply: (String) -> Unit,
) {
    if (suggestedStyleId.isBlank() || suggestedStyleId == currentStyleId) return
    val suggested = styles.firstOrNull { it.id == suggestedStyleId } ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, RoundedCornerShape(8.dp))
            .padding(8.dp)
            // A sentence that appears when a trace finishes, in a panel the designer may have scrolled
            // away from. Polite rather than assertive: it is worth knowing and it is never urgent.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            "Looking at this photograph, the engine suggests the “${suggested.name}” style. " +
                suggested.description,
            color = MaterialTheme.field.body,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(
                onClick = { onApply(suggested.id) },
                enabled = enabled,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Use the “${suggested.name}” style", fontSize = 13.sp)
            }
        }
    }
}
