import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";

import { expect, test, type APIRequestContext, type BrowserContext, type Page } from "@playwright/test";

import { API, CREDENTIALS_MISSING, EMAIL, apiToken, bearer, signInWithToken } from "./support/session";
import { greyPng } from "./support/qrPhotograph";

/**
 * HOW FAST THE DESIGN-WORKSHOP STAGE FORM OPENS, AND HOW FAST IT ANSWERS A KEY — measured, not felt.
 *
 * The stage form is the screen designers spend their day on, and the UX audit timed it at ~4.6 s to
 * open against a 1.3 s median for every other screen, with ~85 ms per keystroke. This spec is the
 * instrument the speed work was done with and the one to re-run before anybody claims it regressed
 * or improved. It is NOT a pass/fail gate: timings on a developer laptop are noise around a trend,
 * and a gate that flakes is a gate people switch off. It prints a table and writes
 * `test-results/stage-form-speed.json`; the only assertions are that the form actually opened.
 *
 * NOT A `-unit` SPEC, deliberately: it needs the API, the database and a built frontend, so it is
 * outside `npm run test:unit` by its name. It is also SKIPPED unless `E2E_STAGE_SPEED=1`, because it
 * seeds a 22-stage workshop with 40 photographs and has no business running in an ordinary e2e pass.
 *
 * ── THE COMMAND ─────────────────────────────────────────────────────────────────────────────────
 *
 *   npx next build && npx next start -p 3000          # a BUILD: dev-mode timings mean nothing
 *   E2E_STAGE_SPEED=1 E2E_EMAIL=admin2@example.org E2E_PASSWORD='LocalDev123!' \
 *     npx playwright test e2e/stage-form-speed.spec.ts
 *
 * Optional: `E2E_CPU_THROTTLE=4` slows the CPU four times through the DevTools protocol (a modest
 * field laptop); `E2E_STAGE_KEY=<key>` measures a named stage instead of stage 4 (see `DEFAULT_STAGE`);
 * `E2E_SPEED_PG_CONTAINER` names the Postgres container (default `designer-portal-pg`) and
 * `E2E_SPEED_PG_DB` the database the API is pointed at; `E2E_STAGE_SPEED_WORKSHOP=<id>` re-measures a
 * workshop an earlier run seeded (its id is in the JSON), so a before and an after read the same rows;
 * `E2E_STAGE_SPEED_PROFILE=1` adds a CPU profile of the typing (readable against a
 * `next build --no-mangling` build).
 *
 * ── WHAT IS SEEDED ──────────────────────────────────────────────────────────────────────────────
 *
 * One workshop, every one of the 22 stages filled from the registry itself (so a field added to
 * `stage_definitions.py` is seeded without this file changing): every singleton field answered,
 * every collection given rows, rich text with headings, paragraphs and a table, and 40 photographs
 * spread over the measured stage's image fields. The photographs are `MediaFile` rows written
 * straight into the database — the local stack's object store is not needed and is usually not
 * running — and their bytes are served to the browser by a route below, so every picture still
 * costs a real request and a real decode.
 *
 * ── WHAT IS MEASURED ────────────────────────────────────────────────────────────────────────────
 *
 * - **Open, cold**: a hard load of the stage URL in a browser that has never held this workshop
 *   (empty IndexedDB) — the first open on a device, which waits for the server's copy.
 * - **Open, warm**: the same URL again in the same browser — every later open.
 * - **Ready** = the "Save stage" button and the first stage box both enabled. **Interactive** = ready,
 *   and the main thread then free of long tasks (>50 ms) for 500 ms; it is the later of the two.
 *   **Settled** = the UX audit's measure: nothing in flight, and no request finished and no long task
 *   ended for 500 ms.
 * - **Requests**: every request the page made until settled, split API / script / image, with
 *   the API's own time per call (request start to first byte) and its payload size.
 * - **Script**: compressed and decoded bytes of every `/_next/static` script the route loaded.
 * - **Keystroke**: 40 characters typed into a plain text box and into the rich-text editor; each is
 *   timed from the key event to the frame after the next paint (`requestAnimationFrame` then a task),
 *   which is the latency a designer feels, including React's render and the browser's layout.
 * - The same keystroke figure with the document preview open beside the form.
 */

