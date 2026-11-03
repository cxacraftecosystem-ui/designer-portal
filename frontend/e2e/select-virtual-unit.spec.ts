import { readFileSync } from "node:fs";
import path from "node:path";

import { expect, test } from "@playwright/test";

import { OVERSCAN, VIRTUALIZE_AT, scrollTopToShow, virtualSlice } from "@/components/ui/selectFilter";

const ROOT = path.resolve(__dirname, "..");
const source = (relative: string) =>
  readFileSync(path.join(ROOT, relative), "utf8").replace(/\r\n/g, "\n");

test("a list of 200 rows or fewer is drawn whole; 201 is virtualised", () => {
  expect(VIRTUALIZE_AT).toBe(200);
  expect(virtualSlice(200, 0, 288, 36)).toEqual({ virtual: false, start: 0, end: 200, padTop: 0, padBottom: 0 });
  const long = virtualSlice(201, 0, 288, 36);
  expect(long.virtual).toBe(true);
  expect(long.start).toBe(0);
  expect(long.end).toBe(8 + OVERSCAN);
  expect(long.padBottom).toBe((201 - long.end) * 36);
});

test("a grouped list is never virtualised", () => {
  expect(virtualSlice(2000, 0, 288, 36, false).virtual).toBe(false);
  expect(virtualSlice(2000, 0, 288, 36, false).end).toBe(2000);
});

test("scrolled to the middle, the slice covers the view plus the overscan, padded to the full length", () => {
  const rows = 2000;
  const slice = virtualSlice(rows, 36 * 1000, 288, 36);
  expect(slice.start).toBe(1000 - OVERSCAN);
  expect(slice.end).toBe(1008 + OVERSCAN);
  expect(slice.padTop + (slice.end - slice.start) * 36 + slice.padBottom).toBe(rows * 36);
});

test("the slice never runs past the end", () => {
  const slice = virtualSlice(300, 36 * 299, 288, 36);
  expect(slice.end).toBe(300);
  expect(slice.padBottom).toBe(0);
});

test("the keyboard highlight is kept in view by the least scroll, never re-centred", () => {
  expect(scrollTopToShow(3, 36, 0, 288)).toBe(0);
  expect(scrollTopToShow(10, 36, 0, 288)).toBe(10 * 36 + 36 - 288);
  expect(scrollTopToShow(2, 36, 500, 288)).toBe(72);
});

test("virtual rows say where they are in the whole list", () => {
  const select = source("components/ui/SearchableSelect.tsx");
  expect(select.match(/aria-setsize=\{listVirtual \? rendered\.length : undefined\}/g)?.length).toBe(2);
  expect(select.match(/aria-posinset=\{listVirtual \? index \+ 1 : undefined\}/g)?.length).toBe(2);
  expect(select.match(/ref=\{listRef\}/g)?.length).toBe(2);
});

test("a local list is never cut at RENDER_CAP; only a server answer is", () => {
  const select = source("components/ui/SearchableSelect.tsx");
  expect(select).toContain("if (!serverAnswered || filtered.length <= RENDER_CAP) return { windowed: filtered, pinned: 0 };");
});

test("the single select is clearable by a separate button, never one nested in the trigger", () => {
  const select = source("components/ui/SearchableSelect.tsx");
  expect(select).toContain("clearable?: boolean;");
  expect(select).toContain("clearable = false,");
  expect(select).toContain('aria-label={clearLabel ?? (ariaLabel ? `Clear ${ariaLabel}` : "Clear selection")}');
  const trigger = select.indexOf("className={cn(TRIGGER_CLASS, showClear");
  const close = select.indexOf("</button>", trigger);
  const clear = select.indexOf("{showClear ? (", trigger);
  expect(clear).toBeGreaterThan(close);
  expect(select).toContain("pointer-coarse:h-11 pointer-coarse:w-11");
});

test("a chip's remove button is 44px to hit on a phone", () => {
  const select = source("components/ui/SearchableSelect.tsx");
  expect(select).toContain("pointer-coarse:before:absolute pointer-coarse:before:-inset-2");
});
