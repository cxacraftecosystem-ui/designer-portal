"use client";

import { useEffect, useState } from "react";
import { Compass } from "lucide-react";

import { GuideHero } from "@/components/guide/GuideHero";
import { GuideJourney } from "@/components/guide/GuideJourney";
import { GuideOutro } from "@/components/guide/GuideOutro";
import { GuideTrackSwitch } from "@/components/guide/GuideTrackSwitch";
import { scrollToStep } from "@/components/guide/guideMotion";
import { GUIDE_TRACKS, guideTrackFor, guideTrackForAnchor, type GuideTrack } from "@/components/guide/tracks";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { useAuth } from "@/components/AuthProvider";
import { PageHeader } from "@/components/PageHeader";

/**
 * Walkthrough — the in-app guide, now THREE decks over one renderer.
 *
 * The page is still a single scroll: an opening band, the deck switcher, the steps threaded onto a
 * scroll-linked spine (`components/guide/GuideJourney`), and a closing checklist. Every step names
 * the real screen it teaches, uses that screen's own name for it, and links straight there — so the
 * guide is a launcher as well as a lesson.
 *
 * ── WHICH DECK, AND WHY THAT IS A DEFAULT RATHER THAN A GATE ────────────────────────────────────
 *
 * `guideTrackFor(user)` picks the deck that OPENS; `GuideTrackSwitch` reaches all three, for
 * everybody, always. `/guide` is deliberately ungated — `steps.ts` argues it at length — and the
 * reason is that the walkthrough teaches the process to people who have not earned the capability
 * yet. Hiding two decks from a reader because of their tier would turn an ungated teaching surface
 * into a per-role one, which is a narrowing of exactly the kind this page exists to refuse. Nothing
 * here is a permission decision; `components/guide/tracks.ts` carries the full argument and the
 * answer to the objection `steps.ts` raises against per-role scripts.
 *
 * ── THE ANCHOR OUTRANKS THE ROLE ────────────────────────────────────────────────────────────────
 *
 * A `/guide#<id>` link is an instruction about a specific card, so it selects the deck that card
 * belongs to even when the reader's tier would have opened a different one. Resolved in an effect
 * rather than during render because `window.location` does not exist on the server, and a render
 * that read it would be a hydration mismatch on a page whose whole opening band animates.
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
 * same route-by-route pairing that spec already performs for the designer's arc.
 */
export default function GuidePage() {
  // Both switches, OR-ed: the OS media query and the app's own Settings toggle. Smooth scrolling
  // is motion too, so "Start at step 1" jumps instantly under either.
  const reduce = useAppReducedMotion();
  const { user } = useAuth();

  /**
   * null means "nobody has chosen; follow the account". It is not initialised to the role's deck,
   * because then a later answer from either source could not be told apart from a reader's click —
   * and the switcher's whole point is that a reader's click wins and keeps winning.
   */
  const [chosen, setChosen] = useState<GuideTrack | null>(null);
  const track = chosen ?? guideTrackFor(user);

  useEffect(() => {
    const anchored = guideTrackForAnchor(window.location.hash.replace("#", ""));
    if (anchored) setChosen(anchored);
  }, []);

  return (
    <>
      <PageHeader
        title="Walkthrough"
        description={track.description}
        icon={<Compass className="h-5 w-5" aria-hidden />}
      />

      {/*
        KEYED ON THE DECK, and the hero's key is load-bearing rather than tidy. `useGsapHeadline`
        splits the headline into per-word spans ONCE per node — it guards on `dataset.split`, because
        re-splitting would nest spans inside spans — so changing `track.headline` on a live node
        would leave the previous deck's words in the DOM and the new sentence unrendered. Remounting
        hands the hook a fresh <h2>. The journey is keyed for the ordinary reason: `expandedId` and
        `activeIndex` are indices into a deck, and carrying a previous deck's state across would
        open a card that is not there.
      */}
      <GuideHero key={track.id} track={track} onStart={() => scrollToStep(track.steps[0].id, reduce)} />

      {/*
        BELOW THE HERO AND ABOVE THE JOURNEY. Not inside `GuideRail`, whose step list is bounded by
        `calc(100vh-19rem)` and whose own comment records that a row of chrome above it costs the
        reader the last rows of the list on a 1366×768 screen.

        CHOOSING A DECK DOES NOT SCROLL. The switcher sits near the top of the page, so a reader
        pressing it is already looking at the right place, and a scroll they did not ask for is
        motion — the same reason "Start at step 1" is gated on `reduce`. What does move slightly is
        the band above, because two decks' intros differ by a line; that is a few pixels and no
        content is lost off the top.
      */}
      <GuideTrackSwitch tracks={GUIDE_TRACKS} active={track} onChoose={setChosen} />

      <GuideJourney key={track.id} steps={track.steps} />

      {/*
        NOT KEYED. It holds no per-deck index and nothing imperative; its `whileInView` reveals have
        already fired by the time anybody can reach the switcher, and remounting would replay all
        three of them under a reader who is looking at the top of the page.
      */}
      <GuideOutro track={track} />
    </>
  );
}
