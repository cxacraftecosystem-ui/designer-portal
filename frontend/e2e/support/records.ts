import { expect, type APIRequestContext } from "@playwright/test";

import { API, bearer } from "./session";

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
  const body = String(2 + (seed % 8)) + String(seed).padStart(10, "0").slice(-10);
  let c = 0;
  body.split("").reverse().forEach((digit, i) => {
    c = d[c][p[(i + 1) % 8][Number(digit)]];
  });
  return body + String(inv[c]);
}

export function stamp(): number {
  return Date.now() % 1_000_000_000 + Math.floor(Math.random() * 1000);
}

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

export async function discard(request: APIRequestContext, token: string, path: string) {
  await request.delete(`${API}${path}`, { headers: bearer(token) }).catch(() => undefined);
}
