import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { readHeldWorkshopPosts, type DwOfficer, type DwOversightAssignment } from "@/app/(protected)/officers/oversight";
import { OWN_POST_HINT, officerOptionsForPost } from "@/app/(protected)/officers/pickers";
import {
  HELD_POST_PENDING,
  HELD_POST_PENDING_FILES,
  MEDIA_HELD_LABEL,
  MEDIA_HELD_REASON,
  attachHold,
  heldPostReason,
  mediaHeldReason,
  mediaListNotice,
  mediaUploadHold,
  mediaWorkshopIds,
  mediaWriteHold,
  recordFilingHold,
  reviewEditHold,
  reviewRecordWorkshopIds,
  writesHeld
} from "@/components/designworkshop/HeldPostNotice";
import { readableError } from "@/components/review/reviewErrors";
import {
  discardUnfiledRecord,
  fileUnfiledRecord,
  heldBackNotice,
  heldBackOf,
  heldBackSentence
} from "@/components/settings/unfiledRecords";
import { ApiError, apiFetch } from "@/lib/api";
import { absenceProvesIneligible } from "@/lib/designWorkshopViewers";
import { selfReleaseRefusal, selfReleaseRefused } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * A WORKSHOP SOMEBODY INSPECTS OR SUPERVISES IS READ-ONLY TO THEM — AND EVERY SCREEN THAT WRITES IT
 * SAYS SO BEFORE THEY TYPE.
 *
 * ── THE RULING ──────────────────────────────────────────────────────────────────────────────────
 *
 * Since 2026-10-09 a Ministry Admin, an admin and the master admin may be appointed a workshop's
 * inspector, Assistant Director or Regional Director, and the server refuses whoever holds such a
 * post — any tier, the admin routes included — every write to that workshop's CONTENT or DESIGNER
 * TEAM, with a 403 whose sentence is `design_workshop_posts.write_refusal`. Reads, appointing OTHER
 * people, restore and generating the report stay theirs. An admin reaches every workshop's write
 * controls through the admin arm, so without the web saying so first they would type a stage, a
 * definition or a photograph filing into a form whose every save is refused.
 *
 * ── WHAT IS PINNED, AND HOW ─────────────────────────────────────────────────────────────────────
 *
 *   1. the read that answers "which posts do I hold here", driven for real with `fetch` stubbed;
 *   2. every screen that writes a workshop asks it and holds its writing controls — while the answer
 *      is still out as well as after a refusal (the hook's three states), with the refusal said into
 *      a live region that exists before it arrives — ON THE SOURCE, because this repository has no
 *      React renderer (the same instrument `ai-verbs-unit.spec.ts` uses for its mounts). Each row
 *      names the screen, so a regression names what it reopened;
 *   3. the self-naming rule for the designer pickers, held to the server's own create doors;
 *   4. a record filed under such a workshop is its content too: the five record forms hold Save and —
 *      for the stored workshop — the picker, the questionnaire page draws an attached form read-only,
 *      and every record screen prints the server's 403 word for word;
 *   5. so is every FILE of it: the delete, the re-run transcript, the identity photograph's decision
 *      and the orphan screen's relink are held for a post holder on every web screen that offers
 *      them, with the reason, and a refusal that arrives anyway is printed as the server wrote it;
 *   6. so is every door that ADDS to it or edits it from elsewhere — a file uploaded on /media, a
 *      failed job re-queued, a questionnaire created, uploaded or reused into it, a record or file
 *      edited from the review queue, and the unfiled-records report's filing and discard — each held
 *      on the source and registered off the tree, so a new door fails here until it is held; and
 *      nobody takes THEMSELVES off a post: their own row and slot stay ticked and switched off.
 *
 * The sentence itself — `heldPostEditRefusal` against the server's `write_refusal` — is
 * `admin-serve-as-unit.spec.ts`'s, and is not repeated here.
 *
 * ⚠ LINE-ENDING AGNOSTIC: single-line `toContain`s and `\s` in patterns, never a literal newline.
 */

const FRONTEND = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(FRONTEND, ...parts), "utf8");
const readBackend = (...parts: string[]) => readFileSync(join(FRONTEND, "..", "backend", ...parts), "utf8");

const realFetch = globalThis.fetch;

const account = (role: UserRole, id = `u-${role.toLowerCase()}`): User =>
  ({ id, email: `${id}@example.org`, name: role, role }) as User;

/** A window just real enough for `apiFetch`: a token to send and a location it may inspect. */
function installFakeWindow() {
  const store = new Map<string, string>([["field_repo_token", "a-perfectly-good-token"]]);
  (globalThis as Record<string, unknown>).window = {
    localStorage: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => void store.set(key, value),
      removeItem: (key: string) => void store.delete(key)
    },
    location: { pathname: "/design-workshops/w1", protocol: "http:", assign: () => undefined, replace: () => undefined }
  };
}

/** Answer the two staffing reads by path, and count every request that is made at all. */
function serveStaffing(answers: { oversight: unknown; inspectors: unknown } | "fail") {
  const asked: string[] = [];
  (globalThis as Record<string, unknown>).fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    asked.push(url);
    if (answers === "fail") {
      return new Response(JSON.stringify({ detail: "Something broke" }), {
        status: 500,
        headers: { "content-type": "application/json" }
      });
    }
    const body = url.includes("/design-workshop-inspections/")
      ? { workshopId: "w1", inspectors: answers.inspectors }
      : { workshopId: "w1", title: "Loom", status: "IN_PROGRESS", designerName: null, designers: [], oversight: answers.oversight };
    return new Response(JSON.stringify(body), { status: 200, headers: { "content-type": "application/json" } });
  };
  return asked;
}

test.afterEach(() => {
  delete (globalThis as Record<string, unknown>).window;
  (globalThis as Record<string, unknown>).fetch = realFetch;
});

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Which posts the reader holds here
 * ──────────────────────────────────────────────────────────────────────────── */

test("an account that may not appoint is never asked, and costs no request", async () => {
  /*
    The two staffing reads are the appointers' routes, so anybody else would be refused them — and
    for that account the server's own 403 on a save is the whole warning. Asking anyway would spend
    two requests per screen to learn nothing.
  */
  installFakeWindow();
  const asked = serveStaffing({ oversight: [], inspectors: [] });
  expect(await readHeldWorkshopPosts("w1", account("DESIGNER"))).toEqual([]);
  expect(await readHeldWorkshopPosts("w1", null)).toEqual([]);
  expect(asked, "nothing was asked").toEqual([]);
});

test("an appointer's posts are read off the oversight rows and the inspection rows, in the server's order", async () => {
  installFakeWindow();
  const reader = account("ADMIN");
  const asked = serveStaffing({
    oversight: [
      { capacity: "REGIONAL_DIRECTOR", userId: reader.id },
      { capacity: "ASSISTANT_DIRECTOR", userId: "u-somebody-else" }
    ],
    inspectors: [{ userId: reader.id }]
  });
  expect(await readHeldWorkshopPosts("w1", reader)).toEqual(["REGIONAL_DIRECTOR", "INSPECTION"]);
  expect(asked.some((url) => url.includes("/design-workshop-oversight/w1")), "the oversight read").toBe(true);
  expect(asked.some((url) => url.includes("/design-workshop-inspections/w1/inspectors")), "the inspection read").toBe(true);
});

