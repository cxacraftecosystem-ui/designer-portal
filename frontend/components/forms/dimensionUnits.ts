/**
 * CENTIMETRES ↔ INCHES FOR THE RECORD FORMS — the pure half, so a judgement about a stored number
 * is exercised by a test rather than only by somebody looking at a screen.
 *
 * `ToolDocumentation.height` and `.width` are the CENTIMETRE boxes and `heightInches` /
 * `breadthInches` are their inch partners; `lengthInches` is standalone and has no partner. Typing
 * in either box of a pair fills the other, and this module is the whole of the arithmetic and the
 * whole of the rule about WHEN a partner may be written.
 *
 * ── WHY IT IS NOT INSIDE `ToolForm.tsx` ─────────────────────────────────────────────────────────
 * There is no React renderer in this repository's devDependencies — Playwright is the whole of it —
 * so a decision written inside JSX can never be driven by a test. The same split, for the same
 * reason, as `components/forms/measurementMethods.ts`, `components/media/gridProposal.ts` and
 * `components/ui/selectFilter.ts`. `frontend/e2e/dimension-units-unit.spec.ts` drives every row of
 * the table below.
 *
 * ── WHY THE ARITHMETIC IS SPELLED THIS PEDANTICALLY ─────────────────────────────────────────────
 * The identical function exists on the handset (`android/.../ui/DimensionUnits.kt`) and the two
 * clients write into the same `Decimal(10, 2)` columns. If they disagree by a hundredth, a tool
 * edited on a laptop and then on a phone changes its own width on every save, silently, for ever.
 * So every step below is chosen to be bit-identical between binary64 in V8 and `Double` on the JVM:
 *
 *  1. ONE GRAMMAR, not each language's own parser. `Number("0x1A")` is 26 while `"0x1A".toDouble()`
 *     throws; `"1.5f".toDouble()` is 1.5 while `Number("1.5f")` is NaN; both accept `"Infinity"`.
 *     A regex both languages implement identically is the only shape the four clients cannot drift
 *     apart on.
 *  2. ONE ROUNDING, expressed as `floor(x + 0.5)`. `kotlin.math.round` is half-away-from-zero and
 *     the JVM's `Math.round` is `floor(x + 0.5)`; they differ for negatives, and a latent
 *     divergence is not made safe by the grammar currently refusing a minus sign.
 *  3. THE OUTPUT NEVER PASSES THROUGH EITHER LANGUAGE'S FLOAT-TO-STRING. JavaScript prints `3` for
 *     an integral 3.0 and Kotlin prints `3.0`. {@link renderScaled} builds the text from the scaled
 *     INTEGER instead.
 *  4. ASSOCIATION ORDER IS LOAD-BEARING. `v * 2.54 * 100` must not be rewritten as `v * 254`, and
 *     `v / 2.54 * 100` must not become `v * 0.3937007874015748`. Each rewrite moves the last ulp and
 *     can flip a hundredth.
 *
 * ── AND WHY IT IS NOT THE BACKEND'S `_inches_to_cm` ─────────────────────────────────────────────
 * `backend/app/services/design_workshops.py` has an `_inches_to_cm` that reads
 * `round(float(v) * 2.54, 2)` — Python's BANKER'S rounding, a different halfway rule from the one
 * here. The two live on opposite sides of the wire, are applied to different columns (that one
 * carries a tool's inches into a design-workshop stage box; this one pairs two columns of the tool
 * record itself), and must NOT be unified: doing so would change every workshop carry figure that
 * lands on a halfway value. The cross-reference is written in both directions on purpose.
 */

/**
 * EXACT. The inch has been defined as 25.4 mm since the 1959 international yard-and-pound
 * agreement, so this is not a measured constant with an error bar and never "2.5 for readability".
 * Named identically to the backend's `design_workshops._CM_PER_INCH` and to Android's
 * `DimensionUnits.CM_PER_INCH`.
 */
export const CM_PER_INCH = 2.54;

/**
 * The one grammar — see the header. No sign, no exponent, no hex, no thousands separator, no
 * trailing type suffix; leading and trailing spaces and tabs only.
 *
 * `1.` AND `.5` ARE BOTH ADMITTED, DELIBERATELY — AND THE BROWSER'S OWN BOXES NEVER PRODUCE EITHER.
 * They are what a person mid-decimal has actually typed, and on the handset that is literally what
 * arrives: `TextInput(… keyboardType = KeyboardType.Decimal)` hands `onValueChange` the raw string,
 * so `propagateDimension` really does see `"1."`. In this client every paired box is a native
 * `<input type="number">`, whose `.value` is `""` for anything the HTML floating-point grammar
 * refuses — and `"1."` is refused, because that grammar requires a digit after the point. So the
 * admission here is for the TWIN and for callers that are not an input at all (the two measurement
 * routes hand {@link propagate} a formatted reading directly), not for anything a browser keystroke
 * can reach. Keep it: a grammar the four clients do not share is how they come to disagree by a
 * hundredth. See {@link propagate}'s rule 4 for what the browser does instead.
 */
const NUMERIC_RE = /^[ \t]*([0-9]+\.?[0-9]*|\.[0-9]+)[ \t]*$/;

/** `99,999,999.99`, scaled by 100 — the largest value a `Decimal(10, 2)` column can hold. */
const DECIMAL_10_2_MAX_SCALED = 9999999999;

