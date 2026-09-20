"use client";

/**
 * WHICH MEGA CARDS THIS READER HAS OPENED — collapsed on a first visit, remembered afterwards.
 *
 * ── COLLAPSED BY DEFAULT IS AN OWNER RULING, AND IT COSTS SOMETHING ────────────────────────────
 *
 * 2026-09-20: *"Each of the megacard is supposed to be a larger card that would stay minimized unless
 * it is clicked upon."* Taken literally and with nothing else, that means a returning reader who opens
 * Records on every visit re-opens it on every visit, which is the kind of obedience that reads as a
 * bug. So the default is collapsed and the CHOICE is remembered: the first visit obeys the ruling
 * exactly, and every visit after it obeys the reader.
 *
 * ── WHY THE FIRST RENDER IS ALWAYS "EVERYTHING CLOSED", EVEN WHEN STORAGE SAYS OTHERWISE ───────
 *
 * Reading `localStorage` in a `useState` initializer would make the client's first render disagree
 * with the server's, and React hydration errors in this app are a wall of console noise rather than a
 * visible failure — `<html suppressHydrationWarning>` is already spending the one exemption this
 * codebase grants, on the preferences boot script. So the set starts EMPTY on both sides and an effect
 * fills it in. `MegaCard`'s `<AnimatePresence initial={false}>` is what stops that second pass
 * animating: the remembered cards are simply already open, with no expand that nobody asked for.
 *
 * ── EVERY READ AND EVERY WRITE IS WRAPPED, BECAUSE THE ACCESSOR ITSELF CAN THROW ───────────────
 *
 * Not merely "can be empty". In a private window, with site data blocked, or inside a thumbnailer,
 * touching `window.localStorage` throws on ACCESS — before `getItem` is ever called. An unguarded
 * read there takes the whole dashboard down, which is a spectacular price for remembering which
 * heading somebody opened. A failed read means "collapsed", which is the documented default anyway,
 * and a failed write means "not remembered", which costs one click.
 *
 * ── THE KEY IS PER SCREEN ──────────────────────────────────────────────────────────────────────
 *
 * `/dashboard` and `/questionnaire` both use this and have different groups with the same-looking
 * ids. One shared key would let opening "Records" on one screen open something unrelated on the
 * other. The prefix matches `lib/preferences.ts`'s `field_repo_` convention so a reader clearing this
 * app's storage by prefix clears these too.
 */

import { useCallback, useEffect, useRef, useState } from "react";

const PREFIX = "field_repo_megacards_";

/**
 * What is stored, or `null` when nothing is — and the DIFFERENCE between those two is load-bearing.
 *
 * `null` means "this reader has never touched this screen", so the caller's first-visit defaults
 * apply. `[]` means "this reader has shut everything", which is a decision and must survive a
 * reload. Collapsing both to `[]` — which the first version of this function did — makes a
 * default-open card re-open every time somebody closes it and leaves.
 *
 * A hand-edited or version-drifted value is discarded rather than trusted, and the filter is on the
 * ELEMENT type as well as the array: one non-string inside would reach `Set.has`, compare false
 * forever, and look exactly like a toggle that does not work.
 */
function readRaw(key: string): string[] | null {
  try {
    const raw = window.localStorage.getItem(PREFIX + key);
    if (raw === null) return null;
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return null;
    return parsed.filter((entry): entry is string => typeof entry === "string");
  } catch {
    return null;
  }
}

function write(key: string, ids: string[]): void {
  try {
    window.localStorage.setItem(PREFIX + key, JSON.stringify(ids));
  } catch {
    /* Not remembered. Costs one click; never costs the screen. */
  }
}

export function useMegaCards(
  storageKey: string,
  /**
   * Cards that are OPEN on a first visit, before anything is remembered.
   *
   * ── THIS IS AN EXCEPTION AND IT IS NARROW ─────────────────────────────────────────────────
   *
   * The owner's ruling — "stay minimized unless it is clicked upon" — was made about the
   * DASHBOARD, where every card is a list of links and a shut one costs a reader nothing. It is
   * passed nothing here and every dashboard card is shut, which is the ruling obeyed exactly.
   *
   * `/questionnaire` is a different screen: it is a WORK surface, not a menu. Its capture form is
   * what `/questionnaire?new=1` — the dashboard tile and the guide both link to it — expects to
   * land on, and `questionnaire-capture.spec.ts` asserts the first instrument section is visible
   * with no clicks at all. A collapsed form there would send both to a closed card with nothing on
   * screen to say the page had loaded. So exactly one card on that screen opens by default, and
   * everything around it is shut.
   *
   * ⚠ A DEFAULT-OPEN CARD IS STILL REMEMBERED CLOSED. Once a reader shuts it, the stored set is
   * authoritative and this list is not consulted again — otherwise the card would re-open on every
   * visit and the toggle would look broken.
   */
  defaultOpen: readonly string[] = []
): {
  isOpen: (id: string) => boolean;
  toggle: (id: string) => void;
} {
  const [open, setOpen] = useState<ReadonlySet<string>>(() => new Set());

  // `hydrated` guards the WRITE, not the read. Without it the effect below would persist the empty
  // first-render set over the reader's real choices before the hydrating effect had run — the
  // classic "my settings reset themselves" bug, arriving through the code that exists to prevent it.
  const hydrated = useRef(false);

  /*
    `defaults` is CAPTURED ON THE FIRST RENDER and never written again, which is the whole of what
    the effect below needs and is why there is no assignment here.

    It is a ref rather than a dependency because a caller writing
    `useMegaCards("questionnaire", ["capture"])` creates a NEW array literal on every render. As an
    effect dependency that re-runs the hydrating effect forever, and each run overwrites whatever the
    reader just toggled — a card that springs back open the instant you shut it.

    And it is not re-assigned during render because `react-hooks/refs` forbids exactly that, rightly:
    a ref written while rendering is a value React cannot see change. Nothing is lost — this list is
    read once, on the first visit, before anything is stored.
  */
  const defaults = useRef(defaultOpen);

  useEffect(() => {
    // No guard around this call, deliberately: touching `window.localStorage` to TEST it is the very
    // access that throws in a blocked context, so a check outside the try is the bug it looks like a
    // fix for. `readRaw` owns the whole hazard and answers null for every failure.
    const stored = readRaw(storageKey);
    // NOTHING STORED means a first visit, so the defaults apply. An EMPTY STORED SET means the
    // reader has shut everything, which is a decision and must not be overwritten by the defaults.
    setOpen(new Set(stored ?? defaults.current));
    hydrated.current = true;
  }, [storageKey]);

  const toggle = useCallback(
    (id: string) => {
      setOpen((current) => {
        const next = new Set(current);
        if (next.has(id)) next.delete(id);
        else next.add(id);
        if (hydrated.current) write(storageKey, [...next]);
        return next;
      });
    },
    [storageKey]
  );

  const isOpen = useCallback((id: string) => open.has(id), [open]);

  return { isOpen, toggle };
}
