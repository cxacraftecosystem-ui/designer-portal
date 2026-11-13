import { measurementMethodsFor, type AcceptedMeasurements } from "@/components/forms/measurementMethods";
import { appendStoredParagraph } from "@/components/richtext/storedRichText";
import {
  isMaskedIdentityNumber,
  locationFromForm,
  numericValue,
  recordedAtFromForm,
  recordedTimezoneFromForm,
  requiredText,
  textValue
} from "@/lib/forms";

export type ExifItem = { filename: string; metadata: Record<string, unknown> };

export type RecordExif = { items: ExifItem[]; remark: string };

export const NO_EXIF: RecordExif = { items: [], remark: "" };

export type FilingLinks = { workshopId: string | null; designWorkshopId: string | null };

function storedNumber(value: string): number | null {
  const n = Number(value);
  return value.trim() && Number.isFinite(n) ? n : null;
}

function captured(form: FormData) {
  return {
    recordedAt: recordedAtFromForm(form),
    recordedTimezone: recordedTimezoneFromForm(form),
    location: locationFromForm(form)
  };
}

function exifMetadata(exif: RecordExif) {
  return exif.items.length ? { mediaExif: exif.items } : {};
}

export type ToolBodyContext = FilingLinks & {
  craftName: string;
  craftIds: string[];
  artisanIds: string[];
  dimensions: { height: string; width: string; length: string; breadth: string; heightInches: string };
  accepted: AcceptedMeasurements;
  initialStatus?: string | null;
  exif: RecordExif;
};

export function toolBody(form: FormData, ctx: ToolBodyContext) {
  const { height, width, length, breadth, heightInches } = ctx.dimensions;
  const { recordedAt, recordedTimezone, location } = captured(form);
  return {
    craftName: ctx.craftName,
    place: requiredText(form, "place"),
    artisanName: requiredText(form, "artisanName"),
    toolkitName: requiredText(form, "toolkitName"),
    localName: textValue(form, "localName"),
    englishName: textValue(form, "englishName"),
    processUsedIn: textValue(form, "processUsedIn"),
    material: textValue(form, "material"),
    materialFamily: textValue(form, "materialFamily"),
    yearsInUse: numericValue(form, "yearsInUse"),
    height: storedNumber(height),
    width: storedNumber(width),
    lengthInches: storedNumber(length),
    breadthInches: storedNumber(breadth),
    heightInches: storedNumber(heightInches),
    measurementMethods: measurementMethodsFor(ctx.accepted, { lengthInches: length, breadthInches: breadth, heightInches }),
    thickness: numericValue(form, "thickness"),
    weight: numericValue(form, "weight"),
    radius: numericValue(form, "radius"),
    maker: requiredText(form, "maker") || "UNKNOWN",
    traditionType: requiredText(form, "traditionType") || "UNKNOWN",
    replacementCost: numericValue(form, "replacementCost"),
    suggestionsForToolImprovement: textValue(form, "suggestionsForToolImprovement"),
    remarks: appendStoredParagraph(textValue(form, "remarks"), ctx.exif.remark),
    artisanId: ctx.artisanIds[0] || null,
    craftId: ctx.craftIds[0] || null,
    craftIds: ctx.craftIds,
    artisanIds: ctx.artisanIds,
    workshopId: ctx.workshopId,
    designWorkshopId: ctx.designWorkshopId,
    status: requiredText(form, "status") || ctx.initialStatus || "PENDING",
    recordedAt,
    recordedTimezone,
    location,
    extraMetadata: exifMetadata(ctx.exif)
  };
}

export type ProductBodyContext = FilingLinks & {
  artisanId: string;
  craftId: string;
  dimensions: { length: string; breadth: string; height: string };
  accepted: AcceptedMeasurements;
  initialStatus?: string | null;
  exif: RecordExif;
};

export function productBody(form: FormData, ctx: ProductBodyContext) {
  const { length, breadth, height } = ctx.dimensions;
  const { recordedAt, recordedTimezone, location } = captured(form);
  return {
    place: requiredText(form, "place"),
    artisanName: requiredText(form, "artisanName"),
    productName: requiredText(form, "productName"),
    localName: textValue(form, "localName"),
    productType: requiredText(form, "productType") || "OTHER",
    timeTakenToCompleteProduct: textValue(form, "timeTakenToCompleteProduct"),
    size: textValue(form, "size"),
    lengthInches: storedNumber(length),
    breadthInches: storedNumber(breadth),
    heightInches: storedNumber(height),
    measurementMethods: measurementMethodsFor(ctx.accepted, { lengthInches: length, breadthInches: breadth, heightInches: height }),
    costOfMaking: numericValue(form, "costOfMaking"),
    sellingPrice: numericValue(form, "sellingPrice"),
    marketDemand: requiredText(form, "marketDemand") || "UNKNOWN",
    rawMaterialsUsed: textValue(form, "rawMaterialsUsed"),
    mainToolsUsed: textValue(form, "mainToolsUsed"),
    productFunctionUse: textValue(form, "productFunctionUse"),
    remarks: appendStoredParagraph(textValue(form, "remarks"), ctx.exif.remark),
    artisanId: ctx.artisanId || null,
    craftId: ctx.craftId || null,
    workshopId: ctx.workshopId,
    designWorkshopId: ctx.designWorkshopId,
    status: requiredText(form, "status") || ctx.initialStatus || "PENDING",
    recordedAt,
    recordedTimezone,
    location,
    extraMetadata: exifMetadata(ctx.exif)
  };
}

export type ProcessStepDraft = {
  serverId?: string;
  name: string;
  stepType: string;
  recordAdditional: boolean;
  notes: string;
};

