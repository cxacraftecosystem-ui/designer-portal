"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";

import { CappedListNotice } from "@/components/data/CappedListNotice";
import { changedKeys, EDITABLE_KEY_SET, type EditableKey } from "@/components/designworkshop/headerDiff";
import { linkedWorkshopView } from "@/components/designworkshop/linkedWorkshopPicker";
import {
  WORKSHOP_NAME_REASSURANCE,
  workshopNameCreateLabel
} from "@/components/designworkshop/StageWorkshopNameField";
import { DateRangePicker, fromIsoDate, toIsoDate } from "@/components/forms/DateTimeField";
import { useRecordOffPage } from "@/components/forms/recordPickers";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { CraftSelect } from "@/components/forms/CraftSelect";
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
import { ActionGroup } from "@/components/ui/ActionGroup";

const LINK_SEARCH_DEBOUNCE_MS = 300;

const TITLE_MAX_LENGTH = 220;

const READ_ONLY_COVER: { key: keyof DwSummary; label: string; why: string }[] = [
  {
    key: "workshopCode",
    label: "Workshop ID",
    why: "prints on the report cover and is what a scanned card resolves to"
  },
  { key: "venue", label: "Venue", why: "collected in stage 1" },
  { key: "scheme", label: "Scheme", why: "collected in stage 1" },
  {
    key: "designerName",
    label: "Designer",
    why: "printed as the author on the report cover and certification"
  },
  { key: "implementingAgency", label: "Implementing agency", why: "collected in stage 1" },
  { key: "sponsor", label: "Sponsor", why: "collected in stage 1" }
];

type Refusal = { field: EditableKey | null; message: string };

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

