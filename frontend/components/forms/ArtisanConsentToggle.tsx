"use client";

/**
 * The artisan's own answer to "may their recordings be sent to an outside service" (transcription,
 * AI summaries, answer drafting). ONE CONSENT STORE (19 Oct 2026): this reads and writes the
 * artisan's row in the `Consent` ledger through `/artisans/{id}/consent` — the same record the Field
 * Repository's artisan form reads and writes, so the two portals cannot disagree. A workshop's
 * answer still speaks for its artisans; an artisan's own refusal stops every send of their
 * recordings in both portals. Saved at once, so it sits outside the form's dirty tracking; shown ON
 * only once the server says so.
 */
import { useEffect, useState } from "react";

import { Toggle } from "@/components/ui/Toggle";
import { ApiError, apiFetch } from "@/lib/api";

export type ArtisanConsent = {
  artisanId: string;
  state: "GRANTED" | "REFUSED" | "NOT_RECORDED";
  source: string | null;
  recordedAt: string | null;
};

export function artisanConsentStatus(consent: Pick<ArtisanConsent, "state" | "source" | "recordedAt">): string {
  if (consent.state !== "GRANTED" && consent.state !== "REFUSED") return "Not asked yet";
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
          description="The artisan agrees their recordings may be sent to a transcription or AI service."
          status={artisanConsentStatus(consent)}
          checked={consent.state === "GRANTED"}
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
