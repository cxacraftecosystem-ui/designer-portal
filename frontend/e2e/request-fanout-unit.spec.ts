import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { getWorkshopOversight, putWorkshopOversight } from "@/app/(protected)/officers/oversight";
import { listDesignWorkshopInspectors, putDesignWorkshopInspectors } from "@/lib/designWorkshopInspections";
import { inFlightCount, shareInFlight, startNewReadGeneration } from "@/lib/inFlight";
import {
  FRESH_MS,
  forgetMyWorkshopPosts,
  myWorkshopPosts,
  resetMyWorkshopPosts
} from "@/lib/myWorkshopPosts";
import type { User } from "@/lib/types";

import { installSessionCookie } from "./support/fakeSessionCookie";

/**
 * Request fan-outs on the web client: what a screen used to ask once per row, or several times at
 * once, and now asks once. Each test counts the requests that reach `fetch`.
 *
 * ⚠ LINE-ENDING AGNOSTIC: single-line `toContain`s only.
 */

const FRONTEND = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(FRONTEND, ...parts), "utf8");
const realFetch = globalThis.fetch;

const admin = { id: "u-admin", email: "admin@example.org", name: "Admin", role: "ADMIN" } as User;

function installFakeWindow() {
  installSessionCookie("a-perfectly-good-token");
  (globalThis as Record<string, unknown>).window = {
    location: { pathname: "/media", protocol: "http:", assign: () => undefined, replace: () => undefined }
  };
}

function serve(answer: (url: string) => { status: number; body?: unknown }) {
  const asked: string[] = [];
  (globalThis as Record<string, unknown>).fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    asked.push(url);
    const reply = answer(url);
    return new Response(reply.body === undefined ? "" : JSON.stringify(reply.body), {
      status: reply.status,
      headers: { "content-type": "application/json" }
    });
  };
  return asked;
}

/**
 * A server whose GETs wait until the test lets them answer — so a read can be held in the air across
 * a save. `release(i, body)` answers the i-th held GET; anything that is not a GET answers at once.
 */
function serveHeld(other: (url: string, method: string) => { status: number; body?: unknown }) {
  const asked: { url: string; method: string }[] = [];
  const held: ((body: unknown) => void)[] = [];
  (globalThis as Record<string, unknown>).fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? "GET").toUpperCase();
    asked.push({ url, method });
    if (method !== "GET") {
      const reply = other(url, method);
      return new Response(JSON.stringify(reply.body ?? {}), {
        status: reply.status,
        headers: { "content-type": "application/json" }
      });
    }
    return new Promise<Response>((resolve) => {
      held.push((body) =>
        resolve(new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } }))
      );
    });
  };
  const gets = () => asked.filter((a) => a.method === "GET");
  return {
    asked,
    gets,
    release: (i: number, body: unknown) => held[i](body),
    async heldCount(n: number) {
      for (let spin = 0; spin < 200 && held.length < n; spin += 1) await new Promise((r) => setTimeout(r, 0));
      expect(held.length).toBe(n);
    }
  };
}

test.beforeEach(() => {
  installFakeWindow();
  resetMyWorkshopPosts();
});

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  (globalThis as Record<string, unknown>).fetch = realFetch;
});

/* ── lib/inFlight ─────────────────────────────────────────────────────────── */

test("reads asked at the same moment ride one request, and each caller gets its own copy", async () => {
  let started = 0;
  const fetchRows = () => {
    started += 1;
    return Promise.resolve({ rows: [1, 2] });
  };
  const [a, b] = await Promise.all([shareInFlight("k", fetchRows), shareInFlight("k", fetchRows)]);
  expect(started).toBe(1);
  expect(a).toEqual(b);
  a.rows.push(3);
  expect(b.rows).toEqual([1, 2]);
  expect(inFlightCount()).toBe(0);
});

test("nothing is kept once a read answers: the next ask reads afresh", async () => {
  let started = 0;
  const fetchCount = () => {
    started += 1;
    return Promise.resolve(started);
  };
  expect(await shareInFlight("again", fetchCount)).toBe(1);
  expect(await shareInFlight("again", fetchCount)).toBe(2);
});

test("a failed shared read fails every caller that rode it, and is not kept", async () => {
  let started = 0;
  const fetchFailing = () => {
    started += 1;
    return Promise.reject(new Error("down"));
  };
  const outcomes = await Promise.allSettled([shareInFlight("bad", fetchFailing), shareInFlight("bad", fetchFailing)]);
  expect(outcomes.map((o) => o.status)).toEqual(["rejected", "rejected"]);
  expect(started).toBe(1);
  expect(inFlightCount()).toBe(0);
});

