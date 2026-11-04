import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { RequiredMark } from "@/components/ui/RequiredMark";

test("no required state draws no asterisk", () => {
  expect(renderToStaticMarkup(createElement(RequiredMark, { when: undefined }))).toBe("");
  expect(renderToStaticMarkup(createElement(RequiredMark, { when: false }))).toBe("");
  expect(renderToStaticMarkup(createElement(RequiredMark, { when: true }))).toContain("*");
});

test("every caller says what it means — there is no bare <RequiredMark />", () => {
  const callers = [
    ["components", "FormControls.tsx"],
    ["components", "forms", "AadhaarField.tsx"],
    ["components", "forms", "DosDontsField.tsx"],
    ["components", "richtext", "DictatedTextArea.tsx"],
    ["components", "richtext", "DictatedTextInput.tsx"],
    ["components", "tasks", "TaskPrimitives.tsx"]
  ];
  for (const path of callers) {
    const source = readFileSync(join(__dirname, "..", ...path), "utf8");
    expect(source, path.join("/")).not.toContain("<RequiredMark />");
    expect(source, path.join("/")).toContain("<RequiredMark when={required} />");
  }
});
