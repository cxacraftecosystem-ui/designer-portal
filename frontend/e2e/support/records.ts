import { expect, type APIRequestContext } from "@playwright/test";

import { API, bearer } from "./session";

/**
 * Fixtures a spec builds for itself, through the same API a person uses.
 *
 * WHY THIS EXISTS AT ALL. Several specs used to open a record by a literal id copied out of the
 * production database — `cmqj4o5rc0079kb592tclmly4` and friends. Those ids do not exist in a local
 * database, so the specs 404'd and reported a failure about location cards that was really a
 * failure about which database was in front of them. Worse, each id is one `prisma migrate reset`
 * away from being wrong for everybody. A spec that builds the record it needs is the only kind that
 * survives a database reset, and it also states, in code, exactly which properties of the record the
 * assertions depend on — which a copied id never does.
 */

/**
 * A Verhoeff-valid 12-digit number, unique per run.
 *
 * `aadhaarNumber` is the repository's deduplication key and is UNIQUE in the database, so a fixed
 * fixture number passes once and then 409s on every later run — a test that skips itself after the
 * first execution is a test that stopped existing. The checksum is the UIDAI one the API enforces
 * (`app/services/artisan_identity.verhoeff_ok`); these are fixtures, not anybody's Aadhaar.
 */
export function verhoeffAadhaar(seed: number): string {
  const d = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6], [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4], [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
  ];
  const p = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4],
    [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7],
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
    [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]
  ];
  const inv = [0, 4, 3, 2, 1, 5, 6, 7, 8, 9];
  // 11 digits from the seed, never starting 0 or 1 (UIDAI reserves those).
  const body = String(2 + (seed % 8)) + String(seed).padStart(10, "0").slice(-10);
  let c = 0;
  body.split("").reverse().forEach((digit, i) => {
    c = d[c][p[(i + 1) % 8][Number(digit)]];
  });
  return body + String(inv[c]);
}

/** Unique-enough seed for one fixture. */
export function stamp(): number {
  return Date.now() % 1_000_000_000 + Math.floor(Math.random() * 1000);
}

/**
 * Whichever craft the repository already has.
 *
 * An artisan must be assigned to one and the seed set differs between databases, so borrowing beats
 * naming: a spec that hard-codes a craft id is the same mistake as a spec that hard-codes an artisan.
 * Returns null when the database has none, so the caller can skip with a reason rather than 422.
 */
export async function anyCraftId(request: APIRequestContext, token: string): Promise<string | null> {
  const res = await request.get(`${API}/api/crafts?pageSize=1`, { headers: bearer(token) });
  if (!res.ok()) return null;
  return ((await res.json())?.items?.[0]?.id as string | undefined) ?? null;
}

export type ArtisanLocation = {
  state?: string | null;
  district?: string | null;
  village?: string;
  latitude: number;
  longitude: number;
};

/**
 * Create an artisan and return its id.
 *
 * A REAL payload, and it has to be: `aadhaarNumber` is checked against the UIDAI Verhoeff checksum,
 * `dos`/`donts` must be non-empty, and — measured against this API, not assumed — a create is
 * REFUSED without a location and refused again if that location has no state and district. A
 * fixture that could cut corners here would be exercising something the product does not do.
 * See `unstateTheAddress` for how the specs about legacy rows get past that on purpose.
 */
export async function createArtisan(
  request: APIRequestContext,
  token: string,
  seed: { name: string; place: string; craftId: string; location: ArtisanLocation }
): Promise<string> {
  const res = await request.post(`${API}/api/artisans`, {
    headers: bearer(token),
    data: {
      name: seed.name,
      place: seed.place,
      craftId: seed.craftId,
      aadhaarNumber: verhoeffAadhaar(stamp()),
      dos: "Keep the warp evenly tensioned.",
      donts: "Do not wash the tied yarn before dyeing.",
      location: seed.location
    }
  });
  expect(res.status(), `artisan fixture "${seed.name}": ${await res.text()}`).toBe(201);
  return (await res.json()).id as string;
}

