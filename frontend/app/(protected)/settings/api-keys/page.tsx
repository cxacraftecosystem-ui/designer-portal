"use client";

import { KeyRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { ApiKeysPanel } from "@/components/settings/ApiKeysPanel";
import { ProviderOrderPanel } from "@/components/settings/ProviderOrderPanel";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { TranscriptionPanel } from "@/components/settings/TranscriptionPanel";
import { isAdmin, isMasterAdmin } from "@/lib/permissions";

export default function ApiKeysPage() {
  const { user, loading } = useAuth();
  const admin = isAdmin(user);
  const master = isMasterAdmin(user);

  const header = (
    <PageHeader
      title="API keys"
      description={
        master
          ? "Provider keys. Changes apply everywhere immediately."
          : "Choose the transcription provider and test its key."
      }
      icon={<KeyRound className="h-5 w-5" aria-hidden />}
    />
  );

  if (loading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!admin) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body="Provider keys and the transcription provider order are managed by admins. Ask one of them if a key needs changing."
        />
      </>
    );
  }

  return (
    <>
      {header}
      <div className="grid gap-4">
        <TranscriptionPanel canChange={master} />
        <ProviderOrderPanel />
        {master ? <ApiKeysPanel /> : null}
      </div>
    </>
  );
}
