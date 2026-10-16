"use client";

import { useParams } from "next/navigation";
import { Brush } from "lucide-react";

import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { craftFields } from "@/components/records/recordFields";
import type { Craft } from "@/lib/types";

/**
 * One craft, read-only. Update is drawn only for Professor and above (`require_craft_manager`) and
 * opens the inline form on /crafts with this craft loaded.
 */
export default function CraftViewPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RecordViewPage<Craft & { status?: string | null }>
      kind="craft"
      id={id}
      endpoint={`/crafts/${encodeURIComponent(id)}`}
      noun="craft"
      icon={<Brush className="h-5 w-5" aria-hidden />}
      titleOf={(craft) => craft.name}
      collabType="craft"
    >
      {(craft) => (
        <>
          <RecordFieldList rows={craftFields(craft)} />
          <ExistingMedia linkedRecordType="craft" linkedRecordId={craft.id} recordHold={null} title="Media" readOnly />
        </>
      )}
    </RecordViewPage>
  );
}
