import { apiFetch } from "@/lib/api";

/**
 * E-mail: whether this deployment sends any, and the signed-in person's opt-outs.
 *
 * `available` is read before ANY e-mail control is drawn — the Settings switch, "Send by e-mail"
 * beside a password link. When it is false the controls are simply absent, and nothing on screen
 * mentions e-mail at all.
 *
 * Kept apart from `lib/preferences.ts` on purpose: appearance preferences are sent whole on every
 * save, and a client that knew nothing about e-mail must not be able to switch someone's e-mails
 * back on by saving a theme.
 */
export type NotificationPreferences = {
  available: boolean;
  /** E-mail me when an inspector files a correction suggestion on, or sends back, a workshop I work on. */
  emailReviewNotes: boolean;
};

export const NO_EMAIL: NotificationPreferences = { available: false, emailReviewNotes: true };

export async function fetchNotificationPreferences(): Promise<NotificationPreferences> {
  try {
    const answer = await apiFetch<Partial<NotificationPreferences>>("/preferences/notifications");
    return { available: answer?.available === true, emailReviewNotes: answer?.emailReviewNotes !== false };
  } catch {
    // An older API or a failed read: draw no e-mail control rather than one that cannot work.
    return { ...NO_EMAIL };
  }
}

export async function saveNotificationPreferences(
  value: Pick<NotificationPreferences, "emailReviewNotes">
): Promise<NotificationPreferences> {
  const answer = await apiFetch<NotificationPreferences>("/preferences/notifications", {
    method: "PUT",
    body: JSON.stringify(value)
  });
  return { available: answer.available === true, emailReviewNotes: answer.emailReviewNotes !== false };
}