export type ProcessBodyContext = FilingLinks & {
  name: string;
  productId: string;
  preProcessAvailable: boolean;
  notes: string;
  status: string;
  steps: ProcessStepDraft[];
  isEdit: boolean;
  now: Date;
};

export function processBody(ctx: ProcessBodyContext) {
  return {
    name: ctx.name.trim(),
    productId: ctx.productId,
    workshopId: ctx.workshopId,
    designWorkshopId: ctx.designWorkshopId,
    preProcessAvailable: ctx.preProcessAvailable,
    notes: ctx.notes.trim() || null,
    status: ctx.status,
    steps: ctx.steps.map((step, index) => ({
      id: step.serverId,
      name: step.name.trim(),
      stepType: step.stepType,
      sortOrder: index + 1,
      notes: step.recordAdditional ? step.notes.trim() || null : null
    })),
    ...(ctx.isEdit ? {} : { recordedAt: ctx.now.toISOString() })
  };
}

export const PEHCHAN_NO = "false";

export type ArtisanBodyContext = FilingLinks & {
  craftId: string;
  initialStatus?: string | null;
  formContract: number;
  duplicateAcknowledged?: string[];
  exif: RecordExif;
};

function wholeNumber(form: FormData, key: string): number | null {
  const value = textValue(form, key);
  return value ? Number(value) : null;
}

export function artisanBody(form: FormData, ctx: ArtisanBodyContext) {
  const { recordedAt, recordedTimezone, location } = captured(form);
  const pehchanAvailable = textValue(form, "pehchanCardAvailable") !== PEHCHAN_NO;
  const pehchanNumber = textValue(form, "pehchanCardNumber");
  const pehchanUnchanged = pehchanAvailable && isMaskedIdentityNumber(pehchanNumber);
  return {
    name: requiredText(form, "name"),
    localName: textValue(form, "localName"),
    gender: textValue(form, "gender"),
    dateOfBirth: textValue(form, "dateOfBirth") || null,
    craftStartDate: textValue(form, "craftStartDate") || null,
    experienceYears: wholeNumber(form, "experienceYears"),
    experienceMonths: wholeNumber(form, "experienceMonths"),
    phone: textValue(form, "phone"),
    phoneCountryCode: textValue(form, "phoneCountryCode"),
    email: textValue(form, "email"),
    place: requiredText(form, "place"),
    address: textValue(form, "address"),
    notes: appendStoredParagraph(textValue(form, "notes"), ctx.exif.remark, "paragraph"),
    aadhaarNumber: textValue(form, "aadhaarNumber"),
    pehchanCardAvailable: pehchanAvailable,
    ...(pehchanUnchanged ? {} : { pehchanCardNumber: pehchanAvailable ? pehchanNumber : null }),
    dos: requiredText(form, "dos"),
    donts: requiredText(form, "donts"),
    craftId: ctx.craftId,
    workshopId: ctx.workshopId,
    designWorkshopId: ctx.designWorkshopId,
    status: requiredText(form, "status") || ctx.initialStatus || "PENDING",
    recordedAt,
    recordedTimezone,
    location,
    extraMetadata: exifMetadata(ctx.exif),
    formContract: ctx.formContract,
    ...(ctx.duplicateAcknowledged ? { duplicateAcknowledged: ctx.duplicateAcknowledged } : {})
  };
}

export type CraftBodyContext = {
  stateOfOrigin: unknown;
  stateOfPractice: unknown;
  giStatus: unknown;
  workshopId: string | null;
  exif: RecordExif;
};

export function craftBody(form: FormData, ctx: CraftBodyContext): Record<string, unknown> {
  const description = [textValue(form, "description"), ctx.exif.remark].filter(Boolean).join("\n\n") || null;
  const payload: Record<string, unknown> = {
    name: requiredText(form, "name"),
    localName: textValue(form, "localName"),
    subCategoryId: requiredText(form, "subCategoryId"),
    stateOfOrigin: ctx.stateOfOrigin,
    stateOfPractice: ctx.stateOfPractice,
    giStatus: ctx.giStatus,
    workshopId: ctx.workshopId || null,
    description
  };
  if (ctx.exif.items.length) payload.extraMetadata = { mediaExif: ctx.exif.items };
  return payload;
}

export type WorkshopBodyContext = {
  workshopType: unknown;
  canSetStatus: boolean;
  artisanIds?: string[];
  craftIds?: string[];
};

export function workshopBody(form: FormData, ctx: WorkshopBodyContext): Record<string, unknown> {
  const payload: Record<string, unknown> = {
    title: requiredText(form, "title"),
    workshopType: ctx.workshopType,
    date: requiredText(form, "date"),
    startDate: requiredText(form, "startDate"),
    endDate: requiredText(form, "endDate"),
    place: requiredText(form, "place"),
    description: textValue(form, "description"),
    notes: textValue(form, "notes"),
    status: ctx.canSetStatus ? requiredText(form, "status") : "PENDING",
    location: locationFromForm(form)
  };
  if (ctx.artisanIds) payload.artisanIds = ctx.artisanIds;
  if (ctx.craftIds) payload.craftIds = ctx.craftIds;
  return payload;
}

export type QuestionnaireCreateContext = { designWorkshopId: string; kind: string };

export function questionnaireCreateBody(form: FormData, ctx: QuestionnaireCreateContext) {
  return {
    title: String(form.get("title") ?? "").trim(),
    description: String(form.get("description") ?? "").trim() || undefined,
    designWorkshopId: ctx.designWorkshopId || undefined,
    kind: ctx.kind || undefined
  };
}
