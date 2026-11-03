"use client";

import { useId, useState } from "react";
import { ChevronDown, ShieldQuestion } from "lucide-react";

import type { UsageConsentNotice } from "@/lib/usage";

function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1.5">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-500">{title}</h3>
      {children}
    </div>
  );
}

function Lines({ items }: { items: string[] }) {
  return (
    <ul className="grid list-disc gap-1.5 pl-5 text-sm leading-6 text-ink-700">
      {items.map((line) => (
        <li key={line}>{line}</li>
      ))}
    </ul>
  );
}

export function UsageConsentNoticeBody({ notice }: { notice: UsageConsentNotice }) {
  return (
    <div className="grid gap-4">
      <Block title="What is recorded">
        <Lines items={notice.collects} />
      </Block>

      <Block title="What is never recorded">
        <Lines items={notice.doesNotCollect} />
      </Block>

      <p className="rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2.5 text-sm font-medium leading-6 text-amber-900">
        {notice.requiredSentence}
      </p>

      <Block title="How long it took">
        <p className="text-sm leading-6 text-ink-700">{notice.durationCaveat}</p>
      </Block>

      <Block title="Who can read it">
        <dl className="grid gap-1.5 text-sm leading-6">
          {Object.entries(notice.readableBy).map(([route, who]) => (
            <div key={route} className="grid gap-0.5 sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-3">
              <dt className="font-medium text-ink-900">{route}</dt>
              <dd className="text-ink-700">{who}</dd>
            </div>
          ))}
        </dl>
      </Block>

      <Block title="Taking it back">
        <p className="text-sm leading-6 text-ink-700">
          <span className="font-medium text-ink-900">{notice.withdrawal.where}</span> {notice.withdrawal.costsNothing}
        </p>
        <Lines items={notice.withdrawal.does} />
        <Lines items={notice.withdrawal.doesNot} />
      </Block>

      <Block title="How long it is kept">
        <p className="text-sm leading-6 text-ink-700">{notice.retention}</p>
      </Block>

      <p className="text-xs leading-5 text-ink-500">
        Notice version {notice.version}. Your answer is saved with this version.
      </p>
    </div>
  );
}

export function UsageConsentDisclosure({
  notice,
  defaultOpen = false,
  tone = "card"
}: {
  notice: UsageConsentNotice;
  defaultOpen?: boolean;
  tone?: "card" | "inset";
}) {
  const [open, setOpen] = useState(defaultOpen);
  const regionId = useId();
  const buttonId = useId();

  return (
    <div className={tone === "inset" ? "rounded-md border border-line-200 bg-surface-50" : ""}>
      <button
        type="button"
        id={buttonId}
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        aria-controls={open ? regionId : undefined}
        className="flex w-full items-center gap-2 rounded-md px-3 py-2.5 text-left text-sm font-medium text-purple-700 dark:text-purple-300 transition hover:bg-purple-50"
      >
        <ShieldQuestion className="h-4 w-4 shrink-0" aria-hidden />
        <span className="min-w-0 flex-1">
          {open ? "Hide what is recorded" : "Read what is recorded, and what is not"}
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <div
          id={regionId}
          role="region"
          aria-labelledby={buttonId}
          className="border-t border-line-200 px-3 py-3.5"
        >
          <UsageConsentNoticeBody notice={notice} />
        </div>
      ) : null}
    </div>
  );
}
