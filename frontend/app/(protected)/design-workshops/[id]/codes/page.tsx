"use client";

/**
 * Cards & tags: print a code for every artisan on the roster and every prototype in the workshop,
 * and scan one back.
 *
 * THE PROBLEM THIS PAGE REMOVES. Stage 14 asks a designer to record an iteration "against a
 * prototype", stage 15 to validate one, stage 16 to document one — three stages, over a fortnight,
 * each of which begins by choosing a prototype from a list of twenty-five. Choose the wrong one and
 * two days of measurements attach to somebody else's work; nothing downstream can tell, because
 * both rows look complete. A tag tied to the object removes the choosing.
 *
 * IT READS THE REGISTRY, NOT A HARD-CODED STAGE NUMBER. Which entity holds prototypes is answered
 * by `entity.name === "DwPrototype"` (the Prisma model the registry declares) and which holds the
 * roster by "the collection with a REF field pointing at `Artisan`". Both are properties the
 * registry already publishes, so renaming stage 13, renumbering it, or moving the roster to another
 * stage changes nothing here — which is the whole reason the registry is served rather than
 * duplicated. When neither is found the page says so in a sentence rather than rendering an empty
 * sheet, because "this workshop has no prototypes yet" and "this app no longer understands the
 * registry" are different problems with different fixes.
 *
 * IT RUNS OFF THE LOCAL DRAFT. Same rule as the stage index: `lib/designWorkshopStore` holds the
 * whole workshop in IndexedDB, so a designer standing in a courtyard with no signal can print tags
 * for prototypes made this morning. The one place a network is even attempted is an artisan code
 * that is not on this workshop's roster, and the page says what it did and did not manage to check.
 *
 * IT PRINTS FROM THE BROWSER AND NOTHING ELSE. No server render, no report block. A QR on a card is
 * not a report feature and inventing a block kind for it would break the five-renderer rule — a
 * block only the browser understood would vanish from the .docx and the two PDFs. The sheet is
 * `window.print()` over `components/designworkshop/WorkshopCodeSheet.tsx`, which is the same
 * mechanism the report preview already offers and works with no backend reachable at all.
 */

