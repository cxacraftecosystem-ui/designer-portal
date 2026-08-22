"use client";

/**
 * THE REPOSITORY RECORD'S OWN PAGE, RENDERED INSIDE THE STAGE ENTITY THAT MIRRORS IT.
 *
 * ── WHAT THE OWNER ASKED FOR, AND WHY THREE WAVES OF MAPPING DID NOT DELIVER IT ───────────────
 * FOUR of a design workshop's entities ask for the same facts a repository record page already
 * collects, out of the EIGHT REF fields that hydrate from one at all — see {@link MIRROR_POINTS} for
 * the four and {@link NOT_EMBEDDED} for why the other four are attributions rather than mirrors.
 *
 * COUNTED, NOT REMEMBERED. An earlier draft of this paragraph said "seven" and "three", which were
 * the numbers before the pin test's first run added two refusals nobody had enumerated — so the
 * numbers here are not to be trusted either, they are to be re-run:
 *   cd frontend && npx playwright test e2e/stage-record-embed-unit.spec.ts --reporter=line
 * walks `referenceHydrationPoints()` and fails on any key neither table below mentions, and its
 * "the refusals are the ones that were argued" case names all four refusals by hand.
 * For three waves this was approached by WIDENING `DW_REFERENCE_HYDRATION` so that more of a chosen
 * record crossed onto the stage row. That closes the DATA gap and leaves the one the
 * owner was actually describing untouched: "copy the same pages over there as is, and add workshop
 * specific fields at the bottom of the list of the fields, the layout and everything needs to be
 * exactly as it is on the each individual page". A wider mapping fills more boxes on a form that is
 * still a generated grid of registry fields. It is not the page.
 *
 * So this mounts the ACTUAL COMPONENT — `ArtisanForm`, `ToolForm`, `ProductForm`, `ProcessForm` —
 * the same one its own route mounts, with nothing removed, reordered or restyled, and puts the
 * stage's own questions into the `footerFields` slot those four grew for exactly this, so they read
 * as the last fields of one continuous form rather than as a second panel underneath one.
 *
 * ── THE THREE PARTS, TOP TO BOTTOM, AND WHY NONE OF THEM CAN BE DROPPED ───────────────────────
 *
 *  1. THE PICKER, unchanged. "This artisan is already in the repository" has to stay one click, and
 *     after the QR wave it can also be answered by scanning the record's printed card. It is drawn
 *     by the ordinary `FieldGrid`, from the ordinary registry field, and this component never sees
 *     it — the host passes it in as a node.
 *
 *  2. THE RECORD FORM, INLINE. The whole point. It saves a REAL repository record through its own
 *     normal route; see "HOW A SAVE FLOWS" below for why it must not be a stage-local copy. On an
 *     UNLINKED COLLECTION ROW it waits behind one button — see {@link StageRecordEmbed.mountOnRequest}
 *     for the GPS watch a create-mode mount starts, which is not something 244 browsable rows may
 *     each do on their own.
 *
 *  3. THE MIRRORED FIELDS, in a disclosure beneath, labelled for what they are. They stay MOUNTED
 *     AND EDITABLE. Hydration only ever fills a blank, so a designer correcting a hydrated value by
 *     hand is a supported and necessary act — the record may be wrong and the artisan is standing
 *     in front of them — and keeping the boxes in the tree is also what keeps their `data-dw-field`
 *     anchors, their per-field refusals, their provenance stamps and the stranded-refusal banner
 *     alive. Hidden is not the same as unmounted, and only one of the two is safe.
 *
 * ── WHAT THIS FILE REFUSES TO DO ──────────────────────────────────────────────────────────────
 *
 *  * IT DOES NOT REPLACE THE FIELD GRID. Every registry field of the entity is still rendered by
 *    `FieldGrid`/`FieldCell`; this only decides which of THREE groups each one is drawn in. Four
 *    things live in `FieldCell` and nowhere else — the `data-dw-field` anchor the workshop search
 *    and the readiness screen navigate to, the per-field message the server returned, the
 *    provenance stamp, and the stranded-refusal banner's assumption that every field of a rendered
 *    entity is drawn. A hand-rolled `FieldInput` in a record form's footer would silence all four,
 *    the refusal worst of all: the server would refuse a value and nothing on screen would say so.
 *    That is why the host passes the grids IN as nodes rather than this file rendering fields.
 *
 *  * IT DOES NOT REORDER THE REGISTRY. The grouping here is a DISPLAY decision and stops at this
 *    file. Registry order drives the report's table columns (`_table_columns` truncates at six) and
 *    `registry_version()` sorts before hashing, so a handset that had already fetched the schema
 *    would compare versions, see agreement, and render the old order for ever. A test also pins
 *    that a REF picker is DECLARED before the fields it fills in.
 *
 *  * IT DOES NOT MOUNT TWO RECORD FORMS IN ONE ROW. `existingProduct` has two mappings —
 *    `productRef` (the large one, the one the form is for) and `artisanRef` (a single pair). The
 *    small one stays an ordinary picker, refused in writing in {@link NOT_EMBEDDED} beside the other
 *    one-pair mappings, and drawn ABOVE the form with the picker it cascades into rather than in the
 *    footer — see {@link splitMirroredFields} for the two independent reasons that has to be so.
 *
 *  * IT DOES NOT PUT A REF PICKER INSIDE THE RECORD FORM. React propagates events through the REACT
 *    tree, so a record dialog opened from a picker in the footer would fire the surrounding form's
 *    `onSubmit`, `onKeyDown` and `onInput` even though it is portalled out of its DOM. Again
 *    {@link splitMirroredFields}.
 *
 *  * IT DOES NOT SUPPRESS THE RECORD FORM'S UNSAVED-CHANGES PROMPT. See the trap note at the foot
 *    of this header.
 *
 * ── HOW A SAVE FLOWS ──────────────────────────────────────────────────────────────────────────
 * The embedded form saves a real repository record through its own route. Then the row is pointed
 * at that record and the mirrored fields are filled by the SERVER's hydration payload, exactly as
 * they are when a designer picks the record in the picker.
 *
 * IT IS NOT A STAGE-LOCAL FORM WRITING STAGE FIELDS, and that is settled rather than open. The
 * mirrored values are DERIVED, not copied: `participant.age` is computed from `Artisan.dateOfBirth`,
 * `participant.artisanCardNo` is `mask_identity_number(pehchanCardNumber)`, and `tool.lengthCm` is
 * the record's INCHES column times 2.54. A stage-local form would have to reimplement the server's
 * data lambdas in TypeScript — and the first of those three, got wrong, puts an unmasked PM
 * Vishwakarma card number into a document every grantee can download.
 *
 * A ROW ALREADY LINKED MOUNTS THE FORM IN EDIT MODE OVER THAT RECORD. A blank create there would
 * quietly offer to make a second artisan every time the row was reopened.
 *
 * ── HOW THE SPLIT IS DECIDED ──────────────────────────────────────────────────────────────────
 * A mirror point is a REF field that has a hydration mapping. The mapping's TARGET keys are the
 * mirrored list; every other field of the entity is workshop-only and goes in the footer. It is
 * derived from the table rather than hard-coded so that a future widening cannot leave a field
 * silently in the wrong group — but WHICH entities get this treatment is an enumerated table with
 * the reasoning beside each row, because that is a judgement and a clever predicate cannot carry an
 * argument. {@link MIRROR_POINTS} is the table; {@link NOT_EMBEDDED} is the other half of it, and
 * `e2e/stage-record-embed-unit.spec.ts` fails if the registry grows a mapping neither one mentions.
 * That pin earned itself on its first run: two of the four refusals below are mappings this wave's
 * own brief did not enumerate, and without it they would have been decided by omission.
 *
 * ── TRAPS THAT SHAPED THIS FILE ───────────────────────────────────────────────────────────────
 *
 * T-FORM. The footer fields are rendered INSIDE a `<form>` that reads its payload with
 *   `new FormData(event.currentTarget)`. Nothing `FieldInput` draws carries a `name`, and that is
 *   now load-bearing rather than incidental — see the `mirror={false}` note in `FieldInput.tsx`,
 *   which this change made true for a second reason. Nothing there sets `required` either, so a
 *   blank stage answer cannot refuse the record form's submit.
 *
 * T-ENTER. `handleFormEnter` walks `el.closest("form")`, so the footer fields join the record
 *   form's Enter order and its dirty tracking. That reads correctly rather than surprisingly: Enter
 *   advances through the workshop-only fields as through any others, and Enter on the LAST of them
 *   submits — which is what Enter on the last field of a form does everywhere in this app, and here
 *   it means "save the record", the same act as the Save button two rows below it.
 *
 * T-DISCARD. "Discard this entry" bumps a key on the `<form>` and remounts the subtree, footer and
 *   all. Nothing may be kept in state inside it. Nothing is: every workshop-only value is written
 *   straight through the stage page's own patch path into the durable draft, which is also the only
 *   shape the autosave snapshot persists.
 *
 * T-PROMPT. THE RECORD FORM'S UNSAVED-CHANGES PROMPT STAYS ARMED. The stage page has no such prompt
 *   because its draft is durable — but that durability is a property of the STAGE's fields, which
 *   `lib/designWorkshopStore` writes. It is not a property of the record form's: the name, the
 *   identity digits, the picked files and the captured fix live in React state and uncontrolled DOM
 *   and are read only at submit. An earlier pass added an "embedded, so do not prompt" flag and a
 *   reviewer argued it back out; `forms/inlineRecordHost.ts` now states the conclusion in capitals.
 *   Suppressing the question does not make the answer true. If the double prompt is ever to go, the
 *   embedded form's fields have to be made durable FIRST.
 *
 *   AND THREE MORE EXITS NOW ASK IT, because arming the prompt is worth nothing on an exit that
 *   never consults it. The stage page's "previous stage" / "next stage" buttons and a collection
 *   row's own collapse both went straight through — correct while everything on the page was
 *   durable, and silent data loss the moment a record form was mounted inside one. Both now call
 *   `useLeaveInterceptor` first, and `UnsavedChangesProvider` walks its stack instead of asking only
 *   the topmost, because stage 5 mounts TWO of these forms and a clean one was answering for a dirty
 *   one. `handleCancel` says what Discard did, since the four forms cannot tell a host which of
 *   their two exits called it.
 *
 * T-OFFLINE. `onCreated` never fires for a save that was banked in the outbox — there is no id, and
 *   a REF field must hold a real server id or the report renders a reference to a deleted record for
 *   ever. The form reports that through `onQueued`, and this says so in words rather than
 *   half-writing a link.
 *
 * T-REMOUNT. EVERY SAVE RE-KEYS THE FORM, AND THAT COSTS ONE MESSAGE. `initial` is read into React
 *   state and uncontrolled DOM at mount and never re-read, so a form left standing over a record
 *   that has moved on is a form whose next Save posts the old values back. After a CREATE, leaving
 *   the create-mode instance standing would let a second Save make a SECOND artisan. After an EDIT
 *   it is subtler and no less real — and on `ProcessForm` it is not subtle at all, because that form
 *   sets `committed` on every accepted write and thereafter refuses to save, reports itself clean
 *   and lets Cancel discard the next correction without asking. {@link remountForm} lists all three
 *   reasons a mount is replaced. The remount destroys anything the form was holding in
 *   its own state, and there is exactly one thing worth holding at that moment: the four forms'
 *   partial-media-failure banner, which names the photographs that did not upload and is set
 *   immediately before `onCreated` is called. `InlineRecordDialog` loses the same message by closing
 *   over it, and its own comment calls that "the trade"; this host makes the same trade for a
 *   stronger reason, because the alternative here is a duplicate record rather than a closed dialog.
 *
 *   WHAT IS DONE ABOUT IT, since a silent loss would not be acceptable: the actionable half of that
 *   message is "the record was saved; re-open it to retry those files", and re-opening it is exactly
 *   what the remount does. So the confirmation this component writes says to check the attachments,
 *   unconditionally — which is true whether or not anything failed, and is not a claim that
 *   something did. RECOVERING THE FILE NAMES WOULD TAKE A CHANGE IN THE FOUR FORMS (reporting the
 *   failures alongside the record rather than only into their own banner), and those are not this
 *   wave's to edit.
 */

