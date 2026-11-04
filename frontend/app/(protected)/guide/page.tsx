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

export default function GuidePage() {
  const reduce = useAppReducedMotion();
  const { user } = useAuth();
  const { adminMode } = useAdminView();

  const [chosen, setChosen] = useState<GuideTrack | null>(null);

  const visible = guideTracksFor(user);
  const fallback = guideTrackFor(user);
  const track = chosen && visible.some((deck) => deck.id === chosen.id) ? chosen : fallback;

  useEffect(() => {
    const anchored = guideTrackForAnchor(window.location.hash.replace("#", ""));
    if (anchored && guideTracksFor(user).some((deck) => deck.id === anchored.id)) setChosen(anchored);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      <PageHeader
        title="Walkthrough"
        description={track.description}
        icon={<Compass className="h-5 w-5" aria-hidden />}
      />

      <GuideHero key={`hero-${track.id}`} track={track} onStart={() => scrollToStep(track.steps[0].id, reduce)} />

      {visible.length > 1 ? <GuideTrackSwitch tracks={visible} active={track} onChoose={setChosen} /> : null}

      <GuideJourney key={`journey-${track.id}`} steps={track.steps} />

      <GuideOutro track={track} user={user} adminMode={adminMode} />
    </>
  );
}
