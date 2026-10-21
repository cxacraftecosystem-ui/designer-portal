import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { inFlightCount, shareInFlight } from "@/lib/inFlight";
import { forgetMyWorkshopPosts, myWorkshopPosts, resetMyWorkshopPosts } from "@/lib/myWorkshopPosts";
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

test("the oversight read and the inspector list are both shared while they are out", () => {
  expect(read("app", "(protected)", "officers", "oversight.ts")).toContain(
    "return shareInFlight(path, () => apiFetch<DwWorkshopOversight>(path));"
  );
  expect(read("lib", "designWorkshopInspections.ts")).toContain(
    "return shareInFlight(path, () => apiFetch<DwInspectorList>(path));"
  );
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

test("a save of oversight or inspectors drops the held answer", () => {
  expect(read("app", "(protected)", "officers", "oversight.ts")).toContain("forgetMyWorkshopPosts();");
  expect(read("lib", "designWorkshopInspections.ts")).toContain("forgetMyWorkshopPosts();");
});

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
