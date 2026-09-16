"use client";

/**
 * CHOOSE THE WORKBOOK, CHOOSE THE YEAR, AND DECIDE WHAT HAPPENS TO THE ROWS IT DOES NOT MENTION.
 *
 * ── THE CHECKBOX IS THE DANGEROUS CONTROL ON THIS SCREEN ────────────────────────────────────────
 *
 * "Withdraw the workshops missing from this sheet" ticked against a PARTIAL correction sheet of
 * twelve rows takes 288 planned workshops out of a ministry's directory in one press. So it ships
 * UNTICKED, its sentence sits beside it rather than in a tooltip, and the server treats everything
 * that is not an explicit yes as a no — three independent defences for one press, because the press
 * is not obviously destructive and its result is a screen that looks tidy.
 *
 * ── `DropCard` AND NOT THE `.file-trigger` LABEL ────────────────────────────────────────────────
 *
 * `DropCard`'s own docstring criticises that pattern by name: its input is `sr-only` inside a label,
 * so the focus ring is invisible and a keyboard reader cannot see where they are. `DropCard` has a
 * real `<button>` tab stop, a drag depth counter that survives a drag over a child element, and a
 * per-file `validate` — and `accept` is a FILTER ON THE DIALOG, never the rule. `validate` is.
 *
 * ── THE YEAR IS OPTIONAL, AND THE MISSING ONE IS THE COLD START ─────────────────────────────────
 *
 * `planYear` became `number | null` in 0.0.12. Null is not a broken caller: it is an empty directory,
 * which is the one sitting in which the ministry's FIRST plan is uploaded, and the page now mounts
 * this dialog in it. See the two prop docstrings for the two places that absence has to be spelled.
 *
 * AND THE YEAR IS STILL THE WORKBOOK'S TO NAME. There is deliberately NO year control on this
 * dialog, not even one shown only when `planYear` is null. The server resolves the year off the
 * Details sheet when the form scalar is absent, the blank pro-forma ships that cell empty for the
 * administrator to fill in, and the 422 they meet if they leave it blank is rendered verbatim below.
 * A number box here would put MIN_PLAN_YEAR/MAX_PLAN_YEAR on the client as a second copy of a server
 * bound, and would make the 409 at `annual_plan.py:382-389` — "this workbook says it is the
 * 2025-26 plan and it was uploaded as the 2026-27 plan" — reachable for the first time, by an
 * administrator who typed a year the sheet they chose contradicts. Sheet-only, and revisit it if
 * administrators are actually meeting that 422.
 */

import { useRef, useState } from "react";
import { AlertTriangle, FileSpreadsheet, Upload } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { DropCard } from "@/components/sketches/upload/DropCard";
import { describeApiDetail } from "@/lib/api";

import { uploadAnnualPlan, type PlanUploadReport } from "./annualPlan";

/**
 * Four megabytes, matching `MAX_UPLOAD_BYTES` on the route.
 *
 * STATED HERE AS WELL AS ENFORCED THERE, and that is not a duplicated rule: the server's ceiling is
 * the one that decides, and this one exists so the refusal arrives before a ministry letterhead PDF
 * is pushed up a village connection. If the two ever disagree the server wins, loudly, with a 413
 * that names the limit.
 */
const MAX_BYTES = 4 * 1024 * 1024;

