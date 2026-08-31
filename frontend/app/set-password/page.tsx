"use client";

/**
 * THE SCREEN AT THE END OF AN ADMINISTRATOR'S PASSWORD LINK.
 *
 * ── WHY IT IS PUBLIC, AND WHAT THAT COSTS ─────────────────────────────────────────────────────
 *
 * It sits OUTSIDE `app/(protected)/` because the whole point of holding a link is that you cannot
 * sign in — a route guard here would send every reader to the one page they are locked out of. So
 * there is no `AppShell`, no `PageHeader` and no back arrow: this page renders its own frame, and
 * it is deliberately the login card's frame so that somebody who arrives here from a message
 * recognises where they are.
 *
 * The token in the query string IS the entire authority, and it is checked server-side four ways
 * (signature, shape, expiry, and the credential fingerprint that makes it single-use). Nothing here
 * decides anything; this screen's only jobs are to ask before drawing a password box, and to say
 * WHICH refusal it got — expired, withdrawn, already used — because each one has a different next
 * action and "invalid link" leaves a person with none of them.
 *
 * ── WHAT THE SERVER DELIBERATELY DOES NOT TELL US ─────────────────────────────────────────────
 *
 * Whose account this is. No email, no name, no role. This route is reachable by anybody with a
 * guess, and a body that named the account would turn a forged-token probe into an account lookup.
 * The person knows whose password they are setting; the screen does not need to.
 *
 * ── `useSearchParams` NEEDS A SUSPENSE BOUNDARY (Next 16) ─────────────────────────────────────
 *
 * Hence the two components. The fallback is the same card with the same height so the page does not
 * jump when the parameter resolves.
 */

import Link from "next/link";
import { Suspense, useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { Eye, EyeOff, Lock } from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { Button } from "@/components/ui/button";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { ApiError } from "@/lib/api";
import {
  MIN_PASSWORD_LENGTH,
  checkPasswordLink,
  passwordRuleLine,
  setPasswordWithLink
} from "@/lib/signIn";

/*
  THE LENGTH FLOOR AND THE SENTENCE UNDER THE BOXES NOW COME FROM `lib/signIn.ts`.

  They were declared here while this was the only screen in the client that asked anybody for a
  password. The first-login gate on `/login` asks for one too, and two screens each holding their
  own copy of one server rule is how the pair comes to state different rules on the day somebody
  changes the server's. The constant moved; nothing about what this screen enforces did.
*/

function AuthCard({ children }: { children: React.ReactNode }) {
  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-bg-0 px-6 py-6 md:px-10">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 grad-mesh opacity-60" />
        <div className="absolute -left-16 top-12 h-72 w-72 rounded-full bg-purple-300/30 blur-3xl" />
        <div className="absolute -right-12 bottom-8 h-80 w-80 rounded-full bg-amber-500/15 blur-3xl" />
      </div>
      <GlassSurface
        options={GLASS_PANEL}
        className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8"
      >
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm" />
          <h1 className="font-display text-2xl font-bold text-ink-900">Set your password</h1>
        </div>
        {children}
      </GlassSurface>
    </main>
  );
}

