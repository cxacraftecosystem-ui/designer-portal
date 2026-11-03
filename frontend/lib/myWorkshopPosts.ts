import { ApiError, apiFetch } from "@/lib/api";
import { workshopPostsHeldBy, type WorkshopPost } from "@/lib/permissions";
import type { User } from "@/lib/types";

export const FRESH_MS = 5_000;

type StaffingRows = {
  oversight?: { capacity: string; userId: string }[];
  inspectors?: { userId: string }[];
};

type Posts = ReadonlyMap<string, WorkshopPost[]>;

let held: { userId: string; at: number; answer: Promise<Posts | null> } | null = null;
let unsupported = false;

export function myWorkshopPosts(user: User): Promise<Posts | null> {
  if (unsupported) return Promise.resolve(null);
  const now = Date.now();
  if (held && held.userId === user.id && now - held.at < FRESH_MS) return held.answer;
  const answer: Promise<Posts | null> = apiFetch<{ workshops?: Record<string, StaffingRows> }>(
    "/design-workshop-oversight/my-posts"
  ).then(
    (body) => {
      const posts = new Map<string, WorkshopPost[]>();
      for (const [workshopId, rows] of Object.entries(body.workshops ?? {})) {
        posts.set(workshopId, workshopPostsHeldBy(user, rows));
      }
      return posts;
    },
    (error: unknown) => {
      if (held?.answer === answer) held = null;
      if (error instanceof ApiError && (error.status === 404 || error.status === 405)) {
        unsupported = true;
        return null;
      }
      return new Map<string, WorkshopPost[]>();
    }
  );
  held = { userId: user.id, at: now, answer };
  return answer;
}

export function forgetMyWorkshopPosts(): void {
  held = null;
}

export function resetMyWorkshopPosts(): void {
  held = null;
  unsupported = false;
}
