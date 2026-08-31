"use client";

/**
 * The SATISFACTION SURVEY: an overall 1-5 rating, six per-aspect sub-ratings and five written
 * prompts, upserted through `PUT /feedback/me`. One row per account, revisable for ever.
 *
 * ── WHAT CHANGED WHEN THE REGISTER LANDED BESIDE IT, AND WHAT DID NOT ──────────────────────────
 *
 * Not the wording, not the aspects, not the upsert. Android's `FeedbackScreen` renders the same
 * six aspects in the same order with the same words, and §1 of the frontend guide says those come
 * from Android verbatim. Two things did change:
 *
 * 1. **Every written box has a microphone**, per the owner's instruction to "add the dictate button
 *    in the text boxes over there as well". It is the ON-DEVICE button — see below.
 * 2. **The written half is now uncontrolled and read through `FormData`**, where it used to be a
 *    `texts` state object. That is not tidying: `DictatedTextArea` owns its own value (a dictated
 *    phrase is written into the box from outside the keyboard, which an uncontrolled `<textarea>`
 *    could only do through a ref and a manual `input` dispatch), so a caller holding the same
 *    strings in state would be a second copy of the answer that the microphone does not update.
 *    The RATINGS stay in React state, because star buttons are `<button>`s that submit nothing.
 *
 * ── WHY THE ON-DEVICE MICROPHONE AND NOT THE SERVER-BACKED ONE ─────────────────────────────────
 *
 * This client has two. `components/designworkshop/Dictation.tsx` posts to
 * `/design-workshops/{id}/dictate`, which REQUIRES a workshop id and a granted dictation consent on
 * that workshop. Feedback has no workshop — it is about the software — so there is no id to send
 * and no consent to check, and the component cannot be used here at all. `OnDeviceDictationButton`,
 * which `DictatedTextArea` and `DictatedTextInput` already wrap with a label, runs on the browser's
 * own Web Speech API and sends nothing anywhere.
 *
 * ── AND WHY `markDirty` IS CALLED BY HAND FROM THE STARS ───────────────────────────────────────
 *
 * The stars are `<button>`s and fire no native input event, exactly like a themed dropdown (§12.1),
 * so `onInput` on the form never sees one. Every star row therefore raises the flag itself.
 */

import { useEffect, useState } from "react";
import { Star } from "lucide-react";

import { DictationUnavailableNotice } from "@/components/richtext/DictationUnavailableNotice";
import { DictatedTextArea } from "@/components/richtext/DictatedTextArea";
import { DictatedTextInput } from "@/components/richtext/DictatedTextInput";
import { SavedFeedbackDetail } from "@/components/feedback/SavedFeedbackDetail";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { handleFormEnter } from "@/lib/formNav";
import type { AppFeedback } from "@/lib/feedback";

/** The six 1-5 aspects, in the Android screen's order and with its exact wording. */
const RATING_FIELDS = [
  { key: "easeOfUse", label: "Ease of use" },
  { key: "reliability", label: "Reliability / stability" },
  { key: "performance", label: "Speed / performance" },
  { key: "design", label: "Design / look & feel" },
  { key: "features", label: "Features / completeness" },
  { key: "recommend", label: "How likely you'd recommend it" }
] as const;

/**
 * The five free-text prompts, again worded exactly as Android words them. The helpers are web-only —
 * a phone keyboard covers half the screen, so Android leaves the box bare, but on a laptop an empty
 * textarea with a one-line label gets a one-line answer.
 *
 * THEY ARE `helper` AND NOT `placeholder` NOW, and the change is load-bearing rather than cosmetic:
 * a placeholder vanishes the moment dictation writes the first word into the box, so the example
 * that was helping somebody compose their answer disappears exactly when they start giving it.
 * `DictatedTextArea` draws `helper` under the label, where it stays.
 */
const TEXT_FIELDS = [
  { key: "likeMost", label: "What do you like most?", helper: "The part of the app you would not want taken away.", rows: 2 },
  { key: "improve", label: "What should we improve?", helper: "The step that takes longest, or the screen you avoid.", rows: 2 },
  {
    key: "bugs",
    label: "Any bugs or issues you hit?",
    helper: "What you were doing, what you expected, and what happened instead.",
    rows: 3
  },
  {
    key: "featureRequests",
    label: "Features you'd like to see",
    helper: "Something you currently do on paper, or in another app.",
    rows: 2
  },
  { key: "comment", label: "Anything else (general comments)", helper: "Anything the questions above did not cover.", rows: 3 }
] as const;

