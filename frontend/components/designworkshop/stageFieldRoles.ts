import { referenceHydrationFor, type DwEntity, type DwField } from "@/lib/designWorkshops";
import { type LengthUnit, LENGTH_UNITS } from "@/lib/photoMeasure";

function folded(value: string): string {
  return value.toLowerCase().replace(/[^a-z]/g, "");
}

const IDENTITY_NUMBER_KEY = /(aadhaar|aadhar|pehchan|cardno|cardnumber|idnumber|identitynumber|idcardno)/;

const IDENTITY_IMAGE_KEY = /(card|aadhaar|aadhar|pehchan|identity|idproof)/;

export function identityNumberField(entity: DwEntity): DwField | null {
  return (
    entity.fields.find(
      (field) => !field.deprecated && field.type === "TEXT" && IDENTITY_NUMBER_KEY.test(folded(`${field.key} ${field.label}`))
    ) ?? null
  );
}

export function offersIdentityOcr(entity: DwEntity, field: DwField): boolean {
  if (field.type !== "IMAGE" && field.type !== "IMAGE_LIST") return false;
  if (!identityNumberField(entity)) return false;
  const images = entity.fields.filter(
    (candidate) => !candidate.deprecated && (candidate.type === "IMAGE" || candidate.type === "IMAGE_LIST")
  );
  const named = images.filter((candidate) => IDENTITY_IMAGE_KEY.test(folded(`${candidate.key} ${candidate.label}`)));
  if (named.length) return named.some((candidate) => candidate.key === field.key);
  return images[0]?.key === field.key;
}

const SIGNATURE_KEY = /(signature|signed|thumbimpression|thumbprint)/;

export function offersSignaturePad(field: DwField): boolean {
  if (field.type !== "IMAGE" && field.type !== "IMAGE_LIST") return false;
  if (field.deprecated) return false;
  return SIGNATURE_KEY.test(folded(`${field.key} ${field.label}`));
}

function keyTokens(key: string): string[] {
  return key
    .split(/(?=[A-Z])|[_\-\s]+/)
    .map((part) => part.toLowerCase())
    .filter(Boolean);
}

const RANGE_START_TOKENS = new Set(["start", "started", "from", "begin", "began", "opened", "commenced"]);

const RANGE_END_TOKENS = new Set(["end", "ended", "to", "until", "complete", "completed", "finish", "finished", "closed"]);

export type DateRangeRole = "start" | "end";

function rangeRole(key: string): { role: DateRangeRole; stem: string } | null {
  const parts = keyTokens(key);
  let role: DateRangeRole | null = null;
  let markerIndex = -1;
  for (let index = 0; index < parts.length; index += 1) {
    const isStart = RANGE_START_TOKENS.has(parts[index]);
    const isEnd = RANGE_END_TOKENS.has(parts[index]);
    if (!isStart && !isEnd) continue;
    if (role) return null;
    role = isStart ? "start" : "end";
    markerIndex = index;
  }
  if (!role) return null;
  return { role, stem: parts.filter((_, index) => index !== markerIndex).join("") };
}

export function dateRangePartner(entity: DwEntity, field: DwField): { role: DateRangeRole; partner: DwField } | null {
  if (field.type !== "DATE" || field.deprecated) return null;
  const own = rangeRole(field.key);
  if (!own) return null;
  const opposite: DateRangeRole = own.role === "start" ? "end" : "start";
  const matches = entity.fields.filter((candidate) => {
    if (candidate.key === field.key || candidate.deprecated || candidate.type !== "DATE") return false;
    const other = rangeRole(candidate.key);
    return other !== null && other.role === opposite && other.stem === own.stem;
  });
  return matches.length === 1 ? { role: own.role, partner: matches[0] } : null;
}

export function measurableLengthFields(entity: DwEntity): { field: DwField; unit: LengthUnit }[] {
  const found: { field: DwField; unit: LengthUnit }[] = [];
  for (const field of entity.fields) {
    if (field.deprecated) continue;
    if (field.type !== "DECIMAL" && field.type !== "INT") continue;
    const unit = field.unit;
    if (!unit) continue;
    const normalised = unit.trim().toLowerCase();
    if (!Object.prototype.hasOwnProperty.call(LENGTH_UNITS, normalised)) continue;
    found.push({ field, unit: normalised as LengthUnit });
  }
  return found;
}

