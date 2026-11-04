export const OTP_LENGTH = 6;

export function onlyDigits(text: string): string {
  return text.replace(/[^0-9]/g, "");
}

export function isCompleteCode(value: string): boolean {
  return value.length === OTP_LENGTH && /^[0-9]{6}$/.test(value);
}

export function normaliseCode(value: string): string {
  return onlyDigits(value).slice(0, OTP_LENGTH);
}

export type OtpEdit = {
  value: string;
  focus: number;
  accepted: boolean;
};

export function reachableBox(value: string, index: number): number {
  return Math.max(0, Math.min(index, value.length, OTP_LENGTH - 1));
}

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

export function pasteInto(value: string, index: number, text: string): OtpEdit {
  return typeInto(value, index, text);
}

export function backspaceAt(value: string, index: number): OtpEdit {
  const current = normaliseCode(value);
  const at = reachableBox(current, index);
  if (at < current.length) {
    return { value: current.slice(0, at) + current.slice(at + 1), focus: at, accepted: true };
  }
  if (at === 0) return { value: current, focus: 0, accepted: false };
  return { value: current.slice(0, at - 1) + current.slice(at), focus: at - 1, accepted: true };
}

export function deleteAt(value: string, index: number): OtpEdit {
  const current = normaliseCode(value);
  const at = reachableBox(current, index);
  if (at >= current.length) return { value: current, focus: at, accepted: false };
  return { value: current.slice(0, at) + current.slice(at + 1), focus: at, accepted: true };
}

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

export function digitLabel(index: number): string {
  return `Digit ${index + 1} of ${OTP_LENGTH}`;
}

export function otpShake(reduce: boolean): { offsets: number[]; duration: number; easing: string } | null {
  if (reduce) return null;
  return { offsets: [0, -6, 6, -6, 6, 0], duration: 360, easing: "cubic-bezier(0.16, 1, 0.3, 1)" };
}
