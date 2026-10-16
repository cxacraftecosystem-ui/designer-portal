import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";

import { RequiredMark } from "@/components/ui/RequiredMark";

/**
 * THE ASTERISK FOLLOWS THE FIELD'S REQUIRED STATE, AND ABSENT MEANS NOT REQUIRED (UX audit DW-02).
 *
 * `RequiredMark` was `{ when = true }`, and a default parameter turns an explicit `undefined` into
 * the default — so `Field`'s `<RequiredMark when={required} />` starred every field written without
 * a `required` prop: "Note (optional) *", an artisan's email and phone drawn as compulsory.
 */
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
