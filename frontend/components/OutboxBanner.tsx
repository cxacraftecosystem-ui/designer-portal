"use client";

import { countNoun } from "@/lib/format";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { CircleAlert, CloudOff, Link2, RefreshCw, Trash2, TriangleAlert } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { DownloadMediaButton } from "@/components/media/DownloadMediaButton";
import { MediaActionButton } from "@/components/media/MediaActionButton";
import { Dropdown } from "@/components/ui/Dropdown";
import { useToast, type ToastTone } from "@/components/ui/Toast";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { listResource } from "@/lib/api";
import { downloadLocalMedia } from "@/lib/localMediaDownload";
import {
  acknowledgeOutboxTrouble,
  danglingKeys,
  discardOutboxEntry,
  getOutboxHealth,
  getOutboxQueuedHere,
  getOutboxSnapshot,
  getServerOutboxHealth,
  getServerOutboxQueuedHere,
  getServerOutboxSnapshot,
  markDifferentPerson,
  markSamePerson,
  outboxEntriesForAnotherAccount,
  outboxIsAnswering,
  outboxOtherAccountLine,
  referenceFieldNoun,
  refreshOutbox,
  repickEmptyLine,
  repickOutboxEntry,
  retryOutboxEntry,
  setOutboxSessionUser,
  subscribeOutbox,
  syncOutbox,
  type OutboxEntry,
  type SyncResult
} from "@/lib/offline";

function fileCount(entry: OutboxEntry): number {
  return entry.media.reduce((sum, batch) => sum + batch.files.length, 0);
}

export function outboxOutcome(result: SyncResult): {
  kind: "sent" | "expired" | "refused" | "offline" | "busy" | "unreadable" | "password" | "idle";
  tone: ToastTone;
  title: string;
  description?: string;
} {
  if (result.synced) {
    const unfiled = result.sentUnfiled ?? [];
    return {
      kind: "sent",
      tone: result.credentialExpired ? "error" : "success",
      title: `${result.synced} saved ${result.synced === 1 ? "entry" : "entries"} sent`,
      description: result.credentialExpired
        ? `Your sign-in expired. ${result.remaining} ${
            result.remaining === 1 ? "entry is" : "entries are"
          } safe on this device — sign in, then Sync now.`
        : result.passwordChangeRequired
          ? `Set a new password to send the rest — ${result.remaining} ${
              result.remaining === 1 ? "entry is" : "entries are"
            } safe on this device.`
          : unfiled.length
          ? unfiled.join(" ")
          : result.remaining
            ? `${result.remaining} still waiting.`
            : "Everything has been sent."
    };
  }
  if (result.credentialExpired) {
    return {
      kind: "expired",
      tone: "error",
      title: "Your sign-in has expired",
      description:
        "Nothing has been sent and nothing has been thrown away — everything below is still on this device. Sign in again, then use Sync now."
    };
  }
  if (result.failed > 0) {
    return {
      kind: "refused",
      tone: "error",
      title: `${result.failed} ${result.failed === 1 ? "entry" : "entries"} couldn't be saved`,
      description: "Nothing was lost. See what each needs below."
    };
  }
  if (result.stoppedOffline) {
    return {
      kind: "offline",
      tone: "error",
      title: "Still no connection",
      description: "Still waiting. Try again with signal."
    };
  }
  if (result.declined) {
    return {
      kind: "busy",
      tone: "info",
      title: "Another tab is sending these",
      description: `${result.remaining} still waiting. Keep that tab open.`
    };
  }
  if (result.storeUnreadable) {
    return {
      kind: "unreadable",
      tone: "error",
      title: "Couldn't read the work saved on this device",
      description:
        "Some work may still be waiting. Reload, and don't clear browser data."
    };
  }
  if (result.passwordChangeRequired) {
    return {
      kind: "password",
      tone: "info",
      title: "Choose a new password first",
      description:
        "Nothing has been sent and nothing has been thrown away. Everything is still on this device and will send once you've set your new password."
    };
  }
  const stranded = result.otherAccount ?? 0;
  return {
    kind: "idle",
    tone: "info",
    title: "Nothing to send",
    description: stranded ? outboxOtherAccountLine(stranded) : undefined
  };
}

