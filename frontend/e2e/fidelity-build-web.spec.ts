import { mkdirSync } from "node:fs";
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { join } from "node:path";

import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from "@playwright/test";

import { GIRIRAJ, WORKSHOP } from "./support/designFidelityFake";
import { DesignFidelityWebFake, KILN_BYTES, KILN_ID, KILN_NAME } from "./support/designFidelityWebFake";

const OUT = process.env.DESIGN_OUT ?? join("test-results", "design-fidelity");

type Theme = "light" | "dark";
type Size = { width: number; height: number };

const DESKTOP: Size = { width: 1440, height: 900 };
const TABLET: Size = { width: 834, height: 1112 };
const PHONE: Size = { width: 390, height: 844 };

test.describe.configure({ mode: "parallel" });

function folderUrl(path: string, extra: Record<string, string> = {}): string {
  return `/data?${new URLSearchParams({ path, ...extra }).toString()}`;
}

async function open(browser: Browser, size: Size, theme: Theme = "light"): Promise<{ context: BrowserContext; page: Page; fake: DesignFidelityWebFake }> {
  const mobile = size.width < 900;
  const context = await browser.newContext({
    viewport: size,
    deviceScaleFactor: size.width <= 600 ? 2 : 1,
    isMobile: mobile,
    hasTouch: mobile,
    colorScheme: theme,
    serviceWorkers: "block",
    acceptDownloads: true,
    reducedMotion: "reduce"
  });
  const fake = new DesignFidelityWebFake();
  await fake.install(context, theme);
  const page = await context.newPage();
  return { context, page, fake };
}

async function settle(page: Page) {
  await page.evaluate(() => document.fonts.ready.then(() => undefined));
  await page.addStyleTag({ content: "*,*::before,*::after{transition:none!important;animation:none!important;caret-color:transparent!important}" });
  await page.waitForTimeout(300);
}

function shotPath(board: string, variant?: string): string {
  const dir = join(OUT, board);
  mkdirSync(dir, { recursive: true });
  return join(dir, `impl${variant ? `-${variant}` : ""}.png`);
}

async function shoot(page: Page, board: string, variant?: string) {
  await settle(page);
  await page.screenshot({ path: shotPath(board, variant) });
}

async function shootAround(page: Page, board: string, variant: string | undefined, targets: Locator[], pad = { x: 40, top: 32, bottom: 32 }) {
  await settle(page);
  const boxes = [];
  for (const target of targets) {
    const box = await target.boundingBox();
    if (box) boxes.push(box);
  }
  const viewport = page.viewportSize()!;
  const top = Math.min(...boxes.map((box) => box.y));
  const bottom = Math.max(...boxes.map((box) => box.y + box.height));
  const left = Math.min(...boxes.map((box) => box.x));
  const right = Math.max(...boxes.map((box) => box.x + box.width));
  const x = Math.max(0, left - pad.x);
  const y = Math.max(0, top - pad.top);
  await page.screenshot({ path: shotPath(board, variant), clip: { x, y, width: Math.min(viewport.width - x, right - left + pad.x * 2), height: bottom - top + pad.top + pad.bottom }, fullPage: true });
}

async function folderReady(page: Page) {
  await expect(page.getByTestId("download-folder")).toBeVisible();
  await expect(page.getByTestId("folder-size")).not.toContainText("Checking size");
}

async function startWorkshopZip(page: Page, fake: DesignFidelityWebFake) {
  fake.workshopZip = true;
  fake.holdFrom = 573;
  await page.goto(folderUrl(WORKSHOP));
  await folderReady(page);
  await page.getByTestId("download-folder").getByRole("button", { name: /^Download( workshop| folder|$)/ }).click();
}

async function zipHeld(page: Page) {
  await expect(page.getByTestId("downloads-tray")).toContainText(/(file|File) 574 of 926/, { timeout: 60_000 });
}

async function toGiriraj(page: Page) {
  await page.getByRole("list", { name: "Folders" }).getByRole("button", { name: /Giriraj Prasad Chhipa/ }).click();
  await expect(page.getByTestId("download-folder").getByRole("heading", { name: "Giriraj Prasad Chhipa" })).toBeVisible();
  await folderReady(page);
}

function row(page: Page, name: string): Locator {
  return page.locator("[data-file-row]").filter({ hasText: name }).first();
}

async function check(page: Page, file: string) {
  await row(page, file.replace(/\.[^.]+$/, "")).getByRole("checkbox", { name: `Select ${file}` }).check();
}

