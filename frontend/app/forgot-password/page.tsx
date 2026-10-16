"use client";

/**
 * "FORGOT YOUR PASSWORD?" — ASK FOR A ONE-TIME SET-PASSWORD LINK BY E-MAIL.
 *
 * Ported in shape from cxa-cms (`app/studio/login/forgot/ForgotPasswordForm.tsx`): one e-mail box, one
 * button, one answer.
 *
 * ── PUBLIC, AND IN THE LOGIN CARD'S FRAME ─────────────────────────────────────────────────────
 *
 * Outside `app/(protected)/` for `/set-password`'s reason: the person asking cannot sign in. It draws
 * the same frosted card as `/set-password`, so the two screens a locked-out person walks through
 * look like the sign-in they came from.
 *
 * ── THE ANSWER IS PRINTED VERBATIM AND IS THE SAME FOR EVERY ADDRESS ──────────────────────────
 *
 * `POST /auth/forgot-password` answers one sentence whether or not an account exists, has a password,
 * or e-mail could be sent — the work happens after the answer has gone. So this screen has no branch
 * that could say "an address is unknown", and must never grow one. The only failures it
 * shows are about the REQUEST: a malformed address (422), too many requests from this connection
 * (429, with `Retry-After` in words), and an unreachable server.
 *
 * The link it leads to lands on `/set-password`, which never signs anybody in: the person then signs
 * in with the new password and, where two-step verification is on, their code — the footnote says so.
 */

import Link from "next/link";
import { useRef, useState } from "react";
import { ArrowLeft, Mail } from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { Button } from "@/components/ui/button";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { ApiError } from "@/lib/api";
import { isUnreachable, serverAskedForTime } from "@/lib/failureTriage";
import { requestForgotPasswordLink, retryAfterWords } from "@/lib/signIn";

export default function ForgotPasswordPage() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState<string | null>(null);
  const emailRef = useRef<HTMLInputElement | null>(null);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    // FIRST, before any await — React nulls `currentTarget` across one.
    const data = new FormData(event.currentTarget);
    event.preventDefault();
    if (pending) return;
    const email = String(data.get("email") ?? "").trim();
    if (!email) {
      setError("Enter your email address.");
      emailRef.current?.focus();
      return;
    }
    setPending(true);
    setError(null);
    setSent(null);
    try {
      const answer = await requestForgotPasswordLink(email);
      setSent(answer?.message || "If that address has an account, a link is on its way.");
    } catch (err) {
      if (err instanceof ApiError && serverAskedForTime(err)) {
        const wait = retryAfterWords(err.headers?.get("retry-after"));
        setError(wait && !err.message.includes("Try again") ? `${err.message} ${wait}` : err.message);
      } else if (isUnreachable(err)) {
        setError("Couldn't connect. Check your connection and try again.");
      } else {
        setError(err instanceof Error && err.message ? err.message : "That did not work. Check the address and try again.");
        emailRef.current?.focus();
      }
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden bg-bg-0 px-6 py-6 md:px-10">
      <div aria-hidden className="pointer-events-none absolute inset-0">
        <div className="absolute inset-0 grad-mesh opacity-60" />
        <div className="absolute -left-16 top-12 h-72 w-72 rounded-full bg-purple-300/30 blur-3xl" />
        <div className="absolute -right-12 bottom-8 h-80 w-80 rounded-full bg-amber-500/15 blur-3xl" />
      </div>
      <GlassSurface options={GLASS_PANEL} className="glass-card relative z-10 w-full max-w-md rounded-xl p-6 shadow-lg md:p-8">
        <div className="mb-5 flex flex-col items-center gap-2 text-center">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm" />
          <h1 className="font-display text-2xl font-bold text-ink-900">Forgot your password?</h1>
          <p className="text-sm leading-6 text-ink-500">
            Enter the email address you sign in with. If it belongs to an account that uses a password, we will email
            it a link to choose a new one. The link works once and lasts 2 hours.
          </p>
        </div>

        <form onSubmit={submit} noValidate className="grid gap-3">
          {/* Both regions mounted from first paint, so what lands in them is announced. */}
          <div
            role="alert"
            aria-live="assertive"
            className={error ? "rounded-md border border-red-200 bg-error-100 px-3 py-2 text-sm text-error-600" : "sr-only"}
          >
            {error ?? ""}
          </div>
          <p
            role="status"
            aria-live="polite"
            className={sent ? "rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700" : "sr-only"}
          >
            {sent ?? ""}
          </p>

          <div className="grid gap-2">
            <label htmlFor="forgot-email" className="text-sm font-medium text-ink-900">
              Email address
            </label>
            <div className="relative">
              <Mail aria-hidden className="absolute left-3 top-1/2 h-5 w-5 -translate-y-1/2 text-ink-500" />
              <input
                ref={emailRef}
                id="forgot-email"
                name="email"
                type="email"
                autoComplete="username"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                inputMode="email"
                enterKeyHint="send"
                maxLength={320}
                disabled={pending}
                placeholder="you@example.com"
                className="field-input h-[52px] pl-10"
              />
            </div>
          </div>

          <Button type="submit" className="w-full font-display font-bold" size="auth" disabled={pending}>
            {pending ? "Sending…" : "Email me a link"}
          </Button>
        </form>

        <p className="mt-5 text-xs leading-5 text-ink-500">
          If two-step verification is on for your account, you will still need your authenticator app to sign in
          afterwards.
        </p>
        <p className="mt-2 text-xs leading-5 text-ink-500">
          No email? Ask an administrator — they can make you a link. Nobody here can see your password, and nobody will
          ever ask you for it.
        </p>

        <p className="mt-5 text-sm">
          <Link href="/login" className="inline-flex items-center gap-1.5 font-medium text-purple-700 hover:underline">
            <ArrowLeft aria-hidden className="h-4 w-4 shrink-0" />
            Back to sign in
          </Link>
        </p>
      </GlassSurface>
    </main>
  );
}
