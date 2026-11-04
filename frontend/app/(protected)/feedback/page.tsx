"use client";

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
  const [reportsToken, setReportsToken] = useState(0);

  const [reportDirty, setReportDirty] = useState(false);
  const [ratingDirty, setRatingDirty] = useState(false);
  const [leavePromptOpen, setLeavePromptOpen] = useState(false);
  useLeaveGuard(reportDirty || ratingDirty, () => setLeavePromptOpen(true));

  const reportFormRef = useRef<HTMLFormElement | null>(null);
  const ratingFormRef = useRef<HTMLFormElement | null>(null);
  const [leaveAfterSave, setLeaveAfterSave] = useState(false);

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
            <div className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{vocabError}</div>
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

      {isAdmin(user) ? <FeedbackReportsInbox vocabulary={vocabulary} /> : null}
      {isMasterAdmin(user) ? <AllRatings /> : null}

      <UnsavedChangesDialog
        open={leavePromptOpen}
        onKeepEditing={() => setLeavePromptOpen(false)}
        onDiscard={() => {
          setLeavePromptOpen(false);
          setReportDirty(false);
          setRatingDirty(false);
          router.back();
        }}
        onSave={() => {
          setLeavePromptOpen(false);
          setLeaveAfterSave(true);
          if (reportDirty) reportFormRef.current?.requestSubmit();
          if (ratingDirty) ratingFormRef.current?.requestSubmit();
        }}
      />
    </>
  );
}

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
        <div className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-red-800">{error}</div>
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
