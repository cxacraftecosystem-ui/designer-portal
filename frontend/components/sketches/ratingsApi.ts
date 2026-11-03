import { ApiError, apiFetch, buildQuery } from "@/lib/api";
import { isTransient } from "@/lib/failureTriage";
import { queueOffline, syncOutbox } from "@/lib/offline";

import type { DesignRating, RatingRound, RoundRanking, SubjectLedger } from "./reviewRanking";

export type DesignRatingBody = {
  subjectId: string;
  round: RatingRound;
  score: number;
  comment?: string | null;
  suggestion?: string | null;
};

export type DesignRatingSaved = { rating: DesignRating; replayed: boolean };

export function submitDesignRating(body: DesignRatingBody) {
  return apiFetch<DesignRatingSaved>("/design-ratings", {
    method: "POST",
    body: JSON.stringify(body)
  });
}

const RATING_SAVED_IN = "rating";

const RATINGS_ENDPOINT = "/design-ratings";

export type DesignRatingOutcome =
  | { queued: true; sent: boolean }
  | { queued: false; saved: DesignRatingSaved };

export async function submitOrQueueDesignRating(
  body: DesignRatingBody,
  label: string
): Promise<DesignRatingOutcome> {
  const queue = async (): Promise<DesignRatingOutcome> => {
    await queueOffline({
      label,
      endpoint: RATINGS_ENDPOINT,
      method: "POST",
      body: JSON.stringify({ ...body, ratedAt: new Date().toISOString() }),
      media: [],
      savedIdIn: RATING_SAVED_IN
    });
    let sent = false;
    if (!(typeof navigator !== "undefined" && navigator.onLine === false)) {
      try {
        const pass = await syncOutbox();
        sent = pass.synced > 0 && pass.remaining === 0;
      } catch {
        sent = false;
      }
    }
    return { queued: true, sent };
  };

  if (typeof navigator !== "undefined" && navigator.onLine === false) return queue();

  try {
    return { queued: false, saved: await submitDesignRating(body) };
  } catch (error) {
    if (isTransient(error) && !(error instanceof ApiError)) return queue();
    throw error;
  }
}

export function fetchSubjectLedger(subjectId: string, round: RatingRound) {
  return apiFetch<SubjectLedger>(`/design-ratings/subjects/${subjectId}${buildQuery({ round })}`);
}

export function fetchRoundRanking(params: {
  round: RatingRound;
  workshopId: string;
  entityKey: string;
}) {
  const { round, workshopId, entityKey } = params;
  return apiFetch<RoundRanking>(
    `/design-ratings/rounds/${round}${buildQuery({ workshopId, entityKey })}`
  );
}

export function refusalText(error: unknown, fallback: string): string {
  return error instanceof ApiError && error.message.trim() ? error.message : fallback;
}