async function main(browser: Browser, theme: Theme) {
  const { context, page, fake } = await open(browser, { width: 1440, height: 1240 }, theme);
  try {
    await startWorkshopZip(page, fake);
    await toGiriraj(page);
    await check(page, "Interview_Dabu-mud-resist_Take-1.mp4");
    await check(page, "Block-carving_close-up_03.jpg");
    await expect(page.getByTestId("selection-size")).toHaveText(/2 selected · 704 MB/);
    await zipHeld(page);
    await page.getByRole("searchbox").first().focus();
    await page.evaluate(() => window.scrollTo(0, 0));
    await shoot(page, "Main", theme === "dark" ? "dark" : undefined);
  } finally {
    fake.release();
    await context.close();
  }
}

test("impl Main", async ({ browser }) => {
  test.setTimeout(150_000);
  await main(browser, "light");
});

test("impl Main · dark", async ({ browser }) => {
  test.setTimeout(150_000);
  await main(browser, "dark");
});

for (const theme of ["light", "dark"] as const) {
  test(`impl WebTablet${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, TABLET, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      await check(page, "Interview_Dabu-mud-resist_Take-1.mp4");
      await check(page, "Block-carving_close-up_03.jpg");
      await expect(page.getByTestId("selection-size")).toHaveText(/2 selected/);
      await page.evaluate(() => window.scrollTo(0, 0));
      await shoot(page, "WebTablet", theme === "dark" ? "dark" : undefined);
    } finally {
      await context.close();
    }
  });

  test(`impl WebPhone${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    test.setTimeout(150_000);
    const { context, page, fake } = await open(browser, PHONE, theme);
    try {
      await startWorkshopZip(page, fake);
      await toGiriraj(page);
      await zipHeld(page);
      await page.evaluate(() => window.scrollTo(0, 0));
      await shoot(page, "WebPhone", theme === "dark" ? "dark" : undefined);
    } finally {
      fake.release();
      await context.close();
    }
  });

  test(`impl WebTranscripts${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 820 }, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ, { view: "transcripts" }));
      const tab = page.getByTestId("transcripts-tab");
      await expect(tab.locator("[data-transcript-row]").first()).toBeVisible();
      await expect(tab.locator("[data-state=working]")).toHaveCount(1);
      await shootAround(page, "WebTranscripts", theme === "dark" ? "dark" : undefined, [page.getByRole("region", { name: "Contents" })], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl WorkshopOverview${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, DESKTOP, theme);
    try {
      await page.goto(folderUrl(WORKSHOP));
      await folderReady(page);
      const overview = page.getByTestId("workshop-overview");
      await expect(overview.locator("tbody tr")).toHaveCount(4);
      await expect(overview).toContainText("+38", { timeout: 10_000 });
      await shootAround(page, "WorkshopOverview", theme === "dark" ? "dark" : undefined, [page.getByTestId("download-folder"), overview], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl WebTables${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 1000 }, theme);
    try {
      await page.route("**/api/data/report?*", async (route) => {
        const url = new URL(route.request().url());
        if (url.searchParams.get("format") === "xlsx") return;
        return route.fallback();
      });
      await page.goto(folderUrl(WORKSHOP, { view: "tables", sheet: "Tools", q: "indigo" }));
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByText("3 rows match “indigo”")).toBeVisible();
      await tables.getByRole("button", { name: "Full report (.xlsx)" }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      await expect(tables.getByText("Full report (.xlsx) is being prepared")).toBeVisible();
      await shootAround(page, "WebTables", theme === "dark" ? "dark" : undefined, [page.getByRole("region", { name: "Contents" })], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl WebTables error${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, { width: 1440, height: 1000 }, theme);
    try {
      await page.route("**/api/data/report?*", async (route) => {
        const url = new URL(route.request().url());
        if (url.searchParams.get("format") === "xlsx") return route.fulfill({ status: 500, contentType: "application/json", headers: { "retry-after": "20" }, body: JSON.stringify({ detail: "The report couldn't be built." }) });
        return route.fallback();
      });
      await page.goto(folderUrl(WORKSHOP, { view: "tables", sheet: "Tools", q: "indigo" }));
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByText("3 rows match “indigo”")).toBeVisible();
      await tables.getByRole("button", { name: "Full report (.xlsx)" }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      await expect(tables.getByRole("alert").filter({ hasText: "Nothing was saved." })).toBeVisible();
      await shootAround(page, "WebTables", theme === "dark" ? "error-dark" : "error", [page.getByRole("region", { name: "Contents" })], { x: 0, top: 0, bottom: 0 });
    } finally {
      await context.close();
    }
  });

  test(`impl PhoneTables${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, PHONE, theme);
    try {
      await page.goto(folderUrl(WORKSHOP, { view: "tables", sheet: "Tools", q: "indigo" }));
      const tables = page.getByRole("region", { name: "Data tables" });
      await expect(tables.getByText("Row 14").or(tables.getByText("Row 1", { exact: true }))).toBeVisible();
      await shoot(page, "PhoneTables", theme === "dark" ? "phone-web-dark" : "phone-web");
    } finally {
      await context.close();
    }
  });

  test(`impl AndroidSelect${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    const { context, page } = await open(browser, PHONE, theme);
    try {
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      const first = row(page, "Interview_Dabu-mud-resist_Take-1");
      await first.dispatchEvent("pointerdown", { pointerType: "touch", isPrimary: true });
      await page.waitForTimeout(650);
      await first.dispatchEvent("pointerup", { pointerType: "touch", isPrimary: true });
      await expect(page.getByRole("region", { name: "Selecting" })).toBeVisible();
      await check(page, "Block-carving_close-up_03.jpg");
      await check(page, "Section-D_Materials_Group-of-3.m4a");
      await expect(page.getByRole("region", { name: "Selecting" })).toContainText("3 selected");
      await page.getByRole("group", { name: "Show file types" }).evaluate((node) => {
        const top = node.getBoundingClientRect().top + window.scrollY - 76;
        window.scrollTo(0, top);
      });
      await shoot(page, "AndroidSelect", theme === "dark" ? "phone-web-dark" : "phone-web");
    } finally {
      await context.close();
    }
  });
}

function kilnServer(): Promise<{ server: Server; url: string }> {
  return new Promise((resolve) => {
    const server = createServer((request, response) => {
      response.writeHead(request.headers.range ? 206 : 200, {
        "content-type": "application/octet-stream",
        "content-length": String(KILN_BYTES),
        "access-control-allow-origin": request.headers.origin ?? "*",
        "access-control-allow-credentials": "true",
        "accept-ranges": "bytes",
        ...(request.headers.range ? { "content-range": `bytes 0-${KILN_BYTES - 1}/${KILN_BYTES}` } : {})
      });
      response.write(Buffer.alloc(140 * 1024, 7), () => setTimeout(() => request.socket.destroy(), 50));
    });
    server.listen(0, "127.0.0.1", () => resolve({ server, url: `http://127.0.0.1:${(server.address() as AddressInfo).port}/kiln` }));
  });
}

