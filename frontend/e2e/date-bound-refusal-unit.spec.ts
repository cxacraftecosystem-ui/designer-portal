import { expect, test } from "@playwright/test";

import {
  boundRefusalMessage,
  dateOutsideBounds,
  formatDisplayDate,
  fromIsoDate,
  parseTypedDate,
  refusalStands,
  toIsoDate,
  type DateRefusal
} from "@/components/forms/DateTimeField";

/**
 * THE LIFETIME OF A REFUSED DATE — what `DateField` shows, stores and reverts to, keystroke by
 * keystroke.
 *
 * THE ORIGINAL DEFECT, WHICH MUST STAY CLOSED. A workshop stored 12/03/2026–14/03/2026 is
 * re-sanctioned for 16–18 March. The designer types 16/03/2026 into Start date, which `endDate` caps
 * at 14/03. The field parsed it, declined it, and said nothing: the characters sat in the box until
 * focus left, and blur then rewrote them as "12/03/2026". She typed 18/03 into End date — that one
 * was above ITS bound, so it committed — and saved 12/03/2026–18/03/2026, a start date nobody chose,
 * on the cover of a report, with the stage still reading complete. The remedy was to HOLD the
 * refused date in the box, say which field the bound came from, and commit it by itself once that
 * field moves.
 *
 * THE DEFECT IN THAT REMEDY, which is what this file was added for. The held refusal had no
 * lifetime: it was raised in `handleText` and every reader tested `refusal !== null`. One keystroke
 * past the refusal — the first backspace of the correction — and
 *
 *   * the sentence underneath went on naming a date that was no longer in the box, under an input
 *     the designer was actively retyping and still marked invalid; and
 *   * `revert()`, whose guard was `if (refusal) return`, stopped resetting the field FOR GOOD, so a
 *     typo left in the box on blur stayed there parsing to nothing while the value actually stored
 *     was unreadable from the screen — the original defect, arrived at from the other side.
 *
 * The rule that closes both is `refusalStands`: a refusal describes ONE DATE and lives exactly as
 * long as the box holds it.
 *
 * WHY A UNIT SPEC AND NOT A BROWSER ONE. The subtlety is a SEQUENCE — type, refuse, backspace,
 * retype, blur, move the partner field — and a browser spec for it needs a signed-in stack running
 * before it can check anything at all. The pieces the sequence turns on are pure and exported
 * (`refusalStands`, `dateOutsideBounds`, `parseTypedDate`, `boundRefusalMessage`), so the whole of
 * it is walked here in milliseconds against the same functions the component calls.
 *
 * READ THIS TOGETHER WITH `discarded-work-unit.spec.ts`, which pins the same field from the other
 * side: it asserts on the SOURCE of `DateField` — that `revert()` still guards on `refusal`, that
 * `handleText` records the bound instead of swallowing it — where this file asserts on the
 * behaviour those lines produce. Neither is a substitute for the other, and the two must be kept
 * agreeing: if you change `handleText`'s shape, the harness below has to move with it.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * `DateField`'s state, without React
 *
 * A faithful miniature: the same three pieces of state, the same handlers in the same order, and
 * the same two effects. Where it models something the component gets from the DOM — whether the
 * input has focus — that is spelled out rather than assumed, because the focus check is what stops
 * a commit from rewriting text the designer is still typing.
 * ──────────────────────────────────────────────────────────────────────────── */

class DateBox {
  iso: string;
  text: string;
  /** `heldRefusal` in the component: what `handleText` recorded, whatever the box shows now. */
  heldRefusal: DateRefusal | null = null;
  focused = false;
  min?: string;
  max?: string;
  /** What the bound is called on screen, so the refusal can name the field to change first. */
  maxLabel?: string;

  constructor(init: { iso?: string; min?: string; max?: string; maxLabel?: string }) {
    this.iso = init.iso ?? "";
    this.min = init.min;
    this.max = init.max;
    this.maxLabel = init.maxLabel;
    const start = fromIsoDate(this.iso);
    this.text = start ? formatDisplayDate(start) : "";
  }

