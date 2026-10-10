import { expect, test } from "@playwright/test";

import {
  approvalActions,
  decisionKindLabel,
  NO_DECISION_REASON,
  noDecisionSentence,
  pinnableExport,
  REVISE_COPY,
  reviseKindOf,
  signOffLines,
  type DwApprovalDetail
} from "@/lib/designWorkshopApprovals";
import { APPROVAL_AUTHORITY_REFUSAL, canAccessRoute, ROUTE_GUARDS, ROLES_BY_RANK } from "@/lib/permissions";
import type { User, UserRole } from "@/lib/types";

/**
 * REPORTS TO APPROVE — the pure half of the Ministry Admin's decision panel.
 *
 * Which buttons a report offers is decided by its STATUS; whether each may be pressed, and the
 * sentence a refused one shows, by the SERVER (`mayDecide`, `decisionRefusal`, `refusals`). These
 * tests hold both halves: no button is live without the server's explicit yes, and no refused button
 * is drawn without a sentence saying why.
 *
 * PURE NODE — no browser, no server.
 */

const user = (role: UserRole): User => ({ id: `u-${role}`, email: "a@b.c", name: role, role }) as User;

function detail(overrides: Partial<DwApprovalDetail>): DwApprovalDetail {
  return {
    id: "dw_1",
    title: "Bagru block printing",
    status: "PRE_SUBMISSION",
    submissionRound: 2,
    updatedAt: "2026-10-09T10:00:00Z",
    deletedAt: null,
    stages: {},
    completeness: {},
    schemaVersion: "v1",
    mayDecide: { approve: true, revise: true, handOn: false },
    decisionRefusal: null,
    refusals: { approve: null, revise: null, handOn: "Only an approved report can be handed on to the office." },
    ...overrides
  } as DwApprovalDetail;
}

test("Pre-submission offers Approve and Send back, both live when the server says yes", () => {
  const actions = approvalActions(detail({}));
  expect(actions.map((a) => a.verb)).toEqual(["approve", "revise"]);
  expect(actions.map((a) => a.label)).toEqual(["Approve", REVISE_COPY.RETURN.button]);
  expect(actions.every((a) => a.allowed && a.reason === null)).toBe(true);
});

test("an approved report offers Hand on and Withdraw the approval", () => {
  const actions = approvalActions(
    detail({ status: "APPROVED", mayDecide: { approve: false, revise: true, handOn: true }, refusals: {} })
  );
  expect(actions.map((a) => a.verb)).toEqual(["handOn", "revise"]);
  expect(actions[1].label).toBe(REVISE_COPY.WITHDRAW.button);
  expect(actions[0].primary).toBe(true);
});

test("a handed-on report offers only the return from the office; a legacy SUBMITTED row offers nothing", () => {
  const handedOn = detail({ status: "SUBMITTED", handedOnAt: "2026-10-09T11:00:00Z", mayDecide: { revise: true }, reviseKind: "RETURN_FROM_OFFICE" });
  expect(approvalActions(handedOn).map((a) => a.label)).toEqual([REVISE_COPY.RETURN_FROM_OFFICE.button]);
  const legacy = detail({ status: "SUBMITTED", handedOnAt: null, reviseKind: null, mayDecide: {} });
  expect(approvalActions(legacy)).toEqual([]);
  expect(noDecisionSentence(legacy)).toMatch(/before reports were approved/);
});

test("statuses with nothing to decide offer nothing and say why", () => {
  for (const status of ["NEEDS_REVISION", "DRAFT", "IN_PROGRESS", "COMPLETE", "ARCHIVED", "SOMETHING_NEW"]) {
    const d = detail({ status, reviseKind: null });
    expect(approvalActions(d), status).toEqual([]);
    expect(noDecisionSentence(d), status).toBeTruthy();
  }
});

test("the rule-7 refusal switches every decision off and is the sentence each one shows", () => {
  const refusal = "You inspect this workshop, so you cannot decide on its report: the officer who inspects a report is not the one who signs it off.";
  const actions = approvalActions(detail({ decisionRefusal: refusal, mayDecide: { approve: false, revise: false } }));
  expect(actions.length).toBe(2);
  for (const action of actions) {
    expect(action.allowed).toBe(false);
    expect(action.reason).toBe(refusal);
  }
});

