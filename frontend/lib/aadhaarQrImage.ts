/**
 * Reading the Aadhaar Secure QR out of a picture, kept apart from `lib/aadhaar.ts` because it is the
 * one piece that needs `import.meta` (to serve the decoder's .wasm from this app's own origin) — a
 * module the pure-Node unit specs must not have to load.
 */

import { looksLikeSecureQr } from "@/lib/aadhaar";

/**
 * Read the QR out of a picture — a photograph of the card, a screenshot of e-Aadhaar.
 *
 * NOT `lib/qrDecode.ts`. That decoder reads versions 1 to 9 on purpose (it refuses anything larger
 * by name — see its header), and an Aadhaar Secure QR is a dense version-30-plus symbol. This
 * loads zxing-cpp compiled to WebAssembly, ONLY when somebody actually picks a picture on this
 * screen — the dynamic import keeps it out of every other page's bundle — and serves the .wasm from
 * this app's own origin rather than the library's default CDN.
 */
export async function readSecureQrFromImage(file: Blob): Promise<string | null> {
  const { prepareZXingModule, readBarcodes } = await import("zxing-wasm/reader");
  const wasmUrl = new URL("zxing-wasm/reader/zxing_reader.wasm", import.meta.url).href;
  prepareZXingModule({
    overrides: {
      locateFile: (path: string, prefix: string) => (path.endsWith(".wasm") ? wasmUrl : prefix + path)
    }
  });
  const results = await readBarcodes(file, {
    formats: ["QRCode"],
    tryHarder: true,
    tryRotate: true,
    tryInvert: true,
    maxNumberOfSymbols: 4
  });
  const hit = results.find((result) => result.isValid && looksLikeSecureQr(result.text));
  return hit ? hit.text.replace(/\s+/g, "") : null;
}
