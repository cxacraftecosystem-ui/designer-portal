"use client";

import Link from "next/link";
import { Activity, ClipboardCheck, KeyRound, Settings as SettingsIcon, UsersRound, type LucideIcon } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { adminChromeVisible, useAdminView } from "@/components/AdminViewProvider";
import { useAuth } from "@/components/AuthProvider";
import { AppSettingsPanel } from "@/components/settings/AppSettingsPanel";
import { GetTheAppPanel } from "@/components/settings/GetTheAppPanel";
import { GrievanceRedressalCard } from "@/components/settings/GrievanceRedressalCard";
import { PublishAppUpdatePanel } from "@/components/settings/PublishAppUpdatePanel";
import { MyAiKeysPanel } from "@/components/settings/MyAiKeysPanel";
import { AccessibilityCard, AppearanceCard } from "@/components/settings/PersonalSettingsCards";
import { UsageConsentCard } from "@/components/settings/UsageConsentCard";
import { WorkshopAccessRequestPanel } from "@/components/settings/WorkshopAccessRequestPanel";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";
import type { User } from "@/lib/types";

/**
 * /settings — this ACCOUNT's settings first, administration second.
 *
 * The page used to run two columns, with the master admin's global configuration filling the left
 * one and a locked "you may not do this" placeholder standing in its place for everyone else. That
 * told ordinary users about powers they will never have and squeezed the two settings they DO own
 * into a narrow rail. Now:
 *
 *  - Appearance and Accessibility sit side by side at full width for everybody (stacked on a phone);
 *  - the "Request workshop access" panel is open to everyone, because asking is not an admin act;
 *  - admin destinations are HIDDEN, not disabled, and only rendered for accounts the API would
 *    actually let through — a non-admin sees no trace that these pages exist.
 *
 * Gating uses `isAdmin` / `isMasterAdmin` from lib/permissions, the same predicates the backend
 * dependencies mirror (`require_admin` / `require_master_admin`), ANDed with `adminChromeVisible`
 * so the admin half also disappears while an admin browses with admin view off. The route itself
 * stays open to everyone: what is left — Appearance, Accessibility and Request workshop access —
 * belongs to the account rather than to the repository, and is exactly what an ordinary user sees.
 */

type AdminLink = {
  label: string;
  description: string;
  href: string;
  icon: LucideIcon;
  visible: (user: User | null | undefined) => boolean;
};

const ADMIN_LINKS: AdminLink[] = [
  {
    // The board is admin-guarded and lives under /settings, but it was reachable ONLY from a button
    // on /tasks — so an admin told "it is in settings" found no trace of it here. Same title and
    // icon the page itself uses, so the card and its destination read as one thing.
    label: "Task assignment",
    description: "Hand documentation work to the people below you, then hold it to account.",
    href: "/settings/tasks",
    icon: ClipboardCheck,
    visible: isAdmin
  },
  {
    label: "Workshop access",
    description:
      "Approve access requests, manage who may work in each workshop at what level, and choose which designers may see a design & prototype workshop.",
    // Points at the console directly rather than at /settings/workshop-access, which is now only a
    // redirect kept alive for bookmarks. A hub card that bounces through a redirect is a hub card
    // that will one day bounce through a broken one.
    href: "/workshop-access/manage",
    icon: UsersRound,
    visible: isAdmin
  },
  {
    // Admins go here for the transcription provider ranking; only the master admin sees the keys
    // themselves once inside. Hiding the card from admins is what left the ranking unreachable for
    // everyone who asked for it, so the card is admin-visible and its description is role-aware.
    label: "API keys",
    description: "Rank the transcription providers, and — for the master admin — rotate, test and reveal keys.",
    href: "/settings/api-keys",
    icon: KeyRound,
    visible: isAdmin
  },
  {
    // NOT "Analytics" — that name is already the cross-workshop content comparison at
    // /admin/analytics, and this repo's backend goes out of its way to keep the two apart (see
    // usage.py's module docstring). isAdmin here mirrors the same rank `require_usage_reader`
    // enforces on the server (Admin and above), so this card and the 403 an under-ranked account
    // would get from the API agree by construction rather than by two people remembering to.
    label: "Usage",
    description: "Which screens are reached, how often, how fast, and how often broken — aggregated across every account.",
    href: "/settings/usage",
    icon: Activity,
    visible: isAdmin
  }
];

