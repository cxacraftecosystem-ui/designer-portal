/**
 * WHAT THE "Linked workshop record" PICKER SHOWS AND SAYS — the pure half of the edit form's link.
 *
 * A PLAIN `.ts` MODULE BESIDE THE COMPONENT, for the reason `headerDiff.ts` already gives for the
 * other half of this form: `DesignWorkshopHeaderForm` is `"use client"` and imports React,
 * `next/navigation`, `lucide-react` and an IndexedDB store, so a Node spec cannot pull it in, and a
 * decision that lives inside a hook cannot be asserted at all in this repository — there is no React
 * renderer in `devDependencies`, Playwright is the whole of it. The defect this module exists to
 * close is invisible in a screenshot and invisible in a browser test that has a working connection,
 * so it has to be reachable from `npm run test:unit` or it is not checked.
 *
 * ── THE DEFECT ─────────────────────────────────────────────────────────────────────────────────
 *
 * The picker used to hold `Workshop[]`, initialised to `[]`, filled by a `.then` and left untouched
 * by a `.catch`. Everything downstream branched on `rows.length`. So a 500, a timed-out request, a
 * dead connection and a genuinely empty answer all produced the identical, confident sentence
 *
 *     "No design & prototype workshops are open to this account, so there is nothing to link to."
 *
 * — a claim about a grant table, made from a request that never arrived. That is this repository's
 * most-repeated bug class (SKILL §17, "absence read as non-existence"), and `lib/workshopOptions.ts`
 * exists to end it: {@link WorkshopListState} is the three-way answer, and `workshopListNotice` and
 * `workshopEmptyLabel` are the four sentences. NOTHING IN THIS FILE WRITES A STATE SENTENCE OF ITS
 * OWN. It chooses between that module's, and adds the two facts that module has no vocabulary for
 * and could not have: what a record filed under a workshop nobody can name should read as, and who
 * is allowed to mark a workshop as a Design & Prototype Development Workshop in the first place.
 *
 * ── AND THE SECOND FACT IS THE ONE THAT ACTUALLY ANSWERS THE OWNER'S REPORT ─────────────────────
 *
 * `Workshop.workshopType` is `@default(OTHER)` and its migration performs no backfill — the enum's
 * own comment says "the list is unchanged unless somebody marks a row". So on a deployment where
 * nobody has opened `/workshops` and set the Kind, this picker correctly offers nothing to
 * EVERYONE, admins included, and the server is not at fault. The old sentence's remedy — "Mark one
 * on the Workshops page" — is an act `can_manage_workshops` gates at `has_rank(user, "PROFESSOR")`,
 * rank 40, and a DESIGNER is rank 35 (`backend/app/core/deps.py`). So the one screen that told a
 * designer why the list was empty told them to go and do something the API refuses them, on a page
 * whose form is hidden from them. {@link LINKED_WORKSHOP_KIND_GAP} is that sentence corrected: it
 * names the narrowing the shared "no workshops are open to this account" line cannot know about,
 * and it names who can act. Widening the scope to fill the picker up would have been the other
 * repair, and it is the wrong one — the rows genuinely are not design-prototype workshops.
 */

import type { SelectOption } from "@/components/ui/selectFilter";
import {
  fieldWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type FieldWorkshopRow,
  type WorkshopListState,
  type WorkshopListVoice,
  type WorkshopOptionSet
} from "@/lib/workshopOptions";

/**
 * WHAT THE LIST IS, printed whenever it holds something — the sentence that was already on this
 * screen, kept word for word.
 *
 * It is NOT one of `workshopOptions`' four state sentences and must not be confused with them:
 * those describe what the read ANSWERED, this describes what the request ASKED FOR. Both narrowings
 * are invisible in the rows — a list of four workshops looks the same whether the repository holds
 * four or four hundred — and a scoped list that explains itself nowhere reads, to everybody who does
 * not already know, as a repository with four workshops in it.
 */
