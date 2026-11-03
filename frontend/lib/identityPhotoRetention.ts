import { apiFetch } from "@/lib/api";

export type DwPhotoRetention = "DISCARD" | "STORE";

export const DW_PHOTO_RETENTION_DEFAULT: DwPhotoRetention = "DISCARD";

export const DW_IDENTITY_RETENTION_PATH = "/design-workshops/ocr/identity/retention";

export type DwPhotoRetentionResult = {
  mediaId?: string | null;
  decision?: DwPhotoRetention | string | null;
  deleted?: boolean | null;
  objectDeleted?: boolean | null;
  retention?: {
    decision?: string | null;
    decidedById?: string | null;
    decidedByName?: string | null;
    decidedAt?: string | null;
  } | null;
};

export function decideIdentityPhotograph(
  mediaId: string,
  decision: DwPhotoRetention
): Promise<DwPhotoRetentionResult> {
  return apiFetch<DwPhotoRetentionResult>(DW_IDENTITY_RETENTION_PATH, {
    method: "POST",
    body: JSON.stringify({ mediaId, decision })
  });
}

export function retentionOutcomeSentence(result: DwPhotoRetentionResult): string {
  if (result.deleted) {
    return "Photo deleted — the file and the record of it are both gone. The number you confirmed is unaffected.";
  }
  const who = result.retention?.decidedByName?.trim();
  return who
    ? `Photo kept on this record, in ${who}'s name. It's unmasked — anyone who can open this record can see it.`
    : "Photo kept on this record. It's unmasked — anyone who can open this record can see it.";
}

export const DW_PHOTO_UNDECIDED =
  "This photo is saved unmasked. Keep it or delete it — the number stays masked either way.";
