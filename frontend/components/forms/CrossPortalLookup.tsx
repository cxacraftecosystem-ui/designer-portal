"use client";

/**
 * C27 — "Find in the Field Repository" on the artisan form, and the link it leaves behind.
 *
 * Draws NOTHING until the server says the feature is on (`crossPortalEnabled`). New artisan: read
 * the Aadhaar or Pehchan number already typed above, ask the designer to confirm the artisan agreed
 * to share, look them up, and offer one "Fill the form" press. Saved artisan: show the link, and
 * offer Refresh (the Field Repository is the source of truth for the identity fields) and Unlink.
 * The consent tick is an AGREEMENT, not a setting, so it is a checkbox (§11.11), and it names every
 * item the lookup shares, from the server's list (`consentLabel`). Unlink asks first.
 */
import Link from "next/link";
import { useEffect, useId, useState } from "react";

import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { ApiError } from "@/lib/api";
import { cardShortCode, parseArtisanCard } from "@/lib/artisanCard";
import type { Artisan } from "@/lib/types";
import {
  consentLabel,
  crossPortalStatus,
  SHARED_FIELDS_FALLBACK,
  getCrossPortalLink,
  linkToFieldRepository,
  lookupByCard,
  lookupInFieldRepository,
  matchSummary,
  refreshFromFieldRepository,
  unlinkFromFieldRepository,
  type CrossPortalLink,
  type CrossPortalMatch
} from "@/lib/crossPortal";

function message(error: unknown, fallback: string): string {
  if (error instanceof ApiError) {
    const detail = (error.payload as { detail?: unknown } | undefined)?.detail;
    if (typeof detail === "string" && detail.trim()) return detail;
  }
  return fallback;
}

/** "F-01234-56789-S5" for a scanned payload or a typed code. */
function cardLabel(input: string): string {
  const parsed = parseArtisanCard(input);
  return parsed.ok ? cardShortCode(parsed.card.issuer, parsed.card.code) : input.trim().toUpperCase();
}

/** Whether the server has the feature on, and the consent tick's words. */
export function useCrossPortal(): { enabled: boolean; consent: string } {
  const [status, setStatus] = useState({ enabled: false, consent: consentLabel(SHARED_FIELDS_FALLBACK) });
  useEffect(() => {
    let cancelled = false;
    void crossPortalStatus().then((answer) => {
      if (!cancelled) setStatus({ enabled: answer.enabled, consent: consentLabel(answer.sharedFields) });
    });
    return () => {
      cancelled = true;
    };
  }, []);
  return status;
}

export function useCrossPortalEnabled(): boolean {
  return useCrossPortal().enabled;
}

export const UNLINK_CONFIRM = {
  title: "Unlink from the Field Repository?",
  body: "Refresh stops. What is already filled in stays.",
  confirmLabel: "Unlink",
  tone: "danger" as const
};

