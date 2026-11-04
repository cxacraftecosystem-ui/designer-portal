"use client";

import { countNoun } from "@/lib/format";
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
import { ActionGroup } from "@/components/ui/ActionGroup";

const TIERS: DwTier[] = ["BASIC", "STANDARD", "ADVANCED"];

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

const OPTION_TYPES = new Set(["ENUM", "MULTI_ENUM"]);
const BOUNDED_TYPES = new Set(["INT", "DECIMAL", "MONEY", "PERCENT"]);

export function CustomSectionsEditor({
  workshopId,
  readOnlyReason = null,
  describedBy
}: {
  workshopId: string;
  readOnlyReason?: string | null;
  describedBy?: string;
}) {
  const confirm = useConfirm();
  const router = useRouter();

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [stored, setStored] = useState<DwCustomSection[]>([]);
  const [storedVersion, setStoredVersion] = useState<string | null>(null);
  const [sections, setSections] = useState<DwCustomSection[]>([]);
  const [answered, setAnswered] = useState<Record<string, Set<string>>>({});
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const frozen = busy || Boolean(readOnlyReason);
  const [refusals, setRefusals] = useState<string[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [crossSectionRefusal, setCrossSectionRefusal] = useState<string | null>(null);
  const [remoteId, setRemoteId] = useState<string | null>(null);
  const [localOnly, setLocalOnly] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const draft = await loadDraft(workshopId);
      const target = draft?.remoteId ?? (isLocalWorkshopId(workshopId) ? null : workshopId);
      if (!target) {
        setLocalOnly(true);
        return;
      }
      setLocalOnly(false);
      setRemoteId(target);
      const [spec, definition, detail] = await Promise.all([
        fetchStageRegistry(),
        fetchCustomDefinition(target, { refresh: true }),
        getDesignWorkshop(target)
      ]);
      setRegistry(spec);
      setStored(definition.sections);
      setStoredVersion(definition.customSchemaVersion);
      setSections(editableSections(definition.sections));
      const held: Record<string, Set<string>> = {};
      for (const stage of spec.stages) {
        const onServer: DwEntryData = detail.stages?.[stage.key]?.custom ?? {};
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

  const takenKeys = useMemo(() => {
    const keys = new Set<string>();
    for (const section of sections) for (const field of section.fields) keys.add(field.key);
    for (const section of stored) for (const field of section.fields) keys.add(field.key);
    return keys;
  }, [sections, stored]);

  const problems = useMemo(
    () => customDefinitionProblems(sections, registry?.stages ?? []),
    [sections, registry]
  );
  const diff = useMemo(() => diffCustomDefinition(stored, sections, answered), [stored, sections, answered]);
  const baseline = useMemo(() => editableSections(stored), [stored]);
  const dirty = useMemo(
    () => JSON.stringify(definitionBody(sections)) !== JSON.stringify(definitionBody(baseline)),
    [sections, baseline]
  );
  const nothingToDo = diffIsEmpty(diff);

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

  const sectionIsAnswered = useCallback(
    (section: DwCustomSection): boolean => storedSectionHoldsAnswer(stored, section.key, answered),
    [answered, stored]
  );

  const retiredIn = useCallback(
    (sectionKey: string): DwCustomField[] =>
      (stored.find((candidate) => candidate.key === sectionKey)?.fields ?? []).filter((field) => field.retired),
    [stored]
  );

  const retiredSections = useMemo(() => stored.filter((section) => section.retired), [stored]);

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

  const sectionDragIds = useMemo(
    () => sections.map((section, index) => `${section.key || "new"}:${index}`),
    [sections]
  );

  const sectionDrag = useDragReorder({
    order: sectionDragIds,
    locked: frozen,
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

  const questionDragIds = useMemo(
    () =>
      sections.flatMap((section, sectionIndex) =>
        section.fields.map((_, fieldIndex) => `${sectionIndex}:${fieldIndex}`)
      ),
    [sections]
  );

  const questionDrag = useDragReorder({
    order: questionDragIds,
    locked: frozen,
    labelFor: useCallback(
      (id: string) => {
        const [sectionIndex, fieldIndex] = id.split(":").map(Number);
        return sections[sectionIndex]?.fields[fieldIndex]?.label?.trim() || "Untitled question";
      },
      [sections]
    ),
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
        setCrossSectionRefusal(null);
        const fromId = questionDragIds[from];
        const toId = questionDragIds[to];
        if (!fromId || !toId) return false;
        const [fromSection, fromField] = fromId.split(":").map(Number);
        const [toSection, toField] = toId.split(":").map(Number);
        if (fromSection !== toSection) {
          setCrossSectionRefusal(
            "A question can only be moved within its own section."
          );
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

  function moveSection(index: number, by: -1 | 1) {
    const target = index + by;
    if (target < 0 || target >= sections.length) return;
    sectionDrag.announceMove(sectionDragIds[index], target);
    setSections((current) => moveIndex(current, index, target));
  }

  function moveField(sectionIndex: number, fieldIndex: number, by: -1 | 1) {
    const section = sections[sectionIndex];
    if (!section) return;
    const target = fieldIndex + by;
    if (target < 0 || target >= section.fields.length) return;
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
    const ok = await confirm(
      isAnswered(section, field.key)
        ? {
            title: "Retire this question?",
            body: `"${field.label || field.key}" stops being asked. Its answers are kept and still print.`,
            confirmLabel: "Retire question",
            tone: "warning"
          }
        : deleteConfirm(
            "Delete this question?",
            `"${field.label || field.key}" is removed from this section.`,
            "Nobody has answered it, online or on this device."
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
            body: `"${section.title || section.key}" stops being asked. Its answers are kept and still print.`,
            confirmLabel: "Retire section",
            tone: "warning"
          }
        : deleteConfirm(
            "Delete this section?",
            `"${section.title || section.key}" and its ${countNoun(liveFields(section).length, "question")} are removed.`,
            "Nobody has answered them, online or on this device."
          )
    );
    if (!ok) return;
    setSections((current) => current.filter((_, at) => at !== index));
  }

  async function save(): Promise<boolean> {
    if (!remoteId || readOnlyReason) return false;
    setBusy(true);
    setRefusals([]);
    setNotice(null);
    try {
      const result = await saveCustomDefinition(remoteId, sections, storedVersion);
      setStored(result.sections);
      setStoredVersion(result.customSchemaVersion);
      setSections(editableSections(result.sections));
      await load();
      const parts = [
        result.created ? `${result.created} question${result.created === 1 ? "" : "s"} added` : "",
        result.superseded
          ? `${result.superseded} reworded`
          : "",
        result.retired ? `${result.retired} retired` : "",
        result.removed ? `${result.removed} removed` : ""
      ].filter(Boolean);
      setNotice(parts.length ? `Saved — ${parts.join("; ")}.` : "Saved. Nothing in the definition changed.");
      return true;
    } catch (err) {
      setRefusals(customSectionsProblem(err, "Unable to save these questions"));
      return false;
    } finally {
      setBusy(false);
    }
  }

  if (loading) return <div className="panel p-4 text-sm text-ink-700">Loading this workshop&apos;s own questions…</div>;

  if (localOnly) {
    return (
      <section className="panel p-4">
        <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
          <p className="text-xs leading-5">
            Questions can be added once this workshop uploads.
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
              <p className="font-medium">These questions couldn&apos;t be saved, for {refusals.length} reasons:</p>
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

      <p aria-live="polite" className="sr-only">
        {sectionDrag.announcement}
      </p>
      <p aria-live="polite" className="sr-only">
        {questionDrag.announcement}
      </p>

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
        const retiredHere = retiredIn(section.key);
        const stageTitle = stageOptions.find((option) => option.value === section.stageKey)?.label;
        const answeredHere = liveFields(section).filter((field) => isAnswered(section, field.key)).length;
        const sectionHolds = sectionIsAnswered(section);
        const sectionDragId = sectionDragIds[sectionIndex];
        const sectionShift = sectionDrag.shiftFor(sectionDragId);
        const sectionDragging = sectionDrag.draggingKey === sectionDragId;
        return (
          <section
            key={`${section.key}-${sectionIndex}`}
            ref={sectionDrag.registerRow(sectionDragId)}
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
                    :
                      sectionHolds
                      ? "A retired question has answers, so this section can't be deleted."
                      : "No answers yet — fully editable."}
                </p>
              </div>
              <ActionGroup align="end">
                <button
                  type="button"
                  className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                  aria-label={`Reorder ${section.title?.trim() || "this section"} — drag, or use the arrow keys`}
                  disabled={frozen || sections.length < 2}
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
                  disabled={frozen || sectionIndex === 0}
                >
                  <ArrowUp className="h-3 w-3" aria-hidden />
                  Up
                </button>
                <button
                  type="button"
                  className={rowAction("neutral")}
                  onClick={() => moveSection(sectionIndex, 1)}
                  disabled={frozen || sectionIndex === sections.length - 1}
                >
                  <ArrowDown className="h-3 w-3" aria-hidden />
                  Down
                </button>
                <button
                  type="button"
                  data-danger className={rowAction("danger")}
                  onClick={() => void removeSection(sectionIndex)}
                  disabled={frozen}
                >
                  {sectionHolds ? (
                    <>
                      <Lock className="h-3 w-3" aria-hidden />
                      Retire
                    </>
                  ) : (
                    "Delete"
                  )}
                </button>
              </ActionGroup>
            </header>

            <div className="grid gap-3 md:grid-cols-2">
              <label className="grid min-w-0 gap-1">
                <span className="field-label">Heading</span>
                <input
                  className="field-input"
                  value={section.title}
                  maxLength={MAX_CUSTOM_TITLE_CHARS}
                  disabled={frozen}
                  onChange={(event) => patchSection(sectionIndex, { title: event.target.value })}
                />
              </label>
              <div className="grid min-w-0 gap-1">
                <span className="field-label" id={`stage-${sectionIndex}`}>
                  Asked at
                </span>
                <Dropdown
                  value={section.stageKey}
                  onChange={(next) => patchSection(sectionIndex, { stageKey: next })}
                  options={stageOptions}
                  placeholder="Choose a stage"
                  disabled={frozen || sectionHolds}
                  ariaLabel="The stage these questions are asked at"
                />
                <p className="text-xs leading-5 text-ink-500">
                  {sectionHolds
                    ? "Fixed — this section has answers."
                    : "Where these questions are asked."}
                </p>
              </div>
              <label className="grid min-w-0 gap-1 md:col-span-2">
                <span className="field-label">Description</span>
                <textarea
                  className="field-input min-h-16"
                  value={section.description}
                  maxLength={MAX_CUSTOM_DESCRIPTION_CHARS}
                  disabled={frozen}
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
                      <p className="mb-2 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                        This question has answers. Saving keeps “{cost.was}” and its answers, and adds your wording as a new question.
                      </p>
                    ) : null}

                    <div className="grid gap-3 md:grid-cols-2">
                      <label className="grid min-w-0 gap-1">
                        <span className="field-label">Question</span>
                        <input
                          className="field-input"
                          value={field.label}
                          maxLength={MAX_CUSTOM_LABEL_CHARS}
                          disabled={frozen}
                          onChange={(event) => {
                            const label = event.target.value;
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
                          disabled={frozen || locked}
                          onChange={(event) => patchField(sectionIndex, fieldIndex, { key: event.target.value })}
                        />
                        <span className="text-xs leading-5 text-ink-500">
                          {locked
                            ? "Fixed once saved."
                            : "Lower-case letter, then letters and digits. Fixed once saved."}
                        </span>
                      </label>

                      <div className="grid min-w-0 gap-1">
                        <span className="field-label">Kind of answer</span>
                        <Dropdown
                          value={field.type}
                          onChange={(next) =>
                            patchField(sectionIndex, fieldIndex, {
                              type: next,
                              options: OPTION_TYPES.has(next) ? field.options : [],
                              minValue: BOUNDED_TYPES.has(next) ? field.minValue : null,
                              maxValue: BOUNDED_TYPES.has(next) ? field.maxValue : null
                            })
                          }
                          options={V1_CUSTOM_TYPES.map((type) => ({ value: type, label: TYPE_LABEL[type] ?? type }))}
                          disabled={frozen}
                          ariaLabel="The kind of answer this question takes"
                        />
                      </div>

                      <div className="grid min-w-0 gap-1">
                        <span className="field-label">Tier</span>
                        <Dropdown
                          value={field.tier}
                          onChange={(next) =>
                            patchField(sectionIndex, fieldIndex, {
                              tier: next as DwTier,
                              required: next === "BASIC" ? field.required : false
                            })
                          }
                          options={TIERS.map((tier) => ({ value: tier, label: tier[0] + tier.slice(1).toLowerCase() }))}
                          disabled={frozen}
                          ariaLabel="Which capture tier this question belongs to"
                        />
                      </div>

                      <label className="grid min-w-0 gap-1">
                        <span className="field-label">Help text</span>
                        <input
                          className="field-input"
                          value={field.help}
                          maxLength={MAX_CUSTOM_HELP_CHARS}
                          disabled={frozen}
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
                          disabled={frozen}
                          onChange={(event) => patchField(sectionIndex, fieldIndex, { unit: event.target.value })}
                        />
                        <span className="text-xs leading-5 text-ink-500">
                          Shown as “Measured in …”.
                        </span>
                      </label>

                      {OPTION_TYPES.has(field.type) ? (
                        <label className="grid min-w-0 gap-1 md:col-span-2">
                          <span className="field-label">Options, one per line</span>
                          <textarea
                            className="field-input min-h-20"
                            value={field.options
                              .map((option) =>
                                option.label && option.label !== option.value
                                  ? `${option.value} | ${option.label}`
                                  : option.value
                              )
                              .join("\n")}
                            disabled={frozen}
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
                            One per line. <code>CODE | Printed label</code> is optional.
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
                              disabled={frozen}
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
                              disabled={frozen}
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
                          disabled={frozen}
                          onClick={() =>
                            patchField(sectionIndex, fieldIndex, {
                              required: !field.required,
                              tier: !field.required ? "BASIC" : field.tier
                            })
                          }
                        >
                          {field.required ? "Make optional" : "Make required"}
                        </button>
                        <span className="text-xs text-ink-500">
                          {field.required
                            ? "Required."
                            : answeredField
                              ?
                                "Answered — wording is fixed. Changing the answer type strands existing answers."
                              : "Optional."}
                        </span>
                      </div>
                      <ActionGroup align="end">
                        <button
                          type="button"
                          className="grid h-8 w-8 cursor-grab touch-none place-items-center rounded-md border border-line-200 text-ink-500 transition hover:bg-surface-50 active:cursor-grabbing disabled:opacity-40"
                          aria-label={`Reorder ${field.label?.trim() || "this question"} — drag, or use the arrow keys`}
                          disabled={frozen || fields.length < 2}
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
                          disabled={frozen || fieldIndex === 0}
                        >
                          <ArrowUp className="h-3 w-3" aria-hidden />
                          Up
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => moveField(sectionIndex, fieldIndex, 1)}
                          disabled={frozen || fieldIndex === fields.length - 1}
                        >
                          <ArrowDown className="h-3 w-3" aria-hidden />
                          Down
                        </button>
                        <button
                          type="button"
                          data-danger className={rowAction("danger")}
                          onClick={() => void removeField(sectionIndex, fieldIndex)}
                          disabled={frozen}
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
                      </ActionGroup>
                    </div>
                  </li>
                );
              })}
            </ul>

            {retiredHere.length ? (
              <div className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
                <h3 className="text-sm font-medium text-ink-900">No longer asked</h3>
                <p className="mt-1 text-xs leading-5 text-ink-500">
                  No longer asked. Answers are kept and still print.
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
                disabled={frozen || fields.length >= MAX_CUSTOM_FIELDS_PER_SECTION}
                onClick={() =>
                  patchSection(sectionIndex, { fields: [...fields, blankCustomField({ sortOrder: fields.length })] })
                }
              >
                <Plus className="h-4 w-4" aria-hidden />
                Add a question
              </button>
              {fields.length >= MAX_CUSTOM_FIELDS_PER_SECTION ? (
                <p className="mt-2 text-xs leading-5 text-ink-500">
                  Maximum {MAX_CUSTOM_FIELDS_PER_SECTION} questions per section.
                </p>
              ) : null}
            </div>
          </section>
        );
      })}

      {retiredSections.length ? (
        <section className="panel grid gap-2 p-4">
          <h2 className="font-display text-lg font-bold text-ink-900">No longer asked</h2>
          <p className="text-xs leading-5 text-ink-500">
            No longer asked. Answers are kept and still print.
          </p>
          <ul className="grid gap-2">
            {retiredSections.map((section) => (
              <li key={section.key} className="rounded-md border border-dashed border-line-200 bg-surface-50 p-3">
                <p className="text-sm text-ink-700">
                  <span className="line-through decoration-ink-300">{section.title || section.key}</span>{" "}
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
          disabled={frozen || sections.length >= MAX_CUSTOM_SECTIONS}
          onClick={() => setSections((current) => [...current, blankCustomSection({ sortOrder: current.length })])}
        >
          <Plus className="h-4 w-4" aria-hidden />
          Add a section
        </button>
        {sections.length >= MAX_CUSTOM_SECTIONS ? (
          <p className="mt-2 text-xs leading-5 text-ink-500">
            Maximum {MAX_CUSTOM_SECTIONS} custom sections.
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
                  ? "Fix one thing to save"
                  : `Fix ${problems.length} things to save`}
              </p>
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
        {!nothingToDo ? (
          <div className="grid gap-2 text-sm text-ink-700">
            <p className="font-medium text-ink-900">Saving will:</p>
            <ul className="grid gap-1 text-xs leading-5">
              {diff.created ? <li>add {diff.created} question{diff.created === 1 ? "" : "s"};</li> : null}
              {diff.superseded.map((entry) => (
                <li key={`${entry.was}→${entry.now}`}>
                  keep “{entry.was}” and add “{entry.now}” as a new question;
                </li>
              ))}
              {diff.retiredFields.length ? (
                <li>
                  stop asking {diff.retiredFields.map((label) => `“${label}”`).join(", ")} (answers kept);
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
          <div className="flex items-start gap-2 rounded-md bg-amber-100 p-3 text-amber-800">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <div className="min-w-0 text-xs leading-5">
              <p className="font-medium">
                {diff.retypedFields.length === 1
                  ? "One answered question changes its answer type"
                  : `${diff.retypedFields.length} answered questions change their answer type`}
              </p>
              <ul className="mt-1 grid gap-1">
                {diff.retypedFields.map((entry) => (
                  <li key={`${entry.label}:${entry.was}→${entry.now}`}>
                    “{entry.label}” — {TYPE_LABEL[entry.was] ?? entry.was} → {TYPE_LABEL[entry.now] ?? entry.now}
                  </li>
                ))}
              </ul>
              <p className="mt-1">
                Existing answers will be flagged until retyped. To keep them, reword the question instead.
              </p>
            </div>
          </div>
        ) : null}

        {diff.movedSections.length ? (
          <p className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-xs leading-5 text-red-700">
            {diff.movedSections.map((title) => `“${title}”`).join(", ")} has answers and can&apos;t move stage. Retire it and add a new section there.
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-2">
          <ActionGroup className="max-sm:w-full"><button
            type="button"
            className="field-button"
            disabled={frozen || problems.length > 0 || diff.movedSections.length > 0 || !dirty}
            aria-describedby={describedBy}
            onClick={() => void save()}
          >
            {busy ? "Saving…" : "Save these questions"}
          </button>
          <button
            type="button"
            className="field-button-secondary"
            disabled={frozen || !dirty}
            onClick={() => {
              setSections(baseline);
              setNotice(null);
              setRefusals([]);
            }}
          >
            Discard changes
          </button></ActionGroup>
          {!dirty ? <span className="text-xs text-ink-500">Nothing has changed yet.</span> : null}
        </div>
      </section>

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
