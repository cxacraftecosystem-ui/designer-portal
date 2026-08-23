"use client";

/**
 * "Media on the artisan record", inside a stage — the hydrated sentence, plus a searchable
 * multi-select over the files that sentence counts.
 *
 * ── WHAT THE OWNER ASKED FOR, AND WHAT THIS ANSWERS WITH ────────────────────────────────────────
 *
 * The request was that "media on the artisan record" become a multi-select dropdown. The label
 * belongs to a registry field — `participant.recordMediaNote`, "Media on the artisan record",
 * `stage_definitions.py:598` — declared TEXT with `max_length=200`, and it is a HYDRATION TARGET:
 * `_media_note` composes a sentence by counting the files attached to the linked Artisan and
 * `hydrate_entries` copies it onto the stage entry at SAVE time. So the box does not hold a name, an
 * id or an enum. It holds a COUNT, written as prose.
 *
 * That is why this control is not `StageWorkshopField` with a different fetch, and the difference is
 * the whole argument. `StageWorkshopField` works because there is a lossless one-to-one between the
 * thing picked (a `Workshop` row) and the string stored (`Workshop.title` — the exact string
 * hydration writes): picking is a safer way of typing the same string. Nothing a designer can tick
 * produces a count. So a multi-select here has to decide what a set of files MEANS in a box that
 * holds a sentence, and this control's answer is stated once and obeyed everywhere:
 *
 *   **THE RECORD'S OWN COUNT IS NEVER MADE SMALLER. THE SELECTION ADDS A SECOND SENTENCE.**
 *
 * Re-running `_media_note`'s grammar over the picked subset is the obvious shape and it is refused:
 * "Attached to the artisan record: 1 photograph." against a record holding four is a false claim in a
 * document that goes to a Development Commissioner's office, and the field exists so that a reader
 * knows what to ask FOR. Under-counting it is worse than never narrowing at all. What a designer
 * narrowing this box is actually doing is pointing — "of the fourteen files on this artisan's record,
 * these three are the ones to ask for first" — so the value becomes the record's own count, verbatim,
 * followed by "See in particular: …". Both sentences are true. `mediaNoteGrammar.ts` owns the strings
 * and the reasoning; this file owns the screen.
 *
 * ── THE ONE THING THE 2026-08-24 MEDIA-NOTE REPORT GOT WRONG, MEASURED ─────────────────────────
 *
 * That report's first and strongest objection was that any designer edit here would make the row read
 * as `diverged` to an admin for ever — "an audit that flags everything flags nothing". IT DOES NOT.
 * `entry_provenance.canonical_divergence` (`entry_provenance.py:390`) only ever looks at fields whose
 * stamp carries `source == "reference"`, and `merge_entry_provenance` (`:175`) rule 3 REPLACES that
 * stamp with a plain designer stamp the moment the stored value differs from what the row held:
 * "what makes a hydrated field's authorship move from the record's recorder to the designer the
 * moment the designer types over it". `FieldProvenance`'s own header says the same thing from the
 * other side — "the moment a designer types over a hydrated field, the stamp becomes a plain
 * `designer` one with no `refModel` at all".
 *
 * So a narrowed note is the designer's answer, is attributed to them, and is NOT compared against the
 * record at all. Divergence keeps meaning exactly what it says: a value hydration wrote, whose record
 * has changed since. That is what makes this control legal without touching the registry — and it is
 * ALSO why the panel says out loud that narrowing moves the authorship. A designer should not have to
 * discover from a provenance line that their name is now on a row the artisan's recorder used to own.
 *
 * ── WHAT IS LOST, ON SCREEN AND NOT IN A COMMIT MESSAGE ─────────────────────────────────────────
 *
 * WHICH FILES WERE TICKED IS NOT STORED ANYWHERE, and cannot be. `DwStageEntry.data` is validated
 * against the registry, so an extra key of ids lands in the save response's `droppedKeys` and is gone;
 * the registry declares no id list here; and `ExistingMedia`'s header records the same absence on the
 * record side — "no record type has a column for 'which of my files are the chosen ones'". The
 * sentence is the whole memory of the pick. A later visit therefore reopens with the chooser EMPTY
 * over a narrowed sentence, and the panel says so rather than letting a designer find it: ticking
 * again replaces the clause instead of adding to it, and the earlier three files are recoverable only
 * as "how many of each kind".
 *
 * ── THE LIST IS FETCHED, BECAUSE THE ENTRY DOES NOT CARRY IT ────────────────────────────────────
 *
 * `DwReferenceOption.data` is the flat hydration dict: `recordMediaNote` is in it AS THE SENTENCE, and
 * `photo`/`photoCaption` are the single image `_reference_photos` resolves. No media list, no ids —
 * the rows are loaded server-side under `include={"media": True}` and discarded once the sentence is
 * built, and `test_reference_carry.py` asserts no `med_…` id appears anywhere on the entry. So the
 * options come from `GET /media?linkedRecordType=…&linkedRecordId=…`, the same request
 * `ExistingMedia.refresh` makes on the record page, through the same `listResource` helper.
 *
 * TWO WAYS THAT FETCH AND THE SENTENCE CAN DISAGREE, and both are handled rather than hidden:
 *
 *  1. THE FETCH FILTERS THE TAG PAIR; `_media_note` COUNTS THE TYPED FK. `media_relation_data` writes
 *     both on upload so they normally agree, but a parent delete SET NULLs the FK and leaves the tag,
 *     so this listing can show rows the sentence never counted. The panel prints the disagreement and
 *     names which number is which; it never silently adopts one.
 *  2. THE FETCH DOES NOT SUBTRACT MEASUREMENT-GRID FRAMES; `_media_note` DOES. So they are subtracted
 *     here, with the same marker string, and they are kept out of the OPTIONS too — offering a
 *     designer a sheet of ruled paper the sentence deliberately excludes would be a chooser that
 *     disagrees with its own field by one, on exactly the records that were measured most carefully.
 *
 * ── WHAT THE FETCH COSTS, STATED RATHER THAN HIDDEN ────────────────────────────────────────────
 *
 * ONE `GET /media` PER OPEN ROW, and on a mirror point that is the SECOND request for the same list:
 * `StageRecordEmbed` mounts the real `ArtisanForm` inline, and `ArtisanForm` renders
 * `<ExistingMedia linkedRecordType="artisan" linkedRecordId={initial.id} />` a few rows above this
 * box. The two are not merged, and that is a decision rather than an oversight — `ExistingMedia` owns
 * a 15-second transcript poll, a paging button and a delete, and threading its rows down into a
 * registry-driven field would couple a generated form to one record page's component and give this
 * control a list whose length another panel's button changes. They also ask different questions of
 * the same answer: that panel lists files, this one counts them and subtracts the grid frames.
 *
 * The bill is bounded by the form rather than by the roster. `EntityForm` UNMOUNTS a collapsed
 * collection row's panel and deliberately does not open them all ("thirty freshly expanded panels is
 * not a form, it is a wall"), so a forty-participant stage does not fire forty requests — it fires
 * one per row a designer opens. Inside an open row the mirrored boxes stay in the tree while their
 * disclosure is shut, which is what keeps their anchors, refusals and provenance stamps alive, so the
 * request is made whether or not this box is currently visible. If that ever becomes the cost that
 * matters, the fix is a per-record cache shared with `ExistingMedia` and not a lazier mount: a field
 * that only fetches when scrolled into view is a field whose sentence disagrees with itself depending
 * on where the page was scrolled to.
 *
 * ── ENTITLEMENT, PRECISELY ─────────────────────────────────────────────────────────────────────
 *
 * `records.viewable_where` returns `{}` for media today, so the list is not narrowed and every row
 * travels: name, type, caption, uploader, when. What is gated per file is `MediaFile.url` and
 * `transcriptText`. This control asks for none of that — the option label is the caption or the
 * filename, the hint is the kind and the date — so it can label every file honestly, and it never
 * puts a fetchable URL on screen or into a stored value. `_media_note`'s "entitlement-gated per file"
 * is an argument against FREEZING ids onto an entry, which this does not do, and not against listing
 * them.
 *
 * ── PLATFORM PARITY, STATED RATHER THAN PARAPHRASED ────────────────────────────────────────────
 *
 * Web only, exactly like `StageWorkshopField`. Android's `FieldRenderer` draws `DwFieldType.TEXT` as a
 * text box and has no equivalent, so a handset gets the hydrated sentence in an editable box — which
 * is what the web had until today, and loses nothing: the sentence is written SERVER-SIDE on the next
 * save either way. The value is a plain string on both clients, so nothing here can make a draft
 * captured on a phone unreadable on a laptop or the reverse. If the handset ever grows this control,
 * the grammar has to come from the server rather than be re-typed in Kotlin, or the two clients will
 * compose two spellings of one count.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import {
  composePointerNote,
  composeRecordCount,
  countableMediaRows,
  hasPointerNote,
  mediaNoteOverrun,
  stripPointerNote
} from "@/components/designworkshop/mediaNoteGrammar";
import type { MediaNoteFieldRole } from "@/components/designworkshop/stageFieldRoles";
import { MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { listResource } from "@/lib/api";
import { inputValue, type DwEntryData, type DwField, type DwValue } from "@/lib/designWorkshops";
import { formatDateTime } from "@/lib/format";
import type { MediaFile } from "@/lib/types";

/**
 * How many attachments one request asks for. 100 is the CEILING, not a preference: `GET /media`
 * declares `pageSize: int = Query(20, ge=1, le=100)`, so raising it gets a 422 rather than more files.
 * The same number and the same reason as `ExistingMedia`.
 */
