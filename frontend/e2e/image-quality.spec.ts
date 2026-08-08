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

/**
 * Calibration and unit tests for lib/imageQuality.ts.
 *
 * WHY THE IMAGES ARE GENERATED AND NOT CHECKED IN. A threshold test that leans on binary fixtures
 * rots quietly: nobody can see what changed in a .jpg in a diff, and a fixture re-saved by an image
 * editor moves every score in the suite with no visible cause. Everything measured below is drawn
 * from code — a deterministic pattern, a box blur of that same pattern, a seeded 1/f noise field —
 * so the numbers can be re-derived from the source alone.
 *
 * WHY THE SCORES ARE ASSERTED AND NOT JUST THE BOOLEANS. `expect(isBlurred(sharp)).toBe(false)`
 * passes for a threshold of 1 and for a threshold of 100000; it proves nothing about where the line
 * is. The assertions below pin the measured VALUES and the gap between the sharp and blurred
 * populations, so the test fails if the measurement drifts even while the verdicts happen to survive
 * — which is the only way a threshold test is worth running.
 */

// ---------------------------------------------------------------------------
// Deterministic image generation (pure, no browser)
// ---------------------------------------------------------------------------

/** Mulberry32 — a tiny seeded PRNG, so "noise" is the same noise on every machine and every run. */
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

/** Hard-edged checkerboard: the highest-frequency content an 8-bit image can carry at this cell size. */
function checkerboard(width: number, height: number, cell: number, amplitude = 255): GreyPlane {
  const mid = 128 - amplitude / 2;
  return plane(width, height, (x, y) =>
    (Math.floor(x / cell) + Math.floor(y / cell)) % 2 === 0 ? mid + amplitude : mid
  );
}

/**
 * A 1/f ("pink") noise field — the standard statistical model of a natural photograph.
 *
 * This matters more than the checkerboard for calibration. Real photographs have most of their
 * energy at low spatial frequencies and comparatively little at the top of the band, so a synthetic
 * pattern made of hard edges scores an order of magnitude higher than any camera ever will. Setting
 * a threshold against checkerboards alone would put the line far above where real photographs live,
 * and every real photograph would be reported as blurred.
 */
