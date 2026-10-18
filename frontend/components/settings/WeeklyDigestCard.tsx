"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { CalendarClock } from "lucide-react";

import { ActionGroup } from "@/components/ui/ActionGroup";
import { Toggle } from "@/components/ui/Toggle";
import {
  DIGEST_LABEL,
  DIGEST_SWITCH,
  LATEST_LINK,
  digestCardView,
  fetchDigestPreference,
  optimistic,
  toggleDigest,
  type DigestPreference
} from "@/lib/weeklyDigest";

/**
 * The Monday summary's switch. Drawn only for reviewers and officials (`eligible`), and drawn
 * whether or not mail is on — the summary also lands in the in-app list, so the switch always works.
 */
export function WeeklyDigestCard() {
  const [state, setState] = useState<DigestPreference | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void fetchDigestPreference().then((answer) => {
      if (live) setState(answer);
    });
    return () => {
      live = false;
    };
  }, []);

  const view = digestCardView(state);
  if (!view || !state) return null;

  async function change(next: boolean) {
    if (!state) return;
    const before = state;
    setState(optimistic(before, next));
    setSaving(true);
    setError(null);
    const outcome = await toggleDigest(before, next);
    setState(outcome.state);
    setError(outcome.error);
    setSaving(false);
  }

  return (
    <section className="panel p-5" aria-labelledby="weekly-digest-heading">
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <CalendarClock className="h-4 w-4" aria-hidden />
        </span>
        <h2 id="weekly-digest-heading" className="font-display font-bold text-ink-900">
          {DIGEST_LABEL}
        </h2>
      </div>
      <Toggle
        className="mt-2"
        label={DIGEST_SWITCH}
        description={`${view.description} ${view.channel}`}
        checked={state.enabled}
        busy={saving}
        onChange={(next) => void change(next)}
        testId="weekly-digest-switch"
      />
      {view.latestHref ? (
        <ActionGroup className="mt-2">
          <Link href={view.latestHref} className="text-sm font-medium text-purple-700 hover:underline dark:text-purple-300">
            {LATEST_LINK}
          </Link>
        </ActionGroup>
      ) : null}
      <p className="pt-3 text-xs leading-5 text-ink-500" role="status">
        {error ?? (saving ? "Saving to your account..." : "Saved to your account automatically.")}
      </p>
    </section>
  );
}
