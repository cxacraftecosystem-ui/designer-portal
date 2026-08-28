/**
 * The MEDIA PICKER's pure half — every judgement that decides what the repository list SAYS, lifted
 * out of `components/media/MediaRepositoryPicker.tsx` so it can be CALLED rather than only looked at.
 *
 * WHY THIS FILE EXISTS. There is no React renderer in this repository's devDependencies — Playwright
 * is the whole of it — so a rule written inside a component body can only ever be asserted as a
 * SUBSTRING of that file, which pins the spelling of a sentence and not the condition that decides
 * whether a designer is shown it. `components/ui/selectFilter.ts`, `components/data/cappedList.ts`
 * and `StageReferenceField`'s `scopeNoticeLines` are the same split for the same reason;
 * `e2e/media-repository-picker-unit.spec.ts` exercises these by calling them.
 *
 * THREE OF THE FOUR THINGS BELOW ARE WAYS A PICKER LIES ABOUT ITS OWN CONTENTS. The first and the
 * third have already shipped here — an unstated truncation is the trap index's "single most repeated
 * bug class in this repo", and both clients read an absent `maxItems` as no ceiling at all until
 * 2026-08-26. The second has not, as far as this file's author could establish, and is written down
 * anyway because it is the first of the three one narrowing away:
 *
 *  · **The list stopped and did not say so.** `GET /media` pages, so what is drawn is a WINDOW on the
 *    repository. A window presented as the whole is rule 10 — a list that quietly stops is
 *    indistinguishable from a place with no records. {@link mediaPickerNotice} is the sentence.
 *  · **The list was narrowed and did not say so.** An IMAGE field asks the endpoint for photographs
 *    only, so a designer hunting for the PDF they uploaded this morning is looking at a list it was
 *    never in. The same sentence names the narrowing.
 *  · **A ceiling turned files away quietly.** `coerce_value` REFUSES an over-long array rather than
 *    trimming it, so the honest act here — where there is somebody to tell — is to take what fits and
 *    NAME what did not. {@link acceptRepositoryPicks} and {@link repositoryRefusalSentence}.
 *
 * The fourth is not a lie but an ANSWER, and it is the one most likely to be read as a failure:
 * `MediaFile.url` is withheld at the encoder for a caller not entitled to those bytes, so a row can
 * arrive complete — name, type, date — with no url at all. {@link repositoryEntitlementNotice} says
 * so in words. Nothing here may refuse such a file: the id is what a field stores, and what an
 * account may open is decided every time the row is read.
 */

import { RENDER_CAP, type SelectOption } from "@/components/ui/selectFilter";
import { formatDateTime } from "@/lib/format";
import type { MediaFile, MediaType } from "@/lib/types";

/**
 * How many rows one request asks `GET /media` for.
 *
 * `RENDER_CAP` AND NOT THE ENDPOINT'S CEILING, which is the trap this constant exists to avoid.
 * `pageSize` on that route is `Query(20, ge=1, le=100)`, so 100 is available and is the wrong number:
 * `SearchableSelect` draws at most `RENDER_CAP` rows and prints its own "Showing the first 80 of 100"
 * footer, so asking for a hundred produces TWO truncation sentences with TWO different totals, one
 * above the other, and says nothing at all about the band between 81 and 100. `/design-review` shipped
 * exactly that, which is why `RENDER_CAP` is exported from `selectFilter` in the first place.
 *
 * A page size written as `RENDER_CAP` cannot drift from the number that governs it.
 */
export const MEDIA_PICKER_PAGE_SIZE = RENDER_CAP;

/**
 * How long after the last keystroke the repository search goes out.
 *
 * The reference picker's number, to the millisecond, and for its reason: `list_media`'s `search`
 * builds an OR of three `contains` tests (`originalFilename`, `caption`, `mimeType`), which is an
 * `ILIKE '%…%'` that no index can answer. Every keystroke that escapes the debounce is a full scan.
 */
export const MEDIA_SEARCH_DEBOUNCE_MS = 300;