test("a read asked after a new generation never rides one asked before it", async () => {
  let started = 0;
  const answers: ((value: number) => void)[] = [];
  const fetchHeld = () => {
    started += 1;
    return new Promise<number>((resolve) => answers.push(resolve));
  };
  const before = shareInFlight("gen", fetchHeld);
  startNewReadGeneration();
  const after = shareInFlight("gen", fetchHeld);
  expect(started).toBe(2);
  answers[0](1);
  answers[1](2);
  expect(await before).toBe(1);
  expect(await after).toBe(2);
  expect(inFlightCount()).toBe(0);
});

test("the oversight read and the inspector list are both shared while they are out", async () => {
  const server = serveHeld(() => ({ status: 200 }));
  const oversight = Promise.all([getWorkshopOversight("w1"), getWorkshopOversight("w1")]);
  const inspectors = Promise.all([listDesignWorkshopInspectors("w1"), listDesignWorkshopInspectors("w1")]);
  await server.heldCount(2);
  expect(server.gets()).toHaveLength(2);
  server.release(0, { workshopId: "w1", oversight: [] });
  server.release(1, { inspectors: [] });
  const [o1, o2] = await oversight;
  expect(o1).toEqual(o2);
  await inspectors;
});

test("the reload after an oversight save never rides a read sent before the save", async () => {
  const server = serveHeld(() => ({ status: 200, body: { oversight: [{ capacity: "REGIONAL_DIRECTOR", userId: "u-new" }] } }));
  const stale = getWorkshopOversight("w1");
  await server.heldCount(1);
  await putWorkshopOversight("w1", { regionalDirectorId: "u-new" });
  const reload = getWorkshopOversight("w1");
  await server.heldCount(2);
  server.release(0, { workshopId: "w1", oversight: [{ capacity: "REGIONAL_DIRECTOR", userId: "u-old" }] });
  server.release(1, { workshopId: "w1", oversight: [{ capacity: "REGIONAL_DIRECTOR", userId: "u-new" }] });
  expect((await stale).oversight[0].userId).toBe("u-old");
  expect((await reload).oversight[0].userId).toBe("u-new");
});

test("a refused oversight save still starts a new generation", async () => {
  const server = serveHeld(() => ({ status: 409, body: { detail: "Changed by someone else" } }));
  const stale = getWorkshopOversight("w1");
  await server.heldCount(1);
  await expect(putWorkshopOversight("w1", { regionalDirectorId: "u-new" })).rejects.toThrow();
  const reload = getWorkshopOversight("w1");
  await server.heldCount(2);
  server.release(0, { workshopId: "w1", oversight: [] });
  server.release(1, { workshopId: "w1", oversight: [] });
  await Promise.all([stale, reload]);
});

test("the reload after an inspector save never rides a read sent before the save", async () => {
  const server = serveHeld(() => ({ status: 200, body: { inspectors: [] } }));
  const stale = listDesignWorkshopInspectors("w1");
  await server.heldCount(1);
  await putDesignWorkshopInspectors("w1", ["u-new"]);
  const reload = listDesignWorkshopInspectors("w1");
  await server.heldCount(2);
  server.release(0, { inspectors: [{ userId: "u-old" }] });
  server.release(1, { inspectors: [{ userId: "u-new" }] });
  expect(await stale).toEqual({ inspectors: [{ userId: "u-old" }] });
  expect(await reload).toEqual({ inspectors: [{ userId: "u-new" }] });
});

test("signing out starts a new read generation and drops the held posts", () => {
  const provider = read("components", "AuthProvider.tsx");
  const logout = provider.slice(provider.indexOf("const logout = useCallback("));
  const body = logout.slice(0, logout.indexOf("}, [adopt]);"));
  expect(body).toContain("startNewReadGeneration();");
  expect(body).toContain("forgetMyWorkshopPosts();");
});

/* ── lib/myWorkshopPosts ──────────────────────────────────────────────────── */

const MY_POSTS = {
  workshops: {
    w1: { oversight: [{ capacity: "REGIONAL_DIRECTOR", userId: "u-admin" }], inspectors: [{ userId: "u-admin" }] },
    w2: { oversight: [], inspectors: [{ userId: "u-admin" }] }
  }
};

