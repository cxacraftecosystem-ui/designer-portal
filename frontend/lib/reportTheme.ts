/**
 * The report's accent colour, and the seven colours derived from it — the browser's copy.
 *
 * A PORT, NOT AN IMPLEMENTATION. `backend/app/services/report_theme.py` is the original and
 * `ReportModel.kt`'s `themeFromAccent` is the other port; all three must return the same eight
 * strings for the same accent. They cannot share code — the Android app writes its report on the
 * handset with no server in reach, and this file exists so the preview can be redrawn in a colour
 * the moment a designer picks it rather than after a round trip — so the contract is the golden
 * table in `backend/tests/test_report_theme.py`, which every value below was checked against.
 *
 * WHAT IT IS FOR HERE. The report page's colour picker changes the preview LIVE. That is the whole
 * point of offering the choice at all: the preview draws the real blocks of the real document at
 * real millimetre sizes, so re-drawing it in the chosen accent is what makes the choice safe to
 * make without generating a file, opening it, deciding against it and starting again. A picker
 * whose result you could only see in Word would be a worse feature than no picker.
 *
 * WHY THE DERIVATION AND NOT EIGHT PICKERS is argued at length in the Python module and not
 * repeated here, with one exception worth restating where the UI lives: the swatch a designer
 * clicks shows the DERIVED accent, not the raw one they picked. A colour too pale to read as a
 * heading is darkened, and a picker that showed the pale version while the paper got the dark one
 * would be lying at the exact moment it is being trusted.
 *
 * ⚠ Every function here is pure and total. `paletteFromAccent(undefined)`, `("")`, `("nonsense")`
 * all return the DCH indigo palette. A colour string arriving from a stage entry written by some
 * other client must never be able to throw inside a render.
 */

/** The eight colours a report is drawn in, as CSS-ready `#rrggbb`, plus the paper they sit on. */
export type ReportPalette = {
  paper: string;
  ink: string;
  muted: string;
  rule: string;
  accent: string;
  accentSoft: string;
  zebra: string;
  tableHeadFill: string;
  tableHeadText: string;
};

/** `report_theme.DEFAULT_ACCENT` — the indigo every report was written in before this existed. */
export const DEFAULT_ACCENT = "#1F3864";

/** `report_theme.CUSTOM_PRESET`. Stored so the picker reopens where the designer left it. */
export const CUSTOM_PRESET = "CUSTOM";

export type AccentPreset = { key: string; label: string; hex: string };

/**
 * `report_theme.ACCENT_PRESETS`, in the same order, which is the order they are drawn in.
 *
 * That order is a lightness ladder from navy to burnt orange, not a rainbow: these reports get
 * printed on monochrome office lasers, where hue is discarded and only lightness survives. Twelve
 * equally dark colours would come out of that tray as twelve identical reports.
 */
export const ACCENT_PRESETS: readonly AccentPreset[] = [
  { key: "NAVY", label: "Navy", hex: "#152A50" },
  { key: "INDIGO", label: "Deep indigo", hex: DEFAULT_ACCENT },
  { key: "FOREST", label: "Forest green", hex: "#1D4835" },
  { key: "PLUM", label: "Plum", hex: "#672F6A" },
  { key: "MAROON", label: "Maroon", hex: "#802F42" },
  { key: "CHARCOAL", label: "Charcoal", hex: "#4B5259" },
  { key: "TEAL", label: "Teal", hex: "#10616A" },
  { key: "BRICK", label: "Brick", hex: "#9C4030" },
  { key: "SLATE", label: "Slate blue", hex: "#4E638E" },
  { key: "OLIVE", label: "Olive", hex: "#6D6B24" },
  { key: "BRONZE", label: "Bronze", hex: "#87682E" },
  { key: "BURNT_ORANGE", label: "Burnt orange", hex: "#AD5D1C" }
] as const;

/* ────────────────────────────────────────────────────────────────────────────
 * Colour arithmetic — `report_theme`'s, line for line
 * ──────────────────────────────────────────────────────────────────────────── */

const HEX_DIGITS = /^[0-9a-fA-F]+$/;

/**
 * `value` as a bare upper-case `RRGGBB`, or null when it is not a colour.
 *
 * Accepts what actually arrives: `#1F3864` from `<input type="color">`, `1f3864` hand-typed into
 * the stage's text field, `#1F8` shorthand, and whitespace around any of them.
 */
