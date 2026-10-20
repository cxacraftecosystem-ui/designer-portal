"use client";

/**
 * The top-bar scanner: camera, "Choose a photo" and "Type the code", then `lib/scanDispatch.ts`, then
 * one result card with its button. Opened by `TopBarScanButton` (the mobile/tablet header), drawn
 * inline by `/scan` and by `/search`'s `RecordCodeScanPanel`, so every surface answers a code the
 * same way. It never navigates on its own: the person presses Open, Redeem, Add or Ask to join
 * (REQUIREMENTS_2026-10-20 §6).
 *
 * The camera, the picture and the typed box are `WorkshopCodeScanner`'s, the one code-reading
 * control in this app (`e2e/qr-surfaces-unit.spec.ts`): `BarcodeDetector` where the browser has it,
 * the bundled decoder (`lib/qrDecode.ts`) where it does not. Here it hands every decoded string to
 * the dispatcher through `takeRaw`, and takes its labels from `SCAN_COPY` so the words match the
 * Field Repository's and Android's. Nothing scanned is stored or written into the typed box.
 */

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { QrCode } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { WorkshopCodeScanner, type ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SCAN_COPY, dispatchScan, type ScanOutcome } from "@/lib/scanDispatch";
import { runScanAction, scanServices } from "@/lib/scanServices";

const POSITIVE = new Set(["OPEN", "REDEEM_JOIN_CARD", "JOIN_SESSION", "ADD_FROM_PEER", "JOIN_WORKSHOP", "POINTER"]);

/** Never called: `takeRaw` answers every route before the scanner would resolve a reference. */
const UNUSED_RESOLVE = async (): Promise<ScanResolution> => ({ ok: false, message: "" });

/** The shared words; the heading is the dialog's or the page's, so the scanner draws none of its own. */
const TOP_BAR_LABELS = {
  heading: "",
  camera: SCAN_COPY.useCamera,
  stopCamera: SCAN_COPY.stopCamera,
  photo: SCAN_COPY.choosePhoto,
  photoPhrase: SCAN_COPY.choosePhoto.toLowerCase(),
  typed: SCAN_COPY.typeCode
};

/** The dispatcher's answer, with its one button. */
export function ScanOutcomeCard({
  outcome,
  busy,
  onPress
}: {
  outcome: ScanOutcome;
  busy: boolean;
  onPress: (outcome: ScanOutcome) => void;
}) {
  const positive = POSITIVE.has(outcome.kind);
  return (
    <div
      data-scan-outcome={outcome.kind}
      className={
        positive
          ? "panel grid gap-3 p-4"
          : "grid gap-3 rounded-lg border border-amber-500 bg-amber-100 p-4 text-amber-800"
      }
    >
      <div className="min-w-0">
        <p className={positive ? "text-sm font-semibold text-ink-900" : "text-sm font-medium"}>{outcome.title}</p>
        {outcome.detail ? <p className="mt-1 text-xs text-ink-muted">{outcome.detail}</p> : null}
      </div>
      {outcome.action ? (
        <ActionGroup fill>
          <button type="button" className="field-button" onClick={() => onPress(outcome)} disabled={busy}>
            {outcome.action.label}
          </button>
        </ActionGroup>
      ) : null}
    </div>
  );
}

/**
 * The scanner's body, for the dialog or a page. `onDone` runs when the person leaves for a result;
 * `heading` titles it where nothing around it does (`/search`).
 */
export function ScanPanel({ onDone, heading = "" }: { onDone?: () => void; heading?: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<ScanOutcome | null>(null);

  const takeRaw = useCallback(async (raw: string) => {
    setOutcome(null);
    setOutcome(await dispatchScan(raw, scanServices()));
  }, []);

  const press = useCallback(
    async (current: ScanOutcome) => {
      if (!current.action) return;
      setBusy(true);
      try {
        const next = await runScanAction(current.action);
        if ("navigate" in next) {
          if (next.external) {
            window.open(next.navigate, "_blank", "noopener,noreferrer");
          } else {
            onDone?.();
            router.push(next.navigate);
          }
          return;
        }
        setOutcome(next.outcome);
      } finally {
        setBusy(false);
      }
    },
    [onDone, router]
  );

  return (
    <div className="grid gap-3" data-testid="scan-panel">
      <WorkshopCodeScanner resolve={UNUSED_RESOLVE} takeRaw={takeRaw} labels={{ ...TOP_BAR_LABELS, heading }} />
      <div role="status" aria-live="polite">
        {outcome ? <ScanOutcomeCard outcome={outcome} busy={busy} onPress={(o) => void press(o)} /> : null}
      </div>
    </div>
  );
}

/** The dialog the top bar's scan button opens. */
export function ScanSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  return (
    <FieldDialog
      open={open}
      onClose={onClose}
      title={SCAN_COPY.scanACode}
      icon={<QrCode className="h-5 w-5" aria-hidden />}
      className="max-w-lg"
    >
      {open ? <ScanPanel onDone={onClose} /> : null}
    </FieldDialog>
  );
}