export default function SettingsPage() {
  const { user, loading } = useAuth();
  const { adminMode } = useAdminView();
  // Role first, toggle second — every `visible` predicate below is `isAdmin` or `isMasterAdmin`, so
  // ANDing the toggle in can only ever remove a card, never add one for a user without the rights.
  const chrome = adminChromeVisible(user, adminMode);
  const admin = isAdmin(user) && chrome;
  const master = isMasterAdmin(user) && chrome;
  const links = ADMIN_LINKS.filter((link) => link.visible(user) && chrome);

  const header = (
    <PageHeader
      title="Settings"
      description={
        admin
          ? "How this account looks and reads, plus the repository administration you are entitled to."
          : "How this account looks and reads, and the workshops you can ask to work in."
      }
      icon={<SettingsIcon className="h-5 w-5" aria-hidden />}
    />
  );

  if (loading) {
    return (
      <>
        {header}
        <div className="panel p-4 text-sm text-ink-500">Loading...</div>
      </>
    );
  }

  return (
    <>
      {header}
      <div className="grid gap-4">
        {/* The two settings every account owns — a pair on anything wider than a phone. */}
        <div className="grid items-start gap-4 md:grid-cols-2">
          <AppearanceCard />
          <AccessibilityCard />
        </div>

        {/*
          NO ROLE GATE, AND HIGH UP, WITH THE OTHER TWO THINGS THIS ACCOUNT OWNS.

          `GET`/`POST /api/usage/consent` are `get_current_user` and nothing more — reading and
          changing your own answer about your own data needs permission from nobody — so this belongs
          exactly where Appearance and Accessibility are and not on `/settings/usage`, which is the
          admin aggregate. Filing a person's own consent behind an admin page would mean asking an
          administrator what you had agreed to.

          It sits above the app download rather than at the bottom because it is the door out of a
          turnstile: `/login` will not let anybody in without agreeing, and an agreement that is hard
          to find your way back to is an agreement that cannot really be withdrawn.
        */}
        <UsageConsentCard />

        {/*
          NO ROLE GATE, AND DIRECTLY UNDER THE CONSENT CARD, FOR THE SAME REASON THAT ONE GIVES.

          `POST /feedback/reports` is `get_current_user` and nothing more: raising a grievance,
          suggesting a change or recommending something needs permission from nobody, so this sits
          with Appearance, Accessibility and the recording consent — the things this ACCOUNT owns —
          rather than behind the "Repository administration" links below.

          THIS HUB AND NOT `/admin`, WHICH IS THE OTHER THING CALLED "SETTINGS" (§16: the dashboard's
          Settings TILE opens the admin hub, the Settings nav ROW opens this page). `/admin` is
          admin-gated end to end, so a grievance card there would be invisible to every account a
          grievance mechanism exists for — and it would mean asking the administration for
          permission to complain about it. That hub already carries a "User feedback" tile pointing
          at the same destination; that one is the READER's door and this is the WRITER's, and both
          leading to /feedback is correct because the page shows a person their own reports and an
          administrator the queue.

          Above `GetTheAppPanel` rather than below it because this is a door somebody opens when
          something has gone wrong, and the app download is a door they open once.
        */}
        <GrievanceRedressalCard />

        {/* Everyone sees this: the two apps are one product and each is better at half the job. */}
        <GetTheAppPanel />

        <WorkshopAccessRequestPanel />

        {/* Personal, not deployment-wide: this is the caller's own provider key, billed to
            them. It sits above the administration links for that reason — the panel that
            manages the ORGANISATION's keys is behind the master-admin link below. */}
        <MyAiKeysPanel />

        {links.length ? (
          <section className="panel p-5">
            <h2 className="font-display font-bold text-ink-900">Repository administration</h2>
            <p className="mt-1 text-sm text-ink-500">Settings that apply to everyone, not just this account.</p>
            <div className="mt-3 grid gap-3 sm:grid-cols-2">
              {links.map((link) => (
                <Link
                  className="group flex flex-col gap-2 rounded-lg border border-line-200 bg-card p-4 shadow-sm transition hover:border-purple-300 hover:shadow-md"
                  href={link.href}
                  key={link.href}
                >
                  <div className="grid h-10 w-10 place-items-center rounded-md bg-purple-800">
                    <link.icon className="h-5 w-5 text-white" aria-hidden />
                  </div>
                  <div className="font-display text-base font-bold leading-snug text-ink-900">{link.label}</div>
                  <p className="text-xs leading-5 text-ink-500">{link.description}</p>
                </Link>
              ))}
            </div>
          </section>
        ) : null}

        {/* Transcription and the off-peak window stay here, where they have always been — now gated
            rather than replaced by a lock panel for everyone below the master admin. The provider
            RANKING is deliberately not here: it lives on the API keys page, next to the providers it
            ranks, where admins can reach it too. */}
        {master ? <AppSettingsPanel /> : null}
        {master ? <PublishAppUpdatePanel /> : null}
      </div>
    </>
  );
}
