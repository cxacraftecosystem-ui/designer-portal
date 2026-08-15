import { expect, test } from "@playwright/test";

import {
  reportServerId,
  reportTemplateId
} from "@/app/(protected)/design-workshops/[id]/report/reportTarget";

/**
 * THE TWO THINGS THE REPORT SCREENS MUST RESOLVE BEFORE THEY ASK THE SERVER ANYTHING.
 *
 * ── DEFECT 1: THE ROUTE PARAM IS NOT ALWAYS A SERVER ID ──────────────────────────────────────
 *
 * A workshop created with no signal is banked in IndexedDB under a `dwlocal-…` id, and the list page
 * navigates the browser to `/design-workshops/dwlocal-…` — a URL the application chose itself. The
 * report page and the report history carried that param straight into five server calls
 * (`getDesignWorkshop`, the preview, the download, the stage-20 accent save and the settings panel's
 * own PUT, plus `fetchDesignWorkshopReportHistory`). `load_workshop_or_404` is a `find_unique` on the
 * primary key, so every one of them came back 404 "Record not found" — printed in a red banner over
 * a workshop that the stage index, all 22 stage forms, readiness, Cards & tags and custom sections
 * open perfectly well from the SAME URL, because each of those resolves `remoteId` first. Once the
 * draft had synced the report was genuinely generatable and the two screens still said the record
 * did not exist: the deliverable of the whole product, unreachable, with the one sentence that sends
 * a designer hunting for lost fieldwork.
 *
 * ── DEFECT 2: THE HEADER COLUMN IS THE SERVER'S LAST RESORT, NOT ITS FIRST ───────────────────
 *
 * `resolve_template_id` reads three candidates in order — the request, stage 20's saved answer, then
 * `DesignWorkshop.templateId`, the column the create form defaulted to `DCH_STANDARD`. The page
 * seeded its dropdown from that column and then SENT it as `requested` on every preview and every
 * download, which returns on the first rung and skips the stage answer entirely. Nothing promotes
 * stage 20 into the column (`PROMOTED_COLUMNS` maps `workshopSetup.*` only), so a designer who
 * answered the REQUIRED 'Report template' field with the photo catalogue got the DCH document from
 * this browser and the catalogue from the handset — one record, two documents, and a required answer
 * inert on the web.
 *
 * WHY THE ASSERTIONS ARE ON THESE FUNCTIONS AND NOT ON A BROWSER. Both are decisions taken from two
 * values with no I/O in them, and both were wrong in the same way: the value nearest to hand was
 * used instead of the value the server resolves. Driving them through a dev server and a live API
 * would test the plumbing — and would need a workshop that is offline-created, synced, and holds a
 * stage-20 answer disagreeing with its own header column, which is a fixture nobody would maintain.
 * These two functions exist so each rule has ONE definition that both screens import; these are its
 * cases.
 */

/* ────────────────────────────────────────────────────────────────────────────
 * 1. Which workshop this is on the server
 * ──────────────────────────────────────────────────────────────────────────── */

/** A server id, in the shape the API really produces (cuid), so nothing here matches by accident. */
const SERVER_ID = "cmsik2jg8000eh8xc1lcy661a";
/** The prefix `createLocalDraft` mints and `isLocalWorkshopId` recognises. */
const LOCAL_ID = "dwlocal-8f2c1d4a9b";

test("a synced offline-created workshop resolves to the id the server knows it by", () => {
  // THE DEFECT. This is the tab the create pushed: the URL is the local id, the draft has since been
  // created on the server and carries its id, and every request the page makes must go out under
  // THAT id. Passing the route param is the 404.
  expect(reportServerId(LOCAL_ID, { remoteId: SERVER_ID })).toBe(SERVER_ID);
  expect(reportServerId(LOCAL_ID, { remoteId: SERVER_ID })).not.toBe(LOCAL_ID);
});

test("a workshop that has never reached the repository resolves to nothing at all", () => {
  // NOT A FAILURE AND NOT AN ERROR — a state with a date on it. There is no server record yet, so
  // the honest answer is null and the screens render "this workshop has not reached the repository
  // yet" instead of firing a request whose 404 says the workshop does not exist.
  expect(reportServerId(LOCAL_ID, { remoteId: null })).toBeNull();

  // And the same when this browser holds no draft for it at all. `loadDraft` is owner-filtered, so a
  // local id opened after signing in as somebody else lands here: still nothing the server can be
  // asked about under that id, and a request would be a 404 either way.
  expect(reportServerId(LOCAL_ID, null)).toBeNull();
});

test("an ordinary server-id URL is unchanged, with or without a local draft", () => {
  // THE WITNESS THAT THIS DID NOT MAKE EVERY WORKSHOP LOCAL-ONLY. The overwhelmingly common case is
  // a workshop opened from the list page by its server id; it must resolve to itself whether or not
  // this laptop has ever cached a draft of it.
  expect(reportServerId(SERVER_ID, null)).toBe(SERVER_ID);
  expect(reportServerId(SERVER_ID, { remoteId: SERVER_ID })).toBe(SERVER_ID);

  // A draft that has not synced yet cannot make a server id unreachable: the param IS a server id,
  // which is only possible because the record exists. Returning null here would black out a report
  // for a workshop the server holds — the mirror image of the defect above.
  expect(reportServerId(SERVER_ID, { remoteId: null })).toBe(SERVER_ID);
});

/* ────────────────────────────────────────────────────────────────────────────
 * 2. Which template this screen opens on
 * ──────────────────────────────────────────────────────────────────────────── */

/** What the create form defaults `DesignWorkshop.templateId` to on the day the workshop is made. */
const COLUMN = "DCH_STANDARD";
/** What the designer later answered stage 20's required 'Report template' question with. */
const ANSWERED = "PHOTO_CATALOGUE";

test("stage 20's answer wins over the workshop's header column", () => {
  // THE DEFECT. The page took the column alone, so the dropdown read "DCH standard workshop report"
  // over a workshop whose required stage-20 answer said photo catalogue — and because the page then
  // sent that value as `requested`, the .docx agreed with the dropdown rather than with the record.
  expect(reportTemplateId(ANSWERED, COLUMN)).toBe(ANSWERED);
});

test("the header column is still the fallback when stage 20 has not been answered", () => {
  // `settingText` returns "" for an unanswered field, and the column is then what the server itself
  // would resolve — the third rung of `resolve_template_id`. Dropping it would leave the dropdown
  // blank on every workshop whose stage 20 is untouched, which is most of them while the fieldwork
  // is still being done.
  expect(reportTemplateId("", COLUMN)).toBe(COLUMN);
});

test("nothing anywhere resolves to nothing, rather than to a guess", () => {
  // Neither rung has a value: the screen has no template to name and must not invent one from the
  // template LIST — "the first template the API happened to return" is not this workshop's template,
  // and it would be sent on a download as though the designer had chosen it.
  expect(reportTemplateId("", "")).toBe("");
});
