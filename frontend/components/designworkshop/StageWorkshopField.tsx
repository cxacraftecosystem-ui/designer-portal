"use client";

/**
 * "Documented at workshop", inside a stage — a searchable list of the workshops this account can
 * actually reach, with a way out for a sitting the repository has never heard of.
 *
 * ── WHAT IT REPLACES ────────────────────────────────────────────────────────────────────────────
 *
 * `participant.documentedAtWorkshop` ("Documented at workshop") and
 * `workshopSetup.craftDocumentedAtWorkshop` ("Workshop the craft was documented at") were bare
 * `<input type="text">` boxes with a dictation button, while the same fact on the artisan and craft
 * record pages is picked from a searchable list of `Workshop` rows (`WorkshopSelect`). Both boxes are
 * hydration targets filled from `Workshop.title`, so the value that ARRIVES is a title the repository
 * chose and anything typed over it is a title nothing can match — and a stage entry is a frozen copy
 * that nothing re-resolves, so the mismatch is what the ministry's document says for good. See
 * `workshopTitleRole` in `stageFieldRoles` for the full argument and for why the two keys are matched
 * exactly rather than by pattern.
 *
 * ── THE VALUE IS A TITLE, NOT AN ID, AND THAT IS NOT NEGOTIABLE HERE ────────────────────────────
 *
 * The registry declares both fields TEXT with a `max_length`, and `stage_definitions.py` is owned by
 * another change this phase. So this control writes the workshop's TITLE — the same string hydration
 * writes — and never an id. Consequences worth stating rather than discovering:
 *
 *   * TITLES ARE DEDUPLICATED. Two workshops may share one title, and since only the title is
 *     stored, picking either would store the same string. Offering the same option twice would be a
 *     control that appears to distinguish two answers it cannot. The hint says how many sittings share
 *     the name, so the reader is not told a false singular.
 *   * A TITLE LONGER THAN `max_length` IS WITHHELD, and the count of what was withheld is printed.
 *     `coerce_value` returns an error for an over-length string, so offering one would be offering an
 *     option that turns the row into a refused answer on save — and dropping it silently would be the
 *     silent-truncation failure this repository names as its most repeated bug class.
 *
 * ── (e): THE LIST IS THE SERVER'S SCOPED ANSWER, AND THERE IS NO FALLBACK ───────────────────────
 *
 * Options come from `loadAccessibleWorkshops()` — `GET /workshops?accessibleOnly=true`, the same
 * request and the same authority as the record page's own picker, memoised per tab. **That request is
 * the only list allowed to fill this control.** There is deliberately no cached, bundled or
 * last-known-good source: a stale access list is wrong in the PERMISSIVE direction (a revoked grant
 * still reads as a grant), and a picker is precisely the wrong place to be permissive. When the
 * request fails the control says so and falls back to the TEXT BOX IT USED TO BE — which withholds no
 * answer a designer could otherwise give, because free text is what this field was until today.
 *
 * ── THE ESCAPE HATCH, AND WHY A CLOSED LIST WOULD BE WRONG HERE ────────────────────────────────
 *
 * A designer may legitimately be recording a sitting nobody filed a `Workshop` row for — the row is
 * created by a professor or above (`require_workshop_manager`) and a designer cannot make one — so a
 * control that could ONLY answer from the list would refuse an answer the registry accepts and the
 * designer knows. "Type a title instead" swaps to the same input this field used to be, dictation
 * button and all, and switching back to the list leaves what was typed alone until something else is
 * picked. The dropdown is the default path, not a gate. This is the difference between this control
 * and `StageAddressField`'s state box, where the list IS closed because the API validates against it.
 *
 * ── AND THE ANSWER ALREADY ON THE ROW IS ALWAYS AN OPTION ───────────────────────────────────────
 *
 * A stored title that is not in the list — a workshop renamed since, one filed under a roster this
 * account is no longer on, or one typed by hand last season — is prepended as its own option rather
 * than dropped. The same rule and the same reason as `useRecordOffPage`: a control that cannot draw
 * its own current value reads as blank, and the obvious repair for a blank box is to answer it again,
 * which overwrites a true answer with a guess. It is not an OFFER of access to anything: it is the
 * string already saved on this row.
 */