import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { ChevronDown } from "lucide-react";

import { useLinkedWorkshopId } from "@/components/designworkshop/LinkedWorkshop";
import {
  INLINE_MODEL_NOUN,
  InlineRecordForm,
  isInlineCreatable,
  type InlineCreatableModel,
  type InlineCreatedRecord
} from "@/components/designworkshop/InlineRecordDialog";
import { inlineSeed } from "@/components/designworkshop/StageReferenceField";
import {
  geoValue,
  hydrateFromReference,
  inputValue,
  isMultiField,
  listStageReferences,
  referenceHydrationFor,
  stringifyRefValue,
  type DwEntity,
  type DwEntryData,
  type DwField,
  type DwReferenceOption,
  type DwValue
} from "@/lib/designWorkshops";

/* ────────────────────────────────────────────────────────────────────────────
 * WHICH ENTITIES GET THE RECORD PAGE, AND WHICH DO NOT
 * ──────────────────────────────────────────────────────────────────────────── */

/** One entity whose stage form embeds the repository record page it mirrors. */
export type MirrorPoint = {
  /** The registry entity key. */
  entityKey: string;
  /**
   * The REF field whose hydration mapping defines the mirrored set — and, when the row is already
   * linked, the record the form is mounted in EDIT mode over.
   */
  refFieldKey: string;
  /** Why this one, in one sentence, for a reader deciding whether the next mapping belongs here. */
  why: string;
};

/**
 * THE FOUR. An enumerated table rather than a predicate, deliberately: whether an entity should
 * carry a whole record page is a judgement about what a designer is doing at that stage, and a
 * predicate cannot hold the argument for it. What IS derived is the field split — see
 * {@link splitMirroredFields} — so a widened mapping cannot leave a box in the wrong group.
 *
 * The order is the order a designer meets them, which is the stage order.
 */
export const MIRROR_POINTS: readonly MirrorPoint[] = [
  {
    entityKey: "participant",
    refFieldKey: "artisanRef",
    why:
      "Stage 3 IS the roster: a row of it is an artisan, and `participant.artisanRef` carries 24 " +
      "pairs — the third widest mapping in the table, behind `tool.toolRef` at 32 and " +
      "`existingProduct.productRef` at 29. It is also the likeliest place in the whole " +
      "workshop to discover that the person in front of you has no record yet, which is the moment " +
      "a designer would otherwise abandon a half-filled stage to go and make one."
  },
  {
    entityKey: "tool",
    refFieldKey: "toolRef",
    why:
      "A stage-5 tool row and a ToolDocumentation record are the same object measured twice — the " +
      "mapping carries the five *AsRecorded dimensions, the converted centimetre pair, the " +
      "assignment list and the measurement-method note. Retyping any of it is how the two copies " +
      "come to disagree."
  },
  {
    entityKey: "traditionalProcess",
    refFieldKey: "processRef",
    why:
      "The stage-5 SINGLETON is where a whole documented process belongs, and its mapping is the " +
      "only one that carries `steps` — the researcher's ordered sub-steps, built from all four " +
      "ProcessStep columns. Everything a Process record holds has a home on this one entity."
  },
  {
    entityKey: "existingProduct",
    refFieldKey: "productRef",
    why:
      "Stage 6's baseline is documented products. `productRef` is the large mapping and the one the " +
      "form is for; the entity's OTHER mapping, `existingProduct.artisanRef`, is a single pair " +
      "(`name -> artisanName`) and stays an ordinary picker among the workshop-only fields — it is " +
      "the cascade that narrows this product list, not a second record to mount a form for."
  }
] as const;

/**
 * THE FOUR THAT ARE DELIBERATELY LEFT OUT, and this is where a reader will come looking.
 *
 * Written down rather than merely absent, because "no form here" and "nobody has got to it yet" are
 * indistinguishable from an empty list — and the pin test in `e2e/stage-record-embed-unit.spec.ts`
 * reads this as the other half of {@link MIRROR_POINTS}, so a new mapping cannot slip past both.
 *
 * TWO OF THESE FOUR WERE FOUND BY THAT PIN RATHER THAN BY THE BRIEF, on the first run, which is the
 * best evidence available that the pin is worth its lines: `existingProduct.artisanRef` and
 * `prototype.productRef` are both ONE-PAIR mappings, and one-pair mappings are easy to read past
 * when you are looking for the wide ones. They are refused for a reason that is the same in both
 * cases and is worth stating once: a mapping that carries a single NAME is an ATTRIBUTION, not a
 * mirror. The row is not a copy of that record and never becomes one, so mounting the record's page
 * over it would offer to save a whole ProductDocumentation from a row that wanted to say who made
 * the thing.
 */
