/**
 * "Add it to the list" from a craft picker (REQUIREMENTS_2026-10-20 §2.4): the form's draft is saved
 * first, `/crafts?new={q}` opens in the same tab, and saving the craft there comes back to the form
 * with what was typed restored and the new craft picked.
 *
 * The draft is a snapshot of the form's own named controls, kept in sessionStorage (this tab only)
 * for one hour. Mirror twins (`tabindex="-1"`, §12.2), hidden inputs and file inputs are skipped:
 * a mirror follows its themed control, and a file cannot be written back into an input.
 */

export const CRAFT_ADD_DRAFT_KEY = "craft_add_draft";
export const CRAFT_ADD_DRAFT_TTL_MS = 60 * 60 * 1000;
export const CRAFT_ADD_RESTORED = "Your entries are back.";

export type DraftControl = { name: string; type: string; value: string; checked?: boolean };

export type CraftAddDraft = {
  /** Path and query of the form's page; the draft only restores there. */
  returnTo: string;
  savedAt: number;
  controls: DraftControl[];
  /** Set by `/crafts` once the craft is saved, so the form can pick it. */
  addedCraftId?: string;
};

const SKIPPED_TYPES = new Set(["hidden", "file", "submit", "button", "reset", "image", "password"]);

type Control = HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement;

function restorable(element: Element): element is Control {
  if (!(element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement)) {
    return false;
  }
  if (!element.name || element.disabled) return false;
  if (element.tabIndex === -1 || element.getAttribute("aria-hidden") === "true") return false;
  return !(element instanceof HTMLInputElement && SKIPPED_TYPES.has(element.type));
}

/** Only a same-origin path is a place to come back to. */
export function safeReturnPath(value: string | null | undefined): string | null {
  if (!value || !value.startsWith("/") || value.startsWith("//") || value.startsWith("/\\")) return null;
  return value;
}

/** `/crafts?new=…` plus where to come back to. */
export function addCraftHref(href: string, returnTo: string): string {
  const joiner = href.includes("?") ? "&" : "?";
  return `${href}${joiner}return=${encodeURIComponent(returnTo)}`;
}

export function snapshotForm(form: HTMLFormElement): DraftControl[] {
  const controls: DraftControl[] = [];
  for (const element of Array.from(form.elements)) {
    if (!restorable(element)) continue;
    const checkable = element instanceof HTMLInputElement && (element.type === "checkbox" || element.type === "radio");
    controls.push({
      name: element.name,
      type: element instanceof HTMLInputElement ? element.type : element.tagName.toLowerCase(),
      value: element.value,
      ...(checkable ? { checked: (element as HTMLInputElement).checked } : {})
    });
  }
  return controls;
}

function setNativeValue(element: Control, value: string) {
  const proto = Object.getPrototypeOf(element) as object;
  const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
  if (setter) setter.call(element, value);
  else element.value = value;
  // Bubbling events reach React's onChange on controlled boxes and the form's own onInput.
  element.dispatchEvent(new Event("input", { bubbles: true }));
  element.dispatchEvent(new Event("change", { bubbles: true }));
}

/** Write a snapshot back. Controls are matched by name and position among that name. Answers how many changed. */
export function applySnapshot(form: HTMLFormElement, controls: readonly DraftControl[]): number {
  const seen = new Map<string, number>();
  const byName = new Map<string, Control[]>();
  for (const element of Array.from(form.elements)) {
    if (!restorable(element)) continue;
    const list = byName.get(element.name) ?? [];
    list.push(element);
    byName.set(element.name, list);
  }
  let changed = 0;
  for (const saved of controls) {
    const at = seen.get(saved.name) ?? 0;
    seen.set(saved.name, at + 1);
    const element = byName.get(saved.name)?.[at];
    if (!element) continue;
    if (element instanceof HTMLInputElement && (element.type === "checkbox" || element.type === "radio")) {
      if (saved.checked !== undefined && element.checked !== saved.checked) {
        element.click();
        changed += 1;
      }
      continue;
    }
    if (element.value !== saved.value) {
      setNativeValue(element, saved.value);
      changed += 1;
    }
  }
  return changed;
}

function storage(): Storage | null {
  try {
    return typeof window === "undefined" ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

export function writeCraftAddDraft(draft: CraftAddDraft): boolean {
  try {
    storage()?.setItem(CRAFT_ADD_DRAFT_KEY, JSON.stringify(draft));
    return true;
  } catch {
    return false;
  }
}

export function readCraftAddDraft(now = Date.now()): CraftAddDraft | null {
  try {
    const raw = storage()?.getItem(CRAFT_ADD_DRAFT_KEY);
    if (!raw) return null;
    const draft = JSON.parse(raw) as CraftAddDraft;
    if (!draft || typeof draft.returnTo !== "string" || !Array.isArray(draft.controls)) return null;
    if (now - draft.savedAt > CRAFT_ADD_DRAFT_TTL_MS) {
      clearCraftAddDraft();
      return null;
    }
    return draft;
  } catch {
    return null;
  }
}

export function clearCraftAddDraft() {
  try {
    storage()?.removeItem(CRAFT_ADD_DRAFT_KEY);
  } catch {
    // Storage blocked: nothing was kept, so nothing to clear.
  }
}

/** `/crafts` calls this after the craft is saved: the form picks it on return. */
export function markCraftAdded(craftId: string): string | null {
  const draft = readCraftAddDraft();
  if (!draft) return null;
  writeCraftAddDraft({ ...draft, addedCraftId: craftId });
  return draft.returnTo;
}

/** The draft for this page, taken once (it is cleared as it is read). */
export function takeCraftAddDraft(here: string): CraftAddDraft | null {
  const draft = readCraftAddDraft();
  if (!draft || draft.returnTo !== here) return null;
  clearCraftAddDraft();
  return draft;
}
