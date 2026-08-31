"use client";

/**
 * THE WORKSHOP'S OWN HEADER, CORRECTED — requirement 27's form.
 *
 * Everything else reachable from a design workshop edits a CHILD of it: the 22 stages, the
 * photographs, the tags, the AI layers, the workshop's own questions, the report. This edits the
 * workshop row itself — the one record in the family that, until this form existed, could be
 * written exactly once (at create) and never again for three of its columns.
 *
 * ── WHY THIS IS NOT `components/forms/DesignWorkshopForm.tsx` WITH AN `initial?:` PROP ───────────
 *
 * That IS the house shape — `ToolForm`, `ProductForm` and `ArtisanForm` each take an optional
 * `initial` and derive `const isEdit = Boolean(initial)` — and it is the right shape for those three
 * because their create and their edit are the same act against the same body. Here they are not, and
 * every one of the four differences is load-bearing rather than cosmetic:
 *
 *   1. **A BLANK BOX MEANS THE OPPOSITE THING.** On the create, an empty craft box means "not known
 *      yet" and the key is dropped; there is no stored value for it to fail to overwrite. Here an
 *      emptied craft box means CLEAR IT, and the server writes NULL. One component holding both
 *      readings of one box is one `if` away from clearing a column somebody merely left alone.
 *   2. **THE DESIGNER KEYS ARE REFUSED HERE, BY NAME.** `designerUserId`/`designerUserIds` are on
 *      the create body and are in the PATCH route's `_NEVER_PATCHABLE` table, so a body that merely
 *      CARRIES either key is 422'd whole. A shared component would render the picker and strip it,
 *      which is the shape that eventually ships with the strip removed.
 *   3. **THE CREATE WORKS OFFLINE AND THIS DOES NOT.** `createWorkshopOrKeepItHere` mints a local
 *      draft with no connection; there is no outbox arm for a header PATCH (see the save handler),
 *      so this act is online-only exactly as the status card on the record page is.
 *   4. **SIX OF THESE BOXES CARRY A WARNING THE CREATE FORM CANNOT.** Craft, cluster, state,
 *      district and the two dates are stage 1's columns; correcting one here and later saving stage
 *      1 with that box empty puts it back to NULL. On a create there is no stage 1 yet and nothing
 *      to warn about.
 *
 * Hoisting the create form out of `app/(protected)/design-workshops/page.tsx` so that the two share
 * a shell is still worth doing; it is a separate change with its own risk (the create path is pinned
 * by `e2e/design-workshop-create-idempotence-unit.spec.ts`) and it is not this one.
 *
 * ── THE ONE RULE THIS FILE EXISTS TO GET RIGHT: UNSET IS NOT NULL ───────────────────────────────
 *
 * `PATCH /design-workshops/{id}` reads its body with `model_dump(exclude_unset=True)`. So:
 *
 *   * a key that is **absent** leaves the stored value alone;
 *   * a key sent as **`null`** — or as `""`, or as whitespace — CLEARS the column to NULL;
 *   * `title`, `templateId` and `status` are NOT NULL columns and answer a clear with a 422 naming
 *     the field.
 *
 * An uncontrolled form reads `""` out of every box the user never touched, so **posting the form**
 * would blank the workshop's craft, cluster, place, dates, notes and link in one press, under a 200.
 * That is the single most likely defect in this change and the whole of {@link changedKeys} is the
 * defence: the body carries a key ONLY when the value on screen DIFFERS from the value the form was
 * seeded with.
 *
 * A DIFF AND NOT A DIRTY-FLAG, deliberately. The obvious alternative is to record which controls the
 * user touched and send those. It has a silent failure this one cannot have: a themed dropdown, a
 * date range and a multi-select are all `<button>`s that fire no native input event (SKILL §12.1), so
 * one missed hand-written `markDirty` means a change the designer made, watched go onto the screen,
 * and never sent — answered 200. Comparing values needs no control to remember to speak up. The
 * dirty flag is still kept, because the unsaved-changes guard is allowed to over-report and a missed
 * hook there costs a prompt rather than a correction.
 */

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { CappedListNotice } from "@/components/data/CappedListNotice";
// The pure half of this form: which keys the body carries, and why an untouched box carries
// none. Extracted so `e2e/design-workshop-header-diff-unit.spec.ts` can call it — a Node spec
// cannot import a `"use client"` module that pulls in React, `next/navigation` and IndexedDB.
import { changedKeys, EDITABLE_KEY_SET, type EditableKey } from "@/components/designworkshop/headerDiff";
// The other pure half, extracted for the same mechanical reason: which options the link picker
// offers and which of the four sentences it prints. `e2e/design-workshop-link-picker-unit.spec.ts`
// is what checks that a failed read stops claiming this account has no workshops.
import { linkedWorkshopView } from "@/components/designworkshop/linkedWorkshopPicker";
// The two strings the design workshop's own name is offered by. Imported rather than restated
// because stage 1's box and this one write the SAME column — see `TITLE_MAX_LENGTH` below.
import {
  WORKSHOP_NAME_REASSURANCE,
  workshopNameCreateLabel
} from "@/components/designworkshop/StageWorkshopNameField";
import { DateRangePicker, fromIsoDate, toIsoDate } from "@/components/forms/DateTimeField";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { ApiError, listResource } from "@/lib/api";
import {
  listDesignWorkshops,
  listReportTemplates,
  patchDesignWorkshop,
  type DwSummary,
  type DwTemplate,
  type DwUpdateBody
} from "@/lib/designWorkshops";
import { adoptServerSummaries } from "@/lib/designWorkshopStore";
import { useUnsavedChanges } from "@/lib/forms";
import { isUnreachable } from "@/lib/offline";
import type { Workshop } from "@/lib/types";
import {
  NO_FIELD_WORKSHOP,
  WORKSHOP_OPTION_PAGE_SIZE,
  deviceLooksOffline,
  workshopEmptyLabel,
  workshopListNotice,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

/**
 * How long a keystroke in the link picker's box waits before it becomes a request.
 *
 * The same 300 ms every server-backed box in this app uses (`WorkshopSelect`, `/design-review`,
 * `DesignWorkshopViewersPanel`, `StageReferenceField`), because a designer who has learnt how fast
 * one of them answers is entitled to the same from the rest. It is duplicated here rather than
 * imported from `components/forms/WorkshopSelect` because that module is a `"use client"` component
 * carrying a submission pre-flight and a late-submission dialog, neither of which belongs on a
 * design workshop's header — importing it for one number would drag both in.
 */
const LINK_SEARCH_DEBOUNCE_MS = 300;

/**
 * The longest title this form will send. The number the box has always carried.
 *
 * ENFORCED IN CODE NOW RATHER THAN BY THE BROWSER, and that is the one thing the title box LOST by
 * becoming a themed control: `maxLength` is an attribute of an `<input>` and a dropdown trigger is a
 * `<button>`. So the create row's term is measured here instead, and an over-long one is refused
 * with a sentence under the box rather than truncated — silently keeping the first 220 characters of
 * a name somebody typed is the "saved, and it is not what you wrote" failure this repository refuses
 * everywhere else.
 */
const TITLE_MAX_LENGTH = 220;

/* ────────────────────────────────────────────────────────────────────────────
 * What this form may write, and what it may only show
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The six columns `workshop_summary` hands this form and `DesignWorkshopPatch` refuses by name.
 *
 * SHOWN, NOT HIDDEN. They are the workshop's cover — the strings a ministry reads off the front page
 * of the report — so a "workshop details" screen that omitted them would look like a screen where
 * they do not exist, and the designer would go looking for them in the one place they are not: this
 * form. Each carries the sentence the server would have answered with, so the reader learns where
 * the value lives rather than that it cannot be changed.
 *
 * The wording is the short form of `_NEVER_PATCHABLE`'s in
 * `backend/app/api/routes/design_workshops.py`; the long form is in that table, and if the two ever
 * disagree the server's is the true one.
 */
const READ_ONLY_COVER: { key: keyof DwSummary; label: string; why: string }[] = [
  {
    key: "workshopCode",
    label: "Workshop ID",
    why: "prints on the report cover and is what a scanned card resolves to"
  },
  { key: "venue", label: "Venue", why: "stage 1 is the only thing that collects it" },
  { key: "scheme", label: "Scheme", why: "stage 1 is the only thing that collects it" },
  {
    key: "designerName",
    label: "Designer",
    why: "the authorship line the cover, the certification block and the .docx itself print"
  },
  { key: "implementingAgency", label: "Implementing agency", why: "stage 1 is the only thing that collects it" },
  { key: "sponsor", label: "Sponsor", why: "stage 1 is the only thing that collects it" }
];

/* ────────────────────────────────────────────────────────────────────────────
 * Reading a refusal back onto the box that caused it
 * ──────────────────────────────────────────────────────────────────────────── */

/** A refusal, and the field it belongs against — `null` when it belongs to the whole form. */
type Refusal = { field: EditableKey | null; message: string };

/**
 * `detail` IS A LIST FOR ANYTHING PYDANTIC REFUSED AND A STRING FOR ANYTHING THE HANDLER REFUSED,
 * and both shapes have to land on the right box.
 *
 * `apiFetch` has already run the body through `describeApiDetail`, so `ApiError.message` is a
 * readable sentence rather than "[object Object]" — but it is the sentence for the WHOLE body, and a
 * 422 about the title printed at the foot of a ten-box form is a message the reader has to go and
 * match to a box themselves. `ApiError.payload` still holds the original, so this reads the field
 * out of it and the caller puts the sentence under that box.
 *
 * TWO SHAPES, AND THE SECOND ONE IS NOT A GUESS. Pydantic's entries carry `loc: ["body", "<field>"]`
 * — the field is read from there and accepted only when it is one of the ten this form can write.
 * The handler's own 422s are plain strings that BEGIN with the column's name, because they are built
 * as `f"{key} cannot be emptied…"` and `f"{key} is not a date this server can read…"`; the leading
 * word is matched against the same ten. Anything else — the immutable-field refusal, which is
 * `loc: ["body"]`, or "No workshop record exists with that id" — has no single owning box and goes
 * to the banner, which is the honest place for a message about the request rather than about a
 * field.
 */
function readRefusal(err: unknown): Refusal {
  const fallback = err instanceof Error && err.message.trim() ? err.message : "";
  if (!(err instanceof ApiError)) return { field: null, message: fallback };
  const detail = (err.payload as { detail?: unknown } | null | undefined)?.detail;

  if (Array.isArray(detail)) {
    for (const entry of detail) {
      if (!entry || typeof entry !== "object") continue;
      const record = entry as { msg?: unknown; loc?: unknown };
      const named = Array.isArray(record.loc)
        ? record.loc.filter((part): part is string => typeof part === "string").at(-1)
        : undefined;
      if (!named || !EDITABLE_KEY_SET.has(named)) continue;
      // Pydantic prefixes every custom-validator message with "Value error, "; the designer needs
      // the sentence after it. Same trim `describeApiDetail` performs, for the same reason.
      const message = String(record.msg ?? "").replace(/^Value error,\s*/, "").trim();
      if (message) return { field: named as EditableKey, message };
    }
    return { field: null, message: fallback };
  }

  if (typeof detail === "string" && detail.trim()) {
    const leading = /^([A-Za-z]+)\s/.exec(detail.trim())?.[1];
    if (leading && EDITABLE_KEY_SET.has(leading)) {
      return { field: leading as EditableKey, message: detail.trim() };
    }
    return { field: null, message: detail.trim() };
  }

  return { field: null, message: fallback };
}

/**
 * THE THREE WAYS A SAVE FAILS, EACH ANSWERED WITH THE NEXT MOVE RATHER THAN WITH THE STATUS CODE.
 *
 * The server's own sentences are correct and are shown verbatim where they exist; what they cannot
 * carry is what the reader should DO, which differs per code and is the whole reason this exists:
 *
 *   * **403** — the account's ROLE is outside `{DESIGNER, ADMIN, MASTER_ADMIN}`. Nothing the reader
 *     can fix from this screen, so the sentence names who changes it.
 *   * **404** — either no such workshop or one this account holds no grant on, and
 *     `load_workshop_or_404` will not say which, deliberately: a 403 there would confirm the id
 *     exists to exactly the people it is turning away. So the remedy has to cover both, and "ask to
 *     be added as a viewer" covers both.
 *   * **409** — soft-deleted. This must NOT be swallowed into a generic failure: it is the one
 *     refusal with a one-click fix by somebody the designer can name, and the server's sentence
 *     already says it.
 */
function describeSaveRefusal(err: unknown, serverSaid: string): string {
  const status = err instanceof ApiError ? err.status : 0;
  if (status === 403) {
    return (
      `${serverSaid || "This account may not edit a design workshop."} Editing a workshop's details ` +
      "needs Designer access or above, which is set on your account rather than on this workshop — " +
      "an administrator is who changes it. Nothing was sent and nothing was changed."
    );
  }
  if (status === 404) {
    return (
      "This workshop is no longer open to this account, so nothing was changed. Either it has been " +
      "removed, or the access that let you open it has been withdrawn — the repository will not say " +
      "which, on purpose. Ask an administrator, or the designer who shared it with you, to add you " +
      "as a viewer of the workshop again."
    );
  }
  if (status === 409) {
    return (
      // The place is NAMED, and it is named after the heading that is actually on that screen:
      // `DeletedWorkshopsCard` renders "Deleted workshops" on `/admin`. A remedy that sends somebody
      // to a screen spelled differently from the one they are looking at is a remedy they conclude
      // does not exist — and this refusal's whole value is that it has a one-click fix by a person
      // the designer can go and ask by name.
      `${serverSaid || "This workshop is deleted. Restore it before editing."} An administrator can ` +
      "restore it from Deleted workshops on the admin page; everything you have typed here is still " +
      "on screen and can be saved once they have."
    );
  }
  return serverSaid
    ? `The workshop was not changed: ${serverSaid}`
    : "The workshop was not changed, and the repository did not say why.";
}

/* ────────────────────────────────────────────────────────────────────────────
 * The form
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * A plain text box with its refusal underneath — and the reason EVERY writable box on this form has
 * one of these, including the five whose refusal "cannot happen".
 *
 * {@link readRefusal} attributes a 422 to any of the ten keys this form can write. If a key it
 * attributes to has nowhere to draw the sentence, the save fails and the screen shows NOTHING: no
 * banner (the message went to `fieldProblem` instead), no navigation, no field message — a Save that
 * silently does nothing, which is the single worst outcome this form has and strictly worse than
 * the generic banner it bypassed. Craft, cluster, state, district and notes had exactly that hole:
 * their `maxLength` makes a length refusal unreachable THROUGH THIS BROWSER, so the missing slot was
 * invisible and would have stayed invisible until the server's bound and the input's disagreed —
 * which is one edit to either side, in a repository that ships the bundle and the API separately.
 * A rendering slot per writable key means the attribution can never outrun the rendering.
 *
 * A COMPONENT AND NOT FIVE COPIES because the error wiring is the part that must not drift: the
 * `aria-describedby`, the `aria-invalid` and the `role="alert"` have to agree with the id the caller
 * passes, and five hand-written copies is five chances for one of them to point at nothing. The
 * wrapper `<div>` is the same one the title box carries and for the same reason — `Field` is a
 * wrapping `<label>`, so a refusal placed inside it joins the input's accessible NAME.
 */
function WritableTextField({
  label,
  name,
  maxLength,
  defaultValue,
  error,
  errorId,
  multiline
}: {
  label: string;
  name: EditableKey;
  maxLength: number;
  defaultValue: string;
  error?: string;
  errorId: string;
  multiline?: boolean;
}) {
  const described = error ? errorId : undefined;
  const invalid = error ? true : undefined;
  return (
    <div className="grid min-w-0 gap-1">
      <Field label={label}>
        {multiline ? (
          <TextArea
            name={name}
            maxLength={maxLength}
            defaultValue={defaultValue}
            aria-invalid={invalid}
            aria-describedby={described}
          />
        ) : (
          <TextInput
            name={name}
            maxLength={maxLength}
            defaultValue={defaultValue}
            aria-invalid={invalid}
            aria-describedby={described}
          />
        )}
      </Field>
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function DesignWorkshopHeaderForm({ initial }: { initial: DwSummary }) {
  const router = useRouter();
  const baseId = useId();
  const formRef = useRef<HTMLFormElement | null>(null);

  const [saving, setSaving] = useState(false);
  /** A refusal that belongs to the whole request rather than to one box. See {@link readRefusal}. */
  const [problem, setProblem] = useState<string | null>(null);
  /** A refusal that belongs to ONE box, printed under it and pointed at by `aria-describedby`. */
  const [fieldProblem, setFieldProblem] = useState<Partial<Record<EditableKey, string>>>({});
  /** "You pressed Save and there was nothing to send" — see the submit handler. */
  const [nothingToSend, setNothingToSend] = useState(false);

  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  /**
   * THE WORKSHOP'S OWN NAME, IN REACT STATE RATHER THAN IN THE FORM — and that move is the whole of
   * the risk this box carried, so it is worth being explicit about what holds it shut.
   *
   * The previous lane left this box a plain `<input name="title">` on purpose and said why:
   * *"converting it means a themed control inside `changedKeys`' value diff on a PATCH that can
   * clear NOT NULL columns."* That hazard is real and is answered by three things rather than by
   * hope:
   *
   *   1. **AN UNTOUCHED TITLE IS STILL ABSENT FROM THE BODY.** This state is SEEDED from
   *      `initial.title`, and `changedKeys` compares `title.trim()` against
   *      `storedText(initial.title)` — which is the same trim. Untouched means equal means the key
   *      is not in `changed` means `body.title` is never assigned means `JSON.stringify` drops it
   *      means `exclude_unset` leaves the column alone. The diff never learns that this control
   *      changed shape, which is exactly the property that makes a diff safer than a dirty flag.
   *   2. **A CLEARED TITLE CANNOT REACH THE COLUMN.** It cannot be cleared at all: there is no
   *      `noneLabel` on the picker, so no row carries `value: ""`, and `workshopNameCreateLabel` is
   *      only offered for a non-empty term. The blank guard in `submit` is kept anyway — see it for
   *      why a refusal that "cannot fire" is still the right thing to have.
   *   3. **IT ARMS THE GUARD BY HAND.** SKILL §12.1: a themed dropdown is a `<button>` and fires no
   *      native input event, so the form's `onInput` cannot see it. `commitTitle` calls `noteEdit`.
   *
   * WHAT IS DIFFERENT FROM THE TEMPLATE PICKER BESIDE IT: nothing. `templateId` has been React state
   * on this form since it was written, for the same reason and read by the same `onScreenValues`.
   */
  const [title, setTitle] = useState(initial.title ?? "");
  /**
   * WHAT THE NAME LIST'S READ ANSWERED — three states, for the reason `linkList` below is three.
   *
   * This one has never been anything else: the box is new, so there was no `[]` to migrate off. It
   * is written as a `WorkshopListState` rather than as rows-plus-a-flag so that the four sentences
   * come from `workshopListNotice` and cannot be spelled a fifth way on this screen.
   */
  const [nameList, setNameList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  /** The name panel's own filter box, wired to the server. `namePending` covers the debounce too. */
  const [nameTerm, setNameTerm] = useState("");
  const [nameApplied, setNameApplied] = useState("");
  const [namePending, setNamePending] = useState(true);
  const [templateId, setTemplateId] = useState(initial.templateId);
  const [workshopId, setWorkshopId] = useState(initial.workshopId ?? "");
  /**
   * WHAT THE LINK PICKER'S READ ANSWERED — three states, and the middle one is why this is not a
   * bare `Workshop[]` any more.
   *
   * It WAS `useState<Workshop[]>([])` with a `.catch` that touched nothing, and every sentence and
   * every option downstream branched on `linkable.length`. So a 500, a timeout and a dead
   * connection all rendered *"No design & prototype workshops are open to this account"* — a
   * confident claim about a grant table produced by a request that never arrived, on the one screen
   * whose whole subject is that link. `lib/workshopOptions.ts` exists for this and its own header
   * calls it the most repeated bug class in this repository; `{ kind: "loading" }` is the honest
   * initial value and `{ kind: "failed" }` is what the catch arm now sets.
   */
  const [linkList, setLinkList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  /** The panel's own filter box. `linkTerm` is what is typed; `linkApplied` is what the answer is about. */
  const [linkTerm, setLinkTerm] = useState("");
  const [linkApplied, setLinkApplied] = useState("");
  /**
   * A read is outstanding — INCLUDING while the debounce timer is still counting.
   *
   * If it only covered the request, the third of a second between the last keystroke and the fetch
   * would be spent drawing the PREVIOUS term's rows with no filter applied to them (the local pass
   * is bypassed under `serverQuery`), i.e. a list that looks like an answer and is not one. Same
   * flag, same reason, as `useWorkshopSelection`'s.
   */
  const [linkPending, setLinkPending] = useState(true);
  /**
   * THE STORED WORKSHOP, ONCE SEEN, IS NOT FORGOTTEN THE MOMENT THE READER TYPES.
   *
   * With the box wired to the server, the options ARE the answer to the term — so typing three
   * letters that do not match this record's own workshop drops it out of the list. Without this the
   * row would fall through to the by-id recovery below, and for the couple of hundred milliseconds
   * that request is in flight the picker would relabel the record's own link "its details are not
   * open to this account", which is a false claim about access made while somebody is merely
   * hunting. `useWorkshopSelection` keeps a `known` union for the same reason and states it: a
   * researcher typing three letters must not make the workshop already on the record disappear.
   */
  const [seenLink, setSeenLink] = useState<Workshop | null>(null);
  /**
   * The workshop's duration, held here rather than read off the DOM, for the reason the create form
   * gives: a range is a pair with a rule between its halves, so the picker has to see the current
   * start to refuse an earlier end. Seeded from the stored dates through `fromIsoDate`, which builds
   * a LOCAL midnight — `new Date("2026-07-20")` is UTC midnight and reads as the previous day west
   * of Greenwich, which on an edit form would move a stored date by a day merely by opening the page.
   */
  const [duration, setDuration] = useState<{ from?: Date; to?: Date }>(() => ({
    from: fromIsoDate(initial.startDate),
    to: fromIsoDate(initial.endDate)
  }));

  const { dirty, markDirty, resetDirty } = useUnsavedChanges();

  /**
   * EVERY EDIT, WHEREVER IT CAME FROM — the dirty flag AND the retirement of the last save's answer.
   *
   * A REFUSAL IS ABOUT A BODY THAT WAS SENT, so it stops being true the moment the boxes stop being
   * the boxes that were sent. Left standing, each of the three lies in a different way and all three
   * are reachable in two keystrokes:
   *
   *   * `nothingToSend` says "nothing on this form differs from what is stored" — printed for a Save
   *     with an empty diff, and still on screen underneath a craft the designer has since typed. It
   *     is the one banner on this form that makes a CLAIM ABOUT THE DIFF, so it is also the one whose
   *     staleness would be read as the diff being broken, which is the exact defect the banner was
   *     added to surface. It must not outlive the diff it described.
   *   * `fieldProblem` is a red sentence under one box, pointed at by `aria-describedby` and
   *     announced with `role="alert"`, saying that box's value was refused. The title box already
   *     clears its native `setCustomValidity` on input for precisely this reason; leaving the React
   *     half behind would clear the browser's copy of a refusal and keep the app's.
   *   * `problem` is the whole-request banner — a 403, a 404, a 409, a 422 with no owning box.
   *
   * ONLY WHEN THERE IS SOMETHING TO CLEAR, and that guard is not tidiness. This runs on the form's
   * `onInput`, i.e. once per keystroke across ten boxes: `setProblem(null)` on an already-null state
   * is a bail-out React charges nothing for, but `setFieldProblem({})` builds a NEW object every
   * time, which React cannot compare away — so an unguarded reset would re-render the whole form,
   * its two searchable dropdowns and its calendar on every character typed into the notes box.
   *
   * NOT `saving`, and not the leave prompt: those describe an act in flight rather than an answer
   * about values, and the save handler resets them itself.
   */
  function noteEdit() {
    markDirty();
    if (nothingToSend) setNothingToSend(false);
    if (problem !== null) setProblem(null);
    // `Object.keys` and not truthiness: `{}` is truthy, so the object's mere existence says nothing
    // about whether a box is carrying a refusal.
    if (Object.keys(fieldProblem).length) setFieldProblem({});
  }
  const [promptOpen, setPromptOpen] = useState(false);
  /**
   * WHICH EXIT IS WAITING ON THE PROMPT — this form's own Cancel button, or the round back control
   * in `PageHeader`. `ToolForm` carries the same flag and its header carries the defect that made it
   * necessary: "Discard" that only empties the form answers the back arrow by throwing the work away
   * and NOT leaving, so the reader presses Back a second time to do the thing they already asked
   * for. The arrow's route in is `useLeaveGuard`, which is registered once for the life of the mount
   * and has no per-press hook to set a flag from, so the flag marks the button instead and is
   * cleared on every way out of the prompt.
   */
  const [promptFromCancel, setPromptFromCancel] = useState(false);
  const { completeLeave, abandonLeave } = useLeaveGuard(dirty, () => setPromptOpen(true));

  const recordHref = `/design-workshops/${initial.id}`;
  const stageOneHref = `${recordHref}/stages/WORKSHOP_SETUP`;

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await listReportTemplates();
        if (!cancelled) setTemplates(list);
      } catch {
        // The picker degrades to the one option below — the template this workshop already carries —
        // and the rest of the form still saves. An error banner for a list that failed would read as
        // the form itself being broken, which is the create form's rule for the same request.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const trimmed = linkTerm.trim();
    // Announced BEFORE the timer, not inside it — see `linkPending`.
    setLinkPending(true);
    const timer = window.setTimeout(() => {
      /*
        `accessibleOnly` and `workshopType` — the same narrowing the create form applies, and for the
        same reason: this picker LINKS the workshop, so an option this account cannot file against is
        an option that points the record at somebody else's roster. The stored link is added back
        below whatever this list turns out to hold; see `offPageWorkshop`.

        `search` IS THE PANEL'S OWN BOX, AND-ed with both narrowings on the server rather than OR-ed
        (`list_workshops` appends the scope to the same `AND` list for exactly this reason, so typing
        cannot reopen a workshop this account may not file against). Until this pass the box filtered
        the page already fetched, so a designer typing the title of a marked workshop that sits past
        the cut was answered "No matches" about a workshop that exists — and the next thing a person
        does after "no matches" is pick something else, which on THIS control re-points a stored link
        and strands every record the workshop-scoped stage pickers filed under the old one.

        `pageSize` is `WORKSHOP_OPTION_PAGE_SIZE` and not the round `100` this used to ask for: it is
        `RENDER_CAP` under another name, and asking for more than `SearchableSelect` will ever draw is
        how a picker ends up with two disagreeing cap sentences — the panel silently trimming at 80
        while the sentence beside it still speaks of a hundred.
      */
      listResource<Workshop>("/workshops", {
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        workshopType: "DESIGN_PROTOTYPE",
        accessibleOnly: "true",
        search: trimmed || undefined
      })
        .then((result) => {
          if (cancelled) return;
          // NOT sorted here. `fieldWorkshopOptions` owns the order (occurrence newest-first, then
          // title, then id) so this control cannot disagree with the other twenty about it.
          setLinkList({ kind: "ok", rows: result.items ?? [], total: result.total });
          setLinkApplied(trimmed);
        })
        .catch(() => {
          /*
            THE CATCH ARM IS THE FIX. It used to be empty, and an empty catch over a list held as
            `[]` is what turned every failure into the sentence "no design & prototype workshops are
            open to this account" — a claim about a grant table produced by a request that never
            arrived. A failed read is a different fact from an empty answer and gets a different
            sentence (`workshopListNotice`); saying so is the whole of this state's job.
          */
          if (!cancelled) setLinkList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setLinkPending(false);
        });
      // Skipped when the box is CLEARED: an empty box is the unnarrowed list, the one request that
      // cannot be superseded by the next letter, and making the way back to the full list wait a
      // third of a second is what teaches somebody that clearing it does nothing.
    }, trimmed ? LINK_SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
    /*
      ONE TIMER, AND IT IS THIS EFFECT'S, so a late answer to a term the reader has typed past is
      discarded by `cancelled` rather than by a generation counter — `apiFetch` carries no
      `AbortSignal`, and this is the arrangement `useWorkshopSelection` and `/design-review` already
      use for the same reason.
    */
  }, [linkTerm]);

  /*
    THE NAMES ALREADY ON RECORD, for the title box. One request, one debounce, one shape — the link
    picker's above, deliberately, because two server-backed boxes on one form that respond at
    visibly different speeds read as one of them being broken.

    A DIFFERENT LIST FROM THE ONE ABOVE, AND THAT IS THE POINT OF THE TWO BOXES BEING TWO. This is
    `GET /design-workshops` — the 22-stage records, whose TITLES are what a designer is naming this
    workshop consistently with. The picker below it is `GET /workshops` — the field/training
    workshop this record is FILED AGAINST. Two tables, two scopes, two access systems, as
    `forms/DesignWorkshopSelect.tsx` sets out at length.
  */
  useEffect(() => {
    let cancelled = false;
    const trimmed = nameTerm.trim();
    // Announced BEFORE the timer, for the reason `linkPending` gives: the third of a second between
    // the last keystroke and the fetch would otherwise draw the PREVIOUS term's rows with no filter
    // over them, which is a list that looks like an answer and is not one.
    setNamePending(true);
    const timer = window.setTimeout(() => {
      listDesignWorkshops({
        page: 1,
        // `RENDER_CAP` under another name — one number for the fetch and the render, so two
        // truncation sentences with two different totals cannot both be true at once.
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        search: trimmed || undefined
      })
        .then((page) => {
          if (cancelled) return;
          setNameList({ kind: "ok", rows: page.items ?? [], total: page.total });
          setNameApplied(trimmed);
        })
        .catch(() => {
          // A FAILED READ IS NOT AN EMPTY ANSWER. Holding it as `[]` is what turns a dropped
          // connection into a claim that this account is on no design workshop. Nothing else
          // changes: the control stays usable, because typing was always the answer here.
          if (!cancelled) setNameList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setNamePending(false);
        });
      // Clearing the box does NOT wait: an empty term is the unnarrowed list, the one request that
      // is always about to be wanted and never about to be superseded by the next letter.
    }, trimmed ? LINK_SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [nameTerm]);

  /**
   * The title rows: this workshop's own name FIRST and always, then the distinct names on record.
   *
   * A PICKER THAT CANNOT DRAW ITS OWN CURRENT VALUE READS AS BLANK. With the box wired to the
   * server the options ARE the answer to the term, so typing three letters that do not match this
   * workshop's name would drop it out of the list — and the obvious repair for a blank title box is
   * to answer it again, which on this form is a PATCH that renames the workshop. Same rule, same
   * reason, as `seenLink` above and as `useRecordOffPage` on the pickers that hold an id.
   *
   * DEDUPLICATED because only the NAME is offered: two workshops may legitimately share one, and a
   * row drawn twice is a control that appears to distinguish two answers it cannot. The server's
   * order is kept — `GET /design-workshops` answers newest first, which is the workshop a designer
   * naming one today almost always means.
   */
  const titleOptions = useMemo(() => {
    const rows = nameList.kind === "ok" ? nameList.rows : [];
    const current = title.trim();
    const seen = new Set<string>();
    const options: { value: string; label: string; hint?: string }[] = [];
    if (current) {
      seen.add(current);
      options.push({ value: current, label: current, hint: "the name on this workshop now" });
    }
    for (const row of rows) {
      const name = row.title?.trim();
      // An over-long name is not offered: `_header_patch_data` would refuse it, so offering it would
      // offer an option that turns the box into a refused save.
      if (!name || name.length > TITLE_MAX_LENGTH || seen.has(name)) continue;
      seen.add(name);
      options.push({ value: name, label: name });
    }
    return options;
  }, [nameList, title]);

  /**
   * WHY THIS SENTENCE IS SUPPRESSED WHILE A TERM IS APPLIED, and only the genuinely-empty one.
   *
   * With the box wired to the server, an `ok` answer with no rows means one of two completely
   * different things: nothing at all is open to this account, or the letters just typed matched
   * nothing. `workshopListNotice` cannot see the term and answers the first, so a designer hunting
   * for "Sambalpuri" would be told no design workshops are open to them — a false claim about a
   * grant table produced by a search. The panel already says the true thing in that case. A FAILED
   * read still speaks with a term applied, because that fact is about the connection and is true
   * whatever was typed.
   */
  const nameVoice: WorkshopListVoice = {
    table: "design",
    // The value here is a NAME, not a grant-bearing reference, so R6's reason for never keeping the
    // list on the device is not this control's reason. `StageWorkshopNameField` carries the ruling.
    accessList: false,
    scoped: true,
    reassurance: WORKSHOP_NAME_REASSURANCE,
    online: !deviceLooksOffline()
  };
  const nameSearchedToNothing =
    nameList.kind === "ok" && nameList.rows.length === 0 && nameApplied.length > 0;
  const nameNotice = nameSearchedToNothing ? "" : workshopListNotice(nameList, nameVoice);

  /**
   * The one door the title is written through — a picked row, or a term committed from the box.
   *
   * `noteEdit` FIRST, because a themed control fires no native input event and the form's `onInput`
   * therefore never sees this (SKILL §12.1); it also retires the last save's answer, which is the
   * reason it is one function rather than a bare `markDirty`.
   *
   * THE LENGTH IS REFUSED, NOT TRIMMED. `maxLength` was an attribute of the `<input>` this box used
   * to be and a dropdown trigger is a `<button>`, so the bound has to be applied here — and applied
   * as a refusal, because silently keeping the first 220 characters of a name somebody typed stores
   * something they did not write on the field the report cover reads. The refusal is set AFTER
   * `noteEdit`, which clears the previous one.
   */
  function commitTitle(next: string) {
    noteEdit();
    if (next.trim().length > TITLE_MAX_LENGTH) {
      setFieldProblem((current) => ({
        ...current,
        title: `That name is ${next.trim().length} characters and the longest this field stores is ${TITLE_MAX_LENGTH}. Shorten it and try again.`
      }));
      return;
    }
    setTitle(next);
  }

  /** The rows of the current answer, with a stable identity so the by-id recovery below settles. */
  const linkRows = useMemo<Workshop[]>(() => (linkList.kind === "ok" ? [...linkList.rows] : []), [linkList]);

  // Remembers this record's own workshop the first time an answer carries it. See `seenLink`.
  useEffect(() => {
    const stored = initial.workshopId ?? "";
    if (!stored) return;
    const found = linkRows.find((row) => row.id === stored);
    if (found) setSeenLink(found);
  }, [linkRows, initial.workshopId]);

  /**
   * THE WORKSHOP THIS RECORD IS ALREADY LINKED TO, whatever page of the list it is on.
   *
   * `ToolForm` carries the shipped bug this prevents, word for word: a picker holding one page of a
   * hundred rows cannot draw a value filed last season, so the control read "not linked" beside a
   * link that was perfectly intact — and the obvious repair for a box that looks unlinked is to pick
   * something, which is the single action that really does rewrite the link. Here it would also
   * orphan every record already filed under the old one. Same hook, called the same way; do not
   * write a variant of it.
   */
  const offPageWorkshop = useRecordOffPage<Workshop>("/workshops", initial.workshopId ?? "", linkRows);

  /**
   * The picker, decided — options, the four state sentences, the cut sentence, and whether the
   * control stands down. Every one of those judgements is in
   * `components/designworkshop/linkedWorkshopPicker.ts` so that a Node spec can execute it; this
   * component's job is to hold the answer and draw it.
   *
   * `offPageWorkshop ?? seenLink` and not the other way round: the by-id read is the fresher of the
   * two and is the one that can reach a workshop outside the list's scope at all.
   */
  const link = useMemo(
    () =>
      linkedWorkshopView({
        list: linkList,
        searchApplied: linkApplied,
        term: linkTerm,
        pending: linkPending,
        storedId: initial.workshopId ?? "",
        storedRow: offPageWorkshop ?? seenLink,
        online: !deviceLooksOffline()
      }),
    [linkList, linkApplied, linkTerm, linkPending, initial.workshopId, offPageWorkshop, seenLink]
  );

  /**
   * RE-POINTING A LINK ORPHANS THE RECORDS ALREADY FILED UNDER THE OLD ONE, and the warning appears
   * only while that is actually about to happen.
   *
   * Five of the registry's REF fields are WORKSHOP-scoped — `existingProduct.artisanRef` and
   * `.productRef`, `prototype.productRef`, `processStep.processRef`, `traditionalProcess.processRef`
   * — and the server narrows each of them with `spec.workshop_where(record.workshopId)` on the
   * LINKED workshop (see `components/designworkshop/LinkedWorkshop.tsx`). So a record created from
   * one of those pickers under the old link is a record those pickers can never show again once the
   * link moves. That is a legitimate thing to do and a terrible thing to do by accident, which is
   * why it is a sentence on the screen and not a refusal.
   *
   * Only when a link is being MOVED or REMOVED, never when one is being added: a workshop that had
   * no link has no scoped records to strand.
   */
  const repointsLink = Boolean(initial.workshopId) && workshopId !== (initial.workshopId ?? "");

  /** Every control's value as a trimmed string — the left-hand side of {@link changedKeys}. */
  function onScreenValues(form: FormData): Record<EditableKey, string> {
    const box = (key: EditableKey) => String(form.get(key) ?? "").trim();
    return {
      // React state, not a form control: a themed dropdown is a `<button>` and submits nothing. Two
      // of these now, and the `box` helper below is what the other eight still read through.
      title: title.trim(),
      templateId: templateId.trim(),
      craftName: box("craftName"),
      clusterName: box("clusterName"),
      state: box("state"),
      district: box("district"),
      // The two hidden inputs the date range writes, in the same `yyyy-mm-dd` the column stores.
      startDate: box("startDate"),
      endDate: box("endDate"),
      workshopId: workshopId.trim(),
      notes: box("notes")
    };
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // React nulls `event.currentTarget` across an await, so the FormData must be built before any
    // async work — not after the first `await`, where it reads as null and every box is empty.
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const onScreen = onScreenValues(form);

    setProblem(null);
    setFieldProblem({});
    setNothingToSend(false);

    /*
      AN EMPTY OR WHITESPACE TITLE IS REFUSED HERE RATHER THAN ON THE WIRE, against the box.

      `min_length=1` on the server catches `""` and not `"   "` — so whitespace reaches
      `_header_patch_data`, which strips it, finds the column is NOT NULL and answers a 422 the
      reader has to match to a box themselves. A workshop titled with three spaces would render as a
      blank heading on every screen that lists it and could then only be found by its id.

      ── IT CANNOT FIRE TODAY, AND IT STAYS ─────────────────────────────────────────────────────

      The box is a creatable combo now, and neither of its two doors can produce a blank: the picker
      carries no `noneLabel`, so no row holds `value: ""`, and `SelectCreateAction` is offered only
      for a non-empty term. `title` is also seeded from a NOT NULL column. So this is a guard over a
      state that has no route to it — which is exactly why it must not be deleted: what it defends is
      a NOT NULL column on a PATCH whose whole contract is that `""` means CLEAR, and the cost of
      being wrong once about "that cannot happen" is a 422 the designer cannot place, or worse.

      ── AND IT IS A RENDERED REFUSAL NOW, NOT `reportValidity` ─────────────────────────────────

      It used to reach `formElement.elements.namedItem("title")` and call `setCustomValidity` +
      `reportValidity` on it. Constraint validation is a property of form CONTROLS, and a themed
      dropdown is a `<button>` that submits nothing and validates nothing — so that route is simply
      gone. The message goes to `fieldProblem.title`, which is treatment 1 of §12.11 rendered as
      treatment 2: a `role="alert"` sentence under the box, pointed at by `aria-describedby`, in the
      same slot a 422 naming this field would land in. One place, one shape, whichever end refused.
    */
    if (!onScreen.title) {
      setFieldProblem((current) => ({
        ...current,
        title:
          "Every workshop has a title — it is the heading every list, search result and report cover shows it by."
      }));
      return;
    }

    const changed = changedKeys(onScreen, initial);
    if (!changed.length) {
      /*
        NOTHING DIFFERS, SO NOTHING IS SENT — AND THE SCREEN SAYS SO INSTEAD OF LEAVING QUIETLY.

        The server answers `{}` with a 200 and the unchanged summary, so a round trip would be
        harmless; the reason for not making it is what a silent navigation would hide. This form's
        one real defect class is a wrong diff, and a wrong diff shows up as "I changed the cluster,
        pressed Save and it went back". If that ever happens, the reader is told here — on the
        screen, at the moment of the press — rather than discovering it on the next load with
        nothing anywhere to read.
      */
      setNothingToSend(true);
      return;
    }

    /*
      BUILT BY NAMING KEYS, ONE AT A TIME, AND NEVER BY SPREADING ANYTHING.

      `lib/designWorkshops.ts` states the reason on `DwUpdateBody` itself: the `Omit` that closes the
      two designer keys is documentation and not enforcement, because an object built by spreading or
      by `Object.fromEntries` widens to an index signature and the compiler stops objecting. The
      server is `extra="forbid"` and refuses a body that merely CARRIES `designerUserId` — as it
      refuses the six cover columns, the stamps and the consent keys, each by name — so the way to be
      sure none of them can arrive is that there is no line here that could add one.

      `undefined` IS HOW A KEY STAYS OFF THE WIRE. `patchDesignWorkshop` sends `JSON.stringify(body)`
      and `JSON.stringify` drops an `undefined` value, so a key this switch never sets is a key the
      request never mentions — which is exactly `exclude_unset`'s "absent" on the other end. `null`
      is a different instruction and is only ever produced from a box the user actually emptied.
    */
    const body: DwUpdateBody = {};
    for (const key of changed) {
      switch (key) {
        case "title":
          // Never nullable: the column is NOT NULL and the blank case returned above.
          body.title = onScreen.title;
          break;
        case "templateId":
          // Never nullable either, and never blank: the picker always holds one of the offered ids.
          body.templateId = onScreen.templateId;
          break;
        case "craftName":
          body.craftName = onScreen.craftName || null;
          break;
        case "clusterName":
          body.clusterName = onScreen.clusterName || null;
          break;
        case "state":
          body.state = onScreen.state || null;
          break;
        case "district":
          body.district = onScreen.district || null;
          break;
        case "startDate":
          body.startDate = onScreen.startDate || null;
          break;
        case "endDate":
          body.endDate = onScreen.endDate || null;
          break;
        case "workshopId":
          body.workshopId = onScreen.workshopId || null;
          break;
        case "notes":
          body.notes = onScreen.notes || null;
          break;
      }
    }

    setSaving(true);
    try {
      const updated = await patchDesignWorkshop(initial.id, body);
      /*
        THE LOCAL COPY IS BROUGHT LEVEL BEFORE THE NAVIGATION, or the record page draws the old
        title back for a moment and — with no connection afterwards — for good.

        The record page reads `lib/designWorkshopStore`'s draft first and only then refreshes from
        the server, so a save that updated the repository and not this browser leaves the screen the
        designer lands on showing what they just corrected away from. `adoptServerSummaries` is the
        store's own way in for a server row, and it declines to overwrite a header holding unsent
        edits — which is exactly right and cannot arise here, because nothing in this form writes
        the draft header.

        Swallowed rather than surfaced: the workshop IS saved by this point, and an IndexedDB write
        that failed must not be reported as a save that failed. The record page's own server read
        corrects the copy on arrival.
      */
      await adoptServerSummaries([updated]).catch(() => undefined);
      // Nothing is owed, so the guard must not fire on the navigation this function is about to do.
      resetDirty();
      /*
        BACK TO THE RECORD PAGE, NOT TO THE LIST — and this is the one place this form departs from
        `ToolForm`/`ProductForm`/`ArtisanForm`, which all `router.push` to their list.

        For those three the record page IS the edit page, so a list is the only other place to go.
        A design workshop has a record page of its own — the 22-stage index, which is where the
        reader came from and where the corrected title, dates and craft are immediately visible in
        the header they were just editing. Dropping them onto a twenty-row list to find their own
        row would be a worse answer to "did that take?".
      */
      router.push(recordHref);
    } catch (err) {
      if (isUnreachable(err)) {
        /*
          NO OUTBOX FOR THIS ONE, AND THE FAILURE SENTENCE HAS TO SAY SO — the same constraint the
          record page's status card states, and for the same reason.

          Stages, photographs and the artisan's consent all survive a dead connection because the
          local draft holds them. A header edit does not: the only writer of the draft header is
          `patchDraftHeader`, whose outbox arm sends a fixed list of keys, and it has never had a UI
          caller — the store's own comment warns that the first one would push `ensureDraft`'s
          empty-string header over the office's real values under a 200. Now that a blank string
          CLEARS a column rather than being dropped, that hazard is strictly worse than when the
          warning was written. So this act is online-only by construction.

          WHAT MUST NOT HAPPEN IS THE FORM EMPTYING. Nothing is reset, nothing is navigated: every
          box still holds what the designer typed, and the sentence says so, because an offline-first
          product that discards a form on a failed request is the one thing this repository's offline
          rules exist against.
        */
        setProblem(
          "The repository could not be reached, so nothing was sent and the workshop is unchanged. " +
            "This one act needs a connection: unlike your stages, a change to the workshop's details is " +
            "not held in the offline queue. Everything you have typed is still on this form — press " +
            "Save again when you have signal."
        );
        return;
      }
      const refusal = readRefusal(err);
      if (refusal.field) {
        const against: Partial<Record<EditableKey, string>> = {};
        against[refusal.field] = refusal.message;
        setFieldProblem(against);
        /*
          AND THE FOCUS GOES TO THE BOX THAT WAS REFUSED, WHERE THERE IS ONE. A sentence under a
          control the reader has scrolled past is a sentence they will not find; moving the caret
          there is what makes "show it against the field" true rather than merely rendered.

          THREE KEYS HAVE NOTHING TO FOCUS AND ALL THREE FALL THROUGH QUIETLY. `startDate`/`endDate`
          are hidden inputs written by the picker, and the `type === "hidden"` test skips them
          explicitly. `templateId` and — since the title became a creatable combo — `title` are
          themed dropdowns, i.e. `<button>`s that submit nothing, so `namedItem` finds no element
          under those names at all and the `instanceof` guard is what catches them. The refusal is
          still RENDERED against each of the three (`fieldProblem`), which is the half that matters;
          what is lost is only the caret move, and there is no element to move it to.
        */
        const control = formElement.elements.namedItem(refusal.field);
        if (control instanceof HTMLElement && !(control instanceof HTMLInputElement && control.type === "hidden")) {
          control.focus();
        }
        return;
      }
      setProblem(describeSaveRefusal(err, refusal.message));
    } finally {
      setSaving(false);
    }
  }

  /** Leave for the record page, past the guard when there is unsaved work. */
  function cancel() {
    if (!dirty) {
      router.push(recordHref);
      return;
    }
    setPromptFromCancel(true);
    setPromptOpen(true);
  }

  /**
   * The offered templates, with the one this workshop already carries guaranteed to be among them.
   *
   * A PICKER THAT CANNOT DRAW ITS OWN CURRENT VALUE READS AS BLANK, and the obvious repair for a
   * blank picker is to answer it — which here would change the report a designer produces. That is
   * `ToolForm`'s shipped craft-dropdown defect in a different control, and the two ways into it are
   * both live: the list request can fail outright (the effect above swallows it deliberately), and a
   * template can be retired from `REPORT_TEMPLATE_IDS` while workshops created under it are still
   * open. The stored id is prepended in both cases; the server still validates whatever is sent.
   */
  const templateOptions = useMemo(() => {
    const offered = templates.map((template) => ({ value: template.id, label: template.name }));
    if (offered.some((option) => option.value === initial.templateId)) return offered;
    return [{ value: initial.templateId, label: initial.templateId }, ...offered];
  }, [templates, initial.templateId]);

  const titleErrorId = `${baseId}-title-error`;
  /* Two more ids for two more facts about one list — see the link picker's trio for the argument. */
  const titleListId = `${baseId}-title-list`;
  const titleScopeId = `${baseId}-title-scope`;
  const templateErrorId = `${baseId}-templateId-error`;
  const linkErrorId = `${baseId}-workshopId-error`;
  /*
    THREE IDS FOR THREE DIFFERENT FACTS ABOUT ONE LIST, all named on the control by
    `aria-describedby`: what the list IS (or what went wrong reading it), why an empty one can be
    empty, and what it left out. They are separate elements rather than one paragraph because two of
    the three are conditional and a reader must not be told about a cut that is not there.
  */
  const linkScopeId = `${baseId}-workshopId-scope`;
  const linkGapId = `${baseId}-workshopId-gap`;
  const linkCutId = `${baseId}-workshopId-cut`;
  const datesErrorId = `${baseId}-dates-error`;

  return (
    <>
      <form
        ref={formRef}
        onSubmit={submit}
        // `onInput` catches every real text box in one place — but ONLY for the unsaved-changes
        // guard. What is SENT is decided by comparing values, not by this flag; see the file header.
        onInput={noteEdit}
        className="panel grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">Workshop details</h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            The workshop&apos;s own record — what it is called, which report it produces, where and when it ran, and the
            workshop it is filed against. The 22 stages, the photographs and the report are all edited from the workshop
            page; nothing here touches them.
          </p>
        </div>

        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
          {/*
            "NAME OF WORKSHOP" GETS THE NAMES ALREADY ON RECORD, AND STILL TAKES ANYTHING TYPED.

            THE SAME CONTROL AS STAGE 1'S, BECAUSE IT IS THE SAME COLUMN. `promoted_values` copies
            `workshopSetup.workshopTitle` onto `DesignWorkshop.title`, and the amber note further
            down this form says which of the two writers wins. A designer who meets a creatable combo
            on the stage form and a plain box here is being asked one question by two controls, on
            the field a ministry reads off the cover — which is exactly the divergence the offer
            exists to close. `StageWorkshopNameField` carries the whole argument, including the
            standing objection to putting a dropdown on this fact and why it does not reach a control
            that cannot refuse an answer.

            `FieldBlock` AND NOT `Field`, and this is what changed structurally. `Field` is a real
            `<label>` wrapped around its control: it cannot name a `<button>` at all, and it forwards
            a stray click into the menu, which slams it shut after one pick. `FieldBlock` is a
            `<div>` named by `aria-labelledby` pointing at its own label span, and it publishes that
            id through `FieldLabelProvider` so the trigger announces "Workshop title, Bagru 2026"
            rather than the value alone.

            THE REFUSAL MOVES INTO `hint` FOR THE SAME REASON IT WAS A SIBLING BEFORE — the note that
            used to sit here explained at length that a refusal rendered INSIDE a `Field` becomes
            part of the input's accessible NAME, because that wrapper is a label with no `for`.
            `FieldBlock` does not have that problem: nothing inside it can reach the name, and its
            `hint` slot is the ordinary home for a described-by paragraph. The `min-w-0` wrapper is
            gone with the same change — `FieldBlock` is itself a grid cell here.
          */}
          <FieldBlock
            label="Workshop title"
            required
            hint={
              <>
                {fieldProblem.title ? (
                  <p id={titleErrorId} role="alert" className="text-xs text-error-600">
                    {fieldProblem.title}
                  </p>
                ) : null}
                {/*
                  WHICH OF THE FOUR EMPTY STATES THIS IS — never one sentence for all of them. It is
                  the only place on this box that can say the list failed, and the box stays fully
                  usable in every one of them, so the sentence describes the LIST and never what the
                  designer may not do.
                */}
                {nameNotice ? (
                  <p id={titleListId} className="text-xs leading-5 text-ink-500" aria-live="polite">
                    {nameNotice}
                  </p>
                ) : null}
                <p id={titleScopeId} className="text-xs leading-5 text-ink-500">
                  Names from workshops you can open. Type a new one if it is not here.
                </p>
              </>
            }
          >
            <Dropdown
              value={title.trim()}
              onChange={commitTitle}
              options={titleOptions}
              placeholder="Type the name, or pick one already on record"
              /*
                NEVER STOOD DOWN, on an empty list or a failed one. R2 — a field may only be
                mandatory where it is answerable — is satisfied without disabling anything, because
                the box IS the answer. `saving` is this form's own and nothing else.
              */
              disabled={saving}
              emptyLabel={workshopEmptyLabel(nameList, nameVoice)}
              describedBy={`${fieldProblem.title ? `${titleErrorId} ` : ""}${titleListId} ${titleScopeId}`}
              /*
                THE BOX IS THE SERVER'S, so a name on page four is reachable by typing it. A local
                filter over one fetched page answers "No matches" about a workshop that exists, and
                the next thing a person does after "no matches" is type the name again slightly
                differently — the exact divergence this offer was added to prevent. `truncated` is
                deliberately absent: `/design-workshops` reports a real total.
              */
              serverQuery={{ value: nameTerm, onChange: setNameTerm, pending: namePending }}
              /*
                THE HALF THE STANDING OBJECTION COULD NOT REFUSE. Whatever is in the box is
                committable, so a workshop that exists nowhere yet is answered as fast as one with a
                history — and the row NAMES IT BACK, quoted, so the reader can see the capitals and
                the stray double space before they store them. No `noneLabel` beside it: this column
                is NOT NULL, and a row offering to leave a document unnamed is a row the server
                refuses.
              */
              createAction={{ label: workshopNameCreateLabel, onCreate: commitTitle }}
            />
          </FieldBlock>

          {/* FieldBlock, not Field: `Field` is a `<label>`, and a `<label>` cannot name a `<button>`
              — every themed dropdown in this app is one. See `FormControls.Field`'s header. */}
          <FieldBlock
            label="Report template"
            hint={
              fieldProblem.templateId ? (
                <p id={templateErrorId} role="alert" className="text-xs text-error-600">
                  {fieldProblem.templateId}
                </p>
              ) : null
            }
          >
            <Dropdown
              value={templateId}
              onChange={(next) => {
                // A themed control fires no native input event, so the form's `onInput` never sees
                // it and the leave guard would never arm. (What gets SENT does not depend on this.)
                noteEdit();
                setTemplateId(next);
              }}
              options={templateOptions}
              ariaLabel="Report template"
              describedBy={fieldProblem.templateId ? templateErrorId : undefined}
              // Templates are fetched rows and there will be a set per cluster, so the filter box is
              // declared rather than left to the option-count rule. Same call as the create form.
              searchable
              disabled={saving}
            />
          </FieldBlock>

          {/* The four promoted text columns. `maxLength` mirrors the server's bound on each so the
              box refuses the 161st character rather than the save refusing the request — but the
              refusal slot is wired anyway; see {@link WritableTextField}. */}
          <WritableTextField
            label="Craft"
            name="craftName"
            maxLength={160}
            defaultValue={initial.craftName ?? ""}
            error={fieldProblem.craftName}
            errorId={`${baseId}-craftName-error`}
          />
          <WritableTextField
            label="Cluster"
            name="clusterName"
            maxLength={160}
            defaultValue={initial.clusterName ?? ""}
            error={fieldProblem.clusterName}
            errorId={`${baseId}-clusterName-error`}
          />
          <WritableTextField
            label="State"
            name="state"
            maxLength={80}
            defaultValue={initial.state ?? ""}
            error={fieldProblem.state}
            errorId={`${baseId}-state-error`}
          />
          <WritableTextField
            label="District"
            name="district"
            maxLength={80}
            defaultValue={initial.district ?? ""}
            error={fieldProblem.district}
            errorId={`${baseId}-district-error`}
          />

          {/*
            ONE RANGE CONTROL AND NOT TWO NATIVE DATE BOXES, for the two reasons the create form
            sets out: a native date input formats itself to the BROWSER's locale, so 02/03/2026 is
            February-to-March on one machine and March-to-April on another with nothing ever
            erroring; and two independent boxes have no idea about each other, so an end a month
            before the start saves happily and prints a workshop of negative duration on a DCH cover.
          */}
          {/* `FieldBlock` and not a bare `<div>` with a `<span>` over it: the picker is TWO inputs
              carrying their own "Start date" / "End date" labels, so the thing that needs naming is
              the GROUP, which is what a `role="group"` with an `aria-labelledby` is for. A heading
              span alone would be a word on the screen that names nothing to a reader. */}
          <div className="md:col-span-2">
            <FieldBlock
              label="Workshop duration"
              hint={
                fieldProblem.startDate || fieldProblem.endDate ? (
                  <p id={datesErrorId} role="alert" className="text-xs text-error-600">
                    {fieldProblem.startDate ?? fieldProblem.endDate}
                  </p>
                ) : null
              }
            >
              {/*
                `yyyy-mm-dd`, byte-identical to what the column stores — `workshop_summary` answers
                `record.startDate.date().isoformat()` and this writes `toIsoDate` — so an untouched
                range compares EQUAL to the stored value and no key is sent. Pinned by
                `e2e/design-workshop-header-diff-unit.spec.ts`, because the two spellings drifting
                apart would send a display-formatted "20/07/2026" to `_parse_date`, which cannot read
                it, and 422 a form whose dates nobody had touched.

                Hidden inputs carry no `name` inside `DateRangePicker` itself, so these two are the
                only `startDate`/`endDate` in this form's `FormData` and `form.get` cannot pick up a
                display box instead.

                ⚠ THESE TWO COLUMNS CAN BE SET AND CORRECTED FROM HERE BUT NOT EMPTIED, and this
                comment previously claimed the opposite. `DateRangePicker` calls `onChange` only when
                `parseTypedDate` succeeds, and its blur handler rewrites the box from `from`/`to` —
                so clearing the "Start date" text and tabbing away restores it, `duration` never
                moves, and Save answers "Nothing on this form differs from what is stored". The diff
                below is right and would send `null` the moment `duration` emptied; nothing empties
                it. A designer removing dates entered against the wrong sitting has to do it in
                stage 1, where a blank box really does null the promoted column (`_coerce_promoted`).
                The affordance belongs on `DateRangePicker` — every caller of it has this hole — and
                not in a copy of the control made here.
              */}
              <input type="hidden" name="startDate" value={duration.from ? toIsoDate(duration.from) : ""} />
              <input type="hidden" name="endDate" value={duration.to ? toIsoDate(duration.to) : ""} />
              <DateRangePicker
                from={duration.from}
                to={duration.to}
                onChange={(next) => {
                  noteEdit();
                  setDuration(next);
                }}
                disabled={saving}
              />
            </FieldBlock>
          </div>
        </div>

        {/*
          THE SIX BOXES ABOVE THAT STAGE 1 ALSO OWNS, SAID OUT LOUD — because the server cannot say
          it in a response and the failure is silent and total.

          `promoted_values` in `stage_schema.py` is the declared single writer of the denormalised
          columns, and `_coerce_promoted` sets a promoted column of a touched entity back to NULL
          when its stage value is blank. So a designer who corrects the cluster here, opens stage 1
          and saves it with that box empty watches the correction go to NULL under a 200 reading
          "Stage saved" — and the workshop then falls out of every list filter and search on craft,
          state, district and date. That exact regression has already shipped once; it is written up
          at `backend/app/services/design_workshops.py`. `title` is the mirror image: the create
          deliberately does not seed `workshopSetup.workshopTitle`, so a title set here stands until
          somebody types a different one into stage 1, at which point stage 1 wins.

          The record page already prints the honest half of this sentence under its progress bar.
          This form has to carry it too, because this is the screen where somebody types into them.
        */}
        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          Craft, cluster, state, district and the dates are also asked in{" "}
          <Link href={stageOneHref} className="font-medium underline">
            stage 1, Workshop Setup &amp; Cover Information
          </Link>
          , and stage 1 is what the report reads. Correcting one here is real and takes effect at once — but the next
          time stage 1 is saved with that box empty, the stage&apos;s answer wins and this one is cleared. If the value is
          wrong on the report, correct it in stage 1; correct it here to fix how the workshop is listed and searched
          today.
        </p>

        {/*
          FULL WIDTH AND OUTSIDE THE FOUR-COLUMN GRID, because it is a picker over records with a
          warning under it rather than a box — and because it is one of the three fields on this form
          that nothing else in the product could change until now (`notes` and the report template
          are the other two: neither is a promoted column, so stage 1 cannot reach them either).
        */}
        <FieldBlock
          label="Linked workshop record"
          hint={
            <>
              {/*
                ONE SENTENCE, CHOSEN BY THE STATE AND NOT BY `rows.length`. Which of the five it is —
                silence while the read is in flight, the failure sentence, the offline sentence, the
                scoped "none are open" sentence, or the sentence describing the scope when the list
                holds something — is decided in `linkedWorkshopPicker.ts` and worded by
                `lib/workshopOptions.ts`. Nothing is written here, because the same five sentences
                spelled a sixth way on this screen is how a reader learns none of them means much.
              */}
              {link.notice ? (
                <p id={linkScopeId} className="text-xs leading-5 text-ink-500">
                  {link.notice}
                </p>
              ) : null}
              {/*
                AND THE SECOND HALF OF "why is it empty", which no shared module can know: this
                request narrows by `workshopType` as well as by access, and the Kind is set by a
                professor or an admin. The sentence it replaced told a designer to go and mark a
                workshop on a page whose form is hidden from them and whose API refuses them
                (`can_manage_workshops` is `has_rank(user, "PROFESSOR")`, 40; DESIGNER is 35).
              */}
              {link.gap ? (
                <p id={linkGapId} className="mt-1 text-xs leading-5 text-ink-500">
                  {link.gap}
                </p>
              ) : null}
              {/*
                WHAT THE LIST LEFT OUT, WITH THE NUMBER. Non-negotiable in this repository and it was
                simply absent here: the request used to ask for 100 rows into a panel that draws 80,
                so on a deployment with more marked workshops than that the picker dropped rows in
                silence. The sentence is `workshopCutSentence`'s and it names the box, which now
                genuinely reaches past the cut because the box goes to the server.
              */}
              <CappedListNotice id={linkCutId} cuts={[link.cut]} />
              {repointsLink ? (
                <p className="mt-1 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                  Changing this link strands the records already filed under the old one. Five of the pickers inside the
                  stages — the artisan and product on an existing product, a prototype&apos;s product, and the process on a
                  process step or a traditional process — only offer records filed against the LINKED workshop, so
                  anything created from them under the old link will not appear in those lists again. The records
                  themselves are untouched and stay in the repository.
                </p>
              ) : null}
              {fieldProblem.workshopId ? (
                <p id={linkErrorId} role="alert" className="mt-1 text-xs text-error-600">
                  {fieldProblem.workshopId}
                </p>
              ) : null}
            </>
          }
        >
          {/*
            SEARCH KEYSTROKES STOP HERE instead of bubbling to the form's `onInput={noteEdit}`.

            A React portal moves the panel out of the DOM but NOT out of the React tree, so the
            filter box's synthetic input events still reach this form — where `noteEdit` arms the
            unsaved-changes guard and retires the last save's answer. Without this, typing three
            letters to FIND a workshop, changing nothing and pressing Escape leaves the page
            unleavable behind the "you have unsaved changes" prompt, and wipes a 422 the designer was
            in the middle of reading. `WorkshopSelect` carries the same guard for the same reason.
            Nothing inside this subtree is a control whose input this form needs.
          */}
          <div onInput={(event) => event.stopPropagation()}>
            <Dropdown
              value={workshopId}
              onChange={(next) => {
                // A themed dropdown is a `<button>` and fires no native input event, so the form's
                // `onInput` never sees this. The diff is what decides the body either way; this is
                // the unsaved-changes guard's copy, which is allowed to over-report.
                noteEdit();
                setWorkshopId(next);
              }}
              options={link.options}
              ariaLabel="Linked workshop record"
              /*
                Both list facts reach the control, not just the refusal: what the list IS and what it
                LEFT OUT are the two things a screen-reader user needs AT the picker rather than
                somewhere underneath it, and `aria-describedby` takes a list of ids. Ids that name
                nothing are ignored by the accessibility tree, so the two notices being conditional
                costs nothing here.
              */
              describedBy={
                fieldProblem.workshopId
                  ? `${linkScopeId} ${linkGapId} ${linkCutId} ${linkErrorId}`
                  : `${linkScopeId} ${linkGapId} ${linkCutId}`
              }
              /*
                THE ROW THAT UN-FILES THE RECORD IS THE PRIMITIVE'S, not a hand-built
                `{ value: "", label: … }` in the options array. Two layers each entitled to draw
                "none" is two rows sharing the React key `""`, a duplicate-key warning, a list
                offering the same answer twice and a control that cannot say which of the two is
                selected — `lib/workshopOptions.ts` forbids it by name. `NO_FIELD_WORKSHOP` is the
                one string for this table, replacing this screen's private ninth spelling of it.
              */
              noneLabel={NO_FIELD_WORKSHOP}
              /*
                Never the literal "No options", and never a claim the state does not support: it is
                `SEARCHING_LABEL` mid-flight, the failure sentence after a failure, and the scoped
                "none are open to this account" only when the read actually answered with none.
              */
              emptyLabel={link.emptyLabel}
              placeholder={link.placeholder}
              /*
                The box is the SERVER'S. `truncated` is deliberately absent: `GET /workshops` reports
                a real `total`, so the cut is stated once, underneath, with its number — a flag arm as
                well would print "there are more and the server did not say how many" under a sentence
                that just said how many.
              */
              serverQuery={{ value: linkTerm, onChange: setLinkTerm, pending: linkPending }}
              /*
                R2/R3 — nothing to pick means the control is disabled AND the sentence above says why.
                A silently disabled picker is the same silent emptiness in another costume. It never
                stands down over a term or mid-flight; see `linkedWorkshopView`.
              */
              disabled={saving || link.standingDown}
            />
          </div>
        </FieldBlock>

        {/* `MAX_NOTES_CHARS` on the server is 20 000; the box carries the same number so a long
            note is stopped at the keyboard rather than at the save. Not a promoted column and with
            no stage-1 twin — this form is the only thing in the product that can change it. */}
        <WritableTextField
          label="Notes"
          name="notes"
          maxLength={20000}
          defaultValue={initial.notes ?? ""}
          error={fieldProblem.notes}
          errorId={`${baseId}-notes-error`}
          multiline
        />

        {/* ── WHAT THIS FORM SHOWS AND CANNOT WRITE ────────────────────────────────────────────── */}
        <div className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
          <div>
            <h3 className="text-sm font-medium text-ink-900">Filled in from stage 1</h3>
            <p className="mt-1 text-xs leading-5 text-ink-500">
              These are the workshop&apos;s cover values and stage 1 is the only thing that collects them, so they are shown
              here and changed there. The repository refuses them on this form by name rather than accepting them and
              quietly not writing them.
            </p>
          </div>
          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {READ_ONLY_COVER.map((entry) => {
              const value = initial[entry.key];
              return (
                <div key={entry.key} className="min-w-0">
                  <dt className="field-label">{entry.label}</dt>
                  {/* NOT RECORDED YET IS SAID, never rendered as an empty line: a blank beside a
                      label is indistinguishable from a value that failed to load, and every one of
                      these is legitimately empty until stage 1 has been saved once. */}
                  <dd className={value ? "text-sm text-ink-900" : "text-sm italic text-ink-500"}>
                    {typeof value === "string" && value.trim() ? value : "Not recorded yet"}
                  </dd>
                  <p className="mt-0.5 text-xs leading-5 text-ink-500">{entry.why}</p>
                </div>
              );
            })}
          </dl>
          <p className="text-xs leading-5 text-ink-500">
            <Link href={stageOneHref} className="font-medium text-purple-700 underline">
              Open stage 1 to change any of these
            </Link>
            .
          </p>
        </div>

        {/*
          TWO MORE THINGS A READER WILL COME HERE LOOKING FOR, and neither is a control on this form.
          Saying where they are is cheap; leaving somebody to conclude the product cannot do it is
          the failure — the designer roster in particular has a screen, and it is not on this page.
        */}
        <p className="text-xs leading-5 text-ink-500">
          The workshop&apos;s <strong>status</strong> is set on the{" "}
          <Link href={recordHref} className="font-medium text-purple-700 underline">
            workshop page
          </Link>
          , under Submission — Mark complete, Submit and Reopen each write one status and say what it means. The{" "}
          <strong>designers on this workshop</strong> are granted from &ldquo;Designers on a workshop&rdquo; on the{" "}
          <Link href="/design-workshops" className="font-medium text-purple-700 underline">
            design workshops list
          </Link>
          , which an administrator opens with admin view on; naming a designer is a create-time act here, because it
          decides whose profile was copied into stages 1 and 3 before those stages existed.
        </p>

        {/*
          THE REFUSAL SITS IMMEDIATELY ABOVE THE BUTTONS AND IS NEVER TOP-PINNED.

          The argument is `ReviewEditPanel`'s and it holds here: this form is ten boxes and two
          panels tall, so a message pinned above the first of them is off-screen at the moment it is
          produced, and a Save that scrolled nothing and said nothing where the reader is looking is
          a button that did nothing. `role="alert"` because this banner is the only place a refused
          save reaches the designer at all.
        */}
        {problem ? (
          <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">
            {problem}
          </div>
        ) : null}
        {nothingToSend ? (
          <div role="status" className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            Nothing on this form differs from what is stored, so nothing was sent and the workshop is unchanged. Change
            something and press Save again, or press Cancel to go back to the workshop.
          </div>
        ) : null}

        <div className="flex justify-end gap-2">
          <button type="button" className="field-button-secondary" onClick={cancel} disabled={saving}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving…" : "Save details"}
          </button>
        </div>
      </form>

      <UnsavedChangesDialog
        open={promptOpen}
        saving={saving}
        onKeepEditing={() => {
          setPromptOpen(false);
          setPromptFromCancel(false);
          // Forgets the act the back control handed over rather than banking it — otherwise "Keep
          // editing" would leave a navigation parked, waiting to fire on the next answer.
          abandonLeave();
        }}
        onDiscard={() => {
          setPromptOpen(false);
          resetDirty();
          if (promptFromCancel) {
            setPromptFromCancel(false);
            router.push(recordHref);
            return;
          }
          // The back arrow raised this. `completeLeave` runs the act the control handed over, so the
          // one answer that means "leave" delivers the leaving as well as the throwing away — see
          // `PendingLeave` in `UnsavedChangesGuard`.
          completeLeave();
        }}
        onSave={() => {
          setPromptOpen(false);
          setPromptFromCancel(false);
          // The form's own validated save, so a blank title keeps the reader here with the box
          // highlighted rather than leaving with the work dropped.
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
