"use client";

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

const RATING_FIELDS = [
  { key: "easeOfUse", label: "Ease of use" },
  { key: "reliability", label: "Reliability / stability" },
  { key: "performance", label: "Speed / performance" },
  { key: "design", label: "Design / look & feel" },
  { key: "features", label: "Features / completeness" },
  { key: "recommend", label: "How likely you'd recommend it" }
] as const;

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

const OVERALL_WORDS = ["Tap a star to rate", "Poor", "Fair", "Good", "Very good", "Excellent"];

const TEXT_MAX = 5000;
const ROLE_MAX = 200;

type RatingKey = (typeof RATING_FIELDS)[number]["key"] | "rating";

function StarRating({
  value,
  onChange,
  label,
  size = "md"
}: {
  value: number | null;
  onChange: (value: number | null) => void;
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
          className="rounded-md p-1 transition hover:bg-purple-50 focus-visible:outline-hidden focus-visible:ring-2 focus-visible:ring-purple-600"
          key={n}
          onClick={() => onChange(value === n ? null : n)}
          type="button"
        >
          <Star className={`${star} ${value && n <= value ? "fill-purple-700 text-purple-700 dark:text-purple-300" : "text-line-200"}`} aria-hidden />
        </button>
      ))}
      <span className="ml-2 text-xs text-ink-500">{value ? `${value}/5` : "Not rated"}</span>
    </div>
  );
}

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
    onDirtyChange(true);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    const data = new FormData(event.currentTarget);
    event.preventDefault();

    const texts = Object.fromEntries(
      [...TEXT_FIELDS.map((f) => f.key), "role" as const].map((key) => [key, String(data.get(key) ?? "").trim() || null])
    );
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
        <div className="mb-4 max-w-2xl rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
      ) : null}
      {success ? (
        <div className="mb-4 max-w-2xl rounded-md border border-emerald-200 bg-success-100 px-3 py-2 text-sm text-success-600">
          {success}
        </div>
      ) : null}

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
