"use client";

import { Check, Copy, Eye, EyeOff, KeyRound, Plus, RotateCw, Trash2, X } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";

import { ResizableTh } from "@/components/ResizableTh";
import { RowActions, rowAction } from "@/components/RowActions";
import { useToast } from "@/components/ui/Toast";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";

/**
 * The managed provider keys (`/api/secrets`, master admin only).
 *
 * Two rules shape this screen:
 *
 * 1. **A value is never rendered until it is asked for.** The list endpoint deliberately carries only
 *    a four-character `hint`; the plaintext exists solely behind `GET /secrets/{key}/reveal`, which is
 *    audit-logged server-side. So the Value column shows `••••·hint` and the eye button fetches on
 *    demand — and un-reveals itself after {@link REVEAL_TIMEOUT_MS} so a key cannot be left on screen
 *    in an empty room.
 * 2. **A saved key is live immediately.** Writes invalidate the server's cache, so the next provider
 *    call uses the new value — no restart, no redeploy. That is the whole reason this page exists,
 *    so the banner says it rather than leaving the master admin wondering whether to SSH in.
 */

const REVEAL_TIMEOUT_MS = 30_000;

type SecretSource = "database" | "environment" | "unset";
type SecretStatus = "UNKNOWN" | "OK" | "FAILED";

/** Mirror of the backend ManagedSecretDto. It NEVER carries the value — only a 4-character hint. */
export type ManagedSecret = {
  key: string;
  label: string;
  description: string;
  configured: boolean;
  source: SecretSource;
  /**
   * A stored override row exists whose ciphertext this deployment cannot open — the
   * JWT_SECRET-rotated-without-SECRETS_ENCRYPTION_KEY casualty `managed_secrets._describe`
   * documents. The value in force is then the ENVIRONMENT one, which is why `source` reads
   * "environment" (or "unset") rather than "database" in this state. Optional on the wire only
   * defensively: the DTO always sends it, but a row read from a cached response predating the
   * field must not silently become "yes, unreadable".
   */
  overrideUnreadable?: boolean;
  hint: string | null;
  lastStatus: SecretStatus;
  lastCheckedAt: string | null;
  lastError: string | null;
  updatedBy: string | null;
  updatedAt: string | null;
};

/**
 * IS THERE A STORED OVERRIDE TO CLEAR? — which is NOT the same question as "where did the value in
 * force come from", and this repository has already paid for conflating them.
 *
 * `source` was made honest in `managed_secrets._describe`: when a saved override cannot be
 * decrypted, the value every provider call sends is the environment one, so the row now reports
 * `source: "environment"` (or `"unset"`) instead of the old lie `"database"`. Three audit findings
 * closed at once — the reveal endpoint, the Test button, and the hint beside the value.
 *
 * But this panel drew its "Clear override" button from `secret.source === "database"`, so that fix
 * took the button off the ONE row it exists for: the admin was told an override was broken and
 * given no control to remove it. The remedy for a state must not be gated on the state's absence.
 *
 * So the two facts are separated here, permanently. `source` = where the VALUE came from;
 * `hasStoredOverride` = whether a ROW exists to delete. The disjunction below is exact rather than
 * a guess: the backend sets `source = "database"` exactly when a row exists AND opens, and
 * `overrideUnreadable` exactly when a row exists and does NOT open. Those two are the whole of
 * "a row exists". If a future field ever states row existence directly, use it — but do NOT go
 * back to reading it off `source`.
 */
export function hasStoredOverride(secret: Pick<ManagedSecret, "source" | "overrideUnreadable">): boolean {
  return secret.source === "database" || secret.overrideUnreadable === true;
}

type RevealResponse = { key: string; value: string | null; source: SecretSource };

