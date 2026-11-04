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

class DateBox {
  iso: string;
  text: string;
  heldRefusal: DateRefusal | null = null;
  focused = false;
  min?: string;
  max?: string;
  maxLabel?: string;

  constructor(init: { iso?: string; min?: string; max?: string; maxLabel?: string }) {
    this.iso = init.iso ?? "";
    this.min = init.min;
    this.max = init.max;
    this.maxLabel = init.maxLabel;
    const start = fromIsoDate(this.iso);
    this.text = start ? formatDisplayDate(start) : "";
  }

  get refusal(): DateRefusal | null {
    return refusalStands(this.heldRefusal, this.text) ? this.heldRefusal : null;
  }

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

  get invalid(): boolean {
    return this.refusal !== null;
  }

  private commit(date: Date | undefined) {
    this.heldRefusal = null;
    this.iso = date ? toIsoDate(date) : "";
    if (!this.focused) this.syncTextFromIso();
  }

  private syncTextFromIso() {
    const current = fromIsoDate(this.iso);
    this.text = current ? formatDisplayDate(current) : "";
  }

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

  blur() {
    this.focused = false;
    if (this.refusal) return;
    this.heldRefusal = null;
    this.syncTextFromIso();
  }

  moveMax(next: string | undefined) {
    this.max = next;
    const held = this.refusal;
    if (!held) return;
    if (dateOutsideBounds(held.date, this.min, this.max)) return;
    this.commit(held.date);
  }
}

function startDateBox() {
  return new DateBox({ iso: "2026-03-12", max: "2026-03-14", maxLabel: "End date" });
}

test("a refused date stays in the box, says why, and is NOT reverted on blur", () => {
  const box = startDateBox();
  box.type("16/03/2026");

  expect(box.iso).toBe("2026-03-12");
  expect(box.text).toBe("16/03/2026");
  expect(box.invalid).toBe(true);
  expect(box.message).toContain("End date");

  box.blur();
  expect(box.text).toBe("16/03/2026");
  expect(box.message).toContain("End date");
});

test("the refused date commits itself once the bound that refused it moves", () => {
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

test("the refusal goes as soon as the date it is about leaves the box", () => {
  const box = startDateBox();
  box.type("16/03/2026");
  expect(box.message).not.toBeNull();

  box.type("16/03/202");
  expect(box.message).toBeNull();
  expect(box.invalid).toBe(false);
});

test("blur stays reachable after a refusal has been typed over", () => {
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
  const box = startDateBox();
  box.type("16/03/2026");
  box.type("16/03/2");
  box.blur();

  box.moveMax("2026-03-31");
  expect(box.iso).toBe("2026-03-12");
  expect(box.text).toBe("12/03/2026");
});

test("refusalStands is about the date in the box, and nothing else", () => {
  const held: DateRefusal = { date: new Date(2026, 2, 16), bound: "max" };
  expect(refusalStands(held, "16/03/2026")).toBe(true);
  expect(refusalStands(held, "16/03/202")).toBe(false);
  expect(refusalStands(held, "")).toBe(false);
  expect(refusalStands(held, "17/03/2026")).toBe(false);
  expect(refusalStands(held, "2026-03-16")).toBe(true);
  expect(refusalStands(null, "16/03/2026")).toBe(false);
});

test("an out-of-range date and half-typed text are different answers", () => {
  expect(parseTypedDate("16/03/202")).toBeUndefined();
  expect(parseTypedDate("31/02/2026")).toBeUndefined();
  const typed = parseTypedDate("16/03/2026");
  expect(typed).toBeTruthy();
  expect(dateOutsideBounds(typed as Date, undefined, "2026-03-14")).toBe("max");
});
