"use client";

/**
 * "Two-step verification" — the account's own switch for a second sign-in step (TOTP).
 *
 * Every role may turn it on and nobody is made to. The card walks one conversation with
 * `/api/auth/two-factor` (lib/twoFactor.ts):
 *
 *   off  → "Turn on" → the QR square and the key → a 6-digit code → the ten recovery codes → done
 *   on   → status, recovery codes left, "Make new recovery codes" (a current code), "Turn off" (a code)
 *
 * THE RECOVERY CODES ARE SHOWN ONCE. The server keeps only their hashes, so the screen that shows them
 * does not go away on its own: it stays until "I've saved them" is pressed, with Copy and Download
 * beside the list. A code typed to switch this off may be either kind — the server tells them apart by
 * shape — while a new set of codes asks for the authenticator, because a person holding only recovery
 * codes is better served signing in with one and starting again.
 *
 * The QR square is drawn by the server (finished SVG), so this card renders an image and nothing here
 * encodes a QR code. It is shown as a `data:` image rather than injected markup: no HTML from a
 * response ever reaches the DOM as markup.
 *
 * A data-screen card, so the recipes its neighbours use — `.panel`, the 8x8 icon chip, `.field-label`,
 * `.field-input`, `.field-button` — as `ChangePasswordCard` does.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, Download, ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { isUnreachable } from "@/lib/failureTriage";
import {
  beginTwoFactorSetup,
  disableTwoFactor,
  enableTwoFactor,
  fetchTwoFactorStatus,
  makeNewRecoveryCodes,
  recoveryCodesFileText,
  type TwoFactorSetup,
  type TwoFactorStatus
} from "@/lib/twoFactor";

/** Which part of the conversation is on screen. */
type Step =
  | { kind: "status" }
  | { kind: "setup"; setup: TwoFactorSetup }
  | { kind: "codes"; codes: string[] }
  | { kind: "confirm"; action: "disable" | "regenerate" };

function messageOf(err: unknown, fallback: string): string {
  if (isUnreachable(err)) return "Couldn't connect. Check your connection and try again.";
  return err instanceof Error ? err.message : fallback;
}

function formatDay(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date.toLocaleDateString(undefined, { dateStyle: "medium" });
}