const PAGE_SIZE = 100;

/**
 * How many pages this control will walk before it declares the list truncated.
 *
 * THREE, AND THE BOUND IS THE POINT RATHER THAN THE NUMBER. A count is only honest over a COMPLETE
 * list, so the alternative to a bound is either a control that pages for ever on a record carrying a
 * bulk import, or one that quietly counts its first hundred files and prints the answer as a total —
 * which is rule 10 of this repository's frontend contract wearing a sentence: "a list that quietly
 * stops is indistinguishable from a place with no records". Past 300 files the panel says the listing
 * is truncated, the composed sentence stops making any claim about the record's own total, and the
 * hydrated count — which the SERVER took over every row — is left in the box untouched.
 */
const MAX_PAGES = 3;

/**
 * How many countable files there have to be before narrowing is offered.
 *
 * TWO, and it is `ExistingMedia`'s floor for `ExistingMedia`'s reason: a multi-select over one file is
 * furniture, because the sentence already names it ("Attached to the artisan record: 1 photograph.")
 * and pointing at the only file there is says nothing a reader did not have. It is NOT the
 * `searchable` rule and must not be read as one — that rule is about a control whose own behaviour
 * flips at eight options; this is a whole control appearing when there is something for it to do.
 */
const CHOOSER_FLOOR = 2;

