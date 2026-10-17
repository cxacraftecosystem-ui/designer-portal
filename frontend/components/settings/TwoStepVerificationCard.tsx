"use client";

/**
 * "Two-step verification" — the account's own switch for a second sign-in step (TOTP).
 *
 * Every role may turn it on and nobody is made to. It is a `Toggle` (owner's ruling, 2026-10-17: a
 * setting is a switch, never the word "Off" beside a button), and the switch tells the truth:
 *
 *   Off → switch on  → setup opens (QR, key, six boxes). The switch is NOT on: it reads "Setting up…".
 *                      Only when the server accepts a code (`/enable`) does it read On; then the ten
 *                      recovery codes are shown, once.
 *   Leaving setup    → Cancel, switching it back off, navigating away, reloading or closing the tab,
 *                      and coming back to a tab hidden for more than five minutes DISCARD the
 *                      unconfirmed key (`POST /auth/two-factor/setup/cancel`). A short hide does not:
 *                      on a phone the person goes to their authenticator app mid-setup
 *                      (`leavingSetupDiscards`). A setup the server still holds from an earlier visit
 *                      is cleared on load and reads Off — never On, never a request for a code nobody
 *                      saved.
 *   On  → switch off → a current code (six boxes) or a recovery code, then it is off.
 *
 * The status line under the switch is plain words: "On since 17 Oct 2026 · 8 recovery codes left"
 * (`twoStepSwitchView`, lib/twoStepSwitch.ts — Android's table too).
 *
 * The QR square is drawn by the server (finished SVG) and shown as a `data:` image, so no HTML from a
 * response ever reaches the DOM as markup and nothing here encodes a QR code.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import { Copy, Download, ShieldCheck } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { TwoStepCodeEntry, type TwoStepCodeEntryHandle, type TwoStepCodeKind } from "@/components/TwoStepCodeEntry";
import { OtpCodeInput, type OtpCodeInputHandle } from "@/components/ui/OtpCodeInput";
import { Toggle } from "@/components/ui/Toggle";
import { isCompleteCode } from "@/components/ui/otpCode";
import { isUnreachable } from "@/lib/failureTriage";
import {
  beginTwoFactorSetup,
  cancelTwoFactorSetup,
  disableTwoFactor,
  enableTwoFactor,
  fetchTwoFactorStatus,
  makeNewRecoveryCodes,
  recoveryCodesFileText,
  type TwoFactorSetup,
  type TwoFactorStatus
} from "@/lib/twoFactor";
import { leavingSetupDiscards, twoStepSwitchView, type TwoStepPhase } from "@/lib/twoStepSwitch";
import { ActionGroup } from "@/components/ui/ActionGroup";

/** Which part of the conversation is on screen. */
type Step =
  | { kind: "idle" }
  | { kind: "setup"; setup: TwoFactorSetup }
  | { kind: "codes"; codes: string[] }
  | { kind: "disable" }
  | { kind: "regenerate" };

function messageOf(err: unknown, fallback: string): string {
  if (isUnreachable(err)) return "Couldn't connect. Check your connection and try again.";
  return err instanceof Error ? err.message : fallback;
}

