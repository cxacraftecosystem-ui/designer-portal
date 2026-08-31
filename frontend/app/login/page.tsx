"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, Lock, Mail } from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { useAuth } from "@/components/AuthProvider";
import { Button, buttonVariants } from "@/components/ui/button";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import {
  ACCESS_STATUS_HEADER,
  accessRefusalChrome,
  accessRefusalKind,
  type AccessRefusalKind
} from "@/lib/accessRoster";
import {
  MIN_PASSWORD_LENGTH,
  SIGN_IN_HINT_HEADER,
  changeOwnPassword,
  mustChangePassword,
  passwordRuleLine,
  signInHintHeading,
  signInHintOf,
  type SignInHint
} from "@/lib/signIn";
// THE NOTICE IS NO LONGER RENDERED HERE. `UsageConsentDisclosure` used to be imported across from
// components/settings so the door and the withdrawal screen showed one text; the door now shows one
// line and links to `/terms`, which renders `UsageConsentNoticeBody` — the same component, the same
// server text — as clause 10. Two consumers still, and still one source of words.
import {
  loadUsageConsentNotice,
  recordUsageConsent,
  usageConsentGateOf,
  type UsageConsentGate,
  type UsageConsentNotice
} from "@/lib/usage";
import type { User } from "@/lib/types";
import { cn } from "@/lib/utils";

declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: { client_id: string; callback: (response: { credential: string }) => void }) => void;
          renderButton: (element: HTMLElement, options: Record<string, string | boolean | number>) => void;
        };
      };
    };
  }
}

/** Official provider marks (inline SVG — no external requests). */
function GoogleMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" className={className} aria-hidden>
      <path
        fill="#EA4335"
        d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z"
      />
      <path
        fill="#4285F4"
        d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z"
      />
      <path
        fill="#FBBC05"
        d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z"
      />
      <path
        fill="#34A853"
        d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z"
      />
    </svg>
  );
}

function MicrosoftMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 21 21" className={className} aria-hidden>
      <rect x="1" y="1" width="9" height="9" fill="#f25022" />
      <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
      <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
      <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
    </svg>
  );
}

function YahooMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} aria-hidden>
      <path
        d="M0 6.71h4.62l2.69 6.88 2.72-6.88h4.5L7.76 22.5H3.23l1.86-4.32L0 6.71zm17.62 5.05h-5.03L17.06 1.5h5.02l-4.46 10.26zm-3.03 1.4c1.55 0 2.8 1.26 2.8 2.81a2.8 2.8 0 1 1-5.61 0c0-1.55 1.26-2.8 2.81-2.8z"
        fill="#5f01d1"
      />
    </svg>
  );
}

/**
 * Below ~420px the badge and the full provider name cannot both fit on one 52px row, and
 * something has to give: the badge hides and the tap still raises the "Coming soon" toast,
 * which beats truncating the provider's name to "Continue with Micro…".
 */
function ComingSoonBadge() {
  return (
    <span className="hidden shrink-0 rounded-full bg-purple-50 px-2 py-0.5 text-[11px] font-semibold text-purple-700 min-[420px]:inline-block">
      Coming soon
    </span>
  );
}

const BRAND_POINTS = [
  "Artisans, crafts, workshops, products, tools and interviews — one connected archive.",
  "Recordings transcribed and translated to English automatically.",
  // EIGHT SINCE 2026-08-27, when INSPECTOR (37) landed. This line said "Seven-tier" and is the
  // second copy of the sentence the landing hero speaks — `HeroLanding.tsx` renders the same
  // "<n>-tier access control" badge but DERIVES its number from `AccessLadder.tsx`'s
  // `TIER_COUNT_WORD`, i.e. from `ROLES_BY_RANK.length`, so the hero re-counted itself on the day
  // the tier shipped and this page did not. It is left hand-written rather than imported because
  // `AccessLadder.tsx` is a framer-motion component and the login page pulls in no animation
  // library at all; the cost of importing one for a single word is worse than the count. If you
  // add a tier, this is one of the lines that will not tell you.
  "Eight-tier access control; every edit audited."
];

/** Shared chrome for the four sign-in actions, so they are one height and one radius. */
const PROVIDER_BUTTON = buttonVariants({ variant: "provider", size: "auth" });

export default function LoginPage() {
  // ToastProvider lives in app/layout.tsx, so this page needs no provider of its own.
  return <LoginView />;
}

/**
 * CONSTANT DOM IDS, NOT `useId`, AND THE REASON IS THE GOOGLE PATH.
 *
 * There is exactly one of this control on the page, so `useId`'s collision-avoidance buys nothing
 * here — and it costs the thing that matters: when the Google button is clicked while the box is
 * unticked, the refusal must not only be SAID, it must put the person on the control that clears
 * it, and the handler that does that lives in `LoginView` and cannot see a child's generated id.
 * (`UsageConsentDisclosure`, which has two consumers, does use `useId` — the rule is about how many
 * instances can exist, not about taste.)
 */
const AGREE_BOX_ID = "usage-consent-agree";
const AGREE_HINT_ID = "usage-consent-agree-hint";

/**
 * THE TURNSTILE: one checkbox, one line, and the phrase that carries the detail is a link.
 *
 * ── WHAT THIS USED TO BE, AND WHY IT IS NOT THAT ANY MORE ───────────────────────────────────────
 *
 * Until 2026-08-30 this control carried the entire usage-recording notice at the door: a two-clause
 * label naming what is and is not collected, an expandable disclosure headed "Read what is
 * recorded, and what is not", and a three-branch live region explaining the disabled button. It was
 * accurate, it was defensible, and nobody read a word of it — which is the failure it was written to
 * prevent, arrived at from the other side. The owner's instruction was to cut it to one line.
 *
 * So the agreement is now the ordinary one — the terms and conditions — and the recording notice is
 * clause 10 of them, at `/terms`, rendered from the same server text this screen used to expand
 * inline. Nothing was dropped; the reading moved to a page built for reading.
 *
 * ── THE LINK OPENS IN A NEW TAB, AND THAT IS THE POINT ──────────────────────────────────────────
 *
 * The objection to a link here was always right: on this screen a navigation away costs a half-typed
 * password. `target="_blank"` is what answers it — the terms open beside the card and the form is
 * exactly where it was left. `rel="noopener noreferrer"` because a `_blank` link without it hands
 * the opened page a live `window.opener`.
 *
 * ── THE ACCESSIBILITY DECISIONS THAT SURVIVE UNCHANGED ──────────────────────────────────────────
 *
 * **A REAL `<input type="checkbox">` WITH A REAL `<label htmlFor>`.** Not a styled `<div
 * role="checkbox">`: this is a legal agreement, it must appear in the form's own validity state, it
 * must respond to Space, and its checked state must be reported by the platform rather than by an
 * attribute somebody remembered to write. `required` is on it too, so a browser that submits past
 * the disabled button still refuses.
 *
 * **THE BLOCKED REASON IS `aria-describedby` ON THE CHECKBOX, NOT ON THE BUTTON.** A `disabled`
 * button is not focusable, so nothing on it is ever announced. The checkbox is the control a reader
 * lands on and the control that clears the block, so the sentence belongs to it.
 *
 * **THE REGION IS PRESENT FROM FIRST PAINT AND ONLY ITS TEXT CHANGES.** Assistive technology
 * announces mutations inside a live region that already existed; one that appears with its message
 * is frequently announced as nothing at all.
 *
 * **THE LABEL DOES NOT WRAP THE LINK BY ACCIDENT.** A `<label>` forwards a stray click to its
 * control, so a click on "terms and conditions" would toggle the box as well as follow the link. The
 * link therefore sits OUTSIDE the `<label>`, as a sibling, and the sentence is split across the two.
 */