export function normaliseHex(value: unknown): string | null {
  if (typeof value !== "string") return null;
  let text = value.trim();
  if (text.startsWith("#")) text = text.slice(1).trim();
  if (!text || !HEX_DIGITS.test(text)) return null;
  // CSS shorthand: each digit doubles. #1F8 is #11FF88, not #001F08.
  if (text.length === 3) text = text.split("").map((digit) => digit + digit).join("");
  if (text.length !== 6) return null;
  return text.toUpperCase();
}

type Rgb = [number, number, number];

function toRgb(hex: string): Rgb {
  return [
    parseInt(hex.slice(0, 2), 16) / 255,
    parseInt(hex.slice(2, 4), 16) / 255,
    parseInt(hex.slice(4, 6), 16) / 255
  ];
}

/**
 * Three 0..1 channels back to `RRGGBB`.
 *
 * `Math.floor(x + 0.5)` and not `Math.round`, matching `int(x + 0.5)` in the Python and
 * `(x + 0.5).toInt()` in the Kotlin. The three languages disagree about halves — Python rounds to
 * even, `Math.round` rounds .5 up but −.5 toward zero — and a channel landing exactly on a half
 * would come back one digit different from the server for the same accent. Written out, all three
 * are half-up, and the clamp keeps a rounding overshoot from producing a seven-digit colour.
 */
function toHex(rgb: Rgb): string {
  return rgb
    .map((channel) => {
      const clamped = Math.max(0, Math.min(1, channel));
      return Math.floor(clamped * 255 + 0.5).toString(16).toUpperCase().padStart(2, "0");
    })
    .join("");
}

/** (hue 0..360, saturation 0..1, lightness 0..1). Hue is 0 for any grey. */
function rgbToHsl([red, green, blue]: Rgb): [number, number, number] {
  const high = Math.max(red, green, blue);
  const low = Math.min(red, green, blue);
  const lightness = (high + low) / 2;
  const span = high - low;
  if (span === 0) return [0, 0, lightness];
  const saturation = lightness > 0.5 ? span / (2 - high - low) : span / (high + low);
  let hue: number;
  if (high === red) hue = (((green - blue) / span) % 6 + 6) % 6;
  else if (high === green) hue = (blue - red) / span + 2;
  else hue = (red - green) / span + 4;
  return [hue * 60, saturation, lightness];
}

function hslToRgb(hue: number, saturation: number, lightness: number): Rgb {
  if (saturation <= 0) return [lightness, lightness, lightness];
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  const sector = (((hue % 360) + 360) % 360) / 60;
  const second = chroma * (1 - Math.abs((sector % 2) - 1));
  let triple: Rgb;
  if (sector < 1) triple = [chroma, second, 0];
  else if (sector < 2) triple = [second, chroma, 0];
  else if (sector < 3) triple = [0, chroma, second];
  else if (sector < 4) triple = [0, second, chroma];
  else if (sector < 5) triple = [second, 0, chroma];
  else triple = [chroma, 0, second];
  const base = lightness - chroma / 2;
  return [triple[0] + base, triple[1] + base, triple[2] + base];
}

function hslHex(hue: number, saturation: number, lightness: number): string {
  return toHex(hslToRgb(hue, Math.max(0, Math.min(1, saturation)), Math.max(0, Math.min(1, lightness))));
}

/** WCAG 2.1 relative luminance of a bare `RRGGBB`. */
export function relativeLuminance(hex: string): number {
  const [red, green, blue] = toRgb(hex).map((channel) =>
    channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4)
  );
  return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
}

/** The WCAG contrast ratio between two bare `RRGGBB` colours: 1 to 21. */
export function contrastRatio(first: string, second: string): number {
  const a = relativeLuminance(first);
  const b = relativeLuminance(second);
  const lighter = Math.max(a, b);
  const darker = Math.min(a, b);
  return (lighter + 0.05) / (darker + 0.05);
}

/* ────────────────────────────────────────────────────────────────────────────
 * The derivation
 * ──────────────────────────────────────────────────────────────────────────── */

