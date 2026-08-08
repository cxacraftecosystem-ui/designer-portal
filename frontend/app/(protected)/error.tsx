"use client";

import { TriangleAlert } from "lucide-react";

import { DeadEndDetail, DeadEndPanel, DeadEndText, DeadEndWayBack } from "@/components/DeadEnd";

/**
 * The error boundary for every signed-in page.
 *
 * WHY THIS EXISTS AS WELL AS `app/error.tsx`, which would catch these pages anyway.
 * Next.js nests `layout → error → page`, so this boundary renders INSIDE `AppShell` while the root
 * one replaces it. Inside means the island navigation survives the failure and a designer who was
 * three stages into a workshop is one tap from the next one; on the root boundary the same crash
 * takes the whole frame down and leaves them with two links. A crash mid-workshop, offline, in a
 * village, is exactly when losing the navigation costs the most.
 *
 * No frame of its own: `AppShell`'s `motion.main` already supplies the width, the padding and the
 * island clearance. Adding another would double the top padding and push the heading off a phone.
 */
export default function ProtectedError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <DeadEndPanel
      tone="alert"
      icon={<TriangleAlert className="h-5 w-5" aria-hidden />}
      title="This page stopped before it finished"
    >
      <DeadEndText>
        Something went wrong while the app was drawing this page. It is a fault in the app, not something you did, and
        the same page often renders correctly on a second attempt.
      </DeadEndText>
      {/*
        Said plainly, because this app is used where a second attempt is expensive. The crash tore
        down this page's React tree and every form in it, but it touched nothing that had already
        been filed: saves made offline are in the IndexedDB outbox (`lib/offline`) and design
        workshops are in the local store (`lib/designWorkshopStore`), both of which survive a browser
        restart, let alone a boundary. Leaving that unsaid invites a designer to assume the worst and
        re-enter a whole stage that was already safe.
      */}
      <DeadEndText>
        Anything already saved is safe, including saves waiting in the offline queue and design workshops held on this
        device. Anything still being typed into a form on this page was lost with it and will need entering again.
      </DeadEndText>
      {/* The digest is the only handle on a production failure: the real message is redacted before
          it reaches the browser, and a phone in the field has no console to read it from. */}
      {error.digest ? <DeadEndDetail label="Reference" value={error.digest} /> : null}
      <DeadEndWayBack>
        <button type="button" onClick={reset} className="field-button">
          Try again
        </button>
      </DeadEndWayBack>
    </DeadEndPanel>
  );
}