export const LINKED_WORKSHOP_SCOPE_SENTENCE =
  "The Workshop row this 22-stage record belongs to. Only workshops filed as a Design & Prototype " +
  "Development Workshop, and only ones you have access to, are offered.";

/**
 * THE SECOND HALF OF "why is it empty", and the half no shared module can write.
 *
 * `workshopListNotice` answers the scope question — "No workshops are open to this account. An
 * administrator can give you access to one." — because `scoped: true` is what
 * `accessibleOnly=true` means. That sentence is true and it is not the whole truth here, because
 * this request carries a SECOND narrowing the module knows nothing about: `workshopType`. An empty
 * answer therefore has two possible causes with two different next moves, and the account reading
 * it can act on neither by itself:
 *
 *   * no workshop is open to this account → an administrator grants access;
 *   * every workshop this account can see is still `OTHER` because nobody has marked one → a
 *     professor or an admin sets the Kind on `/workshops`.
 *
 * The sentence this replaced said "Mark one on the Workshops page", addressed to a DESIGNER who is
 * rank 35 against `can_manage_workshops`' floor of PROFESSOR at 40 — advice that cannot be followed,
 * on the one screen where the reader is already stuck. Naming the rank is what turns the empty state
 * from a dead end into an errand somebody can run.
 */
export const LINKED_WORKSHOP_KIND_GAP =
  "This list also only holds workshops whose Kind is “Design & Prototype Development Workshop”, and " +
  "that Kind is set on the Workshops page by a professor or an administrator — a designer account cannot " +
  "set it. So an empty list here can equally mean the workshops exist and have not been marked yet. Ask a " +
  "professor or an admin to mark the right one, or to give you access to it.";

/**
 * WHAT A RECORD FILED UNDER A WORKSHOP NOBODY WILL NAME READS AS — never a blank trigger.
 *
 * `useRecordOffPage` fetches the stored workshop by id, OUTSIDE the list's scope, and gives up in
 * silence on a 403 or a 404 because on `ToolForm` — where it was written — a required "Craft name"
 * box sits beside the picker still holding the right name. There is no second box here. An
 * unresolvable link would draw the trigger's placeholder, or worse the `noneLabel` row, and both
 * read as NOT LINKED over a record that is perfectly well linked — and the obvious repair for a
 * picker that looks unlinked is to pick something, which is the single action that really does
 * re-point the link and strand every record the five workshop-scoped stage pickers filed under the
 * old one. So the id is offered under a label that says what it is, and choosing it changes nothing
 * (it is already the stored value, so `changedKeys` omits it).
 *
 * `lib/workshopOptions.ts` deliberately has no word for this state — its `OffPageIntent` spells a
 * missing row `row: null` and defines that as "not yet", never "not there", because for the first
 * second of every mount every value is unmatched and a control that acted on the mismatch would
 * synthesise rows out of ids it knows nothing about. This module is where the caller's own answer to
 * that goes: the placeholder is handed IN as a recovered row, so the shared builder still owns the
 * grouping ("Already on this record"), the sort and the cap arithmetic.
 *
 * IT IS DRAWN FROM THE FIRST RENDER, in-flight second included, and that is deliberate rather than
 * sloppy. The two readings a reader can get are "this link has a name I cannot see" (momentarily
 * wrong, self-correcting the instant the by-id read lands) and "this record is not linked"
 * (destructive, and repaired by an action that destroys the link). Only one of those is safe to be
 * wrong about for a second.
 */
export const UNRESOLVED_LINK_LABEL =
  "The workshop this record is filed under — its details are not open to this account";

/** The trigger's word while the first read is outstanding. `WorkshopSelect`'s, verbatim. */
export const LOADING_PLACEHOLDER = "Loading workshops…";

/** The trigger's word once there is a list. `WorkshopSelect`'s, verbatim. */
export const READY_PLACEHOLDER = "Select or type to search";

