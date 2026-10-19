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
import { PORTAL } from "@/lib/namingPortal";

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

  // NAMING 2.1: <Portal>_<IST date>_<HHMM>_<What>.<ext>. Asserted by shape for "now" (the runner's
  // zone is not IST) and exactly for an instant whose IST reading is fixed.
  const DATED = (what: string, ext: string) =>
    new RegExp(`^${PORTAL}_\\d{4}-\\d{2}-\\d{2}_\\d{4}_${what}\\.${ext}$`);

  test("an unnamed recording is a dated Recording", () => {
    expect(localMediaFileName({ mimeType: "audio/webm;codecs=opus", now: AT })).toMatch(DATED("Recording", "webm"));
    expect(localMediaFileName({ name: "blob", mimeType: "audio/mp4", now: AT })).toMatch(DATED("Recording", "m4a"));
    expect(localMediaFileName({ name: "   ", mimeType: "audio/ogg", now: AT })).toMatch(DATED("Recording", "ogg"));
  });

  test("dates from the capture time when the caller has one, in IST", () => {
    const captured = Date.UTC(2026, 0, 1, 21, 34, 5); // 03:04 on 2 January in IST
    expect(localMediaFileName({ mimeType: "audio/webm", capturedAt: captured, now: AT })).toBe(
      `${PORTAL}_2026-01-02_0304_Recording.webm`
    );
  });

  test("other unnamed kinds are named by kind, and .bin only when nothing is known", () => {
    expect(localMediaFileName({ mimeType: "video/mp4", now: AT })).toMatch(DATED("Video", "mp4"));
    expect(localMediaFileName({ mimeType: "image/jpeg", now: AT })).toMatch(DATED("Photo", "jpg"));
    expect(localMediaFileName({ mimeType: "application/pdf", now: AT })).toMatch(DATED("File", "pdf"));
    expect(localMediaFileName({ now: AT })).toMatch(DATED("File", "bin"));
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

  test("the media action button's edge stands out from the card at 3:1, and its ink reads at 4.5:1, light and dark", () => {
    const action = read("components", "media", "MediaActionButton.tsx");
    for (const cls of [
      "border-purple-600 bg-purple-50",
      "text-purple-800",
      "dark:border-purple-500 dark:bg-purple-950 dark:text-purple-200",
      "pointer-coarse:min-h-11"
    ]) {
      expect(action).toContain(cls);
    }
    expect(action, "the 1.6:1 purple-300 edge is gone").not.toContain("border-purple-300");
    const lum = (hex: string) => {
      const [r, g, b] = [1, 3, 5]
        .map((at) => parseInt(hex.slice(at, at + 2), 16) / 255)
        .map((c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
      return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    };
    const ratio = (a: string, b: string) => {
      const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
      return (hi + 0.05) / (lo + 0.05);
    };
    // purple-600 / purple-500 edges against the light card + surface-50 and the dark card + surface-50.
    for (const ground of ["#ffffff", "#faf9fd"]) expect(ratio("#9148d2", ground)).toBeGreaterThanOrEqual(3);
    for (const ground of ["#1a1725", "#201c2d"]) expect(ratio("#aa69e9", ground)).toBeGreaterThanOrEqual(3);
    expect(ratio("#5f1c93", "#f9f5ff"), "purple-800 on purple-50").toBeGreaterThanOrEqual(4.5);
    expect(ratio("#e7d5fe", "#2f0d4b"), "purple-200 on purple-950").toBeGreaterThanOrEqual(4.5);
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
