"use client";

import { useParams } from "next/navigation";
import { Hammer } from "lucide-react";

import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { toolFields } from "@/components/records/recordFields";
import type { ToolDocumentation } from "@/lib/types";

/** One tool record, read-only. Update (in the header) opens the form — see `RecordView.tsx`. */
export default function ToolViewPage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RecordViewPage<ToolDocumentation>
      kind="tool"
      id={id}
      endpoint={`/tools/${encodeURIComponent(id)}`}
      noun="tool record"
      icon={<Hammer className="h-5 w-5" aria-hidden />}
      titleOf={(tool) => tool.toolkitName || "Tool"}
      collabType="tool"
    >
      {(tool) => (
        <>
          <RecordFieldList rows={toolFields(tool)} />
          <ExistingMedia linkedRecordType="tool" linkedRecordId={tool.id} recordHold={null} title="Media" readOnly />
        </>
      )}
    </RecordViewPage>
  );
}
