"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { MessageSquareWarning } from "lucide-react";

import { apiFetch } from "@/lib/api";
import type { FeedbackReportPage } from "@/lib/feedback";

export function GrievanceRedressalCard() {
  const [open, setOpen] = useState<number | null>(null);
  const [total, setTotal] = useState<number | null>(null);

  useEffect(() => {
    let live = true;
    void apiFetch<FeedbackReportPage>("/feedback/reports/mine?page=1&pageSize=1")
      .then((mine) => {
        if (!live) return;
        setTotal(mine.total);
        setOpen(mine.openCount ?? null);
      })
      .catch(() => {
      });
    return () => {
      live = false;
    };
  }, []);

  return (
    <section className="panel p-5">
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
        {total === null ? null : total === 0 ? (
          <p className="text-xs text-ink-500">You have not reported anything yet.</p>
        ) : (
          <p className="text-xs text-ink-500">
            You have filed {total} {total === 1 ? "report" : "reports"}.
          </p>
        )}
        {open ? <p className="text-xs font-medium text-amber-800 dark:text-amber-100">{open} awaiting a reply</p> : null}
      </div>
    </section>
  );
}
