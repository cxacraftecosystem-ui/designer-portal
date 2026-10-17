"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";

import { ArtisanCardPanel } from "@/components/artisans/ArtisanCardPanel";
import { AadhaarVerifyPanel } from "@/components/aadhaar/AadhaarVerifyPanel";
import { ArtisanForm } from "@/components/forms/ArtisanForm";
import { ArtisanQuestionnairePanel } from "@/components/ArtisanQuestionnairePanel";
import { FieldProvenance } from "@/components/FieldProvenance";
import { PageHeader } from "@/components/PageHeader";
import { apiFetch } from "@/lib/api";
import type { Artisan } from "@/lib/types";

export default function EditArtisanPage() {
  const params = useParams<{ id: string }>();
  const [record, setRecord] = useState<Artisan | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    apiFetch<Artisan>(`/artisans/${params.id}`)
      .then(setRecord)
      .catch((err) => setError(err instanceof Error ? err.message : "Unable to load artisan"));
  }, [params.id]);

  return (
    <>
      <PageHeader title="Update artisan" />
      {error ? <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
      {record ? (
        <div className="grid gap-6">
          <ArtisanForm initial={record} />
          {/* The artisan's QR card: an opaque signed token, never the record id (services/artisan_card.py).
              Older DPW1:A cards still scan; nothing prints them now. */}
          <ArtisanCardPanel artisanId={record.id} />
          {/* Draws nothing unless the server has switched an Aadhaar method on. */}
          <AadhaarVerifyPanel subject={{ kind: "artisan", id: record.id }} recordName={record.name} />
          <ArtisanQuestionnairePanel artisanId={record.id} />
          <FieldProvenance extraMetadata={record.extraMetadata} title="Artisan field contributions" />
        </div>
      ) : (
        <div className="text-sm text-ink-700">Loading...</div>
      )}
    </>
  );
}
