"use client";

/**
 * One piece under review: what it is, what it scored, what this designer thinks of it, and — for
 * the people entitled to it — who else has judged it, when and how.
 *
 * ── THE QUANTITATIVE AND THE QUALITATIVE ARE TWO CONTROLS, NOT ONE BOX ──────────────────────────
 *
 * The owner asked for both, and the server keeps them apart for a reason its own schema states: an
 * assessment and a proposed change are different speech acts with different readers, and collapsed
 * into one box the suggestions are unfindable inside the prose. So this card has a score (1–5, the
 * bounds the API enforces), a comment, and a separate suggestion — the same three columns the
 * ledger row has.
 *
 * ── THE SCORE IS A RADIO GROUP AND NOT A ROW OF STARS ───────────────────────────────────────────
 *
 * Five real radios in a `fieldset` with a `legend`: they arrive in the tab order once, arrow-key
 * between themselves, announce "3 of 5" without a label being invented for them, and submit
 * through the form like anything else. A star strip is a row of buttons that has to reimplement
 * every one of those, and the number is what the ranking is actually computed from — so the
 * control a designer uses should be the number they are choosing.
 *
 * ── A JUDGEMENT MADE WITH NO SIGNAL IS KEPT, NOT REFUSED ────────────────────────────────────────
 *
 * Submitting goes through `submitOrQueueDesignRating`, so a request that never reaches the server
 * becomes a durable entry in the offline outbox and delivers itself later. That is new: this form
 * used to be the one surface in the sketches and prototypes feature whose value had no persistence
 * path at all — the network was tried, the refusal was stated honestly, and the paragraph of
 * qualitative feedback lived only in these two textareas until the tab closed. The score, the
 * comment and the suggestion are now as durable as the sketch they are about.
 *
 * WHAT IS DELIBERATELY NOT DONE with a queued rating is anything to the numbers on screen; see the
 * comment in `submit`. The ranking is the repository's, and a card must not show a score it holds
 * against a piece the repository has never been told about.
 *
 * ── WHAT THIS CARD MAY SHOW IS DECIDED BY THE SERVER, NOT HERE ──────────────────────────────────
 *
 * The ledger disclosure renders `ratings[]` exactly as `GET /design-ratings/subjects/{id}` returns
 * it. Admins and master admins get every row with a reviewer on it; a designer gets every row on
 * their OWN record; everybody else gets the aggregate and their own row and no other row is in the
 * response at all. **No column is hidden here.** Hiding a column in a client is not a control, and
 * a card that filtered rows would be a second, weaker opinion about a rule the server already
 * enforces — the kind that goes stale the first time the server's rule changes.
 *
 * `canReadLedger: false` with a populated `summary` is NOT a refusal and is not drawn as one. It is
 * "you can see the score, not the scorers", which is a legitimate and common state for a peer in a
 * round, and the server sends the flag precisely so this card does not have to guess whether an
 * empty list means "nobody rated" or "not yours to see".
 */

import { useCallback, useEffect, useId, useRef, useState } from "react";
import Link from "next/link";
import { ChevronDown, CloudOff, Loader2, MessageSquareQuote, Star } from "lucide-react";

import { isUnreachable } from "@/lib/failureTriage";
import { formatDate, formatDateTime } from "@/lib/format";

import { fetchSubjectLedger, refusalText, submitOrQueueDesignRating } from "./ratingsApi";
import type { DesignRating, RankedItem, RatingRound, SubjectLedger } from "./reviewRanking";

const SCORES = [1, 2, 3, 4, 5] as const;

/** The average, printed the way a designer can check it against the numbers on their own screen. */
function scoreText(score: number | null, count: number): string {
  if (score === null || count === 0) return "Not rated yet";
  return `${score.toFixed(1)} from ${count} ${count === 1 ? "designer" : "designers"}`;
}

type Props = {
  item: RankedItem;
  /** The identifier line off the stored row, when this surface can read it. */
  subtitle: string;
  round: RatingRound;
  /** Where the piece itself lives, for a reader entitled to open it. Null hides the link. */
  openHref: string | null;
  /** True when this list is showing the designer's own arrangement rather than the score order. */
  fixedOrder: boolean;
  /**
   * Whether `placedPosition` describes the whole collection.
   *
   * On the workshop's own tab it does: the caller sees every sketch or prototype in the stage, so
   * "the designers place it 3" is a fact about the workshop's list. On the POOL round the ranking is
   * narrowed to the pieces this caller may see BEFORE the positions are computed (`ranked_payload`
   * says so, and gives the reason: a stranger shown "placed 3 of 3" for one opened prototype has
   * been told how many the workshop holds). A position within an unknown subset is not the makers'
   * order, so it is not printed as one.
   */
  showPlaced: boolean;
  onRated: (rating: DesignRating) => void;
};

