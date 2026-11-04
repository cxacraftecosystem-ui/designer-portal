function isoParts(value: string | null | undefined): [number, number, number] | null {
  const text = value === null || value === undefined ? "" : String(value);
  const match =
    /^(\d{4})-(\d{2})-(\d{2})(?:[T ].*)?$/.exec(text) ?? /^(\d{4})(\d{2})(\d{2})(?:[T ].*)?$/.exec(text);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const probe = new Date(Date.UTC(year, month - 1, day));
  if (
    Number.isNaN(probe.getTime()) ||
    probe.getUTCFullYear() !== year ||
    probe.getUTCMonth() !== month - 1 ||
    probe.getUTCDate() !== day
  ) {
    return null;
  }
  return [year, month, day];
}

function wholeYearsSince(from: string | null | undefined, on: Date): number | null {
  const parts = isoParts(from);
  if (!parts) return null;
  const [year, month, day] = parts;
  const reached = on.getUTCMonth() + 1 > month || (on.getUTCMonth() + 1 === month && on.getUTCDate() >= day);
  return on.getUTCFullYear() - year - (reached ? 0 : 1);
}

function utcToday(): Date {
  return new Date();
}

export function deriveAge(dateOfBirth: string | null | undefined, on?: Date): number | null {
  if (!dateOfBirth) return null;
  const years = wholeYearsSince(dateOfBirth, on ?? utcToday());
  return years !== null && years >= 0 && years <= 130 ? years : null;
}

export function deriveExperienceYears(craftStartDate: string | null | undefined, on?: Date): number | null {
  if (!craftStartDate) return null;
  const years = wholeYearsSince(craftStartDate, on ?? utcToday());
  return years !== null && years >= 0 && years <= 90 ? years : null;
}