const PAPER = "FFFFFF";
const MIN_TEXT_CONTRAST = 3.0;
const MIN_SMALL_TEXT_CONTRAST = 4.5;
const SOFT_LIFT = 0.175;
const SOFT_MAX_LIGHTNESS = 0.62;
const INK_SATURATION = 0.14;
const INK_LIGHTNESS = 0.105;
const MUTED_SATURATION = 0.38;
const MUTED_LIGHTNESS = 0.44;
const RULE_SATURATION = 0.58;
const RULE_LIGHTNESS = 0.786;
const ZEBRA_SATURATION = 0.84;
const ZEBRA_LIGHTNESS = 0.965;
const INK_SATURATION_CAP = 0.1;
const MUTED_SATURATION_CAP = 0.3;
const RULE_SATURATION_CAP = 0.45;
const ZEBRA_SATURATION_CAP = 0.6;

/**
 * The colour at this hue and saturation, darkened one hundredth of a lightness step at a time
 * until it can be read on white paper.
 *
 * A loop rather than a solve, deliberately: the same hundred steps run in the Python and in the
 * Kotlin, and three different closed forms over floating point would be three answers in the last
 * hex digit — which on a table header fill is a visible seam between the file the server writes
 * and the one the phone writes for the same workshop.
 */
function legibleOnPaper(hue: number, saturation: number, lightness: number, minimum: number): string {
  let candidate = hslHex(hue, saturation, lightness);
  for (let step = 0; step <= 100; step += 1) {
    candidate = hslHex(hue, saturation, lightness - step * 0.01);
    if (contrastRatio(candidate, PAPER) >= minimum) break;
  }
  return candidate;
}

function tinted(hue: number, saturation: number, scale: number, cap: number, lightness: number): string {
  return hslHex(hue, Math.min(saturation * scale, cap), lightness);
}

/**
 * A complete, coherent, legible palette from one accent — `report_theme.theme_from_accent`.
 *
 * Never throws and never returns a colour you cannot read: anything unparseable becomes the DCH
 * indigo, a pale accent is darkened until a heading drawn in it is on the page, and the table
 * header's text is white or near-black by measured contrast against the fill rather than by a
 * guess from the hue.
 */
export function paletteFromAccent(accent: unknown): ReportPalette {
  const hex = normaliseHex(accent) ?? normaliseHex(DEFAULT_ACCENT)!;
  const [hue, saturation] = rgbToHsl(toRgb(hex));

  const accentHex = legibleOnPaper(hue, saturation, rgbToHsl(toRgb(hex))[2], MIN_TEXT_CONTRAST);
  const accentLightness = rgbToHsl(toRgb(accentHex))[2];
  const softLightness = Math.min(accentLightness + (1 - accentLightness) * SOFT_LIFT, SOFT_MAX_LIGHTNESS);
  const softHex = legibleOnPaper(hue, saturation, softLightness, MIN_TEXT_CONTRAST);
  const inkHex = tinted(hue, saturation, INK_SATURATION, INK_SATURATION_CAP, INK_LIGHTNESS);
  const headText = contrastRatio(accentHex, PAPER) >= contrastRatio(accentHex, inkHex) ? PAPER : inkHex;

  return {
    paper: "#FFFFFF",
    ink: `#${inkHex}`,
    muted: `#${legibleOnPaper(
      hue,
      Math.min(saturation * MUTED_SATURATION, MUTED_SATURATION_CAP),
      MUTED_LIGHTNESS,
      MIN_SMALL_TEXT_CONTRAST
    )}`,
    rule: `#${tinted(hue, saturation, RULE_SATURATION, RULE_SATURATION_CAP, RULE_LIGHTNESS)}`,
    accent: `#${accentHex}`,
    accentSoft: `#${softHex}`,
    zebra: `#${tinted(hue, saturation, ZEBRA_SATURATION, ZEBRA_SATURATION_CAP, ZEBRA_LIGHTNESS)}`,
    tableHeadFill: `#${accentHex}`,
    tableHeadText: `#${headText}`
  };
}

/**
 * The preset whose colour this is, or null for a colour that came out of the picker.
 *
 * Compared on the DERIVED accent rather than on the string, so a designer who types `#1f3864`
 * into the stage's hex field sees the "Deep indigo" swatch selected rather than an identical
 * colour sitting under "Custom" — the two would print the same file and the screen must not
 * suggest otherwise.
 */
export function presetForAccent(accent: unknown): AccentPreset | null {
  const hex = normaliseHex(accent);
  if (!hex) return null;
  return ACCENT_PRESETS.find((preset) => normaliseHex(preset.hex) === hex) ?? null;
}