import { use, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Printer, QrCode } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { ResolvedRecordRow } from "@/components/RecordCodeScanPanel";
import { WorkshopCodeScanner, type ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import { WorkshopCodeSheet, type WorkshopCodeCard } from "@/components/designworkshop/WorkshopCodeSheet";
import {
  getDesignWorkshop,
  inputValue,
  listValue,
  rowTitle,
  type DwEntity,
  type DwRegistry,
  type DwRow,
  type DwStage
} from "@/lib/designWorkshops";
import {
  adoptServerDetail,
  ensureDraft,
  isLocalWorkshopId,
  loadDraft,
  loadRegistry,
  stageDataOf,
  type DwDraft
} from "@/lib/designWorkshopStore";
import { isUnreachable } from "@/lib/offline";
import { lookUpWorkshopCode, workshopCodeOpenHref, type WorkshopCodeHit } from "@/lib/workshopCodeLookup";
import {
  unresolvedWorkshopCodeMessage,
  workshopCodeIdForRow,
  workshopCodeMatchesRow,
  type WorkshopCodeRef,
  type WorkshopRecordType
} from "@/lib/workshopCodes";

/** The Prisma model whose rows get a tag. Declared by the registry; matched, never assumed. */
const PROTOTYPE_MODEL = "DwPrototype";

type Located = { stage: DwStage; entity: DwEntity } | null;

/** The collection entity whose rows are prototypes, wherever the registry has put it. */
function findPrototypeEntity(registry: DwRegistry): Located {
  for (const stage of registry.stages) {
    for (const entity of stage.entities) {
      if (entity.cardinality === "COLLECTION" && entity.name === PROTOTYPE_MODEL) return { stage, entity };
    }
  }
  return null;
}

/**
 * The roster: the one collection that carries a REF to an `Artisan`.
 *
 * Identified by that reference rather than by name because it is the only thing that makes the row
 * addressable at all — a roster row typed in by hand has no artisan id behind it, and a card
 * printed from one would point at nothing. The registry's comment on that field says the same in
 * the other direction: the picker exists so that a cluster's second workshop can be compared with
 * its first.
 */
function findRosterEntity(registry: DwRegistry): Located {
  for (const stage of registry.stages) {
    for (const entity of stage.entities) {
      if (entity.cardinality !== "COLLECTION") continue;
      const reference = entity.fields.find((field) => field.type === "REF" && field.refModel === "Artisan");
      if (reference) return { stage, entity };
    }
  }
  return null;
}

/** The key of the field on a roster row that holds the artisan's repository id. */
function artisanRefKey(entity: DwEntity): string | null {
  return entity.fields.find((field) => field.type === "REF" && field.refModel === "Artisan")?.key ?? null;
}

/** Rows of one entity as the local draft holds them. */
function rowsOf(draft: DwDraft | null, located: Located): DwRow[] {
  if (!draft || !located) return [];
  return stageDataOf(draft.stages[located.stage.key]).collections[located.entity.key] ?? [];
}

export default function WorkshopCodesPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  const [registry, setRegistry] = useState<DwRegistry | null>(null);
  const [draft, setDraft] = useState<DwDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [offline, setOffline] = useState(false);
  const [kind, setKind] = useState<WorkshopRecordType>("prototype");
  /** The last scanned code that resolved to a record with a page of its own, and where that page is. */
  const [opened, setOpened] = useState<{ ref: WorkshopCodeRef; hit: WorkshopCodeHit } | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      let loadedRegistry: DwRegistry | null = null;
      try {
        const loaded = await loadRegistry();
        if (cancelled) return;
        loadedRegistry = loaded.registry;
        setRegistry(loaded.registry);
      } catch (err) {
        if (cancelled) return;
        setError(
          err instanceof Error
            ? `The list of stages could not be loaded and this browser has no saved copy of it: ${err.message}`
            : "The list of stages could not be loaded and this browser has no saved copy of it."
        );
        return;
      }

      // The local copy first: a prototype made this morning in a village has never been to the
      // server, and it is exactly the row that needs a tag today.
      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      // …AND THEN THE SERVER'S, which `ensureDraft` alone does NOT bring down: for a workshop this
      // browser has never opened it writes an EMPTY shell and returns it. Without the fold below,
      // opening this page first on a new laptop rendered "no prototypes have been recorded yet"
      // over a workshop holding twenty-five — the silent-emptiness failure this repository keeps
      // hitting, and here it would have a designer conclude the tags cannot be printed at all.
      if (isLocalWorkshopId(id) && local && !local.remoteId) return;
      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
        setDraft(merged ?? local);
      } catch (err) {
        if (cancelled) return;
        // `isUnreachable`, not `isTransient` — the same split the stage index makes. A workshop
        // already drawn from the local copy must not be replaced by an error box.
        if (isUnreachable(err)) {
          setOffline(true);
          if (!local) setError("There is no connection and this browser has no copy of this workshop.");
          return;
        }
        setError(err instanceof Error ? err.message : "Unable to load this design workshop");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id]);

  const prototypeEntity = useMemo(() => (registry ? findPrototypeEntity(registry) : null), [registry]);
  const rosterEntity = useMemo(() => (registry ? findRosterEntity(registry) : null), [registry]);

  const prototypeRows = useMemo(() => rowsOf(draft, prototypeEntity), [draft, prototypeEntity]);
  const rosterRows = useMemo(() => rowsOf(draft, rosterEntity), [draft, rosterEntity]);

  const cards: WorkshopCodeCard[] = useMemo(() => {
    if (kind === "prototype") {
      if (!prototypeEntity) return [];
      return prototypeRows.map((row, index) => ({
        key: row._entryId ?? row._clientKey ?? `row-${index}`,
        recordType: "prototype" as const,
        id: workshopCodeIdForRow(row),
        title: rowTitle(prototypeEntity.entity, row, index),
        // The designer's own prototype code, when they gave one — it is what is written on the
        // object already, and a tag that agrees with the object is a tag somebody trusts.
        //
        // `materials` is a TAGS field and therefore an ARRAY, so it goes through `listValue`.
        // `inputValue` returns "" for anything array-shaped, which the `.filter(Boolean)` below
        // would then drop — a line that silently never printed and looked in the source as though
        // it did.
        lines: [inputValue(row.prototypeCode), listValue(row.materials).join(", ")].filter(Boolean)
      }));
    }
    if (!rosterEntity) return [];
    const refKey = artisanRefKey(rosterEntity.entity);
    return rosterRows.map((row, index) => ({
      key: row._entryId ?? row._clientKey ?? `row-${index}`,
      recordType: "artisan" as const,
      id: refKey ? inputValue(row[refKey]) : null,
      title: rowTitle(rosterEntity.entity, row, index),
      // Serial and village, and nothing else. Not the artisan card number, not the phone: a card
      // is a public object once it leaves the room. See WorkshopCodeSheet's header.
      lines: [inputValue(row.serialNo) ? `S. No. ${inputValue(row.serialNo)}` : "", inputValue(row.village)].filter(Boolean)
    }));
  }, [kind, prototypeEntity, prototypeRows, rosterEntity, rosterRows]);

  /**
   * What a scanned code points at.
   *
   * THE LOCAL DRAFT ANSWERS FIRST AND ANSWERS OFFLINE, which is the case that matters: a prototype
   * made this morning in a courtyard is in this browser and nowhere else. Only a code the draft
   * cannot account for reaches the network.
   *
   * IT ANSWERS FOR EVERY RECORD TYPE, not only the two this page PRINTS. A designer standing at this
   * screen with a scanner open will scan whatever is in front of them — the tool on the bench, the
   * product on the table — and "this is a workshop code, but it points at a kind of record this
   * version of the app does not open" would be a lie told by a build that opens it perfectly well.
   * Everything that is not a roster artisan or a prototype goes to `lib/workshopCodeLookup`, whose
   * refusals are deliberately flattened into one sentence: the API answers 404 both for a record
   * that does not exist and for one this designer may not see, and telling the two apart here would
   * hand back exactly the fact it withholds.
   */
  const resolve = useCallback(
    async (ref: WorkshopCodeRef): Promise<ScanResolution> => {
      // Cleared before the work, so the previous scan's "Open" is never sitting under this one's
      // answer one press away from opening the wrong record.
      setOpened(null);

      if (ref.recordType === "prototype") {
        const index = prototypeRows.findIndex((row) => workshopCodeMatchesRow(ref, row));
        if (index < 0 || !prototypeEntity) return { ok: false, message: unresolvedWorkshopCodeMessage("prototype") };
        return {
          ok: true,
          label: rowTitle(prototypeEntity.entity, prototypeRows[index], index),
          detail: `${prototypeEntity.stage.title} · row ${index + 1}${
            inputValue(prototypeRows[index].prototypeCode) ? ` · ${inputValue(prototypeRows[index].prototypeCode)}` : ""
          }`
        };
      }

      if (ref.recordType === "artisan") {
        const refKey = rosterEntity ? artisanRefKey(rosterEntity.entity) : null;
        if (refKey) {
          const index = rosterRows.findIndex((row) => inputValue(row[refKey]) === ref.id);
          if (index >= 0 && rosterEntity) {
            const label = rowTitle(rosterEntity.entity, rosterRows[index], index);
            const detail = `On this workshop's roster · ${rosterEntity.stage.title}`;
            // Answered off the draft with no request, and STILL openable: the href comes from the
            // same table the repository lookup uses, so a roster answer and a network answer send
            // the designer to one place. See `workshopCodeOpenHref`.
            setOpened({ ref, hit: { label, detail, href: workshopCodeOpenHref(ref) } });
            return { ok: true, label, detail };
          }
        }
      }

      const answer = await lookUpWorkshopCode(ref);
      if (!answer.ok) return { ok: false, message: answer.message };
      setOpened({ ref, hit: answer.hit });
      return {
        ok: true,
        label: answer.hit.label,
        detail:
          ref.recordType === "artisan"
            ? // Said out loud, because it changes what the designer should do next: this person is
              // documented but is not in this workshop, so enrolling them is the missing step.
              `${answer.hit.detail || "In the repository"} · not on this workshop's roster`
            : answer.hit.detail
      };
    },
    [prototypeEntity, prototypeRows, rosterEntity, rosterRows]
  );

  const registryUnderstood = kind === "prototype" ? prototypeEntity !== null : rosterEntity !== null;

  return (
    <>
      <PageHeader
        title="Cards & tags"
        description="Printable codes for the artisans on this workshop's roster and for every prototype in it, and a scanner that reads one back. A scan is what stops two days of work being attached to the wrong prototype."
        icon={<QrCode className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <Link href={`/design-workshops/${id}`} className="field-button-secondary">
              Stages
            </Link>
            <button type="button" className="field-button" onClick={() => window.print()} disabled={!cards.length}>
              <Printer className="h-4 w-4" aria-hidden />
              Print
            </button>
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}

      {offline ? (
        // Which copy is on screen is not guessable from the sheet, and it decides whether a
        // missing tag means "nobody has entered that prototype" or "this device has not seen it".
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          There is no connection, so these are the artisans and prototypes saved in this browser. Printing works exactly the
          same; anything recorded on another device is not here yet.
        </div>
      ) : null}

      <div className="mb-5 flex flex-wrap gap-2" role="group" aria-label="What to print">
        {(["prototype", "artisan"] as WorkshopRecordType[]).map((option) => (
          <button
            key={option}
            type="button"
            className={option === kind ? "field-button" : "field-button-secondary"}
            aria-pressed={option === kind}
            onClick={() => setKind(option)}
          >
            {option === "prototype" ? "Prototype tags" : "Artisan cards"}
          </button>
        ))}
      </div>

      <WorkshopCodeScanner
        resolve={resolve}
        description="A tag or card printed by this app, for any kind of record — a prototype or an artisan from this workshop, or a craft, product, process, tool or interview from the repository. Nothing about the record is inside the code: it holds a reference and a check, and nothing else."
      />
      {/* Only for a record that HAS a page. A prototype is a row inside this workshop's draft and has
          no route of its own, so a prototype scan reports and stops there — which is correct: the
          designer is already standing in the workshop the row belongs to. */}
      {opened ? (
        <div className="mt-3">
          <ResolvedRecordRow recordType={opened.ref.recordType} hit={opened.hit} />
        </div>
      ) : null}

      <div className="mt-5">
        {registry === null || draft === null ? (
          <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">Loading this workshop…</p>
        ) : !registryUnderstood ? (
          /* Not the same as "there is nothing to print", and confusing the two would send a
             designer looking for rows they never entered instead of reporting a broken build. */
          <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            This version of the app cannot find {kind === "prototype" ? "the prototype list" : "the artisan roster"} in the
            field registry it was served, so it cannot print{" "}
            {kind === "prototype" ? "prototype tags" : "artisan cards"}. Nothing is missing from your workshop — update the app.
          </p>
        ) : (
          <WorkshopCodeSheet
            recordType={kind}
            cards={cards}
            emptyMessage={
              kind === "prototype"
                ? "No prototypes have been recorded in this workshop yet. Add them at the prototype development stage and the tags appear here."
                : "No artisans are on this workshop's roster yet. Enrol them on the participants stage and their cards appear here."
            }
          />
        )}
      </div>
    </>
  );
}
