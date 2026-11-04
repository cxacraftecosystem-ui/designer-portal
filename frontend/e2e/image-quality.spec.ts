import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { expect, test } from "@playwright/test";

import {
  BLUR_VARIANCE_FLOOR,
  MIN_CONTRAST_STDDEV,
  MIN_LONG_EDGE_PX,
  NEAR_DUPLICATE_MAX_DISTANCE,
  WORK_EDGE_PX,
  contrastStdDev,
  differenceHash,
  findMissingViews,
  findQualityIssues,
  hammingDistance,
  isBlurred,
  laplacianVariance,
  resampleGrey,
  type GreyPlane
} from "../lib/imageQuality";

function seededRandom(seed: number): () => number {
  let state = seed >>> 0;
  return () => {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

function plane(width: number, height: number, fill: (x: number, y: number) => number): GreyPlane {
  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) data[y * width + x] = fill(x, y);
  }
  return { data, width, height };
}

function checkerboard(width: number, height: number, cell: number, amplitude = 255): GreyPlane {
  const mid = 128 - amplitude / 2;
  return plane(width, height, (x, y) =>
    (Math.floor(x / cell) + Math.floor(y / cell)) % 2 === 0 ? mid + amplitude : mid
  );
}

function pinkNoise(width: number, height: number, seed: number, amplitude = 1): GreyPlane {
  const random = seededRandom(seed);
  const field = new Float64Array(width * height);
  for (let octave = 0; octave < 7; octave += 1) {
    const step = 1 << (6 - octave);
    const weight = 1 / (octave + 1);
    const cols = Math.ceil(width / step) + 2;
    const rows = Math.ceil(height / step) + 2;
    const grid = new Float64Array(cols * rows);
    for (let index = 0; index < grid.length; index += 1) grid[index] = random() * 2 - 1;
    for (let y = 0; y < height; y += 1) {
      const gy = y / step;
      const y0 = Math.floor(gy);
      const fy = gy - y0;
      for (let x = 0; x < width; x += 1) {
        const gx = x / step;
        const x0 = Math.floor(gx);
        const fx = gx - x0;
        const a = grid[y0 * cols + x0];
        const b = grid[y0 * cols + x0 + 1];
        const c = grid[(y0 + 1) * cols + x0];
        const d = grid[(y0 + 1) * cols + x0 + 1];
        const top = a + (b - a) * fx;
        const bottom = c + (d - c) * fx;
        field[y * width + x] += (top + (bottom - top) * fy) * weight;
      }
    }
  }
  let min = Infinity;
  let max = -Infinity;
  for (const value of field) {
    if (value < min) min = value;
    if (value > max) max = value;
  }
  const span = max - min || 1;
  return plane(width, height, (x, y) => {
    const normalised = (field[y * width + x] - min) / span;
    return 128 + (normalised - 0.5) * 255 * amplitude;
  });
}

function boxBlur(source: GreyPlane, radius: number, passes = 3): GreyPlane {
  let current = source;
  for (let pass = 0; pass < passes; pass += 1) {
    const horizontal = plane(current.width, current.height, (x, y) => {
      let total = 0;
      let count = 0;
      for (let offset = -radius; offset <= radius; offset += 1) {
        const sx = Math.min(current.width - 1, Math.max(0, x + offset));
        total += current.data[y * current.width + sx];
        count += 1;
      }
      return total / count;
    });
    current = plane(horizontal.width, horizontal.height, (x, y) => {
      let total = 0;
      let count = 0;
      for (let offset = -radius; offset <= radius; offset += 1) {
        const sy = Math.min(horizontal.height - 1, Math.max(0, y + offset));
        total += horizontal.data[sy * horizontal.width + x];
        count += 1;
      }
      return total / count;
    });
  }
  return current;
}

const W = WORK_EDGE_PX;
const H = Math.round((WORK_EDGE_PX * 3) / 4);