import { useEffect, useMemo, useState } from "react";

import { loadAccessibleWorkshops, workshopOccurrenceDate } from "@/components/forms/WorkshopSelect";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { inputValue, type DwField, type DwValue } from "@/lib/designWorkshops";
import { formatDate } from "@/lib/format";
import type { Workshop } from "@/lib/types";

/** What the picker offers for "this record was not documented at any recorded workshop". */
const NOT_RECORDED_LABEL = "Not recorded";

/** One offerable title, with what is known about the sitting(s) that carry it. */
type TitleOption = {
  title: string;
  /** The most recent occurrence date among the workshops sharing this title, formatted. */
  when: string;
  /** How many workshops carry this exact title. */
  count: number;
};

/**
 * The distinct titles of `workshops`, newest occurrence first, with anything the field cannot store
 * removed and counted.
 *
 * Order is inherited from `loadAccessibleWorkshops`, which sorts by occurrence — so the first option
 * is the workshop that ran most recently, which is the one a designer typing today almost always
 * means. Re-sorting alphabetically here would bury this fortnight's workshop in the middle of a
 * hundred rows.
 */
function distinctTitles(workshops: Workshop[], maxLength: number | undefined): { options: TitleOption[]; withheld: number } {
  const byTitle = new Map<string, TitleOption>();
  let withheld = 0;
  for (const workshop of workshops) {
    const title = workshop.title?.trim();
    if (!title) continue;
    if (maxLength && title.length > maxLength) {
      withheld += 1;
      continue;
    }
    const existing = byTitle.get(title);
    if (existing) {
      existing.count += 1;
      continue;
    }
    byTitle.set(title, { title, when: formatDate(workshopOccurrenceDate(workshop) || null), count: 1 });
  }
  return { options: [...byTitle.values()], withheld };
}