const RUN = process.env.E2E_STAGE_SPEED === "1";
const CPU_THROTTLE = Number(process.env.E2E_CPU_THROTTLE ?? 1);
const PG_CONTAINER = process.env.E2E_SPEED_PG_CONTAINER ?? "designer-portal-pg";
const PG_DB = process.env.E2E_SPEED_PG_DB ?? "design_workshop_speed";
const PHOTOS = 40;

type Field = {
  key: string;
  type: string;
  enum?: string;
  options?: { value: string }[];
  refModel?: string;
  maxLength?: number;
  maxItems?: number;
  minValue?: number;
  maxValue?: number;
  format?: string;
  derivedKind?: string;
  deprecated?: boolean;
  captionFor?: string;
};
type Entity = { key: string; cardinality: "SINGLETON" | "COLLECTION"; fields: Field[] };
type Stage = { key: string; number: number; title: string; entities: Entity[] };
type Registry = { enums: Record<string, { value: string }[]>; stages: Stage[] };

/* ── Seeding ─────────────────────────────────────────────────────────────────────────────────── */

const SENTENCES = [
  "The cluster works with handloom cotton and natural dyes sourced from within twenty kilometres.",
  "Most artisans learned the craft from a parent and have practised it for more than fifteen years.",
  "Orders arrive through a cooperative, and payment is usually made within six weeks of delivery.",
  "Designers noted that the motifs are drawn freehand and vary between households in the village.",
  "Younger weavers asked for smaller products that can be sold online without a middleman."
];

function prose(count: number, seed: number): string {
  return Array.from({ length: count }, (_, i) => SENTENCES[(seed + i) % SENTENCES.length]).join(" ");
}

function richDoc(seed: number) {
  const span = (text: string) => [{ text }];
  return {
    blocks: [
      { kind: "HEADING", spans: span("Observations from the visit") },
      { kind: "PARAGRAPH", spans: span(prose(4, seed)) },
      { kind: "PARAGRAPH", spans: span(prose(3, seed + 2)) },
      { kind: "BULLET_ITEM", spans: span("Natural dyes, indigo and madder") },
      { kind: "BULLET_ITEM", spans: span("Pit looms in most households") },
      {
        kind: "TABLE",
        rows: [
          ["Product", "Price (Rs)", "Units a month"],
          ["Stole", "850", "40"],
          ["Cushion cover", "420", "65"],
          ["Table runner", "600", "30"]
        ]
      },
      { kind: "PARAGRAPH", spans: span(prose(2, seed + 4)) }
    ]
  };
}

function clip(text: string, field: Field): string {
  return field.maxLength ? text.slice(0, field.maxLength) : text;
}

/** A plausible answer for one field, or undefined to leave it blank (references, files, recordings). */
function answer(field: Field, registry: Registry, seed: number, photos: () => string[]): unknown {
  if (field.deprecated || field.derivedKind) return undefined;
  const options = field.options ?? (field.enum ? registry.enums[field.enum] : undefined) ?? [];
  switch (field.type) {
    case "TEXT":
      if (field.format === "EMAIL") return "artisan@example.org";
      if (field.format === "PHONE_IN") return "9876543210";
      if (field.format === "PINCODE") return "302001";
      if (field.format) return undefined;
      return clip(`Sample answer ${seed}`, field);
    case "LONG_TEXT":
      return clip(prose(3, seed), field);
    case "RICH_TEXT":
      return richDoc(seed);
    case "INT":
    case "DECIMAL":
    case "PERCENT": {
      const low = field.minValue ?? 0;
      const high = field.maxValue ?? low + 100;
      return Math.max(low, Math.min(high, low + 3 + (seed % 7)));
    }
    case "MONEY":
      return `${1200 + seed * 10}.00`;
    case "DATE":
      return "2026-09-01";
    case "TIME":
      return "10:30";
    case "BOOL":
      return seed % 2 === 0;
    case "ENUM":
      return field.refModel ? undefined : options[seed % Math.max(1, options.length)]?.value;
    case "MULTI_ENUM":
      return field.refModel ? undefined : options.slice(0, 2).map((option) => option.value);
    case "TAGS":
      return ["handloom", "natural dye", "cooperative"];
    case "GEO":
      return { lat: 26.82 + seed / 1000, lon: 75.54, accuracy: 12 };
    case "URL":
      return "https://example.org/cluster";
    case "EMAIL":
      return "artisan@example.org";
    case "PHONE":
      return "+919876543210";
    case "IMAGE": {
      const [one] = photos();
      return one;
    }
    case "IMAGE_LIST": {
      const many = photos();
      return many.length ? many : undefined;
    }
    default:
      // REF, FILE, AUDIO, VIDEO: a reference needs a record and a recording needs bytes; neither
      // changes what the form costs to draw, so both are left blank rather than faked.
      return undefined;
  }
}

