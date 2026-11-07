import { expect, test } from "@playwright/test";

import { safeReturnPath } from "@/lib/craftAddDraft";

test.describe("The crafts return parameter stays on this site", () => {
  test("a tab or newline the URL parser drops cannot make the path protocol-relative", () => {
    for (const value of ["/\t/evil.invalid", "/\n/evil.invalid", "/\r\n/evil.invalid", "/\t\t/evil.invalid", "/\t\\evil.invalid"]) {
      expect(safeReturnPath(value), JSON.stringify(value)).toBeNull();
      const resolved = new URL(value, "https://portal.invalid");
      expect(resolved.origin, JSON.stringify(value)).not.toBe("https://portal.invalid");
    }
  });

  test("other control characters and backslashes are refused", () => {
    expect(safeReturnPath("/\u0000/evil.invalid")).toBeNull();
    expect(safeReturnPath("/a\\b")).toBeNull();
  });

  test("ordinary paths still come back", () => {
    expect(safeReturnPath("/artisans/new")).toBe("/artisans/new");
    expect(safeReturnPath("/tools/abc/edit?focus=craft")).toBe("/tools/abc/edit?focus=craft");
    expect(safeReturnPath("/artisans?q=two words")).toBe("/artisans?q=two words");
    expect(safeReturnPath("//evil.invalid")).toBeNull();
    expect(safeReturnPath("https://evil.invalid")).toBeNull();
  });
});
