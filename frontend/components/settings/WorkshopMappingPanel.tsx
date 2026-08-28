"use client";

/**
 * "Which records are not filed under any workshop, and where do they belong?"
 *
 * WHY THIS SCREEN EXISTS. Every control that narrows by workshop reads one column, `workshopId`. A record
 * with that column empty counts towards NO workshop scope — while remaining perfectly visible under "All
 * records". Since the app opens scoped to the most recent workshop, the result reads as "nothing was
 * documented here" rather than as a filter excluding data that is sitting right there. That is exactly what
 * happened: twenty-five questionnaire interviews and nine hundred and twenty-four media files, all recorded
 * at the one workshop in the repository, none of them carrying its id, and a completion matrix showing
 * nothing but the cells an admin had ticked by hand.
 *
 * The column arrived after that fieldwork, so this will keep happening whenever a client is a version
 * behind — hence a button rather than only a migration. `backend/app/services/workshop_inference.py` holds
 * the evidence ladder both this and the migration share.
 *
 * IT IS A PREVIEW FIRST, ALWAYS. The report is a plain read and renders on load; nothing is written until
 * somebody presses the button, and the button says exactly how many rows it will touch. Rows the evidence
 * cannot settle are listed BY NAME with the reason, because "949 of 949" is a fine answer and "947 of 949"
 * needs to say which two and why — that is the difference between a tool an admin can trust and one they
 * have to verify by hand afterwards.
 *
 * AND SINCE 2026-08-28, THOSE ROWS ARE SOMETHING TO PRESS. Naming the person a decision is being handed to
 * is only half a handover. This block used to read "Left alone — open the record and choose its workshop by
 * hand" over a flat `<ul>` of titles with no link, no id and no control on it: the admin had to leave the
 * page, find each record in a twenty-row list somewhere else, and edit it there, and a record that should
 * never have been recorded could not be got rid of from here at all. Each row is now an activatable card
 * (`UnfiledRecordCard`) opening the three acts the owner asked for — open the record, file it under a
 * workshop by hand, or delete it permanently. `POST` / `DELETE /workshops/unmapped/{bucket}/{id}` are the
 * endpoints, both `require_admin`, which is the same gate this whole report already sits behind.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Link2, RefreshCw } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import {
  UnfiledRecordCard,
  UnfiledRecordDialog,
  type UnfiledRecord
} from "@/components/settings/UnfiledRecordCard";
import { apiFetch } from "@/lib/api";
import { isAdmin } from "@/lib/permissions";

type MappingRow = {
  id: string;
  title: string;
  workshopId: string | null;
  workshopTitle: string | null;
  rung: string | null;
  rungCopy: string | null;
  reason: string | null;
  reasonCopy: string | null;
  candidateTitles: string[];
};

type MappingBucket = {
  bucket: string;
  singular: string;
  plural: string;
  unassigned: number;
  resolved: number;
  unresolved: number;
  byRung: Array<{ rung: string; copy: string; count: number }>;
  byReason: Array<{ reason: string; copy: string; count: number }>;
  byWorkshop: Array<{ workshopId: string; title: string; count: number }>;
  rows: MappingRow[];
  rowsTruncated: boolean;
  applied: number | null;
};

export type WorkshopMappingPlan = {
  /**
   * The WINDOWS the date rung was decided against — dated workshops only, because `build_windows`
   * skips a workshop with neither `startDate` nor `date`. Rendered as the caption at the foot.
   */
  workshops: Array<{ id: string; title: string; start: string; end: string }>;
  /**
   * EVERY workshop, dated or not, for the hand-assignment picker. A workshop with no dates can never
   * win the date rung, so it is exactly the kind of workshop whose records land on this report — and
   * driving the picker off `workshops` above would hide the destination an admin is most likely to
   * be reaching for.
   *
   * OPTIONAL IN THE TYPE ON PURPOSE, the same way `AddressReference.districts` is: this client and
   * the API deploy separately, so a browser can be a version ahead of the server it is talking to.
   * Absent means "this server does not send it yet", and the picker falls back to the dated list AND
   * says on screen that it is short — it must never imply a complete list it does not have.
   */
  allWorkshops?: Array<{ id: string; title: string }>;
  buckets: MappingBucket[];
  totals: { unassigned: number; resolved: number; unresolved: number; applied: number | null };
};

/** Type-of noun for the counts sentence. Plural unless the count is exactly one, as everywhere else. */
function noun(bucket: MappingBucket, count: number): string {
  return count === 1 ? bucket.singular : bucket.plural;
}

/** One day, in the reader's own locale. An unparseable value is echoed rather than rendered blank. */
function formatDay(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleDateString();
}

/**
 * The LAST DAY a window covers, from its exclusive end.
 *
 * The server sends `end` as midnight of the day AFTER the workshop's last day (see `build_windows`), so
 * printing it raw would name a day the window does not include — a caption that contradicts the rule it
 * is explaining.
 */