function psql(sql: string): string {
  return execFileSync("docker", ["exec", PG_CONTAINER, "psql", "-U", "postgres", "-d", PG_DB, "-tAc", sql], {
    encoding: "utf8"
  }).trim();
}

/**
 * The stage measured, and the one the photographs are put on.
 *
 * Stage 4 by default: eighteen rich-text narratives and four photograph galleries, the stage a
 * designer writes most of the report on. `E2E_STAGE_KEY` names another — `WORKSHOP_SETUP` is the one
 * the UX audit timed, `WORKSHOP_PLAN_PARTICIPANTS_OPENING` the participant roster.
 */
const DEFAULT_STAGE = "CLUSTER_CRAFT_BACKGROUND";

function targetStage(registry: Registry): Stage {
  const named = process.env.E2E_STAGE_KEY ?? DEFAULT_STAGE;
  const found = registry.stages.find((stage) => stage.key === named);
  if (!found) throw new Error(`${named} is not a stage in the registry`);
  return found;
}

async function seedWorkshop(request: APIRequestContext, token: string) {
  const registry = (await (await request.get(`${API}/api/design-workshops/schema`, { headers: bearer(token) })).json()) as Registry;
  const target = targetStage(registry);
  const created = await request.post(`${API}/api/design-workshops`, {
    headers: bearer(token),
    data: { title: `Stage speed ${Date.now()}` }
  });
  expect(created.ok(), `create workshop: ${created.status()} ${await created.text()}`).toBeTruthy();
  const workshopId = (await created.json()).id as string;

  const userId = psql(`select id from "User" where lower(email) = lower('${EMAIL.replace(/'/g, "''")}')`);
  expect(userId, "the signed-in account exists in the database the API uses").not.toEqual("");
  const stamp = Date.now();
  const photoIds = Array.from({ length: PHOTOS }, (_, i) => `speed${stamp}p${i}`);
  const values = photoIds
    .map(
      (id, i) =>
        `('${id}', 'photo-${i}.jpg', 'IMAGE', 'image/png', 20000, 'design-workshop', 'media/speed/${stamp}/${i}.png', ` +
        `'http://localhost:9000/design-workshop/media/speed/${stamp}/${i}.png', '${userId}', '${workshopId}', now(), ` +
        `'{"width": 1600, "height": 1200}'::jsonb)`
    )
    .join(",");
  psql(
    `insert into "MediaFile" (id, "originalFilename", "mediaType", "mimeType", "sizeBytes", bucket, "objectKey", url, ` +
      `"uploadedById", "designWorkshopId", "updatedAt", "extraMetadata") values ${values}`
  );

  for (const stage of registry.stages) {
    const measured = stage.key === target.key;
    const pool = measured ? [...photoIds] : [];
    const entries: { entityKey: string; ordinal: number; data: Record<string, unknown> }[] = [];
    let seed = stage.number * 13;
    for (const entity of stage.entities) {
      const rows = entity.cardinality === "SINGLETON" ? 1 : measured ? 8 : 4;
      for (let ordinal = 0; ordinal < rows; ordinal++) {
        const data: Record<string, unknown> = {};
        for (const field of entity.fields) {
          seed += 1;
          const take = () => {
            if (field.type === "IMAGE") return pool.splice(0, 1);
            // Singleton galleries take the bulk; a row's gallery takes two.
            const want = entity.cardinality === "SINGLETON" ? 10 : 2;
            return pool.splice(0, Math.min(want, field.maxItems ?? want));
          };
          const value = answer(field, registry, seed, take);
          if (value !== undefined) data[field.key] = value;
        }
        entries.push({ entityKey: entity.key, ordinal, data });
      }
    }
    const saved = await request.put(`${API}/api/design-workshops/${workshopId}/stages/${stage.key}`, {
      headers: bearer(token),
      data: { entries, submit: false }
    });
    expect(saved.ok(), `seed ${stage.key}: ${saved.status()} ${await saved.text()}`).toBeTruthy();
  }
  return { workshopId, stage: target };
}

