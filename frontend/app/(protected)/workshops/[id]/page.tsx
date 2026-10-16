"use client";

import { useParams } from "next/navigation";
import { MapPinned } from "lucide-react";

import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { workshopFields } from "@/components/records/recordFields";
import type { Workshop } from "@/lib/types";

/**
 * One crafts workshop, read-only. Update is drawn for Professor and above
 * (`require_workshop_manager`) and opens the inline form on /workshops with this workshop loaded.
 */
export default function WorkshopViewPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RecordViewPage<Workshop>
      kind="workshop"
      id={id}
      endpoint={`/workshops/${encodeURIComponent(id)}`}
      noun="workshop"
      icon={<MapPinned className="h-5 w-5" aria-hidden />}
      titleOf={(workshop) => workshop.title?.trim() || "Untitled workshop"}
      collabType="workshop"
    >
      {(workshop) => (
        <>
          <RecordFieldList rows={workshopFields(workshop)} />
          <ExistingMedia linkedRecordType="workshop" linkedRecordId={workshop.id} recordHold={null} title="Media" readOnly />
        </>
      )}
    </RecordViewPage>
  );
}