test.describe("imageQuality — measured calibration of the blur threshold", () => {
  test("sharp and blurred populations are separated by more than an order of magnitude", () => {
    const realistic = { photoLike: pinkNoise(W, H, 1337), photoLikeAlt: pinkNoise(W, H, 90210) };
    const synthetic = { checkerboard8: checkerboard(W, H, 8), checkerboard4: checkerboard(W, H, 4) };

    const score = (image: GreyPlane) => ({ blur: laplacianVariance(image), contrast: contrastStdDev(image) });
    const sharpScores = Object.fromEntries(Object.entries(realistic).map(([name, i]) => [name, score(i)]));
    const blurredScores = Object.fromEntries(
      Object.entries(realistic).flatMap(([name, image]) =>
        [1, 2, 4].map((radius) => [`${name}~blur${radius}`, score(boxBlur(image, radius))] as const)
      )
    );

    console.log("SHARP (photo-like) ", JSON.stringify(sharpScores));
    console.log("BLURRED            ", JSON.stringify(blurredScores));
    console.log(
      "SYNTHETIC CEILING  ",
      JSON.stringify(Object.fromEntries(Object.entries(synthetic).map(([n, i]) => [n, score(i)])))
    );

    const worstSharp = Math.min(...Object.values(sharpScores).map((entry) => entry.blur));
    const bestBlurred = Math.max(...Object.values(blurredScores).map((entry) => entry.blur));

    expect(worstSharp).toBeGreaterThan(BLUR_VARIANCE_FLOOR * 10);
    expect(bestBlurred).toBeLessThan(BLUR_VARIANCE_FLOOR);
    expect(worstSharp / Math.max(bestBlurred, 1)).toBeGreaterThan(100);

    for (const image of Object.values(synthetic)) {
      expect(laplacianVariance(image)).toBeGreaterThan(BLUR_VARIANCE_FLOOR * 100);
    }
  });

  test("a sharp but LOW-CONTRAST photograph is never called blurred", () => {
    const flat = pinkNoise(W, H, 1337, 0.28);
    const measured = { blurScore: laplacianVariance(flat), contrast: contrastStdDev(flat) };
    console.log("LOW-CONTRAST SHARP", JSON.stringify(measured));

    expect(measured.blurScore).toBeLessThan(BLUR_VARIANCE_FLOOR);
    expect(measured.contrast).toBeLessThan(MIN_CONTRAST_STDDEV);
    expect(isBlurred(measured)).toBe(false);
  });

  test("the contrast guard does not swallow a genuinely blurred photograph of a normal subject", () => {
    const source = pinkNoise(W, H, 1337);
    const soft = boxBlur(source, 4);
    const measured = { blurScore: laplacianVariance(soft), contrast: contrastStdDev(soft) };
    console.log("BLURRED NORMAL-CONTRAST", JSON.stringify(measured));
    expect(measured.contrast).toBeGreaterThan(MIN_CONTRAST_STDDEV);
    expect(isBlurred(measured)).toBe(true);
  });
});

test.describe("imageQuality — perceptual hashing", () => {
  test("identical, near-duplicate and unrelated images land in separated distance bands", () => {
    const a = pinkNoise(W, H, 1337);
    const b = pinkNoise(W, H, 90210);

    const hashA = differenceHash(a);
    const hashSelf = differenceHash(a);
    const nearly = boxBlur(a, 1, 1);
    const hashNear = differenceHash(nearly);
    const hashB = differenceHash(b);

    const identical = hammingDistance(hashA, hashSelf);
    const near = hammingDistance(hashA, hashNear);
    const unrelated = hammingDistance(hashA, hashB);
    console.log("HAMMING identical/near/unrelated", identical, near, unrelated);

    expect(identical).toBe(0);
    expect(near).toBeLessThanOrEqual(NEAR_DUPLICATE_MAX_DISTANCE);
    expect(unrelated).toBeGreaterThan(NEAR_DUPLICATE_MAX_DISTANCE * 3);
  });

  test("a downscale of the same photograph still hashes as the same shot", () => {
    const source = pinkNoise(W, H, 4242);
    const half = resampleGrey(source, Math.round(W / 2), Math.round(H / 2));
    const distance = hammingDistance(differenceHash(source), differenceHash(half));
    console.log("HAMMING full vs half-size", distance);
    expect(distance).toBeLessThanOrEqual(NEAR_DUPLICATE_MAX_DISTANCE);
  });
});