/**
 * WHICH `mediaType` THE PICKER ASKS THE ENDPOINT FOR — derived from the capture card's own list, so
 * the two halves of one media field cannot come to disagree about what it accepts.
 *
 * The argument is `ALLOWED_TYPES[field.type]` verbatim: the same array `FieldInput` hands
 * `MediaCaptureField` as `allowedTypes`. Passing the array rather than the field type is the whole
 * point — a picker with its own copy of that mapping is a second register, and the two would drift
 * the first time a field type changed what it accepts.
 *
 * NULL MEANS "ASK FOR EVERYTHING", and it is the answer in TWO cases rather than one:
 *
 *  · **No list at all** — a FILE field passes no `allowedTypes`, so its chooser offers every kind of
 *    attachment and nothing is filtered out of it. The picker matches that: any file may be chosen.
 *  · **More than one kind** — `list_media` takes ONE `mediaType` string, not a list, so a field
 *    accepting two would have to pick one of them and the other's files would be silently absent from
 *    a list that looks complete. Filtering nothing and saying so is the honest answer; narrowing to
 *    half and saying nothing is the failure this module is written against. No field in the registry
 *    declares two today (`ALLOWED_TYPES` maps IMAGE, IMAGE_LIST, AUDIO and VIDEO to exactly one each),
 *    so this arm is unreachable and correct rather than dead — it is what stops the next such field
 *    from shipping a half-list.
 */
export function mediaPickerTypeFilter(allowed: MediaType[] | undefined): MediaType | null {
  if (!allowed || allowed.length !== 1) return null;
  return allowed[0];
}

/**
 * What to call the things in the list, given the narrowing.
 *
 * A sentence about "1 files" or about "files" over a list the server narrowed to photographs is the
 * small dishonesty that makes a reader distrust the large ones. Both forms, because two of the
 * sentences below need the singular and two need the plural.
 */
export function mediaPickerNoun(typeFilter: MediaType | null): { singular: string; plural: string } {
  switch (typeFilter) {
    case "IMAGE":
      return { singular: "photograph", plural: "photographs" };
    case "VIDEO":
      return { singular: "video", plural: "videos" };
    case "AUDIO":
      return { singular: "audio recording", plural: "audio recordings" };
    case "PDF":
      return { singular: "PDF", plural: "PDFs" };
    case "DOCUMENT":
      return { singular: "document", plural: "documents" };
    default:
      // OTHER and the unnarrowed list are both "file": the endpoint's own OTHER bucket is whatever
      // did not classify, and naming it in copy would be inventing a category for the reader.
      return { singular: "file", plural: "files" };
  }
}

/**
 * One repository row per option, in the order the server sent them (`createdAt desc`).
 *
 * THE LABEL IS WHAT A TILE WOULD SHOW — the caption if the uploader wrote one, otherwise the original
 * filename — so the row in the panel and the row in the field read as the same file. `ExistingMedia`
 * makes the same choice for the same reason.
 *
 * EVERYTHING WORTH SEARCHING GOES IN THE LABEL OR THE HINT and the `value` is the media id, which
 * `SearchableSelect` deliberately does not search: a 25-character CUID matches a great many two-letter
 * queries. That costs nothing here — this picker's search is the SERVER'S and the panel's own filter
 * box is off — but the rule holds anyway, because the day somebody turns the box on is the day it
 * would start ranking unrelated files above the one that was typed.
 *
 * AN ALREADY-ATTACHED FILE IS DRAWN AND DISABLED, never hidden. Hiding it would mean a designer who
 * knows the photograph is in the repository types its name, finds nothing, and concludes it is not
 * there — absence reading as non-existence over a file they attached themselves five minutes ago.
 * Disabled says the true thing instead, and `SearchableMultiSelect`'s "select all matching" skips
 * disabled rows, so a bulk tick cannot smuggle a duplicate id into the value either.
 *
 * A ROW WITH NO `url` IS NEITHER HIDDEN NOR DISABLED — see {@link repositoryEntitlementNotice}.
 */
export function mediaPickerOptions({
  rows,
  attachedIds
}: {
  rows: MediaFile[];
  attachedIds: string[];
}): SelectOption[] {
  const attached = new Set(attachedIds);
  return rows.map((row) => {
    const hints = [row.mediaType.toLowerCase(), formatDateTime(row.createdAt)];
    if (row.uploadedBy?.name) hints.push(row.uploadedBy.name);
    // The same sentence `StoredMediaImage`, `MediaCarousel` and `DocumentPreview` use for this state,
    // shortened to fit a picker row. One wording for one fact, across every surface that meets it.
    if (!row.url) hints.push("stored, but this account may not open the file itself");
    if (attached.has(row.id)) hints.push("already attached");
    return {
      value: row.id,
      label: row.caption?.trim() || row.originalFilename,
      hint: hints.join(" · "),
      disabled: attached.has(row.id)
    };
  });
}

