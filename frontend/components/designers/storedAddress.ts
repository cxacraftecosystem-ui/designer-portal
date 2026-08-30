/**
 * Reading `DesignerProfile.addressLine` — the one column on this profile that may not be a string.
 *
 * ── WHAT CHANGED, AND WHAT DELIBERATELY DID NOT ─────────────────────────────────────────────────
 *
 * The address box became `RichTextField` on 2026-08-30. The COLUMN did not change: it is still
 * `String?` in `schema.prisma`, there is no migration, and `encodeStoredRichText`'s rule means it
 * holds exactly the prose it held yesterday for every address nobody has formatted. Only a bolded
 * word — a mark, a heading, a list, an alignment, a table — turns the value into the JSON encoding
 * of a document.
 *
 * So a READER of this column now meets one of three things, and all three genuinely occur:
 *
 *   1. `null` — nobody has filled it in.
 *   2. prose — every row written before the promotion, and every row since that nobody formatted.
 *   3. `{"blocks":[…]}` — a formatted address.
 *
 * A reader that has not learnt about (3) prints the braces. Not a crash and not a 500: the literal
 * characters, on screen, in the place the designer expects their street. This module is that lesson
 * for the two designer surfaces that render the column without editing it.
 *
 * ── THE IDENTITY GUARANTEE IS THE HALF THAT MATTERS, AND IT IS COPIED FROM THE SERVER ───────────
 *
 * `rich_text.plain_from_stored` in `backend/app/services/rich_text.py` is the repository's read
 * boundary, and the property its own docstring insists on is that a plain string comes back as the
 * SAME string — not as one that survived a round trip through `fromPlainText`/`toPlain`. That round
 * trip is not the identity: it strips each line, drops blank ones and collapses runs. Applying it to
 * an address written two years ago would silently reformat data this app's users are the custodians
 * of rather than the authors of, and — worse here — it would do so on a screen whose entire purpose
 * is showing somebody what their report cover will say.
 *
 * **This is why the function below cannot be `toPlain(fromStored(raw))`.** `fromStored` reads a
 * `string` as prose through `fromPlainText`, by design, so that composition compiles, looks right,
 * and quietly rewrites every unformatted address on the way past. The string branch returns its
 * argument untouched instead, exactly as the server does.
 *
 * ── WHY IT LIVES HERE AND NOT IN `components/richtext/` ─────────────────────────────────────────
 *
 * That is where it belongs the day a second feature needs it, beside `decodeStoredRichText` and as
 * the web twin of `plain_from_stored`. It is here because the designer profile is the first and
 * currently only web surface that renders a rich-text column WITHOUT an editor — the artisan,
 * product, tool and process forms all mount `RichTextField`, which decodes for itself. When the
 * shared module grows one, delete this and import that: two spellings of a read boundary is how the
 * two come to disagree about what a document is.
 */

import { decodeStoredRichText } from "@/components/richtext/storedRichText";
import { fromStored, toPlain } from "@/lib/richText";

/**
 * A stored address as the words in it — prose returned untouched, a document flattened.
 *
 * Returns `""` for null, undefined and an empty column so callers can use their own blank wording;
 * every caller here already draws "Not filled in" for an unanswered box and must go on drawing it.
 *
 * The flattening is `toPlain`, which is the same flattener the report builder and the search index
 * use, list markers included — so what this prints is what the .docx would print, rather than a
 * near-enough approximation of it.
 */
export function plainFromStoredAddress(raw: string | null | undefined): string {
  if (raw === null || raw === undefined) return "";
  const decoded = decodeStoredRichText(raw);
  // Prose, and the object identity is the point — see the header. A value that merely begins with a
  // brace but is not a block document has already fallen through to this branch inside
  // `decodeStoredRichText`, which is what it is: somebody's typing.
  if (decoded === null) return "";
  if (typeof decoded === "string") return decoded;
  return toPlain(fromStored(decoded));
}
