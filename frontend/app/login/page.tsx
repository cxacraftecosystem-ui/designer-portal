"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, Lock, Mail } from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { useAuth } from "@/components/AuthProvider";
import { FirstPasswordGate } from "@/components/FirstPasswordGate";
import { GoogleSignInButton } from "@/components/GoogleSignInButton";
import { TwoStepChallengeForm } from "@/components/TwoStepChallengeForm";
import { Button } from "@/components/ui/button";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { useToast } from "@/components/ui/Toast";
import { ApiError } from "@/lib/api";
import {
  beginSignIn,
  callbackErrorMessage,
  configuredOidcProviders,
  loginBody,
  parseCallbackFragment,
  takePendingSignIn,
  type CallbackResult,
  type OidcProvider,
  type OidcProviderId
} from "@/lib/oidcSignIn";
import {
  ACCESS_STATUS_HEADER,
  accessRefusalChrome,
  accessRefusalKind,
  type AccessRefusalKind
} from "@/lib/accessRoster";
import {
  SIGN_IN_HINT_HEADER,
  mustChangePassword,
  signInHintHeading,
  signInHintOf,
  type SignInHint
} from "@/lib/signIn";
import {
  loadUsageConsentNotice,
  recordUsageConsent,
  usageConsentGateOf,
  type UsageConsentGate,
  type UsageConsentNotice
} from "@/lib/usage";
import { isTwoFactorChallenge } from "@/lib/twoFactor";
import type { User } from "@/lib/types";
import { cn } from "@/lib/utils";


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

function ProviderMark({ id, className }: { id: OidcProviderId; className?: string }) {
  return id === "MICROSOFT" ? <MicrosoftMark className={className} /> : <YahooMark className={className} />;
}

const BRAND_POINTS = [
  "Artisans, crafts, workshops, products, tools and interviews — one connected archive.",
  "Recordings transcribed and translated to English automatically.",
  "Eleven-tier access control; every edit audited."
];

export default function LoginPage() {
  return <LoginView />;
}

const AGREE_BOX_ID = "usage-consent-agree";
const AGREE_HINT_ID = "usage-consent-agree-hint";

const noSubscription = () => () => {};

function ConsentGateField({
  agreed,
  noticeError,
  onChange
}: {
  agreed: boolean;
  noticeError: string | null;
  onChange: (agreed: boolean) => void;
}) {
  const boxId = AGREE_BOX_ID;
  const hintId = AGREE_HINT_ID;
  const live = useSyncExternalStore(noSubscription, () => true, () => false);

  return (
    <div className="grid gap-1">
      <div className="flex items-center gap-2.5">
        <input
          id={boxId}
          type="checkbox"
          required
          checked={agreed}
          disabled={!live}
          onChange={(event) => onChange(event.target.checked)}
          aria-describedby={hintId}
          className="h-4 w-4 shrink-0 rounded border-line-200 [accent-color:oklch(0.47_0.198_305)]"
        />
        <span className="min-w-0 text-sm leading-6 text-ink-900">
          <label htmlFor={boxId}>I agree to the</label>{" "}
          <Link
            href="/terms"
            target="_blank"
            rel="noopener noreferrer"
            className="font-medium text-purple-700 dark:text-purple-300 underline underline-offset-2 hover:text-purple-800 dark:hover:text-purple-200"
          >
            terms and conditions
          </Link>
        </span>
      </div>

      <p id={hintId} role="status" aria-live="polite" className="text-xs leading-5 text-ink-500">
        {!agreed
          ? "Required to sign in."
          : noticeError
            ? "Your answer can't be saved right now. You'll be asked again at your next sign-in."
            : ""}
      </p>
    </div>
  );
}

function StandingRefusal({ gate, onContinue }: { gate: UsageConsentGate; onContinue: () => void }) {
  return (
    <div role="status" className="grid gap-2 rounded-md border border-line-200 bg-surface-50 p-4 text-sm leading-6">
      <p className="font-display text-base font-bold text-ink-900">Your earlier answer stands</p>
      <p className="text-ink-700">{gate.reason}</p>
      <p className="text-ink-700">Ticking the box didn&apos;t change it. You can change it in Settings.</p>
      <Button type="button" size="auth" onClick={onContinue} className="mt-1 w-full font-display text-base font-bold">
        Continue
      </Button>
    </div>
  );
}

