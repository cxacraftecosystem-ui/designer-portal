"use client";

import { useCallback, useState } from "react";
import { useRouter } from "next/navigation";
import { QrCode } from "lucide-react";

import { FieldDialog } from "@/components/dialogs/FieldDialog";
import { WorkshopCodeScanner, type ScanResolution } from "@/components/designworkshop/WorkshopCodeScanner";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { SCAN_COPY, dispatchScan, type ScanOutcome } from "@/lib/scanDispatch";
import { runScanAction, scanServices } from "@/lib/scanServices";

const POSITIVE = new Set(["OPEN", "REDEEM_JOIN_CARD", "JOIN_SESSION", "ADD_FROM_PEER", "JOIN_WORKSHOP", "POINTER"]);

const UNUSED_RESOLVE = async (): Promise<ScanResolution> => ({ ok: false, message: "" });

const TOP_BAR_LABELS = {
  heading: "",
  camera: SCAN_COPY.useCamera,
  stopCamera: SCAN_COPY.stopCamera,
  photo: SCAN_COPY.choosePhoto,
  photoPhrase: SCAN_COPY.choosePhoto.toLowerCase(),
  typed: SCAN_COPY.typeCode
};

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
