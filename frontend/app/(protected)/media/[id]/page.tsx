"use client";

import { useParams } from "next/navigation";
import { Images } from "lucide-react";

import { ReadOnlyMediaGallery, RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { mediaFields } from "@/components/records/recordFields";
import { RecordingChips } from "@/components/questionnaire/RecordingChips";
import { useChipNavigator } from "@/components/questionnaire/useUrlFilters";
import type { MediaFile } from "@/lib/types";

/**
 * One uploaded file, read-only: a preview, its transcript, the Download button and what it is
 * attached to. There is no Update — a stored file has no edit form — so the header draws none.
 */
export default function MediaViewPage() {
  const { id } = useParams<{ id: string }>();
  const goChip = useChipNavigator();
  return (
    <RecordViewPage<MediaFile>
      kind="media"
      id={id}
      endpoint={`/media/${encodeURIComponent(id)}`}
      noun="file"
      icon={<Images className="h-5 w-5" aria-hidden />}
      titleOf={(media) => media.caption?.trim() || media.originalFilename || "File"}
    >
      {(media) => (
        <>
          <section className="panel grid gap-3 p-4">
            <RecordingChips chips={media.questionnaireChips} size="md" onChip={goChip} />
            <ReadOnlyMediaGallery title="File" items={[media]} />
          </section>
          <RecordFieldList rows={mediaFields(media)} />
        </>
      )}
    </RecordViewPage>
  );
}
