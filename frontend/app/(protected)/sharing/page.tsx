"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, Check, Share2, X } from "lucide-react";

import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";
import { EmptyState } from "@/components/EmptyState";
import { Field, Select, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RowActions, rowAction } from "@/components/RowActions";
import { useAuth } from "@/components/AuthProvider";
import { SearchableMultiSelect, type SelectOption } from "@/components/ui/SearchableSelect";
import type { ProcessRecord } from "@/components/forms/ProcessForm";
import { apiFetch, listResource } from "@/lib/api";
import { runPerPerson, type BatchOutcome, type BatchTarget } from "@/lib/sharingBatch";
import {
  TIER_RANK,
  classifyChange,
  nameList,
  plural,
  scopeKey,
  scopeRemoval,
  scopeWords,
  splitScopeKey,
  standingsBy,
  type Scope,
  type Standing
} from "@/lib/sharingScope";
import type {
  Artisan,
  Craft,
  DataAccessGrant,
  DataAccessTier,
  MediaFile,
  MyGrants,
  PageResult,
  ProductDocumentation,
  QuestionnaireInterview,
  TierInfo,
  ToolDocumentation,
  User,
  Workshop
} from "@/lib/types";
import { exportFilename } from "@/lib/naming";

const TIER_LABEL: Record<DataAccessTier, string> = {
  DOWNLOAD: "Download (minimum)",
  COMMENT: "Comment (medium)",
  EDIT: "Edit (maximum)"
};

const PICKER_PAGE_SIZE = 100;

type OwnRecord = { recordType: string; recordId: string; name: string };

type RecordGroup = {
  recordType: string;
  heading: string;
  noun: string;
  reach: string | null;
  records: OwnRecord[];
  total: number | null;
  fetched: number;
  failed: string | null;
};

const RECORD_REACH = {
  full: null,
  craft:
    "Comment and edit. Not downloadable on its own.",
  process:
    "Comment and edit. Downloads with its product.",
  media:
    "Comment only. Downloads with its record."
} as const;

const TIER_CONSEQUENCE: Record<DataAccessTier, string> = {
  DOWNLOAD: "download",
  COMMENT: "download and comment on",
  EDIT: "download, comment on and edit"
};

const BULK_CONFIRM_AT = 2;

const REMOVALS_IN_PROSE = 4;

const STATUS_STYLE: Record<string, string> = {
  PENDING: "bg-amber-100 text-amber-800",
  GRANTED: "bg-emerald-100 text-emerald-800",
  DENIED: "bg-red-100 text-red-700",
  REVOKED: "bg-line-200 text-ink-700"
};