function LoginView() {
  const router = useRouter();
  const { login, loginWithGoogle, loginWithOidc, logout, markPasswordChanged, refreshMe, user } = useAuth();
  const [challenge, setChallenge] = useState<string | null>(null);
  const { toast } = useToast();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [doorPassword, setDoorPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refusal, setRefusal] = useState<AccessRefusalKind | null>(null);
  const [hint, setHint] = useState<SignInHint>(null);
  const [loading, setLoading] = useState(false);
  const googleClientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID;
  const [oidcProviders] = useState<OidcProvider[]>(() => configuredOidcProviders());
  const oidcCallback = useRef<CallbackResult | null>(null);
  const [oidcReturned, setOidcReturned] = useState(false);

  const [notice, setNotice] = useState<UsageConsentNotice | null>(null);
  const [noticeError, setNoticeError] = useState<string | null>(null);
  const [agreed, setAgreed] = useState(false);
  const agreedAt = useRef<string | null>(null);
  const [held, setHeld] = useState<UsageConsentGate | null>(null);
  const signingIn = useRef(false);
  const passwordGate = mustChangePassword(user) ? user : null;

  useEffect(() => {
    if (user && !signingIn.current && !held && !passwordGate && !challenge) router.replace("/dashboard");
  }, [challenge, held, passwordGate, router, user]);

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

  const blocked = !agreed;

  const settleConsent = useCallback(
    async (account: User): Promise<UsageConsentGate | null> => {
      const gate = usageConsentGateOf(account);
      if (!gate || !notice) return null;
      if (!gate.required) return gate.state === "REFUSED" ? gate : null;
      try {
        await recordUsageConsent({
          decision: "GRANTED",
          basis: "REQUIRED_AT_SIGN_IN",
          noticeVersion: notice.version,
          recordedAt: agreedAt.current ?? undefined
        });
      } catch {
        toast({
          title: "Your answer could not be saved",
          description:
            "You are signed in. The recording question will be asked again at your next sign-in, and Settings can answer it at any time.",
          tone: "error"
        });
        return null;
      }
      await refreshMe().catch(() => undefined);
      return null;
    },
    [notice, refreshMe, toast]
  );

  const gateNow = useRef({ blocked, settleConsent });
  useEffect(() => {
    gateNow.current = { blocked, settleConsent };
  }, [blocked, settleConsent]);

  const describeFailure = useCallback((err: unknown) => {
    const status = err instanceof ApiError ? err.status : 0;
    const kind = accessRefusalKind(status, err instanceof ApiError ? err.headers?.get(ACCESS_STATUS_HEADER) : null);
    setRefusal(kind);
    setHint(signInHintOf(err instanceof ApiError ? err.headers?.get(SIGN_IN_HINT_HEADER) : null));
    setError(err instanceof ApiError ? err.message : "Couldn't sign in. Check your connection and try again.");
  }, []);

  const onGoogleCredential = useCallback(
    async (credential: string) => {
      setDoorPassword("");
      if (gateNow.current.blocked) {
        setRefusal(null);
        setError("Please agree to the terms and conditions above the sign-in buttons.");
        document.getElementById(AGREE_BOX_ID)?.focus();
        return;
      }
      setError(null);
      setRefusal(null);
      signingIn.current = true;
      try {
        const outcome = await loginWithGoogle(credential);
        if (isTwoFactorChallenge(outcome)) {
          signingIn.current = false;
          setChallenge(outcome.challenge_token);
          return;
        }
        const account = outcome;
        const standing = await gateNow.current.settleConsent(account);
        signingIn.current = false;
        if (standing) {
          setHeld(standing);
          return;
        }
        if (mustChangePassword(account)) return;
        router.replace("/dashboard");
      } catch (err) {
        signingIn.current = false;
        describeFailure(err);
      }
    },
    [describeFailure, loginWithGoogle, router]
  );

  const onGoogleLoadError = useCallback(
    () =>
      toast({
        title: "Could not load Google sign-in",
        description: "Check your connection and reload the page.",
        tone: "error",
        duration: 0
      }),
    [toast]
  );

  async function finishSecondStep(account: User) {
    signingIn.current = true;
    try {
      const standing = await settleConsent(account);
      if (standing) {
        setHeld(standing);
        return;
      }
      if (mustChangePassword(account)) return;
      router.replace("/dashboard");
    } finally {
      signingIn.current = false;
      setChallenge(null);
    }
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (blocked) return;
    setLoading(true);
    setError(null);
    setRefusal(null);
    setHint(null);
    signingIn.current = true;
    setDoorPassword(password);
    try {
      const outcome = await login(email, password);
      if (isTwoFactorChallenge(outcome)) {
        setChallenge(outcome.challenge_token);
        return;
      }
      const account = outcome;
      const standing = await settleConsent(account);
      if (standing) {
        setHeld(standing);
        return;
      }
      if (mustChangePassword(account)) return;
      router.replace("/dashboard");
    } catch (err) {
      setDoorPassword("");
      describeFailure(err);
    } finally {
      signingIn.current = false;
      setLoading(false);
    }
  }

  async function startOidc(provider: OidcProvider) {
    if (blocked) {
      setRefusal(null);
      setHint(null);
      setError("Please agree to the terms and conditions above the sign-in buttons.");
      document.getElementById(AGREE_BOX_ID)?.focus();
      return;
    }
    setError(null);
    setRefusal(null);
    setHint(null);
    setLoading(true);
    try {
      window.location.assign(await beginSignIn(provider, window.location.origin, agreedAt.current));
    } catch {
      setLoading(false);
      setError(callbackErrorMessage(provider.id, null));
    }
  }

  useEffect(() => {
    const callback = parseCallbackFragment(window.location.hash);
    if (!callback) return;
    window.history.replaceState(null, "", window.location.pathname + window.location.search);
    oidcCallback.current = callback;
    setOidcReturned(true);
  }, []);

  useEffect(() => {
    const callback = oidcCallback.current;
    if (!oidcReturned || !callback) return;
    if (notice === null && noticeError === null) return;
    oidcCallback.current = null;
    setDoorPassword("");
    const pending = takePendingSignIn(callback.state);
    if (!pending) {
      setRefusal(null);
      setError("That sign-in could not be completed in this tab. Start it again from here.");
      return;
    }
    if (callback.error || !callback.code) {
      setRefusal(null);
      setError(callbackErrorMessage(pending.provider, callback.error));
      return;
    }
    const code = callback.code;
    agreedAt.current = pending.agreedAt;
    setAgreed(true);
    setError(null);
    setRefusal(null);
    setHint(null);
    setLoading(true);
    signingIn.current = true;
    void (async () => {
      try {
        const outcome = await loginWithOidc(loginBody(pending, code));
        if (isTwoFactorChallenge(outcome)) {
          signingIn.current = false;
          setChallenge(outcome.challenge_token);
          return;
        }
        const account = outcome;
        const standing = await settleConsent(account);
        signingIn.current = false;
        if (standing) {
          setHeld(standing);
          return;
        }
        if (mustChangePassword(account)) return;
        router.replace("/dashboard");
      } catch (err) {
        signingIn.current = false;
        describeFailure(err);
      } finally {
        setLoading(false);
      }
    })();
  }, [describeFailure, loginWithOidc, notice, noticeError, oidcReturned, router, settleConsent]);

  return (
    <div className="grid min-h-dvh lg:grid-cols-[43%_57%]">
      <aside className="relative hidden flex-col justify-between overflow-hidden bg-purple-950 p-10 lg:flex">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div
            className="absolute -left-24 -top-24 h-96 w-96 rounded-full opacity-70"
            style={{ background: "radial-gradient(circle, oklch(0.47 0.198 305 / 0.5), transparent 62%)" }}
          />
          <div
            className="absolute -bottom-28 -right-16 h-104 w-104 rounded-full opacity-40"
            style={{ background: "radial-gradient(circle, oklch(0.7 0.145 80 / 0.25), transparent 60%)" }}
          />
        </div>
        <Link href="/" className="relative z-10 flex items-center gap-3">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-md" />
          <span className="font-display text-xl font-bold tracking-tight text-white">Design Prototype Workshop</span>
        </Link>
        <div className="relative z-10">
          <p className="eyebrow text-gold-300!">Living craft documentation</p>
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
        <p className="relative z-10 text-xs text-white/40">
          By invitation. An administrator approves your address and chooses your role, or creates your account for
          you.
        </p>
      </aside>

      <main className="relative flex items-center justify-center overflow-hidden bg-bg-0 px-6 py-6 md:px-10">
        <div aria-hidden className="pointer-events-none absolute inset-0">
          <div className="absolute inset-0 grad-mesh opacity-60" />
          <div className="absolute -left-16 top-12 h-72 w-72 rounded-full bg-purple-300/30 blur-3xl" />
          <div className="absolute -right-12 bottom-8 h-80 w-80 rounded-full bg-amber-500/15 blur-3xl" />
        </div>

        <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
          <div className="mb-6 flex flex-col items-center gap-2 text-center">
            <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm lg:hidden" />
            <h1 className="font-display text-2xl font-bold text-ink-900">Welcome back</h1>
            <p className="text-sm text-ink-500">
              {held || passwordGate || challenge ? "One thing before you go on" : "Sign in to your account"}
            </p>
          </div>

          {held ? (
            <StandingRefusal gate={held} onContinue={() => setHeld(null)} />
          ) : passwordGate ? (
            <FirstPasswordGate
              currentPassword={doorPassword}
              onDone={() => {
                setDoorPassword("");
                markPasswordChanged();
                router.replace("/dashboard");
              }}
              onSignOut={() => {
                setDoorPassword("");
                setPassword("");
                logout().catch(() => undefined);
              }}
            />
          ) : challenge ? (
            <TwoStepChallengeForm
              challengeToken={challenge}
              onSignedIn={finishSecondStep}
              onCancel={() => {
                setChallenge(null);
                setDoorPassword("");
              }}
            />
          ) : (
            <>
          {error ? <SignInRefusal kind={refusal} hint={hint} message={error} /> : null}

          <form onSubmit={submit} className="grid gap-3">
            <div className="grid gap-2">
              <label htmlFor="email" className="text-sm font-medium text-ink-900">
                Email, phone or empanelment number
              </label>
              <div className="relative">
                <Mail aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
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
              <Link href="/forgot-password" className="justify-self-end text-sm font-medium text-purple-700 dark:text-purple-300 hover:underline">
                Forgot your password?
              </Link>
            </div>

            <ConsentGateField
              agreed={agreed}
              noticeError={noticeError}
              onChange={(next) => {
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

          {googleClientId || oidcProviders.length > 0 ? (
            <>
              <div className="my-4 flex items-center gap-3">
                <span className="h-px flex-1 bg-line-200" />
                <span className="text-sm text-ink-300">OR</span>
                <span className="h-px flex-1 bg-line-200" />
              </div>

              <div className="grid gap-2.5">
              {googleClientId ? (
                <GoogleSignInButton
                  clientId={googleClientId}
                  disabled={blocked}
                  onCredential={(credential) => void onGoogleCredential(credential)}
                  onLoadError={onGoogleLoadError}
                />
              ) : null}
              {oidcProviders.map((provider) => (
                <Button
                  key={provider.id}
                  type="button"
                  variant="provider"
                  size="auth"
                  disabled={loading}
                  aria-describedby={blocked ? AGREE_HINT_ID : undefined}
                  onClick={() => void startOidc(provider)}
                  className="w-full min-w-0"
                >
                  <ProviderMark id={provider.id} className="h-5 w-5 shrink-0" />
                  <span className="min-w-0 truncate">Continue with {provider.label}</span>
                </Button>
              ))}
              </div>
            </>
          ) : null}

          <p className="mt-4 text-center text-sm text-ink-500">
            No account yet?{" "}
            <Link href="/" className="font-medium text-purple-700 dark:text-purple-300 hover:underline">
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
  const hintHeading = signInHintHeading(hint);
  if (hintHeading) {
    return (
      <div role="alert" className="mb-4 grid gap-1.5 rounded-md border border-amber-500/40 bg-amber-100 px-3 py-3 text-sm text-amber-900">
        <p className="font-display text-base font-bold leading-snug">{hintHeading}</p>
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
      <p className="leading-6">{message}</p>
      <p className={cn("text-xs leading-5", waiting ? "text-amber-800" : "text-error-600/90")}>{chrome.advice}</p>
    </div>
  );
}
