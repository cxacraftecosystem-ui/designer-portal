"use client";

/**
 * THE MINISTRY DASHBOARD — every workshop on the platform, its designers' progress, and the lists to
 * download.
 *
 * ── WHAT THIS SCREEN IS FOR, AND WHY NOTHING IN THE PRODUCT COULD ANSWER IT ─────────────────────
 *
 * Every other ministry screen is about ONE workshop: one plan row, one sanction order, one posting,
 * one report read back. Nothing answered "how is the programme going?" — which workshops are moving,
 * which have stalled on a designer who never opened stage 1, which are finished — and answering it
 * meant opening workshops one at a time and remembering. `app/services/ministry_dashboard.py`
 * carries the server half of that argument, including why none of the three existing whole-estate
 * reads could be used.
 *
 * ── THE SCOPE SENTENCE IS THE MOST IMPORTANT STRING ON THE PAGE ─────────────────────────────────
 *
 * A Ministry Administrator and the master admin read the estate; an Assistant Director and a
 * Regional Director read the workshops they were POSTED to — the same `oversight_by_clause` that
 * scopes `/officers/monitored`, whose own header says *"an officer with no oversight row sees an
 * empty page, and that IS the whole scope."* An officer reading "every workshop on the platform"
 * over their own four would be told the national programme is four workshops large, which is this
 * repository's most repeated bug class with a title on it. **The sentence comes off the wire**
 * (`scopeLabel`) and is printed verbatim — a paraphrase composed here would be a second description
 * of one decision, and the day the scope moves only one of them would move with it.
 *
 * ── IT RE-READS ITSELF, AND THAT ARGUES PAST A STANDING REFUSAL ─────────────────────────────────
 *
 * `useOpenTaskCount` states the rule this page is an exception to: *"── NO TIMER, DELIBERATELY ── A
 * background poll on every page, forever, is a real cost paid by every signed-in account."* That
 * ruling is about APP-WIDE CHROME — a badge on every screen, for every tier — and the argument does
 * not reach here, for three reasons that are stated rather than assumed:
 *
 *   1. IT IS ONE SCREEN AND NOT EVERY SCREEN. The timer is mounted by this page and dies with it.
 *   2. ITS AUDIENCE IS FOUR TIERS, NOT THE WHOLE USER BASE — the objection that endpoint openness
 *      made decisive for the task and access badges does not apply.
 *   3. THE SCREEN'S WHOLE PURPOSE IS CURRENCY. A ministry officer leaves this open on a second
 *      monitor; a register that silently showed this morning's figures at four in the afternoon
 *      would be worse than no register, because it would be believed.
 *
 * And it is bounded by everything the ruling would have asked for: it PAUSES while the tab is hidden
 * and reads once on return, it never fetches while a request is in flight, and every tick enters the
 * same single load path the filters and the pager use.
 *
 * ── THE LIVE REGION RULES, WHICH ARE SHARPER HERE THAN ANYWHERE ELSE IN THE APP ─────────────────
 *
 * `EntityForm`'s note gives the three conditions under which a moving number may sit in a live
 * region — it changes only when the reader deliberately acted, it appears near a threshold rather
 * than from first paint, and what is announced is the consequence of the act just performed — and a
 * self-refreshing table fails all three. `role="status"` implies `aria-atomic`, so every tick would
 * re-read the WHOLE region: an officer using a screen reader would be interrupted every thirty
 * seconds, forever, and could never finish a row. The remedy that note states verbatim is the one
 * taken here: *"A continuous readout would have to be `aria-live=\"off\"` with the sentence said
 * some other way."* So the table is `aria-live="off"`, the "last read" line is plain text with a
 * real Refresh button beside it, and every progress figure is a `role="progressbar"` carrying its
 * sentence in `aria-valuetext` — `GalleryProgress` is the worked precedent.
 */

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Check, Download, LayoutDashboard, Lock, RefreshCw, ShieldCheck, UserCog, Users } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { MegaCard } from "@/components/dashboard/MegaCard";
import { useMegaCards } from "@/components/dashboard/useMegaCards";
import { EmptyState } from "@/components/EmptyState";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { ResizableTh } from "@/components/ResizableTh";
import { SearchInput } from "@/components/SearchInput";
import { StatusBadge } from "@/components/StatusBadge";
import { ApiError } from "@/lib/api";
import { formatDate, formatDateTime } from "@/lib/format";
import {
  DEFAULT_REGISTER_KIND,
  PEOPLE_KINDS,
  REGISTER_KINDS,
  downloadBeneficiaries,
  downloadRegister,
  fetchRegisterEntitlements,
  fetchRegisterSummary,
  hasProgressFigure,
  listRegisterDesignWorkshops,
  listRegisterOtherWorkshops,
  listRegisterPeople,
  personProgressSentence,
  progressSentence,
  standingMembersSentence,
  standingsFor,
  unclassifiedSentence,
  type DesignRegisterPage,
  type OtherRegisterPage,
  type RegisterEntitlements,
  type PeopleKind,
  type RegisterKind,
  type RegisterPeoplePage,
  type RegisterPerson,
  type RegisterSummary
} from "@/lib/ministryDashboard";
import { isUnreachable } from "@/lib/offline";
import { canSeeMinistryDashboard, roleLabel } from "@/lib/permissions";

const PAGE_SIZE = 20;

