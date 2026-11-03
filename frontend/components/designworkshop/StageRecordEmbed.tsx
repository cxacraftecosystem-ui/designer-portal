"use client";

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

export type MirrorPoint = {
  entityKey: string;
  refFieldKey: string;
  why: string;
};

export const MIRROR_POINTS: readonly MirrorPoint[] = [
  {
    entityKey: "participant",
    refFieldKey: "artisanRef",
    why:
      "Stage 3 IS the roster: a row of it is an artisan, and `participant.artisanRef` carries 26 " +
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
    point: "traditionalProcess.productRef",
    why:
      "One pair (`name -> documentedFor`) beside the 7 of `processRef` on the same entity, whose " +
      "`ProcessForm` IS a mirror point above. It is the CASCADE — choosing the product is what " +
      "narrows the process list below it — and this entity may no more mount two record forms than " +
      "`existingProduct` may: one Save would be able to write a ProductDocumentation and a Process, " +
      "and the row would have two ideas of what it is about. It stays an ORDINARY PICKER, drawn " +
      "above the record form in the `pickers` group beside `processRef` in declaration order, which " +
      "is where {@link splitMirroredFields} already puts every REF field of a mirror-point entity. " +
      "Note it is the SECOND writer of `documentedFor` rather than a new box: the mirrored/footer " +
      "split is derived from the mapping's TARGET keys, and that target is already mirrored through " +
      "`processRef`, so this refusal moves no field between the two groups."
  },
  {
    point: "processStep.productRef",
    why:
      "The same pair on the other stage-5 entity, and its sibling `processStep.processRef` is " +
      "refused above rather than shipped — so there is no record form on this entity for a second " +
      "one to compete with, and the reason is the stronger one: a Process has many steps, hydration " +
      "cannot pick one, and a form per step row would save the sequence the other rows came from. A " +
      "ProductDocumentation form here would be worse still, being a form for the GRANDPARENT of the " +
      "row it sits on. Ordinary picker, drawn with `processRef` in the pickers group."
  },
  {
    point: "artisanBaseline.interviewRef",
    why:
      "There is no form to mount. A `QuestionnaireInterview` is a SITTING — a title, a date, a " +
      "place, a language and a set of artisans — recorded through the questionnaire area's own " +
      "screens, and `isInlineCreatable` does not name the model, so `mirrorRefField` would return " +
      "null and the embed would fall back to the generated grid anyway. It should not be made " +
      "creatable from here either: an interview is EVIDENCE this stage cites, not a record the " +
      "citation's author should be able to mint beside the citation. All eleven pairs it carries " +
      "are COUNTS and administrative facts about the sitting — never an answer, never an artisan's " +
      "name — so there is no record page's worth of fields to mirror in the first place."
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
  },
  {
    point: "finalProduct.prototypeRef",
    why:
      "THE FIRST INTERNAL CARRY, 2026-09-03, and it is refused here for a reason that applies to " +
      "every one that follows: the source is not a RECORD. A `DwPrototype` is a row of THIS " +
      "workshop's stage 13 — there is no repository page for it, `isInlineCreatable` does not name " +
      "the model, and the thing a designer wants when the nine copied boxes look wrong is to open " +
      "stage 13, which is two taps away in the same workshop and is where that row is edited with " +
      "its own photographs, its own maker and its own stage logs beside it. Mounting a second " +
      "editor over it here would give one prototype two forms in one workshop, each able to save " +
      "from a different Save — the exact hazard `processStep.processRef` above describes, with the " +
      "aggravation that both forms belong to the same designer on the same afternoon."
  },
  {
    point: "prototypeValidation.prototypeRef",
    why:
      "The same source and the same refusal as `finalProduct.prototypeRef` above, and a narrower " +
      "mapping on top of it: three dimensions out of the prototype's twenty-nine fields. Stage 15 " +
      "is a JUDGEMENT of a prototype — a decision, five quality ratings, two approvals — so the row " +
      "is not a copy of that prototype and never becomes one, which is the attribution-not-mirror " +
      "test `existingProduct.artisanRef` and `prototype.productRef` are refused by. Ordinary picker."
  },
  {
    point: "surveyPlan.questionnaireRef",
    why:
      "THERE IS NO FORM TO MOUNT, AND THE THING THIS ONE POINTS AT IS NOT MADE BY FILLING ONE IN. " +
      "A `Questionnaire` is the INSTRUMENT — sections, questions, and every sitting taken on it — " +
      "and it enters this system by UPLOADING AN .xlsx that `questionnaire_forms` parses. There is " +
      "no small create form anywhere to reuse, `isInlineCreatable` does not name the model, and " +
      "writing one here would be a second questionnaire builder competing with the one at " +
      "/questionnaires over the same rows. ONE PAIR CROSSES (`name -> questionnaireName`), which " +
      "is the attribution-not-mirror shape `existingProduct.artisanRef` is refused by: stage 7 " +
      "CITES the instrument it will use, it does not restate it. The prose box beside the picker " +
      "is still where a designer writes the plan, and it is deliberately untouched by the link. " +
      "There is a second reason not to make it creatable from here, and it is the one " +
      "`artisanBaseline.interviewRef` gives: the picker is narrowed by an ACCOUNT rule — the " +
      "designer's own forms, their workshops' forms, and the published default — so a create " +
      "button here would mint instruments into a list whose whole job is to be somebody's."
  }
] as const;

