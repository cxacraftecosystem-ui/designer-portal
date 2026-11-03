import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import {
  DESIGNER_PROFILE_GROUPS,
  DESIGNER_PROFILE_LABELS,
  DESIGNER_PROFILE_REQUIRED_FIELDS,
  isDesignerProfileFieldRequired
} from "@/components/designers/profileCopy";
import { plainFromStoredRichText } from "@/components/richtext/storedRichText";
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

const user = (role: UserRole): User => ({ id: "u1", email: "a@b.c", name: "A", role } as User);

const ROLES: UserRole[] = [
  "MASTER_ADMIN",
  "ADMIN",
  "MINISTRY_ADMIN",
  "REGIONAL_DIRECTOR",
  "ASSISTANT_DIRECTOR",
  "PROFESSOR",
  "INSPECTOR",
  "DESIGNER",
  "RESEARCHER",
  "FIELD_CONTRIBUTOR",
  "CROWDSOURCE_VOLUNTEER"
];

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

test("the form is read whole, and no item in it spans four columns", () => {
  expect(FORM_CODE).not.toContain("md:col-span-4");
  expect(FORM_CODE.length).toBeGreaterThan(1000);
});

test.describe("the photograph, signature and CV cards get the row they ask for", () => {
  test("no item spans more columns than its grid declares", () => {
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
    const wrappers = FORM_CODE.match(/className="min-w-0 md:col-span-2">\s*<(?:MediaSlot|DocumentSlot)\b/g) ?? [];
    expect(wrappers.length, "the photograph, signature and CV wrappers").toBe(3);
  });

  test("the read-only view already spanned correctly and still does", () => {
    for (const token of VIEW_CODE.match(/md:col-span-(\d+)/g) ?? []) {
      expect(Number(token.replace("md:col-span-", ""))).toBeLessThanOrEqual(2);
    }
  });
});

test.describe("the CV slot spells CV", () => {
  test("the label is the acronym and the upload card composes from it verbatim", () => {
    expect(DESIGNER_PROFILE_LABELS.cvMediaId).toBe("CV");
    expect(FORM_CODE).toContain("`Replace ${label}`");
    expect(FORM_CODE).toContain("`Attach ${label}`");
  });

  test("no sentence on this form lower-cases a phrase that contains a name", () => {
    expect(FORM_CODE).not.toContain("caption.toLowerCase()");
    expect(FORM_CODE).toContain('"designer CV"');
  });
});

