import type { SelectOption } from "@/components/ui/selectFilter";
import { roleLabel } from "@/lib/permissions";
import {
  CAPACITY_LABELS,
  type DwOfficer,
  type DwOversightAssignment,
  type DwOversightCapacity
} from "./oversight";

function personLabel(person: { name?: string | null; email?: string | null }): string {
  return person.name?.trim() || person.email?.trim() || "Unknown user";
}

export const OWN_POST_HINT = "you hold this post — another administrator has to take you off it";

export function officerSlotRefusal(
  officer: Pick<DwOfficer, "capacities">,
  capacity: DwOversightCapacity
): string | null {
  if (officer.capacities.includes(capacity)) return null;
  if (officer.capacities.length === 0) return "cannot be named in either post";
  const posts = officer.capacities.map((held) => `the ${CAPACITY_LABELS[held]} post`);
  return `may hold ${posts.join(" or ")}, not this one`;
}

export function officerOptionsForPost({
  officers,
  capacity,
  holder,
  remembered,
  readerId
}: {
  officers: readonly DwOfficer[] | null | undefined;
  capacity: DwOversightCapacity;
  holder?: DwOversightAssignment | null;
  remembered?: ReadonlyMap<string, DwOfficer>;
  readerId?: string | null;
}): SelectOption[] {
  const rows: SelectOption[] = [];
  const offered = new Set<string>();
  const add = (officer: DwOfficer, refusal: string | null) => {
    if (offered.has(officer.id)) return;
    offered.add(officer.id);
    const who = `${officer.email} · ${roleLabel(officer.role)}`;
    rows.push({
      value: officer.id,
      label: personLabel(officer),
      hint: refusal ? `${who} — ${refusal}` : who,
      disabled: Boolean(refusal),
      ...(refusal ? { group: "Cannot hold this post" } : {})
    });
  };

  const candidates = (officers ?? []).filter((officer) => officer.id !== readerId);
  for (const officer of candidates) {
    if (officerSlotRefusal(officer, capacity) === null) add(officer, null);
  }
  for (const officer of candidates) {
    const refusal = officerSlotRefusal(officer, capacity);
    if (refusal !== null) add(officer, refusal);
  }
  if (holder && !offered.has(holder.userId)) {
    const current = remembered?.get(holder.userId) ?? {
      id: holder.userId,
      name: holder.name,
      email: holder.email,
      role: holder.role,
      capacities: [capacity]
    };
    if (readerId && holder.userId === readerId) {
      offered.add(current.id);
      rows.push({
        value: current.id,
        label: personLabel(current),
        hint: `${current.email} · ${roleLabel(current.role)} — ${OWN_POST_HINT}`,
        disabled: true
      });
    } else {
      add(current, null);
    }
  }
  return rows;
}

export function officersOfferedTo(
  officers: readonly DwOfficer[] | null | undefined,
  readerId?: string | null
): number {
  return (officers ?? []).filter((officer) => officer.id !== readerId).length;
}
