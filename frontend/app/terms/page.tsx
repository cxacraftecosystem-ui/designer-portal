"use client";

/**
 * THE TERMS PAGE — where the long text went.
 *
 * ── WHY THIS ROUTE EXISTS ───────────────────────────────────────────────────────────────────────
 *
 * The sign-in card used to carry the whole usage-recording notice: three sentences of label copy,
 * an expandable disclosure, and a live region explaining why a button was disabled. It was the
 * first thing a designer met and the last thing any of them read. The owner's instruction on
 * 2026-08-30 was to reduce the door to one line — "I agree to terms and conditions", the phrase
 * linked — which leaves exactly one question: where does the text go?
 *
 * Here. Nothing is deleted. The recording notice is still rendered word for word off
 * `GET /usage/consent/notice` (see `UsageConsentNoticeBody`'s own header for why not one sentence of
 * it is written in TSX), it is still versioned, and the version a person agreed to is still what the
 * sign-in card posts back. What changed is that a person who wants to read it now goes to a page
 * built for reading instead of squinting at a disclosure inside a 448px glass card over a
 * half-typed password.
 *
 * ── PUBLIC, AND THEREFORE NOT `PageHeader` ──────────────────────────────────────────────────────
 *
 * This route sits beside `/login` and outside `app/(protected)`, so there is no
 * `UnsavedChangesProvider` above it and `PageHeader`'s back arrow — which calls `interceptLeave` —
 * has no provider to talk to. The header here is hand-rolled for that reason and for no other; do
 * not "standardise" it onto `PageHeader` without moving the route inside the protected tree, which
 * would put the terms behind the sign-in they are a condition of.
 *
 * ── THE NOTICE MAY FAIL TO LOAD, AND THAT IS NOT AN ERROR PAGE ──────────────────────────────────
 *
 * The terms proper are static and are the substance of the agreement; the recording notice is one
 * section of them, served. If the endpoint cannot be reached, every other section still renders and
 * that one says so in a line. Blanking the page because a sub-section failed would hide the terms a
 * person came to read on the strength of a fetch that has nothing to do with most of them.
 */

import Link from "next/link";
import { useEffect, useState } from "react";
import { ArrowLeft } from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { UsageConsentNoticeBody } from "@/components/settings/UsageConsentNotice";
import { loadUsageConsentNotice, type UsageConsentNotice } from "@/lib/usage";

/** One numbered clause. The heading carries the number so the list is quotable in a message. */
function Clause({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <section className="grid gap-2">
      <h2 className="font-display text-lg font-bold text-ink-900">
        <span className="text-ink-500">{n}.</span> {title}
      </h2>
      <div className="grid gap-2 text-sm leading-6 text-ink-700">{children}</div>
    </section>
  );
}

export default function TermsPage() {
  const [notice, setNotice] = useState<UsageConsentNotice | null>(null);
  const [noticeError, setNoticeError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadUsageConsentNotice()
      .then((result) => {
        if (!cancelled) setNotice(result);
      })
      .catch((err) => {
        if (cancelled) return;
        setNoticeError(err instanceof Error ? err.message : "The recording notice could not be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="min-h-screen bg-bg-0">
      <header className="border-b border-line-200 bg-card">
        <div className="mx-auto flex max-w-3xl items-center gap-3 px-4 py-4">
          <WorkshopLogo className="h-9 w-9 shrink-0 rounded-lg" />
          <div className="min-w-0 flex-1">
            <p className="truncate font-display text-base font-bold text-ink-900">Design Prototype Workshop</p>
            <p className="truncate text-xs text-ink-500">Terms and conditions</p>
          </div>
          <Link
            href="/login"
            className="inline-flex shrink-0 items-center gap-1.5 rounded-md px-3 py-2 text-sm font-medium text-purple-700 hover:bg-purple-50"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden />
            Sign in
          </Link>
        </div>
      </header>

      <main id="main-content" className="mx-auto max-w-3xl px-4 py-8">
        <h1 className="display-title text-3xl md:text-4xl">Terms and conditions</h1>
        <p className="mt-2 text-sm leading-6 text-ink-500">
          These terms govern your use of the Design Prototype Workshop platform. Ticking the box on the sign-in screen
          accepts them.
        </p>

        <div className="panel mt-6 grid gap-6 p-5 md:p-6">
          <Clause n={1} title="Who may use the platform">
            <p>
              Access is by invitation. An administrator approves an account before it can sign in, and may set, change or
              withdraw its tier at any time.
            </p>
          </Clause>

          <Clause n={2} title="Your account">
            <p>
              An account belongs to one person. Keep your credentials to yourself, and tell an administrator at once if
              you believe someone else has them. Every record, edit and review is stored against the account that made
              it.
            </p>
          </Clause>

          <Clause n={3} title="What you record">
            <p>
              Record only what you observed, and only with the knowledge of the artisans, groups and institutions
              concerned. Photographs, recordings and identity details of other people are entered on their behalf, so
              their agreement is yours to obtain before you enter them.
            </p>
          </Clause>

          <Clause n={4} title="Identity numbers">
            <p>
              Aadhaar and Pehchan card numbers are stored masked and are never shown in lists, exports or reports. Do not
              enter a regulated identity number anywhere other than the field provided for it.
            </p>
          </Clause>

          <Clause n={5} title="The material you enter">
            <p>
              Records, media and reports created here belong to the programme that commissioned the workshop. You keep
              the right to be identified as their author, and your name travels with them.
            </p>
          </Clause>

          <Clause n={6} title="Offline use">
            <p>
              The Android app holds work on the device when there is no signal and sends it when there is. Work held on a
              device is your responsibility until it has sent — do not uninstall the app or clear its data while the
              outbox has entries in it.
            </p>
          </Clause>

          <Clause n={7} title="Availability">
            <p>
              The platform is provided as it stands. Maintenance, releases and connectivity can interrupt it, and no
              uptime is guaranteed.
            </p>
          </Clause>

          <Clause n={8} title="Suspension">
            <p>
              An administrator may suspend or remove an account that is misused, shared, or used to enter material the
              account holder had no right to enter.
            </p>
          </Clause>

          <Clause n={9} title="Changes">
            <p>
              These terms and the recording notice below can change. The version you agreed to is recorded against your
              account, so it is always possible to establish which words were on screen when you agreed.
            </p>
          </Clause>

          {/*
            THE RECORDING NOTICE, VERBATIM AND VERSIONED. Clause 10 is the one section of this page
            that is not written here: every sentence comes off the server so a deployment that
            changes what it records publishes the changed notice on the same deploy.
          */}
          <section className="grid gap-3 border-t border-line-200 pt-6">
            <h2 className="font-display text-lg font-bold text-ink-900">
              <span className="text-ink-500">10.</span> How your use of the platform is recorded
            </h2>
            {notice ? (
              <UsageConsentNoticeBody notice={notice} />
            ) : noticeError ? (
              <p className="text-sm leading-6 text-ink-700">
                The recording notice could not be loaded, so it is not shown here. It is also available in Settings once
                you have signed in. <span className="text-ink-500">{noticeError}</span>
              </p>
            ) : (
              <p className="text-sm leading-6 text-ink-500">Loading the recording notice…</p>
            )}
          </section>
        </div>

        <p className="mt-6 text-center text-sm text-ink-500">
          <Link href="/login" className="font-medium text-purple-700 hover:underline">
            Back to sign in
          </Link>
        </p>
      </main>
    </div>
  );
}