export function mirrorPointFor(entity: DwEntity): MirrorPoint | null {
  return MIRROR_POINTS.find((point) => point.entityKey === entity.key) ?? null;
}

export function mirrorRefField(entity: DwEntity, point: MirrorPoint): DwField | null {
  const field = entity.fields.find((candidate) => candidate.key === point.refFieldKey);
  if (!field || field.deprecated) return null;
  if (field.type !== "REF" || !isInlineCreatable(field.refModel)) return null;
  return field;
}

export type MirrorGroups = { pickers: DwField[]; mirrored: DwField[]; workshopOnly: DwField[] };

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

export function embeddedRecordId(refField: DwField, row: DwEntryData): string {
  return inputValue(row[refField.key]);
}

function liveRegionClass(busy: boolean, notice: { tone: "warn" | "done" } | null): string {
  if (busy || notice?.tone === "done") return "text-xs leading-5 text-ink-500";
  if (notice) return "rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800";
  return "sr-only";
}

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
  refField: DwField;
  row: DwEntryData;
  workshopId: string;
  onPatch: (values: Record<string, DwValue>) => void;
  picker: ReactNode;
  workshopFields: ReactNode;
  mirroredFields: ReactNode;
  mountOnRequest?: boolean;
}) {
  const linkedId = embeddedRecordId(refField, row);
  const model = refField.refModel as InlineCreatableModel;
  const noun = INLINE_MODEL_NOUN[model];
  const linkedWorkshopId = useLinkedWorkshopId();
  const filterValue = refField.refFilterBy ? inputValue(row[refField.refFilterBy]) : "";

  const seed = useMemo(
    () => inlineSeed({ entity, field: refField, row, filterValue, linkedWorkshopId }),
    [entity, refField, row, filterValue, linkedWorkshopId]
  );

  const live = useRef({ row, onPatch });
  useEffect(() => {
    live.current = { row, onPatch };
  }, [row, onPatch]);

  const [notice, setNotice] = useState<{ tone: "warn" | "done"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const hydration = useRef(0);
  const claimed = useRef(linkedId);
  useEffect(() => {
    if (linkedId === claimed.current) return;
    claimed.current = linkedId;
    hydration.current += 1;
    setBusy(false);
    setNotice(null);
  }, [linkedId]);

  const [formGeneration, setFormGeneration] = useState(0);
  const remountForm = useCallback(() => setFormGeneration((generation) => generation + 1), []);

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

  const adoptCreated = useCallback(
    async (recordId: string) => {
      const previous = inputValue(live.current.row[refField.key]);
      setBusy(true);
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;
      claimed.current = recordId;
      live.current.onPatch({ [refField.key]: recordId });
      const described = await describeForField(workshopId, refField, filterValue, recordId);
      if (hydration.current !== generation) return;
      setBusy(false);
      if (!described) {
        setNotice({
          tone: "warn",
          text: previous
            ? "Saved and linked, but its details didn't load, so the old values were cleared. Fill them in, or pick it again."
            : "Saved and linked, but its details didn't load. Fill the boxes in, or pick it again."
        });
      } else {
        setNotice({
          tone: "done",
          text: "Saved and linked. Check its photos have finished uploading."
        });
      }
      const option: DwReferenceOption = described ?? { id: recordId, label: "", sublabel: "", data: {} };
      const { row: currentRow, onPatch: writeNow } = live.current;
      writeNow({
        ...hydrateFromReference(entity, refField, option, currentRow, previous),
        [refField.key]: recordId
      });
    },
    [entity, refField, filterValue, workshopId]
  );

  const adoptEdited = useCallback(
    async (recordId: string) => {
      const before = beforeEdit.current;
      setBusy(true);
      setNotice(null);
      hydration.current += 1;
      const generation = hydration.current;
      const after = await describeForField(workshopId, refField, filterValue, recordId);
      if (hydration.current !== generation) return;
      setBusy(false);
      if (!after) {
        setNotice({
          tone: "warn",
          text: "Saved, but this row didn't refresh. Pick the record again to update it."
        });
        remountForm();
        return;
      }
      const mapping = referenceHydrationFor(entity, refField);
      const { row: current, onPatch: writeNow } = live.current;
      const patch: Record<string, DwValue> = {};
      for (const [sourceKey, targetKey] of Object.entries(mapping)) {
        const target = entity.fields.find((candidate) => candidate.key === targetKey);
        if (!target || target.deprecated) continue;
        if (isMultiField(target)) continue;
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
        if (held && !(was !== null && held === was)) continue;
        const next = stringifyRefValue(after.data?.[sourceKey]);
        if (next === held) continue;
        patch[targetKey] = next === null ? null : next;
      }
      if (Object.keys(patch).length) writeNow(patch);
      if (!before) {
        setNotice({
          tone: "warn",
          text: "Saved. Boxes you'd already filled in were left as they are."
        });
      } else {
        setNotice({ tone: "done", text: "Saved." });
      }
      remountForm();
    },
    [entity, refField, filterValue, remountForm, workshopId]
  );

  const handleSaved = useCallback(
    (record: InlineCreatedRecord) => {
      if (linkedId && linkedId === record.id) void adoptEdited(record.id);
      else void adoptCreated(record.id);
    },
    [linkedId, adoptCreated, adoptEdited]
  );

  const handleUseExisting = useCallback(
    (artisan: { id: string }) => {
      void adoptCreated(artisan.id);
    },
    [adoptCreated]
  );

  const handleQueued = useCallback(() => {
    setNotice({
      tone: "warn",
      text: `Saved on this device. Once it uploads, pick this ${noun} above to link it.`
    });
  }, [noun]);

  const handleCancel = useCallback(() => {
    remountForm();
    setNotice({
      tone: "done",
      text: `Cleared. Nothing was saved.`
    });
  }, [remountForm]);

  const handleDiscardAndLeave = useCallback(() => {
    remountForm();
    setNotice({
      tone: "done",
      text: `Discarded. Press the same control again to continue.`
    });
  }, [remountForm]);

  const [asked, setAsked] = useState(false);
  const mount = useCallback(() => setAsked(true), []);
  const mounted = !mountOnRequest || Boolean(linkedId) || asked;

  return (
    <div className="grid gap-4">
      {picker}

      <div className="rounded-md border border-line-200 bg-surface-50 p-3">
        <p className="text-xs leading-5 text-ink-muted">
          {linkedId
            ? `Linked ${noun}. Edits here change the record everywhere it is used.`
            : `Fill in the ${noun} here — it is saved and linked to this row.`}
        </p>
      </div>

      {mounted ? (
        <InlineRecordForm
          key={`${linkedId || "new"}:${formGeneration}`}
          model={model}
          recordId={linkedId || undefined}
          seed={seed}
          footerFields={workshopFields}
          onCreated={handleSaved}
          onCancel={handleCancel}
          onDiscardAndLeave={handleDiscardAndLeave}
          onQueued={handleQueued}
          onUseExisting={model === "Artisan" ? handleUseExisting : undefined}
        />
      ) : (
        <>
          <button type="button" className="field-button-secondary justify-self-start" onClick={mount}>
            Fill the {noun} in here
          </button>
          {workshopFields}
        </>
      )}

      <p role="status" className={liveRegionClass(busy, notice)}>
        {busy ? "Loading the record…" : notice?.text ?? ""}
      </p>

      {mirroredFields}
    </div>
  );
}

export function MirroredFieldsDisclosure({
  id,
  count,
  refused = 0,
  defaultOpen = false,
  children
}: {
  id: string;
  count: number;
  refused?: number;
  defaultOpen?: boolean;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(defaultOpen);
  const refusedBefore = useRef(refused);
  useEffect(() => {
    const had = refusedBefore.current;
    refusedBefore.current = refused;
    if (refused > 0 && had === 0) setOpen(true);
  }, [refused]);
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
        className="inline-flex items-center gap-2 text-sm font-medium text-purple-700 dark:text-purple-300 transition hover:text-purple-800 dark:hover:text-purple-200"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => setOpen((current) => !current)}
      >
        <ChevronDown className={`h-4 w-4 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
        Filled in from the linked record
        <span className="rounded-full bg-field-200 px-2 py-0.5 text-xs font-medium text-ink-700">{count}</span>
        {refused ? (
          <span className="rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-red-800">
            {refused} to fix
          </span>
        ) : null}
      </button>
      <div id={id} hidden={!open} className={open ? "mt-4 grid gap-3" : "hidden"}>
        <p className="text-xs leading-5 text-ink-muted">
          Filled in from the linked record where blank. Edits here don&rsquo;t change the record.
        </p>
        {children}
      </div>
    </div>
  );
}