/* ── Measuring ───────────────────────────────────────────────────────────────────────────────── */

/**
 * Installed before any page script runs: long tasks, and the per-keystroke clock.
 *
 * TWO CLOCKS PER KEY, both started at the event's own timestamp:
 *  - `__keyWork` stops in the first task after the key's own: everything the page DID for the key —
 *    the handlers, React's render, commit and effects. This is the figure held to the 16 ms budget,
 *    because it is the part the page controls and the part that makes the next key wait.
 *  - `__keys` stops in a task queued from the first animation frame after it — the frame that paints
 *    the key — so it adds style, layout, paint AND the wait for the display's next refresh, which on
 *    a 60 Hz screen is anything up to 16.7 ms on its own. It is what a designer sees.
 */
function instrument() {
  const w = window as unknown as {
    __longTasks: { start: number; end: number }[];
    __keys: number[];
    __keyWork: number[];
  };
  w.__longTasks = [];
  w.__keys = [];
  w.__keyWork = [];
  try {
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) w.__longTasks.push({ start: entry.startTime, end: entry.startTime + entry.duration });
    }).observe({ type: "longtask", buffered: true });
  } catch {
    // Not supported: interactive degrades to ready.
  }
  window.addEventListener(
    "keydown",
    (event) => {
      const t0 = event.timeStamp;
      const work = new MessageChannel();
      work.port1.onmessage = () => w.__keyWork.push(performance.now() - t0);
      work.port2.postMessage(0);
      requestAnimationFrame(() => {
        const channel = new MessageChannel();
        channel.port1.onmessage = () => w.__keys.push(performance.now() - t0);
        channel.port2.postMessage(0);
      });
    },
    true
  );
}

type OpenResult = {
  readyMs: number;
  interactiveMs: number;
  settledMs: number;
  longTaskMs: number;
  api: { url: string; at: number; done: number; ms: number; kb: number }[];
  requests: { api: number; script: number; image: number; other: number };
  scriptKb: { compressed: number; decoded: number };
  scriptKbBeforeReady: { count: number; compressed: number; decoded: number };
};

async function waitReady(page: Page): Promise<number> {
  return page.evaluate(
    () =>
      new Promise<number>((resolve, reject) => {
        const deadline = performance.now() + 60_000;
        const check = () => {
          const save = [...document.querySelectorAll("button")].find((b) => b.textContent?.trim() === "Save stage");
          const box = document.querySelector(
            "main [data-dw-field] textarea:not([disabled]), main [data-dw-field] input[type=text]:not([disabled]), main [data-dw-field] [contenteditable=true]"
          );
          if (save && !(save as HTMLButtonElement).disabled && box) return resolve(performance.now());
          if (performance.now() > deadline) return reject(new Error("the stage form never became ready"));
          requestAnimationFrame(check);
        };
        check();
      })
  );
}

async function waitQuiet(page: Page, readyMs: number): Promise<{ interactiveMs: number; longTaskMs: number }> {
  return page.evaluate(
    (ready) =>
      new Promise<{ interactiveMs: number; longTaskMs: number }>((resolve) => {
        const w = window as unknown as { __longTasks: { start: number; end: number }[] };
        const settle = () => {
          const tasks = w.__longTasks;
          const last = tasks.length ? Math.max(...tasks.map((t) => t.end)) : 0;
          const now = performance.now();
          if (now - Math.max(last, ready) >= 500 || now - ready > 15_000) {
            resolve({
              interactiveMs: Math.max(ready, last),
              longTaskMs: tasks.filter((t) => t.start <= Math.max(ready, last)).reduce((sum, t) => sum + (t.end - t.start), 0)
            });
          } else setTimeout(settle, 100);
        };
        settle();
      }),
    readyMs
  );
}

