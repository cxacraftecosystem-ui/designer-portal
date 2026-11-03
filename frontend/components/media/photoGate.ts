import {
  BLUR_VARIANCE_FLOOR,
  MIN_CONTRAST_STDDEV,
  MIN_LONG_EDGE_PX,
  isBlurred,
  isUnderResolution,
  type ImageMeasurement,
  type QualityFlag,
  type QualitySeverity
} from "@/lib/imageQuality";

export type GateFault = {
  kind: "BLUR" | "LOW_RESOLUTION" | "EXACT_DUPLICATE" | "NEAR_DUPLICATE";
  flag: QualityFlag;
  severity: QualitySeverity;
  message: string;
};

export function faultRefuses(fault: GateFault): boolean {
  return fault.kind !== "NEAR_DUPLICATE";
}

export const nearDuplicateIsNeverRefused = true;

export type GateAttached = {
  label: string;
  checksum?: string | null;
};

export type GateVerdict = {
  admitted: boolean;
  faults: GateFault[];
};

export function gatePhotograph({
  measurement,
  checksum,
  attached
}: {
  measurement: ImageMeasurement;
  checksum?: string | null;
  attached?: GateAttached[];
}): GateVerdict {
  const faults: GateFault[] = [];

  if (isBlurred(measurement)) {
    faults.push({
      kind: "BLUR",
      flag: "BLUR",
      severity: "MEDIUM",
      message:
        `the sharpness reading was ${Math.round(measurement.blurScore)} against a floor of ` +
        `${BLUR_VARIANCE_FLOOR}, so it is out of focus or the camera moved. Hold still, tap the ` +
        `subject to focus, and take it again.`
    });
  }

  if (isUnderResolution(measurement)) {
    faults.push({
      kind: "LOW_RESOLUTION",
      flag: "LOW_RESOLUTION",
      severity: "MEDIUM",
      message:
        `it is ${measurement.width}x${measurement.height}, and a report plate needs about ` +
        `${MIN_LONG_EDGE_PX}px on the long edge. Raise the camera's resolution, or send the ` +
        `original rather than a copy something has already shrunk.`
    });
  }

  const identical = checksum ? (attached ?? []).filter((item) => item.checksum && item.checksum === checksum) : [];
  if (identical.length) {
    faults.push({
      kind: "EXACT_DUPLICATE",
      flag: "DUPLICATE",
      severity: "LOW",
      message:
        `the identical file is already attached here as ` +
        `${identical.map((item) => `"${item.label}"`).join(", ")}. Nothing is lost by leaving this ` +
        `copy out.`
    });
  }

  return { admitted: !faults.some(faultRefuses), faults };
}

export function gateScopeSentence(): string {
  return (
    "Each photograph is checked on this device before it uploads — for focus, for resolution, and " +
    "for being the identical file twice. Exposure and subject are not checked; judge those by eye."
  );
}

export function gateRefusalSentence(refused: Array<{ name: string; faults: GateFault[] }>): string | null {
  if (!refused.length) return null;
  const clauses = refused.map((entry) => {
    const reasons = entry.faults
      .filter(faultRefuses)
      .map((fault) => fault.message)
      .join(" And ");
    return `${entry.name} — ${reasons}`;
  });
  const noun = refused.length === 1 ? "photograph was" : "photographs were";
  return (
    `${refused.length} ${noun} not uploaded. ${clauses.join(" ")} ` +
    `Nothing was sent, so take ${refused.length === 1 ? "it" : "them"} again and attach ` +
    `${refused.length === 1 ? "it" : "them"} — this list stays until you do.`
  );
}

export function declaredMinItems(field: { key: string; minItems?: unknown }): number | null {
  const declared = field.minItems;
  return typeof declared === "number" && declared > 0 ? declared : null;
}

export type GalleryCounts = {
  held: number;
  onDevice: number;
  uploading: number;
  screening: number;
};

export type GalleryProgress = {
  held: number;
  floor: number;
  percent: number;
  readout: string;
  words: string;
  complete: boolean;
};

export function galleryProgress({ counts, floor }: { counts: GalleryCounts; floor: number }): GalleryProgress {
  const held = Math.max(0, counts.held);
  const safeFloor = Math.max(1, floor);
  const percent = Math.max(0, Math.min(100, Math.round((held / safeFloor) * 100)));
  const readout = `${held} of ${floor}`;
  const remaining = Math.max(0, floor - held);

  const clauses: string[] = [];
  if (remaining === 0) {
    clauses.push(`All ${floor} photographs are attached.`);
  } else if (held === 0) {
    clauses.push(`None of the ${floor} photographs this gallery needs are attached yet.`);
  } else {
    clauses.push(
      `${readout} photographs are attached. ${remaining} more ` +
        `${remaining === 1 ? "is" : "are"} needed.`
    );
  }
  if (counts.screening > 0) {
    clauses.push(
      `${counts.screening} ${counts.screening === 1 ? "is" : "are"} being checked before ` +
        `${counts.screening === 1 ? "it uploads" : "they upload"}.`
    );
  }
  if (counts.uploading > 0) {
    clauses.push(
      `${counts.uploading} more ${counts.uploading === 1 ? "is" : "are"} uploading and ` +
        `${counts.uploading === 1 ? "is" : "are"} not counted yet.`
    );
  }
  if (counts.onDevice > 0) {
    clauses.push(
      `${counts.onDevice} of the ${held} ${counts.onDevice === 1 ? "is" : "are"} on this device ` +
        `only until the connection returns.`
    );
  }

  return { held, floor, percent, readout, words: clauses.join(" "), complete: remaining === 0 };
}

export function galleryFloorSentence({ floor, label }: { floor: number; label: string }): string {
  return (
    `All ${floor} are required. ${label} still saves with fewer — nothing you attach is ever at ` +
    `risk — but until it holds ${floor} the stage is scored incomplete, and the generated report ` +
    `says so. Attached is not saved: the count reaches the workshop when you save the stage.`
  );
}

export type CapturedFinding = {
  mediaId: string;
  fileName: string;
  flag: QualityFlag;
  severity: QualitySeverity;
  note: string;
  raisedAt: string;
};

export const AUTO_FILLABLE_FLAGS: readonly QualityFlag[] = ["BLUR", "LOW_RESOLUTION", "DUPLICATE"];

export type MediaQualityFlagRow = {
  mediaId: string;
  flag: QualityFlag;
  severity: QualitySeverity;
  autoDetected: true;
  note: string;
};

export function mediaQualityFlagRows(findings: CapturedFinding[]): MediaQualityFlagRow[] {
  const byKey = new Map<string, MediaQualityFlagRow>();
  for (const finding of findings) {
    if (!AUTO_FILLABLE_FLAGS.includes(finding.flag)) continue;
    byKey.set(`${finding.mediaId}:${finding.flag}`, {
      mediaId: finding.mediaId,
      flag: finding.flag,
      severity: finding.severity,
      autoDetected: true,
      note: `${finding.fileName}: ${finding.note}`
    });
  }
  return [...byKey.values()];
}
