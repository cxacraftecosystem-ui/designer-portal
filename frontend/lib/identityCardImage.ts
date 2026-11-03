import { fileName } from "@/lib/naming";

export const IDENTITY_MAX_EDGE = 2000;

export const IDENTITY_JPEG_QUALITY = 0.92;

export type IdentityUploadPlan = {
  width: number;
  height: number;
  scaled: boolean;
};

export function identityUploadPlan(width: number, height: number, maxEdge = IDENTITY_MAX_EDGE): IdentityUploadPlan {
  const longest = Math.max(width, height);
  if (!Number.isFinite(longest) || longest <= 0) return { width, height, scaled: false };
  if (longest <= maxEdge) return { width, height, scaled: false };
  const factor = maxEdge / longest;
  return { width: Math.round(width * factor), height: Math.round(height * factor), scaled: true };
}

export async function prepareIdentityPhotograph(file: File): Promise<File> {
  if (typeof createImageBitmap !== "function" || typeof document === "undefined") return file;

  let bitmap: ImageBitmap | null = null;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
    const plan = identityUploadPlan(bitmap.width, bitmap.height);
    if (!Number.isFinite(plan.width) || plan.width <= 0 || plan.height <= 0) return file;

    const canvas = document.createElement("canvas");
    canvas.width = plan.width;
    canvas.height = plan.height;
    const context = canvas.getContext("2d");
    if (!context) return file;
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, plan.width, plan.height);
    context.drawImage(bitmap, 0, 0, plan.width, plan.height);

    const blob = await new Promise<Blob | null>((resolve) => {
      canvas.toBlob(resolve, "image/jpeg", IDENTITY_JPEG_QUALITY);
    });
    if (!blob) return file;
    if (blob.size >= file.size && !plan.scaled) return file;
    return new File([blob], fileName({ entity: "Artisan", date: new Date(), what: "Identity-Card", ext: "jpg" }), {
      type: "image/jpeg"
    });
  } catch {
    return file;
  } finally {
    bitmap?.close();
  }
}