test.describe("the mandatory fields", () => {
  test("the set is exactly the four the owner named", () => {
    expect([...DESIGNER_PROFILE_REQUIRED_FIELDS]).toEqual(["displayName", "qualification", "phone", "email"]);

    const optional: DesignerProfileField[] = [
      "localName",
      "designation",
      "institution",
      "department",
      "specialisation",
      "experienceYears",
      "experienceMonths",
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
    expect(DESIGNER_PROFILE_REQUIRED_FIELDS.length + optional.length).toBe(
      Object.keys(DESIGNER_PROFILE_LABELS).length
    );
  });

  test("each one is marked on the form AND enforced by the browser", () => {
    for (const field of DESIGNER_PROFILE_REQUIRED_FIELDS) {
      const calls = FORM_CODE.split(`isDesignerProfileFieldRequired("${field}")`).length - 1;
      expect(
        calls,
        `${field} must take its required-ness from isDesignerProfileFieldRequired, never a literal`
      ).toBeGreaterThan(0);
      expect(
        FORM_CODE,
        `${field} must not be marked required by a literal anywhere on this form`
      ).not.toContain(`name="${field}"\n          required={true}`);
    }
    const dictatedInput = stripComments(read("components/richtext/DictatedTextInput.tsx"));
    expect(dictatedInput, "the asterisk").toContain("<RequiredMark when={required} />");
    expect(dictatedInput, "the browser's own rule").toContain("required={required}");
    const mark = stripComments(read("components/ui/RequiredMark.tsx"));
    expect(mark, "the required mark must be red").toContain("text-error-600");
    expect(mark, "and legible on the dark canvas, where error-600 falls under 4.5:1").toContain(
      "dark:text-red-400"
    );
    expect(VIEW_CODE).toContain("isDesignerProfileFieldRequired(field)");
  });

  test("the phone constraint is on the visible box, never on the zero-size mirror", () => {
    const phone = stripComments(read("components/forms/PhoneField.tsx"));
    const mirror = phone.slice(phone.indexOf("{mirror ? ("));
    expect(mirror, "the mirror branch was not found; this assertion is aimed at it").toContain("aria-hidden");
    expect(
      mirror,
      "`required` must not be moved onto the mirror — the refusal would point at a zero-size element"
    ).not.toContain("required");
    expect(mirror).toContain("pattern=");
    expect(phone).toContain("required={required}");
    expect(phone).toContain("required = false");
  });

  test("the client's e-mail rule IS the platform's, and there is no second one", () => {
    expect(FORM_CODE).toContain('name="email"');
    expect(FORM_CODE).toContain('type="email"');

    for (const file of [FORM, VIEW, COPY]) {
      const code = stripComments(read(file));
      expect(code, `${file} must not carry a second e-mail rule`).not.toMatch(/@[^"'`\s]*\\\.|\\S\+@|\[\^@\]/);
      expect(code, `${file} must not set a custom validity beside a native required`).not.toContain(
        "setCustomValidity"
      );
    }
  });

  test("the server refuses the same four columns, in the same words", () => {
    const schema = readAbsolute(SCHEMA);
    const table = schema.slice(schema.indexOf("REQUIRED_PROFILE_COLUMNS"), schema.indexOf("class DesignerRosterCreate"));
    for (const field of DESIGNER_PROFILE_REQUIRED_FIELDS) {
      expect(table, `the server does not name ${field} as mandatory`).toContain(
        `"${field}": "${DESIGNER_PROFILE_LABELS[field]}"`
      );
    }
    expect(schema).toContain('@field_validator("displayName", "qualification", "phone", "email")');
    expect((table.match(/^\s{4}"[A-Za-z]+": "/gm) ?? []).length).toBe(DESIGNER_PROFILE_REQUIRED_FIELDS.length);
    expect(schema).toContain("email: EmailStr | None = None");
  });
});

test("the free-text boxes have the microphone the rest of the app has", () => {
  expect(FORM_CODE).toContain("DictatedTextInput");
  expect(FORM_CODE).toContain("DictatedTextArea");
  expect(FORM_CODE).toContain('name="biography"');
  expect(FORM_CODE).toContain('name="addressLine"');
  const dictated = FORM_CODE.match(/<DictatedField\b/g) ?? [];
  expect(dictated.length, "every applicable free-text box, not just the original two").toBeGreaterThan(2);

  const dictatedBlocks = [
    ...FORM_CODE.matchAll(/<DictatedField\b[\s\S]*?\/>/g),
    ...FORM_CODE.matchAll(/<DictatedTextArea\b[\s\S]*?\/>/g)
  ]
    .map((match) => match[0])
    .join("\n");
  for (const excluded of ["email", "phone", "pincode", "dateOfBirth"]) {
    expect(
      dictatedBlocks,
      `${excluded} must NOT carry a microphone — a recogniser cannot produce a value it will accept`
    ).not.toContain(`name="${excluded}"`);
  }
  expect(FORM_CODE).toContain("DictationUnavailableNotice");
  expect(FORM_CODE).toContain("explainWhenUnavailable={false}");
});

const TILE_OFFERED: Record<UserRole, boolean> = {
  MASTER_ADMIN: true,
  ADMIN: true,
  MINISTRY_ADMIN: true,
  REGIONAL_DIRECTOR: true,
  ASSISTANT_DIRECTOR: true,
  PROFESSOR: true,
  RESEARCHER: true,
  INSPECTOR: false,
  DESIGNER: false,
  FIELD_CONTRIBUTOR: false,
  CROWDSOURCE_VOLUNTEER: false
};

test.describe("the View Data tile", () => {
  test("every tier gets the answer the owner asked for", () => {
    expect(ROLES.map((role) => ({ role, tile: canSeeDataTile(user(role)) }))).toEqual(
      ROLES.map((role) => ({ role, tile: TILE_OFFERED[role] }))
    );

    expect(canSeeDataTile(null)).toBe(false);
    expect(canSeeDataTile(undefined)).toBe(false);
  });

  test("a floor ALONE would get two tiers wrong, which is why the carve-out exists", () => {
    const wrongByAFloor = ROLES.filter(
      (role) =>
        !canSeeDataTile(user(role)) &&
        [
          "RESEARCHER",
          "PROFESSOR",
          "ASSISTANT_DIRECTOR",
          "REGIONAL_DIRECTOR",
          "MINISTRY_ADMIN",
          "ADMIN",
          "MASTER_ADMIN",
          "DESIGNER",
          "INSPECTOR"
        ].includes(role)
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
    expect(tile).toContain('canDownloadDataset(user) ? "/data" : "/search"');
    expect(tile).toContain('newLabel: "Open"');
    expect(DASHBOARD_CODE).toContain(".filter((tile) => tile.visible !== false)");
  });

  test("nothing about this is a route guard", () => {
    expect(routeGuardFor("/data")?.gate).toBe("require_dataset_downloader");
    expect(routeGuardFor("/search"), "/search must stay open — its endpoints are").toBeNull();
    expect(stripComments(read("lib/permissions.ts"))).not.toContain("can: canSeeDataTile");
  });
});

test.describe("the designer's address, once it can be formatted", () => {
  const FORMATTED =
    '{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"12 Nagar Road, "},{"text":"Bagru","marks":["BOLD"]}]}]}';

  test("an address written before this change renders exactly as it did", () => {
    expect(plainFromStoredRichText("12 Nagar Road, Bagru")).toBe("12 Nagar Road, Bagru");
    expect(plainFromStoredRichText("12 Nagar Road\n\n  Bagru 303007  ")).toBe(
      "12 Nagar Road\n\n  Bagru 303007  "
    );
    expect(plainFromStoredRichText("१२ नगर रोड, बगरू")).toBe("१२ नगर रोड, बगरू");
    expect(plainFromStoredRichText(null)).toBe("");
    expect(plainFromStoredRichText(undefined)).toBe("");
    expect(plainFromStoredRichText("{Flat 2} Nagar Road")).toBe("{Flat 2} Nagar Road");
  });

  test("a formatted address renders as its words and never as braces", () => {
    const shown = plainFromStoredRichText(FORMATTED);
    expect(shown).toBe("12 Nagar Road, Bagru");
    expect(
      shown,
      "the read-only profile is printing raw JSON — which reads to an admin as a designer who " +
        "pasted something strange into their address, not as a rendering bug"
    ).not.toContain("blocks");
    const twoBlocks =
      '{"blocks":[{"kind":"PARAGRAPH","spans":[{"text":"12 Nagar Road"}]},' +
      '{"kind":"PARAGRAPH","spans":[{"text":"Bagru 303007"}]}]}';
    expect(plainFromStoredRichText(twoBlocks)).toBe("12 Nagar Road\nBagru 303007");
  });

  test("the editor and the read-only view both go through the rich-text boundary", () => {
    expect(FORM_CODE).toContain("<RichTextField");
    expect(FORM_CODE, "the address is what became rich text — not the biography beside it").toMatch(
      /<RichTextField\s+name="addressLine"/
    );
    expect(FORM_CODE).not.toMatch(/<DictatedField\s+name="addressLine"/);
    expect(FORM_CODE).toMatch(/<RichTextField[\s\S]*?defaultValue=\{profile\.addressLine \?\? ""\}/);

    expect(VIEW_CODE).toContain("plainFromStoredRichText");
    expect(
      VIEW_CODE,
      "the view must flatten the address BEFORE its generic `String(raw)` branch, or the branch " +
        "order decides whether an admin sees an address or a JSON document"
    ).toMatch(/field === "addressLine"[\s\S]*?plainFromStoredRichText/);
  });

  test("the ceiling the box prints is the ceiling the server applies", () => {
    expect(FORM_CODE).toMatch(/<RichTextField[\s\S]*?maxLength=\{MAX\.addressLine\}/);
    expect(FORM_CODE).toContain("addressLine: 300");

    const schema = readAbsolute(SCHEMA);
    expect(schema).toContain("ADDRESS_LINE_PLAIN_MAX = 300");
    expect(
      schema,
      "the raw string must have a bound of its own — without one, a client bug can post a novel " +
        "into a profile column"
    ).toContain("max_length=ADDRESS_LINE_STORED_MAX");
  });

  test("both clients call the two address halves by the same two names", () => {
    expect(DESIGNER_PROFILE_LABELS.addressLine).toBe("Address line");
    expect(DESIGNER_PROFILE_GROUPS.find((group) => group.key === "address")?.title).toBe(
      "Postal address"
    );
    expect(DESIGNER_PROFILE_GROUPS.find((group) => group.key === "address")?.title).not.toBe(
      DESIGNER_PROFILE_LABELS.addressLine
    );
  });

  test("the village box carries one label and not two", () => {
    const location = stripComments(read("components/forms/LocationFields.tsx"));
    expect(location).toContain('label="Village or place"');
    expect(
      (location.match(/label="Village or place"/g) ?? []).length,
      "the village box is labelled twice again — keep the control's own `<label htmlFor>` and do " +
        "not wrap it, or the input goes back to being named only by its group"
    ).toBe(1);
    expect(location).not.toContain('<FieldBlock label="Village or place">');
  });
});
