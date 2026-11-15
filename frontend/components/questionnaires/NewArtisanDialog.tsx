"use client";

import { lazy, Suspense } from "react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { qs } from "@/lib/questionnaire/qs";
import type { Artisan } from "@/lib/types";

const InlineRecordForm = lazy(() => import("@/components/designworkshop/InlineRecordDialog").then((m) => ({ default: m.InlineRecordForm })));

export function NewArtisanDialog({ title, onClose, onCreated }: { title: string; onClose: () => void; onCreated: (artisan: Artisan) => void }) {
  return (
    <FieldDialog open onClose={onClose} title={title} dismissOnBackdrop={false} surfaceClassName="max-w-4xl">
      <Suspense fallback={<p className="min-h-40 px-1 py-6 text-sm text-ink-500">{qs("loading")}</p>}>
        <InlineRecordForm model="Artisan" onCreated={(record) => onCreated(record as Artisan)} onCancel={onClose} onQueued={onClose} />
      </Suspense>
    </FieldDialog>
  );
}
