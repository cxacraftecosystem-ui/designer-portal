/**
 * The pure half of `OtpCodeInput` — what a keystroke, a paste or a Backspace does to a six-digit code.
 *
 * Kept out of the component for the reason `selectFilter.ts` gives: there is no React renderer in
 * devDependencies, so a rule that lives inside JSX is exercised only by somebody looking at a screen.
 * `e2e/otp-code-input-unit.spec.ts` calls these directly. Android's `OtpCode` object
 * (`ui/OtpCodeField.kt`) is the same rules, function for function, so one sequence of keys leaves the
 * same six boxes on both clients.
 *
 * THE MODEL IS A CONTIGUOUS STRING OF DIGITS, never an array with holes. Box n shows character n; the
 * caret can only stand on a filled box or the first empty one. A code with a gap in the middle is not
 * a code anybody can submit, and a model that can hold one is a model that has to explain it.
 *
 * DIGITS ONLY, ASCII ONLY. A letter, a space, a hyphen or a full-width "１" typed into a box is
 * refused outright (the box does not change); the same characters inside a PASTE are stripped, because
 * "123 456" and "123-456" are how authenticator apps and SMS messages print a code and the person
 * meant the six digits. The server's own `match_totp_step` refuses anything but six ASCII digits too.
 */

export const OTP_LENGTH = 6;

/** Every ASCII digit in `text`, in order. Nothing else survives — not even full-width digits. */
export function onlyDigits(text: string): string {
  return text.replace(/[^0-9]/g, "");
}

/** The code is whole: exactly six ASCII digits. */
export function isCompleteCode(value: string): boolean {
  return value.length === OTP_LENGTH && /^[0-9]{6}$/.test(value);
}

/** Clamp an incoming value (a parent's state, an autofill) to the model: digits only, at most six. */
export function normaliseCode(value: string): string {
  return onlyDigits(value).slice(0, OTP_LENGTH);
}

export type OtpEdit = {
  /** The code after the edit. */
  value: string;
  /** Which box the caret belongs in now. */
  focus: number;
  /** False when the keystroke was refused (not a digit) and nothing changed. */
  accepted: boolean;
};

/** The box a caret may stand in: a filled box, or the first empty one — never past it. */
export function reachableBox(value: string, index: number): number {
  return Math.max(0, Math.min(index, value.length, OTP_LENGTH - 1));
}

/**
 * What the code becomes when `text` arrives in box `index` — a typed digit, or a whole string from
 * a paste or the browser's one-time-code autofill (which delivers all six into one box).
 *
 * One digit REPLACES the digit in that box (or extends the code when the box was the first empty one)
 * and moves the caret one box on. Several digits are written from that box forward, overwriting, and
 * a string carrying six or more digits fills all six boxes whichever box it landed in — that is what
 * "paste the code" means to the person doing it.
 */
export function typeInto(value: string, index: number, text: string): OtpEdit {
  const current = normaliseCode(value);
  const digits = onlyDigits(text);
  if (!digits) return { value: current, focus: reachableBox(current, index), accepted: false };
  if (digits.length >= OTP_LENGTH) {
    return { value: digits.slice(0, OTP_LENGTH), focus: OTP_LENGTH - 1, accepted: true };
  }
  const at = reachableBox(current, index);
  const next = (current.slice(0, at) + digits + current.slice(at + digits.length)).slice(0, OTP_LENGTH);
  return { value: next, focus: Math.min(at + digits.length, OTP_LENGTH - 1), accepted: true };
}

/** A paste is `typeInto` with the pasted text — kept as its own name so call sites read as what they do. */
export function pasteInto(value: string, index: number, text: string): OtpEdit {
  return typeInto(value, index, text);
}

/**
 * Backspace in box `index`: a filled box is emptied and the caret stays; an empty box sends the caret
 * back one and empties THAT box — the way every code entry on a phone behaves, so holding Backspace
 * walks the whole code away.
 */
export function backspaceAt(value: string, index: number): OtpEdit {
  const current = normaliseCode(value);
  const at = reachableBox(current, index);
  if (at < current.length) {
    return { value: current.slice(0, at) + current.slice(at + 1), focus: at, accepted: true };
  }
  if (at === 0) return { value: current, focus: 0, accepted: false };
  return { value: current.slice(0, at - 1) + current.slice(at), focus: at - 1, accepted: true };
}

/** Delete in box `index`: empties that box and leaves the caret where it is. */
export function deleteAt(value: string, index: number): OtpEdit {
  const current = normaliseCode(value);
  const at = reachableBox(current, index);
  if (at >= current.length) return { value: current, focus: at, accepted: false };
  return { value: current.slice(0, at) + current.slice(at + 1), focus: at, accepted: true };
}

/** Arrow keys, Home and End: where the caret goes. Never past the first empty box. */
export function moveFocus(value: string, index: number, key: string): number {
  const current = normaliseCode(value);
  switch (key) {
    case "ArrowLeft":
      return reachableBox(current, index - 1);
    case "ArrowRight":
      return reachableBox(current, index + 1);
    case "Home":
      return 0;
    case "End":
      return reachableBox(current, OTP_LENGTH - 1);
    default:
      return reachableBox(current, index);
  }
}

/** The accessible name of box `n` (zero-based in, one-based out): "Digit 3 of 6". */
export function digitLabel(index: number): string {
  return `Digit ${index + 1} of ${OTP_LENGTH}`;
}

/**
 * The refusal shake: ±6px over 360ms on the house ease-out, or nothing at all under reduced motion.
 * The red border on every box is the static half and is drawn either way.
 */
export function otpShake(reduce: boolean): { offsets: number[]; duration: number; easing: string } | null {
  if (reduce) return null;
  return { offsets: [0, -6, 6, -6, 6, 0], duration: 360, easing: "cubic-bezier(0.16, 1, 0.3, 1)" };
}
