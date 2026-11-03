import { apiFetch } from "@/lib/api";

export type HandInMode = "EACH" | "DAILY" | "OFF";

export type NotificationPreferences = {
  available: boolean;
  emailReviewNotes: boolean;
  emailWorkshopUpdates: boolean;
  emailAccessNotices: boolean;
  emailHandInNotices: HandInMode;
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