  /** `refusal` in the component: the record IF the box still holds the date it was raised for. */
  get refusal(): DateRefusal | null {
    return refusalStands(this.heldRefusal, this.text) ? this.heldRefusal : null;
  }

  /** The sentence rendered under the box, or null when there is none. */
  get message(): string | null {
    const held = this.refusal;
    if (!held) return null;
    return boundRefusalMessage(
      held.date,
      held.bound,
      (held.bound === "min" ? this.min : this.max) ?? "",
      held.bound === "min" ? undefined : this.maxLabel
    );
  }

  /** `aria-invalid` on the input. */
  get invalid(): boolean {
    return this.refusal !== null;
  }

  /** `commit` — the only thing that writes the stored value. */
  private commit(date: Date | undefined) {
    this.heldRefusal = null;
    this.iso = date ? toIsoDate(date) : "";
    // The `[iso]` effect: a value changed from outside reaches the visible text, but NOT while the
    // input has focus, or normalising a half-typed date would fight the typist.
    if (!this.focused) this.syncTextFromIso();
  }

  private syncTextFromIso() {
    const current = fromIsoDate(this.iso);
    this.text = current ? formatDisplayDate(current) : "";
  }

  /** `handleText` — one keystroke, branch for branch. */
  type(next: string) {
    this.focused = true;
    this.text = next;
    if (next.trim() === "") return this.commit(undefined);
    const parsed = parseTypedDate(next);
    if (!parsed) return;
    const outside = dateOutsideBounds(parsed, this.min, this.max);
    if (outside) {
      this.heldRefusal = { date: parsed, bound: outside };
      return;
    }
    this.commit(parsed);
  }

  /** `revert` — the blur handler. */
  blur() {
    this.focused = false;
    if (this.refusal) return;
    this.heldRefusal = null;
    this.syncTextFromIso();
  }

  /** The partner field moves, and the self-commit effect re-checks the held refusal. */
  moveMax(next: string | undefined) {
    this.max = next;
    const held = this.refusal;
    if (!held) return;
    if (dateOutsideBounds(held.date, this.min, this.max)) return;
    this.commit(held.date);
  }
}

/** The workshop from the incident: stored 12–14 March, being moved to 16–18. */
function startDateBox() {
  return new DateBox({ iso: "2026-03-12", max: "2026-03-14", maxLabel: "End date" });
}

/* ────────────────────────────────────────────────────────────────────────────
 * The original defect, still closed
 * ──────────────────────────────────────────────────────────────────────────── */

test("a refused date stays in the box, says why, and is NOT reverted on blur", () => {
  const box = startDateBox();
  box.type("16/03/2026");

  // Not stored — it really is outside the bound…
  expect(box.iso).toBe("2026-03-12");
  // …but it is still on screen, flagged, and the sentence names the field to change first. Quietly
  // putting "12/03/2026" back here is the bug that shipped a start date nobody chose.
  expect(box.text).toBe("16/03/2026");
  expect(box.invalid).toBe(true);
  expect(box.message).toContain("End date");

  box.blur();
  expect(box.text).toBe("16/03/2026");
  expect(box.message).toContain("End date");
});

test("the refused date commits itself once the bound that refused it moves", () => {
  // The other half of the original remedy: the designer reads "change End date first", changes it,
  // and must not come back to a Start date box showing text that was never stored.
  const box = startDateBox();
  box.type("16/03/2026");
  box.blur();

  box.moveMax("2026-03-18");
  expect(box.iso).toBe("2026-03-16");
  expect(box.text).toBe("16/03/2026");
  expect(box.message).toBeNull();
  expect(box.invalid).toBe(false);
});