/** Everything the picker needs to be told. Every field is a fact the component holds; none is read. */
export type LinkedWorkshopPickerInput = {
  /** What `GET /workshops?workshopType=DESIGN_PROTOTYPE&accessibleOnly=true` answered. */
  list: WorkshopListState<FieldWorkshopRow>;
  /**
   * The term the ANSWER is about — never the keystroke. The two differ for the third of a second the
   * debounce runs, and printing a claim about a scope over an answer to a term nobody has sent yet
   * is the same class of lie this whole module is about.
   */
  searchApplied: string;
  /** The term in the box right now. Only {@link LinkedWorkshopView.standingDown} may read it. */
  term: string;
  /** A read is outstanding, INCLUDING while the debounce is still counting. */
  pending: boolean;
  /** `initial.workshopId ?? ""` — what the server says this record is filed under. */
  storedId: string;
  /** `useRecordOffPage`'s answer: the stored workshop, or null while in flight or after a refusal. */
  storedRow: FieldWorkshopRow | null;
  /** `!deviceLooksOffline()`. Passed in so this module stays callable without a `navigator`. */
  online: boolean;
};

export type LinkedWorkshopView = {
  /** The shared builder's answer, kept whole so a caller can report its numbers. */
  set: WorkshopOptionSet;
  /** `set.options`, for the control. No `value: ""` row — the primitive draws that from `noneLabel`. */
  options: SelectOption[];
  /** The one sentence under the control: a state sentence, the scope sentence, or "". */
  notice: string;
  /** The extra sentence for a genuinely empty list, or "". See {@link LINKED_WORKSHOP_KIND_GAP}. */
  gap: string;
  /** The line inside the panel. Never the literal "No options". */
  emptyLabel: string;
  /** What the cut left out, with the number. "" when the list is whole. */
  cut: string;
  /** R2/R3: nothing to pick, so the control is disabled and the sentence above says why. */
  standingDown: boolean;
  placeholder: string;
  /** The stored link is drawn as a bare id under {@link UNRESOLVED_LINK_LABEL}. */
  unresolved: boolean;
};

/**
 * The whole picker, decided.
 *
 * ── WHY `fieldWorkshopOptions` AND NOT `designWorkshopOptions` ─────────────────────────────────
 *
 * Because the row being picked is a `Workshop`, not a `DesignWorkshop`. `lib/workshopOptions.ts`'s
 * header is explicit that "workshop" names two tables with two access systems and two option scopes,
 * that there are two builders and there will never be one, and that a single builder over a union of
 * both would offer a designer two rows that mean different things, gate differently and save into
 * different columns. `DesignWorkshopHeaderForm` edits a `DesignWorkshop` and this control links it to
 * a `Workshop` row, so the FIELD builder is the correct one of the two — the same one the create
 * form's "Start from a recorded workshop" picker uses over the identical request.
 *
 * `group: true` because the request narrows by `workshopType` alone: it says nothing about whether a
 * workshop's window has closed, so an ended one still needs its own heading (and is still offered,
 * never `disabled` — filing a 22-stage record against a workshop that ran last month is the ordinary
 * case, not the exception).
 *
 * `offPage: "recover"` and not `"refuse"`, and the distinction is the one `OffPageIntent` says only
 * the caller can make: `workshopId` here is a value STORED ON A RECORD and this control describes a
 * read that is already true. "refuse" belongs to `AdoptLocalDraftDialog`, where the picked row is a
 * one-way, unrepeatable DESTINATION. Refusing here would blank the trigger over a filed record.
 */
