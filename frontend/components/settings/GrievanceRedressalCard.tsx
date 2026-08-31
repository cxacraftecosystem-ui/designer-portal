"use client";

/**
 * "Grievances, suggestions and recommendations" — the redressal card on the ACCOUNT's settings hub.
 *
 * ── WHICH HUB THIS IS ON, AND WHY IT IS THIS ONE ───────────────────────────────────────────────
 *
 * There are two things called "Settings" in this product and the trap is well documented (§16): the
 * Settings TILE on the dashboard opens the ADMIN hub at `/admin`, while the "Settings" nav ROW opens
 * this account's own `/settings`. The card belongs on `/settings`, and the neighbours settle it.
 *
 * `/settings` carries Appearance, Accessibility, "Recording how you use this platform", Get the
 * Android app and Request workshop access — every one of them a thing THIS ACCOUNT owns, needs no
 * permission for, and would go looking for on its own behalf. Raising a grievance is exactly that
 * shape: `POST /feedback/reports` is `get_current_user` and nothing more, because asking to be heard
 * is not an administrative act, and the usage-consent card's own comment makes the identical
 * argument one card up ("filing a person's own consent behind an admin page would mean asking an
 * administrator what you had agreed to"). Filing a person's own grievance behind an admin hub would
 * be worse: it would mean asking the administration for permission to complain about it.
 *
 * `/admin` is the wrong home for the same reason. It is admin-gated end to end, so a card there
 * would be invisible to every account below ADMIN — which is every account a grievance mechanism
 * exists for. That hub already has a "User feedback" TILE pointing at the same destination, and it
 * is the READER's door; this is the WRITER's. Both leading to `/feedback` is correct and not a
 * duplication: the page shows a person their own reports and an administrator the queue.
 *
 * ── IT COUNTS RATHER THAN MERELY SIGNPOSTING ───────────────────────────────────────────────────
 *
 * A card that only says "go here to complain" tells a person nothing about the complaint they have
 * already made, which is the very gap this whole change exists to close. So it reads their own
 * reports and says how many are still open. The count is the account's OWN, from
 * `/feedback/reports/mine`, so it needs no permission and cannot leak anybody else's queue.
 *
 * A FAILED READ DRAWS NO NUMBER — never a zero. "Nothing outstanding" over a request that did not
 * come back is a lie a reader would act on by not opening the page, which is the rule the /admin
 * hub's badge states in the same words. The card itself still renders, because the door has to be
 * there whether or not the count could be fetched.
 */

import { useEffect, useState } from "react";
import Link from "next/link";
import { MessageSquareWarning } from "lucide-react";

import { apiFetch } from "@/lib/api";
import type { FeedbackReportPage } from "@/lib/feedback";

export function GrievanceRedressalCard() {
  /**
   * `null` is "not known" — loading, or a read that failed — and it draws no sentence at all.
   * A number is a number. The two are deliberately not collapsed; see the header.
   */
  const [open, setOpen] = useState<number | null>(null);
  const [total, setTotal] = useState<number | null>(null);

  useEffect(() => {
    // A `cancelled` flag and not a generation counter: this is a ONE-SHOT mount effect with no
    // parameters that can change, so there is no later request for an earlier one to race (§14.5).
    let live = true;
    // ONE REQUEST, and `pageSize=1` because only the ENVELOPE is wanted. `total` and `openCount`
    // are all this card draws, and pulling ten full reports with their details to print two digits
    // would be several kilobytes of prose fetched to be thrown away on every visit to /settings.
    void apiFetch<FeedbackReportPage>("/feedback/reports/mine?page=1&pageSize=1")
      .then((mine) => {
        if (!live) return;
        setTotal(mine.total);
        setOpen(mine.openCount ?? null);
      })
      .catch(() => {
        // Silent, and it must be: this is a count on a hub card, so a failure costs a nicety rather
        // than breaking a screen. The link below is the part that matters and it always renders.
        // Nothing is written on failure, so both numbers stay null and both sentences stay absent.
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <section className="panel p-5">
      {/*
        The same card anatomy as Appearance and Accessibility beside it — an 8x8 `bg-purple-950`
        icon chip, a `font-display font-bold` h2, then `text-xs leading-5 text-ink-500` helper text.
        Copied from `PersonalSettingsCards.CardHeading` rather than invented; that component is
        private to its file, which is why this is the shape and not the import.
      */}
      <div className="flex items-center gap-2.5">
        <span className="grid h-8 w-8 place-items-center rounded-md bg-purple-950 text-purple-100">
          <MessageSquareWarning className="h-4 w-4" aria-hidden />
        </span>
        <h2 className="font-display font-bold text-ink-900">Grievances, suggestions and recommendations</h2>
      </div>
      <p className="mt-1.5 text-xs leading-5 text-ink-500">
        Raise a grievance, suggest a change or recommend something. You can see who has read it and what they said back.
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-3">
        <Link className="field-button" href="/feedback">
          Open
        </Link>
        {/*
          Drawn only when the count is KNOWN. `total === null` is a read that has not landed or did
          not come back, and both must be silent — see the header for why a confident zero is worse
          than no sentence.
        */}
        {total === null ? null : total === 0 ? (
          <p className="text-xs text-ink-500">You have not reported anything yet.</p>
        ) : (
          <p className="text-xs text-ink-500">
            You have filed {total} {total === 1 ? "report" : "reports"}.
          </p>
        )}
        {open ? <p className="text-xs font-medium text-amber-800">{open} awaiting a reply</p> : null}
      </div>
    </section>
  );
}
