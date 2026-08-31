/**
 * THE MANDATORY-FIELD ASTERISK, IN RED, IN ONE PLACE.
 *
 * ── WHY A COMPONENT FOR ONE CHARACTER ───────────────────────────────────────────────────────────
 *
 * Because it was written out by hand ten times. `{required ? " *" : ""}` appears verbatim in
 * `FormControls.tsx`, `DesignerProfileView.tsx`, `DictatedTextInput.tsx`, `DictatedTextArea.tsx`,
 * `ReviewEditPanel.tsx`, `TaskPrimitives.tsx`, `LocationFields.tsx`, `DosDontsField.tsx`,
 * `ArtisanForm.tsx` and `AadhaarField.tsx` — ten labels, ten string literals, and no way to change
 * how a required field is marked without finding all ten and hoping there is not an eleventh. That
 * is the same shape as every register in this repo that was written down twice and went stale (see
 * §16 of the frontend skill). The mark is now a component: one owner, and the next form gets it
 * right without being told.
 *
 * ── WHY RED, AND WHY THE RED IS NOT `error-600` ALONE ───────────────────────────────────────────
 *
 * The owner's instruction on 2026-08-30 was that the asterisk be red "so as to have a better
 * ergonomic design" — a plain-ink `*` next to plain-ink label text is a character a reader has to
 * hunt for, and hunting for it on a nineteen-field stage form is exactly the moment a required box
 * gets missed and the save is refused by a browser bubble.
 *
 * `text-error-600` is `#dc2626`, and it is one of this palette's LITERAL status colours — it does
 * not invert (`tailwind.config.ts`; §3.5 of the frontend skill). On the light canvas that is
 * correct. On `--card` in dark (`#1a1725`) it lands at roughly 4:1, which is thin for a mark whose
 * whole job is to be caught out of the corner of an eye. There is no red rung in this palette that
 * inverts, so this is the case `dark:` exists for — the exception mechanism, used because a token
 * genuinely cannot answer, and not because a token was not looked for. `red-400` is stock Tailwind
 * `#f87171`; it is not brand, and it is deliberately not introduced as a project token, because the
 * only thing in the product that needs it is this one glyph.
 *
 * ── IT IS NOT `aria-hidden`, AND THAT IS A DELIBERATE NON-CHANGE ────────────────────────────────
 *
 * The obvious tidy-up is to hide the asterisk from assistive technology, since every control it
 * marks also carries `required` / `aria-required` and a screen reader announces "required" from
 * that. It is not done here. Ten labels changing their announced accessible name in one commit is a
 * change to how ten forms read aloud, made as a side effect of a colour instruction, and this
 * component is not the place to decide it. The mark is announced exactly as it has always been
 * announced; what changed is what it looks like. If the announcement is to change, change it on
 * purpose, with the `aria-required` audit that belongs beside it.
 *
 * ── THE LEADING SPACE IS PART OF THE MARK ───────────────────────────────────────────────────────
 *
 * Every call site wrote `" *"`, not `"*"`, and JSX drops the leading whitespace of a text node that
 * follows an element — the same trap `StageDocumentPreview`'s `{" "}` exists for. So the space lives
 * INSIDE this span rather than being left to the caller to remember, and `Label *` cannot become
 * `Label*` by somebody tidying a template literal.
 */
export function RequiredMark({ when = true }: { when?: boolean }) {
  if (!when) return null;
  return <span className="text-error-600 dark:text-red-400"> *</span>;
}
