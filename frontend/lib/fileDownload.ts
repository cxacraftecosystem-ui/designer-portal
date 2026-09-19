/**
 * TAKING A FILE OUT OF THE PRODUCT — the one implementation, and the reason there is now only one.
 *
 * ── THIS MODULE IS A CONSOLIDATION THAT THE TREE ASKED FOR BY NAME ──────────────────────────────
 *
 * `app/(protected)/officers/oversight.ts` carried a private `fetchWorkbook` under a ⚠ that read, in
 * as many words: *"THIS IS A SECOND COPY OF `lib/questionnaireForms.fetchWorkbook`, AND IT SHOULD
 * NOT BE … The right shape is one exported helper both doors call — `lib/questionnaireForms.ts` was
 * owned by another workstream in the wave that wrote this, so the copy is deliberate and temporary
 * rather than an oversight. **If you are the person consolidating them, export `fetchWorkbook` and
 * delete this.**"* By the time the ministry dashboard needed a download there were FOUR copies —
 * `questionnaireForms.ts`, `annual-plan/annualPlan.ts`, `sanctionOrders.ts` and `oversight.ts` — and
 * a fifth was the alternative to this file. `readableError`'s own header sets the precedent the
 * count had already broken: *"two private re-implementations already exist and a third must not."*
 *
 * ── WHY `apiFetch` CANNOT DO THIS, WHICH IS THE WHOLE REASON THE COPIES EXISTED ─────────────────
 *
 * `lib/api.apiFetch` reads every response as JSON or, failing that, as TEXT — and reading a workbook
 * or a CSV as text hands back a mangled string cast to the caller's type. That is a download which
 * "succeeds" and produces a file Excel refuses to open, which is worse than one that fails. It also
 * discards the response headers, and `Content-Disposition` is where the server's own filename is.
 *
 * So a download is a hand-built `fetch`, and the three obligations below are what every copy of it
 * had to discharge:
 *
 *   1. **Refuse the request when this build has no usable API address.** `assertApiConfigured()` —
 *      without it a misconfigured deploy silently reaches `http://localhost:8000` and the failure is
 *      a network error nobody can read.
 *   2. **Attach the bearer token.** It lives in `localStorage`, which is also why no `<a href>` in
 *      this app may point at an authenticated endpoint: a plain link navigation cannot carry it.
 *   3. **Turn a failure body into the sentence the server actually sent.** `apiFetch` builds
 *      `ApiError.message` with `String(detail)` and FastAPI's 422 detail is a LIST that stringifies
 *      to "[object Object]"; `describeApiDetail` is the shared reader. And `statusText` is EMPTY over
 *      HTTP/2 — which every deployed request is — so it can never be the last resort on its own, or
 *      a body-less failure reaches the screen as a blank error box.
 *
 * `components/DownloadCsvButton.tsx` discharged only the second of the three until this file landed:
 * it threw `new Error("Unable to export CSV (HTTP 403)")` over a 403 whose body explained exactly
 * what the reader had to do next.
 */

import { API_BASE, ApiError, assertApiConfigured, describeApiDetail, getToken } from "@/lib/api";

/** A file fetched from the API: the bytes, and the name the server asked for it to be saved as. */
export type FetchedFile = {
  blob: Blob;
  fileName: string;
};

/**
 * The filename out of a `Content-Disposition` header, or null.
 *
 * ⚠ **THE RFC 6266 `filename*` FORM IS PREFERRED OVER THE ASCII `filename`, AND EVERY COPY OF THIS
 * FUNCTION HAD IT THE OTHER WAY ROUND.** A header carrying both is written
 * `attachment; filename="report.docx"; filename*=UTF-8''%E0%A4%AC%E0%A4%97%E0%A4%B0%E0%A5%82.docx`,
 * ASCII first — that ORDER is required, because a client that understands only the plain form has to
 * meet it before the one it would choke on. The old single regex matched the first occurrence, so it
 * took the ASCII fallback every time and the Devanagari name the server built
 * (`data_browser._content_disposition`, `design_workshops._content_disposition`) never reached a
 * single download. The extended form is the one that carries the real name; the plain one is a
 * transliteration or a stem.
 *
 * `decodeURIComponent` is tried and its failure swallowed: a name that is not valid percent-encoding
 * is still a usable name, and it throws on a bare "%" — losing a download over a literal percent
 * sign in a craft name would be absurd.
 */