function StatusPill({ status }: { status: string }) {
  return <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_STYLE[status] ?? "bg-line-200"}`}>{status}</span>;
}

function tierAtLeast(tier: DataAccessTier, min: DataAccessTier) {
  return TIER_RANK[tier] >= TIER_RANK[min];
}

type Change = ReturnType<typeof classifyChange>;

function grantSuffix(standing: Standing | undefined, change: Change) {
  if (!standing) return "";
  switch (standing.status) {
    case "GRANTED":
      return change === "same"
        ? ` — ${standing.tier}, ${scopeWords(standing)} · no change`
        : ` — has ${standing.tier}, ${scopeWords(standing)}`;
    case "PENDING":
      return ` — asked for ${standing.tier}`;
    case "DENIED":
      return ` — you denied ${standing.tier}`;
    default:
      return ` — ${standing.tier} revoked`;
  }
}

function requestSuffix(standing: Standing | undefined) {
  if (!standing) return "";
  switch (standing.status) {
    case "GRANTED":
      return ` — you already have ${standing.tier}`;
    case "PENDING":
      return " — request pending";
    case "DENIED":
      return " — previously denied";
    default:
      return " — your access was revoked";
  }
}

type Attempt =
  | { kind: "grant"; tier: DataAccessTier; allData: boolean; scopeItems: Array<{ recordType: string; recordId: string }> }
  | { kind: "request"; tier: DataAccessTier; requestNote?: string };

type Ledger = { attempt: Attempt; intent: string; succeeded: BatchOutcome[]; failed: BatchOutcome[] };

function BatchReport({ ledger, busy, onRetry, onDismiss }: { ledger: Ledger; busy: boolean; onRetry: () => void; onDismiss: () => void }) {
  const { succeeded, failed } = ledger;
  const noun = ledger.attempt.kind === "grant" ? "granted" : "sent";
  const total = succeeded.length + failed.length;

  return (
    <div className="mt-4 overflow-hidden rounded-md border border-amber-500/40">
      <div className="flex items-start gap-2 bg-amber-100 px-3 py-2 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold">
            {succeeded.length} of {total} {noun}.
          </p>
          <p className="text-xs">{ledger.intent}</p>
        </div>
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss this report"
          className="-mr-1 shrink-0 rounded p-1 text-amber-800 transition hover:bg-amber-500/20 dark:text-amber-100"
        >
          <X className="h-4 w-4" aria-hidden />
        </button>
      </div>
      <div className="bg-card p-3 text-sm">
        {succeeded.length ? (
          <>
            <p className="field-label">{ledger.attempt.kind === "grant" ? "Granted" : "Request sent"}</p>
            <ul className="mt-1 grid gap-1">
              {succeeded.map((row) => (
                <li key={row.id} className="flex items-start gap-2 text-ink-700">
                  <Check className="mt-0.5 h-4 w-4 shrink-0 text-emerald-700 dark:text-emerald-400" aria-hidden />
                  <span className="min-w-0">{row.label}</span>
                </li>
              ))}
            </ul>
          </>
        ) : null}
        <p className={`field-label ${succeeded.length ? "mt-3" : ""}`}>
          {ledger.attempt.kind === "grant" ? "Not granted" : "Not sent"}
        </p>
        <ul className="mt-1 grid gap-1">
          {failed.map((row) => (
            <li key={row.id} className="flex items-start gap-2 text-ink-700">
              <X className="mt-0.5 h-4 w-4 shrink-0 text-error-600 dark:text-red-400" aria-hidden />
              <span className="min-w-0">
                <span className="font-medium text-ink-900">{row.label}</span> — {row.error}
                {row.status ? <span className="text-ink-500"> (HTTP {row.status})</span> : null}
              </span>
            </li>
          ))}
        </ul>
        <div className="mt-3 flex flex-wrap items-center gap-3">
          <button className="field-button" disabled={busy} onClick={onRetry}>
            Retry {failed.length === 1 ? "the one that failed" : `the ${failed.length} that failed`}
          </button>
          {succeeded.length ? (
            <p className="min-w-0 flex-1 text-xs text-ink-500">
              The {succeeded.length} above {succeeded.length === 1 ? "keeps its" : "keep their"} access — retrying
              re-sends only the {failed.length} that failed, and cannot duplicate anything.
            </p>
          ) : null}
        </div>
      </div>
    </div>
  );
}

export default function SharingPage() {
  const confirm = useConfirm();
  const { user: currentUser } = useAuth();
  const [grants, setGrants] = useState<MyGrants | null>(null);
  const [tiers, setTiers] = useState<TierInfo[]>([]);
  const [users, setUsers] = useState<User[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState<{ kind: Attempt["kind"]; done: number; total: number } | null>(null);
  const [ledger, setLedger] = useState<Ledger | null>(null);

  const [reqOwnerIds, setReqOwnerIds] = useState<string[]>([]);
  const [reqOwnerText, setReqOwnerText] = useState("");
  const [reqTier, setReqTier] = useState<DataAccessTier>("DOWNLOAD");
  const [reqNote, setReqNote] = useState("");

  const [grantGranteeIds, setGrantGranteeIds] = useState<string[]>([]);
  const [grantTier, setGrantTier] = useState<DataAccessTier>("DOWNLOAD");
  const [grantScopeAll, setGrantScopeAll] = useState(true);
  const [myRecords, setMyRecords] = useState<RecordGroup[] | null>(null);
  const [loadingRecords, setLoadingRecords] = useState(false);
  const [selectedKeys, setSelectedKeys] = useState<Set<string>>(new Set());

  async function loadMyRecords() {
    if (myRecords || loadingRecords || !currentUser?.id) return;
    setLoadingRecords(true);
    const mine = currentUser.id;
    const owned = { pageSize: PICKER_PAGE_SIZE, createdBy: mine };
    const [a, p, t, w, q, c, pr, m] = await Promise.allSettled([
      listResource<Artisan>("/artisans", owned),
      listResource<ProductDocumentation>("/products", owned),
      listResource<ToolDocumentation>("/tools", owned),
      listResource<Workshop>("/workshops", owned),
      listResource<QuestionnaireInterview>("/questionnaire/interviews", owned),
      listResource<Craft & { createdById?: string | null }>("/crafts", owned),
      listResource<ProcessRecord>("/processes", owned),
      listResource<MediaFile & { uploadedById?: string | null }>("/media", {
        pageSize: PICKER_PAGE_SIZE,
        uploadedBy: mine
      })
    ]);

    function group<T extends { id: string }>(
      recordType: string,
      heading: string,
      noun: string,
      reach: string | null,
      result: PromiseSettledResult<PageResult<T>>,
      isMine: (row: T) => boolean,
      name: (row: T) => string
    ): RecordGroup {
      if (result.status === "rejected") {
        const reason = result.reason;
        return {
          recordType,
          heading,
          noun,
          reach,
          records: [],
          total: null,
          fetched: 0,
          failed: reason instanceof Error ? reason.message : "could not be loaded"
        };
      }
      const page = result.value;
      const items = Array.isArray(page?.items) ? page.items : [];
      return {
        recordType,
        heading,
        noun,
        reach,
        records: items.filter(isMine).map((row) => ({ recordType, recordId: row.id, name: name(row) || row.id })),
        total: typeof page?.total === "number" ? page.total : null,
        fetched: items.length,
        failed: null
      };
    }

    setMyRecords([
      group("artisan", "Artisans", "Artisan", RECORD_REACH.full, a, (x) => x.createdById === mine, (x) => x.name),
      group("product", "Products", "Product", RECORD_REACH.full, p, (x) => x.createdById === mine, (x) => x.productName),
      group("tool", "Tools", "Tool", RECORD_REACH.full, t, (x) => x.createdById === mine, (x) => x.toolkitName),
      group("workshop", "Workshops", "Workshop", RECORD_REACH.full, w, (x) => x.createdById === mine, (x) => x.title),
      group("questionnaire", "Interviews", "Interview", RECORD_REACH.full, q, (x) => x.createdById === mine, (x) => x.title),
      group("craft", "Crafts", "Craft", RECORD_REACH.craft, c, (x) => x.createdById === mine, (x) => x.name),
      group("process", "Processes", "Process", RECORD_REACH.process, pr, (x) => x.createdById === mine, (x) => x.name),
      group(
        "media",
        "Files",
        "File",
        RECORD_REACH.media,
        m,
        (x) => (x.uploadedById ?? x.uploadedBy?.id) === mine,
        (x) => x.caption?.trim() || x.originalFilename
      )
    ]);
    setLoadingRecords(false);
  }

  function toggleRecord(key: string) {
    setSelectedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  const load = useCallback(async () => {
    try {
      const [g, t, u] = await Promise.all([
        apiFetch<MyGrants>("/data-access/grants"),
        apiFetch<TierInfo[]>("/data-access/tiers"),
        apiFetch<User[]>("/users/directory").catch(() => [] as User[])
      ]);
      setGrants(g);
      setTiers(t);
      setUsers((u ?? []).filter((x) => x.id !== currentUser?.id));
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load sharing data");
    }
  }, [currentUser?.id]);

  useEffect(() => {
    load();
  }, [load]);

  const canPickUsers = users.length > 0;

  const ownerNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, `${u.name} (${u.email})`));
    return map;
  }, [users]);

  const plainNameById = useMemo(() => {
    const map = new Map<string, string>();
    users.forEach((u) => map.set(u.id, u.name));
    return map;
  }, [users]);

  const incoming = useMemo(() => grants?.incoming ?? [], [grants]);
  const outgoing = useMemo(() => grants?.outgoing ?? [], [grants]);

  const granteeStandings = useMemo(() => standingsBy(incoming, "granteeId"), [incoming]);
  const ownerStandings = useMemo(() => standingsBy(outgoing, "ownerId"), [outgoing]);

  const nextScope: Scope = useMemo(
    () => ({ allData: grantScopeAll, keys: selectedKeys }),
    [grantScopeAll, selectedKeys]
  );

  const grantOptions: SelectOption[] = useMemo(
    () =>
      users.map((u) => {
        const standing = granteeStandings.get(u.id);
        return {
          value: u.id,
          label: `${u.name} · ${u.email}${grantSuffix(standing, classifyChange(standing, { ...nextScope, tier: grantTier }))}`
        };
      }),
    [users, granteeStandings, nextScope, grantTier]
  );

  const requestOptions: SelectOption[] = useMemo(
    () =>
      users.map((u) => {
        const standing = ownerStandings.get(u.id);
        return {
          value: u.id,
          label: `${u.name} · ${u.email}${requestSuffix(standing)}`,
          disabled: standing?.status === "GRANTED"
        };
      }),
    [users, ownerStandings]
  );

  const recordNameByKey = useMemo(() => {
    const map = new Map<string, string>();
    (myRecords ?? []).forEach((group) =>
      group.records.forEach((record) =>
        map.set(scopeKey(record.recordType, record.recordId), `${group.noun} · ${record.name}`)
      )
    );
    return map;
  }, [myRecords]);

  const recordShortfalls = useMemo(() => {
    const groups = myRecords ?? [];
    return {
      capped: groups.filter((group) => group.total !== null && group.total > group.fetched),
      failed: groups.filter((group) => group.failed)
    };
  }, [myRecords]);

  const reductions = useMemo(
    () =>
      grantGranteeIds
        .map((id) => ({ id, standing: granteeStandings.get(id) }))
        .filter(
          (entry): entry is { id: string; standing: Standing } =>
            Boolean(entry.standing) && classifyChange(entry.standing, { ...nextScope, tier: grantTier }) === "reduce"
        )
        .map(({ id, standing }) => {
          const removal = scopeRemoval(standing, nextScope);
          const removedKeys = removal.kind === "records" ? removal.keys : [];
          const named = removedKeys.map((key) => recordNameByKey.get(key)).filter((label): label is string => Boolean(label));
          return {
            id,
            name: plainNameById.get(id) ?? id,
            held: `${standing.tier}, ${scopeWords(standing)}`,
            tierFrom: TIER_RANK[grantTier] < TIER_RANK[standing.tier] ? standing.tier : null,
            losesAllData: removal.kind === "allData",
            named,
            unnamed: removedKeys.length - named.length
          };
        }),
    [grantGranteeIds, granteeStandings, nextScope, grantTier, plainNameById, recordNameByKey]
  );

  function removalSentence(entry: (typeof reductions)[number]): string {
    const parts: string[] = [];
    if (entry.tierFrom) parts.push(`drops from ${entry.tierFrom} to ${grantTier}`);
    if (entry.losesAllData) {
      parts.push(`loses everything except the ${plural(selectedKeys.size, "record")} you ticked`);
    } else if (entry.named.length || entry.unnamed) {
      const list = nameList(entry.named, REMOVALS_IN_PROSE);
      const tail = entry.unnamed
        ? `${entry.named.length ? "; plus " : ""}${plural(entry.unnamed, "record")} this page cannot list`
        : "";
      parts.push(`loses ${list}${tail}`);
    }
    if (!parts.length) parts.push("has their grant replaced");
    return `${entry.name} (has ${entry.held}) ${parts.join(", and ")}.`;
  }

  const grantScopePhrase = grantScopeAll ? "all your data" : `${plural(selectedKeys.size, "selected record")}`;

  const grantSentence =
    grantGranteeIds.length === 0
      ? "Choose one or more colleagues, then press Grant."
      : `Grant ${grantTier} on ${grantScopePhrase} to ${
          grantGranteeIds.length === 1
            ? plainNameById.get(grantGranteeIds[0]) ?? "1 colleague"
            : plural(grantGranteeIds.length, "colleague")
        }.`;

  const requestCount = canPickUsers ? reqOwnerIds.length : reqOwnerText.trim() ? 1 : 0;
  const requestSentence =
    requestCount === 0
      ? "Choose one or more researchers, then press Request."
      : `Request ${reqTier} access to all data from ${
          requestCount === 1
            ? (canPickUsers ? plainNameById.get(reqOwnerIds[0]) : null) ?? "1 researcher"
            : plural(requestCount, "researcher")
        }.`;

  async function act<T>(fn: () => Promise<T>, ok: string) {
    setBusy(true);
    setError(null);
    setMessage(null);
    try {
      await fn();
      setMessage(ok);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Action failed");
    } finally {
      setBusy(false);
    }
  }

  async function runPeople(attempt: Attempt, intent: string, targets: BatchTarget[]) {
    setBusy(true);
    setError(null);
    setMessage(null);
    setLedger(null);
    setProgress({ kind: attempt.kind, done: 0, total: targets.length });

    const result = await runPerPerson(
      targets,
      (target) =>
        attempt.kind === "grant"
          ? apiFetch("/data-access/grants", {
              method: "POST",
              body: JSON.stringify({
                granteeId: target.id,
                tier: attempt.tier,
                allData: attempt.allData,
                scopeItems: attempt.scopeItems
              })
            })
          : apiFetch("/data-access/requests", {
              method: "POST",
              body: JSON.stringify({ ownerId: target.id, tier: attempt.tier, allData: true, requestNote: attempt.requestNote })
            }),
      (done, total) => setProgress({ kind: attempt.kind, done, total })
    );

    setProgress(null);
    await load();
    setBusy(false);

    const failedIds = result.failed.map((row) => row.id);
    if (attempt.kind === "grant") {
      setGrantGranteeIds(failedIds);
      if (!failedIds.length) {
        setSelectedKeys(new Set());
        setGrantScopeAll(true);
      }
    } else {
      setReqOwnerIds(failedIds);
      if (!failedIds.length) {
        setReqNote("");
        setReqOwnerText("");
      }
    }

    if (!result.failed.length) {
      setMessage(
        attempt.kind === "grant"
          ? `Access granted to ${plural(result.succeeded.length, "colleague")}.`
          : `Sent ${plural(result.succeeded.length, "request")}.`
      );
      return;
    }
    setLedger({ attempt, intent, succeeded: result.succeeded, failed: result.failed });
  }

  function targetsFor(ids: string[]): BatchTarget[] {
    return ids.map((id) => ({ id, label: ownerNameById.get(id) ?? id }));
  }

  async function submitRequest() {
    const ids = canPickUsers ? reqOwnerIds : reqOwnerText.trim() ? [reqOwnerText.trim()] : [];
    if (!ids.length) {
      setError("Choose at least one researcher.");
      return;
    }
    const names = ids.map((id) => plainNameById.get(id) ?? id);
    if (ids.length >= BULK_CONFIRM_AT) {
      const ok = await confirm({
        title: `Send ${plural(ids.length, "access request")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will each be asked for{" "}
            <span className="font-medium text-ink-900">{reqTier}</span> access to all of their data.
          </>
        ),
        note: "Nothing is shared until they approve.",
        confirmLabel: `Send ${ids.length} requests`
      });
      if (!ok) return;
    }
    await runPeople({ kind: "request", tier: reqTier, requestNote: reqNote.trim() || undefined }, requestSentence, targetsFor(ids));
  }

  async function submitGrant() {
    if (!grantGranteeIds.length) {
      setError("Choose at least one colleague.");
      return;
    }
    const scopeItems = grantScopeAll ? [] : Array.from(selectedKeys).map(splitScopeKey);
    if (!grantScopeAll && scopeItems.length === 0) {
      setError("Pick a record, or All my data.");
      return;
    }
    const names = grantGranteeIds.map((id) => plainNameById.get(id) ?? id);
    if (grantGranteeIds.length >= BULK_CONFIRM_AT || reductions.length > 0) {
      const severe = reductions.length > 0 || (grantTier === "EDIT" && grantScopeAll);
      const ok = await confirm({
        title: `Grant ${grantTier} to ${plural(grantGranteeIds.length, "colleague")}?`,
        body: (
          <>
            <span className="font-medium text-ink-900">{nameList(names)}</span> will be able to{" "}
            {TIER_CONSEQUENCE[grantTier]} <span className="font-medium text-ink-900">{grantScopePhrase}</span> straight
            away.
          </>
        ),
        note: (
          <>
            {reductions.length ? (
              <span className="block font-medium text-ink-900">
                This takes access away from {reductions.length === 1 ? "someone" : `${reductions.length} of them`}. One
                tier and one scope apply to everyone chosen, so an existing grant is replaced, not added to:
                <span className="mt-1 block font-normal">
                  {reductions.map((r) => (
                    <span key={r.id} className="block">
                      {removalSentence(r)}
                    </span>
                  ))}
                </span>
              </span>
            ) : null}
            <span className="block">You can revoke access any time.</span>
          </>
        ),
        tone: severe ? "danger" : "warning",
        confirmLabel: `Grant to ${grantGranteeIds.length}`
      });
      if (!ok) return;
    }
    await runPeople({ kind: "grant", tier: grantTier, allData: grantScopeAll, scopeItems }, grantSentence, targetsFor(grantGranteeIds));
  }

  function retryLedger() {
    if (!ledger) return;
    runPeople(ledger.attempt, ledger.intent, ledger.failed.map((row) => ({ id: row.id, label: row.label })));
  }

  async function decide(grant: DataAccessGrant, status: "GRANTED" | "DENIED", tier?: DataAccessTier) {
    const who = grant.grantee?.name ?? grant.granteeId;
    if (status === "DENIED") {
      const ok = await confirm({
        title: "Deny this request?",
        body: (
          <>
            <span className="font-medium text-ink-900">{who}</span> won&apos;t get access.
          </>
        ),
        note: "They can ask again.",
        tone: "warning",
        confirmLabel: "Deny request"
      });
      if (!ok) return;
    }
    await act(
      () =>
        apiFetch(`/data-access/grants/${grant.id}/decide`, {
          method: "POST",
          body: JSON.stringify({ status, tier: tier ?? grant.tier })
        }),
      status === "GRANTED" ? "Access granted." : "Request denied."
    );
  }

  async function changeTier(grant: DataAccessGrant, tier: DataAccessTier) {
    await act(() => apiFetch(`/data-access/grants/${grant.id}`, { method: "PATCH", body: JSON.stringify({ tier }) }), "Tier updated.");
  }

  async function revoke(grant: DataAccessGrant) {
    const who = grant.grantee?.name ?? grant.granteeId;
    const ok = await confirm({
      title: "Revoke this access?",
      body: (
        <>
          <span className="font-medium text-ink-900">{who}</span> loses access immediately.
        </>
      ),
      note: "Comments and edits they already made are kept.",
      tone: "danger",
      confirmLabel: "Revoke access"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}/revoke`, { method: "POST" }), "Access revoked.");
  }

  async function remove(grant: DataAccessGrant) {
    const ok = await confirm({
      ...deleteConfirm(
        "Remove this sharing entry?",
        "The entry and its history are deleted.",
        "This can't be undone."
      ),
      confirmLabel: "Remove entry"
    });
    if (!ok) return;
    await act(() => apiFetch(`/data-access/grants/${grant.id}`, { method: "DELETE" }), "Removed.");
  }

  async function downloadOwnerData(ownerId: string, ownerLabel: string) {
    let capped = false;
    await act(async () => {
      const manifest = await apiFetch<{
        files: Array<{ path: string; url?: string | null; content?: string | null }>;
        totalFiles: number;
        totalMedia: number;
        truncated?: boolean;
      }>(`/export/dataset?ownerId=${encodeURIComponent(ownerId)}`);
      capped = Boolean(manifest.truncated);
      const { default: JSZip } = await import("jszip");
      const zip = new JSZip();
      const failed: string[] = [];
      let done = 0;
      for (const file of manifest.files) {
        done += 1;
        setMessage(`Preparing download… ${done}/${manifest.files.length}`);
        if (file.content != null) {
          zip.file(file.path, file.content);
          continue;
        }
        if (!file.url) {
          failed.push(file.path);
          continue;
        }
        try {
          const response = await fetch(file.url);
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          zip.file(file.path, await response.blob());
        } catch {
          failed.push(file.path);
        }
      }
      if (failed.length) {
        zip.file(
          "_failed-downloads.txt",
          `These files could not be fetched and are not in the archive:\n\n${failed.join("\n")}`
        );
      }
      const blob = await zip.generateAsync({ type: "blob" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = exportFilename("Shared-Data", "Export", "zip", { subject: ownerLabel });
      a.click();
      URL.revokeObjectURL(url);
    }, "Download ready.");
    if (capped) {
      setMessage(
        `Download ready, but incomplete — too large. Ask an admin for ${ownerLabel}'s full data.`
      );
    }
  }

  function progressLine(kind: Attempt["kind"]) {
    if (!progress || progress.kind !== kind) return null;
    return `Working… ${progress.done} of ${progress.total} sent.`;
  }

  return (
    <>
      <PageHeader
        title="Sharing"
        description="Request and grant access to data."
        icon={<Share2 className="h-5 w-5" aria-hidden />}
      />

      {error ? <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {message ? <div className="mb-4 rounded-md border border-emerald-200 bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{message}</div> : null}

      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Access tiers</h2>
        <ul className="mt-2 grid gap-2 md:grid-cols-3">
          {tiers.map((t) => (
            <li key={t.tier} className="rounded-md border border-line-200 bg-field-50 p-3 text-sm">
              <div className="font-semibold text-ink">{TIER_LABEL[t.tier]}</div>
              <div className="mt-1 text-ink-muted">{t.description}</div>
            </li>
          ))}
        </ul>
      </section>

      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Request access to researchers&apos; data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_2fr_auto] md:items-end">
          <Field label="Researchers">
            {canPickUsers ? (
              <SearchableMultiSelect
                values={reqOwnerIds}
                onChange={setReqOwnerIds}
                options={requestOptions}
                searchable
                placeholder="Select…"
                ariaLabel="Researchers to request access from"
                disabled={busy}
                confirmOnSelect={false}
              />
            ) : (
              <TextInput value={reqOwnerText} onChange={(e) => setReqOwnerText(e.target.value)} placeholder="Researcher user id" />
            )}
          </Field>
          <Field label="Tier">
            <Select value={reqTier} onChange={(e) => setReqTier(e.target.value as DataAccessTier)}>
              <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
              <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
              <option value="EDIT">{TIER_LABEL.EDIT}</option>
            </Select>
          </Field>
          <Field label="Note (optional)">
            <TextInput value={reqNote} onChange={(e) => setReqNote(e.target.value)} placeholder="Why you need access" />
          </Field>
          <button className="field-button" disabled={busy} onClick={submitRequest}>
            Request
          </button>
        </div>
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("request") ?? requestSentence}
        </p>
        <p className="mt-2 text-xs text-ink-muted">
          One tier for everyone chosen. The owner can narrow it when approving.
        </p>
        {ledger?.attempt.kind === "request" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      <section className="panel mb-5 p-4">
        <h2 className="font-display font-bold text-lg text-ink">Grant access to your data</h2>
        <div className="mt-3 grid gap-3 md:grid-cols-[2fr_1.4fr_auto] md:items-end">
          <Field label="Colleagues">
            <SearchableMultiSelect
              values={grantGranteeIds}
              onChange={setGrantGranteeIds}
              options={grantOptions}
              searchable
              placeholder="Select…"
              ariaLabel="Colleagues to grant access to"
              disabled={busy}
              confirmOnSelect={false}
            />
          </Field>
          <Field label="Tier">
            <Select value={grantTier} onChange={(e) => setGrantTier(e.target.value as DataAccessTier)}>
              <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
              <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
              <option value="EDIT">{TIER_LABEL.EDIT}</option>
            </Select>
          </Field>
          <button className="field-button" disabled={busy} onClick={submitGrant}>
            Grant
          </button>
        </div>
        <p className="mt-3 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm font-medium text-ink-900">
          {progressLine("grant") ?? grantSentence}
        </p>
        {reductions.length ? (
          <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
            <span>
              This would take access away from{" "}
              {reductions.length === 1 ? "one of them" : `${reductions.length} of them`}. A grant replaces an existing one
              rather than adding to it:
              <span className="mt-1 block">
                {reductions.map((r) => (
                  <span key={r.id} className="block">
                    {removalSentence(r)}
                  </span>
                ))}
              </span>
            </span>
          </p>
        ) : null}
        <div className="mt-3 flex flex-wrap gap-4 text-sm">
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="grantScope"
              checked={grantScopeAll}
              onChange={() => setGrantScopeAll(true)}
            />
            All my data
          </label>
          <label className="flex items-center gap-2">
            <input
              type="radio"
              name="grantScope"
              checked={!grantScopeAll}
              onChange={() => {
                setGrantScopeAll(false);
                loadMyRecords();
              }}
            />
            Only selected records
          </label>
        </div>
        {!grantScopeAll ? (
          <>
            <div className="mt-2 max-h-64 overflow-y-auto rounded-md border border-line-200 bg-field-50 p-2">
              {loadingRecords || !myRecords ? (
                <p className="px-2 py-1 text-sm text-ink-muted">Loading your records…</p>
              ) : myRecords.every((group) => !group.records.length && !group.failed) ? (
                <p className="px-2 py-1 text-sm text-ink-muted">You have no records to share.</p>
              ) : (
                myRecords.map((group) => {
                  if (!group.records.length && !group.failed) return null;
                  const capped = group.total !== null && group.total > group.fetched;
                  return (
                    <div key={group.recordType} className="mb-2 last:mb-0">
                      <p className="px-2 pb-1 pt-1 text-xs font-semibold uppercase tracking-wide text-ink-500">
                        {group.heading}
                        {group.failed
                          ? " · list unavailable"
                          : capped
                            ? ` · ${group.records.length} shown, ${group.total} in all`
                            : ` · ${group.records.length}`}
                      </p>
                      {group.failed ? (
                        <p className="px-2 pb-1 text-xs text-amber-800 dark:text-amber-100">
                          {group.failed} — saving here removes these from a colleague&apos;s grant.
                        </p>
                      ) : null}
                      {group.reach ? <p className="px-2 pb-1 text-xs text-ink-muted">{group.reach}</p> : null}
                      {group.records.map((record) => {
                        const key = scopeKey(record.recordType, record.recordId);
                        return (
                          <label key={key} className="flex items-center gap-2 rounded px-2 py-1 hover:bg-field-100">
                            <input type="checkbox" checked={selectedKeys.has(key)} onChange={() => toggleRecord(key)} />
                            <span className="min-w-0 flex-1 truncate text-sm text-ink">{record.name}</span>
                          </label>
                        );
                      })}
                    </div>
                  );
                })
              )}
            </div>
            {recordShortfalls.capped.length || recordShortfalls.failed.length ? (
              <p className="mt-2 flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800 dark:bg-amber-500/15 dark:text-amber-100">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
                <span>
                  {recordShortfalls.capped.length ? (
                    <span className="block">
                      Showing the first {PICKER_PAGE_SIZE} of each type. Not shown:{" "}
                      {recordShortfalls.capped
                        .map((group) => `${(group.total ?? 0) - group.fetched} more ${group.heading.toLowerCase()}`)
                        .join(", ")}
                      .
                    </span>
                  ) : null}
                  {recordShortfalls.failed.length ? (
                    <span className="block">
                      {nameList(recordShortfalls.failed.map((group) => group.heading))} could not be listed at all.
                    </span>
                  ) : null}
                  <span className="block font-medium">
                    Saving replaces the whole selection. Use{" "}
                    <span className="font-semibold">All my data</span> if records are missing.
                  </span>
                </span>
              </p>
            ) : null}
          </>
        ) : null}
        <p className="mt-2 text-xs text-ink-muted">
          Granted immediately, at one tier and scope for everyone chosen.
        </p>
        {ledger?.attempt.kind === "grant" ? (
          <BatchReport ledger={ledger} busy={busy} onRetry={retryLedger} onDismiss={() => setLedger(null)} />
        ) : null}
      </section>

      <section className="panel mb-5 overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Access to your data</h2>
          <p className="text-sm text-ink-muted">Who has or wants your data.</p>
        </div>
        {incoming.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No requests yet" />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {incoming.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-ink">{g.grantee?.name ?? ownerNameById.get(g.granteeId) ?? g.granteeId}</div>
                  <div className="text-xs text-ink-muted">
                    {g.grantee?.email} · {g.allData ? "All data" : `${g.scopeItems?.length ?? 0} records`} {g.requestNote ? `· “${g.requestNote}”` : ""}
                  </div>
                </div>
                <StatusPill status={g.status} />
                <Select className="max-w-44" value={g.tier} onChange={(e) => changeTier(g, e.target.value as DataAccessTier)} disabled={busy || g.status !== "GRANTED"}>
                  <option value="DOWNLOAD">{TIER_LABEL.DOWNLOAD}</option>
                  <option value="COMMENT">{TIER_LABEL.COMMENT}</option>
                  <option value="EDIT">{TIER_LABEL.EDIT}</option>
                </Select>
                <RowActions>
                  {g.status === "PENDING" ? (
                    <>
                      <button className="field-button" disabled={busy} onClick={() => decide(g, "GRANTED")}>
                        Approve
                      </button>
                      <button data-danger className={rowAction("danger")} disabled={busy} onClick={() => decide(g, "DENIED")}>
                        Deny
                      </button>
                    </>
                  ) : g.status === "GRANTED" ? (
                    <button data-danger className={rowAction("danger")} disabled={busy} onClick={() => revoke(g)}>
                      Revoke
                    </button>
                  ) : (
                    <button className={rowAction("neutral")} disabled={busy} onClick={() => remove(g)}>
                      Remove
                    </button>
                  )}
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 p-4">
          <h2 className="font-display font-bold text-lg text-ink">Your access to others&apos; data</h2>
          <p className="text-sm text-ink-muted">Data you requested or were granted access to.</p>
        </div>
        {outgoing.length === 0 ? (
          <div className="p-4">
            <EmptyState title="No access yet" />
          </div>
        ) : (
          <ul className="divide-y divide-line-200">
            {outgoing.map((g) => (
              <li key={g.id} className="flex flex-wrap items-center gap-3 p-4">
                <div className="min-w-0 flex-1">
                  <div className="font-medium text-ink">{g.owner?.name ?? ownerNameById.get(g.ownerId) ?? g.ownerId}</div>
                  <div className="text-xs text-ink-muted">
                    {g.owner?.email} · {TIER_LABEL[g.tier]} · {g.allData ? "All data" : `${g.scopeItems?.length ?? 0} records`}
                  </div>
                </div>
                <StatusPill status={g.status} />
                <RowActions>
                  {g.status === "GRANTED" && tierAtLeast(g.tier, "DOWNLOAD") ? (
                    <button className="field-button" disabled={busy} onClick={() => downloadOwnerData(g.ownerId, g.owner?.name ?? g.ownerId)}>
                      Download data
                    </button>
                  ) : null}
                  <button className={rowAction("neutral")} disabled={busy} onClick={() => remove(g)}>
                    {g.status === "PENDING" ? "Withdraw" : "Remove"}
                  </button>
                </RowActions>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  );
}
