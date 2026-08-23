"use client";

/**
 * The REF picker: search the records the database already holds, choose one, and let it fill the row
 * in.
 *
 * WHY THIS IS NOT `Dropdown` / `SearchableSelect`. Those two filter a list the caller already has, in
 * the browser, and that is exactly the wrong shape here. A cluster has hundreds of artisans, the
 * server caps a reference response at fifty rows and says so, and the product list is not a list at
 * all until an artisan has been chosen — it is a different list per row. So the search is the
 * SERVER'S (`GET /design-workshops/{id}/references`), debounced, and the panel is a real combobox
 * over whatever came back.
 *
 * THE CASCADE is the reason this file exists rather than a two-line change to the old dropdown. A
 * descriptor carrying `refFilterBy` names a field ON THE SAME ROW whose value narrows this one: the
 * product picker on an "existing products" row offers the products of the artisan chosen on THAT row
 * and nothing else. Two rows are two artisans and therefore two lists, which is why the filter value
 * is read from the row rather than from the stage.
 *
 * THREE THINGS THE SERVER SAYS THAT THIS PANEL MUST REPEAT, because each of them is a way a picker
 * lies about its own contents:
 *
 * - `scopedToWorkshop: false` on a WORKSHOP-scoped field means the workshop is not linked to a
 *   Workshop record and the server widened to the whole table rather than serving nothing. A
 *   designer reading an unlabelled list assumes it is this workshop's roster and picks somebody who
 *   was never in the room.
 * - `truncated: true` means there are more matches than are drawn. A list that quietly stops at
 *   fifty is indistinguishable from a cluster with fifty artisans.
 * - `filtered: true` with an EMPTY list is a real and ordinary answer — the artisan was typed in by
 *   hand on day two and has no documented products. Saying "no results" there invites the designer
 *   to clear the artisan and pick somebody else's product.
 *
 * A THIRD WAY TO CHOOSE, BESIDE TYPING AND CREATING: THE CARD ITSELF. Every record this repository
 * issues carries a printed code, and a designer holding a colleague's artisan card could look it up
 * on `/search` and then had to READ THE NAME OFF IT AND TYPE IT INTO THE BOX BELOW — which is the
 * mis-typed-identifier failure the whole code feature exists to remove, reintroduced at the last
 * step. So this control mounts `WorkshopCodeScanner`, resolves the scanned id through the SAME
 * references endpoint the list comes from (its `recordId` parameter is the by-id half), and then
 * hands the answer to {@link StageReferenceSelect}'s own `choose` — the identical code path a
 * manual pick takes, so the hydration, the cascade clearing and the row patch cannot differ by how
 * the record was chosen. A scan is never a second way to write a row. See
 * {@link scanTypeRefusal} and {@link scanLookupOutcome} for the three refusals, each of which is a
 * sentence rather than a silent no-op, and {@link scanCommitDecision} for the fourth answer — the
 * one where the lookup succeeded and the row moved underneath it while it was in flight.
 */

