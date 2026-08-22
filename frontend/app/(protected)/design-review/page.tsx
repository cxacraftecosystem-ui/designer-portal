"use client";

/**
 * Design review — LEVEL TWO: the wider pool of designers, on pieces a workshop has finished.
 *
 * ── WHY THIS IS A ROUTE OF ITS OWN AND NOT A TAB UNDER THE WORKSHOP ─────────────────────────────
 *
 * Measured, not assumed. `load_workshop_or_404` (backend/app/services/design_workshops.py:127)
 * admits the workshop's creator, an admin, and the holder of a `DesignWorkshopViewer` grant, and
 * answers everybody else with the same 404 and the same "Record not found" a nonexistent id gets.
 * Every one of the 22 stage SAVE routes is gated by that same helper. A pool reviewer is by
 * definition one of the designers it turns away — so teaching it about the pool round would not
 * merely widen a read, it would hand every designer on the platform write access to every finished
 * workshop's fieldwork.
 *
 * The pool round therefore goes through `design_ratings.load_ratable_workshop_or_404`, a second and
 * deliberately narrow door: a design-workshop role is let through, and `pool_visible` then removes
 * every piece whose `peerRoundClosedAt` is blank. What is behind that door is the rateable rows and
 * their scores, and nothing else about the workshop.
 *
 * ── BOTH RATEABLE ENTITIES ARE OFFERED, AND THE COMMENT HERE ONCE SAID THE OPPOSITE ─────────────
 *
 * This page used to hardcode `entityKey="prototype"` under a comment asserting that
 * `peerRoundClosedAt` "only the prototype entity declares — a sketch has no such field, so
 * `pool_is_open` is false for every sketch". That was true of an older registry and is false of this
 * one. `stage_definitions.py` declares `f("peerRoundClosedAt", "Peer review closed on", DATE, A, …)`
 * on the `sketch` entity of stage 11, under a 25-line note whose own words are "The omission was the
 * outlier, not the rule" and "the field grants the ABILITY to open a sketch"; and
 * `design_ratings.POOL_OPENS_WHEN_FIELD` says in capitals "**A SKETCH CARRIES THE SAME KEY, AND THIS
 * NOTE USED TO SAY THE OPPOSITE**" and "nothing here special-cases the entity".
 *
 * The stale citation had a real cost, which is why it is written up rather than quietly deleted: the
 * set-aside sketches stage 11 exists to record — the designs never prototyped, which the registry's
 * own note calls "exactly the designs a wider pool might pick up" — were unreachable at level 2 for
 * every designer on the platform, because this page offered no way to ask for them. Nothing on the
 * server needed changing: `_entity_or_422` admits both members of `RATEABLE_ENTITIES` and
 * `pool_visible` gates per row, so this is a chooser and nothing more. Prototypes lead because they
 * are what most pool rounds are about; a workshop with nothing open in the chosen kind gets the same
 * one-sentence 404 as any other empty round.
 *
 * ── WHY THIS PAGE ASKS FOR A WORKSHOP, AND WHY THAT IS NOT A GAP IN THE PAGE ────────────────────
 *
 * `GET /design-ratings/rounds/POOL` requires a `workshopId`, for both rounds, and the reason is
 * structural rather than an unfinished API: the placed order IS `DwStageEntry.ordinal`, which
 * orders the rows of ONE collection inside ONE workshop. Two prototypes in two workshops can both
 * be ordinal 0, and an arrangement a designer made across a mixed list would have nowhere to be
 * stored. So the pool round is the same list read by a wider audience, not a wider list, and a
 * cross-workshop BROWSE is a different feature that needs its own answer — an endpoint that can
 * name the finished prototypes across the archive without also disclosing which workshop ids exist.
 *
 * Until that exists, a pool reviewer arrives here with a workshop in hand: a link from the designers
 * who made the pieces, or the id off a workshop's own page. The page says so rather than showing an
 * empty list that looks like an empty archive.
 *
 * ── WHAT A STRANGER IS TOLD ─────────────────────────────────────────────────────────────────────
 *
 * A workshop with nothing finished, a workshop this caller may not reach, and a workshop id that
 * never existed all answer 404 with one sentence. That is the API's decision and this page does not
 * try to tell them apart: the archive is keyed by cuid, and a page that distinguished them would
 * turn any designer login into an enumeration of the ministry's records one paste at a time.
 *
 * ── THE PERMISSION GATE IS HERE AS WELL AS IN THE NAV, AND A THIRD PLACE IS STILL OWED ──────────
 *
 * `load_ratable_workshop_or_404`'s first line refuses anybody outside `can_run_design_workshops`, so
 * a field contributor or a researcher who types this URL can never read a round. Until that was said
 * ON THE PAGE, what they got was the whole shell — header, workshop form and all — and then the
 * API's 404 rendered as "this round could not be read", which reads as a broken page rather than a
 * locked one. So the page refuses first, in the words the permission actually has.
 *
 * WHAT IS STILL OWED, NAMED RATHER THAN LEFT AS A GAP: a `ROUTE_GUARDS` row in `lib/permissions.ts`
 * and its matching row in `docs/PERMISSIONS.md` §5. That table is what `AppShell` applies above every
 * page and what `docs/tools/check-docs.mjs` cross-checks, and both files are outside this unit's
 * hands. The gate below is the page defending itself, which this repository considers a second line
 * and not the first.
 */