async function openStage(page: Page, url: string): Promise<OpenResult> {
  const apiBodies: Promise<{ url: string; kb: number }>[] = [];
  const onResponse = (response: import("@playwright/test").Response) => {
    if (!response.url().startsWith(API)) return;
    apiBodies.push(
      response
        .body()
        .then((body) => ({ url: response.url(), kb: body.length / 1024 }))
        .catch(() => ({ url: response.url(), kb: 0 }))
    );
  };
  // In flight, counted from Playwright's side: Resource Timing only ever sees requests that finished.
  let inFlight = 0;
  const started = () => (inFlight += 1);
  const ended = () => (inFlight = Math.max(0, inFlight - 1));
  page.on("response", onResponse);
  page.on("request", started);
  page.on("requestfinished", ended);
  page.on("requestfailed", ended);
  await page.goto(url);
  const readyMs = await waitReady(page);
  const { interactiveMs, longTaskMs } = await waitQuiet(page, readyMs);
  /*
    SETTLED — the audit's own measure ("network idle and nothing still loading"): no request in flight,
    and none finished and no long task ended in the last 500 ms. The later of the three activities is
    the moment the page stopped working, which is what is reported.
  */
  let settledMs = interactiveMs;
  for (let waited = 0; waited < 30_000; waited += 100) {
    const last = await page.evaluate(() => {
      const w = window as unknown as { __longTasks: { end: number }[] };
      const resources = performance.getEntriesByType("resource") as PerformanceResourceTiming[];
      return {
        now: performance.now(),
        activity: Math.max(0, ...resources.map((r) => r.responseEnd), ...w.__longTasks.map((t) => t.end))
      };
    });
    settledMs = Math.max(interactiveMs, last.activity);
    if (inFlight === 0 && last.now - settledMs >= 500) break;
    await page.waitForTimeout(100);
  }
  page.off("response", onResponse);
  page.off("request", started);
  page.off("requestfinished", ended);
  page.off("requestfailed", ended);
  const sizes = await Promise.all(apiBodies);
  const resources = await page.evaluate((cutoff) =>
    performance
      .getEntriesByType("resource")
      .filter((entry) => entry.startTime <= cutoff)
      .map((entry) => {
        const r = entry as PerformanceResourceTiming;
        return {
          name: r.name,
          kind: r.initiatorType,
          start: r.startTime,
          end: r.responseEnd,
          ms: r.responseStart > 0 ? r.responseStart - r.requestStart : r.duration,
          encoded: r.encodedBodySize,
          decoded: r.decodedBodySize
        };
      }),
    settledMs
  );
  const api = resources
    .filter((r) => r.name.startsWith(API))
    .map((r) => ({
      // Record ids (cuids) become `:id`; route words such as `schema` and `batch` are kept.
      url: r.name
        .replace(API, "")
        .replace(/\/c[a-z0-9]{20,}(?=[/?]|$)/g, "/:id")
        .replace(/\/media\/speed[^/?]+/, "/media/:id")
        .replace(/ids=[^&]+/, "ids=…"),
      at: Math.round(r.start),
      done: Math.round(r.end),
      ms: Math.round(r.ms),
      kb: Math.round((sizes.find((s) => s.url === r.name)?.kb ?? r.decoded / 1024) * 10) / 10
    }));
  const scripts = resources.filter((r) => /\/_next\/static\/.+\.js/.test(r.name));
  const images = resources.filter((r) => r.kind === "img" || /localhost:9000|minio:9000/.test(r.name));
  return {
    readyMs: Math.round(readyMs),
    interactiveMs: Math.round(interactiveMs),
    settledMs: Math.round(settledMs),
    longTaskMs: Math.round(longTaskMs),
    api,
    requests: {
      api: api.length,
      script: scripts.length,
      image: images.length,
      other: resources.length - api.length - scripts.length - images.length
    },
    scriptKb: {
      compressed: Math.round(scripts.reduce((sum, r) => sum + r.encoded, 0) / 1024),
      decoded: Math.round(scripts.reduce((sum, r) => sum + r.decoded, 0) / 1024)
    },
    // What the form waited for: the scripts that had started by the time it was ready. The total
    // above also counts what is fetched afterwards on purpose (the preview, fetched when idle).
    scriptKbBeforeReady: {
      count: scripts.filter((r) => r.start <= readyMs).length,
      compressed: Math.round(scripts.filter((r) => r.start <= readyMs).reduce((sum, r) => sum + r.encoded, 0) / 1024),
      decoded: Math.round(scripts.filter((r) => r.start <= readyMs).reduce((sum, r) => sum + r.decoded, 0) / 1024)
    }
  };
}

