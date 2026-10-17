/**
 * A stated address another source supplied (C27: the Field Repository's) for `LocationFields`.
 * Pure, so the rule is unit-tested without the map.
 */
export type StatedPrefill = {
  key: number;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  /** Who it came from, for the "check it" line. */
  source: string;
};

/** The stated-address values a prefill writes: a district only where its state is given. */
export function statedPrefillValues(prefill: StatedPrefill): { state?: string; district?: string; village?: string } {
  const out: { state?: string; district?: string; village?: string } = {};
  const state = prefill.state?.trim();
  if (state) {
    out.state = state;
    const district = prefill.district?.trim();
    if (district) out.district = district;
  }
  const village = prefill.village?.trim();
  if (village) out.village = village;
  return out;
}
