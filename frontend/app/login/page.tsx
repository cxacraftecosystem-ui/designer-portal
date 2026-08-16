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
  "Six-tier access control; every edit audited."
];

/** Shared chrome for the four sign-in actions, so they are one height and one radius. */
const PROVIDER_BUTTON = buttonVariants({ variant: "provider", size: "auth" });

export default function LoginPage() {
  // ToastProvider lives in app/layout.tsx, so this page needs no provider of its own.
  return <LoginView />;
}

function LoginView() {
  const router = useRouter();
  const { login, loginWithGoogle, user } = useAuth();
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

  useEffect(() => {
    if (user) router.replace("/dashboard");
  }, [router, user]);

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
          setError(null);
          setRefusal(null);
          try {
            await loginWithGoogle(response.credential);
            router.replace("/dashboard");
          } catch (err) {
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
    setLoading(true);
    setError(null);
    setRefusal(null);
    try {
      await login(email, password);
      router.replace("/dashboard");
    } catch (err) {
      describeFailure(err);
    } finally {
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
            <p className="text-sm text-ink-500">Sign in to your account</p>
          </div>

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

            <Button type="submit" size="auth" disabled={loading} className="mt-1 w-full font-display text-base font-bold">
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
                  "relative w-full min-w-0 overflow-hidden focus-within:outline focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-purple-700"
                )}
              >
                <span aria-hidden className="pointer-events-none flex min-w-0 items-center gap-2.5">
                  <GoogleMark className="h-5 w-5 shrink-0" />
                  <span className="min-w-0 truncate">Continue with Google</span>
                </span>
                {/* GSI renders a 40px-tall button; stretching this layer vertically makes the
                    invisible hit area cover the full 52px of chrome behind it. */}
                <div
                  ref={googleHost}
                  className="absolute inset-0 flex items-center justify-center opacity-0 [transform:scaleY(1.35)]"
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