const REPICK_SOURCES: Record<string, { endpoint: string; noun: string; label: (row: RepickRow) => string }> = {
  designWorkshopId: {
    endpoint: "/design-workshops",
    noun: "design & prototype workshop",
    label: (row) => [row.title, row.craftName, row.clusterName ?? row.state].filter(Boolean).join(" · ")
  },
  workshopId: {
    endpoint: "/workshops",
    noun: "workshop",
    label: (row) => [row.title, row.place].filter(Boolean).join(" · ")
  }
};

type RepickRow = {
  id: string;
  title?: string | null;
  place?: string | null;
  craftName?: string | null;
  clusterName?: string | null;
  state?: string | null;
};

function RepickDialog({
  entry,
  field,
  onClose,
  onDone
}: {
  entry: OutboxEntry;
  field: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const source = REPICK_SOURCES[field];
  const [rows, setRows] = useState<RepickRow[] | null>(null);
  const [listed, setListed] = useState(false);
  const [choice, setChoice] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!source) return;
    let cancelled = false;
    listResource<RepickRow>(source.endpoint, { pageSize: 80 })
      .then((page) => {
        if (cancelled) return;
        setRows(page.items);
        setListed(true);
      })
      .catch(() => {
        if (!cancelled) setRows([]);
      });
    return () => {
      cancelled = true;
    };
  }, [source]);

  const noun = source?.noun ?? referenceFieldNoun(field);
  const options = (rows ?? []).map((row) => ({ value: row.id, label: source?.label(row) || row.id }));

  return (
    <FieldDialog
      open
      onClose={onClose}
      busy={saving}
      icon={<Link2 className="h-4 w-4" aria-hidden />}
      title={`Point “${entry.label}” at another ${noun}`}
      description="Choose one that still exists to send it."
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={onClose} disabled={saving}>
            Cancel
          </button>
          <button
            type="button"
            className="field-button"
            disabled={saving || rows === null}
            onClick={async () => {
              setSaving(true);
              try {
                await repickOutboxEntry(entry.id!, field, choice || null);
                onDone();
              } finally {
                setSaving(false);
              }
            }}
          >
            {saving ? "Saving…" : "Use this"}
          </button>
        </>
      }
    >
      {rows === null ? (
        <p className="text-sm text-ink-700">Reading the {noun} list{"…"}</p>
      ) : options.length === 0 ? (
        <p className="text-sm text-ink-700">{repickEmptyLine(noun, listed)}</p>
      ) : (
        <Dropdown
          value={choice}
          onChange={setChoice}
          searchable
          ariaLabel={`Choose a ${noun}`}
          placeholder={`File it under no ${noun}`}
          options={[{ value: "", label: `File it under no ${noun}` }, ...options]}
        />
      )}
    </FieldDialog>
  );
}

