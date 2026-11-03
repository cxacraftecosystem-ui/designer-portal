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

import { installSessionCookie } from "./support/fakeSessionCookie";

const FRONTEND = join(__dirname, "..");
const read = (...parts: string[]) => readFileSync(join(FRONTEND, ...parts), "utf8");
const readBackend = (...parts: string[]) => readFileSync(join(FRONTEND, "..", "backend", ...parts), "utf8");

const realFetch = globalThis.fetch;

const account = (role: UserRole, id = `u-${role.toLowerCase()}`): User =>
  ({ id, email: `${id}@example.org`, name: role, role }) as User;

function installFakeWindow() {
  installSessionCookie("a-perfectly-good-token");
  (globalThis as Record<string, unknown>).window = {
    location: { pathname: "/design-workshops/w1", protocol: "http:", assign: () => undefined, replace: () => undefined }
  };
}

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

test("an account that may not appoint is never asked, and costs no request", async () => {
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
  installFakeWindow();
  serveStaffing("fail");
  expect(await readHeldWorkshopPosts("w1", account("ADMIN"))).toEqual([]);
});

test("the hook resolves a device-minted address to the repository's id, and drops a stale answer", () => {
  const hook = read("components", "designworkshop", "HeldPostNotice.tsx");
  expect(hook).toContain('import { readHeldWorkshopPosts } from "@/app/(protected)/officers/oversight";');
  expect(hook).toContain("heldPostEditRefusal(answer.posts)");
  expect(hook).toContain("isLocalWorkshopId(workshopId)");
  expect(hook).toContain("(await loadDraft(workshopId).catch(() => null))?.remoteId ?? null");
  expect(hook).toContain("answer && answer.workshopId === workshopId && answer.userId === user?.id");
});

test("three states: still asking holds the writes, none known releases them, and only an appointer is ever asked", () => {
  expect(writesHeld(undefined), "still asking").toBe(true);
  expect(writesHeld("You are this workshop's inspector, …"), "a post held").toBe(true);
  expect(writesHeld(null), "none known").toBe(false);

  expect(heldPostReason(undefined)).toBe(HELD_POST_PENDING);
  expect(heldPostReason(null)).toBeNull();
  expect(heldPostReason("the refusal")).toBe("the refusal");

  const hook = read("components", "designworkshop", "HeldPostNotice.tsx");
  expect(hook).toContain("const asks = Boolean(workshopId && user && canAssignWorkshopOversight(user));");
  expect(hook).toContain("if (!asks) return null;");
  expect(hook).toMatch(/\?\s*heldPostEditRefusal\(answer\.posts\)\s*:\s*undefined;/);
  expect(hook).toContain("const mine = await myWorkshopPosts(user);");
  expect(hook).toContain("posts = mine ? (mine.get(serverId) ?? []) : await readHeldWorkshopPosts(serverId, user);");
  expect(hook).toMatch(/\} catch \{\s*posts = \[\];\s*\}/);
});

