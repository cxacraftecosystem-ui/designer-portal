import { apiFetch } from "@/lib/api";

/**
 * E-mail: whether this deployment sends any, and the signed-in person's choices.
 *
 * `available` is read before ANY e-mail control is drawn — the Settings switches, "Send by e-mail"
 * beside a password link. When it is false the controls are simply absent, and nothing on screen
 * mentions e-mail at all.
 *
 * Kept apart from `lib/preferences.ts` on purpose: appearance preferences are sent whole on every
 * save, and a client that knew nothing about e-mail must not be able to switch someone's e-mails
 * back on by saving a theme. A save sends only the choice that changed (2026-10-18), so a field this
 * client does not know is never overwritten.
 */
export type HandInMode = "EACH" | "DAILY" | "OFF";

export type NotificationPreferences = {
  available: boolean;
  /** E-mail me when an inspector files a correction suggestion on, or sends back, a workshop I work on. */
  emailReviewNotes: boolean;
  /** A workshop I work on was approved or handed on; I was given a workshop; an officer reminded me. */
  emailWorkshopUpdates: boolean;
  /** Requests for access, decisions on mine, comments on my records. */
  emailAccessNotices: boolean;
  /** How I hear that a report I review was handed in. */
  emailHandInNotices: HandInMode;
  /** Whether the hand-in choice applies to this account (an inspector or the approving authority). */
  reviewsHandIns: boolean;
};

export const NO_EMAIL: NotificationPreferences = {
  available: false,
  emailReviewNotes: true,
  emailWorkshopUpdates: true,
  emailAccessNotices: true,
  emailHandInNotices: "EACH",
  reviewsHandIns: false
};

export const HAND_IN_MODES: { value: HandInMode; label: string }[] = [
  { value: "EACH", label: "Each time" },
  { value: "DAILY", label: "Once a day" },
  { value: "OFF", label: "Never" }
];

/** The server's answer read defensively: an older API sends only the first two fields. */
export function readNotificationPreferences(answer: Partial<NotificationPreferences> | null | undefined): NotificationPreferences {
  const mode = answer?.emailHandInNotices;
  return {
    available: answer?.available === true,
    emailReviewNotes: answer?.emailReviewNotes !== false,
    emailWorkshopUpdates: answer?.emailWorkshopUpdates !== false,
    emailAccessNotices: answer?.emailAccessNotices !== false,
    emailHandInNotices: mode === "DAILY" || mode === "OFF" ? mode : "EACH",
    reviewsHandIns: answer?.reviewsHandIns === true
  };
}

export async function fetchNotificationPreferences(): Promise<NotificationPreferences> {
  try {
    return readNotificationPreferences(
      await apiFetch<Partial<NotificationPreferences>>("/preferences/notifications")
    );
  } catch {
    // An older API or a failed read: draw no e-mail control rather than one that cannot work.
    return { ...NO_EMAIL };
  }
}

export async function saveNotificationPreferences(
  value: Partial<Pick<NotificationPreferences, "emailReviewNotes" | "emailWorkshopUpdates" | "emailAccessNotices" | "emailHandInNotices">>
): Promise<NotificationPreferences> {
  const answer = await apiFetch<Partial<NotificationPreferences>>("/preferences/notifications", {
    method: "PUT",
    body: JSON.stringify(value)
  });
  return readNotificationPreferences(answer);
}
