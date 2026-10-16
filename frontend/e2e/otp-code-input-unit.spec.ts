import { expect, test } from "@playwright/test";

import {
  OTP_LENGTH,
  backspaceAt,
  deleteAt,
  digitLabel,
  isCompleteCode,
  moveFocus,
  normaliseCode,
  onlyDigits,
  otpShake,
  pasteInto,
  reachableBox,
  typeInto
} from "@/components/ui/otpCode";

/**
 * THE SIX-BOX CODE ENTRY — the owner's rules (2026-10-17), keystroke by keystroke.
 *
 * Exactly six boxes; digits only (a letter is refused and the box does not change); a paste of the
 * code fills all six from whichever box it lands in; typing advances; Backspace on an empty box steps
 * back and empties that one; the code is complete only at six ASCII digits. `OtpCodeInput` renders
 * these functions and nothing else decides; Android's `OtpCode` (OtpCodeTest) is the same table.
 */

test("there are exactly six boxes, named Digit n of 6", () => {
  expect(OTP_LENGTH).toBe(6);
  expect(Array.from({ length: OTP_LENGTH }, (_, i) => digitLabel(i))).toEqual([
    "Digit 1 of 6",
    "Digit 2 of 6",
    "Digit 3 of 6",
    "Digit 4 of 6",
    "Digit 5 of 6",
    "Digit 6 of 6"
  ]);
});

test("digits only: a letter, a space, a hyphen or a full-width digit typed into a box is refused", () => {
  for (const key of ["a", "Z", " ", "-", "+", ".", "１", "٣"]) {
    const edit = typeInto("12", 2, key);
    expect(edit.accepted, key).toBe(false);
    expect(edit.value, key).toBe("12");
    expect(edit.focus, key).toBe(2);
  }
  expect(onlyDigits("１２3a4-5 6")).toBe("3456");
});

test("typing a digit fills the box and moves the caret on; the last box keeps the caret", () => {
  let value = "";
  let focus = 0;
  for (const digit of "482913") {
    const edit = typeInto(value, focus, digit);
    expect(edit.accepted).toBe(true);
    value = edit.value;
    focus = edit.focus;
  }
  expect(value).toBe("482913");
  expect(focus).toBe(5);
  expect(isCompleteCode(value)).toBe(true);
});

test("typing over a filled box replaces that digit and nothing else", () => {
  expect(typeInto("123456", 2, "9")).toEqual({ value: "129456", focus: 3, accepted: true });
});

test("the caret can never stand past the first empty box, so the code has no holes", () => {
  expect(reachableBox("12", 5)).toBe(2);
  expect(typeInto("12", 5, "3")).toEqual({ value: "123", focus: 3, accepted: true });
  expect(moveFocus("12", 1, "ArrowRight")).toBe(2);
  expect(moveFocus("12", 2, "ArrowRight")).toBe(2);
  expect(moveFocus("12", 2, "End")).toBe(2);
  expect(moveFocus("123456", 0, "End")).toBe(5);
  expect(moveFocus("123456", 3, "Home")).toBe(0);
  expect(moveFocus("123456", 0, "ArrowLeft")).toBe(0);
});

test("a paste of the six-digit code fills every box, from whichever box it lands in", () => {
  for (const box of [0, 2, 5]) {
    expect(pasteInto("", box, "739104")).toEqual({ value: "739104", focus: 5, accepted: true });
    expect(pasteInto("11", box, "739104")).toEqual({ value: "739104", focus: 5, accepted: true });
  }
  // How authenticators, SMS and e-mails print a code: the separators go, the digits stay.
  expect(pasteInto("", 0, "739 104").value).toBe("739104");
  expect(pasteInto("", 0, "739-104").value).toBe("739104");
  expect(pasteInto("", 0, " Your code is 739104. ").value).toBe("739104");
  // More than six: the first six. Nothing numeric at all: refused, unchanged.
  expect(pasteInto("", 0, "73910455").value).toBe("739104");
  expect(pasteInto("12", 2, "abc")).toEqual({ value: "12", focus: 2, accepted: false });
});

test("an autofill of the whole code into the first box fills all six (autocomplete=one-time-code)", () => {
  expect(typeInto("", 0, "905512")).toEqual({ value: "905512", focus: 5, accepted: true });
});

test("a partial paste is written from the box it landed in", () => {
  expect(pasteInto("1", 1, "234")).toEqual({ value: "1234", focus: 4, accepted: true });
});

test("Backspace empties a filled box in place, and on an empty box steps back and empties that", () => {
  expect(backspaceAt("1234", 3)).toEqual({ value: "123", focus: 3, accepted: true });
  expect(backspaceAt("123", 3)).toEqual({ value: "12", focus: 2, accepted: true });
  expect(backspaceAt("123456", 5)).toEqual({ value: "12345", focus: 5, accepted: true });
  expect(backspaceAt("", 0)).toEqual({ value: "", focus: 0, accepted: false });
  // Holding Backspace walks the whole code away.
  let value = "123456";
  let focus = 5;
  for (let i = 0; i < 8; i += 1) ({ value, focus } = backspaceAt(value, focus));
  expect(value).toBe("");
  expect(focus).toBe(0);
});

test("Delete empties the box under the caret and leaves the caret there", () => {
  expect(deleteAt("123456", 0)).toEqual({ value: "23456", focus: 0, accepted: true });
  expect(deleteAt("12", 2).accepted).toBe(false);
});

test("complete means exactly six ASCII digits — the moment onComplete fires", () => {
  expect(isCompleteCode("12345")).toBe(false);
  expect(isCompleteCode("123456")).toBe(true);
  expect(isCompleteCode("1234567")).toBe(false);
  expect(isCompleteCode("12345a")).toBe(false);
  expect(normaliseCode("12-34-56-78")).toBe("123456");
});

test("the refusal shakes ±6px over 360ms, and not at all under reduced motion", () => {
  expect(otpShake(true)).toBeNull();
  const shake = otpShake(false);
  expect(shake?.offsets).toEqual([0, -6, 6, -6, 6, 0]);
  expect(shake?.duration).toBe(360);
  expect(shake?.easing).toBe("cubic-bezier(0.16, 1, 0.3, 1)");
});
