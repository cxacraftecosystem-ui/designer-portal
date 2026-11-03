"use client";

import { Fragment, useEffect, useId, useState } from "react";
import { Check, ClipboardCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { queuePageNotice } from "@/components/data/cappedList";
import { CappedListNotice } from "@/components/data/CappedListNotice";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { OpenableCard, OpenableRow, OpenableRowLink } from "@/components/data/OpenableRow";
import { ResizableTh } from "@/components/ResizableTh";
import { ReviewEditPanel } from "@/components/review/ReviewEditPanel";
import { reviewEditableFields } from "@/components/review/reviewEditFields";
import { RowActions, rowAction } from "@/components/RowActions";
import { StatusBadge } from "@/components/StatusBadge";
import { readableError } from "@/components/review/reviewErrors";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { canEditOthersRecord, hasRank, isAdmin, roleLabel } from "@/lib/permissions";
import { reviewRecordViewHref } from "@/lib/recordView";
import { ActionGroup } from "@/components/ui/ActionGroup";

type PendingItem = {
  recordType: string;
  id: string;
  label: string;
  place?: string | null;
  status?: string | null;
  createdAt?: string | null;
  createdBy?: { id?: string; name?: string | null; role?: string | null } | null;
  needsAdminApproval?: boolean;
  outOfWindow?: boolean;
};

type PendingEnvelope = {
  items: PendingItem[];
  shown?: number;
  total?: number;
  cap?: number;
  truncated?: boolean;
  nextBefore?: string | null;
};

function rowKey(item: PendingItem) {
  return `${item.recordType}-${item.id}`;
}

const PAGE_SIZE = 20;

const QUEUE_PAGE = 50;
const QUEUE_PAGE_MAX = 200;

type DecisionAction = "approve" | "revise" | "reject";
type PanelAction = DecisionAction | "edit";

const ACTION_COPY: Record<DecisionAction, { noteLabel: string; confirmLabel: string }> = {
  approve: { noteLabel: "Approval note (optional)", confirmLabel: "Confirm approve" },
  revise: { noteLabel: "What must the contributor change? (required)", confirmLabel: "Send for revision" },
  reject: { noteLabel: "Rejection note (optional)", confirmLabel: "Confirm reject" }
};

export default function ReviewPage() {
  const confirm = useConfirm();
  const { user } = useAuth();
  const [items, setItems] = useState<PendingItem[]>([]);
  const [queue, setQueue] = useState<{ total: number; more: boolean; nextBefore: string | null }>({
    total: 0,
    more: false,
    nextBefore: null
  });
  const [loading, setLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState<string | null>(null);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [page, setPage] = useState(1);
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState<{ key: string; action: PanelAction } | null>(null);
  const [note, setNote] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [info, setInfo] = useState<string | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [bulkProgress, setBulkProgress] = useState<{ done: number; total: number } | null>(null);
  const noteId = useId();
  const noteHelpId = `${noteId}-help`;
  const viewerIsAdmin = isAdmin(user);

  function readEnvelope(result: PendingEnvelope, rows: PendingItem[]) {
    setItems(rows);
    setQueue({
      total: result.total ?? rows.length,
      more: Boolean(result.truncated) && Boolean(result.nextBefore),
      nextBefore: result.nextBefore ?? null
    });
  }

  async function load() {
    const size = Math.min(QUEUE_PAGE_MAX, Math.max(QUEUE_PAGE, items.length));
    try {
      const result = await apiFetch<PendingEnvelope>(`/review/pending?pageSize=${size}`);
      readEnvelope(result, result.items);
      setLoadFailed(null);
    } catch (err) {
      const reason = readableError(err, "The review queue could not be read.");
      if (items.length === 0) setLoadFailed(reason);
      else setError(reason);
    } finally {
      setLoading(false);
    }
  }

  async function loadOlder() {
    if (!queue.nextBefore) return;
    setLoadingOlder(true);
    try {
      const result = await apiFetch<PendingEnvelope>(
        `/review/pending?pageSize=${QUEUE_PAGE}&before=${encodeURIComponent(queue.nextBefore)}`
      );
      const seen = new Set(items.map(rowKey));
      readEnvelope(result, [...items, ...result.items.filter((item) => !seen.has(rowKey(item)))]);
      setError(null);
    } catch (err) {
      setError(readableError(err, "Older records could not be loaded. Try again."));
    } finally {
      setLoadingOlder(false);
    }
  }

  function retryFirstLoad() {
    setLoading(true);
    setLoadFailed(null);
    void load();
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const pages = Math.max(1, Math.ceil(items.length / PAGE_SIZE));
  useEffect(() => {
    if (page > pages) setPage(pages);
  }, [page, pages]);
  const visible = items.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);
  const eligible = visible.filter(bulkEligible);
  const chosen = items.filter((item) => selected.has(rowKey(item)));
  const allEligibleTicked = eligible.length > 0 && eligible.every((item) => selected.has(rowKey(item)));

  useEffect(() => {
    setSelected((current) => {
      if (current.size === 0) return current;
      const live = new Set(items.map(rowKey));
      const next = new Set([...current].filter((key) => live.has(key)));
      return next.size === current.size ? current : next;
    });
  }, [items]);

  function bulkEligible(item: PendingItem) {
    return !editLocked(item);
  }

  function toggleSelected(item: PendingItem) {
    const key = rowKey(item);
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  async function approveSelected(rows: PendingItem[]) {
    const ok = await confirm({
      title: `Approve ${rows.length} record${rows.length === 1 ? "" : "s"}?`,
      body: "Each is approved as it stands.",
      note: "This can't be undone here.",
      confirmLabel: `Approve ${rows.length}`
    });
    if (!ok) return;

    setSubmitting(true);
    setError(null);
    setInfo(null);
    const failed: string[] = [];
    for (const [index, item] of rows.entries()) {
      setBulkProgress({ done: index, total: rows.length });
      try {
        await apiFetch(`/review/${item.recordType}/${item.id}/approve`, {
          method: "POST",
          body: JSON.stringify({ notes: null })
        });
      } catch {
        failed.push(rowKey(item));
      }
    }
    setBulkProgress(null);
    setSubmitting(false);
    setSelected(new Set(failed));

    const approved = rows.length - failed.length;
    if (failed.length) {
      setError(
        `${approved} approved, ${failed.length} failed (still ticked — maybe already decided).`
      );
    } else {
      setInfo(`${approved} record${approved === 1 ? "" : "s"} approved.`);
    }
    await load();
  }

  function toggleAction(item: PendingItem, action: PanelAction) {
    const key = rowKey(item);
    setNote("");
    setInfo(null);
    setActive((current) => (current && current.key === key && current.action === action ? null : { key, action }));
  }

  function editLocked(item: PendingItem) {
    return Boolean(item.needsAdminApproval || item.outOfWindow) && !viewerIsAdmin;
  }

  function offersEdit(item: PendingItem) {
    return (
      !editLocked(item) &&
      reviewEditableFields(item.recordType).length > 0 &&
      canEditOthersRecord(user, item.createdBy?.role)
    );
  }

  async function submitDecision(item: PendingItem, action: DecisionAction) {
    const notes = note.trim();
    if (action === "revise" && !notes) return;
    if (action === "reject") {
      const ok = await confirm({
        title: "Reject this record?",
        body: (
          <>
            <span className="font-medium text-ink-900">{item.label}</span> will show as rejected to the contributor.
          </>
        ),
        note: "To have it fixed, use Send for revision instead.",
        tone: "danger",
        confirmLabel: "Reject record"
      });
      if (!ok) return;
    }
    setSubmitting(true);
    try {
      await apiFetch(`/review/${item.recordType}/${item.id}/${action}`, {
        method: "POST",
        body: JSON.stringify({ notes: notes || null })
      });
      setActive(null);
      setNote("");
      setError(null);
      setInfo(null);
      await load();
    } catch (err) {
      const verb = action === "revise" ? "send this record for revision" : `${action} this record`;
      setError(err instanceof Error ? err.message : `Unable to ${verb}`);
    } finally {
      setSubmitting(false);
    }
  }

  function decisionButtons(item: PendingItem, activeAction: PanelAction | null) {
    return (
      <RowActions>
        <button
          type="button"
          className={rowAction("positive", activeAction === "approve" ? "bg-success-100" : undefined)}
          onClick={() => toggleAction(item, "approve")}
          disabled={submitting}
        >
          Approve
        </button>
        {!offersEdit(item) ? null : (
          <button
            type="button"
            className={rowAction("edit", activeAction === "edit" ? "bg-field-100" : undefined)}
            onClick={() => toggleAction(item, "edit")}
            disabled={submitting}
          >
            {activeAction === "edit" ? "Close editor" : "Update"}
          </button>
        )}
        <button
          type="button"
          className={rowAction("neutral", activeAction === "revise" ? "bg-surface-50" : undefined)}
          onClick={() => toggleAction(item, "revise")}
          disabled={submitting}
        >
          Send for revision
        </button>
        <button
          type="button"
          data-danger className={rowAction("danger", activeAction === "reject" ? "bg-red-50 dark:bg-red-950/40" : undefined)}
          onClick={() => toggleAction(item, "reject")}
          disabled={submitting}
        >
          Reject
        </button>
      </RowActions>
    );
  }

  function decisionPanel(item: PendingItem, activeAction: PanelAction) {
    if (activeAction === "edit") {
      if (!offersEdit(item)) return null;
      return (
        <ReviewEditPanel
          recordType={item.recordType}
          recordId={item.id}
          label={item.label}
          onSaved={(message) => {
            setInfo(message);
            setError(null);
          }}
          onApproved={async (message) => {
            setActive(null);
            setInfo(message);
            setError(null);
            await load();
          }}
          onClose={() => setActive(null)}
        />
      );
    }
    return (
      <>
        <label htmlFor={noteId} className="block text-xs font-semibold uppercase tracking-wide text-ink-500">
          {ACTION_COPY[activeAction].noteLabel}
        </label>
        <textarea
          id={noteId}
          aria-describedby={activeAction === "revise" ? noteHelpId : undefined}
          className="mt-1.5 w-full rounded-md border border-line-200 bg-card p-2 text-sm text-ink-900"
          rows={3}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          autoFocus
        />
        {activeAction === "revise" ? (
          <p id={noteHelpId} className="mt-1 text-xs text-ink-500">
            Required. The contributor sees it as &quot;Needs revision&quot;.
          </p>
        ) : null}
        <ActionGroup className="mt-2">
          <button
            type="button"
            className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
            disabled={submitting || (activeAction === "revise" && !note.trim())}
            onClick={() => submitDecision(item, activeAction)}
          >
            {submitting ? "Saving..." : ACTION_COPY[activeAction].confirmLabel}
          </button>
          <button
            type="button"
            className="field-button-secondary h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
            disabled={submitting}
            onClick={() => {
              setActive(null);
              setNote("");
            }}
          >
            Cancel
          </button>
        </ActionGroup>
      </>
    );
  }

  function selectBox(item: PendingItem) {
    if (!bulkEligible(item)) return null;
    return (
      <label className="-m-2 inline-flex cursor-pointer items-center justify-center p-2 pointer-coarse:min-h-11 pointer-coarse:min-w-11">
        <input
          type="checkbox"
          className="h-4 w-4 accent-purple-700 pointer-coarse:h-5 pointer-coarse:w-5"
          aria-label={`Select ${item.label}`}
          checked={selected.has(rowKey(item))}
          onChange={() => toggleSelected(item)}
        />
      </label>
    );
  }

  function outOfWindow(item: PendingItem) {
    return item.needsAdminApproval || item.outOfWindow ? (
      <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs font-semibold text-amber-800">
        Out of workshop window — needs approval
      </span>
    ) : null;
  }

  function lockNote(item: PendingItem) {
    return editLocked(item) ? (
      <p className="mt-1 max-w-md text-xs text-amber-800 dark:text-amber-100">
        Outside its workshop&apos;s dates — admins only.
      </p>
    ) : null;
  }

  function toggleAllEligible(on: boolean) {
    setSelected((current) => {
      const next = new Set(current);
      eligible.forEach((item) => (on ? next.add(rowKey(item)) : next.delete(rowKey(item))));
      return next;
    });
  }

  const countLine = queuePageNotice(items.length, queue.total, queue.more);

  return (
    <>
      <PageHeader
        title="Review"
        description={
          hasRank(user, "PROFESSOR")
            ? "Approve, update, send back or reject pending records."
            : "Approve, send back or reject pending records."
        }
        icon={<ClipboardCheck className="h-5 w-5" aria-hidden />}
      />
      {error ? (
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}
      {info ? (
        <div role="status" className="mb-4 rounded-md border border-success-600/30 bg-success-100 px-3 py-2 text-sm text-success-600">
          {info}
        </div>
      ) : null}
      <section className="panel overflow-hidden">
        {loading || loadFailed ? null : (
          <CappedListNotice className="border-b border-line-200 px-4 py-2.5" cuts={[countLine]} />
        )}
        {loading ? (
          <div className="p-4 text-sm text-ink-700">Loading...</div>
        ) : loadFailed ? (
          <div role="alert" className="flex flex-wrap items-center justify-between gap-3 p-4">
            <p className="text-sm text-red-700 dark:text-red-400">{loadFailed}</p>
            <button type="button" className="field-button-secondary" onClick={retryFirstLoad}>
              Try again
            </button>
          </div>
        ) : items.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title="No pending submissions"
              body="Records from roles below yours appear here."
            />
          </div>
        ) : (
          <>
            {chosen.length ? (
              <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line-200 bg-purple-50 px-4 py-3 dark:bg-purple-950/40">
                <span className="text-sm font-medium text-ink-900">
                  {chosen.length} record{chosen.length === 1 ? "" : "s"} selected
                  {bulkProgress ? ` · approving ${bulkProgress.done + 1} of ${bulkProgress.total}…` : ""}
                </span>
                <ActionGroup>
                  <button
                    type="button"
                    className="text-xs font-semibold text-ink-500 pointer-coarse:min-h-11 pointer-coarse:px-2"
                    onClick={() => setSelected(new Set())}
                  >
                    Clear
                  </button>
                  <button
                    type="button"
                    className="field-button h-9 min-h-0 px-3 text-xs pointer-coarse:h-11"
                    disabled={submitting}
                    onClick={() => approveSelected(chosen)}
                  >
                    <Check className="h-3.5 w-3.5" aria-hidden />
                    Approve {chosen.length}
                  </button>
                </ActionGroup>
              </div>
            ) : null}
            <ul className="divide-y divide-line-200 md:hidden">
              {eligible.length > 0 ? (
                <li className="px-4 py-2 text-xs text-ink-500">
                  <label className="inline-flex cursor-pointer items-center gap-3 pointer-coarse:min-h-11">
                    <input
                      type="checkbox"
                      className="h-4 w-4 accent-purple-700 pointer-coarse:h-5 pointer-coarse:w-5"
                      checked={allEligibleTicked}
                      onChange={(event) => toggleAllEligible(event.target.checked)}
                    />
                    Select every record on this page
                  </label>
                </li>
              ) : null}
              {visible.map((item) => {
                const key = rowKey(item);
                const activeAction = active?.key === key ? active.action : null;
                const panel = activeAction ? decisionPanel(item, activeAction) : null;
                const viewHref = reviewRecordViewHref(item.recordType, item.id);
                return (
                  <li
                    key={key}
                    className={selected.has(key) ? "bg-purple-50/60 dark:bg-purple-950/30" : undefined}
                  >
                    <OpenableCard href={viewHref} className="flex items-start gap-3 px-4 py-3">
                      <div className="pt-0.5">{selectBox(item)}</div>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-start justify-between gap-2">
                          <p className="min-w-0 font-medium text-ink-900">
                            {viewHref ? <OpenableRowLink href={viewHref}>{item.label}</OpenableRowLink> : item.label}
                          </p>
                          <StatusBadge status={item.status ?? "PENDING"} />
                        </div>
                        {outOfWindow(item)}
                        <p className="mt-1 text-xs leading-5 text-ink-500">
                          <span className="capitalize">{item.recordType}</span>
                          {" · "}
                          {item.createdBy?.name ?? "Unknown"}
                          {item.createdBy?.role ? ` (${roleLabel(item.createdBy.role)})` : ""}
                          {item.place ? ` · ${item.place}` : ""}
                          {" · "}
                          {formatDateTime(item.createdAt)}
                        </p>
                        {lockNote(item)}
                        <div className="mt-3 [&>div]:justify-start">{decisionButtons(item, activeAction)}</div>
                      </div>
                    </OpenableCard>
                    {panel ? <div className="mx-4 mb-3 rounded-md bg-surface-50 p-3">{panel}</div> : null}
                  </li>
                );
              })}
            </ul>
            <div className="hidden overflow-x-auto md:block">
              <table className="w-full min-w-[920px] text-left text-sm">
                <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                  <tr>
                    <th scope="col" className="w-10 px-4 py-3">
                      <input
                        type="checkbox"
                        className="h-4 w-4 accent-purple-700"
                        aria-label="Select every record on this page"
                        checked={allEligibleTicked}
                        disabled={eligible.length === 0}
                        onChange={(event) => toggleAllEligible(event.target.checked)}
                      />
                    </th>
                    <ResizableTh>Record</ResizableTh>
                    <ResizableTh>Type</ResizableTh>
                    <ResizableTh>Creator</ResizableTh>
                    <ResizableTh>Place or link</ResizableTh>
                    <ResizableTh>Status</ResizableTh>
                    <ResizableTh>Submitted</ResizableTh>
                    <ResizableTh className="text-right">Decision</ResizableTh>
                  </tr>
                </thead>
                <tbody className="divide-y divide-line-200">
                  {visible.map((item) => {
                    const key = rowKey(item);
                    const activeAction = active?.key === key ? active.action : null;
                    const panel = activeAction ? decisionPanel(item, activeAction) : null;
                    const viewHref = reviewRecordViewHref(item.recordType, item.id);
                    const rowTone = selected.has(key) ? "bg-purple-50/60 dark:bg-purple-950/30" : undefined;
                    const cells = (
                      <>
                          <td className="px-4 py-3 align-top">{selectBox(item)}</td>
                          <td className="px-4 py-3">
                            <div className="font-medium text-ink-900">
                              {viewHref ? <OpenableRowLink href={viewHref}>{item.label}</OpenableRowLink> : item.label}
                              {outOfWindow(item)}
                            </div>
                            {lockNote(item)}
                          </td>
                          <td className="px-4 py-3 capitalize text-ink-700">{item.recordType}</td>
                          <td className="px-4 py-3">
                            <div className="flex flex-wrap items-center gap-2">
                              <span className="text-ink-900">{item.createdBy?.name ?? "Unknown"}</span>
                              {item.createdBy?.role ? (
                                <span className="rounded-full border border-line-200 bg-surface-50 px-2 py-0.5 text-xs font-medium text-ink-500">
                                  {roleLabel(item.createdBy.role)}
                                </span>
                              ) : null}
                            </div>
                          </td>
                          <td className="px-4 py-3 text-ink-700">{item.place ?? "-"}</td>
                          <td className="px-4 py-3">
                            <StatusBadge status={item.status ?? "PENDING"} />
                          </td>
                          <td className="px-4 py-3 text-ink-700">{formatDateTime(item.createdAt)}</td>
                          <td className="px-4 py-3 text-right">{decisionButtons(item, activeAction)}</td>
                      </>
                    );
                    return (
                      <Fragment key={key}>
                        {viewHref ? (
                          <OpenableRow href={viewHref} className={rowTone ?? ""}>
                            {cells}
                          </OpenableRow>
                        ) : (
                          <tr className={rowTone}>{cells}</tr>
                        )}
                        {panel ? (
                          <tr className="bg-surface-50">
                            <td className="px-4 py-3" colSpan={8}>
                              {panel}
                            </td>
                          </tr>
                        ) : null}
                      </Fragment>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </>
        )}
        {!loading && !loadFailed && items.length > 0 ? (
          <Pagination page={page} pages={pages} total={queue.total} onPage={setPage} />
        ) : null}
        {!loading && !loadFailed && queue.more ? (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-line-200 px-4 py-3">
            <p className="text-xs text-ink-500">{countLine}</p>
            <button type="button" className="field-button-secondary" disabled={loadingOlder} onClick={loadOlder}>
              {loadingOlder ? "Loading…" : "Load older"}
            </button>
          </div>
        ) : null}
      </section>
    </>
  );
}
