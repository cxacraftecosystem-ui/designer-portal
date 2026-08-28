/**
 * THE FOUR RULES THE DESIGNER PROFILE GAINED ON 2026-08-27, PINNED WHERE THEY CAN BE CHECKED
 * WITHOUT A SERVER.
 *
 * The owner's report was that the page was "very poorly executed": a collapsed media column, a
 * lower-cased "cv", no mandatory fields, no dictation, and a "View Data" tile offered to designers.
 * Four of those five are RULES rather than pixels — which tiers see a tile, which boxes must be
 * answered, which email rule is authoritative, and how wide a grid item may span — and every one of
 * them is the kind that goes wrong silently in a later edit.
 *
 * ── WHY THIS IS A SOURCE-READING SPEC ───────────────────────────────────────────────────────────
 *
 * There is no React renderer in this repository's devDependencies — `discarded-work-unit.spec.ts`,
 * `web-surface-gaps-unit.spec.ts` and `dashboard-tile-parity-unit.spec.ts` all say so and all read
 * source for the same reason — so a form's JSX can only be exercised by somebody looking at a
 * screen. Reading the source is also the stronger check for three of these four: what must not
 * happen is somebody EDITING a `required`, a `col-span` or a `visible:` expression, and a string
 * assertion catches that where a behavioural test through one fixture account would not.
 *
 * `-unit` MEANS NO BROWSER AND NO API (`e2e/README.md`: `test:unit` is the CI gate and runs with the
 * stack down). That constraint decides the shape of the e-mail assertions in particular: the rule
 * this page enforces IS the platform's `type="email"`, so there is no pure function to call and the
 * honest thing to pin is that the client delegates — to the browser and to the server's `EmailStr` —
 * and has not grown a second opinion of its own.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DESIGNER_PROFILE_LABELS,
  DESIGNER_PROFILE_REQUIRED_FIELDS,
  isDesignerProfileFieldRequired
} from "@/components/designers/profileCopy";
import { canSeeDataTile, routeGuardFor } from "@/lib/permissions";
import type { DesignerProfileField } from "@/lib/designers";
import type { User, UserRole } from "@/lib/types";

const FRONTEND = join(__dirname, "..");
const REPO = join(FRONTEND, "..");

const FORM = "components/designers/DesignerProfileForm.tsx";
const VIEW = "components/designers/DesignerProfileView.tsx";
const COPY = "components/designers/profileCopy.ts";
const DASHBOARD = "app/(protected)/dashboard/page.tsx";
const SCHEMA = join(REPO, "backend", "app", "schemas", "designers.py");

const read = (relative: string) => readFileSync(join(FRONTEND, relative), "utf8").split("\r\n").join("\n");
const readAbsolute = (path: string) => readFileSync(path, "utf8").split("\r\n").join("\n");

/** The role is the whole fixture: every predicate here reads nothing else off the user. */
const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

const ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "PROFESSOR",
  "INSPECTOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

/**
 * Strip comments from TypeScript source, leaving string contents alone.
 *
 * Every assertion below that looks for a phrase "in the file" must not be satisfiable by a SENTENCE
 * about it, and these files are heavily commented — this one names `col-span-4`, `toLowerCase` and
 * `setCustomValidity` in prose precisely because they are the mistakes being guarded against. The
 * quote handling is what makes the stripping safe: a `//` inside a URL in a string is not a comment.
 */
function stripComments(source: string): string {
  let out = "";
  let i = 0;
  let stringChar: string | null = null;

  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];

    if (stringChar) {
      out += c;
      if (c === "\\") {
        out += next ?? "";
        i += 2;
        continue;
      }
      if (c === stringChar) stringChar = null;
      i += 1;
      continue;
    }
    if (c === '"' || c === "'" || c === "`") {
      stringChar = c;
      out += c;
      i += 1;
      continue;
    }
    if (c === "/" && next === "/") {
      while (i < source.length && source[i] !== "\n") i += 1;
      continue;
    }
    if (c === "/" && next === "*") {
      i += 2;
      while (i < source.length && !(source[i] === "*" && source[i + 1] === "/")) i += 1;
      i += 2;
      continue;
    }
    out += c;
    i += 1;
  }
  return out;
}