function ConsentGateField({
  agreed,
  noticeError,
  onChange
}: {
  agreed: boolean;
  /**
   * The recording notice failed to fetch. It does NOT block the door — the terms are a static page
   * and the tick is an agreement to them — but it does mean `settleConsent` has no version to file
   * the answer against, so the question comes round again. One line, in the region that is already
   * here, rather than a panel: it is rare, there is nothing to act on, and the sign-in works.
   */
  noticeError: string | null;
  onChange: (agreed: boolean) => void;
}) {
  const boxId = AGREE_BOX_ID;
  const hintId = AGREE_HINT_ID;

  return (
    <div className="grid gap-1">
      <div className="flex items-center gap-2.5">
        <input
          id={boxId}
          type="checkbox"
          required
          checked={agreed}
          onChange={(event) => onChange(event.target.checked)}
          aria-describedby={hintId}
          // `accent-color` is the one honest way to tint a native checkbox without replacing it, and
          // replacing it is what costs the platform's own checked semantics. The literal is the
          // action colour from `tailwind.config.ts` — purple-700 — written out because
          // `accent-purple-700` compiles to a Tailwind utility that carries `<alpha-value>` and the
          // property takes no alpha.
          className="h-4 w-4 shrink-0 rounded border-line-200 [accent-color:oklch(0.47_0.198_305)]"
        />
        <span className="min-w-0 text-sm leading-6 text-ink-900">
          <label htmlFor={boxId}>I agree to the</label>{" "}
          <Link
            href="/terms"
            target="_blank"
            rel="noopener noreferrer"
            className="font-medium text-purple-700 underline underline-offset-2 hover:text-purple-800"
          >
            terms and conditions
          </Link>
        </span>
      </div>

      <p id={hintId} role="status" aria-live="polite" className="text-xs leading-5 text-ink-500">
        {!agreed
          ? "Required to sign in."
          : noticeError
            ? "The recording notice is unavailable, so this answer is not filed yet. You will be asked again."
            : ""}
      </p>
    </div>
  );
}

/**
 * A STANDING REFUSAL, HELD ON SCREEN BEFORE THE PERSON IS LET THROUGH.
 *
 * This is the panel that makes the whole design defensible, and it exists for one account shape: a
 * person who withdrew in Settings and has now ticked the box at the door, because the sign-in screen
 * cannot know who is signing in until they have. Their earlier answer stands and NOTHING was posted
 * — a turnstile that quietly re-granted consent on every sign-in would turn withdrawal into
 * theatre, which is exactly the failure `docs/DECISION-usage-consent-at-sign-in.md` was written
 * against.
 *
 * They ticked a box saying they agree, so they are owed the correction in words rather than a silent
 * no-op. The sentence is the server's `gate.reason`, which for a REFUSED account already says the
 * answer is on record, that it costs them nothing, and that Settings can change it.
 *
 * `role="status"` and not `alert`: nothing has gone wrong, and nothing is being refused — the person
 * is signed in and one button away from the app.
 */
function StandingRefusal({ gate, onContinue }: { gate: UsageConsentGate; onContinue: () => void }) {
  return (
    <div role="status" className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-4 text-sm leading-6">
      <p className="font-display text-base font-bold text-ink-900">Your earlier answer stands</p>
      <p className="text-ink-700">{gate.reason}</p>
      {/* One line, not a paragraph. The server's sentence above already says what the answer is and
          that it costs nothing; all this half has to add is that the tick did not overturn it. */}
      <p className="text-ink-700">Ticking the box did not change it. Settings is where to change it.</p>
      <Button type="button" size="auth" onClick={onContinue} className="mt-1 w-full font-display text-base font-bold">
        Continue
      </Button>
    </div>
  );
}

/**
 * THE FIRST-LOGIN PASSWORD, ASKED BETWEEN SIGN-IN AND THE DASHBOARD.
 *
 * ── THE REQUIREMENT, AND WHAT WAS ACTUALLY MISSING ──────────────────────────────────────────────
 *
 * Owner: *"they would be able to set the password on their first login, and confirm it"*. Every
 * piece of the mechanism existed and nothing joined them up: `POST /api/users` creates an account
 * with `mustChangePassword` set, `serialize_user` puts the flag on every sign-in answer and every
 * `/me`, and `POST /auth/change-password` is the route it names — and NO SCREEN ON EITHER CLIENT
 * READ THE FLAG. An account created with a password an administrator typed signed in, worked
 * normally, and nobody was ever asked to replace a secret that by construction two people know.
 *
 * ── IT REPORTS, WE REFUSE — THE CONSENT GATE'S OWN SHAPE ────────────────────────────────────────
 *
 * The server deliberately does not 403 the sign-in, for the reason the column's comment gives: the
 * only route that can change a password needs a bearer token, so refusing here would leave the
 * account permanently unable to comply. So this is the same arrangement as `StandingRefusal` above
 * and as Android's `UsageConsentGateScreen`: the server states the fact, the client is the gate.
 *
 * ── WHY IT REPLACES THE CARD RATHER THAN OPENING A DIALOG ───────────────────────────────────────
 *
 * A dialog is dismissible, and "you may set a password if you feel like it" is not the requirement.
 * The person holds a token by this point, so leaving the sign-in controls live underneath would also
 * offer them a second sign-in they neither need nor can usefully make — the argument
 * `StandingRefusal` already makes one branch up.
 *
 * ── THE CURRENT PASSWORD IS USUALLY NOT ASKED FOR, AND SOMETIMES MUST BE ────────────────────────
 *
 * `POST /auth/change-password` requires it even for an account carrying this flag, and it is right
 * to: the flag means "the password you hold was typed for you", not "anybody at this keyboard may
 * replace it". On the ordinary path the person typed that password into the box above ten seconds
 * ago, so asking for it again would be asking somebody to re-type a secret this page is already
 * holding. Where the page is NOT holding one — a Google sign-in, or a session already open when the
 * page loaded — the box appears, because the alternative is a gate whose only button cannot succeed.
 *
 * ── AND IT HAS A WAY OUT, WHICH IS NOT A HEDGE ──────────────────────────────────────────────────
 *
 * "Sign out instead" is the escape `UsageConsentGateScreen` carries, for its reason: a person who
 * cannot complete this — they do not know the temporary password, the server is unreachable — must
 * not be held on one screen whose controls all do nothing. Signing out returns them to a door they
 * can use with a different account. It does not let anybody INTO the product.
 */
