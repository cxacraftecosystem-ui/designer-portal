export const WORKSHOP_CODE_NAMESPACE = "DPW";

export const WORKSHOP_CODE_VERSION = 1;

export const SUPPORTED_VERSIONS: ReadonlySet<number> = new Set([1]);

export type WorkshopRecordType =
  | "artisan"
  | "craft"
  | "workshop"
  | "product"
  | "process"
  | "tool"
  | "questionnaire"
  | "media"
  | "designWorkshop"
  | "prototype";

const TYPE_LETTER: Record<WorkshopRecordType, string> = {
  artisan: "A",
  craft: "C",
  workshop: "W",
  product: "D",
  process: "S",
  tool: "T",
  questionnaire: "Q",
  media: "M",
  designWorkshop: "G",
  prototype: "P"
};

const TYPE_FROM_LETTER: Record<string, WorkshopRecordType> = Object.fromEntries(
  Object.entries(TYPE_LETTER).map(([type, letter]) => [letter, type as WorkshopRecordType])
);

export const WORKSHOP_RECORD_TYPES: readonly WorkshopRecordType[] = Object.keys(TYPE_LETTER) as WorkshopRecordType[];

const TYPE_LABEL: Record<WorkshopRecordType, string> = {
  artisan: "Artisan",
  craft: "Craft",
  workshop: "Workshop",
  product: "Product",
  process: "Process",
  tool: "Tool",
  questionnaire: "Interview",
  media: "Media file",
  designWorkshop: "Design workshop",
  prototype: "Prototype"
};

const TYPE_PLURAL: Record<WorkshopRecordType, string> = {
  artisan: "artisans",
  craft: "crafts",
  workshop: "workshops",
  product: "products",
  process: "processes",
  tool: "tools",
  questionnaire: "interviews",
  media: "media files",
  designWorkshop: "design workshops",
  prototype: "prototypes"
};

export function workshopRecordTypeLabel(recordType: WorkshopRecordType): string {
  return TYPE_LABEL[recordType];
}

function knownRecordTypesSentence(): string {
  const words = WORKSHOP_RECORD_TYPES.map((type) => TYPE_PLURAL[type]);
  return `${words.slice(0, -1).join(", ")} and ${words[words.length - 1]}`;
}

export type WorkshopCodeRef = {
  recordType: WorkshopRecordType;
  id: string;
};

const ID_PATTERN = /^[a-z0-9][a-z0-9-]{7,63}$/;

const DEVICE_LOCAL_ID_PREFIX = "dwlocal-";

const ANDROID_DEVICE_LOCAL_ID_PREFIX = "local-";

function isDeviceLocalId(recordType: WorkshopRecordType, id: string): boolean {
  if (id.startsWith(DEVICE_LOCAL_ID_PREFIX)) return true;
  return recordType === "designWorkshop" && id.startsWith(ANDROID_DEVICE_LOCAL_ID_PREFIX);
}

const CHECK_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const CHECK_LENGTH = 4;

const CHECK_CONFUSABLES: Record<string, string> = { I: "1", L: "1", O: "0" };

export function workshopCodeCheck(prefix: string): string {
  let hash = 0x811c9dc5;
  for (let index = 0; index < prefix.length; index++) {
    hash ^= prefix.charCodeAt(index);
    hash = (hash + ((hash << 1) + (hash << 4) + (hash << 7) + (hash << 8) + (hash << 24))) >>> 0;
  }
  const value = hash & 0xfffff;
  let out = "";
  for (let shift = (CHECK_LENGTH - 1) * 5; shift >= 0; shift -= 5) out += CHECK_ALPHABET[(value >>> shift) & 31];
  return out;
}

export type EncodeRefusal =
  | "NO_ID"
  | "UNKNOWN_RECORD_TYPE"
  | "ID_NOT_AN_IDENTIFIER"
  | "ID_LOOKS_SENSITIVE"
  | "ID_IS_DEVICE_LOCAL";

export type EncodeResult =
  | { ok: true; code: string; ref: WorkshopCodeRef }
  | { ok: false; reason: EncodeRefusal; message: string };

