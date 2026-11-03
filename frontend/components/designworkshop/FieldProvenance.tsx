"use client";

import type { DwFieldStamp } from "@/lib/designWorkshops";

const SOURCE_REFERENCE = "reference";

function shortDate(iso?: string): string {
  if (!iso) return "";
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return "";
  const month = monthFormat().format(parsed);
  return `${parsed.getDate()} ${month}`;
}

let monthFormatter: Intl.DateTimeFormat | null = null;
function monthFormat(): Intl.DateTimeFormat {
  monthFormatter ??= new Intl.DateTimeFormat(undefined, { month: "short" });
  return monthFormatter;
}

function recordName(refModel?: string | null): string {
  const named: Record<string, string> = {
    Artisan: "artisan",
    Craft: "craft",
    Process: "process",
    ProductDocumentation: "product",
    ToolDocumentation: "tool",
    QuestionnaireInterview: "interview"
  };
  return (refModel && named[refModel]) || "linked";
}

export function fieldProvenanceLine(stamp?: DwFieldStamp | null): string {
  if (!stamp) return "";
  const who = stamp.byName?.trim() || "";
  const when = shortDate(stamp.at);

  if (stamp.source === SOURCE_REFERENCE) {
    if (!stamp.refModel && !who) return "";
    const record = `From the ${recordName(stamp.refModel)} record`;
    if (who) return `${record}, by ${who}`;
    return record;
  }

  if (!stamp.by) return "";
  const person = who || "A former colleague";
  return when ? `${person}, ${when}` : person;
}

export function FieldProvenance({ stamp }: { stamp?: DwFieldStamp | null }) {
  const line = fieldProvenanceLine(stamp);
  if (!line) return null;
  return (
    <p aria-hidden className="text-[0.6875rem] leading-4 text-ink-500">
      {line}
    </p>
  );
}