import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import { Check, ChevronDown, Loader2, Pencil, Plus, ScanLine, Search, X } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import {
  INLINE_MODEL_NOUN,
  InlineRecordDialog,
  isInlineCreatable,
  type InlineCreatableModel
} from "@/components/designworkshop/InlineRecordDialog";
import { useLinkedWorkshopId } from "@/components/designworkshop/LinkedWorkshop";
import { WorkshopCodeScanner, type ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import type { InlineHostSeed } from "@/components/forms/inlineRecordHost";
import {
  geoValue,
  hydrateFromReference,
  inputValue,
  isMultiField,
  listStageReferences,
  referenceDisplayHint,
  referenceHydrationFor,
  stringifyRefValue,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption,
  type DwReferencePayload,
  type DwValue
} from "@/lib/designWorkshops";
import { isUnreachable } from "@/lib/offline";
import { canManageCrafts } from "@/lib/permissions";
import { workshopRecordTypeLabel, type WorkshopCodeRef, type WorkshopRecordType } from "@/lib/workshopCodes";

/**
 * How long after the last keystroke the search goes out.
 *
 * 300 ms is the measured floor for this: a `contains` search over the artisan table is an ILIKE
 * '%…%' that no index can answer (the server says so in its own comment), so every keystroke that
 * escapes the debounce is a full scan of the largest table in the database. Shorter and a fast
 * typist fires six of them for one name; longer and the list visibly lags the box.
 */
const SEARCH_DEBOUNCE_MS = 300;

/** The server's own default page. Stated here only so the truncation notice can name it. */
const REFERENCE_PAGE = 50;

/**
 * The widest page the server will serve — `REFERENCE_LIMIT_MAX` in `design_workshops.py`, which
 * clamps anything larger rather than refusing it.
 *
 * Asked for by ONE call only: {@link describeCreated}, which is hunting for a single record it
 * already knows the id of. Every other request here is a list a human reads, and fifty is the right
 * size for that; a page four times as long would be four times the ILIKE scan for rows nobody
 * scrolls to. The hunt is the opposite case — the one row it exists to find is the one that must not
 * fall off the end of the page.
 */
const REFERENCE_PAGE_MAX = 200;

type PickerState = {
  payload: DwReferencePayload | null;
  loading: boolean;
  problem: string | null;
};

/**
 * Everything the two variants share: the debounce, the race guard and the request itself.
 *
 * `generation` and not an `AbortSignal`, because `apiFetch` takes no signal — the house convention
 * for this exact case is to count fetches and ignore the late answer. A picker that rendered a stale
 * answer would show the results for "kam" under the word "kamla", which is precisely when a designer
 * clicks the first row without reading it.
 */
function useReferenceOptions({
  workshopId,
  field,
  filterValue,
  query,
  active
}: {
  workshopId: string;
  field: DwField;
  filterValue: string;
  query: string;
  active: boolean;
}): PickerState {
  const [state, setState] = useState<PickerState>({ payload: null, loading: false, problem: null });
  const generation = useRef(0);

  useEffect(() => {
    if (!active || !field.refModel) return;
    const current = generation.current + 1;
    generation.current = current;
    setState((previous) => ({ ...previous, loading: true, problem: null }));
    const timer = window.setTimeout(() => {
      listStageReferences(workshopId, {
        model: field.refModel as string,
        scope: field.refScope,
        // Sent only when the descriptor asks for the cascade. An unasked-for `filterBy` on a model
        // that cannot honour one is a 422 by design — the server refuses to silently serve the whole
        // table to a picker the designer believes is narrowed.
        filterBy: field.refFilterBy ? filterValue || null : null,
        search: query.trim() || null
      })
        .then((payload) => {
          if (generation.current !== current) return;
          setState({ payload, loading: false, problem: null });
        })
        .catch((error) => {
          if (generation.current !== current) return;
          setState({
            payload: null,
            loading: false,
            problem: error instanceof Error ? error.message : "The list could not be loaded."
          });
        });
    }, query ? SEARCH_DEBOUNCE_MS : 0);
    return () => window.clearTimeout(timer);
  }, [workshopId, field.refModel, field.refScope, field.refFilterBy, filterValue, query, active]);

  return state;
}

/**
 * WHAT THE LIST ACTUALLY IS, in sentences, given the field and what the server answered.
 *
 * LIFTED OUT OF THE COMPONENT SO IT CAN BE EXECUTED BY A TEST. There is no React renderer in this
 * repository's devDependencies, so a rule that stays inside a component body can only ever be
 * asserted as a SUBSTRING of this file — which pins the spelling of a sentence and not the condition
 * that decides whether a designer is shown it. `inlineSeed` was lifted for the same reason and the
 * same test file covers both. The component below is what is left: `lines.join(" ")` in a `<p>`.
 */
export function scopeNoticeLines(field: DwField, payload: DwReferencePayload | null): string[] {
  if (!payload) return [];
  const lines: string[] = [];
  if (field.refScope === "WORKSHOP" && !payload.scopedToWorkshop) {
    lines.push(
      "This design workshop is not linked to a workshop record yet, so every documented record is offered rather than only this workshop's."
    );
  }
  /*
    THE OTHER HALF OF THE SAME SENTENCE, AND THE ONE THAT COSTS A DESIGNER AN AFTERNOON.

    This notice only ever spoke when the design workshop was UNLINKED. A LINKED one with nothing
    attached to its workshop record is the commoner case and said nothing at all: the list came back
    empty and the picker printed its generic "No records to choose from yet.", which is a claim about
    the REPOSITORY when the truth is a claim about the FILTER. A designer reads it as "nothing has
    been documented", closes the picker and types thirty products in by hand — the exact behaviour
    this whole feature exists to end — while the records sit in the repository one unticked link
    away.

    NOT SAID WHEN THE CASCADE EMPTIED THE LIST (`payload.filtered`): the picker's own empty line
    already names the parent row as the reason, and two competing explanations of one empty list is
    worse than the generic one.

    IT CHANGES NO SCOPING, deliberately. Whether WORKSHOP scope should widen when the linked
    workshop holds nothing is a server-side decision shared with four other screens; this says what
    the list IS and what the designer can do about it, and leaves that decision to be made
    deliberately. `DwReferenceField.kt`'s empty state carries the same sentence.
  */
  if (field.refScope === "WORKSHOP" && payload.scopedToWorkshop && !payload.filtered && !payload.options.length) {
    lines.push(
      "Nothing is documented under this design workshop’s linked workshop yet — this list is narrowed to that workshop rather than to the whole repository. Create the record here, or link the existing one to the workshop and it will appear."
    );
  }
  if (payload.truncated) {
    lines.push(`Only the first ${REFERENCE_PAGE} matches are listed — type more of the name to narrow them.`);
  }
  return lines;
}

/** The line under the list. Never omitted when {@link scopeNoticeLines} has something to say. */
function ScopeNotice({ field, payload }: { field: DwField; payload: DwReferencePayload | null }) {
  const lines = scopeNoticeLines(field, payload);
  if (!lines.length) return null;
  return <p className="px-3 pb-2 text-xs leading-5 text-ink-500">{lines.join(" ")}</p>;
}

/* ────────────────────────────────────────────────────────────────────────────
 * The record that did not exist when the list was fetched
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A record `InlineRecordDialog` just made, in the only shape this file is allowed to read it in.
 *
 * The dialog hands back the RAW REPOSITORY ROW — the same JSON `POST /products` returns — and the
 * whole argument below is that this file must not interpret one. So the type names the three
 * name-bearing columns and nothing else: `name` on an Artisan and a Process, `productName` on a
 * ProductDocumentation, `toolkitName` on a ToolDocumentation.
 */
type CreatedRecord = {
  id: string;
  name?: string | null;
  productName?: string | null;
  toolkitName?: string | null;
};

/**
 * The name a just-created record goes by.
 *
 * THE ONE THING READ OFF A RAW ROW HERE, and the exception is narrow on purpose. A label is a
 * label on every surface — the server's own `label` lambda for each of these models reads this
 * exact column, and it is also the FIRST of that model's `search_fields`, which is what makes it
 * usable as the search term in {@link describeCreated}. The values that fill a stage row are a
 * different matter entirely; see the note there for why none of them may be read from here.
 */
function createdLabel(record: CreatedRecord): string {
  return (record.name || record.productName || record.toolkitName || "").trim();
}

/**
 * WHAT THE SERVER WOULD SAY ABOUT A RECORD THAT DID NOT EXIST WHEN THE LIST WAS FETCHED.
 *
 * THE DEFECT THIS EXISTS TO END. Both pickers below used to build the new option's `data` out of
 * the raw row the create returned and hand it straight to `hydrateFromReference`. The row's keys
 * are PRISMA COLUMN NAMES and the hydration table's keys are the REFERENCE PAYLOAD's: for a
 * product the table asks for `name`, `price`, `use`, `material`, and the row carries
 * `productName`, `sellingPrice`, `productFunctionUse`, `rawMaterialsUsed`. Not one of them
 * matched, so an inline-created product hydrated NOTHING. That is not a cosmetic loss:
 * `existingProduct.name` and `existingProduct.price` are `required=True` and the server validates
 * BEFORE it hydrates (`design_workshops.py` says so beside the ordering), so the designer was left
 * looking at two blank required boxes and the stage 422'd on submit — seconds after they created
 * the record that holds both answers.
 *
 * WHY THE FIX IS A ROUND TRIP AND NOT A RENAMING TABLE IN THE BROWSER. `REFERENCE_MODELS[…].data`
 * is not a rename of the row, it is a TRANSLATION, and every part of it is load-bearing:
 * `_inches_to_cm` multiplies by 2.54 because the source columns are inches and
 * `existingProduct.lengthCm` prints "cm"; `_money` renders a Prisma Decimal as a two-place string
 * because a float round trip turns 1250.10 into 1250.0999999999999; `mask_identity_number` keeps a
 * full PM Vishwakarma card number out of a report every grantee can download; three exhaustive
 * enum tables refuse to guess a PRODUCT_CATEGORY for the four ProductType members that have no
 * honest one; the photograph and its caption are a join onto MediaFile that the row does not
 * contain at all. A browser-side copy would be a SECOND declaration of knowledge that already
 * lives in exactly one place — and every way it could drift writes a value that is WRONG rather
 * than missing. Hydration only ever fills blanks, so a wrong value written here can be corrected
 * but never un-answered; there is no state the mistake heals from. One request is cheaper.
 *
 * ANDROID DECIDED THIS FIRST and matching the handset is worth more than inventing a third
 * behaviour: `DwReferenceField.kt` arms `pendingHydration` for a record it holds an id but no
 * description of, and waits for the server's `data`, with a comment explaining that hydrating from
 * an empty map would be actively destructive.
 *
 * FOUND BY SEARCHING FOR THE RECORD'S OWN NAME, because the references endpoint has no by-id
 * route — see {@link createdLabel} for why that name is a reliable search term rather than a
 * guess.
 *
 * ASKED WITH THE SAME SCOPE AND FILTER THE PICKER'S OWN LIST USES, deliberately. A wider question
 * here would hydrate a row from a record this picker can never show: if the new product does not
 * belong to the artisan on this row, the cascade excludes it from the list and it must be excluded
 * from the hydration too, or the row quietly holds another artisan's price under this one's name.
 *
 * Returns null for both kinds of miss — a network failure and a record the narrowed list does not
 * contain — because the caller has one honest sentence to say about either and no action that
 * differs between them.
 */
async function describeCreated(
  workshopId: string,
  field: DwField,
  filterValue: string,
  record: CreatedRecord
): Promise<DwReferenceOption | null> {
  return describeRecord(workshopId, field, filterValue, record.id, createdLabel(record));
}

/**
 * What the server would say about ONE record, found by searching for the name it goes by.
 *
 * The body {@link describeCreated} used to hold, lifted out because the EDIT path needs the same
 * question asked twice — once before the record changes and once after — and a second copy of a
 * search that has to use the picker's own scope and filter is a second place for that to be got
 * wrong. See {@link describeCreated} for why the round trip exists at all rather than a renaming
 * table in the browser, and why the scope and filter are not negotiable.
 *
 * `label` is the search term and it must be the name the record goes by AT THE MOMENT OF ASKING:
 * before an edit that is the name already hydrated onto the row, and after one it is the name the
 * form just saved. Searching after an edit with the name from before it is how a renamed record
 * becomes undescribable.
 */
async function describeRecord(
  workshopId: string,
  field: DwField,
  filterValue: string,
  recordId: string,
  label: string
): Promise<DwReferenceOption | null> {
  try {
    const payload = await listStageReferences(workshopId, {
      model: field.refModel as string,
      scope: field.refScope,
      filterBy: field.refFilterBy ? filterValue || null : null,
      search: label.trim() || null,
      limit: REFERENCE_PAGE_MAX
    });
    return payload.options.find((option) => option.id === recordId) ?? null;
  } catch {
    return null;
  }
}

/**
 * WHAT THIS PICKER ALREADY KNOWS ABOUT A RECORD IT IS ABOUT TO OPEN A CREATE FORM FOR.
 *
 * ── THE DEFECT THIS ENDS ──────────────────────────────────────────────────────────────────────
 * `InlineRecordDialog` was rendered with nothing but `{ open, model, onClose, onCreated }` while
 * this component was holding the row's artisan and the workshop the whole time. The full-page
 * routes seed the same boxes from the query string; a dialog has none, so what actually filled them
 * was the carry bag — the last artisan this designer documented ANYWHERE. The product was then
 * filed under the wrong artisan or under nobody, the server's cascade excluded it from this very
 * list, `describeCreated` could not describe it, and the required Name and Price boxes stayed
 * blank until the stage 422'd. The one record that held both answers had just been created.
 *
 * ── THE CASCADE VALUE IS NOT ALWAYS AN ARTISAN ID ─────────────────────────────────────────────
 * Two fields in the registry cascade, and they hold different kinds of id. `existingProduct
 * .productRef` filters by `existingProduct.artisanRef`, which is an `Artisan` id. `prototype
 * .productRef` filters by `prototype.artisanRef`, which is a `DwParticipant` ROSTER ENTRY id — the
 * maker was chosen from stage 3's list of who was in the room. The server resolves the second back
 * to an artisan (`_artisan_id_behind`, one indexed primary-key read) and deliberately spares the
 * clients that rule; a browser cannot follow it, and filing a product under a roster-entry id would
 * be worse than filing it under nobody. So the filter field's OWN `refModel` decides: an artisan id
 * is seeded, a participant id is not, and the stage-13 create falls back to exactly the behaviour
 * it has today rather than to a fabricated parent.
 *
 * ── THE WORKSHOP IS SEEDED WHEREVER THERE IS ONE, NOT ONLY WHERE IT IS REQUIRED ───────────────
 * Five REF fields are WORKSHOP-scoped and the server narrows them on the linked Workshop, so for
 * those the seed is the difference between a record that appears in the list it was made from and
 * one that never does. It is seeded on the unscoped pickers too — the stage-3 roster above all,
 * which is the likeliest place of all to discover an artisan is missing — because the claim it
 * makes is simply true: this designer is standing at that workshop filling in its stages. The
 * alternative is not "no claim"; it is `useWorkshopSelection`'s probe picking THE MOST RECENT
 * WORKSHOP THIS ACCOUNT MAY SUBMIT TO, which has no connection to the room at all. Either way the
 * value lands in a visible dropdown the designer can change.
 *
 * NOTHING IS GUESSED WHEN THE DESIGN WORKSHOP HAS NO LINKED WORKSHOP. `useLinkedWorkshopId` answers
 * null there — the same state the references endpoint reports as `scopedToWorkshop: false` and
 * `ScopeNotice` prints out loud — and the key is simply omitted.
 */
/*
  EXPORTED SO IT CAN BE TESTED, the same reason `hasUnsavedWork` is exported from `ProcessForm`.
  This repository has no React renderer in its devDependencies, so a rule left inside a component
  body can only ever be READ by a test; the rule about which kind of id may be seeded is exactly the
  kind that must be EXECUTED, because getting it wrong files a product under a roster entry and
  nothing on any screen would say so. See `inline-record-host-unit.spec.ts`.
*/
export function inlineSeed({
  entity,
  field,
  row,
  filterValue,
  linkedWorkshopId
}: {
  /** Omitted by the roster picker, which has no row and no cascade. */
  entity?: DwEntity;
  field: DwField;
  row?: DwEntryData;
  filterValue?: string;
  linkedWorkshopId: string | null;
}): InlineHostSeed {
  const seed: InlineHostSeed = {};
  const filterField =
    entity && field.refFilterBy ? entity.fields.find((candidate) => candidate.key === field.refFilterBy) : undefined;
  if (entity && row && filterField && filterValue && filterField.refModel === "Artisan") {
    seed.artisanId = filterValue;
    // The name the row already shows for that artisan — hydrated onto it when they were picked, so
    // it is the server's own spelling rather than anything this file invented. It fills a REQUIRED
    // free-text box on the product and tool forms, which would otherwise open blank beside an
    // artisan the designer has already chosen.
    const name = referenceDisplayHint(entity, filterField, row);
    if (name) seed.artisanName = name;
  }
  if (linkedWorkshopId) seed.workshopId = linkedWorkshopId;
  return seed;
}

/**
 * What the picker says when an inline create went into the offline outbox instead of the database.
 *
 * ONE SENTENCE IN ONE PLACE because both pickers need it and the two must not drift. It says three
 * things, and all three are load-bearing: the record IS saved (or the designer creates it again and
 * banks a duplicate); the row is NOT linked (or they submit the stage believing it is); and what to
 * do when signal returns. It deliberately does not offer a retry — there is nothing to retry, the
 * outbox owns the entry now.
 */
const QUEUED_OFFLINE_NOTICE =
  "There is no connection, so this record has been saved on this device and will be sent when there is signal. " +
  "It has no repository id yet, so nothing could be linked here — reopen this list once it has been sent and " +
  "choose it then.";

/**
 * The two things the craft picker can say about a craft that is not in the register, by rank.
 *
 * TWO SENTENCES IN ONE PLACE for the same reason as the one above, and this pair has a second reader:
 * Android's `DwReferenceSelectField` offers NEITHER today — its empty craft list says "No records on
 * this device yet", which is a claim about this device's CACHE and an instruction to find a tower. For
 * a craft that has never been documented anywhere, connecting achieves nothing, so that sentence sends
 * a designer looking for signal instead of at the craft register. When the handset gains its half it
 * must carry these words, not a second phrasing of them.
 */
const CRAFT_REGISTER_LINK = "Add or correct a craft on the crafts page (opens in a new tab)";

const CRAFT_REGISTER_BLOCKED =
  "Adding a craft to the register needs craft-creation access — ask the master admin. This link is optional: type " +
  "the craft's name in the Craft box above and the stage still saves.";

/* ────────────────────────────────────────────────────────────────────────────
 * Scanning a card or tag straight into this box
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHICH `refModel` EACH KIND OF PRINTED CODE NAMES, and why the table is deliberately short.
 *
 * A scan hands over a {@link WorkshopCodeRef} — a record TYPE and an id — while a picker is declared
 * with a `refModel`. This is the whole of the translation between the two.
 *
 * `workshop`, `questionnaire` and `media` are ABSENT BECAUSE NO REF FIELD POINTS AT THEM. The
 * registry declares ten `ref_model=` values (`Artisan`, `Craft`, `Process`, `ProductDocumentation`,
 * `ToolDocumentation` and the five `Dw…` entities) and none of them is a Workshop, an interview or a
 * media file — so a code of one of those types can only ever be the wrong type for the box it was
 * scanned at, and absence is the correct answer rather than an omission. The day a registry field
 * does point at one, this table is what has to gain the row.
 *
 * THE FIVE `Dw…` ENTITIES ARE NOT ALL HERE EITHER, read the other way: `DwPrototype` is the only row
 * inside a design workshop that this repository prints a tag for — `WORKSHOP_RECORD_TYPES` in
 * `lib/workshopCodes.ts` is the whole list of code-bearing types, and `prototype` is the one of them
 * that names no repository record at all: `lookUpWorkshopCode` refuses a prototype reference outright
 * ("A prototype belongs to one design workshop and is looked up inside it"), which is why a tag for
 * one can only ever be answered inside the workshop that printed it — here. A sketch, a cost sheet, a
 * final product and a roster entry carry no code at all, so nothing can be scanned into their
 * pickers and {@link pickerTakesScans} does not offer a reader on one.
 */
export const SCANNED_TYPE_REF_MODEL: Partial<Record<WorkshopRecordType, string>> = {
  artisan: "Artisan",
  craft: "Craft",
  product: "ProductDocumentation",
  process: "Process",
  tool: "ToolDocumentation",
  prototype: "DwPrototype"
};

/**
 * The reverse table, INVERTED rather than written out a second time — the same rule and the same
 * reason as `TYPE_FROM_LETTER` in `lib/workshopCodes.ts`. Two hand-kept halves is how a picker comes
 * to accept a code that names a different kind of record, which is the one failure a scanned
 * identifier is supposed to have removed.
 */
const REF_MODEL_TYPE: Record<string, WorkshopRecordType> = Object.fromEntries(
  Object.entries(SCANNED_TYPE_REF_MODEL).map(([type, model]) => [model, type as WorkshopRecordType])
);

/** "a" or "an" for a noun about to be dropped into the middle of a refusal. */
function article(noun: string): string {
  return /^[aeiou]/i.test(noun) ? "an" : "a";
}

/**
 * Can a printed code ever name a record this box takes?
 *
 * The gate on offering a card reader at all. A `DwSketch` picker would otherwise carry a control
 * that refuses every code in the world, which reads as a broken scanner rather than as a box no card
 * belongs in.
 */
export function pickerTakesScans(field: DwField): boolean {
  return Boolean(field.refModel && REF_MODEL_TYPE[field.refModel]);
}

/**
 * WHAT THIS BOX HOLDS, in the word a refusal can put in a sentence.
 *
 * The record-type label wherever the model is one a code can name — the SAME word
 * `workshopRecordTypeLabel` gives the two existing scanners, so a designer who has read "Product" on
 * `/search` meets "product" here — and the field's own label otherwise, because a `DwSketch` has no
 * name in the code grammar at all and the box's label is the only true thing left to call it.
 */
function pickerNoun(field: DwField): string {
  const known = field.refModel ? REF_MODEL_TYPE[field.refModel] : undefined;
  return known ? workshopRecordTypeLabel(known).toLowerCase() : field.label.toLowerCase();
}

/**
 * REFUSAL (a): THE CODE NAMES THE WRONG KIND OF RECORD. Null when it names the right one.
 *
 * ASKED BEFORE THE NETWORK, because there is nothing to ask. An artisan's id looked up in the
 * product table is not a near miss, and the empty answer would come back as "no product matches that
 * code" — true, useless, and read by the person holding the card as a damaged tag. They would
 * photograph it again.
 *
 * THE SENTENCE NAMES BOTH TYPES, which is the whole of its value: the designer is holding one card
 * and looking at one box, and only the pair says which of the two is the mistake. A picker that said
 * "wrong kind of code" would leave them to work out which kind this one wanted.
 *
 * The no-model branch is not reachable from this file's own control — {@link pickerTakesScans} gates
 * it — and it is kept because this function is exported and a host that skips that gate deserves the
 * honest answer rather than a crash or a silence.
 */
export function scanTypeRefusal(field: DwField, ref: WorkshopCodeRef): string | null {
  const wanted = field.refModel ? REF_MODEL_TYPE[field.refModel] : undefined;
  if (wanted === ref.recordType) return null;
  const scanned = workshopRecordTypeLabel(ref.recordType).toLowerCase();
  if (!wanted) {
    return (
      `That code names ${article(scanned)} ${scanned}. “${field.label}” holds a row recorded in this design ` +
      `workshop, and rows of that kind carry no printed code — choose one from the list instead.`
    );
  }
  const noun = pickerNoun(field);
  return (
    `That code names ${article(scanned)} ${scanned}, and “${field.label}” takes ${article(noun)} ${noun}. ` +
    `Scan the ${noun}’s own card or tag, or search for it in the list.`
  );
}

/** What a by-id lookup came to: the row to choose, or the one sentence explaining why there is none. */
export type ScanLookup = { ok: true; option: DwReferenceOption } | { ok: false; message: string };

/**
 * REFUSAL (b): the record is real, and this WORKSHOP-scoped box still excludes it.
 *
 * THE NORMAL CASE FOR A SCANNED CARD, not an edge one: five REF fields are WORKSHOP-scoped against a
 * repository model, and the card in the designer's hand was printed by whoever documented the record
 * — at their cluster, under their workshop. The server answers that with `outOfScope` and hands the
 * row over under its own key precisely so a client can say so; see `DwReferencePayload`.
 *
 * IT IS SAID, AND THE SCOPE IS NOT WIDENED. Offering the row would point a stage at a record this
 * picker's own list can never show, and the report's table would then cite work from another cluster
 * with nothing on screen having admitted it. So the row is NAMED (that is what tells the designer
 * the right card was scanned) and left unchosen, and the sentence carries the remedy the empty-list
 * notice already carries — link the record to this workshop, and it appears here by itself.
 */
function outOfScopeRefusal(option: DwReferenceOption): string {
  return (
    `That code names “${option.label}”, which is documented under a different workshop — and this box offers only ` +
    `records linked to this design workshop’s workshop. Nothing on this row has been changed. Link that record to ` +
    `this workshop and scan again, or choose one of the records that already belong to it.`
  );
}

/**
 * REFUSAL (c): ONE SENTENCE FOR "NO SUCH RECORD" AND FOR "NOT YOURS", AND IT MUST STAY ONE.
 *
 * `lib/workshopCodeLookup.ts`'s header carries the argument in full and it applies here unchanged:
 * the API answers 404 rather than 403 for a record the caller may not read, and the references
 * endpoint's by-id path composes the same read predicate for the same reason, so an absent record
 * and an unreadable one arrive as the identical empty answer. Do not add a branch that tells them
 * apart — a scanner that did would let somebody enumerate the repository one photographed card at a
 * time.
 *
 * NOT `unresolvedWorkshopCodeMessage`, and the difference is only the remedy: that sentence sends
 * the reader to a screen ("open the workshop that made it", "search for the artisan by name"),
 * which is right for a scanner that opens records and wrong for a box that fills a row in. The RULE
 * is what is carried over, not the words.
 *
 * THE CASCADE IS NAMED WHERE THERE IS ONE, and that gives nothing away: the filter is sent on every
 * request this picker makes, so the possibility is true of every code scanned at a cascaded box and
 * the sentence is the same for all of them. It has to be said, because the server's out-of-scope
 * probe keeps the artisan clause — a product that belongs to somebody else's artisan lands here and
 * not in refusal (b) — and "it may not be in the repository" would be a lie told about a record the
 * designer can see two rows up.
 */
function unresolvedRefusal(field: DwField, cascadeLabel: string): string {
  const noun = pickerNoun(field);
  const reasons = [
    "it may not be in the repository",
    "it may belong to work this account cannot open",
    ...(cascadeLabel ? [`it may not belong to the ${cascadeLabel.toLowerCase()} chosen on this row`] : [])
  ];
  return (
    `No ${noun} this box can offer matches that code — ${reasons.join(", or ")}. Nothing on this row has been ` +
    `changed; search for it by name in the list instead.`
  );
}

/**
 * What the server's answer to a by-id lookup means, as a row to choose or a sentence to read.
 *
 * LIFTED OUT OF THE COMPONENT SO IT CAN BE EXECUTED BY A TEST, for the reason
 * {@link scopeNoticeLines} gives above: there is no React renderer in this repository's
 * devDependencies, so a rule left inside a component body can only ever be asserted as a SUBSTRING
 * of this file — which pins the spelling of a refusal and not the condition that decides which of
 * the three a designer is shown. `e2e/qr-surfaces-unit.spec.ts` calls this with real payloads.
 */
export function scanLookupOutcome({
  field,
  ref,
  payload,
  cascadeLabel
}: {
  field: DwField;
  ref: WorkshopCodeRef;
  payload: DwReferencePayload;
  /** The label of the field this one cascades from, or "" when it cascades from nothing. */
  cascadeLabel: string;
}): ScanLookup {
  // THE ID ITSELF IS THE PROOF, wherever it is present: an option carrying the scanned id IS the
  // record on the card, and it reached `options` only by passing this field's scope and cascade.
  const exact = payload.options.find((option) => option.id === ref.id);
  if (exact) return { ok: true, option: exact };
  /*
    A PROTOTYPE TAG LEGITIMATELY CARRIES AN ID THE OPTION DOES NOT, WHICH IS THE ONE CASE WITHOUT
    THAT PROOF. `workshopCodeIdForRow` prints the row's `_clientKey` while the row has never reached
    the server — a tag has to be printable the afternoon the prototype is made, and a workshop can go
    a fortnight without signal — and `_in_record_options` matches EITHER spelling while answering
    with the row's server id. So the one option a narrowed answer holds IS the row that was scanned.

    `truncated` IS WHAT SAYS THE ANSWER WAS NARROWED, and it is why the request asks for a page of
    one. An id clause matches at most one row, so a by-id answer can never honestly be truncated — an
    API deployed before the by-id half does not refuse an unknown query parameter, it IGNORES it and
    returns the ordinary list, and with `limit: 1` that shows up here as `truncated: true` the moment
    the workshop holds a second prototype. Taking a row out of THAT list would tag the stage with
    whatever sorts first: a wrong record chosen confidently, which is the failure a scanned
    identifier exists to end. (An old server plus a workshop holding exactly one prototype is the
    residual gap, and it closes itself the moment the API is the one that answers `recordId`.)
  */
  if (ref.recordType === "prototype" && !payload.truncated && payload.options.length === 1) {
    return { ok: true, option: payload.options[0] };
  }
  // Both halves required. The server derives the flag FROM the row, so they cannot disagree; a flag
  // with no row would be a client this build does not know how to render, and the sentence below is
  // the honest answer for that too.
  if (payload.outOfScope === true && payload.outOfScopeOption) {
    return { ok: false, message: outOfScopeRefusal(payload.outOfScopeOption) };
  }
  return { ok: false, message: unresolvedRefusal(field, cascadeLabel) };
}

/**
 * NO SIGNAL. A by-id resolve is the one thing on this control that cannot be answered locally.
 *
 * The stage page renders from IndexedDB before the network is asked, and this picker has always been
 * the exception — `useReferenceOptions` fetches its list every time it opens, offline or not — so a
 * scan is no more network-bound than typing into the same box. What matters is that the failure is
 * SAID and that nothing is written: the row is only ever patched by `choose`, which runs on a
 * resolved option and on nothing else, so a lookup that never landed leaves the row exactly as it
 * was rather than half-pointed at a record nobody confirmed.
 *
 * `isUnreachable` and not `isTransient`: a 500 means the server was reached and then failed, and
 * telling a designer their signal is at fault sends them out of the building while the real bug
 * wears an offline message. Same split, same reason, as `lib/workshopCodeLookup.ts`.
 */
const SCAN_OFFLINE_NOTICE =
  "There is no connection, so the repository could not be asked which record that code names. The code itself " +
  "checked out, so the card is fine and nothing on this row has been changed — scan it again when there is signal, " +
  "or search for the record by name in the list.";

/** The server answered, and not with an answer. Says what did NOT happen, which is the useful half. */
const SCAN_LOOKUP_FAILED_NOTICE =
  "That code could not be looked up just now. Nothing on this row has been changed — try the scan again, or search " +
  "for the record by name in the list.";

/**
 * ONE CARD READER OPEN AT A TIME, ACROSS EVERY PICKER ON THE STAGE — a correctness rule and not
 * tidiness.
 *
 * `WorkshopCodeScanner` binds a PASTE listener to the WINDOW, which is the WhatsApp-screenshot route
 * and the one that makes this bite: two readers mounted at once both decode one pasted picture, and
 * the record lands in TWO rows — a scan writing a row nobody aimed it at, which is precisely what
 * this lane must not add. Two of them also mean two live `getUserMedia` streams in a courtyard, and
 * a second copy of the manual-entry box, whose `id="workshop-code-manual"` is fixed, would steal the
 * first one's `<label>`.
 *
 * A WINDOW EVENT RATHER THAN A CONTEXT, because these pickers are siblings scattered through
 * `EntityForm`'s grid with no common owner short of the stage page itself — and a provider mounted
 * for this would be a provider every future host has to remember to mount.
 */
const SCANNER_OPENED = "dw-stage-scanner-opened";

/**
 * The one line under a picker, and the tone it is drawn in.
 *
 * THE TONE IS PART OF THE MESSAGE AND NOT A DECISION FOR THE CALL SITE, because the line now has an
 * author that reports something that went RIGHT — {@link scanCommitDecision} says the row has been
 * filled in from a card. Drawn in the amber the other two authors use, that confirmation reads as a
 * warning about a scan that worked, and a designer told in amber that their row is filled goes
 * looking for what went wrong. Both tones are SENTENCES; neither carries its meaning in the colour,
 * which is the rule the three scan refusals follow too.
 */
type PickerNotice = { tone: "warn" | "done"; text: string };

/**
 * WHAT THE ROW SAYS WHEN THE UNSAVED-CHANGES GUARD TOOK THE PRESS INSTEAD OF THE ROW.
 *
 * Three controls on this picker can be refused — a pick from the list, a card read by the scanner
 * and "Clear the link" — and all three used to leave nothing behind but a prompt raised somewhere
 * else on the stage, so the designer answered it and had no idea their pick had been dropped.
 *
 * ── IT DOES NOT PROMISE THAT "DISCARD" FINISHES THE PICK, BECAUSE TODAY IT DOES NOT ─────────────
 * The act is banked — `useLeaveInterceptor` hands it to the provider, which holds it against the
 * form that blocked — but no form calls `completeLeave()` yet: the four record forms' "Discard" runs
 * `resetDirty()` and then their host's `onDiscardAndLeave`, and `StageRecordEmbed`'s implementation
 * of that clears the form and says the page did not move. A sentence here promising that the record
 * is chosen "straight away" would therefore be a false claim sitting in the same panel as the
 * embed's true one, in amber next to green. So the instruction is the one that actually works:
 * answer the prompt, then press this again. WHEN `completeLeave()` LANDS IN THE FOUR FORMS THIS
 * SENTENCE CHANGES WITH IT — the retry clause is the whole of what becomes wrong.
 *
 * ── AND IT DOES NOT SAY WHOSE PROMPT IT IS, BECAUSE THIS CONTROL CANNOT KNOW ─────────────────────
 * `interceptLeave` answers a bare boolean, and `UnsavedChangesProvider` walks EVERY registered form
 * innermost-first. On stage TRADITIONAL_PROCESS_BASELINE that is a `ProcessForm` singleton mounted
 * from first paint plus a `ToolForm` for every open tool row, so the form that blocked can belong to
 * a different row entirely; and on an unlinked `mountOnRequest` row there may be no form under this
 * picker at all. "The form below this picker" would send the designer looking in the wrong place,
 * which is the same qualification `StageRecordEmbed.handleDiscardAndLeave`'s notice argues for.
 */
export function refusedByUnsavedWork(retry: string): PickerNotice {
  return {
    tone: "warn",
    text: `A form on this stage has unsaved work, so this row was left as it was. Answer the prompt on screen, then ${retry}.`
  };
}

/** What a by-id lookup stashed for the commit, and the cascade value it was resolved under. */
export type ScanHeld = { id: string; filter: string; option: DwReferenceOption };

/**
 * WHETHER A LOOKED-UP RECORD MAY STILL BE WRITTEN ONTO THE ROW — the whole of the commit's judgement,
 * lifted out of the component so a test can execute it.
 *
 * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────────
 * This check used to live inside `commitScan` and compare the cascade value the lookup was resolved
 * under against `filterValue` READ FROM THE SAME RENDER'S CLOSURE — so it compared a variable with
 * the variable it had been copied from, and the branch could not be taken. It was not merely
 * decorative. `WorkshopCodeScanner`'s camera loop re-arms itself (`readFrame` schedules the next
 * `readFrame`), so on the camera route — the route used in the room — the `resolve`/`onResolved`
 * pair is FROZEN at the render in which "Scan" was pressed, for the whole camera session. Change the
 * artisan on the row from one person to another while the camera is open, hold up the first
 * artisan's product card, and the frozen pair looked the record up under the OLD artisan, found it,
 * passed a guard that could never fire, and wrote it onto a row that now names somebody else: the
 * report attributing one artisan's work to another that this file's comments twice say must not
 * happen. `awaitingCascade` does not cover it — that fires when the parent is CLEARED, not changed.
 *
 * ── WHAT MAKES THE GUARD REAL ─────────────────────────────────────────────────────────────────
 * Both ends now read the cascade through {@link StageReferenceSelect}'s live ref rather than through
 * a closure: `resolveScan` stamps the value the REQUEST went out under, and the commit is handed the
 * value as it is AT COMMIT TIME. They differ only when the row genuinely moved during the round
 * trip, which is the case this refuses — and because the request reads the live value too, scanning
 * again after such a refusal asks the question the row is asking now rather than repeating the stale
 * one for ever.
 *
 * ── WHY IT ALSO OWNS THE SENTENCE THAT SAYS THE ROW WAS FILLED ────────────────────────────────
 * The lookup's own announcement is rendered by the scanner BEFORE this runs, so it can only honestly
 * report what was FOUND. Every path below that declines to write would otherwise leave "chosen for
 * this row" standing as the only thing said while nothing was written. The commit is the only thing
 * that knows, so the commit is what says it.
 *
 * A `null` notice means "say nothing further": the scanner has already announced the refusal in its
 * own status block, and a second copy of it under the field is the control arguing with itself.
 */
export function scanCommitDecision({
  held,
  ref,
  resolution,
  filterValue,
  field
}: {
  held: ScanHeld | null;
  ref: WorkshopCodeRef;
  resolution: ScanResolution;
  /** The cascade value AS IT IS NOW. Never the one the lookup closed over — that is the bug above. */
  filterValue: string;
  field: DwField;
}): { commit: DwReferenceOption | null; notice: string | null } {
  // A refusal, or an answer to a different code than the one this commit is for — a second scan
  // overtaking the first. Nothing was stashed to write, and the scanner has already said why.
  if (!resolution.ok || !held || held.id !== ref.id) return { commit: null, notice: null };
  if (held.filter !== filterValue) {
    return {
      commit: null,
      notice:
        "The record this list depends on changed while that code was being looked up, so nothing was chosen. " +
        "Nothing on this row has been changed — scan the card again to choose from the new list."
    };
  }
  return {
    commit: held.option,
    notice: `“${held.option.label}” is now chosen for “${field.label}” on this row.`
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * Single select
 * ──────────────────────────────────────────────────────────────────────────── */

export function StageReferenceSelect({
  workshopId,
  entity,
  field,
  row,
  value,
  onChange,
  onPatch,
  disabled,
  labelId,
  recordFormMountedOver = null
}: {
  workshopId: string;
  entity: DwEntity;
  field: DwField;
  /** The whole record this field sits on — the cascade and the hydration both read it. */
  row: DwEntryData;
  value: DwValue | undefined;
  onChange: (value: DwValue) => void;
  /** Write several keys of the same record at once. Hydration is a multi-key write by nature. */
  onPatch: (values: Record<string, DwValue>) => void;
  disabled?: boolean;
  labelId: string;
  /**
   * A REPOSITORY RECORD THAT ALREADY HAS AN EDIT SURFACE OPEN ON THIS PAGE, or null — which is every
   * picker whose host does not pass one, i.e. everything outside a mirror point's own embed.
   *
   * ── THE DEFECT THIS CLOSES ────────────────────────────────────────────────────────────────────
   * `StageRecordEmbed` mounts the record's own page inside the stage row, in EDIT mode over the
   * linked record, with `initial` read once at mount and never re-read. This control is drawn
   * directly above it and offered "Edit this {noun}" on the SAME record — every mirror point is
   * inline-creatable, so the pencil was always there. Correct the village in the dialog, save, and
   * the page below still holds the pre-edit snapshot; its next Save PATCHes the shared repository
   * record back to what it said before the correction. One value with two owners, on a record other
   * workshops read, and the OLDER one wins.
   *
   * ── WHY AN ID AND NOT A BOOLEAN ───────────────────────────────────────────────────────────────
   * A mirror point can host TWO pickers. Stage 6 draws the artisan cascade picker above the product
   * picker, inside the same embed, and the record page below is the PRODUCT's — so the artisan's
   * pencil is still the only way to fix that artisan without leaving the stage, and must stay. The
   * host passes the record its form is mounted over and each picker compares it against its own
   * choice, so the suppression lands on exactly the one control that has a second editor under it.
   *
   * The record page is not a lesser edit surface than the dialog: it is the same four forms, and the
   * embed's own explainer says in words that changing it changes the record. Nothing is lost by
   * taking the pencil away there, which is why this prevents rather than recovers.
   *
   * ── AND WHAT IT DOES NOT REACH ─────────────────────────────────────────────────────────
   * `MirroredEntityBody` is the only host that passes this, so the defect is closed on the pickers
   * of an embed and NOWHERE ELSE. A REF field elsewhere on the same stage can name the same record
   * and still draw its pencil over it: on TRADITIONAL_PROCESS_BASELINE the `processStep` rows point
   * at the same Process the stage-5 singleton has a form open over — see `StageRecordEmbed`'s
   * `NOT_EMBEDDED` entry for `processStep.processRef`. That needs the stage page, which is the only
   * place that knows both entities, and is not what this prop is.
   */
  recordFormMountedOver?: string | null;
}) {
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [highlight, setHighlight] = useState(0);
  /**
   * May this designer add a row to the craft register?
   *
   * Read for the CRAFT branch below and nothing else — it decides which of two true sentences the
   * picker offers, never whether the picker works. `canManageCrafts` is the same predicate
   * `/crafts` uses to decide whether to render its form at all, so the two cannot disagree about who
   * would arrive at a page they can act on.
   */
  const { user } = useAuth();
  const craftManager = canManageCrafts(user);
  /**
   * The one line under this control, and it has three authors.
   *
   * The cascade writes it when it clears a choice that no longer belongs to the record above.
   * {@link adoptCreated} writes it when a record was created and the server could not be got to
   * describe it. {@link commitScan} writes it when a scanned card has filled the row in, and when
   * the answer to one arrived too late to be written. Named `notice` rather than `cascadeNotice`
   * since the second author arrived: a message about an inline create is not a cascade message, and
   * two boxes stacked under one field is a form arguing with itself.
   *
   * The third author is the first that can report a success, which is why the state carries a tone —
   * see {@link PickerNotice}.
   */
  const [notice, setNotice] = useState<PickerNotice | null>(null);
  /**
   * A record made from inside this picker, held only until the server can describe it.
   *
   * Carries the label because nothing else on screen can name it: it is in no fetched list, and
   * the display field it would normally be read back from has not been hydrated yet. Android keeps
   * a `locallyCreated` stand-in for exactly this window and for exactly this reason.
   */
  const [pending, setPending] = useState<{ id: string; label: string } | null>(null);
  /**
   * Which record the inline dialog is working on, or null when it is closed.
   *
   * `{ mode: "create" }` makes a new one; `{ mode: "edit", id }` opens the linked record. One piece
   * of state rather than two booleans, because the two are mutually exclusive and two flags is how
   * a dialog ends up open in both modes at once.
   */
  const [inlineDialog, setInlineDialog] = useState<{ mode: "create" } | { mode: "edit"; id: string } | null>(null);
  /** Is the card reader open under this control? At most one on the stage — see {@link SCANNER_OPENED}. */
  const [scanOpen, setScanOpen] = useState(false);
  /**
   * What the last scan resolved to, held between `resolve` and `onResolved`.
   *
   * `WorkshopCodeScanner`'s `onResolved` is handed the reference and the RESOLUTION — a label and a
   * sentence, which is all a panel that only reports needs — and not the row behind them. This is
   * where the row waits, with the id it was resolved for and the cascade value it was resolved
   * under, so the commit below can check that it is still answering the question it was asked. A ref
   * and not state: nothing renders from it, and a re-render between the two calls would be a second
   * chance to write the row.
   */
  const scanHit = useRef<ScanHeld | null>(null);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const inputRef = useRef<HTMLInputElement | null>(null);

  const selectedId = inputValue(value);
  const filterValue = field.refFilterBy ? inputValue(row[field.refFilterBy]) : "";
  /**
   * A cascading picker with nothing to cascade FROM asks for nothing at all.
   *
   * The server treats an absent `filterBy` as "no filter" and serves the whole table, which is the
   * correct answer to the question it was asked and the wrong list to put on this control: the
   * descriptor says this field is the products OF the artisan on this row, so an unnarrowed list
   * offers another artisan's work to a designer who has every reason to believe it was narrowed.
   * Nothing is fetched and the panel says which box to answer first.
   */
  const awaitingCascade = Boolean(field.refFilterBy) && !filterValue;
  /**
   * The context an inline create is opened with — see {@link inlineSeed}.
   *
   * Recomputed rather than captured when the button is pressed, because the cascade above can clear
   * the artisan out from under it: a seed frozen at the moment the panel opened would hand the form
   * a parent the row no longer names.
   */
  const linkedWorkshopId = useLinkedWorkshopId();
  const seed = useMemo(
    () => inlineSeed({ entity, field, row, filterValue, linkedWorkshopId }),
    [entity, field, row, filterValue, linkedWorkshopId]
  );
  const { payload, loading, problem } = useReferenceOptions({
    workshopId,
    field,
    filterValue,
    query,
    active: open && !awaitingCascade
  });
  const options = payload?.options ?? [];

  /**
   * The name of the record already chosen.
   *
   * Preferred over anything the option list happens to hold, because the list is the results of
   * whatever is typed in the search box right now and the chosen record is very often not in it. See
   * {@link referenceDisplayHint}: the name is on the row already, put there by hydration.
   */
  const chosenLabel =
    options.find((option) => option.id === selectedId)?.label ||
    // A record created seconds ago is in no list and has hydrated no display field onto the row
    // yet, so its own name is the only thing on this screen that can name it. It is consulted
    // BEFORE the row's hint because during a re-point the row still holds the PREVIOUS record's
    // name — showing that beside the new record's id is the one outcome the hydration rules exist
    // to prevent, and it must not be reintroduced by a label.
    (pending?.id === selectedId ? pending.label : "") ||
    referenceDisplayHint(entity, field, row);

  /**
   * The row as it is RIGHT NOW, for a hydration that started before the current render.
   *
   * `adoptCreated` awaits a round trip and only then applies its patch, and the only-fill-blanks
   * rule reads the row to decide what it may write. The `row` captured when the create finished is
   * a snapshot; a designer types into the boxes beside the picker while the answer is in flight,
   * and a patch decided against the snapshot would overwrite what they had just typed. This is the
   * hook equivalent of the handset rebuilding its `openInlineRecord` closure on every composition,
   * which its own comment says is there to keep hydration bookkeeping from going stale.
   */
  const latestRow = useRef(row);
  useEffect(() => {
    latestRow.current = row;
  }, [row]);

  /**
   * Which deferred hydration is still wanted.
   *
   * A create arms an answer that lands a round trip later, and anything the designer does in
   * between — picking somebody else, clearing the link, creating a second record — makes that
   * answer wrong rather than merely late. Bumping the count is how the in-flight continuation
   * learns it has been superseded; Android drops its `pendingHydration` at the same three moments
   * and names the same failure, one artisan's village landing under another's name.
   */
  const hydration = useRef(0);
  const supersede = useCallback(() => {
    hydration.current += 1;
    setPending(null);
  }, []);

  /**
   * WHEN THE ARTISAN CHANGES, THE PRODUCT CHOSEN FOR THE PREVIOUS ARTISAN IS CLEARED — and said.
   *
   * Leaving it is the worse half of the trade and it is not close: the id would still point at
   * another artisan's product, the server's hydration would leave the copied product name standing
   * because the field is no longer blank, and the report's table would attribute one artisan's work
   * to another with nothing anywhere admitting it. Clearing loses one pick that has to be made
   * again, in a picker that is now showing the right list.
   *
   * Only a CHANGE fires it. The ref is seeded with the filter's value at mount, so re-opening a
   * saved row — where the artisan and the product were both stored and agree — clears nothing.
   */
  const lastFilter = useRef(filterValue);
  useEffect(() => {
    if (!field.refFilterBy) return;
    if (lastFilter.current === filterValue) return;
    const had = lastFilter.current;
    lastFilter.current = filterValue;
    if (!selectedId) return;
    /*
      NO GUARD HERE, AND FOR THE PRESSES IT IS NOT A GAP. This clear also re-keys the embedded record
      form below. When the parent field moved because somebody PRESSED the picker above — `choose`, or
      "Clear the link" — the asking has already happened, and it covered this field too: the guard's
      walk asks EVERY registered form innermost-first, not only the one under the picker that was
      pressed. Asking again here would be a second prompt for one act.

      THE PARENT VALUE CAN ALSO MOVE WITHOUT A PRESS, and those cases are genuinely unasked. The
      create continuation is the known one and is argued for where it happens — see `onCreated`
      below, which re-points this very field on purpose and explains why a prompt there could only
      end in an orphaned record. The others are not presses at all: a draft rehydrating from
      IndexedDB, or a server reconcile landing a confirmed reference. Closing those means asking at
      the control that STARTS them rather than here, where all that is left is a clear that has to
      happen either way — leaving the id would attribute one artisan's product to another, which is
      the trade the paragraph above settles.
    */
    onChange(null);
    setNotice({
      tone: "warn",
      text: had
        ? "The record this list depends on changed, so the previous choice was cleared — pick one from the new list."
        : "Choose the record above first; this list narrows to it."
    });
    // `selectedId` and `onChange` are deliberately outside the dependency list: this effect must run
    // when the FILTER moves and at no other time. Including the value would re-run it on the clear
    // it just performed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filterValue, field.refFilterBy]);

  // Dismissal. Bound on the document rather than the panel so a click anywhere lands, and on
  // `mousedown` so the panel is gone before the click's own target has a chance to move under it.
  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  /*
    THE OTHER PICKERS' CARD READERS ARE CLOSED WHEN THIS ONE OPENS — see {@link SCANNER_OPENED} for
    the pasted screenshot that lands in two rows, which is the reason this exists rather than an
    argument about clutter.

    The announcement goes out BEFORE this instance starts listening, so it never closes itself; every
    other open reader hears a detail that is not its own and stands down. Closing unmounts the
    scanner, which is what releases its camera, its window paste listener and its copy of the
    manual-entry box's id.
  */
  const scannerId = `${baseId}-scanner`;
  useEffect(() => {
    if (!scanOpen) return;
    // Clearing the parent row's answer takes the reader away with the list it was narrowed to (the
    // render below drops it), so the toggle is put back to "Scan…" rather than left reading "Close
    // the card reader" over nothing.
    if (awaitingCascade) {
      setScanOpen(false);
      return;
    }
    window.dispatchEvent(new CustomEvent(SCANNER_OPENED, { detail: scannerId }));
    const onAnother = (event: Event) => {
      if ((event as CustomEvent<string>).detail !== scannerId) setScanOpen(false);
    };
    window.addEventListener(SCANNER_OPENED, onAnother);
    return () => window.removeEventListener(SCANNER_OPENED, onAnother);
  }, [awaitingCascade, scanOpen, scannerId]);

  // Derived every render, never trusted from state: the stored index goes stale the instant the
  // filter changes, and Enter would then commit a row that is not on screen.
  const safeHighlight = highlight >= 0 && highlight < options.length ? highlight : 0;

  /**
   * Point the row at this record — the write itself, with nothing asked first.
   *
   * NOTHING OUTSIDE {@link choose} MAY CALL THIS. It is split out only so the guarded wrapper has a
   * body to bank; a second caller reaching past the wrapper is a second way to destroy a half-filled
   * record form without asking, which is the defect the wrapper exists for.
   */
  const commitChoice = useCallback(
    (option: DwReferenceOption) => {
      // A pick supersedes a create still waiting to be described. Left armed, the answer landing a
      // moment later would fill this row in from the record made before the designer changed their
      // mind — one product's price under another product's name, with the id naming the second.
      supersede();
      const patch = hydrateFromReference(entity, field, option, row, selectedId);
      // One write, not two. The id and the fields it filled belong to the same act, and applying
      // them separately would let a re-render between them see a row naming a record whose name it
      // has not copied yet — which is the state the report reads as "reference to a deleted record".
      onPatch({ ...patch, [field.key]: option.id });
      setOpen(false);
      setQuery("");
      setNotice(null);
    },
    [entity, field, row, selectedId, onPatch, supersede]
  );

  /**
   * ASK BEFORE RE-POINTING THE ROW, BECAUSE A RE-POINT DESTROYS THE FORM UNDERNEATH THIS PICKER.
   *
   * ── WHAT THIS CONTROL CAN THROW AWAY ────────────────────────────────────────────────────────────
   * On a mirror-point entity `StageRecordEmbed` mounts the record's own page directly below this
   * picker and keys it `${linkedId}:${formGeneration}` — deliberately, so a form can never be left
   * standing over a record that has moved on. The other edge of that key is this control: choosing a
   * different record, or scanning a card for one, changes `linkedId` and REMOUNTS the form. Its
   * name, its identity digits, its uncontrolled DOM and its attached files live in React state and
   * are read only at that form's own submit, so all of it goes, and for a file it is worse than
   * silent — `useEagerStaging` releases its owner and the object already in storage is deleted about
   * two seconds later.
   *
   * The back arrow, "previous stage" / "next stage" and a row's own collapse have all consulted the
   * unsaved-changes guard for a while. This one never did, and it is the control most likely to be
   * pressed by accident: it sits at the top of the panel, it is the first thing a designer touches on
   * a row, and its whole purpose is to change the very id the form below is keyed on.
   *
   * ── WHY IT IS THE SHARED GUARD AND NOT A LOCAL CONFIRM ──────────────────────────────────────────
   * The question is "is there unsaved work on this stage", and only the forms themselves can answer
   * it. The guard asks in each form's own words, with its own Save / Discard / Keep editing, and it
   * BANKS this pick against the answer that means "yes, throw it away" — though nothing calls
   * `completeLeave()` yet, so today that answer clears the form and the pick has to be made again.
   * That is what the refusal's own line says, rather than a promise the forms cannot keep yet: see
   * {@link refusedByUnsavedWork}.
   *
   * ── WHAT IS NOT ASKED ───────────────────────────────────────────────────────────────────────────
   * Re-picking the record ALREADY chosen: `linkedId` does not move, so nothing remounts and nothing
   * is lost. And a picker on a row with no dirty form under it blocks nothing at all — the walk in
   * `UnsavedChangesProvider` returns false when no registered form is dirty, so every ordinary REF
   * field behaves exactly as it did.
   *
   * Returns whether the row was actually pointed, which {@link commitScan} needs: a card reader that
   * announced "chosen for this row" over a deferred pick would be describing a write that has not
   * happened and may never.
   */
  const interceptLeave = useLeaveInterceptor();
  const choose = useCallback(
    (option: DwReferenceOption) => {
      if (option.id !== selectedId && interceptLeave(() => commitChoice(option))) {
        // AND THE ROW SAYS SO. The prompt that was raised belongs to a form, names a form and says
        // nothing about this picker, so without this line the pick is dropped in silence: the
        // designer answers "Discard", watches the form empty, and has no reason to think the record
        // they chose is not the one this row now names. See {@link refusedByUnsavedWork} for why the
        // sentence neither promises the pick will land by itself nor says whose prompt it is.
        setNotice(refusedByUnsavedWork("choose the record again"));
        return false;
      }
      commitChoice(option);
      return true;
    },
    [commitChoice, interceptLeave, selectedId]
  );

  /**
   * `choose` AND THE CASCADE VALUE AS THEY ARE RIGHT NOW, for a caller that was built some seconds
   * ago and cannot be rebuilt.
   *
   * ── WHY A REF AND NOT THE CLOSURE ─────────────────────────────────────────────────────────────
   * Everything else on this control is called from a handler React re-binds on every render, so the
   * closure IS the current one. The card reader is the exception and it is not a small one:
   * `WorkshopCodeScanner`'s camera loop re-arms itself frame by frame, so the `resolve` and
   * `onResolved` it calls are the pair captured at the render in which "Scan" was pressed — for the
   * whole session, however long the designer holds the camera open and whatever they type in the
   * meantime.
   *
   * `choose` closes over `row`, `selectedId` and `onPatch`, and on a collection row `onPatch` is
   * `(values) => patchRowMany(index, values)`, which rebuilds the WHOLE rows array from the `rows`
   * it captured at that render — `EntityForm` says so twice in its own comments, and the word it
   * uses is "silently discards". So a frozen `choose` does not merely write a stale row: it replaces
   * the collection with a snapshot taken before the scan, throwing away every edit made to every row
   * since the camera was opened, with nothing on screen admitting it. A stale `selectedId` breaks
   * the other half — `hydrateFromReference` would be told the row still points where it pointed
   * before, so a re-point would leave the previous record's values standing under the new record's
   * name, which is precisely what the clear-on-re-point rule exists to stop.
   *
   * Updated on every render that changes any of them, and read only when a scan resolves. The
   * cascade value rides along because it is the other thing the scan must judge itself against (see
   * {@link scanCommitDecision}); the descriptor rides along so that the two scan callbacks need no
   * dependencies at all, which is what makes them provably free of the closure this ref exists to
   * escape.
   */
  const liveScan = useRef({ choose, field, filterValue });
  useEffect(() => {
    liveScan.current = { choose, field, filterValue };
  }, [choose, field, filterValue]);

  /**
   * A record created from inside this picker: linked at once, hydrated when the server can say what
   * belongs on the row.
   *
   * TWO WRITES AND NOT ONE, WHICH IS THE OPPOSITE OF WHAT `choose` ARGUES ABOVE, and the difference
   * is that here there is nothing to write in the first act. The link is the half we are certain
   * of; the values are a question only the server can answer, and a picker that showed nothing at
   * all until a round trip came back reads as the create having failed — which is the moment a
   * designer creates the same record a second time. So the link lands immediately, the control says
   * what it is waiting for, and the boxes fill when the answer arrives.
   */
  /**
   * WHAT THE LINKED RECORD SAID BEFORE THE DESIGNER STARTED EDITING IT.
   *
   * ── THE DEFECT THIS EXISTS FOR ────────────────────────────────────────────────────────────────
   * The edit button beside this picker exists so that "a designer who spots that the artisan's
   * village is wrong while filling stage 13 should not have to abandon the stage to fix one field"
   * — {@link InlineRecordDialog}'s own words. It saved the repository record and did nothing else.
   * `onCreated` handed the saved row to {@link adoptCreated}, which computes
   * `previous === record.id`, so `hydrateFromReference` sees `replaced === false` and skips every
   * box that is already filled. The corrected village therefore reached the repository and never
   * reached the row — and because a submitted report reads the COPY and never re-resolves, the old
   * village went on printing in the .docx for ever. The designer watched themselves fix it.
   *
   * ── WHY A "BEFORE" SNAPSHOT AND NOT SIMPLY OVERWRITING ────────────────────────────────────────
   * Clearing and refilling the mapped boxes — what a re-POINT does — would be wrong here for the
   * reason that rule is right there and wrong here: on a re-point every value belongs to a record
   * this row no longer names, so keeping any of it fabricates a person. On an edit the record is
   * the SAME one, so a value the designer corrected by hand at the keyboard is still theirs, and a
   * reference record is not more authoritative than the person standing in front of the artisan.
   *
   * So a box is refreshed only where it still holds exactly what this picker last filled in, which
   * needs the record's PREVIOUS answer. The handset reaches the same rule from the other side —
   * `hydrationPatch` in `DwReferenceField.kt` keeps a `lastHydration` map and its middle rule is
   * "a key whose value equals what the PREVIOUS pick wrote is overwritten" — so this is the web
   * being brought into line with the client that got it right, not a new policy.
   *
   * Captured when the dialog OPENS rather than when it closes, because after the save the record no
   * longer holds the old answer anywhere. Null when the fetch failed or has not landed; see
   * {@link adoptEdited} for what is said out loud in that case rather than guessed at.
   */
  const beforeEdit = useRef<DwReferenceOption | null>(null);

  /**
   * Take up an edit made to the record this row already names.
   *
   * Deliberately NOT {@link adoptCreated}: that function's whole shape is about a record the row
   * does not name yet — it re-points the field, arms a pending label, and clears the previous
   * record's values when the link moves. None of that applies. The link is unchanged, the id is
   * unchanged, and the only question is which boxes may take up the new answer.
   */
  const adoptEdited = useCallback(
    async (recordId: string, label: string) => {
      const before = beforeEdit.current;
      const after = await describeRecord(workshopId, field, filterValue, recordId, label);
      beforeEdit.current = null;
      if (!after) {
        // Said rather than silently skipped. The record HAS changed — the designer just changed it
        // — so leaving the row alone without a word is the state that reads as "the edit did not
        // save", and the next move is to edit it again.
        setNotice({
          tone: "warn",
          text: "Your changes were saved to the record, but this list cannot describe it just now, so the boxes on this row still show what it said before. Re-open the list and pick it again to refresh them."
        });
        return;
      }
      const mapping = referenceHydrationFor(entity, field);
      const patch: Record<string, DwValue> = {};
      for (const [sourceKey, targetKey] of Object.entries(mapping)) {
        const target = entity.fields.find((candidate) => candidate.key === targetKey);
        if (!target || target.deprecated) continue;
        // A GALLERY IS SEEDED AND NEVER REPLACED, on every surface and in every one of these three
        // rules. It holds the photographs the designer took in the room and there is no second copy
        // of those anywhere; an edit to the record's own catalogue shot must not reach them.
        if (isMultiField(target)) continue;
        /*
          A GEO TARGET IS THE ONE PLACE AN OBJECT IS THE CORRECT SHAPE, and the scalar path below
          does not merely fail to write it — it ERASES it. `inputValue` returns "" for any object,
          so `current` is falsy and `mayWrite` is true on every pass; `stringifyRefValue` returns
          null for any object, so `next` is null; and `null === ""` is false, so the box was SET TO
          NULL. Three mappings carry a GEO source (`participant.subjectLocation`,
          `tool.recordSubjectLocation`, `existingProduct.recordSubjectLocation`), and the value is
          the SUBJECT PIN — the village's own coordinate, the only location invariant 5 lets cross,
          and the one thing on the row the desk's fix must never replace. So every save from the
          "Edit this {noun}" dialog silently emptied it, and the designer's next act on a blank map
          card is to drop their own pin, which is the desk.

          `hydrateFromReference` grew this arm first and `StageRecordEmbed`'s `adoptEdited` second;
          this is the same arm on the third surface, and the two that host the same record had to
          stop disagreeing about it. The ABSENT case writes nothing rather than null — "the record
          has no pin" must not delete a pin the designer dropped on the village themselves.
        */
        if (target.type === "GEO") {
          const nextPoint = geoValue(after.data?.[sourceKey] as DwValue);
          if (!nextPoint) continue;
          const heldPoint = geoValue(row[targetKey]);
          const wasPoint = before ? geoValue(before.data?.[sourceKey] as DwValue) : null;
          const sameAsHydrated =
            heldPoint !== null &&
            wasPoint !== null &&
            heldPoint.lat === wasPoint.lat &&
            heldPoint.lon === wasPoint.lon;
          if (heldPoint && !sameAsHydrated) continue;
          if (heldPoint && heldPoint.lat === nextPoint.lat && heldPoint.lon === nextPoint.lon) continue;
          patch[targetKey] = nextPoint;
          continue;
        }
        const current = inputValue(row[targetKey]).trim();
        const was = before ? stringifyRefValue(before.data?.[sourceKey]) : null;
        // Blank fills, as always. Otherwise only a box still holding exactly what this picker last
        // put there may move — a designer's correction outranks the record it came from.
        const mayWrite = !current || (was !== null && current === was);
        if (!mayWrite) continue;
        const next = stringifyRefValue(after.data?.[sourceKey]);
        // `null` and not `""`: "the record no longer says anything here" and "the record says it is
        // empty" are the same answer on the wire, and both mean the box goes back to unanswered.
        if (next === current) continue;
        patch[targetKey] = next === null ? null : next;
      }
      if (Object.keys(patch).length) onPatch(patch);
      if (!before) {
        // The snapshot never arrived, so only blanks could be filled and a corrected value the row
        // already held has been left standing. Better to say so than to overwrite a designer's own
        // words on a guess.
        setNotice({
          tone: "warn",
          text: "Your changes were saved. Boxes on this row that were already filled in have been left as they are — check them against the record if you changed something they show."
        });
      }
    },
    [entity, field, filterValue, onPatch, row, workshopId]
  );

  const adoptCreated = useCallback(
    async (record: CreatedRecord) => {
      const label = createdLabel(record);
      /*
       * The record this row named BEFORE this one, read here rather than at the end because by then
       * the field holds the new id. It is the whole difference between "fill the blanks" and "clear
       * what the last record wrote, then fill" — see the long block in `hydrateFromReference`.
       */
      const previous = selectedId;
      onChange(record.id);
      setPending({ id: record.id, label });
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;

      const described = await describeCreated(workshopId, field, filterValue, record);
      // Superseded while the answer was in flight. Whatever the designer did instead is newer than
      // this, and writing now would land a stale record's values on a row that has moved on.
      if (hydration.current !== generation) return;
      setPending(null);

      if (!described) {
        setNotice({
          tone: "warn",
          text: previous
            ? "The record was saved and linked, but this list cannot describe it just now — so the boxes the previous record had filled in have been CLEARED rather than left standing under the new record's name. Fill them in by hand, or reopen the list and search for it."
            : "The record was saved and linked, but this list cannot describe it just now, so the boxes it would have filled in are still blank. Fill them in by hand, or reopen the list and search for it — a required box left blank is refused when the stage is submitted."
        });
      }

      /*
       * ONE CALL FOR BOTH OUTCOMES, AND THE FAILING ONE IS NOT A NO-OP.
       *
       * A description that never arrived is handed on as an option with an EMPTY `data`, which the
       * same table then reads exactly as it should: with no previous link there is nothing to clear
       * and nothing to write, so the patch is empty; with a previous link the mapped boxes are
       * cleared and left blank. That second case is the one worth spelling out — the alternative is
       * the previous record's name, village and price sitting beside the new record's id, which
       * `hydrateFromReference`'s own comment calls the one outcome worse than either alternative,
       * and which nothing downstream could ever re-resolve. A blank required box is refused loudly
       * at submit; a filled box naming the wrong record is not refused at all.
       *
       * The id is rewritten alongside the patch for the same reason `choose` writes them together,
       * and it is safe to rewrite because a designer who unlinked or re-picked during the round
       * trip has already superseded this continuation above.
       *
       * AND IT IS THE ONE RE-POINT BY THIS COMPONENT THAT DOES *NOT* CONSULT THE UNSAVED-CHANGES
       * GUARD, deliberately. `choose` and "Clear the link" do, because a press that re-keys the
       * embedded record form below can be refused and banked, and pressing again is a thing a
       * designer can do. This is not a press: it is the tail of a create the designer started from
       * this very picker, the record already exists in the repository, and there is nothing to press
       * again. A prompt here could only end in "Keep editing", which would leave a record made FOR
       * this row not linked to it — a worse outcome than the one it would be preventing. The cascade
       * auto-clear names this paragraph as its own exception, and for the same reason.
       *
       * WHAT REMAINS TRUE is that this path still remounts the embed below and takes any typing in it
       * with it; closing that means asking at the press that OPENS the create dialog, which is a
       * decision about a control that destroys nothing at the moment it is pressed and is not made
       * here.
       */
      const option: DwReferenceOption = described ?? { id: record.id, label, sublabel: "", data: {} };
      onPatch({
        ...hydrateFromReference(entity, field, option, latestRow.current, previous),
        [field.key]: record.id
      });
    },
    [entity, field, filterValue, onChange, onPatch, selectedId, workshopId]
  );

  /** The LABEL of the field this one cascades from — never its key: "artisanRef" is not a question. */
  const cascadeLabel = field.refFilterBy
    ? (entity.fields.find((candidate) => candidate.key === field.refFilterBy)?.label ?? field.refFilterBy)
    : "";

  /**
   * WHAT A SCANNED CODE POINTS AT, asked of the same endpoint the list comes from.
   *
   * THE SCOPE, THE CASCADE AND THE FIELD'S OWN MODEL ARE ALL SENT, exactly as the list sends them,
   * and that is the point of resolving through this endpoint rather than through
   * `lib/workshopCodeLookup.ts`: that module answers "what record is this" for a scanner that OPENS
   * things, with no idea which box the reader is standing at. A row filled from it would be filled
   * from a record this picker's own list can never show — another cluster's artisan, or a product
   * belonging to somebody other than the artisan named two boxes up. The narrowing has to be the
   * same narrowing, or the scan is a hole in it.
   *
   * IT ONLY LOOKS UP, AND IT ONLY REPORTS WHAT IT FOUND. Nothing is written here; {@link commitScan}
   * does that, and only for an answer that resolved. So every refusal below — and every failure of
   * the request itself — leaves the row exactly as it was. The returned `detail` therefore says what
   * the code NAMES and never that it was chosen: the scanner renders this answer in its status block
   * BEFORE the commit runs, so a claim about the row made here would still be standing, unretracted,
   * on every path where the commit then declines to write.
   *
   * THE CASCADE IS READ LIVE, through {@link liveScan}, and not from this callback's closure. On the
   * camera route this function is frozen at the render "Scan" was pressed — see the ref's own note —
   * so a closure read would send the artisan the row named a minute ago and narrow the lookup to the
   * wrong person. It is read once here and STAMPED onto the stash, so the commit can tell "the row
   * has not moved" from "the row moved while this was in flight".
   */
  const resolveScan = useCallback(
    async (ref: WorkshopCodeRef): Promise<ScanResolution> => {
      scanHit.current = null;
      const wrongType = scanTypeRefusal(field, ref);
      if (wrongType) return { ok: false, message: wrongType };
      const askedUnder = liveScan.current.filterValue;
      if (field.refFilterBy && !askedUnder) {
        // Belt and braces, and now real braces: the reader is unmounted in this state, but the
        // camera loop can be mid-frame when the artisan is cleared, and this callback would not have
        // heard about it. An unanswered cascade sends no `filterBy` and the server would then
        // resolve the id against the whole table — one artisan's product, landed on another's row.
        return {
          ok: false,
          message: `Answer “${cascadeLabel}” on this row first — a scan into this box is narrowed to it exactly as the list is.`
        };
      }
      let payload: DwReferencePayload;
      try {
        payload = await listStageReferences(workshopId, {
          model: field.refModel as string,
          scope: field.refScope,
          filterBy: field.refFilterBy ? askedUnder || null : null,
          recordId: ref.id,
          // ONE ROW, because an id clause can match at most one — and because that turns a server
          // which has never heard of `recordId` into a visible `truncated: true` rather than a list
          // this function might read a record out of. See {@link scanLookupOutcome}.
          limit: 1
        });
      } catch (error) {
        return { ok: false, message: isUnreachable(error) ? SCAN_OFFLINE_NOTICE : SCAN_LOOKUP_FAILED_NOTICE };
      }
      const outcome = scanLookupOutcome({ field, ref, payload, cascadeLabel: field.refFilterBy ? cascadeLabel : "" });
      if (!outcome.ok) return { ok: false, message: outcome.message };
      scanHit.current = { id: ref.id, filter: askedUnder, option: outcome.option };
      return { ok: true, label: outcome.option.label, detail: outcome.option.sublabel || undefined };
    },
    [cascadeLabel, field, workshopId]
  );

  /**
   * A SCANNED RECORD IS CHOSEN THROUGH `choose`, THE WAY A CLICKED ONE IS.
   *
   * Not a shortcut and not an optimisation: `choose` supersedes a create still waiting to be
   * described, runs `hydrateFromReference` against the row (which is what CLEARS the previous
   * record's values on a re-point instead of leaving them under the new record's name), and writes
   * the id and the hydrated fields in ONE patch. A scan that assembled its own patch would be a
   * second set of answers to all three questions, and the day they drifted the difference would be a
   * report attributing one artisan's work to another with nothing on screen having said so.
   *
   * THROUGH THE LIVE `choose`, WHICH IS THE OTHER HALF OF THAT SENTENCE. Both it and the cascade
   * value are taken from {@link liveScan} at the moment of the commit, never from this callback's
   * closure: on the camera route the closure is frozen at the render "Scan" was pressed, and a
   * frozen `choose` writes through a frozen `onPatch` — which on a collection row rebuilds the whole
   * array from a stale snapshot and discards every edit made since. The judgement itself is
   * {@link scanCommitDecision}, lifted out so a test can drive it with a filter that moved between
   * the lookup and the commit; this callback is the wiring and nothing else.
   *
   * The order matters: `choose` clears the notice as its last act (a manual pick leaves no line
   * behind), so the sentence saying the row was filled is set AFTER it and not before.
   */
  const commitScan = useCallback((ref: WorkshopCodeRef, resolution: ScanResolution) => {
    const held = scanHit.current;
    scanHit.current = null;
    const { choose: chooseNow, field: fieldNow, filterValue: filterNow } = liveScan.current;
    const decision = scanCommitDecision({ held, ref, resolution, filterValue: filterNow, field: fieldNow });
    /*
      A SCAN IS A RE-POINT AND IS ASKED ABOUT LIKE ANY OTHER — see {@link StageReferenceSelect}'s
      `choose`. It goes through the same guarded call precisely so a card cannot do what a click is
      not allowed to do, which is the rule this whole callback was written to keep.

      AND THE LINE UNDERNEATH MUST NOT CLAIM THE WRITE HAPPENED. `decision.notice` on a commit reads
      as "chosen for this row"; while the prompt is on screen nothing has been chosen, and if the
      designer answers "Keep editing" nothing ever will be. The lookup's own `role="status"` block has
      already said what the code NAMES, so what is owed here is what the row DID.
    */
    if (decision.commit && !chooseNow(decision.commit)) {
      // RE-WORDED OVER THE LINE `choose` HAS JUST SET, deliberately: both run in this one handler so
      // the last write is what the designer reads, and the retry differs. A designer who came in
      // through the camera has to scan the card again, not find the record in a list they never
      // opened. Everything else about the sentence — no promise that "Discard" lands the pick, no
      // claim about which form is holding them — is {@link refusedByUnsavedWork}'s, for the reasons
      // written there.
      setNotice(refusedByUnsavedWork("scan the card again"));
      return;
    }
    if (decision.notice) setNotice({ tone: decision.commit ? "done" : "warn", text: decision.notice });
  }, []);

  const emptyLine = useMemo(() => {
    if (awaitingCascade) {
      return `Answer “${cascadeLabel}” on this row first — this list holds only the records belonging to it.`;
    }
    if (loading) return "Searching…";
    if (problem) return problem;
    if (payload?.filtered && !options.length) {
      return "That record has nothing documented under it yet. Anything typed in by hand here stays typed in — it is not a mistake.";
    }
    if (query.trim()) return `Nothing matches “${query.trim()}”.`;
    return "No records to choose from yet.";
  }, [awaitingCascade, cascadeLabel, loading, problem, payload, options.length, query]);

  return (
    <div className="grid gap-1" ref={wrapperRef}>
      <div className="relative">
        <button
          type="button"
          className="field-input flex w-full items-center justify-between gap-2 text-left"
          aria-haspopup="listbox"
          aria-expanded={open}
          aria-labelledby={labelId}
          disabled={disabled}
          onClick={() => setOpen((current) => !current)}
        >
          {/* `ink-500` rather than the `ink-300` placeholder rung: with nothing chosen this span is
              the control's only text, and ink-300 is 2.44:1 on the card — below AA. See the same
              note on `SearchableSelect`'s trigger. */}
          <span className={chosenLabel ? "min-w-0 flex-1 truncate text-ink-900" : "min-w-0 flex-1 truncate text-ink-500"}>
            {chosenLabel || (selectedId ? "A record is selected — open the list to see which" : "Search and select")}
          </span>
          <ChevronDown className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
        </button>

        {open ? (
          // z-10 is the ladder's rung for in-page chrome. Nothing on a stage page is fixed except the
          // island at z-50, which this must stay under.
          <div className="absolute left-0 right-0 top-full z-10 mt-1 overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
            <div className="flex items-center gap-2 border-b border-line-200 px-3 py-2">
              <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
              <input
                ref={inputRef}
                className="min-w-0 flex-1 bg-transparent text-sm text-ink-900 outline-none placeholder:text-ink-300"
                type="text"
                role="combobox"
                aria-expanded
                aria-controls={listboxId}
                aria-autocomplete="list"
                aria-activedescendant={options.length ? `${baseId}-opt-${safeHighlight}` : undefined}
                placeholder={`Search ${field.label.toLowerCase()}`}
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value);
                  setHighlight(0);
                }}
                onKeyDown={(event) => {
                  if (event.key === "ArrowDown") {
                    event.preventDefault();
                    setHighlight((current) => Math.min(current + 1, Math.max(0, options.length - 1)));
                  } else if (event.key === "ArrowUp") {
                    event.preventDefault();
                    setHighlight((current) => Math.max(current - 1, 0));
                  } else if (event.key === "Enter") {
                    event.preventDefault();
                    const option = options[safeHighlight];
                    if (option) choose(option);
                  } else if (event.key === "Escape") {
                    event.preventDefault();
                    setOpen(false);
                  }
                }}
              />
              {loading ? <Loader2 className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
            </div>

            <ul id={listboxId} role="listbox" aria-labelledby={labelId} className="max-h-72 overflow-y-auto">
              {options.length ? (
                options.map((option, index) => {
                  const chosen = option.id === selectedId;
                  return (
                    <li key={option.id} id={`${baseId}-opt-${index}`} role="option" aria-selected={chosen}>
                      <button
                        type="button"
                        className={`flex w-full items-start gap-2 px-3 py-2 text-left transition ${
                          /* purple-50 is near-white in BOTH themes — the brand ramp does not
                             invert — so the highlight needs its dark counterpart or it paints a
                             white bar across a dark menu. `purple-950` is the rung
                             `ui/SearchableSelect` and `ui/calendar` use for this exact job. */
                          index === safeHighlight
                            ? "bg-purple-50 dark:bg-purple-950"
                            : "hover:bg-surface-50"
                        }`}
                        onMouseEnter={() => setHighlight(index)}
                        onClick={() => choose(option)}
                      >
                        <span className="mt-0.5 h-4 w-4 shrink-0 text-purple-700">
                          {chosen ? <Check className="h-4 w-4" aria-hidden /> : null}
                        </span>
                        <span className="min-w-0">
                          <span className="block truncate text-sm text-ink-900">{option.label}</span>
                          {option.sublabel ? (
                            <span className="block truncate text-xs text-ink-500">{option.sublabel}</span>
                          ) : null}
                        </span>
                      </button>
                    </li>
                  );
                })
              ) : (
                <li className="px-3 py-3 text-sm text-ink-500">{emptyLine}</li>
              )}
            </ul>
            {/*
              CREATE THE MISSING RECORD FROM HERE, rather than leaving a half-filled stage to do it.

              Always offered, not only when the search finds nothing: a designer often knows the
              artisan is absent before they finish typing the name, and a control that appears only
              after an empty result is one they have to discover twice. It sits BELOW the list so it
              can never be the thing an Enter key commits.

              Only for repository records — see `isInlineCreatable`. A `DwSketch` or `DwPrototype`
              is a row of this same workshop and is added by its own stage, not from a picker.
            */}
            {isInlineCreatable(field.refModel) && !disabled ? (
              <button
                type="button"
                className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50"
                onClick={() => {
                  setOpen(false);
                  setInlineDialog({ mode: "create" });
                }}
              >
                <Plus className="h-4 w-4 shrink-0" aria-hidden />
                {query.trim()
                  ? `Create “${query.trim()}” as a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`
                  : `Create a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`}
              </button>
            ) : null}
            {/*
              THE CRAFT PICKER'S ANSWER TO THE SAME QUESTION, WHICH IS NOT A CREATE BUTTON.

              A craft is a shared taxonomy row rather than something a designer observed, and a
              per-workshop create is how near-duplicate crafts multiply — `INLINE_CREATABLE`'s
              docstring carries that argument in full. But stage 1 is the first control a designer
              ever touches in this app, and until this line it was the only picker in the product
              that offered nothing at all when the craft was missing or misspelt: the remedy existed
              on /crafts and nothing said so.

              A NEW TAB, deliberately, and it is the whole point of this lane rather than a
              stylistic choice: the stage stays open behind it, exactly as `InlineRecordDialog`
              keeps it open for the other four models. Navigating in place would be the same defect
              this file's Cancel button had.

              `craftRef` is OPTIONAL and stage 1's `craftName` is typeable, so this is a way
              forward and never a gate.
            */}
            {field.refModel === "Craft" && !disabled ? (
              craftManager ? (
                <a
                  href="/crafts"
                  target="_blank"
                  rel="noopener"
                  className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50"
                >
                  <Plus className="h-4 w-4 shrink-0" aria-hidden />
                  {CRAFT_REGISTER_LINK}
                </a>
              ) : (
                /*
                 * BELOW PROFESSOR THE LINK ABOVE IS A DEAD END, so it is not offered.
                 *
                 * `/crafts` renders its form only when `canManageCrafts(user)` — `hasRank(user,
                 * "PROFESSOR")` — and below that rank the page says "Browse the craft vocabulary
                 * below. Ask the master admin for craft creation access to add or edit crafts." The
                 * anchor was gated on `field.refModel === "Craft" && !disabled` and on nothing else,
                 * so most designers read "Add or correct a craft on the crafts page", left the
                 * picker, opened a new tab and landed on a read-only list. The one control in the
                 * product added specifically so the remedy would not have to be remembered was
                 * sending them somewhere they cannot act — at the first control they ever touch in
                 * this app.
                 *
                 * A SENTENCE THEY CAN ACT ON, not an absence: the way forward for a craft that is
                 * not in the register is the typed `craftName` box, and `craftRef` is optional, so
                 * saying so keeps stage 1 unblocked without sending anybody looking for signal or
                 * for a page they cannot use.
                 */
                <p className="border-t border-line-200 px-3 py-2.5 text-xs leading-5 text-ink-500">{CRAFT_REGISTER_BLOCKED}</p>
              )
            ) : null}
            <ScopeNotice field={field} payload={payload} />
          </div>
        ) : null}
      </div>

      {/*
        THE CARD ITSELF, AS A WAY OF ANSWERING THIS BOX.

        A REAL BUTTON WITH A REAL NAME, and the name carries the field's own label because a stage
        holds many of these and "Scan a card or tag" said eleven times is eleven controls a screen
        reader cannot tell apart. It is a plain toggle: tab reaches it, Enter opens the reader, and
        everything inside the reader — camera, upload, and the typed code for a cracked lens or a
        glare across a laminated card — is `WorkshopCodeScanner`'s own, keyboard route included. No
        second manual-entry box is built here; it already has one.

        THE LABEL ALONE IS NOT ENOUGH, AND THE CASE IT FAILS IN IS IN THE REGISTRY TODAY. Two
        entities on ONE stage can declare the same field label: stage 4 puts "Documented process" on
        both `traditionalProcess` (Process overview) and `processStep` (Process steps), and stage 6's
        prototype stage puts "Prototype" on both `prototypeStageLog` (Stage logs) and `materialUsage`
        (Material usage) — four scannable boxes, two pairs of identical names, all four reachable in
        one tab order. So the accessible name carries the ENTITY as well, and the visible text stays
        as it is: `aria-label` is a superset of what is drawn, which is what WCAG 2.5.3 asks of a
        control whose spoken name is longer than its printed one.

        ROWS DO NOT MULTIPLY IT, which is the reason the row is not in the name. A collection expands
        exactly ONE row at a time — `EntityForm` holds a single `openKey` and toggles it — so the
        thirty roster rows are thirty buttons only in the sense that a list is thirty links: one of
        them exists at a time. Two entities are the case that actually collides, and that is the case
        this names.

        OFFERED ONLY WHERE A CARD COULD ANSWER IT ({@link pickerTakesScans}) and only while the
        cascade above has been answered. A cascaded picker with no parent chosen sends no `filterBy`,
        and the server would then resolve the scanned id against the whole table — so the control
        stands down and the sentence beside it says which box to answer first, rather than the reader
        quietly widening a list the designer believes is narrowed.
      */}
      {!disabled && pickerTakesScans(field) ? (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
            aria-expanded={scanOpen}
            aria-controls={scanOpen && !awaitingCascade ? scannerId : undefined}
            aria-label={`${
              scanOpen ? `Close the card reader for “${field.label}”` : `Scan a card or tag into “${field.label}”`
            }, in ${entity.title}`}
            disabled={awaitingCascade}
            onClick={() => setScanOpen((current) => !current)}
          >
            <ScanLine className="h-3 w-3" aria-hidden />
            {scanOpen ? `Close the card reader for “${field.label}”` : `Scan a card or tag into “${field.label}”`}
          </button>
          {awaitingCascade ? (
            <span className="text-xs text-ink-500">
              Answer “{cascadeLabel}” on this row first — a scan into this box is narrowed to it too.
            </span>
          ) : null}
        </div>
      ) : null}

      {scanOpen && !disabled && !awaitingCascade && pickerTakesScans(field) ? (
        <div id={scannerId}>
          {/*
            `resolve` LOOKS UP AND `onResolved` COMMITS, which is the split that keeps a refusal from
            writing anything: the second is handed the resolution and writes nothing on every `ok:
            false`. The scanner announces the LOOKUP in its own `role="status"` block, as a SENTENCE —
            no colour and no icon carries any of the three refusals.

            IT CANNOT ANNOUNCE THE COMMIT, and that is why {@link commitScan} says that half itself.
            The status block is written BEFORE `onResolved` is called, so anything the lookup claimed
            about the row would still be standing on every path where the commit then declines to
            write — "chosen for this row" as the only thing said while nothing was chosen. The lookup
            reports what the code NAMES; the line under this control reports what the row DID.
          */}
          <WorkshopCodeScanner
            resolve={resolveScan}
            onResolved={commitScan}
            description={`A card or tag printed by this app for ${article(pickerNoun(field))} ${pickerNoun(field)}. The record it names is chosen for “${field.label}” on this row and fills the row in, exactly as picking it from the list would.`}
          />
        </div>
      ) : null}

      {selectedId ? (
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="inline-flex items-center gap-1 text-xs font-medium text-ink-500 underline"
            disabled={disabled}
            onClick={() => {
              // Only the reference is cleared. The name, village and phone it filled in STAY: they
              // are what the designer confirmed in the room, and a report that loses a participant's
              // name because somebody unlinked a duplicate artisan record is the failure the copy
              // exists to prevent.
              //
              // An unlink also supersedes a create still waiting to be described: left armed, the
              // answer landing a second later would re-link the record the designer has just
              // deliberately unlinked and fill the row in from it. The handset drops its
              // `pendingHydration` on the same gesture and says the same thing.
              const unlink = () => {
                supersede();
                onChange(null);
                setNotice(null);
              };
              // AND IT IS ASKED ABOUT FOR THE SAME REASON A RE-POINT IS — see `choose`. Clearing the
              // link moves `linkedId` from a record to nothing, which remounts the embedded record
              // page below in CREATE mode and takes everything typed into it with the old mount.
              // Banked as well as refused, so that the answer meaning "leave" can perform the unlink
              // once the forms give that answer; until they do, the line below is what the designer
              // needs — the link is still there and the control has to be pressed again.
              if (interceptLeave(unlink)) {
                setNotice(refusedByUnsavedWork("press “Clear the link” again"));
                return;
              }
              unlink();
            }}
          >
            <X className="h-3 w-3" aria-hidden />
            Clear the link
          </button>
          {/*
            FIX THE RECORD FROM HERE TOO. Spotting that the artisan's village is wrong while filling
            stage 13 is the common case, and the only remedy was to leave the stage. The dialog
            re-reads the record rather than seeding from this picker's option, which carries only a
            label and a sublabel — a form seeded from that would blank every field it does not hold.

            NOT WHEN THE RECORD ALREADY HAS A FORM OPEN OVER IT — see {@link recordFormMountedOver},
            which is the whole argument. Two editors on one repository record, and the one that was
            opened first posts its pre-edit snapshot over the correction made in the other.
          */}
          {isInlineCreatable(field.refModel) && !disabled && recordFormMountedOver !== selectedId ? (
            <button
              type="button"
              className="inline-flex items-center gap-1 rounded-md border border-line-200 px-2 py-1 text-xs font-medium text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
              onClick={() => {
                /*
                  THE SNAPSHOT IS TAKEN HERE, BEFORE THE FORM CAN CHANGE ANYTHING, and it is the
                  whole of what makes {@link adoptEdited} able to tell a value this picker filled in
                  from one the designer corrected by hand. A moment later the record no longer holds
                  its old answer anywhere.

                  Fired and not awaited: the designer is opening a form they will spend a while in,
                  and blocking the dialog on a search would make the button feel broken. The armed
                  request lands long before there is anything to save. If it never lands,
                  `adoptEdited` says so out loud rather than guessing.
                */
                beforeEdit.current = null;
                void describeRecord(workshopId, field, filterValue, selectedId, chosenLabel).then(
                  (option) => {
                    beforeEdit.current = option;
                  }
                );
                setInlineDialog({ mode: "edit", id: selectedId });
              }}
            >
              <Pencil className="h-3 w-3" aria-hidden />
              Edit this {INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}
            </button>
          ) : null}
          <span className="text-xs text-ink-500">
            Clearing the link keeps the details it filled in — they are part of this record now.
          </span>
        </div>
      ) : null}

      {/*
        Said, not spun. The link is already made and visible above; this line exists so the blank
        boxes beside it read as "not yet" rather than as "this picker filled nothing in", which is
        the reading that has a designer retyping what is about to arrive.
      */}
      {pending ? (
        <p className="text-xs leading-5 text-ink-500">
          Filling in what the repository holds about “{pending.label || "the new record"}”…
        </p>
      ) : null}

      {/* Two tones, one line, and the words carry the meaning in both — see {@link PickerNotice}. The
          confirmation is drawn in the same plain voice as the "filling in…" line above rather than in
          the amber the warnings use, because a designer told in amber that their row is filled in
          goes looking for the problem. */}
      {notice ? (
        notice.tone === "done" ? (
          <p className="text-xs leading-5 text-ink-500">{notice.text}</p>
        ) : (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-2 py-1 text-xs leading-5 text-amber-800">
            {notice.text}
          </p>
        )
      ) : null}

      {inlineDialog && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          recordId={inlineDialog.mode === "edit" ? inlineDialog.id : undefined}
          onClose={() => setInlineDialog(null)}
          /*
            THE ROW'S OWN ARTISAN AND THIS WORKSHOP, HANDED TO THE FORM — see {@link inlineSeed} for
            the record filed under the wrong artisan that this closes, and for why a roster-entry id
            is deliberately not passed as one.

            Only on a CREATE. An edit opens the record that already exists, and seeding a parent
            over it would rewrite a link the designer never touched. This ternary is now BELT AND
            BRACES rather than the guard: it used to be the only place in the tree that enforced the
            rule, while the dialog handed `seed` to all three forms unconditionally. The enforcement
            lives in `InlineRecordDialog`'s `seedForForm`, where the rule is documented.
          */
          seed={inlineDialog.mode === "create" ? seed : undefined}
          /*
            LINKED FROM THE CREATE, DESCRIBED FROM THE SERVER — see `adoptCreated` and
            `describeCreated` for the defect that split those two apart. What used to be here was a
            `DwReferenceOption` assembled out of the raw repository row, whose column names are not
            the hydration table's keys; it hydrated nothing and left required boxes blank.

            Nothing has to invalidate the list: `useReferenceOptions` re-fetches whenever the picker
            becomes active again, so the next open shows the record in its proper place with the
            server's own label and sublabel.
          */
          /*
            TWO ADOPTERS, ONE CALLBACK, AND THE MODE DECIDES — because a create and an edit are not
            the same act on this row. A create RE-POINTS the field, so `adoptCreated` clears what the
            previous record wrote and fills from the new one. An edit leaves the link exactly where
            it is, so `adoptEdited` refreshes only the boxes still holding what this picker last put
            there. Running the create path over an edit is what left a corrected village in the
            repository and the old one in the report; running the edit path over a create would
            leave the previous record's values standing under the new record's id.
          */
          onCreated={(record) => {
            if (inlineDialog.mode === "edit") {
              void adoptEdited(inlineDialog.id, createdLabel(record) || chosenLabel);
              return;
            }
            void adoptCreated(record);
          }}
          /*
            SAID, NOT SWALLOWED. Offline the save is banked in the outbox and there is no server id,
            so `onCreated` never fires: before this the dialog stayed open, the button flipped back
            from "Saving…" to "Save artisan", and nothing else on screen changed — the page host's
            answer, `OutboxBanner`, sits outside this dialog's portal on a body whose scroll the
            dialog has locked. A designer read that as a failed save and pressed the button again,
            banking three copies of one artisan.

            NOTHING IS LINKED, DELIBERATELY. A REF must hold a real server id — `hydrate_entries`,
            `canonical_divergence` and the report's `ReferencedRecord` join all resolve on it — so a
            client-invented placeholder would print for ever as a reference to a deleted record.
            The link is left unmade and the sentence says so.
          */
          onQueued={() => {
            supersede();
            setNotice({ tone: "warn", text: QUEUED_OFFLINE_NOTICE });
          }}
          /*
            THE DUPLICATE PROMPT'S "OPEN THE EXISTING RECORD", ANSWERED HERE INSTEAD OF BY LEAVING.

            Inside this dialog a duplicate is the ordinary outcome: the designer pressed "Create a
            new artisan" because the search did not show the person in front of them, and the
            deduplication key matched anyway. `ArtisanForm` used to `router.push` to that record's
            edit page, taking the stage with it — so acting on the very thing the prompt exists to
            surface cost them their place.

            It goes through `adoptCreated` like any other new link, which means the row is hydrated
            from `describeCreated`'s SERVER payload and from nothing else. The conflict object also
            carries `maskedValue`, and a masked Aadhaar or Pehchan string must never be written onto
            a stage entry; only the id and the name cross, and the name only as a search term.
          */
          onUseExisting={(artisan) => {
            void adoptCreated({ id: artisan.id, name: artisan.name });
          }}
        />
      ) : null}
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * Multi select — the roster
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Choose many records at once, for the collections whose rows ARE the chosen records.
 *
 * The roster is the case this was asked for: a workshop has thirty participants, each one a row of
 * the same collection whose only required field is an artisan reference, and adding them one at a
 * time is thirty presses of "Add participant" followed by thirty searches. Here the list is opened
 * once, ticked, and confirmed — and each tick becomes a row, hydrated with the artisan's name,
 * village and specialisation exactly as a single pick would be.
 *
 * WHY IT DOES NOT ALSO REMOVE. Un-ticking a name would have to delete a row, and a row is not just
 * its reference: by the second day it carries days attended, a photograph, and notes from an
 * interview. So this control only ADDS — already-chosen records are shown ticked and disabled, and
 * removal stays where a row is removed, next to the row, where what is about to be lost is visible.
 *
 * WHY IT DOES NOT ALSO EDIT, which is the same argument one step further and is worth stating
 * because the single picker DOES have a pencil beside it and the asymmetry reads like an omission.
 * This panel is a BUILD-A-SELECTION control: thirty rows are ticked in one pass and confirmed once,
 * and every option in it is a name and a village on one line. Correcting a record from here would
 * mean opening the full artisan form over a half-ticked list to change a value the panel is not
 * showing — the designer would be editing something they cannot see, and then returning to a
 * selection they have to remember the state of. The single picker's pencil is gated on
 * `selectedId`, and a multi-select has no analogue of "the one that is chosen".
 *
 * The edit path is one row-expansion away and it is the better one: `bulkField` resolves to
 * `participant.artisanRef` and to nothing else on the current registry, and every roster ROW renders
 * its own `StageReferenceSelect` through `FieldInput`'s REF branch — pencil included — beside the
 * days attended and the notes that say which participant is being corrected. Android's roster
 * picker is the same shape, and the two must stay that way.
 */
export function StageReferenceMultiPicker({
  workshopId,
  field,
  /** Ids already used by a row of the collection — ticked, disabled, and counted. */
  alreadyChosen,
  onAdd,
  disabled,
  triggerLabel
}: {
  workshopId: string;
  field: DwField;
  alreadyChosen: string[];
  onAdd: (options: DwReferenceOption[]) => void;
  disabled?: boolean;
  triggerLabel: string;
}) {
  const baseId = useId();
  const listboxId = `${baseId}-listbox`;
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [picked, setPicked] = useState<DwReferenceOption[]>([]);
  /**
   * THE KEYBOARD THIS PANEL DID NOT HAVE.
   *
   * Its single-select sibling {@link StageReferenceSelect} has had all of this from the start — a
   * highlight, arrow keys, Enter to take the highlighted row, `role="combobox"` on the box with
   * `aria-controls` and `aria-activedescendant` pointing at the row. This one had a search input
   * carrying `autoFocus`, `aria-label`, `value` and `onChange` and nothing else, and a
   * `<ul role="listbox" aria-multiselectable>` with no combobox owning it.
   *
   * It was not unusable — every row is a real `<button>`, so Tab reaches them and Enter or Space
   * activates one — but Tab is the WRONG instrument here and that is the point: this is the ROSTER,
   * the control a designer opens to tick thirty participants, so walking to the thirtieth row means
   * thirty tab stops, and the reader is told nothing about where they are in the list because no
   * combobox is announcing a highlight. A screen-reader user got a listbox with no owner, which is
   * a set of options belonging to nothing.
   *
   * So: arrows move a highlight, Enter ticks the highlighted row and STAYS OPEN (ticking one is
   * rarely the end of a roster), and the box says what it is. Exactly the shared primitive's
   * contract in `ui/SearchableSelect`, and deliberately not that primitive — this panel searches the
   * SERVER on every keystroke and offers an inline create at its foot, neither of which
   * `SearchableMultiSelect` can express.
   */
  const [highlight, setHighlight] = useState(0);
  /** Open while the designer is creating a record that is missing from the roster. */
  const [creating, setCreating] = useState(false);
  /** The label of a record just created, while the server is being asked to describe it. */
  const [describing, setDescribing] = useState<string | null>(null);
  /** Said inside the panel when that description could not be got, or when a save went to the outbox. */
  const [createProblem, setCreateProblem] = useState<string | null>(null);
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  /**
   * This workshop, handed to an inline create — see {@link inlineSeed}.
   *
   * No `entity`, `row` or `filterValue`: `EntityForm` only offers this control for a REF field with
   * no `refFilterBy` at all, so the roster's list is never a cascaded one and there is no parent
   * artisan to carry. The workshop is the whole of what this picker knows, and it is the half that
   * matters here — the roster IS the record of who was in that room.
   */
  const linkedWorkshopId = useLinkedWorkshopId();
  const seed = useMemo(() => inlineSeed({ field, linkedWorkshopId }), [field, linkedWorkshopId]);

  const { payload, loading, problem } = useReferenceOptions({
    workshopId,
    field,
    filterValue: "",
    query,
    active: open
  });
  const options = payload?.options ?? [];
  const existing = useMemo(() => new Set(alreadyChosen), [alreadyChosen]);
  const pickedIds = useMemo(() => new Set(picked.map((option) => option.id)), [picked]);

  useEffect(() => {
    if (!open) return;
    function onPointer(event: MouseEvent) {
      /*
        A CLICK INSIDE THE RECORD DIALOG IS NOT A CLICK OUTSIDE THIS PANEL, however the DOM reads.

        `FieldDialog` renders through a PORTAL, so the artisan form the "Create a new artisan"
        button opens is not a descendant of `wrapperRef` — and this handler, which cannot see the
        difference between the dialog and the page behind it, closed the roster on the designer's
        very first keystroke in that form. Reopening it resets `picked`, so the half-ticked list the
        button exists to protect was lost anyway and the record they had just made was never added.
        The single picker does not have this problem only because it closes itself deliberately
        before opening the dialog.

        Matched on the overlay's own data attribute rather than on a ref, because the dialog is
        mounted by the picker's own subtree and owning a ref into somebody else's portal is a
        tighter coupling than reading the marker it already publishes.
      */
      const target = event.target as Element | null;
      if (target?.closest?.("[data-field-dialog-overlay]")) return;
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === "Escape") {
        event.stopPropagation();
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  /**
   * The highlight, DERIVED — never the stored number straight.
   *
   * The stored index goes stale the instant the server answers a new query: `options` is replaced
   * wholesale on every keystroke, so a number that pointed at row 20 of the previous answer would
   * commit a row nobody looked at. Same rule, same reason, as `SearchableSelect`'s `safeHighlight`.
   */
  const safeHighlight = highlight >= 0 && highlight < options.length ? highlight : 0;

  /** Tick or untick one row, from the pointer or from Enter. Rows already on the list are inert. */
  const toggleOption = useCallback(
    (option: DwReferenceOption) => {
      if (existing.has(option.id)) return;
      setPicked((current) =>
        current.some((item) => item.id === option.id)
          ? current.filter((item) => item.id !== option.id)
          : [...current, option]
      );
    },
    [existing]
  );

  /**
   * A record created from inside the roster: described by the server, THEN ticked.
   *
   * The same defect and the same fix as `adoptCreated` on the single picker — see
   * {@link describeCreated} for why the raw row the create returns cannot be handed to hydration.
   * The difference is what happens when the description cannot be got. This control's ticks become
   * ROWS, through `hydrateFromReference` in `EntityForm.addFromReferences`, so a tick made from an
   * empty payload is a roster row carrying a link and no name — indistinguishable in the
   * participant table from a designer who added a blank row by accident. Nothing is ticked in that
   * case; the panel says the record was saved and where to find it, and the search above will now
   * return it because the server does hold it.
   */
  const tickCreated = useCallback(
    async (record: CreatedRecord) => {
      setCreateProblem(null);
      setDescribing(createdLabel(record) || "the new record");
      // No filter value to carry: `EntityForm` only offers this control for a REF field with no
      // `refFilterBy` at all, so the roster's list is never a cascaded one.
      const option = await describeCreated(workshopId, field, "", record);
      setDescribing(null);
      if (!option) {
        setCreateProblem(
          "The record was saved, but this list cannot describe it just now, so it has not been ticked. Search for its name above and tick it there."
        );
        return;
      }
      setPicked((current) => (current.some((entry) => entry.id === option.id) ? current : [...current, option]));
    },
    [field, workshopId]
  );

  return (
    <div className="relative" ref={wrapperRef}>
      <button
        type="button"
        className="field-button-secondary"
        aria-haspopup="listbox"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => {
          setOpen((current) => !current);
          setPicked([]);
          // The keyboard starts at the top of whatever list this open produces, not wherever the
          // last one was left.
          setHighlight(0);
          // Cleared with the selection it belongs to. Left standing, "the record was saved but has
          // not been ticked" would greet the next designer to open this roster, about a record they
          // did not create and can now see in the list.
          setCreateProblem(null);
        }}
      >
        {triggerLabel}
      </button>

      {open ? (
        <div className="absolute right-0 z-10 mt-1 w-[min(28rem,90vw)] overflow-hidden rounded-md border border-line-200 bg-card shadow-panel">
          <div className="flex items-center gap-2 border-b border-line-200 px-3 py-2">
            <Search className="h-4 w-4 shrink-0 text-ink-500" aria-hidden />
            <input
              className="min-w-0 flex-1 bg-transparent text-sm text-ink-900 outline-none placeholder:text-ink-300"
              type="text"
              autoFocus
              role="combobox"
              aria-expanded
              aria-controls={listboxId}
              aria-autocomplete="list"
              aria-activedescendant={options.length ? `${baseId}-opt-${safeHighlight}` : undefined}
              aria-label={`Search ${field.label.toLowerCase()}`}
              placeholder={`Search ${field.label.toLowerCase()}`}
              value={query}
              onChange={(event) => {
                setQuery(event.target.value);
                // A new query is a new list, so the highlight goes back to its top row. Left where
                // it was, the number would point into the answer that has just been replaced.
                setHighlight(0);
              }}
              onKeyDown={(event) => {
                if (event.key === "ArrowDown") {
                  event.preventDefault();
                  setHighlight((current) => Math.min(current + 1, Math.max(0, options.length - 1)));
                } else if (event.key === "ArrowUp") {
                  event.preventDefault();
                  setHighlight((current) => Math.max(current - 1, 0));
                } else if (event.key === "Enter") {
                  /*
                    TICKS AND STAYS OPEN, and the `preventDefault` is not optional. A stage form is
                    rendered inside the record page's `<form onKeyDown={handleFormEnter}>`, so an
                    Enter allowed to escape this box reaches `focusNextField` and throws the keyboard
                    at another field mid-roster — which on a half-ticked list of thirty participants
                    is the moment the panel closes and the selection is gone. The shared primitive
                    cuts the same class of problem off with `containEvents`; this panel is not
                    portalled, so it stops its own key here.
                  */
                  event.preventDefault();
                  const option = options[safeHighlight];
                  if (option) toggleOption(option);
                } else if (event.key === "Escape") {
                  event.preventDefault();
                  setOpen(false);
                }
              }}
            />
            {loading ? <Loader2 className="h-4 w-4 shrink-0 animate-spin text-ink-500" aria-hidden /> : null}
          </div>

          <ul id={listboxId} role="listbox" aria-multiselectable className="max-h-72 overflow-y-auto">
            {options.length ? (
              options.map((option, index) => {
                const used = existing.has(option.id);
                const ticked = pickedIds.has(option.id);
                return (
                  <li key={option.id} id={`${baseId}-opt-${index}`} role="option" aria-selected={ticked || used}>
                    <button
                      type="button"
                      /*
                        THREE STATES, AND EVERY PURPLE WASH CARRIES ITS DARK COUNTERPART. The brand
                        ramp does not invert — purple-50 is near-white in both themes — so a bare
                        `bg-purple-50` painted a white bar across a dark menu. `purple-950` is what
                        `ui/SearchableSelect` and `ui/calendar` use for the same job, so a row under
                        the cursor here looks like a row under the cursor anywhere else in the app.
                        The keyboard highlight and a tick are told apart by the ring, because on a
                        multi-select they are genuinely different facts: one is where the keyboard is,
                        the other is what has been chosen.
                      */
                      className={`flex w-full items-start gap-2 px-3 py-2 text-left transition ${
                        used
                          ? "cursor-not-allowed opacity-60"
                          : ticked
                            ? "bg-purple-50 dark:bg-purple-950"
                            : "hover:bg-surface-50"
                      } ${
                        index === safeHighlight && !used
                          ? "ring-1 ring-inset ring-purple-600/40 dark:ring-purple-400/40"
                          : ""
                      }`}
                      disabled={used}
                      onMouseEnter={() => {
                        if (!used) setHighlight(index);
                      }}
                      onClick={() => toggleOption(option)}
                    >
                      <span
                        aria-hidden
                        className={`mt-0.5 grid h-4 w-4 shrink-0 place-items-center rounded-sm border ${
                          ticked || used ? "border-purple-700 bg-purple-700 text-white" : "border-line-200 bg-card"
                        }`}
                      >
                        {ticked || used ? <Check className="h-3 w-3" /> : null}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-sm text-ink-900">{option.label}</span>
                        {option.sublabel ? (
                          <span className="block truncate text-xs text-ink-500">{option.sublabel}</span>
                        ) : null}
                        {used ? <span className="block text-xs text-ink-500">Already on this list</span> : null}
                      </span>
                    </button>
                  </li>
                );
              })
            ) : (
              <li className="px-3 py-3 text-sm text-ink-500">
                {loading ? "Searching…" : problem ? problem : query.trim() ? `Nothing matches “${query.trim()}”.` : "No records to choose from yet."}
              </li>
            )}
          </ul>

          {/*
            The same escape the single picker has, for the same reason. This is the ROSTER — the
            control a designer opens to tick thirty participants — so it is the likeliest place of
            all to discover that one of them has no record yet, and the most expensive place to have
            to leave: a half-ticked list is lost the moment the page navigates away.
          */}
          {isInlineCreatable(field.refModel) && !disabled ? (
            <button
              type="button"
              className="flex w-full items-center gap-2 border-t border-line-200 px-3 py-2.5 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50"
              onClick={() => setCreating(true)}
            >
              <Plus className="h-4 w-4 shrink-0" aria-hidden />
              {query.trim()
                ? `Create “${query.trim()}” as a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`
                : `Create a new ${INLINE_MODEL_NOUN[field.refModel as InlineCreatableModel]}`}
            </button>
          ) : null}

          <ScopeNotice field={field} payload={payload} />

          {describing ? (
            <p className="border-t border-line-200 px-3 py-2 text-xs leading-5 text-ink-500">
              Reading back what the repository holds about “{describing}”…
            </p>
          ) : null}
          {createProblem ? (
            <p className="border-t border-line-200 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
              {createProblem}
            </p>
          ) : null}

          <div className="flex items-center justify-between gap-2 border-t border-line-200 px-3 py-2">
            <span className="text-xs text-ink-500" id={`${baseId}-count`}>
              {picked.length ? `${picked.length} selected` : "Nothing selected yet"}
            </span>
            <div className="flex items-center gap-2">
              <button type="button" className="text-xs font-medium text-ink-500 underline" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button
                type="button"
                className="field-button"
                aria-describedby={`${baseId}-count`}
                disabled={!picked.length}
                onClick={() => {
                  onAdd(picked);
                  setPicked([]);
                  setOpen(false);
                }}
              >
                Add {picked.length || ""}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {creating && isInlineCreatable(field.refModel) ? (
        <InlineRecordDialog
          open
          model={field.refModel}
          onClose={() => setCreating(false)}
          /*
            TICKED, not added. The roster's own "Add" button is what commits the selection, and a
            record that added itself the moment it was created would break that — the designer would
            have made a row they had not confirmed, in a list they were still building. So the new
            record joins `picked` and the count goes up by one, exactly as if they had found it and
            ticked it.

            What it is ticked WITH is the server's description of the record and never the raw row
            the create returned; `tickCreated` and `describeCreated` carry that argument.
          */
          /*
            UNGATED, AND SAFE BECAUSE THE GATE IS IN THE DIALOG. A multi-select has no edit path — it
            never passes `recordId` — so there is no record here for a seed to overwrite. The
            create-only rule is enforced once, in `InlineRecordDialog`'s `seedForForm`, precisely so
            that this line staying as it is cannot break it, and so that the day this picker does
            grow an edit path the rule does not have to be remembered again here.
          */
          seed={seed}
          onCreated={(record) => {
            void tickCreated(record);
          }}
          /*
            Nothing is ticked, and the panel says why. A queued save has no server id, so there is no
            record for `describeCreated` to read back and nothing a roster row could point at — and a
            roster row carrying a link to nothing is indistinguishable in the participant table from a
            blank row somebody added by accident. The same sentence the single picker uses, so a
            designer meets one explanation of this state rather than two.
          */
          onQueued={() => {
            setDescribing(null);
            setCreateProblem(QUEUED_OFFLINE_NOTICE);
          }}
          /*
            The duplicate prompt found the artisan already in the repository — tick THEM rather than
            navigating to their edit page and losing the half-ticked roster. `tickCreated` describes
            them through the server exactly as it does a genuinely new record; nothing from the
            conflict payload, which carries a masked identity number, is read here.
          */
          onUseExisting={(artisan) => {
            void tickCreated({ id: artisan.id, name: artisan.name });
          }}
        />
      ) : null}
    </div>
  );
}