/** Android parity (FeedbackScreen): the word under the overall stars. */
const OVERALL_WORDS = ["Tap a star to rate", "Poor", "Fair", "Good", "Very good", "Excellent"];

/** The server's own ceiling on every free-text column, mirrored so the browser refuses first. */
const TEXT_MAX = 5000;
const ROLE_MAX = 200;

type RatingKey = (typeof RATING_FIELDS)[number]["key"] | "rating";

/**
 * A 1-5 star row.
 *
 * Stars rather than a number box for the reason Android uses them: a rating is a judgement, not a
 * measurement, and a five-target row is one tap where a number field is a tap, a keyboard and a
 * decision about whether 4.5 is allowed. Clicking the current value clears it, because "I would
 * rather not answer this one" has to stay reachable once a star has been pressed by accident.
 */
function StarRating({
  value,
  onChange,
  label,
  size = "md"
}: {
  value: number | null;
  onChange: (value: number | null) => void;
  /** Used for the per-star accessible name, so a screen reader hears which aspect is being rated. */
  label: string;
  size?: "sm" | "md";
}) {
  const star = size === "sm" ? "h-5 w-5" : "h-6 w-6";
  return (
    <div className="flex items-center gap-1" role="group" aria-label={label}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          aria-label={`${label}: ${n} star${n > 1 ? "s" : ""}`}
          aria-pressed={value === n}
          className="rounded-md p-1 transition hover:bg-purple-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-purple-600"
          key={n}
          onClick={() => onChange(value === n ? null : n)}
          type="button"
        >
          <Star className={`${star} ${value && n <= value ? "fill-purple-700 text-purple-700" : "text-line-200"}`} aria-hidden />
        </button>
      ))}
      <span className="ml-2 text-xs text-ink-500">{value ? `${value}/5` : "Not rated"}</span>
    </div>
  );
}

/** One labelled aspect row: label on the left, stars on the right on anything wider than a phone. */
function AspectRating({ label, value, onChange }: { label: string; value: number | null; onChange: (value: number | null) => void }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 border-t border-line-200 py-2 first:border-t-0">
      <span className="text-sm text-ink-900">{label}</span>
      <StarRating label={label} value={value} onChange={onChange} size="sm" />
    </div>
  );
}

