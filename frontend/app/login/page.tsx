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
// ACROSS FROM components/settings ON PURPOSE. The recording notice has exactly two consumers — this
// card, which asks, and the settings card, which lets a person take it back — and they must show the
// same words in the same order or somebody agrees to one description of this system and reads a
// different one when they come to withdraw. One component, imported by both; it lives beside its
// settings consumer because that is where the rest of the consent surface is.
import { UsageConsentDisclosure } from "@/components/settings/UsageConsentNotice";
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
 * THE TURNSTILE: a real checkbox, a real label, the notice expanding under it, and the reason the
 * button below is dead — in words, in a region that speaks.
 *
 * ── EVERY ACCESSIBILITY DECISION HERE, AND THE FAILURE EACH ONE PREVENTS ────────────────────────
 *
 * **A REAL `<input type="checkbox">` WITH A REAL `<label htmlFor>`.** Not a styled `<div
 * role="checkbox">` and not a `Toggle`: this is a legal agreement, it must appear in a form's own
 * validity state, it must respond to Space, and its checked state must be reported by the platform
 * rather than by an attribute somebody remembered to write. `required` is on it too, so a browser
 * that submits past the disabled button still refuses.
 *
 * **THE BLOCKED REASON IS `aria-describedby` ON THE CHECKBOX, NOT ON THE BUTTON.** A `disabled`
 * button is not focusable, so nothing on it is ever announced — a description hung there is a
 * description no screen-reader user will ever hear. The checkbox is the control they will actually
 * land on, and it is the control that clears the block, so the sentence belongs to it.
 *
 * **THE REGION IS PRESENT FROM FIRST PAINT AND ONLY ITS TEXT CHANGES.** Assistive technology
 * announces mutations inside a live region that already existed; a region that appears at the same
 * moment as its message is frequently announced as nothing at all. This is the same rule
 * `components/ui/Toast.tsx` follows for its own viewport, and for the same reason.
 *
 * **THE STATE IS SAID, NOT ONLY COLOURED.** "Required" is a word before it is an amber tint, and the
 * cleared state says "You can now sign in" rather than merely turning green — there is no colour
 * anywhere on this control that carries a fact the text does not.
 */
