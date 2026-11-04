import { API_BASE, ApiError, assertApiConfigured, describeApiDetail } from "@/lib/api";

export type FetchedFile = {
  blob: Blob;
  fileName: string;
};

export function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;

  const extended = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\s]+)/i.exec(header);
  if (extended) return decodeMaybe(extended[1]);

  const plain = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;\s]+)/i.exec(header);
  if (!plain) return null;
  const raw = plain[1] ?? plain[2] ?? "";
  return raw ? decodeMaybe(raw) : null;
}

function decodeMaybe(value: string): string {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}

export function saveBlobToDisk(blob: Blob, fileName: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function fetchFile(
  path: string,
  fallbackName: string,
  post?: { json: unknown }
): Promise<FetchedFile> {
  assertApiConfigured();

  const headers = new Headers();
  if (post) headers.set("Content-Type", "application/json");

  const response = await fetch(`${API_BASE}/api${path}`, {
    headers,
    cache: "no-store",
    ...(post ? { method: "POST", body: JSON.stringify(post.json) } : {})
  });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();
    const detail =
      typeof payload === "object" && payload && "detail" in payload
        ? (payload as { detail: unknown }).detail
        : undefined;
    throw new ApiError(
      response.status,
      describeApiDetail(
        detail,
        response.statusText || "The download didn't finish. Please try again."
      ),
      payload,
      response.headers
    );
  }

  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

export async function downloadFile(path: string, fallbackName: string): Promise<string> {
  const file = await fetchFile(path, fallbackName);
  saveBlobToDisk(file.blob, file.fileName);
  return file.fileName;
}
