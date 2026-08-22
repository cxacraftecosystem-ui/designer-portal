"use client";

import { ArrowLeft } from "lucide-react";
import { useRouter } from "next/navigation";

import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";

/**
 * Round back control (Android `BackPill` parity): steps to the previous screen, or to an
 * explicit `href` when the page knows where "back" should land (deep links, post-save).
 *
 * This is the ONLY back control on a page. Forms used to add a second, rounded "Back" pill of their
 * own purely because the unsaved-changes prompt needed to live where the dirty flag was; that pill
 * is gone and the prompt happens here instead, via the guard context. If you are adding a back
 * control to a form, you do not need one — the page header already has it.
 */
export function BackButton({ href }: { href?: string }) {
  const router = useRouter();
  const interceptLeave = useLeaveInterceptor();
  return (
    <button
      type="button"
      aria-label="Go back"
      title="Go back"
      onClick={() => {
        /*
          A dirty form answers true and raises its own prompt; leaving is then that dialog's
          decision, not ours. Decided before either navigation branch so an explicit `href` cannot
          slip past the prompt.

          THE NAVIGATION IS BUILT FIRST AND HANDED OVER, rather than run in the branch below, because
          the guard now banks it, so that the answer meaning "leave" can finish the exact exit this
          arrow began instead of leaving the designer to press the arrow a second time. (The answer
          itself is the forms' half and has not landed: nothing calls `completeLeave()` yet, so a
          second press is still needed today.) Which of the two branches it is matters — an explicit
          `href` is a deep link's or a post-save's known destination and `router.back()` would pop
          somewhere else entirely — and this is the only place that knows.
        */
        const go = href ? () => router.push(href) : () => router.back();
        if (interceptLeave(go)) return;
        go();
      }}
      className="grid h-10 w-10 shrink-0 place-items-center rounded-full border border-line-200 bg-card text-purple-700 shadow-sm transition hover:border-purple-300 hover:bg-purple-50"
    >
      <ArrowLeft className="h-5 w-5" aria-hidden />
    </button>
  );
}