function pinkNoise(width: number, height: number, seed: number, amplitude = 1): GreyPlane {
  const random = seededRandom(seed);
  const field = new Float64Array(width * height);
  // Sum successively finer octaves at successively lower weight: amplitude ∝ 1/frequency.
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
        // Bilinear interpolation between lattice points — a smooth octave, not blocky noise.
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

/**
 * A separable box blur of `radius`, applied `passes` times.
 *
 * Three passes of a box blur is the standard cheap approximation of a Gaussian, so this produces a
 * defocus that looks like a real one rather than the directional smear a single pass gives.
 */
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

/** The working size every measurement is taken at, so the numbers below are the real ones. */
const W = WORK_EDGE_PX;
const H = Math.round((WORK_EDGE_PX * 3) / 4);

// ---------------------------------------------------------------------------
// The measured corpus
// ---------------------------------------------------------------------------

test.describe("imageQuality — measured calibration of the blur threshold", () => {
  test("sharp and blurred populations are separated by more than an order of magnitude", () => {
    /**
     * The 1/f fields are the population that decides the threshold, because they are the only ones
     * here with the spatial statistics of a photograph. The checkerboards are kept as a synthetic
     * CEILING — they show how much headroom exists above a real photograph — and deliberately take
     * no part in the assertions: a hard checkerboard softened by one pixel is not a blurred
     * photograph, it is a slightly softer checkerboard, and it still scores in the thousands.
     */
    const realistic = { photoLike: pinkNoise(W, H, 1337), photoLikeAlt: pinkNoise(W, H, 90210) };
    const synthetic = { checkerboard8: checkerboard(W, H, 8), checkerboard4: checkerboard(W, H, 4) };

    const score = (image: GreyPlane) => ({ blur: laplacianVariance(image), contrast: contrastStdDev(image) });
    const sharpScores = Object.fromEntries(Object.entries(realistic).map(([name, i]) => [name, score(i)]));
    const blurredScores = Object.fromEntries(
      Object.entries(realistic).flatMap(([name, image]) =>
        [1, 2, 4].map((radius) => [`${name}~blur${radius}`, score(boxBlur(image, radius))] as const)
      )
    );

    // Printed so a future reader can see the corpus this threshold was actually set against,
    // without having to re-derive it.
    console.log("SHARP (photo-like) ", JSON.stringify(sharpScores));
    console.log("BLURRED            ", JSON.stringify(blurredScores));
    console.log(
      "SYNTHETIC CEILING  ",
      JSON.stringify(Object.fromEntries(Object.entries(synthetic).map(([n, i]) => [n, score(i)])))
    );

    const worstSharp = Math.min(...Object.values(sharpScores).map((entry) => entry.blur));
    const bestBlurred = Math.max(...Object.values(blurredScores).map((entry) => entry.blur));

    // THE ASSERTION THAT MATTERS: the threshold sits strictly between the two populations, and the
    // sharp side clears it by the wide margin the constant's comment claims. If a change to the
    // measurement narrows this gap, this fails long before a designer sees a false warning.
    expect(worstSharp).toBeGreaterThan(BLUR_VARIANCE_FLOOR * 10);
    expect(bestBlurred).toBeLessThan(BLUR_VARIANCE_FLOOR);
    expect(worstSharp / Math.max(bestBlurred, 1)).toBeGreaterThan(100);

    // Sharp synthetic patterns are far above the line too — no configuration of this measure should
    // ever call a hard-edged, high-contrast image blurred.
    for (const image of Object.values(synthetic)) {
      expect(laplacianVariance(image)).toBeGreaterThan(BLUR_VARIANCE_FLOOR * 100);
    }
  });

  test("a sharp but LOW-CONTRAST photograph is never called blurred", () => {
    // The dangerous false positive: undyed cotton on a white sheet is sharp and nearly flat, so its
    // Laplacian variance is low for reasons that have nothing to do with focus. The amplitude here
    // is chosen to land the sample just INSIDE the trap — a blur score below the floor — so the test
    // proves the guard is doing the work rather than testing an image that would have passed anyway.
    const flat = pinkNoise(W, H, 1337, 0.28);
    const measured = { blurScore: laplacianVariance(flat), contrast: contrastStdDev(flat) };
    console.log("LOW-CONTRAST SHARP", JSON.stringify(measured));

    // It really does score below the blur floor — that is the trap, stated as a measurement.
    expect(measured.blurScore).toBeLessThan(BLUR_VARIANCE_FLOOR);
    expect(measured.contrast).toBeLessThan(MIN_CONTRAST_STDDEV);
    // ...and the contrast guard is the only thing stopping it becoming a warning.
    expect(isBlurred(measured)).toBe(false);
  });

  test("the contrast guard does not swallow a genuinely blurred photograph of a normal subject", () => {
    const source = pinkNoise(W, H, 1337);
    const soft = boxBlur(source, 4);
    const measured = { blurScore: laplacianVariance(soft), contrast: contrastStdDev(soft) };
    console.log("BLURRED NORMAL-CONTRAST", JSON.stringify(measured));
    // Blurring barely touches contrast (it is a low-frequency property), so the guard stays open.
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
    // "The same shot twice": the same subject, very slightly softer and shifted a pixel.
    const nearly = boxBlur(a, 1, 1);
    const hashNear = differenceHash(nearly);
    const hashB = differenceHash(b);

    const identical = hammingDistance(hashA, hashSelf);
    const near = hammingDistance(hashA, hashNear);
    const unrelated = hammingDistance(hashA, hashB);
    console.log("HAMMING identical/near/unrelated", identical, near, unrelated);

    expect(identical).toBe(0);
    expect(near).toBeLessThanOrEqual(NEAR_DUPLICATE_MAX_DISTANCE);
    // Unrelated images must clear the threshold by a wide margin, or the check would report two
    // different products as the same shot — the false positive that discredits the whole feature.
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
    // A portrait photograph of the same pixel count is judged on its LONG edge, so it passes.
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

    // A legacy row with no checksum must not match, and must not be reported as anything.
    expect(
      findQualityIssues({ measurement: sharp, checksum: "sha256:abc", attached: [{ label: "old.jpg", checksum: null }] })
    ).toEqual([]);
    // Neither may an unknown checksum on OUR side produce a claim.
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
    // Stage 11's sketch has a single `image`; stage 16's finalProduct has galleries. Neither has
    // named views, and this check must never claim otherwise.
    expect(findMissingViews("sketch", { image: "a" })).toEqual([]);
    expect(findMissingViews("finalProduct", { finalPhotos: ["a"] })).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// The browser half: the real decode path, and what it actually costs
// ---------------------------------------------------------------------------

/**
 * The module is compiled and injected rather than imported by the page, because there is no route
 * that exposes it and adding one would be app surface built purely for a test. `transpileModule`
 * only strips types, so what runs in the browser is this module's real source.
 */
async function browserModuleSource(): Promise<string> {
  const ts = await import("typescript");
  // Relative to the Playwright root (frontend/), which is where the runner is invoked from.
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

      /** Draw a detailed, high-frequency scene, optionally through a real Gaussian blur. */
      async function makeJpeg(width: number, height: number, blurPx: number): Promise<File> {
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext("2d")!;
        context.fillStyle = "#f2efe6";
        context.fillRect(0, 0, width, height);
        // A deterministic woven-cloth-like pattern: fine stripes, blocks and text, i.e. exactly the
        // sort of detail a craft photograph carries and a blur destroys.
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

      // A full 12 MP frame, the size a modern handset actually produces.
      const bigFile = await makeJpeg(4000, 3000, 0);
      const big = await api.measureImageFile(bigFile);

      // Timed again, separately from the first (cold) run, so the number is not a one-shot outlier.
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

    // The decode path reports the ORIGINAL pixel dimensions, which is what the resolution check and
    // the report's plate sizing both depend on — not the working size it measured at.
    expect(results.big?.width).toBe(4000);
    expect(results.big?.height).toBe(3000);
    expect(results.sharpSmall?.width).toBe(1600);

    // The real, encoded, browser-decoded versions separate exactly as the synthetic ones do.
    expect(results.sharpSmall!.blurScore).toBeGreaterThan(BLUR_VARIANCE_FLOOR * 3);
    expect(results.blurredSmall!.blurScore).toBeLessThan(BLUR_VARIANCE_FLOOR);

    // THE ON-DEVICE BUDGET. This runs on a desktop CI machine, so the number here is not a phone's
    // number; the ceiling is set well above the measured desktop cost precisely so it stays a
    // regression guard (it catches "somebody made this convolve at full size") rather than a
    // hardware benchmark that fails on a slow agent.
    const slowest = Math.max(...results.runs);
    expect(slowest).toBeLessThan(1500);
  });
});
