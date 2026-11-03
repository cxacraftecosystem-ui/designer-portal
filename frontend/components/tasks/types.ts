import type { UserRole } from "@/lib/types";

export type TaskStatus = "OPEN" | "IN_PROGRESS" | "DONE" | "CANCELLED";

export type TaskUserBrief = {
  id: string;
  name: string;
  email?: string;
  role: UserRole;
  roleLabel?: string;
};

export type TaskArtisanRef = { id: string; name: string; place?: string | null };
export type TaskSectionRef = { id: string; code: string; title: string; sortOrder: number };

export type FieldTask = {
  id: string;
  title: string;
  description?: string | null;
  status: TaskStatus;
  dueAt?: string | null;
  completedAt?: string | null;
  recordType?: string | null;
  recordId?: string | null;
  workshopId?: string | null;
  recordTypes: string[];
  artisanIds: string[];
  sectionIds: string[];
  targetCount?: number | null;
  progressCount: number;
  batchId?: string | null;
  assigneeId: string;
  createdById: string;
  createdAt: string;
  updatedAt: string;
  assignee?: TaskUserBrief | null;
  createdBy?: TaskUserBrief | null;
  workshopTitle?: string | null;
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
  recordTypeLabels: string[];
  percentComplete: number | null;
  isOverdue: boolean;
  derivedCount: number | null;
  derivedTarget: number | null;
  derivedBreakdown: Record<string, number>;
};

export type TaskOptions = {
  recordTypes: { value: string; label: string; pluralLabel: string }[];
  assignees: TaskUserBrief[];
  workshops: { id: string; title: string; place?: string | null; date?: string | null }[];
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
  assigneesTruncated?: boolean;
  workshopsTruncated?: boolean;
  artisansTruncated?: boolean;
};

export type TaskBatchAssignee = {
  taskId: string;
  user: TaskUserBrief | null;
  status: TaskStatus;
  progressCount: number;
  derivedCount: number | null;
  percentComplete: number | null;
  completedAt?: string | null;
};

export type TaskBatch = {
  batchId: string | null;
  key: string;
  title: string;
  description?: string | null;
  dueAt?: string | null;
  createdAt: string;
  createdBy: TaskUserBrief | null;
  workshopId?: string | null;
  workshopTitle?: string | null;
  recordTypes: string[];
  recordTypeLabels: string[];
  artisans: TaskArtisanRef[];
  sections: TaskSectionRef[];
  targetCount: number | null;
  assigneeCount: number;
  statusCounts: Record<TaskStatus, number>;
  doneCount: number;
  openCount: number;
  overdueCount: number;
  reportedTotal: number;
  derivedTotal: number | null;
  percentComplete: number;
  assignees: TaskBatchAssignee[];
};

export type TaskProgressAssignee = {
  user: TaskUserBrief | null;
  taskCount: number;
  statusCounts: Record<TaskStatus, number>;
  openCount: number;
  overdueCount: number;
  targetTotal: number | null;
  reportedTotal: number;
  derivedTotal: number | null;
  percentComplete: number;
  tasks: FieldTask[];
};

export type TaskProgressReport = {
  workshopId: string | null;
  workshopTitle: string | null;
  assigneeCount: number;
  taskCount: number;
  doneCount: number;
  openCount: number;
  overdueCount: number;
  truncated: boolean;
  assignees: TaskProgressAssignee[];
};

export type TaskBatchResult = {
  batchId: string;
  title: string;
  created: number;
  batch: TaskBatch;
  tasks: FieldTask[];
};
