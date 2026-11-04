"use client";

import { countNoun } from "@/lib/format";
import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { CloudOff, RefreshCw, TriangleAlert } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { useToast } from "@/components/ui/Toast";
import {
  acknowledgeStoreTrouble,
  getDraftsSnapshot,
  getServerDraftsSnapshot,
  getServerStoreHealth,
  getStoreHealth,
  refreshDrafts,
  retryDraft,
  setDraftSessionUser,
  storeIsAnswering,
  subscribeDrafts,
  syncDesignWorkshopDrafts,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { workshopDisplayTitle } from "@/lib/workshopTitle";

export function syncOutcome(result: {
  workshopsCreated: number;
  stagesSent: number;
  mediaUploaded: number;
  failed: number;
  pending: number;
  stoppedOffline: boolean;
  passwordChangeRequired?: boolean;
  credentialExpired?: boolean;
}): {
  kind: "sent" | "refused" | "expired" | "password" | "offline" | "idle";
  tone: "success" | "error" | "info";
  title: string;
} {
  const sent = result.workshopsCreated + result.stagesSent + result.mediaUploaded;
  if (sent) {
    return {
      kind: "sent",
      tone: result.credentialExpired ? "error" : "success",
      title: `${sent} saved ${sent === 1 ? "change" : "changes"} sent`
    };
  }
  if (result.failed > 0) {
    return {
      kind: "refused",
      tone: "error",
      title: `${result.failed} ${result.failed === 1 ? "item" : "items"} couldn't be saved`
    };
  }
  if (result.credentialExpired) return { kind: "expired", tone: "error", title: "Your sign-in has expired" };
  if (result.passwordChangeRequired) return { kind: "password", tone: "info", title: "Choose a new password first" };
  if (result.stoppedOffline) return { kind: "offline", tone: "error", title: "Still no connection" };
  return { kind: "idle", tone: "info", title: "Nothing to send" };
}

export function outstanding(draft: DwDraft) {
  const stages = Object.values(draft.stages).filter((stage) => stage.dirtyAt !== null || stage.removedFrom.length > 0);
  const refusals = [
    ...(draft.failure?.permanent ? [draft.failure.message] : []),
    ...Object.values(draft.stages)
      .filter((stage) => stage.failure?.permanent)
      .map((stage) => `${stage.stageKey}: ${stage.failure?.message ?? ""}`)
  ];
  const held = Object.values(draft.stages)
    .filter((stage) => stage.failure && !stage.failure.permanent)
    .map((stage) => stage.failure?.message ?? "");
  const folds = Object.values(draft.stages)
    .filter((stage) => (stage.foldNote ?? "").trim().length > 0)
    .map((stage) => ({ stageKey: stage.stageKey, note: stage.foldNote as string }));
  return {
    stages: stages.length,
    neverSent: draft.remoteId === null,
    headerOnly: draft.headerDirtyAt !== null,
    refusals,
    held,
    folds
  };
}

export function editingWorkshopId(pathname: string | null): string | null {
  const match = /^\/design-workshops\/([^/]+)\/stages\/[^/]+/.exec(pathname ?? "");
  return match ? decodeURIComponent(match[1]) : null;
}

export function bannerRowWanted(
  draft: Pick<DwDraft, "localId" | "remoteId">,
  state: ReturnType<typeof outstanding>,
  editing: string | null
): boolean {
  const wanted =
    state.stages > 0 ||
    state.neverSent ||
    state.headerOnly ||
    state.refusals.length > 0 ||
    state.held.length > 0 ||
    state.folds.length > 0;
  if (!wanted) return false;
  const thisOne = editing !== null && (draft.localId === editing || draft.remoteId === editing);
  if (!thisOne) return true;
  return state.neverSent || state.headerOnly || state.refusals.length > 0 || state.held.length > 0 || state.folds.length > 0;
}

export function DesignWorkshopDraftBanner() {
  const drafts = useSyncExternalStore(subscribeDrafts, getDraftsSnapshot, getServerDraftsSnapshot);
  const editing = editingWorkshopId(usePathname());
  const health = useSyncExternalStore(subscribeDrafts, getStoreHealth, getServerStoreHealth);
  const [syncing, setSyncing] = useState(false);
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();
  const sessionResolved = !authLoading;

  useEffect(() => {
    if (!sessionResolved) return;
    setDraftSessionUser(user?.id ?? null, user?.role ?? null);
    return () => setDraftSessionUser(null, null);
  }, [sessionResolved, user?.id, user?.role]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncDesignWorkshopDrafts();
        const outcome = syncOutcome(result);
        if (outcome.kind === "sent") {
          toast({
            id: "dw-draft-sync",
            tone: outcome.tone,
            title: outcome.title,
            description: result.credentialExpired
              ? `Signed out — ${countNoun(result.pending, "workshop")} still waiting. Sign in to send ${result.pending === 1 ? "it" : "them"}.`
              : result.pending
                ? `${countNoun(result.pending, "workshop")} still waiting.`
                : "Everything on this device has been sent."
          });
        } else if (trigger === "manual") {
          toast({
            id: "dw-draft-sync",
            tone: outcome.tone,
            title: outcome.title,
            description:
              outcome.kind === "refused"
                ? "Nothing was lost. See what each one needs below."
                : outcome.kind === "offline"
                  ? "Still waiting on this device. Try again with signal."
                  : outcome.kind === "expired"
                    ? "Nothing sent; it's safe on this device. Sign in again."
                    : outcome.kind === "password"
                      ? "Nothing sent. It sends after you set your new password."
                      : undefined
          });
        }
      } finally {
        setSyncing(false);
      }
    },
    [toast]
  );

  useEffect(() => {
    if (!sessionResolved || !user) return;
    void refreshDrafts().then((rows) => {
      if (!rows.length) return;
      if (typeof navigator !== "undefined" && navigator.onLine === false) return;
      void drain("auto");
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sessionResolved, user?.id]);

  useEffect(() => {
    function onOnline() {
      if (getDraftsSnapshot().length) void drain("auto");
    }
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [drain]);

  const rows = drafts
    .map((draft) => ({ draft, state: outstanding(draft) }))
    .filter(({ draft, state }) => bannerRowWanted(draft, state, editing));

  const trouble = !storeIsAnswering(health);

  if (!rows.length && !trouble) return null;

  const waiting = rows.filter(({ state }) => !state.refusals.length).length;

  const troublePanel = trouble ? (
    <section
      aria-live="assertive"
      className="mb-4 grid gap-2 rounded-lg border border-error-600/40 bg-error-50 p-4 text-ink-900"
    >
      <div className="flex items-start gap-2">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-error-600" aria-hidden />
        <div>
          <h2 className="font-display text-sm font-bold">This browser&rsquo;s storage isn&rsquo;t responding</h2>
          <p className="mt-0.5 text-xs text-ink-700">
            {health.writeFailedAt
              ? "Recent edits may not be saved — storage is full or this is a private window. Free space and reload before typing more."
              : "Couldn't read your saved work. Don't clear browser data — reload, and contact an admin if it persists."}
          </p>
        </div>
      </div>
      <div>
        <button type="button" className="field-button-secondary" onClick={() => acknowledgeStoreTrouble()}>
          I have read this
        </button>
      </div>
    </section>
  ) : null;

  if (!rows.length) return troublePanel;

  return (
    <>
    {troublePanel}
    <section
      aria-live="polite"
      className="mb-4 grid gap-3 rounded-lg border border-amber-500/40 bg-amber-100/60 p-4 text-ink-900"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          <CloudOff className="mt-0.5 h-4 w-4 shrink-0 text-amber-800 dark:text-amber-100" aria-hidden />
          <div>
            <h2 className="font-display text-sm font-bold">
              {waiting
                ? `${waiting} design ${waiting === 1 ? "workshop is" : "workshops are"} saved on this device only`
                : "Design workshops on this device need your attention"}
            </h2>
            <p className="mt-0.5 text-xs text-ink-700">
              {waiting
                ? "Saved offline; they upload when you reconnect. Don't clear browser data."
                : "These couldn't be saved and need attention."}
            </p>
          </div>
        </div>
        {waiting ? (
          <button type="button" className="field-button-secondary shrink-0" disabled={syncing} onClick={() => drain("manual")}>
            <RefreshCw className={`h-4 w-4 ${syncing ? "animate-spin" : ""}`} aria-hidden />
            {syncing ? "Sending…" : "Sync now"}
          </button>
        ) : null}
      </div>

      <ul className="grid gap-1.5">
        {rows.map(({ draft, state }) => (
          <li
            key={draft.localId}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="min-w-0">
              <Link
                href={`/design-workshops/${draft.remoteId ?? draft.localId}`}
                className="text-sm font-medium underline-offset-2 hover:underline"
              >
                {workshopDisplayTitle(draft.header)}
              </Link>
              <span className="ml-2 text-xs text-ink-500">
                {[
                  state.neverSent ? "not uploaded yet" : null,
                  state.stages ? `${countNoun(state.stages, "stage")} waiting` : null,
                  state.headerOnly && !state.neverSent ? "workshop details waiting" : null
                ]
                  .filter(Boolean)
                  .join(" · ") || "waiting"}
                {" · "}
                {new Date(draft.updatedAt).toLocaleString()}
              </span>
              {state.held.map((message, index) => (
                <p key={`held-${index}`} className="mt-0.5 text-xs text-ink-700">
                  {message}
                </p>
              ))}
              {state.folds.map((fold) => (
                <p key={`fold-${fold.stageKey}`} className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  <span>
                    <span className="font-semibold">{fold.stageKey}: </span>
                    {fold.note}
                  </span>
                </p>
              ))}
              {state.refusals.map((message, index) => (
                <p key={`refused-${index}`} className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  {message}
                </p>
              ))}
            </div>
            {state.refusals.length ? (
              <button
                type="button"
                className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700 dark:text-purple-300"
                onClick={async () => {
                  await retryDraft(draft.localId);
                  await drain("manual");
                }}
              >
                <RefreshCw className="h-3.5 w-3.5" aria-hidden />
                Try again
              </button>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
    </>
  );
}
