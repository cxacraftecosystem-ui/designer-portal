import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { actionGroupClass, actionGroupShape } from "@/components/ui/actionGroupLayout";

/*
  THE BUTTON-ROW RULE, held in one place. One button on its line is centred; two share one line and
  never wrap past it; three or more stack full width on a phone and become one row of pills above
  `sm`. The three shared surfaces that draw button rows — the page header, a list row and a dialog
  footer — must route through the primitive, so the rule cannot drift screen by screen.
*/

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

test("the count decides the shape", () => {
  expect(actionGroupShape(0)).toBe("one");
  expect(actionGroupShape(1)).toBe("one");
  expect(actionGroupShape(2)).toBe("two");
  expect(actionGroupShape(3)).toBe("many");
  expect(actionGroupShape(7)).toBe("many");
});

test("one button is centred, and a pinned primary may span a phone's width", () => {
  expect(actionGroupClass(1)).toContain("justify-center");
  expect(actionGroupClass(1, "end")).toContain("justify-center");
  expect(actionGroupClass(1, "center", true)).toContain("max-sm:[&>*]:w-full");
  expect(actionGroupClass(1)).not.toContain("w-full");
});

test("two buttons share one line and never wrap", () => {
  const two = actionGroupClass(2);
  expect(two).toContain("flex-nowrap");
  expect(two).not.toContain("flex-wrap ");
  expect(two).toContain("max-sm:[&>*]:flex-1");
  expect(two).toContain("max-sm:[&>*]:min-w-0");
  expect(actionGroupClass(2, "end")).toContain("justify-end");
});

test("three or more stack on a phone and become pills from sm up, danger last", () => {
  const many = actionGroupClass(4);
  expect(many).toContain("flex-col");
  expect(many).toContain("max-sm:[&>*]:w-full");
  expect(many).toContain("sm:flex-row");
  expect(many).toContain("sm:[&>*]:rounded-full!");
  expect(many).toContain("[&>[data-danger]]:order-last");
  expect(actionGroupClass(3, "end")).toContain("sm:justify-end");
});

test("the shared row surfaces route through ActionGroup", () => {
  expect(read("components/PageHeader.tsx")).toContain('<ActionGroup align="end">{actions}</ActionGroup>');
  expect(read("components/RowActions.tsx")).toContain('<ActionGroup align="end">{children}</ActionGroup>');
  expect(read("components/dialogs/FieldDialog.tsx")).toContain('<ActionGroup align="end" className="mt-5">{footer}</ActionGroup>');
});

test("a destructive row action is marked so the group can set it apart", () => {
  const list = read("app/(protected)/design-workshops/page.tsx");
  expect(list).toContain('data-danger className={rowAction("danger")}');
});