/** The new-artisan half: find, then fill. */
export function CrossPortalFind({
  readNumbers,
  onFill,
  filled,
  cardInput = null
}: {
  /** A Field Repository card scanned or typed elsewhere: the lookup asks by the card, not the numbers. */
  cardInput?: string | null;
  /** The Aadhaar digits and Pehchan number as typed on the form right now. */
  readNumbers: () => { aadhaarNumber: string | null; pehchanCardNumber: string | null };
  onFill: (match: CrossPortalMatch) => void;
  /** The match already filled in, if any — the form then says where its values came from. */
  filled: CrossPortalMatch | null;
}) {
  const { enabled, consent: consentText } = useCrossPortal();
  const consentId = useId();
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [match, setMatch] = useState<CrossPortalMatch | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [existingId, setExistingId] = useState<string | null>(null);

  if (!enabled) return null;

  if (filled) {
    return (
      <div className="md:col-span-3 rounded-md border border-purple-200 bg-purple-50 px-3 py-2 text-sm text-purple-900 dark:border-purple-800 dark:bg-purple-950/40 dark:text-purple-100">
        Filled from the Field Repository: {matchSummary(filled)}. Check it, then save.
      </div>
    );
  }

  async function find() {
    setNote(null);
    setMatch(null);
    setExistingId(null);
    if (cardInput) {
      setBusy(true);
      try {
        const answer = await lookupByCard(cardInput);
        if (answer.existingArtisanId) {
          setExistingId(answer.existingArtisanId);
          setNote("This artisan is already here.");
        } else if (answer.match) setMatch(answer.match);
        else setNote("Not found in the Field Repository, or not shared by the artisan.");
      } catch (error) {
        setNote(message(error, "The Field Repository can't be reached. Fill the form yourself."));
      } finally {
        setBusy(false);
      }
      return;
    }
    const numbers = readNumbers();
    if (!numbers.aadhaarNumber && !numbers.pehchanCardNumber) {
      setNote("Enter the Aadhaar or Pehchan number first.");
      return;
    }
    setBusy(true);
    try {
      const answer = await lookupInFieldRepository(numbers);
      if (answer.match) setMatch(answer.match);
      else setNote("Not found in the Field Repository, or not shared by the artisan.");
    } catch (error) {
      setNote(message(error, "The Field Repository can't be reached. Fill the form yourself."));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="md:col-span-3 grid gap-2 rounded-md border border-line-200 bg-card p-3">
      <p className="text-sm font-medium text-ink-900">
        {cardInput ? `Field Repository card ${cardLabel(cardInput)}` : "Find in the Field Repository"}
      </p>
      <label htmlFor={consentId} className="flex items-start gap-2 text-sm text-ink-700">
        <input
          id={consentId}
          type="checkbox"
          className="mt-0.5 h-4 w-4 accent-purple-700"
          checked={consent}
          onChange={(event) => setConsent(event.target.checked)}
        />
        <span>{consentText}</span>
      </label>
      {match ? (
        <div className="grid gap-2">
          <p className="text-sm text-ink-900">Found: {matchSummary(match)}</p>
          <ActionGroup align="start">
            <button type="button" className="field-button" onClick={() => onFill(match)}>
              Fill the form
            </button>
            <button type="button" className="field-button-secondary" onClick={() => setMatch(null)}>
              Not this person
            </button>
          </ActionGroup>
        </div>
      ) : (
        <ActionGroup align="start">
          <button
            type="button"
            className="field-button-secondary"
            disabled={!consent || busy}
            onClick={() => void find()}
          >
            {busy ? "Looking…" : "Find"}
          </button>
        </ActionGroup>
      )}
      {note ? (
        <p role="status" className="text-xs text-ink-muted">
          {note}
          {existingId ? (
            <>
              {" "}
              <Link href={`/artisans/${encodeURIComponent(existingId)}`} className="font-medium underline">
                Open the artisan
              </Link>
            </>
          ) : null}
        </p>
      ) : null}
    </div>
  );
}

/** The saved-artisan half: the link, Refresh and Unlink — or Link, for an artisan saved before. */
export function CrossPortalLinkPanel({
  artisanId,
  onRefreshed
}: {
  artisanId: string;
  /**
   * After a refresh changed fields: the artisan as the server now holds it, and which identity
   * fields moved. The form MUST take these values, or its next Save writes the old ones back.
   */
  onRefreshed: (artisan: Artisan | null, changed: string[]) => void;
}) {
  const { enabled, consent: consentText } = useCrossPortal();
  const confirm = useConfirm();
  const consentId = useId();
  const [link, setLink] = useState<CrossPortalLink | null>(null);
  const [consent, setConsent] = useState(false);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);

  useEffect(() => {
    if (!enabled) return;
    let cancelled = false;
    getCrossPortalLink(artisanId)
      .then((answer) => {
        if (!cancelled) setLink(answer);
      })
      .catch(() => {
        if (!cancelled) setLink({ linked: false });
      });
    return () => {
      cancelled = true;
    };
  }, [artisanId, enabled]);

  if (!enabled || !link) return null;

  async function run(action: () => Promise<void>, failure: string) {
    setBusy(true);
    setNote(null);
    try {
      await action();
    } catch (error) {
      setNote(message(error, failure));
    } finally {
      setBusy(false);
    }
  }

  if (!link.linked) {
    return (
      <div className="md:col-span-3 grid gap-2 rounded-md border border-line-200 bg-card p-3">
        <p className="text-sm font-medium text-ink-900">Field Repository</p>
        <label htmlFor={consentId} className="flex items-start gap-2 text-sm text-ink-700">
          <input
            id={consentId}
            type="checkbox"
            className="mt-0.5 h-4 w-4 accent-purple-700"
            checked={consent}
            onChange={(event) => setConsent(event.target.checked)}
          />
          <span>{consentText}</span>
        </label>
        <ActionGroup align="start">
          <button
            type="button"
            className="field-button-secondary"
            disabled={!consent || busy}
            onClick={() =>
              void run(async () => setLink(await linkToFieldRepository(artisanId)), "Couldn't link. Try again.")
            }
          >
            {busy ? "Linking…" : "Link"}
          </button>
        </ActionGroup>
        {note ? (
          <p role="status" className="text-xs text-ink-muted">
            {note}
          </p>
        ) : null}
      </div>
    );
  }

  return (
    <div className="md:col-span-3 grid gap-2 rounded-md border border-purple-200 bg-purple-50 p-3 dark:border-purple-800 dark:bg-purple-950/40">
      <p className="text-sm text-purple-900 dark:text-purple-100">
        Linked to the Field Repository. Name, gender, date of birth and Pehchan come from there.
      </p>
      <ActionGroup align="start">
        <button
          type="button"
          className="field-button-secondary"
          disabled={busy}
          onClick={() =>
            void run(async () => {
              const answer = await refreshFromFieldRepository(artisanId);
              setLink(answer.link);
              setNote(answer.changed.length ? "Updated from the Field Repository." : "Already up to date.");
              if (answer.changed.length) onRefreshed(answer.artisan ?? null, answer.changed);
            }, "Couldn't refresh. Try again.")
          }
        >
          Refresh
        </button>
        <button
          type="button"
          data-danger
          className="field-danger inline-flex min-h-10 items-center justify-center rounded-md px-4 py-2 text-sm font-medium"
          disabled={busy}
          onClick={() =>
            void (async () => {
              if (!(await confirm(UNLINK_CONFIRM))) return;
              await run(async () => setLink(await unlinkFromFieldRepository(artisanId)), "Couldn't unlink. Try again.");
            })()
          }
        >
          Unlink
        </button>
      </ActionGroup>
      {note ? (
        <p role="status" className="text-xs text-ink-muted">
          {note}
        </p>
      ) : null}
    </div>
  );
}