test("an absent mayDecide is a refusal with a sentence, never a live button", () => {
  const actions = approvalActions(detail({ mayDecide: undefined, refusals: undefined }));
  expect(actions.every((a) => !a.allowed)).toBe(true);
  expect(actions.every((a) => a.reason === NO_DECISION_REASON)).toBe(true);
});

test("a refused decision shows the server's own status sentence", () => {
  const sentence = "This report changed after you opened it.";
  const [approve] = approvalActions(detail({ mayDecide: { approve: false, revise: true }, refusals: { approve: sentence } }));
  expect(approve.allowed).toBe(false);
  expect(approve.reason).toBe(sentence);
});

test("a deleted report offers nothing", () => {
  expect(approvalActions(detail({ deletedAt: "2026-10-09T00:00:00Z" }))).toEqual([]);
});

test("reviseKind is the server's word, derived only when absent", () => {
  expect(reviseKindOf({ status: "PRE_SUBMISSION", handedOnAt: null, reviseKind: undefined })).toBe("RETURN");
  expect(reviseKindOf({ status: "APPROVED", handedOnAt: null, reviseKind: undefined })).toBe("WITHDRAW");
  expect(reviseKindOf({ status: "SUBMITTED", handedOnAt: "x", reviseKind: undefined })).toBe("RETURN_FROM_OFFICE");
  expect(reviseKindOf({ status: "SUBMITTED", handedOnAt: null, reviseKind: undefined })).toBeNull();
  expect(reviseKindOf({ status: "APPROVED", handedOnAt: null, reviseKind: null })).toBeNull();
});

test("decision kinds have words, and an unknown one is still a decision", () => {
  expect(decisionKindLabel("APPROVED")).toBe("Approved");
  expect(decisionKindLabel("APPROVAL_WITHDRAWN")).toBe("Approval withdrawn");
  expect(decisionKindLabel("RETURNED")).toBe("Sent back by the approving authority");
  expect(decisionKindLabel("SENT_BACK")).toBe("Sent back by an inspector");
  expect(decisionKindLabel("HANDED_ON")).toBe("Handed on to the office");
  expect(decisionKindLabel("SOMETHING_NEW")).toBe("A decision on this report");
});

test("the sign-off lines name who, never guess, and say nothing for an unsigned report", () => {
  expect(signOffLines({ status: "PRE_SUBMISSION" })).toEqual([]);
  const lines = signOffLines({
    approvedAt: "2026-10-09T10:00:00Z",
    approvedById: "u1",
    approvedByName: "R. Mahapatra",
    handedOnAt: "2026-10-10T10:00:00Z",
    handedOnById: "u1",
    handedOnByName: null,
    handedOnTo: "Office of the Development Commissioner (Handicrafts)"
  });
  expect(lines[0]).toMatch(/^Approved by R\. Mahapatra on /);
  expect(lines[1]).toMatch(/^Handed on to Office of the Development Commissioner \(Handicrafts\) by somebody no longer on record on /);
});

test("only an export made after the approval can be named as the file that went", () => {
  const latest = { id: "e1", format: "DOCX", generatedAt: "2026-10-09T12:00:00Z" };
  expect(pinnableExport({ latestExport: latest, approvedAt: "2026-10-09T10:00:00Z" })?.id).toBe("e1");
  expect(pinnableExport({ latestExport: latest, approvedAt: "2026-10-09T13:00:00Z" })).toBeNull();
  expect(pinnableExport({ latestExport: null, approvedAt: "2026-10-09T10:00:00Z" })).toBeNull();
});

test("the screen opens for the Ministry Admin and the master admin only, with the server's refusal", () => {
  const guard = ROUTE_GUARDS.find((row) => row.path === "/design-workshop-approvals");
  expect(guard?.message).toBe(APPROVAL_AUTHORITY_REFUSAL);
  expect(guard?.ministry).toBe(true);
  const open = ROLES_BY_RANK.filter((role) => canAccessRoute(user(role), "/design-workshop-approvals/dw_1"));
  expect([...open].sort()).toEqual(["MASTER_ADMIN", "MINISTRY_ADMIN"]);
});
