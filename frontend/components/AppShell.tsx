"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect } from "react";
import { motion } from "framer-motion";
import { EyeOff, Lock } from "lucide-react";

import {
  adminChromeRouteFor,
  adminChromeVisible,
  useAdminView,
  type AdminChromeRoute
} from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { DynamicIslandNav } from "@/components/DynamicIslandNav";
import { FirstPasswordGate } from "@/components/FirstPasswordGate";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";
import { PageSelvedge } from "@/components/PageSelvedge";
import { WorkshopLogo } from "@/components/WorkshopLogo";
import { isAdmin, ministrySurface, roleLabel, routeGuardFor } from "@/lib/permissions";
import { mustChangePassword } from "@/lib/signIn";
import { ActionGroup } from "@/components/ui/ActionGroup";

export function AppShell({ children }: { children: React.ReactNode }) {
  const { user, loading, logout, markPasswordChanged } = useAuth();
  const { adminMode, adminViewResolved, setAdminView } = useAdminView();
  const router = useRouter();
  const pathname = usePathname();
  const reduce = useAppReducedMotion();

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, router, user]);

  if (loading) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-bg-0 text-sm text-ink-500">
        Opening the repository…
      </main>
    );
  }

  if (!user) return null;

  if (mustChangePassword(user)) {
    return (
      <FirstPasswordLocked
        onDone={markPasswordChanged}
        onSignOut={() => {
          logout().catch(() => undefined);
        }}
      />
    );
  }

  const guard = routeGuardFor(pathname);
  const blocked = Boolean(guard && !guard.can(user));

  const chrome = blocked ? null : adminChromeRouteFor(pathname);
  const chromeSettling = Boolean(chrome) && isAdmin(user) && !adminViewResolved;
  const chromeHidden = Boolean(chrome) && !adminChromeVisible(user, adminMode);

  const ministry = !blocked && ministrySurface(pathname);

  return (
    <div className="min-h-screen bg-bg-0">
      <a
        href="#main-content"
        className="sr-only left-3 top-3 z-60 rounded-md bg-purple-700 px-3 py-2 text-sm font-medium text-white focus:not-sr-only focus:fixed"
      >
        Skip to content
      </a>
      <DynamicIslandNav />
      <PageSelvedge />
      <motion.main
        id="main-content"
        tabIndex={-1}
        key={pathname}
        initial={reduce ? { opacity: 1, y: 0 } : { opacity: 0, y: 6 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduce ? { duration: 0 } : { duration: 0.22, ease: "easeOut" }}
        data-surface={ministry ? "ministry" : undefined}
        className="relative mx-auto max-w-7xl px-4 pb-[calc(4.5rem+env(safe-area-inset-bottom))] pt-[calc(60px+1.25rem)] lg:pb-12 lg:pt-24"
      >
        {blocked && guard ? (
          <RouteLocked title={guard.title} message={guard.message} role={roleLabel(user.role)} />
        ) : chrome && chromeSettling ? (
          <section className="panel p-6 text-sm text-ink-500">Checking your admin view…</section>
        ) : chrome && chromeHidden ? (
          <AdminViewHidden route={chrome} onEnable={() => setAdminView(true)} />
        ) : (
          children
        )}
      </motion.main>
    </div>
  );
}

function FirstPasswordLocked({ onDone, onSignOut }: { onDone: () => void; onSignOut: () => void }) {
  return (
    <div className="grid min-h-screen place-items-center bg-bg-0 px-4 py-12">
      <main id="main-content" tabIndex={-1} className="w-full max-w-md">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <WorkshopLogo className="h-12 w-12 rounded-xl shadow-sm" />
          <h1 className="font-display text-2xl font-bold text-ink-900">Set a new password</h1>
        </div>
        <section className="panel p-6">
          <FirstPasswordGate currentPassword="" onDone={onDone} onSignOut={onSignOut} />
        </section>
      </main>
    </div>
  );
}

function RouteLocked({ title, message, role }: { title: string; message: string; role: string }) {
  return (
    <section className="panel px-6 py-14 text-center" aria-live="polite">
      <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-purple-50 text-purple-700">
        <Lock className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">{title}</h1>
      <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">{message}</p>
      <p className="mt-3 text-xs text-ink-500">
        You are signed in as <span className="font-medium text-ink-700">{role}</span>. An admin can raise your access.
      </p>
      <ActionGroup className="mt-7">
        <Link href="/dashboard" className="field-button">
          Back to dashboard
        </Link>
        <Link href="/guide" className="field-button-secondary">
          Open the walkthrough
        </Link>
      </ActionGroup>
    </section>
  );
}

function AdminViewHidden({ route, onEnable }: { route: AdminChromeRoute; onEnable: () => void }) {
  return (
    <section className="panel px-6 py-14 text-center" aria-live="polite">
      <div className="mx-auto mb-4 grid h-12 w-12 place-items-center rounded-full bg-surface-50 text-ink-500">
        <EyeOff className="h-5 w-5" aria-hidden />
      </div>
      <h1 className="font-display text-xl font-bold tracking-tight text-ink-900">
        {route.label} is hidden while admin view is off
      </h1>
      <p className="mx-auto mt-3 max-w-lg text-sm leading-6 text-ink-500">
        {route.blurb} Admin view is off, so you are seeing the portal as an ordinary user does.
      </p>
      <p className="mt-3 text-xs text-ink-500">
        Your access has not changed — this is your own setting.
      </p>
      <ActionGroup className="mt-7">
        <button type="button" onClick={onEnable} className="field-button">
          Turn admin view back on
        </button>
        {route.alternative ? (
          <Link href={route.alternative.href} className="field-button-secondary">
            {route.alternative.label}
          </Link>
        ) : null}
        <Link href="/dashboard" className="field-button-secondary">
          Back to dashboard
        </Link>
      </ActionGroup>
    </section>
  );
}