export function StageMediaNoteField({
  field,
  role,
  row,
  value,
  onChange,
  labelId,
  describedBy,
  invalid,
  disabled,
  dictation
}: {
  field: DwField;
  /** Which record's files this sentence counts, and what the sentence calls it. */
  role: MediaNoteFieldRole;
  /** The whole row, read for ONE thing: the id in the REF field this sentence hydrates from. */
  row: DwEntryData;
  value: DwValue | undefined;
  onChange: (next: DwValue) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
  /**
   * The dictation button, rendered by `FieldInput` and passed down.
   *
   * KEPT, unlike `StageWorkshopField`'s, which is withheld in list mode. There a dictated value is
   * the exact failure the control removes — a spoken workshop title never matches the stored one. Here
   * the box legitimately holds a designer's own prose about footage ("the audio introduction is the
   * one to listen to"), the field is not a closed list in any mode, and a sentence is the one kind of
   * value a recogniser is good at.
   */
  dictation?: React.ReactNode;
}) {
  /** The id of the record whose files the sentence counts — "" when nothing is linked yet. */
  const refId = inputValue(row[role.refField.key]).trim();

  const [rows, setRows] = useState<MediaFile[] | null>(null);
  /** How many the SERVER says are attached, which is not `rows.length` once the list is truncated. */
  const [total, setTotal] = useState(0);
  /** True once the listing could not be loaded. Not the same state as "loaded and empty". */
  const [failed, setFailed] = useState(false);
  /**
   * Which files the designer is pointing at. Ids and not indexes, for `ExistingMedia`'s reason: the
   * list is rewritten in place by a re-fetch, and an index into an array something else reorders
   * selects whatever happens to be in that slot next.
   */
  const [picked, setPicked] = useState<string[]>([]);
  /**
   * The number of characters the last refused selection would have overrun `max_length` by, or 0.
   *
   * STATE AND NOT A DERIVED VALUE, because it is a fact about an ACT the designer performed — "the
   * selection you just made was not written" — and the derived version would evaporate the moment the
   * box was edited by hand, which is exactly when the reader still needs to know the box does not
   * match the ticks.
   */
  const [refusedOverrun, setRefusedOverrun] = useState(0);
  /**
   * Which fetch is the current one. The generation counter every list page in this app uses, and for
   * the same reason: `listResource` takes no AbortSignal, so a late answer is IGNORED rather than
   * cancelled. Two can overlap here whenever a designer re-points the picker while a page is in
   * flight, and without this the previous artisan's files would land under the new artisan's id.
   */
  const generation = useRef(0);

  /** A different record is a different list, and a pick made against the last one means nothing. */
  useEffect(() => {
    setRows(null);
    setTotal(0);
    setFailed(false);
    setPicked([]);
    setRefusedOverrun(0);
  }, [role.linkedRecordType, refId]);

  useEffect(() => {
    if (!refId) return;
    const mine = (generation.current += 1);
    let cancelled = false;
    (async () => {
      try {
        const collected: MediaFile[] = [];
        let serverTotal = 0;
        for (let page = 1; page <= MAX_PAGES; page += 1) {
          const result = await listResource<MediaFile>("/media", {
            linkedRecordType: role.linkedRecordType,
            linkedRecordId: refId,
            page,
            pageSize: PAGE_SIZE
          });
          if (cancelled || mine !== generation.current) return;
          collected.push(...result.items);
          serverTotal = result.total;
          // A short page is the end of the list, so a record with exactly 100 files does not pay for
          // a second, empty request. Same early exit as `ExistingMedia.refresh`.
          if (result.items.length < PAGE_SIZE) break;
        }
        if (cancelled || mine !== generation.current) return;
        setRows(collected);
        // Never able to read as FEWER than are on screen: `total` and the collected pages are two
        // answers from two requests, and a transient stale count would print a smaller total over a
        // longer list.
        setTotal(Math.max(serverTotal, collected.length));
      } catch {
        if (cancelled || mine !== generation.current) return;
        // Said out loud below and NOT rendered as an empty list. "This record has no media" and "we
        // could not ask" are different facts, and only one of them is a reason to stop looking.
        setFailed(true);
        setRows([]);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [role.linkedRecordType, refId]);

  /**
   * Forget a pick whose file is no longer listed.
   *
   * Only ever SHRINKS the selection, and only against a list that has arrived — `rows === null` is
   * "not answered yet", not "no files" — so it can never turn a real pick into "point at nothing"
   * while a request is in flight. `ExistingMedia` carries the same guard for the same failure: a
   * selection made entirely of ids that match nothing renders as an empty answer over a record that
   * has files, which is this repository's silent-emptiness class in miniature.
   */
  useEffect(() => {
    if (rows === null) return;
    setPicked((current) => {
      if (!current.length) return current;
      const live = new Set(rows.map((media) => media.id));
      const kept = current.filter((id) => live.has(id));
      return kept.length === current.length ? current : kept;
    });
  }, [rows]);

  /** Everything attached except the measurement-grid frames — see the header. */
  const countable = useMemo(() => countableMediaRows(rows ?? []), [rows]);
  /** How many grid frames were subtracted, so the panel can say so rather than be off by them. */
  const gridFrames = (rows?.length ?? 0) - countable.length;
  /** True when every attached file is on this page. A count over a partial list is not a count. */
  const wholeList = rows !== null && !failed && total <= rows.length;

  /**
   * The sentence the record's own files add up to, or null when it cannot honestly be composed.
   *
   * Null while loading, on a failure, and on a TRUNCATED listing — in the last case deliberately,
   * because the number would be a partial total presented as a whole one.
   */
  const recordCount = useMemo(
    () =>
      wholeList
        ? composeRecordCount({ subject: role.subject, rows: countable, numberedPrefix: role.numberedPrefix })
        : null,
    [wholeList, countable, role.subject, role.numberedPrefix]
  );

  const current = inputValue(value);
  /** The box's value with any earlier pointer clause removed — the claim about the record alone. */
  const claim = stripPointerNote(current);

  /**
   * One option per countable file.
   *
   * The LABEL is what the record page's own gallery shows for the same file — the caption if the
   * uploader wrote one, otherwise the original filename — so a row here and a tile there read as the
   * same file. The id is deliberately NOT searched by `SearchableSelect` (a 25-character CUID matches
   * a great many two-letter queries), so everything worth searching is in the label or the HINT,
   * which is searched as well as shown: the kind of file and when it was uploaded, which is how
   * somebody says "the audio from Tuesday" to a control.
   */
  const options = useMemo<DropdownOption[]>(
    () =>
      countable.map((media) => ({
        value: media.id,
        label: media.caption || media.originalFilename,
        hint: `${media.mediaType.toLowerCase()} · ${formatDateTime(media.createdAt)}`
      })),
    [countable]
  );

  /**
   * Write the value one selection means, or refuse it out loud.
   *
   * THE BASE IS ALWAYS THE CLAIM ALREADY IN THE BOX, stripped of any earlier clause first — which is
   * what makes a second pick REPLACE the pointer rather than append a second one. Without the strip
   * the third click produces a value that has grown two sentences, overrun 200 characters, and will be
   * refused by `coerce_value` on a save the designer cannot connect to anything they did.
   *
   * WHERE THE BOX IS BLANK the record's own count is used as the base when it is available, because
   * that is precisely the sentence hydration would have written into the blank on the next save. Where
   * it is not available (a truncated listing) `composePointerNote` falls back to a sentence that makes
   * no claim about the record at all.
   *
   * AN OVER-LENGTH RESULT IS NOT WRITTEN AND NOT SHORTENED. The ticks stay where the designer put
   * them so the panel can say which selection was refused and by how much; the box keeps the value it
   * had. Truncating to fit would store a count nobody could tell was wrong, which is the failure this
   * repository names as its most repeated class.
   */
  const applySelection = useCallback(
    (next: string[]) => {
      setPicked(next);
      if (!next.length) {
        setRefusedOverrun(0);
        // Back to the claim alone — the record's own count, which is what hydration wrote and what
        // the report prints. `null` and not `""` for a claim that was never there: an empty string is
        // a value, and `coerce_value` reads a blank as an unanswered field either way, but `null` is
        // what every other control in this form sends for "nothing here".
        onChange(claim || null);
        return;
      }
      const chosen = countable.filter((media) => next.includes(media.id));
      const composed = composePointerNote({ subject: role.subject, base: claim || recordCount || "", rows: chosen });
      if (!composed) return;
      const overrun = mediaNoteOverrun(composed, field.maxLength);
      setRefusedOverrun(overrun);
      if (overrun) return;
      onChange(composed);
    },
    [claim, countable, recordCount, role.subject, field.maxLength, onChange]
  );

  /** The note itself. Always present, always editable, in every state below. */
  const noteBox = (
    <>
      <input
        className="field-input"
        type="text"
        maxLength={field.maxLength || undefined}
        // `aria-labelledby` and not a `<label htmlFor>`: this control contains a dropdown and two
        // buttons, so `FieldInput` wraps it with `unlabelled` — a `<span className="field-label">`
        // whose id arrives here. A wrapping `<label>` would forward a stray click into the menu and
        // slam it shut after one pick.
        aria-labelledby={labelId}
        aria-describedby={describedBy}
        aria-invalid={invalid}
        value={current}
        disabled={disabled}
        onChange={(event) => {
          onChange(event.target.value);
          // A hand edit is a different answer from the one the ticks describe, so the ticks stop
          // claiming to describe it. Clearing them here rather than leaving them is the honest
          // direction: a chooser showing three files over a sentence somebody has since rewritten is
          // a control lying about the box beneath it.
          if (picked.length) setPicked([]);
          setRefusedOverrun(0);
        }}
      />
      {dictation}
    </>
  );

  /* ── The reference is not linked, so there is no record to list ──────────────────────────────── */
  if (!refId) {
    return (
      <div className="grid gap-1">
        {noteBox}
        <p className="text-xs text-ink-500">
          This sentence fills itself in from the {role.subject} record and counts the files attached to it. Choose{" "}
          <span className="font-medium">{role.refField.label}</span> on this row and those files can be listed here.
        </p>
      </div>
    );
  }

  /* ── The listing could not be loaded ────────────────────────────────────────────────────────── */
  if (failed) {
    return (
      <div className="grid gap-1">
        {noteBox}
        {/*
          Said out loud rather than left as a box that looks like every other box — the same treatment
          `StageWorkshopField` gives a failed workshop list. A designer who cannot see WHY the files
          are missing concludes the record has none, which is the worse of the two readings. Nothing
          is withheld by the failure: the sentence is composed SERVER-SIDE on the next save whatever
          this listing does.
        */}
        <p className="text-xs text-ink-500">
          The files attached to the {role.subject} record could not be listed, so this is a plain box. The sentence still
          fills itself in from the record when this stage is saved; reload the page to try the list again.
        </p>
      </div>
    );
  }

  /* ── Still loading ─────────────────────────────────────────────────────────────────────────── */
  if (rows === null) {
    return (
      <div className="grid gap-1">
        {noteBox}
        {/*
          A sentence and NOT a disabled dropdown. A disabled control that may not appear at all once
          the answer arrives — the chooser is withheld below two files — would be a control the
          designer watches for and then cannot find, and a placeholder that reads "Loading…" over a
          list that turns out to be empty has promised something. The box above is usable throughout.
        */}
        <p className="text-xs text-ink-500">Listing the files attached to the {role.subject} record…</p>
      </div>
    );
  }

  /* ── Loaded ────────────────────────────────────────────────────────────────────────────────── */

  const truncated = total > rows.length;
  /**
   * The box's claim and this listing do not agree about the same record.
   *
   * Only asked where a count can be composed at all, and only against the CLAIM rather than the whole
   * value, so a designer's own pointer clause never registers as a disagreement about the count.
   */
  const disagrees = Boolean(recordCount && claim && claim !== recordCount);
  const gridSentence =
    gridFrames > 0
      ? `${gridFrames} measurement-grid frame${gridFrames === 1 ? " is" : "s are"} attached and deliberately not counted — a photograph of ruled paper is not footage of the subject.`
      : "";

  return (
    <div className="grid gap-1">
      {noteBox}

      {countable.length >= CHOOSER_FLOOR ? (
        <div className="grid gap-1">
          {/*
            A sub-label for the chooser, as a `<span>` and never a `<label>`: the field's own name is
            already published above by `FieldInput`'s `unlabelled` wrapper, and a second `<label>`
            around a themed dropdown is the click-forwarding trap this app's form rules open with.
            `ariaLabel` below is what names the trigger, because HTML-AAM computes a `<button>`'s name
            from its own contents and would otherwise announce only the tick count.
          */}
          <span className="field-label">Point the reader at particular files</span>
          <MultiSelectDropdown
            values={picked}
            onChange={applySelection}
            options={options}
            placeholder={`All ${countable.length} attached file${countable.length === 1 ? "" : "s"}`}
            ariaLabel={`Which of the ${role.subject} record's files this sentence points at`}
            describedBy={describedBy}
            disabled={disabled}
            // The options are RECORDS — one per uploaded file — so the filter box is this call site's
            // decision and not the option count's. A record with nine attachments would otherwise
            // grow a filter box the same record with seven does not have, and this list is a file
            // count: one on a new artisan, forty on a long-documented one.
            searchable
            // No Confirm, and no advance. The effect of each tick is ON SCREEN in the box above as it
            // happens, so there is nothing for a "done" button to signal, and moving focus away from a
            // control the designer is still adjusting is wrong — the same call `ExistingMedia` and the
            // list funnels make. What is different here is that this control DOES fill in a form
            // field, which is normally the case for `confirmOnSelect`: the box above is what settles
            // it. A designer can see the answer being written.
            confirmOnSelect={false}
          />

          {picked.length ? (
            <>
              <p className="text-xs text-ink-500">
                {picked.length} of the {countable.length} attached file{countable.length === 1 ? "" : "s"} named. The
                record&apos;s own count is kept as the first sentence — narrowing never makes it smaller.{" "}
                <button
                  type="button"
                  className="font-medium text-purple-700 underline underline-offset-2"
                  onClick={() => applySelection([])}
                  disabled={disabled}
                >
                  Name none of them
                </button>{" "}
                to leave only that count.
              </p>
              {/*
                WHO OWNS THIS BOX NOW. Stated because a designer should not have to learn it from a
                provenance line: `merge_entry_provenance` rule 3 moves the stamp from the record's
                recorder to the person making the save the moment the stored value differs from what
                the row held, and `FieldProvenance` then draws their name where "From the artisan
                record" used to be. It is the right behaviour and it is invisible until it happens.
              */}
              <p className="text-xs text-ink-500">
                This is your answer now rather than the record&apos;s: the provenance line under this box will name you
                instead of the {role.subject} record, and an admin&apos;s divergence audit stops comparing this box
                against the record at all. Clearing the box entirely puts it back — hydration refills a blank on the
                next save.
              </p>
            </>
          ) : (
            <p className="text-xs text-ink-500">
              The box counts everything attached to the {role.subject} record, which is what the report prints. Ticking
              files adds a second sentence naming the ones a reader should ask for first; the count itself is never
              changed.
            </p>
          )}

          {gridSentence ? (
            /*
              THE SUBTRACTION, SAID WHERE THE NUMBERS ARE. Without this line the chooser offers "All 5
              attached files" while the record page beside it lists 7, and nothing on screen accounts
              for the two. `GET /media` does not subtract grid frames and `_media_note` does, so this
              control has to be the one that explains the difference — the same rule as every other cap
              and skip in this app: a list that quietly stops is indistinguishable from a place with
              nothing in it.
            */
            <p className="text-xs text-ink-500">{gridSentence}</p>
          ) : null}

          {refusedOverrun > 0 ? (
            /*
              REFUSED, NOT TRUNCATED, and the box is untouched. `coerce_value` rejects a string longer
              than the declared bound and `save_stage` then restores the refused key from `previous`,
              so a value written over the bound would come back as an error against a box that had
              silently reverted. error-600 is one of the two literal status colours in the palette and
              deliberately does not invert: "this is wrong" must read identically in both themes.
            */
            <p className="text-xs font-medium leading-5 text-error-600">
              Naming those {picked.length} files would make the sentence {refusedOverrun} character
              {refusedOverrun === 1 ? "" : "s"} longer than the {field.maxLength} this field stores, so nothing was
              written and the box is unchanged. Name fewer files, or shorten the sentence in the box by hand first.
            </p>
          ) : null}
        </div>
      ) : countable.length === 1 ? (
        <p className="text-xs text-ink-500">
          One file is attached to the {role.subject} record and the sentence already names it, so there is nothing to
          narrow. {gridSentence}
        </p>
      ) : (
        /*
          Loaded, and nothing countable. TWO CAUSES, AND THEY ARE NOT DISTINGUISHABLE FROM HERE, so
          both are named. Either the record genuinely has nothing attached — in which case
          `_media_note` returns None, the key is ABSENT from the payload and the box stays blank,
          deliberately, rather than reading "0 files" — or the files it counted are attached by the
          typed foreign key while this listing follows the string tag pair, which is the state a
          deleted parent leaves behind (`media.py` SET NULLs the FK and leaves the tag).
        */
        <p className="text-xs text-ink-500">
          No files are listed against the {role.subject} record{gridFrames > 0 ? ", other than grid frames" : ""}, so
          there is nothing to point at. {gridSentence}
          {claim
            ? " The sentence in the box was written when this stage was last saved — either the record has lost files since, or the ones it counted are linked in a way this listing does not follow. It is left exactly as it is."
            : ""}
        </p>
      )}

      {truncated ? (
        /*
          THE CAP IS STATED, and it also changes what the control will compose: with part of the list
          missing, a count over what arrived would be a smaller number printed as a total. So
          `recordCount` is null above, the "use the count of the files listed here" offer is withheld,
          and a narrowing sentence makes no claim about the record. The hydrated count in the box is
          the SERVER's, taken over every row, and is the one number here that is complete.
        */
        <p className="text-xs text-ink-500">
          Only the {rows.length} most recent of {total} attached files are listed here, so nothing on this screen can
          state the record&apos;s own total. The count already in the box was taken over all {total} by the server.
        </p>
      ) : null}

      {disagrees ? (
        /*
          NAMED BEFORE IT IS OFFERED, the same discipline `IdentityCardCapture` is mounted under in
          `FieldInput` — it prints the value being replaced and says that confirming replaces it. The
          two numbers have two different authorities: the box was composed by the server over the
          typed foreign key at save time, this listing follows the string tag pair now. Adopting one
          silently would be picking a winner on the designer's behalf.
        */
        <p className="text-xs text-ink-500">
          The count in the box and the files listed here do not agree. The box says
          {" "}
          <span className="font-medium">{claim}</span> — written by the server when this stage was last saved — and the{" "}
          {countable.length} file{countable.length === 1 ? "" : "s"} listed now add up to{" "}
          <span className="font-medium">{recordCount}</span>{" "}
          <button
            type="button"
            className="font-medium text-purple-700 underline underline-offset-2"
            onClick={() => {
              setPicked([]);
              setRefusedOverrun(0);
              onChange(recordCount);
            }}
            disabled={disabled}
          >
            Use the count of the files listed here
          </button>{" "}
          replaces the sentence in the box with the second one and drops any files it names.
        </p>
      ) : null}

      {hasPointerNote(current) && !picked.length ? (
        /*
          THE LOSS, ON SCREEN. Which files an earlier visit named is stored nowhere — see the header —
          so the chooser cannot reopen with them ticked and must not pretend the clause is unrelated
          to it. Ticking again REPLACES the clause (`stripPointerNote` runs first), which is the
          behaviour a designer would expect from a chooser and the opposite of what an accumulating
          sentence would do.
        */
        <p className="text-xs text-ink-500">
          The sentence in the box already names particular files. Which files those were is recorded nowhere — only how
          many of each kind — so ticking here replaces that clause rather than adding to it.
        </p>
      ) : null}
    </div>
  );
}