/**
 * WHAT THE LIST ACTUALLY IS, in one sentence, given what the server answered.
 *
 * Always a sentence and never null, because it is also the panel's `emptyLabel`: an empty picker that
 * says "No options" is the generic line that cannot tell "the repository holds none of these" from
 * "your search matched none of these" from "the request failed". Every state this list can be in has
 * a sentence here, so the panel and the line under the box are two views of one fact rather than a
 * form arguing with itself.
 *
 * THE TRUNCATION CLAUSE NAMES THE BOX THAT REACHES THE REST. `searchable={false}` does not switch the
 * render cap off, and the picker's filter box is deliberately the SERVER'S — so the one instruction
 * that must never appear is "keep typing to narrow the list" pointing at a control the panel does not
 * have. §11.5 of the frontend reference is this paragraph.
 */
export function mediaPickerNotice({
  loading,
  problem,
  query,
  shown,
  total,
  typeFilter
}: {
  loading: boolean;
  problem: string | null;
  query: string;
  /** Rows this page is holding — never more than {@link MEDIA_PICKER_PAGE_SIZE}. */
  shown: number;
  /** `PageResult.total`: how many rows the WHERE matched, which is not how many were sent. */
  total: number;
  typeFilter: MediaType | null;
}): string {
  const noun = mediaPickerNoun(typeFilter);
  if (problem) return problem;
  /*
    LOADING IS ANSWERED FIRST, AND IN TWO FORMS, because both of the sentences underneath it are FALSE
    while a request is in flight and each is false in a way a designer acts on.

    With nothing on screen, the "there is nothing here" line reads as a claim about the repository for
    as long as the request takes — which on a village connection is the several seconds somebody
    spends deciding the feature does not work. With the PREVIOUS search's rows still drawn it is
    worse, not better: the reader has just typed a filename, the list under the box is the answer to
    something else, and a sentence reconciling it to that other query is the "No matches" lie one step
    removed. So the rows stay (blanking them on every keystroke is its own flicker) and the sentence
    says which question they answer. `MediaField` draws the same distinction for a media id it has not
    finished looking up, for the same reason.
  */
  if (loading) {
    return shown === 0
      ? `Searching the repository for ${noun.plural}…`
      : `Searching the repository… the ${shown} ${shown === 1 ? noun.singular : noun.plural} listed below ` +
          `${shown === 1 ? "is" : "are"} the answer to the previous search.`;
  }
  if (total === 0) {
    return query.trim()
      ? `No ${noun.singular} in the repository matches “${query.trim()}”. The search asks the repository, not this page, so this is every ${noun.singular} you may read.`
      : // "you may read" and not a flat "there is none": `list_media` composes `viewable_where`, so an
        // empty answer is a claim about this ACCOUNT'S view of the repository and never about the
        // repository. Stating it as the latter is how a reader concludes a colleague never uploaded
        // the photograph they are looking at on their own screen.
        `The repository holds no ${noun.singular} you may read yet.`;
  }
  if (shown < total) {
    return (
      `Showing the ${shown} most recently uploaded of ${total} ${noun.plural}. ` +
      `Type in the search box above to reach the rest — it asks the repository, so it searches every ` +
      `${noun.singular} you may read, not only the ${shown} listed here.`
    );
  }
  return total === 1
    ? `The one ${noun.singular} you may read is listed.`
    : `All ${total} ${noun.plural} you may read are listed.`;
}

/**
 * "Some of these are stored but not openable from this account", or null when they all are.
 *
 * AN ANSWER AND NOT A FAILURE, which is why it is a separate sentence from {@link mediaPickerNotice}
 * rather than a clause inside it. `MediaFile.url` travels only to a caller entitled to those bytes —
 * the uploader, an account they granted data access to, or a member of the design workshop the file
 * is tagged to — so a row arriving with a name, a type and a date and NO url is the encoder working
 * correctly. `StoredMediaImage`, `MediaCarousel` and `DocumentPreview` each draw that state as a
 * worded panel rather than a broken frame; this is the same fact stated before the choice is made.
 *
 * IT MUST NOT READ AS "DO NOT PICK THIS ONE". Attaching such a file is correct and often the point —
 * a co-designer pointing a stage at a photograph a colleague took. What is stored is the media id,
 * and whether a given reader may open the bytes is decided when the row is read, not when it is
 * attached, so a refusal here would be this client inventing a rule the server does not have.
 */
export function repositoryEntitlementNotice(rows: MediaFile[]): string | null {
  const withheld = rows.filter((row) => !row.url).length;
  if (!withheld) return null;
  return (
    `${withheld} of the ${rows.length} listed ${withheld === 1 ? "is" : "are"} stored, but this account may not ` +
    `open the ${withheld === 1 ? "file" : "files"} ${withheld === 1 ? "itself" : "themselves"}. ` +
    `${withheld === 1 ? "It" : "They"} can still be attached: this field stores a media id, and whether a ` +
    `reader may open the bytes is decided each time the file is read.`
  );
}