export function UploadPlanDialog({
  open,
  onClose,
  planYear,
  planYearLabel,
  onUploaded
}: {
  open: boolean;
  onClose: () => void;
  /**
   * The year the directory screen is showing, or **null when it is showing none**. Sent so a sheet
   * cannot be filed under the wrong one.
   *
   * NULL IS NOT AN ERROR STATE HERE, IT IS THE COLD START. On a repository whose directory is empty
   * `GET /annual-plan/years` answers `200 []` and the page has no year to offer — and this is
   * precisely the sitting in which the first plan gets uploaded. The server was always built for it:
   * `upload_annual_plan` takes `planYear: str | None` and resolves the year as `typed if typed is
   * not None else parsed.planYear`, off the workbook's own Details sheet, 422ing only when BOTH are
   * absent. The blank pro-forma ships with that cell empty for the administrator to fill in.
   */
  planYear: number | null;
  /**
   * How the year reads on screen (`"2026-27"`), or `""` when there is none.
   *
   * ⚠ IT IS ALLOWED TO BE EMPTY AND MUST NOT BE BACKFILLED BY THE CALLER. The page used to pass
   * `yearLabel || String(planYear)`, which was unreachable while this dialog could not mount without
   * a year and became `"null"` the moment it could — putting "Upload the null plan" in the title of
   * a ministry administrator's dialog. The title below branches on `planYear` instead.
   */
  planYearLabel: string;
  onUploaded: (report: PlanUploadReport) => void;
}) {
  const [chosen, setChosen] = useState<File | null>(null);
  const [withdrawAbsent, setWithdrawAbsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const chooseRef = useRef<HTMLButtonElement | null>(null);

  function reset() {
    setChosen(null);
    setWithdrawAbsent(false);
    setError(null);
  }

  async function submit() {
    if (!chosen || busy) return;
    setBusy(true);
    setError(null);
    try {
      /*
        `planYear ?? undefined` AND NOT `planYear`, AND IT IS THE COMPILER THAT DECIDES THIS.

        `uploadAnnualPlan`'s option is `planYear?: number` and its body already skips the append when
        the value is nullish (`annualPlan.ts:223`), so at RUNTIME `null` would have been fine. It is
        the TYPE that refuses it: `frontend/tsconfig.json` is `"strict": true` with no
        `exactOptionalPropertyTypes`, and under plain `strict` an optional property accepts
        `number | undefined` — `null` is not assignable to it. Widening the prop above without this
        line does not compile.

        Not `planYear ?? 0` and not an omitted key built by hand, either: `_plan_year_or_422` reads
        `0` as a year and refuses it with a bounds message about MIN_PLAN_YEAR, which is a refusal
        about a number the administrator never typed. Absence is the thing that means "read it off
        the sheet", and `undefined` is how absence is spelled here.
      */
      const report = await uploadAnnualPlan(chosen, { planYear: planYear ?? undefined, withdrawAbsent });
      reset();
      onUploaded(report);
    } catch (caught) {
      // `describeApiDetail` because FastAPI's 422 detail is a LIST that stringifies to
      // "[object Object]" — the reason that helper exists, and the reason two private
      // re-implementations of it already had to be deleted.
      setError(
        describeApiDetail(
          (caught as { payload?: { detail?: unknown } })?.payload?.detail,
          caught instanceof Error ? caught.message : "The upload could not be read."
        )
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open={open}
      onClose={() => {
        if (busy) return;
        reset();
        onClose();
      }}
      busy={busy}
      // BRANCHED ON THE YEAR, NOT ON THE LABEL. `Upload the ${planYearLabel} plan` alone reads
      // "Upload the  plan" with a doubled space on the very sitting this dialog was reopened for —
      // the first upload into an empty directory, where there is no year yet to name. The caller
      // is forbidden from papering over the empty label (see `planYearLabel` above); this is where
      // the absence is spelled instead.
      title={planYear == null ? "Upload the annual plan" : `Upload the ${planYearLabel} plan`}
      description="The whole sheet, every time. Rows already in the plan are corrected, new rows are added, and uploading the same sheet twice changes nothing."
      icon={<FileSpreadsheet className="h-4 w-4" aria-hidden />}
      className="max-w-xl"
      initialFocusRef={chooseRef}
      footer={
        <>
          <button
            type="button"
            className="field-button-secondary"
            onClick={() => {
              reset();
              onClose();
            }}
            disabled={busy}
          >
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={!chosen || busy}>
            <Upload className="h-4 w-4" aria-hidden />
            {busy ? "Reading the sheet…" : "Upload"}
          </button>
        </>
      }
    >
      {error ? (
        <p className="mb-3 rounded-md border border-error-600/30 bg-error-100 px-3 py-2 text-sm leading-6 text-error-600">
          {error}
        </p>
      ) : null}

      <DropCard
        label="The plan workbook"
        buttonLabel="Choose the .xlsx file"
        accept=".xlsx,.xlsm,.xltx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        acceptSentence="An Excel workbook (.xlsx) — the pro-forma, filled in."
        buttonRef={chooseRef}
        disabled={busy}
        // THE RULE, as against `accept` above, which is only the dialog's filter. A file dragged in
        // never passes through that filter at all.
        validate={(file) =>
          /\.(xlsx|xlsm|xltx)$/i.test(file.name)
            ? file.size > MAX_BYTES
              ? `"${file.name}" is larger than 4 MB. A directory of three hundred workshops is well under that — check you have chosen the plan and not a scanned document.`
              : null
            : `"${file.name}" is not an Excel workbook. Use File > Save As in Excel and choose "Excel Workbook (.xlsx)".`
        }
        onFiles={(files) => {
          setChosen(files[0] ?? null);
          setError(null);
        }}
      >
        {chosen ? (
          <p className="mt-2 text-sm text-ink-700">
            Chosen: <span className="font-medium text-ink-900">{chosen.name}</span>
          </p>
        ) : null}
      </DropCard>

      <label className="mt-4 flex items-start gap-3 rounded-md border border-line-200 bg-surface-50 p-3">
        {/*
          `accent-purple-700` STAYS PURPLE ON A MINISTRY SURFACE, AND THAT IS S1's CALL RECORDED.

          The 0.0.12 ministry-accent hand-off table proposed swapping this to the ministry-700 rung
          and the foundations slice flagged it back as a conflict rather than applying it. (Written
          as a rung and not as the class it would be, deliberately: Tailwind's extractor is a plain
          regex over these files and does not know a comment from code, so spelling the utility here
          would ship a rule nothing uses to every page in the product.) `accent-color` is
          the native checkbox's CHECKED FILL — it is an input's action colour, and the owner's ruling
          on ministry orange is surface accent only: pale grounds, borders, the header chip, the desk
          tiles; "never spend orange on `.field-button` or `.field-input`", and every call to action
          stays purple-700. A ticked box is exactly a call to action, and on this dialog it is the
          dangerous one. It would also land ΔE 0.004 from the `amber-800` warning triangle that
          appears two lines below the moment it is ticked — the same colour, saying the opposite
          thing. The orange on this screen is the panel rule and the header chip, both of which the
          scoped block in globals.css draws without anybody touching a component.
        */}
        <input
          type="checkbox"
          className="mt-1 h-4 w-4 shrink-0 accent-purple-700"
          checked={withdrawAbsent}
          disabled={busy}
          onChange={(event) => setWithdrawAbsent(event.currentTarget.checked)}
        />
        <span className="text-sm leading-6 text-ink-700">
          <span className="font-medium text-ink-900">
            Withdraw the workshops that are in this year&apos;s plan and not in this sheet.
          </span>{" "}
          Leave this unticked if the sheet is a partial correction. A workshop that has already been
          opened is never withdrawn, whatever this says.
        </span>
      </label>

      {withdrawAbsent ? (
        <p className="mt-2 flex items-start gap-2 text-sm leading-6 text-amber-800">
          <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          Every planned row this sheet does not mention will be marked withdrawn. Nothing is deleted
          — a withdrawn row comes back the moment it appears in a later sheet.
        </p>
      ) : null}
    </FieldDialog>
  );
}
