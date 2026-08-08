/**
 * Forward geocoding — typing "Barpali" and being taken there.
 *
 * WHAT IT IS FOR. Both map pickers in this app open on the centre of India at zoom 4. A designer
 * documenting a cluster in rural Odisha then has to pan and pinch roughly two thousand kilometres to
 * find a village they could have named in eight keystrokes, on the rural connection every other
 * comment in this area is written for. Every tile that scroll drags down is a tile MapTiler bills for
 * and a tile that link has to carry.
 *
 * WHAT IT IS EMPHATICALLY NOT FOR, and the rule the rest of this feature is built around: NOTHING
 * HERE EVER SETS A VALUE. A result of this search moves the CAMERA. The coordinate that reaches a
 * research record is the one the designer then places by hand, and the state and district that reach
 * it come from the reverse path — `LocationFields.resolvePoint`, which puts the geocoder's spelling
 * through the same closed lists `backend/app/services/address.py` validates a write against. That is
 * the whole reason this module returns no address fields at all: it CANNOT be wired into a record by
 * accident, because it has nothing a record wants. A search that dropped a pin would put a
 * geocoder's guess into a research record with nobody in the loop, which is character-for-character
 * the failure `LocationFields` exists to have ended ("The geocoder may offer a value; a person
 * accepts it").
 *
 * THE PARAMETERS ARE MAPTILER'S, checked against docs.maptiler.com/cloud/api/geocoding/ — `country`,
 * `proximity`, `limit` (1–10), `autocomplete`, `language`. Mapbox's geocoding API takes a set that
 * looks very nearly the same and is a different service; nothing here was copied from it.
 */

import { ApiError } from "@/lib/api";
import { isTransient, isUnreachable } from "@/lib/offline";

const maptilerKey = process.env.NEXT_PUBLIC_MAPTILER_API_KEY;

/**
 * Whether places can be looked up at all in this build.
 *
 * The same per-tenant key that draws the tiles, so this is true exactly when there is a map to move —
 * which is why the callers never have to reason about the two separately.
 */
export function placeSearchAvailable(): boolean {
  return Boolean(maptilerKey);
}

/** One place the geocoder offers. Deliberately camera-shaped: a point and an extent, no address. */
export type PlaceHit = {
  id: string;
  /** "Barpali" — the name on its own, which is what the designer typed and wants to recognise. */
  name: string;
  /** "Paikmal, Odisha, India" — everything after the name, and the ONLY thing telling two Barpalis apart. */
  context: string;
  lon: number;
  lat: number;
  /**
   * `[w, s, e, n]` when the feature has an extent.
   *
   * A district or a city is an area, and flying to its centre at village zoom shows a designer one
   * arbitrary street inside it. Framing the extent shows them the thing they asked for.
   */
  bbox?: [number, number, number, number];
};

/**
 * India only.
 *
 * Every cluster this repository documents is Indian, the address register it validates against is
 * the 36 Indian states and 795 Indian districts, and without this a search for "Bagru" ranks a
 * same-named place elsewhere in the world above the block-printing town in Rajasthan. There is no
 * setting for it because there is no second answer.
 */
const COUNTRY = "in";

/** MapTiler caps `limit` at 10. Eight fills the panel without asking anyone to scroll a menu. */
const LIMIT = 8;

/**
 * Below this, a query is noise: one or two letters match thousands of places, so the request costs a
 * quota unit to return a list nobody can choose from. The caller says so rather than searching.
 */
export const MIN_QUERY_LENGTH = 3;

type GeocodeFeature = {
  id?: string;
  text?: string;
  place_name?: string;
  center?: [number, number];
  bbox?: [number, number, number, number];
};

/**
 * Split "Barpali, Paikmal, Odisha, India" into the name and the part that disambiguates it.
 *
 * `place_name` repeats `text` as its first element, so printing both would read "Barpali — Barpali,
 * Paikmal, Odisha, India" on every row. The remainder is the half that matters: the live API returns
 * four Barpalis for one query, in two states, and the only thing separating them is this string.
 */
function describe(feature: GeocodeFeature): { name: string; context: string } {
  const name = (feature.text ?? "").trim();
  const full = (feature.place_name ?? "").trim();
  if (!name) return { name: full, context: "" };
  if (!full || full === name) return { name, context: "" };
  return { name, context: full.startsWith(`${name},`) ? full.slice(name.length + 1).trim() : full };
}