export const NOT_EMBEDDED: readonly { point: string; why: string }[] = [
  {
    point: "existingProduct.artisanRef",
    why:
      "One pair (`name -> artisanName`) beside the 29 of `productRef` on the same entity, and it " +
      "is the CASCADE rather than a second subject: choosing the artisan is what narrows the " +
      "product list below it. Two record forms in one row would each be able to save a different " +
      "record from one Save, and the row would have two ideas of what it is about. It stays an " +
      "ORDINARY PICKER — drawn above the record form in the `pickers` group beside `productRef`, " +
      "in declaration order, which is where {@link splitMirroredFields} already puts every REF " +
      "field of a mirror-point entity."
  },
  {
    point: "prototype.productRef",
    why:
      "One pair (`name -> productName`) out of the prototype's 29 fields, and the field is labelled " +
      "\u201cExisting product developed from\u201d \u2014 an attribution, not a copy. A prototype is a " +
      "DwPrototype: it is made AT the workshop and has no repository record to mirror. Its cascade " +
      "also filters on `artisanRef`, which at stage 13 holds a DwParticipant entry id rather than an " +
      "Artisan id, so `inlineSeed` deliberately refuses to seed a parent there and a create would " +
      "file the product under nobody."
  },
  {
    point: "processStep.processRef",
    why:
      "Only 3 of the step's 16 fields mirror, and they mirror the PARENT process rather than the " +
      "step — the registry says so beside the mapping. A Process has many steps and hydration " +
      "cannot pick one, so a whole ProcessForm per step row would be a form-per-row for a record " +
      "the whole collection shares, and saving it from any one row would rewrite the sequence the " +
      "others came from. The right home for a documented process is the stage-5 singleton, which is " +
      "in MIRROR_POINTS above. WHICH LEAVES ONE HAZARD OPEN, stated rather than left to be " +
      "rediscovered: the singleton mounts a `ProcessForm` over that same Process, and a step row " +
      "linked to it still offers “Edit this process”. `recordFormMountedOver` suppresses that " +
      "pencil only on the pickers `MirroredEntityBody` renders, and these rows are an ordinary grid " +
      "the stage page renders separately, so an edit saved from a step row is PATCHed back to its " +
      "pre-edit values by the singleton's next Save — that form read `initial` at mount and the " +
      "linked id never changed. Correcting the process in the singleton itself is safe; closing the " +
      "hazard needs the stage page, which is the only place that holds both entities."
  },
  {
    point: "workshopSetup.craftRef",
    why:
      "There is no CraftForm to mount — the craft form is inline JSX on the crafts page — and craft " +
      "CREATE is gated on PROFESSOR while a DESIGNER is a lower rank, so the button would 403 for " +
      "the exact role it exists for. `InlineRecordDialog`'s own header sets out the deeper reason " +
      "not to rush it: a craft is a row of a shared taxonomy of about 178, and a picker that mints " +
      "near-duplicates in the field fractures every join that hangs off it. This needs a policy " +
      "decision, not a UI change."
  }
] as const;

/** The mirror point for this entity, or null when it has none. */
export function mirrorPointFor(entity: DwEntity): MirrorPoint | null {
  return MIRROR_POINTS.find((point) => point.entityKey === entity.key) ?? null;
}

/**
 * The reference field a mirror point names, resolved against the entity actually in hand.
 *
 * Null rather than a throw when the field is missing or is not an inline-creatable REF: a browser
 * can be holding a registry OLDER or NEWER than this build (`design-workshop-schema-skew` is a
 * whole spec about it), and the honest answer to "this build thinks there is a form here and the
 * registry disagrees" is to fall back to the ordinary generated grid, not to blank the stage.
 */
export function mirrorRefField(entity: DwEntity, point: MirrorPoint): DwField | null {
  const field = entity.fields.find((candidate) => candidate.key === point.refFieldKey);
  if (!field || field.deprecated) return null;
  if (field.type !== "REF" || !isInlineCreatable(field.refModel)) return null;
  return field;
}

/**
 * The THREE groups an entity's fields are drawn in when its record page is embedded.
 *
 * `pickers` go above the form, `workshopOnly` inside it as its last fields, `mirrored` in the
 * disclosure below. Declaration order is preserved within each group: the grouping is a display
 * decision and must not become a reordering.
 */
export type MirrorGroups = { pickers: DwField[]; mirrored: DwField[]; workshopOnly: DwField[] };

/**
 * Which of an entity's fields are filled in FROM the record, which are the workshop's own, and which
 * are pickers that have to sit above the form rather than inside it.
 *
 * DERIVED FROM THE HYDRATION TABLE AND NEVER FROM A LIST OF NAMES. The mapping's target keys are,
 * by definition, exactly the fields the linked record fills in; anything else on the entity is a
 * question only this workshop asks. Widen the mapping tomorrow and the new field moves group with
 * no edit here — which is the whole reason the split is computed rather than written out.
 *
 * ── WHY `pickers` IS A GROUP OF ITS OWN AND NOT JUST THE ONE REF FIELD ────────────────────────
 * The mirror point's own REF field is obviously there: it is the control that says "this record is
 * already in the repository", and listing it in the footer would put two of the same control on one
 * row. EVERY OTHER REF FIELD OF THE ENTITY JOINS IT, for two reasons that happen to point the same
 * way, and on today's registry that is exactly one field — `existingProduct.artisanRef`:
 *
 *  1. THE CASCADE HAS TO BE ANSWERABLE FIRST. `existingProduct.productRef` declares
 *     `refFilterBy: "artisanRef"` and its own help text says "Pick the artisan first to narrow this
 *     list". Left in the footer, the artisan picker would appear BELOW the product picker and below
 *     the whole product record page — the registry declares it first, and a test pins that a REF
 *     picker is declared before the fields it fills in, so putting it last on screen would invert on
 *     screen the one ordering the registry is careful about.
 *
 *  2. A PICKER CAN OPEN A RECORD DIALOG, AND A DIALOG INSIDE A `<form>` IS NOT INERT. `FieldDialog`
 *     portals to `document.body`, so there is no nested `<form>` element — but React propagates
 *     events through the REACT tree, not the DOM tree, so a dialog rendered inside the embedded
 *     record form's subtree bubbles its `submit`, `keydown` and `input` into that form's handlers.
 *     Concretely: creating an artisan from the picker would fire the PRODUCT form's `onSubmit` as
 *     well (saving a product nobody asked to save), its `onKeyDown` would advance focus a second
 *     time on every Enter, and its `onInput` would mark the product form dirty from typing in a
 *     different record. Above the form, none of that is reachable.
 *
 * A tier is not consulted for this group: an ADVANCED picker hoisted to the top is more prominent
 * than its tier asks for, and that is the right trade against a control that would otherwise be able
 * to submit a form it is not part of.
 *
 * `fields` is passed in already filtered (`formFields`) so this has no opinion about deprecation or
 * captions — two rules that already live in one place each. Tier-splitting is the caller's, and is
 * applied to the returned groups.
 */
