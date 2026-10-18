"use client";

/**
 * One Monday summary (`GET /notifications/digests/{id}`), for its owner only. A snapshot taken on
 * Monday at 08:00; every row opens the live queue it counted.
 */

import Link from "next/link";
import { use, useEffect, useState } from "react";
import { ArrowRight, CalendarClock } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { readableError } from "@/components/review/reviewErrors";
import { ApiError } from "@/lib/api";
import { digestTiles, fetchDigest, sectionRows, weekTitle, type Digest } from "@/lib/weeklyDigest";

export default function DigestPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [digest, setDigest] = useState<Digest | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setDigest(null);
    setError(null);
    fetchDigest(id)
      .then((answer) => {
        if (!cancelled) setDigest(answer);
      })
      .catch((failure) => {
        if (cancelled) return;
        setError(
          failure instanceof ApiError && failure.status === 404
            ? "This summary isn't available."
            : readableError(failure, "Couldn't load this summary.")
        );
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  const icon = <CalendarClock className="h-5 w-5" aria-hidden />;

  if (error) {
    return (
      <>
        <PageHeader title="Weekly summary" icon={icon} />
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      </>
    );
  }

  if (!digest) {
    return (
      <>
        <PageHeader title="Weekly summary" icon={icon} />
        <p className="text-sm text-ink-500" role="status">
          Loading…
        </p>
      </>
    );
  }

  const tiles = digestTiles(digest);
  const sections = digest.sections.map((section) => ({ section, ...sectionRows(section) }));
  const empty = sections.every(({ lines }) => lines.length === 0);

  return (
    <>
      <PageHeader title={weekTitle(digest.weekStart)} description="As of Monday 08:00." icon={icon} />

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {tiles.map((tile) => (
          <div key={tile.key} className="panel p-4" data-testid={`digest-tile-${tile.key}`}>
            <p className="field-label">{tile.label}</p>
            <p className="mt-1 font-display text-3xl font-bold tabular-nums text-ink-900">{tile.figure}</p>
            {tile.note ? <p className="mt-0.5 text-xs text-ink-500">{tile.note}</p> : null}
          </div>
        ))}
      </div>
      {digest.coverage?.scope ? <p className="mt-2 text-xs text-ink-500">{digest.coverage.scope}</p> : null}

      {empty ? (
        <div className="panel mt-6 p-5 text-sm text-ink-500">Nothing was waiting, overdue or new that week.</div>
      ) : (
        <div className="mt-6 grid gap-4">
          {sections
            .filter(({ lines }) => lines.length > 0)
            .map(({ section, lines, items }) => (
              <section key={section.key} className="panel p-5" aria-labelledby={`digest-${section.key}`}>
                <h2 id={`digest-${section.key}`} className="font-display font-bold text-ink-900">
                  {section.label}
                </h2>
                <ul className="mt-3 divide-y divide-line-200">
                  {lines.map((line) => (
                    <li key={line.key}>
                      <Link
                        href={line.link}
                        className="flex min-h-11 items-center justify-between gap-3 py-2 text-sm text-ink-900 hover:text-purple-700"
                      >
                        <span className="min-w-0">{line.label}</span>
                        <span className="flex items-center gap-2 font-semibold tabular-nums">
                          {line.count}
                          <ArrowRight className="h-4 w-4 text-ink-500" aria-hidden />
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
                {items.length > 0 ? (
                  <ul className="mt-3 grid gap-1">
                    {items.map((item) => (
                      <li key={item.link}>
                        <Link href={item.link} className="text-sm font-medium text-purple-700 hover:underline dark:text-purple-300">
                          {item.title}
                        </Link>
                      </li>
                    ))}
                  </ul>
                ) : null}
              </section>
            ))}
        </div>
      )}
    </>
  );
}
