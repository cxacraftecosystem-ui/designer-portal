"use client";

/**
 * The administrator's inbox: every filed report, and the two controls that answer one.
 *
 * ── WHY THIS EXISTS AT ALL: A WRITE PATH WITH NO READER IS A HOLE ──────────────────────────────
 *
 * Before the register landed, `/admin`'s "User feedback" tile was visible to every ADMIN and led to
 * `/feedback`, whose list was gated `isMasterAdmin` on the client and `require_master_admin` on the
 * server. An admin who followed that tile arrived at the feedback FORM and no inbox — the hub
 * advertised a queue that account could not read. This component is what makes that tile true.
 *
 * `GET /feedback/reports` is `require_admin` for the same reason: a redressal mechanism in which
 * exactly one account in the institution can acknowledge anything is a mechanism that will not
 * redress anything. The RATING list beside it stays master-admin — a colleague's standing opinion
 * of the software is a different thing from a complaint that needs answering, and widening who may
 * read it is not this change's to make.
 *
 * ── THE TWO CONTROLS, AND WHY ONE OF THEM INSISTS ON WORDS ─────────────────────────────────────
 *
 * "Mark as seen" writes a NAME and a TIME and promises nothing else, so its note is optional.
 * "Resolve" says the matter is finished, and the server refuses it without a note — an institution
 * that may close a grievance without saying how has a queue-clearing button, not a redressal
 * mechanism. The dialog therefore disables its own submit until something is typed, so the refusal
 * is met before the round trip rather than as a 422 the reader has to interpret.
 *
 * ── AND WHY THE FILTERS ARE A NARROWING, NEVER A DEFAULT ───────────────────────────────────────
 *
 * The list opens UNFILTERED. A queue that opens on "SUBMITTED only" hides the acknowledged reports
 * that have been sitting unanswered for a month, which are exactly the ones a redressal mechanism
 * fails at. `openCount` is drawn beside the heading and counts the whole table, never the page, so
 * narrowing to grievances cannot report the queue as empty while eleven bug reports go unread.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Inbox } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { FeedbackReportCard } from "@/components/feedback/FeedbackReportCard";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { Pagination } from "@/components/Pagination";
import { Select } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { apiFetch } from "@/lib/api";
import type { FeedbackReport, FeedbackReportPage, FeedbackVocabulary } from "@/lib/feedback";

const PAGE_SIZE = 10;

export function FeedbackReportsInbox({ vocabulary }: { vocabulary: FeedbackVocabulary | null }) {
  const [items, setItems] = useState<FeedbackReport[] | null>(null);
  const [total, setTotal] = useState(0);
  const [pages, setPages] = useState(0);
  const [openCount, setOpenCount] = useState<number | null>(null);
  const [page, setPage] = useState(1);
  const [kind, setKind] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [error, setError] = useState<string | null>(null);
  /** The report a decision dialog is open for, and which of the two decisions it is. */
  const [deciding, setDeciding] = useState<{ report: FeedbackReport; resolve: boolean } | null>(null);

  // Generation counter, not an abort — `apiFetch` takes no signal and what matters is ignoring the
  // late answer (§14.5). One counter covers paging AND the two filters, so they cannot race.
  const load = useRef(0);

  const read = useCallback(async (wanted: number, wantedKind: string, wantedStatus: string) => {
    const generation = ++load.current;
    const query = new URLSearchParams({ page: String(wanted), pageSize: String(PAGE_SIZE) });
    if (wantedKind) query.set("kind", wantedKind);
    if (wantedStatus) query.set("status", wantedStatus);
    try {
      const result = await apiFetch<FeedbackReportPage>(`/feedback/reports?${query.toString()}`);
      if (generation !== load.current) return;
      setItems(result.items);
      setTotal(result.total);
      setPages(result.pages);
      // `?? null` and never `?? 0`: absent means "we could not ask", and a confident 0 over a queue
      // we failed to read is a number an admin would act on by not opening it. Same rule the /admin
      // hub's badge states for the pending-access count.
      setOpenCount(result.openCount ?? null);
      setError(null);
    } catch (err) {
      if (generation !== load.current) return;
      setError(err instanceof Error ? err.message : "Could not read the feedback queue.");
      setItems((current) => current ?? []);
    }
  }, []);

  useEffect(() => {
    void read(page, kind, statusFilter);
  }, [read, page, kind, statusFilter]);

  return (
    <section className="mt-8">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="font-display text-lg font-bold text-ink-900">Grievances, suggestions and recommendations</h2>
        {/*
          The number is never alone — the word beside it says what the number IS. A bare digit in a
          heading is decoration an admin learns to ignore, which is the same rule the /admin hub's
          badge carries.
        */}
        {openCount ? <p className="text-xs font-medium text-amber-800">{openCount} not yet read</p> : null}
      </div>

      <div className="panel mb-3 grid gap-3 p-4 sm:grid-cols-2">
        <FieldBlock label="Kind">
          <Select
            value={kind}
            searchable={false}
            // The filter narrows the list on the same screen, so focus must NOT jump to the next
            // control after a pick (§17) — the reader's next act is to read the list, not to type.
            advanceOnSelect={false}
            onChange={(event) => {
              setKind(event.target.value);
              setPage(1);
            }}
            aria-label="Filter by kind"
          >
            <option value="">Every kind</option>
            {(vocabulary?.kind ?? []).map((choice) => (
              <option key={choice.value} value={choice.value}>
                {choice.label}
              </option>
            ))}
          </Select>
        </FieldBlock>
        <FieldBlock label="Status">
          <Select
            value={statusFilter}
            searchable={false}
            advanceOnSelect={false}
            onChange={(event) => {
              setStatusFilter(event.target.value);
              setPage(1);
            }}
            aria-label="Filter by status"
          >
            <option value="">Every status</option>
            {(vocabulary?.status ?? []).map((choice) => (
              <option key={choice.value} value={choice.value}>
                {choice.label}
              </option>
            ))}
          </Select>
        </FieldBlock>
      </div>

      {error ? (
        <div className="mb-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}

      {!items ? (
        <div className="panel p-4 text-sm text-ink-500">Loading...</div>
      ) : items.length === 0 ? (
        <div className="panel p-4">
          <EmptyState
            title={kind || statusFilter ? "Nothing matches that filter" : "Nothing reported yet"}
            body={
              kind || statusFilter
                ? "Clear the filters above to see the whole queue."
                : "Grievances, suggestions, recommendations and bug reports from either app appear here."
            }
          />
        </div>
      ) : (
        <>
          <div className="grid gap-3">
            {items.map((report) => (
              <FeedbackReportCard
                key={report.id}
                report={report}
                showAuthor
                actions={
                  report.status === "RESOLVED" ? null : (
                    <>
                      {report.status === "SUBMITTED" ? (
                        <button
                          className="field-button-secondary"
                          type="button"
                          onClick={() => setDeciding({ report, resolve: false })}
                        >
                          <Inbox className="h-4 w-4" aria-hidden />
                          Mark as seen
                        </button>
                      ) : null}
                      <button className="field-button" type="button" onClick={() => setDeciding({ report, resolve: true })}>
                        <Check className="h-4 w-4" aria-hidden />
                        Resolve
                      </button>
                    </>
                  )
                }
              />
            ))}
          </div>
          {/*
            Wrapped in a `panel` because `Pagination` draws a bare `border-t` footer meant to sit
            INSIDE a bordered container — the list pages all mount it as the last child of one. Left
            loose under a stack of cards it reads as a rule ruled across the page from nowhere.
          */}
          {pages > 1 ? (
            <div className="panel mt-3">
              <Pagination page={page} pages={pages} total={total} onPage={setPage} />
            </div>
          ) : null}
        </>
      )}

      {deciding ? (
        <DecisionDialog
          report={deciding.report}
          resolve={deciding.resolve}
          onClose={() => setDeciding(null)}
          onDone={() => {
            setDeciding(null);
            void read(page, kind, statusFilter);
          }}
        />
      ) : null}
    </section>
  );
}

/**
 * The note dialog for both decisions.
 *
 * ONE DIALOG, TWO MODES, because the parts that must not drift are shared: both write a name and a
 * time, both re-read the list afterwards, and both put the note in front of the administrator with
 * the sentence "the person who filed this reads it" above the box. What differs is whether the box
 * may be left empty, and that is one boolean rather than two components.
 *
 * NOT a `ConfirmProvider` confirm: this needs a text box, and `useConfirm` answers yes/no.
 */
function DecisionDialog({
  report,
  resolve,
  onClose,
  onDone
}: {
  report: FeedbackReport;
  resolve: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Mirrors the server's rule rather than restating it as a second policy: resolving needs words,
  // acknowledging does not. The server is still the authority — this only spares the reader a 422.
  const blocked = resolve && note.trim().length === 0;

  async function send() {
    setBusy(true);
    setError(null);
    try {
      await apiFetch(`/feedback/reports/${report.id}/${resolve ? "resolve" : "acknowledge"}`, {
        method: "POST",
        body: JSON.stringify({ note: note.trim() || null })
      });
      onDone();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not record that.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <FieldDialog
      open
      onClose={onClose}
      // `busy` is what stops Escape and the backdrop dismissing the dialog mid-write, so the reader
      // cannot walk away from a request whose answer they still need to see.
      busy={busy}
      title={resolve ? "Resolve this report" : "Mark this report as seen"}
      description={
        resolve
          ? "Say what was done. The person who filed it reads this."
          : "Records your name and the time. Add a note if you want to — they will read it."
      }
      footer={
        <>
          <button className="field-button-secondary" type="button" onClick={onClose} disabled={busy}>
            Cancel
          </button>
          <button className="field-button" type="button" onClick={send} disabled={busy || blocked}>
            {busy ? "Saving..." : resolve ? "Resolve" : "Mark as seen"}
          </button>
        </>
      }
    >
      <div className="grid gap-1">
        <label className="field-label" htmlFor="feedback-decision-note">
          {resolve ? "What was done" : "Note (optional)"}
        </label>
        <textarea
          className="field-input"
          id="feedback-decision-note"
          rows={4}
          maxLength={5000}
          value={note}
          onChange={(event) => setNote(event.target.value)}
        />
        {error ? <p className="text-sm text-error-600">{error}</p> : null}
      </div>
    </FieldDialog>
  );
}
