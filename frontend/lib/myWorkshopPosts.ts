/**
 * THE POSTS THIS ACCOUNT HOLDS, ON EVERY WORKSHOP, IN ONE READ — shared by every screen that asks.
 *
 * `useHeldPostRefusal` and `useHeldPostRefusals` (components/designworkshop/HeldPostNotice) used to
 * ask each workshop for itself: `GET /design-workshop-oversight/{id}` plus its inspector list, two
 * requests per workshop, asked again by every component that mounted and on every stage-to-stage
 * step. A media list naming twelve workshops was twenty-four requests before its delete buttons could
 * be drawn. `GET /design-workshop-oversight/my-posts` answers the caller's own oversight and inspection
 * rows on every live workshop at once, in the same row shapes, so the posts are derived with the very
 * function the two per-workshop reads were run through (`workshopPostsHeldBy`).
 *
 * KEPT FOR {@link FRESH_MS} AND DROPPED ON A SAVE OR A SIGN-OUT. The answer is held for a few seconds
 * so the components one screen mounts together, and a stage-to-stage step, share it; a write this
 * tab makes to oversight or inspectors (the only two writes that move a post) drops it at once
 * ({@link forgetMyWorkshopPosts}), and so does signing out. What another admin changes is seen
 * within the few seconds. The server's 403, which names the post, stays the last word either way —
 * this is a reading, not a gate.
 *
 * AN OLDER SERVER answers the path as `GET /{id}` with id "my-posts", i.e. 404; that switches this
 * tab to `null` ("ask per workshop") for good, which is exactly the old behaviour. Any other failure
 * answers an empty map — none known, as a failed per-workshop read did — and is not kept.
 */

import { ApiError, apiFetch } from "@/lib/api";
import { workshopPostsHeldBy, type WorkshopPost } from "@/lib/permissions";
import type { User } from "@/lib/types";

/** How long one answer serves every screen in this tab: long enough for one screen's mount burst. */
export const FRESH_MS = 5_000;

type StaffingRows = {
  oversight?: { capacity: string; userId: string }[];
  inspectors?: { userId: string }[];
};

type Posts = ReadonlyMap<string, WorkshopPost[]>;

let held: { userId: string; at: number; answer: Promise<Posts | null> } | null = null;
let unsupported = false;

/**
 * `{workshop id: posts held}` for `user` — a workshop absent from the map is one they hold nothing
 * on — or `null` when the server has no batch route and each workshop must be asked for itself.
 */
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

/** Drop the held answer — after this tab saves a workshop's oversight or inspectors, and on sign-out. */
export function forgetMyWorkshopPosts(): void {
  held = null;
}

/** Forget everything, the "older server" verdict included. FOR TESTS AND FOR NOTHING ELSE. */
export function resetMyWorkshopPosts(): void {
  held = null;
  unsupported = false;
}