function SetPasswordForm() {
  const router = useRouter();
  const params = useSearchParams();
  const token = params.get("token") ?? "";

  /** `null` while the check is in flight — "Checking…" and an empty page are different answers. */
  const [linkValid, setLinkValid] = useState<boolean | null>(null);
  const [linkReason, setLinkReason] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    let cancelled = false;
    if (!token) {
      setLinkValid(false);
      setLinkReason("missing");
      return;
    }
    checkPasswordLink(token)
      .then((result) => {
        if (cancelled) return;
        setLinkValid(result.valid);
        setLinkReason(result.reason);
      })
      .catch(() => {
        if (cancelled) return;
        // A FAILED CHECK IS NOT A DEAD LINK. The network may simply be down, and telling somebody
        // their link is invalid when it has not been examined sends them back to an administrator
        // for nothing. The form is drawn; the POST is the authority either way.
        setLinkValid(true);
        setLinkReason(null);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  const submit = useCallback(
    async (event: React.FormEvent<HTMLFormElement>) => {
      event.preventDefault();
      if (password !== confirm) {
        setError("The two passwords do not match.");
        return;
      }
      setSaving(true);
      setError(null);
      try {
        await setPasswordWithLink(token, password);
        setDone(true);
      } catch (err) {
        setError(
          err instanceof ApiError || err instanceof Error
            ? err.message
            : "Could not set the password."
        );
      } finally {
        setSaving(false);
      }
    },
    [confirm, password, token]
  );

  if (done) {
    return (
      <AuthCard>
        <div className="grid gap-4">
          <p className="text-sm leading-6 text-ink-700">
            Your password is set. Any other device that was signed in to this account has been signed
            out.
          </p>
          <Button className="w-full font-display font-bold" onClick={() => router.replace("/login")} size="auth">
            Go to sign in
          </Button>
        </div>
      </AuthCard>
    );
  }

  if (linkValid === null) {
    return (
      <AuthCard>
        <p className="text-sm text-ink-500">Checking this link…</p>
      </AuthCard>
    );
  }

  if (!linkValid) {
    return (
      <AuthCard>
        <div className="grid gap-4">
          <div
            role="alert"
            className="rounded-md border border-red-200 bg-error-100 px-3 py-3 text-sm leading-6 text-error-600"
          >
            {LINK_REFUSALS[linkReason ?? ""] ?? "This password link is not valid."}
          </div>
          <Link href="/login" className="field-button-secondary w-full">
            Back to sign in
          </Link>
        </div>
      </AuthCard>
    );
  }

  return (
    <AuthCard>
      <form onSubmit={submit} className="grid gap-3">
        {error ? (
          <div role="alert" className="rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600">
            {error}
          </div>
        ) : null}

        <div className="grid gap-2">
          <label htmlFor="new-password" className="text-sm font-medium text-ink-900">
            New password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="new-password"
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="field-input h-[52px] pl-10 pr-11"
            />
            {/*
              ONE TOGGLE FOR BOTH BOXES, not one each. The pair is typed in sequence by one person
              checking one password against itself; two independent eyes would let them be revealed
              separately, which is the one arrangement in which "they do not match" is still a
              mystery. `aria-pressed` because it is a toggle, not an action.
            */}
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

        <div className="grid gap-2">
          <label htmlFor="confirm-password" className="text-sm font-medium text-ink-900">
            Repeat password
          </label>
          <div className="relative">
            <Lock aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
            <input
              id="confirm-password"
              type={showPassword ? "text" : "password"}
              autoComplete="new-password"
              required
              minLength={MIN_PASSWORD_LENGTH}
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
              className="field-input h-[52px] pl-10"
            />
          </div>
        </div>

        <p className="text-xs leading-5 text-ink-500">{passwordRuleLine("This link works once.")}</p>

        <Button type="submit" size="auth" disabled={saving} className="mt-1 w-full font-display text-base font-bold">
          {saving ? "Saving…" : "Set password"}
        </Button>
      </form>
    </AuthCard>
  );
}

/**
 * One sentence per refusal, because each has a different next action.
 *
 * The keys are the server's own reason words (`app/services/credential_links.py`), never the
 * server's sentences — matching on prose is what breaks the first time somebody fixes a comma.
 */
const LINK_REFUSALS: Record<string, string> = {
  missing: "This link is incomplete. Open the whole link the administrator sent you.",
  malformed: "This is not a link this site issued. Ask the administrator for another.",
  expired: "This link has expired. Ask the administrator for a new one.",
  revoked: "This link was withdrawn. Ask the administrator for a new one.",
  spent: "This link has already been used. Sign in with the password you set.",
  "unknown-account": "This link no longer points at an account."
};

export default function SetPasswordPage() {
  return (
    <Suspense
      fallback={
        <AuthCard>
          <p className="text-sm text-ink-500">Checking this link…</p>
        </AuthCard>
      }
    >
      <SetPasswordForm />
    </Suspense>
  );
}
