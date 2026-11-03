import { buildQuery } from "@/lib/api";

export const OPEN_TASK_BADGE_HREF = "/tasks";

export const OPEN_TASK_COUNT_PATH = `/tasks${buildQuery({
  view: "assigned",
  status: "OPEN",
  pageSize: 1,
  withDerived: "false"
})}`;

export function openTaskBadgeSentence(count: number): string {
  return count === 1 ? "1 open task is assigned to you" : `${count} open tasks are assigned to you`;
}
