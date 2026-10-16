/**
 * Download while uploading: the name a local file is saved under, and where the button is wired.
 *
 * The naming rules are pure and tested directly. The wiring is a source assertion, for this
 * repository's usual reason: there is no React renderer in devDependencies, so a button inside JSX
 * is only ever exercised by somebody looking at a screen.
 */

import { readFileSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "@playwright/test";

import { baseMimeType, extensionForMime, fileDateStamp, localMediaFileName } from "@/lib/localMediaDownload";

const read = (...parts: string[]) => readFileSync(join(__dirname, "..", ...parts), "utf8");

const AT = new Date(2026, 9, 16, 14, 30, 5);

test.describe("extensionForMime", () => {
  test("drops codec parameters and case", () => {
    expect(baseMimeType("Audio/WebM; codecs=opus")).toBe("audio/webm");
    expect(extensionForMime("audio/webm;codecs=opus")).toBe("webm");
  });

  test("names the containers the recorders and cameras produce", () => {
    expect(extensionForMime("audio/mp4")).toBe("m4a");
    expect(extensionForMime("audio/ogg")).toBe("ogg");
    expect(extensionForMime("audio/mpeg")).toBe("mp3");
    expect(extensionForMime("video/mp4")).toBe("mp4");
    expect(extensionForMime("video/quicktime")).toBe("mov");
    expect(extensionForMime("image/jpeg")).toBe("jpg");
    expect(extensionForMime("image/heic")).toBe("heic");
    expect(extensionForMime("application/pdf")).toBe("pdf");
  });

  test("matches exactly, never by substring, and answers null when it does not know", () => {
    expect(extensionForMime("application/vnd.something-pdf")).toBeNull();
    expect(extensionForMime("")).toBeNull();
    expect(extensionForMime(null)).toBeNull();
    expect(extensionForMime("application/octet-stream")).toBeNull();
  });
});

test.describe("localMediaFileName", () => {
  test("keeps a file's own name", () => {
    expect(localMediaFileName({ name: "IMG_2041.jpg", mimeType: "image/jpeg", now: AT })).toBe("IMG_2041.jpg");
    expect(localMediaFileName({ name: "field-recording-1760000000000.webm", mimeType: "audio/webm", now: AT })).toBe(
      "field-recording-1760000000000.webm"
    );
  });

  test("adds the mime type's extension only when the name has none", () => {
    expect(localMediaFileName({ name: "interview take 2", mimeType: "audio/mp4", now: AT })).toBe("interview take 2.m4a");
    expect(localMediaFileName({ name: "notes", mimeType: "application/octet-stream", now: AT })).toBe("notes");
  });

  test("makes a name safe for a file system", () => {
    expect(localMediaFileName({ name: 'a/b:c*?"<>|.png', mimeType: "image/png", now: AT })).toBe("a-b-c-.png");
    expect(localMediaFileName({ name: "..hidden.pdf", mimeType: "application/pdf", now: AT })).toBe("hidden.pdf");
  });

  test("an unnamed recording is a dated voice note", () => {
    expect(localMediaFileName({ mimeType: "audio/webm;codecs=opus", now: AT })).toBe("voice-note-2026-10-16-143005.webm");
    expect(localMediaFileName({ name: "blob", mimeType: "audio/mp4", now: AT })).toBe("voice-note-2026-10-16-143005.m4a");
    expect(localMediaFileName({ name: "   ", mimeType: "audio/ogg", now: AT })).toBe("voice-note-2026-10-16-143005.ogg");
  });

  test("dates from the capture time when the caller has one", () => {
    const captured = new Date(2026, 0, 2, 3, 4, 5).getTime();
    expect(localMediaFileName({ mimeType: "audio/webm", capturedAt: captured, now: AT })).toBe(
      "voice-note-2026-01-02-030405.webm"
    );
  });

  test("other unnamed kinds are named by kind, and .bin only when nothing is known", () => {
    expect(localMediaFileName({ mimeType: "video/mp4", now: AT })).toBe("video-2026-10-16-143005.mp4");
    expect(localMediaFileName({ mimeType: "image/jpeg", now: AT })).toBe("photo-2026-10-16-143005.jpg");
    expect(localMediaFileName({ mimeType: "application/pdf", now: AT })).toBe("file-2026-10-16-143005.pdf");
    expect(localMediaFileName({ now: AT })).toBe("file-2026-10-16-143005.bin");
  });

  test("the stamp is zero-padded local time", () => {
    expect(fileDateStamp(new Date(2026, 2, 4, 5, 6, 7))).toBe("2026-03-04-050607");
  });
});

test.describe("every upload surface offers Download", () => {
  test("the shared button reads 'Download' with the repo's download icon", () => {
    const button = read("components", "media", "DownloadMediaButton.tsx");
    // The tonal `MediaActionButton` since 2026-10-17 (the owner could not see the old 2px chip): the
    // icon AND the word, 44px on a phone.
    expect(button).toContain('label="Download"');
    expect(button).toContain("icon={DOWNLOAD_ICON}");
    const action = read("components", "media", "MediaActionButton.tsx");
    expect(action).toContain('import { Download, RefreshCw } from "lucide-react"');
    expect(action).toContain('export const DOWNLOAD_ICON = <Download className="h-4 w-4" aria-hidden />;');
    expect(action).toContain("min-h-11");
  });

  test("the helper revokes its object URL through saveBlobToDisk", () => {
    const helper = read("lib", "localMediaDownload.ts");
    expect(helper).toContain("export function downloadLocalMedia(");
    expect(helper).toContain("saveBlobToDisk(source, fileName)");
    expect(read("lib", "fileDownload.ts")).toContain("window.setTimeout(() => URL.revokeObjectURL(url), 0)");
  });

  const SURFACES: Array<[string[], string]> = [
    [["components", "forms", "MediaCaptureField.tsx"], "downloadLocalMedia(file)"],
    [["app", "(protected)", "questionnaire", "page.tsx"], "downloadLocalMedia(file)"],
    [["components", "media", "UploadProgress.tsx"], "downloadLocalMedia(current)"],
    [["components", "media", "UploadProgress.tsx"], "downloadMediaUrl(item.file.url!"],
    [["components", "designworkshop", "FieldInput.tsx"], "downloadLocalMedia(local.blob"],
    [["components", "OutboxBanner.tsx"], "downloadLocalMedia(file)"],
    [["components", "designworkshop", "Dictation.tsx"], "downloadLocalMedia(lastRecording.blob"]
  ];
  for (const [path, call] of SURFACES) {
    test(`${path.join("/")} wires ${call}`, () => {
      expect(read(...path)).toContain(call);
    });
  }
});
