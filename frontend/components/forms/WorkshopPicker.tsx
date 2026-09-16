"use client";

import { useCallback, useEffect, useId, useMemo, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { Field } from "@/components/FormControls";
import {
  DesignWorkshopSelect,
  useDesignWorkshopSelection,
  type DesignWorkshopSelectState
} from "@/components/forms/DesignWorkshopSelect";
import { WorkshopSelect, useWorkshopSelection, type WorkshopSelection } from "@/components/forms/WorkshopSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { WORKSHOP_KIND_FLOOR } from "@/lib/designWorkshops";
import { canRunDesignWorkshops } from "@/lib/permissions";
import { NO_FIELD_WORKSHOP } from "@/lib/workshopOptions";
import type { Workshop } from "@/lib/types";
import {
  DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY,
  defaultWorkshopType,
  listWorkshopTypes,
  sortWorkshopTypes,
  type WorkshopTypeOption
} from "@/lib/workshopTypes";

/**
 * TWO DROPDOWNS, NEVER THREE. The one workshop control every record form mounts.
 *
 *     "Type of workshop"  → the administrator's list (`WorkshopTypeOption`, `GET /workshop-types`)
 *     "Workshop"          → the workshops OF THAT TYPE, most recent first
 *
 * ── WHAT THIS REPLACED, AND WHY THE THIRD BOX HAD TO GO ─────────────────────────────────────────
 *
 * Until this release a record form carried THREE controls for one question. `WorkshopSelect` picked
 * a `Workshop` and wrote `workshopId`. Beside it `DesignWorkshopCascade` drew a KIND box —
 * `stage_schema.ENUMS["WORKSHOP_KIND"]`, the vocabulary that answers *stage 1 of a design workshop's
 * own questionnaire* — and under that a second workshop box picking a `DesignWorkshop` and writing
 * `designWorkshopId`. The kind box saved nothing; its own hint said so out loud. So a researcher
 * filing one tool met two "workshop" dropdowns whose difference is a schema fact, plus a third box
 * that narrowed one of them and was not stored anywhere.
 *
 * The owner's words: *"we do not need one separately for each of the type of the workshops"* and
 * *"Do not invent complexity."* There is now ONE name dropdown whose contents depend on the type
 * chosen above it. Which table it reads, and which column the answer lands in, is
 * {@link WorkshopTypeOption.routesToDesignWorkshop} — a per-row flag on the administrator's list.
 *
 * ── THE TYPE IS NOT STORED ON THE RECORD, AND THAT IS THE SENTENCE THAT MAKES THIS READABLE ─────
 *
 * No column holds it and none should. The workshop the researcher picked already knows its own type
 * — `DesignWorkshop.workshopKind` is answered in stage 1, and a `Workshop` is an ordinary field
 * workshop by construction — so a second copy filed beside the record would be a denormalisation
 * that can disagree with its own source the first time a workshop's stage 1 is corrected, and
 * nothing would ever read the two together to notice. The retired kind box said exactly this about
 * itself and it was the sentence that made that control understandable; it is said again under the
 * new box, because the box looks like something that is saved and is not.
 *
 * What DID change is that the type is no longer only a lens. It decides WHERE the answer is written
 * (R3), so changing it is a real edit and arms the form's unsaved-changes guard. The lens/answer
 * distinction survives in the only place it ever mattered: what reaches the payload is a workshop id
 * and never a type token.
 *
 * ── WHY THIS IS THE `WORKSHOP_KIND` REGISTRY'S NEIGHBOUR AND NOT ITS REPLACEMENT ────────────────
 *
 * `backend/app/services/stage_schema.ENUMS["WORKSHOP_KIND"]` stays exactly where it is, unchanged.
 * It answers "what kind of design workshop is THIS one?" — a question inside a workshop's own
 * 22-stage questionnaire, asked of the designer running it, and its `registry_version()` is what
 * gates a handset's re-sync of the whole stage registry. `WorkshopTypeOption` answers "which list
 * may a record form offer?" — a question about the shape of six forms, on screens that have no
 * stage 1 at all, whose answer an administrator edits at runtime from `/admin/workshop-types`.
 * Two questions, two homes. The six rows are SEEDED with the registry's six keys so that every
 * `workshopKind` already stored resolves to a label, and nothing synchronises them afterwards.
 *
 * ── THE CONTROL IS ONE COMPONENT, MOUNTED ONCE PER FORM, FOR THE REASON THE CASCADE GAVE ────────
 *
 * The retired cascade's header made the argument and it is worth more now than it was then: pairing
 * two boxes at each call site would be one copy per form of a decision that has a right answer and
 * several wrong ones — which type a new record opens on, what happens to a chosen workshop when the
 * type changes, whether the type reaches the payload, and which of the two id columns is cleared.
 * The field repository learnt this the expensive way with its tracer: wired form by form, four of
 * nine mounts were missed, and a researcher reported the feature simply absent. One mount, one
 * decision, argued here.
 *
 * ── AND THE TWO HALVES ARE THE PICKERS THAT WERE ALREADY THERE ─────────────────────────────────
 *
 * The box below the type box is `WorkshopSelect` or `DesignWorkshopSelect` — the same two components
 * this release folded in, rendered one at a time, never both. That is deliberate and it is not
 * laziness: between them those two files carry the late-submission gate, the pre-flight assignment
 * warning, the four sentences that tell an empty list from a failed read from an offline device, the
 * server-backed search box, the capped-list notice with a real total, the "Already on this record"
 * recovery of a workshop that is off the page, the stand-down rule for a list with nothing in it,
 * and the "not linked" row. Every one of those closed a defect that shipped. A third component
 * re-deriving them would have re-opened them one at a time; what this file adds is the box above,
 * the routing, and the defaulting.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * The type list: one request per sitting, and a floor for a browser that has never had one
 *
 * THE FOUR PURE FUNCTIONS BELOW ARE EXPORTED FOR `e2e/workshop-picker-unit.spec.ts` and for nothing
 * else — no other module imports them. That is the same arrangement, for the same reason, that the
 * retired cascade made for its default: a test that can CALL the rule asserts the ruling instead of
 * restating the code, and "an existing record keeps the workshop it names" is a rule worth a test
 * that would go red rather than a comment that would go stale.
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The types this app knows about when `GET /workshop-types` has never answered.
 *
 * ── A LIST THAT DOES NOT ARRIVE IS A FLOOR, NOT A BROKEN FORM ───────────────────────────────────
 *
 * This is the retired cascade's ruling, carried across intact. Without a floor, a record form opened
 * by a researcher whose device has been out of signal since the tab loaded would have an empty type
 * box, therefore no routing, therefore no workshop box at all — a form that cannot be filled in
 * because a list of six labels did not load. The floor keeps both branches reachable offline, which
 * is the state this app is designed around.
 *
 * ── IT IS `WORKSHOP_KIND_FLOOR`, BECAUSE THE SEED WAS ──────────────────────────────────────────
 *
 * The six seeded rows carry the registry's six keys and its six labels (see the migration
 * `20260916160000_workshop_type_options`), so the floor is that same constant rather than a second
 * copy of it that can drift. A stale floor costs a never-online browser a missing or misnamed
 * option; it can never cause a wrong save, because the id that is saved comes off a workshop row the
 * server served.
 *
 * ── THE ONE PLACE A KEY DECIDES ROUTING, AND WHY THAT IS NOT THE BUG THE CONTRACT WARNS ABOUT ───
 *
 * `lib/workshopTypes.ts` is emphatic: read {@link WorkshopTypeOption.routesToDesignWorkshop}, never
 * test the key, because an administrator may add a second design-workshop-backed programme and no
 * client should have to ship to learn about it. That rule is about SERVED ROWS, which carry the flag
 * — and every served row in this file is read through the flag and never through its key. A floor
 * row has no server to read the flag from; somebody has to author it, and the alternative (a floor
 * with the flag false on all six) would silently take the design-workshop branch off every offline
 * record form, which is the branch a designer in a courtyard most needs. So the key appears here,
 * once, in the construction of a local constant, and the moment a served list arrives it is thrown
 * away whole — `served ?? WORKSHOP_TYPE_FLOOR` replaces the array rather than merging into it, so a
 * type the administrator has retired cannot be resurrected by this constant.
 */
export const WORKSHOP_TYPE_FLOOR: readonly WorkshopTypeOption[] = WORKSHOP_KIND_FLOOR.map((option, index) => ({
  id: `floor:${option.value}`,
  key: option.value,
  label: option.label,
  // Matches the seed's ordinals (10, 20, …) so a floor list and a served list order alike.
  sortOrder: (index + 1) * 10,
  isActive: true,
  routesToDesignWorkshop: option.value === DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY,
  createdAt: null,
  updatedAt: null
}));

/**
 * How long a fetched type list may keep answering.
 *
 * One minute, which is `ACCESSIBLE_WORKSHOPS_TTL_MS` in `forms/WorkshopSelect.tsx` and
 * `DEFAULT_TTL_MS` in `lib/designWorkshopDefault.ts`, deliberately the same number for the reason
 * both of them state: long enough that a researcher opening four record forms in a sitting pays one
 * round trip rather than four, short enough that an administrator's edit reaches the next form
 * inside the same sitting rather than at the next reload.
 *
 * **A FAILURE IS NOT CACHED AT ALL** — the memo is cleared in the `catch`, so the next form to mount
 * asks again rather than inheriting a bad minute. That is the house rule in all three of those
 * files and the one thing about a cache this repository has been bitten by.
 *
 * THE MEMO LIVES HERE AND NOT IN `lib/workshopTypes.ts` because this is the only consumer that
 * mounts per record — the admin screen opens once and wants a fresh read every time. If a second
 * per-record consumer appears, move it there and let the admin screen keep its own uncached call.
 */
const WORKSHOP_TYPES_TTL_MS = 60_000;
let typesRequest: Promise<WorkshopTypeOption[]> | null = null;
let typesAskedAt = 0;
let typesCache: WorkshopTypeOption[] | null = null;

/**
 * Whatever a previous screen already fetched, synchronously, or null.
 *
 * Same device and same reason as `peekStageRegistry` in the retired cascade: a form opened second in
 * a sitting draws the ADMINISTRATOR'S list on its first paint instead of flashing the built-in floor
 * and replacing it a moment later — and a type box that changes its options under the reader is a
 * type box somebody re-picks.
 */
function peekWorkshopTypes(): WorkshopTypeOption[] | null {
  return typesCache;
}

function loadWorkshopTypes(): Promise<WorkshopTypeOption[]> {
  if (typesRequest && Date.now() - typesAskedAt > WORKSHOP_TYPES_TTL_MS) typesRequest = null;
  if (!typesRequest) {
    // Stamped when the request GOES OUT and not when it resolves: a slow answer must not extend its
    // own freshness, because the whole point of the window is how old the evidence is.
    typesAskedAt = Date.now();
    typesRequest = listWorkshopTypes()
      .then((rows) => {
        // NEVER `includeInactive`. A retired type absent from the dropdown is the entire meaning of
        // retiring one; that parameter belongs to the admin screen and to nothing else.
        const sorted = sortWorkshopTypes(rows);
        typesCache = sorted;
        return sorted;
      })
      .catch((error) => {
        typesRequest = null;
        typesAskedAt = 0;
        throw error;
      });
  }
  return typesRequest;
}

/* ────────────────────────────────────────────────────────────────────────────
 * Which type the box opens on
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * Which column a record ALREADY names a workshop in, or null when it names none.
 *
 * `true` = `designWorkshopId`, `false` = `workshopId`, `null` = neither. This is the single most
 * important value in the file: whenever it is not null the type box is pinned to a type with that
 * routing, so no amount of list-arriving, administrator-editing or default-computing can move where
 * an existing record saves. Only a PERSON can.
 */
export function storedRoutingOf(workshopId: string | null | undefined, designWorkshopId: string | null | undefined) {
  if (designWorkshopId) return true;
  if (workshopId) return false;
  return null;
}

/** The first ACTIVE type routing where we need it, in the administrator's order. */
function firstTypeRouting(types: readonly WorkshopTypeOption[], toDesignWorkshop: boolean) {
  return (
    sortWorkshopTypes(types.filter((type) => type.isActive)).find(
      (type) => type.routesToDesignWorkshop === toDesignWorkshop
    ) ?? null
  );
}

/**
 * THE TYPE THE BOX OPENS ON — the whole of the defaulting rule, as a pure function.
 *
 * Pure and re-computed on every render rather than written into state by an effect, and that shape
 * is the correctness argument rather than a style preference. The alternative — seed state once when
 * the list arrives, guarded by a ref — has to answer "what happens when a SECOND list arrives" (the
 * floor is replaced by the served rows a moment later, every time), and every answer to that is a
 * branch that can move a filed record. Here the person's own choice is held in
 * `chosenTypeKey` and, once set, this function's answer is never consulted again; until it is set,
 * the answer is a function of the list in hand and the record's stored routing, so a later list can
 * only ever correct the LABEL and never the destination.
 *
 * ── AN EXISTING RECORD KEEPS THE WORKSHOP IT NAMES. THIS IS THE WHOLE POINT. ────────────────────
 *
 * `storedRouting` comes from the record's own two columns (or from an inline host's seed, which is
 * somebody else's answer and is treated identically). When it is non-null the type is whichever
 * ACTIVE type routes that way, so opening a product filed last season under an ordinary workshop
 * shows the ordinary-workshop list with that workshop selected, and opening one filed under a design
 * workshop shows the design list with that one selected. `preferDesignWorkshops` is not consulted at
 * all in that case: "the most recent workshop the account can reach" is the default for a NEW
 * record, and applying it to an existing one would re-file historic records under whatever is
 * newest — invisibly, because nothing on the screen would say a link had moved.
 *
 * ── AND IT NAMES THE PROGRAMME EVEN AFTER AN ADMINISTRATOR RETIRES IT ──────────────────────────
 *
 * If a record is filed under a design workshop and the administrator has since deactivated the one
 * type that routes there, `firstTypeRouting` answers null. Falling through to the ordinary list
 * would flip the routing and clear `designWorkshopId` on the next save, which is exactly the silent
 * re-filing this function exists to prevent — so the key is returned anyway and
 * {@link typeOptionsFor} draws it as a row of its own. That is the same ruling both workshop pickers
 * already make one level down ("the record's own workshop is always an option, however old it is"),
 * applied to the box above them. `DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY` is used to NAME that
 * programme, which is what the constant is exported for; the routing is still decided by the stored
 * column and never by comparing that key to a served row.
 *
 * ── WHICH NON-DESIGN TYPE, AND WHY THAT IS THE ADMINISTRATOR'S QUESTION AND NOT THIS FILE'S ───
 *
 * `Workshop` has no per-type column — its own `workshopType` enum holds DESIGN_PROTOTYPE/OTHER and
 * every live row is OTHER — so ALL FIVE non-design types draw the identical list. That is a direct
 * consequence of R1 (both tables stay, no data movement) and must not be "fixed" by inventing a
 * column. It means that when a record naming an ordinary `Workshop` is opened, five types are
 * equally true of it and the box has to show one: it shows the FIRST IN THE ADMINISTRATOR'S OWN
 * ORDER, and the hint under it says the type is not saved on the record. If a deployment would
 * rather that box read "Other" than "Skill Upgradation", the remedy is one drag in
 * `/admin/workshop-types` — `PATCH /workshop-types/reorder` exists for exactly this — and not a
 * rule in this file about which of the administrator's rows is the tactful one.
 *
 * The same order decides what a NEW record opens on for an account that does NOT run design
 * workshops, through `defaultWorkshopType`'s documented fallback ("the first type in the list").
 * Today that is Design & Prototype, seeded at sortOrder 10. A researcher who is on no design
 * workshop therefore opens on a list that is empty FOR THEM — and is told so in those words ("No
 * design workshops are open to this account. An administrator can give you access to one.")
 * rather than being shown a blank box. Overriding that here would be a second copy of a rule
 * `lib/workshopTypes.ts` already owns, and would take the ordering back off the administrator who
 * was given a screen to set it.
 */
export function openingTypeKey({
  types,
  storedRouting,
  preferDesignWorkshops
}: {
  types: readonly WorkshopTypeOption[];
  storedRouting: boolean | null;
  preferDesignWorkshops: boolean;
}): string {
  if (storedRouting !== null) {
    const match = firstTypeRouting(types, storedRouting);
    if (match) return match.key;
    return storedRouting ? DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY : "";
  }
  // R4: a DESIGNER opens on Design & Prototype. The predicate stays in `lib/permissions.ts` and is
  // passed in, because this file deciding what a designer is would be a second copy of a ladder
  // `test_role_ladder_parity.py` exists to keep singular.
  return defaultWorkshopType([...types], { preferDesignWorkshops })?.key ?? "";
}

/**
 * The rows the type box draws: every ACTIVE type, plus the current one if it is not among them.
 *
 * The merge is the reason this is a function rather than a `.map()`. A type box rendering a `value`
 * no option carries reads as BLANK, and the documented consequence of a blank workshop control on
 * these forms is not a missing label: it is somebody repairing it by picking something else, which
 * re-files the record. Both pickers below already recover the record's own workshop for that exact
 * reason; this recovers the record's own programme.
 *
 * The label falls back to the floor's and then to the raw token — never to "Unknown" and never to an
 * empty string, which is `workshopTypeLabel`'s rule and `enum_label`'s on the server: a record filed
 * under a type an administrator has deleted is still a record with a type, and printing the token is
 * the honest rendering of "this is what it says".
 */
export function typeOptionsFor(types: readonly WorkshopTypeOption[], currentKey: string) {
  const active = sortWorkshopTypes(types.filter((type) => type.isActive));
  const rows = !currentKey || active.some((type) => type.key === currentKey) ? active : [...active, retired(currentKey)];
  return rows.map((type) => ({ value: type.key, label: type.label }));
}

function retired(key: string): WorkshopTypeOption {
  const floor = WORKSHOP_TYPE_FLOOR.find((type) => type.key === key);
  return {
    id: `retired:${key}`,
    key,
    label: floor?.label ?? key,
    sortOrder: Number.MAX_SAFE_INTEGER,
    isActive: false,
    routesToDesignWorkshop: floor?.routesToDesignWorkshop ?? false,
    createdAt: null,
    updatedAt: null
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * State
 * ──────────────────────────────────────────────────────────────────────────── */

export type WorkshopPickerState = {
  /** The chosen type's key, or "" when there are none to choose from. Never sent anywhere. */
  typeKey: string;
  /** True when the chosen type's workshops come from `DesignWorkshop`. Decides both ids below. */
  routesToDesignWorkshop: boolean;
  /**
   * What to put in the payload as `workshopId`. **"" when the chosen type routes to design.**
   *
   * Both ids are exposed rather than one "id + column" pair because that is the shape all four forms
   * already build (`workshopId: ... || null, designWorkshopId: ... || null`) and because the record
   * genuinely has two nullable columns — R1 keeps both tables and both foreign keys. What the picker
   * guarantees is that AT MOST ONE of them is ever non-empty: one control, one answer.
   */
  workshopId: string;
  /** What to put in the payload as `designWorkshopId`. **"" when the chosen type routes to a `Workshop`.** */
  designWorkshopId: string;
  /**
   * True while the ORDINARY-workshop half is still loading its list or walking its submission probe.
   *
   * IT IS THE FIELD HALF'S FLAG AND NOT THE WHOLE CONTROL'S, and the asymmetry is inherited rather
   * than invented: `useWorkshopSelection` finds "the most recent workshop this account may submit
   * to" asynchronously and exposes `loading` for it, while `useDesignWorkshopSelection` starts at
   * `""` and is prefilled by `DesignWorkshopSelect` when `lib/designWorkshopDefault.ts` answers, so
   * there is no equivalent flag to report on the design branch.
   *
   * ONE CALLER, AND IT READS IT THROUGH `routesToDesignWorkshop`: the interview form's artisan
   * roster holds its first scoped request until the picker has stopped choosing for itself
   * (`workshopScopeSettling`, `components/questionnaires/interviewArtisans.ts`, which carries the
   * whole argument including why the design half is deliberately not waited on). Exposed here
   * rather than letting that page reach into `view.field`, which is internal wiring.
   */
  loading: boolean;
  /**
   * Every ordinary `Workshop` this form has learnt about, most recent occurrence first.
   *
   * Read by all four forms to put a `workshopName` on the carry bag. It is NOT the current answer
   * and it only ever grows — see `WorkshopSelection.workshops`, whose note explains why searching
   * must not be able to make the record's own workshop unnameable.
   */
  workshops: Workshop[];
  /**
   * True once a PERSON has touched any part of this control — the type box, either workshop box.
   *
   * Never true for a default the app applied. `ProcessForm` diffs a signature rather than listening
   * for an event, so it gates both ids on this; the other three arm their guard from `onDirty`.
   * A blank new form announcing unsaved work before anybody types is what teaches researchers to
   * click through the guard, and it must still mean something an hour later when there IS an
   * interview in the form.
   */
  touched: boolean;
  /**
   * Select an ordinary `Workshop` by id — the carry bag's door, and a person's act.
   *
   * IT MOVES THE TYPE BOX TOO, which is the only thing that makes it work now that the box above
   * decides which list is on screen. `useCarryContext` offers the sitting this researcher was last
   * working in; applying a `workshopId` while the type box sat on Design & Prototype would put a
   * workshop in a state nothing on screen shows and nothing would save it. A carried answer is a
   * real, recent, human answer and outranks a computed default.
   */
  setWorkshopId: (workshopId: string) => void;
  /**
   * Call FIRST in the form's submit handler, exactly as `useWorkshopSelection`'s was.
   *
   * Resolves true when the save may go ahead, false when the researcher backed out of a late
   * submission. **It is a no-op when the chosen type routes to a design workshop**, and that is not
   * a weakening of the gate: the window and the assignment roster are properties of a `Workshop`
   * (`GET /workshops/{id}/submission-check`), a `DesignWorkshop` has neither, and nothing is being
   * filed against the ordinary workshop the field half may still be holding.
   */
  confirmSubmission: () => Promise<boolean>;
  /** Internal wiring for <WorkshopPicker>; not meant for call sites. */
  view: {
    types: readonly WorkshopTypeOption[];
    typesServed: boolean;
    setTypeKey: (key: string) => void;
    field: WorkshopSelection;
    design: DesignWorkshopSelectState;
    designInitial: string | null | undefined;
  };
};

/**
 * Owns the whole workshop question for one form.
 *
 * `initialWorkshopId` / `initialDesignWorkshopId` are the record's stored columns on an EDIT, or an
 * inline host's seed on a CREATE. `isEdit` is what tells the two halves they may not default.
 * `resetKey` re-seeds the ordinary-workshop half when one mounted form is used for several records;
 * pass the record's id.
 */
export function useWorkshopPicker({
  initialWorkshopId,
  initialDesignWorkshopId,
  isEdit = false,
  resetKey = null
}: {
  initialWorkshopId?: string | null;
  initialDesignWorkshopId?: string | null;
  isEdit?: boolean;
  resetKey?: string | null;
} = {}): WorkshopPickerState {
  const { user } = useAuth();

  /**
   * BOTH HALVES ARE MOUNTED AT ONCE, and only one of them is drawn.
   *
   * Hooks cannot be called conditionally, so this is not a choice — but it is the behaviour that
   * would have been chosen anyway. Each half computes its own default while the other is on screen,
   * so switching the type box shows an answer immediately instead of a blank box and a spinner; and
   * a researcher who switches back finds the workshop they had. It costs the same two list requests
   * the form issued before this release, when both controls were visible.
   */
  const field = useWorkshopSelection({ initialWorkshopId, isEdit, resetKey });
  const design = useDesignWorkshopSelection(initialDesignWorkshopId ?? null, resetKey);

  /**
   * `undefined` ON A CREATE, THE STORED VALUE (OR `null`) ON AN EDIT — and the difference is the
   * whole prefill rule, not a nicety.
   *
   * `DesignWorkshopSelect` reads `initial === undefined` as "this is a new record, fill it in for
   * me" and `null` as "this record is stored with no workshop, leave it alone". Derived from
   * `isEdit` here rather than from a record object at four call sites, because four spellings of one
   * ternary is four chances for one form to start overwriting the workshop an existing record names.
   * Same convention, same reason, as `LocationFields.initial`.
   */
  const designInitial = isEdit ? (initialDesignWorkshopId ?? null) : undefined;

  const [served, setServed] = useState<WorkshopTypeOption[] | null>(() => peekWorkshopTypes());
  useEffect(() => {
    let cancelled = false;
    void loadWorkshopTypes()
      .then((rows) => {
        if (!cancelled) setServed(rows);
      })
      .catch(() => {
        /* the floor is already on screen — see WORKSHOP_TYPE_FLOOR */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const types = served ?? WORKSHOP_TYPE_FLOOR;

  /** What a PERSON picked, or null while nobody has. See {@link openingTypeKey} for why. */
  const [chosenTypeKey, setChosenTypeKey] = useState<string | null>(null);
  /*
    A DIFFERENT RECORD IS IN THE FORM, SO THE PERSON'S OWN CHOICE IS SPENT.

    Both halves below re-seed on `resetKey`; without this the box ABOVE them would not, and the type
    a researcher chose while editing one record would decide where the NEXT one files — over the top
    of that record's own stored routing, which is the one thing this control is built to protect.

    Adjusted during the render that noticed, rather than in an effect, because an effect would let
    one commit paint with the old type over the new record's workshop list. React re-runs this
    component immediately with the new state and nothing else has rendered in between.
  */
  const [seenResetKey, setSeenResetKey] = useState<string | null>(resetKey);
  if (seenResetKey !== resetKey) {
    setSeenResetKey(resetKey);
    setChosenTypeKey(null);
  }
  const storedRouting = storedRoutingOf(initialWorkshopId, initialDesignWorkshopId);
  const preferDesignWorkshops = canRunDesignWorkshops(user);
  const autoTypeKey = useMemo(
    () => openingTypeKey({ types, storedRouting, preferDesignWorkshops }),
    [types, storedRouting, preferDesignWorkshops]
  );
  const typeKey = chosenTypeKey ?? autoTypeKey;

  /**
   * WHERE THE ANSWER GOES, READ OFF THE ROW AND NEVER OFF THE KEY.
   *
   * The fallback matters as much as the lookup. A key with no row in hand is either the floor being
   * replaced mid-render or the retired-programme case above; in both, the record's own stored column
   * is the better authority than a guess, and `false` is the last resort only when there is no
   * stored column either (a brand-new record on a list that has not arrived, where nothing can be
   * mis-filed because nothing is filed).
   */
  const routesToDesignWorkshop =
    types.find((type) => type.key === typeKey)?.routesToDesignWorkshop ?? storedRouting ?? false;

  const setTypeKey = useCallback((key: string) => setChosenTypeKey(key), []);

  const fieldSetWorkshopId = field.setWorkshopId;
  const setWorkshopId = useCallback(
    (next: string) => {
      fieldSetWorkshopId(next);
      // The type box follows the answer, so what was applied is what is on screen. See the state
      // type's note. Nothing to do when there is no ordinary-workshop type to move to — the
      // administrator has retired all five and the field half is unreachable anyway.
      const target = firstTypeRouting(types, false);
      if (target) setChosenTypeKey(target.key);
    },
    [fieldSetWorkshopId, types]
  );

  const fieldConfirmSubmission = field.confirmSubmission;
  const confirmSubmission = useCallback(
    () => (routesToDesignWorkshop ? Promise.resolve(true) : fieldConfirmSubmission()),
    [routesToDesignWorkshop, fieldConfirmSubmission]
  );

  return {
    typeKey,
    routesToDesignWorkshop,
    /*
      ONE CONTROL, ONE ANSWER, TWO DESTINATIONS (R3). The half that is not on screen contributes
      nothing to the payload — its id stays in its own state so switching back restores it, and the
      form writes `|| null` over both, so the column the record is not filed in is cleared.

      MEASURED BEFORE IT WAS WRITTEN, because "clears the other column" is the kind of sentence that
      sounds harmless and is not. On this database (2026-09-16) no record of any of the four types
      holds both columns at once: ToolDocumentation 92 rows / 51 with a workshop / 0 with a design
      workshop, Artisan 774 / 204 / 4, ProductDocumentation 473 / 153 / 0, Process 897 / 51 / 0, and
      the both-set count is 0 in all four. So consolidating to one answer moves nothing that exists
      today; what it prevents is a record that names two different workshops and no way on screen to
      tell which one it belongs to.
    */
    workshopId: routesToDesignWorkshop ? "" : field.workshopId,
    designWorkshopId: routesToDesignWorkshop ? design.workshopId : "",
    // NOT narrowed by the routing, deliberately — see the field's own note. It reports what the
    // ordinary half is doing whichever half is on screen, and the one reader combines it with
    // `routesToDesignWorkshop` rather than being handed a flag that has already decided for it.
    loading: field.loading,
    workshops: field.workshops,
    touched: field.touched || design.touched || chosenTypeKey !== null,
    setWorkshopId,
    confirmSubmission,
    view: { types, typesServed: served !== null, setTypeKey, field, design, designInitial }
  };
}

/* ────────────────────────────────────────────────────────────────────────────
 * The control
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The two boxes. Mount this as the first control on the form — the workshop is the context every
 * other answer belongs to, which is Android parity and not merely an ordering preference.
 *
 * ONE MOUNT IS DELIBERATELY NOT FIRST, so this is a default rather than a law. The questionnaire
 * capture form (`app/(protected)/questionnaire/page.tsx`) draws this fourth, below Interview title,
 * Place and Language, because that form's order is declared once in
 * `shared/questionnaire-form-contract.json` and `backend/tests/test_questionnaire_form_contract.py`
 * holds this client and the handset to it as an equality. Moving it to the head of that form to
 * match the other four is a red build; the contract is where a disagreement about it gets settled.
 *
 * `onDirty` arms the form's unsaved-changes guard. A themed dropdown is a `<button>` and fires no
 * native input event, so every themed control on these forms arms the guard by hand; `ProcessForm`
 * passes nothing and diffs `state.touched` into its signature instead.
 */
export function WorkshopPicker({
  state,
  onDirty,
  saving
}: {
  state: WorkshopPickerState;
  onDirty?: () => void;
  saving?: boolean;
}) {
  const { typeKey, routesToDesignWorkshop, view } = state;
  // Named so the paragraph explaining what the box IS reaches the control itself. A hint a reader
  // only meets by looking underneath the field is a hint a screen-reader user never meets at all.
  const baseId = useId();
  const hintId = `${baseId}-type-hint`;
  const options = useMemo(() => typeOptionsFor(view.types, typeKey), [view.types, typeKey]);

  return (
    /*
      SEARCH KEYSTROKES STOP HERE instead of bubbling to the form's `onInput` dirty tracker.

      Both halves put a real `<input type="text">` inside their panel, and the record forms mark
      themselves dirty from `<form onInput={markDirty}>`. Left alone, merely TYPING to filter the
      list — changing nothing — arms the "unsaved changes" prompt, so somebody who searched, picked
      nothing and pressed Escape could not leave without confirming. `WorkshopSelect` has carried
      this firewall for its own panel since it was written and `DesignWorkshopSelect` never did;
      putting both halves inside one wrapper is what finally covers the second one. Nothing in here
      is a form control the parent needs input events from — all three boxes are themed `<button>`s
      that report a real change through `onDirty` and never through the DOM.
    */
    <div className="grid min-w-0 content-start gap-3" onInput={(event) => event.stopPropagation()}>
      {/* R2: the first box, and it is called what the owner calls it. */}
      <Field label="Type of workshop">
        <Dropdown
          value={typeKey}
          onChange={(next) => {
            view.setTypeKey(next);
            // Changing the type changes WHERE the record files (R3), so it is a real edit and not a
            // filter. The retired kind box deliberately did not do this, and was right not to: it
            // saved nothing.
            onDirty?.();
          }}
          options={options}
          /*
            NEVER THE PRIMITIVE'S LITERAL "No options". An administrator can deactivate every row,
            and a box that answers that with two words tells a researcher nothing about whose problem
            it is. The form still works in that state — with no type carrying the flag the control
            routes at the ordinary `Workshop` list, which is R5's floor and not an error.
          */
          emptyLabel="No types of workshop are set up. An administrator can add them."
          ariaLabel="Type of workshop"
          disabled={saving}
          describedBy={hintId}
          /*
            NO FOCUS ADVANCE. `advanceOnSelect` exists for a box you fill in and move past; this one
            you adjust and then READ THE RESULT OF, in the control immediately below, whose list is
            being re-fetched at that moment. Jumping focus into a list mid-flight is how a reader
            picks the first row of the previous answer.
          */
          advanceOnSelect={false}
        />
      </Field>
      {/*
        THE TWO THINGS THE READER CANNOT SEE.

        1. THAT IT IS NOT SAVED. The box looks exactly like the ones that are. R3 asks for this
           sentence by name, and the control it replaces carried the same one for the same reason.
        2. WHICH LIST THIS IS. DROPDOWN_DESIGN R3 — a silently short list reads as "there are only
           these", which this repository names as its most repeated bug class. A browser that has
           never reached `/workshop-types` is holding six built-in labels and must say so, because
           an administrator may have added a seventh programme that simply is not here.
      */}
      <p id={hintId} className="text-xs text-ink-500">
        {view.typesServed
          ? "Chooses which workshops the box below lists. It is not saved on this record — the workshop you pick already carries its own type."
          : "These are this app's built-in types of workshop — connect once to refresh them. They choose which workshops the box below lists, and are not saved on this record."}
      </p>
      {/*
        R2: THE SECOND BOX, AND THERE IS NEVER A THIRD. One of these two, decided by the flag on the
        chosen type — `DesignWorkshop` rows saved to `designWorkshopId`, or `Workshop` rows saved to
        `workshopId`. Both are labelled "Workshop": the reader is answering one question, and a label
        that changed with the type would be the two-pickers-for-one-question problem this release
        exists to end, wearing one control.

        `NO_FIELD_WORKSHOP` on both, for the same reason (R5). The design half's own default row says
        "Not filed under a design workshop", which is right when it is one of two boxes on a form and
        wrong when it is the only one — one question, one spelling of "no".
      */}
      {routesToDesignWorkshop ? (
        <DesignWorkshopSelect
          state={view.design}
          initial={view.designInitial}
          label="Workshop"
          noneLabel={NO_FIELD_WORKSHOP}
          onDirty={onDirty}
          saving={saving}
        />
      ) : (
        <WorkshopSelect state={view.field} label="Workshop" onDirty={onDirty} saving={saving} />
      )}
    </div>
  );
}