const FORM_CODE = stripComments(read(FORM));
const VIEW_CODE = stripComments(read(VIEW));
const DASHBOARD_CODE = stripComments(read(DASHBOARD));

test("the comment stripper really removed the prose these assertions would otherwise match", () => {
  // Without this the whole file is vacuously green: `FORM_CODE` still holding its own comments would
  // make "the source does not contain `col-span-4`" fail for an honest reason and, worse, would make
  // "the source DOES contain X" pass off a sentence describing X.
  expect(read(FORM)).toContain("md:col-span-4");
  expect(FORM_CODE).not.toContain("md:col-span-4");
  expect(FORM_CODE.length).toBeGreaterThan(1000);
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * 1. The media column: a grid item may not span past the grid it is in
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the photograph, signature and CV cards get the row they ask for", () => {
  test("no item spans more columns than its grid declares", () => {
    /*
      THE DEFECT, IN ONE ASSERTION.

      The CV wrapper was `md:col-span-4` inside the group grid `grid gap-3 md:grid-cols-2`. CSS Grid
      does NOT clamp an over-long span: auto-placement adds IMPLICIT columns to accommodate it, and
      an implicit column is sized by `grid-auto-columns`, i.e. `auto`. So one class turned a
      two-track grid into `minmax(0,1fr) minmax(0,1fr) auto auto`, the signature card was
      auto-placed into the implicit pair instead of onto its own row, and the `fr` tracks — which
      only ever receive the space LEFT OVER after intrinsic tracks are sized — shrank towards zero.
      Measured in Chromium at 1280px against this markup: tracks `482px 482px 107px 107px`, a 976px
      photograph card beside a 226px signature card; and at the point the `fr` pair reaches 0 the
      item spanning it measures exactly one `gap-3` — 12px around 127px of content, which is what
      was reported from the live page.

      `min-w-0` DOES NOT FIX THIS and reaching for it is the trap the house rules set up: it stops an
      item refusing to shrink below its content, and cannot stop a track from being created. The
      wrappers carry it as an ordinary belt; the span is the cause.

      The rule is asserted generically rather than as "the file does not say col-span-4", so it also
      catches `md:col-span-3`, and catches the day somebody narrows the group grid to one column.
    */
    const declared = FORM_CODE.match(/md:grid-cols-(\d+)/g) ?? [];
    expect(declared, "the group grid no longer declares its columns; this assertion is aimed at it").toContain(
      "md:grid-cols-2"
    );
    const widest = Math.max(...declared.map((token) => Number(token.replace("md:grid-cols-", ""))));

    for (const token of FORM_CODE.match(/md:col-span-(\d+)/g) ?? []) {
      const span = Number(token.replace("md:col-span-", ""));
      expect(
        span,
        `\`${token}\` spans past a \`md:grid-cols-${widest}\` grid. CSS Grid answers that by ADDING ` +
          "implicit auto columns, not by clamping — which mis-places the neighbouring cards and " +
          "starves the 1fr tracks. Use the grid's own column count."
      ).toBeLessThanOrEqual(widest);
    }
  });

  test("every media wrapper carries the belt as well as the fix", () => {
    // Three wrappers, three `min-w-0`s, and they are not the fix — see above. They are here because a
    // `MediaCaptureField` and a rendered PDF are wide content in a grid item, and `min-width: auto`
    // is what would let one of them widen the column rather than scroll inside it.
    const wrappers = FORM_CODE.match(/className="min-w-0 md:col-span-2"/g) ?? [];
    expect(wrappers.length, "the photograph, signature and CV wrappers").toBe(3);
  });

  test("the read-only view already spanned correctly and still does", () => {
    // `DesignerProfileView` draws the same three fields on a `md:grid-cols-2` and has always used
    // `md:col-span-2` for the wide ones. Pinned so a future edit cannot copy the broken shape back
    // across from the editor, which is exactly how the two screens would come to disagree.
    for (const token of VIEW_CODE.match(/md:col-span-(\d+)/g) ?? []) {
      expect(Number(token.replace("md:col-span-", ""))).toBeLessThanOrEqual(2);
    }
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * 2. "CV" is an acronym everywhere it is printed
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the CV slot spells CV", () => {
  test("the label is the acronym and the upload card composes from it verbatim", () => {
    expect(DESIGNER_PROFILE_LABELS.cvMediaId).toBe("CV");
    // `Attach ${label}` / `Replace ${label}` — NOT `${label.toLowerCase()}`, which rendered
    // `<h3>Attach cv</h3>` on the one screen a designer types on while five other labels on the same
    // page said "CV". `MediaSlot`'s twin still lower-cases and is right to: "photograph" and
    // "signature" are ordinary nouns.
    expect(FORM_CODE).toContain("`Replace ${label}`");
    expect(FORM_CODE).toContain("`Attach ${label}`");
  });

  test("no sentence on this form lower-cases a phrase that contains a name", () => {
    // The trouble sentence had the same defect one layer down: `The ${caption.toLowerCase()} did not
    // upload` printed "the designer cv did not upload" for the CV column. `uploadOne` now takes the
    // mid-sentence phrase as its own argument, because a `.toLowerCase()` cannot know which words
    // are names.
    expect(FORM_CODE).not.toContain("caption.toLowerCase()");
    expect(FORM_CODE).toContain('"designer CV"');
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * 3. The four mandatory boxes, and the e-mail rule
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test.describe("the mandatory fields", () => {
  test("the set is exactly the four the owner named", () => {
    /*
      WRITTEN OUT RATHER THAN DERIVED. Comparing `DESIGNER_PROFILE_REQUIRED_FIELDS` to itself would
      be a tautology and a widening — a required `website`, say — would sail through. This literal is
      the independent statement of the instruction: "Name, qualification, email, and phone number
      should be mandatory fields as well."
    */
    expect([...DESIGNER_PROFILE_REQUIRED_FIELDS]).toEqual(["displayName", "qualification", "phone", "email"]);

    const optional: DesignerProfileField[] = [
      "localName",
      "designation",
      "institution",
      "department",
      "specialisation",
      "experienceYears",
      "biography",
      "website",
      "addressLine",
      "city",
      "state",
      "pincode",
      "photoMediaId",
      "signatureMediaId",
      "cvMediaId",
      "empanelmentNo",
      "empanelmentDate"
    ];
    for (const field of optional) {
      expect(
        isDesignerProfileFieldRequired(field),
        `${field} must stay optional — a box may only be mandatory where it is answerable, and an ` +
          "empanelment number the designer has not been issued would stop them saving a biography"
      ).toBe(false);
    }
    // 4 + 17 = the twenty-one writable columns, so the two lists together are the whole record and
    // a new column cannot be added without a decision being made about it here.
    expect(DESIGNER_PROFILE_REQUIRED_FIELDS.length + optional.length).toBe(
      Object.keys(DESIGNER_PROFILE_LABELS).length
    );
  });

  test("each one is marked on the form AND enforced by the browser", () => {
    /*
      BOTH HALVES, because either alone is a lie. An asterisk with no `required` is a form that
      promises a refusal it will not deliver; a `required` with no asterisk refuses a designer at
      the submit button over a box that never said it was needed.

      Both are driven off `isDesignerProfileFieldRequired(...)` rather than a literal `true`, so the
      mark a reader sees and the rule the browser enforces cannot drift apart — that is what these
      assertions are really pinning.
    */
    for (const field of DESIGNER_PROFILE_REQUIRED_FIELDS) {
      const calls = FORM_CODE.split(`isDesignerProfileFieldRequired("${field}")`).length - 1;
      expect(
        calls,
        `${field} should read the required flag twice on the form — once for the label's asterisk ` +
          "and once for the control's own `required` attribute"
      ).toBe(2);
    }
    // And the view marks them from the same function, so an admin reading a colleague's profile can
    // see which blank is the one that will stop the next save.
    expect(VIEW_CODE).toContain("isDesignerProfileFieldRequired(field)");
  });

  test("the phone constraint is on the visible box, never on the zero-size mirror", () => {
    /*
      §12.2's rule is that a themed control's mirror must be `type="text"` and not `type="hidden"`,
      because a hidden input is exempt from constraint validation. That says which element CAN carry
      a constraint; it is not an instruction to put every constraint there. `PhoneField`'s mirror is
      `absolute h-0 w-0 opacity-0` — measured in Chromium, `required` on it does block the submit and
      does report "Please fill out this field", pointed at a box of zero size. The visible number
      input is an ordinary `<input>` in the same form, so the identical rule refuses the submit AND
      focuses the box that is empty.
    */
    const phone = stripComments(read("components/forms/PhoneField.tsx"));
    const mirror = phone.slice(phone.indexOf("{mirror ? ("));
    expect(mirror, "the mirror branch was not found; this assertion is aimed at it").toContain("aria-hidden");
    expect(
      mirror,
      "`required` must not be moved onto the mirror — the refusal would point at a zero-size element"
    ).not.toContain("required");
    // The mirror keeps `pattern`, which is a different question: the composed value's SHAPE, which
    // cannot be asked of the digits box alone, and which can only fail once digits exist.
    expect(mirror).toContain("pattern=");
    expect(phone).toContain("required={required}");
    // Default false, so the design-workshop stage forms — where completeness is judged by
    // `stage_completeness` at report time and never by the browser — are untouched.
    expect(phone).toContain("required = false");
  });

  test("the client's e-mail rule IS the platform's, and there is no second one", () => {
    /*
      THE CLIENT AGREES WITH THE SERVER RATHER THAN INVENTING A RULE. The column is `EmailStr`, so a
      malformed address 422s the whole twenty-one-field body. `type="email"` is the WHATWG rule: an
      `@`-bearing address the server would accept is not refused here, and one with no `@` never
      leaves the browser.

      A hand-written regex would be a second opinion that can disagree with `EmailStr`, and the
      direction it disagrees in — refusing an address the server would have stored — is the one a
      designer cannot work around. So the assertion is an ABSENCE, and the absence is the rule.
    */
    expect(FORM_CODE).toContain('name="email"');
    expect(FORM_CODE).toContain('type="email"');

    for (const file of [FORM, VIEW, COPY]) {
      const code = stripComments(read(file));
      expect(code, `${file} must not carry a second e-mail rule`).not.toMatch(/@[^"'`\s]*\\\.|\\S\+@|\[\^@\]/);
      // §12.8: never `required` AND a custom validity message on one control — with both set, which
      // sentence the browser shows is up to the browser, so the field reports the wrong fault some
      // of the time. The box is required, so `required` is the attribute that stands.
      expect(code, `${file} must not set a custom validity beside a native required`).not.toContain(
        "setCustomValidity"
      );
    }
  });

  test("the server refuses the same four columns, in the same words", () => {
    /*
      A CLIENT-ONLY RULE IS A RULE THE API DOES NOT HAVE, and this API is written to by a handset as
      well as a browser. `DesignerProfileUpdate` now carries field validators over exactly these four
      columns — field validators, so pydantic runs them only for a key that was SUPPLIED and the
      `exclude_unset` contract ("absent leaves the stored value alone") is untouched in both
      directions.

      THE LABELS ARE ASSERTED TOO, not just the column names. The refusal is read by a designer on a
      screen and has to name the box they are looking at, which means the server holds a copy of four
      strings this file owns — so the copy is diffed here rather than trusted.
    */
    const schema = readAbsolute(SCHEMA);
    const table = schema.slice(schema.indexOf("REQUIRED_PROFILE_COLUMNS"), schema.indexOf("class DesignerRosterCreate"));
    for (const field of DESIGNER_PROFILE_REQUIRED_FIELDS) {
      expect(table, `the server does not name ${field} as mandatory`).toContain(
        `"${field}": "${DESIGNER_PROFILE_LABELS[field]}"`
      );
    }
    // The validator is bound to those four and no others — a fifth name here without one in
    // `DESIGNER_PROFILE_REQUIRED_FIELDS` is a column the API refuses and no form marks.
    expect(schema).toContain('@field_validator("displayName", "qualification", "phone", "email")');
    expect((table.match(/^\s{4}"[A-Za-z]+": "/gm) ?? []).length).toBe(DESIGNER_PROFILE_REQUIRED_FIELDS.length);
    // `email` is still the server's own `EmailStr` and this rule adds nothing to it — the `@` rule
    // lives in exactly one place on each side of the wire.
    expect(schema).toContain("email: EmailStr | None = None");
  });
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * 4. Dictation
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

test("the free-text boxes have the microphone the rest of the app has", () => {
  /*
    The reference is `/artisans/new`, which offers "Dictate Address in English (India)" and "Dictate
    Notes in English (India)" through `OnDeviceDictationButton` — on-device recognition, no
    `MediaRecorder`, no network, nothing to obtain consent for. This page had none at all.

    TWO BOXES, NOT TWENTY-THREE, and the split is the artisan form's: dictation goes where the answer
    is PROSE. A recogniser writes "at" for `@`, spells digits out and punctuates a URL, so a
    microphone under the e-mail, phone, pincode and date boxes would reliably produce a value the
    field then refuses — and a form whose every row carries a button is a form where the button stops
    being noticed.
  */
  expect(FORM_CODE).toContain("OnDeviceDictationButton");
  const dictated = FORM_CODE.match(/<DictatedField\b/g) ?? [];
  expect(dictated.length, "the biography and the address").toBe(2);
  expect(FORM_CODE).toContain('name="biography"');
  expect(FORM_CODE).toContain('name="addressLine"');
});

/* ────────────────────────────────────────────────────────────────────────────────────────────────
 * 5. The View Data tile
 * ──────────────────────────────────────────────────────────────────────────────────────────────── */

/**
 * WHO SEES THE TILE, WRITTEN OUT ROLE BY ROLE RATHER THAN DERIVED FROM THE PREDICATE.
 *
 * Deriving it would assert `canSeeDataTile` equal to itself and a widening would sail through.
 * DESIGNER and INSPECTOR are the rows that carry this table: both outrank RESEARCHER, both are
 * false, and every rank-threshold instinct gets them wrong — which is why the predicate is a floor
 * at PROFESSOR with RESEARCHER carved out beside it, rather than a bare threshold.
 *
 * THIS TABLE IS THE GUARD AND IT DOES NOT CARE HOW THE PREDICATE IS SPELLED. It was written when
 * the rule was a four-item array and it reads the same now the rule is a floor plus a carve-out,
 * which is the point: the OWNER's requirement is these eight answers, and any mechanism that
 * produces them is acceptable while any that does not is a bug, whatever it looks like.
 */
const TILE_OFFERED: Record<UserRole, boolean> = {
  MASTER_ADMIN: true,
  ADMIN: true,
  PROFESSOR: true,
  RESEARCHER: true,
  // FALSE. The owner's instruction names four tiers and neither of these is among them: a designer
  // and an inspector work on design workshops, and the dashboard was offering them a reading surface
  // the "View Data" MENU ROW had already decided they were not the audience for.
  INSPECTOR: false,
  DESIGNER: false,
  // FALSE, and stated rather than left to the rank comparison: both sit below RESEARCHER, and
  // neither loses a way in — "Browse records" is an ungated menu row to the same /search the tile
  // was sending them to.
  FIELD_CONTRIBUTOR: false,
  CROWDSOURCE_VOLUNTEER: false
};

test.describe("the View Data tile", () => {
  test("every tier gets the answer the owner asked for", () => {
    expect(ROLES.map((role) => ({ role, tile: canSeeDataTile(user(role)) }))).toEqual(
      ROLES.map((role) => ({ role, tile: TILE_OFFERED[role] }))
    );

    // A signed-out reader is nobody's role. The dashboard is behind the shell, so this is belt —
    // but a predicate that answered `true` for `null` would light every tile on a logged-out render.
    expect(canSeeDataTile(null)).toBe(false);
    expect(canSeeDataTile(undefined)).toBe(false);
  });

  test("a floor ALONE would get two tiers wrong, which is why the carve-out exists", () => {
    /*
      THE INDEPENDENT DEMONSTRATION, so "a floor alone cannot say this" is checked rather than
      asserted. RESEARCHER-and-above is the tightest floor that admits all four named tiers, and it
      also admits DESIGNER(35) and INSPECTOR(37), which sit INSIDE the range. That is precisely why
      the predicate floors at PROFESSOR and names RESEARCHER separately instead of dropping the
      floor to 30. Any future edit that "simplifies" it to `hasRank(user, "RESEARCHER")` turns this
      red and names both tiers it would have let in.
    */
    const wrongByAFloor = ROLES.filter(
      (role) => !canSeeDataTile(user(role)) && ["RESEARCHER", "PROFESSOR", "ADMIN", "MASTER_ADMIN", "DESIGNER", "INSPECTOR"].includes(role)
    );
    expect(wrongByAFloor.sort()).toEqual(["DESIGNER", "INSPECTOR"]);
  });

  test("the dashboard tile reads that predicate, and the filter that consumes it is default-allow", () => {
    const start = DASHBOARD_CODE.indexOf('label: "View Data"');
    expect(start, "the View Data tile is gone from the dashboard").toBeGreaterThan(-1);
    let open = start;
    while (open >= 0 && DASHBOARD_CODE[open] !== "{") open -= 1;
    let depth = 0;
    let end = open;
    for (; end < DASHBOARD_CODE.length; end += 1) {
      if (DASHBOARD_CODE[end] === "{") depth += 1;
      if (DASHBOARD_CODE[end] === "}") {
        depth -= 1;
        if (depth === 0) break;
      }
    }
    const tile = DASHBOARD_CODE.slice(open, end + 1);

    expect(tile, "the tile carries no `visible` predicate at all").toContain("visible: canSeeDataTile(user)");
    // The DESTINATION still forks on the grant, and that is a different question from whether the
    // tile is drawn: a reader without dataset access is sent to Browse records rather than at a
    // padlock.
    expect(tile).toContain('canDownloadDataset(user) ? "/data" : "/search"');
    expect(tile).toContain('newLabel: "Open"');
    // Default-ALLOW is why the assertion above is about presence and not only spelling: a tile whose
    // `visible` key was deleted is shown to every signed-in account.
    expect(DASHBOARD_CODE).toContain(".filter((tile) => tile.visible !== false)");
  });

  test("nothing about this is a route guard", () => {
    /*
      SAID OUT LOUD, because the tempting next edit is to "finish the job" with a `ROUTE_GUARDS` row
      and that would be a client-side rule the API does not have.

      `/data` keeps its own guard on `canDownloadDataset` — Professor and above, or the explicit
      per-user grant — so a designer holding that grant still opens it, and still sees the "View
      Data" menu row, and simply gets no tile. `/search` stays unguarded because the endpoints behind
      it take nothing but a signed-in user and scope their rows per viewer on the server.
    */
    expect(routeGuardFor("/data")?.gate).toBe("require_dataset_downloader");
    expect(routeGuardFor("/search"), "/search must stay open — its endpoints are").toBeNull();
    // And the tile predicate is nowhere in the guard table, in either direction.
    expect(stripComments(read("lib/permissions.ts"))).not.toContain("can: canSeeDataTile");
  });
});
