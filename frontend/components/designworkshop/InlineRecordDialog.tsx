"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import { deferredTool, registerWarm } from "@/components/designworkshop/deferredTool";

import { FieldDialog } from "@/components/dialogs";
import { useLeaveInterceptor } from "@/components/UnsavedChangesGuard";
import { apiFetch } from "@/lib/api";
import type { ProcessRecord } from "@/components/forms/ProcessForm";

function FormLoading() {
  return <p className="min-h-40 px-1 py-6 text-sm text-ink-500">Opening the form…</p>;
}
const ArtisanForm = deferredTool(() => import("@/components/forms/ArtisanForm").then((m) => m.ArtisanForm), {
  placeholder: FormLoading
});
const ProcessForm = deferredTool(() => import("@/components/forms/ProcessForm").then((m) => m.ProcessForm), {
  placeholder: FormLoading
});
const ProductForm = deferredTool(() => import("@/components/forms/ProductForm").then((m) => m.ProductForm), {
  placeholder: FormLoading
});
const ToolForm = deferredTool(() => import("@/components/forms/ToolForm").then((m) => m.ToolForm), {
  placeholder: FormLoading
});
for (const form of [ArtisanForm, ProcessForm, ProductForm, ToolForm]) registerWarm(form.preload);
import type { InlineHostSeed, UseExistingArtisan } from "@/components/forms/inlineRecordHost";
import type { Artisan, ArtisanIdentityMatch, ProductDocumentation, ToolDocumentation } from "@/lib/types";

export const INLINE_CREATABLE = ["Artisan", "ProductDocumentation", "ToolDocumentation", "Process"] as const;
export type InlineCreatableModel = (typeof INLINE_CREATABLE)[number];

export function isInlineCreatable(refModel: string | undefined): refModel is InlineCreatableModel {
  return !!refModel && (INLINE_CREATABLE as readonly string[]).includes(refModel);
}

export const INLINE_MODEL_NOUN: Record<InlineCreatableModel, string> = {
  Artisan: "artisan",
  ProductDocumentation: "product",
  ToolDocumentation: "tool",
  Process: "process"
};

const INLINE_MODEL_PATH: Record<InlineCreatableModel, string> = {
  Artisan: "artisans",
  ProductDocumentation: "products",
  ToolDocumentation: "tools",
  Process: "processes"
};

export type InlineCreatedRecord = Artisan | ProductDocumentation | ToolDocumentation | ProcessRecord;

type CreatedRecord = InlineCreatedRecord;


export function InlineRecordForm({
  model,
  recordId,
  seed,
  footerFields,
  onCreated,
  onCancel,
  onDiscardAndLeave,
  onQueued,
  onUseExisting
}: {
  model: InlineCreatableModel;
  recordId?: string;
  seed?: InlineHostSeed;
  footerFields?: ReactNode;
  onCreated: (record: CreatedRecord) => void;
  onCancel: () => void;
  onDiscardAndLeave?: () => void;
  onQueued?: () => void;
  onUseExisting?: UseExistingArtisan;
}) {
  const noun = INLINE_MODEL_NOUN[model];
  const editing = Boolean(recordId);

  const seedForForm = editing ? undefined : seed;

  const [initial, setInitial] = useState<CreatedRecord | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    if (!recordId) {
      setInitial(null);
      setLoadError(null);
      return;
    }
    let cancelled = false;
    setInitial(null);
    setLoadError(null);
    apiFetch<CreatedRecord>(`/${INLINE_MODEL_PATH[model]}/${recordId}`)
      .then((record) => {
        if (!cancelled) setInitial(record);
      })
      .catch((err) => {
        if (!cancelled) setLoadError(err instanceof Error ? err.message : `Unable to open this ${noun}`);
      });
    return () => {
      cancelled = true;
    };
  }, [recordId, model, noun]);

  return (
    <>
      {loadError ? (
        <p className="rounded-md border border-error-500/30 bg-error-50 px-3 py-2 text-sm text-error-700">{loadError}</p>
      ) : null}

      {editing && !initial && !loadError ? (
        <p className="px-1 py-6 text-sm text-ink-500">Opening this {noun}…</p>
      ) : null}

      {(!editing || initial) && !loadError ? (
        <>
          {model === "Artisan" ? (
            <ArtisanForm
              initial={(initial as Artisan) ?? undefined}
              seed={seedForForm}
              footerFields={footerFields}
              onCreated={onCreated}
              onCancel={onCancel}
              onDiscardAndLeave={onDiscardAndLeave}
              onQueued={onQueued}
              onUseExisting={onUseExisting}
            />
          ) : null}
          {model === "ProductDocumentation" ? (
            <ProductForm
              initial={(initial as ProductDocumentation) ?? undefined}
              seed={seedForForm}
              footerFields={footerFields}
              onCreated={onCreated}
              onCancel={onCancel}
              onDiscardAndLeave={onDiscardAndLeave}
              onQueued={onQueued}
            />
          ) : null}
          {model === "ToolDocumentation" ? (
            <ToolForm
              initial={(initial as ToolDocumentation) ?? undefined}
              seed={seedForForm}
              footerFields={footerFields}
              onCreated={onCreated}
              onCancel={onCancel}
              onDiscardAndLeave={onDiscardAndLeave}
              onQueued={onQueued}
            />
          ) : null}
          {model === "Process" ? (
            <ProcessForm
              initial={(initial as ProcessRecord) ?? undefined}
              footerFields={footerFields}
              onDone={onCancel}
              onCancel={onCancel}
              onDiscardAndLeave={onDiscardAndLeave}
              onCreated={onCreated}
              onQueued={onQueued}
            />
          ) : null}
        </>
      ) : null}
    </>
  );
}

export function InlineRecordDialog({
  open,
  model,
  recordId,
  seed,
  onClose,
  onCreated,
  onQueued,
  onUseExisting
}: {
  open: boolean;
  model: InlineCreatableModel;
  seed?: InlineHostSeed;
  recordId?: string;
  onClose: () => void;
  onCreated: (record: CreatedRecord) => void;
  onQueued?: () => void;
  onUseExisting?: UseExistingArtisan;
}) {
  const noun = INLINE_MODEL_NOUN[model];
  const editing = Boolean(recordId);

  const finish = useCallback(
    (record: CreatedRecord) => {
      onCreated(record);
      onClose();
    },
    [onCreated, onClose]
  );

  const reportQueued = useCallback(() => {
    onQueued?.();
    onClose();
  }, [onQueued, onClose]);

  const adoptExisting = useCallback(
    (artisan: ArtisanIdentityMatch) => {
      onUseExisting?.(artisan);
      onClose();
    },
    [onUseExisting, onClose]
  );

  const interceptLeave = useLeaveInterceptor();
  const closeUnlessAsked = useCallback(() => {
    if (interceptLeave(onClose)) return;
    onClose();
  }, [interceptLeave, onClose]);

  return (
    <FieldDialog
      open={open}
      onClose={closeUnlessAsked}
      title={editing ? `Edit ${noun}` : `New ${noun}`}
      description={
        editing
          ? `Saves to the record. The stage stays open.`
          : `Saves the ${noun} and selects it. The stage stays open.`
      }
      dismissOnBackdrop={false}
      surfaceClassName="max-w-4xl"
    >
      <InlineRecordForm
        model={model}
        recordId={recordId}
        seed={seed}
        onCreated={finish}
        onCancel={onClose}
        onQueued={reportQueued}
        onUseExisting={onUseExisting ? adoptExisting : undefined}
      />
    </FieldDialog>
  );
}