function summarise(samples: number[]) {
  const sorted = [...samples].sort((a, b) => a - b);
  const at = (q: number) => Math.round(sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))] * 10) / 10;
  return { n: sorted.length, median: at(0.5), p95: at(0.95), max: at(1) };
}

async function typeInto(page: Page, selector: string, text: string) {
  const box = page.locator(selector).first();
  if (!(await box.count())) return null;
  await box.scrollIntoViewIfNeeded();
  await box.click();
  await page.keyboard.press("End");
  await page.evaluate(() => {
    const w = window as unknown as { __keys: number[]; __keyWork: number[] };
    w.__keys = [];
    w.__keyWork = [];
  });
  // 80 ms between keys is a brisk typist; it keeps one key's frame from being charged to the next.
  await page.keyboard.type(text, { delay: 80 });
  await page.waitForTimeout(400);
  const { keys, work } = await page.evaluate(() => {
    const w = window as unknown as { __keys: number[]; __keyWork: number[] };
    return { keys: w.__keys, work: w.__keyWork };
  });
  return { work: summarise(work), toPaint: summarise(keys) };
}

/** A stage field's plain box, and a stage field's rich-text editor — never the recording-place card above them. */
const TEXT_BOX = "main [data-dw-field] textarea:not([disabled]), main [data-dw-field] input[type=text]:not([disabled])";
const RICH_TEXT = "main [data-dw-field] [contenteditable=true]";

async function measureKeys(page: Page) {
  const text = " the dye is fixed with alum and washed twice";
  return {
    textBox: await typeInto(page, TEXT_BOX, text),
    richText: await typeInto(page, RICH_TEXT, text)
  };
}

type ProfileNode = { id: number; callFrame: { functionName: string; url: string; lineNumber: number }; children?: number[] };

/**
 * Where the main thread's time goes while typing — only with `E2E_STAGE_SPEED_PROFILE=1`, and only
 * readable against a build made with `next build --no-mangling` (otherwise every name is one letter).
 * Prints the twenty functions with the most SELF time across the typing run.
 */
async function profileTyping(page: Page, cdp: import("@playwright/test").CDPSession, selector: string) {
  await cdp.send("Profiler.enable");
  await cdp.send("Profiler.setSamplingInterval", { interval: 100 });
  await cdp.send("Profiler.start");
  await typeInto(page, selector, " profiled typing in the stage form");
  const { profile } = (await cdp.send("Profiler.stop")) as unknown as {
    profile: { nodes: ProfileNode[]; samples: number[]; timeDeltas: number[] };
  };
  const self = new Map<number, number>();
  profile.samples.forEach((id, index) => self.set(id, (self.get(id) ?? 0) + (profile.timeDeltas[index] ?? 0)));
  const byName = new Map<string, number>();
  for (const node of profile.nodes) {
    const name = `${node.callFrame.functionName || "(anonymous)"} ${node.callFrame.url.split("/").pop()}:${node.callFrame.lineNumber}`;
    byName.set(name, (byName.get(name) ?? 0) + (self.get(node.id) ?? 0) / 1000);
  }
  return [...byName.entries()]
    .filter(([name]) => !name.startsWith("(idle)") && !name.startsWith("(program)"))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 20)
    .map(([name, ms]) => `${ms.toFixed(1).padStart(8)} ms  ${name}`);
}