/**
 * The ceiling clause, shared by the two controls that can grow a media field.
 *
 * ONE SENTENCE IN ONE PLACE, because the split it encodes is a contract rather than a wording
 * preference: `field_to_dict` emits `maxItems` ONLY for a field that declares one, so a client that
 * prints "up to 200" is naming a figure it did not read and the server may change — and a stated cap
 * that is not the enforced cap is worse than no sentence at all (docs/DESIGN_WORKSHOP.md:229-232).
 * Enforcement is unconditional (`effectiveMaxItems`, 200 where nothing is declared); PRINTING is
 * conditional on `declaredCap`. Both halves, neither traded for the other.
 *
 * The capture card's refusal and the picker's refusal differ only in their last clause — what each
 * control KEEPS for the reader to retry with — so that is where they part company and this is what
 * they share. A second private copy of the declared/undeclared branch is exactly how the two would
 * come to disagree about the one paragraph the doc governs.
 */
export function capCeilingClause({
  label,
  declaredCap,
  accounted
}: {
  label: string;
  /** The registry's declared ceiling, or null where it declared none. Never the effective one. */
  declaredCap: number | null;
  /** How many entries the field is already holding or is about to — attached plus in flight. */
  accounted: number;
}): string {
  if (declaredCap === null) return `${label} is full`;
  return (
    `${label} holds at most ${declaredCap} file${declaredCap === 1 ? "" : "s"}, and ` +
    `${accounted} ${accounted === 1 ? "is" : "are"} accounted for`
  );
}

/**
 * Take what fits, hand back what did not.
 *
 * TRIMMING IS RIGHT HERE AND REFUSING IS RIGHT ON THE SERVER, the same argument `acceptFiles` makes
 * one door earlier: `coerce_value` is the last door, it cannot ask, and silently keeping 20 of 25
 * there would store a value no client sent. Here there is somebody to tell, immediately, so the
 * honest act is to take what fits and name the rest.
 *
 * `room` IS NEVER NULL FOR A GALLERY. An undeclared ceiling is a ceiling of `DW_DEFAULT_MAX_ITEMS`,
 * not the absence of one, so this is always called with a number — and `Math.max(0, …)` is not
 * padding: `room` is computed from attached-plus-in-flight against the cap, and a value already over
 * its ceiling (a draft written by an older client enforcing a larger one) must refuse everything
 * rather than slice with a negative and quietly hand back the whole list.
 */
export function acceptRepositoryPicks<T>({ picked, room }: { picked: T[]; room: number }): {
  attach: T[];
  refused: T[];
} {
  const capacity = Math.max(0, room);
  if (picked.length <= capacity) return { attach: picked, refused: [] };
  return { attach: picked.slice(0, capacity), refused: picked.slice(capacity) };
}

/**
 * The picker's refusal, in words — or null when the ceiling turned nothing away.
 *
 * NAMES THE FILES, because "only 20 photographs are allowed" tells a designer holding 25 nothing
 * about WHICH five to deal with. Same rule `uploadMediaBatch`'s callers are under one step later, and
 * the same rule `acceptFiles` follows for files off the chooser.
 *
 * ITS LAST CLAUSE IS THE ONE THING IT DOES NOT SHARE WITH THE CAPTURE CARD'S. A file the chooser
 * turned away is gone from the browser and has to be picked out of the filesystem again, so that
 * sentence says "pick them again — this list stays until you do". A repository pick was never a file
 * at all: the rows are still ticked in the picker, so the instruction is to make room and press
 * Attach again, and saying "pick them again" would send a designer hunting for something they have
 * already chosen.
 *
 * "IN THE REPOSITORY LIST" AND NOT "IN THE LIST ABOVE", because the picker is a disclosure and this
 * sentence outlives it being closed: the ticks are held in state, so collapsing the panel hides the
 * list and forgets nothing, and a sentence pointing "above" would then name a control that is not on
 * screen — the same defect as a `capHint` naming an absent filter box, one paragraph over.
 */
export function repositoryRefusalSentence({
  label,
  declaredCap,
  accounted,
  refusedNames
}: {
  label: string;
  declaredCap: number | null;
  accounted: number;
  refusedNames: string[];
}): string | null {
  if (!refusedNames.length) return null;
  const one = refusedNames.length === 1;
  return (
    `${capCeilingClause({ label, declaredCap, accounted })}. Not attached: ${refusedNames.join(", ")}. ` +
    `Remove something already attached, then press Attach again — ${one ? "it is" : "they are"} still ` +
    `ticked in the repository list.`
  );
}
