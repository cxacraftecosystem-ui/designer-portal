import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { registryProvenanceNotice } from "@/lib/registryProvenance";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

test("a registry fetched on this render says nothing", () => {
  expect(registryProvenanceNotice("network")).toBe("");
});

test("a registry that has not resolved yet says nothing", () => {
  expect(registryProvenanceNotice(null)).toBe("");
});

test("a registry served from this tab's memory says so, and names the reload that fixes it", () => {
  const sentence = registryProvenanceNotice("memory");

  expect(sentence, "this is the state that used to be silent").not.toBe("");
  expect(sentence).toContain("may be out of date");
  expect(sentence).toContain("Reload the page");
  expect(sentence).toContain("fields added since");
});

test("a registry read off disk after a failed fetch says it is the copy saved on this device", () => {
  const sentence = registryProvenanceNotice("cache");

  expect(sentence).toContain("Couldn't connect, so this is the copy of the form saved on this device");
  expect(sentence).toContain("the last time you were online");
});

test("the two stale states do not print the same sentence", () => {
  expect(registryProvenanceNotice("memory")).not.toBe(registryProvenanceNotice("cache"));
});

test("the stage form renders the decided sentence and compares no source itself", () => {
  const source = read("app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx");

  expect(source).toContain("registryProvenanceNotice(registrySource)");
  expect(source).toContain("{registryNotice ? (");
  const code = source.replace(/\/\*[\s\S]*?\*\//g, "").replace(/^[ \t]*\/\/.*$/gm, "");
  expect(code, "the decision belongs in lib/registryProvenance").not.toContain('registrySource === "cache"');
  expect(code).not.toContain('registrySource === "memory"');
});