function describeSaveRefusal(err: unknown, serverSaid: string): string {
  const status = err instanceof ApiError ? err.status : 0;
  if (status === 403) {
    return `${serverSaid || "You can't edit this workshop."} Nothing was changed.`;
  }
  if (status === 404) {
    return (
      "You no longer have access to this workshop. Nothing was changed."
    );
  }
  if (status === 409) {
    return (
      `${serverSaid || "This workshop is deleted. Restore it before editing."} An admin can restore it from ` +
      "Deleted workshops."
    );
  }
  return serverSaid
    ? `The workshop was not changed: ${serverSaid}`
    : "The workshop was not changed. Please try again.";
}

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
  const [problem, setProblem] = useState<string | null>(null);
  const [fieldProblem, setFieldProblem] = useState<Partial<Record<EditableKey, string>>>({});
  const [nothingToSend, setNothingToSend] = useState(false);
  const initialCraftId = (initial as DwSummary & { craftId?: string | null }).craftId ?? "";
  const [craftId, setCraftId] = useState(initialCraftId);
  const [craftName, setCraftName] = useState(initial.craftName ?? "");

  const [templates, setTemplates] = useState<DwTemplate[]>([]);
  const [title, setTitle] = useState(initial.title ?? "");
  const [nameList, setNameList] = useState<WorkshopListState<DwSummary>>({ kind: "loading" });
  const [nameTerm, setNameTerm] = useState("");
  const [nameApplied, setNameApplied] = useState("");
  const [namePending, setNamePending] = useState(true);
  const [templateId, setTemplateId] = useState(initial.templateId);
  const [workshopId, setWorkshopId] = useState(initial.workshopId ?? "");
  const [linkList, setLinkList] = useState<WorkshopListState<Workshop>>({ kind: "loading" });
  const [linkTerm, setLinkTerm] = useState("");
  const [linkApplied, setLinkApplied] = useState("");
  const [linkPending, setLinkPending] = useState(true);
  const [seenLink, setSeenLink] = useState<Workshop | null>(null);
  const [duration, setDuration] = useState<{ from?: Date; to?: Date }>(() => ({
    from: fromIsoDate(initial.startDate),
    to: fromIsoDate(initial.endDate)
  }));

  const { dirty, markDirty, resetDirty } = useUnsavedChanges();

  function noteEdit() {
    markDirty();
    if (nothingToSend) setNothingToSend(false);
    if (problem !== null) setProblem(null);
    if (Object.keys(fieldProblem).length) setFieldProblem({});
  }
  const [promptOpen, setPromptOpen] = useState(false);
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
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    const trimmed = linkTerm.trim();
    setLinkPending(true);
    const timer = window.setTimeout(() => {
      listResource<Workshop>("/workshops", {
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        workshopType: "DESIGN_PROTOTYPE",
        accessibleOnly: "true",
        search: trimmed || undefined
      })
        .then((result) => {
          if (cancelled) return;
          setLinkList({ kind: "ok", rows: result.items ?? [], total: result.total });
          setLinkApplied(trimmed);
        })
        .catch(() => {
          if (!cancelled) setLinkList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setLinkPending(false);
        });
    }, trimmed ? LINK_SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [linkTerm]);

  useEffect(() => {
    let cancelled = false;
    const trimmed = nameTerm.trim();
    setNamePending(true);
    const timer = window.setTimeout(() => {
      listDesignWorkshops({
        page: 1,
        pageSize: WORKSHOP_OPTION_PAGE_SIZE,
        search: trimmed || undefined
      })
        .then((page) => {
          if (cancelled) return;
          setNameList({ kind: "ok", rows: page.items ?? [], total: page.total });
          setNameApplied(trimmed);
        })
        .catch(() => {
          if (!cancelled) setNameList({ kind: "failed" });
        })
        .finally(() => {
          if (!cancelled) setNamePending(false);
        });
    }, trimmed ? LINK_SEARCH_DEBOUNCE_MS : 0);
    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [nameTerm]);

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
      if (!name || name.length > TITLE_MAX_LENGTH || seen.has(name)) continue;
      seen.add(name);
      options.push({ value: name, label: name });
    }
    return options;
  }, [nameList, title]);

  const nameVoice: WorkshopListVoice = {
    table: "design",
    accessList: false,
    scoped: true,
    reassurance: WORKSHOP_NAME_REASSURANCE,
    online: !deviceLooksOffline()
  };
  const nameSearchedToNothing =
    nameList.kind === "ok" && nameList.rows.length === 0 && nameApplied.length > 0;
  const nameNotice = nameSearchedToNothing ? "" : workshopListNotice(nameList, nameVoice);

  function commitTitle(next: string) {
    noteEdit();
    if (next.trim().length > TITLE_MAX_LENGTH) {
      setFieldProblem((current) => ({
        ...current,
        title: `Too long — ${next.trim().length} of ${TITLE_MAX_LENGTH} characters.`
      }));
      return;
    }
    setTitle(next);
  }

  const linkRows = useMemo<Workshop[]>(() => (linkList.kind === "ok" ? [...linkList.rows] : []), [linkList]);

  useEffect(() => {
    const stored = initial.workshopId ?? "";
    if (!stored) return;
    const found = linkRows.find((row) => row.id === stored);
    if (found) setSeenLink(found);
  }, [linkRows, initial.workshopId]);

  const offPageWorkshop = useRecordOffPage<Workshop>("/workshops", initial.workshopId ?? "", linkRows);

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

  const repointsLink = Boolean(initial.workshopId) && workshopId !== (initial.workshopId ?? "");

  function onScreenValues(form: FormData): Record<EditableKey, string> {
    const box = (key: EditableKey) => String(form.get(key) ?? "").trim();
    return {
      title: title.trim(),
      templateId: templateId.trim(),
      craftName: craftName.trim(),
      clusterName: box("clusterName"),
      state: box("state"),
      district: box("district"),
      startDate: box("startDate"),
      endDate: box("endDate"),
      workshopId: workshopId.trim(),
      notes: box("notes")
    };
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const onScreen = onScreenValues(form);

    setProblem(null);
    setFieldProblem({});
    setNothingToSend(false);

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
      setNothingToSend(true);
      return;
    }

    const body: DwUpdateBody & { craftId?: string | null } = {};
    for (const key of changed) {
      switch (key) {
        case "title":
          body.title = onScreen.title;
          break;
        case "templateId":
          body.templateId = onScreen.templateId;
          break;
        case "craftName":
          body.craftName = onScreen.craftName || null;
          body.craftId = craftId || null;
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
      await adoptServerSummaries([updated]).catch(() => undefined);
      resetDirty();
      router.push(recordHref);
    } catch (err) {
      if (isUnreachable(err)) {
        setProblem(
          "No connection — nothing was saved. Save again when you're online."
        );
        return;
      }
      const refusal = readRefusal(err);
      if (refusal.field) {
        const against: Partial<Record<EditableKey, string>> = {};
        against[refusal.field] = refusal.message;
        setFieldProblem(against);
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

  function cancel() {
    if (!dirty) {
      router.push(recordHref);
      return;
    }
    setPromptFromCancel(true);
    setPromptOpen(true);
  }

  const templateOptions = useMemo(() => {
    const offered = templates.map((template) => ({ value: template.id, label: template.name }));
    if (offered.some((option) => option.value === initial.templateId)) return offered;
    return [{ value: initial.templateId, label: initial.templateId }, ...offered];
  }, [templates, initial.templateId]);

  const titleErrorId = `${baseId}-title-error`;
  const titleListId = `${baseId}-title-list`;
  const titleScopeId = `${baseId}-title-scope`;
  const templateErrorId = `${baseId}-templateId-error`;
  const linkErrorId = `${baseId}-workshopId-error`;
  const linkScopeId = `${baseId}-workshopId-scope`;
  const linkGapId = `${baseId}-workshopId-gap`;
  const linkCutId = `${baseId}-workshopId-cut`;
  const datesErrorId = `${baseId}-dates-error`;

  return (
    <>
      <form
        ref={formRef}
        onSubmit={submit}
        onInput={noteEdit}
        className="panel grid gap-4 p-4"
      >
        <div>
          <h2 className="font-display text-lg font-bold text-ink-900">Workshop details</h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            Name, report type, place, dates and linked workshop.
          </p>
        </div>

        <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-4">
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
                {nameNotice ? (
                  <p id={titleListId} className="text-xs leading-5 text-ink-500" aria-live="polite">
                    {nameNotice}
                  </p>
                ) : null}
                <p id={titleScopeId} className="text-xs leading-5 text-ink-500">
                  Pick a name or type a new one.
                </p>
              </>
            }
          >
            <Dropdown
              value={title.trim()}
              onChange={commitTitle}
              options={titleOptions}
              placeholder="Type or pick a name"
              disabled={saving}
              emptyLabel={workshopEmptyLabel(nameList, nameVoice)}
              describedBy={`${fieldProblem.title ? `${titleErrorId} ` : ""}${titleListId} ${titleScopeId}`}
              serverQuery={{ value: nameTerm, onChange: setNameTerm, pending: namePending }}
              createAction={{ label: workshopNameCreateLabel, onCreate: commitTitle }}
            />
          </FieldBlock>

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
                noteEdit();
                setTemplateId(next);
              }}
              options={templateOptions}
              ariaLabel="Report template"
              describedBy={fieldProblem.templateId ? templateErrorId : undefined}
              searchable
              disabled={saving}
            />
          </FieldBlock>

          <FieldBlock label="Craft">
            <CraftSelect
              ariaLabel="Craft"
              value={craftId}
              placeholder={initialCraftId ? undefined : initial.craftName || undefined}
              fallbackLabels={initialCraftId && initial.craftName ? { [initialCraftId]: initial.craftName } : undefined}
              disabled={saving}
              describedBy={fieldProblem.craftName ? `${baseId}-craftName-error` : undefined}
              onDirty={markDirty}
              onChange={(nextId, craft) => {
                setCraftId(nextId);
                setCraftName(craft?.name ?? "");
              }}
            />
            {fieldProblem.craftName ? (
              <p id={`${baseId}-craftName-error`} role="alert" className="text-xs text-error-600">
                {fieldProblem.craftName}
              </p>
            ) : null}
          </FieldBlock>
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

        <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          The report uses these from{" "}
          <Link href={stageOneHref} className="font-medium underline">
            stage 1, Workshop Setup &amp; Cover Information
          </Link>
          . Edits here only change how the workshop is listed.
        </p>

        <FieldBlock
          label="Linked workshop record"
          hint={
            <>
              {link.notice ? (
                <p id={linkScopeId} className="text-xs leading-5 text-ink-500">
                  {link.notice}
                </p>
              ) : null}
              {link.gap ? (
                <p id={linkGapId} className="mt-1 text-xs leading-5 text-ink-500">
                  {link.gap}
                </p>
              ) : null}
              <CappedListNotice id={linkCutId} cuts={[link.cut]} />
              {repointsLink ? (
                <p className="mt-1 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
                  Records filed under the old link will no longer appear in this workshop&apos;s pickers.
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
          <div onInput={(event) => event.stopPropagation()}>
            <Dropdown
              value={workshopId}
              onChange={(next) => {
                noteEdit();
                setWorkshopId(next);
              }}
              options={link.options}
              ariaLabel="Linked workshop record"
              describedBy={
                fieldProblem.workshopId
                  ? `${linkScopeId} ${linkGapId} ${linkCutId} ${linkErrorId}`
                  : `${linkScopeId} ${linkGapId} ${linkCutId}`
              }
              noneLabel={NO_FIELD_WORKSHOP}
              emptyLabel={link.emptyLabel}
              placeholder={link.placeholder}
              serverQuery={{ value: linkTerm, onChange: setLinkTerm, pending: linkPending }}
              disabled={saving || link.standingDown}
            />
          </div>
        </FieldBlock>

        <WritableTextField
          label="Notes"
          name="notes"
          maxLength={20000}
          defaultValue={initial.notes ?? ""}
          error={fieldProblem.notes}
          errorId={`${baseId}-notes-error`}
          multiline
        />

        <div className="grid gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
          <div>
            <h3 className="text-sm font-medium text-ink-900">Filled in from stage 1</h3>
            <p className="mt-1 text-xs leading-5 text-ink-500">
              Set in stage 1.
            </p>
          </div>
          <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {READ_ONLY_COVER.map((entry) => {
              const value = initial[entry.key];
              return (
                <div key={entry.key} className="min-w-0">
                  <dt className="field-label">{entry.label}</dt>
                  <dd>
                    <span className={value ? "block text-sm text-ink-900" : "block text-sm italic text-ink-500"}>
                      {typeof value === "string" && value.trim() ? value : "Not recorded yet"}
                    </span>
                    <span className="mt-0.5 block text-xs leading-5 text-ink-500">{entry.why}</span>
                  </dd>
                </div>
              );
            })}
          </dl>
          <p className="text-xs leading-5 text-ink-500">
            <Link href={stageOneHref} className="font-medium text-purple-700 dark:text-purple-300 underline">
              Open stage 1 to change any of these
            </Link>
            .
          </p>
        </div>

        <p className="text-xs leading-5 text-ink-500">
          <strong>Status</strong> is set on the{" "}
          <Link href={recordHref} className="font-medium text-purple-700 dark:text-purple-300 underline">
            workshop page
          </Link>
          . <strong>Designers</strong> are added from the{" "}
          <Link href="/design-workshops" className="font-medium text-purple-700 dark:text-purple-300 underline">
            design workshops list
          </Link>
          .
        </p>

        {problem ? (
          <div role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700">
            {problem}
          </div>
        ) : null}
        {nothingToSend ? (
          <div role="status" className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            No changes to save.
          </div>
        ) : null}

        <ActionGroup align="end">
          <button type="button" className="field-button-secondary" onClick={cancel} disabled={saving}>
            Cancel
          </button>
          <button className="field-button" disabled={saving}>
            {saving ? "Saving…" : "Save details"}
          </button>
        </ActionGroup>
      </form>

      <UnsavedChangesDialog
        open={promptOpen}
        saving={saving}
        onKeepEditing={() => {
          setPromptOpen(false);
          setPromptFromCancel(false);
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
          completeLeave();
        }}
        onSave={() => {
          setPromptOpen(false);
          setPromptFromCancel(false);
          formRef.current?.requestSubmit();
        }}
      />
    </>
  );
}