export function linkedWorkshopView(input: LinkedWorkshopPickerInput): LinkedWorkshopView {
  /**
   * `scoped: true` because the request carries `accessibleOnly=true`, which is exactly the condition
   * `WorkshopListVoice.scoped` documents. It picks "No workshops are open to this account" over "No
   * workshops have been recorded yet", and the two must never collapse: sending a designer to an
   * administrator because the repository is empty wastes a day, and telling them the repository is
   * empty when they merely hold no grants makes them create a duplicate of a workshop that exists.
   */
  const voice: WorkshopListVoice = { table: "field", scoped: true, online: input.online };

  const stored = input.storedId.trim();
  const listed = input.list.kind === "ok" ? input.list.rows : [];
  const onPage = stored !== "" && listed.some((row) => row.id === stored);
  /** The by-id read has not produced the row, and the list does not hold it either. */
  const unresolved = stored !== "" && !onPage && input.storedRow === null;

  /*
    WHAT IS HANDED TO THE BUILDER AS THE RECOVERED ROW. Three cases, and the third is the one that
    matters: the real row when the by-id read answered, nothing when the list already holds it (the
    builder would drop a duplicate anyway, but passing it invites a reader to think it might not),
    and the SYNTHESISED id-only row when neither — see UNRESOLVED_LINK_LABEL for why a blank trigger
    is the one outcome that is not allowed.
  */
  const recovered: FieldWorkshopRow | null =
    stored === "" || onPage ? null : (input.storedRow ?? { id: stored, title: UNRESOLVED_LINK_LABEL });

  const set = fieldWorkshopOptions(input.list, {
    group: true,
    offPage: { mode: "recover", row: recovered }
  });

  /*
    THE STATE SENTENCE IS SUPPRESSED WHILE THE ANSWER IS ABOUT A TERM, and this guard is the whole
    reason `searchApplied` is an input. "No workshops are open to this account" is a claim about a
    SCOPE; over an answer to "zzz" it is simply false, and false in the direction that sends a
    designer to an administrator about access they already have. The panel says the true thing in
    that state — `serverNoMatchSentence`, a claim about the whole list because the term went to the
    server — and says it where the reader is looking. A FAILED read still speaks, term or no term:
    the read failing is not something the box did.
  */
  const stateNotice = input.list.kind === "ok" && input.searchApplied ? "" : workshopListNotice(input.list, voice);
  const hasRows = input.list.kind === "ok" && input.list.rows.length > 0;
  const notice = stateNotice || (hasRows ? LINKED_WORKSHOP_SCOPE_SENTENCE : "");

  /*
    ONLY OVER A REAL, UNNARROWED, ANSWERED EMPTY. Not while loading (nothing is known), not after a
    failure (the read is the thing that failed, and telling somebody to go and get a workshop marked
    because the network dropped is a wasted errand), and not over a search that matched nothing (the
    Kind filter is not why "zzz" found no rows).
  */
  const gap =
    input.list.kind === "ok" && !input.searchApplied && input.list.rows.length === 0
      ? LINKED_WORKSHOP_KIND_GAP
      : "";

  /*
    NOT WHILE THE BOX HOLDS A TERM, which is not a softening of R3 but the only way to obey it here:
    the filter box lives INSIDE the panel, so disabling the trigger makes the panel unopenable and
    the term unclearable, and a reader who typed something that matched nothing would be locked out
    of the control by their own keystroke with no way back to the full list. A read still in flight
    stands nothing down either — "there is nothing to pick" is a claim, and mid-flight it is not one
    this knows to be true. `workshopListStandsDown` takes the OPTIONS and not the state on purpose: a
    failed read that still recovered the record's own workshop is not an empty control, and standing
    it down would leave a designer looking at a correct value they cannot change.
  */
  const standingDown = !input.pending && !input.term.trim() && workshopListStandsDown(set);

  return {
    set,
    options: set.options,
    notice,
    gap,
    emptyLabel: workshopEmptyLabel(input.list, voice),
    /*
      `searchable: true` — and it is true for the first time on this control. The argument means "does
      this control's box reach past the cut", not "is there a box on screen": until this pass the box
      filtered the eighty rows already fetched, so a designer typing the title of a marked workshop
      that sits at row 90 was answered "No matches" about a workshop that exists, and the next thing a
      person does after "no matches" is pick something else — which on THIS control re-points a stored
      link. The box now goes to the server's `search`, AND-ed with both narrowings, so the sentence
      may finally say "Keep typing to narrow the list".
    */
    cut: workshopCutSentence(set, { term: input.searchApplied, searchable: true }),
    standingDown,
    placeholder: input.list.kind === "loading" ? LOADING_PLACEHOLDER : READY_PLACEHOLDER,
    unresolved
  };
}
