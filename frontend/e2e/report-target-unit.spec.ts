import { expect, test } from "@playwright/test";

import {
  reportServerId,
  reportTemplateId
} from "@/app/(protected)/design-workshops/[id]/report/reportTarget";

const SERVER_ID = "cmsik2jg8000eh8xc1lcy661a";
const LOCAL_ID = "dwlocal-8f2c1d4a9b";

test("a synced offline-created workshop resolves to the id the server knows it by", () => {
  expect(reportServerId(LOCAL_ID, { remoteId: SERVER_ID })).toBe(SERVER_ID);
  expect(reportServerId(LOCAL_ID, { remoteId: SERVER_ID })).not.toBe(LOCAL_ID);
});

test("a workshop that has never reached the repository resolves to nothing at all", () => {
  expect(reportServerId(LOCAL_ID, { remoteId: null })).toBeNull();

  expect(reportServerId(LOCAL_ID, null)).toBeNull();
});

test("an ordinary server-id URL is unchanged, with or without a local draft", () => {
  expect(reportServerId(SERVER_ID, null)).toBe(SERVER_ID);
  expect(reportServerId(SERVER_ID, { remoteId: SERVER_ID })).toBe(SERVER_ID);

  expect(reportServerId(SERVER_ID, { remoteId: null })).toBe(SERVER_ID);
});

const COLUMN = "DCH_STANDARD";
const ANSWERED = "PHOTO_CATALOGUE";

test("stage 20's answer wins over the workshop's header column", () => {
  expect(reportTemplateId(ANSWERED, COLUMN)).toBe(ANSWERED);
});

test("the header column is still the fallback when stage 20 has not been answered", () => {
  expect(reportTemplateId("", COLUMN)).toBe(COLUMN);
});

test("nothing anywhere resolves to nothing, rather than to a guess", () => {
  expect(reportTemplateId("", "")).toBe("");
});
