export type StatedPrefill = {
  key: number;
  state?: string | null;
  district?: string | null;
  village?: string | null;
  source: string;
};

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
