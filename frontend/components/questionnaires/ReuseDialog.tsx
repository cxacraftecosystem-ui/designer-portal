"use client";

import { useCallback, useEffect, useId, useMemo, useState } from "react";
import { CopyPlus } from "lucide-react";

import { attachHold, HeldPostNotice, useHeldPostRefusal, writesHeld } from "@/components/designworkshop/HeldPostNotice";
import { FieldDialog } from "@/components/dialogs";
import { Field, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { listQuestionnaires, reuseQuestionnaire, type QFormReuseResult } from "@/lib/questionnaireForms";
import { ATTACH_LATER, designWorkshopOptions, type DesignWorkshopRow } from "@/lib/workshopOptions";

export type ReuseTarget = DesignWorkshopRow;

const REUSED = "reused";

function reusedTitle(sourceTitle: string, taken: string[]): string {
  const base = (sourceTitle || "").trim().slice(0, 200).trimEnd() || "Questionnaire";
  const lowered = new Set(taken.map((existing) => existing.trim().toLowerCase()));
  let candidate = `${base} (${REUSED})`;
  let n = 2;
  while (lowered.has(candidate.toLowerCase())) {
    candidate = `${base} (${REUSED} ${n})`;
    n += 1;
  }
  return candidate;
}

export function ReuseDialog({
  open,
  onClose,
  onReused,
  questionnaireId,
  sourceTitle,
  sourceWorkshopId,
  workshops,
  workshopsNotice
}: {
  open: boolean;
  onClose: () => void;
  onReused: (result: QFormReuseResult) => void;
  questionnaireId: string;
  sourceTitle: string;
  sourceWorkshopId?: string | null;
  workshops: ReuseTarget[];
  workshopsNotice?: string;
}) {
  const [designWorkshopId, setDesignWorkshopId] = useState("");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [atTarget, setAtTarget] = useState<string[] | null>(null);
  const targetHold = useHeldPostRefusal(designWorkshopId || null);
  const hold = attachHold(targetHold, "the copy");
  const holdNoticeId = useId();

  const defaultTitle = useMemo(() => reusedTitle(sourceTitle, atTarget ?? []), [sourceTitle, atTarget]);
  const plainDefault = useMemo(() => reusedTitle(sourceTitle, []), [sourceTitle]);

  const targets = useMemo(() => {
    const built = designWorkshopOptions(
      { kind: "ok", rows: workshops, total: workshops.length },
      { group: true, offPage: { mode: "refuse" } }
    );
    return built.options.map((option) =>
      option.value && option.value === sourceWorkshopId
        ? {
            ...option,
            hint: option.hint
              ? `where this one already is · ${option.hint}`
              : "where this one already is"
          }
        : option
    );
  }, [workshops, sourceWorkshopId]);

  const reset = useCallback(() => {
    setDesignWorkshopId("");
    setTitle("");
    setError(null);
    setAtTarget(null);
  }, []);

  useEffect(() => {
    if (open) reset();
  }, [open, reset]);

  useEffect(() => {
    if (!open) {
      setAtTarget(null);
      return;
    }
    let cancelled = false;
    const look = designWorkshopId
      ? listQuestionnaires({ designWorkshopId, pageSize: 100, activeOnly: false })
      : listQuestionnaires({ mineOnly: true, pageSize: 100, activeOnly: false });
    look
      .then((result) => {
        if (cancelled) return;
        const rows = result.items ?? [];
        setAtTarget(
          (designWorkshopId ? rows : rows.filter((row) => row.designWorkshopId === null)).map(
            (row) => row.title
          )
        );
      })
      .catch(() => {
        if (!cancelled) setAtTarget(null);
      });
    return () => {
      cancelled = true;
    };
  }, [open, designWorkshopId]);

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit() {
    if (writesHeld(hold)) return;
    setBusy(true);
    setError(null);
    try {
      const result = await reuseQuestionnaire(questionnaireId, {
        ...(designWorkshopId ? { designWorkshopId } : {}),
        ...(title.trim() ? { title: title.trim() } : {})
      });
      reset();
      onReused(result);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to reuse this questionnaire");
    } finally {
      setBusy(false);
    }
  }

  const typed = title.trim();
  const collides =
    Boolean(typed) && (atTarget ?? []).some((existing) => existing.trim().toLowerCase() === typed.toLowerCase());
  const place = designWorkshopId
    ? {
        already: "is already at that workshop",
        both: "both will appear in that workshop’s report annexure under the same name",
        apart: "in the report annexure"
      }
    : {
        already: "of yours is already waiting to be attached to a workshop",
        both: "both will sit in your questionnaire list under the same name",
        apart: "in your list"
      };
  const numbered = !typed && defaultTitle !== plainDefault;
  const sameWorkshop = Boolean(designWorkshopId) && designWorkshopId === sourceWorkshopId;

  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title="Reuse this questionnaire at another workshop"
      description="Copies the questions, not the answers."
      icon={<CopyPlus className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button
            type="button"
            className="field-button"
            onClick={submit}
            disabled={busy || writesHeld(hold)}
            aria-describedby={typeof hold === "string" ? holdNoticeId : undefined}
          >
            {busy ? "Copying the questions…" : "Create the reuse"}
          </button>
        </>
      }
    >
      <div className="grid gap-4">
        {error ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
        ) : null}

        <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <ul className="grid gap-1">
            <li>
              <span className="font-medium text-ink-900">Questions are copied</span>, except retired ones.
            </li>
            <li>
              <span className="font-medium text-ink-900">Answers are not.</span> Your copy starts empty.
            </li>
            <li>
              <span className="font-medium text-ink-900">They&rsquo;re separate.</span> Later edits apply to one only.
            </li>
          </ul>
        </div>

        <FieldBlock label="Design workshop for the copy">
          {workshops.length ? (
            <div className="grid gap-2">
              <Dropdown
                value={designWorkshopId}
                onChange={setDesignWorkshopId}
                options={targets}
                noneLabel={ATTACH_LATER}
                ariaLabel="Design workshop for the copy"
                searchable
                disabled={busy}
                advanceOnSelect={false}
              />
              {workshopsNotice ? (
                <p className="text-xs leading-5 text-ink-500" aria-live="polite">
                  {workshopsNotice}
                </p>
              ) : null}
              <p className="text-xs leading-5 text-ink-500">
                This list shows the newest workshops only, and the box searches only what is listed. If the one you
                want is not here, leave this as &ldquo;{ATTACH_LATER}&rdquo; and attach the copy from its own page
                afterwards.
              </p>
            </div>
          ) : (
            <div className="grid gap-2">
              {workshopsNotice ? (
                <p className="text-sm leading-6 text-ink-700" aria-live="polite">
                  {workshopsNotice}
                </p>
              ) : null}
              <p className="text-sm leading-6 text-ink-700">
                No workshops to attach to. You can attach it later.
              </p>
            </div>
          )}
        </FieldBlock>

        <HeldPostNotice refusal={hold} id={holdNoticeId} className="" sayPending />

        <Field label="Title for the copy">
          <TextInput
            value={title}
            maxLength={220}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={defaultTitle}
          />
        </Field>

        {collides ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            A questionnaire called <span className="font-medium">“{typed}”</span> {place.already}. This is allowed — a
            second round of the same instrument is an ordinary thing to run — but the title you have typed is used as it
            stands, so {place.both}. Clear the box to have it numbered for you, or say which round this one is.
          </p>
        ) : numbered ? (
          <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            A questionnaire of that name {place.already}, so the copy will be called{" "}
            <span className="font-medium text-ink-900">“{defaultTitle}”</span> — numbered up so the two are tellable apart{" "}
            {place.apart}. Type a title to say which round it is instead.
          </p>
        ) : sameWorkshop ? (
          <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            Already attached here. Both copies will print in the report.
          </p>
        ) : null}
      </div>
    </FieldDialog>
  );
}