/**
 * Strip the stated state and district back off a record, leaving the coordinate.
 *
 * This is the ONLY way to build the record the "two groups" spec is about — a coordinate with no
 * stated address behind it — because the create endpoint refuses one. The refusal is correct and is
 * itself under test elsewhere: the rule applies to records being made NOW, while the rows it has to
 * tolerate are the ones made before it existed. A PATCH can still empty them, which is what a
 * migration that added the columns left behind, so this reproduces that history rather than
 * pretending the create rule is weaker than it is.
 */
export async function unstateTheAddress(
  request: APIRequestContext,
  token: string,
  artisanId: string,
  location: { latitude: number; longitude: number },
  place?: string
) {
  const res = await request.patch(`${API}/api/artisans/${artisanId}`, {
    headers: bearer(token),
    data: {
      ...(place ? { place } : {}),
      location: { ...location, state: null, district: null }
    }
  });
  expect(res.ok(), `unstate the address on ${artisanId}: ${res.status()} ${await res.text()}`).toBeTruthy();
}

type ArtisanRow = { id: string; place?: string | null; location?: { state?: string | null } | null };

/**
 * The first artisan the repository already holds that matches `predicate`, or null.
 *
 * For the records a spec cannot legally create. The mandatory-location rule refuses a create with no
 * location at all, so the "record that predates the rule" can only be FOUND, never built — and a
 * spec that hard-codes which one it is breaks the first time the database is reseeded. Searching
 * says what the spec actually depends on ("some artisan with no location row") instead of naming a
 * row that happened to have that shape once.
 */
export async function findArtisan(
  request: APIRequestContext,
  token: string,
  predicate: (row: ArtisanRow) => boolean
): Promise<string | null> {
  const res = await request.get(`${API}/api/artisans?pageSize=100`, { headers: bearer(token) });
  if (!res.ok()) return null;
  const items = ((await res.json())?.items ?? []) as ArtisanRow[];
  return items.find(predicate)?.id ?? null;
}

/** Create a design workshop and return its id. */
export async function createWorkshop(
  request: APIRequestContext,
  token: string,
  title: string
): Promise<string> {
  const res = await request.post(`${API}/api/design-workshops`, {
    headers: bearer(token),
    data: { title }
  });
  expect(res.ok(), `create a design workshop: ${res.status()} ${await res.text()}`).toBeTruthy();
  return (await res.json()).id as string;
}

/**
 * Write one or more entities onto one stage.
 *
 * `droppedKeys` is asserted empty because the stage PUT accepts an entity key it does not know,
 * returns 200, and silently discards the payload — so a fixture with a typo'd `entityKey` seeds
 * nothing and the spec then fails in the browser complaining about a missing field. `errors` is
 * asserted for the same reason, one field at a time. `submit` stays false: these fixtures are
 * deliberately half-filled and a submitting save would 422.
 */
export async function saveStage(
  request: APIRequestContext,
  token: string,
  workshopId: string,
  stageKey: string,
  entries: { entityKey: string; ordinal?: number; data: Record<string, unknown> }[],
  options: { replaceCollections?: boolean } = {}
) {
  const res = await request.put(`${API}/api/design-workshops/${workshopId}/stages/${stageKey}`, {
    headers: bearer(token),
    data: { entries, submit: false, ...options }
  });
  expect(res.ok(), `save ${stageKey}: ${res.status()} ${await res.text()}`).toBeTruthy();
  const body = await res.json();
  expect(body.droppedKeys ?? [], `${stageKey} seed named an entity the server knows`).toEqual([]);
  expect(body.errors ?? {}, `${stageKey} accepted every value the fixture sent`).toEqual({});
  return body;
}

/**
 * Best-effort teardown.
 *
 * Deliberately does not assert: a spec whose teardown fails should not turn a green run red, and the
 * record it leaves behind costs a local database nothing. It exists so a developer's `/artisans`
 * list is not a hundred fixtures deep after a week of runs.
 */
export async function discard(request: APIRequestContext, token: string, path: string) {
  await request.delete(`${API}${path}`, { headers: bearer(token) }).catch(() => undefined);
}