test("a failed read answers none known — never a claim that no post is held", async () => {
  // "None known" leaves the server's own 403 as the last word; an error here must not turn into a
  // refusal sentence either, because the screen would then switch off a workshop the reader may write.
  installFakeWindow();
  serveStaffing("fail");
  expect(await readHeldWorkshopPosts("w1", account("ADMIN"))).toEqual([]);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Every screen that writes a workshop asks, and holds its writes
 * ──────────────────────────────────────────────────────────────────────────── */

test("the hook resolves a device-minted address to the repository's id, and drops a stale answer", () => {
  const hook = read("components", "designworkshop", "HeldPostNotice.tsx");
  expect(hook).toContain('import { readHeldWorkshopPosts } from "@/app/(protected)/officers/oversight";');
  expect(hook).toContain("heldPostEditRefusal(answer.posts)");
  // A `dwlocal-…` address that has synced keeps its local id; the posts are the server record's.
  expect(hook).toContain("isLocalWorkshopId(workshopId)");
  expect(hook).toContain("(await loadDraft(workshopId).catch(() => null))?.remoteId ?? null");
  // An answer about the previous workshop — or for another account — is not drawn over the next one.
  expect(hook).toContain("answer && answer.workshopId === workshopId && answer.userId === user?.id");
});

test("three states: still asking holds the writes, none known releases them, and only an appointer is ever asked", () => {
  /*
    "Asking" and "none" were one `null` until 2026-10-09, so nothing could hold a form while the
    staffing reads were out: an admin who held a post typed into a stage — banked into an IndexedDB
    draft the repository refuses for ever — and then watched the box disable under the caret.
  */
  expect(writesHeld(undefined), "still asking").toBe(true);
  expect(writesHeld("You are this workshop's inspector, …"), "a post held").toBe(true);
  expect(writesHeld(null), "none known").toBe(false);

  // A component whose prop defaults to null would read `undefined` as "nothing held", so the pending
  // state reaches it as a sentence.
  expect(heldPostReason(undefined)).toBe(HELD_POST_PENDING);
  expect(heldPostReason(null)).toBeNull();
  expect(heldPostReason("the refusal")).toBe("the refusal");

  const hook = read("components", "designworkshop", "HeldPostNotice.tsx");
  // Pending only for an account that may appoint — nobody else is ever asked, so nobody else waits.
  expect(hook).toContain("const asks = Boolean(workshopId && user && canAssignWorkshopOversight(user));");
  expect(hook).toContain("if (!asks) return null;");
  expect(hook).toMatch(/\?\s*heldPostEditRefusal\(answer\.posts\)\s*:\s*undefined;/);
  // Every path ends in an answer: a workshop only this device holds is "none", and so is any failure.
  expect(hook).toContain("if (serverId) posts = await readHeldWorkshopPosts(serverId, user);");
  expect(hook).toMatch(/\} catch \{\s*posts = \[\];\s*\}/);
});

test("the notice is said into a live region that exists before the answer arrives", () => {
  /*
    The refusal lands a round trip after the page, and a region created together with its first
    sentence announces nothing — so it used to appear in silence while every control went inert.
    The wrapper is always rendered; the amber panel is what appears inside it. The pending sentence is
    printed OUTSIDE the region, so an appointer is not told "checking" aloud on every screen.
  */
  const notice = read("components", "designworkshop", "HeldPostNotice.tsx");
  const drawn = notice.slice(notice.indexOf("export function HeldPostNotice("));
  expect(drawn, "the component was located").toMatch(
    /<div id=\{id\} role="status" aria-live="polite" className=\{refusal \? undefined : "sr-only"\}>\s*\{refusal \? \(/
  );
  expect(drawn, "never `hidden`, which takes the region out of the tree").not.toMatch(/: "hidden"\}/);
  expect(drawn, "and it no longer returns nothing when there is no refusal").not.toMatch(/if \(!refusal\) return null;/);
  const region = drawn.slice(drawn.indexOf('<div id={id} role="status"'), drawn.indexOf("</div>"));
  expect(region, "the pending sentence is not inside the live region").not.toContain("HELD_POST_PENDING");
  expect(drawn).toMatch(/\{sayPending && refusal === undefined \? \(/);
});

/**
 * Each screen that writes a workshop, and the lines that make it ask and hold. A row per screen so a
 * failure names the screen whose controls came back.
 */
const SCREENS: ReadonlyArray<{ screen: string; path: string[]; must: string[]; mustNot?: string[] }> = [
  {
    screen: "the stage index — Edit details, the status buttons and the consent row",
    path: ["app", "(protected)", "design-workshops", "[id]", "page.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(id);",
      "<HeldPostNotice refusal={postRefusal} />",
      // Not while the answer is out either: `null` is the one value that means "none known".
      "canRunDesignWorkshops(user) && !neverSent && postRefusal === null && frozenReason === null ?",
      "const mayDecide = canRunDesignWorkshops(user) && !readOnlyReason;",
      "readOnlyReason={heldPostReason(postRefusal)}"
    ],
    mustNot: ["readOnlyReason={postRefusal}"]
  },
  {
    screen: "the stage form",
    path: ["app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(serverId);",
      // `writesHeld` and not `Boolean(...)`: the lock covers the pending state as well as a refusal.
      "const locked = saving || writesHeld(postRefusal) || frozenReason !== null;",
      "<HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />",
      "if (!stage || writesHeld(postRefusal) || frozenReason !== null) return;",
      "aria-describedby={postRefusal ? heldNoticeId : undefined}"
    ],
    // Every box and both Save buttons are held by `locked`; one left on `saving` is one reopened.
    mustNot: ["disabled={saving}", "Boolean(postRefusal)"]
  },
  {
    screen: "Edit workshop details",
    path: ["app", "(protected)", "design-workshops", "[id]", "edit", "page.tsx"],
    must: [
      'useHeldPostRefusal(state?.kind === "loaded" ? state.record.id : null)',
      // The form only once "none known" is the answer — never drawn, then taken away.
      "postRefusal === null ? (",
      "<HeldPostNotice refusal={postRefusal} sayPending />"
    ]
  },
  {
    screen: "this workshop's own questions",
    path: ["app", "(protected)", "design-workshops", "[id]", "custom-sections", "page.tsx"],
    must: [
      "useHeldPostRefusal(id)",
      "<HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />",
      "readOnlyReason={heldPostReason(postRefusal)}",
      "describedBy={postRefusal ? heldNoticeId : undefined}"
    ]
  },
  {
    screen: "the custom sections editor",
    path: ["components", "designworkshop", "CustomSectionsEditor.tsx"],
    must: [
      "const frozen = busy || Boolean(readOnlyReason);",
      "locked: frozen,",
      "if (!remoteId || readOnlyReason) return false;",
      "aria-describedby={describedBy}"
    ],
    mustNot: ["disabled={busy", "locked: busy,"]
  },
  {
    screen: "AI layers",
    path: ["app", "(protected)", "design-workshops", "[id]", "ai-layers", "page.tsx"],
    must: ["useHeldPostRefusal(id)", "<AiLayersPanel workshopId={id} readOnlyReason={heldPostReason(postRefusal)} />"]
  },
  {
    screen: "the AI layers panel",
    path: ["components", "designworkshop", "AiLayersPanel.tsx"],
    // `anyBusy={anyBusy}` survives exactly once, inside `LayerRow`, passing its own (already held) prop
    // down to the next row of the chain; every mount from the panel itself hands `locked`.
    must: ["const locked = anyBusy || Boolean(readOnlyReason);", "readOnlyReason ??", "anyBusy={locked}"]
  },
  {
    screen: "bulk photo import",
    path: ["app", "(protected)", "design-workshops", "[id]", "photos", "page.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(id);",
      "if (!draft || !chosen.length || writesHeld(postRefusal)) return;",
      "disabled={Boolean(reading) || confirming || writesHeld(postRefusal)}",
      "<HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />",
      "aria-describedby={postRefusal ? heldNoticeId : undefined}"
    ],
    mustNot: ["Boolean(postRefusal)"]
  },
  {
    screen: "the report's settings and colour",
    path: ["app", "(protected)", "design-workshops", "[id]", "report", "page.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(remoteId);",
      "readOnlyReason={heldPostReason(postRefusal)}",
      "const heldReason = heldPostReason(postRefusal);",
      "if (heldReason) {"
    ]
  },
  {
    screen: "the report settings panel",
    path: ["components", "designworkshop", "report", "ReportSettingsPanel.tsx"],
    must: ["if (!entity || readOnlyReason) return;", "disabled={saving || Boolean(readOnlyReason)}"]
  },
  {
    screen: "sketches and prototypes, both routes",
    path: ["components", "sketches", "SketchesWorkspace.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(workshopId);",
      '<HeldPostNotice refusal={postRefusal} sayPending={tab === "upload"} />',
      "<UploadTabHost workshopId={workshopId} registry={registry} readOnlyReason={heldPostReason(postRefusal)} />",
      "readOnlyReason={heldPostReason(postRefusal)}"
    ],
    mustNot: ["readOnlyReason={postRefusal}"]
  },
  {
    screen: "the sketches upload tab",
    path: ["components", "sketches", "UploadTabHost.tsx"],
    must: ["const frozen = busy || Boolean(readOnlyReason);", "disabled={frozen || !anythingReady}"]
  },
  {
    screen: "the review tab's arrangement (a rating is not refused and stays live)",
    path: ["components", "sketches", "ReviewPanel.tsx"],
    must: ["stageKey !== null && !readOnlyReason", ": readOnlyReason"]
  },
  {
    screen: "Design workshop visibility (the designer team)",
    path: ["components", "settings", "DesignWorkshopViewersPanel.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(workshopId || null);",
      "disabled={writesHeld(postRefusal)}",
      "disabled={!dirty || saving || writesHeld(postRefusal)}",
      "if (!workshopId || writesHeld(postRefusal)) return;",
      "<HeldPostNotice refusal={postRefusal} id={heldNoticeId} className=\"mt-4\" sayPending />",
      "aria-describedby={postRefusal ? heldNoticeId : undefined}"
    ],
    mustNot: ["Boolean(postRefusal)"]
  },
  {
    screen: "Workshop oversight's Designers panel",
    path: ["app", "(protected)", "officers", "page.tsx"],
    must: [
      "readOnlyReason={readOnlyReason}",
      "disabled={saving || Boolean(readOnlyReason)}",
      "|| dropsTheLeadWithNoReplacement || readOnlyReason) return;",
      '<HeldPostNotice refusal={readOnlyReason} id={heldNoticeId} className="mt-3" />',
      "aria-describedby={readOnlyReason ? heldNoticeId : undefined}"
    ]
  }
];

for (const { screen, path, must, mustNot } of SCREENS) {
  test(`a post holder's writes are held, with the reason, on ${screen}`, () => {
    const source = read(...path);
    for (const line of must) {
      expect(source, `${path.join("/")} has lost: ${line}`).toContain(line);
    }
    for (const line of mustNot ?? []) {
      expect(source, `${path.join("/")} still has: ${line}`).not.toContain(line);
    }
  });
}

test("the AI layers panel hands every mount the held flag, leaving only the row's own pass-down", () => {
  const panel = read("components", "designworkshop", "AiLayersPanel.tsx");
  expect(panel.split("anyBusy={anyBusy}").length - 1, "one pass-down, inside LayerRow").toBe(1);
});

test("Workshop oversight keeps its two appointment panels live for a post holder", () => {
  /*
    APPOINTING OTHER PEOPLE IS NOT A WRITE OF THE WORKSHOP, and the ruling keeps it: a post holder
    may still name its Assistant Director, Regional Director and inspectors (never themselves). So
    the read-only reason reaches exactly two panels on this page — Designers and the artisan list —
    and the panel that names officers and the inspectors' panel are not handed it.
  */
  const page = read("app", "(protected)", "officers", "page.tsx");
  const mounts = page.slice(page.indexOf("<DesignerPanel"), page.indexOf("<ArtisanListPanel"));
  const oversight = mounts.slice(mounts.indexOf("<OversightPanel"), mounts.indexOf("<DesignWorkshopInspectorsPanel"));
  expect(oversight, "the officers panel stays live").not.toContain("readOnlyReason");
  expect(mounts.slice(mounts.indexOf("<DesignWorkshopInspectorsPanel")), "and so does the inspectors panel").not.toContain(
    "readOnlyReason"
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 3. Who the designer pickers offer
 * ──────────────────────────────────────────────────────────────────────────── */

test("a CREATE form offers the reader as a designer, because the create doors accept it", () => {
  /*
    THE SERVER'S RULE, READ OFF ITS OWN SOURCE. Both create doors subtract the creator from the named
    set before validating it — the creator's access is `createdById`, and no viewer row is written
    for them — so a creator ticking themselves is accepted. A create form that left the reader out
    would refuse what the API allows.
  */
  expect(readBackend("app", "api", "routes", "design_workshops.py")).toContain(
    "wanted = set(designer_ids) - {current_user.id}"
  );
  expect(readBackend("app", "services", "design_workshops.py")).toContain("wanted = set(designer_ids) - {actor.id}");

  const listPage = read("app", "(protected)", "design-workshops", "page.tsx");
  const createPicker = listPage.slice(listPage.indexOf("<WorkshopDesignerPicker"));
  expect(createPicker.slice(0, createPicker.indexOf("/>")), "the create form must offer the reader").not.toContain(
    "excludeUserIds"
  );

  const officers = read("app", "(protected)", "officers", "page.tsx");
  const startForm = officers.slice(officers.indexOf("function StartWorkshopForm("), officers.indexOf("function DesignerPanel("));
  expect(startForm, "Workshop oversight's start form must offer the reader").not.toContain("excludeUserIds=");
});

test("a picker on a workshop that EXISTS never offers the reader, because naming yourself there is refused", () => {
  // `design_workshop_posts` rule 1 — a 409 on the viewers PUT and on both oversight designer doors.
  expect(readBackend("app", "services", "design_workshop_posts.py")).toContain("nobody appoints themselves to a");

  const officers = read("app", "(protected)", "officers", "page.tsx");
  const designerPanel = officers.slice(officers.indexOf("function DesignerPanel("), officers.indexOf("function OversightPanel("));
  expect(designerPanel).toContain("excludeUserIds={user ? [user.id] : undefined}");

  const viewers = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  expect(viewers).toContain("(eligible ?? []).filter((person) => person.id !== readerId)");
});

test("the reader's own row is never 'no longer eligible': they are missing from the list because nobody names themselves", () => {
  /*
    Both directories leave the caller out, and both panels drop the reader again — so on a complete
    list an admin whom somebody else appointed to inspect this workshop (or gave designer access) read
    their own row as barred. Absence proves a bar for everybody else, and for the reader proves nothing.
  */
  expect(absenceProvesIneligible(true, "u-somebody", "u-reader"), "a complete list, somebody else").toBe(true);
  expect(absenceProvesIneligible(true, "u-reader", "u-reader"), "a complete list, the reader").toBe(false);
  expect(absenceProvesIneligible(false, "u-somebody", "u-reader"), "a cut or searched list proves nothing").toBe(false);
  expect(absenceProvesIneligible(true, "u-somebody", null)).toBe(true);

  const inspectors = read("components", "settings", "DesignWorkshopInspectorsPanel.tsx");
  expect(inspectors).toContain("absenceProvesIneligible(eligibleListIsComplete, row.userId, readerId)");
  expect(inspectors).not.toMatch(/eligibleListIsComplete \? " — inspecting, no longer eligible"/);
  const viewers = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  expect(viewers).toContain("absenceProvesIneligible(eligibleListIsComplete, row.userId, readerId)");
  expect(viewers).not.toMatch(/eligibleListIsComplete \? " — has access, no longer eligible"/);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 4. A record filed under a workshop the reader inspects or supervises
 * ──────────────────────────────────────────────────────────────────────────── */

const REFUSAL = "You are this workshop's inspector, so you can read it but not change it.";

test("a stored workshop holds the record — Save, Delete and the picker; a chosen one holds Save and leaves the picker live", () => {
  /*
    A record filed under a design workshop is that workshop's content (2026-10-09): a post holder may
    not edit it, delete it, take it out, or file another into it. The STORED workshop makes the record
    read-only, picker included — moving it out is refused too. A CHOSEN workshop only refuses the
    filing, and choosing another is the way forward, so the picker must stay live or the form traps them.
  */
  const stored = recordFilingHold(REFUSAL, null, "artisan");
  expect(stored.recordHeld).toBe(true);
  expect(stored.saveHeld).toBe(true);
  expect(stored.notice).toBe(
    `This artisan is filed under a design workshop you hold a post on, so it cannot be changed or deleted here. ${REFUSAL}`
  );

  const chosen = recordFilingHold(null, REFUSAL, "product");
  expect(chosen.recordHeld, "the picker stays live: choosing another workshop is the way out").toBe(false);
  expect(chosen.saveHeld).toBe(true);
  expect(chosen.notice).toBe(
    `The workshop chosen for this product is one you hold a post on, so it cannot be filed there — choose another. ${REFUSAL}`
  );

  // Still asking holds exactly what an answer would, and says it as pending, not as a refusal.
  expect(recordFilingHold(undefined, null, "tool")).toEqual({
    recordHeld: true,
    saveHeld: true,
    notice: undefined,
    stored: undefined
  });
  expect(recordFilingHold(null, undefined, "tool")).toEqual({ recordHeld: false, saveHeld: true, notice: undefined, stored: null });
  // The stored workshop's refusal wins the sentence: it is the one the reader cannot choose away.
  expect(recordFilingHold(REFUSAL, REFUSAL, "process").notice).toMatch(/^This process is filed under/);
  // Nothing held, nothing said.
  expect(recordFilingHold(null, null, "interview")).toEqual({ recordHeld: false, saveHeld: false, notice: null, stored: null });

  // What the record's FILES are held by is the stored workshop's own answer, three states and all —
  // never the chosen one's: nothing is filed there until the save that is itself held.
  expect(stored.stored).toBe(REFUSAL);
  expect(chosen.stored, "a chosen workshop holds no file that is already attached").toBeNull();
});

/** The five record forms, and the noun each one names itself by. */
const RECORD_FORMS: ReadonlyArray<{ form: string; path: string[]; stored: string; noun: string }> = [
  { form: "the artisan form", path: ["components", "forms", "ArtisanForm.tsx"], stored: "initial?.designWorkshopId", noun: "artisan" },
  { form: "the product form", path: ["components", "forms", "ProductForm.tsx"], stored: "initial?.designWorkshopId", noun: "product" },
  { form: "the process form", path: ["components", "forms", "ProcessForm.tsx"], stored: "initial?.designWorkshopId", noun: "process" },
  { form: "the tool form", path: ["components", "forms", "ToolForm.tsx"], stored: "initial?.designWorkshopId", noun: "tool" },
  {
    form: "the interview form",
    path: ["app", "(protected)", "questionnaire", "page.tsx"],
    stored: "editingInterview?.designWorkshopId",
    noun: "interview"
  }
];

for (const { form, path, stored, noun } of RECORD_FORMS) {
  test(`${form} holds its picker and its Save for a post holder, and says why beside Save`, () => {
    const source = read(...path);
    const where = path.join("/");
    expect(source, `${where}: the hold is asked of the stored and the chosen workshop`).toContain(
      `const filing = useRecordFilingHold(${stored}, workshop.designWorkshopId, "${noun}");`
    );
    expect(source, `${where}: the picker`).toMatch(/<WorkshopPicker state=\{workshop\}[^>]*disabled=\{filing\.recordHeld\}/);
    expect(source, `${where}: Save`).toMatch(/disabled=\{[^}]*filing\.saveHeld\}/);
    expect(source, `${where}: Save names the reason`).toContain("aria-describedby={filing.notice ? filingNoticeId : undefined}");
    expect(source, `${where}: the reason, in a live region beside Save`).toContain(
      '<HeldPostNotice refusal={filing.notice} id={filingNoticeId} className="" sayPending />'
    );
    // A disabled button does not stop `requestSubmit()` from the unsaved-changes prompt.
    expect(source, `${where}: submit keeps the rule`).toContain("if (filing.saveHeld) return;");
  });
}

test("the picker's own hold disables both of its boxes, as a save in flight does", () => {
  const picker = read("components", "forms", "WorkshopPicker.tsx");
  expect(picker).toContain("const held = Boolean(saving) || disabled;");
  expect((picker.match(/saving=\{held\}/g) ?? []).length, "both halves").toBe(2);
  expect(picker).toContain("disabled={held}");
});

test("a refused save or delete shows the server's sentence word for word", async () => {
  /*
    The web's hold reaches only an account that may appoint, and an answer still in flight is not a
    refusal — so the server's 403 stays the last word, and every record screen prints it verbatim
    rather than a sentence of its own.
  */
  installFakeWindow();
  const detail =
    "You are this workshop's inspector, so you can read it but not change it: whoever inspects or supervises a workshop does not write it.";
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail }), { status: 403, headers: { "content-type": "application/json" } });
  const refused = await apiFetch("/artisans/a1", { method: "PATCH", body: "{}" }).catch((error: unknown) => error);
  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message).toBe(detail);
  expect(readableError(refused, "Unable to save artisan")).toBe(detail);

  expect(read("components", "forms", "ArtisanForm.tsx")).toContain('setError(readableError(err, "Unable to save artisan"));');
  for (const [file, fallback] of [
    [["components", "forms", "ProductForm.tsx"], "Unable to save product record"],
    [["components", "forms", "ProcessForm.tsx"], "Unable to save process"],
    [["components", "forms", "ToolForm.tsx"], "Unable to save tool record"],
    [["app", "(protected)", "questionnaire", "page.tsx"], "Unable to save interview"],
    [["app", "(protected)", "questionnaire", "page.tsx"], "Unable to delete interview"],
    [["app", "(protected)", "artisans", "page.tsx"], "Unable to delete artisan"],
    [["app", "(protected)", "products", "page.tsx"], "Unable to delete product record"],
    [["app", "(protected)", "processes", "page.tsx"], "Unable to delete process record"],
    [["app", "(protected)", "tools", "page.tsx"], "Unable to delete tool record"]
  ] as const) {
    expect(read(...file), `${file.join("/")} prints the server's own sentence`).toContain(
      `setError(err instanceof Error ? err.message : "${fallback}");`
    );
  }
});

test("a questionnaire attached to a held workshop is read, answered and downloaded — never changed", () => {
  /*
    Every edit to a questionnaire form attached to a workshop the reader inspects or supervises is
    refused, its owner and an admin included. The page already knows how to draw a form its reader may
    not change (a colleague's), so a holder gets that view with the post's sentence instead — and keeps
    the workbook download, which is a read.
  */
  const page = read("app", "(protected)", "questionnaires", "[id]", "page.tsx");
  expect(page).toContain("const owns = canEditOwnOrAdmin(user, form?.ownerId);");
  expect(page).toContain("const attachedHold = useHeldPostRefusal(form?.designWorkshopId ?? null);");
  // `null` and nothing else is "none known": still asking holds the controls too.
  expect(page).toContain("const mayEdit = owns && attachedHold === null;");
  expect(page).toContain(
    "This questionnaire is attached to a design workshop you hold a post on, so it cannot be changed here."
  );
  // The colleague's-form notice is for somebody who does not own it, not for a holder who does.
  expect(page).toMatch(/\{!owns \? \(\s*\/\*/);
  // The download is a read: `owns`, not `mayEdit`.
  expect(page).toMatch(/\{owns \? \(\s*<button type="button" className="field-button-secondary" onClick=\{download\}/);
  expect(page).toContain("() => patchQuestionnaire(id, { designWorkshopId: designWorkshopId || null })");
  expect(page, "a refused attach prints the server's sentence").toContain(
    "setError(err instanceof Error ? err.message : failure);"
  );
});

/* ────────────────────────────────────────────────────────────────────────────
 * 5. A file a held workshop holds
 * ──────────────────────────────────────────────────────────────────────────── */

test("a file's own columns name its workshops — the filing and the stage tag, compared as the server does", () => {
  /*
    The server finds a file's workshops five ways (`media_design_workshop_ids`) and refuses a post
    holder every write to it if ANY is held. Two are on the row: `designWorkshopId`, and the
    `designWorkshop` tag every stage capture carries — matched after a strip and case-insensitively,
    exactly as `dictation_consent.tagged_workshop_id` matches it, against that module's own spelling.
  */
  expect(readBackend("app", "services", "dictation_consent.py")).toContain('MEDIA_TAG = "designWorkshop"');
  expect(mediaWorkshopIds({ designWorkshopId: "w1" })).toEqual(["w1"]);
  expect(mediaWorkshopIds({ linkedRecordType: "designWorkshop", linkedRecordId: "w2" })).toEqual(["w2"]);
  expect(mediaWorkshopIds({ linkedRecordType: " DESIGNWORKSHOP ", linkedRecordId: " w2 " })).toEqual(["w2"]);
  expect(mediaWorkshopIds({ designWorkshopId: "w1", linkedRecordType: "designWorkshop", linkedRecordId: "w2" })).toEqual([
    "w1",
    "w2"
  ]);
  expect(mediaWorkshopIds({ designWorkshopId: "w1", linkedRecordType: "designWorkshop", linkedRecordId: "w1" }), "asked once").toEqual([
    "w1"
  ]);
  // A RECORD link is not a workshop: the record's own filing is the form's to hand in (`recordHold`).
  expect(mediaWorkshopIds({ linkedRecordType: "artisan", linkedRecordId: "a1" })).toEqual([]);
  expect(mediaWorkshopIds({ designWorkshopId: null, linkedRecordType: null, linkedRecordId: null })).toEqual([]);
});

test("one file's hold is the server's rule in three states: refused if any workshop is held", () => {
  expect(mediaWriteHold([]), "a file that names no workshop").toBeNull();
  expect(mediaWriteHold([null, null])).toBeNull();
  expect(mediaWriteHold([null, undefined]), "still asking about one of them").toBeUndefined();
  expect(mediaWriteHold([undefined, REFUSAL]), "a held post is known, whatever is still out").toBe(REFUSAL);
  expect(mediaWriteHold([REFUSAL, null])).toBe(REFUSAL);

  // What a held file's own control says in place of its usual text — the reason, or pending, or nothing.
  expect(mediaHeldReason(REFUSAL)).toBe(MEDIA_HELD_REASON);
  expect(mediaHeldReason(undefined)).toBe(HELD_POST_PENDING);
  expect(mediaHeldReason(null)).toBeNull();
});

test("a list says the server's sentence once above itself, and marks each held file in words", () => {
  /*
    A page of twenty photographs of the one workshop somebody inspects would otherwise be twenty
    copies of a three-line paragraph — so one notice for the list, each distinct sentence once, and a
    word on every held row or tile. Pending is its own state, said BELOW the live region, in words
    that name these files' workshops rather than "this workshop".
  */
  const other = "You are this workshop's Regional Director, so you can read it but not change it.";
  const notice = mediaListNotice([REFUSAL, null, REFUSAL, other, undefined], "deleted or re-transcribed");
  expect(notice).toContain(`Files marked “${MEDIA_HELD_LABEL}”`);
  expect(notice).toContain("cannot be deleted or re-transcribed here.");
  expect(notice!.split(REFUSAL).length - 1, "each sentence once").toBe(1);
  expect(notice).toContain(other);
  expect(mediaListNotice([null, undefined], "removed"), "still asking").toBeUndefined();
  expect(mediaListNotice([null, null], "removed"), "nothing held, nothing said").toBeNull();
  expect(mediaListNotice([], "relinked")).toBeNull();
  expect(HELD_POST_PENDING_FILES).not.toContain("this workshop");

  const hook = read("components", "designworkshop", "HeldPostNotice.tsx");
  const drawn = hook.slice(hook.indexOf("export function HeldPostNotice("));
  expect(drawn, "the pending line takes the list's sentence").toContain("{pendingSentence}");
  expect(drawn).toContain("pendingSentence = HELD_POST_PENDING");
});

test("the list hook asks once per distinct workshop, only for an appointer, and shares the one resolution", () => {
  const hook = read("components", "designworkshop", "HeldPostNotice.tsx");
  const lists = hook.slice(hook.indexOf("export function useHeldPostRefusals("));
  expect(lists, "the hook was located").toContain("const appoints = Boolean(user && canAssignWorkshopOversight(user));");
  // One request pair per distinct workshop however many rows name it, and a stable key for the set.
  expect(lists).toContain('const wanted = appoints ? [...new Set(workshopIds.filter(Boolean))].sort().join("\\n") : "";');
  // Only what is not yet known is asked; answers are kept per account.
  expect(lists).toContain("const missing = wanted.split(\"\\n\").filter((id) => !posts?.has(id));");
  expect(lists).toContain("known.userId === user.id ? known.posts : null");
  // Nobody else is ever asked, and an unanswered workshop holds its rows.
  expect(lists).toContain("if (!appoints) return null;");
  expect(lists).toMatch(/return held \? heldPostEditRefusal\(held\) : undefined;/);
  // The single-workshop hook and the list hook resolve an address the same way, in one function.
  expect((hook.match(/await heldPostsOn\(/g) ?? []).length, "both hooks").toBe(2);
});

/**
 * Each web screen that writes a stored file, and the lines that hold it. Transcript edit and refine
 * have no web control (see the next test), and the identity decision is held by the stage form's own
 * lock — its row is the last.
 */
const MEDIA_SCREENS: ReadonlyArray<{ screen: string; path: string[]; must: string[]; mustNot?: string[] }> = [
  {
    screen: "Miscellaneous Media — Delete and Transcribe now on each row",
    path: ["app", "(protected)", "media", "page.tsx"],
    must: [
      // One ask per distinct workshop, across the rows and the upload's own two choices (section 6).
      "const heldFor = useHeldPostRefusals([",
      "...(data?.items ?? []).filter(writable).flatMap(mediaWorkshopIds),",
      "const holdOf = (item: MediaFile): HeldPostRefusal => (writable(item) ? heldFor(mediaWorkshopIds(item)) : null);",
      "disabled={transcribingId === item.id || writesHeld(hold)}",
      "onClick={() => remove(item)}",
      "disabled={writesHeld(hold)}",
      "aria-describedby={typeof hold === \"string\" ? heldNoticeId : undefined}",
      'refusal={mediaListNotice((data?.items ?? []).map(holdOf), "deleted or re-transcribed")}',
      "{MEDIA_HELD_LABEL}",
      // A refusal that arrives anyway is the server's sentence, as written.
      'setError(err instanceof Error ? err.message : "Unable to remove media file");',
      'setError(err instanceof Error ? err.message : "Unable to transcribe this file right now");'
    ],
    mustNot: ["onClick={() => remove(item.id)}"]
  },
  {
    screen: "a record's attached files — the ✕ and the re-run transcript on each",
    path: ["components", "media", "ExistingMedia.tsx"],
    must: [
      // REQUIRED, no default: `undefined` is "still asking", and a default of null would release it.
      "recordHold: HeldPostRefusal;",
      "const holdOf = (media: MediaFile): HeldPostRefusal => mediaWriteHold([recordHold, heldFor(mediaWorkshopIds(media))]);",
      "if (writesHeld(holdOf(media))) return;",
      "removeHeld={writesHeld(hold)}",
      "statusLabel={typeof hold === \"string\" ? MEDIA_HELD_LABEL : null}",
      "readOnlyReason={mediaHeldReason(hold)}",
      'refusal={mediaListNotice(shown.map(holdOf), "removed or re-transcribed")}',
      'setError(err instanceof ApiError ? err.message : "Unable to remove this media file.");'
    ],
    mustNot: ["recordHold = null", "recordHold?:"]
  },
  {
    screen: "the transcript's re-run control",
    path: ["components", "media", "TranscriptBlock.tsx"],
    must: [
      "disabled={running || Boolean(readOnlyReason)}",
      "if (readOnlyReason) return;",
      'setFailure(err instanceof Error ? err.message : "Transcribing now failed.'
    ]
  },
  {
    screen: "the tile's remove control",
    path: ["components", "media", "MediaLightbox.tsx"],
    must: ["disabled={removeHeld}", "if (!removeHeld) onRemove();", "aria-describedby={removeHeld ? removeDescribedBy : undefined}"]
  },
  {
    screen: "a process's and its steps' attached files",
    path: ["components", "forms", "ProcessForm.tsx"],
    must: [
      "recordHold: HeldPostRefusal;",
      "if (writesHeld(holdOf(media))) return;",
      "removeHeld={writesHeld(hold)}",
      'onError(err instanceof Error ? err.message : "Unable to remove media");'
    ]
  },
  {
    screen: "the recovered recordings — the relink control",
    path: ["app", "(protected)", "admin", "page.tsx"],
    must: [
      "const heldFor = useHeldPostRefusals((orphans ?? []).flatMap(mediaWorkshopIds));",
      "const hold = heldFor(mediaWorkshopIds(item));",
      "{writesHeld(hold) ? (",
      "{MEDIA_HELD_LABEL}",
      '"relinked")}'
    ]
  },
  {
    screen: "the identity photograph's keep and delete, under the stage form's own lock",
    path: ["components", "designworkshop", "IdentityCardReader.tsx"],
    must: [
      'onClick={() => void decide(entry.mediaId, "DISCARD")}',
      'onClick={() => void decide(entry.mediaId, "STORE")}',
      // The refusal is the server's sentence, after the state the record is left in.
      "still stored on this record. ${error.message}`"
    ]
  }
];

for (const { screen, path, must, mustNot } of MEDIA_SCREENS) {
  test(`a post holder's file writes are held, with the reason, on ${screen}`, () => {
    const source = read(...path);
    for (const line of must) {
      expect(source, `${path.join("/")} has lost: ${line}`).toContain(line);
    }
    for (const line of mustNot ?? []) {
      expect(source, `${path.join("/")} still has: ${line}`).not.toContain(line);
    }
  });
}

test("the identity decision is held by the stage form's lock, which covers the pending answer too", () => {
  const reader = read("components", "designworkshop", "IdentityCardReader.tsx");
  // Both buttons, and nothing else decides: `disabled` is the stage's `locked`, handed down.
  expect((reader.match(/disabled=\{disabled \|\| busy !== null\}/g) ?? []).length, "keep and delete").toBe(2);
  const field = read("components", "designworkshop", "FieldInput.tsx");
  expect(field.slice(field.indexOf("<IdentityCardReader")).slice(0, 400)).toContain("disabled={disabled}");
  const stage = read("app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx");
  expect(stage).toContain("const locked = saving || writesHeld(postRefusal) || frozenReason !== null;");
  expect(stage.slice(stage.indexOf("<EntityForm")).slice(0, 600)).toContain("disabled={locked}");
});

test("every record form hands its attached files the stored workshop's answer", () => {
  for (const form of ["ArtisanForm.tsx", "ProductForm.tsx", "ToolForm.tsx"]) {
    expect(read("components", "forms", form), form).toMatch(/<ExistingMedia linkedRecordType="[a-z]+" linkedRecordId=\{initial\.id\} recordHold=\{filing\.stored\} \/>/);
  }
  expect((read("components", "forms", "ProcessForm.tsx").match(/recordHold=\{filing\.stored\}/g) ?? []).length, "both lists").toBe(2);
  // A craft and a crafts workshop are never filed under a design workshop: none, said explicitly.
  expect(read("app", "(protected)", "crafts", "page.tsx")).toContain("recordHold={null}");
  expect(read("app", "(protected)", "workshops", "page.tsx")).toContain("recordHold={null}");
});

/** Every `.ts`/`.tsx` file under the three source trees. */
function webSources(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) webSources(full, found);
    else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) found.push(full);
  }
  return found;
}

test("the web has no transcript edit or refine control — a new one owes the same hold", () => {
  /*
    `POST /media/{id}/transcript` and `/refine-transcript` are the handset's (MainActivity). The
    server refuses both to a post holder; were the web to grow either, it would have to be held like
    the controls above, and this is what makes that a decision rather than an accident.
  */
  const offenders: string[] = [];
  for (const tree of ["app", "components", "lib"]) {
    for (const file of webSources(join(FRONTEND, tree))) {
      const source = readFileSync(file, "utf8");
      if (/refine-transcript/.test(source) || /\/media\/\$\{[^}]+\}\/transcript\b/.test(source)) {
        offenders.push(file.slice(FRONTEND.length + 1));
      }
    }
  }
  expect(offenders).toEqual([]);
});

test("every web call that changes a stored file comes from one of the held screens above", () => {
  /*
    THE REGISTER, READ OFF THE TREE: a new screen that deletes a file, re-runs its transcript, decides
    an identity photograph or relinks an orphan fails here until it is added — and adding it is the
    moment to give it the hold. `lib/` declarations are the calls themselves, not callers of them.
  */
  const found = { deletes: [] as string[], reruns: [] as string[], identity: [] as string[], relinks: [] as string[] };
  for (const tree of ["app", "components", "lib"]) {
    for (const file of webSources(join(FRONTEND, tree))) {
      const source = readFileSync(file, "utf8");
      const where = file.slice(FRONTEND.length + 1).replace(/\\/g, "/");
      if (/apiFetch\(`\/media\/\$\{[^}]+\}`,\s*\{\s*method:\s*"DELETE"/.test(source)) found.deletes.push(where);
      if (/transcribeMediaNow\(/.test(source) && where !== "lib/media.ts") found.reruns.push(where);
      if (/decideIdentityPhotograph\(/.test(source) && where !== "lib/identityPhotoRetention.ts") found.identity.push(where);
      if (/\/relink[`"']/.test(source)) found.relinks.push(where);
    }
  }
  expect(found.deletes.sort()).toEqual(
    ["app/(protected)/media/page.tsx", "components/forms/ProcessForm.tsx", "components/media/ExistingMedia.tsx"].sort()
  );
  expect(found.reruns.sort()).toEqual(["app/(protected)/media/page.tsx", "components/media/TranscriptBlock.tsx"].sort());
  expect(found.identity).toEqual(["components/designworkshop/IdentityCardReader.tsx"]);
  // The web's relink control is the orphan screen's link to /media; no web code calls the route.
  expect(found.relinks).toEqual([]);
});

test("a refused file write shows the server's sentence word for word", async () => {
  installFakeWindow();
  const detail =
    "You are this workshop's inspector, so you can read it but not change it: whoever inspects or supervises a workshop does not write it.";
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail }), { status: 403, headers: { "content-type": "application/json" } });
  const refused = await apiFetch("/media/m1", { method: "DELETE" }).catch((error: unknown) => error);
  expect(refused).toBeInstanceOf(ApiError);
  expect((refused as ApiError).message).toBe(detail);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 6. Every door that ADDS to a held workshop, or edits it from elsewhere — and nobody releasing
 *    themselves
 * ──────────────────────────────────────────────────────────────────────────── */

test("the /media upload is held on the workshop chosen and on what the files hang off — the choice first", () => {
  /*
    `/media/complete` refuses a post holder a NEW file filed under, tagged to, or attached to a record
    filed under their workshop — and refuses it after every byte was PUT, so three photographs met
    three refusals. The upload now asks first, about both of the things the form names.
  */
  expect(mediaUploadHold(null, null), "nothing held, nothing said").toBeNull();
  expect(mediaUploadHold(undefined, null), "still asking about the workshop chosen").toBeUndefined();
  expect(mediaUploadHold(null, undefined), "still asking about the linked record's").toBeUndefined();
  const chosen = mediaUploadHold(REFUSAL, null);
  expect(chosen).toBe(
    `The design workshop chosen for these files is one you hold a post on, so they cannot be filed under it — choose another, or none. ${REFUSAL}`
  );
  expect(mediaUploadHold(null, REFUSAL)).toBe(
    `The record chosen for these files is filed under a design workshop you hold a post on, so they cannot be attached to it — choose another. ${REFUSAL}`
  );
  // A held post known beats an answer still out, and the workshop chosen speaks first when both are.
  expect(mediaUploadHold(REFUSAL, undefined)).toBe(chosen);
  expect(mediaUploadHold(undefined, REFUSAL)).toMatch(/^The record chosen for these files/);
  expect(mediaUploadHold(REFUSAL, REFUSAL)).toBe(chosen);
});

test("a questionnaire is held on the workshop it would be attached to, and the picker stays the way out", () => {
  expect(attachHold(null, "this questionnaire")).toBeNull();
  expect(attachHold(undefined, "the copy"), "still asking").toBeUndefined();
  expect(attachHold(REFUSAL, "this questionnaire")).toBe(
    `The design workshop chosen for this questionnaire is one you hold a post on, so this questionnaire cannot be attached to it — choose another, or leave it unattached. ${REFUSAL}`
  );
  expect(attachHold(REFUSAL, "the copy")).toMatch(
    /^The design workshop chosen for the copy is one you hold a post on, so the copy cannot be attached to it/
  );
});

test("the review queue's edit is held on the workshop its record or file names, and keeps the three decisions", () => {
  /*
    `POST /review/{type}/{id}/edit` asks the record forms' own gate, so a post holder's Save and
    "Save and approve" are refused as on the record's screen. Approve, Reject and Send for revision
    are moderation and stay theirs — the sentence says so, or a held Save reads as a closed row.
  */
  expect(reviewRecordWorkshopIds("artisan", { designWorkshopId: "w1" })).toEqual(["w1"]);
  expect(reviewRecordWorkshopIds("questionnaire", { designWorkshopId: " w2 " })).toEqual(["w2"]);
  expect(reviewRecordWorkshopIds("product", { designWorkshopId: null })).toEqual([]);
  expect(reviewRecordWorkshopIds("workshop", { title: "Bagru" }), "a crafts workshop names none").toEqual([]);
  expect(reviewRecordWorkshopIds("artisan", null)).toEqual([]);
  expect(
    reviewRecordWorkshopIds("media", { designWorkshopId: "w1", linkedRecordType: "designWorkshop", linkedRecordId: "w2" })
  ).toEqual(["w1", "w2"]);
  // A file's RECORD link is not a workshop on its row — the media lists' own reading.
  expect(reviewRecordWorkshopIds("media", { linkedRecordType: "artisan", linkedRecordId: "a1" })).toEqual([]);

  expect(reviewEditHold(null, "artisan")).toBeNull();
  expect(reviewEditHold(undefined, "artisan"), "still asking").toBeUndefined();
  expect(reviewEditHold(REFUSAL, "artisan")).toBe(
    `This artisan is filed under a design workshop you hold a post on, so its fields cannot be edited here — approving it, rejecting it or sending it back for revision is still yours. ${REFUSAL}`
  );
  expect(reviewEditHold(REFUSAL, "questionnaire")).toMatch(/^This interview is filed under a design workshop/);
  expect(reviewEditHold(REFUSAL, "media")).toMatch(/^This file is part of a design workshop you hold a post on, so its fields/);
});

/**
 * Each door that adds to a workshop or edits its records from elsewhere, and the lines that hold it.
 * A row per door, so a failure names the door that reopened.
 */
const ADD_SCREENS: ReadonlyArray<{ screen: string; path: string[]; must: string[]; mustNot?: string[] }> = [
  {
    screen: "the review queue's edit panel — both Saves and every box, the three decisions untouched",
    path: ["components", "review", "ReviewEditPanel.tsx"],
    must: [
      "setFiling(reviewRecordWorkshopIds(recordType, record));",
      "const heldFor = useHeldPostRefusals(filing);",
      "const hold = baseline ? heldFor(filing) : null;",
      "const heldNotice = reviewEditHold(hold, recordType);",
      "const locked = busy || writesHeld(hold);",
      "if (!baseline || changedKeys.length === 0 || blanked.length > 0 || writesHeld(hold)) return;",
      "disabled={locked || changedKeys.length === 0 || blanked.length > 0}",
      'aria-describedby={typeof heldNotice === "string" ? heldNoticeId : undefined}',
      '<HeldPostNotice refusal={heldNotice} id={heldNoticeId} className="" sayPending />',
      // A refusal the hold did not foresee — a file whose workshop shows only another way — verbatim.
      'setError(readableError(err, "Unable to save this edit"));'
    ],
    mustNot: ["disabled={busy || changedKeys.length === 0"]
  },
  {
    // The other half of the same ruling: Approve, Reject and Send for revision are moderation, not
    // authorship, and a post holder keeps them — so the queue's own row actions ask nothing.
    screen: "the review queue's three decisions, which stay live",
    path: ["app", "(protected)", "review", "page.tsx"],
    must: [
      'onClick={() => toggleAction(item, "approve")}',
      'onClick={() => toggleAction(item, "revise")}',
      'onClick={() => toggleAction(item, "reject")}'
    ],
    mustNot: ["writesHeld(", "useHeldPostRefusal", "HeldPostNotice"]
  },
  {
    screen: "Miscellaneous Media — Upload, on the workshop chosen and the record linked",
    path: ["app", "(protected)", "media", "page.tsx"],
    must: [
      "const uploadChosen = designWorkshop.workshopId ? [designWorkshop.workshopId] : [];",
      "...mediaWorkshopIds({ linkedRecordType: linkedType, linkedRecordId: linkedEntryId || null }),",
      "...((linkedEntryId && entryFilings?.get(linkedEntryId)) || [])",
      "const uploadHold = mediaUploadHold(heldFor(uploadChosen), heldFor(uploadLinked));",
      // Asked before a single byte goes up — the difference from the server's own refusal.
      "if (writesHeld(uploadHold)) return;",
      "disabled={uploading || selectedFiles.length === 0 || !linkedType || writesHeld(uploadHold)}",
      'aria-describedby={typeof uploadHold === "string" ? uploadNoticeId : undefined}',
      '<HeldPostNotice refusal={uploadHold} id={uploadNoticeId} className="" sayPending />',
      // The five record types a design workshop files, and a parent file's own two links.
      "filings: filedUnder(page.items)",
      "filings: new Map(page.items.map((x) => [x.id, mediaWorkshopIds(x)]))"
    ]
  },
  {
    screen: "the media jobs panel — Retry",
    path: ["components", "media", "MediaJobsPanel.tsx"],
    must: [
      "const heldFor = useHeldPostRefusals((items ?? []).filter(offersRetry).flatMap(jobWorkshops));",
      "if (writesHeld(holdOf(job))) return;",
      "disabled={busyJobId === job.id || writesHeld(hold)}",
      'aria-describedby={typeof hold === "string" ? heldNoticeId : undefined}',
      'refusal={mediaListNotice((items ?? []).map(holdOf), "re-processed")}',
      "{MEDIA_HELD_LABEL}",
      'setError(err instanceof Error ? err.message : "Unable to re-queue this job");'
    ]
  },
  {
    screen: "the job row's file, which now carries its two workshop links",
    path: ["lib", "media.ts"],
    must: ['| "designWorkshopId"', '| "linkedRecordType"', '| "linkedRecordId"']
  },
  {
    screen: "My questionnaires — Create, on the workshop chosen",
    path: ["app", "(protected)", "questionnaires", "page.tsx"],
    must: [
      "const createWorkshopHold = useHeldPostRefusal(newWorkshopId || null);",
      'const createHold = attachHold(createWorkshopHold, "this questionnaire");',
      "if (!title || writesHeld(createHold)) return;",
      "disabled={creating || writesHeld(createHold)}",
      'aria-describedby={typeof createHold === "string" ? createHoldId : undefined}',
      '<HeldPostNotice refusal={createHold} id={createHoldId} className="" sayPending />',
      'setError(err instanceof Error ? err.message : "Unable to create the questionnaire");'
    ]
  },
  {
    screen: "the questionnaire upload dialog — Upload, on the workshop chosen",
    path: ["components", "questionnaires", "UploadDialog.tsx"],
    must: [
      "const workshopHold = useHeldPostRefusal(editing ? null : designWorkshopId || null);",
      'const hold = attachHold(workshopHold, "this questionnaire");',
      "if (!file || writesHeld(hold)) return;",
      "disabled={!file || busy || writesHeld(hold)}",
      'aria-describedby={typeof hold === "string" ? holdNoticeId : undefined}',
      '<HeldPostNotice refusal={hold} id={holdNoticeId} className="" sayPending />',
      'setError(err instanceof Error ? err.message : "Unable to read that workbook");'
    ]
  },
  {
    screen: "the questionnaire reuse dialog — Create the reuse, on the target chosen",
    path: ["components", "questionnaires", "ReuseDialog.tsx"],
    must: [
      "const targetHold = useHeldPostRefusal(designWorkshopId || null);",
      'const hold = attachHold(targetHold, "the copy");',
      "if (writesHeld(hold)) return;",
      "disabled={busy || writesHeld(hold)}",
      'aria-describedby={typeof hold === "string" ? holdNoticeId : undefined}',
      '<HeldPostNotice refusal={hold} id={holdNoticeId} className="" sayPending />',
      'setError(err instanceof Error ? err.message : "Unable to reuse this questionnaire");'
    ]
  },
  {
    screen: "the unfiled-records report — the bulk filing's refusal verbatim, and what it left alone",
    path: ["components", "settings", "WorkshopMappingPanel.tsx"],
    must: [
      'setError(cause instanceof Error ? cause.message : "The records could not be mapped.");',
      // From the apply answer, in the server's own words where it sent them.
      "setHeldBack(heldBackSentence(result));",
      "{applied !== null && heldBack ? (",
      // The rows a design workshop claims are not listed, and the report says so.
      "Records filed under a design &amp; prototype workshop are not listed here."
    ]
  },
  {
    screen: "an unfiled record's dialog — the filing's 403 and the discard's 409, verbatim",
    path: ["components", "settings", "UnfiledRecordCard.tsx"],
    must: [
      'setError(readableError(cause, "That record could not be filed just now."));',
      'setError(readableError(cause, "That record could not be deleted just now."));'
    ]
  }
];

for (const { screen, path, must, mustNot } of ADD_SCREENS) {
  test(`a post holder's door is held, with the reason, on ${screen}`, () => {
    const source = read(...path);
    for (const line of must) {
      expect(source, `${path.join("/")} has lost: ${line}`).toContain(line);
    }
    for (const line of mustNot ?? []) {
      expect(source, `${path.join("/")} still has: ${line}`).not.toContain(line);
    }
  });
}

test("the unfiled-records doors print a refusal as the server wrote it — the discard's 409 and the filing's 403", async () => {
  /*
    A row a design workshop claims is that workshop's evidence, not rubbish nobody filed: its discard
    is refused for every admin with a 409 that sends them to the record's own screen, and a holder's
    filing with the 403 naming the post. Neither is the client's to reword.
  */
  installFakeWindow();
  // `workshop_inference._claimed_detail`'s shape, with a workshop and a file filled in.
  const conflict =
    "“IMG_0042.jpg” belongs to the design workshop “Loom”, so it is not one of the unfiled records and is not deleted from here. Open the media file itself to change or delete it.";
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail: conflict }), { status: 409, headers: { "content-type": "application/json" } });
  const discarded = await discardUnfiledRecord("media", "m1").catch((error: unknown) => error);
  expect(discarded).toBeInstanceOf(ApiError);
  expect(readableError(discarded, "That record could not be deleted just now.")).toBe(conflict);

  const post =
    "You are this workshop's inspector, so you can read it but not change it: whoever inspects or supervises a workshop does not write it.";
  (globalThis as Record<string, unknown>).fetch = async () =>
    new Response(JSON.stringify({ detail: post }), { status: 403, headers: { "content-type": "application/json" } });
  const filed = await fileUnfiledRecord("products", "p1", "w9").catch((error: unknown) => error);
  expect(filed).toBeInstanceOf(ApiError);
  expect(readableError(filed, "That record could not be filed just now.")).toBe(post);
});

/**
 * One of the server's sentence builders in `backend/app/services`, read off disk: the f-string pieces
 * of `def name(...)`'s `return (...)`, joined — so the web's copy is held to the words, not to a second
 * typing of them in this file.
 */
function serverReturnTemplate(file: string[], name: string): string {
  const body = readBackend(...file).match(new RegExp(`def ${name}\\([\\s\\S]*?return \\(([\\s\\S]*?)\\n\\s*\\)`));
  expect(body, `${name} could not be read in backend/${file.join("/")}`).toBeTruthy();
  return [...body![1].matchAll(/\bf?"((?:[^"\\]|\\.)*)"/g)].map((piece) => piece[1]).join("");
}

/** A module-level string constant of the server's, its adjacent literals joined. */
function serverConstant(file: string[], name: string): string {
  const body = readBackend(...file).match(new RegExp(`^${name} = \\(([\\s\\S]*?)\\n\\)`, "m"));
  expect(body, `${name} could not be read in backend/${file.join("/")}`).toBeTruthy();
  return [...body![1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((piece) => piece[1]).join("");
}

test("the bulk filing says what it left alone for the reader's post — the server's sentence — and never invents a count", () => {
  expect(heldBackOf({ buckets: [], totals: { heldBack: 3 } }), "the server's total").toBe(3);
  expect(heldBackOf({ buckets: [{ heldBack: 2 }, { heldBack: null }, {}], totals: {} }), "else the buckets'").toBe(2);
  // An answer that reports nothing is "not reported" — an older server — and must not print as zero.
  expect(heldBackOf({ buckets: [{}, { heldBack: null }], totals: {} })).toBeNull();

  // THE SERVER'S SENTENCE, VERBATIM, whenever it sent one — and only then this client's own.
  const said = "2 records that belong to a design workshop you inspect or supervise were left as they were.";
  expect(heldBackSentence({ buckets: [], totals: { heldBack: 2 }, heldBackDetail: said })).toBe(said);
  expect(heldBackSentence({ buckets: [], totals: { heldBack: 3 }, heldBackDetail: null })).toBe(heldBackNotice(3));
  expect(heldBackSentence({ buckets: [], totals: { heldBack: 0 }, heldBackDetail: null }), "none held").toBeNull();
  expect(heldBackSentence({ buckets: [{}], totals: {} }), "a preview, or an older server").toBeNull();

  // The fallback is the server's own two sentences, read off the server: `HELD_BACK_ONE_DETAIL` for one
  // row and `HELD_BACK_DETAIL` for several, so a rewording on either side fails here.
  const one = serverConstant(["app", "services", "workshop_inference.py"], "HELD_BACK_ONE_DETAIL");
  const several = serverConstant(["app", "services", "workshop_inference.py"], "HELD_BACK_DETAIL");
  expect(several, "HELD_BACK_DETAIL no longer counts the rows").toContain("{count}");
  expect(heldBackNotice(3)).toBe(several.replace("{count}", "3"));
  expect(heldBackNotice(1)).toBe(one);
  expect(heldBackNotice(null)).toBeNull();
  expect(heldBackNotice(0)).toBeNull();
});

test("nobody takes themselves off a post: the server's own sentence names the post and who may act", () => {
  /*
    The appointment side had its mirror — nobody appoints themselves — and the release did not, so an
    admin could untick their own inspection, save, and write the workshop a second later. The server
    refuses it with a 409; the web keeps the reader's own row and slot ticked and switched off, with
    the server's sentence, so the 409 is never the first a reader hears of it — and a save the web
    stops itself says it whole, "Nothing was changed." included.
  */
  const template = serverReturnTemplate(["app", "services", "design_workshop_posts.py"], "self_release_refusal");
  expect(template, "self_release_refusal no longer names the post").toContain("{_labels(posts)}");
  for (const [post, label] of [
    ["INSPECTION", "inspector"],
    ["ASSISTANT_DIRECTOR", "Assistant Director"],
    ["REGIONAL_DIRECTOR", "Regional Director"]
  ] as const) {
    expect(selfReleaseRefused(post), post).toBe(template.replace("{_labels(posts)}", label));
    expect(selfReleaseRefusal(post), `${post}: the standing note is the refusal less its last sentence`).toBe(
      selfReleaseRefused(post).replace(/ Nothing was changed\.$/, "")
    );
  }
  expect(selfReleaseRefusal("INSPECTION")).toBe(
    "You are this workshop's inspector, and nobody takes themselves off a post: another administrator has to take you off."
  );
});

test("the reader's own Assistant or Regional Director slot is drawn with them in it, and never choosable away", () => {
  const reader: DwOfficer = {
    id: "u-reader",
    name: "Reader",
    email: "reader@example.org",
    role: "ADMIN",
    capacities: ["ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR"]
  };
  const colleague: DwOfficer = { ...reader, id: "u-colleague", name: "Colleague", email: "colleague@example.org" };
  const heldBy = (userId: string, person: DwOfficer): DwOversightAssignment => ({
    capacity: "ASSISTANT_DIRECTOR",
    userId,
    name: person.name,
    email: person.email,
    role: person.role,
    assignedAt: null,
    assignedById: "u-somebody-else"
  });

  const own = officerOptionsForPost({
    officers: [reader, colleague],
    capacity: "ASSISTANT_DIRECTOR",
    holder: heldBy(reader.id, reader),
    readerId: reader.id
  }).find((row) => row.value === reader.id);
  expect(own, "the reader's own post is drawn").toBeTruthy();
  expect(own?.disabled).toBe(true);
  expect(own?.hint).toContain(OWN_POST_HINT);
  expect(own?.group, "they hold it — it is not 'Cannot hold this post'").toBeUndefined();

  // Somebody else holding it is drawn live, and so is everybody the reader may name.
  const other = officerOptionsForPost({
    officers: [reader, colleague],
    capacity: "ASSISTANT_DIRECTOR",
    holder: heldBy(colleague.id, colleague),
    readerId: reader.id
  });
  expect(other.find((row) => row.value === colleague.id)?.disabled).toBe(false);
  expect(other.map((row) => row.value), "and the reader is still never offered").not.toContain(reader.id);
});

const SELF_RELEASE_SCREENS: ReadonlyArray<{ screen: string; path: string[]; must: string[] }> = [
  {
    screen: "the inspectors panel — the reader's own row stays ticked and switched off",
    path: ["components", "settings", "DesignWorkshopInspectorsPanel.tsx"],
    must: [
      'row.userId === readerId ? { disabled: true, hint: "you — another administrator has to take you off" } : undefined',
      "const readerInspects = readerId !== null && baseline.includes(readerId);",
      "if (readerId && baseline.includes(readerId) && !selected.includes(readerId)) {",
      'setSaveError(selfReleaseRefused("INSPECTION"));',
      '{selfReleaseRefusal("INSPECTION")} Your own name stays ticked;',
      "readerInspects ? selfNoteId : null"
    ]
  },
  {
    screen: "Workshop oversight — the reader's own Assistant or Regional Director slot is switched off",
    path: ["app", "(protected)", "officers", "page.tsx"],
    must: [
      "const own = Boolean(user?.id) && row?.userId === user?.id;",
      "disabled={saving || own}",
      "describedBy={own ? ownNoteId : undefined}",
      "{selfReleaseRefusal(capacity)}",
      "setRefusal(selfReleaseRefused(capacity));"
    ]
  }
];

for (const { screen, path, must } of SELF_RELEASE_SCREENS) {
  test(`nobody releases themselves on ${screen}`, () => {
    const source = read(...path);
    for (const line of must) {
      expect(source, `${path.join("/")} has lost: ${line}`).toContain(line);
    }
  });
}

test("a disabled row is neither toggled nor swept by Select all — which is what keeps the reader's own tick", () => {
  const select = read("components", "ui", "SearchableSelect.tsx");
  expect(select).toContain("if (!option || option.disabled) return;");
  expect(select).toContain("const bulkRows = useMemo(() => filtered.filter((option) => !option.disabled), [filtered]);");
});

/** Every web source under the three trees whose text matches `pattern`, repo-relative and sorted. */
function callersOf(pattern: RegExp, except: readonly string[] = []): string[] {
  const found: string[] = [];
  for (const tree of ["app", "components", "lib"]) {
    for (const file of webSources(join(FRONTEND, tree))) {
      const where = file.slice(FRONTEND.length + 1).replace(/\\/g, "/");
      if (!except.includes(where) && pattern.test(readFileSync(file, "utf8"))) found.push(where);
    }
  }
  return found.sort();
}

test("every web call that adds to a workshop or edits it from elsewhere comes from a held door", () => {
  /*
    THE SECOND REGISTER, READ OFF THE TREE as the first one is: a new screen that uploads a file,
    re-queues a job, edits from the review queue, files or discards an unfiled record, or attaches a
    questionnaire fails here until it is added — and adding it is the moment to give it the hold, or
    to write down why it has none. `lib/` declarations are the calls themselves, not callers of them.
  */

  // A FILE INTO THE REPOSITORY. Both upload entry points end at `/media/complete`, which refuses a post
  // holder a file filed under, tagged to, or hanging off a record filed under their workshop.
  expect(callersOf(/\buploadMediaBatch\(/, ["lib/media.ts"])).toEqual(
    [
      "app/(protected)/crafts/page.tsx", // a craft's files: a craft is never filed under a design workshop
      "app/(protected)/media/page.tsx", // `uploadHold`: the workshop chosen and the record linked
      "app/(protected)/questionnaire/page.tsx", // an interview's files: `filing.saveHeld`
      "app/(protected)/workshops/page.tsx", // a crafts workshop's files: never filed under a design workshop
      "components/designers/DesignerProfileForm.tsx", // a designer's own profile: belongs to no workshop
      "components/designworkshop/FieldInput.tsx", // a stage's capture: the stage form's own lock
      "components/designworkshop/RichTextEditor.tsx", // a picture in a stage's prose: the same lock
      "components/forms/ArtisanForm.tsx", // `filing.saveHeld`
      "components/forms/ProductForm.tsx", // `filing.saveHeld`
      "components/forms/ToolForm.tsx", // `filing.saveHeld`
      "lib/designWorkshopStore.ts", // the draft drain: replays only what a held stage never took
      "lib/offline.ts" // the outbox drain: replays only what a held save never queued
    ].sort()
  );
  expect(callersOf(/\buploadMediaFile\(/, ["lib/media.ts"])).toEqual(
    // Each inside its record form's save, which `filing.saveHeld` holds.
    ["components/forms/ProcessForm.tsx", "components/forms/ProductForm.tsx", "components/forms/ToolForm.tsx"].sort()
  );

  // A FAILED JOB RE-QUEUED — its transcript is written back onto the file later.
  expect(callersOf(/\bretryMediaProcessingJob\(/, ["lib/media.ts"])).toEqual(["components/media/MediaJobsPanel.tsx"]);

  // A RECORD OR FILE EDITED FROM THE REVIEW QUEUE.
  expect(callersOf(/\/review\/\$\{[^}]+\}\/\$\{[^}]+\}\/edit\b/)).toEqual(["components/review/ReviewEditPanel.tsx"]);

  // THE UNFILED-RECORDS REPORT'S WRITES — the single filing and discard, and the bulk filing. Their
  // refusals are printed as written (the two tests above); the plain read of the report is not a write.
  expect(callersOf(/apiFetch(?:<[^>]*>)?\(\s*["'`]\/workshops\/unmapped\//)).toEqual(
    ["components/settings/WorkshopMappingPanel.tsx", "components/settings/unfiledRecords.ts"].sort()
  );
  expect(callersOf(/\b(?:fileUnfiledRecord|discardUnfiledRecord)\(/, ["components/settings/unfiledRecords.ts"])).toEqual([
    "components/settings/UnfiledRecordCard.tsx"
  ]);

  // A QUESTIONNAIRE ATTACHED TO A WORKSHOP AS IT IS MADE — created, uploaded or reused into it.
  expect(callersOf(/endpoint:\s*"\/questionnaires",/)).toEqual(["app/(protected)/questionnaires/page.tsx"]);
  expect(callersOf(/\bcreateQuestionnaire\(/, ["lib/questionnaireForms.ts"]), "the queued create is the one door").toEqual([]);
  expect(callersOf(/\buploadQuestionnaire\(/, ["lib/questionnaireForms.ts"])).toEqual(["components/questionnaires/UploadDialog.tsx"]);
  expect(callersOf(/\breuseQuestionnaire\(/, ["lib/questionnaireForms.ts"])).toEqual(["components/questionnaires/ReuseDialog.tsx"]);
  // And an existing form MOVED into one, from its own page: held for the workshop it is stored under
  // (`mayEdit`), and a held destination's 403 prints in the page's banner as written.
  expect(callersOf(/patchQuestionnaire\([^)]*designWorkshopId/)).toEqual(["app/(protected)/questionnaires/[id]/page.tsx"]);

  // A WORKSHOP'S STAFFING SAVED — each from the one screen that keeps the reader's own post ticked and
  // switched off (the self-release rows above). A new caller owes the same, or the 409 is its warning.
  expect(callersOf(/\bputDesignWorkshopInspectors\(/, ["lib/designWorkshopInspections.ts"])).toEqual([
    "components/settings/DesignWorkshopInspectorsPanel.tsx"
  ]);
  expect(callersOf(/\bputWorkshopOversight\(/, ["app/(protected)/officers/oversight.ts"])).toEqual([
    "app/(protected)/officers/page.tsx"
  ]);
});
