"use client";

/**
 * THE EYE TOGGLE, ONCE, SO THE FOUR SECRET BOXES IN THIS APP BEHAVE THE SAME WAY.
 *
 * Until 2026-08-30 there was exactly one eye in the web client — inline in `app/login/page.tsx` —
 * and the three other places a person types a secret had none: the admin's "create a user" password
 * (`/users`), the repository API keys (`ApiKeysPanel`) and a person's own AI key
 * (`MyAiKeysPanel`). All three are boxes somebody PASTES OR RE-TYPES a long value into and then has
 * no way to check, which is the one situation a masked field is worst at: a mistyped repository key
 * fails as "the provider rejected this", hours later, on somebody else's screen.
 *
 * ── WHAT THIS COMPONENT IS AND IS NOT ─────────────────────────────────────────────────────────
 *
 * It is the BUTTON, not the input. The four call sites have four different inputs — a `TextInput`,
 * two bare `<input>`s with their own class strings, and the login card's 52px-tall field — and a
 * wrapper component that owned the input would have had to grow a prop for each of them. The
 * caller keeps its input, adds `pr-11` so the glyph has somewhere to sit, and renders this inside
 * the same `relative` box.
 *
 * ── THE ACCESSIBILITY RULES, WHICH ARE THE PART THAT IS EASY TO GET WRONG ─────────────────────
 *
 * * **`aria-pressed`, not just an `aria-label`.** This is a toggle with two states, and a button
 *   whose only signal is its own changing label reads as two different buttons to a screen reader.
 * * **The label says what the PRESS will do** ("Show password" while hidden), which is the
 *   convention every browser's own password reveal follows.
 * * **`type="button"`.** Inside a `<form>` a bare `<button>` submits it — so an eye on the login
 *   card would attempt a sign-in with a half-typed password.
 * * **The icon is `aria-hidden`.** The button already has a name; a labelled icon inside a labelled
 *   button is announced twice.
 *
 * ── AND THE ONE RULE THAT IS ABOUT THE SECRET RATHER THAN THE CONTROL ────────────────────────
 *
 * Revealing is per-render state held by the CALLER and is never persisted — not to `localStorage`,
 * not to preferences. A field laptop is shared, and a reveal that survived a reload would put the
 * next person's key on screen for them.
 */

import { Eye, EyeOff } from "lucide-react";

import { cn } from "@/lib/utils";

export function PasswordRevealButton({
  revealed,
  onToggle,
  className,
  size = 22,
  noun = "password"
}: {
  revealed: boolean;
  onToggle: () => void;
  /** Positioning only. The call site owns where inside its `relative` box the glyph sits. */
  className?: string;
  size?: number;
  /**
   * What the box holds, for the accessible name. Two of the four call sites hold an API KEY, and
   * "Show password" on a box labelled "Paste your key" describes a control that is not there —
   * which is exactly the sort of mismatch a screen-reader user cannot see past.
   */
  noun?: string;
}) {
  return (
    <button
      type="button"
      aria-label={revealed ? `Hide ${noun}` : `Show ${noun}`}
      aria-pressed={revealed}
      onClick={onToggle}
      className={cn(
        "absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 transition hover:text-ink-500",
        className
      )}
    >
      {revealed ? <EyeOff size={size} aria-hidden /> : <Eye size={size} aria-hidden />}
    </button>
  );
}