test("every workshop's posts arrive in ONE request, however many workshops a screen names", async () => {
  const asked = serve(() => ({ status: 200, body: MY_POSTS }));
  const answers = await Promise.all(["w1", "w2", "w3", "w1"].map(async () => myWorkshopPosts(admin)));
  expect(asked).toHaveLength(1);
  expect(asked[0]).toContain("/design-workshop-oversight/my-posts");
  const posts = answers[0];
  expect(posts?.get("w1")).toEqual(["REGIONAL_DIRECTOR", "INSPECTION"]);
  expect(posts?.get("w2")).toEqual(["INSPECTION"]);
  expect(posts?.get("w3")).toBeUndefined();
});

test("the answer is shared across screens until a save drops it", async () => {
  const asked = serve(() => ({ status: 200, body: MY_POSTS }));
  await myWorkshopPosts(admin);
  await myWorkshopPosts(admin);
  expect(asked).toHaveLength(1);
  forgetMyWorkshopPosts();
  await myWorkshopPosts(admin);
  expect(asked).toHaveLength(2);
});

test("a server without the route is asked per workshop from then on, as before", async () => {
  const asked = serve(() => ({ status: 404, body: { detail: "Record not found" } }));
  expect(await myWorkshopPosts(admin)).toBeNull();
  expect(await myWorkshopPosts(admin)).toBeNull();
  expect(asked).toHaveLength(1);
});

test("any other failure is none known, and the next screen asks again", async () => {
  const asked = serve(() => ({ status: 422, body: { detail: "Something was refused" } }));
  const first = await myWorkshopPosts(admin);
  expect(first?.size).toBe(0);
  await myWorkshopPosts(admin);
  expect(asked).toHaveLength(2);
});

test("the held answer lasts seconds, not half a minute", () => {
  expect(FRESH_MS).toBeLessThanOrEqual(5_000);
});

test("the held answer is asked again once it is older than FRESH_MS", async () => {
  const asked = serve(() => ({ status: 200, body: MY_POSTS }));
  const realNow = Date.now;
  let now = realNow();
  Date.now = () => now;
  try {
    await myWorkshopPosts(admin);
    now += FRESH_MS - 1;
    await myWorkshopPosts(admin);
    expect(asked).toHaveLength(1);
    now += 2;
    await myWorkshopPosts(admin);
    expect(asked).toHaveLength(2);
  } finally {
    Date.now = realNow;
  }
});

for (const [name, save] of [
  ["an oversight save", () => putWorkshopOversight("w1", { regionalDirectorId: null })],
  ["an inspector save", () => putDesignWorkshopInspectors("w1", [])]
] as const) {
  test(`${name} drops the held answer, answered or refused`, async () => {
    for (const status of [200, 409]) {
      resetMyWorkshopPosts();
      const asked = serve((url) =>
        url.includes("/my-posts") ? { status: 200, body: MY_POSTS } : { status, body: { oversight: [], inspectors: [] } }
      );
      await myWorkshopPosts(admin);
      await save().catch(() => undefined);
      await myWorkshopPosts(admin);
      expect(asked.filter((url) => url.includes("/my-posts"))).toHaveLength(2);
    }
  });
}

/* ── Screens ──────────────────────────────────────────────────────────────── */

test("the measure tab resolves its photographs through the media batch, not one GET each", () => {
  const host = read("components", "sketches", "UploadTabHost.tsx");
  expect(host).toContain("const media: MediaFile = await fetchMediaRow(ref);");
  expect(host).not.toContain("apiFetch<MediaFile>(`/media/${ref}`)");
});

test("the task board's chip counts come with the list instead of four more requests", () => {
  const page = read("app", "(protected)", "tasks", "page.tsx");
  expect(page).toContain('withStatusCounts: "true"');
  expect(page).toContain("if (data.statusCounts) setCounts(data.statusCounts);");
});

test("the unread count polls once for the tab, not once per subscriber", () => {
  const hook = read("components", "hooks", "useUnreadNotifications.ts");
  expect(hook).toContain("let pollTimer: number | null = null;");
  expect(hook).toContain("startPolling();");
  expect(hook.match(/window\.setInterval\(/g) ?? []).toHaveLength(1);
});

test("with the Failed pill on, the jobs panel does not ask for the failed count twice", () => {
  const panel = read("components", "media", "MediaJobsPanel.tsx");
  expect(panel).toContain('if (filter === "FAILED") {');
  expect(panel).toContain("return { result, failedTotal: result.total };");
});

test("opening a second people card asks only for that card", () => {
  const page = read("app", "(protected)", "ministry-dashboard", "page.tsx");
  expect(page).toContain("(peopleKind) => peopleLanded.current[peopleKind] !== loadToken");
  expect(page).toContain("peopleLanded.current[peopleKind] = loadToken;");
});
