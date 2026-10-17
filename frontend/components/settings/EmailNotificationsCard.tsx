"use client";

import { useEffect, useState } from "react";
import { Mail } from "lucide-react";

import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { Toggle } from "@/components/ui/Toggle";
import {
  HAND_IN_MODES,
  fetchNotificationPreferences,
  saveNotificationPreferences,
  type HandInMode,
  type NotificationPreferences
} from "@/lib/notifications";

type SwitchKey = "emailReviewNotes" | "emailWorkshopUpdates" | "emailAccessNotices";

/** The three opt-outs, in the order a designer meets them. Words held to Android's settings card. */
export const EMAIL_SWITCHES: { key: SwitchKey; label: string; description: string }[] = [
  {
    key: "emailReviewNotes",
    label: "Corrections on my workshops",
    description: "When an inspector suggests a correction or sends a workshop back."
  },
  {
    key: "emailWorkshopUpdates",
    label: "Workshop updates",
    description: "When a report is approved or handed on, or you're given a workshop."
  },
  {
    key: "emailAccessNotices",
    label: "Requests and comments",
    description: "Access requests, decisions on yours, and comments on your records."
  }
];

/**
 * This account's e-mail choices. NO ROLE GATE — the choice belongs to the person — and NOTHING AT
 * ALL is drawn until the server says mail is on (`available`). The hand-in choice is drawn only for
 * an inspector or the approving authority (`reviewsHandIns`). Each change saves only that field.
 */
export function EmailNotificationsCard() {
  const [state, setState] = useState<NotificationPreferences | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void fetchNotificationPreferences().then((answer) => {
      if (live) setState(answer);
    });
    return () => {
      live = false;
    };
  }, []);

  if (!state?.available) return null;

  async function save(change: Partial<NotificationPreferences>) {
    const before = state;
    setState((current) => (current ? { ...current, ...change } : current));
    setSaving(true);
    setError(null);
    try {
      setState(await saveNotificationPreferences(change));
    } catch (err) {
      setState(before);
      setError(err instanceof Error ? err.message : "Couldn't save. Try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section className="panel p-5" aria-labelledby="email-notifications-heading">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <Mail className="h-4 w-4" aria-hidden />
        </span>
        <h2 id="email-notifications-heading" className="font-display font-bold text-ink-900">
          E-mail
        </h2>
      </div>
      <div className="mt-3 grid gap-1">
        {EMAIL_SWITCHES.map((item) => (
          <Toggle
            key={item.key}
            label={item.label}
            description={item.description}
            checked={state[item.key]}
            onChange={(next) => void save({ [item.key]: next })}
          />
        ))}
      </div>
      {state.reviewsHandIns ? (
        <div className="mt-4 grid gap-1.5 sm:max-w-xs">
          <span id="hand-in-mail-label" className="text-sm font-medium text-ink-900">
            Reports handed in for review
          </span>
          <SearchableSelect
            value={state.emailHandInNotices}
            onChange={(value) => void save({ emailHandInNotices: value as HandInMode })}
            options={HAND_IN_MODES}
            ariaLabel="Reports handed in for review"
          />
          <span className="text-xs leading-5 text-ink-500">“Once a day” sends one summary at 9 am.</span>
        </div>
      ) : null}
      <p className="pt-4 text-xs leading-5 text-ink-500" role="status">
        {error ?? (saving ? "Saving…" : "Saved automatically.")}
      </p>
    </section>
  );
}
