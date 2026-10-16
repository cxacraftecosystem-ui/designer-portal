/**
 * THE ROWS A RECORD'S READ-ONLY PAGE PRINTS, one builder per kind, in the order of the record's own
 * form so a reader who knows the form finds each value where they expect it.
 *
 * Pure on purpose: there is no React renderer in this repo's devDependencies, so a judgement made
 * inside JSX is only ever checked by somebody looking at a screen. `e2e/record-view-unit.spec.ts`
 * calls these instead — above all the one judgement that must not drift, which is what a view is
 * NOT allowed to print.
 *
 * ── WHAT A VIEW NEVER PRINTS ────────────────────────────────────────────────────────────────────
 *
 * The identity numbers. §12.8 of the frontend contract: an artisan's Aadhaar arrives IN FULL from
 * `GET /artisans/{id}` — this very endpoint — and must never be rendered in a list, a card or an
 * export view; the lists show only whether a card is on file. A read-only page is opened by a click
 * on that list, so it answers the same question the list does — "On file" — and the digits stay on
 * the edit form, in front of the person correcting them. The same for the Pehchan card number.
 *
 * Empty values are left out rather than printed as a dash: a column of "—" says nothing except that
 * the page is padded, and the record's form is one click away for anybody who wants every box.
 */

import type { ProcessRecord } from "@/components/forms/ProcessForm";
import { bytes, formatDate } from "@/lib/format";
import type { Artisan, Craft, MediaFile, ProductDocumentation, QuestionnaireInterview, ToolDocumentation, Workshop } from "@/lib/types";

/** One printed line: a label and its value, already in words. */
export type RecordFieldRow = { label: string; value: string };

type Raw = string | number | boolean | null | undefined;