export function ReviewCard({ item, subtitle, round, openHref, fixedOrder, showPlaced, onRated }: Props) {
  const groupName = useId();
  const ledgerId = useId();
  const mine = item.myRating;
  const [score, setScore] = useState<number | null>(mine?.score ?? null);
  const [comment, setComment] = useState(mine?.comment ?? "");
  const [suggestion, setSuggestion] = useState(mine?.suggestion ?? "");
  const [saving, setSaving] = useState(false);
  /*
    THE SAME FACT AS `saving`, HELD WHERE AN EFFECT CAN READ IT WITHOUT DEPENDING ON IT. See the
    effect below: this ref exists because a boolean in the dependency array fires the effect on the
    way DOWN as well as on the way up, and the way down is the failure path.
  */
  const savingRef = useRef(false);
  const [problem, setProblem] = useState<string | null>(null);
  /*
    THE OUTCOME SENTENCE, AND WHETHER IT IS A LANDING OR A PROMISE.

    One state and not two, because the two facts are never both true and a card holding "sent" and
    "queued" at once would have to decide which to draw. `queued` is what keeps the styling honest:
    a rating sitting in IndexedDB must not appear in the same green box as one the repository has
    acknowledged — that box is this card's only affirmative signal, and spending it on work that has
    not moved is exactly the false all-clear this repository keeps having to un-ship.
  */
  const [saved, setSaved] = useState<{ text: string; queued: boolean } | null>(null);
  const [ledgerOpen, setLedgerOpen] = useState(false);
  const [ledger, setLedger] = useState<SubjectLedger | null>(null);
  const [ledgerProblem, setLedgerProblem] = useState<string | null>(null);
  const [ledgerLoading, setLedgerLoading] = useState(false);

  /*
    THE BOXES FOLLOW A RATING THAT ARRIVED FROM THE SERVER, and only when this designer is not
    part-way through typing one. `mine` changes when the round is re-fetched — another tab, another
    device, an amendment made on the phone — and a card that ignored it would go on offering to
    "amend" a rating it was showing the previous version of.

    ── `saving` IS READ FROM A REF AND IS NOT IN THE DEPENDENCY ARRAY, AND THAT IS THE WHOLE POINT ─

    It used to be a dependency, which meant the effect fired on the true→false transition at the end
    of every submission — including the FAILING one, where `catch` sets the message and `finally`
    clears the flag in a single render. The body then ran with `mine` unchanged (a first-time rating
    has none at all) and reset the two textareas to blank, one line under a message that reads, in
    these exact words, "What you have written is still in the boxes". A designer in a courtyard with
    no signal wrote a paragraph of qualitative feedback, pressed Submit, and watched it vanish under
    a sentence promising it had not. Offline is the ordinary path on this fleet, not the edge.

    With the flag in a ref the effect fires only when `mine` itself changes, so a failed submission
    is a no-op for the boxes and the promise in the message is one the code keeps. A refresh landing
    DURING a submission is still ignored — the reason the guard existed — and once the submission
    succeeds `onRated` changes `mine`, which re-runs this and settles the boxes on what the server
    actually stored.
  */
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
          ? "The server could not be reached, so who rated this piece is not known here yet."
          : refusalText(error, "This review history could not be read.")
      );
    } finally {
      setLedgerLoading(false);
    }
  }, [item.subjectId, round]);

  function toggleLedger() {
    const next = !ledgerOpen;
    setLedgerOpen(next);
    // Loaded on demand rather than with the round: a workshop with thirty prototypes would
    // otherwise make thirty extra requests to fill in disclosures nobody has opened.
    if (next && !ledger && !ledgerLoading) void loadLedger();
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (score === null) {
      setProblem("Choose a score from 1 to 5 — the ranking is computed from it.");
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
        // The banner's label for a queued entry: what a designer can recognise a week later on the
        // list of things this device is still holding. An endpoint is not that; the piece's own name
        // is. "Untitled piece" is the same fallback the heading above uses, so the two agree.
        `Rating · ${item.label || "Untitled piece"}`
      );
      /*
        ── A QUEUED RATING IS NOT PASSED TO `onRated`, AND THAT IS NOT AN OVERSIGHT ────────────────

        `onRated` hands the list a `DesignRating` — a stored row, with its id, its reviewer and the
        aggregate recomputed around it — and there is no such row yet. Manufacturing one from what
        is in these boxes would put a score into the ranking, and into the average printed on every
        card of this round, that the repository has never seen and might yet refuse. The boxes keep
        the text (the effect above only resets them when `mine` itself changes, and it has not), so
        nothing the designer typed is lost from the screen either.

        WHAT IT COSTS, SAID PLAINLY RATHER THAN HIDDEN: until the queue drains, this card still shows
        the round as it stood before, and the sentence below is the only thing that says a judgement
        is on its way. That is the same bargain the arrangement above makes — "saved on this device,
        going up later" — and it is why the sentence names the banner that follows it.
      */
      if (outcome.queued) {
        /*
          TWO SENTENCES, BECAUSE THERE ARE TWO OUTCOMES AND ONE OF THEM USED TO BE MIS-STATED.

          A queued rating is now handed straight to the outbox drain unless the browser already knows
          it is offline (`submitOrQueueDesignRating`), so "it sends itself when this device next has a
          connection" is the wrong sentence for the ordinary case: the connection is fine, the send
          has ALREADY been attempted, and this tab is not waiting on an `online` event that will
          never fire for it. `outcome.sent` is what that pass reported.

          NEITHER BRANCH CLAIMS THE RANKING MOVED, and that is the same bargain as the paragraph
          above: `onRated` needs a stored row with its id and its recomputed aggregate, and the drain
          does not hand one back. So a rating that HAS landed still is not in the averages on these
          cards until the page is read again — which is said out loud rather than left for a designer
          to discover by comparing two numbers.
        */
        setSaved({
          queued: true,
          text: outcome.sent
            ? "This rating was saved on this device and has now been sent to the repository. The scores on these " +
              "cards do not include it until this page is opened again — reload to see the round with it in."
            : "This rating is saved on this device and has NOT reached the repository yet. This browser has already " +
              "tried once and will keep trying — the sync banner above the page lists it until it lands, and can " +
              "send it on demand. The scores on these cards will not move until it does."
        });
      } else {
        onRated(outcome.saved.rating);
        setSaved({
          queued: false,
          text: outcome.saved.replayed
            ? "The server already held this rating, unchanged."
            : mine
              ? "Your rating has been amended."
              : "Your rating has been recorded."
        });
        // Re-read rather than patched in place: the ledger carries other people's rows, and this
        // caller's amendment may be one of several that landed since it was opened.
        if (ledgerOpen) void loadLedger();
      }
    } catch (error) {
      setProblem(
        /*
          A CONNECTION FAILURE NO LONGER REACHES THIS BRANCH: `submitOrQueueDesignRating` turns one
          into a queued entry, which is the `outcome.queued` arm above. What reaches it now is a
          device that could not keep the rating either — `queueOffline`'s IndexedDB write threw,
          which on a field laptop means private mode or a full disk — and `triageFailure` files a
          bare storage error under "unreachable", so this is where it lands. The sentence therefore
          no longer claims anything about the network, which it cannot know: what it claims is that
          nothing was stored and that the text is still on screen, which is what a designer acts on.
        */
        isUnreachable(error)
          ? "This rating has NOT been sent, and this device could not keep it either: its storage refused the write, which usually means the browser is in private mode or the disk is full. What you have written is still in the boxes."
          : refusalText(error, "This rating was not accepted.")
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
          <h3 className="font-display text-base font-bold text-ink-900">{item.label || "Untitled piece"}</h3>
          {subtitle ? <p className="mt-0.5 text-xs text-ink-muted">{subtitle}</p> : null}
          <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-500">
            <span className="inline-flex items-center gap-1 font-medium text-ink-700">
              <Star className="h-3.5 w-3.5" aria-hidden />
              {scoreText(item.score, item.ratingCount)}
            </span>
            {/*
              BOTH PLACES, ALWAYS, AND SAID IN WORDS. The gap between them IS the feature: a reader
              must be able to see at a glance that this piece is third in the designers' order and
              first on the scores. Printing only the one the list is sorted by would make the two
              orders indistinguishable, which is the failure the whole override rule exists to stop.

              THE DESIGNERS' NUMBER IS `placedPosition` AND NOT THE ROW'S PLACE ON THIS SCREEN. It
              used to be the on-screen index, which is the same number as `defaultPosition` for as
              long as the list is unsorted by anybody — the default state, and the only state a pool
              reviewer ever sees — so the card printed one number twice and claimed in a comment
              above it that the two could be compared. The place on screen is already the numbered
              chip beside the card (see `RankableList`), which is where a list index belongs.
            */}
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
                  /*
                    THE FOCUS RING IS ON THE LABEL BECAUSE THE RADIO ITSELF IS `sr-only`. A hidden
                    control's own focus ring is hidden with it, so a keyboard user tabbing into this
                    group had nothing on screen to say where they were (WCAG 2.4.7) — the `chosen`
                    word tracks the CHECKED option, which is a different question from "where is the
                    focus". `focus-within` is the same pattern `components/ui/calendar.tsx` uses for
                    its transparent `<select>`, and it is why the radios can stay hidden at all.
                  */
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
                  {/*
                    THE CHOSEN SCORE IS MARKED BY A WORD AS WELL AS BY THE FILL. Colour never
                    carries meaning alone here, and a purple chip among four pale ones is exactly
                    the signal that disappears in greyscale, in forced-colours mode and for a
                    colour-blind reader.
                  */}
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
            className="field-input min-h-[4.5rem]"
            value={comment}
            maxLength={4000}
            onChange={(event) => setComment(event.target.value)}
            placeholder="Your assessment of the piece as it stands."
          />
        </label>

        <label className="grid gap-1">
          <span className="field-label">What you would change</span>
          <textarea
            className="field-input min-h-[4.5rem]"
            value={suggestion}
            maxLength={4000}
            onChange={(event) => setSuggestion(event.target.value)}
            placeholder="A suggestion or recommendation the maker can act on."
          />
        </label>

        {/*
          WHERE THESE THREE ANSWERS GO, AND WHERE THEY DO NOT — said once, under the boxes.

          The score, the assessment and the suggestion are stored in `DwReviewRating` and NO report
          section reads that table: `report_builder`, `report_templates` and `report_model` have no
          reference to it and `SpecialSection` has no member for it (measured, and stated in
          `backend/tests/test_report_sketch_prototype_mapping.py`, which deliberately pins the gap in
          neither direction because closing it is an owner call — a new `SpecialSection` has to be
          ported to Kotlin and moves a pinned template fixture). What the printed document DOES carry
          out of this tab is the ARRANGEMENT these ratings settle, and the stamp saying who settled it.

          A designer writing four hundred words of assessment into a box on a page whose other half
          feeds a ministry report will reasonably assume the words go there too. Rule 10 of this
          repository's frontend contract is about a list that quietly stops; this is the same rule
          read one step further — work that is captured and printed nowhere has to say so on the
          screen that captures it, or the only person who ever finds out is the one who reads the
          finished document looking for their own paragraph.
        */}
        <p className="text-xs leading-5 text-ink-500">
          Scores, assessments and suggestions stay in this workshop&apos;s review ledger — they are read here and in the
          ranking, and the printed report does not carry them. What the report takes from this tab is the ORDER the
          pieces end up in, and a line saying who settled it. Anything that has to appear in the document belongs on the
          piece&apos;s own stage form.
        </p>

        {problem ? (
          <p className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{problem}</p>
        ) : null}
        {saved ? (
          <p
            className={
              saved.queued
                ? "flex items-start gap-2 rounded-md border border-line-200 bg-amber-100 px-3 py-2 text-sm text-amber-800"
                : "rounded-md border border-line-200 bg-success-100 px-3 py-2 text-sm text-success-600"
            }
          >
            {/* A STATIC MARK, NOT A COLOUR ALONE. The amber box and the green box are one hue apart
                for a reader who can compare them; the icon is what tells a colour-blind reader, and a
                reduced-motion reader, that this one is still outstanding. */}
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

/**
 * The ledger rows, exactly as the server sent them.
 *
 * BOTH CLOCKS ARE PRINTED WHEN THEY DIFFER. "When" in the owner's sentence is ambiguous between
 * "when the designer judged it" and "when the server heard about it", and on this fleet those can
 * be a fortnight apart — a rating captured in a courtyard reaches the server whenever the phone
 * next finds signal. The server sends both for that reason; showing only one would decide the
 * ambiguity silently, and in the direction that credits the sync with the judgement.
 */
function Ledger({ ledger }: { ledger: SubjectLedger }) {
  if (ledger.ratings.length === 0) {
    return (
      <p className="text-sm text-ink-muted">
        {ledger.summary.ratingCount === 0
          ? "Nobody has rated this piece yet."
          : ledger.canReadLedger
            ? "No rating rows came back for this round."
            : `${ledger.summary.ratingCount} designer(s) have rated this piece. Who they are is not yours to see — you can see the score, not the scorers.`}
      </p>
    );
  }
  return (
    <>
      {!ledger.namesShown ? (
        <p className="text-xs text-ink-muted">
          These ratings are shown without their reviewers. That is the server&apos;s decision for this round, not
          something withheld by this page.
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
                      : "reviewer not named on this response"}
                </span>
              </p>
              <p className="mt-0.5 text-xs text-ink-muted">
                Judged {formatDate(judged ?? heard)}
                {judged && heard && judged.slice(0, 10) !== heard.slice(0, 10)
                  ? ` · reached the server ${formatDateTime(heard)}`
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