function ConsentGateField({
  notice,
  noticeError,
  agreed,
  onChange
}: {
  notice: UsageConsentNotice | null;
  noticeError: string | null;
  agreed: boolean;
  onChange: (agreed: boolean) => void;
}) {
  const boxId = AGREE_BOX_ID;
  const hintId = AGREE_HINT_ID;

  /*
    THE NOTICE COULD NOT BE LOADED, SO NOBODY IS ASKED TO AGREE TO IT.

    No checkbox at all, and sign-in is NOT blocked — the alternatives are both worse than saying so.
    Barring the door would put the front of the app behind one endpoint; showing a checkbox anyway
    would collect an agreement to text this screen could not display, which is not a smaller kind of
    consent but a different thing entirely. The server's gate is untouched by any of this, so the
    question is put again at the next sign-in and Settings can answer it at any time.
  */
  if (noticeError) {
    return (
      <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2.5 text-sm leading-6 text-ink-700">
        <p className="font-medium text-ink-900">The recording notice could not be loaded.</p>
        <p className="mt-1">
          You are not being asked to agree to text this screen cannot show you, so sign-in is not blocked. You will be
          asked again next time, and Settings carries the notice and the answer at any point.
        </p>
        <p className="mt-1 text-xs text-ink-500">{noticeError}</p>
      </div>
    );
  }

  const loading = notice === null;

  return (
    <div className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-3">
      <div className="flex items-start gap-2.5">
        <input
          id={boxId}
          type="checkbox"
          required
          disabled={loading}
          checked={agreed}
          onChange={(event) => onChange(event.target.checked)}
          aria-describedby={hintId}
          // `accent-color` is the one honest way to tint a native checkbox without replacing it, and
          // replacing it is what costs the platform's own checked semantics. The literal is the
          // action colour from `tailwind.config.ts` — purple-700 — written out because
          // `accent-purple-700` compiles to a Tailwind utility that carries `<alpha-value>` and the
          // property takes no alpha.
          className="mt-0.5 h-4 w-4 shrink-0 rounded border-line-200 [accent-color:oklch(0.47_0.198_305)]"
        />
        <label htmlFor={boxId} className="min-w-0 text-sm leading-6 text-ink-900">
          <span className="font-medium">
            I agree to have my use of this platform recorded, whichever way I sign in.
          </span>{" "}
          <span className="text-ink-700">
            Which screens I open, how long the server took, and whether it worked — never what I type, never a search
            box, never a record id. It is required to use the platform, and I can withdraw it in Settings at any time
            without losing access.
          </span>
        </label>
      </div>

      {/* Expandable IN PLACE and not a link away: on this screen a link would navigate off a
          half-typed password, and a notice behind one is a notice nobody reads. */}
      {notice ? <UsageConsentDisclosure notice={notice} tone="inset" /> : null}

      <p
        id={hintId}
        role="status"
        aria-live="polite"
        className={cn("text-xs leading-5", agreed ? "text-ink-500" : "text-amber-800")}
      >
        {loading
          ? "Loading the recording notice. The sign-in buttons stay disabled until it has arrived, so nobody signs in past a question that had not finished loading."
          : agreed
            ? `You can now sign in. Your answer is recorded against notice version ${notice?.version ?? ""}, with the fact that it was required at sign-in, so a later reader can tell it apart from a free choice.`
            : "Required. Both the Sign In button and Continue with Google stay disabled until this box is ticked — agreeing is a condition of using this platform."}
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
      <p className="font-display text-base font-bold text-ink-900">You are signed in — and your earlier answer stands</p>
      <p className="text-ink-700">{gate.reason}</p>
      <p className="text-ink-700">
        Nothing was recorded from the box you ticked just now. Withdrawing recording does not cost you anything here,
        so it has been left exactly as you set it; Settings is where to change it if you want to.
      </p>
      <Button type="button" size="auth" onClick={onContinue} className="mt-1 w-full font-display text-base font-bold">
        Continue
      </Button>
    </div>
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
 * 3. **THE NOTICE FAILING TO LOAD MUST NOT BAR THE DOOR.** If `GET /usage/consent/notice` cannot be
 *    reached, there is no checkbox at all and sign-in proceeds — with a sentence saying why. The
 *    alternative is an app whose front door depends on a new endpoint, and a tick that means "I
 *    agree to text that did not load" is not consent in either direction. The gate stays true on
 *    the server, so the person is asked at their next sign-in and in Settings.
 * 4. **THE REASON A CONTROL IS BLOCKED IS SPOKEN, NOT ONLY GREYED.** A `disabled` button is not
 *    focusable, so an `aria-describedby` on it is never announced; the requirement rides on the
 *    CHECKBOX instead, and a live region that is present from first paint says what is blocking and
 *    then says it has cleared.
 */
function LoginView() {
  const router = useRouter();
  const { login, loginWithGoogle, refreshMe, user } = useAuth();
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

  useEffect(() => {
    if (user && !signingIn.current && !held) router.replace("/dashboard");
  }, [held, router, user]);

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
   * THE GATE ITSELF: blocked unless the box is ticked, or unless the notice could not be loaded.
   *
   * **IT IS ALSO BLOCKED WHILE THE NOTICE IS STILL IN FLIGHT**, which is not fussiness. `notice` and
   * `noticeError` are both null for the first few hundred milliseconds, and a rule written as
   * `notice !== null && !agreed` would leave the button live for exactly that window — a fast
   * typist with a saved password signs in past a gate that had not finished arriving, once, on a
   * slow connection, and nothing on screen ever says so. Blocking on the unknown is the only
   * direction that cannot silently let somebody through.
   *
   * A FAILED FETCH DOES NOT BAR THE DOOR — see reason 3 in the header comment. That is why the
   * condition is `noticeError === null` and not `notice !== null`: a rejection clears the gate, and
   * `ConsentGateField` states in words that the question is not being asked and why.
   */
  const blocked = !agreed && noticeError === null;

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
            setError(
              "Please tick the box agreeing to how your use of the platform is recorded — it is above the sign-in buttons, and the full notice expands there. Agreeing is required to use this platform, and it can be withdrawn later in Settings."
            );
            // SAYING IT IS NOT ENOUGH: the person clicked a button and, from where they are looking,
            // nothing happened. Focus moves to the control that clears the block, so a keyboard or
            // screen-reader user is put on it rather than being told to go and find it. `focus` is
            // safe on a missing element only because it is guarded — this id is rendered on every
            // branch of `ConsentGateField` except the notice-failed one, and on that branch
            // `blocked` is false and this line is unreachable.
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
    signingIn.current = true;
    try {
      const account = await login(email, password);
      const standing = await settleConsent(account);
      if (standing) {
        setHeld(standing);
        return;
      }
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
        <p className="relative z-10 text-xs text-white/40">
          Signing in is by invitation: an administrator approves your address first, and new accounts then join as
          Crowdsource Volunteers until they are promoted. If yours has not been approved yet, signing in tells you so and
          puts you in the queue.
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
            <p className="text-sm text-ink-500">{held ? "One thing before you go on" : "Sign in to your account"}</p>
          </div>

          {/*
            THE SIGN-IN CONTROLS ARE REPLACED, NOT COVERED, WHILE A STANDING REFUSAL IS BEING SHOWN.
            The person is already signed in at this point — leaving a live "Sign In" button under
            the panel would offer them a second sign-in they do not need and cannot usefully make.
          */}
          {held ? (
            <StandingRefusal gate={held} onContinue={() => setHeld(null)} />
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
          {error ? <SignInRefusal kind={refusal} message={error} /> : null}

          <form onSubmit={submit} className="grid gap-3">
            <div className="grid gap-2">
              <label htmlFor="email" className="text-sm font-medium text-ink-900">
                Email address
              </label>
              <div className="relative">
                <Mail aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
                <input
                  id="email"
                  type="email"
                  autoComplete="email"
                  required
                  placeholder="Enter your email"
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
              tabbing onto a dead button and being told nothing. Its own copy says "whichever way I
              sign in", because a checkbox drawn inside the password form otherwise reads as a
              condition of the password form.
            */}
            <ConsentGateField
              notice={notice}
              noticeError={noticeError}
              agreed={agreed}
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
function SignInRefusal({ kind, message }: { kind: AccessRefusalKind | null; message: string }) {
  const chrome = kind ? accessRefusalChrome(kind) : null;

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
