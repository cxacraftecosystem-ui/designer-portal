"use client";

/**
 * "Verify with Aadhaar" — for the signed-in account (Settings) and for an artisan record (its edit
 * page). One component, one flow, the handset's flow (`android/.../ui/aadhaar/AadhaarScreens.kt`):
 *
 *   status → consent → method → the method's form → status again.
 *
 * RENDERS NOTHING while the server has both Aadhaar features switched off (`aadhaarFeaturesOf`), and
 * offers only the methods that are on. Consent is asked EVERY time, before any picture is read or
 * file is chosen, and is not remembered: the server refuses a request without it.
 *
 * Nothing the person provides is kept in the browser: the picture is decoded in memory, the ZIP is
 * base64'd in memory, and the number and the code typed for an OTP live in component state until
 * the panel closes. None of it goes near localStorage, IndexedDB or the offline outbox.
 */

import { useCallback, useEffect, useId, useState } from "react";
import { BadgeCheck, FileArchive, KeyRound, QrCode, ShieldAlert, ShieldCheck } from "lucide-react";

import { OtpCodeInput } from "@/components/ui/OtpCodeInput";
import { isCompleteCode } from "@/components/ui/otpCode";
import { useAuth } from "@/components/AuthProvider";
import {
  type AadhaarSubject,
  type AadhaarSummary,
  type OtpIssued,
  aadhaarEnabled,
  aadhaarErrorMessage,
  aadhaarFeaturesOf,
  consentPoints,
  consentStatement,
  decideAadhaar,
  fileToBase64,
  METHOD_LABEL,
  readAadhaar,
  requestOtp,
  STATUS_LABEL,
  verifyOfflineXml,
  verifyOtp,
  verifySecureQr,
  withdrawAadhaar
} from "@/lib/aadhaar";
import { readSecureQrFromImage } from "@/lib/aadhaarQrImage";
import { isAdmin } from "@/lib/permissions";
import { ActionGroup } from "@/components/ui/ActionGroup";

type Step = "status" | "consent" | "method" | "qr" | "zip" | "otp";

const STATUS_TONE: Record<string, string> = {
  VERIFIED: "border-success-600/25 bg-success-100 text-success-600",
  NAME_MISMATCH: "border-amber-500/30 bg-amber-100 text-amber-800",
  REJECTED: "border-error-600/30 bg-error-100 text-red-800"
};

export function AadhaarStatusChip({ summary }: { summary: Pick<AadhaarSummary, "status"> }) {
  if (!summary.status) return null;
  const Icon = summary.status === "VERIFIED" ? BadgeCheck : ShieldAlert;
  return (
    <span
      className={`inline-flex w-fit items-center gap-1 rounded-full border px-2.5 py-1 text-xs font-medium ${STATUS_TONE[summary.status]}`}
    >
      <Icon className="h-3.5 w-3.5" aria-hidden />
      {STATUS_LABEL[summary.status]}
    </span>
  );
}

