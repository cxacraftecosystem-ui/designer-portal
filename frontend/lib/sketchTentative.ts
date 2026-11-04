import type { DwEntity, DwEntryData, DwField, DwValue } from "@/lib/designWorkshops";

export const TENTATIVE_FIELD_KEY = "isTentative";

export function tentativeField(entity: DwEntity | null | undefined): DwField | null {
  if (!entity) return null;
  return (
    entity.fields.find(
      (field) => field.key === TENTATIVE_FIELD_KEY && field.type === "BOOL" && !field.deprecated
    ) ?? null
  );
}

export function isTentativeRow(row: DwEntryData | null | undefined): boolean {
  if (!row) return false;
  const value: DwValue | undefined = row[TENTATIVE_FIELD_KEY];
  return value === true;
}

export function tentativeFirst<T>(items: readonly T[], isTentative: (item: T) => boolean): T[] {
  const first: T[] = [];
  const rest: T[] = [];
  for (const item of items) {
    if (isTentative(item)) first.push(item);
    else rest.push(item);
  }
  return [...first, ...rest];
}
