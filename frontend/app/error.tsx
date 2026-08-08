"use client";

import { TriangleAlert } from "lucide-react";

import { DeadEndDetail, DeadEndFrame, DeadEndPanel, DeadEndText, DeadEndWayBack } from "@/components/DeadEnd";

/**
 * The outermost error boundary. Without it an exception anywhere below the root layout gives the
 * same bare framework screen the missing 404 gave — no branding, no navigation, no way back.
 *
 * It catches what `app/(protected)/error.tsx` structurally cannot: the landing page, `/login`, and
 * `app/(protected)/layout.tsx` itself, because a layout's own failure is caught by its PARENT
 * boundary and never by the one nested inside it. That last case is why `DeadEndFrame` is asked for
 * no navigation here — the island is mounted inside that layout, so it is one of the things whose
 * failure lands on this screen, and redrawing it would throw again.
 *
 * Errors in the ROOT layout are still uncovered; that needs `global-error.tsx`, which replaces the
 * whole document — its own `<html>`, no fonts, no theme boot script, so none of the tokens below
 * would apply. It is the rarest failure in the app (the root layout renders four providers and no
 * data) and is deliberately left out rather than answered with a screen that could not look like
 * this one anyway.
 */
export default function RootError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <DeadEndFrame nav={false}>
      <DeadEndPanel
        tone="alert"
        icon={<TriangleAlert className="h-5 w-5" aria-hidden />}
        title="This page stopped before it finished"
      >
        <DeadEndText>
          Something went wrong while the app was drawing this page. It is a fault in the app, not something you did, and
          the same page often renders correctly on a second attempt.
        </DeadEndText>
        {/* The digest is the only handle a designer has on a production failure: the real message is
            redacted before it reaches the browser, and a phone in a village has no console to read.
            Printed so it can be copied into a message rather than described from memory. */}
        {error.digest ? <DeadEndDetail label="Reference" value={error.digest} /> : null}
        <DeadEndWayBack>
          <button type="button" onClick={reset} className="field-button">
            Try again
          </button>
        </DeadEndWayBack>
      </DeadEndPanel>
    </DeadEndFrame>
  );
}
