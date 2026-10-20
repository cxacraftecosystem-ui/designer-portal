"use client";

import { useParams } from "next/navigation";
import { Brush } from "lucide-react";

import { GI_LABEL } from "@/components/crafts/craftAdmin";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { craftFields, type RecordFieldRow } from "@/components/records/recordFields";
import { craftIndex, craftPath, useCraftCatalogue, type CraftCatalogue } from "@/lib/craftCatalogue";
import type { Craft } from "@/lib/types";

/** The master-list attributes (REQUIREMENTS_2026-10-20 §2.6): serial, path, GI, states. */
function masterListFields(craft: Craft, catalogue: CraftCatalogue | null): RecordFieldRow[] {
  const listed = catalogue ? craftIndex(catalogue).byId.get(craft.id) : undefined;
  const path = listed && catalogue ? craftPath(craftIndex(catalogue), listed) : null;
  const status = craft.status === "LEGACY" ? "Waiting for review" : craft.status === "MERGED" ? "Merged into another craft" : null;
  const rows: [string, string | null | undefined][] = [
    ["Category › Sub-category", path],
    ["Serial", craft.sourceSerial != null ? String(craft.sourceSerial) : null],
    ["GI status", craft.giStatus ? GI_LABEL[craft.giStatus] : null],
    ["State of origin", craft.stateOfOrigin?.join(", ")],
    ["State of practice", craft.stateOfPractice?.join(", ")],
    ["On the list", status]
  ];
  return rows.filter((row): row is [string, string] => Boolean(row[1])).map(([label, value]) => ({ label, value }));
}

/**
 * One craft, read-only. Update is drawn only for Professor and above (`require_craft_manager`) and
 * opens the inline form on /crafts with this craft loaded.
 */
export default function CraftViewPage() {
  const { id } = useParams<{ id: string }>();
  const { catalogue } = useCraftCatalogue();
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
          <RecordFieldList rows={[...masterListFields(craft as Craft, catalogue), ...craftFields(craft).filter((row) => row.label !== "Category")]} />
          <ExistingMedia linkedRecordType="craft" linkedRecordId={craft.id} recordHold={null} title="Media" readOnly />
        </>
      )}
    </RecordViewPage>
  );
}