function FirstPasswordGate({
  currentPassword,
  onDone,
  onSignOut
}: {
  /** The password typed at the door, or "" when this session did not see one. */
  currentPassword: string;
  onDone: () => void;
  onSignOut: () => void;
}) {
  const [current, setCurrent] = useState(currentPassword);
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [show, setShow] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Asked only where the door has nothing to offer. Computed from the PROP and not from `current`,
  // which the person is about to type into — reading the state would make the box vanish under the
  // caret on the first keystroke.
  const askCurrent = currentPassword.length === 0;

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (next !== confirm) {
      setError("The two passwords do not match.");
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await changeOwnPassword(current, next);
      onDone();
    } catch (err) {
      // The server's own sentence wins: it is the only text that knows whether the current password
      // was wrong, whether the account has no password to change at all, or whether the new one was
      // refused — three different next moves behind one status code family.
      setError(err instanceof Error ? err.message : "Could not set the password.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={submit} className="grid gap-3">
      {/* `role="status"` and not `alert`: nothing has gone wrong and nothing is being refused — the
          person is signed in and one form away from the app. Same reading as `StandingRefusal`. */}
      <div role="status" className="rounded-md border border-line-200 bg-surface-50 p-3 text-sm leading-6 text-ink-700">
        {/* TERSE. The whole explanation is that somebody else chose the password they just used. */}
        An administrator set your password. Choose your own to continue.
      </div>
      {error ? (
        <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
          {error}
        </div>
      ) : null}

      {askCurrent ? (
        <div className="grid gap-2">
          <label htmlFor="gate-current-password" className="text-sm font-medium text-ink-900">
            Current password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="gate-current-password"
              type={show ? "text" : "password"}
              autoComplete="current-password"
              required
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
              className="field-input h-[52px] pl-10"
            />
          </div>
        </div>
      ) : null}

      <div className="grid gap-2">
        <label htmlFor="gate-new-password" className="text-sm font-medium text-ink-900">
          New password
        </label>
        <div className="relative">
          <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
          <input
            id="gate-new-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            value={next}
            onChange={(event) => setNext(event.target.value)}
            className="field-input h-[52px] pl-10 pr-11"
          />
          {/* ONE TOGGLE FOR EVERY BOX ON THIS FORM, the ruling `/set-password` already made: the
              pair is typed in sequence by one person checking one password against itself, and two
              independent eyes would let them be revealed separately — the one arrangement in which
              "they do not match" is still a mystery. `aria-pressed` because it is a toggle. */}
          <button
            type="button"
            aria-label={show ? "Hide password" : "Show password"}
            aria-pressed={show}
            onClick={() => setShow((value) => !value)}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 hover:text-ink-500"
          >
            {show ? <EyeOff size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
          </button>
        </div>
      </div>

      <div className="grid gap-2">
        <label htmlFor="gate-confirm-password" className="text-sm font-medium text-ink-900">
          Repeat password
        </label>
        <div className="relative">
          <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
          <input
            id="gate-confirm-password"
            type={show ? "text" : "password"}
            autoComplete="new-password"
            required
            minLength={MIN_PASSWORD_LENGTH}
            value={confirm}
            onChange={(event) => setConfirm(event.target.value)}
            className="field-input h-[52px] pl-10"
          />
        </div>
      </div>

      {/* The same first clause as `/set-password`, from the same function — see `passwordRuleLine`.
          The second clause differs because nobody here is holding a link, and because this route
          deliberately does NOT revoke sessions the way a link redemption does. */}
      <p className="text-xs leading-5 text-ink-500">{passwordRuleLine("Other devices stay signed in.")}</p>

      <Button type="submit" size="auth" disabled={saving} className="mt-1 w-full font-display text-base font-bold">
        {saving ? "Saving…" : "Set password and continue"}
      </Button>
      <button type="button" className="field-button-secondary w-full" onClick={onSignOut}>
        Sign out instead
      </button>
    </form>
  );
}

/**
 * WHY THE CONSENT GATE LIVES ON THIS SCREEN AND WHAT IT MAY AND MAY NOT DO.
 *
 * ── THE GATE IS A TURNSTILE, AND THE SERVER DELIBERATELY DOES NOT ENFORCE IT ────────────────────
 *
 * `POST /auth/login` still mints a token for an account that has never answered, and that is not an
 * oversight: recording an answer needs a bearer token, so a 403 at the door would be a gate nobody
 * could ever get past, and it would be a second lockout reachable by the break-glass master admin.
 * `serialize_user` therefore REPORTS — every sign-in answer carries `usageConsentGate` — and the
 * blocking half belongs to the clients. This screen is the web half of it.
 *
 * ── THE FOUR THINGS THAT MADE THIS HARDER THAN A CHECKBOX ───────────────────────────────────────
 *
 * 1. **GOOGLE IS THE PATH RESEARCHERS ACTUALLY USE, AND `disabled` CANNOT REACH IT.** The real
 *    Google button is rendered by Google's own script INSIDE `googleHost`, under a transparent
 *    overlay, and nothing in this bundle owns that element. So the gate is enforced in THREE places
 *    and not one: an early return inside the GIS callback (the only one that is actually load-
 *    bearing — it runs after Google has already signed the person in), `pointer-events-none` so the
 *    click never lands, and `inert` so the keyboard cannot reach it either. Disabling only the
 *    password submit would have shipped a gate that the majority of sign-ins walk straight past.
 * 2. **A REFUSAL MUST SURVIVE THE TURNSTILE.** Somebody who withdrew in settings and then signs in
 *    ticks this box like everybody else — the screen cannot know who they are before they sign in.
 *    Posting GRANTED for them would silently overturn a withdrawal on every single sign-in, which
 *    would make the withdrawal theatre and the whole flow indefensible. So the answer is recorded
 *    ONLY where the server says `required`, and a standing REFUSED is held on screen and explained
 *    in the server's own words before the person is let through.
 * 3. **THE NOTICE FAILING TO LOAD MUST NOT BAR THE DOOR** — and since 2026-08-30 it cannot even
 *    reach the door. The box now agrees to `/terms`, a page this bundle serves, so a tick means what
 *    it says whether or not `GET /usage/consent/notice` answered. The notice is still fetched,
 *    because `settleConsent` files the answer against its version; when it is missing, nothing is
 *    filed, one line under the box says so, and the server's gate still reads `required`, so the
 *    question comes round at the next sign-in and in Settings. What this bullet used to describe —
 *    no checkbox at all, and the gate cleared by a failed fetch — was correct while the checkbox WAS
 *    the notice, and would now be a front door that a dead endpoint walks straight through.
 * 4. **THE REASON A CONTROL IS BLOCKED IS SPOKEN, NOT ONLY GREYED.** A `disabled` button is not
 *    focusable, so an `aria-describedby` on it is never announced; the requirement rides on the
 *    CHECKBOX instead, and a live region that is present from first paint says what is blocking and
 *    then says it has cleared.
 */
function LoginView() {
  const router = useRouter();
  const { login, loginWithGoogle, logout, refreshMe, user } = useAuth();
  const { toast } = useToast();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /**
   * WHAT THE REFUSAL WAS, held beside the message rather than sniffed back out of it.
   *
   * The two answers this screen must never blur into one are "your password is wrong" and "an
   * administrator has not approved you yet". They arrive as a 401 and a 403 with different words,
   * and the card draws different chrome around them — so the kind is kept as data. Deriving it from
   * the sentence would break silently the first time somebody rewords the sentence, and the screen
   * would go on looking perfectly correct while telling a person waiting on an approval to check
   * their password.
   */
  const [refusal, setRefusal] = useState<AccessRefusalKind | null>(null);
  /**
   * The OTHER kind of refusal: about what was typed rather than about admission.
   *
   * Held separately from `refusal` and not folded into it, because the two headers answer two
   * different questions and a single switch over both would have to invent a precedence for the
   * (impossible today, cheap to keep impossible) case where a server sends both.
   */
  const [hint, setHint] = useState<SignInHint>(null);
  const [loading, setLoading] = useState(false);
  const googleHost = useRef<HTMLDivElement | null>(null);
  const renderedWidth = useRef(0);
  const googleClientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;

  /** The recording notice, fetched ungated. `null` while in flight; `noticeError` once it failed. */
  const [notice, setNotice] = useState<UsageConsentNotice | null>(null);
  const [noticeError, setNoticeError] = useState<string | null>(null);
  const [agreed, setAgreed] = useState(false);
  /**
   * THE MOMENT THE BOX WAS TICKED, held from the tick rather than read at POST time.
   *
   * The two are separated by a credential check and a network round trip, which on the connections
   * this product is used over is not a matter of milliseconds. The server already stamps its own
   * `createdAt` when it hears the answer, so sending the moment of the tick is the only thing that
   * makes the pair of clocks mean anything — otherwise both fields record the same event twice and
   * a record says "the device reported this" about something the device never separately observed.
   */
  const agreedAt = useRef<string | null>(null);
  /**
   * A standing answer this sign-in must NOT overwrite, held on screen until the person acknowledges
   * it. Set only for an account whose recorded answer is REFUSED — see the header comment.
   */
  const [held, setHeld] = useState<UsageConsentGate | null>(null);
  /**
   * True from the first line of a sign-in attempt until it has been fully settled.
   *
   * A REF AND NOT STATE, and that is load-bearing. `login()` calls `setUser`, React flushes that
   * update across the `await` boundary, and the redirect effect below runs BEFORE any `setHeld` in
   * the same handler could land — so a state flag would be read as `false` and the person would be
   * navigated to /dashboard a frame before the panel explaining their standing refusal appeared. A
   * ref is written synchronously and is already correct when that effect runs.
   */
  const signingIn = useRef(false);
  /**
   * True once THIS visit has replaced the temporary password, so the gate below closes even if the
   * `/me` that would have proved it never lands.
   *
   * DERIVED-PLUS-A-LATCH RATHER THAN A COPY OF THE ACCOUNT. `passwordGate` reads the live `user`, so
   * a session that was ALREADY open when this page loaded meets the gate too — not only the sign-in
   * that has just happened — and there is no second copy of the flag to go stale. The latch is what
   * covers the one case the derivation cannot: `changeOwnPassword` has succeeded server-side, and the
   * best-effort `refreshMe()` after it fails on a dropped connection. Without it the person would be
   * asked a second time for a password they had just set, and told the current one was wrong.
   */
  const [passwordSet, setPasswordSet] = useState(false);
  /**
   * The account that must choose its own password before it is let in, or null.
   *
   * Held apart from `held` for the same reason `hint` is held apart from `refusal`: two gates that
   * answer two different questions, and folding them into one piece of state would oblige somebody to
   * invent a precedence between "your earlier consent answer stands" and "set a password". The
   * ordering is expressed where it belongs — in the render below, consent first — and is argued there.
   */
  const passwordGate = !passwordSet && mustChangePassword(user) ? user : null;

  useEffect(() => {
    if (user && !signingIn.current && !held && !passwordGate) router.replace("/dashboard");
  }, [held, passwordGate, router, user]);

  useEffect(() => {
    let cancelled = false;
    loadUsageConsentNotice()
      .then((result) => {
        if (!cancelled) setNotice(result);
      })
      .catch((err) => {
        if (cancelled) return;
        setNoticeError(err instanceof Error ? err.message : "The recording notice could not be loaded.");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * THE GATE ITSELF: blocked until the box is ticked. One clause, and it used to be two.
   *
   * The second clause was `&& noticeError === null` — a failed fetch of the recording notice cleared
   * the gate, because a tick could not honestly mean "I agree" to text the screen had not managed to
   * show. That reasoning was correct while the checkbox WAS the recording notice. It is not any
   * more: the box now agrees to the terms and conditions, which are a static page this bundle serves
   * and which no fetch can fail to produce. So the tick means what it says whether or not
   * `/usage/consent/notice` answered, and the gate is the tick.
   *
   * **NOTHING WAITS ON THE NOTICE ANY LONGER EITHER.** The old control disabled the checkbox while
   * the fetch was in flight, so that nobody signed in past a question that had not arrived. There is
   * no such question now, and keeping the wait would have made the front door of the app depend on an
   * endpoint it no longer needs. The notice is still fetched — `settleConsent` files the answer
   * against its version — and its absence is stated under the box rather than blocking anybody.
   */
  const blocked = !agreed;

  /**
   * What to do with the tick now that we know whose account it was. Returns the gate to HOLD on, or
   * null to proceed.
   *
   * The three branches, and the middle one is the one that matters:
   *
   *  - `required` — nobody has asked this account, or the notice has moved. Record GRANTED with the
   *    basis `REQUIRED_AT_SIGN_IN`, which is the column that stops a turnstile being filed as a free
   *    choice, then re-read `/me` so the session carries the fresh gate.
   *  - not required, state REFUSED — **the tick does not overturn it.** Nothing is posted, and the
   *    server's own sentence is put on screen before the person is let through, because they have
   *    just ticked a box saying they agree and the honest thing is to tell them their earlier answer
   *    still stands and where to change it.
   *  - not required, state GRANTED at the current version — the answer already stands; a second
   *    identical decision row would be noise in a log whose whole value is that every row is a
   *    decision somebody made.
   */
  const settleConsent = useCallback(
    async (account: User): Promise<UsageConsentGate | null> => {
      const gate = usageConsentGateOf(account);
      // No gate on the payload means a server that predates the column. Claim nothing: do not
      // record an answer, do not hold anybody, and let the session through.
      if (!gate || !notice) return null;
      if (!gate.required) return gate.state === "REFUSED" ? gate : null;
      try {
        await recordUsageConsent({
          decision: "GRANTED",
          basis: "REQUIRED_AT_SIGN_IN",
          // What this screen ACTUALLY SHOWED, never a constant — an agreement filed against text the
          // person did not see is a signature on a page nobody turned.
          noticeVersion: notice.version,
          recordedAt: agreedAt.current ?? undefined
        });
      } catch {
        /*
          THE ANSWER DID NOT LAND, AND THIS IS THE ONE FAILURE THAT MUST NOT BLOCK ANYBODY.

          They are signed in and hold a token; the server's gate still reads `required`, so they will
          be asked again at their next sign-in and the settings card asks too. Until then the account
          keeps being recorded exactly as an unasked account is — anonymously — which is the state it
          was already in a second ago. A toast is the right carrier precisely because there is
          nothing here to act on: `aria-live="polite"` never interrupts, and this notice would be
          wrong in a dialog.
        */
        toast({
          title: "Your answer could not be saved",
          description:
            "You are signed in. The recording question will be asked again at your next sign-in, and Settings can answer it at any time.",
          tone: "error"
        });
        return null;
      }
      /*
        THE RE-READ IS OUTSIDE THE CATCH ABOVE, AND THAT IS NOT COSMETIC.

        The answer has landed by this point. If `/me` then fails — a dropped connection between two
        requests, which on the connections this product is used over is an ordinary event — folding
        it into the same `catch` would tell somebody their answer could not be saved when it had
        been, and invite them to answer it again. The refresh is only a cache invalidation: the
        session goes on carrying the pre-click gate until the next `/me`, and nothing is lost. So it
        is best-effort, and it says nothing when it fails.
      */
      await refreshMe().catch(() => undefined);
      return null;
    },
    [notice, refreshMe, toast]
  );

  /**
   * THE GATE, REACHABLE FROM INSIDE GOOGLE'S CALLBACK WITHOUT BEING IN ITS DEPENDENCY ARRAY.
   *
   * The GIS effect below calls `window.google.accounts.id.initialize`, which replaces Google's
   * whole client configuration and forces `renderGoogleButton` to redraw its iframe. Putting
   * `blocked` in that effect's deps would re-run all of it on every keystroke of the checkbox —
   * and, worse, would make the tick that unblocks the button the same thing that tears the button
   * down. So the callback reads the current values through a ref, which is the treatment
   * `useEditDeepLink` gives its own late-settling inputs for the same reason.
   */
  const gateNow = useRef({ blocked, settleConsent });
  useEffect(() => {
    gateNow.current = { blocked, settleConsent };
  }, [blocked, settleConsent]);

  /**
   * One place that turns a failed sign-in into what this card shows, used by BOTH paths.
   *
   * Both, and that is the whole point of hoisting it. The password path and the Google path are
   * refused by the same gate for the same reasons, and until this feature the Google path reported
   * everything through a toast headed "Google sign-in failed" — which, for somebody awaiting an
   * administrator's approval, names the wrong culprit entirely and sends them to try the password
   * boxes that will refuse them identically.
   *
   * DECLARED ABOVE THE GOOGLE EFFECT THAT DEPENDS ON IT. A `const` referenced in an earlier hook's
   * dependency array is read during render, before the initialiser has run — a temporal-dead-zone
   * crash on the app's front door, and one that only fires when Google sign-in is configured.
   */
  const describeFailure = useCallback((err: unknown) => {
    const status = err instanceof ApiError ? err.status : 0;
    // `headers` is absent when there was no response at all (offline, or a build with no API
    // address), and the header itself is absent when a proxy stripped it or the deployment predates
    // it. Both land on UNCLASSIFIED, which draws neutral chrome around the server's own sentence
    // rather than guessing at a category — the only safe direction to be wrong in on this screen.
    const kind = accessRefusalKind(status, err instanceof ApiError ? err.headers?.get(ACCESS_STATUS_HEADER) : null);
    setRefusal(kind);
    // The identifier-shaped refusals ride their own header for the reason the server's comment
    // gives: the refusal BODY is asserted to hold nothing but `detail`, so anything a client
    // needs to branch on has to travel beside it. Absent means unclassified, which draws the
    // plain box around the server's own sentence.
    setHint(signInHintOf(err instanceof ApiError ? err.headers?.get(SIGN_IN_HINT_HEADER) : null));
    setError(err instanceof Error ? err.message : "Unable to sign in or reach the server.");
  }, []);

  /**
   * Google Identity Services will only render *its* button, at its own size — which is why
   * it never matched the rest of the card. So we draw our own chrome and lay the real GSI
   * button over it, transparent: it stays the only focusable, clickable thing (the flow is
   * untouched, no dead request), while the size and radius become ours. GSI takes a pixel
   * width, so the hit area is re-rendered whenever the card resizes.
   */
  const renderGoogleButton = useCallback(() => {
    const host = googleHost.current;
    if (!host || !window.google) return;
    const width = Math.round(Math.min(400, Math.max(200, host.offsetWidth)));
    if (width === renderedWidth.current) return;
    renderedWidth.current = width;
    host.replaceChildren();
    window.google.accounts.id.renderButton(host, {
      theme: "outline",
      size: "large",
      shape: "rectangular",
      text: "continue_with",
      width
    });
  }, []);

  useEffect(() => {
    if (!googleClientId) return;

    const initialize = () => {
      window.google?.accounts.id.initialize({
        client_id: googleClientId,
        callback: async (response) => {
          /*
            THE LOAD-BEARING HALF OF THE CONSENT GATE ON THE GOOGLE PATH.

            By the time this runs, Google has already authenticated the person; what has NOT happened
            is `POST /auth/login`, so refusing here refuses the thing that actually matters — no
            session, no token, no account created. The `pointer-events-none` and `inert` on the host
            below are belt and braces for a control this bundle does not own; THIS is the brace that
            holds if Google ever changes how that element is rendered.

            Into the card and not a toast, for the same reason every other refusal on this screen is:
            the person is looking at the form, and a message that disappears on a timer is a message
            somebody reads as "the button did nothing".
          */
          if (gateNow.current.blocked) {
            setRefusal(null);
            setError("Please agree to the terms and conditions above the sign-in buttons.");
            // SAYING IT IS NOT ENOUGH: the person clicked a button and, from where they are looking,
            // nothing happened. Focus moves to the control that clears the block, so a keyboard or
            // screen-reader user is put on it rather than being told to go and find it. `focus` is
            // safe on a missing element only because it is guarded — and since 2026-08-30 the box is
            // rendered unconditionally (there is no longer a notice-failed branch that omits it), so
            // the guard is the only thing standing between this and a control that is always there.
            document.getElementById(AGREE_BOX_ID)?.focus();
            return;
          }
          setError(null);
          setRefusal(null);
          signingIn.current = true;
          try {
            const account = await loginWithGoogle(response.credential);
            const standing = await gateNow.current.settleConsent(account);
            signingIn.current = false;
            if (standing) {
              setHeld(standing);
              return;
            }
            // THE PASSWORD GATE IS READ OFF THE ACCOUNT HERE AND NOT LEFT TO THE REDIRECT EFFECT,
            // for the reason `signingIn` exists at all: `loginWithGoogle` has already called
            // `setUser`, so navigating now would put somebody on the dashboard a frame before the
            // gate could render. The effect's guard is the belt; this is the brace, and it is the
            // one that holds on the path a person actually walks.
            if (mustChangePassword(account)) return;
            router.replace("/dashboard");
          } catch (err) {
            signingIn.current = false;
            /*
              INTO THE CARD, NOT INTO A TOAST — and this is the biggest behavioural change on this
              screen.

              A verified Google address that is not on the allow-list no longer self-provisions an
              account: it becomes a pending request and is refused with a sentence saying so. That
              sentence is the ONLY thing standing between the person and the belief that Google is
              broken, and a toast is the wrong carrier for it — it disappears on a timer, it is
              announced away from the form, and its old title ("Google sign-in failed") blamed the
              identity provider for an administrator's decision. The panel below stays on screen,
              says what happened, and says what to do next.
            */
            describeFailure(err);
          }
        }
      });
      renderGoogleButton();
    };

    if (window.google) {
      initialize();
      return;
    }
    const existing = document.querySelector<HTMLScriptElement>('script[src="https://accounts.google.com/gsi/client"]');
    if (existing) {
      existing.addEventListener("load", initialize, { once: true });
      return;
    }
    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.onload = initialize;
    script.onerror = () =>
      toast({
        title: "Could not load Google sign-in",
        description: "Check your connection and reload the page.",
        tone: "error",
        duration: 0
      });
    document.body.appendChild(script);
  }, [describeFailure, googleClientId, loginWithGoogle, renderGoogleButton, router, toast]);

  useEffect(() => {
    const host = googleHost.current;
    if (!host) return;
    const observer = new ResizeObserver(() => renderGoogleButton());
    observer.observe(host);
    return () => observer.disconnect();
  }, [renderGoogleButton]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // The submit button is already disabled while `blocked`, so this cannot normally be reached —
    // it is here for the Enter key in a text field, which submits a form past a disabled button in
    // some browsers, and because a gate that exists in exactly one place is a gate one refactor
    // away from not existing.
    if (blocked) return;
    setLoading(true);
    setError(null);
    setRefusal(null);
    setHint(null);
    signingIn.current = true;
    try {
      const account = await login(email, password);
      const standing = await settleConsent(account);
      if (standing) {
        setHeld(standing);
        return;
      }
      // See the Google path above: read off the ACCOUNT rather than waiting for the redirect effect,
      // which runs on a `user` React has already flushed.
      if (mustChangePassword(account)) return;
      router.replace("/dashboard");
    } catch (err) {
      describeFailure(err);
    } finally {
      signingIn.current = false;
      setLoading(false);
    }
  }

  /** Fires the notice and nothing else — these providers have no endpoint behind them yet. */
  function comingSoon(provider: string) {
    toast({
      id: `coming-soon-${provider}`,
      title: `${provider} sign-in is coming soon`,
      description: "Use Google, or your email and password, for now.",
      tone: "info"
    });
  }

  return (
    <div className="grid min-h-dvh lg:grid-cols-[43%_57%]">
      {/* ── Brand panel (left) ─────────────────────────────────────────── */}
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-purple-950 p-10 lg:flex">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div
            className="absolute -left-24 -top-24 h-96 w-96 rounded-full opacity-70"
            style={{ background: "radial-gradient(circle, oklch(0.47 0.198 305 / 0.5), transparent 62%)" }}
          />
          <div
            className="absolute -bottom-28 -right-16 h-[26rem] w-[26rem] rounded-full opacity-40"
            style={{ background: "radial-gradient(circle, oklch(0.7 0.145 80 / 0.25), transparent 60%)" }}
          />
        </div>
        <Link href="/" className="relative z-10 flex items-center gap-3">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-md" />
          <span className="font-display text-xl font-bold tracking-tight text-white">Design Prototype Workshop</span>
        </Link>
        <div className="relative z-10">
          <p className="eyebrow !text-gold-300">Living craft documentation</p>
          <h2 className="mt-3 font-display text-3xl font-bold leading-snug tracking-tight text-white">
            Every masterpiece begins with <span className="text-gold-gradient">understanding</span>.
          </h2>
          <ul className="mt-8 space-y-4">
            {BRAND_POINTS.map((point) => (
              <li key={point} className="flex gap-3 text-sm leading-relaxed text-white/75">
                <span className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-gold-400" aria-hidden />
                {point}
              </li>
            ))}
          </ul>
        </div>
        {/*
          THIS SENTENCE USED TO SAY "New Google accounts join as Crowdsource Volunteers and are
          elevated by an admin", AND IT STOPPED BEING TRUE THE DAY THE ALLOW-LIST SHIPPED. A verified
          Google address that nobody has approved no longer gets an account at all — it becomes a
          request an administrator decides. Leaving the old promise here would have been the product
          telling somebody, on the very screen that is about to refuse them, that they were about to
          be let in.
        */}
        {/* THREE SENTENCES CUT TO ONE, 2026-08-30. The claim itself is unchanged and still true —
            approval first, Crowdsource Volunteer on arrival — and the half that was dropped
            ("signing in tells you so and puts you in the queue") is not lost: `SignInRefusal` says
            it, in the server's own words, at the moment it is actually relevant. */}
        <p className="relative z-10 text-xs text-white/40">
          By invitation. An administrator approves your address first; new accounts join as Crowdsource Volunteers.
        </p>
      </aside>

      {/* ── Auth card (right) ──────────────────────────────────────────── */}
      <main className="relative flex items-center justify-center overflow-hidden bg-bg-0 px-6 py-6 md:px-10">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div className="absolute inset-0 grad-mesh opacity-60" />
          <div className="absolute -left-16 top-12 h-72 w-72 rounded-full bg-purple-300/30 blur-3xl" />
          <div className="absolute -right-12 bottom-8 h-80 w-80 rounded-full bg-amber-500/15 blur-3xl" />
        </div>

        {/* The mesh and the two blurred orbs above are what the card's rim refracts; on
            Safari/Firefox `useLiquidGlass` falls back to the frosted blur `.glass-card`
            already describes, so the surface reads the same either way. */}
        <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
          <div className="mb-6 flex flex-col items-center gap-2 text-center">
            <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm lg:hidden" />
            <h1 className="font-display text-2xl font-bold text-ink-900">Welcome back</h1>
            <p className="text-sm text-ink-500">
              {held || passwordGate ? "One thing before you go on" : "Sign in to your account"}
            </p>
          </div>

          {/*
            THE SIGN-IN CONTROLS ARE REPLACED, NOT COVERED, WHILE EITHER GATE IS BEING SHOWN.
            The person is already signed in at this point — leaving a live "Sign In" button under
            the panel would offer them a second sign-in they do not need and cannot usefully make.

            CONSENT FIRST, THEN THE PASSWORD, and the order is not arbitrary. The consent panel is a
            CORRECTION — the person ticked a box saying they agree and their standing refusal was not
            overwritten — so it is about something that has already happened and is owed to them
            before anything else asks them for a keystroke. The password gate is a REQUEST, and a
            request stacked on top of an unread correction is how the correction goes unread. They can
            both be true at once (an admin-created account whose owner withdrew consent in Settings),
            which is why this is a chain and not two independent branches.
          */}
          {held ? (
            <StandingRefusal gate={held} onContinue={() => setHeld(null)} />
          ) : passwordGate ? (
            <FirstPasswordGate
              // The password typed at the door THIS visit, so the ordinary path never asks for it
              // twice. Empty on the Google path and on a session that was already open when this
              // page loaded, and `FirstPasswordGate` draws the box in exactly those two cases.
              currentPassword={password}
              onDone={() => {
                setPasswordSet(true);
                // BEST-EFFORT AND DELIBERATELY NOT AWAITED-INTO-THE-BRANCH, the treatment
                // `settleConsent` gives its own re-read: the password IS set by this point, and
                // folding a failed `/me` into the same outcome would tell somebody their password
                // could not be saved when it had been. The latch above is what makes the gate close
                // either way; the refresh is only a cache invalidation.
                refreshMe().catch(() => undefined);
                router.replace("/dashboard");
              }}
              onSignOut={() => {
                // The escape, and it lands back on this same card with the form live — `logout`
                // clears the session, which drops `user`, which drops `passwordGate`.
                setPasswordSet(false);
                setPassword("");
                logout().catch(() => undefined);
              }}
            />
          ) : (
            <>
          {/*
            `role="alert"` and not a plain box. Sign-in fails without moving focus and without
            changing anything above the fold: the button says "Sign In" again and one red line
            appears above the form. To somebody using a screen reader that is silence — they press
            Enter, hear nothing, and have no way to tell a wrong password from a server that never
            answered. This is the front door of the app, so it is the one place where "the message
            is on screen" is furthest from "the message arrived".
          */}
          {error ? <SignInRefusal kind={refusal} hint={hint} message={error} /> : null}

          <form onSubmit={submit} className="grid gap-3">
            <div className="grid gap-2">
              <label htmlFor="email" className="text-sm font-medium text-ink-900">
                Email, phone or empanelment number
              </label>
              <div className="relative">
                <Mail aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
                {/*
                  ── `type="text"`, AND THAT ONE ATTRIBUTE IS THE WHOLE OF THE CHANGE HERE ────────

                  It was `type="email"`, which is a CLIENT-SIDE REFUSAL: a browser will not submit
                  "DES/2024/0142" from it at all, so the server's three-way resolution would have
                  been unreachable from this screen no matter what the backend did. There is nothing
                  to replace it with — a pattern that admitted an address, a telephone number and an
                  arbitrary institutional numbering scheme admits everything — and nothing is lost by
                  its absence: an identifier that names no account is answered by the same 401 as a
                  wrong password, which is the answer the server deliberately kept unchanged.

                  `autoComplete="username"` and not `"email"`: the field is no longer an address, and
                  a password manager told otherwise offers the wrong stored value to a designer who
                  signs in with their empanelment number.

                  The id stays `email` so the label binding, the `name`, and every test that types
                  into it are untouched — the same reason the wire field kept its name.
                */}
                <input
                  id="email"
                  type="text"
                  autoComplete="username"
                  required
                  placeholder="Email, phone or empanelment number"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  className="field-input h-[52px] pl-10"
                />
              </div>
            </div>

            <div className="grid gap-2">
              <label htmlFor="password" className="text-sm font-medium text-ink-900">
                Password
              </label>
              <div className="relative">
                <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
                <input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  autoComplete="current-password"
                  required
                  minLength={8}
                  placeholder="Enter your password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  className="field-input h-[52px] pl-10 pr-11"
                />
                <button
                  type="button"
                  aria-label={showPassword ? "Hide password" : "Show password"}
                  aria-pressed={showPassword}
                  onClick={() => setShowPassword((value) => !value)}
                  className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1 text-ink-300 hover:text-ink-500"
                >
                  {showPassword ? <EyeOff size={22} aria-hidden /> : <Eye size={22} aria-hidden />}
                </button>
              </div>
            </div>

            {/*
              INSIDE THE FORM AND ABOVE THE BUTTON, WHICH IS THE ONLY PLACE THAT IS CORRECT FOR BOTH
              PATHS AT ONCE.

              In DOM order it precedes every sign-in control on this card — the password submit here,
              and the three provider buttons below the divider — so somebody reading the page with a
              screen reader meets the requirement before either control that it disables, rather than
              tabbing onto a dead button and being told nothing.
            */}
            <ConsentGateField
              agreed={agreed}
              noticeError={noticeError}
              onChange={(next) => {
                // Stamped at the tick, not at the POST. See `agreedAt`'s declaration.
                agreedAt.current = next ? new Date().toISOString() : null;
                setAgreed(next);
              }}
            />

            <Button
              type="submit"
              size="auth"
              disabled={loading || blocked}
              className="mt-1 w-full font-display text-base font-bold"
            >
              {loading ? "Signing in…" : "Sign In"}
            </Button>
          </form>

          <div className="my-4 flex items-center gap-3">
            <span className="h-px flex-1 bg-line-200" />
            <span className="text-sm text-ink-300">OR</span>
            <span className="h-px flex-1 bg-line-200" />
          </div>

          <div className="grid gap-2.5">
            {googleClientId ? (
              <div
                className={cn(
                  PROVIDER_BUTTON,
                  // The GSI button underneath carries the focus, so the ring has to be drawn
                  // by the wrapper — an outline on a transparent element is invisible.
                  "relative w-full min-w-0 overflow-hidden focus-within:outline focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-purple-700",
                  // Matches `.field-button`'s own disabled treatment, because the control it is
                  // standing in for is disabled and must not look live.
                  blocked && "cursor-not-allowed opacity-60"
                )}
                // `aria-disabled` and NOT `disabled`: this is a `div`, and the element that actually
                // takes the click is Google's, inside it. The attribute is what a screen reader
                // reads; `inert` below is what actually removes it from the tab order.
                aria-disabled={blocked || undefined}
              >
                <span aria-hidden className="pointer-events-none flex min-w-0 items-center gap-2.5">
                  <GoogleMark className="h-5 w-5 shrink-0" />
                  <span className="min-w-0 truncate">Continue with Google</span>
                </span>
                {/* GSI renders a 40px-tall button; stretching this layer vertically makes the
                    invisible hit area cover the full 52px of chrome behind it. */}
                {/*
                  THE TWO GUARDS ON A CONTROL THIS BUNDLE DOES NOT OWN.

                  `pointer-events-none` stops the click and `inert` stops the keyboard — Google
                  renders a focusable button (in older builds, an iframe) into this host, and a
                  `disabled` attribute reaches none of it. Neither is the load-bearing guard: the
                  early return inside the GIS callback is, because it is the only one that still
                  holds if Google changes what it renders here. These two exist so the button never
                  LOOKS live while the box is unticked, which is what stops somebody clicking it,
                  completing Google's own account chooser, and only then being refused.
                */}
                <div
                  ref={googleHost}
                  inert={blocked || undefined}
                  className={cn(
                    "absolute inset-0 flex items-center justify-center opacity-0 [transform:scaleY(1.35)]",
                    blocked && "pointer-events-none"
                  )}
                />
              </div>
            ) : (
              <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-500">
                Add NEXT_PUBLIC_GOOGLE_CLIENT_ID and GOOGLE_CLIENT_ID to enable Google sign-in.
              </div>
            )}
            {/* The badge rides in the flex row rather than floating over it — absolutely
                positioned it sat on top of the longer label and clipped it. */}
            {/* `min-w-0` on the grid item is load-bearing: the labels are nowrap, so without it
                the button refuses to shrink below its content and overflows the card on phones. */}
            <Button type="button" variant="provider" size="auth" onClick={() => comingSoon("Microsoft")} className="w-full min-w-0">
              <MicrosoftMark className="h-5 w-5 shrink-0" />
              <span className="min-w-0 truncate">Continue with Microsoft</span>
              <ComingSoonBadge />
            </Button>
            <Button type="button" variant="provider" size="auth" onClick={() => comingSoon("Yahoo")} className="w-full min-w-0">
              <YahooMark className="h-5 w-5 shrink-0" />
              <span className="min-w-0 truncate">Continue with Yahoo</span>
              <ComingSoonBadge />
            </Button>
          </div>

          <p className="mt-4 text-center text-sm text-ink-500">
            No account yet?{" "}
            <Link href="/" className="font-medium text-purple-700 hover:underline">
              See what Design Prototype Workshop does
            </Link>
          </p>
            </>
          )}
        </GlassSurface>
      </main>
    </div>
  );
}

/**
 * WHY A SIGN-IN WAS REFUSED, DRAWN SO THAT THE TWO CASES CANNOT BE MISTAKEN FOR EACH OTHER.
 *
 * ── THE RULING THIS IMPLEMENTS ──────────────────────────────────────────────────────────────────
 *
 * "Wrong password and pending approval should be differentiated." A person waiting on an
 * administrator, told "invalid email or password", will reset a password that was never wrong —
 * twice — and then telephone somebody who cannot help them, because this product has no
 * registration page and no password-reset email, so the vague answer leaves them with no next
 * action that exists. The account-enumeration cost of saying so was weighed and accepted; what is
 * NOT accepted is saying anything more than "this address is awaiting approval". Nothing on this
 * card names the person, their tier, whether a password was ever set, or anything about any other
 * account — and nothing added later may either. The server's sentence is the whole disclosure.
 *
 * ── THE THREE SHAPES ────────────────────────────────────────────────────────────────────────────
 *
 * A WRONG CREDENTIAL stays exactly what it was: one red line, the size of a validation error,
 * because that is what it is. Dressing it in a panel would make every typo look like an account
 * problem, which is this feature's own mistake made backwards.
 *
 * A CLASSIFIED REFUSAL — awaiting approval, refused, suspended, queue full — gets a filled panel
 * with a heading, the server's own sentence verbatim, and one line saying what to do. The heading
 * exists because a 13px line above a "Sign In" button is read as a validation error and dismissed;
 * this is the only place the person will ever be told what is actually happening to them.
 *
 * AN UNCLASSIFIED FAILURE — no response at all, or a deployment/proxy that did not send the
 * classifying header — gets the neutral line and the server's words. Never a guessed heading: being
 * told "your access has been suspended" because a proxy dropped a header would be worse than the
 * plain sentence.
 *
 * ── THE COLOUR IS NOT THE MESSAGE ───────────────────────────────────────────────────────────────
 *
 * "Waiting" is amber and "refused" is red, and both say which they are in words, because a person
 * who cannot distinguish the two colours must still be able to tell "an administrator has not got
 * to you yet" from "an administrator said no". `role="alert"` for the same reason it was already
 * here: sign-in fails without moving focus and without changing anything above the fold, so to
 * somebody using a screen reader the difference between a wrong password and a server that never
 * answered is otherwise silence.
 */
function SignInRefusal({
  kind,
  hint,
  message
}: {
  kind: AccessRefusalKind | null;
  hint: SignInHint;
  message: string;
}) {
  const chrome = kind ? accessRefusalChrome(kind) : null;
  // THE HINT WINS OVER THE ADMISSION CHROME WHERE BOTH APPLY, and today both never do: every
  // hint arrives on a 401, which `accessRefusalKind` classifies as BAD_CREDENTIAL, for which
  // `accessRefusalChrome` returns null. The order is written down anyway so that adding a hint
  // to a 403 later cannot silently produce two headings in one box.
  const hintHeading = signInHintHeading(hint);
  if (hintHeading) {
    return (
      <div role="alert" className="mb-4 grid gap-1.5 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-3 text-sm text-amber-900">
        <p className="font-display text-base font-bold leading-snug">{hintHeading}</p>
        {/* The server's sentence, verbatim — it is the only text that knows what to do next. */}
        <p className="leading-6">{message}</p>
      </div>
    );
  }

  if (!chrome) {
    return (
      <div role="alert" className="mb-4 rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
        {message}
      </div>
    );
  }

  const waiting = chrome.tone === "waiting";
  return (
    <div
      role="alert"
      className={cn(
        "mb-4 grid gap-1.5 rounded-md border px-3 py-3 text-sm",
        waiting ? "border-amber-500/40 bg-amber-100 text-amber-900" : "border-red-200 bg-error-100 text-error-600"
      )}
    >
      <p className="font-display text-base font-bold leading-snug">{chrome.heading}</p>
      {/* The server's sentence, verbatim. It is the only text that knows why THIS attempt was
          refused, and nothing this bundle could write in its place would know it. */}
      <p className="leading-6">{message}</p>
      <p className={cn("text-xs leading-5", waiting ? "text-amber-800" : "text-error-600/90")}>{chrome.advice}</p>
    </div>
  );
}