export function offersPhotoMeasure(entity: DwEntity, field: DwField): boolean {
  if (field.type !== "IMAGE" && field.type !== "IMAGE_LIST") return false;
  if (field.deprecated) return false;
  return measurableLengthFields(entity).length > 0;
}

const LINE_ART_KEY = /(lineart|linedrawing|vector|plate|artwork)/;

export function offersSketchRectify(entity: DwEntity, field: DwField): boolean {
  if (field.type !== "FILE" || field.deprecated) return false;
  if (!LINE_ART_KEY.test(folded(`${field.key} ${field.label}`))) return false;
  return sketchSourceFields(entity).length > 0;
}

export function sketchSourceFields(entity: DwEntity): DwField[] {
  return entity.fields.filter(
    (field) => !field.deprecated && (field.type === "IMAGE" || field.type === "IMAGE_LIST")
  );
}

export type AddressListRole = "state" | "district" | "pincode";

const ADDRESS_FIELD_KEYS: Record<string, AddressListRole> = {
  state: "state",
  recordState: "state",
  designerState: "state",
  district: "district",
  recordDistrict: "district",
  pincode: "pincode",
  recordPincode: "pincode",
  designerPincode: "pincode"
};

function addressSibling(entity: DwEntity, own: DwField, want: AddressListRole): DwField | null {
  return (
    entity.fields.find(
      (candidate) =>
        candidate.key !== own.key &&
        !candidate.deprecated &&
        candidate.type === "TEXT" &&
        ADDRESS_FIELD_KEYS[candidate.key] === want
    ) ?? null
  );
}

export type AddressFieldRole = {
  role: AddressListRole;
  stateField: DwField | null;
  districtField: DwField | null;
};

export function addressListRole(entity: DwEntity, field: DwField): AddressFieldRole | null {
  if (field.type !== "TEXT" || field.deprecated) return null;
  const role = ADDRESS_FIELD_KEYS[field.key];
  if (!role) return null;
  const stateField = role === "state" ? null : addressSibling(entity, field, "state");
  if (role === "district" && !stateField) return null;
  const districtField = role === "state" ? addressSibling(entity, field, "district") : null;
  return { role, stateField, districtField };
}

const WORKSHOP_TITLE_FIELD_KEYS = new Set([
  "documentedAtWorkshop",
  "craftDocumentedAtWorkshop",
  "interviewDocumentedAtWorkshop"
]);

export function workshopTitleRole(field: DwField): boolean {
  if (field.type !== "TEXT" || field.deprecated) return false;
  return WORKSHOP_TITLE_FIELD_KEYS.has(field.key);
}

export function ownWorkshopTitleRole(field: DwField): boolean {
  if (field.type !== "TEXT" || field.deprecated) return false;
  return field.key === "workshopTitle";
}

const MEDIA_NOTE_SOURCE_KEYS = new Set(["recordMediaNote", "craftMediaNote"]);

const MEDIA_NOTE_MODELS: Record<string, { linkedRecordType: string; subject: string; numberedPrefix: string }> = {
  Artisan: { linkedRecordType: "artisan", subject: "artisan", numberedPrefix: "" },
  Craft: { linkedRecordType: "craft", subject: "craft", numberedPrefix: "" },
  ProductDocumentation: { linkedRecordType: "product", subject: "product", numberedPrefix: "" },
  ToolDocumentation: { linkedRecordType: "tool", subject: "tool", numberedPrefix: "STAGE_STEP_" }
};

export type MediaNoteFieldRole = {
  refField: DwField;
  linkedRecordType: string;
  subject: string;
  numberedPrefix: string;
};

export function recordMediaNoteRole(entity: DwEntity, field: DwField): MediaNoteFieldRole | null {
  if (field.type !== "TEXT" || field.deprecated) return null;
  const matches: MediaNoteFieldRole[] = [];
  for (const candidate of entity.fields) {
    if (candidate.type !== "REF" || candidate.deprecated) continue;
    const mapping = referenceHydrationFor(entity, candidate);
    const fills = Object.entries(mapping).some(
      ([sourceKey, targetKey]) => targetKey === field.key && MEDIA_NOTE_SOURCE_KEYS.has(sourceKey)
    );
    if (!fills) continue;
    const model = candidate.refModel ? MEDIA_NOTE_MODELS[candidate.refModel] : undefined;
    if (!model) return null;
    matches.push({ refField: candidate, ...model });
  }
  return matches.length === 1 ? matches[0] : null;
}