/** 300 ms, this app's number, and clearing the box does not wait — the sibling registers' rule. */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * How often the register re-reads itself while the tab is in front.
 *
 * THIRTY SECONDS, AND THE TWO EXISTING POLLS ARE FIFTEEN. Those two (`ExistingMedia`'s transcript
 * poll, `MediaJobsPanel`'s job queue) are armed only while a job the reader is WATCHING is in
 * flight, and they stop the moment it lands; this one runs for as long as the screen is open. Half
 * their rate is the trade: a stage save somewhere in the country reaches this register inside half a
 * minute, and an officer who leaves the page open for an afternoon costs the API 120 reads rather
 * than 240. `docs/SCALABILITY.md` sizes this deployment against a single-worker box.
 */
const REFRESH_MS = 30_000;

/**
 * How many people one card shows at a time.
 *
 * Smaller than the workshop register's page because a person's row is three lines of figures rather
 * than one, and because three of these cards can be open at once. The server clamps `pageSize` to
 * `MAX_PAGE_SIZE` regardless, so this is a readability choice and not a limit.
 */
const PEOPLE_PAGE_SIZE = 20;

/** One glyph per people register. The fourth channel, beside the title, the note and the count. */
const PEOPLE_ICONS = { designers: Users, officers: UserCog, inspectors: ShieldCheck } as const;

/**
 * What a failed read says, without ever implying the register is empty.
 *
 * The shape is `/officers/monitored`'s `describeFailure`, and the last clause of each sentence is the
 * load-bearing half: an empty table under an error banner reads as "there are no workshops", which is
 * the one thing this screen must never say by accident.
 */
function describeFailure(error: unknown): string {
  if (!(error instanceof ApiError) || isUnreachable(error)) {
    return "This device cannot reach the repository, so the register could not be read. It is not empty — nothing was read at all. Check the connection and try again.";
  }
  if (error.status === 403) {
    return `${error.message} The register is not empty — it was not read at all.`;
  }
  return `${error.message} The register could not be read, which is not the same as there being no workshops.`;
}

export default function MinistryDashboardPage() {
  const { user, loading } = useAuth();

  const [kind, setKind] = useState<RegisterKind>(DEFAULT_REGISTER_KIND);
  const [standing, setStanding] = useState("");
  const [query, setQuery] = useState("");
  const [applied, setApplied] = useState("");
  const [page, setPage] = useState(1);

  const [data, setData] = useState<DesignRegisterPage | OtherRegisterPage | null>(null);
  /**
   * WHICH REGISTER THE ROWS IN `data` CAME FROM, which is NOT always the one the switch now shows.
   *
   * ⚠ THE TABLE RENDERS FROM THIS AND NEVER FROM `kind`, AND THAT IS A CORRECTNESS FIX RATHER THAN
   * a nicety. `kind` changes the instant the officer presses the other button; the rows arrive one
   * round trip later. Rendering `kind === "design" ? <DesignTable rows={data.items as …}/>` would,
   * for every frame in between, cast the OTHER register's rows to this one's shape and read columns
   * that are not on them — a table of empty cells under the right headings, which is indistinguishable
   * from a register that genuinely holds nothing. That is this repository's most repeated bug class
   * arriving through a type assertion.
   *
   * The two tables are deliberately two components over two row types rather than one over a union,
   * so the only place the two shapes can be confused is this pairing — and this is it being made
   * impossible.
   */
  const [dataKind, setDataKind] = useState<RegisterKind>(DEFAULT_REGISTER_KIND);
  const [summary, setSummary] = useState<RegisterSummary | null>(null);

  /**
   * ── THE THREE PEOPLE REGISTERS, ADDED 2026-09-20 ──────────────────────────────────────────
   *
   * Owner: *"the dashboard carries no information about designers, ad, rd, inspectors, make it
   * extremely more capable and powerful, there is no specific card for designers where they can do
   * their stuff"*. Each is a collapsible mega card wearing the mango tone, and each holds its own
   * page because three registers sharing one pager would page all three at once.
   *
   * ⚠ A FAILED PEOPLE READ IS `null` AND A READ THAT FOUND NOBODY IS AN EMPTY PAGE, and the card
   * says which. This screen's whole discipline is that "none" and "not read" are different facts;
   * collapsing them here would be the same defect one surface out from `_roll_up`.
   */
  const [people, setPeople] = useState<Partial<Record<PeopleKind, RegisterPeoplePage>>>({});
  const [peopleError, setPeopleError] = useState<Partial<Record<PeopleKind, string>>>({});
  const [peoplePage, setPeoplePage] = useState<Partial<Record<PeopleKind, number>>>({});
  const [entitlements, setEntitlements] = useState<RegisterEntitlements | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [readAt, setReadAt] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  /**
   * WHAT "Refresh now" SAYS OUT LOUD — and the only live region on this page.
   *
   * ⚠ IT IS NOT THE "Read at …" LINE, AND THAT DISTINCTION IS THE WHOLE DESIGN. That line changes on
   * a TIMER, and `EntityForm`'s three-condition test refuses a live region for exactly that: a
   * readout that moves without the reader acting interrupts them every thirty seconds forever. So it
   * stays plain text.
   *
   * A MANUAL REFRESH PASSES ALL THREE CONDITIONS THE TIMER FAILS: the reader deliberately acted, it
   * is not announced from first paint, and what is announced is the consequence of the act just
   * performed. Without it the button is inaudible — a screen-reader user presses Refresh and nothing
   * whatsoever is announced, so they cannot tell it from a dead control.
   *
   * The region is MOUNTED FROM FIRST PAINT AND EMPTY, which is `cappedList`'s rule: assistive tech
   * only announces mutations inside a region that already existed, so one created in the same commit
   * as its first sentence announces nothing at all.
   */
  const [refreshNote, setRefreshNote] = useState("");
  /** Set by the button, read by the load — so only a MANUAL read announces its outcome. */
  const manualRefresh = useRef(false);
  const [busyDownload, setBusyDownload] = useState<string | null>(null);

  /**
   * EVERY TRIGGER ENTERS THE ONE LOAD PATH BY BUMPING THIS TOKEN — the poll, the type switch, the
   * standing switch, the debounced search and the pager. `MediaJobsPanel` is the precedent and the
   * reason is the race its sibling had to install a counter to survive: two independent write paths
   * into one list state leave a generation guard protecting only one of them.
   */
  const [loadToken, setLoadToken] = useState(0);

  /** A generation counter rather than an abort: `apiFetch` takes no `AbortSignal`. */
  const currentLoad = useRef(0);

  /**
   * IS A READ IN THE AIR RIGHT NOW — read by the TIMER and by nothing else.
   *
   * ⚠ THE GENERATION COUNTER IS NOT ENOUGH ON ITS OWN, AND THIS PAGE IS THE ONE SCREEN WHERE THAT
   * MATTERS. On the four sibling list pages every load is started by a PERSON, so superseding an
   * older one is exactly right: they typed again, they meant the newer answer. Here a load is also
   * started by a clock, and the two interact badly. If a read takes longer than {@link REFRESH_MS}
   * — a village connection, or the register scoring a hundred workshops — the next tick bumps the
   * token, the guard discards the reply that was about to land, and the tick after that does it
   * again. The register never renders at all: "Loading…" for ever, with a request stacking up every
   * thirty seconds on the one screen whose whole purpose is to be current.
   *
   * So the TICK yields to a read in flight. A person's action still supersedes one — pressing the
   * other register, or a page, must not wait for a slow poll — which is why the guard is in `tick`
   * and not in the load effect.
   */
  const inFlight = useRef(false);

  const allowed = !loading && canSeeMinistryDashboard(user);

  /*
    Every people card is SHUT on a first visit, which is the owner's ruling and also the reason
    these three reads cost nothing until somebody wants them: the effect below fetches only what is
    open. `useMegaCards`' own header carries the argument for remembering the choice afterwards.
  */
  const peopleCards = useMegaCards("ministry-dashboard");

  /* ── The debounced search settles into `applied`, and a narrowed list starts at page one ───── */
  useEffect(() => {
    const term = query.trim();
    const timer = window.setTimeout(
      () => {
        setApplied(term);
        // A narrowed list is a different list, and staying on page 3 of it shows "Page 3 of 1" with
        // nothing under it.
        setPage(1);
      },
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [query]);

  /* ── Switching register or standing is a different list: back to page one, and re-read ─────── */
  const chooseKind = useCallback((next: RegisterKind) => {
    setKind(next);
    // THE STANDING IS CLEARED, NOT CARRIED. The two registers have different vocabularies — a
    // lifecycle group on one, a review status on the other — so a value carried across would be a
    // word the new list has never heard of. The server ignores an unrecognised narrowing rather than
    // refusing it, so carrying it would silently show "Everything" under a button reading "Ongoing".
    setStanding("");
    setPage(1);
  }, []);

  const chooseStanding = useCallback((next: string) => {
    setStanding(next);
    setPage(1);
  }, []);

  /* ── The one load path ─────────────────────────────────────────────────────────────────────── */
  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    const generation = (currentLoad.current += 1);

    // CAPTURED, so the answer can be paired with the question that asked it. `kind` is state and is
    // read at render time; this is the value this particular request was issued for.
    const requested = kind;
    const request =
      requested === "design"
        ? listRegisterDesignWorkshops({ page, pageSize: PAGE_SIZE, search: applied || undefined, standing: standing || undefined })
        : listRegisterOtherWorkshops({ page, pageSize: PAGE_SIZE, search: applied || undefined, standing: standing || undefined });

    inFlight.current = true;
    request
      .finally(() => {
        // CLEARED FOR EVERY OUTCOME AND BEFORE THE GUARD BELOW, so a superseded read — which returns
        // early out of both handlers — still releases the timer. A flag only cleared on the path
        // that renders would be a poll that stops for ever the first time a person clicks mid-read.
        inFlight.current = false;
      })
      .then((result) => {
        if (generation !== currentLoad.current) return;
        if (cancelled) return;
        setData(result);
        // Set together with the rows, in the same commit, from the request that produced them —
        // never from `kind`, which may already have moved on. See `dataKind`'s note.
        setDataKind(requested);
        setError(null);
        setReadAt(new Date().toISOString());
        if (manualRefresh.current) {
          manualRefresh.current = false;
          setRefreshNote(`Register re-read. ${result.total} ${result.total === 1 ? "workshop" : "workshops"}.`);
        }
      })
      .catch((err) => {
        if (generation !== currentLoad.current) return;
        if (cancelled) return;
        // THE ROWS STAY ON SCREEN. `DeletedWorkshopsCard`'s rule: *"A card that emptied itself on a
        // dropped connection would report a full trash as an empty one."* On a register that
        // re-reads itself every thirty seconds, emptying on a failed tick would blank a live national
        // programme to nothing several times an hour.
        setError(describeFailure(err));
        if (manualRefresh.current) {
          manualRefresh.current = false;
          // The FAILURE is announced too. A button that says nothing when it fails is worse than one
          // that says nothing at all, because the silence reads as success.
          setRefreshNote("The register could not be re-read. The message above says what the server answered.");
        }
      });

    return () => {
      cancelled = true;
    };
  }, [allowed, kind, page, applied, standing, loadToken]);

  /* ── The summary, re-read on the same token so the tiles and the table cannot disagree ─────── */
  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    fetchRegisterSummary()
      .then((result) => {
        if (!cancelled) setSummary(result);
      })
      .catch(() => {
        // Silent, and the tiles simply do not appear. A failed COUNT is not a failed register, and a
        // second red banner about a number nobody asked for would bury the one about the rows.
      });
    return () => {
      cancelled = true;
    };
  }, [allowed, loadToken]);

  /*
    ── THE PEOPLE READS, ON THE SAME TOKEN AS EVERY OTHER READ ON THIS SCREEN ──────────────────

    `loadToken` is the one thing the poll, the filters, the pager and the manual Refresh all bump, so
    depending on it here is what keeps the three people cards in step with the workshop table rather
    than drifting a poll behind it. A second independent timer would be a second answer to "as of
    when", on a screen whose whole claim is that it says so.

    ONLY WHAT IS OPEN IS FETCHED. A shut card issues no request — which is most of them, most of the
    time — and opening one fetches it immediately rather than at the next tick, because a card that
    sat blank for up to thirty seconds would read as a card with nothing in it.

    `cancelled` AND NOT AN ABORT SIGNAL: `apiFetch` takes none. The flag is what stops a response
    that arrives after a re-render writing over newer state, which is the same convention the
    workshop read above uses and the same one `list-fetch-generation-unit.spec.ts` pins.
  */
  const openPeople = PEOPLE_KINDS.filter((entry) => peopleCards.isOpen(entry.id))
    .map((entry) => entry.id)
    .join(",");

  useEffect(() => {
    if (!allowed || !openPeople) return;
    let cancelled = false;
    const kinds = openPeople.split(",") as PeopleKind[];
    kinds.forEach((peopleKind) => {
      listRegisterPeople(peopleKind, { page: peoplePage[peopleKind] ?? 1, pageSize: PEOPLE_PAGE_SIZE })
        .then((result) => {
          if (cancelled) return;
          setPeople((current) => ({ ...current, [peopleKind]: result }));
          setPeopleError((current) => ({ ...current, [peopleKind]: undefined }));
        })
        .catch((err: unknown) => {
          if (cancelled) return;
          /*
            NAMED ON ITS OWN CARD, never in the page's banner. The banner belongs to the workshop
            register; a second red box there about a list nobody has opened would bury the one that
            is about what the reader is looking at. And the card keeps its heading, so a failed read
            is a card that says why rather than a card that is not there.
          */
          setPeopleError((current) => ({
            ...current,
            [peopleKind]: err instanceof Error ? err.message : "This list could not be read."
          }));
        });
    });
    return () => {
      cancelled = true;
    };
    // `peoplePage` is read rather than watched as a whole object — the pager below bumps the token,
    // which is what re-runs this. Depending on the object itself would re-fetch every open card on
    // every page change of any one of them.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [allowed, loadToken, openPeople]);

  /* ── What this account may take out, asked once ────────────────────────────────────────────── */
  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    fetchRegisterEntitlements()
      .then((result) => {
        if (!cancelled) setEntitlements(result);
      })
      .catch(() => {
        // Unknown stays unknown: the beneficiaries button is drawn and its refusal, if there is one,
        // arrives from the server. Hiding a control because a probe failed is the worse error — it
        // reads as a feature that was removed.
      });
    return () => {
      cancelled = true;
    };
  }, [allowed]);

  /* ── The timer, and the visibility rules around it ─────────────────────────────────────────── */
  useEffect(() => {
    if (!allowed) return;

    const tick = () => {
      // THE POLL DOES NOT FETCH. It bumps the token the single load effect depends on, so the poll,
      // the filters, the pager and the manual Refresh are one request path and the generation guard
      // above is the only race protection the screen needs.
      if (document.visibilityState === "hidden") return;
      // A READ IS ALREADY IN THE AIR — yield. See `inFlight`: bumping the token here is what makes a
      // link slower than the interval unable to ever finish a read.
      if (inFlight.current) return;
      setLoadToken((token) => token + 1);
    };

    const timer = window.setInterval(tick, REFRESH_MS);

    // COMING BACK TO THE TAB READS ONCE, IMMEDIATELY. A hidden tab's ticks are skipped above rather
    // than the interval being torn down, so the officer who returns after an hour does not wait up to
    // thirty seconds to find out they are looking at an hour-old register. This is the same event the
    // three shared count stores use for the same reason; they use it to RE-FIRE and so does this.
    const onVisible = () => {
      if (document.visibilityState === "visible") setLoadToken((token) => token + 1);
    };
    document.addEventListener("visibilitychange", onVisible);

    return () => {
      window.clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [allowed]);

  /**
   * A NARROWED REGISTER IS A SHORTER ONE, AND THE PAGE NUMBER HAS TO COME BACK WITH IT.
   *
   * Every filter on this page resets to page one, so the ordinary route is covered. What is not is
   * the register shrinking UNDER a page the reader is already on — the poll re-reads every thirty
   * seconds, and a workshop leaving the current standing group between two ticks can take the last
   * page with it. `Pagination` would then print "Page 3 of 1" over an empty table, and the empty
   * state beside it calls a full national register "genuinely empty" — this repository's most
   * repeated bug class, arriving from the clock rather than from a click.
   *
   * Guarded on `pages > 0` so a genuinely empty register (`pages: 0`) is left alone: that one IS
   * empty, and resetting its page would be a loop.
   */
  useEffect(() => {
    if (!data) return;
    if (data.pages > 0 && page > data.pages) setPage(1);
  }, [data, page]);

  /* ── Downloads ─────────────────────────────────────────────────────────────────────────────── */
  const runDownload = useCallback(
    async (name: string, run: () => Promise<unknown>) => {
      setDownloadError(null);
      setBusyDownload(name);
      try {
        await run();
      } catch (err) {
        setDownloadError(
          err instanceof ApiError
            ? err.message
            : "The download could not be started. Check the connection and try again."
        );
      } finally {
        setBusyDownload(null);
      }
    },
    []
  );

  /* ── The refusal ───────────────────────────────────────────────────────────────────────────── */
  if (!loading && !canSeeMinistryDashboard(user)) {
    return (
      <div>
        <PageHeader title="Ministry dashboard" icon={<LayoutDashboard className="h-5 w-5" aria-hidden />} />
        <section className="panel px-6 py-14 text-center" aria-live="polite">
          {/*
            PURPLE, DELIBERATELY. This route is a ministry surface and `AppShell` stamps
            `data-surface="ministry"` on it — but only when the page is actually being SERVED, and a
            refusal is shown to somebody who is NOT a ministry account. Putting the ministry's own
            colour around the notice that they are not of the ministry would be a lie told in colour.
            `/officers/monitored` carries the identical padlock for the identical reason.
          */}
          <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
            <Lock className="h-5 w-5" aria-hidden />
          </div>
          <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">Ministry access required</h1>
          <p className="mx-auto mt-3 max-w-xl text-sm leading-6 text-ink-500">
            The ministry dashboard gathers every design &amp; prototype workshop and every other
            workshop on the platform, with each designer&apos;s progress, for the ministry&apos;s own
            posts — Assistant Director, Regional Director and Ministry Administrator — and the master
            admin. Admins read the same estate on Cross-workshop analytics in the settings hub;
            designers read the workshops they are on through Design workshops.
          </p>
          <p className="mt-3 text-xs text-ink-500">
            You are signed in as <span className="font-medium text-ink-700">{roleLabel(user?.role)}</span>.
          </p>
          <div className="mt-7 flex flex-wrap items-center justify-center gap-3">
            <Link href="/dashboard" className="field-button">
              Back to dashboard
            </Link>
            <Link href="/design-workshops" className="field-button-secondary">
              Design workshops
            </Link>
          </div>
        </section>
      </div>
    );
  }

  const rows = data?.items ?? [];
  const standings = standingsFor(kind);
  /* The switch and the rows can legitimately disagree for one round trip. Saying so is cheaper than
     a reader wondering why the headings and the button do not match — and far cheaper than clearing
     the table, which would make a deliberate switch look like an empty register. */
  const switching = data !== null && dataKind !== kind;
  const membersSentence = standingMembersSentence(data ?? null, standing);
  const unclassified = summary ? unclassifiedSentence(summary.designWorkshops.unclassified) : null;

  return (
    <div>
      <PageHeader
        title="Ministry dashboard"
        description="Every workshop the platform holds — ongoing, completed and newly registered — with each designer's progress through the stages."
        icon={<LayoutDashboard className="h-5 w-5" aria-hidden />}
      />

      {/* THE SCOPE, IN THE SERVER'S OWN WORDS. See the header: this is the sentence that stops an
          officer's four workshops reading as the whole national programme. */}
      {data ? (
        /*
          `.section-band` AND NOT `bg-surface-50` SPELLED OUT. The scoped ministry block repaints the
          RECIPE, so this band picks up the surface's light accent without this file knowing anything
          about the ramp — which is the whole point of scoping recipes rather than editing pages.
        */
        <p className="section-band mb-4 border border-line-200 px-4 py-3 text-sm leading-6 text-ink-700">
          {data.scopeLabel}
        </p>
      ) : null}

      {error ? (
        /* `role="alert"` for the same reason the download banner two sections down has it, and its
           absence here was the inconsistency review named: this one is the MORE important of the two
           — it is the sentence that stops an empty table reading as an empty register — and it was
           the silent one. An alert interrupts, which is right for a failure the reader did not cause
           and cannot see; the self-refreshing TABLE stays `aria-live="off"` for the opposite reason. */
        <div role="alert" className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      {/* ── At a glance ─────────────────────────────────────────────────────────────────────── */}
      {summary ? (
        <section aria-labelledby="ministry-register-glance" className="mb-6">
          <h2 id="ministry-register-glance" className="mb-3 font-display text-lg font-bold text-ink-900">
            The programme at a glance
          </h2>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
            {[
              { label: "Design & prototype", value: summary.designWorkshops.total },
              { label: "Ongoing", value: summary.designWorkshops.ongoing },
              { label: "Completed", value: summary.designWorkshops.completed },
              { label: "Newly registered", value: summary.designWorkshops.registered },
              { label: "Other workshops", value: summary.otherWorkshops.total }
            ].map((card) => (
              <div key={card.label} className="panel p-4">
                <div className="text-xs font-medium text-ink-500">{card.label}</div>
                {/* `tabular-nums` so a figure ticking from 9 to 10 does not shift the line. */}
                <div className="mt-2 font-display text-2xl font-bold tabular-nums text-ink-900">{card.value}</div>
              </div>
            ))}
          </div>
          {unclassified ? <p className="mt-2 text-xs leading-5 text-ink-500">{unclassified}</p> : null}
          {/* THE TWO HALVES ARE SCOPED DIFFERENTLY AND THE TILES SAY SO, because the caption above the
              table is the DESIGN half's and a reader would otherwise carry it across to the tile
              beside it. Drawn only when they actually differ — on a Ministry Admin's screen both are
              the estate and the sentence would be noise. */}
          {summary.designWorkshops.scope !== summary.otherWorkshops.scope ? (
            <p className="mt-2 text-xs leading-5 text-ink-500">{summary.otherWorkshops.scopeLabel}</p>
          ) : null}
        </section>
      ) : null}

      {/* ── The two switches ────────────────────────────────────────────────────────────────── */}
      <section aria-labelledby="ministry-register-filters" className="mb-4 grid gap-4">
        <h2 id="ministry-register-filters" className="sr-only">
          Which workshops to show
        </h2>

        {/*
          `role="group"` + `aria-pressed` BUTTONS AND NOT A TABLIST. `GuideTrackSwitch` carries the
          argument: tabs owe arrow-key roving focus and `aria-controls` pointing at a `tabpanel`, and
          what this controls is most of the page rather than one panel — *"a control that claims an
          interaction model it does not implement is worse than a plainer one that does."*

          AND THE SELECTED STATE IS NOT A COLOUR. Rule 5 of the frontend contract governs any signal
          carried by one channel: the tick and the word survive colour-blindness, a greyscale
          printout and forced-colours mode. `aria-pressed` carries it for assistive technology, which
          is why the tick is `aria-hidden`.
        */}
        <div role="group" aria-label="Type of workshop" className="grid gap-2.5 sm:grid-cols-2">
          {REGISTER_KINDS.map((option) => {
            const selected = option.value === kind;
            return (
              <button
                key={option.value}
                type="button"
                aria-pressed={selected}
                onClick={() => chooseKind(option.value)}
                className={
                  selected
                    ? "flex h-full w-full flex-col items-start gap-1 rounded-md border border-ministry-600 bg-ministry-50 px-3.5 py-3 text-left transition-shadow hover:shadow-md dark:bg-ministry-950/40"
                    : "flex h-full w-full flex-col items-start gap-1 rounded-md border border-line-200 bg-card px-3.5 py-3 text-left transition-shadow hover:border-ministry-300 hover:shadow-md"
                }
              >
                <span className="flex w-full items-center gap-1.5">
                  <span
                    className={
                      selected
                        ? "font-display text-sm font-bold text-ministry-700 dark:text-ministry-300"
                        : "font-display text-sm font-bold text-ink-900"
                    }
                  >
                    {option.label}
                  </span>
                  {selected ? (
                    <Check className="ml-auto h-4 w-4 shrink-0 text-ministry-700 dark:text-ministry-300" aria-hidden />
                  ) : null}
                </span>
                <span className="text-xs leading-5 text-ink-500">{option.note}</span>
              </button>
            );
          })}
        </div>

        <div>
          <div role="group" aria-label="Standing" className="flex flex-wrap gap-2">
            {standings.map((option) => {
              const selected = option.value === standing;
              return (
                <button
                  key={option.value || "all"}
                  type="button"
                  aria-pressed={selected}
                  onClick={() => chooseStanding(option.value)}
                  className={
                    /*
                      ⚠ THE SELECTED STATE IS A FILLED GROUND WITH WHITE INK, AND IT WAS A PALE
                      GROUND WITH MINISTRY INK UNTIL REVIEW CAUGHT IT. That is the amber collision
                      this feature's own ruling claims to have answered, reproduced on the one
                      control nobody checked: `bg-ministry-50 text-ministry-700` in a `rounded-full`
                      pill IS a status pill — same shape, same pale ground, and `ministry-700` is
                      `amber-800` to ΔE 0.004. Beside a row whose standing badge is `bg-amber-100
                      text-amber-800`, a thing to PRESS and a fact about a workshop would have been
                      one colour in one shape.

                      The globals.css rule keeps `.field-button-secondary`'s ink at `ink-900` for
                      exactly this reason and says "the one place the two inks WOULD have met is the
                      secondary button". This was a second place. Filled, it is the primary button's
                      shape instead, which no pill in this product has — and white on `ministry-700`
                      is 7.20:1 in both themes, so no `dark:` pair is owed, for the same reason the
                      primary owes none.
                    */
                    selected
                      ? "inline-flex items-center gap-1.5 rounded-full border border-ministry-700 bg-ministry-700 px-3 py-1.5 text-xs font-semibold text-white"
                      : "inline-flex items-center gap-1.5 rounded-full border border-line-200 bg-card px-3 py-1.5 text-xs font-medium text-ink-700 transition hover:border-ministry-300"
                  }
                >
                  {selected ? <Check className="h-3.5 w-3.5 shrink-0" aria-hidden /> : null}
                  {option.label}
                </button>
              );
            })}
          </div>
          {/* WHICH STANDINGS THE WORD COVERED, from the server's own grouping — so a reader never has
              to guess what "Ongoing" included, and a group re-cut on the server reaches this line
              without a deploy. */}
          {membersSentence ? <p className="mt-2 text-xs leading-5 text-ink-500">{membersSentence}</p> : null}
        </div>

        <SearchInput
          value={query}
          onChange={setQuery}
          onSubmit={() => {
            setApplied(query.trim());
            setPage(1);
          }}
          ariaLabel={kind === "design" ? "Search design and prototype workshops" : "Search recorded workshops"}
          placeholder={
            kind === "design"
              ? "Search by title, workshop code, craft or cluster"
              : "Search by title, place or description"
          }
        />
      </section>

      {/* ── Live line and downloads ─────────────────────────────────────────────────────────── */}
      <section className="mb-4 flex flex-wrap items-center justify-between gap-x-4 gap-y-3">
        <div className="text-xs leading-5 text-ink-500">
          {/*
            PLAIN TEXT AND NO LIVE REGION. See the header: a self-refreshing readout inside
            `role="status"` re-reads the whole region on every tick and interrupts a screen-reader
            user every thirty seconds, forever. The sentence is said here, and the reader asks for it
            when they want it.
          */}
          {readAt ? (
            <>
              Read at <span className="font-medium text-ink-700">{formatDateTime(readAt)}</span>. This
              register re-reads itself while this tab is in front{error ? ", and the last re-read failed" : ""}.
              {switching ? " The table below is still the other register — the one you asked for is loading." : ""}
            </>
          ) : (
            "Reading the register…"
          )}
          <button
            type="button"
            onClick={() => {
              manualRefresh.current = true;
              setRefreshNote("Re-reading the register…");
              setLoadToken((token) => token + 1);
            }}
            className="ml-2 inline-flex items-center gap-1 rounded-sm px-1.5 py-0.5 font-semibold text-ministry-700 underline-offset-2 hover:underline dark:text-ministry-300"
          >
            <RefreshCw className="h-3.5 w-3.5" aria-hidden />
            Refresh now
          </button>
        </div>

        {/* Mounted from first paint and empty — see `refreshNote`. `sr-only` because the sighted
            reader already has the "Read at …" line and the rows themselves changing. */}
        <p role="status" aria-live="polite" className="sr-only">
          {refreshNote}
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            className="field-button-secondary"
            disabled={busyDownload !== null}
            onClick={() =>
              runDownload("register", () =>
                downloadRegister(kind, { search: applied || undefined, standing: standing || undefined })
              )
            }
          >
            <Download className="h-4 w-4" aria-hidden />
            {busyDownload === "register" ? "Preparing…" : "Download workshops"}
          </button>
          <button
            type="button"
            className="field-button"
            disabled={busyDownload !== null || entitlements?.beneficiaries === false}
            onClick={() => runDownload("beneficiaries", downloadBeneficiaries)}
          >
            <Download className="h-4 w-4" aria-hidden />
            {busyDownload === "beneficiaries" ? "Preparing…" : "Download beneficiaries"}
          </button>
        </div>
      </section>

      {/*
        SAID BESIDE THE BUTTONS AND NEVER AS A 403 AFTER THE CLICK. `can_export_design_workshop_data`
        states the standing rule in capitals — *"SAY IT ON SCREEN, NEVER 403 A BUTTON … Handing them a
        download button that answers 403 would teach them the product is broken rather than that the
        rule exists."* The first sentence is what the register file contains, which is the question a
        reader has about a file they are about to send somewhere.
      */}
      <p className="mb-4 text-xs leading-5 text-ink-500">
        “Download workshops” carries the columns on this screen — the workshop, where and when, its
        designer, its standing and its progress — and no stage content: no answers, no photographs, no
        recordings, no consent decisions. It takes the filters above with it and says so inside the
        file. “Download beneficiaries” is the whole artisan list, not only the artisans of the
        workshops listed here, and every identity number in it is masked to its last four characters.
        {entitlements && !entitlements.beneficiaries ? (
          <span className="mt-1 block font-medium text-ink-700">{entitlements.beneficiariesRefusal}</span>
        ) : null}
      </p>
      {downloadError ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700" role="alert">
          {downloadError}
        </div>
      ) : null}

      {/* ── The register ────────────────────────────────────────────────────────────────────── */}
      <section className="panel overflow-hidden">
        {data === null && error ? (
          /*
            A FAILED FIRST READ IS NOT "Loading…", AND THE TWO LOOKED IDENTICAL UNTIL REVIEW CAUGHT
            IT. `items === null` means "still asking" everywhere in this app, and the catch
            deliberately does not touch the rows — so on the FIRST failure, when there are no rows to
            keep, the panel sat on "Loading…" for ever under a red banner while the live line said
            "Reading the register…". Three states collapsed into the wrong one: an officer watching a
            spinner that will never resolve is worse off than one told the read failed, because the
            spinner tells them to wait.

            This is the one branch `/officers/monitored`'s rule does not cover — there, a failure
            always has rows behind it because the error arrives after a successful load. Here the
            timer means the first read can fail on its own.
          */
          <div className="p-4 text-sm text-ink-700">
            The register could not be read, so there is nothing to show yet — this is not an empty
            register. The banner above says what the server answered. It will try again on its own,
            and “Refresh now” asks immediately.
          </div>
        ) : data === null ? (
          <div className="p-4 text-sm text-ink-700">Loading…</div>
        ) : rows.length === 0 ? (
          <div className="p-4">
            <EmptyState
              title={
                applied || standing
                  ? "No workshop here matches those filters"
                  : "No workshop is in this register yet"
              }
              body={
                applied || standing
                  ? "This searches only the workshops described above, which is the whole of what you can read here. Clear the filters to see them all."
                  : "Nothing failed to load — this register is genuinely empty for the scope described above."
              }
            />
          </div>
        ) : (
          // `aria-live="off"` EXPLICITLY, NOT BY OMISSION. The table's contents are replaced on a
          // timer, and a container that later acquired a live role by an edit elsewhere would begin
          // interrupting a screen-reader user every thirty seconds. Written down so the decision is
          // visible at the element it governs.
          <div className="overflow-x-auto" aria-live="off">
            {dataKind === "design" ? (
              <DesignTable rows={(data as DesignRegisterPage).items} />
            ) : (
              <OtherTable rows={(data as OtherRegisterPage).items} />
            )}
          </div>
        )}
        {data ? <Pagination onPage={setPage} page={data.page} pages={data.pages} total={data.total} /> : null}
      </section>

      {/*
        ══ THE PEOPLE REGISTERS ══════════════════════════════════════════════════════════════════

        Owner, 2026-09-20: *"the dashboard carries no information about designers, ad, rd,
        inspectors, make it extremely more capable and powerful, there is no specific card for
        designers where they can do their stuff."*

        THEY WEAR `mango` AND NOT `ministry`, and the distinction is the whole of §4 of
        docs/DECISION-mega-cards-and-group-colour.md. `ministry` (hue 45) is this surface's ACTION
        colour — it paints the buttons on this very page through the `[data-surface="ministry"]`
        block. `mango` (hue 71) is the navigation MARK the owner asked for, taken off the site they
        named, and it may only ever be a chip, an ink or a hover border. Using the action ramp for
        the card chips would make a heading look like a control.

        TWO IN A ROW ON A LARGE SCREEN, ONE BELOW — the same rail as the dashboard's, `items-start`
        for the same reason (a shut card must not stretch to an open neighbour's height).
      */}
      <div className="mt-6 grid items-start gap-5 lg:grid-cols-2">
        {PEOPLE_KINDS.map((entry) => {
          const loaded = people[entry.id];
          const failed = peopleError[entry.id];
          return (
            <MegaCard
              key={entry.id}
              title={entry.title}
              note={entry.note}
              icon={PEOPLE_ICONS[entry.id]}
              tone="mango"
              /*
                THE SERVER'S TOTAL, AND ZERO UNTIL IT HAS ANSWERED. A card that has not been opened
                has read nothing, so it counts nothing — which is honest, because opening it is what
                measures. The sentence inside says which of the two a zero is.
              */
              count={loaded?.total ?? 0}
              countLabel={entry.countLabel}
              expanded={peopleCards.isOpen(entry.id)}
              onToggle={() => peopleCards.toggle(entry.id)}
            >
              <PeoplePanel
                kind={entry.id}
                data={loaded}
                error={failed}
                onPage={(next) => setPeoplePage((current) => ({ ...current, [entry.id]: next }))}
              />
            </MegaCard>
          );
        })}
      </div>
    </div>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * One people register
 * ──────────────────────────────────────────────────────────────────────────── */

/**
 * The inside of a people card: every sentence the server sent, then the rows.
 *
 * ⚠ **EVERY CAPTION HERE IS THE SERVER'S OWN STRING, PRINTED VERBATIM.** `scopeLabel`,
 * `progressNote`, `withheldAccountsNote`, `unpostedNote` and `feedbackNote` are composed server-side
 * because only the server knows what it actually read. A client that wrote its own version of
 * "Progress could not be read" would be guessing at a state it cannot observe, and this router
 * already shipped one caption over two differently-scoped counts — it told an Assistant Director
 * "the workshops you were named on" above a national figure.
 *
 * ⚠ **NOTHING IS FILTERED OR SORTED HERE.** No `.filter()`, no `.sort()`, no `.slice()` over
 * `data.items`. The order is the server's (workshops descending, then name), the page is the
 * server's, and a client that narrowed a list would print a total that disagreed with the rows under
 * it. `ministry-dashboard-unit.spec.ts` asserts the absence.
 */
function PeoplePanel({
  kind,
  data,
  error,
  onPage
}: {
  kind: PeopleKind;
  data: RegisterPeoplePage | undefined;
  error: string | undefined;
  onPage: (page: number) => void;
}) {
  if (error) {
    return (
      <p role="alert" className="text-sm leading-6 text-red-700">
        {error} This list could not be read, which is NOT the same as there being nobody in it.
      </p>
    );
  }
  if (!data) return <p className="text-sm text-ink-700">Loading...</p>;

  return (
    <div className="grid gap-3">
      {/* The scope, in the server's words, per list. Never composed here. */}
      <p className="text-xs leading-5 text-ink-500">{data.scopeLabel}</p>

      {/*
        A WHOLE BLANK COLUMN NEEDS A SENTENCE, NOT A BOOLEAN. Without this a column of dashes reads
        as a programme where nothing has been done — the most damaging false statement this screen
        could make, which is why the server sends the words rather than a flag.
      */}
      {data.progressNote ? (
        <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
          {data.progressNote}
        </p>
      ) : null}
      {data.withheldAccountsNote ? (
        <p className="text-xs leading-5 text-ink-500">{data.withheldAccountsNote}</p>
      ) : null}
      {data.unpostedAccountsNote ? (
        <p className="text-xs leading-5 text-ink-500">{data.unpostedAccountsNote}</p>
      ) : null}
      {data.feedbackNote ? <p className="text-xs leading-5 text-ink-500">{data.feedbackNote}</p> : null}
      {/* A list that stopped short must say so: absence reading as non-existence is this
          repository's most repeated defect class. */}
      {data.scan?.truncated ? (
        <p className="text-xs leading-5 text-ink-500">
          This list was built from the first {data.scan.read} of {data.scan.total} workshops in scope.
        </p>
      ) : null}
      {data.unpostedAccountsTruncated ? (
        <p className="text-xs leading-5 text-ink-500">
          The account directory stopped at its own ceiling, so somebody holding nothing in this scope
          may be missing from this list.
        </p>
      ) : null}

      {data.items.length === 0 ? (
        <EmptyState title={`No ${kind} in this scope`} />
      ) : (
        // `aria-live="off"` EXPLICITLY, for the same reason the workshop table carries it: these
        // rows are replaced on a thirty-second timer and a container that later acquired a live
        // role would interrupt a screen-reader user twice a minute.
        <div className="overflow-x-auto" aria-live="off">
          <table className="w-full min-w-[640px] text-left text-sm">
            <thead className="bg-surface-50 text-xs uppercase text-ink-500">
              <tr>
                <th className="px-3 py-2">Name</th>
                <th className="px-3 py-2">Workshops</th>
                <th className="px-3 py-2">Standing</th>
                <th className="px-3 py-2">{kind === "inspectors" ? "Corrections" : "Progress"}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-200">
              {data.items.map((person) => (
                <PersonRow key={person.id} kind={kind} person={person} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      <Pagination onPage={onPage} page={data.page} pages={data.pages} total={data.total} />
    </div>
  );
}

/**
 * One person.
 *
 * ⚠ **`null` IS PRINTED AS A DASH AND A REASON, NEVER AS A ZERO.** The server is explicit that a
 * measured zero and an unmeasured figure are different facts, and a table read by the ministry that
 * rendered both as "0" would say that work which exists was never done.
 */
function PersonRow({ kind, person }: { kind: PeopleKind; person: RegisterPerson }) {
  return (
    <tr>
      <td className="px-3 py-2">
        <span className="block font-medium text-ink-900">{person.name ?? "Unnamed account"}</span>
        <span className="block text-xs text-ink-500">{roleLabel(person.role)}</span>
      </td>
      <td className="px-3 py-2 tabular-nums">
        {person.workshops}
        {kind === "designers" && typeof person.workshopsCreated === "number" ? (
          // Both arms, because the creator holds no viewer row: a register built on viewer rows
          // alone would be missing the lead designer of every workshop nobody has shared.
          <span className="block text-xs text-ink-500">
            {person.workshopsCreated} opened · {person.workshopsNamedOn ?? 0} named on
          </span>
        ) : null}
        {kind === "officers" && person.byCapacity ? (
          <span className="block text-xs text-ink-500">
            {Object.entries(person.byCapacity)
              .map(([capacity, count]) => `${count} ${capacity.toLowerCase()}`)
              .join(" · ")}
            {person.unknownCapacity ? ` · ${person.unknownCapacity} unrecognised` : ""}
          </span>
        ) : null}
      </td>
      <td className="px-3 py-2 text-xs leading-5 text-ink-700">
        {person.registered} registered · {person.ongoing} ongoing · {person.completed} completed
        {person.unclassifiedStanding ? (
          <span className="block text-ink-500">{person.unclassifiedStanding} in no group</span>
        ) : null}
      </td>
      <td className="px-3 py-2 text-xs leading-5 text-ink-700">
        {kind === "inspectors" ? (
          typeof person.feedbackFiled === "number" ? (
            <>
              {person.feedbackFiled} filed
              <span className="block text-ink-500">{person.sendBacks ?? 0} sent a workshop back</span>
            </>
          ) : (
            // The feedback read failed for this request. A dash and a word, never a zero.
            <span className="text-ink-500">— not read</span>
          )
        ) : (
          personProgressSentence(person)
        )}
      </td>
    </tr>
  );
}

/* ────────────────────────────────────────────────────────────────────────────
 * The two tables
 * ──────────────────────────────────────────────────────────────────────────── */

function DesignTable({ rows }: { rows: DesignRegisterPage["items"] }) {
  return (
    <table className="w-full min-w-[1100px] text-left text-sm">
      <thead className="bg-surface-50 text-xs uppercase text-ink-500">
        <tr>
          <ResizableTh>Workshop</ResizableTh>
          <ResizableTh>Designer</ResizableTh>
          <ResizableTh>Place</ResizableTh>
          <ResizableTh>Dates</ResizableTh>
          <ResizableTh>Artisans</ResizableTh>
          <ResizableTh>Standing</ResizableTh>
          <ResizableTh>Progress</ResizableTh>
          <ResizableTh>Last changed</ResizableTh>
        </tr>
      </thead>
      <tbody className="divide-y divide-line-200">
        {rows.map((row) => (
          <tr key={row.id} className="hover:bg-surface-50">
            <td className="px-4 py-3">
              <span className="block font-medium text-ink-900">{row.title?.trim() || "Untitled design workshop"}</span>
              {/* The code is denormalised from stage 1 and is null until that stage has been saved —
                  so its absence means "stage 1 is not done", not "missing". An officer reads that as
                  a finding rather than as a gap in this screen; `/officers/monitored` says the same. */}
              <span className="block text-xs text-ink-500">
                {row.workshopCode ?? "No workshop code yet"}
                {row.craftName ? ` · ${row.craftName}` : ""}
                {row.clusterName ? ` · ${row.clusterName}` : ""}
              </span>
            </td>
            <td className="px-4 py-3 text-ink-700">{row.designerName?.trim() || "Not named yet"}</td>
            <td className="px-4 py-3 text-ink-700">
              {[row.venue, row.district, row.state].filter(Boolean).join(", ") || "—"}
            </td>
            <td className="px-4 py-3 text-ink-700">
              {formatDate(row.startDate)}
              {row.endDate ? ` – ${formatDate(row.endDate)}` : ""}
            </td>
            {/* `null` is "the roster could not be read", NOT zero — the server sends the two apart
                for the same reason the progress column refuses a zero it did not measure. A workshop
                with nobody on it on the day it opens is honestly 0 and prints 0. */}
            <td className="px-4 py-3 tabular-nums text-ink-700">
              {row.beneficiaries === null ? (
                <span className="text-ink-500" title="The artisan roster could not be read for this workshop.">
                  not read
                </span>
              ) : (
                row.beneficiaries
              )}
            </td>
            <td className="px-4 py-3">
              <StatusBadge status={row.status} />
            </td>
            <td className="px-4 py-3">
              <ProgressCell progress={row.progress} />
            </td>
            <td className="px-4 py-3 text-ink-700">{row.updatedAt ? formatDateTime(row.updatedAt) : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function OtherTable({ rows }: { rows: OtherRegisterPage["items"] }) {
  return (
    <table className="w-full min-w-[840px] text-left text-sm">
      <thead className="bg-surface-50 text-xs uppercase text-ink-500">
        <tr>
          <ResizableTh>Workshop</ResizableTh>
          <ResizableTh>Kind</ResizableTh>
          <ResizableTh>Place</ResizableTh>
          <ResizableTh>Dates</ResizableTh>
          <ResizableTh>Artisans</ResizableTh>
          <ResizableTh>Status</ResizableTh>
          <ResizableTh>Recorded</ResizableTh>
        </tr>
      </thead>
      <tbody className="divide-y divide-line-200">
        {rows.map((row) => (
          <tr key={row.id} className="hover:bg-surface-50">
            <td className="px-4 py-3 font-medium text-ink-900">{row.title?.trim() || "Untitled workshop"}</td>
            <td className="px-4 py-3 text-ink-700">
              {row.workshopType === "DESIGN_PROTOTYPE" ? "Design & prototype" : "Other"}
            </td>
            <td className="px-4 py-3 text-ink-700">{row.place || "—"}</td>
            <td className="px-4 py-3 text-ink-700">
              {formatDate(row.startDate ?? row.date)}
              {row.endDate ? ` – ${formatDate(row.endDate)}` : ""}
            </td>
            <td className="px-4 py-3 tabular-nums text-ink-700">{row.beneficiaries}</td>
            <td className="px-4 py-3">
              <StatusBadge status={row.status} />
            </td>
            <td className="px-4 py-3 text-ink-700">{row.createdAt ? formatDateTime(row.createdAt) : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/**
 * One workshop's progress: a bar, and the same sentence in words beside it.
 *
 * `role="progressbar"` WITH `aria-valuetext`, AND NEVER THE STATUS ROLE — `GalleryProgress` is the
 * worked precedent and `EntityForm`'s note is the argument. The sentence is ALSO printed as plain
 * text for the sighted reader, because a figure that exists only inside an ARIA attribute is a figure
 * most readers never get.
 *
 * THE FILL MOVES BY A CSS TRANSITION ON ITS WIDTH AND NOT BY framer-motion. The two global reduced-motion
 * rules in `globals.css` reach CSS and cannot reach framer's inline styles, and there is no
 * `MotionConfig reducedMotion="user"` anywhere in this app — so a framer bar would animate for
 * exactly the readers who asked it not to. The transition NAMES the width property rather than using
 * the everything shorthand, which would also animate the colour.
 *
 * ⚠ AND NEITHER UTILITY IS SPELLED OUT IN THIS COMMENT, deliberately. Tailwind's extractor is a
 * plain regex over these files and does not know a comment from code, so naming the shorthand here
 * would ship a rule nothing uses to every page in the product — `UploadPlanDialog` records the same
 * rule. It also shadowed this file's own spec, which reads the source to assert the shorthand is
 * absent.
 */
function ProgressCell({ progress }: { progress: DesignRegisterPage["items"][number]["progress"] }) {
  const sentence = progressSentence(progress);
  if (!hasProgressFigure(progress)) {
    // NOT A ZERO AND NOT AN EMPTY CELL. "Nothing has been done" and "we could not ask" are the two
    // things this column must never say in place of each other.
    return <span className="block max-w-[22rem] text-xs leading-5 text-ink-500">{sentence}</span>;
  }
  const percent = progress.percent ?? 0;
  return (
    <div className="min-w-[12rem] max-w-[22rem]">
      {/*
        ⚠ THE BAR IS DECORATION AND THE SENTENCE IS THE ANNOUNCEMENT, and it was the other way round
        until review caught it. It carried `role="progressbar"` with `aria-valuetext={sentence}` AND
        printed the same sentence as text underneath, so a screen reader read every row's progress
        TWICE — and the progressbar had no accessible name at all, because `GalleryProgress`'s
        precedent is one bar on a page that a heading names, while this is one per row in a table
        with nothing to name it.

        Dropping the role fixes both at once and loses nothing: the sentence below is complete on its
        own ("11 of 22 stages complete, 50 required fields outstanding — 50%"), which is rule 5 of the
        frontend contract working as intended — the WORD carries the signal and the bar only agrees
        with it. A progressbar whose value is also printed beside it in full is redundant semantics,
        not extra semantics.
      */}
      <div aria-hidden className="h-1.5 w-full overflow-hidden rounded-full bg-line-200">
        <div
          className="h-full rounded-full bg-ministry-600 transition-[width] duration-300 ease-out dark:bg-ministry-400"
          style={{ width: `${percent}%` }}
        />
      </div>
      <span className="mt-1 block text-xs leading-5 tabular-nums text-ink-500">{sentence}</span>
    </div>
  );
}
