"use client";

/**
 * THE CONFIRMATION STEP — and the whole design of it is what it does NOT ask about.
 *
 * ══ THE RULE THIS PANEL IS BUILT ON ═════════════════════════════════════════════════════════════
 *
 *     A confirmation dialog that asks about everything is as useless as one that asks about
 *     nothing. An officer asked forty questions answers them the way they answer a cookie banner —
 *     at which point the one row that genuinely needed a decision goes through with the rest.
 *
 * So the server sorts every row into three lists and only ONE of them is a question:
 *
 * * **Ready** — settled without a human. A designer who has never used the portal, a name that
 *   differs from the record only in case or spacing, a duplicated address in one cell, two spellings
 *   of one Gmail: all mechanical, all auto-resolved, each with a warning saying what was assumed.
 *   They are drawn here read-only, collapsed, with a tick to drop any of them — because "I did not
 *   mean to include that one" is an answer this screen must accept, and it is not a question.
 * * **Needs your decision** — the only rows with controls on them. The name and the address name two
 *   different known people; the two cells hold different numbers of entries; there are names and no
 *   addresses; there is an address nobody in the building has a name for. Every one of these is a
 *   case where a human could legitimately have meant something different, and guessing files a
 *   ministry workshop, an account and a credential under the wrong person.
 * * **Cannot be recorded** — read-only, with the reason verbatim, and **no control at all**. An
 *   ended empanelment, a barred address, an account that cannot run a workshop, the officer's own
 *   mailbox, a mailbox two accounts answer to: every one is a decision an ADMINISTRATOR took on a
 *   screen, and a confirmation step that could overturn one would make a spreadsheet the senior
 *   authority in this product.
 *
 * ══ WHAT AN OFFICER MAY EDIT HERE, AND WHAT THEY MAY NOT ════════════════════════════════════════
 *
 * **THE PAIRING, AND NOTHING ELSE.** Names and addresses can be re-paired, a designer dropped, a row
 * dropped. The number, the date and the amount are read-only: they are what the ministry ISSUED, and
 * `SanctionOrderUpdate` already refuses to let an officer edit them on a row that exists — an
 * importer that let them be edited on the way IN would be a second rule for one column.
 *
 * ══ WHY IT IS A PANEL AND NOT A DIALOG ══════════════════════════════════════════════════════════
 *
 * Two hundred rows do not belong in a modal. A `FieldDialog` traps focus and closes on Escape from
 * anywhere inside it — which is right for a file chooser and wrong for a screen holding twenty
 * unsaved decisions about who a ministry instrument names. It is a panel on the page, and leaving it
 * is a deliberate press.
 *
 * ══ THE CONFIRMATION IS STATELESS, AND THIS COMPONENT IS WHERE THAT COST LANDS ══════════════════
 *
 * There is no token: this client holds the entire resolved import and posts it back. So a tab left
 * open over lunch posts a resolution built against a register that has moved on — and that is not
 * silent, because every refusal runs again per row on the server and a stale row comes back REFUSED
 * with its own sentence rather than recorded wrongly. What is genuinely lost is the guarantee that
 * the sheet on screen is the sheet these numbers came from, which is why the filename and the row
 * numbers travel with the body.
 */

import { useMemo, useState } from "react";
import { AlertTriangle, CheckCircle2, HelpCircle, Plus, X } from "lucide-react";

import { Field, TextInput } from "@/components/FormControls";
import { formatSanctionAmount, type SanctionImportPreview, type SanctionImportRow } from "@/lib/sanctionOrders";

/** One designer as the officer has resolved them. Name and address, because that is all an order needs. */
type Pair = { name: string; email: string };

/** One row's editable state. `record: false` is "leave it out", which is an answer and not an absence. */
type Decision = { record: boolean; designers: Pair[] };

/**
 * The starting state, taken from the server's proposal.
 *
 * READY ROWS START TICKED AND REVIEW ROWS START UNTICKED, and the asymmetry is the whole ergonomic
 * argument. A ready row was settled without a human, so requiring a press for each of two hundred of
 * them would make the confirmation step a chore whose only effect is to train officers to press
 * "select all". A review row has a question on it, so it stays out until somebody answers — an
 * unanswered question must never default to "record it anyway", because the thing being defaulted is
 * whose name goes on a ministry instrument.
 */
