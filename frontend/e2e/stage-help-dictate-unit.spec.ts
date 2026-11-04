import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  adornmentPosition,
  DICTATE_BUTTON_BASE,
  DICTATE_BUTTON_RECORDING,
  dictateButtonDescription,
  dictateButtonLabel,
  DICTATION_BESIDE_ROW,
  DICTATION_FIELD_PADDED,
  DICTATION_LANGUAGE_BUTTON,
  dictationLanguageButtonLabel
} from "@/components/dictation/dictationAdornment";
import { hasDetails, infoToggleAria, infoToggleLabel } from "@/components/ui/infoToggleAria";

const ROOT = join(__dirname, "..");
const read = (rel: string) => readFileSync(join(ROOT, rel), "utf8");

type WireField = { key: string; help?: string; details?: string };
type WireRegistry = { stages: Array<{ entities: Array<{ key: string; fields: WireField[] }> }> };

const REGISTRY = JSON.parse(
  read("../android/app/src/main/assets/design-workshop-schema.json")
) as WireRegistry;
const FIELDS = REGISTRY.stages.flatMap((stage) =>
  stage.entities.flatMap((entity) => entity.fields.map((field) => ({ path: `${entity.key}.${field.key}`, field })))
);

test("every help line in the shipped registry is one sentence of at most 120 characters", () => {
  const helps = FIELDS.filter(({ field }) => field.help);
  expect(helps.length).toBeGreaterThan(100);
  const long = helps.filter(({ field }) => (field.help ?? "").length > 120).map(({ path }) => path);
  expect(long, "move the rest of these into details").toEqual([]);
  const twoSentences = helps
    .filter(({ field }) => /[.!?…]["”’)]?\s+\S/.test((field.help ?? "").trim()))
    .map(({ path }) => path);
  expect(twoSentences, "one sentence per help line").toEqual([]);
});

test("details travel only where they say something", () => {
  const withDetails = FIELDS.filter(({ field }) => "details" in field);
  expect(withDetails.length).toBeGreaterThan(0);
  expect(withDetails.every(({ field }) => hasDetails(field.details))).toBe(true);
});

test("the info toggle is a disclosure: named, collapsed by default, pointing at its panel only while open", () => {
  expect(infoToggleLabel("Aadhaar number")).toBe("About Aadhaar number");
  const closed = infoToggleAria("Aadhaar number", false, "f1-details");
  expect(closed).toEqual({ "aria-label": "About Aadhaar number", "aria-expanded": false });
  expect("aria-controls" in closed).toBe(false);
  const open = infoToggleAria("Aadhaar number", true, "f1-details");
  expect(open["aria-expanded"]).toBe(true);
  expect(open["aria-controls"]).toBe("f1-details");

  expect(hasDetails(undefined)).toBe(false);
  expect(hasDetails("   ")).toBe(false);
  expect(hasDetails("More.")).toBe(true);
});

test("the stage form draws the toggle beside the label, collapsed, and mounts the panel only when open", () => {
  const source = read("components/designworkshop/FieldInput.tsx");
  const toggle = read("components/ui/InfoDisclosure.tsx");
  expect(toggle).toContain('type="button"');
  expect(toggle).toContain("{...infoToggleAria(fieldLabel, expanded, panelId)}");
  expect(toggle).toContain("h-5 w-5");
  expect(toggle).toContain("after:-inset-3");

  expect(source).toContain("const [detailsOpen, setDetailsOpen] = useState(false);");
  expect(source).toContain("{detailsOpen ? <InfoPanel id={detailsId}>{field.details}</InfoPanel> : null}");
  expect(source.replace(/\s+/g, " ")).toContain("{label} <InfoToggle");
  expect(source.match(/\{labelRow\(/g)).toHaveLength(2);
});

test("the microphone is a 44px icon button named Dictate, with the field and language as its description", () => {
  expect(DICTATE_BUTTON_BASE).toContain("h-11 w-11");
  expect(dictateButtonLabel(false)).toBe("Dictate");
  expect(dictateButtonLabel(true)).toBe("Stop dictating");
  expect(dictateButtonLabel(false, true)).toBe("Transcribing");
  expect(dictateButtonDescription("Remarks", "English (India)")).toBe("Remarks, English (India)");
  expect(DICTATE_BUTTON_RECORDING).toContain("bg-purple-700");
  expect(DICTATE_BUTTON_RECORDING).toContain("ring-4");
  expect(DICTATION_LANGUAGE_BUTTON).toContain("h-11");
  expect(DICTATION_LANGUAGE_BUTTON).toContain("after:-inset-x-2.5");
  expect(dictationLanguageButtonLabel("हिन्दी — Hindi")).toBe("Dictation language: हिन्दी — Hindi");
});

test("the adornment sits inside the box: centred on one line, top-right on a textarea, with room padded for it", () => {
  expect(adornmentPosition(false)).toContain("inset-y-0 end-1");
  expect(adornmentPosition(true)).toContain("end-1 top-1");
  expect(DICTATION_FIELD_PADDED).toContain("[&_.field-input]:pe-");

  const dictation = read("components/designworkshop/Dictation.tsx");
  expect(dictation).toContain("{children}");
  expect(dictation).toContain("<div className={adornmentPosition(multiline)}>{adornment}</div>");
  expect(dictation).toContain('aria-haspopup="menu"');
  expect(dictation).toContain('role="menuitemradio"');
  expect(dictation).not.toContain("underline");
  expect(dictation).not.toContain('"Dictate"');

  const field = read("components/designworkshop/FieldInput.tsx").replace(/\s+/g, " ");
  expect(field).toMatch(/<DictationButton [^>]*workshopId=\{workshopId\}> \{textBox\} <\/DictationButton>/);
  expect(field).toMatch(/<DictationButton [^>]*multiline > <textarea/);
  expect(field).not.toContain('{field.type === "TEXT" ? ( <DictationButton onCommit={appendDictated} disabled={disabled} fieldLabel={field.label} workshopId={workshopId} /> ) : null}');
});

test("the workshop-name dropdown carries the microphone on the trigger's line, not under the box", () => {
  expect(DICTATION_BESIDE_ROW).toContain("flex");
  expect(DICTATION_BESIDE_ROW).toContain("items-center");
  const dictation = read("components/designworkshop/Dictation.tsx").replace(/\s+/g, " ");
  expect(dictation).toContain('<div className={DICTATION_BESIDE_ROW}> <div className="min-w-0 flex-1">{children}</div> {adornment} </div>');

  const field = read("components/designworkshop/FieldInput.tsx").replace(/\s+/g, " ");
  expect(field).toMatch(/<StageWorkshopNameField [^]*?<DictationButton [^>]*beside > \{control\} <\/DictationButton>/);

  const name = read("components/designworkshop/StageWorkshopNameField.tsx");
  expect(name).toContain("(dictation ?? ((control: React.ReactNode) => control))(");
  expect(name).not.toMatch(/^\s*\{dictation\}\s*$/m);
});
