/**
 * HOW MUCH WORK IS WAITING FOR THE PERSON READING THE SCREEN — the pure half of the task badge.
 *
 * The request and the sentence live here, apart from React, for the reason
 * `components/ui/selectFilter.ts` and `components/data/cappedList.ts` exist: this repository has no
 * React renderer in its devDependencies, so a judgement made inside JSX is only ever exercised by
 * somebody looking at a screen. Both of the things below are judgements a regression would hide
 * rather than show — a badge that quietly costs twenty resolved tasks and a corpus-wide progress
 * derivation renders the same number as one that costs a count — so they are called directly by
 * `e2e/task-badge-unit.spec.ts` instead.
 */

import { buildQuery } from "@/lib/api";

/**
 * The entry the count rides on.
 *
 * A constant rather than a literal in three places (the desktop dropdown, the sheet, and the
 * `enabled` test that decides whether to spend the request at all), so the badge and the fetch that
 * feeds it can only ever be about one destination — the same rule `PENDING_ACCESS_BADGE_HREF`
 * follows in `DynamicIslandNav.tsx`.
 */
export const OPEN_TASK_BADGE_HREF = "/tasks";

/**
 * `GET /tasks` asked for the TOTAL and nothing else.
 *
 * ── WHY pageSize=1 ──────────────────────────────────────────────────────────────────────────────
 * `page_payload` returns `total` beside the rows, so one row is enough to learn the number and the
 * badge never reads `items`. Asking for a normal page instead would have the server resolve twenty
 * tasks' workshop titles, artisan rows and questionnaire sections (`INCLUDE` in
 * `backend/app/api/routes/tasks.py`) so that a two-character pill can print a digit. `pageSize=0` is
 * not an option — the endpoint declares `pageSize: int = Query(20, ge=1, le=100)` and would 422.
 *
 * ── WHY withDerived=false ───────────────────────────────────────────────────────────────────────
 * The derived half of a task is what the repository can independently SEE against the scope, which
 * means counting real records per task; `serialize_tasks` skips all of it when this is false. A
 * badge does not need it, and this is a request the nav makes on behalf of every signed-in user.
 *
 * ── WHY status=OPEN AND NOT "not done" ──────────────────────────────────────────────────────────
 * The endpoint's `status` filter takes one value, and OPEN is the one that means "nobody has picked
 * this up yet". IN_PROGRESS is work the person has already told the app they have started, so
 * counting it would keep the number lit while they are actively working — a badge that only clears
 * when the last task is DONE stops being a notification and becomes wallpaper.
 *
 * `withDerived` travels as the string "false" because `buildQuery` takes no booleans (its parameter
 * type is `string | number | undefined | null`); `/tasks` parses it as a bool. The tasks page's own
 * per-status chip counts spell it the same way.
 */
export const OPEN_TASK_COUNT_PATH = `/tasks${buildQuery({
  view: "assigned",
  status: "OPEN",
  pageSize: 1,
  withDerived: "false"
})}`;

/**
 * The whole sentence a pointer and a screen reader are given.
 *
 * NOT A BARE DIGIT — the rule `PendingAccessBadge` states in this repo's other badge. A number alone
 * in a corner is decoration, and the reader who most needs this one is the person who has never seen
 * it before. It is one sentence rather than a visible fragment plus an `sr-only` tail so the plural
 * rule exists once, in a function a test can call, instead of inside JSX where nothing checks it.
 */
export function openTaskBadgeSentence(count: number): string {
  return count === 1 ? "1 open task is assigned to you" : `${count} open tasks are assigned to you`;
}