export function AppRatingForm({
  onDirtyChange,
  formRef
}: {
  onDirtyChange: (dirty: boolean) => void;
  /**
   * The page's handle on the `<form>`, so the unsaved-changes dialog's "Save" can `requestSubmit()`
   * it — the browser's own validation first, exactly as the sibling report form does. See that
   * component's prop for why `requestSubmit` rather than the handler.
   */
  formRef?: React.RefObject<HTMLFormElement | null>;
}) {
  const [saved, setSaved] = useState<AppFeedback | null>(null);
  const [loading, setLoading] = useState(true);
  const [ratings, setRatings] = useState<Record<RatingKey, number | null>>({
    rating: null,
    easeOfUse: null,
    reliability: null,
    performance: null,
    design: null,
    features: null,
    recommend: null
  });
  /**
   * The one-line "your role" box, held here rather than by the control.
   *
   * `DictatedTextInput` is controlled BY ITS CALLER where `DictatedTextArea` controls itself — that
   * asymmetry is documented on the input's own header and it is not an accident. The consequence
   * here is that this state must live OUTSIDE the keyed `<form>` below (a keyed element remounts,
   * and state inside the remounted subtree would be destroyed on the very render that seeds it), so
   * it is seeded from the same fetch that seeds the stars.
   */
  const [roleValue, setRoleValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    apiFetch<AppFeedback>("/feedback/me")
      .then((mine) => {
        if (!live || !mine?.id) return;
        setSaved(mine);
        setRoleValue(mine.role ?? "");
        setRatings({
          rating: mine.rating ?? null,
          easeOfUse: mine.easeOfUse ?? null,
          reliability: mine.reliability ?? null,
          performance: mine.performance ?? null,
          design: mine.design ?? null,
          features: mine.features ?? null,
          recommend: mine.recommend ?? null
        });
      })
      .catch((err) => {
        if (!live) return;
        setError(err instanceof Error ? err.message : "Unable to load your feedback");
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, []);

  function setRating(key: RatingKey, value: number | null) {
    setRatings((current) => ({ ...current, [key]: value }));
    // A star is a <button>: no native input event, so the flag is raised by hand (§12.1).
    onDirtyChange(true);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    // First statement, before any await — React nulls `currentTarget` across one.
    const data = new FormData(event.currentTarget);
    event.preventDefault();

    // A cleared box means "no answer", which the column stores as NULL, not as "".
    const texts = Object.fromEntries(
      [...TEXT_FIELDS.map((f) => f.key), "role" as const].map((key) => [key, String(data.get(key) ?? "").trim() || null])
    );
    // Android parity: the save is refused only when the whole form is empty. Every single answer is
    // optional, and nobody should be made to invent a rating to send a note.
    const anyProvided = Object.values(ratings).some(Boolean) || Object.values(texts).some(Boolean);
    if (!anyProvided) {
      setSuccess(null);
      setError("Add at least one rating or a written answer first.");
      return;
    }

    setBusy(true);
    setSuccess(null);
    setError(null);
    try {
      const result = await apiFetch<AppFeedback>("/feedback/me", {
        method: "PUT",
        body: JSON.stringify({ ...ratings, ...texts })
      });
      setSaved(result);
      onDirtyChange(false);
      setSuccess("Thank you — your ratings have been saved. You can revisit and change them any time.");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to save feedback");
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return <div className="panel max-w-2xl p-5 text-sm text-ink-500">Loading your ratings...</div>;
  }

  return (
    <>
      {error ? (
        <div className="mb-4 max-w-2xl rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {success ? (
        <div className="mb-4 max-w-2xl rounded-md border border-emerald-200 bg-success-100 px-3 py-2 text-sm text-success-600">
          {success}
        </div>
      ) : null}

      {/*
        `key={saved?.id ?? "new"}`: the written boxes are uncontrolled and seeded from `defaultValue`,
        so the form has to REMOUNT when the fetch lands or the boxes keep the empty strings they were
        built with. The record forms carry the identical key for the identical reason (§12.1). It is
        stable after that — one account has one survey row for ever — so nothing remounts on save.
      */}
      <form
        className="panel grid max-w-2xl gap-4 p-5"
        key={saved?.id ?? "new"}
        ref={formRef}
        onSubmit={submit}
        onInput={() => onDirtyChange(true)}
        onKeyDown={handleFormEnter}
      >
        <div>
          <h3 className="font-display text-lg font-bold text-ink-900">Ratings</h3>
          <label className="field-label mt-3 block" htmlFor="feedback-overall-note">
            Overall rating
          </label>
          <div className="mt-1.5" id="feedback-overall-note">
            <StarRating label="Overall rating" value={ratings.rating} onChange={(value) => setRating("rating", value)} />
          </div>
          <p className="mt-1 text-xs text-ink-500">{OVERALL_WORDS[ratings.rating ?? 0]}</p>

          <div className="mt-4 border-t border-line-200 pt-1">
            {RATING_FIELDS.map((aspect) => (
              <AspectRating
                key={aspect.key}
                label={aspect.label}
                value={ratings[aspect.key]}
                onChange={(value) => setRating(aspect.key, value)}
              />
            ))}
          </div>
        </div>

        <div className="border-t border-line-200 pt-4">
          <h3 className="font-display text-lg font-bold text-ink-900">In your words</h3>
          {/*
            Said ONCE for the whole form. Six microphones disappear together on a browser with no
            recogniser, and six copies of the same paragraph is the reader learning to skip grey
            text — which is why every box below passes `explainWhenUnavailable={false}`.
          */}
          <DictationUnavailableNotice className="mt-2" />
        </div>

        <DictatedTextInput
          name="role"
          label="Your role"
          value={roleValue}
          onChange={(next) => {
            setRoleValue(next);
            onDirtyChange(true);
          }}
          helper="Researcher, field documenter, reviewer..."
          maxLength={ROLE_MAX}
          explainWhenUnavailable={false}
        />

        {TEXT_FIELDS.map((prompt) => (
          <DictatedTextArea
            key={prompt.key}
            name={prompt.key}
            label={prompt.label}
            helper={prompt.helper}
            rows={prompt.rows}
            maxLength={TEXT_MAX}
            defaultValue={saved?.[prompt.key] ?? ""}
            explainWhenUnavailable={false}
            onDirty={() => onDirtyChange(true)}
          />
        ))}

        <div className="flex flex-wrap items-center gap-3">
          <button className="field-button" disabled={busy} type="submit">
            {busy ? "Saving..." : saved ? "Update ratings" : "Save ratings"}
          </button>
          {saved?.updatedAt ? <span className="text-xs text-ink-500">Last updated {formatDateTime(saved.updatedAt)}</span> : null}
        </div>
      </form>

      {saved ? (
        <section className="panel mt-4 max-w-2xl p-5">
          <h3 className="font-display font-bold text-ink-900">Your saved ratings</h3>
          <SavedFeedbackDetail feedback={saved} />
        </section>
      ) : null}
    </>
  );
}
