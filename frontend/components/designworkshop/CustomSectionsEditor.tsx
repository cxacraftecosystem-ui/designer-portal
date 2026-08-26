"use client";

/**
 * Where a workshop's own questions are written, and where the price of every edit is stated first.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * THE WHOLE SCREEN IS SHAPED BY ONE SERVER RULE: A REPLACE IS NOT A DELETE.
 *
 * `PUT /design-workshops/{id}/custom-sections` takes the WHOLE definition in one write, and what is
 * absent from that body is retired if it has answers and removed only if it does not. Rewording a
 * question that has been answered SUPERSEDES it: the original is retired under its own wording, keeping
 * its answers, and the new wording becomes a new question with a new key. The failure that rule exists
 * to prevent is written out in `services/questionnaire_forms.py` and it is worth repeating on every
 * screen that can cause it: *"How many looms?" answered "12", reworded to "How many weavers?", and a
 * ministry report now states there are twelve weavers.*
 *
 * **SO EVERY ONE OF THOSE CONVERSIONS IS SHOWN BEFORE THE SAVE, NOT EXPLAINED AFTER IT.** The server
 * does not refuse — it converts, and a designer who presses Save and is only then told what happened to
 * their question has been overruled by a machine on a screen about their own instrument. The
 * questionnaire editor's `QuestionRow` reached the same conclusion from the same rule; this is that
 * argument applied to a typed field, and `diffCustomDefinition` is where the prediction lives.
 *
 * FOUR RULES REACH THE SCREEN BECAUSE THE SERVER ENFORCES THEM AND A DESIGNER MUST NOT MEET THEM AS A
 * 422:
 *
 *  1. **A key is permanent.** It is what the answer is stored under; renaming one orphans every answer
 *     already given. So the key box is offered once, seeded from the label, and locked the moment the
 *     question has been saved.
 *  2. **Only a BASIC question may be required.** The tiers exist so a workshop held in a village with no
 *     power can still produce a complete report, and a required Standard question makes the completeness
 *     gate unsatisfiable exactly where the app is most needed.
 *  3. **A duplicate label on one stage is refused**, and the reason is worth showing rather than
 *     hiding: `missing` holds LABELS and is de-duplicated, so two questions sharing one label collapse
 *     into a single row on the readiness screen and in the report's Outstanding column while the count
 *     beside it still says two — a document disagreeing with itself about its own arithmetic.
 *  4. **A section belongs to a stage, and an answered section cannot move.** Its answers live in the
 *     container of the stage it is asked at, so moving it would leave them behind: still stored, no
 *     longer asked, no longer scored, invisible on every form. That one the server refuses outright
 *     rather than converting, so the editor says so before the button is pressed.
 *
 * ────────────────────────────────────────────────────────────────────────────────────────────────
 * WHY THE AUTHORING LIVES ON THE WEB AND NOT ON THE HANDSET, stated here because this is the screen
 * that would otherwise look like an arbitrary asymmetry. Writing a definition is a write to a
 * server-owned contract whose outcome depends on ANSWER ROWS THE HANDSET DOES NOT HOLD — whether a
 * field is retired or deleted, whether a rewording supersedes — and this repository already refuses
 * exactly that class of offline write, in writing, in `WorkshopRepository.kt`: *"a cached copy of a
 * questionnaire cannot know that a question was retired an hour ago … the reconciliation would then have
 * to either refuse the batch (losing the sitting) or attach it to the new wording (fabricating
 * evidence)."* A keyboard, a connection, and a person who can read the 422 is the right place for it.
 *
 * NO NEW ROUTE GUARD ROW. `routeMatches` matches whole segments and their descendants, so the existing
 * `/design-workshops` guard (`canRunDesignWorkshops`, mirroring `can_run_design_workshops` in `deps.py`)
 * already covers this URL. Editing needs `_require_designer` plus edit rights on the workshop — the same
 * pair `save_stage_data` uses, deliberately and with nothing added, because a definition decides what a
 * stage asks and is therefore a stage write. A second rule with the same predicate would be one more
 * place to forget when the predicate changes.
 *
 * NOTHING HERE IS AVAILABLE OFFLINE, and it says so through the server's own refusal rather than by
 * pretending. A definition edit cannot be banked in the outbox: replaying it three days later would
 * apply the retire/supersede rules against answers that have moved on, which is the fabricated-evidence
 * outcome the rule above refuses.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { AlertTriangle, ArrowDown, ArrowUp, GripVertical, Lock, Plus, Trash2 } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { useDragReorder, moveIndex } from "@/components/hooks/useDragReorder";
import { rowAction } from "@/components/RowActions";
import { Dropdown } from "@/components/ui/Dropdown";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import {
  MAX_CUSTOM_DESCRIPTION_CHARS,
  MAX_CUSTOM_FIELDS_PER_SECTION,
  MAX_CUSTOM_HELP_CHARS,
  MAX_CUSTOM_LABEL_CHARS,
  MAX_CUSTOM_SECTIONS,
  MAX_CUSTOM_TITLE_CHARS,
  MAX_CUSTOM_UNIT_CHARS,
  V1_CUSTOM_TYPES,
  answeredCustomKeys,
  blankCustomField,
  blankCustomSection,
  customDefinitionProblems,
  customFieldsForStage,
  customSectionsProblem,
  definitionBody,
  diffCustomDefinition,
  diffIsEmpty,
  editableSections,
  fetchCustomDefinition,
  fieldEditCost,
  liveFields,
  saveCustomDefinition,
  storedSectionHoldsAnswer,
  suggestCustomKey,
  type DwCustomField,
  type DwCustomSection
} from "@/lib/customSections";
import {
  fetchStageRegistry,
  getDesignWorkshop,
  type DwEntryData,
  type DwRegistry,
  type DwTier
} from "@/lib/designWorkshops";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";

const TIERS: DwTier[] = ["BASIC", "STANDARD", "ADVANCED"];

/**
 * What each type is called on this screen.
 *
 * PLAIN WORDS AND NOT THE SERVER'S TOKENS, because the token is what the answer is stored as and the
 * label is what a designer chooses by. The tokens are still what travels on the wire — nothing here
 * translates a value, only a name.
 */
const TYPE_LABEL: Record<string, string> = {
  TEXT: "Short text",
  LONG_TEXT: "Long text",
  INT: "Whole number",
  DECIMAL: "Number",
  MONEY: "Money",
  PERCENT: "Percentage",
  DATE: "Date",
  TIME: "Time",
  BOOL: "Yes / No",
  ENUM: "Choose one",
  MULTI_ENUM: "Choose several",
  TAGS: "Tags"
};

/** The types that carry an option list, and the only ones whose option box is drawn. */
const OPTION_TYPES = new Set(["ENUM", "MULTI_ENUM"]);
const BOUNDED_TYPES = new Set(["INT", "DECIMAL", "MONEY", "PERCENT"]);

