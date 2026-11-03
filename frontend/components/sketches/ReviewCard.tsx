"use client";

import { useCallback, useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import { ChevronDown, CloudOff, Loader2, MessageSquareQuote, Star } from "lucide-react";

import { isUnreachable } from "@/lib/failureTriage";
import { countNoun, formatDate, formatDateTime } from "@/lib/format";

import { fetchSubjectLedger, refusalText, submitOrQueueDesignRating } from "./ratingsApi";
import type { DesignRating, RankedItem, RatingRound, SubjectLedger } from "./reviewRanking";

const SCORES = [1, 2, 3, 4, 5] as const;

function scoreText(score: number | null, count: number): string {
  if (score === null || count === 0) return "Not rated yet";
  return `${score.toFixed(1)} from ${count} ${count === 1 ? "designer" : "designers"}`;
}

type Props = {
  item: RankedItem;
  subtitle: string;
  round: RatingRound;
  openHref: string | null;
  fixedOrder: boolean;
  showPlaced: boolean;
  tentative: string | null;
  onRated: (rating: DesignRating) => void;
};

export function ReviewCard({
  item,
  subtitle,
  round,
  openHref,
  fixedOrder,
  showPlaced,
  tentative,
  onRated
}: Props) {
  const groupName = useId();
  const ledgerId = useId();
  const mine = item.myRating;
  const [score, setScore] = useState<number | null>(mine?.score ?? null);
  const [comment, setComment] = useState(mine?.comment ?? "");
  const [suggestion, setSuggestion] = useState(mine?.suggestion ?? "");
  const [saving, setSaving] = useState(false);
  const savingRef = useRef(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState<{ text: string; queued: boolean } | null>(null);
  const [ledgerOpen, setLedgerOpen] = useState(false);
  const [ledger, setLedger] = useState<SubjectLedger | null>(null);
  const [ledgerProblem, setLedgerProblem] = useState<string | null>(null);
  const [ledgerLoading, setLedgerLoading] = useState(false);

  useEffect(() => {
    if (savingRef.current) return;
    setScore(mine?.score ?? null);
    setComment(mine?.comment ?? "");
    setSuggestion(mine?.suggestion ?? "");
  }, [mine?.score, mine?.comment, mine?.suggestion]);

  const loadLedger = useCallback(async () => {
    setLedgerLoading(true);
    setLedgerProblem(null);
    try {
      setLedger(await fetchSubjectLedger(item.subjectId, round));
    } catch (error) {
      setLedgerProblem(
        isUnreachable(error)
          ? "No connection. Try again."
          : refusalText(error, "Couldn't load the review history.")
      );
    } finally {
      setLedgerLoading(false);
    }
  }, [item.subjectId, round]);

  function toggleLedger() {
    const next = !ledgerOpen;
    setLedgerOpen(next);
    if (next && !ledger && !ledgerLoading) void loadLedger();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (score === null) {
      setProblem("Choose a score from 1 to 5.");
      return;
    }
    setSaving(true);
    savingRef.current = true;
    setProblem(null);
    setSaved(null);
    try {
      const outcome = await submitOrQueueDesignRating(
        {
          subjectId: item.subjectId,
          round,
          score,
          comment: comment.trim() || null,
          suggestion: suggestion.trim() || null
        },
        `Rating · ${item.label || "Untitled piece"}`
      );
      if (outcome.queued) {
        setSaved({
          queued: true,
          text: outcome.sent
            ? "This rating was saved on this device and has now been uploaded. Reload the page to see it in the " +
              "scores on these cards."
            : "This rating is saved on this device but hasn't been uploaded yet. It will keep trying — the sync banner " +
              "at the top of the page lists it and can send it now. The scores on these cards won't change until " +
              "it's uploaded."
        });
      } else {
        onRated(outcome.saved.rating);
        setSaved({
          queued: false,
          text: outcome.saved.replayed
            ? "This rating was already saved, unchanged."
            : mine
              ? "Your rating has been amended."
              : "Your rating has been recorded."
        });
        if (ledgerOpen) void loadLedger();
      }
    } catch (error) {
      setProblem(
        isUnreachable(error)
          ? "Couldn't send or save. Leave private mode or free some space."
          : refusalText(error, "Couldn't save the rating.")
      );
    } finally {
      savingRef.current = false;
      setSaving(false);
    }
  }

  return (
    <article className="panel p-4">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-display text-base font-bold text-ink-900">{item.label || "Untitled piece"}</h3>
            {tentative ? (
              <span className="rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
                {tentative}
              </span>
            ) : null}
          </div>
          {subtitle ? <p className="mt-0.5 text-xs text-ink-muted">{subtitle}</p> : null}
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-500">
            <span className="inline-flex items-center gap-1 font-medium text-ink-700">
              <Star className="h-3.5 w-3.5" aria-hidden />
              {scoreText(item.score, item.ratingCount)}
            </span>
            <span>
              {showPlaced ? `The designers place it ${item.placedPosition} · ` : ""}
              scores put it {item.defaultPosition}
              {fixedOrder ? "" : " (this list is in score order)"}
            </span>
          </p>
        </div>
        {openHref ? (
          <Link href={openHref} className="field-button-secondary shrink-0">
            Open the record
          </Link>
        ) : null}
      </header>

      <form onSubmit={submit} className="mt-4 grid gap-3 border-t border-line-200 pt-4">
        <fieldset className="grid gap-2">
          <legend className="field-label">Your score for this piece</legend>
          <div className="flex flex-wrap items-center gap-2">
            {SCORES.map((value) => {
              const active = score === value;
              return (
                <label
                  key={value}
                  className={
                    active
                      ? "inline-flex cursor-pointer items-center gap-2 rounded-md border border-purple-700 bg-purple-700 px-3 py-1.5 text-sm font-semibold text-white focus-within:ring-2 focus-within:ring-purple-700/40 focus-within:ring-offset-2"
                      : "inline-flex cursor-pointer items-center gap-2 rounded-md border border-line-200 bg-card px-3 py-1.5 text-sm font-medium text-ink-700 hover:border-purple-300 hover:bg-purple-50 focus-within:border-purple-700 focus-within:ring-2 focus-within:ring-purple-700/25"
                  }
                >
                  <input
                    type="radio"
                    name={groupName}
                    value={value}
                    checked={active}
                    onChange={() => setScore(value)}
                    className="sr-only"
                  />
                  {value}
                  {active ? <span className="text-[11px] font-normal">chosen</span> : null}
                </label>
              );
            })}
          </div>
        </fieldset>

        <label className="grid gap-1">
          <span className="field-label">What you think of it</span>
          <textarea
            className="field-input min-h-18"
            value={comment}
            maxLength={4000}
            onChange={(event) => setComment(event.target.value)}
            placeholder="Your assessment of the piece as it stands."
          />
        </label>

        <label className="grid gap-1">
          <span className="field-label">What you would change</span>
          <textarea
            className="field-input min-h-18"
            value={suggestion}
            maxLength={4000}
            onChange={(event) => setSuggestion(event.target.value)}
            placeholder="A suggestion for the maker"
          />
        </label>

        <p className="text-xs leading-5 text-ink-500">
          Reviews aren&apos;t printed; the report uses only the final order.
        </p>

        {problem ? (
          <p className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{problem}</p>
        ) : null}
        {saved ? (
          <p
            className={
              saved.queued
                ? "flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-sm text-amber-800"
                : "rounded-md border border-line-200 bg-success-100 px-3 py-2 text-sm text-success-600"
            }
          >
            {saved.queued ? <CloudOff className="mt-0.5 h-4 w-4 shrink-0" aria-hidden /> : null}
            <span>{saved.text}</span>
          </p>
        ) : null}

        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" className="field-button" disabled={saving}>
            {saving ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden /> : null}
            {mine ? "Amend my rating" : "Submit my rating"}
          </button>
          {mine ? (
            <span className="text-xs text-ink-muted">
              You rated this {mine.score} on {formatDate(mine.ratedAt ?? mine.createdAt)}.
            </span>
          ) : null}
        </div>
      </form>

      <div className="mt-4 border-t border-line-200 pt-3">
        <button
          type="button"
          className="inline-flex items-center gap-2 text-sm font-medium text-ink-700"
          aria-expanded={ledgerOpen}
          aria-controls={ledgerOpen ? ledgerId : undefined}
          onClick={toggleLedger}
        >
          <MessageSquareQuote className="h-4 w-4" aria-hidden />
          Who rated this, when and how
          <ChevronDown className={ledgerOpen ? "h-4 w-4 rotate-180" : "h-4 w-4"} aria-hidden />
        </button>
        {ledgerOpen ? (
          <div id={ledgerId} className="mt-3 grid gap-2">
            {ledgerLoading ? <p className="text-sm text-ink-muted">Reading the review history…</p> : null}
            {ledgerProblem ? <p className="text-sm text-error-600">{ledgerProblem}</p> : null}
            {ledger ? <Ledger ledger={ledger} /> : null}
          </div>
        ) : null}
      </div>
    </article>
  );
}

function Ledger({ ledger }: { ledger: SubjectLedger }) {
  if (ledger.ratings.length === 0) {
    return (
      <p className="text-sm text-ink-muted">
        {ledger.summary.ratingCount === 0
          ? "Nobody has rated this piece yet."
          : ledger.canReadLedger
            ? "No ratings were found for this round."
            : `${countNoun(ledger.summary.ratingCount, "rating")}, anonymous.`}
      </p>
    );
  }
  return (
    <>
      {!ledger.namesShown ? (
        <p className="text-xs text-ink-muted">
          Reviewers&apos; names aren&apos;t shown for this round.
        </p>
      ) : null}
      <ul className="grid gap-2">
        {ledger.ratings.map((rating) => {
          const judged = rating.ratedAt;
          const heard = rating.createdAt;
          return (
            <li key={rating.id} className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm">
              <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-ink-700">
                <span className="font-semibold text-ink-900">{rating.score ?? "—"}/5</span>
                <span className="text-ink-muted">
                  {rating.mine
                    ? "your rating"
                    : rating.reviewerId
                      ? `reviewer ${rating.reviewerId}`
                      : "reviewer not named"}
                </span>
              </p>
              <p className="mt-0.5 text-xs text-ink-muted">
                Judged {formatDate(judged ?? heard)}
                {judged && heard && judged.slice(0, 10) !== heard.slice(0, 10)
                  ? ` · uploaded ${formatDateTime(heard)}`
                  : ""}
              </p>
              {rating.comment ? <p className="mt-1 whitespace-pre-wrap text-ink-700">{rating.comment}</p> : null}
              {rating.suggestion ? (
                <p className="mt-1 whitespace-pre-wrap text-ink-700">
                  <span className="field-label">Suggested</span> {rating.suggestion}
                </p>
              ) : null}
            </li>
          );
        })}
      </ul>
    </>
  );
}