const SOURCE_META: Record<SecretSource, { label: string; tone: string; help: string }> = {
  database: {
    label: "Database",
    tone: "border-purple-300 bg-purple-50 text-purple-700",
    help: "Saved here — this value overrides the deployed environment."
  },
  environment: {
    label: "Environment",
    tone: "border-line-200 bg-surface-50 text-ink-700",
    help: "Coming from the deployed environment. Saving here overrides it."
  },
  unset: {
    label: "Not set",
    tone: "border-amber-500/30 bg-amber-100 text-amber-800",
    help: "No value anywhere — the features that need it are switched off."
  }
};

const STATUS_META: Record<SecretStatus, { label: string; tone: string }> = {
  OK: { label: "OK", tone: "border-success-600/25 bg-success-100 text-success-600" },
  FAILED: { label: "Failed", tone: "border-error-600/25 bg-error-100 text-error-600" },
  UNKNOWN: { label: "Unknown", tone: "border-line-200 bg-surface-50 text-ink-500" }
};

/** Which per-row action is in flight, so only that row's buttons go busy. */
type Busy = { key: string; action: "reveal" | "test" | "save" | "clear" } | null;

export function ApiKeysPanel() {
  const { toast } = useToast();

  const [secrets, setSecrets] = useState<ManagedSecret[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Busy>(null);
  /** key -> plaintext, present ONLY while that row is revealed. */
  const [revealed, setRevealed] = useState<Record<string, string>>({});
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState("");
  const [confirmingClear, setConfirmingClear] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  // One auto-hide timer per revealed key. Kept in a ref (not state) so re-renders never restart a
  // countdown that is already running.
  const timers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});
  useEffect(() => {
    const pending = timers.current;
    return () => Object.values(pending).forEach(clearTimeout);
  }, []);

  useEffect(() => {
    apiFetch<ManagedSecret[]>("/secrets")
      .then(setSecrets)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load the managed keys"));
  }, []);

  const hide = useCallback((key: string) => {
    clearTimeout(timers.current[key]);
    delete timers.current[key];
    setRevealed((current) => {
      if (!(key in current)) return current;
      const next = { ...current };
      delete next[key];
      return next;
    });
  }, []);

  /** Swap one row in place, so a test/save/clear never costs a full reload of the table. */
  const replaceRow = useCallback((row: ManagedSecret) => {
    setSecrets((current) => (current ? current.map((item) => (item.key === row.key ? row : item)) : [row]));
  }, []);

  async function reveal(secret: ManagedSecret) {
    if (revealed[secret.key] !== undefined) {
      hide(secret.key);
      return;
    }
    setBusy({ key: secret.key, action: "reveal" });
    setError(null);
    try {
      const result = await apiFetch<RevealResponse>(`/secrets/${secret.key}/reveal`);
      if (!result.value) {
        toast({ title: "Nothing to reveal", description: `${secret.label} has no value set anywhere.`, tone: "info" });
        return;
      }
      setRevealed((current) => ({ ...current, [secret.key]: result.value as string }));
      clearTimeout(timers.current[secret.key]);
      timers.current[secret.key] = setTimeout(() => hide(secret.key), REVEAL_TIMEOUT_MS);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to reveal that key");
    } finally {
      setBusy(null);
    }
  }

  async function copy(secret: ManagedSecret) {
    const value = revealed[secret.key];
    if (!value) return;
    try {
      await navigator.clipboard.writeText(value);
      setCopied(secret.key);
      setTimeout(() => setCopied((current) => (current === secret.key ? null : current)), 2000);
    } catch {
      toast({ title: "Could not copy", description: "Your browser blocked clipboard access — select the value instead.", tone: "error" });
    }
  }

  async function test(secret: ManagedSecret) {
    setBusy({ key: secret.key, action: "test" });
    setError(null);
    try {
      const row = await apiFetch<ManagedSecret>(`/secrets/${secret.key}/test`, { method: "POST" });
      replaceRow(row);
      toast({
        title: row.lastStatus === "OK" ? `${row.label} is working` : `${row.label} failed`,
        description: row.lastStatus === "OK" ? "The provider accepted the key." : row.lastError ?? "The provider rejected the key.",
        tone: row.lastStatus === "OK" ? "success" : "error"
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to test that key");
    } finally {
      setBusy(null);
    }
  }

  async function save(secret: ManagedSecret) {
    const value = draft.trim();
    if (!value) return;
    setBusy({ key: secret.key, action: "save" });
    setError(null);
    try {
      const row = await apiFetch<ManagedSecret>(`/secrets/${secret.key}`, {
        method: "PUT",
        body: JSON.stringify({ value })
      });
      replaceRow(row);
      hide(secret.key);
      setEditing(null);
      setDraft("");
      toast({ title: `${row.label} saved`, description: "It is live for everyone right now — no restart needed.", tone: "success" });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save that key");
    } finally {
      setBusy(null);
    }
  }

  async function clearOverride(secret: ManagedSecret) {
    setBusy({ key: secret.key, action: "clear" });
    setError(null);
    try {
      const row = await apiFetch<ManagedSecret>(`/secrets/${secret.key}`, { method: "DELETE" });
      replaceRow(row);
      hide(secret.key);
      setConfirmingClear(null);
      toast({
        title: `${row.label} override cleared`,
        description:
          row.source === "environment"
            ? "The deployed environment value applies again."
            : "There is no value for this key anywhere now.",
        tone: "info"
      });
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to clear that override");
    } finally {
      setBusy(null);
    }
  }

  function startEdit(secret: ManagedSecret) {
    setConfirmingClear(null);
    setEditing(secret.key);
    setDraft("");
  }

  return (
    <div className="grid gap-4">
      <div className="rounded-md border border-purple-300 bg-purple-50 px-4 py-3 text-sm leading-6 text-purple-800">
        <span className="font-medium">Saved keys take effect immediately, for everyone.</span> A key stored here overrides the
        deployed environment on the very next provider call — the API and the transcription queue both pick it up without a
        restart or a redeploy. Values are encrypted at rest, are never written to logs, and revealing one is recorded against
        your account.
      </div>

      {error ? <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div> : null}

      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">Managed API keys</h2>
          <p className="text-sm text-ink-500">
            Every key the repository can be configured with. Test one to check it against the provider before a field team
            depends on it.
          </p>
        </div>

        {!secrets ? (
          <div className="p-4 text-sm text-ink-500">{error ? "Could not load the keys." : "Loading…"}</div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1000px] text-left text-sm">
              <thead className="bg-surface-50 text-xs uppercase text-ink-500">
                <tr>
                  <ResizableTh>Key</ResizableTh>
                  <ResizableTh>Value</ResizableTh>
                  <ResizableTh>Source</ResizableTh>
                  <ResizableTh>Status</ResizableTh>
                  <ResizableTh>Last updated</ResizableTh>
                  <ResizableTh className="text-right">Actions</ResizableTh>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-200">
                {secrets.map((secret) => {
                  const plaintext = revealed[secret.key];
                  const source = SOURCE_META[secret.source] ?? SOURCE_META.unset;
                  const status = STATUS_META[secret.lastStatus] ?? STATUS_META.UNKNOWN;
                  const rowBusy = busy?.key === secret.key ? busy.action : null;

                  return (
                    <tr className="align-top" key={secret.key}>
                      <td className="px-4 py-3">
                        <div className="font-medium text-ink-900">{secret.label}</div>
                        <div className="font-mono text-[0.6875rem] uppercase tracking-wide text-ink-500">{secret.key}</div>
                        <p className="mt-1 max-w-sm text-xs leading-5 text-ink-500">{secret.description}</p>
                      </td>

                      <td className="px-4 py-3">
                        <div className="flex items-start gap-2">
                          {plaintext ? (
                            <code className="max-w-[18rem] break-all rounded-md bg-surface-50 px-2 py-1 font-mono text-xs text-ink-900">
                              {plaintext}
                            </code>
                          ) : (
                            <span className="font-mono text-xs text-ink-500">
                              {secret.configured ? `••••••••${secret.hint ?? ""}` : "Not set"}
                            </span>
                          )}
                          <button
                            aria-label={plaintext ? `Hide ${secret.label}` : `Reveal ${secret.label}`}
                            className="shrink-0 rounded-md border border-line-200 p-1.5 text-ink-700 transition hover:border-purple-300 hover:bg-purple-50 disabled:cursor-not-allowed disabled:opacity-50"
                            disabled={!secret.configured || rowBusy === "reveal"}
                            onClick={() => reveal(secret)}
                            title={plaintext ? "Hide the value" : "Reveal the value (recorded against your account)"}
                            type="button"
                          >
                            {plaintext ? <EyeOff className="h-4 w-4" aria-hidden /> : <Eye className="h-4 w-4" aria-hidden />}
                          </button>
                          {plaintext ? (
                            <button
                              aria-label={`Copy ${secret.label}`}
                              className="shrink-0 rounded-md border border-line-200 p-1.5 text-ink-700 transition hover:border-purple-300 hover:bg-purple-50"
                              onClick={() => copy(secret)}
                              title="Copy to clipboard"
                              type="button"
                            >
                              {copied === secret.key ? (
                                <Check className="h-4 w-4 text-success-600" aria-hidden />
                              ) : (
                                <Copy className="h-4 w-4" aria-hidden />
                              )}
                            </button>
                          ) : null}
                        </div>
                        {plaintext ? (
                          <p className="mt-1 text-[0.6875rem] leading-4 text-ink-500">Hides itself again in 30 seconds.</p>
                        ) : null}
                      </td>

                      <td className="px-4 py-3">
                        <span className={`inline-block rounded-full border px-2.5 py-1 text-xs font-medium ${source.tone}`}>
                          {source.label}
                        </span>
                        <p className="mt-1 max-w-[12rem] text-[0.6875rem] leading-4 text-ink-500">{source.help}</p>
                        {/* Why an "Environment" (or "Not set") row is nonetheless offering "Clear
                            override": there IS a saved key here, it just cannot be decrypted any
                            more, so it is not the key being sent. Without this sentence the badge
                            and the button contradict each other on screen. */}
                        {secret.overrideUnreadable ? (
                          <p className="mt-1 max-w-[12rem] text-[0.6875rem] leading-4 text-error-600">
                            A key saved here can no longer be decrypted, so it is not in use. Re-enter it, or clear the
                            broken override.
                          </p>
                        ) : null}
                      </td>

                      <td className="px-4 py-3">
                        <span className={`inline-block rounded-full border px-2.5 py-1 text-xs font-medium ${status.tone}`}>
                          {status.label}
                        </span>
                        <p className="mt-1 text-[0.6875rem] leading-4 text-ink-500">
                          {secret.lastCheckedAt ? `Checked ${formatDateTime(secret.lastCheckedAt)}` : "Never tested"}
                        </p>
                        {secret.lastError ? (
                          <p className="mt-1 max-w-[14rem] text-[0.6875rem] leading-4 text-error-600">{secret.lastError}</p>
                        ) : null}
                      </td>

                      <td className="px-4 py-3 text-ink-700">
                        {secret.updatedAt ? (
                          <>
                            <div className="text-xs">{formatDateTime(secret.updatedAt)}</div>
                            <div className="text-[0.6875rem] text-ink-500">by {secret.updatedBy ?? "unknown"}</div>
                          </>
                        ) : (
                          <span className="text-xs text-ink-500">Never edited here</span>
                        )}
                      </td>

                      <td className="px-4 py-3">
                        <RowActions>
                          <button
                            className={rowAction("neutral")}
                            disabled={rowBusy === "test"}
                            onClick={() => test(secret)}
                            type="button"
                          >
                            <RotateCw className={`h-3.5 w-3.5 ${rowBusy === "test" ? "animate-spin" : ""}`} aria-hidden />
                            {rowBusy === "test" ? "Testing…" : "Test"}
                          </button>
                          <button className={rowAction("edit")} onClick={() => startEdit(secret)} type="button">
                            {secret.source === "unset" ? (
                              <>
                                <Plus className="h-3.5 w-3.5" aria-hidden />
                                Add key
                              </>
                            ) : (
                              <>
                                <KeyRound className="h-3.5 w-3.5" aria-hidden />
                                Update
                              </>
                            )}
                          </button>
                          {/* Drawn from "is there a row to delete", never from "where did the value
                              come from" — see `hasStoredOverride`. An unreadable override reports
                              source "environment"/"unset" and is precisely the row an admin needs
                              this button for. */}
                          {hasStoredOverride(secret) ? (
                            <button
                              className={rowAction("danger")}
                              onClick={() => {
                                setEditing(null);
                                setConfirmingClear(secret.key);
                              }}
                              type="button"
                            >
                              <Trash2 className="h-3.5 w-3.5" aria-hidden />
                              Clear override
                            </button>
                          ) : null}
                        </RowActions>

                        {editing === secret.key ? (
                          <div className="mt-3 rounded-md border border-purple-300 bg-purple-50 p-3">
                            <label className="grid gap-1">
                              <span className="field-label">
                                {secret.source === "unset" ? `New ${secret.label} key` : `Replace the ${secret.label} key`}
                              </span>
                              <input
                                autoComplete="off"
                                autoFocus
                                className="field-input font-mono text-xs"
                                onChange={(event) => setDraft(event.target.value)}
                                onKeyDown={(event) => {
                                  if (event.key === "Enter") {
                                    event.preventDefault();
                                    void save(secret);
                                  }
                                  if (event.key === "Escape") setEditing(null);
                                }}
                                placeholder="Paste the key — whitespace is trimmed"
                                spellCheck={false}
                                type="password"
                                value={draft}
                              />
                            </label>
                            <p className="mt-2 text-[0.6875rem] leading-4 text-ink-500">
                              Saving replaces the value for the whole repository at once, effective immediately.
                            </p>
                            <div className="mt-2 flex justify-end gap-2">
                              <button
                                className={rowAction("neutral")}
                                onClick={() => {
                                  setEditing(null);
                                  setDraft("");
                                }}
                                type="button"
                              >
                                <X className="h-3.5 w-3.5" aria-hidden />
                                Cancel
                              </button>
                              <button
                                className={rowAction("edit")}
                                disabled={!draft.trim() || rowBusy === "save"}
                                onClick={() => save(secret)}
                                type="button"
                              >
                                <Check className="h-3.5 w-3.5" aria-hidden />
                                {rowBusy === "save" ? "Saving…" : "Save key"}
                              </button>
                            </div>
                          </div>
                        ) : null}

                        {confirmingClear === secret.key ? (
                          <div className="mt-3 rounded-md border border-red-200 bg-error-100 p-3 text-left">
                            <p className="text-xs font-medium text-error-600">Clear the stored {secret.label} key?</p>
                            <p className="mt-1 text-[0.6875rem] leading-4 text-ink-700">
                              {secret.overrideUnreadable
                                ? "The saved override cannot be decrypted, so nothing is using it: deleting it changes no value, it only removes the broken row and the warning beside it."
                                : "The saved override is deleted and the deployed environment value applies again from the next call. If the environment has no value for this key, the features that need it stop working."}
                            </p>
                            <div className="mt-2 flex justify-end gap-2">
                              <button className={rowAction("neutral")} onClick={() => setConfirmingClear(null)} type="button">
                                Keep it
                              </button>
                              <button
                                className={rowAction("danger")}
                                disabled={rowBusy === "clear"}
                                onClick={() => clearOverride(secret)}
                                type="button"
                              >
                                <Trash2 className="h-3.5 w-3.5" aria-hidden />
                                {rowBusy === "clear" ? "Clearing…" : "Clear override"}
                              </button>
                            </div>
                          </div>
                        ) : null}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
