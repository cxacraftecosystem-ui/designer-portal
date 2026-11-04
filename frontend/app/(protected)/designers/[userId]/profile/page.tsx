"use client";

import { use, useEffect, useState } from "react";
import Link from "next/link";
import { IdCard } from "lucide-react";

import { DesignerProfileForm } from "@/components/designers/DesignerProfileForm";
import { DesignerProfileView } from "@/components/designers/DesignerProfileView";
import { DESIGNER_PROFILE_COPY_NOTICE } from "@/components/designers/profileCopy";
import { PageHeader } from "@/components/PageHeader";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { useAuth } from "@/components/AuthProvider";
import { ApiError, apiFetch } from "@/lib/api";
import {
  designerProfileIsEmpty,
  getDesignerProfile,
  saveDesignerProfile,
  type DesignerProfile
} from "@/lib/designers";
import { isAdmin, roleLabel } from "@/lib/permissions";

type DirectoryUser = { id: string; name: string; email: string; role: string };

export default function DesignerProfilePage({ params }: { params: Promise<{ userId: string }> }) {
  const { userId } = use(params);
  const { user } = useAuth();

  const [profile, setProfile] = useState<DesignerProfile | null>(null);
  const [account, setAccount] = useState<DirectoryUser | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);

  const isOwner = Boolean(user?.id) && user?.id === userId;
  const mayEdit = isOwner || isAdmin(user);

  useEffect(() => {
    let cancelled = false;
    setNotFound(false);
    setError(null);
    setProfile(null);
    setAccount(null);
    getDesignerProfile(userId)
      .then((loaded) => {
        if (!cancelled) setProfile(loaded);
      })
      .catch((err) => {
        if (cancelled) return;
        if (err instanceof ApiError && err.status === 404) {
          setNotFound(true);
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this designer profile");
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  useEffect(() => {
    let cancelled = false;
    apiFetch<DirectoryUser[]>("/users/directory")
      .then((rows) => {
        if (!cancelled) setAccount(rows.find((row) => row.id === userId) ?? null);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [userId]);

  const displayName = account?.name || profile?.displayName || "This designer";
  const subtitle = account
    ? `${account.email} · ${roleLabel(account.role)}`
    : profile?.institution ?? null;

  return (
    <>
      <PageHeader
        title={notFound ? "Designer profile" : `${displayName}’s designer profile`}
        description={
          notFound
            ? undefined
            : mayEdit
              ? "Their details for workshop reports."
              : "Their details for workshop reports. Read-only."
        }
        icon={<IdCard className="h-5 w-5" aria-hidden />}
        actions={
          isOwner ? (
            <Link href="/designers/profile" className="field-button-secondary">
              My designer profile
            </Link>
          ) : null
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {notFound ? (
        <RestrictedPanel
          title="No profile to show"
          body={
            "Profile not found, or you don't have access."
          }
        />
      ) : profile === null ? (
        !error ? <section className="panel p-4 text-sm text-ink-700">Loading this profile…</section> : null
      ) : mayEdit ? (
        <>
          {subtitle ? <p className="mb-4 text-sm text-ink-muted">{subtitle}</p> : null}
          <DesignerProfileForm
            key={profile.userId}
            profile={profile}
            save={(body) => saveDesignerProfile(userId, body)}
            onSaved={setProfile}
            possessive={isOwner ? "my" : "this"}
          />
        </>
      ) : (
        <>
          {subtitle ? <p className="mb-4 text-sm text-ink-muted">{subtitle}</p> : null}
          <p className="mb-5 rounded-md border border-line-200 bg-surface-50 px-4 py-3 text-sm leading-6 text-ink-muted">
            Only {displayName} and an admin can change this profile. {DESIGNER_PROFILE_COPY_NOTICE}
          </p>
          {designerProfileIsEmpty(profile) ? (
            <section className="panel p-4 text-sm leading-6 text-ink-700">
              {displayName} hasn&rsquo;t filled in a profile yet.
            </section>
          ) : (
            <DesignerProfileView profile={profile} />
          )}
        </>
      )}
    </>
  );
}