/**
 * The number in a dimension box, or null where the text cannot be one.
 *
 * Non-negative by construction: the grammar admits no minus sign, which is the same bound the boxes
 * carry as `min={0}` and the schemas carry as `ge=0`.
 *
 * THE `isFinite` GUARD IS REACHABLE, contrary to what a first reading of the grammar suggests: a
 * four-hundred-digit integer matches {@link NUMERIC_RE} perfectly and `Number()` answers `Infinity`
 * for it, in both languages. Without the guard `Infinity * 2.54 * 100` would pass the
 * `Decimal(10, 2)` ceiling test — `Infinity > 9999999999` is true, so it is refused there too — but
 * only by accident of the comparison, and a value that is not a number must be refused for being one
 * rather than for being large. The Kotlin twin carries the same check for the same reason.
 */
export function parseDimension(text: string): number | null {
  if (!NUMERIC_RE.test(text)) return null;
  const value = Number(text.trim());
  return Number.isFinite(value) ? value : null;
}

/**
 * Hundredths, from the scaled integer — never from `Number.prototype.toString`.
 *
 * `254 → "254"`, `2540 → "25.4"`, `318 → "3.18"`. Trailing zeros are dropped so the text reads the
 * way a person would have typed it, and so an inch value that converts back to a whole number of
 * centimetres does not print `"254.00"` into a box whose partner said `"100"`.
 */
function renderScaled(scaled: number): string {
  const whole = Math.floor(scaled / 100);
  const frac = scaled % 100;
  if (frac === 0) return String(whole);
  if (frac % 10 === 0) return `${whole}.${frac / 10}`;
  return `${whole}.${frac < 10 ? `0${frac}` : frac}`;
}

/** Centimetres, two decimals, from an inch box's text — or null where nothing may be written. */
export function cmTextFromInches(text: string): string | null {
  const value = parseDimension(text);
  if (value === null) return null;
  const scaled = Math.floor(value * CM_PER_INCH * 100 + 0.5);
  if (scaled > DECIMAL_10_2_MAX_SCALED) return null;
  return renderScaled(scaled);
}

/** Inches, two decimals, from a centimetre box's text — or null where nothing may be written. */
export function inchesTextFromCm(text: string): string | null {
  const value = parseDimension(text);
  if (value === null) return null;
  const scaled = Math.floor((value / CM_PER_INCH) * 100 + 0.5);
  if (scaled > DECIMAL_10_2_MAX_SCALED) return null;
  return renderScaled(scaled);
}

/**
 * WRITE THE PARTNER BOX — the four rules that keep this pairing from eating a stored number.
 *
 * 1. **ONE-DIRECTIONAL.** This is only ever called from the change handler of the box a person is
 *    typing in, and it only ever writes the OTHER box. It must never be reached from an effect
 *    watching both values: a watcher fires for the value it just wrote, and `1 cm → 0.39 in →
 *    0.99 cm` is how a round trip eats a hundredth per keystroke. React's `setState` does not
 *    re-invoke the target control's own `onChange`, so the natural shape is already correct — the
 *    rule is here to stop somebody "simplifying" it into a `useEffect`.
 * 2. **CLEARING PROPAGATES, AND IT IS THE FIRST BRANCH.** An empty box means "no value", and
 *    leaving a stale converted number standing beside it is a lie about a measurement. This is the
 *    one input that fails the grammar and still writes the partner, which is why it is tested
 *    before the parse rather than falling out of it.
 * 3. **ANYTHING ELSE THAT IS NOT A NUMBER WRITES NOTHING.** `"-"`, `"1.2.3"`, `"abc"`, `"1e3"` and
 *    a value past `Decimal(10, 2)` leave the partner exactly as it is.
 * 4. **A PARTIALLY TYPED DECIMAL IS A NUMBER, NOT A MISTAKE** — where the caller can hand one over.
 *    `"1."` parses and converts, so the partner tracks the keystrokes instead of blanking and
 *    refilling, and `"1.2."` (not a number) holds `3.05` for exactly the one keystroke it takes to
 *    finish typing rather than claiming the designer had said there is no width.
 *
 * ── AND RULE 4 DOES NOT DESCRIBE THIS CLIENT'S KEYSTROKES, WHICH IS WORTH KNOWING BEFORE YOU
 *    DEBUG THE CENTIMETRE BOX BLINKING ──────────────────────────────────────────────────────────
 * Every paired box on `ToolForm` is `<input type="number">`, and a native number input reports `""`
 * for text the HTML floating-point grammar refuses — `"1."` among it. So the keystroke that leaves
 * `"3."` in the Height (inches) box arrives here as the EMPTY string, takes rule 2, and blanks the
 * centimetre partner; the next digit refills it. It is visible, it is not data loss by itself, and
 * it has one sharp edge: a save pressed at exactly that instant sends `null` for BOTH columns of the
 * pair, because the state really is empty while the box still draws `3.` (the browser keeps the
 * rejected text, and React skips a DOM write it believes is already `""`).
 *
 * The rule is kept as it is because the grammar is a four-client contract (see {@link NUMERIC_RE}):
 * the handset's Compose field DOES hand over `"1."`, and so do the measurement routes, which call
 * {@link propagate} with a formatted reading and never touch an input. Making rule 4 reachable in the
 * browser would mean `type="text" inputMode="decimal"` on all four boxes, which trades this blink for
 * the loss of `min={0}` refusal at the control — a worse bargain on columns the schema bounds `ge=0`.
 */
export function propagate(
  text: string,
  convert: (value: string) => string | null,
  setPartner: (value: string) => void
): void {
  if (text.trim() === "") {
    setPartner("");
    return;
  }
  const next = convert(text);
  if (next !== null) setPartner(next);
}
