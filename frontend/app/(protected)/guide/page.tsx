"use client";

import { useEffect, useState } from "react";
import { Compass } from "lucide-react";

import { GuideHero } from "@/components/guide/GuideHero";
import { GuideJourney } from "@/components/guide/GuideJourney";
import { GuideOutro } from "@/components/guide/GuideOutro";
import { GuideTrackSwitch } from "@/components/guide/GuideTrackSwitch";
import { scrollToStep } from "@/components/guide/guideMotion";
import { guideTrackFor, guideTrackForAnchor, guideTracksFor, type GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";

/**
 * Walkthrough — the in-app guide, now THREE decks over one renderer.
 *
 * The page is still a single scroll: an opening band, the deck switcher where there is more than one
 * deck to switch between, the steps threaded onto a scroll-linked spine
 * (`components/guide/GuideJourney`), and a closing checklist. Every step names the real screen it
 * teaches, uses that screen's own name for it, and links straight there — so the guide is a launcher
 * as well as a lesson.
 *
 * ── WHICH DECK, AND WHY IT IS NOW A GATE — 2026-09-16 ───────────────────────────────────────────
 *
 * `guideTracksFor(user)` decides which decks this account may read; `guideTrackFor(user)` decides
 * which of them OPENS. Everybody except an ADMIN and a MASTER ADMIN gets exactly one deck, the one
 * their role owns, and the switcher is not rendered for them at all — a single button under a
 * heading reading "Which walkthrough", carrying `aria-pressed={true}` and changing nothing when
 * pressed, is a control that controls nothing.
 *
 * ⚠ THIS PARAGRAPH ARGUED THE OPPOSITE UNTIL 2026-09-16 AND THE REVERSAL IS A PRODUCT RULING, not a
 * defect being fixed, so what it said is kept: "Hiding two decks from a reader because of their tier
 * would turn an ungated teaching surface into a per-role one, which is a narrowing of exactly the
 * kind this page exists to refuse." The owner ruled that a reader should be given the walkthrough
 * that matches their role. What survives of the old argument is the half about the PAGE rather than
 * the deck: `/guide` is still absent from `ROUTE_GUARDS`, the nav entry is still `can: everyone`,
 * and every signed-in account still opens this page and reads a whole walkthrough. What changed is
 * WHICH one, and that nobody outside the two admin tiers is offered the other two.
 * `components/guide/tracks.ts` carries the full argument, the tier-by-tier map and the record of
 * which tiers have no deck written for them.
 *
 * ── THE ROLE OUTRANKS THE ANCHOR ────────────────────────────────────────────────────────────────
 *
 * The other half of the same ruling (OQ-5, arm b), and it also reverses what stood here: a
 * `/guide#<id>` link used to select the deck that card belongs to whatever the reader's tier. It now
 * selects it ONLY when that deck is one this account may read, and is SILENTLY IGNORED otherwise —
 * no note, no redirect. The page then opens on the reader's own deck exactly as if the hash were not
 * there, and `GuideJourney` discards the unknown id as it always has.
 *
 * The intersection is done HERE and not inside `guideTrackForAnchor`, which stays pure and stays
 * pinned to resolve every anchor of every deck. Resolved in an effect rather than during render
 * because `window.location` does not exist on the server, and a render that read it would be a
 * hydration mismatch on a page whose whole opening band animates.
 *
 * ── NO OTHER DECK'S PROSE IS IN THE HTML, AND THAT IS THE SHELL'S DOING RATHER THAN THIS PAGE'S ──
 *
 * `AppShell` returns the "Opening the repository…" frame while `AuthProvider` is loading and `null`
 * when there is no user, and `AuthProvider` starts `loading: true` — so this component never runs on
 * the server and the prerendered document carries no deck at all. **VERIFIED** against a real build:
 * `.next/server/app/guide.html` contains "Opening the repository…" once and not one of the three
 * headlines, and `guide.rsc` beside it carries none of them either. The filter below is
 * therefore about what is PRESENTED to a signed-in reader; nothing that reaches the wire before
 * sign-in has ever had to be filtered. The decks do all ship in the client BUNDLE — see the module
 * header in `tracks.ts` for why that is accepted rather than overlooked.
 *
 * ── THE COUNT IS DERIVED AND NEVER WRITTEN DOWN ─────────────────────────────────────────────────
 *
 * In the header, in the hero and in the switcher. The header said the literal "Ten steps" while
 * `GUIDE_STEPS` held sixteen, and then nineteen, so the one sentence a reader saw before scrolling
 * was the only place on the page that disagreed with the page. Each deck's `description` now
 * interpolates its own `steps.length`, and this component states no number at all.
 *
 * The designer's deck also exists in prose at `docs/WALKTHROUGH.md`, for handing to a researcher who
 * is not sitting at a screen. ⚠ THE OTHER TWO DECKS HAVE NO PROSE TWIN, and that is a gap rather
 * than a decision: `docs/WALKTHROUGH.md` is titled "for designers" and `e2e/guide-walkthrough-unit.spec.ts`
 * holds it to `GUIDE_STEPS` alone, so neither the document nor its guard was written to carry three
 * audiences. Whoever adds them owes a document per deck, a row in `docs/README.md`'s index, and the
 * same route-by-route pairing that spec already performs for the designer's arc. The scoping of
 * 2026-09-16 makes that gap sharper rather than leaving it where it was: a ministry officer now has
 * exactly one deck on screen and no printed version of it, and can no longer reach the one deck that
 * does have a printed version.
 */
export default function GuidePage() {
  // Both switches, OR-ed: the OS media query and the app's own Settings toggle. Smooth scrolling
  // is motion too, so "Start at step 1" jumps instantly under either.
  const reduce = useAppReducedMotion();
  const { user } = useAuth();
  /**
   * Read here and handed down, because `GuideOutro`'s exits are two gates and not one: `AppShell`
   * applies `adminChromeVisible` AFTER `canAccessRoute` and renders `AdminViewLocked` instead of the
   * page, so an exit tile that asked only the route table offered an admin with admin view off a tile
   * to `/users` that lands on that panel. The page reads the hook — it is already inside
   * `AdminViewProvider`, which the ROOT layout mounts above everything — and the band keeps the
   * "renderable with no provider above it" property its own docstring argues for.
   */
  const { adminMode } = useAdminView();

  /**
   * null means "nobody has chosen; follow the account". It is not initialised to the role's deck,
   * because then a later answer from either source could not be told apart from a reader's click —
   * and the switcher's whole point is that a reader's click wins and keeps winning.
   *
   * FOR A ONE-DECK ACCOUNT NOTHING EVER WRITES IT, and that is the shape of the scoping rather than
   * an oversight: the switcher is not rendered and the anchor effect below refuses a deck this
   * account may not read, so `chosen` stays null for nine of the eleven tiers and `track` is
   * `guideTrackFor(user)` for the life of the page.
   */
  const [chosen, setChosen] = useState<GuideTrack | null>(null);

  /**
   * THE GATE, APPLIED ONCE, AT THE ONE PLACE THAT RENDERS. Everybody except an admin and a master
   * admin gets a single-deck list; `guideTrackFor` is always a member of it, by construction of
   * `guideTracksFor`.
   *
   * `track` is intersected with `visible` rather than simply taken from `chosen`, and that is not
   * belt-and-braces about a value only the switcher writes. It is what makes "the role wins" true of
   * the SCREEN rather than true of each caller of `setChosen` — the anchor effect below is the
   * second such caller and a third would not have to remember. A deck this account may not read can
   * therefore never be rendered, whatever put it in state.
   */
  const visible = guideTracksFor(user);
  const fallback = guideTrackFor(user);
  const track = chosen && visible.some((deck) => deck.id === chosen.id) ? chosen : fallback;

  useEffect(() => {
    const anchored = guideTrackForAnchor(window.location.hash.replace("#", ""));
    // ROLE WINS, ANCHOR SILENTLY IGNORED (OQ-5b) — see the header. An anchor into a deck this
    // account may not read leaves the page exactly where it would have been without it.
    if (anchored && guideTracksFor(user).some((deck) => deck.id === anchored.id)) setChosen(anchored);
    // ONCE PER MOUNT, DELIBERATELY, AND `user` IS SAFE TO READ FROM INSIDE IT. `AppShell` returns
    // early while `loading` and returns `null` with no user, so `user` is already resolved on this
    // component's first render and cannot arrive later — which is also why there is no flash of the
    // wrong deck. Putting `user` in the deps would re-run the hash read on any later `/me` refresh
    // and could scroll a reader who is half-way down the page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      <PageHeader
        title="Walkthrough"
        description={track.description}
        icon={<Compass className="h-5 w-5" aria-hidden />}
      />

      {/*
        KEYED ON THE DECK, AND EACH KEY IS PREFIXED SO THAT THE TWO DIFFER FROM EACH OTHER. Both
        halves of that sentence are load-bearing and the second half is the one that shipped a bug.

        WHY THEY ARE KEYED AT ALL. `useGsapHeadline` splits the hero's headline into per-word spans
        ONCE per node — it guards on `dataset.split`, because re-splitting would nest spans inside
        spans — so changing `track.headline` on a live node would leave the previous deck's words in
        the DOM and the new sentence unrendered. Remounting hands the hook a fresh <h2>. The journey
        is keyed for the ordinary reason: `expandedId` and `activeIndex` are indices into ONE deck,
        and carrying a previous deck's state across would open a card that is not there.

        ⚠ WHY THEY MUST ALSO DIFFER FROM EACH OTHER — THE DEFECT THIS COMMENT DID NOT NOTICE, and
        the reason it survived review: it explained the hero's key at length and never looked at the
        sibling three elements down carrying the identical one. Both read `key={track.id}`, and they
        are siblings in one fragment, so they compile to one children array with one duplicate key.
        React reconciles that array through `mapRemainingChildren`, a Map keyed by key: the LATER
        child overwrites the earlier one, and only what is still in that Map at the end is deleted.
        So on every deck change the journey was found and unmounted correctly and THE OLD HERO WAS
        EVICTED FROM THE MAP AND NEVER UNMOUNTED — its DOM stayed, the new hero was inserted below
        it, and the purple bands piled up in visit order, each keeping a live "Start at step 1"
        button in the tab order and an un-killed GSAP timeline behind it. It is not development-only:
        the same code is in `react-dom-client.production.js`; only the console warning is dev-only.

        SO: A KEY ON A FRAGMENT'S CHILD IS UNIQUE AMONG ITS SIBLINGS OR IT IS WRONG — changing per
        deck is necessary and is not sufficient. Do not fix this by deleting a key; both remounts
        are required, for the two different reasons above. `GuideTrackSwitch.tsx`'s `key={track.id}`
        is inside a `.map()` over one list and is correct as it stands.
      */}
      <GuideHero key={`hero-${track.id}`} track={track} onStart={() => scrollToStep(track.steps[0].id, reduce)} />

      {/*
        DRAWN ONLY WHEN THERE IS SOMETHING TO CHOOSE BETWEEN — today that is an ADMIN or a MASTER
        ADMIN and nobody else. It is not cosmetic: one button under a heading reading "Which
        walkthrough", rendered `aria-pressed={true}` and doing nothing when pressed, is a control
        that controls nothing, and the panel's own sentence would be describing a choice the reader
        does not have. `visible.length > 1` rather than `isAdmin(user)` so the page asks the
        question the render actually depends on, and so a future audience granted two decks needs no
        edit here.

        BELOW THE HERO AND ABOVE THE JOURNEY. Not inside `GuideRail`, whose step list is bounded by
        `calc(100vh-19rem)` and whose own comment records that a row of chrome above it costs the
        reader the last rows of the list on a 1366×768 screen.

        CHOOSING A DECK DOES NOT SCROLL. The switcher sits near the top of the page, so a reader
        pressing it is already looking at the right place, and a scroll they did not ask for is
        motion — the same reason "Start at step 1" is gated on `reduce`. What does move slightly is
        the band above, because two decks' intros differ by a line; that is a few pixels and no
        content is lost off the top.
      */}
      {visible.length > 1 ? <GuideTrackSwitch tracks={visible} active={track} onChoose={setChosen} /> : null}

      <GuideJourney key={`journey-${track.id}`} steps={track.steps} />

      {/*
        NOT KEYED. It holds no per-deck index and nothing imperative; its `whileInView` reveals have
        already fired by the time anybody can reach the switcher, and remounting would replay all
        three of them under a reader who is looking at the top of the page.
      */}
      <GuideOutro track={track} user={user} adminMode={adminMode} />
    </>
  );
}
