import { looksLikeSecureQr } from "@/lib/aadhaar";

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
