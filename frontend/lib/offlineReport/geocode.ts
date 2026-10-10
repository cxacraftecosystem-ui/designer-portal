/**
 * Placing an address on the report's map with no connection: `report_builder._geocode` and the
 * three server modules it reads (`address`, `place_atlas`, `geography`), ported over the tables in
 * `gazetteer.json`.
 *
 * THE TABLES ARE GENERATED, NOT RE-TYPED. `backend/tools/report_offline_parity.py --write` exports
 * the closed state and district lists, the place atlas and the state seats from the server's own
 * modules, and `backend/tests/test_report_offline_parity.py` fails if the committed file is stale.
 * What is ported here is only the procedure: fold a name, look it up, fall back a rung.
 */

import gazetteer from "@/lib/offlineReport/gazetteer.json";

type Place = {
  key: string;
  label: string;
  region: string;
  state: string;
  lat: number;
  lon: number;
  precision: "TOWN" | "DISTRICT" | "STATE";
  aliases: string[];
  district: string | null;
};

type Gazetteer = {
  states: string[];
  stateLookup: Record<string, string>;
  districtsByState: Record<string, string[]>;
  districtLookup: Record<string, Record<string, string>>;
  districtSuffixes: string[];
  stateSeats: Record<string, [string, number, number]>;
  atlasStateSeats: Record<string, [string, number, number]>;
  atlasStateAliases: Record<string, string>;
  maxAliasWords: number;
  places: Place[];
  atlasDistrictAnchors: Record<string, [number, number]>;
};

const G = gazetteer as unknown as Gazetteer;
const STATES = new Set(G.states);
const ALIAS_TO_PLACE = new Map<string, Place>();
for (const place of G.places) for (const alias of place.aliases) ALIAS_TO_PLACE.set(alias, place);

/** `address._fold`. */
export function fold(value: string): string {
  return value.toLowerCase().replace(/&/g, "and").replace(/[^a-z0-9]+/g, "");
}

/** `address.normalize_state`. */
function normalizeState(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  if (!text) return null;
  return G.stateLookup[fold(text)] ?? text;
}

/** `address.canonical_state`: the canonical name, or null for anything off the closed list. */
export function canonicalState(value: unknown): string | null {
  const normalized = normalizeState(value);
  return normalized !== null && STATES.has(normalized) ? normalized : null;
}

function districtKeys(value: string): string[] {
  const folded = fold(value);
  for (const suffix of G.districtSuffixes) {
    if (folded.endsWith(suffix) && folded.length > suffix.length) return [folded, folded.slice(0, -suffix.length)];
  }
  return [folded];
}

/** `address.canonical_district`: the district within `state`, or null when the pair is not real. */
export function canonicalDistrict(state: unknown, value: unknown): string | null {
  const resolvedState = canonicalState(state);
  if (resolvedState === null) return null;
  if (value === null || value === undefined) return null;
  const text = String(value).trim();
  if (!text) return null;
  const index = G.districtLookup[resolvedState];
  let normalized = text;
  if (index) {
    for (const key of districtKeys(text)) {
      const found = index[key];
      if (found) {
        normalized = found;
        break;
      }
    }
  }
  return (G.districtsByState[resolvedState] ?? []).includes(normalized) ? normalized : null;
}

/** `geography.state_seat`. */
export function stateSeat(state: unknown): [string, number, number] | null {
  const canonical = canonicalState(state);
  return canonical ? (G.stateSeats[canonical] ?? null) : null;
}

/** The district anchors the atlas alone gives, keyed `State|District` — the map's floor offline. */
export function atlasDistrictPoints(): Record<string, [number, number]> {
  return { ...G.atlasDistrictAnchors };
}

function tokens(text: string): string[] {
  const out: string[] = [];
  for (const part of text.split(/[^A-Za-z0-9&]+/)) {
    for (const piece of part.split(/(&)/)) if (piece) out.push(piece === "&" ? "and" : piece);
  }
  return out;
}

function resolveStateRun(run: string): string | null {
  const folded = fold(run);
  for (const candidate of [folded, folded.replace(/\d+$/, "")]) {
    if (!candidate) continue;
    if (candidate in G.atlasStateAliases) return G.atlasStateAliases[candidate];
    const resolved = G.stateLookup[candidate];
    if (resolved) return resolved;
  }
  return null;
}

/** `place_atlas.resolve_place`: a named place, or the state's seat, or nothing. */
export function resolvePlace(text: string | null | undefined): Place | null {
  if (!text || !text.trim()) return null;
  const words = tokens(text);
  if (!words.length) return null;
  let state: string | null = null;
  let consumed: [number, number] = [0, 0];
  outer: for (let width = Math.min(G.maxAliasWords, words.length); width > 0; width -= 1) {
    for (let start = 0; start + width <= words.length; start += 1) {
      const found = resolveStateRun(words.slice(start, start + width).join(""));
      if (found && found in G.atlasStateSeats) {
        state = found;
        consumed = [start, start + width];
        break outer;
      }
    }
  }
  for (let width = Math.min(G.maxAliasWords, words.length); width > 0; width -= 1) {
    for (let start = 0; start + width <= words.length; start += 1) {
      let overlaps = false;
      for (let i = start; i < start + width; i += 1) if (i >= consumed[0] && i < consumed[1]) overlaps = true;
      if (overlaps) continue;
      const run = fold(words.slice(start, start + width).join(""));
      const place = ALIAS_TO_PLACE.get(run) ?? ALIAS_TO_PLACE.get(run.replace(/\d+$/, ""));
      if (place) return place;
    }
  }
  if (state) {
    const [name, lat, lon] = G.atlasStateSeats[state];
    return {
      key: `state-${fold(state)}`,
      label: state,
      region: `State-level only — drawn at ${name}`,
      state,
      lat,
      lon,
      precision: "STATE",
      aliases: [],
      district: null
    };
  }
  return null;
}

export type Located = { lat: number; lon: number; state: string; label: string; precise: boolean };

/** `report_builder._geocode`. */
export function geocode(
  text: unknown,
  stateHint: unknown,
  districtPoints: Record<string, [number, number]> | null,
  clean: (value: unknown) => string
): Located | null {
  const local = clean(text).trim();
  const stateText = clean(stateHint).trim();
  const joined = [local, stateText].filter(Boolean).join(", ");
  if (joined) {
    const found = resolvePlace(joined);
    if (found && found.precision !== "STATE") {
      return { lat: found.lat, lon: found.lon, state: canonicalState(found.state) ?? found.state, label: found.label, precise: true };
    }
  }
  if (districtPoints && Object.keys(districtPoints).length) {
    const stateKey = canonicalState(stateText) ?? stateText;
    if (stateKey) {
      const parts = local
        .replace(/;/g, ",")
        .split(",")
        .map((p) => p.trim())
        .filter(Boolean);
      for (const candidate of [local, ...parts.reverse()]) {
        const district = canonicalDistrict(stateKey, candidate);
        if (!district) continue;
        const point = districtPoints[`${stateKey}|${district}`];
        if (point) return { lat: point[0], lon: point[1], state: stateKey, label: district, precise: true };
      }
    }
  }
  if (joined) {
    const found = resolvePlace(joined);
    if (found) {
      return { lat: found.lat, lon: found.lon, state: canonicalState(found.state) ?? found.state, label: found.label, precise: false };
    }
  }
  const canonical = canonicalState(stateText) ?? canonicalState(local);
  if (canonical) {
    const seat = stateSeat(canonical);
    if (seat) return { lat: seat[1], lon: seat[2], state: canonical, label: canonical, precise: false };
  }
  return null;
}
