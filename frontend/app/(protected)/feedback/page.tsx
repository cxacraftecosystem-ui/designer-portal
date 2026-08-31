"use client";

/**
 * /feedback — "Give app feedback", and now two genuinely different things under one roof.
 *
 * ── WHAT WAS HERE, AND WHAT WAS MISSING ────────────────────────────────────────────────────────
 *
 * This page used to be the satisfaction survey and nothing else: seven star ratings and five
 * prompts, upserted into ONE row per account (`Feedback.userId` is `@unique`). Four things followed
 * from that and each is now closed:
 *
 * 1. A person who reported a bug and later filed a grievance OVERWROTE the bug report. There was no
 *    second submission and no history.
 * 2. There was no KIND, so nobody could count grievances or list only the bugs.
 * 3. There was no STATUS, so nothing could show anyone that what they wrote had been read — the
 *    brief's own test for whether a redressal mechanism is one.
 * 4. The list was master-admin only, while `/admin`'s "User feedback" tile has been ADMIN-visible
 *    all along: an admin who followed that tile arrived at this form and no inbox.
 *
 * So the page now carries the REGISTER first (file one, then read your own with its status), the
 * SURVEY second, and the two admin surfaces last. The survey did not lose anything — a standing
 * answer to "how is the app working for you" really is one per person, and accumulating it would
 * make the master admin's screen a pile of duplicates rather than a picture.
 *
 * ── ONE LEAVE GUARD, SHARED BY BOTH FORMS ──────────────────────────────────────────────────────
 *
 * `UnsavedChangesProvider` holds exactly one interceptor per mount, and both forms on this page can
 * be dirty at once. So the page owns the flag and each form raises it; a person who has typed a
 * grievance and reaches for the back arrow is asked, whichever form the words are in. There is no
 * second back control anywhere here — `PageHeader`'s round arrow is the page's one back control
 * (`e2e/back-control.spec.ts` counts them).
 */

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { MessageSquare } from "lucide-react";

import { AppRatingForm } from "@/components/feedback/AppRatingForm";
import { EmptyState } from "@/components/EmptyState";
import { FeedbackReportForm } from "@/components/feedback/FeedbackReportForm";
import { FeedbackReportsInbox } from "@/components/feedback/FeedbackReportsInbox";
import { MyFeedbackReports } from "@/components/feedback/MyFeedbackReports";
import { PageHeader } from "@/components/PageHeader";
import { SavedFeedbackDetail } from "@/components/feedback/SavedFeedbackDetail";
import { UnsavedChangesDialog } from "@/components/UnsavedChangesDialog";
import { useLeaveGuard } from "@/components/UnsavedChangesGuard";
import { useAuth } from "@/components/AuthProvider";
import { apiFetch } from "@/lib/api";
import { formatDateTime } from "@/lib/format";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import { fetchFeedbackVocabulary, type AppFeedback, type FeedbackVocabulary } from "@/lib/feedback";