export function splitMirroredFields(entity: DwEntity, refField: DwField, fields: DwField[]): MirrorGroups {
  const targets = new Set(Object.values(referenceHydrationFor(entity, refField)));
  const groups: MirrorGroups = { pickers: [], mirrored: [], workshopOnly: [] };
  for (const field of fields) {
    if (field.key === refField.key || field.type === "REF") groups.pickers.push(field);
    else if (targets.has(field.key)) groups.mirrored.push(field);
    else groups.workshopOnly.push(field);
  }
  return groups;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Describing what was just saved
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * WHAT THE SERVER WOULD SAY ABOUT THIS RECORD, asked exactly as this field's own picker would ask.
 *
 * WHY A ROUND TRIP AT ALL, WHEN THE FORM JUST HANDED US THE RECORD. Because the record's keys are
 * PRISMA COLUMN NAMES and the hydration table's keys are the REFERENCE PAYLOAD's, and the payload
 * is a TRANSLATION rather than a rename: `_inches_to_cm` multiplies by 2.54 because the stage box
 * prints "cm"; `_money` renders a Decimal as a two-place string because a float round trip turns
 * 1250.10 into 1250.0999999999999; `mask_identity_number` keeps a full PM Vishwakarma number out of
 * a report every grantee can download; the photograph and its caption are a join onto MediaFile the
 * row does not contain at all. Hydration only ever fills blanks, so a wrong value written from the
 * raw row could be corrected but never un-answered. `StageReferenceField`'s `describeCreated` has
 * the long form of this argument and it is the same argument.
 *
 * ASKED WITH THIS FIELD'S OWN SCOPE AND CASCADE, deliberately: a record the picker could never show
 * must not hydrate the row either, or a stage quietly holds another artisan's price under this
 * one's name.
 *
 * BY ID AND NOT BY NAME. The picker's own helper (`describeRecord` in `StageReferenceField`)
 * searches for the record's LABEL, because it predates the `recordId` clause on `/references`. That
 * clause is ANDed with the same scope and cascade, and unlike a name search it cannot be defeated by
 * a record that was renamed in the very form being described — which is precisely what an inline
 * EDIT does.
 *
 * IT IS A DEPENDENCY ON `recordId` BEING SERVED, and the degradation is worth knowing: FastAPI
 * ignores a query parameter it does not declare, so against a server that predates the clause this
 * asks for an unnarrowed page and almost certainly does not find the record in it. That resolves to
 * null, which is the same answer as any other miss — the row is linked, the boxes stay blank, and
 * the amber notice below says so and says what to do. Fail-closed and visible, never a wrong value:
 * hydration only fills blanks, so a wrong value written here could be corrected but never
 * un-answered, which is why every arm of this function prefers saying nothing to guessing.
 *
 * Null for every kind of miss, because the caller has one honest sentence to say about all of them
 * and no action that differs between them. An out-of-scope record answers null too: the server
 * returns it under `outOfScopeOption` with `options` left empty on purpose, and hydrating from a
 * row this picker refuses to offer would be widening the scope by the back door.
 */
async function describeForField(
  workshopId: string,
  field: DwField,
  filterValue: string,
  recordId: string
): Promise<DwReferenceOption | null> {
  try {
    const payload = await listStageReferences(workshopId, {
      model: field.refModel as string,
      scope: field.refScope,
      filterBy: field.refFilterBy ? filterValue || null : null,
      recordId
    });
    return payload.options.find((option) => option.id === recordId) ?? null;
  } catch {
    return null;
  }
}

/**
 * THE RECORD THIS EMBED'S FORM IS MOUNTED OVER IN EDIT MODE, or "" when the row names none.
 *
 * One function and not a repeated one-liner, because two surfaces reading the same row have to agree
 * about it: this component uses it to decide what it opens the record page over, and the host uses
 * it to tell the picker drawn above which record already has an edit surface open — see
 * `StageReferenceSelect.recordFormMountedOver`. The mount gate does not enter into it. A LINKED row
 * is never gated ({@link StageRecordEmbed.mountOnRequest} says so), so "the row names a record" and
 * "there is a form open over it" are the same answer; an UNLINKED row draws no pencil to suppress,
 * because the picker only offers one over a choice it has already made.
 */
export function embeddedRecordId(refField: DwField, row: DwEntryData): string {
  return inputValue(row[refField.key]);
}

/** One live region, styled by what it currently holds — see where it is rendered. */
function liveRegionClass(busy: boolean, notice: { tone: "warn" | "done" } | null): string {
  if (busy || notice?.tone === "done") return "text-xs leading-5 text-ink-500";
  if (notice) return "rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800";
  // Empty, but STILL MOUNTED: a region that arrives with its content is a region assistive
  // technology may never announce. `sr-only` is out of flow, so an empty one adds no grid row and
  // no gap — the layout is exactly what it was before the region became permanent.
  return "sr-only";
}

/* ────────────────────────────────────────────────────────────────────────────
 * The embed
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The record page, mounted inside one stage entity, with the stage's own fields at the bottom of it.
 *
 * The three field GROUPS arrive as nodes rather than as field lists, and that is the constraint the
 * whole design turns on: registry fields must go on being drawn by `FieldGrid`/`FieldCell`, which
 * lives in `EntityForm.tsx` with the anchors, the refusals, the provenance stamps and the
 * assumptions the stranded-refusal banner makes about them. A component that took field lists would
 * eventually draw one itself.
 */
export function StageRecordEmbed({
  entity,
  refField,
  row,
  workshopId,
  onPatch,
  picker,
  workshopFields,
  mirroredFields,
  mountOnRequest = false
}: {
  entity: DwEntity;
  /**
   * The REF field this embed is for, already resolved by {@link mirrorRefField}.
   *
   * THE `MirrorPoint` ITSELF IS DELIBERATELY NOT A PROP. Everything this component needs is on the
   * field — the model to mount, the scope and cascade to describe with, the mapping to hydrate
   * through — and a second parameter carrying the same identity is a second thing that can disagree
   * with the first. The table is for deciding WHETHER there is an embed here, which the host has
   * already done by the time this is mounted.
   *
   * THERE IS NO `disabled` EITHER, and its absence is a decision. The stage's `disabled` means "the
   * STAGE is being saved", and a record save is a different request to a different endpoint with its
   * own in-flight state; the four forms have no `disabled` prop to forward it to in any case.
   * Freezing the record page because a stage PUT is in the air would stop a designer typing for a
   * reason that has nothing to do with them.
   */
  refField: DwField;
  /** The whole stage record — the row of a collection, or the singleton. */
  row: DwEntryData;
  workshopId: string;
  /**
   * Write several keys of this record in ONE commit — the id and everything hydration filled in.
   *
   * A loop of single writes cannot stand in for it: in a collection each call rebuilds the array
   * from the `rows` captured at render, so the second is built on a snapshot that predates the
   * first and silently discards it. Choosing an artisan writes twenty keys.
   */
  onPatch: (values: Record<string, DwValue>) => void;
  /** The reference control, drawn by the host through the ordinary grid. Part 1. */
  picker: ReactNode;
  /** The stage's own questions, drawn by the host, to go INSIDE the form. Part 2's footer. */
  workshopFields: ReactNode;
  /** The boxes the linked record fills in, drawn by the host. Part 3. */
  mirroredFields: ReactNode;
  /**
   * DO NOT MOUNT THE FORM UNTIL THE DESIGNER ASKS FOR IT — for an UNLINKED row only, and set by the
   * host for a COLLECTION row only.
   *
   * ── WHAT MOUNTING COSTS ON A ROW NOBODY IS FILLING IN ─────────────────────────────────────────
   * `LocationFields` treats "no `initial`" as the one switch that turns AUTO-CAPTURE ON, and a
   * create-mode mount is exactly that: its mount effect calls `startAutoCapture()` with
   * `enableHighAccuracy: true`, which is a `watchPosition` plus the reverse-geocode chain behind it.
   * That is correct on `/artisans/new` — the researcher is standing in front of the artisan — and it
   * is not correct for the fourth row a designer opened while looking for somebody on a 244-row
   * roster. Opening rows to read them would wake the radio once per row, and collapsing and
   * reopening restarts it. The three collection mirror points are also the three whose forms carry
   * a location card at all; the singleton is `ProcessForm`, which has none.
   *
   * TWO MORE THINGS RIDE ON THE SAME GATE. A mounted form registers a leave guard and holds
   * attached files in its own state, so a row that is merely being browsed cannot acquire either.
   *
   * A LINKED ROW IS NEVER GATED. Its form opens in EDIT mode, which never auto-captures, and the
   * whole point of the embed is that the record is right there.
   */
  mountOnRequest?: boolean;
}) {
  const linkedId = embeddedRecordId(refField, row);
  const model = refField.refModel as InlineCreatableModel;
  /** "artisan", "product", "tool", "process" — the picker's own word for it, never a second one. */
  const noun = INLINE_MODEL_NOUN[model];
  const linkedWorkshopId = useLinkedWorkshopId();
  const filterValue = refField.refFilterBy ? inputValue(row[refField.refFilterBy]) : "";

  /**
   * What the picker would seed a create with — the row's artisan and the linked workshop.
   *
   * Recomputed every render rather than captured when the form mounted, for the reason
   * `StageReferenceSelect` recomputes its own: the cascade above can clear the artisan out from
   * under it, and a frozen seed would hand the form a parent the row no longer names. Shared with
   * the picker rather than reimplemented, so the two doors into the same form cannot disagree about
   * whose product is being made.
   */
  const seed = useMemo(
    () => inlineSeed({ entity, field: refField, row, filterValue, linkedWorkshopId }),
    [entity, refField, row, filterValue, linkedWorkshopId]
  );

  /**
   * WHAT THE ROW SAYS AND HOW TO WRITE TO IT, BOTH AS THEY ARE RIGHT NOW.
   *
   * ── THE VALUES ────────────────────────────────────────────────────────────────────────────────
   * A save is a round trip and a designer types into the boxes beside it while the answer is in
   * flight, so the row this callback closed over is stale by the time the answer lands.
   *
   * ── AND THE WRITER, WHICH IS THE HALF THAT WAS MISSING ────────────────────────────────────────
   * Guarding the values and not the writer is worse than guarding neither, because it reads as
   * solved. On the three COLLECTION mirror points `onPatch` is `(values) => patchRowMany(index,
   * values)`, and `patchRowMany` rebuilds the WHOLE rows array from the `rows` it captured at that
   * render — `EntityForm` says so twice in its own comments and the word it uses is "silently
   * discards". So a stale writer does not merely write a stale row: it replaces the collection with
   * a snapshot taken before the save, throwing away every edit made to every row since — a mirrored
   * box corrected on this component's own amber advice, a footer field, an edit to another row —
   * with nothing on screen admitting it. A reorder during the window also makes the captured `index`
   * name a different row entirely. `StageReferenceField`'s `liveScan` is the same ref for the same
   * reason, one control over; the SINGLETON path is safe either way because `patchSingletonMany` is
   * an updater.
   *
   * Refreshed in an effect and not during render: a render can be discarded under concurrent
   * rendering, and a ref written on one that never committed would point at a writer React threw
   * away.
   */
  const live = useRef({ row, onPatch });
  useEffect(() => {
    live.current = { row, onPatch };
  }, [row, onPatch]);

  /**
   * The one line under the form, and it has two voices.
   *
   * TONE AND NOT JUST TEXT, for the reason `StageReferenceField`'s own `PickerNotice` carries one:
   * amber is the colour this app refuses a value in, and a designer told in amber that their row is
   * filled goes looking for what went wrong. "Saved to the repository and linked to this row" is not
   * a warning and must not be dressed as one — the two hosts either teach one vocabulary or two.
   */
  const [notice, setNotice] = useState<{ tone: "warn" | "done"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  /**
   * WHICH HYDRATION IS STILL THE CURRENT ONE.
   *
   * A describe round trip is seconds long and the picker is drawn directly above this form, live
   * throughout it. In that window a designer can choose a different record or press "Clear the
   * link", and the continuation below would then write `{...hydrateFromReference(…), [ref]: the id
   * it started with}` — re-pointing the row back at the record this embed created, over the choice
   * they just made, and computing the clear-on-re-point against a `previous` that is two links old.
   * The row would end up naming one record while holding another's values, which
   * `hydrateFromReference`'s own header calls the one outcome worse than either alternative.
   *
   * `StageReferenceField` has had this counter since the picker could create: it bumps on every
   * create, on every pick and on every unlink, and the handset drops its `pendingHydration` at the
   * same three moments. This is that rule on the second host, with one difference forced by the
   * shape of this one — the link is NOT written from here alone. The picker above writes it too, and
   * this component is never told when it does, so the supersede is hung off the linked id CHANGING
   * TO SOMETHING THIS COMPONENT DID NOT CLAIM. `claimed` is what the in-flight hydration is writing,
   * so its own `onPatch` does not supersede itself while everybody else's does: choose, scan and
   * "Clear the link" are all caught without the picker having to report any of them.
   */
  const hydration = useRef(0);
  const claimed = useRef(linkedId);
  useEffect(() => {
    if (linkedId === claimed.current) return;
    claimed.current = linkedId;
    hydration.current += 1;
    // AND THE STATE THE SUPERSEDED CONTINUATION WILL NEVER COME BACK TO CLEAR. It returns early on
    // the generation check, so if this did not clear them the "Reading the record back…" line would
    // stand for the rest of the page's life, and the notice beside it would be about a record this
    // row no longer names. The picker that superseded us reports its own progress under itself.
    setBusy(false);
    setNotice(null);
  }, [linkedId]);

  /**
   * WHICH MOUNT OF THE RECORD FORM IS ON SCREEN, and every reason there is a new one.
   *
   * The form reads `initial` into React state and uncontrolled DOM AT MOUNT and never re-reads it,
   * so a form left standing over a record that has moved on is a form whose next Save posts the old
   * values back. Two things move a record on, and both remount:
   *
   *  1. A CREATE, which is also a re-point — handled by the linked id in the key rather than here.
   *  2. AN EDIT SAVED FROM THIS FORM. It changes neither the linked id nor anything else in the key,
   *     so without an explicit bump the form stays mounted over its own stale `initial` — and on
   *     `ProcessForm` that is not merely stale but LOCKED: it sets `committed` on every accepted
   *     write, create and edit alike, after which `submit` returns early, the button reads "Saved"
   *     and `hasUnsavedWork` is false for ever. Correct a stage-5 process twice and the second
   *     correction cannot be saved, by a form reporting itself clean.
   *
   * THERE USED TO BE A THIRD, and it is gone because the picker it recovered from no longer opens a
   * second editor. That picker is the one drawn directly above this form: it offered "Edit this …"
   * on the SAME record, so a save made there left this form standing over a stale `initial`, and the
   * embed recovered by remounting through a context the dialog reported into. The record page IS the
   * edit surface while it is mounted, so the pencil is now suppressed instead —
   * `StageReferenceSelect`'s `recordFormMountedOver`, which this host feeds through
   * `embeddedRecordId`. Prevention rather than recovery, and the recovery could lose typing the
   * remount threw away.
   *
   * WHAT THE SUPPRESSION DOES NOT REACH, written down because "there is no second editor anywhere"
   * is the easy thing to read into the paragraph above and it is not true. Only
   * `MirroredEntityBody` passes `recordFormMountedOver`, and it passes it to the pickers of THIS
   * embed, because it is the only host holding both the id and the picker. Stage
   * TRADITIONAL_PROCESS_BASELINE also renders the `processStep` collection, whose own `processRef`
   * is the same model at the same WORKSHOP scope and is refused a form of its own in
   * {@link NOT_EMBEDDED}; its rows are therefore an ordinary grid with no id to compare, so a step
   * row linked to the process this singleton has open still draws "Edit this process" over it. The
   * deleted recovery never reached that path either — a `processStep` row is a different entity in
   * a different subtree, rendered by the stage page rather than from in here. Closing it means the
   * STAGE PAGE telling every entity which record has a form open, since it is the only place that
   * knows both; until then it is the hazard NOT_EMBEDDED's entry describes.
   *
   * Cancel bumps it too: the values live in React state and uncontrolled DOM inside the form, so
   * remounting by key is the only thing that could clear them.
   */
  const [formGeneration, setFormGeneration] = useState(0);
  const remountForm = useCallback(() => setFormGeneration((generation) => generation + 1), []);

  /**
   * WHAT THE LINKED RECORD SAID BEFORE THE DESIGNER STARTED EDITING IT.
   *
   * Captured when this embed opens over an already-linked row, because after the save the record no
   * longer holds its old answer anywhere. It is what lets an edit refresh a box that still holds
   * what the picker last put there while leaving a box the designer corrected by hand alone: on an
   * EDIT the record is the same one, so a value they typed in the room is still theirs and a
   * reference record is not more authoritative than the person in front of them. Clearing and
   * refilling — what a re-POINT does — would be wrong here for the reason it is right there.
   *
   * `StageReferenceField`'s `adoptEdited` exists because an inline edit once reached the repository
   * and never reached the row: the corrected village printed in the .docx for ever, and the designer
   * had watched themselves fix it. This is that rule, on the second host.
   *
   * TAKEN AGAIN ON EVERY REMOUNT, not once per linked id, and the generation is in the token for
   * that reason. A save leaves this snapshot spent — the record no longer says what it says — and
   * every save remounts, so re-describing on the generation refreshes it at exactly the right
   * moments and no others. It also closes the race the token exists for: the FIRST describe, fired
   * on mount over one bar of signal, could otherwise land after a save and overwrite the post-save
   * snapshot with the pre-save one, deciding the NEXT edit against an answer two saves old. A
   * continuation whose token has moved is dropped, exactly as {@link adoptCreated}'s is.
   */
  const beforeEdit = useRef<DwReferenceOption | null>(null);
  const describedFor = useRef<string>("");
  useEffect(() => {
    if (!linkedId) {
      beforeEdit.current = null;
      describedFor.current = "";
      return;
    }
    const token = `${linkedId}:${formGeneration}`;
    if (describedFor.current === token) return;
    describedFor.current = token;
    beforeEdit.current = null;
    let cancelled = false;
    void describeForField(workshopId, refField, filterValue, linkedId).then((option) => {
      if (!cancelled && describedFor.current === token) beforeEdit.current = option;
    });
    return () => {
      cancelled = true;
    };
  }, [linkedId, formGeneration, workshopId, refField, filterValue]);

  /**
   * A record CREATED from inside this embed: linked at once, hydrated when the server can describe
   * what belongs on the row.
   *
   * TWO WRITES AND NOT ONE. The link is the half we are certain of and it lands immediately, because
   * a surface that showed nothing at all until a round trip came back reads as the save having
   * failed — which is the moment a designer creates the same record a second time. The values are a
   * question only the server can answer.
   */
  const adoptCreated = useCallback(
    async (recordId: string) => {
      const previous = inputValue(live.current.row[refField.key]);
      setBusy(true);
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;
      // Claimed BEFORE the write, so the effect that watches the linked id reads this as our own
      // re-point rather than as somebody superseding us.
      claimed.current = recordId;
      live.current.onPatch({ [refField.key]: recordId });
      const described = await describeForField(workshopId, refField, filterValue, recordId);
      // Superseded while the answer was in flight — the designer picked another record above, or
      // cleared the link. Whatever they did is newer than this, and writing now would land a stale
      // record's values on a row that has moved on, under an id it no longer holds.
      if (hydration.current !== generation) return;
      setBusy(false);
      if (!described) {
        setNotice({
          tone: "warn",
          text: previous
            ? "The record was saved and linked, but this list cannot describe it just now — so the boxes the previous record had filled in have been CLEARED rather than left standing under the new record's name. Fill them in by hand, or reopen the picker and search for it."
            : "The record was saved and linked, but this list cannot describe it just now, so the boxes it would have filled in are still blank. Fill them in by hand, or reopen the picker and search for it — a required box left blank is refused when the stage is submitted."
        });
      } else {
        /*
          THE HALF OF THE FORM'S OWN BANNER THAT SURVIVES THE REMOUNT — see T-REMOUNT in the header.
          A save that wrote the record but lost some photographs sets a banner naming them and then
          calls `onCreated`, and re-keying the form into edit mode destroys it. The file names are
          gone; the ACTION is not, and it is the same action either way: the form below is now the
          saved record, so its attachments can be checked and re-added on the spot. Said without
          claiming anything failed, because this cannot tell.
        */
        setNotice({
          tone: "done",
          text: "Saved to the repository and linked to this row. The form below is now that record — worth checking its photographs, since anything still uploading when you pressed Save may not have landed."
        });
      }
      /*
        ONE CALL FOR BOTH OUTCOMES, AND THE FAILING ONE IS NOT A NO-OP. A description that never
        arrived is handed on as an option with an EMPTY `data`, which the same table reads exactly as
        it should: with no previous link there is nothing to clear and nothing to write; with one,
        the mapped boxes are CLEARED rather than left standing under the new record's id. That second
        case is the one worth spelling out — the previous record's name and price beside the new
        record's id is what `hydrateFromReference` calls the one outcome worse than either
        alternative, and nothing downstream can ever re-resolve it. A blank required box is refused
        loudly at submit; a filled box naming the wrong record is not refused at all.

        BOTH THE VALUES AND THE WRITER COME FROM `live` AND NOT FROM THIS CALLBACK'S CLOSURE — see
        the note on {@link live} for what a stale writer costs on a collection row, which is every
        edit made to every row of it since the save began.
      */
      const option: DwReferenceOption = described ?? { id: recordId, label: "", sublabel: "", data: {} };
      const { row: currentRow, onPatch: writeNow } = live.current;
      writeNow({
        ...hydrateFromReference(entity, refField, option, currentRow, previous),
        [refField.key]: recordId
      });
    },
    [entity, refField, filterValue, workshopId]
  );

  /**
   * Take up an EDIT made to the record this row already names.
   *
   * Deliberately not {@link adoptCreated}: that function's whole shape is about a record the row
   * does not name yet — it re-points the field and clears the previous record's values when the
   * link moves. None of that applies. The link is unchanged and the only question is which boxes may
   * take up the new answer, which is decided against the "before" snapshot above.
   */
  const adoptEdited = useCallback(
    async (recordId: string) => {
      const before = beforeEdit.current;
      setBusy(true);
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;
      const after = await describeForField(workshopId, refField, filterValue, recordId);
      // Re-pointed or unlinked while the answer was in flight: this row is no longer about the
      // record that was edited, and every box below would be judged against the wrong "before".
      if (hydration.current !== generation) return;
      setBusy(false);
      if (!after) {
        setNotice({
          tone: "warn",
          text: "Your changes were saved to the record, but this list cannot describe it just now, so the boxes on this row still show what it said before. Re-open the picker and choose it again to refresh them."
        });
        // Still remounted: the record HAS changed, so the form's `initial` and its dirty tracking
        // are stale whether or not the list could describe it — and on `ProcessForm` a form left
        // standing after an accepted write cannot be saved again at all.
        remountForm();
        return;
      }
      const mapping = referenceHydrationFor(entity, refField);
      const { row: current, onPatch: writeNow } = live.current;
      const patch: Record<string, DwValue> = {};
      for (const [sourceKey, targetKey] of Object.entries(mapping)) {
        const target = entity.fields.find((candidate) => candidate.key === targetKey);
        if (!target || target.deprecated) continue;
        // A GALLERY IS SEEDED AND NEVER REPLACED, on every surface and in all three of these rules.
        // It holds the photographs the designer took in the room and there is no second copy of
        // those anywhere; an edit to the record's own catalogue shot must not reach them.
        if (isMultiField(target)) continue;
        /*
          A GEO TARGET IS THE ONE PLACE AN OBJECT IS THE CORRECT SHAPE, and the scalar path below
          does not merely fail to write it — it ERASES it. `inputValue` returns "" for any object,
          so `held` is falsy and the "only a box still holding what hydration wrote may move" guard
          never fires; `stringifyRefValue` returns null for any object, so `next` is null; and
          `null === ""` is false, so the box was set to null. Three mappings carry a GEO source
          (`participant.subjectLocation`, `tool.recordSubjectLocation`,
          `existingProduct.recordSubjectLocation`),
          and the value is the SUBJECT PIN — the village's own coordinate, the only location
          invariant 5 lets cross, and the one thing on the row the desk's fix must never replace.
          So every save from this form silently emptied it, and the designer's next act on a blank
          map card is to drop their own pin, which is the desk.

          `hydrateFromReference` grew this arm for the same reason; this is the same arm, and the
          ABSENT case writes nothing rather than null — "the record has no pin" must not delete a
          pin the designer dropped on the village themselves.
        */
        if (target.type === "GEO") {
          const nextPoint = geoValue(after.data?.[sourceKey] as DwValue);
          if (!nextPoint) continue;
          const heldPoint = geoValue(current[targetKey]);
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
        const held = inputValue(current[targetKey]).trim();
        const was = before ? stringifyRefValue(before.data?.[sourceKey]) : null;
        // Blank fills, as always. Otherwise only a box still holding exactly what the last hydration
        // put there may move — a designer's correction outranks the record it came from.
        if (held && !(was !== null && held === was)) continue;
        const next = stringifyRefValue(after.data?.[sourceKey]);
        if (next === held) continue;
        // `null` and not `""`: "the record no longer says anything here" and "the record says it is
        // empty" are one answer on the wire, and both mean the box goes back to unanswered.
        patch[targetKey] = next === null ? null : next;
      }
      if (Object.keys(patch).length) writeNow(patch);
      if (!before) {
        setNotice({
          tone: "warn",
          text: "Your changes were saved. Boxes on this row that were already filled in have been left as they are — check them against the record if you changed something they show."
        });
      } else {
        setNotice({ tone: "done", text: "Your changes were saved to the record, and this row has been refreshed from it." });
      }
      /*
        THE FORM IS REMOUNTED OVER THE RECORD IT JUST WROTE, and this is not tidiness.

        `initial` is read once at mount, so after an edit the mounted form's `initialSignature`, its
        dirty tracking and its uncontrolled DOM all describe a record that no longer exists in that
        shape. On `ProcessForm` it is worse than stale: `setCommitted(true)` runs on EVERY accepted
        write, and thereafter `submit` returns early, the button is disabled and reads "Saved", and
        `hasUnsavedWork` is false — so a second correction to the same stage-5 process cannot be
        saved, and Cancel discards it without even asking, because the form believes it is clean.
        The create path only ever looked safe here by accident: writing the id re-keyed the form.

        LAST, AFTER THE PATCH, so the notice and the row are already settled when the subtree goes.
      */
      remountForm();
    },
    [entity, refField, filterValue, remountForm, workshopId]
  );

  const handleSaved = useCallback(
    (record: InlineCreatedRecord) => {
      // WHICH OF THE TWO IS DECIDED BY WHAT THE ROW NAMED WHEN THE FORM WAS MOUNTED, not by
      // comparing ids: an edit that leaves the id unchanged and a re-point to a record that happens
      // to be the same one are the same event, and both want the edit rule.
      if (linkedId && linkedId === record.id) void adoptEdited(record.id);
      else void adoptCreated(record.id);
    },
    [linkedId, adoptCreated, adoptEdited]
  );

  /**
   * THE DUPLICATE PROMPT'S "Open existing", WHICH IS A NAVIGATION UNLESS A HOST TAKES IT.
   *
   * A duplicate is the ORDINARY outcome at this mirror point, not the exception: the designer is
   * typing an artisan into stage 3 precisely because the picker's search did not show the person in
   * front of them, and the identity number matches anyway. Without this callback `ArtisanForm` has
   * three fallbacks and all three are `/artisans/{id}/edit` — the duplicate dialog's own button, the
   * inline conflict banner, and `AadhaarField`'s link, which appears LIVE as the digits are typed
   * and before any save. Every one of them pops the half-filled 22-stage draft the designer is
   * standing in, which is the single thing `forms/inlineRecordHost` exists to prevent and which this
   * file's header claims the embed never does. The picker and the dialog both wire it; this host was
   * the only one of the three that did not.
   *
   * IT DOES WHAT THE PICKER DOES: link the row to that artisan, describe them through the server and
   * hydrate. NOTHING BUT THE ID CROSSES. `ArtisanIdentityMatch` also carries a place, a craft and —
   * on the conflict path — a `maskedValue`, which is a masked Aadhaar or Pehchan string, and a
   * masked identity number must never be written onto a stage entry. The name is not read either:
   * `describeForField` asks the server by id and takes the label it answers with, so the row is
   * filled from the reference payload and from nothing this dialog handed us.
   *
   * The row's link then changes, which re-keys the form into EDIT mode over the artisan that already
   * exists — which is what "open the existing record" meant, without leaving the stage to do it.
   */
  const handleUseExisting = useCallback(
    (artisan: { id: string }) => {
      void adoptCreated(artisan.id);
    },
    [adoptCreated]
  );

  /**
   * A save that went into the offline outbox.
   *
   * NOTHING IS WRITTEN TO THE ROW. There is no server id, and a REF field must hold a real one —
   * `hydrate_entries`, `canonical_divergence` and the report's `ReferencedRecord` join all resolve
   * on it, so a client-invented id would render for ever as a reference to a deleted record. So the
   * row is left honestly unlinked and this says so, which is the half the designer needs: the work
   * is banked, and the link is the thing still to do.
   */
  const handleQueued = useCallback(() => {
    setNotice({
      tone: "warn",
      text: `This ${noun} is saved on this device and will be sent when the connection returns. It is NOT linked to this row yet — there is no repository id until it has been sent. Come back and choose it in the picker above once it has gone.`
    });
  }, [noun]);

  /**
   * Cancel, which here means "put the form back to what the record says".
   *
   * It cannot navigate and it must not unmount the form: this is not a dialog and there is nowhere
   * to go — the row is still being filled in and the record page is part of it. `forms/inlineRecordHost`
   * requires every non-route host to supply this, because without it the forms fall back to
   * `router.back()` and pop the stage the designer is standing in. Remounting by key is what
   * actually clears the boxes: the values live in React state and uncontrolled DOM inside the form,
   * so there is nothing here that could reset them one by one.
   *
   * IT IS ALSO WHERE THE BACK ARROW'S "Discard" LANDS, and that is the one thing said out loud here
   * rather than left to be inferred. The four forms call this from BOTH exits — the Cancel button
   * and the unsaved-changes dialog's Discard — and cannot tell a host which one it was. In a dialog
   * that reads correctly because the dialog visibly closes; here the only visible effect is the form
   * emptying, so a designer who pressed Back, was asked, and answered Discard would be left standing
   * on the same page with their typing gone and nothing saying why. The line below says why, and
   * says that Back still has to be pressed. GIVING THE TWO EXITS DIFFERENT CALLBACKS IS THE REAL
   * FIX AND IT BELONGS IN THE FOUR FORMS, which this wave does not own.
   */
  const handleCancel = useCallback(() => {
    remountForm();
    setNotice({
      tone: "done",
      text: `The ${noun} form has been cleared and nothing was saved. If you were leaving this stage or closing this row, do it again — nothing unsaved is holding you here now.`
    });
  }, [noun, remountForm]);

  /**
   * Has the designer asked for the form on an unlinked row? See {@link mountOnRequest}.
   *
   * KEPT WHEN THE ROW BECOMES LINKED and reset only when the gate itself goes away, so that a save
   * — which links the row and re-keys the form into edit mode — does not fold the form back up
   * underneath the designer who has just filled it in.
   */
  const [asked, setAsked] = useState(false);
  const mount = useCallback(() => setAsked(true), []);
  const mounted = !mountOnRequest || Boolean(linkedId) || asked;

  return (
    <div className="grid gap-4">
      {picker}

      {/*
        WHAT THE PAGE BELOW ACTUALLY IS, said before a designer starts typing into it.
        Without this line the embedded form reads as more stage fields, and the two things it does
        that stage fields do not — writing to the shared repository, and changing a record other
        workshops are also reading — would be discovered by their consequences. `INLINE_MODEL_NOUN`
        rather than a local map, so the word here is the same word the picker's own button uses.
      */}
      <div className="rounded-md border border-line-200 bg-surface-50 p-3">
        <p className="text-xs leading-5 text-ink-muted">
          {linkedId
            ? `This row is linked to an existing ${noun} record in the repository. The page below IS that record — what you change here changes the record itself, for every workshop that references it, and the boxes filled in from it are refreshed when you save.`
            : `Fill the ${noun} in here and it is saved to the repository and linked to this row. It is the same page as the ${noun}'s own, so nothing has to be typed twice.`}
        </p>
      </div>

      {/*
        KEYED ON THE LINKED RECORD AND ON THE FORM GENERATION — see {@link remountForm} for all three
        reasons a mount is replaced. Changing the picker's choice has to rebuild the form over the
        new record: `initial` is read into React state and uncontrolled DOM at mount and never
        re-read, so a form left standing would go on showing the previous artisan's boxes above the
        new artisan's id — the same fabrication the hydration rules exist to prevent, one level up.

        MOUNTED ON REQUEST ON AN UNLINKED COLLECTION ROW — see {@link mountOnRequest}.
      */}
      {mounted ? (
        <InlineRecordForm
          key={`${linkedId || "new"}:${formGeneration}`}
          model={model}
          recordId={linkedId || undefined}
          seed={seed}
          footerFields={workshopFields}
          onCreated={handleSaved}
          onCancel={handleCancel}
          onQueued={handleQueued}
          /*
            THE DUPLICATE PROMPT, WHICH IS A NAVIGATION WITHOUT THIS — see {@link handleUseExisting}.
            `Artisan` only, because it is the only one of the four models with an identity
            conflict; the prop is optional and the other three never call it.
          */
          onUseExisting={model === "Artisan" ? handleUseExisting : undefined}
        />
      ) : (
        <>
          <button type="button" className="field-button-secondary justify-self-start" onClick={mount}>
            Fill the {noun} in here
          </button>
          {/*
            THE WORKSHOP'S OWN QUESTIONS ARE STILL DRAWN, and that is not optional. They normally
            live in the form's footer slot, so gating the form would otherwise leave them rendered
            NOWHERE — and a registry field that is drawn nowhere loses its `data-dw-field` anchor
            (the workshop search and the readiness screen navigate to it), its per-field refusal, its
            provenance stamp, and its place in `strandedRefusals`' assumption that every field of a
            rendered entity is drawn. The last is the worst: the server would refuse a value and the
            page banner would announce a box nothing had drawn.

            Safe outside the `<form>` for the same reason they are safe inside it — nothing
            `FieldInput` draws carries a `name` or a `required`, so it belongs to no form either way.
            Placed BELOW the button, where the form's footer would have put them.
          */}
          {workshopFields}
        </>
      )}

      {/*
        ONE LIVE REGION, PRESENT FROM FIRST RENDER, whose TEXT changes.

        Assistive technology reliably announces a mutation inside a region that already existed and
        unreliably announces a region that arrives with its content — the rule this repository
        already wrote down for the toast viewport. Both messages here are ones a designer must not
        miss: "the record was saved but the boxes could not be filled in" is the whole warning, and
        an unspoken one is a row that quietly disagrees with the record it names.

        The tone decides the styling and never the meaning: every sentence says what happened in
        words. Amber is reserved for the sentences that ask for something to be done, because a
        designer told in amber that their row is filled goes looking for the problem — the same
        argument, and the same two tones, as `StageReferenceField`'s `PickerNotice`.
      */}
      <p role="status" className={liveRegionClass(busy, notice)}>
        {busy ? "Reading the record back so this row can be filled in from it…" : notice?.text ?? ""}
      </p>

      {mirroredFields}
    </div>
  );
}

/**
 * The disclosure the mirrored boxes sit behind.
 *
 * A SEPARATE CONTROL FROM "More detail" AND NOT A THIRD TIER. These fields are not advanced, they
 * are ANSWERED FROM SOMEWHERE ELSE, and that is a different thing to tell a designer: the count
 * beside "More detail" answers "is it worth opening"; this one answers "where did that value come
 * from". Merging them would put a hand-typed optional box and a copy of a repository record under
 * one heading.
 *
 * ── IT HIDES ITS CHILDREN AND DOES NOT UNMOUNT THEM, WHICH IS THE ONE WAY IT DIFFERS FROM
 *    `AdvancedDisclosure` ───────────────────────────────────────────────────────────────────────
 * That control drops its panel on collapse and compensates with a refusal pill and an auto-open,
 * because an ADVANCED field is behind it precisely so forty optional boxes are not mounted on a
 * handset. These boxes are a different case in both directions:
 *
 *  * THEY ARE THE ONLY COPY OF THE RECORD'S ANSWERS ON THIS ROW, and they are EDITABLE — hydration
 *    only ever fills a blank, so a designer correcting a hydrated village by hand is a supported and
 *    necessary act. Unmounted, everything that makes that correctable disappears with them: the
 *    `data-dw-field` anchor a workshop search navigates to, the per-field message the server
 *    returned, and the provenance stamp saying whose answer is on screen.
 *  * `strandedRefusals` CANNOT COVER THE GAP. It is handed no field list and no tiers, so it treats
 *    every key of a rendered entity as drawn — a refusal on an unmounted field would be counted as
 *    shown by the page banner and shown by nothing.
 *
 * The cost is bounded in a way `AdvancedDisclosure`'s is not: this only ever renders inside an OPEN
 * row (a collection's collapsed rows draw no fields at all), so it is one extra hidden grid per open
 * row rather than one per row in the list.
 *
 * `hidden` — the attribute and the utility class together, so it does not depend on preflight — is
 * `display: none`, which is what keeps these out of the Tab order and out of `focusableFields`'
 * `isVisible` test while the panel is shut. `aria-controls` is therefore unconditional here, unlike
 * on `AdvancedDisclosure`, because the element it names always exists.
 *
 * The refusal pill works exactly as it does there: a refused value is not announced by the page
 * banner and left invisible down here. `defaultOpen` does NOT — it is watched rather than read once,
 * because unlike an advanced group this one can hold a REQUIRED box, and because a workshop-search
 * result can arrive long after the row is open. See the effect for both.
 */
export function MirroredFieldsDisclosure({
  id,
  count,
  refused = 0,
  defaultOpen = false,
  children
}: {
  id: string;
  count: number;
  /** How many of these boxes the last save refused. See `AdvancedDisclosure` for the whole argument. */
  refused?: number;
  /**
   * "This should be open." Read on every render, not only the first — see the effect below for the
   * two things that make it true after mount and why neither could open this control before.
   */
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  // The none → some edge only, so a designer who closed this is not fought by it on every render.
  const refusedBefore = useRef(refused);
  useEffect(() => {
    const had = refusedBefore.current;
    refusedBefore.current = refused;
    if (refused > 0 && had === 0) setOpen(true);
  }, [refused]);
  /*
    AND THE SAME EDGE ON `defaultOpen`, WHICH `useState` READS EXACTLY ONCE.

    Two things arrive after this control has mounted and both have to be able to open it, and
    neither could:

     * A WORKSHOP SEARCH RESULT. `focus` comes from `useMemo(readStageFocus(searchParams))` and
       nothing re-keys the tree, so a `?find=` that lands while the row is already open changed
       `defaultOpen` and was never read again. Worse than a missed scroll: the panel is
       `display: none`, so `FieldCell`'s focus effect calls `scrollIntoView` on a hidden node, which
       is a no-op — the readiness jump lands silently on nothing.
     * A REQUIRED BOX WITH NOTHING IN IT. The mirrored group is not all optional detail the way
       `AdvancedDisclosure`'s is: on today's registry it holds `participant.name`, `tool.name` and
       `existingProduct.name`/`price`, all BASIC and required. The client-side "still needed" count
       is computed from the same data, so a collapsed panel means a pill pointing at boxes drawn
       nowhere. The host ORs that into `defaultOpen`.

    The edge and not the level, exactly as above: a designer who closes this is not fought on the
    next render.
  */
  const openBefore = useRef(defaultOpen);
  useEffect(() => {
    const had = openBefore.current;
    openBefore.current = defaultOpen;
    if (defaultOpen && !had) setOpen(true);
  }, [defaultOpen]);

  if (!count) return null;
  return (
    <div className="border-t border-line-200 pt-4">
      <button
        type="button"
        className="inline-flex items-center gap-2 text-sm font-medium text-purple-700 transition hover:text-purple-800"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((current) => !current)}
      >
        <ChevronDown className={`h-4 w-4 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
        Filled in from the linked record
        <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">{count}</span>
        {refused ? (
          <span className="rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-error-600">
            {refused} to fix
          </span>
        ) : null}
      </button>
      {/* HIDDEN, NEVER UNMOUNTED — see the header. The attribute and the class say the same thing so
          the rule does not depend on Tailwind's preflight surviving a config change. */}
      <div id={id} hidden={!open} className={open ? "mt-4 grid gap-3" : "hidden"}>
        {/* Said in words and not only by position: these boxes are EDITABLE, and a designer who
            assumes otherwise leaves a wrong village standing in a ministry report. */}
        <p className="text-xs leading-5 text-ink-muted">
          These are filled in from the linked record when it is chosen or saved, and only where they
          were blank. Correct any of them here — what you type is kept, and the record is not changed
          by it.
        </p>
        {children}
      </div>
    </div>
  );
}
