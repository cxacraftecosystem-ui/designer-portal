"use client";

/**
 * Create an artisan, product, tool or process WITHOUT leaving the stage you are filling in.
 *
 * THE PROBLEM THIS REMOVES. Half the fields of a 22-stage design workshop are references — the
 * artisan who wove the prototype, the product it was copied from, the tool it was made on. The
 * picker can only offer records that already exist, so a designer who reaches stage 13 and finds
 * the artisan missing had to abandon a half-filled stage, navigate to /artisans/new, fill in a
 * full-page form, navigate back, find their place again, and re-open the row. In a room, with the
 * artisan standing in front of them, that is the moment the app stops being used.
 *
 * IT MOUNTS THE REAL FORM, NOT A SIMPLER ONE. `ArtisanForm`, `ProductForm`, `ToolForm` and
 * `ProcessForm` are rendered here exactly as their own pages render them, through an `onCreated`
 * callback those forms grew for this. That is deliberate and it is the whole design: an artisan
 * record carries an Aadhaar checksum, a duplicate check against the repository's deduplication
 * key, a mandatory location and the Do's and Don'ts. A "quick create" with four boxes would be a
 * SECOND answer to what an artisan is, it would drift from the first, and the records it made
 * would be the ones missing the fields nobody could see were missing.
 *
 * So the dialog is large, and it should be. What it saves is not typing — it is the designer's
 * place in the stage.
 */

import { useCallback, useEffect, useState } from "react";

import { FieldDialog } from "@/components/dialogs";
import { apiFetch } from "@/lib/api";
import { ArtisanForm } from "@/components/forms/ArtisanForm";
import { ProcessForm } from "@/components/forms/ProcessForm";
import { ProductForm } from "@/components/forms/ProductForm";
import { ToolForm } from "@/components/forms/ToolForm";
import type { ProcessRecord } from "@/components/forms/ProcessForm";
import type { Artisan, ProductDocumentation, ToolDocumentation } from "@/lib/types";

/**
 * The reference models a stage field can point at and this dialog can create.
 *
 * `Dw…` models are NOT here and cannot be: a `DwSketch` or a `DwPrototype` is another ROW of the
 * same workshop, created by adding a row to its own stage, not a repository record. Offering to
 * "create" one from a picker would put a second, parallel way to add a prototype into the app.
 */
export const INLINE_CREATABLE = ["Artisan", "ProductDocumentation", "ToolDocumentation", "Process"] as const;
export type InlineCreatableModel = (typeof INLINE_CREATABLE)[number];

export function isInlineCreatable(refModel: string | undefined): refModel is InlineCreatableModel {
  return !!refModel && (INLINE_CREATABLE as readonly string[]).includes(refModel);
}

/** What the picker shows on the button, and what the dialog calls the thing being made. */
export const INLINE_MODEL_NOUN: Record<InlineCreatableModel, string> = {
  Artisan: "artisan",
  ProductDocumentation: "product",
  ToolDocumentation: "tool",
  Process: "process"
};

/** Where one record of each model is read from, for the EDIT case. */
const INLINE_MODEL_PATH: Record<InlineCreatableModel, string> = {
  Artisan: "artisans",
  ProductDocumentation: "products",
  ToolDocumentation: "tools",
  Process: "processes"
};

type CreatedRecord = Artisan | ProductDocumentation | ToolDocumentation | ProcessRecord;

export function InlineRecordDialog({
  open,
  model,
  recordId,
  onClose,
  onCreated
}: {
  open: boolean;
  model: InlineCreatableModel;
  /**
   * Edit this record instead of creating one.
   *
   * The same argument as creating: a designer who spots that the artisan's village is wrong while
   * filling stage 13 should not have to abandon the stage to fix one field. The record is fetched
   * here rather than passed in because the picker holds an OPTION — an id, a label and a sublabel
   * — and a form seeded from that would blank every field the option does not carry.
   */
  recordId?: string;
  onClose: () => void;
  /**
   * The new record. The caller selects it in the picker and hydrates the row from it, which is why
   * the whole record is handed back rather than an id: the hydration writes the artisan's name,
   * village, gender and phone onto the stage entry, and a second fetch to learn what we already
   * have would be a round trip in the middle of a designer's sentence.
   */
  onCreated: (record: CreatedRecord) => void;
}) {
  const noun = INLINE_MODEL_NOUN[model];
  const editing = Boolean(recordId);

  /** The full record, for the edit case. Null while it is being read. */
  const [initial, setInitial] = useState<CreatedRecord | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    if (!open || !recordId) {
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
        // Named rather than silent: an edit dialog that opens empty reads as the record having
        // been lost, and the designer's next move is to create a duplicate.
        if (!cancelled) setLoadError(err instanceof Error ? err.message : `Unable to open this ${noun}`);
      });
    return () => {
      cancelled = true;
    };
  }, [open, recordId, model, noun]);

  const finish = useCallback(
    (record: CreatedRecord) => {
      onCreated(record);
      onClose();
    },
    [onCreated, onClose]
  );

  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={editing ? `Edit ${noun}` : `New ${noun}`}
      description={
        editing
          ? `Changes are saved to the repository record. The stage you are filling in stays open.`
          : `This ${noun} is saved to the repository and selected here. The stage you are filling in stays open.`
      }
      /*
        NOT dismissible on a stray backdrop click. The form inside holds real typing — an Aadhaar
        number read off a card, a location captured at the place — and losing it to a misplaced
        click beside the panel is the one failure this dialog must not have. The close control and
        Escape both still work.
      */
      dismissOnBackdrop={false}
      surfaceClassName="max-w-4xl"
    >
      {/*
        Each form is mounted with NO `initial`, so it is in create mode, and with `onCreated`, which
        is what stops it routing to its own list page and abandoning the stage behind this dialog.
      */}
      {loadError ? (
        <p className="rounded-md border border-error-500/30 bg-error-50 px-3 py-2 text-sm text-error-700">{loadError}</p>
      ) : null}

      {editing && !initial && !loadError ? (
        <p className="px-1 py-6 text-sm text-ink-500">Opening this {noun}…</p>
      ) : null}

      {/*
        The form is only mounted once the record is IN HAND for an edit. Mounting it earlier and
        letting `initial` arrive later would leave the designer typing into boxes that are about to
        be overwritten by the fetch.
      */}
      {(!editing || initial) && !loadError ? (
        <>
      {model === "Artisan" ? <ArtisanForm initial={(initial as Artisan) ?? undefined} onCreated={finish} /> : null}
      {model === "ProductDocumentation" ? (
        <ProductForm initial={(initial as ProductDocumentation) ?? undefined} onCreated={finish} />
      ) : null}
      {model === "ToolDocumentation" ? (
        <ToolForm initial={(initial as ToolDocumentation) ?? undefined} onCreated={finish} />
      ) : null}
      {model === "Process" ? (
        /*
          `onCreated` LIKE ITS THREE SIBLINGS, which it did not used to have.
          `ProcessForm` takes `onDone`/`onCancel` as well because it is embedded on its own page too,
          and both are still handed `onClose` for the paths where there is no record to report — the
          designer cancelling, and an offline save that is queued with no server id yet. What is new
          is that a process saved ONLINE now comes back here and gets selected and hydrated, the way
          an artisan, a product and a tool always did. Before this, the button offering to "create
          this as a new process" made one and left the picker empty.
        */
        <ProcessForm
          initial={(initial as ProcessRecord) ?? undefined}
          onDone={onClose}
          onCancel={onClose}
          onCreated={finish}
        />
      ) : null}
        </>
      ) : null}
    </FieldDialog>
  );
}
