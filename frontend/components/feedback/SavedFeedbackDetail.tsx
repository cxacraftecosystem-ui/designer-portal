"use client";

import type { AppFeedback } from "@/lib/feedback";

export function SavedFeedbackDetail({ feedback }: { feedback: AppFeedback }) {
  const scores = [
    { label: "Overall", value: feedback.rating },
    { label: "Ease of use", value: feedback.easeOfUse },
    { label: "Reliability", value: feedback.reliability },
    { label: "Performance", value: feedback.performance },
    { label: "Design", value: feedback.design },
    { label: "Features", value: feedback.features },
    { label: "Would recommend", value: feedback.recommend }
  ].filter((entry) => Boolean(entry.value));

  const answers = [
    { label: "Likes most", value: feedback.likeMost },
    { label: "To improve", value: feedback.improve },
    { label: "Bugs / issues", value: feedback.bugs },
    { label: "Feature requests", value: feedback.featureRequests },
    { label: "General comments", value: feedback.comment }
  ].filter((entry) => Boolean(entry.value));

  if (!scores.length && !answers.length && !feedback.role) {
    return <p className="mt-2 text-sm text-ink-500">No details provided.</p>;
  }

  return (
    <div className="mt-2 grid gap-2">
      {feedback.role ? (
        <p className="text-xs text-ink-500">
          <span className="font-medium text-ink-900">Role:</span> {feedback.role}
        </p>
      ) : null}
      {scores.length ? (
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink-500">
          {scores.map((entry) => (
            <span key={entry.label}>
              {entry.label}: {entry.value}/5
            </span>
          ))}
        </div>
      ) : null}
      {answers.map((entry) => (
        <p className="text-sm leading-6 text-ink-700" key={entry.label}>
          <span className="font-medium text-ink-900">{entry.label}:</span>{" "}
          <span className="whitespace-pre-wrap">{entry.value}</span>
        </p>
      ))}
    </div>
  );
}