test.describe("imageQuality — findings", () => {
  const sharp = { width: 4000, height: 3000, blurScore: 900, contrast: 60, perceptualHash: "0".repeat(16), elapsedMs: 0 };

  test("a good photograph produces no findings at all", () => {
    expect(findQualityIssues({ measurement: sharp })).toEqual([]);
  });

  test("resolution is judged on the long edge and quotes both numbers", () => {
    const small = { ...sharp, width: 1024, height: 768 };
    const [finding] = findQualityIssues({ measurement: small });
    expect(finding.flag).toBe("LOW_RESOLUTION");
    expect(finding.message).toContain("1024x768");
    expect(finding.message).toContain(String(MIN_LONG_EDGE_PX));
    expect(findQualityIssues({ measurement: { ...sharp, width: 1300, height: 4000 } })).toEqual([]);
  });

  test("an exact duplicate is found by checksum lookup, and a missing checksum is never 'unique'", () => {
    const findings = findQualityIssues({
      measurement: sharp,
      checksum: "sha256:abc",
      attached: [{ label: "loom-front.jpg", checksum: "sha256:abc" }]
    });
    expect(findings).toHaveLength(1);
    expect(findings[0].flag).toBe("DUPLICATE");
    expect(findings[0].message).toContain("loom-front.jpg");

    expect(
      findQualityIssues({ measurement: sharp, checksum: "sha256:abc", attached: [{ label: "old.jpg", checksum: null }] })
    ).toEqual([]);
    expect(
      findQualityIssues({ measurement: sharp, checksum: null, attached: [{ label: "x.jpg", checksum: "sha256:abc" }] })
    ).toEqual([]);
  });

  test("a near-duplicate is reported only within the Hamming threshold", () => {
    const near = findQualityIssues({
      measurement: sharp,
      attached: [{ label: "same-shot.jpg", perceptualHash: "0".repeat(15) + "3" }]
    });
    expect(near[0]?.flag).toBe("DUPLICATE");
    expect(near[0]?.message).toContain("same-shot.jpg");

    const far = findQualityIssues({
      measurement: sharp,
      attached: [{ label: "other-product.jpg", perceptualHash: "f".repeat(16) }]
    });
    expect(far).toEqual([]);
  });

  test("exact and near duplicates are never reported twice about one file", () => {
    const findings = findQualityIssues({
      measurement: sharp,
      checksum: "sha256:abc",
      attached: [{ label: "twin.jpg", checksum: "sha256:abc", perceptualHash: "0".repeat(16) }]
    });
    expect(findings.filter((finding) => finding.flag === "DUPLICATE")).toHaveLength(1);
  });
});

test.describe("imageQuality — missing views, only where the registry has real slots", () => {
  test("stage 6's existingProduct reports a part-filled view set", () => {
    const findings = findMissingViews("existingProduct", { viewFront: "media-1", viewDetail: "media-2" });
    expect(findings).toHaveLength(1);
    expect(findings[0].flag).toBe("MISSING_VIEW");
    expect(findings[0].message).toContain("back view");
    expect(findings[0].message).toContain("2 of 3");
  });

  test("an untouched set and a complete set are both silent", () => {
    expect(findMissingViews("existingProduct", {})).toEqual([]);
    expect(
      findMissingViews("existingProduct", { viewFront: "a", viewBack: "b", viewDetail: "c" })
    ).toEqual([]);
  });

  test("entities with no named view slots produce nothing, invented or otherwise", () => {
    expect(findMissingViews("sketch", { image: "a" })).toEqual([]);
    expect(findMissingViews("finalProduct", { finalPhotos: ["a"] })).toEqual([]);
  });
});

