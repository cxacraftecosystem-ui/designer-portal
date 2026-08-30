"use client";

/**
 * Correct a design workshop's own record — requirement 27, the web half.
 *
 * ── THE SHAPE IS THE HOUSE SHAPE, NOT A THIRD ONE ───────────────────────────────────────────────
 *
 * This repository has exactly two ways to edit a record and a link that picks the wrong one loses
 * the record id: a REAL ROUTE at `/{type}/{id}/edit` (artisans, products, tools) or an INLINE form
 * on the list page reached by `/{type}?edit={id}` (crafts, workshops, processes). Design workshops
 * take the first, and there was never a choice to make: they already have a record page of their own
 * at `/design-workshops/[id]`, so the id never has to travel as a query parameter, and
 * `lib/workshopCodeLookup.ts` already resolves a scanned workshop card to that real path.
 *
 * So this file is `app/(protected)/tools/[id]/edit/page.tsx` with the same bones — `params`, a
 * fetch BY ID, a `PageHeader`, an error treatment, and a form seeded with `initial` — and two
 * additions this family needs, each argued where it appears: a workshop that exists only on this
 * device, and a repository that cannot be reached.
 *
 * ── WHY THE FORM IS SEEDED FROM THE SERVER ALONE, ON AN OFFLINE-FIRST SCREEN ────────────────────
 *
 * Every other page under `/design-workshops/[id]` reads `lib/designWorkshopStore`'s local draft
 * FIRST and treats the server read as a refresh, so a fortnight of stages can be filled in with no
 * signal at all. This page deliberately does not, and the reason is the one rule the form exists to
 * keep:
 *
 *   `PATCH /design-workshops/{id}` reads its body with `exclude_unset=True`, so what is SENT is the
 *   difference between what is on screen and what the form was seeded with. **A wrong seed is
 *   therefore a wrong diff**, and a wrong diff is not a stale screen — it is a write.
 *
 * Two ways a local seed can be wrong, and both are ordinary rather than exotic. `ensureDraft`
 * fabricates a header of empty strings for a workshop this browser has merely OPENED, which would
 * draw a titleless, craftless form over a workshop the office has fully filled in. And a draft this
 * device holds can legitimately be BEHIND the repository — a co-designer or an admin corrected the
 * cluster yesterday — so a form seeded from it shows the old value, and a designer "leaving it
 * alone" is in fact leaving alone a value that no longer exists.
 *
 * The saving act is online-only in any case: there is no outbox arm for a header PATCH (see the
 * form's failure branch), exactly as there is none for the status on the record page. So an offline
 * seed would buy a form that cannot be submitted, drawn from values that may not be current, whose
 * only new capability is getting the diff wrong. The honest answer is the panel below, which says
 * that this one act needs a connection and that nothing else on the workshop does.
 */

