import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createContext, runInContext } from "node:vm";

import { expect, test } from "@playwright/test";

type Worker = {
  pageKey: (request: Request, url: URL) => Request;
  isPrefetch: (request: Request) => boolean;
  listeners: Map<string, (event: unknown) => void>;
};

function loadWorker(): Worker {
  const listeners = new Map<string, (event: unknown) => void>();
  const context: Record<string, unknown> = {
    globalThis: { addEventListener: (name: string, listener: (event: unknown) => void) => listeners.set(name, listener), location: { origin: "https://dw.example" } },
    URL,
    URLSearchParams,
    Request,
    Math
  };
  createContext(context);
  runInContext(readFileSync(join(__dirname, "..", "public", "sw.js"), "utf8"), context);
  return { pageKey: context.pageKey as Worker["pageKey"], isPrefetch: context.isPrefetch as Worker["isPrefetch"], listeners };
}

const worker = loadWorker();

function key(href: string, headers: Record<string, string> = {}): string {
  return worker.pageKey(new Request(href, { headers }), new URL(href)).url;
}

test("an RSC payload is kept per folder, so Back never gets another folder's payload", () => {
  const rsc = { RSC: "1", "Next-Router-State-Tree": "tree" };
  expect(key("https://dw.example/data?path=by-workshop/a", rsc)).not.toBe(key("https://dw.example/data?path=by-workshop/b", rsc));
  expect(key("https://dw.example/data?path=a&view=tables", rsc)).toBe(key("https://dw.example/data?view=tables&path=a", rsc));
});

test("the router headers are part of the key", () => {
  const href = "https://dw.example/data?path=a";
  expect(key(href, { RSC: "1", "Next-Router-State-Tree": "one" })).not.toBe(key(href, { RSC: "1", "Next-Router-State-Tree": "two" }));
  expect(key(href, { RSC: "1", "Next-Url": "/data" })).not.toBe(key(href, { RSC: "1" }));
});

test("the page shell stays one entry per path, and never collides with a payload", () => {
  expect(key("https://dw.example/data?path=a")).toBe("https://dw.example/data");
  expect(key("https://dw.example/data?path=a", { RSC: "1" })).not.toBe("https://dw.example/data");
});

test("prefetches are never answered from or written to the page cache", () => {
  expect(worker.isPrefetch(new Request("https://dw.example/data", { headers: { RSC: "1", "Next-Router-Prefetch": "1" } }))).toBe(true);
  expect(worker.isPrefetch(new Request("https://dw.example/data", { headers: { RSC: "1" } }))).toBe(false);
  const sw = readFileSync(join(__dirname, "..", "public", "sw.js"), "utf8");
  expect(sw).toContain("if (isPage && isPrefetch(request)) return;");
  expect(sw).not.toContain("?__dw_rsc=1");
});