test("the notice is said into a live region that exists before the answer arrives", () => {
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

const SCREENS: ReadonlyArray<{ screen: string; path: string[]; must: string[]; mustNot?: string[] }> = [
  {
    screen: "the stage index — Update details, the status buttons and the consent row",
    path: ["app", "(protected)", "design-workshops", "[id]", "page.tsx"],
    must: [
      "const postRefusal = useHeldPostRefusal(id);",
      "<HeldPostNotice refusal={postRefusal} />",
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
      "const locked = saving || writesHeld(postRefusal) || frozenReason !== null;",
      "<HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />",
      "if (!stage || writesHeld(postRefusal) || frozenReason !== null) return;",
      "aria-describedby={postRefusal ? heldNoticeId : undefined}"
    ],
    mustNot: ["disabled={saving}", "Boolean(postRefusal)"]
  },
  {
    screen: "Update workshop details",
    path: ["app", "(protected)", "design-workshops", "[id]", "edit", "page.tsx"],
    must: [
      'useHeldPostRefusal(state?.kind === "loaded" ? state.record.id : null)',
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
  const page = read("app", "(protected)", "officers", "page.tsx");
  const mounts = page.slice(page.indexOf("<DesignerPanel"), page.indexOf("<ArtisanListPanel"));
  const oversight = mounts.slice(mounts.indexOf("<OversightPanel"), mounts.indexOf("<DesignWorkshopInspectorsPanel"));
  expect(oversight, "the officers panel stays live").not.toContain("readOnlyReason");
  expect(mounts.slice(mounts.indexOf("<DesignWorkshopInspectorsPanel")), "and so does the inspectors panel").not.toContain(
    "readOnlyReason"
  );
});

test("a CREATE form offers the reader as a designer, because the create doors accept it", () => {
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
  expect(readBackend("app", "services", "design_workshop_posts.py")).toContain("nobody appoints themselves to a");

  const officers = read("app", "(protected)", "officers", "page.tsx");
  const designerPanel = officers.slice(officers.indexOf("function DesignerPanel("), officers.indexOf("function OversightPanel("));
  expect(designerPanel).toContain("excludeUserIds={user ? [user.id] : undefined}");

  const viewers = read("components", "settings", "DesignWorkshopViewersPanel.tsx");
  expect(viewers).toContain("(eligible ?? []).filter((person) => person.id !== readerId)");
});

test("the reader's own row is never 'no longer eligible': they are missing from the list because nobody names themselves", () => {
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

const REFUSAL = "You are this workshop's inspector, so you can read it but not change it.";

test("a stored workshop holds the record — Save, Delete and the picker; a chosen one holds Save and leaves the picker live", () => {
  const stored = recordFilingHold(REFUSAL, null, "artisan");
  expect(stored.recordHeld).toBe(true);
  expect(stored.saveHeld).toBe(true);
  expect(stored.notice).toBe(
    `This artisan belongs to a workshop you oversee, so it can't be changed or deleted. ${REFUSAL}`
  );

  const chosen = recordFilingHold(null, REFUSAL, "product");
  expect(chosen.recordHeld, "the picker stays live: choosing another workshop is the way out").toBe(false);
  expect(chosen.saveHeld).toBe(true);
  expect(chosen.notice).toBe(
    `You oversee that workshop, so this product can't be filed there — choose another. ${REFUSAL}`
  );

  expect(recordFilingHold(undefined, null, "tool")).toEqual({
    recordHeld: true,
    saveHeld: true,
    notice: undefined,
    stored: undefined
  });
  expect(recordFilingHold(null, undefined, "tool")).toEqual({ recordHeld: false, saveHeld: true, notice: undefined, stored: null });
  expect(recordFilingHold(REFUSAL, REFUSAL, "process").notice).toMatch(/^This process belongs to a workshop you oversee/);
  expect(recordFilingHold(null, null, "interview")).toEqual({ recordHeld: false, saveHeld: false, notice: null, stored: null });

  expect(stored.stored).toBe(REFUSAL);
  expect(chosen.stored, "a chosen workshop holds no file that is already attached").toBeNull();
});

const RECORD_FORMS: ReadonlyArray<{ form: string; path: string[]; stored: string; noun: string }> = [
  { form: "the artisan form", path: ["components", "forms", "ArtisanForm.tsx"], stored: "initial?.designWorkshopId", noun: "artisan" },
  { form: "the product form", path: ["components", "forms", "ProductForm.tsx"], stored: "initial?.designWorkshopId", noun: "product" },
  { form: "the process form", path: ["components", "forms", "ProcessForm.tsx"], stored: "initial?.designWorkshopId", noun: "process" },
  { form: "the tool form", path: ["components", "forms", "ToolForm.tsx"], stored: "initial?.designWorkshopId", noun: "tool" }
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
  const page = read("app", "(protected)", "questionnaires", "[id]", "page.tsx");
  expect(page).toContain("const owns = canEditOwnOrAdmin(user, form?.ownerId);");
  expect(page).toContain("const attachedHold = useHeldPostRefusal(form?.designWorkshopId ?? null);");
  expect(page).toContain("const mayEdit = owns && attachedHold === null;");
  expect(page).toContain(
    "Read-only: attached to a workshop you oversee."
  );
  expect(page).toMatch(/\{!owns \? \(\s*<div className="mb-4 flex items-start gap-2/);
  expect(page).toMatch(/\{owns \? \(\s*<button type="button" className="field-button-secondary" onClick=\{download\}/);
  expect(page).toContain("() => patchQuestionnaire(id, { designWorkshopId: designWorkshopId || null })");
  expect(page, "a refused attach prints the server's sentence").toContain(
    "setError(err instanceof Error ? err.message : failure);"
  );
});

test("a file's own columns name its workshops — the filing and the stage tag, compared as the server does", () => {
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
  expect(mediaWorkshopIds({ linkedRecordType: "artisan", linkedRecordId: "a1" })).toEqual([]);
  expect(mediaWorkshopIds({ designWorkshopId: null, linkedRecordType: null, linkedRecordId: null })).toEqual([]);
});

test("one file's hold is the server's rule in three states: refused if any workshop is held", () => {
  expect(mediaWriteHold([]), "a file that names no workshop").toBeNull();
  expect(mediaWriteHold([null, null])).toBeNull();
  expect(mediaWriteHold([null, undefined]), "still asking about one of them").toBeUndefined();
  expect(mediaWriteHold([undefined, REFUSAL]), "a held post is known, whatever is still out").toBe(REFUSAL);
  expect(mediaWriteHold([REFUSAL, null])).toBe(REFUSAL);

  expect(mediaHeldReason(REFUSAL)).toBe(MEDIA_HELD_REASON);
  expect(mediaHeldReason(undefined)).toBe(HELD_POST_PENDING);
  expect(mediaHeldReason(null)).toBeNull();
});

test("a list says the server's sentence once above itself, and marks each held file in words", () => {
  const other = "You are this workshop's Regional Director, so you can read it but not change it.";
  const notice = mediaListNotice([REFUSAL, null, REFUSAL, other, undefined], "deleted or re-transcribed");
  expect(notice).toContain(`Files marked “${MEDIA_HELD_LABEL}”`);
  expect(notice).toContain("can't be deleted or re-transcribed.");
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
  expect(lists).toContain('const wanted = appoints ? [...new Set(workshopIds.filter(Boolean))].sort().join("\\n") : "";');
  expect(lists).toContain("const missing = wanted.split(\"\\n\").filter((id) => !posts?.has(id));");
  expect(lists).toContain("known.userId === user.id ? known.posts : null");
  expect(lists).toContain("if (!appoints) return null;");
  expect(lists).toMatch(/return held \? heldPostEditRefusal\(held\) : undefined;/);
  expect((hook.match(/await heldPostsOn\(/g) ?? []).length, "both hooks").toBe(2);
});

const MEDIA_SCREENS: ReadonlyArray<{ screen: string; path: string[]; must: string[]; mustNot?: string[] }> = [
  {
    screen: "Miscellaneous Media — Delete and Transcribe now on each row",
    path: ["app", "(protected)", "media", "page.tsx"],
    must: [
      "const heldFor = useHeldPostRefusals([",
      "...(data?.items ?? []).filter(writable).flatMap(mediaWorkshopIds),",
      "const holdOf = (item: MediaFile): HeldPostRefusal => (writable(item) ? heldFor(mediaWorkshopIds(item)) : null);",
      "disabled={transcribingId === item.id || writesHeld(hold)}",
      "onClick={() => remove(item)}",
      "disabled={writesHeld(hold)}",
      "aria-describedby={typeof hold === \"string\" ? heldNoticeId : undefined}",
      'refusal={mediaListNotice((data?.items ?? []).map(holdOf), "deleted or re-transcribed")}',
      "{MEDIA_HELD_LABEL}",
      'setError(err instanceof Error ? err.message : "Unable to remove media file");',
      'setError(err instanceof Error ? err.message : "Unable to transcribe this file right now");'
    ],
    mustNot: ["onClick={() => remove(item.id)}"]
  },
  {
    screen: "a record's attached files — the ✕ and the re-run transcript on each",
    path: ["components", "media", "ExistingMedia.tsx"],
    must: [
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
      "the photo is still stored. ${error.message}`"
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
  expect((reader.match(/disabled=\{disabled \|\| busy !== null\}/g) ?? []).length, "keep and delete").toBe(2);
  const field = read("components", "designworkshop", "FieldInput.tsx");
  expect(field.slice(field.indexOf("<IdentityCardReader")).slice(0, 400)).toContain("disabled={disabled}");
  const stage = read("app", "(protected)", "design-workshops", "[id]", "stages", "[stageKey]", "page.tsx");
  expect(stage).toContain("const locked = saving || writesHeld(postRefusal) || frozenReason !== null;");
  expect(stage.slice(stage.indexOf("<MemoEntityForm")).slice(0, 600)).toContain("disabled={locked}");
});

test("every record form hands its attached files the stored workshop's answer", () => {
  for (const form of ["ArtisanForm.tsx", "ProductForm.tsx", "ToolForm.tsx"]) {
    expect(read("components", "forms", form), form).toMatch(/<ExistingMedia linkedRecordType="[a-z]+" linkedRecordId=\{initial\.id\} recordHold=\{filing\.stored\} \/>/);
  }
  expect((read("components", "forms", "ProcessForm.tsx").match(/recordHold=\{filing\.stored\}/g) ?? []).length, "both lists").toBe(2);
  expect(read("app", "(protected)", "crafts", "page.tsx")).toContain("recordHold={null}");
  expect(read("app", "(protected)", "workshops", "page.tsx")).toContain("recordHold={null}");
});

function webSources(dir: string, found: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) webSources(full, found);
    else if (entry.name.endsWith(".ts") || entry.name.endsWith(".tsx")) found.push(full);
  }
  return found;
}

test("the web's one transcript refine control is held like the controls above", () => {
  const offenders: string[] = [];
  for (const tree of ["app", "components", "lib"]) {
    for (const file of webSources(join(FRONTEND, tree))) {
      const source = readFileSync(file, "utf8");
      if (/refine-transcript/.test(source) || /\/media\/\$\{[^}]+\}\/transcript\b/.test(source)) {
        offenders.push(file.slice(FRONTEND.length + 1).replace(/\\/g, "/"));
      }
    }
  }
  expect(offenders.sort()).toEqual(["components/media/TranscriptRefine.tsx", "lib/transcriptRefine.ts"]);
  const block = read("components", "media", "TranscriptBlock.tsx");
  expect(block).toContain("readOnly={props.readOnly || Boolean(props.readOnlyReason)}");
  expect(read("components", "media", "TranscriptRefine.tsx")).toContain("if (readOnly || !mayRefine(user, media)) return null;");
});

test("every web call that changes a stored file comes from one of the held screens above", () => {
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

test("the /media upload is held on the workshop chosen and on what the files hang off — the choice first", () => {
  expect(mediaUploadHold(null, null), "nothing held, nothing said").toBeNull();
  expect(mediaUploadHold(undefined, null), "still asking about the workshop chosen").toBeUndefined();
  expect(mediaUploadHold(null, undefined), "still asking about the linked record's").toBeUndefined();
  const chosen = mediaUploadHold(REFUSAL, null);
  expect(chosen).toBe(
    `You oversee that workshop, so these files can't be filed under it — choose another, or none. ${REFUSAL}`
  );
  expect(mediaUploadHold(null, REFUSAL)).toBe(
    `That record belongs to a workshop you oversee, so these files can't be attached to it — choose another. ${REFUSAL}`
  );
  expect(mediaUploadHold(REFUSAL, undefined)).toBe(chosen);
  expect(mediaUploadHold(undefined, REFUSAL)).toMatch(/^That record belongs to a workshop you oversee/);
  expect(mediaUploadHold(REFUSAL, REFUSAL)).toBe(chosen);
});

test("a questionnaire is held on the workshop it would be attached to, and the picker stays the way out", () => {
  expect(attachHold(null, "this questionnaire")).toBeNull();
  expect(attachHold(undefined, "the copy"), "still asking").toBeUndefined();
  expect(attachHold(REFUSAL, "this questionnaire")).toBe(
    `You oversee that workshop, so this questionnaire can't be attached to it — choose another, or none. ${REFUSAL}`
  );
  expect(attachHold(REFUSAL, "the copy")).toMatch(
    /^You oversee that workshop, so the copy can't be attached to it/
  );
});

test("the review queue's edit is held on the workshop its record or file names, and keeps the three decisions", () => {
  expect(reviewRecordWorkshopIds("artisan", { designWorkshopId: "w1" })).toEqual(["w1"]);
  expect(reviewRecordWorkshopIds("questionnaire", { designWorkshopId: " w2 " })).toEqual(["w2"]);
  expect(reviewRecordWorkshopIds("product", { designWorkshopId: null })).toEqual([]);
  expect(reviewRecordWorkshopIds("workshop", { title: "Bagru" }), "a crafts workshop names none").toEqual([]);
  expect(reviewRecordWorkshopIds("artisan", null)).toEqual([]);
  expect(
    reviewRecordWorkshopIds("media", { designWorkshopId: "w1", linkedRecordType: "designWorkshop", linkedRecordId: "w2" })
  ).toEqual(["w1", "w2"]);
  expect(reviewRecordWorkshopIds("media", { linkedRecordType: "artisan", linkedRecordId: "a1" })).toEqual([]);

  expect(reviewEditHold(null, "artisan")).toBeNull();
  expect(reviewEditHold(undefined, "artisan"), "still asking").toBeUndefined();
  expect(reviewEditHold(REFUSAL, "artisan")).toBe(
    `This artisan belongs to a workshop you oversee, so its fields can't be edited — you can still approve, reject or send it back. ${REFUSAL}`
  );
  expect(reviewEditHold(REFUSAL, "questionnaire")).toMatch(/^This interview belongs to a workshop you oversee/);
  expect(reviewEditHold(REFUSAL, "media")).toMatch(/^This file belongs to a workshop you oversee, so its fields/);
});

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
      'setError(readableError(err, "Unable to save this edit"));'
    ],
    mustNot: ["disabled={busy || changedKeys.length === 0"]
  },
  {
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
      "if (writesHeld(uploadHold)) return;",
      "disabled={uploading || selectedFiles.length === 0 || !linkedType || writesHeld(uploadHold)}",
      'aria-describedby={typeof uploadHold === "string" ? uploadNoticeId : undefined}',
      '<HeldPostNotice refusal={uploadHold} id={uploadNoticeId} className="" sayPending />',
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
      "setHeldBack(heldBackSentence(result));",
      "{applied !== null && heldBack ? (",
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
  installFakeWindow();
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

function serverReturnTemplate(file: string[], name: string): string {
  const body = readBackend(...file).match(new RegExp(`def ${name}\\([\\s\\S]*?return \\(([\\s\\S]*?)\\n\\s*\\)`));
  expect(body, `${name} could not be read in backend/${file.join("/")}`).toBeTruthy();
  return [...body![1].matchAll(/\bf?"((?:[^"\\]|\\.)*)"/g)].map((piece) => piece[1]).join("");
}

function serverConstant(file: string[], name: string): string {
  const body = readBackend(...file).match(new RegExp(`^${name} = \\(([\\s\\S]*?)\\n\\)`, "m"));
  expect(body, `${name} could not be read in backend/${file.join("/")}`).toBeTruthy();
  return [...body![1].matchAll(/"((?:[^"\\]|\\.)*)"/g)].map((piece) => piece[1]).join("");
}

test("the bulk filing says what it left alone for the reader's post — the server's sentence — and never invents a count", () => {
  expect(heldBackOf({ buckets: [], totals: { heldBack: 3 } }), "the server's total").toBe(3);
  expect(heldBackOf({ buckets: [{ heldBack: 2 }, { heldBack: null }, {}], totals: {} }), "else the buckets'").toBe(2);
  expect(heldBackOf({ buckets: [{}, { heldBack: null }], totals: {} })).toBeNull();

  const said = "2 records that belong to a design workshop you inspect or supervise were left as they were.";
  expect(heldBackSentence({ buckets: [], totals: { heldBack: 2 }, heldBackDetail: said })).toBe(said);
  expect(heldBackSentence({ buckets: [], totals: { heldBack: 3 }, heldBackDetail: null })).toBe(heldBackNotice(3));
  expect(heldBackSentence({ buckets: [], totals: { heldBack: 0 }, heldBackDetail: null }), "none held").toBeNull();
  expect(heldBackSentence({ buckets: [{}], totals: {} }), "a preview, or an older server").toBeNull();

  const one = serverConstant(["app", "services", "workshop_inference.py"], "HELD_BACK_ONE_DETAIL");
  const several = serverConstant(["app", "services", "workshop_inference.py"], "HELD_BACK_DETAIL");
  expect(several, "HELD_BACK_DETAIL no longer counts the rows").toContain("{count}");
  expect(heldBackNotice(3)).toBe(several.replace("{count}", "3"));
  expect(heldBackNotice(1)).toBe(one);
  expect(heldBackNotice(null)).toBeNull();
  expect(heldBackNotice(0)).toBeNull();
});

test("nobody takes themselves off a post: the server's own sentence names the post and who may act", () => {
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

  expect(callersOf(/\buploadMediaBatch\(/, ["lib/media.ts"])).toEqual(
    [
      "app/(protected)/crafts/page.tsx",
      "app/(protected)/media/page.tsx",
      "app/(protected)/workshops/page.tsx",
      "components/designers/DesignerProfileForm.tsx",
      "components/designworkshop/FieldInput.tsx",
      "components/designworkshop/RichTextEditor.tsx",
      "components/forms/ArtisanForm.tsx",
      "components/forms/ProductForm.tsx",
      "components/forms/ToolForm.tsx",
      "lib/designWorkshopStore.ts",
      "lib/offline.ts"
    ].sort()
  );
  expect(callersOf(/\buploadMediaFile\(/, ["lib/media.ts"])).toEqual(
    ["components/forms/ProcessForm.tsx", "components/forms/ProductForm.tsx", "components/forms/ToolForm.tsx"].sort()
  );

  expect(callersOf(/\bretryMediaProcessingJob\(/, ["lib/media.ts"])).toEqual(["components/media/MediaJobsPanel.tsx"]);

  expect(callersOf(/\/review\/\$\{[^}]+\}\/\$\{[^}]+\}\/edit\b/)).toEqual(["components/review/ReviewEditPanel.tsx"]);

  expect(callersOf(/apiFetch(?:<[^>]*>)?\(\s*["'`]\/workshops\/unmapped\//)).toEqual(
    ["components/settings/WorkshopMappingPanel.tsx", "components/settings/unfiledRecords.ts"].sort()
  );
  expect(callersOf(/\b(?:fileUnfiledRecord|discardUnfiledRecord)\(/, ["components/settings/unfiledRecords.ts"])).toEqual([
    "components/settings/UnfiledRecordCard.tsx"
  ]);

  expect(callersOf(/endpoint:\s*"\/questionnaires",/)).toEqual(["app/(protected)/questionnaires/page.tsx"]);
  expect(callersOf(/\bcreateQuestionnaire\(/, ["lib/questionnaireForms.ts"]), "the queued create is the one door into a workshop").toEqual([
    "components/questionnaires/BundleControls.tsx"
  ]);
  expect(callersOf(/\buploadQuestionnaire\(/, ["lib/questionnaireForms.ts"])).toEqual(["components/questionnaires/UploadDialog.tsx"]);
  expect(callersOf(/\breuseQuestionnaire\(/, ["lib/questionnaireForms.ts"])).toEqual(["components/questionnaires/ReuseDialog.tsx"]);
  expect(callersOf(/patchQuestionnaire\([^)]*designWorkshopId/)).toEqual(["app/(protected)/questionnaires/[id]/page.tsx"]);

  expect(callersOf(/\bputDesignWorkshopInspectors\(/, ["lib/designWorkshopInspections.ts"])).toEqual([
    "components/settings/DesignWorkshopInspectorsPanel.tsx"
  ]);
  expect(callersOf(/\bputWorkshopOversight\(/, ["app/(protected)/officers/oversight.ts"])).toEqual([
    "app/(protected)/officers/page.tsx"
  ]);
});
