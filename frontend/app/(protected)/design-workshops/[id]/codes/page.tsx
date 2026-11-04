"use client";

import { use, useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Printer, QrCode } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { ResolvedRecordRow } from "@/components/RecordCodeScanPanel";
import { JoinCardsPanel } from "@/components/designworkshop/JoinCardsPanel";
import { WorkshopCodeScanner, type ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import { WorkshopCodeSheet, type WorkshopCodeCard } from "@/components/designworkshop/WorkshopCodeSheet";
import { ArtisanCardSheetActions, useArtisanCardsEnabled } from "@/components/artisans/ArtisanCardSheetActions";
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

const PROTOTYPE_MODEL = "DwPrototype";

type Located = { stage: DwStage; entity: DwEntity } | null;

function findPrototypeEntity(registry: DwRegistry): Located {
  for (const stage of registry.stages) {
    for (const entity of stage.entities) {
      if (entity.cardinality === "COLLECTION" && entity.name === PROTOTYPE_MODEL) return { stage, entity };
    }
  }
  return null;
}

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

function artisanRefKey(entity: DwEntity): string | null {
  return entity.fields.find((field) => field.type === "REF" && field.refModel === "Artisan")?.key ?? null;
}

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
            ? `Couldn't load the stages: ${err.message}`
            : "Couldn't load the stages."
        );
        return;
      }

      const local = isLocalWorkshopId(id) ? await loadDraft(id) : await ensureDraft(id);
      if (cancelled) return;
      if (local) setDraft(local);

      if (isLocalWorkshopId(id) && local && !local.remoteId) return;
      try {
        const detail = await getDesignWorkshop(local?.remoteId ?? id);
        if (cancelled) return;
        const merged = await adoptServerDetail(detail, loadedRegistry);
        if (cancelled) return;
        setDraft(merged ?? local);
      } catch (err) {
        if (cancelled) return;
        if (isUnreachable(err)) {
          setOffline(true);
          if (!local) setError("You're offline and this workshop isn't on this device.");
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

  const cardsEnabled = useArtisanCardsEnabled();

  const cards: WorkshopCodeCard[] = useMemo(() => {
    if (kind === "prototype") {
      if (!prototypeEntity) return [];
      return prototypeRows.map((row, index) => ({
        key: row._entryId ?? row._clientKey ?? `row-${index}`,
        recordType: "prototype" as const,
        id: workshopCodeIdForRow(row),
        title: rowTitle(prototypeEntity.entity, row, index),
        lines: [inputValue(row.prototypeCode), listValue(row.materials).join(", ")].filter(Boolean)
      }));
    }
    if (cardsEnabled || !rosterEntity) return [];
    const refKey = artisanRefKey(rosterEntity.entity);
    return rosterRows.map((row, index) => ({
      key: row._entryId ?? row._clientKey ?? `row-${index}`,
      recordType: "artisan" as const,
      id: refKey ? inputValue(row[refKey]) : null,
      title: rowTitle(rosterEntity.entity, row, index),
      lines: [inputValue(row.serialNo) ? `S. No. ${inputValue(row.serialNo)}` : "", inputValue(row.village)].filter(Boolean)
    }));
  }, [kind, prototypeEntity, prototypeRows, cardsEnabled, rosterEntity, rosterRows]);

  const resolve = useCallback(
    async (ref: WorkshopCodeRef): Promise<ScanResolution> => {
      setOpened(null);

      if (ref.recordType === "prototype") {
        const index = prototypeRows.findIndex((row) => workshopCodeMatchesRow(ref, row));
        if (index < 0 || !prototypeEntity) return { ok: false, message: unresolvedWorkshopCodeMessage("prototype") };
        return {
          ok: true,
          label: rowTitle(prototypeEntity.entity, prototypeRows[index], index),
          detail: `${prototypeEntity.stage.title} · entry ${index + 1}${
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
            ?
              `${answer.hit.detail || "In the records"} · not on this workshop's roster`
            : answer.hit.detail
      };
    },
    [prototypeEntity, prototypeRows, rosterEntity, rosterRows]
  );

  const registryUnderstood = kind === "prototype" ? prototypeEntity !== null : rosterEntity !== null;
  const serverWorkshopId = isLocalWorkshopId(id) ? draft?.remoteId ?? null : id;

  return (
    <>
      <PageHeader
        title="Cards & tags"
        description="Join cards, artisan cards and prototype tags."
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
        <div className="mb-4 rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm text-amber-800">
          Offline — showing what&rsquo;s on this device.
        </div>
      ) : null}

      <div className="mb-5">
        <JoinCardsPanel
          workshopId={draft?.remoteId ?? id}
          workshopTitle={draft?.header.title || "Design workshop"}
          shareable={!isLocalWorkshopId(id) || Boolean(draft?.remoteId)}
        />
      </div>

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
        description="Scan any card or tag printed by this app."
      />
      {opened ? (
        <div className="mt-3">
          <ResolvedRecordRow recordType={opened.ref.recordType} hit={opened.hit} />
        </div>
      ) : null}

      <div className="mt-5">
        {registry === null || draft === null ? (
          <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">Loading this workshop…</p>
        ) : !registryUnderstood ? (
          <p className="rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            {kind === "prototype" ? "Prototype tags" : "Artisan cards"} can&rsquo;t be printed. Reload to update the app.
          </p>
        ) : kind === "artisan" && cardsEnabled ? (
          <div className="panel grid gap-3 p-4">
            <p className="text-sm text-ink-700">
              {rosterRows.length
                ? `${rosterRows.length} on the roster.`
                : "No artisans on the roster yet."}
            </p>
            {serverWorkshopId ? (
              <ArtisanCardSheetActions
                source={rosterRows.length ? { designWorkshopId: serverWorkshopId } : null}
                primaryLabel="Download artisan cards (PDF)"
              />
            ) : (
              <p className="text-sm text-ink-muted">Sync this workshop to print its artisan cards.</p>
            )}
          </div>
        ) : (
          <WorkshopCodeSheet
            recordType={kind}
            cards={cards}
            emptyMessage={
              kind === "prototype"
                ? "No prototypes yet."
                : "No artisans on the roster yet."
            }
          />
        )}
      </div>
    </>
  );
}