function formatDay(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const day = new Date(iso);
  return Number.isNaN(day.getTime())
    ? null
    : day.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function AadhaarVerifyPanel({
  subject,
  recordName
}: {
  subject: AadhaarSubject;
  /** The artisan's name, for the heading; the account's own panel needs none. */
  recordName?: string;
}) {
  const { user, refreshMe } = useAuth();
  const features = aadhaarFeaturesOf(user);
  const headingId = useId();
  const [summary, setSummary] = useState<AadhaarSummary | null>(null);
  const [step, setStep] = useState<Step>("status");
  const [agreed, setAgreed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // The offline e-KYC form.
  const [zip, setZip] = useState<File | null>(null);
  const [shareCode, setShareCode] = useState("");
  const [mobile, setMobile] = useState("");
  const [email, setEmail] = useState("");
  // The OTP form. The number never leaves this component's state, and it is cleared on every exit.
  const [uid, setUid] = useState("");
  const [otp, setOtp] = useState("");
  const [issued, setIssued] = useState<OtpIssued | null>(null);

  const enabled = aadhaarEnabled(features);
  const subjectKey = subject.kind === "self" ? "self" : subject.id;

  const load = useCallback(async () => {
    try {
      setSummary(await readAadhaar(subject));
    } catch {
      setSummary(null);
    }
    // `subject` is rebuilt by every parent render; its identity is `subjectKey`.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subjectKey]);

  useEffect(() => {
    if (enabled) void load();
  }, [enabled, load]);

  if (!enabled) return null;

  function reset(next: Step = "status") {
    setStep(next);
    setAgreed(false);
    setZip(null);
    setShareCode("");
    setMobile("");
    setEmail("");
    setUid("");
    setOtp("");
    setIssued(null);
  }

  async function finish(result: AadhaarSummary) {
    setSummary(result);
    reset();
    setNotice(
      result.status === "VERIFIED"
        ? "Verified with Aadhaar."
        : "Signature valid, but the name differs. An admin will review it."
    );
    if (subject.kind === "self") await refreshMe();
  }

  async function run(action: () => Promise<AadhaarSummary>, fallback: string) {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await finish(await action());
    } catch (err) {
      setError(aadhaarErrorMessage(err, fallback));
    } finally {
      setBusy(false);
    }
  }

  async function onPicture(file: File | undefined) {
    if (!file) return;
    setBusy(true);
    setError(null);
    let text: string | null = null;
    try {
      text = await readSecureQrFromImage(file);
    } catch {
      text = null;
    }
    setBusy(false);
    if (!text) {
      setError(
        "No Aadhaar QR code found. Use a sharp, straight-on picture of the large QR code."
      );
      return;
    }
    await run(() => verifySecureQr(subject, text), "Couldn't verify the QR code. Try again.");
  }

  async function sendOtp() {
    setBusy(true);
    setError(null);
    try {
      setIssued(await requestOtp(subject, uid));
    } catch (err) {
      setError(aadhaarErrorMessage(err, "The code could not be sent. Try again."));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    setBusy(true);
    setError(null);
    try {
      setSummary(await withdrawAadhaar(subject));
      setNotice("The Aadhaar verification was removed.");
      if (subject.kind === "self") await refreshMe();
    } catch (err) {
      setError(aadhaarErrorMessage(err, "It could not be removed. Try again."));
    } finally {
      setBusy(false);
    }
  }

  async function decide(accept: boolean) {
    if (subject.kind !== "artisan") return;
    await run(() => decideAadhaar({ kind: "artisan", id: subject.id }, accept), "The decision could not be saved.");
    setNotice(accept ? "Accepted: the artisan is verified." : "Not accepted.");
  }

  const methods: { key: Step; label: string; note: string; icon: typeof QrCode }[] = [];
  if (features.offline) {
    methods.push({
      key: "qr",
      label: "A picture of the Aadhaar QR code",
      note: "The large QR code on the letter, card or e-Aadhaar.",
      icon: QrCode
    });
    methods.push({
      key: "zip",
      label: "The offline e-KYC file",
      note: "The ZIP from UIDAI and its share code.",
      icon: FileArchive
    });
  }
  if (features.online) {
    methods.push({
      key: "otp",
      label: "A one-time code from UIDAI",
      note: "Sent to the Aadhaar-linked mobile.",
      icon: KeyRound
    });
  }

  const title = subject.kind === "self" ? "Aadhaar verification" : `Aadhaar verification${recordName ? ` — ${recordName}` : ""}`;
  const mismatch = summary?.status === "NAME_MISMATCH";

  return (
    <section className="panel p-5" aria-labelledby={headingId}>
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <ShieldCheck className="h-4 w-4" aria-hidden />
        </span>
        <h2 id={headingId} className="font-display font-bold text-ink-900">
          {title}
        </h2>
      </div>

      {step === "status" ? (
        <div className="mt-3 grid gap-3">
          {summary?.status ? (
            <div className="grid gap-1 text-sm text-ink-700">
              <AadhaarStatusChip summary={summary} />
              <span>
                {summary.method ? METHOD_LABEL[summary.method] : null}
                {formatDay(summary.verifiedAt) ? ` · ${formatDay(summary.verifiedAt)}` : null}
                {summary.last4 ? ` · Aadhaar ending ${summary.last4}` : null}
              </span>
              {summary.name ? <span>Name as UIDAI holds it: {summary.name}</span> : null}
              {mismatch && summary.recordName ? (
                <span className="text-amber-800">Name on this record: {summary.recordName}</span>
              ) : null}
            </div>
          ) : (
            <p className="text-sm leading-6 text-ink-700">
              {subject.kind === "self"
                ? "Verify this account with your Aadhaar."
                : "Verify this artisan with their Aadhaar."}
            </p>
          )}
          <ActionGroup>
            <button type="button" className="field-button" disabled={busy} onClick={() => setStep("consent")}>
              <ShieldCheck className="h-4 w-4" aria-hidden />
              {summary?.status ? "Verify again" : "Verify with Aadhaar"}
            </button>
            {summary?.status ? (
              <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void remove()}>
                Remove verification
              </button>
            ) : null}
            {mismatch && subject.kind === "artisan" && isAdmin(user) ? (
              <>
                <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void decide(true)}>
                  Accept as the same person
                </button>
                <button type="button" className="field-button-secondary" disabled={busy} onClick={() => void decide(false)}>
                  Do not accept
                </button>
              </>
            ) : null}
          </ActionGroup>
        </div>
      ) : null}

      {step === "consent" ? (
        <div className="mt-3 grid gap-3">
          <p className="text-sm font-medium text-ink-900">Before you continue</p>
          <ul className="grid list-disc gap-1.5 pl-5 text-sm leading-6 text-ink-700">
            {consentPoints(subject).map((point) => (
              <li key={point}>{point}</li>
            ))}
          </ul>
          <label className="flex items-start gap-2 text-sm text-ink-900">
            <input
              type="checkbox"
              className="mt-1 h-4 w-4 accent-purple-700"
              checked={agreed}
              onChange={(event) => setAgreed(event.target.checked)}
            />
            <span>{consentStatement(subject)}</span>
          </label>
          <ActionGroup>
            <button type="button" className="field-button" disabled={!agreed} onClick={() => setStep("method")}>
              Continue
            </button>
            <button type="button" className="field-button-secondary" onClick={() => reset()}>
              Cancel
            </button>
          </ActionGroup>
        </div>
      ) : null}

      {step === "method" ? (
        <div className="mt-3 grid gap-2">
          <p className="text-sm font-medium text-ink-900">How would you like to verify?</p>
          {methods.map((method) => (
            <button
              key={method.key}
              type="button"
              className="flex items-start gap-3 rounded-md border border-line-200 bg-card p-3 text-left transition hover:border-purple-300 hover:bg-purple-50"
              onClick={() => setStep(method.key)}
            >
              <method.icon className="mt-0.5 h-5 w-5 text-purple-700 dark:text-purple-300" aria-hidden />
              <span className="grid gap-0.5">
                <span className="text-sm font-medium text-ink-900">{method.label}</span>
                <span className="text-xs leading-5 text-ink-500">{method.note}</span>
              </span>
            </button>
          ))}
          <div>
            <button type="button" className="field-button-secondary" onClick={() => reset()}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      {step === "qr" ? (
        <div className="mt-3 grid gap-3">
          <p className="text-sm leading-6 text-ink-700">
            Read on this device; only the code is sent.
          </p>
          <label className={`file-trigger ${busy ? "pointer-events-none opacity-60" : ""}`}>
            <QrCode className="h-4 w-4" aria-hidden />
            {busy ? "Reading…" : "Choose a picture"}
            <input
              className="hidden"
              type="file"
              accept="image/*"
              disabled={busy}
              onChange={(event) => {
                const file = event.target.files?.[0];
                event.target.value = "";
                void onPicture(file);
              }}
            />
          </label>
          <div>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => reset()}>
              Cancel
            </button>
          </div>
        </div>
      ) : null}

      {step === "zip" ? (
        <form
          className="mt-3 grid gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            if (!zip) return;
            void run(
              async () => verifyOfflineXml(subject, await fileToBase64(zip), shareCode, { mobile, email }),
              "The file could not be verified. Try again."
            );
          }}
        >
          <label className="file-trigger">
            <FileArchive className="h-4 w-4" aria-hidden />
            {zip ? zip.name : "Choose the ZIP file"}
            <input
              className="hidden"
              type="file"
              accept=".zip,application/zip"
              onChange={(event) => setZip(event.target.files?.[0] ?? null)}
            />
          </label>
          <label className="grid gap-1">
            <span className="field-label">Share code</span>
            <input
              className="field-input"
              value={shareCode}
              autoComplete="off"
              onChange={(event) => setShareCode(event.target.value)}
              required
            />
          </label>
          <p className="text-xs leading-5 text-ink-500">
            Optional: the linked mobile or email, to check too. Not kept.
          </p>
          <div className="grid gap-3 md:grid-cols-2">
            <label className="grid gap-1">
              <span className="field-label">Mobile number (optional)</span>
              <input className="field-input" inputMode="tel" autoComplete="off" value={mobile} onChange={(event) => setMobile(event.target.value)} />
            </label>
            <label className="grid gap-1">
              <span className="field-label">E-mail (optional)</span>
              <input className="field-input" type="email" autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} />
            </label>
          </div>
          <ActionGroup>
            <button type="submit" className="field-button" disabled={busy || !zip || !shareCode.trim()}>
              {busy ? "Checking…" : "Verify"}
            </button>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => reset()}>
              Cancel
            </button>
          </ActionGroup>
        </form>
      ) : null}

      {step === "otp" ? (
        <form
          className="mt-3 grid gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            if (!issued) {
              void sendOtp();
              return;
            }
            void run(() => verifyOtp(subject, uid, otp, issued), "The code could not be checked. Try again.");
          }}
        >
          <label className="grid gap-1">
            <span className="field-label">Virtual ID (VID) or Aadhaar number</span>
            <input
              className="field-input"
              inputMode="numeric"
              autoComplete="off"
              value={uid}
              disabled={!!issued}
              onChange={(event) => setUid(event.target.value)}
              required
            />
            <span className="text-xs leading-5 text-ink-500">
              A 16-digit Virtual ID keeps the number private. Not kept.
            </span>
          </label>
          {issued ? (
            // The owner's six boxes (OtpCodeInput): digits only, paste fills all six. Not submitted on
            // completion here — the Verify button below is the act, as on the rest of this panel.
            <OtpCodeInput
              id="aadhaar-otp"
              label="The six-digit code UIDAI sent"
              value={otp}
              onChange={setOtp}
              disabled={busy}
              autoFocus
            />
          ) : null}
          <ActionGroup>
            <button type="submit" className="field-button" disabled={busy || !uid.trim() || (!!issued && !isCompleteCode(otp))}>
              {busy ? "Please wait…" : issued ? "Verify" : "Send the code"}
            </button>
            <button type="button" className="field-button-secondary" disabled={busy} onClick={() => reset()}>
              Cancel
            </button>
          </ActionGroup>
        </form>
      ) : null}

      <p className="mt-3 text-sm leading-6" role="status" aria-live="polite">
        {error ? <span className="text-error-600">{error}</span> : notice ? <span className="text-ink-700">{notice}</span> : null}
      </p>
    </section>
  );
}
