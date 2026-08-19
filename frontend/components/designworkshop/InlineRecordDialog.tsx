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
import type { InlineHostSeed, UseExistingArtisan } from "@/components/forms/inlineRecordHost";
import type { Artisan, ArtisanIdentityMatch, ProductDocumentation, ToolDocumentation } from "@/lib/types";

/**
 * The reference models a stage field can point at and this dialog can create.
 *
 * `Dw…` models are NOT here and cannot be: a `DwSketch` or a `DwPrototype` is another ROW of the
 * same workshop, created by adding a row to its own stage, not a repository record. Offering to
 * "create" one from a picker would put a second, parallel way to add a prototype into the app.
 *
 * ── CRAFT IS ABSENT, AND THAT IS A DECISION RATHER THAN AN OVERSIGHT ──────────────────────────
 * `Craft` is a genuine repository model with its own `ReferenceModel`, and stage 1 really does
 * declare `workshopSetup.craftRef` against it — so the omission looked like a gap until somebody
 * asked what a per-workshop craft create would DO.
 *
 * A craft is not a record of something a designer observed. It is a row of a SHARED TAXONOMY, about
 * 178 of them for the whole repository, and everything else joins to it: an artisan's craft, a
 * product's craft, the workshop scope filters, the map's rollups, the dataset export. A picker that
 * mints one is a picker that mints a NEAR-DUPLICATE — "Bagru Block Printing" beside "Bagru block
 * print" beside "Block Printing (Bagru)" — created in the field, by somebody who could not see the
 * existing row because they were searching for a different spelling of it, and never merged
 * afterwards because nothing anywhere says two crafts are the same craft. Every one of those splits
 * a corpus in half along a line no report will ever admit to.
 *
 * The other four are the opposite case: an artisan, a product, a tool and a process are things a
 * designer met in a room, they belong to whoever documented them, and a duplicate is a nuisance
 * rather than a fracture in the taxonomy.
 *
 * WHAT A DESIGNER GETS INSTEAD. `craftRef` is OPTIONAL and stage 1's `craftName` is a free-text box
 * they can simply type, so nobody is blocked; and the picker offers a link to /crafts so the one
 * remedy that is right — curate the taxonomy where the whole taxonomy is visible — is one click
 * away rather than something to be remembered. See `StageReferenceSelect`'s craft branch.
 *
 * IF THIS IS EVER REVERSED, a `CraftForm` has to be extracted first (the craft form is inline on
 * `app/(protected)/crafts/page.tsx` and there is nothing here to mount), it must go through
 * `onCreated`/`adoptCreated` like the other four so `workshopSetup.craftRef`'s hydration runs, and
 * `INLINE_CREATABLE` in Android's `DwReferenceField.kt` has to gain it in the same change or the
 * two surfaces disagree about which pickers can create.
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
  seed,
  onClose,
  onCreated,
  onQueued,
  onUseExisting
}: {
  open: boolean;
  model: InlineCreatableModel;
  /**
   * What the picker that opened this dialog already knows about the record being made.
   *
   * ── THE DEFECT THIS EXISTS FOR ──────────────────────────────────────────────────────────────
   * This dialog used to take exactly `{ open, model, recordId, onClose, onCreated }` and pass the
   * forms nothing, even though the picker rendering it was holding the row's artisan and the
   * workshop the whole time. The full-page routes seed the same boxes from the query string; a
   * dialog has none, so the only thing that filled them was the carry bag — the LAST artisan this
   * designer documented anywhere. At stage 6 a designer picks Kamla on the row, presses
   * 'Create "Sambalpuri saree" as a new product', and the product is filed under whoever was last
   * in the bag, or under nobody. The server narrows this very picker on that column, so the record
   * is then invisible in the control that created it and `describeCreated` cannot describe it —
   * two blank required boxes and a 422 on submit, seconds after the record holding both answers
   * was made. The obvious next move is to create it a second time.
   *
   * Every value in it lands in a control the designer can see and change; see {@link InlineHostSeed}.
   */
  seed?: InlineHostSeed;
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
  /**
   * The save was banked in the offline outbox: no record, no server id, nothing to link.
   *
   * The dialog closes on it — the designer is done here either way — and the caller says so where
   * they are looking. It cannot be folded into `onCreated`: a REF field must hold a real server id
   * (`hydrate_entries`, `canonical_divergence` and the report's `ReferencedRecord` join all resolve
   * on it), so a placeholder would render for ever as a reference to a deleted record. The row is
   * left unlinked and the picker says why.
   */
  onQueued?: () => void;
  /**
   * The artisan the duplicate check found already in the repository — {@link UseExistingArtisan}.
   *
   * Only meaningful for `Artisan`. Absent, the form keeps its page behaviour and navigates to the
   * existing record's edit route, which is right on `/artisans/new` and loses the stage here.
   */
  onUseExisting?: UseExistingArtisan;
}) {
  const noun = INLINE_MODEL_NOUN[model];
  const editing = Boolean(recordId);

  /**
   * THE SEED IS CREATE-ONLY, AND THIS IS WHERE THAT IS ENFORCED.
   *
   * The rule is stated on {@link InlineRecordDialog}'s `seed` prop above — seeding a parent over an
   * existing record would rewrite a link nobody touched — and it was enforced NOWHERE IN THIS FILE:
   * all three forms were handed `seed` unconditionally, and the only gate in the tree was one call
   * site's `seed={inlineDialog.mode === "create" ? seed : undefined}` in `StageReferenceSelect`.
   * The multipicker beside it passes a bare `seed={seed}` and is harmless only because it never
   * passes a `recordId`; the day it grows one — or any other host opens this dialog on a record —
   * the rule would be gone with no line of code having changed.
   *
   * THE FORMS CANNOT BE THE GUARD, which is why it has to be this line. They resolve the seed with
   * `??`, not with an is-edit test: `initial?.artisanId ?? seed?.artisanId` and
   * `initialWorkshopId: initial?.workshopId ?? seed?.workshopId`. On a record whose artisan or
   * workshop column is NULL — exactly the row this lane exists to let a designer fix without
   * abandoning the stage — `??` falls straight through to the seed, the form submits a parent the
   * designer never chose, and `useWorkshopSelection` marks it `touched` so the carry banner does
   * not mention it either.
   *
   * So the ternary at the single picker and the bare prop at the multipicker are now belt and
   * braces over this, rather than the whole of it.
   */
  const seedForForm = editing ? undefined : seed;

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

  /**
   * A save that went into the outbox instead of onto the wire.
   *
   * Closing is the same act as `finish` — the designer is done with this form either way — but
   * there is no record, so the picker is told separately and says so out loud. Before this the
   * queued branch of all four forms simply returned: the button flipped back from "Saving…" to
   * "Save artisan", the dialog stayed open, the row stayed unlinked, and the one thing that would
   * have explained it (`OutboxBanner`) was behind this dialog's own overlay on a body whose scroll
   * this dialog had locked.
   */
  const reportQueued = useCallback(() => {
    onQueued?.();
    onClose();
  }, [onQueued, onClose]);

  /**
   * "Open the existing record" from the duplicate prompt, answered without leaving the stage.
   *
   * Handed to `ArtisanForm` only when the caller offered somewhere to hand it; on its own page the
   * form still routes, which is right there. See {@link UseExistingArtisan} for why nothing but the
   * id and the name may cross this boundary.
   */
  const adoptExisting = useCallback(
    (artisan: ArtisanIdentityMatch) => {
      onUseExisting?.(artisan);
      onClose();
    },
    [onUseExisting, onClose]
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
        Each form is mounted with NO `initial`, so it is in create mode, and with the four host
        callbacks that stop it navigating — `onCreated` for a save, `onCancel` for the button
        designers actually press to back out, `onQueued` for a save that went to the outbox, and (on
        the artisan) `onUseExisting` for the duplicate prompt. All four used to end in `router.back()`
        or `router.push()`, and this dialog is not a route: every one of them popped the real history
        entry and abandoned the stage the designer was standing in. See `forms/inlineRecordHost`.
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
      {model === "Artisan" ? (
        <ArtisanForm
          initial={(initial as Artisan) ?? undefined}
          seed={seedForForm}
          onCreated={finish}
          onCancel={onClose}
          onQueued={reportQueued}
          onUseExisting={onUseExisting ? adoptExisting : undefined}
        />
      ) : null}
      {model === "ProductDocumentation" ? (
        <ProductForm
          initial={(initial as ProductDocumentation) ?? undefined}
          seed={seedForForm}
          onCreated={finish}
          onCancel={onClose}
          onQueued={reportQueued}
        />
      ) : null}
      {model === "ToolDocumentation" ? (
        <ToolForm
          initial={(initial as ToolDocumentation) ?? undefined}
          seed={seedForForm}
          onCreated={finish}
          onCancel={onClose}
          onQueued={reportQueued}
        />
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
          onQueued={reportQueued}
        />
      ) : null}
        </>
      ) : null}
    </FieldDialog>
  );
}