/** A stored code ("HIGH_DEMAND") in plain words ("High demand"). Free text passes through. */
export function plainWords(value: string | null | undefined): string | null {
  if (value == null) return null;
  const trimmed = value.trim();
  if (!trimmed) return null;
  if (!/^[A-Z0-9_]+$/.test(trimmed)) return trimmed;
  const spaced = trimmed.replace(/_/g, " ").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/** A Decimal-or-number with an optional unit; anything that is not a finite number is dropped. */
function measure(value: string | number | null | undefined, unit?: string): string | null {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  if (!Number.isFinite(number)) return null;
  return unit ? `${number} ${unit}` : String(number);
}

/** Builds the rows, dropping every label whose value is empty. */
export function rows(entries: Array<[label: string, value: Raw]>): RecordFieldRow[] {
  const out: RecordFieldRow[] = [];
  for (const [label, value] of entries) {
    if (value === null || value === undefined) continue;
    const text = typeof value === "boolean" ? (value ? "Yes" : "No") : String(value).trim();
    if (!text) continue;
    out.push({ label, value: text });
  }
  return out;
}

/** "On file" or nothing — never the number. See the header. */
function onFile(value: string | null | undefined): string | null {
  return value && value.trim() ? "On file" : null;
}

function createdLine(createdAt?: string | null, by?: { name?: string | null } | null): string | null {
  if (!createdAt) return null;
  const when = formatDate(createdAt);
  return by?.name ? `${when} by ${by.name}` : when;
}

export function artisanFields(artisan: Artisan): RecordFieldRow[] {
  return rows([
    ["Local name", artisan.localName],
    ["Craft", artisan.craft?.name],
    ["Place", artisan.place],
    ["Address", artisan.address],
    ["Gender", plainWords(artisan.gender)],
    ["Phone", artisan.phone],
    ["Email", artisan.email],
    ["Date of birth", artisan.dateOfBirth ? formatDate(artisan.dateOfBirth) : null],
    ["Practising since", artisan.craftStartDate ? formatDate(artisan.craftStartDate) : null],
    ["Years of experience", artisan.experienceYears],
    ["Aadhaar number", onFile(artisan.aadhaarNumber)],
    ["Pehchan card", artisan.pehchanCardAvailable === undefined ? null : artisan.pehchanCardAvailable],
    ["Pehchan card number", onFile(artisan.pehchanCardNumber)],
    ["Do's", artisan.dos],
    ["Don'ts", artisan.donts],
    ["Notes", artisan.notes],
    ["Workshop", artisan.workshop?.title],
    ["Recorded", createdLine(artisan.createdAt, artisan.createdBy)]
  ]);
}

export function productFields(product: ProductDocumentation): RecordFieldRow[] {
  return rows([
    ["Local name", product.localName],
    ["Product type", plainWords(product.productType)],
    ["Craft", product.craftName],
    ["Artisan", product.artisanName],
    ["Place", product.place],
    ["Time taken to complete", product.timeTakenToCompleteProduct],
    ["Size", product.size],
    ["Length", measure(product.lengthInches, "in")],
    ["Breadth", measure(product.breadthInches, "in")],
    ["Height", measure(product.heightInches, "in")],
    ["Cost of making", measure(product.costOfMaking)],
    ["Selling price", measure(product.sellingPrice)],
    ["Market demand", plainWords(product.marketDemand)],
    ["Raw materials used", product.rawMaterialsUsed],
    ["Main tools used", product.mainToolsUsed],
    ["Function or use", product.productFunctionUse],
    ["Remarks", product.remarks],
    ["Workshop", product.workshop?.title],
    ["Recorded", createdLine(product.createdAt, product.createdBy)]
  ]);
}

export function toolFields(tool: ToolDocumentation): RecordFieldRow[] {
  return rows([
    ["Local name", tool.localName],
    ["English name", tool.englishName],
    ["Craft", tool.craftName],
    ["Artisan", tool.artisanName],
    ["Place", tool.place],
    ["Process used in", tool.processUsedIn],
    ["Material", tool.material],
    ["Years in use", tool.yearsInUse],
    ["Height", measure(tool.height, "cm")],
    ["Width", measure(tool.width, "cm")],
    ["Length", measure(tool.lengthInches, "in")],
    ["Breadth", measure(tool.breadthInches, "in")],
    ["Height (inches)", measure(tool.heightInches, "in")],
    ["Thickness", measure(tool.thickness)],
    ["Weight", measure(tool.weight)],
    ["Radius", measure(tool.radius)],
    ["Maker", plainWords(tool.maker)],
    ["Tradition type", plainWords(tool.traditionType)],
    ["Replacement cost", measure(tool.replacementCost)],
    ["Suggestions for improvement", tool.suggestionsForToolImprovement],
    ["Remarks", tool.remarks],
    ["Recorded", createdLine(tool.createdAt, tool.createdBy)]
  ]);
}

export function processFields(process: ProcessRecord): RecordFieldRow[] {
  return rows([
    ["Product", process.product?.productName],
    ["Artisan", process.product?.artisanName],
    ["Craft", process.product?.craftName],
    ["Pre-processes recorded", process.preProcessAvailable === undefined ? null : process.preProcessAvailable],
    ["Steps", process.steps?.length ?? null],
    ["Notes", process.notes],
    ["Workshop", process.workshop?.title],
    ["Recorded", createdLine(process.createdAt, process.createdBy)]
  ]);
}

export function craftFields(craft: Craft & { createdBy?: { name?: string | null } | null }): RecordFieldRow[] {
  return rows([
    ["Local name", craft.localName],
    ["Category", craft.category],
    ["Place", craft.place],
    ["Description", craft.description],
    ["Workshop", craft.workshop?.title],
    ["Recorded", createdLine(craft.createdAt, craft.createdBy)]
  ]);
}

export function workshopFields(workshop: Workshop): RecordFieldRow[] {
  const from = workshop.startDate ?? workshop.date;
  const to = workshop.endDate;
  const when = from ? (to && to !== from ? `${formatDate(from)} to ${formatDate(to)}` : formatDate(from)) : null;
  const artisans = (workshop.artisans ?? []).map((link) => link.artisan?.name).filter(Boolean);
  return rows([
    ["Dates", when],
    ["Place", workshop.place],
    ["Description", workshop.description],
    ["Notes", workshop.notes],
    ["Artisans", artisans.length ? artisans.join(", ") : null],
    ["Recorded", createdLine(workshop.createdAt, workshop.createdBy)]
  ]);
}

export function interviewFields(interview: QuestionnaireInterview): RecordFieldRow[] {
  const artisans = (interview.artisans ?? []).map((link) => link.artisan?.name).filter(Boolean);
  const when = interview.interviewDate ?? interview.recordedAt;
  return rows([
    ["Artisans", artisans.length ? artisans.join(", ") : null],
    ["Date", when ? formatDate(when) : null],
    ["Place", interview.place],
    ["Language", interview.language],
    ["Notes", interview.notes],
    ["Workshop", interview.workshop?.title],
    ["Recorded", createdLine(interview.createdAt, interview.createdBy)]
  ]);
}

export function mediaFields(media: MediaFile): RecordFieldRow[] {
  return rows([
    ["Caption", media.caption],
    ["Kind", plainWords(media.mediaType)],
    ["File type", media.mimeType],
    ["Size", Number(media.sizeBytes) > 0 ? bytes(media.sizeBytes) : null],
    ["Attached to", plainWords(media.linkedRecordType)],
    ["Uploaded", createdLine(media.createdAt, media.uploadedBy)]
  ]);
}