import { use, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { DraftingCompass, RefreshCw } from "lucide-react";

import { DesignWorkshopHeaderForm } from "@/components/designworkshop/DesignWorkshopHeaderForm";
import { PageHeader } from "@/components/PageHeader";
import { ApiError } from "@/lib/api";
import { getDesignWorkshop, type DwSummary } from "@/lib/designWorkshops";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";
import { isUnreachable } from "@/lib/offline";

/** What the load settled on. `null` while it is still running. */
type LoadState =
  | { kind: "loaded"; record: DwSummary }
  /** The repository did not answer. Nothing is drawn from the local copy — see the file header. */
  | { kind: "offline" }
  /** This workshop was started on this device and has never reached the repository. */
  | { kind: "local-only" }
  /** 404: either no such workshop or not one this account may open, and the server will not say. */
  | { kind: "unopenable" }
  /** The repository answered and refused, or something else went wrong. Its own words. */
  | { kind: "failed"; message: string };

export default function EditDesignWorkshopPage({ params }: { params: Promise<{ id: string }> }) {
  // Next 16 hands route params over as a promise; `use` unwraps it in a client component.
  const { id } = use(params);
  const [state, setState] = useState<LoadState | null>(null);
  /** Bumped by Retry, which is the only thing that re-runs the load. */
  const [attempt, setAttempt] = useState(0);

  const retry = useCallback(() => {
    setState(null);
    setAttempt((previous) => previous + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      /*
        A `dwlocal-…` ID IS A WORKSHOP THIS BROWSER MINTED, and it may or may not have become a real
        record since. `loadDraft` and never `ensureDraft`: this page must not bring a draft into
        existence for an address somebody typed, and the fabricated empty header that would come of
        it is precisely what the header of this file refuses to seed a form from.
      */
      let serverId = id;
      if (isLocalWorkshopId(id)) {
        const local = await loadDraft(id);
        if (cancelled) return;
        if (!local?.remoteId) {
          setState({ kind: "local-only" });
          return;
        }
        // It has synced since, and the local id goes on resolving afterwards — so the address is
        // still `dwlocal-…` while the record it names is the server's. Ask about that one.
        serverId = local.remoteId;
      }

      try {
        // BY ID, and never looked up in a list: the reader arrives from the workshop's own page and
        // the row is usually nowhere near page one of a twenty-row list. Same rule as every other
        // edit route in this app.
        const record = await getDesignWorkshop(serverId);
        if (cancelled) return;
        setState({ kind: "loaded", record });
      } catch (err) {
        if (cancelled) return;
        // `isUnreachable`, NOT `isTransient` — the split every screen in this family makes.
        // `isTransient` answers "is it worth retrying" and counts every 5xx as yes, so a repository
        // that answered and then failed would raise the "there is no connection" panel and send the
        // designer to look at their signal for a fault the server had already reported.
        if (isUnreachable(err)) {
          setState({ kind: "offline" });
          return;
        }
        if (err instanceof ApiError && err.status === 404) {
          setState({ kind: "unopenable" });
          return;
        }
        setState({
          kind: "failed",
          message: err instanceof Error && err.message.trim() ? err.message : "Unable to load this design workshop."
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, attempt]);

  const recordHref = `/design-workshops/${id}`;

  return (
    <>
      <PageHeader
        title="Edit workshop details"
        description={
          state?.kind === "loaded"
            ? state.record.title
            : "The workshop's own record — its title, its report template, where and when it ran, its notes and the workshop it is filed against."
        }
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={recordHref} className="field-button-secondary">
            Back to the workshop
          </Link>
        }
      />

      {state === null ? <div className="text-sm text-ink-700">Loading...</div> : null}

      {state?.kind === "loaded" ? <DesignWorkshopHeaderForm initial={state.record} /> : null}

      {state?.kind === "offline" ? (
        /*
          AMBER AND NOT RED, because nothing is broken and nothing has been lost: this is the one act
          on this workshop that needs a connection, and every other one still works. `SubmissionCard`
          on the record page carries the same constraint and the same argument — the offline queue
          holds stage answers, photographs and the artisan's consent, and does not hold a change to
          the workshop's own row.

          A PANEL AND NOT AN EMPTY FORM. A form drawn here would either be blank (there is no server
          answer to seed it with) or drawn from a local copy that can be behind the repository — and
          in both cases pressing Save would send the difference between the screen and a seed that is
          not what is stored. See this file's header.
        */
        <section className="panel grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">The repository could not be reached</h2>
          <p className="text-sm leading-6 text-ink-700">
            The workshop&apos;s own details — its title, report template, craft, cluster, place, dates, notes and linked
            workshop — can only be corrected while there is a connection. Unlike your stages, a change to the workshop
            record itself is not held in the offline queue, so this screen does not offer boxes it could not save.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            Everything else on this workshop still works with no signal: the 22 stages, the photographs and the
            workshop&apos;s own questions are all kept in this browser and sent when the connection returns.
          </p>
          <div className="flex flex-wrap gap-2">
            <button type="button" className="field-button" onClick={retry}>
              <RefreshCw className="h-4 w-4" aria-hidden />
              Try again
            </button>
            <Link href={recordHref} className="field-button-secondary">
              Back to the workshop
            </Link>
          </div>
        </section>
      ) : null}

      {state?.kind === "local-only" ? (
        /*
          A workshop created on this device that has never reached the repository. There is no row to
          PATCH, so a form here would be a form whose Save cannot work — and a SENTENCE rather than a
          disabled control, for the reason the record page's status card gives: a greyed button
          refuses a press without saying why, which is how somebody concludes the app is broken.

          THE TITLE AND THE REST ARE STILL EDITABLE, just not here: they are on the draft, and the
          way to change them before it syncs is the workshop's own pages. Saying so is the whole
          point of this panel.
        */
        <section className="panel grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">This workshop is still only on this device</h2>
          <p className="text-sm leading-6 text-ink-700">
            It was created here and has not reached the repository yet, so there is no record to correct. Everything you
            have typed is saved in this browser and nothing is at risk. Sync it from the design workshops list — the
            details can be corrected the moment it lands.
          </p>
          <div className="flex flex-wrap gap-2">
            <Link href="/design-workshops" className="field-button">
              All design workshops
            </Link>
            <Link href={recordHref} className="field-button-secondary">
              Back to the workshop
            </Link>
          </div>
        </section>
      ) : null}

      {state?.kind === "unopenable" ? (
        /*
          THE WORDING STAYS AMBIGUOUS BETWEEN THE TWO CAUSES BECAUSE THE SERVER'S IS.
          `load_workshop_or_404` answers the identical 404 for "no such record" and "not one this
          account may open", with a comment saying it will not distinguish them: a 403 there would
          confirm the id exists to exactly the people the clause is turning away. "Ask to be added as
          a viewer" is the remedy for both, and it is one the reader can act on today. Copied in
          substance from the record page's own dead-end panel so the two screens say one thing.
        */
        <section className="panel grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">
            There is no design workshop at this address that this account can open
          </h2>
          <p className="text-sm leading-6 text-ink-700">
            Either no such workshop exists, or it belongs to another designer and has not been shared with you. If a
            colleague sent you this link, ask them to add you as a viewer of their workshop — an administrator can also
            do it — and then open the link again.
          </p>
          <Link href="/design-workshops" className="field-button-secondary">
            All design workshops
          </Link>
        </section>
      ) : null}

      {state?.kind === "failed" ? (
        // Page-level banner, the third of the four error treatments: the whole screen failed, so the
        // message belongs under the header rather than against any one box. `apiFetch` has already
        // run FastAPI's `detail` through `describeApiDetail`, so this is a sentence and not
        // "[object Object]" — a 403 from the designer gate and the 409 for a soft-deleted workshop
        // both arrive readable.
        <div className="grid gap-3">
          <div
            role="alert"
            className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700"
          >
            {state.message}
          </div>
          <div className="flex flex-wrap gap-2">
            <button type="button" className="field-button-secondary" onClick={retry}>
              <RefreshCw className="h-4 w-4" aria-hidden />
              Try again
            </button>
            <Link href={recordHref} className="field-button-secondary">
              Back to the workshop
            </Link>
          </div>
        </div>
      ) : null}
    </>
  );
}