async function browserModuleSource(): Promise<string> {
  const ts = await import("typescript");
  const source = readFileSync(resolve(process.cwd(), "lib/imageQuality.ts"), "utf8");
  const compiled = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext }
  }).outputText;
  return `${compiled}\nwindow.__imageQuality = { measureImageFile, findQualityIssues, laplacianVariance, WORK_EDGE_PX };`;
}

test.describe("imageQuality — the browser decode path and its on-device cost", () => {
  test("measures real encoded images, and a 12 MP photograph stays within the frame budget", async ({ page }) => {
    await page.goto("/");
    await page.addScriptTag({ content: await browserModuleSource(), type: "module" });
    await page.waitForFunction(() => Boolean((window as unknown as Record<string, unknown>).__imageQuality));

    const results = await page.evaluate(async () => {
      const api = (window as unknown as Record<string, unknown>).__imageQuality as {
        measureImageFile: (file: File) => Promise<{
          width: number;
          height: number;
          blurScore: number;
          contrast: number;
          perceptualHash: string;
          elapsedMs: number;
        } | null>;
      };

      async function makeJpeg(width: number, height: number, blurPx: number): Promise<File> {
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext("2d")!;
        context.fillStyle = "#f2efe6";
        context.fillRect(0, 0, width, height);
        const step = Math.max(2, Math.round(width / 160));
        for (let x = 0; x < width; x += step * 2) {
          context.fillStyle = "#3a2f2a";
          context.fillRect(x, 0, step, height);
        }
        for (let y = 0; y < height; y += step * 3) {
          context.fillStyle = "rgba(180,90,60,0.75)";
          context.fillRect(0, y, width, step);
        }
        context.fillStyle = "#101010";
        context.font = `${Math.round(height / 12)}px sans-serif`;
        for (let row = 0; row < 6; row += 1) {
          context.fillText("Bagru block print 12345", width * 0.05, height * (0.18 + row * 0.14));
        }

        let out = canvas;
        if (blurPx > 0) {
          const blurCanvas = document.createElement("canvas");
          blurCanvas.width = width;
          blurCanvas.height = height;
          const blurContext = blurCanvas.getContext("2d")!;
          blurContext.filter = `blur(${blurPx}px)`;
          blurContext.drawImage(canvas, 0, 0);
          out = blurCanvas;
        }
        const blob = await new Promise<Blob>((resolve) =>
          out.toBlob((value) => resolve(value!), "image/jpeg", 0.92)
        );
        return new File([blob], `generated-${width}x${height}-blur${blurPx}.jpg`, { type: "image/jpeg" });
      }

      const sharpSmall = await api.measureImageFile(await makeJpeg(1600, 1200, 0));
      const blurredSmall = await api.measureImageFile(await makeJpeg(1600, 1200, 6));

      const bigFile = await makeJpeg(4000, 3000, 0);
      const big = await api.measureImageFile(bigFile);

      const runs: number[] = [];
      for (let index = 0; index < 3; index += 1) {
        const measured = await api.measureImageFile(bigFile);
        if (measured) runs.push(measured.elapsedMs);
      }

      return { sharpSmall, blurredSmall, big, bigBytes: bigFile.size, runs };
    });

    console.log("BROWSER sharp 1600x1200 ", JSON.stringify(results.sharpSmall));
    console.log("BROWSER blurred 1600x1200", JSON.stringify(results.blurredSmall));
    console.log("BROWSER 12MP 4000x3000   ", JSON.stringify(results.big), "bytes:", results.bigBytes);
    console.log("BROWSER 12MP repeat runs (ms)", JSON.stringify(results.runs));

    expect(results.big?.width).toBe(4000);
    expect(results.big?.height).toBe(3000);
    expect(results.sharpSmall?.width).toBe(1600);

    expect(results.sharpSmall!.blurScore).toBeGreaterThan(BLUR_VARIANCE_FLOOR * 3);
    expect(results.blurredSmall!.blurScore).toBeLessThan(BLUR_VARIANCE_FLOOR);

    const slowest = Math.max(...results.runs);
    expect(slowest).toBeLessThan(1500);
  });
});