function initialDecisions(preview: SanctionImportPreview): Record<number, Decision> {
  const state: Record<number, Decision> = {};
  for (const row of preview.ready) {
    state[row.sheetRow] = {
      record: true,
      designers: row.designers.map((designer) => ({ name: designer.name, email: designer.email }))
    };
  }
  for (const row of preview.needsReview) {
    state[row.sheetRow] = {
      record: false,
      designers: row.designers.map((designer) => ({ name: designer.name, email: designer.email }))
    };
  }
  return state;
}

/** A row is answerable when every designer on it has both halves. Blank rows cannot be recorded. */
function answered(decision: Decision): boolean {
  return (
    decision.designers.length > 0 &&
    decision.designers.every((pair) => pair.name.trim() !== "" && pair.email.trim() !== "")
  );
}

function RowHead({ row }: { row: SanctionImportRow }) {
  return (
    <div className="flex flex-wrap items-baseline justify-between gap-2">
      <span className="font-display text-sm font-semibold text-ink-900">
        Row {row.sheetRow} · {row.sanctionOrderNo}
      </span>
      <span className="text-sm text-ink-700">
        {row.sanctionOrderDate} · {formatSanctionAmount(row.sanctionAmount)}
      </span>
    </div>
  );
}

export function SanctionImportReview({
  preview,
  busy,
  onCancel,
  onConfirm
}: {
  preview: SanctionImportPreview;
  busy: boolean;
  onCancel: () => void;
  onConfirm: (rows: {
    sheetRow: number;
    action: "record" | "skip";
    sanctionOrderNo: string;
    sanctionOrderDate: string;
    sanctionAmount: string;
    notes?: string | null;
    designers: Pair[];
  }[]) => void;
}) {
  const [decisions, setDecisions] = useState<Record<number, Decision>>(() =>
    initialDecisions(preview)
  );

  const editable = useMemo(
    () => [...preview.ready, ...preview.needsReview],
    [preview.ready, preview.needsReview]
  );

  function update(sheetRow: number, next: Partial<Decision>) {
    setDecisions((current) => ({
      ...current,
      [sheetRow]: { ...current[sheetRow]!, ...next }
    }));
  }

  function setPair(sheetRow: number, index: number, patch: Partial<Pair>) {
    const current = decisions[sheetRow];
    if (!current) return;
    const designers = current.designers.map((pair, at) =>
      at === index ? { ...pair, ...patch } : pair
    );
    update(sheetRow, { designers });
  }

  /**
   * Which rows still have an unanswered question.
   *
   * DRAWN AS A COUNT ON THE CONFIRM BUTTON rather than enforced by disabling it. An officer who has
   * decided to leave three rows out is finished, and a button that stayed dead until every row was
   * ticked would be a button that lies about what is missing.
   */
  const outstanding = preview.needsReview.filter((row) => {
    const decision = decisions[row.sheetRow];
    return decision?.record && !answered(decision);
  }).length;

  const willRecord = editable.filter((row) => decisions[row.sheetRow]?.record).length;

  function confirm() {
    onConfirm(
      editable.map((row) => {
        const decision = decisions[row.sheetRow]!;
        const keep = decision.record && answered(decision);
        return {
          sheetRow: row.sheetRow,
          action: keep ? ("record" as const) : ("skip" as const),
          sanctionOrderNo: row.sanctionOrderNo,
          // NON-NULL BY CONSTRUCTION: a row with no readable date or amount was REFUSED by the
          // server and is not in `ready` or `needsReview` at all — the confirm body's schema could
          // not express it either, because `sanctionOrderDate` is a required date there. The
          // fallbacks are for the type checker and are never reached; a row that somehow arrived
          // here without them would be refused again on the way in, by name.
          sanctionOrderDate: row.sanctionOrderDate ?? "",
          sanctionAmount: row.sanctionAmount ?? "0",
          notes: row.notes,
          // THE LEAD IS ELEMENT 0, in the order on screen. Never sorted: this list is the officer's
          // answer to "who leads this order", and the first name is the one that reaches the report.
          designers: decision.designers.map((pair) => ({
            name: pair.name.trim(),
            email: pair.email.trim()
          }))
        };
      })
    );
  }

  return (
    <section className="panel mb-5 p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="font-display text-base font-semibold text-ink-900">
            Check this sheet before anything is recorded
          </h2>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            {preview.rowsRead} row{preview.rowsRead === 1 ? "" : "s"} read
            {preview.sheet ? ` from "${preview.sheet}"` : null} · {preview.ready.length} ready ·{" "}
            {preview.needsReview.length} need{preview.needsReview.length === 1 ? "s" : ""} your
            decision · {preview.refused.length} cannot be recorded
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <button type="button" className="field-button-secondary" onClick={onCancel} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="field-button" onClick={confirm} disabled={busy}>
            {busy
              ? "Recording…"
              : `Record ${willRecord} order${willRecord === 1 ? "" : "s"}`}
          </button>
        </div>
      </div>

      {outstanding > 0 ? (
        <p className="mt-3 flex items-start gap-2 text-sm leading-6 text-amber-800">
          <AlertTriangle className="mt-1 h-4 w-4 shrink-0" aria-hidden />
          {outstanding} row{outstanding === 1 ? " is" : "s are"} ticked to record and still missing a
          name or an address. {outstanding === 1 ? "It" : "They"} will be left out and listed in the
          report.
        </p>
      ) : null}

      {/* ── NEEDS A DECISION — the only rows on this screen with controls on them ──────────── */}
      {preview.needsReview.length > 0 ? (
        <div className="mt-4">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <span className="grid h-6 w-6 place-items-center rounded-md bg-amber-100 text-amber-800">
              <HelpCircle className="h-3.5 w-3.5" aria-hidden />
            </span>
            Needs your decision ({preview.needsReview.length})
          </h3>
          <ul className="mt-2 grid gap-3">
            {preview.needsReview.map((row) => {
              const decision = decisions[row.sheetRow]!;
              return (
                <li
                  key={row.sheetRow}
                  className="rounded-md border border-amber-200 bg-amber-50/40 p-3 dark:border-amber-900 dark:bg-amber-950/20"
                >
                  <RowHead row={row} />
                  {row.questions.map((question) => (
                    <div key={question.code} className="mt-2">
                      {/* VERBATIM. Written on the server to be read by a person. */}
                      <p className="text-sm leading-6 text-ink-700">{question.question}</p>
                      {question.candidates.length > 0 ? (
                        <div className="mt-2 flex flex-wrap gap-2">
                          {question.candidates.map((candidate) => (
                            <button
                              key={`${candidate.source}-${candidate.email}-${candidate.name}`}
                              type="button"
                              className="field-button-secondary"
                              onClick={() =>
                                update(row.sheetRow, {
                                  record: true,
                                  designers: [{ name: candidate.name, email: candidate.email }]
                                })
                              }
                            >
                              Use {candidate.name || candidate.email}
                              {candidate.email ? ` · ${candidate.email}` : ""}
                            </button>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  ))}

                  {row.unpairedNames.length > 0 || row.unpairedEmails.length > 0 ? (
                    <p className="mt-2 text-xs leading-5 text-ink-500">
                      {row.unpairedNames.length > 0
                        ? `Names with no address: ${row.unpairedNames.join(", ")}. `
                        : null}
                      {row.unpairedEmails.length > 0
                        ? `Addresses with no name: ${row.unpairedEmails.join(", ")}.`
                        : null}
                    </p>
                  ) : null}

                  <div className="mt-3 grid gap-2">
                    {decision.designers.map((pair, index) => (
                      <div key={index} className="grid gap-2 md:grid-cols-[1fr_1fr_auto]">
                        <Field label={index === 0 ? "Designer's name (lead)" : "Designer's name"}>
                          <TextInput
                            value={pair.name}
                            maxLength={160}
                            onChange={(event) =>
                              setPair(row.sheetRow, index, { name: event.currentTarget.value })
                            }
                          />
                        </Field>
                        <Field label="Designer's Gmail ID">
                          <TextInput
                            value={pair.email}
                            type="email"
                            onChange={(event) =>
                              setPair(row.sheetRow, index, { email: event.currentTarget.value })
                            }
                          />
                        </Field>
                        <div className="flex items-end">
                          <button
                            type="button"
                            className="field-button-secondary"
                            aria-label={`Remove designer ${index + 1} from row ${row.sheetRow}`}
                            onClick={() =>
                              update(row.sheetRow, {
                                designers: decision.designers.filter((_, at) => at !== index)
                              })
                            }
                          >
                            <X className="h-4 w-4" aria-hidden />
                          </button>
                        </div>
                      </div>
                    ))}
                    <div>
                      <button
                        type="button"
                        className="field-button-secondary"
                        onClick={() =>
                          update(row.sheetRow, {
                            designers: [...decision.designers, { name: "", email: "" }]
                          })
                        }
                      >
                        <Plus className="h-4 w-4" aria-hidden />
                        Add a designer
                      </button>
                    </div>
                  </div>

                  <label className="mt-3 flex items-center gap-2 text-sm text-ink-700">
                    <input
                      type="checkbox"
                      className="h-4 w-4 accent-purple-700"
                      checked={decision.record}
                      onChange={(event) =>
                        update(row.sheetRow, { record: event.currentTarget.checked })
                      }
                    />
                    Record this order
                  </label>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}

      {/* ── READY — settled without a human, and droppable all the same ───────────────────── */}
      {preview.ready.length > 0 ? (
        <div className="mt-4">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <span className="grid h-6 w-6 place-items-center rounded-md bg-success-100 text-success-600">
              <CheckCircle2 className="h-3.5 w-3.5" aria-hidden />
            </span>
            Ready to record ({preview.ready.length})
          </h3>
          <ul className="mt-2 rounded-md border border-line-200 bg-surface-50">
            {preview.ready.map((row) => {
              const decision = decisions[row.sheetRow]!;
              return (
                <li key={row.sheetRow} className="border-t border-line-200 px-3 py-2 first:border-t-0">
                  <div className="flex flex-wrap items-baseline justify-between gap-2">
                    <label className="flex items-center gap-2 text-sm text-ink-700">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-purple-700"
                        checked={decision.record}
                        onChange={(event) =>
                          update(row.sheetRow, { record: event.currentTarget.checked })
                        }
                      />
                      <span className="font-medium text-ink-900">
                        Row {row.sheetRow} · {row.sanctionOrderNo}
                      </span>
                    </label>
                    <span className="text-sm text-ink-700">
                      {row.sanctionOrderDate} · {formatSanctionAmount(row.sanctionAmount)}
                    </span>
                  </div>
                  <p className="mt-1 text-sm leading-6 text-ink-500">
                    {row.designers
                      .map(
                        (designer, index) =>
                          `${designer.name} (${designer.email})${index === 0 && row.designers.length > 1 ? " — lead" : ""}${designer.accountExists ? "" : " — new account"}`
                      )
                      .join(", ")}
                  </p>
                </li>
              );
            })}
          </ul>
        </div>
      ) : null}

      {/* ── CANNOT BE RECORDED — read-only, and no control, deliberately ──────────────────── */}
      {preview.refused.length > 0 ? (
        <div className="mt-4">
          <h3 className="flex items-center gap-2 text-sm font-semibold text-ink-900">
            <span className="grid h-6 w-6 place-items-center rounded-md bg-error-100 text-error-600">
              <AlertTriangle className="h-3.5 w-3.5" aria-hidden />
            </span>
            Cannot be recorded ({preview.refused.length})
          </h3>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            There is nothing to decide about these. An address an administrator has barred, an
            empanelment an administrator has ended, an account that is not a designer&apos;s, your own
            address, or a row the sheet did not give enough of. Correct the sheet, or the roster, and
            upload again.
          </p>
          <ul className="mt-2 rounded-md border border-line-200 bg-surface-50">
            {preview.refused.map((row) => (
              <li key={row.sheetRow} className="border-t border-line-200 px-3 py-2 first:border-t-0">
                <p className="text-xs font-medium uppercase tracking-wide text-ink-500">
                  Row {row.sheetRow} · {row.sanctionOrderNo}
                </p>
                {/* VERBATIM, and it is the same sentence the officer's own form would have shown. */}
                <p className="mt-1 text-sm leading-6 text-ink-700">{row.reason}</p>
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {/* ── WHAT THE READ HAD TO ASSUME, on the rows it did not have to ask about ─────────── */}
      {preview.problems.filter((problem) => problem.severity !== "error").length > 0 ? (
        <div className="mt-4">
          <h3 className="text-sm font-semibold text-ink-900">
            What was assumed ({preview.problems.filter((p) => p.severity !== "error").length})
          </h3>
          <p className="mt-1 text-sm leading-6 text-ink-muted">
            These rows are ready. Each line says what the read had to assume, so you can check it
            against the paper rather than being asked about it.
          </p>
          <ul className="mt-2 rounded-md border border-line-200 bg-surface-50">
            {preview.problems
              .filter((problem) => problem.severity !== "error")
              .map((problem, index) => (
                <li
                  key={`${problem.row}-${index}`}
                  className="border-t border-line-200 px-3 py-2 text-sm leading-6 text-ink-700 first:border-t-0"
                >
                  <span className="text-xs font-medium uppercase tracking-wide text-ink-500">
                    {problem.row != null ? `Row ${problem.row}` : "This workbook"}
                  </span>
                  <br />
                  {problem.reason}
                </li>
              ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}