export function StageWorkshopField({
  field,
  value,
  onChange,
  labelId,
  describedBy,
  invalid,
  disabled,
  dictation
}: {
  field: DwField;
  value: DwValue | undefined;
  onChange: (next: DwValue) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
  /**
   * The dictation button for this field, rendered by `FieldInput` and passed down so it can appear on
   * the free-text half and nowhere else.
   *
   * A recogniser hands back words, and a dictated workshop title is the exact failure this control
   * exists to remove — "Bagru block print workshop twenty twenty five" against a row that reads
   * "Bagru Block Print Workshop 2025". So the button follows the box: offered where a designer is
   * deliberately typing prose the list cannot answer, withheld where they are choosing from it.
   */
  dictation?: React.ReactNode;
}) {
  const [workshops, setWorkshops] = useState<Workshop[] | null>(null);
  /** True once the list could not be loaded. Not the same state as "loaded and empty". */
  const [failed, setFailed] = useState(false);
  /**
   * Has the reader asked for the plain box?
   *
   * Starts false even when the stored value is not in the list, deliberately: the stored answer is
   * offered as an option (see the header), so the list can draw it, and opening in text mode would
   * hide the dropdown from every row hydration has already filled — which is most of them.
   */
  const [typing, setTyping] = useState(false);

  useEffect(() => {
    let cancelled = false;
    loadAccessibleWorkshops()
      .then((rows) => {
        if (!cancelled) setWorkshops(rows);
      })
      .catch(() => {
        // Offline, or the repository is unhappy. The box below is what this field was yesterday, so
        // nothing is withheld and nothing needs an error banner of its own.
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const current = inputValue(value);
  const { options: titles, withheld } = useMemo(
    () => distinctTitles(workshops ?? [], field.maxLength),
    [workshops, field.maxLength]
  );

  const options = useMemo<DropdownOption[]>(() => {
    const rows: DropdownOption[] = [{ value: "", label: NOT_RECORDED_LABEL }];
    // The row's own answer first, when the list does not contain it — see the header. Its hint says
    // where it came from, so nobody reads it as a workshop they have been given access to.
    if (current && !titles.some((option) => option.title === current)) {
      rows.push({ value: current, label: current, hint: "already on this row" });
    }
    for (const option of titles) {
      rows.push({
        value: option.title,
        label: option.title,
        // `hint` and not part of the label: hint is drawn beside the option and is SEARCHED, while the
        // VALUE stays the bare title. Folding the date into the label would store "… · 12 Mar 2025".
        hint: option.count > 1 ? `${option.count} workshops share this title` : option.when === "-" ? undefined : option.when
      });
    }
    return rows;
  }, [current, titles]);

  if (typing || failed) {
    return (
      <div className="grid gap-1">
        <input
          className="field-input"
          type="text"
          maxLength={field.maxLength || undefined}
          aria-labelledby={labelId}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          value={current}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
        />
        {dictation}
        {failed ? (
          // Said out loud rather than left as a box that looks like every other box. A designer who
          // cannot see why the list is missing assumes there are no workshops.
          <p className="text-xs text-ink-500">
            The list of workshops could not be loaded, so this is a plain box. Type the workshop&apos;s title as it is
            recorded, or reload the page to try the list again.
          </p>
        ) : (
          <button
            type="button"
            className="justify-self-start text-xs font-medium text-purple-700 underline underline-offset-2"
            onClick={() => setTyping(false)}
            disabled={disabled}
          >
            Choose from the list of workshops instead
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="grid gap-1">
      <Dropdown
        value={current}
        onChange={onChange}
        options={options}
        placeholder={workshops === null ? "Loading workshops…" : "Choose the workshop"}
        // "There is nothing to choose from" is not "your query matched nothing", and here the first
        // one has a cause worth naming: every workshop is somebody else's roster.
        emptyLabel="No workshops are open to this account"
        disabled={disabled || workshops === null}
        // `field.label`, the same way `StageAddressField` names its two dropdowns. `FieldInput`'s
        // `unlabelled` wrapper emits a raw `<span className="field-label">` rather than a `Field` /
        // `FieldBlock`, so there is no label context for `SearchableSelect` to compose a name from —
        // without this the trigger would announce only the chosen value and never the question.
        ariaLabel={field.label}
        describedBy={describedBy}
        // Options are fetched RECORDS, so the filter box is the call site's decision and not the
        // option count's — a workshop list is one row on a fresh deployment and a hundred on this
        // one, and a control that grows a search box on its own behaves two ways on two deployments.
        searchable
      />
      {/*
        WHAT THE LIST IS. Invisible from the outside — a designer cannot tell "the workshops I may
        use" from "every workshop there is" by looking at a dropdown — and a narrowing nobody
        announced is absence reading as non-existence.
      */}
      <p className="text-xs text-ink-500">
        Only workshops you have access to are listed.{" "}
        <button
          type="button"
          className="font-medium text-purple-700 underline underline-offset-2"
          onClick={() => setTyping(true)}
          disabled={disabled}
        >
          Type a title instead
        </button>{" "}
        if this record was documented at a workshop that is not in the list.
      </p>
      {withheld > 0 ? (
        // Stated rather than dropped. It cannot happen with today's titles, and the sentence is here
        // because the day it does, a designer must not be left hunting for a workshop that is on
        // screen nowhere and refused by nothing.
        <p className="text-xs text-ink-500">
          {withheld} workshop{withheld === 1 ? "" : "s"} {withheld === 1 ? "is" : "are"} not listed because{" "}
          {withheld === 1 ? "its title is" : "their titles are"} longer than the {field.maxLength} characters this field
          stores. Type a shortened title instead.
        </p>
      ) : null}
    </div>
  );
}
