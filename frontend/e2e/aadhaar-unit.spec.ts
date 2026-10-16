import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  aadhaarEnabled,
  aadhaarFeaturesOf,
  consentPoints,
  consentStatement,
  looksLikeSecureQr,
  METHOD_LABEL,
  STATUS_LABEL
} from "@/lib/aadhaar";

/**
 * Aadhaar verification on the web: hidden unless the server switched it on, consent before anything,
 * and nothing on screen about what this deployment cannot do.
 *
 * PURE NODE, like the other `-unit` specs: the rules here decide whether an Aadhaar screen exists
 * at all, and that has to be checkable without a server that has the feature on.
 */

const ROOT = join(__dirname, "..");
const read = (path: string) => readFileSync(join(ROOT, path), "utf8");
/** The source with its comments taken out — the rules below are about what RUNS and what is SHOWN. */
const code = (path: string) =>
  read(path)
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "");

test.describe("the server's switches", () => {
  test("an account with no features key — an older server, or a cached session — sees nothing", () => {
    for (const user of [null, undefined, {}, { features: null }, { features: "on" }]) {
      expect(aadhaarEnabled(aadhaarFeaturesOf(user))).toBe(false);
    }
  });

  test("both switches off is off", () => {
    const off = aadhaarFeaturesOf({ features: { aadhaarOfflineVerification: false, aadhaarOnlineAuth: false } });
    expect(off).toEqual({ offline: false, online: false, ekyc: false });
    expect(aadhaarEnabled(off)).toBe(false);
  });

  test("each switch turns on only its own methods, and only a literal true counts", () => {
    expect(aadhaarFeaturesOf({ features: { aadhaarOfflineVerification: true } })).toEqual({
      offline: true,
      online: false,
      ekyc: false
    });
    expect(aadhaarFeaturesOf({ features: { aadhaarOnlineAuth: true, aadhaarOnlineEkyc: true } })).toEqual({
      offline: false,
      online: true,
      ekyc: true
    });
    expect(aadhaarFeaturesOf({ features: { aadhaarOfflineVerification: "true" } }).offline).toBe(false);
  });

  test("every Aadhaar component returns nothing before it draws anything while the switches are off", () => {
    const panel = read("components/aadhaar/AadhaarVerifyPanel.tsx");
    expect(panel).toContain("if (!enabled) return null;");
    const badge = read("components/aadhaar/AadhaarUserBadge.tsx");
    expect(badge).toContain("if (!aadhaarEnabled(aadhaarFeaturesOf(viewer))) return null;");
  });
});

test.describe("consent", () => {
  test("the notice says what is read, what is kept, what is never kept, and how to undo it", () => {
    for (const subject of [{ kind: "self" as const }, { kind: "artisan" as const, id: "a1" }]) {
      const text = consentPoints(subject).join(" ");
      expect(text).toContain("voluntary");
      expect(text).toContain("What is kept");
      expect(text).toContain("never kept: the full Aadhaar number, the photograph, the address");
      expect(text).toContain("remove the result");
      expect(consentStatement(subject)).toMatch(/agree/);
    }
  });

  test("the panel cannot reach a method before the box is ticked", () => {
    const panel = read("components/aadhaar/AadhaarVerifyPanel.tsx");
    expect(panel).toContain('disabled={!agreed} onClick={() => setStep("method")}');
  });
});

test.describe("what the screens never say", () => {
  test("no Aadhaar copy mentions a method the deployment lacks", () => {
    const sources = [
      "lib/aadhaar.ts",
      "components/aadhaar/AadhaarVerifyPanel.tsx",
      "components/aadhaar/AadhaarUserBadge.tsx"
    ].map(code);
    const forbidden = [/coming soon/i, /not (yet )?available/i, /not built/i, /\bAUA\b/, /licen[cs]e/i, /not (yet )?supported/i];
    for (const source of sources) {
      for (const pattern of forbidden) expect(source).not.toMatch(pattern);
    }
  });

  test("nothing typed or uploaded is parked in the browser", () => {
    const sources = ["lib/aadhaar.ts", "lib/aadhaarQrImage.ts", "components/aadhaar/AadhaarVerifyPanel.tsx"]
      .map(code)
      .join("\n");
    expect(sources).not.toMatch(/localStorage|sessionStorage|indexedDB|saveOrQueue/);
  });
});

test.describe("the QR text", () => {
  test("only a long decimal number is taken for a Secure QR", () => {
    expect(looksLikeSecureQr("1".repeat(1500))).toBe(true);
    expect(looksLikeSecureQr("https://example.org")).toBe(false);
    expect(looksLikeSecureQr("12345")).toBe(false);
  });

  test("every status and method has a word on screen", () => {
    expect(Object.keys(STATUS_LABEL).sort()).toEqual(["NAME_MISMATCH", "REJECTED", "VERIFIED"]);
    expect(Object.keys(METHOD_LABEL).sort()).toEqual(["OFFLINE_XML", "ONLINE_EKYC", "ONLINE_OTP", "SECURE_QR"]);
  });
});
