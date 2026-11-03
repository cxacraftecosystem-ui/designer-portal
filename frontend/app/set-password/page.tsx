"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Eye, EyeOff, Lock } from "lucide-react";

import { useAuth } from "@/components/AuthProvider";
import { WorkshopLogo } from "@/components/WorkshopLogo";
import { Button } from "@/components/ui/button";
import { GLASS_PANEL, GlassSurface } from "@/components/ui/GlassSurface";
import { ApiError } from "@/lib/api";
import {
  MAX_PASSWORD_LENGTH,
  MIN_PASSWORD_LENGTH,
  checkPasswordLink,
  passwordRuleLine,
  setPasswordWithLink,
  takeLinkTokenFromAddress
} from "@/lib/signIn";

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
  const { clearSession } = useAuth();

  const [token, setToken] = useState<string | null>(null);
  const [linkValid, setLinkValid] = useState<boolean | null>(null);
  const [linkReason, setLinkReason] = useState<string | null>(null);
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  useEffect(() => {
    const taken = takeLinkTokenFromAddress(window.location.href);
    const address = taken.address;
    if (address !== null) {
      queueMicrotask(() => window.history.replaceState(null, "", address));
    }
    setToken((previous) => previous || taken.token);
  }, []);

  useEffect(() => {
    if (token === null) return;
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
      if (!token) return;
      if (password !== confirm) {
        setError("The two passwords do not match.");
        return;
      }
      setSaving(true);
      setError(null);
      try {
        await setPasswordWithLink(token, password);
        clearSession();
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
    [clearSession, confirm, password, token]
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
              maxLength={MAX_PASSWORD_LENGTH}
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
              maxLength={MAX_PASSWORD_LENGTH}
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

const LINK_REFUSALS: Record<string, string> = {
  missing: "This link is incomplete. Open the whole link the administrator sent you.",
  malformed: "This is not a link this site issued. Ask the administrator for another.",
  expired: "This link has expired. Ask the administrator for a new one.",
  revoked: "This link was withdrawn. Ask the administrator for a new one.",
  spent: "This link has already been used. Sign in with the password you set.",
  "unknown-account": "This link no longer points at an account."
};

export default function SetPasswordPage() {
  return <SetPasswordForm />;
}