export function encodeWorkshopCode(ref: {
  recordType: WorkshopRecordType | string;
  id: string | null | undefined;
}): EncodeResult {
  const letter = TYPE_LETTER[ref.recordType as WorkshopRecordType];
  if (!letter) {
    return {
      ok: false,
      reason: "UNKNOWN_RECORD_TYPE",
      message: `No code can be printed for a “${ref.recordType}” — codes exist for ${knownRecordTypesSentence()}.`
    };
  }

  const id = (ref.id ?? "").trim();
  if (!id) {
    return {
      ok: false,
      reason: "NO_ID",
      message: "Save the record first."
    };
  }

  const stripped = id.replace(/[\s-]/g, "");
  if (/^\d+$/.test(stripped)) {
    return {
      ok: false,
      reason: "ID_LOOKS_SENSITIVE",
      message:
        "Identity numbers can't be printed in a QR code."
    };
  }
  if (/^[A-Z0-9]+$/.test(stripped) && stripped.length <= 20 && /[A-Z]/.test(stripped)) {
    return {
      ok: false,
      reason: "ID_LOOKS_SENSITIVE",
      message:
        "Identity numbers can't be printed in a QR code."
    };
  }

  if (isDeviceLocalId(ref.recordType as WorkshopRecordType, id)) {
    return {
      ok: false,
      reason: "ID_IS_DEVICE_LOCAL",
      message:
        "Not uploaded yet — sync, then print its code."
    };
  }

  if (!ID_PATTERN.test(id)) {
    return {
      ok: false,
      reason: "ID_NOT_AN_IDENTIFIER",
      message:
        "Not a record identifier."
    };
  }

  const prefix = `${WORKSHOP_CODE_NAMESPACE}${WORKSHOP_CODE_VERSION}:${letter}:${id.toUpperCase()}`;
  return { ok: true, code: `${prefix}:${workshopCodeCheck(prefix)}`, ref: { recordType: ref.recordType as WorkshopRecordType, id } };
}

export type DecodeRefusal =
  | "EMPTY"
  | "NOT_A_WORKSHOP_CODE"
  | "NEWER_VERSION"
  | "UNKNOWN_RECORD_TYPE"
  | "MALFORMED"
  | "DEVICE_LOCAL"
  | "CHECK_FAILED";

export type DecodeResult =
  | { ok: true; ref: WorkshopCodeRef; code: string }
  | { ok: false; reason: DecodeRefusal; message: string };

export function decodeWorkshopCode(input: string | null | undefined): DecodeResult {
  const raw = (input ?? "").replace(/\s+/g, "").toUpperCase();
  if (!raw) return { ok: false, reason: "EMPTY", message: "Nothing was scanned or typed." };

  const parts = raw.split(":");
  if (parts.length !== 4 || !parts[0].startsWith(WORKSHOP_CODE_NAMESPACE)) {
    return {
      ok: false,
      reason: "NOT_A_WORKSHOP_CODE",
      message:
        "Not a workshop code. They begin “DPW”."
    };
  }

  const versionText = parts[0].slice(WORKSHOP_CODE_NAMESPACE.length);
  if (!/^\d+$/.test(versionText)) {
    return {
      ok: false,
      reason: "NOT_A_WORKSHOP_CODE",
      message: "Not a workshop code. They begin “DPW”."
    };
  }
  const version = Number(versionText);
  if (!SUPPORTED_VERSIONS.has(version)) {
    return {
      ok: false,
      reason: "NEWER_VERSION",
      message: `Newer code format (${version}). Reload to update the app.`
    };
  }

  const recordType = TYPE_FROM_LETTER[parts[1]];
  if (!recordType) {
    return {
      ok: false,
      reason: "UNKNOWN_RECORD_TYPE",
      message: "Can't open this kind of record. Reload to update the app."
    };
  }

  const id = parts[2].toLowerCase();
  if (isDeviceLocalId(recordType, id)) {
    return {
      ok: false,
      reason: "DEVICE_LOCAL",
      message:
        "This code was made before the workshop uploaded. Ask its creator to sync and send a new code."
    };
  }
  if (!ID_PATTERN.test(id)) {
    return {
      ok: false,
      reason: "MALFORMED",
      message: "Incomplete or damaged. Check each character."
    };
  }

  const typedCheck = parts[3]
    .split("")
    .map((character) => CHECK_CONFUSABLES[character] ?? character)
    .join("");
  const prefix = `${WORKSHOP_CODE_NAMESPACE}${version}:${parts[1]}:${parts[2]}`;
  if (typedCheck.length !== CHECK_LENGTH || typedCheck !== workshopCodeCheck(prefix)) {
    return {
      ok: false,
      reason: "CHECK_FAILED",
      message:
        "A character is wrong. Check each one."
    };
  }

  return { ok: true, ref: { recordType, id }, code: `${prefix}:${typedCheck}` };
}

export function formatWorkshopCodeForPrint(code: string): string {
  return (code.match(/.{1,4}/g) ?? []).join(" ");
}

export function unresolvedWorkshopCodeMessage(recordType: WorkshopRecordType): string {
  if (recordType === "prototype") {
    return "No prototype here matches that tag. Find it in the list.";
  }
  if (recordType === "designWorkshop") {
    return "No workshop you can open matches this code. Ask an admin to add you — your work here is kept.";
  }
  const noun = TYPE_LABEL[recordType].toLowerCase();
  return `No ${noun} you can open matches. Search by name instead.`;
}

export function workshopCodeIdForRow(row: { _entryId?: string; _clientKey?: string }): string | null {
  return row._entryId ?? row._clientKey ?? null;
}

export function workshopCodeMatchesRow(ref: WorkshopCodeRef, row: { _entryId?: string; _clientKey?: string }): boolean {
  if (ref.recordType !== "prototype") return false;
  return ref.id === row._entryId || ref.id === row._clientKey;
}