export function TwoStepVerificationCard() {
  const { user, refreshMe } = useAuth();
  const [status, setStatus] = useState<TwoFactorStatus | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [step, setStep] = useState<Step>({ kind: "idle" });
  const [code, setCode] = useState("");
  const [codeKind, setCodeKind] = useState<TwoStepCodeKind>("totp");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [errorKey, setErrorKey] = useState(0);
  const [notice, setNotice] = useState<string | null>(null);
  const setupBox = useRef<OtpCodeInputHandle | null>(null);
  const entryBox = useRef<TwoStepCodeEntryHandle | null>(null);
  /** True while an unconfirmed key is on screen — read by the leave handlers, which outlive renders. */
  const settingUp = useRef(false);

  const load = useCallback(async () => {
    try {
      let answer = await fetchTwoFactorStatus();
      // A setup left over from an earlier visit (or another tab) and never finished: it is Off, and
      // with no setup on THIS screen nobody can finish it, so forget the key now rather than keep it.
      if (!answer.enabled && answer.pending && !settingUp.current) {
        await cancelTwoFactorSetup().catch(() => undefined);
        answer = { ...answer, pending: false };
      }
      setStatus(answer);
      setLoadError(null);
    } catch (err) {
      setLoadError(messageOf(err, "Two-step verification could not be loaded."));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    settingUp.current = step.kind === "setup";
  }, [step]);

  // LEAVING SETUP DISCARDS IT: a reload or a closed tab (`pagehide`, sent with keepalive so it outlives
  // the page), a navigation inside the app (this component unmounting), and coming back to a tab that
  // was hidden for longer than SETUP_AWAY_LIMIT_MS. A shorter hide is a trip to the authenticator app
  // and keeps the key — see `leavingSetupDiscards`.
  useEffect(() => {
    let hiddenAt: number | null = null;
    function onPageHide() {
      if (settingUp.current) void cancelTwoFactorSetup({ keepalive: true }).catch(() => undefined);
    }
    function onVisibility() {
      if (document.visibilityState === "hidden") {
        hiddenAt = Date.now();
        return;
      }
      const away = hiddenAt === null ? 0 : Date.now() - hiddenAt;
      hiddenAt = null;
      if (settingUp.current && leavingSetupDiscards("hidden", away)) {
        settingUp.current = false;
        setStep({ kind: "idle" });
        setCode("");
        setCodeKind("totp");
        setError(null);
        setNotice("Setup was stopped because you were away. Two-step verification is still off. Switch it on to get a new code to scan.");
        void cancelTwoFactorSetup()
          .catch(() => undefined)
          .then(() => load());
      }
    }
    window.addEventListener("pagehide", onPageHide);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      window.removeEventListener("pagehide", onPageHide);
      document.removeEventListener("visibilitychange", onVisibility);
      if (settingUp.current) void cancelTwoFactorSetup({ keepalive: true }).catch(() => undefined);
    };
  }, [load]);

  if (!user) return null;

  function go(next: Step) {
    setStep(next);
    setCode("");
    setCodeKind("totp");
    setError(null);
  }

  function refuse(message: string) {
    setCode("");
    setError(message);
    setErrorKey((key) => key + 1);
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

  async function startSetup() {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      go({ kind: "setup", setup: await beginTwoFactorSetup() });
      requestAnimationFrame(() => setupBox.current?.focus());
    } catch (err) {
      setError(messageOf(err, "Setup could not be started."));
    } finally {
      setBusy(false);
    }
  }

  async function leaveSetup() {
    settingUp.current = false;
    go({ kind: "idle" });
    setNotice("Setup cancelled. Two-step verification is still off.");
    try {
      await cancelTwoFactorSetup();
    } catch {
      // The server forgets an unconfirmed key by itself after 15 minutes, and it never counted as on.
    }
    await load();
  }

  async function submit(entered: string) {
    const typed = entered.trim();
    if (!typed || busy) return;
    if (step.kind !== "disable" || codeKind === "totp") {
      if (!isCompleteCode(typed)) return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (step.kind === "setup") {
        const codes = await enableTwoFactor(typed);
        settingUp.current = false;
        go({ kind: "codes", codes });
        void refreshMe();
      } else if (step.kind === "regenerate") {
        go({ kind: "codes", codes: await makeNewRecoveryCodes(typed) });
      } else if (step.kind === "disable") {
        await disableTwoFactor(typed);
        go({ kind: "idle" });
        setNotice("Two-step verification is off.");
        void refreshMe();
      }
      await load();
    } catch (err) {
      refuse(messageOf(err, "That code didn't work. Try again."));
      requestAnimationFrame(() => (step.kind === "disable" ? entryBox.current?.focus() : setupBox.current?.focus()));
    } finally {
      setBusy(false);
    }
  }

  const phase: TwoStepPhase = step.kind;
  const view = twoStepSwitchView(status, phase);

  function onSwitch(next: boolean) {
    if (next) {
      void startSetup();
    } else if (step.kind === "setup") {
      void leaveSetup();
    } else if (status?.enabled) {
      go({ kind: "disable" });
      requestAnimationFrame(() => entryBox.current?.focus());
    }
  }

  return (
    <section className="panel p-5">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ShieldCheck className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">Two-step verification</h2>
      </div>

      <Toggle
        className="mt-2"
        label="Two-step verification"
        description="After you sign in, also enter a 6-digit code from an authenticator app on your phone, such as Google Authenticator or Microsoft Authenticator."
        checked={view.checked}
        // The switch may be pressed to LEAVE setup or a turn-off — that is the way back — so only a
        // request in flight or a status still loading makes it inert.
        busy={busy || status === null}
        busyLabel={busy ? "Working…" : "Loading…"}
        // "Setting up…" / "Enter a code to turn it off." while a step is open; the plain state otherwise.
        status={view.busy && view.busyLabel ? view.busyLabel : view.statusLine}
        disabled={status === null && loadError !== null}
        onChange={onSwitch}
      />

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

      {status === null && loadError ? (
        <button type="button" className="field-button-secondary mt-3" onClick={() => void load()}>
          Try again
        </button>
      ) : null}

      {status?.enabled && step.kind === "idle" ? (
        <div className="mt-3 grid gap-3">
          {status.recovery_codes_remaining <= 2 ? (
            <p className="rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2 text-sm text-amber-800">
              You are running out of recovery codes. Make new ones so you can still get in without your phone.
            </p>
          ) : null}
          <div>
            <button type="button" className="field-button-secondary" onClick={() => go({ kind: "regenerate" })}>
              Make new recovery codes
            </button>
          </div>
        </div>
      ) : null}

      {step.kind === "setup" ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit(code);
          }}
          className="mt-3 grid gap-4"
        >
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
              <span className="font-medium text-ink-900">2.</span> Enter the 6-digit code the app shows. Two-step
              verification switches on only when this code is checked.
            </li>
          </ol>
          <OtpCodeInput
            ref={setupBox}
            id="two-step-setup-code"
            label="6-digit code"
            hint="Codes change every 30 seconds. Type the one on screen now."
            value={code}
            onChange={setCode}
            onComplete={(full) => void submit(full)}
            error={Boolean(error)}
            errorKey={errorKey}
            disabled={busy}
          />
          <ActionGroup>
            <button type="submit" className="field-button" disabled={busy || !isCompleteCode(code)}>
              {busy ? "Checking…" : "Turn on"}
            </button>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void leaveSetup()}>
              Cancel
            </button>
          </ActionGroup>
        </form>
      ) : null}

      {step.kind === "codes" ? (
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
          <ActionGroup>
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
                go({ kind: "idle" });
                setNotice(null);
              }}
            >
              I&rsquo;ve saved them
            </button>
          </ActionGroup>
        </div>
      ) : null}

      {step.kind === "disable" || step.kind === "regenerate" ? (
        <form
          onSubmit={(event) => {
            event.preventDefault();
            void submit(code);
          }}
          className="mt-3 grid gap-3"
        >
          {step.kind === "disable" ? (
            <TwoStepCodeEntry
              ref={entryBox}
              idPrefix="two-step-disable"
              kind={codeKind}
              onKindChange={setCodeKind}
              value={code}
              onChange={setCode}
              onComplete={(full) => void submit(full)}
              allowRecovery
              totpHint="Enter the code your authenticator app shows now to turn two-step verification off."
              error={Boolean(error)}
              errorKey={errorKey}
              disabled={busy}
            />
          ) : (
            <OtpCodeInput
              ref={setupBox}
              id="two-step-regenerate-code"
              label="6-digit code from your authenticator app"
              hint="Your old recovery codes will stop working."
              value={code}
              onChange={setCode}
              onComplete={(full) => void submit(full)}
              error={Boolean(error)}
              errorKey={errorKey}
              disabled={busy}
              autoFocus
            />
          )}
          <ActionGroup>
            <button
              type="submit"
              className={step.kind === "disable" ? "field-danger rounded-md px-4 py-2 text-sm font-medium" : "field-button"}
              disabled={busy || (codeKind === "totp" || step.kind === "regenerate" ? !isCompleteCode(code) : !code.trim())}
            >
              {busy ? "Checking…" : step.kind === "disable" ? "Turn off" : "Make new codes"}
            </button>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => go({ kind: "idle" })}>
              Cancel
            </button>
          </ActionGroup>
        </form>
      ) : null}
    </section>
  );
}