export function fileNameFromDisposition(header: string | null): string | null {
  if (!header) return null;

  // The extended form first. `filename*=UTF-8''<pct-encoded>` — the charset and the (empty) language
  // tag are part of the grammar and are stripped here rather than parsed, because this app's server
  // emits UTF-8 and nothing else, and a charset we could not decode would be a worse answer than the
  // ASCII fallback below.
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

/**
 * Hand a generated file to the browser's download machinery.
 *
 * ⚠ **THE OBJECT URL IS REVOKED ON THE NEXT TASK AND NEVER IN THE SAME TICK AS THE CLICK.** Revoking
 * it synchronously races the browser's own read of it, and Safari in particular ends up downloading
 * nothing at all with no error anywhere. `questionnaire-voice-note-unit.spec.ts` pins the exact
 * shape of that line in `MarkdownDocument` for the same reason.
 *
 * The anchor is appended to the document before it is clicked and removed after. A detached anchor's
 * click is honoured by Chromium and ignored by Firefox, which is the other half of the same bug.
 */
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

/**
 * GET a file from the API, with the three obligations discharged. Never reads the body as JSON.
 *
 * `cache: "no-store"` matches `apiFetch`'s default and is deliberate for the same reason: a download
 * served from a stale store is a file that reports the repository as it was, under today's filename.
 *
 * @param path an API path WITHOUT the `/api` prefix — `"/export/artisans.csv"` — exactly as
 *   `apiFetch` takes it, so the two read the same at a call site.
 * @param fallbackName used only when the server sent no `Content-Disposition` filename. Every server
 *   route in this app does send one; the fallback is what stops a missing header producing a file
 *   called "download".
 */
export async function fetchFile(path: string, fallbackName: string): Promise<FetchedFile> {
  assertApiConfigured();

  const headers = new Headers();
  const token = getToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const response = await fetch(`${API_BASE}/api${path}`, { headers, cache: "no-store" });
  if (!response.ok) {
    const contentType = response.headers.get("content-type") ?? "";
    const payload = contentType.includes("application/json")
      ? await response.json()
      : await response.text();
    /*
      ⚠ `undefined` AND NOT `payload` WHEN THERE IS NO `detail`, AND THIS LINE WAS THE OTHER WAY
      ROUND UNTIL REVIEW CAUGHT IT. All four copies this function replaced fell back to `undefined`,
      and that was right: the non-JSON branch above hands back `response.text()`, so a gateway or a
      proxy that answers an HTML error page — which is what a 502 looks like from outside the app —
      would have put a whole `<!DOCTYPE html>…` document through `describeApiDetail` and onto the
      screen as the reason a download failed. `undefined` falls through to the sentence below, which
      names the status and is the thing a reader can act on.
    */
    const detail =
      typeof payload === "object" && payload && "detail" in payload
        ? (payload as { detail: unknown }).detail
        : undefined;
    throw new ApiError(
      response.status,
      describeApiDetail(
        detail,
        response.statusText || `The server refused the request (HTTP ${response.status}).`
      ),
      payload
    );
  }

  return {
    blob: await response.blob(),
    fileName: fileNameFromDisposition(response.headers.get("content-disposition")) ?? fallbackName
  };
}

/**
 * {@link fetchFile} and then {@link saveBlobToDisk} — the whole download, for a caller that has
 * nothing to do with the bytes.
 *
 * IT STILL THROWS. The two halves are separate functions because several callers need the blob
 * itself (the report page previews it, the trace panel names it), and because a caller that saves
 * must still be the one to decide what a failure looks like on ITS screen — §12.11's rule that the
 * error treatment is chosen by meaning, not by the layer that raised it.
 */
export async function downloadFile(path: string, fallbackName: string): Promise<string> {
  const file = await fetchFile(path, fallbackName);
  saveBlobToDisk(file.blob, file.fileName);
  return file.fileName;
}