import { Suspense, useCallback, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Globe2, Lock } from "lucide-react";
import Link from "next/link";

import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";
import { ReviewPanel } from "@/components/sketches/ReviewPanel";
import type { RateableEntityKey } from "@/components/sketches/reviewRanking";
import { canRunDesignWorkshops, roleLabel } from "@/lib/permissions";

/**
 * The two kinds of piece a pool round can be read over.
 *
 * The same pair as `ENTITIES` on the workshop's own tab and the same pair as the server's
 * `RATEABLE_ENTITIES`. The child rows of a prototype — its stage logs, its material usage — are
 * parts of one piece rather than things a designer ranks against each other, and the API refuses
 * them by name.
 */
const ENTITIES: ReadonlyArray<{ key: RateableEntityKey; label: string; hint: string }> = [
  {
    key: "prototype",
    label: "Prototypes",
    hint: "Prototypes this workshop has declared finished."
  },
  {
    key: "sketch",
    label: "Sketches",
    hint: "Sketches this workshop has declared finished — including the ones it never prototyped."
  }
];

export default function DesignReviewPage() {
  return (
    <Suspense fallback={<PageHeader title="Design review" icon={<Globe2 className="h-5 w-5" aria-hidden />} />}>
      <DesignReview />
    </Suspense>
  );
}