test("a partner moved only PART of the way still refuses, with the sentence updated", () => {
  const box = startDateBox();
  box.type("16/03/2026");
  box.moveMax("2026-03-15");

  expect(box.iso).toBe("2026-03-12");
  expect(box.message).toContain("15/03/2026");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The defect IN that remedy: a refusal with no lifetime
 * ──────────────────────────────────────────────────────────────────────────── */

test("the refusal goes as soon as the date it is about leaves the box", () => {
  // The first backspace of the correction. "16/03/202" is not a date, so nothing is stored and
  // nothing is refused — and the sentence must not go on naming 16/03/2026, which is no longer on
  // screen, under a box being actively retyped.
  const box = startDateBox();
  box.type("16/03/2026");
  expect(box.message).not.toBeNull();

  box.type("16/03/202");
  expect(box.message).toBeNull();
  expect(box.invalid).toBe(false);
});

test("blur stays reachable after a refusal has been typed over", () => {
  // The guard was `if (refusal) return` over the RAW record, which made a refusal a one-way door:
  // the field never reset itself again. Leave a typo in the box and tab away, and it sat there
  // parsing to nothing while the stored value — the one thing that tells a designer what the record
  // holds — was invisible.
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("16/03/2");

  box.blur();
  expect(box.text).toBe("12/03/2026");
  expect(box.message).toBeNull();
  expect(box.invalid).toBe(false);
  expect(box.iso).toBe("2026-03-12");
});

test("retyping the refused date brings the refusal back", () => {
  // Exact in both directions. The date really is still outside the bound, so the sentence is owed
  // again — a refusal that could only ever be shown once would leave the second attempt looking as
  // though it had been accepted.
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("16/03/202");
  expect(box.message).toBeNull();

  box.type("16/03/2026");
  expect(box.message).toContain("End date");
  expect(box.invalid).toBe(true);
});

test("typing an ACCEPTABLE date clears the refusal and stores it", () => {
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("13/03/2026");

  expect(box.iso).toBe("2026-03-13");
  expect(box.message).toBeNull();
  expect(box.invalid).toBe(false);
});

test("typing a DIFFERENT out-of-bounds date replaces the sentence rather than keeping the old one", () => {
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("20/03/2026");

  expect(box.message).toContain("20/03/2026");
  expect(box.message).not.toContain("16/03/2026");
});

test("emptying the box clears the value and the refusal together", () => {
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("");

  expect(box.iso).toBe("");
  expect(box.message).toBeNull();
  expect(box.invalid).toBe(false);
});

test("a refusal that was typed over does NOT commit itself when the bound later moves", () => {
  // The self-commit is for a date the designer is waiting on, which is a date still in the box.
  // Writing 16/03 here — minutes later, because a sibling field changed — would be the silent
  // substitution the whole refusal state exists to prevent, and the designer would never see it
  // happen: the box shows something else entirely.
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("16/03/2");
  box.blur();

  box.moveMax("2026-03-31");
  expect(box.iso).toBe("2026-03-12");
  expect(box.text).toBe("12/03/2026");
});

/* ────────────────────────────────────────────────────────────────────────────
 * The rule itself
 * ──────────────────────────────────────────────────────────────────────────── */

test("refusalStands is about the date in the box, and nothing else", () => {
  // Stated directly, because every gate in the field reads it and the whole of this file's second
  // half is a consequence of it. `held !== null` is what it replaced.
  const held: DateRefusal = { date: new Date(2026, 2, 16), bound: "max" };
  expect(refusalStands(held, "16/03/2026")).toBe(true);
  // Half-typed text is not a date, so it is not THIS date: the message goes and the revert returns.
  expect(refusalStands(held, "16/03/202")).toBe(false);
  expect(refusalStands(held, "")).toBe(false);
  expect(refusalStands(held, "17/03/2026")).toBe(false);
  // The same day typed in the other accepted notation IS the same subject — the refusal is about
  // the day, not about the characters.
  expect(refusalStands(held, "2026-03-16")).toBe(true);
  expect(refusalStands(null, "16/03/2026")).toBe(false);
});

test("an out-of-range date and half-typed text are different answers", () => {
  // These two shared a branch — a bare `return` — before the refusal state existed, and that is the
  // whole of the original defect. `handleText` must never merge them again.
  expect(parseTypedDate("16/03/202")).toBeUndefined();
  expect(parseTypedDate("31/02/2026")).toBeUndefined();
  const typed = parseTypedDate("16/03/2026");
  expect(typed).toBeTruthy();
  expect(dateOutsideBounds(typed as Date, undefined, "2026-03-14")).toBe("max");
});