/**
 * Ask MapTiler which places answer to this name.
 *
 * Throws rather than returning an empty list on failure, because "the search is down" and "there is
 * no such village" are different sentences to a designer standing in the village — see
 * {@link describeSearchFailure}. An empty ARRAY is the honest answer to the second one only.
 *
 * `proximity` is read from the caller at call time rather than passed down as a prop, so a map the
 * designer is panning cannot re-render this module's callers on every frame.
 */
export async function searchPlaces(
  query: string,
  { signal, proximity }: { signal: AbortSignal; proximity?: { lon: number; lat: number } | null }
): Promise<PlaceHit[]> {
  if (!maptilerKey) {
    // Not reachable from the UI, which hides the box without a key — but a throw here means a future
    // caller that forgets the check fails loudly instead of searching against `?key=undefined`.
    throw new ApiError(503, "This build has no map key, so places cannot be looked up.", null);
  }

  const parameters = new URLSearchParams({
    key: maptilerKey,
    country: COUNTRY,
    language: "en",
    limit: String(LIMIT),
    // The designer is typing a prefix, not a finished name; this is what makes the list useful before
    // they have spelt the whole village.
    autocomplete: "true"
  });
  // Bias to where the map is already looking. Without it a cluster in Odisha is outranked by a
  // same-named town on the other side of the country — verified against the live API, where
  // "Barpali" returns two Odisha places and two Chhattisgarh ones for the same six letters.
  if (proximity) parameters.set("proximity", `${proximity.lon},${proximity.lat}`);

  const response = await fetch(
    `https://api.maptiler.com/geocoding/${encodeURIComponent(query)}.json?${parameters.toString()}`,
    { signal }
  );
  if (!response.ok) {
    throw new ApiError(response.status, `The place search answered ${response.status}.`, null);
  }

  let body: { features?: GeocodeFeature[] };
  try {
    body = (await response.json()) as { features?: GeocodeFeature[] };
  } catch {
    // A 200 carrying something that is not JSON is the captive portal this repo has already been
    // bitten by (see the "Sync now" note in lib/offline.ts): the wi-fi answered, the geocoder did
    // not. 502 puts it in the try-again bucket below, which is the truthful next move.
    throw new ApiError(502, "The place search answered with something that could not be read.", null);
  }

  return (body.features ?? [])
    .filter((feature): feature is GeocodeFeature & { center: [number, number] } =>
      Array.isArray(feature.center) && Number.isFinite(feature.center[0]) && Number.isFinite(feature.center[1])
    )
    .map((feature, index) => {
      const { name, context } = describe(feature);
      return {
        id: feature.id ?? `hit-${index}`,
        name,
        context,
        lon: feature.center[0],
        lat: feature.center[1],
        ...(feature.bbox && feature.bbox.length === 4 ? { bbox: feature.bbox } : {})
      };
    });
}

/**
 * Why the search did not answer, in the words the designer needs.
 *
 * THREE BUCKETS, AND THEY ARE THIS REPOSITORY'S EXISTING TWO QUESTIONS, not a third vocabulary.
 * `isUnreachable` asks "did anything reach a server at all"; `isTransient` asks "is it worth trying
 * again". The reason they are kept apart is written at length above `isUnreachable` in
 * `lib/offline.ts`: telling somebody their signal is at fault when the server answered sends them out
 * of the building to look for a better one and leaves a real bug wearing an offline message. A
 * designer in the field with one bar has to be told "no connection", and a designer whose tenant has
 * run out of quota has to be told something an administrator can act on.
 *
 * None of these is "no such place" — that is an empty ARRAY from a successful request, and it is the
 * caller's to word, because it is not a failure.
 */
export function describeSearchFailure(error: unknown): string {
  if (isUnreachable(error)) {
    return "No connection, so places cannot be looked up right now. The map still works, and the coordinate boxes need no network at all.";
  }
  if (isTransient(error)) {
    return "The place search is not answering just now — this is the service, not your connection. Try again in a moment.";
  }
  return "The place search refused this request: this build's map key may be missing, expired or out of quota. Nothing is wrong with what you typed, and an administrator can check the key.";
}