for (const theme of ["light", "dark"] as const) {
  test(`impl WebTrayQueue${theme === "dark" ? " · dark" : ""}`, async ({ browser }) => {
    test.setTimeout(240_000);
    const { server, url } = await kilnServer();
    const { context, page, fake } = await open(browser, DESKTOP, theme);
    try {
      await page.route(`**/api/data/media/${KILN_ID}/download`, (route) => route.continue({ url }));
      await page.goto(folderUrl(GIRIRAJ));
      await folderReady(page);
      const kiln = row(page, "Kiln-firing_wide_07");
      await kiln.getByRole("button", { name: `Download ${KILN_NAME}` }).click();
      const tray = page.getByTestId("downloads-tray");
      const kilnItem = tray.getByTestId("tray-item").filter({ hasText: KILN_NAME });
      await expect(kilnItem).toHaveAttribute("data-status", "failed", { timeout: 30_000 });
      await expect(kilnItem.getByRole("button", { name: "Retry" })).toBeVisible({ timeout: 90_000 });
      await page.getByTestId("download-folder").getByRole("button", { name: /Report/ }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      await expect(tray.getByTestId("tray-item").filter({ hasText: "Report (.xlsx)" }).first()).toHaveAttribute("data-status", "saved");
      fake.workshopZip = true;
      await page.getByTestId("download-folder").getByRole("button", { name: /^Download/ }).click();
      await expect(tray.getByTestId("tray-item").filter({ hasText: "Giriraj Prasad Chhipa" })).toHaveAttribute("data-status", "saved", { timeout: 30_000 });
      fake.holdFrom = 573;
      await page.getByTestId("download-folder").getByRole("button", { name: "3rd Craft Toolkit Workshop" }).first().click().catch(async () => {
        await page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("button", { name: "3rd Craft Toolkit Workshop" }).click();
      });
      await folderReady(page);
      await page.getByTestId("download-folder").getByRole("button", { name: /^Download/ }).click();
      await page.getByTestId("download-folder").getByRole("button", { name: /Report/ }).click();
      await page.getByRole("dialog", { name: "Report (.xlsx)" }).getByRole("button", { name: "Make report" }).click();
      await zipHeld(page);
      await expect(tray.getByTestId("tray-item").filter({ hasText: "Starts when the one above finishes" })).toBeVisible();
      await settle(page);
      const box = (await tray.boundingBox())!;
      await page.screenshot({ path: shotPath("WebTrayQueue", theme === "dark" ? "dark" : undefined), clip: { x: box.x + box.width + 24 - 560, y: Math.max(0, box.y + box.height + 24 - 720), width: 560, height: Math.min(720, box.y + box.height + 24) } });
    } finally {
      fake.release();
      await context.close();
      server.close();
    }
  });
}