export function OutboxBanner() {
  const entries = useSyncExternalStore(subscribeOutbox, getOutboxSnapshot, getServerOutboxSnapshot);
  const health = useSyncExternalStore(subscribeOutbox, getOutboxHealth, getServerOutboxHealth);
  const queuedHere = useSyncExternalStore(subscribeOutbox, getOutboxQueuedHere, getServerOutboxQueuedHere);
  const [syncing, setSyncing] = useState(false);
  const [repicking, setRepicking] = useState<{ entry: OutboxEntry; field: string } | null>(null);
  const { toast } = useToast();
  const { user, loading: authLoading } = useAuth();

  useEffect(() => {
    if (authLoading) return;
    setOutboxSessionUser(user?.id ?? null);
    return () => setOutboxSessionUser(null);
  }, [authLoading, user?.id]);

  const announcedQueued = useRef(0);

  useEffect(() => {
    const seen = announcedQueued.current;
    if (queuedHere <= seen) return;
    const added = queuedHere - seen;
    announcedQueued.current = queuedHere;
    toast({
      id: "outbox-queued",
      tone: "info",
      title: `${added === 1 ? "Entry" : `${added} entries`} saved on this device`,
      description: "It uploads when you're online."
    });
  }, [queuedHere, toast]);

  const drain = useCallback(
    async (trigger: "auto" | "manual") => {
      setSyncing(true);
      try {
        const result = await syncOutbox();
        const outcome = outboxOutcome(result);
        const speaks =
          outcome.kind === "sent" || trigger === "manual" || outcome.kind === "expired" || outcome.kind === "unreadable";
        if (speaks) {
          toast({ id: "outbox-sync", tone: outcome.tone, title: outcome.title, description: outcome.description });
        }
      } catch (error) {
        toast({
          id: "outbox-sync",
          tone: "error",
          title: "Couldn't finish sending",
          description:
            error instanceof Error && error.message
              ? `${error.message} Nothing was lost — reload and try again.`
              : "Nothing was lost — reload and try again."
        });
      } finally {
        setSyncing(false);
      }
    },
    [toast]
  );

  useEffect(() => {
    void refreshOutbox().then((rows) => {
      if (!rows.length) return;
      if (typeof navigator !== "undefined" && navigator.onLine === false) return;
      void drain("auto");
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    function onOnline() {
      if (getOutboxSnapshot().length) drain("auto");
    }
    window.addEventListener("online", onOnline);
    return () => window.removeEventListener("online", onOnline);
  }, [drain]);

  const trouble = !outboxIsAnswering(health);

  if (!entries.length && !trouble) return null;

  const rejected = entries.filter((entry) => entry.failure);
  const otherAccount = outboxEntriesForAnotherAccount(entries, user?.id ?? null);
  const waiting = entries.length - rejected.length - otherAccount;

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
              ? "Your latest record may not be saved — storage is full or this is a private window. Free space and reload before typing more."
              : "Couldn't read saved records. Don't clear browser data — reload, and contact an admin if it persists."}
          </p>
        </div>
      </div>
      <div>
        <button type="button" className="field-button-secondary" onClick={() => acknowledgeOutboxTrouble()}>
          I have read this
        </button>
      </div>
    </section>
  ) : null;

  if (!entries.length) return troublePanel;

  return (
    <>
    {repicking ? (
      <RepickDialog
        entry={repicking.entry}
        field={repicking.field}
        onClose={() => setRepicking(null)}
        onDone={() => {
          setRepicking(null);
          void drain("manual");
        }}
      />
    ) : null}
    {troublePanel}
    <section
      aria-live="polite"
      className="mb-4 grid gap-3 rounded-lg border border-amber-500/40 bg-amber-100/60 p-4 text-ink-900"
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-2">
          {waiting ? (
            <CloudOff className="mt-0.5 h-4 w-4 shrink-0 text-amber-800 dark:text-amber-100" aria-hidden />
          ) : (
            <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-800 dark:text-amber-100" aria-hidden />
          )}
          <div>
            <h2 className="font-display text-sm font-bold">
              {waiting
                ? `${waiting} ${waiting === 1 ? "entry is" : "entries are"} saved on this device only`
                : "Entries on this device need your attention"}
            </h2>
            <p className="mt-0.5 text-xs text-ink-700">
              {waiting
                ? "They upload when you reconnect. Don't clear browser data."
                : rejected.length
                  ? "These couldn't be saved and need attention."
                  :
                    "Nothing here is waiting for a connection."}
            </p>
            {otherAccount ? (
              <p className="mt-1 text-xs font-medium text-amber-800 dark:text-amber-100">{outboxOtherAccountLine(otherAccount)}</p>
            ) : null}
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
        {entries.map((entry) => (
          <li
            key={entry.id}
            className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-line-200 bg-surface-50 px-3 py-2"
          >
            <div className="min-w-0">
              <span className="text-sm font-medium">{entry.label}</span>
              <span className="ml-2 text-xs text-ink-500">
                {new Date(entry.createdAt).toLocaleString()}
                {fileCount(entry) ? ` · ${countNoun(fileCount(entry), "file")}` : ""}
              </span>
              {entry.failure && entry.possibleDuplicate ? (
                <div className="mt-0.5 text-xs">
                  <p className="font-semibold text-amber-800 dark:text-amber-100">May already be recorded</p>
                  <p className="text-ink-700">{entry.failure}</p>
                </div>
              ) : entry.failure ? (
                <p className="mt-0.5 flex items-start gap-1 text-xs text-error-600">
                  <TriangleAlert className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                  {entry.failure}
                </p>
              ) : null}
              {fileCount(entry) ? (
                <ul className="mt-1.5 flex flex-wrap gap-1.5">
                  {entry.media.flatMap((batch, batchIndex) =>
                    batch.files.map((file, fileIndex) => (
                      <li
                        key={`${batchIndex}-${fileIndex}-${file.name}`}
                        className="inline-flex max-w-full items-center gap-1.5 rounded-md border border-line-200 bg-card py-0.5 pl-2 pr-0.5"
                      >
                        <span className="max-w-48 truncate text-xs text-ink-700" title={file.name}>
                          {file.name}
                        </span>
                        <DownloadMediaButton
                          name={file.name}
                          onDownload={() => downloadLocalMedia(file)}
                        />
                      </li>
                    ))
                  )}
                </ul>
              ) : null}
            </div>
            {entry.failure && entry.possibleDuplicate ? (
              <div className="flex shrink-0 flex-wrap items-center gap-3">
                <ActionGroup align="end" inline>
                  <button
                    type="button"
                    className="field-button-secondary"
                    disabled={syncing}
                    onClick={async () => {
                      const target = entry.possibleDuplicate?.candidates[0]?.artisan.id;
                      if (!target) return;
                      await markSamePerson(entry.id!, target).catch(() => undefined);
                      await drain("manual");
                    }}
                  >
                    Same person
                  </button>
                  <button
                    type="button"
                    className="field-button"
                    disabled={syncing}
                    onClick={async () => {
                      await markDifferentPerson(entry.id!).catch(() => undefined);
                      await drain("manual");
                    }}
                  >
                    Different person
                  </button>
                </ActionGroup>
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-xs font-semibold text-error-600"
                  onClick={() =>
                    void discardOutboxEntry(entry.id!).catch(() =>
                      toast({
                        id: "outbox-discard",
                        tone: "error",
                        title: "That entry couldn't be discarded",
                        description: "It's still here. Reload and try again."
                      })
                    )
                  }
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden />
                  Discard
                </button>
              </div>
            ) : entry.failure ? (
              <div className="flex shrink-0 items-center gap-3">
                {danglingKeys(entry)
                  .filter((key) => key in REPICK_SOURCES)
                  .slice(0, 1)
                  .map((key) => (
                    <button
                      key={key}
                      type="button"
                      className="inline-flex items-center gap-1 text-xs font-semibold text-purple-700 dark:text-purple-300"
                      disabled={syncing}
                      onClick={() => setRepicking({ entry, field: key })}
                    >
                      <Link2 className="h-3.5 w-3.5" aria-hidden />
                      Re-pick it
                    </button>
                  ))}
                <MediaActionButton
                  label="Retry"
                  icon={<RefreshCw className="h-4 w-4" aria-hidden />}
                  disabled={syncing}
                  onClick={async () => {
                    await retryOutboxEntry(entry.id!).catch(() => {
                    });
                    await drain("manual");
                  }}
                />
                <button
                  type="button"
                  className="inline-flex items-center gap-1 text-xs font-semibold text-error-600"
                  onClick={() =>
                    void discardOutboxEntry(entry.id!).catch(() =>
                      toast({
                        id: "outbox-discard",
                        tone: "error",
                        title: "That entry couldn't be discarded",
                        description: "It's still here. Reload and try again."
                      })
                    )
                  }
                >
                  <Trash2 className="h-3.5 w-3.5" aria-hidden />
                  Discard
                </button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
    </>
  );
}
