"use client";

import { Suspense, useEffect, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { IdCard, Sparkles } from "lucide-react";

import { DesignerProfileForm } from "@/components/designers/DesignerProfileForm";
import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import {
  getMyDesignerProfile,
  saveMyDesignerProfile,
  type DesignerProfile
} from "@/lib/designers";
import { canManageDesignerRoster } from "@/lib/permissions";

function WelcomeNotice() {
  const params = useSearchParams();
  if (params.get("welcome") !== "1") return null;
  return (
    <div className="mb-4 flex items-start gap-3 rounded-md border border-line-200 bg-field-100 px-3 py-3">
      <Sparkles className="mt-0.5 h-5 w-5 shrink-0 text-field-600 dark:text-purple-300" aria-hidden />
      <div className="min-w-0 text-sm leading-6 text-ink-700">
        <p className="font-medium text-ink-900">Welcome — start here.</p>
        <p className="mt-0.5">
          Fill this in once; it&rsquo;s copied into your reports. You can finish later from the menu.
        </p>
      </div>
    </div>
  );
}

export default function MyDesignerProfilePage() {
  const { user } = useAuth();
  const [profile, setProfile] = useState<DesignerProfile | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    getMyDesignerProfile()
      .then((loaded) => {
        if (!cancelled) setProfile(loaded);
      })
      .catch((err) => {
        if (!cancelled) setError(err instanceof Error ? err.message : "Unable to load your designer profile");
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader
        title="My designer profile"
        description="Your details for workshop reports."
        icon={<IdCard className="h-5 w-5" aria-hidden />}
        actions={
          canManageDesignerRoster(user) ? (
            <Link href="/admin/designers" className="field-button-secondary">
              Designer roster
            </Link>
          ) : null
        }
      />

      <Suspense fallback={null}>
        <WelcomeNotice />
      </Suspense>

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {profile === null ? (
        !error ? <section className="panel p-4 text-sm text-ink-700">Loading your profile…</section> : null
      ) : (
        <DesignerProfileForm
          profile={profile}
          save={saveMyDesignerProfile}
          onSaved={setProfile}
          possessive="my"
        />
      )}
    </>
  );
}