export function CustomSectionsEditor({ workshopId }: { workshopId: string }) {
  const confirm = useConfirm();
  const router = useRouter();

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  /** What the server holds, kept beside the draft so every edit's cost can be worked out. */
  const [stored, setStored] = useState<DwCustomSection[]>([]);
  /*
    THE DIGEST OF THE COPY ABOVE, AND IT IS SENT BACK ON SAVE. Without it the PUT is last-write-wins
    over the whole definition: a colleague's section added while this tab was open is REMOVED by this
    tab's Save, under a 200, with nothing on either screen. Held as its own state rather than derived
    from `stored`, because the digest includes retired fields and is minted by the server — a client
    that computed its own would disagree the first time a question was superseded.

    Null until the first load resolves. `save()` cannot be reached before then (the button is inside
    the loaded view), but it is typed nullable rather than defaulted to "" so that an empty string
    could never be mistaken for a real digest and sent.
  */
  const [storedVersion, setStoredVersion] = useState<string | null>(null);
  const [sections, setSections] = useState<DwCustomSection[]>([]);
  /** `{stage key: the field keys that hold an answer}` — the hinge of the whole edit-after-answers rule. */
  const [answered, setAnswered] = useState<Record<string, Set<string>>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  /**
   * The server's own refusals, as a LIST rather than a sentence.
   *
   * A 422 from `PUT /custom-sections` carries EVERY rule the definition breaks, deliberately, and
   * flattening them into one string would undo the reason the route was written that way — a designer
   * fixing a form one round trip at a time gives up on the third. See {@link customSectionsProblem}.
   */
  const [refusals, setRefusals] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  /**
   * Why a drag did nothing, when it did nothing on purpose.
   *
   * A gesture that is silently ignored is indistinguishable from a gesture that is broken, and this
   * one is ignored for a real reason (see the drag block below). Kept apart from `notice`, which
   * reports what a SAVE did: mixing "the server accepted your definition" and "that drag was not
   * allowed" into one line would make each of them read as the other's aftermath.
   */
  const [crossSectionRefusal, setCrossSectionRefusal] = useState<string | null>(null);
  /**
   * The id the SERVER knows this workshop by, which is not always the one in the URL.
   *
   * **A WORKSHOP MADE IN A COURTYARD IS REACHED BY ITS LOCAL ID FOR THE REST OF ITS LIFE.** Every link
   * on the stage index carries whatever id the browser is holding, and for a workshop this device
   * created that is a `local-…` string the server has never seen; the draft record keeps the server's
   * own id beside it once the sync has been. Reading the URL straight through — which compiles, and
   * works perfectly for every workshop that arrived from the repository — made this screen answer 404
   * for exactly the workflow the app exists for: a workshop captured offline, synced on the drive home,
   * and then extended with the designer's own questions from the local list. `codes/page.tsx` resolves
   * it the same way and for the same reason.
   */
  const [remoteId, setRemoteId] = useState<string | null>(null);
  /** True when this workshop exists only here, so there is nothing on the server to attach questions to. */
  const [localOnly, setLocalOnly] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      /*
        THE LOCAL DRAFT IS READ FOR EVERY WORKSHOP, NOT ONLY FOR ONE WITH A `local-…` ID.

        It used to be fetched only to resolve `remoteId`, so the `isLocalWorkshopId` guard was exactly
        right for that one purpose and exactly wrong for the other thing this draft holds: THE ANSWERS
        THIS DEVICE HAS NOT SENT YET. `loadDraft` matches either id (see `matchesId`), so one
        unconditional read serves both, and the second use is the one that decides whether a question
        may be offered a free Delete. What the guard cost is written out on {@link answeredCustomKeys}.

        Null is an ordinary answer here and not a failure: a colleague's workshop opened on this laptop
        for the first time has no local copy, and the server's buckets are then the whole truth
        available — which is the state this screen was always in before.
      */
      const draft = await loadDraft(workshopId);
      const target = draft?.remoteId ?? (isLocalWorkshopId(workshopId) ? null : workshopId);
      if (!target) {
        // NOT A REFUSAL AND NOT AN ERROR. There is no server record to attach a definition to yet, which
        // is a state with a date on it rather than a fault: the workshop is created, with everything in
        // it, on the next connection. Saying that is the difference between a designer waiting for the
        // sync and a designer retyping a fortnight of work into a second workshop.
        setLocalOnly(true);
        return;
      }
      setLocalOnly(false);
      setRemoteId(target);
      /*
        THREE READS, AND THE THIRD IS WHAT MAKES THIS SCREEN HONEST.

        The registry names the 22 stages a section may be attached to. The definition is what is being
        edited. The WORKSHOP is read for its stage buckets, because "does this question have an answer"
        is what decides whether an edit retires or deletes, supersedes or merely edits — and that fact
        lives in the answers, not in the definition. Without it the editor could only offer a Delete the
        server converts into a retire and explain afterwards.

        `refresh: true` on the definition: this screen is where it is WRITTEN, so serving a copy this tab
        cached ten minutes ago would let a designer edit a definition a colleague has since changed and
        meet the difference as a supersede they did not ask for.
      */
      const [spec, definition, detail] = await Promise.all([
        fetchStageRegistry(),
        fetchCustomDefinition(target, { refresh: true }),
        getDesignWorkshop(target)
      ]);
      setRegistry(spec);
      /*
        TWO PIECES OF STATE OFF ONE PAYLOAD, AND THE PROJECTION IS THE LOAD-BEARING HALF. `stored` is
        the WHOLE definition, retired sections and fields included, because that is what every edit's
        cost is decided against — a retired key cannot be re-used, and it is the stored copy that says
        which questions hold answers. `sections` is only what may be typed into. See
        {@link editableSections} for what handing the whole payload to the boxes costs.
      */
      setStored(definition.sections);
      setStoredVersion(definition.customSchemaVersion);
      setSections(editableSections(definition.sections));
      const held: Record<string, Set<string>> = {};
      for (const stage of spec.stages) {
        const onServer: DwEntryData = detail.stages?.[stage.key]?.custom ?? {};
        /*
          BOTH BUCKETS, AND THE SECOND ONE IS THE WHOLE POINT OF READING THE DRAFT ABOVE.

          Asked of the definition's own field list for that stage — including retired fields, because a
          retired field's answer is exactly what makes its key unusable by a new question — and asked of
          the SERVER's answers together with the ones still sitting in this browser. A stage held back by
          an upload that has not finished is answered as far as the designer is concerned, and they are
          right: the answer exists, it is on this device, and it is going to the server the moment the
          photograph on it lands. Judging it by the server's copy alone is how this screen came to offer
          a free Delete for a fortnight of fieldwork and promise, in the dialog, that nothing was lost.
        */
        held[stage.key] = answeredCustomKeys(
          customFieldsForStage(definition, stage.key),
          onServer,
          draft?.stages?.[stage.key]?.custom
        );
      }
      setAnswered(held);
      setRefusals([]);
    } catch (err) {
      setRefusals(customSectionsProblem(err, "Unable to load this workshop's own questions"));
    } finally {
      setLoading(false);
    }
  }, [workshopId]);

  useEffect(() => {
    void load();
  }, [load]);

  const stageOptions = useMemo(
    () => (registry?.stages ?? []).map((stage) => ({ value: stage.key, label: `${stage.number}. ${stage.title}` })),
    [registry]
  );

  /** Every key in the draft, so a suggested key cannot collide with one already in use. */
  const takenKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const section of sections) for (const field of section.fields) keys.add(field.key);
    for (const section of stored) for (const field of section.fields) keys.add(field.key);
    return keys;
  }, [sections, stored]);

  /*
    THE REGISTRY IS PASSED, AND WITHOUT IT TWO SERVER RULES WERE UNCHECKED. Audit 2026-08-15 (MINOR).

    `registry` has been in this component's state all along and was used only to build the stage
    picker. Meanwhile `customDefinitionProblems` could not mirror the two stage-scoped collision
    checks the server enforces — a custom key equal to a live field key of that stage, and a custom
    label case-folding equal to a live label of that stage's singleton entity — because neither it
    nor `fieldProblems` had ever been given a stage to check against. `problems` was therefore empty
    for a colliding definition and the Save gate below was open, so a whole-set PUT was refused after
    the fact, taking every unrelated edit in the same body with it.

    `?? []` rather than skipping the memo: with no registry loaded the two checks simply do not run,
    which is the honest degradation. A pre-flight validator may never refuse something the server
    would accept, so absent evidence must mean silence and not a refusal.
  */
  const problems = useMemo(
    () => customDefinitionProblems(sections, registry?.stages ?? []),
    [sections, registry]
  );
  const diff = useMemo(() => diffCustomDefinition(stored, sections, answered), [stored, sections, answered]);
  /** What the boxes held when this screen was last in step with the server. The Discard target. */
  const baseline = useMemo(() => editableSections(stored), [stored]);
  /**
   * Has anything changed — asked of the BYTES THAT WOULD BE SENT and of nothing else.
   *
   * **NOT `diffIsEmpty(diff)`, WHICH IS A DIFFERENT QUESTION AND WAS THE WRONG GATE.** A diff carries
   * only what a save CONVERTS or what the designer must be warned about (created, superseded, retired,
   * removed, retyped); rule 2 on the server is that an answered question's help, required flag, unit,
   * bounds and position are free to change — those five, which is the service's own list and does NOT
   * include the type — and a section's heading, description and stage likewise. None of those appears in
   * a diff, so gating Save on an empty diff disabled the button for every one of those edits while the
   * screen said "Nothing has changed yet": a form in which a typo in a question could be corrected on
   * screen and never saved.
   *
   * Compared through {@link definitionBody} rather than by deep-equalling the state, because that is
   * exactly the document the PUT carries: it drops the keys the envelope forbids, and it assigns
   * `sortOrder` from the POSITION, so a reorder reads as a change and a stored number that nobody
   * moved does not.
   */
  const dirty = useMemo(
    () => JSON.stringify(definitionBody(sections)) !== JSON.stringify(definitionBody(baseline)),
    [sections, baseline]
  );
  const nothingToDo = diffIsEmpty(diff);

  /*
    THE DEFINITION LIVES IN `sections` AND NOWHERE ELSE — no draft store, no localStorage, no
    IndexedDB — so an unmount is a deletion. `dirty` above already knows, to the byte, that there is
    work the server has not got; until these two blocks existed it was consulted by three cosmetic
    things (the Save button's disabled state, Discard's, and the "Nothing has changed yet." caption)
    and by no exit at all. A designer who authored three sections across stages 6, 11 and 17 and then
    tapped the round back arrow to check what stage 11 already asks came back to the definition as it
    was before she started.

    `useLeaveGuard` covers the header's back arrow, which is the exit that was reported. `beforeunload`
    covers closing the tab and reloading, which no interceptor can see. Neither covers the header's
    own "All 22 stages" link, because that is rendered by the page above this component and a plain
    `<Link>` is not a `BackButton` — see the follow-up noted with this fix.
  */
  const [leavePromptOpen, setLeavePromptOpen] = useState(false);
  useLeaveGuard(dirty, () => setLeavePromptOpen(true));

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const isAnswered = useCallback(
    (section: DwCustomSection, key: string) => Boolean(answered[section.stageKey]?.has(key)),
    [answered]
  );

  /**
   * Will the SERVER read this section as answered — the one question four controls on this screen are
   * decided by, asked once and asked in the library.
   *
   * {@link storedSectionHoldsAnswer} is `plan_definition`'s own expression and carries the reasoning for
   * each of its three halves; what belongs here is why it is not written out again below. This screen had
   * FOUR in-component answers to this question — the button's label, the dialog's, the heading's and the
   * stage dropdown's — and three of them were the narrow `liveFields`-over-the-editable-copy test, which
   * cannot see a retired field and so reads the commonest answered section in the system as untouched.
   * Wired to one exported predicate they cannot disagree with each other, and the predicate can be pinned
   * by `custom-sections-unit.spec.ts` — which the four could not, there being no rendering test for this
   * screen.
   */
  const sectionIsAnswered = useCallback(
    (section: DwCustomSection): boolean => storedSectionHoldsAnswer(stored, section.key, answered),
    [answered, stored]
  );

  /**
   * The questions of one section that are no longer asked — ALL of them, answered or not.
   *
   * **NOT `retiredFields`, WHICH ANSWERS A DIFFERENT QUESTION.** That one is for a FORM, which prints a
   * retired question only where an answer stands against it: a crossed-out row with nothing under it is
   * noise on a screen a designer is filling in. Here the list is the answer to "where did my question
   * go, and why will it not let me use that key again", and an unanswered retired question — every
   * other question of a section that was retired because ONE of them had been answered — is exactly the
   * one a designer would otherwise re-declare and be refused by name.
   */
  const retiredIn = useCallback(
    (sectionKey: string): DwCustomField[] =>
      (stored.find((candidate) => candidate.key === sectionKey)?.fields ?? []).filter((field) => field.retired),
    [stored]
  );

  /** The sections that are no longer asked. Shown, never sent — see {@link definitionBody}. */
  const retiredSections = useMemo(() => stored.filter((section) => section.retired), [stored]);

  /** The stored field this draft field descends from, or undefined for one that is new. */
  const storedField = useCallback(
    (section: DwCustomSection, key: string): DwCustomField | undefined =>
      stored
        .find((candidate) => candidate.key === section.key)
        ?.fields.find((field) => field.key === key && !field.retired),
    [stored]
  );

  function patchSection(index: number, patch: Partial<DwCustomSection>) {
    setSections((current) => current.map((section, at) => (at === index ? { ...section, ...patch } : section)));
  }

  function patchField(sectionIndex: number, fieldIndex: number, patch: Partial<DwCustomField>) {
    setSections((current) =>
      current.map((section, at) =>
        at === sectionIndex
          ? {
              ...section,
              fields: section.fields.map((field, fieldAt) =>
                fieldAt === fieldIndex ? { ...field, ...patch } : field
              )
            }
          : section
      )
    );
  }

  /* ══════════════════════════════════════════════════════════════════════════════════════════════
     DRAG AND DROP, ON TOP OF THE ARROWS — NOT INSTEAD OF THEM
     ══════════════════════════════════════════════════════════════════════════════════════════════

     The owner asked on 2026-08-25 for reordering to be served by "a plus button, up down arrows and
     drag and drop as well". The plus buttons and the arrows were already here; this is the third.

     THE ARROWS REMAIN THE PRIMARY PATH and are never hidden or disabled in favour of the grip. A
     drag is a pointer gesture: it is unreachable from a keyboard, from a switch device and from a
     screen reader, so drag ALONE would put the arrangement of a ministry report's sections behind a
     mouse. `RankableList` made the same call for the same reason and the wording there is the same.

     ── WHY ONE HOOK PER LEVEL, WITH COMPOSITE IDS, AND NOT ONE PER SECTION ─────────────────────────

     The Rules of Hooks. The question rows are rendered inside `sections.map(...)`, so a
     `useDragReorder` per section would be a hook called in a loop whose count changes when a section
     is added — which React refuses, correctly. So there are exactly two hooks: one over the
     sections, and ONE over every question in the workshop, whose ids encode the section they belong
     to (`sectionIndex:fieldIndex`).

     That choice has a consequence the reader has to be told about, because it bit once: the question
     hook's `order` is NOT the list on screen, so its default announcement would quote a position
     against the workshop's whole question count. `describeMove` below is what fixes that.

     ── AND WHY A CROSS-SECTION DROP IS REFUSED RATHER THAN PERFORMED ───────────────────────────────

     A single question list spanning every section means the geometry can perfectly well name a
     target row in a DIFFERENT section, and it would be easy to let that move the question across.
     It is refused, out loud, because moving a question between sections is not a reorder at all: a
     custom answer is stored in a per-(workshop, stage) container keyed by the field key, and the
     section decides both the stage it is asked at and the key namespace it is unique in. Dragging
     "How many looms?" from a stage-5 section into a stage-17 one would silently re-file every answer
     already recorded under it — the class of thing `custom_sections.py`'s supersede/retire rules
     exist to make impossible. If it is ever wanted it needs the server's consent, not a gesture.
  */

  /**
   * The sections' drag ids.
   *
   * `key` IS EMPTY ON A SECTION NOBODY HAS SAVED YET — the server mints it — so the index is part of
   * the identity rather than decoration. That is safe here in a way it would not be in `RankableList`:
   * `sections` is a local editing buffer, nothing outside this component mutates it, and the id only
   * has to be stable for the length of one gesture. The hook's snapshot guard still bites on the case
   * that matters (a row added or removed mid-gesture changes this array).
   */
  const sectionDragIds = useMemo(
    () => sections.map((section, index) => `${section.key || "new"}:${index}`),
    [sections]
  );

  const sectionDrag = useDragReorder({
    order: sectionDragIds,
    locked: busy,
    labelFor: useCallback(
      (id: string) => {
        const index = Number(id.split(":").pop());
        return sections[index]?.title?.trim() || "Untitled section";
      },
      [sections]
    ),
    onReorder: useCallback((from: number, to: number) => {
      setSections((current) => moveIndex(current, from, to));
    }, [])
  });

  /** Every question in the workshop, in render order, tagged with the section it belongs to. */
  const questionDragIds = useMemo(
    () =>
      sections.flatMap((section, sectionIndex) =>
        section.fields.map((_, fieldIndex) => `${sectionIndex}:${fieldIndex}`)
      ),
    [sections]
  );

  const questionDrag = useDragReorder({
    order: questionDragIds,
    locked: busy,
    labelFor: useCallback(
      (id: string) => {
        const [sectionIndex, fieldIndex] = id.split(":").map(Number);
        return sections[sectionIndex]?.fields[fieldIndex]?.label?.trim() || "Untitled question";
      },
      [sections]
    ),
    /**
     * "How many looms? moved to position 3 of 5 in Loom shed." — the position the READER can see.
     *
     * The hook's default sentence would say "position 3 of 37", because `order` here is every
     * question in the WORKSHOP: one hook drives them all, since the Rules of Hooks forbid one per
     * section. A position quoted against a total nobody can see describes nothing, so this composes
     * the section-scoped one. The drag path had that defect from the day it shipped, and the arrows —
     * being silent until today — did not have it at all.
     *
     * IT ALSO NAMES THE SECTION, which the section-level announcement has no need to. A question is
     * identified by its wording AND where it sits, and "moved to position 3 of 5" is ambiguous on a
     * screen holding five sections that each have a third question.
     *
     * BOTH ARGUMENTS ARE PRE-MOVE, and that is what makes it correct. `key` is the id of the question
     * being moved and resolves against the arrangement as it stands; `index` is a position in that
     * same arrangement, and the id currently sitting there carries the within-section slot the
     * question is about to take. Cross-section drops are refused below, so that id is always in the
     * same section as `key`.
     */
    describeMove: useCallback(
      (key: string, index: number) => {
        const [sectionIndex, fieldIndex] = key.split(":").map(Number);
        const section = sections[sectionIndex];
        const question = section?.fields[fieldIndex]?.label?.trim() || "Untitled question";
        const heading = section?.title?.trim() || "Untitled section";
        const landing = Number(questionDragIds[index]?.split(":")[1] ?? index);
        const total = section?.fields.length ?? 0;
        return `${question} moved to position ${landing + 1} of ${total} in ${heading}.`;
      },
      [questionDragIds, sections]
    ),
    onReorder: useCallback(
      (from: number, to: number) => {
        // CLEARED WHERE THE GESTURE STARTS, NOT ONLY WHERE ONE SUCCEEDS. The amber banner further
        // down explains why the LAST drop did nothing, and it used to be cleared only on the accepted
        // same-section path at the foot of this callback. So a refusal outlived the gesture it
        // describes: it sat above the first section through renames, new questions, retirements and
        // whole other drags, still claiming to account for what the designer had just done. Clearing
        // on ENTRY covers every outcome in one line — the refusal below re-sets it in the same batch,
        // and the accepted path needs no clear of its own because it has already fallen through this
        // one.
        setCrossSectionRefusal(null);
        const fromId = questionDragIds[from];
        const toId = questionDragIds[to];
        // `false` REFUSES, so the hook stays silent and does not announce a move that did not happen.
        // An unresolvable id is a refusal too: nothing moved, so nothing may be announced.
        if (!fromId || !toId) return false;
        const [fromSection, fromField] = fromId.split(":").map(Number);
        const [toSection, toField] = toId.split(":").map(Number);
        // See the block comment above: a question does not move between sections by gesture.
        if (fromSection !== toSection) {
          setCrossSectionRefusal(
            "A question can only be reordered inside its own section. Moving one to another section " +
              "would change the stage it is asked at and re-file the answers already recorded under " +
              "it, so it is not something a drag can do."
          );
          // REFUSED, AND THE HOOK IS TOLD SO. Without this the polite live region announced the move
          // as done — with a position from the target section and a total from the source one, so
          // "position 4 of 2" — while the amber banner beside it said it had been refused. The
          // accessible channel was the one that lied.
          return false;
        }
        setSections((current) =>
          current.map((section, at) =>
            at === fromSection ? { ...section, fields: moveIndex(section.fields, fromField, toField) } : section
          )
        );
      },
      [questionDragIds]
    )
  });

  /* ── THE ARROWS SPEAK, AND THEY SPEAK THROUGH THE SAME CHANNEL THE DRAG DOES ───────────────────────

     Both of these used to decide their bounds INSIDE the `setSections` updater and announce nothing,
     which left this screen with the worst possible split: the POINTER gesture spoke and the KEYBOARD
     path — the only one a switch device or a screen reader has — was silent. The arrows are the
     PRIMARY path here, so the silent one was the one that mattered most.

     The bounds check is therefore lifted OUT of the updater, because a sentence cannot be composed
     from inside one. That is safe rather than merely convenient: these are click handlers on buttons
     that are `disabled` at both ends of their list, so the `sections` read here is the committed state
     the button was rendered from and there is no concurrent writer to race.

     DECLARED BELOW BOTH HOOKS, and that is not cosmetic. They read `sectionDragIds` and
     `questionDragIds`, and while a hoisted function declaration would run fine at click time, reading
     a `useMemo` result above its own declaration is a pattern the React Compiler cannot follow — it
     reported "could not preserve existing memoization" against `sectionDragIds` and stopped optimising
     the component. Data flow down the file, controls after the state they read.

     `moveIndex` rather than the element swap they used to do. For a one-step arrow the two are
     identical, so this changes no behaviour — it is here so the arrows and the drag go through ONE
     primitive. The same swap-versus-move divergence was a real cross-client bug in `EntityForm`'s
     rows, where the web swapped and the handset moved: identical for one step, different the moment a
     gesture spans five. */
  function moveSection(index: number, by: -1 | 1) {
    const target = index + by;
    if (target < 0 || target >= sections.length) return;
    // The PRE-move id, which is what makes the label right: `labelFor` resolves the id's index
    // against the `sections` this render closed over, i.e. the arrangement before the move. Section
    // ids are one-per-visible-list, so the hook's own "position N of TOTAL" wording is correct here
    // and is used unchanged.
    sectionDrag.announceMove(sectionDragIds[index], target);
    setSections((current) => moveIndex(current, index, target));
  }

  function moveField(sectionIndex: number, fieldIndex: number, by: -1 | 1) {
    const section = sections[sectionIndex];
    if (!section) return;
    const target = fieldIndex + by;
    if (target < 0 || target >= section.fields.length) return;
    // `announceMove` takes an index into `order`, which for questions is the FLATTENED list across
    // every section — so the within-section target is resolved back to its flat position rather than
    // passed straight through. Getting this wrong would announce a question from another section.
    const flatTarget = questionDragIds.indexOf(`${sectionIndex}:${target}`);
    if (flatTarget >= 0) questionDrag.announceMove(`${sectionIndex}:${fieldIndex}`, flatTarget);
    setSections((current) =>
      current.map((entry, at) =>
        at === sectionIndex ? { ...entry, fields: moveIndex(entry.fields, fieldIndex, target) } : entry
      )
    );
  }

  async function removeField(sectionIndex: number, fieldIndex: number) {
    const section = sections[sectionIndex];
    const field = section.fields[fieldIndex];
    // TWO DIFFERENT CONFIRMATIONS, because they are two different acts. One sentence for both would
    // either frighten somebody deleting a typo or reassure somebody about a deletion that is not
    // happening — `QuestionRow` draws exactly this distinction and for the same reason.
    const ok = await confirm(
      isAnswered(section, field.key)
        ? {
            title: "Retire this question?",
            body: `"${field.label || field.key}" stops being asked. It has answers recorded against it, so it cannot be deleted — those answers stay in the record, print in the report under this wording, and stop counting towards the stage's completeness.`,
            confirmLabel: "Retire question",
            tone: "warning"
          }
        : deleteConfirm(
            "Delete this question?",
            `"${field.label || field.key}" is removed from this section.`,
            // NOT "Nobody has answered it, so nothing is lost." That sentence was an assertion about
            // every device in the cluster made from what two of them hold — the server's answers and
            // this browser's unsent ones — and a handset with a stage still in its own outbox is
            // outside both. It now says what was actually checked, which is a smaller claim and a true
            // one; the designer knows what else is out there and this screen does not.
            "No answer to it has reached the server, and none is waiting to be sent from this browser."
          )
    );
    if (!ok) return;
    setSections((current) =>
      current.map((candidate, at) =>
        at === sectionIndex
          ? { ...candidate, fields: candidate.fields.filter((_, fieldAt) => fieldAt !== fieldIndex) }
          : candidate
      )
    );
  }

  async function removeSection(index: number) {
    const section = sections[index];
    const touched = sectionIsAnswered(section);
    const ok = await confirm(
      touched
        ? {
            title: "Retire this section?",
            body: `"${section.title || section.key}" stops being asked. Answers have been recorded against it, so neither it nor its questions can be deleted — everything already recorded stays readable and prints in the report under the wording it was asked as.`,
            confirmLabel: "Retire section",
            tone: "warning"
          }
        : deleteConfirm(
            "Delete this section?",
            `"${section.title || section.key}" and its ${liveFields(section).length} question(s) are removed.`,
            // The same correction as the per-question dialog above, for the same reason.
            "No answers to them have reached the server, and none are waiting to be sent from this browser."
          )
    );
    if (!ok) return;
    setSections((current) => current.filter((_, at) => at !== index));
  }

  /**
   * Returns whether the definition REACHED THE SERVER.
   *
   * The boolean exists for the unsaved-changes prompt, which has to decide whether leaving is now
   * safe: a 422 or a 409 leaves the draft on screen (see the catch below) and leaving on the back of
   * one would discard exactly the work the refusal is asking the designer to correct.
   */
  async function save(): Promise<boolean> {
    // The SERVER'S id, never the URL's — see {@link remoteId}. Null cannot be reached from the button
    // (the screen renders the local-only sentence instead of the boxes), and it is checked rather than
    // asserted because a PUT to `/design-workshops/local-…/custom-sections` is a 404 a designer can do
    // nothing about.
    if (!remoteId) return false;
    setBusy(true);
    setRefusals([]);
    setNotice(null);
    try {
      // The digest this screen LOADED, not one derived from what is on it. A stale tab is refused
      // with 409 rather than allowed to delete a colleague's questions under a 200.
      const result = await saveCustomDefinition(remoteId, sections, storedVersion);
      setStored(result.sections);
      setStoredVersion(result.customSchemaVersion);
      setSections(editableSections(result.sections));
      // Reloaded rather than patched, because the write changes what is ANSWERED-relevant: a superseded
      // question's answers now sit under the retired key, and the new key has none. Recomputing that from
      // the response alone would be a second implementation of the server's own plan.
      await load();
      /*
        THE SERVER'S OWN COUNTS, SAID PLAINLY. A save that quietly superseded two questions is a save
        whose effect the designer discovers on the next report. `created`/`superseded`/`retired`/`removed`
        are returned for exactly this purpose.
      */
      const parts = [
        result.created ? `${result.created} question${result.created === 1 ? "" : "s"} added` : "",
        result.superseded
          ? `${result.superseded} kept under ${result.superseded === 1 ? "its" : "their"} old wording and re-asked under the new one`
          : "",
        result.retired ? `${result.retired} retired, with the answers already given kept` : "",
        result.removed ? `${result.removed} removed` : ""
      ].filter(Boolean);
      setNotice(parts.length ? `Saved — ${parts.join("; ")}.` : "Saved. Nothing in the definition changed.");
      return true;
    } catch (err) {
      // NOTHING IS DISCARDED ON A REFUSAL. The draft stays exactly as the designer typed it, so the
      // refusal names something they can correct in the boxes still in front of them — throwing the
      // edits away and asking them to start again is how a designer stops using a feature.
      setRefusals(customSectionsProblem(err, "Unable to save these questions"));
      return false;
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <div className="panel p-4 text-sm text-ink-700">Loading this workshop&apos;s own questions…</div>;

  if (localOnly) {
    /*
      A STATE WITH A DATE ON IT, NOT A FAULT, AND IT SAYS SO. A definition is a write to a server-owned
      contract whose outcome depends on the answers already recorded, so it cannot be banked in the
      outbox and replayed days later — that is the fabricated-evidence refusal in the file header, not an
      omission. What a designer needs here is which of the two states they are in and what closes it.
    */
    return (
      <section className="panel p-4">
        <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <p className="text-xs leading-5">
            This workshop exists only on this device — it has not been created on the repository yet, and a
            workshop&apos;s own questions are held there. Everything captured here is safe and nothing is waiting on
            this screen: the workshop is created, with all of it, the moment there is a connection. Come back then
            and the questions can be added, and every answer already recorded stays exactly as it is.
          </p>
        </div>
      </section>
    );
  }

  return (
    <div className="grid gap-5">
      {refusals.length ? (
        <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {refusals.length === 1 ? (
            <p>{refusals[0]}</p>
          ) : (
            <>
              <p className="font-medium">The server refused this definition for {refusals.length} reasons:</p>
              <ul className="mt-1 grid gap-1 text-xs leading-5">
                {refusals.map((problem) => (
                  <li key={problem}>{problem}</li>
                ))}
              </ul>
            </>
          )}
        </div>
      ) : null}
      {notice ? (
        <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">{notice}</div>
      ) : null}

      {/*
        THE TWO LIVE REGIONS ARE RENDERED WHETHER OR NOT THEY HOLD ANYTHING. Assistive technology only
        announces mutations inside a region that already existed when the page settled — the same rule
        `Toast`'s always-mounted viewport follows, and the reason a region created at the moment of the
        announcement says nothing at all.

        TWO AND NOT ONE, because they are two lists. A designer who has just moved a question wants to
        hear about the question; folding both into one region means the second announcement replaces
        the first mid-sentence when a drag on one level follows a drag on the other.
      */}
      <p aria-live="polite" className="sr-only">
        {sectionDrag.announcement}
      </p>
      <p aria-live="polite" className="sr-only">
        {questionDrag.announcement}
      </p>

      {/*
        WHY A DRAG DID NOTHING, WHEN IT DID NOTHING DELIBERATELY — AND THE REGION THAT SAYS SO IS
        MOUNTED BEFORE IT HAS ANYTHING TO SAY.

        THE `role="status"` USED TO BE ON THE AMBER BOX ITSELF, so the region came into existence in the
        same commit as its first sentence — which is the exact defect the two `sr-only` regions eight
        lines above are always-mounted to avoid, and whose comment states the rule in as many words:
        assistive technology only announces mutations inside a region that ALREADY EXISTED. One file
        disagreeing with itself, and the disagreement cost the whole message: a designer using a screen
        reader dragged a question into another section, the drop was declined, nothing on screen moved,
        and nothing was said. This sentence is the ONLY account of why the gesture had no effect —
        without it a deliberate refusal is indistinguishable from a drag that missed — so silence here
        is not a missing nicety, it is the message going unsaid.

        So the region is this element, which never unmounts, and what changes is the CLASS and not the
        node: `sr-only` while there is nothing to say. That keeps it in the accessibility tree, and
        because `sr-only` is absolutely positioned it is out of flow and adds no row to this
        `grid gap-5` — an always-visible empty box would open a 1.25rem hole above the first section.
        Swapping in `hidden` (`display: none`) or remounting the node would take the region back out of
        the tree and put the defect straight back. `EntityForm`'s cap notice and the workshop page's
        submit outcome are the same fix from the same session; a fourth spelling of it is not wanted.

        ITS CHILDREN ARE STILL CONDITIONAL, AND THAT IS NOT A HALF-MEASURE. Dismiss is a real focusable
        control: left mounted inside the empty region it would be an invisible tab stop, on the keyboard
        route between the editor's own toolbar and the first section, offering to dismiss nothing. What
        has to survive is the region's IDENTITY, never its contents.

        `role="status"` AND NOT `alert`, AMBER AND NOT RED: nothing is broken and nothing was lost — the
        question is exactly where it was — so this must not interrupt, and drawing it in the error colour
        would file a refusal under "something went wrong". Dismissable because it is about a gesture that
        is over; unlike the save notice, it has no lasting state to describe.
      */}
      <div
        role="status"
        aria-live="polite"
        className={
          crossSectionRefusal
            ? "flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800"
            : "sr-only"
        }
      >
        {crossSectionRefusal ? (
          <>
            <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
            <p className="min-w-0 flex-1">{crossSectionRefusal}</p>
            <button
              type="button"
              className="shrink-0 text-xs font-medium underline"
              onClick={() => setCrossSectionRefusal(null)}
            >
              Dismiss
            </button>
          </>
        ) : null}
      </div>

      {sections.map((section, sectionIndex) => {
        const fields = section.fields;
        // The questions of this section that are no longer asked. Resolved once: it is read five times
        // below, and a designer with sixty questions in a section should not pay for five walks of them.
        const retiredHere = retiredIn(section.key);
        const stageTitle = stageOptions.find((option) => option.value === section.stageKey)?.label;
        // TWO DIFFERENT QUESTIONS, AND ONLY ONE OF THEM DECIDES ANYTHING. `answeredHere` counts the boxes
        // ON SCREEN that hold answers, which is what a sentence saying "3 of its questions have answers"
        // has to count. `sectionHolds` is the SERVER'S question — does any field of the stored section
        // hold one, retired or not — and every control whose behaviour the server decides is wired to
        // that one, because the two differ in the commonest state there is: a section whose only answered
        // question has since been retired shows nothing on screen and is still retired rather than
        // deleted, and still cannot be moved.
        const answeredHere = liveFields(section).filter((field) => isAnswered(section, field.key)).length;
        const sectionHolds = sectionIsAnswered(section);
        const sectionDragId = sectionDragIds[sectionIndex];
        const sectionShift = sectionDrag.shiftFor(sectionDragId);
        const sectionDragging = sectionDrag.draggingKey === sectionDragId;
        return (
          <section
            key={`${section.key}-${sectionIndex}`}
            ref={sectionDrag.registerRow(sectionDragId)}
            /*
              The transform is inline because it is a live pixel offset, and `transition-transform` is
              a class because the global reduced-motion rules in `globals.css` can reach a CSS
              transition and cannot reach an inline framer style. The dragged panel is lifted with a
              ring rather than an opacity change: a translucent panel over another panel is unreadable,
              and the ring is also the half a reduced-motion reader still gets.
            */
            style={sectionShift ? { transform: `translateY(${sectionShift}px)` } : undefined}
            className={
              sectionDragging
                ? "panel relative z-10 grid gap-4 p-4 shadow-panel ring-2 ring-purple-600/40 transition-transform"
                : "panel relative grid gap-4 p-4 transition-transform"
            }
          >
            <header className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <h2 className="font-display text-lg font-bold text-ink-900">
                  {section.title || "Untitled section"}
                </h2>
                <p className="mt-1 text-xs text-ink-500">
                  {stageTitle ? `Asked at ${stageTitle}.` : "No stage chosen yet."}{" "}
                  {answeredHere
                    ? `${answeredHere} of its questions ${answeredHere === 1 ? "has" : "have"} answers recorded.`
                    : /*
                        THE THIRD STATE IS THE ONE THAT WAS MISSING, and without it this line told a
                        designer the opposite of what the buttons below would do. A section whose only
                        answered question has since been RETIRED shows nothing in `answeredHere` — the
                        count walks the boxes on screen, and a retired question has no box — while the
                        server still reads the section as answered and will retire rather than delete it,
                        and still refuses its keys to any new question. "Freely editable" was then a
                        promise contradicted by the very next dialog.
                      */
                      sectionHolds
                      ? "Its questions on screen are unanswered, but a question no longer asked holds an answer — so this section is kept, not deleted, and its keys cannot be re-used."
                      : "No answers recorded yet, so everything here is still freely editable."}
                </p>
              </div>
              <div className="flex flex-wrap justify-end gap-2">
                {/*
                  THE GRIP ANSWERS THE KEYBOARD TOO, even though the two arrows beside it already do.
                  It is the affordance that LOOKS like reordering, so a reader who finds it must not
                  have to go and find two other buttons — and a handle that swallowed the arrow keys
                  while doing nothing would read as broken. `touch-none` is what stops the browser
                  claiming the gesture as a scroll before the first pointermove arrives.
                */}
                <button
                  type="button"
                  className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                  aria-label={`Reorder ${section.title?.trim() || "this section"} — drag, or use the arrow keys`}
                  disabled={busy || sections.length < 2}
                  {...sectionDrag.handleProps(sectionDragId)}
                  onKeyDown={(event) => {
                    if (event.key === "ArrowUp") {
                      event.preventDefault();
                      moveSection(sectionIndex, -1);
                    } else if (event.key === "ArrowDown") {
                      event.preventDefault();
                      moveSection(sectionIndex, 1);
                    }
                  }}
                >
                  <GripVertical className="h-4 w-4" aria-hidden />
                </button>
                <button
                  type="button"
                  className={rowAction("neutral")}
                  onClick={() => moveSection(sectionIndex, -1)}
                  disabled={busy || sectionIndex === 0}
                >
                  <ArrowUp className="h-3 w-3" aria-hidden />
                  Up
                </button>
                <button
                  type="button"
                  className={rowAction("neutral")}
                  onClick={() => moveSection(sectionIndex, 1)}
                  disabled={busy || sectionIndex === sections.length - 1}
                >
                  <ArrowDown className="h-3 w-3" aria-hidden />
                  Down
                </button>
                <button
                  type="button"
                  className={rowAction("danger")}
                  onClick={() => void removeSection(sectionIndex)}
                  disabled={busy}
                >
                  {/* THE LABEL IS THE HONEST ONE, decided by whether anything here has been answered
                      rather than by what the button does to the array. Offering "Delete" on a section the
                      server will retire instead is the single most misleading control this screen could
                      carry — and it was still doing exactly that, from `answeredHere`, while the dialog
                      one click later correctly said "Retire this section?": the count walks the boxes on
                      screen and a retired question has no box, so the commonest answered section in the
                      system read as untouched. `sectionHolds` is the server's own test. */}
                  {sectionHolds ? (
                    <>
                      <Lock className="h-3 w-3" aria-hidden />
                      Retire
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </div>
            </header>

            <div className="grid gap-3 md:grid-cols-2">
              <label className="grid min-w-0 gap-1">
                <span className="field-label">Heading</span>
                <input
                  className="field-input"
                  value={section.title}
                  maxLength={MAX_CUSTOM_TITLE_CHARS}
                  disabled={busy}
                  onChange={(event) => patchSection(sectionIndex, { title: event.target.value })}
                />
              </label>
              {/* A plain div and not `Field`: a `<label>` forwards a stray click to the first labelable
                  control inside it, which slams a themed dropdown shut after one pick, and it folds every
                  named descendant into the control's accessible name. */}
              <div className="grid min-w-0 gap-1">
                <span className="field-label" id={`stage-${sectionIndex}`}>
                  Asked at
                </span>
                <Dropdown
                  value={section.stageKey}
                  onChange={(next) => patchSection(sectionIndex, { stageKey: next })}
                  options={stageOptions}
                  placeholder="Choose a stage"
                  // LOCKED ON THE SERVER'S OWN TEST AND NOT ON THE BOXES ON SCREEN. `plan_definition`
                  // refuses the move with `answered_here & {f.key for f in previous.fields}` — every
                  // field of the stored section, retired ones included — so a section whose only
                  // answered question has since been retired is one the server will not move while
                  // `answeredHere` reads 0. Left on the narrow count the dropdown opened, the designer
                  // picked another stage, and the screen then said two opposite things at once: a red
                  // block naming answers already recorded, and a heading above it reading "No answers
                  // recorded yet, so everything here is still freely editable". The save was blocked
                  // either way; what the designer could not do was find out which sentence was true.
                  disabled={busy || sectionHolds}
                  ariaLabel="The stage these questions are asked at"
                />
                <p className="text-xs leading-5 text-ink-500">
                  {sectionHolds
                    ? "Fixed: answers have been recorded here, and they are stored with the stage they were asked at. To ask these questions somewhere else, retire this section and add a new one on the other stage — everything already recorded stays readable."
                    : "Where the answers are stored and where they count towards that stage's completeness. If the section should print at the back of the report, it still belongs to the stage it is asked at."}
                </p>
              </div>
              <label className="grid min-w-0 gap-1 md:col-span-2">
                <span className="field-label">Description</span>
                <textarea
                  className="field-input min-h-16"
                  value={section.description}
                  maxLength={MAX_CUSTOM_DESCRIPTION_CHARS}
                  disabled={busy}
                  onChange={(event) => patchSection(sectionIndex, { description: event.target.value })}
                />
              </label>
            </div>

            <ul className="grid gap-3">
              {fields.map((field, fieldIndex) => {
                const before = storedField(section, field.key);
                const answeredField = isAnswered(section, field.key);
                const cost = fieldEditCost(before, { label: field.label }, answeredField);
                const locked = Boolean(before);
                const questionDragId = `${sectionIndex}:${fieldIndex}`;
                const questionShift = questionDrag.shiftFor(questionDragId);
                const questionDragging = questionDrag.draggingKey === questionDragId;
                return (
                  <li
                    key={`${field.key}-${fieldIndex}`}
                    ref={questionDrag.registerRow(questionDragId)}
                    style={questionShift ? { transform: `translateY(${questionShift}px)` } : undefined}
                    className={
                      questionDragging
                        ? "relative z-10 rounded-md border border-purple-600 bg-card p-3 shadow-panel ring-2 ring-purple-600/40 transition-transform"
                        : "relative rounded-md border border-line-200 p-3 transition-transform"
                    }
                  >
                    {cost.kind === "SUPERSEDE" ? (
                      // SHOWN WHILE THE BOX STILL HOLDS THE NEW WORDING AND BEFORE SAVE, not afterwards.
                      // amber-100 / amber-800 are the palette's tinted-card pair; amber-50 and amber-200
                      // are stock Tailwind and do not pair with them.
                      <p className="mb-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                        This question already has answers, and you have changed its wording. Saving will KEEP
                        the original question and its answers as they are — under “{cost.was}” — and add your
                        new wording as a separate question with no answers yet. Nothing is overwritten, and
                        both print in the report. Everything else about it (help text, unit, whether it is
                        required, its position) can be changed freely.
                      </p>
                    ) : null}

                    <div className="grid gap-3 md:grid-cols-2">
                      <label className="grid min-w-0 gap-1">
                        <span className="field-label">Question</span>
                        <input
                          className="field-input"
                          value={field.label}
                          maxLength={MAX_CUSTOM_LABEL_CHARS}
                          disabled={busy}
                          onChange={(event) => {
                            const label = event.target.value;
                            // THE KEY IS SEEDED FROM THE LABEL ONCE AND NEVER FOLLOWS IT. A key that
                            // tracked the wording would silently orphan every answer the moment somebody
                            // corrected a typo in the question — the answers are stored under the key.
                            const patch: Partial<DwCustomField> =
                              !locked && !field.key
                                ? { label, key: suggestCustomKey(label, takenKeys) }
                                : { label };
                            patchField(sectionIndex, fieldIndex, patch);
                          }}
                        />
                      </label>

                      <label className="grid min-w-0 gap-1">
                        <span className="field-label">Stored as</span>
                        <input
                          className="field-input"
                          value={field.key}
                          disabled={busy || locked}
                          onChange={(event) => patchField(sectionIndex, fieldIndex, { key: event.target.value })}
                        />
                        <span className="text-xs leading-5 text-ink-500">
                          {locked
                            ? "Fixed once saved. This is what every answer is filed under, so renaming it would orphan them."
                            : "Lower-case letter first, then letters and digits. It is never shown to anybody, and it cannot be changed after the first save."}
                        </span>
                      </label>

                      <div className="grid min-w-0 gap-1">
                        <span className="field-label">Kind of answer</span>
                        <Dropdown
                          value={field.type}
                          onChange={(next) =>
                            patchField(sectionIndex, fieldIndex, {
                              type: next,
                              // The bounds and the option list are cleared when they stop applying, because
                              // the server refuses a definition that carries a setting it would never check
                              // — the alternative is a stored-and-ignored control, of which this repository
                              // has already had to go back and fix seven.
                              options: OPTION_TYPES.has(next) ? field.options : [],
                              minValue: BOUNDED_TYPES.has(next) ? field.minValue : null,
                              maxValue: BOUNDED_TYPES.has(next) ? field.maxValue : null
                            })
                          }
                          options={V1_CUSTOM_TYPES.map((type) => ({ value: type, label: TYPE_LABEL[type] ?? type }))}
                          disabled={busy}
                          ariaLabel="The kind of answer this question takes"
                        />
                      </div>

                      <div className="grid min-w-0 gap-1">
                        <span className="field-label">Tier</span>
                        {/* No `searchable`: TIERS is Basic / Standard / Advanced — three words,
                            fixed by the schema. The two pickers above it are 22 stages and 12
                            answer kinds and are over the eight-option threshold, so they have filter
                            boxes already; this one does not, and the difference is the list, not an
                            oversight. */}
                        <Dropdown
                          value={field.tier}
                          onChange={(next) =>
                            patchField(sectionIndex, fieldIndex, {
                              tier: next as DwTier,
                              // Required is dropped with the tier rather than left to be refused: only a
                              // Basic question may be required, and a designer who moved a required question
                              // to Advanced meant to move it, not to be told they cannot.
                              required: next === "BASIC" ? field.required : false
                            })
                          }
                          options={TIERS.map((tier) => ({ value: tier, label: tier[0] + tier.slice(1).toLowerCase() }))}
                          disabled={busy}
                          ariaLabel="Which capture tier this question belongs to"
                        />
                      </div>

                      <label className="grid min-w-0 gap-1">
                        <span className="field-label">Help text</span>
                        <input
                          className="field-input"
                          value={field.help}
                          maxLength={MAX_CUSTOM_HELP_CHARS}
                          disabled={busy}
                          onChange={(event) => patchField(sectionIndex, fieldIndex, { help: event.target.value })}
                        />
                      </label>

                      <label className="grid min-w-0 gap-1">
                        <span className="field-label">Unit</span>
                        <input
                          className="field-input"
                          value={field.unit}
                          maxLength={MAX_CUSTOM_UNIT_CHARS}
                          placeholder="metres, kg, days…"
                          disabled={busy}
                          onChange={(event) => patchField(sectionIndex, fieldIndex, { unit: event.target.value })}
                        />
                        <span className="text-xs leading-5 text-ink-500">
                          Printed under the box as “Measured in …”, so the answer is recorded in one unit
                          rather than three.
                        </span>
                      </label>

                      {OPTION_TYPES.has(field.type) ? (
                        <label className="grid min-w-0 gap-1 md:col-span-2">
                          <span className="field-label">Options, one per line</span>
                          <textarea
                            className="field-input min-h-20"
                            /*
                              A LABEL EQUAL TO ITS TOKEN IS NOT PRINTED BACK. `field_payload` emits the
                              RESOLVED label — `CustomOption.display`, which is the token itself when
                              nobody wrote one — so a designer who typed `COTTON` on one line got
                              `COTTON | COTTON` back in this box after the first save, and would have had
                              to delete the half they never wrote on every visit. The two forms mean the
                              same thing to the server either way.
                            */
                            value={field.options
                              .map((option) =>
                                option.label && option.label !== option.value
                                  ? `${option.value} | ${option.label}`
                                  : option.value
                              )
                              .join("\n")}
                            disabled={busy}
                            onChange={(event) =>
                              patchField(sectionIndex, fieldIndex, {
                                options: event.target.value
                                  .split("\n")
                                  .map((line) => line.trim())
                                  .filter(Boolean)
                                  .map((line) => {
                                    const [value, label] = line.split("|");
                                    return { value: value.trim(), label: (label ?? "").trim() };
                                  })
                              })
                            }
                          />
                          <span className="text-xs leading-5 text-ink-500">
                            One option per line. Write <code>COTTON | Cotton (unbleached)</code> to store one
                            token and print another; a line with no printed form prints the token itself,
                            which is the honest behaviour — inventing “Cotton” from <code>COTTON</code> would
                            be guessing at a word that goes into a ministry document.
                          </span>
                        </label>
                      ) : null}

                      {BOUNDED_TYPES.has(field.type) ? (
                        <>
                          <label className="grid min-w-0 gap-1">
                            <span className="field-label">Smallest allowed</span>
                            <input
                              className="field-input"
                              type="number"
                              value={field.minValue ?? ""}
                              disabled={busy}
                              onChange={(event) =>
                                patchField(sectionIndex, fieldIndex, {
                                  minValue: event.target.value === "" ? null : Number(event.target.value)
                                })
                              }
                            />
                          </label>
                          <label className="grid min-w-0 gap-1">
                            <span className="field-label">Largest allowed</span>
                            <input
                              className="field-input"
                              type="number"
                              value={field.maxValue ?? ""}
                              disabled={busy}
                              onChange={(event) =>
                                patchField(sectionIndex, fieldIndex, {
                                  maxValue: event.target.value === "" ? null : Number(event.target.value)
                                })
                              }
                            />
                          </label>
                        </>
                      ) : null}
                    </div>

                    <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          disabled={busy}
                          onClick={() =>
                            patchField(sectionIndex, fieldIndex, {
                              required: !field.required,
                              // Making a question required moves it to Basic rather than being refused: the
                              // designer's intent is plain, and the tier rule exists to keep a required
                              // question answerable in a village, not to make requiring one impossible.
                              tier: !field.required ? "BASIC" : field.tier
                            })
                          }
                        >
                          {field.required ? "Make optional" : "Make required"}
                        </button>
                        <span className="text-xs text-ink-500">
                          {field.required
                            ? "Required, so it is Basic and it blocks submitting this stage until it is answered."
                            : answeredField
                              ? /*
                                  "…everything else can still change" WAS A SENTENCE THIS SCREEN COULD
                                  NOT KEEP. Rule 2 on the server lists five things and the sentence has
                                  to be those five: *"may freely change its help, its required flag, its
                                  unit, its bounds and its position"* — `custom_sections.py`'s module
                                  docstring, repeated by `_plan_fields`' own RULE 2 comment. The kind of
                                  answer is in neither list. Changing it is accepted in silence and
                                  strands the recorded answer in the old type's shape, which comes back
                                  days later as "… is not a valid int" against a value nobody typed and,
                                  under submit, as a stage that will not go.

                                  NAMING THE SERVER'S FIVE RATHER THAN FIVE OF ITS OWN: this said "help,
                                  unit, TIER, bounds and position", which quietly swapped the required
                                  flag — the one of the five with a control right beside this sentence —
                                  for a word the rule does not use.
                                */
                                "Answered — its wording is fixed from here. Its help, whether it is required, its unit, its bounds and its position can all still change freely. Changing the kind of answer is the one thing that strands the answer already recorded."
                              : "Optional."}
                        </span>
                      </div>
                      <div className="flex flex-wrap justify-end gap-2">
                        {/* Same contract as the section grip above: an accelerator over the arrows,
                            never a replacement for them, and it answers the arrow keys itself. */}
                        <button
                          type="button"
                          className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                          aria-label={`Reorder ${field.label?.trim() || "this question"} — drag, or use the arrow keys`}
                          disabled={busy || fields.length < 2}
                          {...questionDrag.handleProps(questionDragId)}
                          onKeyDown={(event) => {
                            if (event.key === "ArrowUp") {
                              event.preventDefault();
                              moveField(sectionIndex, fieldIndex, -1);
                            } else if (event.key === "ArrowDown") {
                              event.preventDefault();
                              moveField(sectionIndex, fieldIndex, 1);
                            }
                          }}
                        >
                          <GripVertical className="h-4 w-4" aria-hidden />
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => moveField(sectionIndex, fieldIndex, -1)}
                          disabled={busy || fieldIndex === 0}
                        >
                          <ArrowUp className="h-3 w-3" aria-hidden />
                          Up
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => moveField(sectionIndex, fieldIndex, 1)}
                          disabled={busy || fieldIndex === fields.length - 1}
                        >
                          <ArrowDown className="h-3 w-3" aria-hidden />
                          Down
                        </button>
                        <button
                          type="button"
                          className={rowAction("danger")}
                          onClick={() => void removeField(sectionIndex, fieldIndex)}
                          disabled={busy}
                        >
                          {answeredField ? (
                            <>
                              <Lock className="h-3 w-3" aria-hidden />
                              Retire
                            </>
                          ) : (
                            <>
                              <Trash2 className="h-3 w-3" aria-hidden />
                              Delete
                            </>
                          )}
                        </button>
                      </div>
                    </div>
                  </li>
                );
              })}
            </ul>

            {retiredHere.length ? (
              /*
                NO LONGER ASKED, STILL ON THE SCREEN, AND NOT IN A BOX. A retired question was retired
                because it had been answered, so its wording is part of the evidence — and the whole
                point of superseding rather than editing is that the wording a question was asked as
                stays readable. Drawn as text rather than as a disabled row for the reason the answering
                form draws it as text: a control invites a correction, and correcting it is the one thing
                that must not happen. Its KEY is printed because that is what a designer needs when they
                try to re-use it and are refused by name.
              */
              <div className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
                <h3 className="text-sm font-medium text-ink-900">No longer asked</h3>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  {retiredHere.length === 1 ? "This question is" : "These questions are"} not asked any more. What was
                  already recorded against {retiredHere.length === 1 ? "it" : "them"} is kept, prints in the report
                  under the wording it was asked as, and counts towards nothing. The keys cannot be used again by a
                  new question.
                </p>
                <ul className="mt-2 grid gap-1">
                  {retiredHere.map((field) => (
                    <li key={field.key} className="text-xs leading-5 text-ink-700">
                      <span className="line-through decoration-ink-300">{field.label || field.key}</span>{" "}
                      <span className="text-ink-500">
                        (stored as {field.key}
                        {field.supersededById ? "; reworded, and asked again above" : ""})
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            <div>
              <button
                type="button"
                className="field-button-secondary"
                disabled={busy || fields.length >= MAX_CUSTOM_FIELDS_PER_SECTION}
                onClick={() =>
                  patchSection(sectionIndex, { fields: [...fields, blankCustomField({ sortOrder: fields.length })] })
                }
              >
                <Plus className="h-4 w-4" aria-hidden />
                Add a question
              </button>
              {fields.length >= MAX_CUSTOM_FIELDS_PER_SECTION ? (
                // Every cap says so on screen. A control that silently stops working is indistinguishable
                // from one that is broken.
                <p className="mt-2 text-xs leading-5 text-ink-500">
                  This section holds the maximum of {MAX_CUSTOM_FIELDS_PER_SECTION} questions. Split it into
                  two sections to ask more.
                </p>
              ) : null}
            </div>
          </section>
        );
      })}

      {retiredSections.length ? (
        /*
          THE SECTIONS THAT ARE NO LONGER ASKED, SHOWN AND NEVER SENT.

          They arrive on every read — `definition_payload` includes them deliberately, because their keys
          are the keys the `_custom` rows still hold — and they must not be dropped from this screen: a
          designer who retired "Loom shed" last month and cannot see it here has no way to know why
          `looms` is refused as a key, and no way to see the wording their answers were given under.

          They are also not editable, and that is the correction of a real defect rather than a
          simplification. Offering them as boxes meant this screen sent them back in the next body — and
          `plan_definition` reads a named section as "ask this", so every retired section of the workshop
          was silently un-retired by the next save of an unrelated question. {@link definitionBody} now
          refuses to name one at all; this block is the honest half of that.
        */
        <section className="panel grid gap-2 p-4">
          <h2 className="font-display text-lg font-bold text-ink-900">No longer asked</h2>
          <p className="text-xs leading-5 text-ink-500">
            {retiredSections.length === 1 ? "This section is" : "These sections are"} not asked any more, because
            answers had already been recorded against{" "}
            {retiredSections.length === 1 ? "it" : "them"} when {retiredSections.length === 1 ? "it" : "they"} were
            removed. Everything recorded stays readable, prints in the report under the wording it was asked as,
            and counts towards nothing. Saving this screen does not change{" "}
            {retiredSections.length === 1 ? "it" : "them"}. To ask any of these questions again, add a new section
            with new keys — the old keys belong to the answers already given.
          </p>
          <ul className="grid gap-2">
            {retiredSections.map((section) => (
              <li key={section.key} className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
                <p className="text-sm text-ink-700">
                  <span className="line-through decoration-ink-300">{section.title || section.key}</span>{" "}
                  {/* The section KEY is printed, not only its heading: a new section given this key would
                      not be a new section at all — the server reads a named section as "ask this" and would
                      bring this one back, its retired questions and all. */}
                  <span className="text-xs text-ink-500">
                    (stored as {section.key};{" "}
                    {stageOptions.find((option) => option.value === section.stageKey)?.label ?? section.stageKey})
                  </span>
                </p>
                <ul className="mt-1 grid gap-1">
                  {section.fields.map((field) => (
                    <li key={field.key} className="text-xs leading-5 text-ink-500">
                      {field.label || field.key} (stored as {field.key})
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div>
        <button
          type="button"
          className="field-button-secondary"
          disabled={busy || sections.length >= MAX_CUSTOM_SECTIONS}
          onClick={() => setSections((current) => [...current, blankCustomSection({ sortOrder: current.length })])}
        >
          <Plus className="h-4 w-4" aria-hidden />
          Add a section
        </button>
        {sections.length >= MAX_CUSTOM_SECTIONS ? (
          <p className="mt-2 text-xs leading-5 text-ink-500">
            A workshop may carry {MAX_CUSTOM_SECTIONS} custom sections. Merge two of them, or move the
            questions that belong to another stage into a section on that stage.
          </p>
        ) : null}
      </div>

      {problems.length ? (
        <section className="panel p-4">
          <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <div className="min-w-0">
              <p className="text-sm font-medium">
                {problems.length === 1
                  ? "One thing has to change before this can be saved"
                  : `${problems.length} things have to change before this can be saved`}
              </p>
              {/* EVERY refusal at once and not the first, because a designer fixing a form one round
                  trip at a time gives up on the third — which is the argument the server's own 422 makes
                  by returning all of them. */}
              <ul className="mt-2 grid gap-1 text-xs leading-5">
                {problems.map((problem) => (
                  <li key={problem}>{problem}</li>
                ))}
              </ul>
            </div>
          </div>
        </section>
      ) : null}

      <section className="panel grid gap-3 p-4">
        {/* WHAT SAVING WILL DO, IN FULL, BEFORE IT IS DONE. Everything in this block is a conversion the
            server performs silently and reports only in counts afterwards. */}
        {!nothingToDo ? (
          <div className="grid gap-2 text-sm text-ink-700">
            <p className="font-medium text-ink-900">Saving will:</p>
            <ul className="grid gap-1 text-xs leading-5">
              {diff.created ? <li>add {diff.created} question{diff.created === 1 ? "" : "s"};</li> : null}
              {diff.superseded.map((entry) => (
                <li key={`${entry.was}→${entry.now}`}>
                  keep “{entry.was}” and the answers given under it exactly as they are, and ask “{entry.now}”
                  as a new question beside it;
                </li>
              ))}
              {diff.retiredFields.length ? (
                <li>
                  stop asking {diff.retiredFields.map((label) => `“${label}”`).join(", ")} — the answers already
                  recorded stay readable and print in the report;
                </li>
              ) : null}
              {diff.deletedFields.length ? (
                <li>delete {diff.deletedFields.map((label) => `“${label}”`).join(", ")}, which nobody has answered;</li>
              ) : null}
              {diff.retiredSections.length ? (
                <li>
                  retire the section{diff.retiredSections.length === 1 ? "" : "s"}{" "}
                  {diff.retiredSections.map((title) => `“${title}”`).join(", ")}, keeping every answer recorded
                  under {diff.retiredSections.length === 1 ? "it" : "them"};
                </li>
              ) : null}
              {diff.deletedSections.length ? (
                <li>
                  delete the unanswered section{diff.deletedSections.length === 1 ? "" : "s"}{" "}
                  {diff.deletedSections.map((title) => `“${title}”`).join(", ")}.
                </li>
              ) : null}
            </ul>
          </div>
        ) : null}

        {diff.retypedFields.length ? (
          /*
            THE ONE ENTRY IN THE DIFF THE SERVER DOES NOT CONVERT, AND THEREFORE THE ONLY ONE NOTHING
            ELSE IN THE SYSTEM WILL EVER MENTION. A supersede is reported back in a count; a retype is
            accepted in silence and the answer already recorded stays in the shape the old kind wrote
            it. `plan_custom_write`'s phase one coerces it against the new kind on the next save of that
            stage and comes back "How many looms? is not a valid int" — the server's own words, built
            from the TYPE TOKEN by `coerce_value`'s except arm, not from the plain name this screen uses
            for it — against a value nobody typed on that visit. Under submit that refusal 422s the
            whole stage, which is the "permanently unsubmittable" failure `plan_custom_write` records in
            those words for a bounds change.

            AMBER RATHER THAN RED, and not in the "Saving will:" list: it is neither a refusal (the
            server accepts it, so the Save button stays live and the designer keeps the decision) nor
            something the save itself performs.
          */
          <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <div className="min-w-0 text-xs leading-5">
              <p className="font-medium">
                {diff.retypedFields.length === 1
                  ? "One answered question is changing the kind of answer it takes"
                  : `${diff.retypedFields.length} answered questions are changing the kind of answer they take`}
              </p>
              <ul className="mt-1 grid gap-1">
                {diff.retypedFields.map((entry) => (
                  <li key={`${entry.label}:${entry.was}→${entry.now}`}>
                    “{entry.label}” — {TYPE_LABEL[entry.was] ?? entry.was} → {TYPE_LABEL[entry.now] ?? entry.now}
                  </li>
                ))}
              </ul>
              {/* WHAT THE SERVER ACTUALLY DOES, AND NOT ONE STEP MORE. This said "the stage will refuse to
                  save", which is a sentence the server does not support: `save_stage` writes every other
                  answer, keeps the stored value under the refused key (the rejected-value loop), and returns
                  200 — the route 422s only when `submit` is set. A designer told their stage will not save
                  goes looking for a save that is working, and stops believing the next warning. What is
                  true is narrower and worse: the refusal comes back on EVERY save, naming a value they did
                  not type on that visit, and the stage cannot be submitted until it is retyped. */}
              <p className="mt-1">
                The server accepts this and changes nothing about the answers already recorded, so they stay
                in the record exactly as they were given — but they were written in the old form. Every later
                save of that stage goes through, and comes back refusing that one answer by name and leaving
                the value already stored exactly where it is; the stage cannot be submitted until somebody
                retypes it into the new form. If the answers already given are still right as they stand,
                leave the kind of answer alone; if the question really has changed, reword it as well, and it
                becomes a new question with the old one kept beside it.
              </p>
            </div>
          </div>
        ) : null}

        {diff.movedSections.length ? (
          // THE ONE EDIT THE SERVER REFUSES OUTRIGHT rather than converting, named before the button is
          // pressed and with the way round it. A 409 arriving after a save is a designer discovering a rule
          // by breaking it.
          <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs leading-5 text-red-700">
            {diff.movedSections.map((title) => `“${title}”`).join(", ")} cannot be moved to another stage,
            because answers have already been recorded against{" "}
            {diff.movedSections.length === 1 ? "it" : "them"} and they are stored with the stage they were
            asked at. Put the stage back, or retire the section here and add a new one on the other stage —
            the answers already given stay readable either way.
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="field-button"
            // `dirty` AND NOT `nothingToDo` — see the note on `dirty`. The diff describes what a save
            // CONVERTS; whether there is anything to save at all is a question about the body.
            disabled={busy || problems.length > 0 || diff.movedSections.length > 0 || !dirty}
            onClick={() => void save()}
          >
            {busy ? "Saving…" : "Save these questions"}
          </button>
          <button
            type="button"
            className="field-button-secondary"
            disabled={busy || !dirty}
            // Back to the EDITABLE projection of what the server holds, never to `stored` itself:
            // putting the whole payload back would return every retired section and retired field to
            // the boxes, which is the state this screen exists not to be in.
            onClick={() => {
              setSections(baseline);
              setNotice(null);
              setRefusals([]);
            }}
          >
            Discard changes
          </button>
          {!dirty ? <span className="text-xs text-ink-500">Nothing has changed yet.</span> : null}
        </div>
      </section>

      {/*
        Save runs the same PUT the button runs, refusals and all, and leaves ONLY if the server took
        it — a 409 from a stale digest or a 422 listing the rules the definition breaks keeps the
        designer on the screen with the boxes and the messages together, which is the whole reason the
        catch above does not clear the draft.

        Discard puts the editable projection of what the server holds back before navigating, rather
        than merely navigating: the component is about to unmount, but a `router.back()` into a cached
        route can put this screen back in front of the designer, and it must not still be holding the
        edits they just said to throw away.
      */}
      <UnsavedChangesDialog
        open={leavePromptOpen}
        saving={busy}
        onKeepEditing={() => setLeavePromptOpen(false)}
        onDiscard={() => {
          setSections(baseline);
          setNotice(null);
          setRefusals([]);
          setLeavePromptOpen(false);
          router.back();
        }}
        onSave={() => {
          void (async () => {
            const saved = await save();
            setLeavePromptOpen(false);
            if (saved) router.back();
          })();
        }}
      />
    </div>
  );
}