export default function FeedbackPage() {
  const { user } = useAuth();
  const router = useRouter();

  const [vocabulary, setVocabulary] = useState<FeedbackVocabulary | null>(null);
  const [vocabError, setVocabError] = useState<string | null>(null);
  /** Bumped on every filed report, so "Your reports" re-reads rather than splicing a row in. */
  const [reportsToken, setReportsToken] = useState(0);

  // One flag for both forms — see the header. `reportDirty` and `ratingDirty` are separate so a
  // successful send on one cannot lower a flag the other raised.
  const [reportDirty, setReportDirty] = useState(false);
  const [ratingDirty, setRatingDirty] = useState(false);
  const [leavePromptOpen, setLeavePromptOpen] = useState(false);
  useLeaveGuard(reportDirty || ratingDirty, () => setLeavePromptOpen(true));

  /**
   * The dialog's "Save" needs a form to submit, and this page has two of them.
   *
   * IT SUBMITS EVERY DIRTY FORM, and it does so through `requestSubmit()` so each one runs the
   * browser's own constraint validation first — a report with no subject stops on the field rather
   * than being posted and refused. "Save" on a page with two unsaved forms cannot honestly mean one
   * of them; asking the reader which would be a fourth way forward on a dialog whose whole design
   * note is that three is already the maximum a person can hold.
   */
  const reportFormRef = useRef<HTMLFormElement | null>(null);
  const ratingFormRef = useRef<HTMLFormElement | null>(null);
  const [leaveAfterSave, setLeaveAfterSave] = useState(false);

  /**
   * Leave once every form that was dirty has gone clean.
   *
   * AN EFFECT AND NOT A CALLBACK CHAIN, because "saved" arrives as two independent flag drops from
   * two components and the navigation must wait for BOTH. Gated on the intent flag, so a perfectly
   * ordinary successful save with nobody trying to leave does not navigate anywhere; and a form
   * whose validation refused keeps its flag raised, so the reader stays put with the dialog closed
   * and the offending field highlighted — which is the browser's own behaviour, not a second one.
   */
  useEffect(() => {
    if (!leaveAfterSave || reportDirty || ratingDirty) return;
    setLeaveAfterSave(false);
    router.back();
  }, [leaveAfterSave, reportDirty, ratingDirty, router]);

  useEffect(() => {
    let live = true;
    fetchFeedbackVocabulary()
      .then((served) => {
        if (live) setVocabulary(served);
      })
      .catch((err) => {
        if (live) setVocabError(err instanceof Error ? err.message : "Could not load the feedback options.");
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <>
      <PageHeader
        title="Give app feedback"
        description="Report a grievance, suggest a change, recommend something, or rate how the app is working for you."
        icon={<MessageSquare className="h-5 w-5" aria-hidden />}
      />

      <section className="panel max-w-2xl p-5">
        <h2 className="font-display text-lg font-bold text-ink-900">Report something</h2>
        <p className="mt-1 text-sm leading-6 text-ink-500">
          A grievance, a suggestion, a recommendation or a bug. Each one is kept separately and you can see who reads it.
        </p>
        <div className="mt-4">
          {vocabError ? (
            // The whole form depends on the served lists, so a failed fetch has to say so rather
            // than draw empty dropdowns. Nothing here invents a fallback list of kinds — that is the
            // drift `lib/feedback.ts` opens by forbidding.
            <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{vocabError}</div>
          ) : !vocabulary ? (
            <p className="text-sm text-ink-500">Loading...</p>
          ) : (
            <FeedbackReportForm
              vocabulary={vocabulary}
              formRef={reportFormRef}
              onDirtyChange={setReportDirty}
              onFiled={() => setReportsToken((n) => n + 1)}
            />
          )}
        </div>
      </section>

      <MyFeedbackReports refreshToken={reportsToken} />

      <section className="mt-8">
        <h2 className="mb-1 font-display text-lg font-bold text-ink-900">Rate the app</h2>
        <p className="mb-3 max-w-2xl text-sm leading-6 text-ink-500">
          One standing rating per person, which you can revisit and change at any time. Everything is optional.
        </p>
        <AppRatingForm formRef={ratingFormRef} onDirtyChange={setRatingDirty} />
      </section>

      {/*
        THE ADMIN INBOX IS `isAdmin`, THE RATINGS LIST IS `isMasterAdmin`, AND THE SPLIT IS THE POINT.
        `GET /feedback/reports` is `require_admin` because a redressal mechanism where exactly one
        account can acknowledge anything will not redress anything; `GET /feedback` stays
        `require_master_admin` because a colleague's standing opinion of the software is a different
        thing from a complaint that needs answering. Both mirror their route's own dependency, which
        is the rule — the client never invents a permission.
      */}
      {isAdmin(user) ? <FeedbackReportsInbox vocabulary={vocabulary} /> : null}
      {isMasterAdmin(user) ? <AllRatings /> : null}

      <UnsavedChangesDialog
        open={leavePromptOpen}
        onKeepEditing={() => setLeavePromptOpen(false)}
        onDiscard={() => {
          setLeavePromptOpen(false);
          // Dropping the flags first is what lets the navigation through: the interceptor claims the
          // back control only while something is dirty.
          setReportDirty(false);
          setRatingDirty(false);
          router.back();
        }}
        onSave={() => {
          setLeavePromptOpen(false);
          setLeaveAfterSave(true);
          // Every dirty form, through the browser's own submit path. The effect above does the
          // leaving, once each of them has actually gone clean.
          if (reportDirty) reportFormRef.current?.requestSubmit();
          if (ratingDirty) ratingFormRef.current?.requestSubmit();
        }}
      />
    </>
  );
}

/**
 * The master admin's list of satisfaction surveys.
 *
 * IT STILL READS A BARE ARRAY, and that is a constraint rather than a preference: `GET /feedback`
 * answers `list[dict]` and its own docstring records why (an envelope would have replaced this
 * screen with a runtime error the day it deployed, and both sides have to move together). The
 * REPORT routes beside it are new, so they carry the house envelope; do not "make these consistent"
 * by changing one side.
 *
 * The 200-row bound and its `X-Total-Count` / `X-Truncated` headers are still unreadable here — the
 * API's CORS config exposes neither — so this screen cannot say it was capped. That is written up
 * on the route, not papered over with a length comparison, which would be inferring the cut from a
 * number this client cannot check.
 */
function AllRatings() {
  const [items, setItems] = useState<AppFeedback[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    apiFetch<AppFeedback[]>("/feedback")
      .then((rows) => {
        if (live) setItems(rows);
      })
      .catch((err) => {
        if (!live) return;
        setError(err instanceof Error ? err.message : "Unable to load all feedback");
        setItems((current) => current ?? []);
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <section className="mt-8">
      <h2 className="mb-3 font-display text-lg font-bold text-ink-900">Everyone&apos;s ratings</h2>
      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">{error}</div>
      ) : null}
      {!items ? (
        <div className="panel p-4 text-sm text-ink-500">Loading...</div>
      ) : items.length === 0 ? (
        <div className="panel p-4">
          <EmptyState title="No ratings yet" body="Ratings people give from the web or Android apps will appear here." />
        </div>
      ) : (
        <div className="grid gap-3">
          {items.map((item) => (
            <article className="panel p-4" key={item.id}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="text-sm font-medium text-ink-900">
                  {item.user?.name ?? "Unknown user"}
                  <span className="ml-2 text-xs font-normal text-ink-500">{item.user?.email}</span>
                </div>
                <div className="text-xs text-ink-500">{formatDateTime(item.updatedAt)}</div>
              </div>
              <SavedFeedbackDetail feedback={item} />
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