async function newMeasuredContext(browser: import("@playwright/test").Browser, token: string): Promise<BrowserContext> {
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const png = greyPng({ width: 64, height: 48, data: new Uint8ClampedArray(64 * 48).fill(150) });
  // The local object store is usually not running; the photographs still cost a request and a decode.
  await context.route(/^https?:\/\/(localhost|127\.0\.0\.1|minio):9000\//, (route) =>
    route.fulfill({ status: 200, contentType: "image/png", body: png })
  );
  await context.addInitScript(instrument);
  return context;
}

// NO TRACE AND NO SCREENSHOTS. The config keeps a trace for failures, and recording one snapshots the
// DOM in the page on every keyboard action — measured at several milliseconds a key, charged to the
// very number this spec exists to report.
test.use({ trace: "off", screenshot: "off", video: "off" });

test.describe("stage form speed", () => {
  test.skip(!RUN, "Set E2E_STAGE_SPEED=1 to seed a workshop and measure the stage form (see the file header).");
  test.skip(Boolean(CREDENTIALS_MISSING), CREDENTIALS_MISSING);
  test.setTimeout(600_000);

  test("opens a busy stage and answers a key", async ({ browser, request }) => {
    const token = await apiToken(request);
    // `E2E_STAGE_SPEED_WORKSHOP=<id>` re-measures a workshop an earlier run seeded, so a before and an
    // after are taken over the very same rows.
    const reuse = process.env.E2E_STAGE_SPEED_WORKSHOP;
    const { workshopId, stage } = reuse
      ? {
          workshopId: reuse,
          stage: targetStage(
            (await (await request.get(`${API}/api/design-workshops/schema`, { headers: bearer(token) })).json()) as Registry
          )
        }
      : await seedWorkshop(request, token);
    const url = `/design-workshops/${workshopId}/stages/${stage.key}`;

    const context = await newMeasuredContext(browser, token);
    const page = await context.newPage();
    await signInWithToken(page, token);
    const cdp = await context.newCDPSession(page);
    if (CPU_THROTTLE > 1) await cdp.send("Emulation.setCPUThrottlingRate", { rate: CPU_THROTTLE });
    // The preview starts closed whatever this browser remembered.
    await page.addInitScript(() => {
      try {
        window.localStorage.setItem("dw_stage_preview_open", "0");
      } catch {
        // Blocked storage leaves it closed anyway.
      }
    });

    const cold = await openStage(page, url);
    const warm = await openStage(page, url);
    const keys = await measureKeys(page);
    const profile =
      process.env.E2E_STAGE_SPEED_PROFILE === "1"
        ? {
            textBox: await profileTyping(page, cdp, TEXT_BOX),
            richText: await profileTyping(page, cdp, RICH_TEXT)
          }
        : undefined;

    // The same keystrokes with the document beside the form.
    const opening = Date.now();
    await page.getByRole("button", { name: /Document preview/ }).click();
    await page.locator("[data-stage-preview] .rp-sheet").first().waitFor({ timeout: 60_000 });
    const previewMs = Date.now() - opening;
    await page.waitForTimeout(1500);
    const keysWithPreview = await measureKeys(page);
    // The preview must still be following the form: the words just typed are on its pages.
    await expect(page.locator("[data-stage-preview]")).toContainText("washed twice", { timeout: 15_000 });

    const result = {
      measuredAt: new Date().toISOString(),
      cpuThrottle: CPU_THROTTLE,
      workshopId,
      stage: `${stage.number}. ${stage.title} (${stage.key})`,
      photos: PHOTOS,
      cold,
      warm,
      keystrokeMs: keys,
      previewFirstPageMs: previewMs,
      keystrokeMsWithPreview: keysWithPreview,
      profile
    };
    const out = path.join(process.cwd(), "test-results");
    mkdirSync(out, { recursive: true });
    writeFileSync(path.join(out, `stage-form-speed${CPU_THROTTLE > 1 ? `-cpu${CPU_THROTTLE}` : ""}.json`), JSON.stringify(result, null, 2));
    console.log(JSON.stringify(result, null, 2));

    expect(cold.readyMs).toBeGreaterThan(0);
    expect(warm.readyMs).toBeGreaterThan(0);
    await context.close();
  });
});