export function TwoStepVerificationCard() {
  const { user, refreshMe } = useAuth();
  const [status, setStatus] = useState<TwoFactorStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [step, setStep] = useState<Step>({ kind: "status" });
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const codeBox = useRef<HTMLInputElement | null>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await fetchTwoFactorStatus());
      setLoadError(null);
    } catch (err) {
      setLoadError(messageOf(err, "Two-step verification could not be loaded."));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // The code box takes the caret whenever a step that asks for a code opens.
  useEffect(() => {
    if (step.kind === "setup" || step.kind === "confirm") codeBox.current?.focus();
  }, [step]);

  if (!user) return null;

  function go(next: Step) {
    setStep(next);
    setCode("");
    setError(null);
  }

  async function copy(text: string, said: string) {
    try {
      await navigator.clipboard.writeText(text);
      setNotice(said);
    } catch {
      setNotice("Copying is blocked in this browser. Select the text and copy it yourself.");
    }
  }

  function download(codes: string[]) {
    const blob = new Blob([recoveryCodesFileText(codes, user?.email ?? "")], { type: "text/plain" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "recovery-codes.txt";
    link.click();
    URL.revokeObjectURL(url);
  }

  async function start() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      go({ kind: "setup", setup: await beginTwoFactorSetup() });
    } catch (err) {
      setError(messageOf(err, "Setup could not be started."));
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!code.trim() || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (step.kind === "setup") {
        go({ kind: "codes", codes: await enableTwoFactor(code.trim()) });
        void refreshMe();
      } else if (step.kind === "confirm" && step.action === "regenerate") {
        go({ kind: "codes", codes: await makeNewRecoveryCodes(code.trim()) });
      } else if (step.kind === "confirm" && step.action === "disable") {
        await disableTwoFactor(code.trim());
        go({ kind: "status" });
        setNotice("Two-step verification is off.");
        void refreshMe();
      }
      await load();
    } catch (err) {
      setCode("");
      setError(messageOf(err, "That code didn't work. Try again."));
      codeBox.current?.focus();
    } finally {
      setBusy(false);
    }
  }

  const enabled = status?.enabled === true;
  const since = formatDay(status?.enabled_at ?? null);

  const codeField = (label: string, hint: string, allowRecovery: boolean) => (
    <div>
      <label htmlFor="two-step-settings-code" className="field-label">
        {label}
      </label>
      <input
        id="two-step-settings-code"
        ref={codeBox}
        type="text"
        required
        inputMode={allowRecovery ? "text" : "numeric"}
        autoComplete="one-time-code"
        autoCapitalize="none"
        spellCheck={false}
        maxLength={allowRecovery ? 20 : 7}
        value={code}
        onChange={(event) => setCode(event.target.value)}
        className="field-input mt-1.5 max-w-56 font-mono tracking-[0.2em]"
        aria-describedby="two-step-settings-code-hint"
      />
      <p id="two-step-settings-code-hint" className="mt-1.5 text-xs leading-5 text-ink-500">
        {hint}
      </p>
    </div>
  );

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ShieldCheck className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">Two-step verification</h2>
        {enabled ? (
          <span className="rounded-full border border-success-600/25 bg-success-100 px-2.5 py-0.5 text-xs font-medium text-success-600">
            On
          </span>
        ) : null}
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        After you sign in, also enter a 6-digit code from an authenticator app on your phone, such as Google
        Authenticator or Microsoft Authenticator.
      </p>

      {/* Both regions mounted from first paint and emptied rather than removed, so what they say is
          announced — `ChangePasswordCard`'s pattern. */}
      <div
        role="alert"
        className={
          error || loadError
            ? "mt-3 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600"
            : "sr-only"
        }
      >
        {error ?? loadError ?? ""}
      </div>
      <p
        role="status"
        className={notice ? "mt-3 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700" : "sr-only"}
      >
        {notice ?? ""}
      </p>

      {status === null ? (
        loadError ? (
          <button type="button" className="field-button-secondary mt-3" onClick={() => void load()}>
            Try again
          </button>
        ) : (
          <p className="mt-3 text-sm text-ink-500">Loading…</p>
        )
      ) : step.kind === "status" ? (
        enabled ? (
          <div className="mt-3 grid gap-3">
            <dl className="grid gap-1 text-sm text-ink-700">
              {since ? (
                <div className="flex gap-2">
                  <dt className="text-ink-500">Turned on</dt>
                  <dd>{since}</dd>
                </div>
              ) : null}
              <div className="flex gap-2">
                <dt className="text-ink-500">Recovery codes left</dt>
                <dd>{status.recovery_codes_remaining} of 10</dd>
              </div>
            </dl>
            {status.recovery_codes_remaining <= 2 ? (
              <p className="rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm text-amber-800">
                You are running out of recovery codes. Make new ones so you can still get in without your phone.
              </p>
            ) : null}
            <div className="flex flex-wrap gap-2">
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => go({ kind: "confirm", action: "regenerate" })}
              >
                Make new recovery codes
              </button>
              <button type="button" className="field-danger rounded-md px-4 py-2 text-sm font-medium" onClick={() => go({ kind: "confirm", action: "disable" })}>
                Turn off
              </button>
            </div>
          </div>
        ) : (
          <div className="mt-3">
            <button type="button" className="field-button" disabled={busy} onClick={() => void start()}>
              {busy ? "Starting…" : "Turn on"}
            </button>
          </div>
        )
      ) : step.kind === "setup" ? (
        <form onSubmit={submitCode} className="mt-3 grid gap-4">
          <ol className="grid gap-4 text-sm leading-6 text-ink-700">
            <li className="grid gap-3">
              <span>
                <span className="font-medium text-ink-900">1.</span> Scan this code with your authenticator app.
              </span>
              {/* eslint-disable-next-line @next/next/no-img-element -- server-drawn SVG, inline data, nothing to optimise */}
              <img
                src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(step.setup.qr_svg)}`}
                alt="QR code to add this account to your authenticator app"
                width={184}
                height={184}
                className="h-46 w-46 rounded-md border border-line-200 bg-white"
              />
            </li>
            <li className="grid gap-2">
              <span>
                <span className="font-medium text-ink-900">Can&rsquo;t scan it?</span> Type this key into the app instead.
              </span>
              <div className="flex flex-wrap items-center gap-2">
                <code className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 font-mono text-sm tracking-wider text-ink-900 break-all">
                  {step.setup.secret.replace(/(.{4})/g, "$1 ").trim()}
                </code>
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={() => void copy(step.setup.secret, "Key copied.")}
                >
                  <Copy className="h-4 w-4" aria-hidden />
                  Copy key
                </button>
              </div>
            </li>
            <li>
              <span className="font-medium text-ink-900">2.</span> Enter the 6-digit code the app shows.
            </li>
          </ol>
          {codeField("6-digit code", "Codes change every 30 seconds. Type the one on screen now.", false)}
          <div className="flex flex-wrap gap-2">
            <button type="submit" className="field-button" disabled={busy || !code.trim()}>
              {busy ? "Checking…" : "Turn on"}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => go({ kind: "status" })}>
              Cancel
            </button>
          </div>
        </form>
      ) : step.kind === "codes" ? (
        <div className="mt-3 grid gap-3">
          <div>
            <h3 className="font-display text-base font-bold text-ink-900">Save these codes</h3>
            <p className="mt-1 text-sm leading-6 text-ink-700">
              If you lose your phone, each code gets you in once instead of the 6-digit code. Keep them somewhere safe.
              They will not be shown again.
            </p>
          </div>
          <ul className="grid max-w-md grid-cols-2 gap-2 rounded-md border border-line-200 bg-surface-50 p-3 font-mono text-sm text-ink-900">
            {step.codes.map((recovery) => (
              <li key={recovery}>{recovery}</li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => void copy(step.codes.join("\n"), "Recovery codes copied.")}
            >
              <Copy className="h-4 w-4" aria-hidden />
              Copy
            </button>
            <button type="button" className="field-button-secondary" onClick={() => download(step.codes)}>
              <Download className="h-4 w-4" aria-hidden />
              Download
            </button>
            <button
              type="button"
              className="field-button"
              onClick={() => {
                go({ kind: "status" });
                setNotice(null);
              }}
            >
              I&rsquo;ve saved them
            </button>
          </div>
        </div>
      ) : (
        <form onSubmit={submitCode} className="mt-3 grid gap-3">
          {step.action === "disable"
            ? codeField(
                "Code",
                "Enter the 6-digit code from your authenticator app, or one of your recovery codes.",
                true
              )
            : codeField("6-digit code", "Enter the code from your authenticator app. Your old recovery codes will stop working.", false)}
          <div className="flex flex-wrap gap-2">
            <button
              type="submit"
              className={step.action === "disable" ? "field-danger rounded-md px-4 py-2 text-sm font-medium" : "field-button"}
              disabled={busy || !code.trim()}
            >
              {busy ? "Checking…" : step.action === "disable" ? "Turn off" : "Make new codes"}
            </button>
            <button type="button" className="field-button-secondary" onClick={() => go({ kind: "status" })}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