function formatLastDay(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  date.setDate(date.getDate() - 1);
  return date.toLocaleDateString();
}

export function WorkshopMappingPanel() {
  const { user } = useAuth();
  const [plan, setPlan] = useState<WorkshopMappingPlan | null>(null);
  const [loading, setLoading] = useState(true);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [applied, setApplied] = useState<number | null>(null);
  /**
   * The card whose actions are open, and whether the dialog is showing.
   *
   * TWO PIECES OF STATE FOR ONE THING, deliberately: `selected` outlives `dialogOpen` so the exit
   * animation plays over the real record instead of an empty card. `ConfirmProvider` keeps its
   * `options` past `open` for exactly this reason.
   */
  const [selected, setSelected] = useState<UnfiledRecord | null>(null);
  const [dialogOpen, setDialogOpen] = useState(false);
  /**
   * What the last single-record action did, in words. Kept apart from `applied` (the bulk button's
   * own sentence) because they report different acts, and one overwriting the other would tell an
   * admin that the row they just deleted had been filed.
   */
  const [rowNotice, setRowNotice] = useState<string | null>(null);

  /**
   * MAY THIS READER ACT? Admin and master admin, mirroring the `require_admin` both endpoints carry.
   *
   * The panel is already mounted only for `isAdmin(user)` on `/workshops`, so in practice this is
   * true whenever the component exists. It is re-asked here anyway, for two reasons that are not
   * paranoia: this component is exported and a second caller would inherit the parent's gate by
   * accident rather than by decision, and a control an unentitled reader can press is a refusal
   * dressed as a feature. The gate that MATTERS is the server's — a client guard alone is not a
   * guard — and the two are the same predicate on purpose.
   */
  const canAct = isAdmin(user);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPlan(await apiFetch<WorkshopMappingPlan>("/workshops/unmapped"));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The unmapped records could not be read.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function apply() {
    setApplying(true);
    setError(null);
    try {
      const result = await apiFetch<WorkshopMappingPlan>("/workshops/unmapped/map", { method: "POST" });
      setApplied(result.totals.applied ?? 0);
      // RE-READ rather than render the apply response. That response is built from the ladder run the
      // writes were derived FROM — it is a record of what was done, not a picture of what is left — so
      // rendering it would leave the tiles reporting the records just filed as still unfiled and the
      // button offering to file them again. A fresh read is the only thing that can say "nothing left".
      await load();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The records could not be mapped.");
    } finally {
      setApplying(false);
    }
  }

  /**
   * A single record filed or deleted. RE-READS the plan for exactly the reason `apply()` above does:
   * the response describes what was done to ONE row, not what is left, so patching the row out of
   * local state would leave every count on this panel — the three tiles, the per-bucket lines, the
   * bulk button's own "file N records" — describing a repository that no longer exists. It is one
   * request against an admin screen; the alternative is six numbers quietly drifting.
   */
  const afterRowAction = useCallback(
    async (notice: string) => {
      setRowNotice(notice);
      setDialogOpen(false);
      await load();
    },
    [load]
  );

  // Only the buckets that have something to say. A panel listing "0 product records" five times over
  // buries the one line that matters.
  const buckets = (plan?.buckets ?? []).filter((bucket) => bucket.unassigned > 0);
  const totals = plan?.totals;

  /**
   * The picker's list, sorted by title here rather than trusted from the API — the same rule every
   * other consumer of a workshop list in this client follows, so the form and this panel cannot
   * disagree about an order.
   */
  const pickerWorkshops = useMemo(() => {
    const complete = plan?.allWorkshops;
    const source = complete ?? (plan?.workshops ?? []).map((entry) => ({ id: entry.id, title: entry.title }));
    return [...source].sort((a, b) => a.title.localeCompare(b.title));
  }, [plan]);
  const pickerComplete = Boolean(plan?.allWorkshops);

  return (
    <section className="panel p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2 font-display text-sm font-bold text-ink-900">
            <Link2 className="h-4 w-4 text-purple-700" aria-hidden />
            Records not filed under a workshop
          </h2>
          <p className="mt-1 max-w-2xl text-xs leading-5 text-ink-500">
            A record with no workshop is excluded from every workshop scope — the search box, the map, the
            data browser, the exports and the completion matrix — while still appearing under{" "}
            <span className="font-medium text-ink-700">All records</span>. That reads as an empty workshop
            rather than as a filter. This finds them and files each one where its own evidence points: the
            record it hangs off, the artisans in it, or the workshop whose dates it was recorded inside.
          </p>
        </div>
        <button
          type="button"
          onClick={() => void load()}
          disabled={loading || applying}
          className="field-button-secondary shrink-0"
        >
          <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} aria-hidden />
          Re-check
        </button>
      </div>

      {error ? (
        <p className="mt-3 rounded-md bg-error-100 px-3 py-2 text-xs text-error-600">{error}</p>
      ) : null}

      {loading && !plan ? (
        <p className="mt-3 text-xs text-ink-500">Reading every record that names no workshop…</p>
      ) : null}

      {plan && totals ? (
        <>
          {totals.unassigned === 0 ? (
            <p className="mt-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-700">
              <Check className="h-4 w-4 shrink-0 text-success-600" aria-hidden />
              Every record in the repository names the workshop it was captured at. Nothing is hidden from a
              workshop scope.
            </p>
          ) : (
            <>
              <dl className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
                <Stat label="Not filed" value={totals.unassigned} />
                <Stat label="Can be filed now" value={totals.resolved} tone="good" />
                <Stat label="Need a person" value={totals.unresolved} tone={totals.unresolved ? "warn" : undefined} />
              </dl>

              <ul className="mt-3 grid gap-2">
                {buckets.map((bucket) => (
                  <li key={bucket.bucket} className="rounded-md border border-line-200 bg-surface-50 p-3">
                    <p className="text-xs font-semibold text-ink-900">
                      {bucket.unassigned} {noun(bucket, bucket.unassigned)}
                      {bucket.applied !== null ? (
                        <span className="ml-1 font-normal text-success-600">
                          — {bucket.applied} filed just now
                        </span>
                      ) : (
                        <span className="ml-1 font-normal text-ink-500">
                          — {bucket.resolved} can be filed
                          {bucket.unresolved ? `, ${bucket.unresolved} need a person` : ""}
                        </span>
                      )}
                    </p>

                    {bucket.byWorkshop.length ? (
                      <p className="mt-1 text-[11px] leading-4 text-ink-500">
                        Filed under{" "}
                        {bucket.byWorkshop
                          .map((entry) => `${entry.title} (${entry.count})`)
                          .join(", ")}
                        .
                      </p>
                    ) : null}

                    {/* WHICH RULE DECIDED, per row, aggregated. An admin approving a bulk write needs to
                        know whether 924 files were filed because their parent records said so or because
                        their timestamps merely fell in a window — those are different levels of certainty
                        and the panel must not flatten them into one number. */}
                    {bucket.byRung.length ? (
                      <ul className="mt-1.5 grid gap-0.5">
                        {bucket.byRung.map((entry) => (
                          <li key={entry.rung} className="text-[11px] leading-4 text-ink-700">
                            <span className="font-semibold text-ink-900">{entry.count}</span> because{" "}
                            {entry.copy}.
                          </li>
                        ))}
                      </ul>
                    ) : null}

                    {bucket.unresolved ? (
                      (() => {
                        const stuck = bucket.rows.filter((row) => !row.workshopId);
                        return (
                          /* NOT `overflow-hidden`. The cards inside are full-width buttons and the
                             global focus ring is an `outline` drawn OUTSIDE the border box at
                             `outline-offset: 2px` — clipping this block would erase the ring on the
                             card a keyboard user is standing on. */
                          <div className="mt-2 rounded border border-amber-100 bg-amber-100/40 p-2">
                            <p className="flex items-center gap-1.5 text-[11px] font-semibold text-amber-800">
                              <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden />
                              {canAct
                                ? "Left alone — open one to file it by hand or discard it"
                                : "Left alone — open the record and choose its workshop by hand"}
                            </p>
                            {canAct ? (
                              /* A GRID OF CARDS, one per row. Two columns from `sm` up: these titles
                                 are filenames and interview names, and a single column of forty of
                                 them pushes the workshop form on this page below the fold. */
                              <ul className="mt-1.5 grid gap-1.5 sm:grid-cols-2">
                                {stuck.map((row) => {
                                  const record: UnfiledRecord = {
                                    bucket: bucket.bucket,
                                    singular: bucket.singular,
                                    row
                                  };
                                  return (
                                    <li key={row.id}>
                                      <UnfiledRecordCard
                                        record={record}
                                        onOpen={() => {
                                          setSelected(record);
                                          setDialogOpen(true);
                                        }}
                                      />
                                    </li>
                                  );
                                })}
                              </ul>
                            ) : (
                              /* NOT AN ADMIN: the same rows, as plain text. Rendering the cards
                                 disabled would be a screen full of controls that refuse — the rule
                                 this client follows for every gated action is to not render it, and
                                 the names and reasons are still worth reading. */
                              <ul className="mt-1 grid gap-0.5">
                                {stuck.map((row) => (
                                  <li key={row.id} className="text-[11px] leading-4 text-ink-700">
                                    <span className="font-medium text-ink-900">{row.title}</span>
                                    {row.reasonCopy ? <span className="text-ink-500"> — {row.reasonCopy}</span> : null}
                                    {row.candidateTitles.length ? (
                                      <span className="text-ink-500"> ({row.candidateTitles.join(" or ")})</span>
                                    ) : null}
                                  </li>
                                ))}
                              </ul>
                            )}
                            {/* Compared against THIS list's own length, not `rowsTruncated`. That flag is
                                about the bucket's whole row list, which the server caps and fills with
                                resolved rows too — reading it here reported "the list is capped" on a
                                complete list of two, which is a false alarm on the one block an admin has
                                to act on.

                                Still the rule with cards on screen, and more load-bearing than it was:
                                a grid that stops has no scrollbar and no last row to notice, so an
                                admin who works through every card on it would otherwise believe the
                                job finished. */}
                            {stuck.length < bucket.unresolved ? (
                              <p className="mt-1.5 text-[11px] leading-4 text-ink-500">
                                {bucket.unresolved - stuck.length} more{" "}
                                {bucket.unresolved - stuck.length === 1 ? "is" : "are"} not shown here — the
                                server sends at most a page of rows per record type. Re-check after clearing
                                these to see the rest.
                              </p>
                            ) : null}
                          </div>
                        );
                      })()
                    ) : null}
                  </li>
                ))}
              </ul>

              {totals.resolved > 0 ? (
                <div className="mt-3 flex flex-wrap items-center gap-3">
                  <button type="button" onClick={apply} disabled={applying} className="field-button">
                    {applying
                      ? "Filing…"
                      : `File ${totals.resolved} record${totals.resolved === 1 ? "" : "s"} under its workshop`}
                  </button>
                  <p className="text-[11px] leading-4 text-ink-500">
                    Only ever fills an empty workshop — nothing already filed is moved or cleared, and
                    pressing it twice changes nothing.
                  </p>
                </div>
              ) : null}
            </>
          )}

          {/* WHAT THE LAST SINGLE-RECORD ACTION DID, in the words `unfiledRecords` writes for both
              outcomes. Above the bulk sentence, because it is the more recent act. It survives the
              re-read that follows the action — the row it names has left the list by then, and
              "gone from the list" is not a report of what happened to it. */}
          {rowNotice ? (
            <p className="mt-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs leading-5 text-ink-700">
              <Check className="h-4 w-4 shrink-0 text-success-600" aria-hidden />
              {rowNotice}
            </p>
          ) : null}

          {applied !== null ? (
            <p className="mt-3 flex items-center gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-xs text-ink-700">
              <Check className="h-4 w-4 shrink-0 text-success-600" aria-hidden />
              {applied === 0
                ? "Nothing left to file — every record the evidence could settle was already filed."
                : `${applied} record${applied === 1 ? "" : "s"} filed. They now appear under their workshop everywhere: the search box, the map, the data browser, the exports and the completion matrix.`}
            </p>
          ) : null}

          {plan.workshops.length ? (
            /* The WINDOWS, not just the titles. An end date is stored differently by each client — the
               web form sends the last millisecond of the day, Android sends midnight — so "recorded
               inside that workshop's dates" is only checkable if the dates the server read are on
               screen. The end shown is the last day the window covers, not the exclusive bound. */
            <ul className="mt-3 grid gap-0.5 text-[11px] leading-4 text-ink-300">
              {plan.workshops.map((workshop) => (
                <li key={workshop.id}>
                  {workshop.title} — read as {formatDay(workshop.start)} to {formatLastDay(workshop.end)}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-3 text-[11px] leading-4 text-ink-500">
              No workshop in the repository has a date, so nothing can be filed by when it was recorded.
              Adding a start and end date to a workshop makes that evidence available.
            </p>
          )}
        </>
      ) : null}

      {/* OUTSIDE the `plan` branch, so a re-read cannot unmount it mid-exit-animation, and rendered
          only for a reader who may act — a dialog full of controls the server would 403 is not a
          feature. `selected` is kept past the close on purpose (see its declaration). */}
      {canAct ? (
        <UnfiledRecordDialog
          open={dialogOpen}
          record={selected}
          workshops={pickerWorkshops}
          workshopsComplete={pickerComplete}
          onClose={() => setDialogOpen(false)}
          onDone={afterRowAction}
        />
      ) : null}
    </section>
  );
}

function Stat({
  label,
  value,
  tone
}: {
  label: string;
  value: number;
  tone?: "good" | "warn";
}) {
  return (
    <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2">
      <dt className="text-[11px] font-medium uppercase tracking-wide text-ink-500">{label}</dt>
      <dd
        className={`mt-0.5 font-display text-xl font-bold ${
          tone === "good" ? "text-success-600" : tone === "warn" ? "text-amber-800" : "text-ink-900"
        }`}
      >
        {value}
      </dd>
    </div>
  );
}
