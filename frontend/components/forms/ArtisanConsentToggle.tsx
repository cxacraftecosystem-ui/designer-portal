"use client";

import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError, apiFetch } from "@/lib/api";

export type ArtisanConsent = {
  artisanId: string;
  state: "GRANTED" | "REFUSED" | "NOT_RECORDED";
  source: string | null;
  recordedAt: string | null;
};

export const ARTISAN_CONSENT_DESCRIPTION =
  "On by default. Switch off if the artisan does not want their recordings sent to a transcription or AI service.";

export function artisanConsentStatus(consent: Pick<ArtisanConsent, "state" | "source" | "recordedAt">): string {
  if (consent.state !== "GRANTED" && consent.state !== "REFUSED") return "On by default";
  const when = consent.recordedAt
    ? new Date(consent.recordedAt).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })
    : null;
  const head = consent.state === "GRANTED" ? "Agreed" : "Declined";
  const by = consent.source === "owner attestation" ? " · confirmed by the owner" : "";
  return `${head}${when ? ` on ${when}` : ""}${by}`;
}

export function ArtisanConsentToggle({ artisanId }: { artisanId: string }) {
  const [consent, setConsent] = useState<ArtisanConsent | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const path = `/artisans/${encodeURIComponent(artisanId)}/consent`;

  useEffect(() => {
    let cancelled = false;
    apiFetch<ArtisanConsent>(path)
      .then((answer) => {
        if (!cancelled) setConsent(answer);
      })
      .catch(() => {
        if (!cancelled) setError("Couldn't load recording consent.");
      });
    return () => {
      cancelled = true;
    };
  }, [path]);

  async function change(next: boolean) {
    setBusy(true);
    setError(null);
    try {
      setConsent(
        await apiFetch<ArtisanConsent>(path, {
          method: "POST",
          body: JSON.stringify({ state: next ? "GRANTED" : "REFUSED" })
        })
      );
    } catch (err) {
      const detail = err instanceof ApiError ? (err.payload as { detail?: unknown } | undefined)?.detail : null;
      setError(typeof detail === "string" ? detail : "Couldn't save. Try again.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid gap-1 md:col-span-3">
      {consent ? (
        <Toggle
          label="Recordings may be transcribed"
          description={ARTISAN_CONSENT_DESCRIPTION}
          status={artisanConsentStatus(consent)}
          checked={consent.state !== "REFUSED"}
          busy={busy}
          busyLabel="Saving…"
          onChange={(next) => void change(next)}
        />
      ) : null}
      {error ? (
        <p role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
    </div>
  );
}
