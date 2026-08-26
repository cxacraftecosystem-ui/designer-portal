"use client";

import { Compass } from "lucide-react";

import { GuideHero } from "@/components/guide/GuideHero";
import { GuideJourney } from "@/components/guide/GuideJourney";
import { GuideOutro } from "@/components/guide/GuideOutro";
import { scrollToStep } from "@/components/guide/guideMotion";
import { GUIDE_STEPS } from "@/components/guide/steps";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { PageHeader } from "@/components/PageHeader";

/**
 * Walkthrough — the in-app guide that teaches a new researcher the entire documentation
 * process, in the order it happens in the field.
 *
 * The page is a single scroll: an opening band, the steps threaded onto a scroll-linked
 * spine (`components/guide/GuideJourney`), and a closing checklist. Every step names the real
 * screen it teaches, uses the Android-parity feature name for it, and links straight there —
 * so the guide is a launcher as well as a lesson.
 *
 * The same content in prose lives at `docs/WALKTHROUGH.md`, for handing to a researcher who is
 * not sitting at a screen.
 *
 * THE COUNT IS DERIVED AND NOT WRITTEN DOWN, in the description below as well as in the hero. It
 * said "Ten steps" as a literal while `GUIDE_STEPS` held sixteen, and then nineteen — so the one
 * sentence a reader sees before scrolling was the one place on the page that disagreed with the
 * page. `GUIDE_STEPS.length` is already passed to `GuideHero`; there is no reason for the header to
 * be told separately.
 */
export default function GuidePage() {
  // Both switches, OR-ed: the OS media query and the app's own Settings toggle. Smooth scrolling
  // is motion too, so "Start at step 1" jumps instantly under either.
  const reduce = useAppReducedMotion();

  return (
    <>
      <PageHeader
        title="Walkthrough"
        description={
          `How a craft gets documented and how a design & prototype workshop is run, from your own ` +
          `designer profile to the report a ministry officer receives. ${GUIDE_STEPS.length} steps, in the order you do them.`
        }
        icon={<Compass className="h-5 w-5" aria-hidden />}
      />

      <GuideHero stepCount={GUIDE_STEPS.length} onStart={() => scrollToStep(GUIDE_STEPS[0].id, reduce)} />

      <GuideJourney />

      <GuideOutro />
    </>
  );
}