function DesignReview() {
  const router = useRouter();
  const search = useSearchParams();
  const { user } = useAuth();
  const workshopId = (search.get("workshop") ?? "").trim();
  const [typed, setTyped] = useState(workshopId);
  const [entityKey, setEntityKey] = useState<RateableEntityKey>("prototype");

  const open = useCallback(
    (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      const value = typed.trim();
      if (!value) return;
      /*
        A PASTED LINK IS ACCEPTED AS WELL AS A BARE ID. A designer sent "come and look at this" will
        have the workshop's URL on their clipboard, not its cuid, and refusing it would send them
        editing a string by hand. The last non-empty path segment of a `/design-workshops/{id}/…`
        URL is the id; anything else is passed through untouched and the API answers for it.
      */
      const fromUrl = value.match(/design-workshops\/([^/?#]+)/);
      const id = fromUrl ? fromUrl[1] : value;
      router.push(`/design-review?workshop=${encodeURIComponent(id)}`);
    },
    [router, typed]
  );

  /*
    THE SAME PREDICATE THE API APPLIES FIRST, APPLIED FIRST HERE. Not a narrowing of it: this is a
    mirror of `load_ratable_workshop_or_404`'s own opening refusal, so nobody is stopped here whom
    the server would have served. It names the tier and offers two routes that are always open,
    rather than dead-ending on a padlock.
  */
  if (!canRunDesignWorkshops(user)) {
    return (
      <div>
        <PageHeader title="Design review" icon={<Globe2 className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">Designer access required</h1>
          <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
            Rating another workshop&apos;s finished pieces is part of the design work itself, so it belongs to
            designers, admins and the master admin. The rounds are read through a route that refuses everybody else
            before it looks at the workshop at all.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>. An admin can
            raise your access.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/guide" className="field-button-secondary">
              Open the walkthrough
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const chosen = ENTITIES.find((entity) => entity.key === entityKey) ?? ENTITIES[0];

  return (
    <div>
      <PageHeader
        title="Design review"
        description="Sketches and prototypes that a design workshop has declared finished, opened to every designer on the platform. Rate them, say what you would change, and see where the scores put them."
        icon={<Globe2 className="h-5 w-5" aria-hidden />}
      />

      <form onSubmit={open} className="panel mb-5 grid gap-3 p-4">
        <label className="grid gap-1">
          <span className="field-label">Which workshop&apos;s finished pieces</span>
          <input
            className="field-input"
            value={typed}
            onChange={(event) => setTyped(event.target.value)}
            placeholder="Paste the workshop's link, or its id"
            aria-describedby="design-review-why"
          />
        </label>
        <p id="design-review-why" className="max-w-3xl text-sm leading-6 text-ink-muted">
          The pool round is read one workshop at a time, because the ranking it shows is that workshop&apos;s own row
          order and there is no such thing as a place across two workshops. Browsing every finished prototype in the
          archive is a different question and does not have an answer yet — until it does, come here from a link the
          workshop&apos;s designers sent you.
        </p>
        <div>
          <button type="submit" className="field-button" disabled={!typed.trim()}>
            Open this round
          </button>
        </div>
      </form>

      {workshopId ? (
        <>
          {/*
            THE TWO RATEABLE ENTITIES ARE A CHOICE HERE TOO, for the reason in the header: a sketch
            carries `peerRoundClosedAt` exactly as a prototype does, so a workshop can open one to
            the pool, and a page with no chooser made those sketches unreachable at level 2 for
            everybody. `pool_visible` decides per row what any given reader sees, so choosing a kind
            with nothing open in it is answered by the API and reported as an empty round, not
            prevented here.
          */}
          <div role="group" aria-label="What to review" className="mb-4 flex flex-wrap items-center gap-2">
            {ENTITIES.map((entity) => {
              const active = entity.key === entityKey;
              return (
                <button
                  key={entity.key}
                  type="button"
                  aria-pressed={active}
                  onClick={() => setEntityKey(entity.key)}
                  className={
                    active
                      ? "rounded-md border border-purple-700 bg-purple-700 px-3 py-1.5 text-sm font-semibold text-white"
                      : "rounded-md border border-line-200 bg-card px-3 py-1.5 text-sm font-medium text-ink-700 hover:border-purple-300 hover:bg-purple-50"
                  }
                >
                  {entity.label}
                  {/* The choice is carried by a word as well as by the fill — colour never carries
                      meaning on its own in this app. */}
                  <span className={active ? "ml-2 text-[11px] font-normal text-white/80" : "sr-only"}>
                    {active ? "showing" : "not showing"}
                  </span>
                </button>
              );
            })}
            <span className="text-xs text-ink-muted">{chosen.hint}</span>
          </div>
          {/*
            `readsStageRows` IS FALSE HERE AND THAT IS THE WHOLE DIFFERENCE BETWEEN THE TWO LEVELS.
            The panel then never touches the workshop's draft, never asks for its stage, and offers no
            arrangement controls — because the ordinal a reorder would write belongs to a stage this
            caller is refused. The rating, which is what a pool reviewer actually contributes, works
            exactly as it does inside the workshop.
          */}
          <ReviewPanel workshopId={workshopId} round="POOL" readsStageRows={false} entityKey={entityKey} />
        </>
      ) : (
        <p className="panel px-4 py-6 text-center text-sm text-ink-muted">
          Nothing is open yet. Paste a workshop above to read its finished sketches and prototypes.
        </p>
      )}
    </div>
  );
}
