import { expect, test } from "@playwright/test";

import { type GreyPlane } from "../lib/imageQuality";
import { applyHomography, type Point } from "../lib/photoMeasure";
import {
  type Corners,
  GUESS_MIN_FILL,
  RECTIFY_MAX_EDGE_PX,
  bilinearSample,
  globalThreshold,
  guessSheetCorners,
  makePlate,
  orderCorners,
  otsuThreshold,
  rectifiedSize,
  rectifyHomography,
  rectifyPlane,
  sauvolaThreshold,
  sauvolaWindow
} from "../lib/sketchRectify";

/**
 * Unit tests for lib/sketchRectify.ts — the geometry and the statistics behind "make a plate out of a
 * photograph of a sketch".
 *
 * WHY SYNTHETIC IMAGES AND NOT PHOTOGRAPHS, the same argument as e2e/photo-measure.spec.ts: every case
 * below is CONSTRUCTED from its answer. The rectification cases warp a plane whose value at every
 * point is known in closed form, so the expected output is computed rather than recorded. The
 * thresholding case builds a page whose illumination, paper reflectance and stroke reflectance are
 * each a number chosen in advance, so "the strokes were recovered" is a count against a mask rather
 * than a judgement about how a picture looks. A test against a real photograph could only assert that
 * the module still prints whatever it printed the day it was written.
 *
 * THE THRESHOLDING CASE IS THE ONE THAT MATTERS. Everything else here checks that arithmetic was typed
 * correctly. `sketchRectify — a lamp gradient defeats a global threshold` is the test that justifies
 * the existence of the local method at all: it runs BOTH methods over the same page and asserts the
 * difference between them, so if somebody ever replaces the local threshold with a global one to
 * simplify the file, the suite says exactly what was lost and where.
 */

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function plane(width: number, height: number, value: (x: number, y: number) => number): GreyPlane {
  const data = new Uint8ClampedArray(width * height);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) data[y * width + x] = value(x, y);
  }
  return { data, width, height };
}

function at(target: GreyPlane, x: number, y: number): number {
  return target.data[y * target.width + x];
}

/**
 * A deliberately awkward projective transform with a NON-ZERO bottom row, so parallel lines in the
 * world genuinely converge in the image.
 *
 * The same reasoning as the `TILT` fixture in e2e/photo-measure.spec.ts: an affine transform
 * (h31 = h32 = 0) is recovered perfectly by code containing no projective handling whatsoever, so a
 * test built on one proves nothing about the part that is hard.
 */
const TILT = [1.1, 0.22, 140, 0.13, 1.05, 90, 0.00042, 0.00026, 1] as const;

// ---------------------------------------------------------------------------
// Corner ordering
// ---------------------------------------------------------------------------

test.describe("sketchRectify — corner ordering", () => {
  test("puts any click order into TL, TR, BR, BL", () => {
    const marks: Point[] = [
      { x: 320, y: 40 }, // top right
      { x: 30, y: 220 }, // bottom left
      { x: 20, y: 30 }, // top left
      { x: 350, y: 260 } // bottom right
    ];
    const ordered = orderCorners(marks);
    expect(ordered).not.toBeNull();
    expect(ordered).toEqual([
      { x: 20, y: 30 },
      { x: 320, y: 40 },
      { x: 350, y: 260 },
      { x: 30, y: 220 }
    ]);
  });

  test("makes the reading-order bow-tie unrepresentable", () => {
    // TL, TR, BOTTOM-LEFT, BOTTOM-RIGHT — the natural reading order, and a crossed quadrilateral.
    // photoMeasure REFUSES this configuration; this module reorders it into the sheet the designer
    // plainly meant, and the test is that the result is the non-crossing ring rather than the input.
    const readingOrder: Point[] = [
      { x: 10, y: 10 },
      { x: 200, y: 10 },
      { x: 10, y: 150 },
      { x: 200, y: 150 }
    ];
    const ordered = orderCorners(readingOrder) as Corners;
    expect(ordered).toEqual([
      { x: 10, y: 10 },
      { x: 200, y: 10 },
      { x: 200, y: 150 },
      { x: 10, y: 150 }
    ]);

    // The signed area of the ring is what proves it does not cross: a bow-tie's two lobes cancel, so
    // its shoelace sum is far below the true area. Here it must equal the full rectangle, 190 x 140.
    let shoelace = 0;
    for (let index = 0; index < 4; index += 1) {
      const current = ordered[index];
      const next = ordered[(index + 1) % 4];
      shoelace += current.x * next.y - next.x * current.y;
    }
    expect(Math.abs(shoelace) / 2).toBeCloseTo(190 * 140, 6);
  });

  test("refuses anything that is not four finite points", () => {
    expect(orderCorners([{ x: 0, y: 0 }])).toBeNull();
    expect(
      orderCorners([
        { x: 0, y: 0 },
        { x: 1, y: 0 },
        { x: 1, y: 1 },
        { x: Number.NaN, y: 1 }
      ])
    ).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Bilinear resampling — against values computed by hand
// ---------------------------------------------------------------------------

test.describe("sketchRectify — bilinear resampling", () => {
  /**
   * A 2x2 plane, so every expected value below is one line of arithmetic:
   *
   *     (0,0) = 0     (1,0) = 100
   *     (0,1) = 200   (1,1) = 255
   */
  const tiny: GreyPlane = { data: new Uint8ClampedArray([0, 100, 200, 255]), width: 2, height: 2 };

  test("reproduces the sample points exactly", () => {
    expect(bilinearSample(tiny, 0, 0)).toBe(0);
    expect(bilinearSample(tiny, 1, 0)).toBe(100);
    expect(bilinearSample(tiny, 0, 1)).toBe(200);
    expect(bilinearSample(tiny, 1, 1)).toBe(255);
  });

  test("matches hand-computed interpolations", () => {
    // Halfway along the top edge: (0 + 100) / 2.
    expect(bilinearSample(tiny, 0.5, 0)).toBeCloseTo(50, 12);
    // Halfway down the left edge: (0 + 200) / 2.
    expect(bilinearSample(tiny, 0, 0.5)).toBeCloseTo(100, 12);
    // Dead centre: the mean of all four, (0 + 100 + 200 + 255) / 4.
    expect(bilinearSample(tiny, 0.5, 0.5)).toBeCloseTo(138.75, 12);
    // A quarter along the top: 0 + (100 - 0) * 0.25.
    expect(bilinearSample(tiny, 0.25, 0)).toBeCloseTo(25, 12);
    // Three quarters across, one quarter down — the full tensor product:
    //   upper = 0   + (100 - 0)   * 0.75 = 75
    //   lower = 200 + (255 - 200) * 0.75 = 241.25
    //   value = 75  + (241.25 - 75) * 0.25 = 116.5625
    expect(bilinearSample(tiny, 0.75, 0.25)).toBeCloseTo(116.5625, 12);
  });

  /**
   * A stroke must not come out of rectification in pieces.
   *
   * WHAT THIS PINS, PRECISELY, because it is less than it first appears and the difference was
   * measured rather than assumed. This is a regression guard on STROKE CONTINUITY THROUGH THE WHOLE
   * RESAMPLING PATH — it fails if the geometry drifts, if the sampler starts reading the wrong
   * neighbourhood, or if a future change begins skipping output rows. It does NOT prove that bilinear
   * is necessary: it was run against a nearest-neighbour implementation of {@link bilinearSample} and
   * PASSED, because a three-pixel stroke is wide enough to survive either sampler at this scale.
   *
   * The test that does discriminate the two is `matches hand-computed interpolations` above — under
   * nearest neighbour it reads 100 where the arithmetic says 50. That, not this, is the evidence for
   * the resampler; this is the evidence that a stroke drawn on paper is still one stroke on the plate.
   *
   * The honest limit is worth recording too: bilinear point-sampling cannot carry a ONE-pixel feature
   * through a downscale, and a one-pixel line is below the 3–6px range SAUVOLA_WINDOW_FRACTION's
   * comment claims for a pencil line at working resolution. A sketch photographed so small that its
   * strokes land on single pixels is under-resolved before this module sees it, which is the
   * condition `imageQuality.MIN_LONG_EDGE_PX` already warns about at attach time.
   */
  test("keeps a thin diagonal stroke continuous instead of dashing it", () => {
    // A three-pixel black diagonal on white, resampled through a mild rotation-like tilt. Three
    // pixels because that is the bottom of the range SAUVOLA_WINDOW_FRACTION's comment claims for a
    // pencil line at working resolution — a one-pixel line is below what this module says it handles,
    // and bilinear point-sampling genuinely cannot carry a one-pixel feature through a downscale.
    const source = plane(300, 300, (x, y) => (Math.abs(x - y) < 1.5 ? 0 : 255));
    const marked: Corners = [
      { x: 30, y: 12 },
      { x: 268, y: 34 },
      { x: 262, y: 276 },
      { x: 18, y: 258 }
    ];
    const rectified = rectifyPlane(source, marked, 220, 220)!;

    // Every row of the plate that the stroke crosses must contain a solidly dark pixel. A dashed
    // result leaves whole rows at paper white.
    // Rows 25..195 only: the diagonal enters the marked quadrilateral through its left edge at about
    // plate row 15 and leaves through the right edge at about row 206, so rows outside that span are
    // legitimately blank and counting them would be asserting against the fixture's own geometry.
    let emptyRows = 0;
    for (let y = 25; y < 195; y += 1) {
      let darkest = 255;
      for (let x = 0; x < 220; x += 1) darkest = Math.min(darkest, at(rectified, x, y));
      if (darkest > 128) emptyRows += 1;
    }
    expect(emptyRows).toBe(0);
  });

  test("reads paper white outside the photograph rather than smearing the edge", () => {
    // Beyond the half-pixel border there is genuinely no photograph. See OUTSIDE_VALUE.
    expect(bilinearSample(tiny, -0.6, 0)).toBe(255);
    expect(bilinearSample(tiny, 1.6, 0)).toBe(255);
    expect(bilinearSample(tiny, 0, -0.51)).toBe(255);
    expect(bilinearSample(tiny, 0, 1.51)).toBe(255);
    expect(bilinearSample(tiny, Number.NaN, 0)).toBe(255);
    // Inside the border the nearest real pixel is the honest answer, not the outside value.
    expect(bilinearSample(tiny, -0.5, 0)).toBeCloseTo(0, 12);
    expect(bilinearSample(tiny, -0.25, 0)).toBeCloseTo(0, 12);
  });
});

// ---------------------------------------------------------------------------
// The homography, as this module uses it
// ---------------------------------------------------------------------------

test.describe("sketchRectify — rectifying homography", () => {
  test("carries the plate's own corners onto the marked corners", () => {
    const marked: Corners = [
      { x: 140, y: 90 },
      { x: 470, y: 118 },
      { x: 505, y: 372 },
      { x: 96, y: 340 }
    ];
    const width = 200;
    const height = 300;
    const homography = rectifyHomography(marked, width, height);
    expect(homography).not.toBeNull();

    const corners: Point[] = [
      { x: 0, y: 0 },
      { x: width - 1, y: 0 },
      { x: width - 1, y: height - 1 },
      { x: 0, y: height - 1 }
    ];
    corners.forEach((corner, index) => {
      const mapped = applyHomography(homography!, corner);
      expect(mapped.x).toBeCloseTo(marked[index].x, 9);
      expect(mapped.y).toBeCloseTo(marked[index].y, 9);
    });
  });

  /**
   * THE FIFTH POINT IS THE WHOLE TEST. Any solve that returns anything at all reproduces the four
   * correspondences it was fitted to — that is what "fitted" means, and a completely wrong
   * implementation still passes the case above. A point that took no part in the fit is the only
   * evidence that what came back is the transform rather than a curve through four dots.
   */
  test("recovers a known projective transform at a point that took no part in the fit", () => {
    const width = 210;
    const height = 297;
    const plateCorners: Point[] = [
      { x: 0, y: 0 },
      { x: width - 1, y: 0 },
      { x: width - 1, y: height - 1 },
      { x: 0, y: height - 1 }
    ];
    // Project the plate's corners through TILT to make the "marks" a designer would have clicked.
    const project = (point: Point): Point => {
      const w = TILT[6] * point.x + TILT[7] * point.y + TILT[8];
      return {
        x: (TILT[0] * point.x + TILT[1] * point.y + TILT[2]) / w,
        y: (TILT[3] * point.x + TILT[4] * point.y + TILT[5]) / w
      };
    };
    const marked = plateCorners.map(project) as unknown as Corners;

    const recovered = rectifyHomography(marked, width, height);
    expect(recovered).not.toBeNull();

    // A point deep inside the sheet, on no edge and no diagonal, so it is constrained by nothing that
    // was fitted.
    for (const probe of [
      { x: 73, y: 129 },
      { x: 151, y: 44 },
      { x: 19, y: 268 }
    ]) {
      const expected = project(probe);
      const actual = applyHomography(recovered!, probe);
      expect(actual.x).toBeCloseTo(expected.x, 9);
      expect(actual.y).toBeCloseTo(expected.y, 9);
    }
  });

  test("refuses corners that lie in a straight line", () => {
    const collinear: Corners = [
      { x: 0, y: 0 },
      { x: 50, y: 50 },
      { x: 100, y: 100 },
      { x: 150, y: 150 }
    ];
    expect(rectifyHomography(collinear, 100, 100)).toBeNull();
    expect(rectifyPlane(plane(20, 20, () => 128), collinear, 100, 100)).toBeNull();
    const refusal = makePlate(plane(20, 20, () => 128), collinear);
    expect(refusal.ok).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Resampling the whole plane
// ---------------------------------------------------------------------------

test.describe("sketchRectify — resampling", () => {
  /**
   * A source whose value is a LINEAR function of its own pixel coordinates.
   *
   * Chosen because bilinear interpolation is EXACT for such a function — it is a tensor-product linear
   * interpolant — so the expected value at every output pixel is `a·px + b·py + c` evaluated at the
   * projected source point, computed in closed form rather than sampled. Any error the test sees is
   * therefore the geometry being wrong, not interpolation blur being blamed on it.
   */
  test("lands every output pixel on the source point the homography names", () => {
    const a = 0.11;
    const b = 0.07;
    const c = 30;
    const source = plane(600, 450, (x, y) => a * x + b * y + c);

    const marked: Corners = [
      { x: 90, y: 60 },
      { x: 470, y: 95 },
      { x: 512, y: 372 },
      { x: 61, y: 331 }
    ];
    const width = 160;
    const height = 220;
    const rectified = rectifyPlane(source, marked, width, height);
    expect(rectified).not.toBeNull();
    const homography = rectifyHomography(marked, width, height)!;

    let worst = 0;
    for (let y = 0; y < height; y += 7) {
      for (let x = 0; x < width; x += 5) {
        const point = applyHomography(homography, { x, y });
        const expected = a * point.x + b * point.y + c;
        worst = Math.max(worst, Math.abs(at(rectified!, x, y) - expected));
      }
    }
    // One count of an 8-bit channel: the only loss left is Uint8ClampedArray rounding the exact
    // interpolated value to an integer.
    expect(worst).toBeLessThanOrEqual(1);
  });

  test("straightens a tilted rectangle back into a rectangle", () => {
    // A black bar across the middle of a world sheet, projected through TILT into an image, then
    // rectified. In the world the bar is horizontal; in the image it is not; after rectification it
    // must be horizontal again — which is the entire user-visible promise of the feature.
    const worldWidth = 200;
    const worldHeight = 260;
    const barTop = 100;
    const barBottom = 130;
    const project = (point: Point): Point => {
      const w = TILT[6] * point.x + TILT[7] * point.y + TILT[8];
      return {
        x: (TILT[0] * point.x + TILT[1] * point.y + TILT[2]) / w,
        y: (TILT[3] * point.x + TILT[4] * point.y + TILT[5]) / w
      };
    };
    const worldCorners: Point[] = [
      { x: 0, y: 0 },
      { x: worldWidth - 1, y: 0 },
      { x: worldWidth - 1, y: worldHeight - 1 },
      { x: 0, y: worldHeight - 1 }
    ];
    const marked = worldCorners.map(project) as unknown as Corners;
    // The image is built by SCATTERING the world through `project` at quarter-pixel steps — dense
    // enough that the bar lands solid, and gaps elsewhere do not matter because the assertion below is
    // about which ROWS of the rectified plate are dark, not about pixel-perfect coverage.
    const image = plane(700, 560, () => 255);
    for (let wy = 0; wy < worldHeight; wy += 0.25) {
      for (let wx = 0; wx < worldWidth; wx += 0.25) {
        const value = wy >= barTop && wy <= barBottom ? 0 : 255;
        if (value === 255) continue;
        const point = project({ x: wx, y: wy });
        const px = Math.round(point.x);
        const py = Math.round(point.y);
        if (px < 0 || py < 0 || px >= image.width || py >= image.height) continue;
        image.data[py * image.width + px] = 0;
      }
    }

    const rectified = rectifyPlane(image, marked, worldWidth, worldHeight)!;
    // Every row inside the bar must be almost entirely black; every row well outside it almost
    // entirely white. A bar still tilted would make both counts middling on the rows near its ends.
    const darkFraction = (row: number): number => {
      let dark = 0;
      for (let x = 5; x < worldWidth - 5; x += 1) if (at(rectified, x, row) < 128) dark += 1;
      return dark / (worldWidth - 10);
    };
    expect(darkFraction(barTop + 8)).toBeGreaterThan(0.95);
    expect(darkFraction(Math.floor((barTop + barBottom) / 2))).toBeGreaterThan(0.95);
    expect(darkFraction(barBottom - 8)).toBeGreaterThan(0.95);
    expect(darkFraction(barTop - 20)).toBeLessThan(0.05);
    expect(darkFraction(barBottom + 20)).toBeLessThan(0.05);
  });
});

// ---------------------------------------------------------------------------
// Output sizing
// ---------------------------------------------------------------------------

test.describe("sketchRectify — plate sizing", () => {
  test("takes the longer of each pair of opposite edges", () => {
    const corners: Corners = [
      { x: 0, y: 0 },
      { x: 400, y: 0 },
      { x: 300, y: 200 },
      { x: 0, y: 200 }
    ];
    // Top edge 400, bottom edge 300 -> width 400. Left edge 200, right edge sqrt(100^2+200^2)=223.6.
    const size = rectifiedSize(corners);
    expect(size).toEqual({ width: 400, height: 224 });
  });

  test("never upscales a sheet that occupied few pixels", () => {
    const small: Corners = [
      { x: 0, y: 0 },
      { x: 300, y: 0 },
      { x: 300, y: 200 },
      { x: 0, y: 200 }
    ];
    const size = rectifiedSize(small, { maxEdge: RECTIFY_MAX_EDGE_PX })!;
    expect(size.width).toBe(300);
    expect(size.height).toBe(200);
  });

  test("honours a declared sheet proportion without enlarging the plate", () => {
    const corners: Corners = [
      { x: 0, y: 0 },
      { x: 400, y: 0 },
      { x: 400, y: 400 },
      { x: 0, y: 400 }
    ];
    // A4 portrait. The estimate was square; the declared proportion is narrower, so the WIDTH comes
    // in and the height stays — the plate never grows to satisfy an aspect.
    const size = rectifiedSize(corners, { aspect: 210 / 297 })!;
    expect(size.height).toBe(400);
    expect(size.width).toBe(Math.round(400 * (210 / 297)));
  });

  test("clamps the long edge to the ceiling", () => {
    const huge: Corners = [
      { x: 0, y: 0 },
      { x: 4000, y: 0 },
      { x: 4000, y: 3000 },
      { x: 0, y: 3000 }
    ];
    const size = rectifiedSize(huge)!;
    expect(Math.max(size.width, size.height)).toBe(RECTIFY_MAX_EDGE_PX);
    expect(size.height / size.width).toBeCloseTo(3000 / 4000, 2);
  });
});

// ---------------------------------------------------------------------------
// THE JUSTIFICATION: local thresholding against a lamp gradient
// ---------------------------------------------------------------------------

/**
 * A page as a tube light on one wall actually leaves it.
 *
 * ILLUMINATION IS MULTIPLICATIVE, WHICH IS THE WHOLE REASON A GLOBAL THRESHOLD CANNOT WORK. What a
 * camera records is illumination times reflectance. The paper's reflectance is a constant of the
 * paper and the pencil's is a constant of the pencil, but the illumination ramps across the sheet, so
 * the RECORDED value of a pencil stroke at the bright end (0.38 x 255 = 97) is higher than the
 * recorded value of BLANK PAPER at the dark end (0.92 x 70 = 64). No single number can put those two
 * on the correct sides of itself. That is not a hard case for a global threshold; it is a
 * contradiction, and it is what the field photographs actually look like.
 */
function lampLitPage(): { page: GreyPlane; strokes: Uint8Array } {
  const width = 400;
  const height = 200;
  const paperReflectance = 0.92;
  const inkReflectance = 0.38;
  const strokes = new Uint8Array(width * height);
  const page = plane(width, height, (x, y) => {
    const illumination = 70 + (185 * x) / (width - 1);
    // Vertical strokes every 40px, three pixels wide, all the way down the page — so both halves of
    // the page carry the same drawing and any difference in the result is the method, not the input.
    const isStroke = x % 40 >= 18 && x % 40 <= 20 && y > 10 && y < height - 10;
    if (isStroke) strokes[y * width + x] = 1;
    return illumination * (isStroke ? inkReflectance : paperReflectance);
  });
  return { page, strokes };
}

/** Fraction of the pixels in `mask` that came out black, over the given horizontal band. */
function blackFraction(result: GreyPlane, mask: Uint8Array, wanted: 0 | 1, fromX: number, toX: number): number {
  let hits = 0;
  let total = 0;
  for (let y = 0; y < result.height; y += 1) {
    for (let x = fromX; x < toX; x += 1) {
      const index = y * result.width + x;
      if (mask[index] !== wanted) continue;
      total += 1;
      if (result.data[index] < 128) hits += 1;
    }
  }
  return total === 0 ? 0 : hits / total;
}

test.describe("sketchRectify — a lamp gradient defeats a global threshold", () => {
  test("the local method recovers the strokes at both ends and leaves the paper clean", () => {
    const { page, strokes } = lampLitPage();
    const half = page.width / 2;
    const local = sauvolaThreshold(page);

    // Strokes recovered in the DARK half and in the BRIGHT half alike.
    expect(blackFraction(local, strokes, 1, 0, half)).toBeGreaterThan(0.9);
    expect(blackFraction(local, strokes, 1, half, page.width)).toBeGreaterThan(0.9);
    // And the blank paper stayed blank at both ends — the Niblack failure this method exists to avoid.
    expect(blackFraction(local, strokes, 0, 0, half)).toBeLessThan(0.05);
    expect(blackFraction(local, strokes, 0, half, page.width)).toBeLessThan(0.05);
  });

  test("the best global threshold turns the dark half of the page black", () => {
    const { page, strokes } = lampLitPage();
    const half = page.width / 2;
    // Otsu, not a hand-picked level and not the plain mean: the comparison is against the strongest
    // global method there is, so "a global threshold cannot do this" is not a strawman.
    const global = globalThreshold(page, otsuThreshold(page));

    // The blank paper of the dark half comes out as ink. This is the failure in one line.
    expect(blackFraction(global, strokes, 0, 0, half)).toBeGreaterThan(0.5);
  });

  /**
   * The assertion that is the point of the whole file: the two methods are run over the SAME page and
   * the difference between them is pinned. Replacing the local threshold with a global one to
   * "simplify" the module fails here, and the failure names the damage.
   */
  test("the difference between the two methods is the feature", () => {
    const { page, strokes } = lampLitPage();
    const half = page.width / 2;
    const local = sauvolaThreshold(page);
    const global = globalThreshold(page, otsuThreshold(page));

    const localFlood = blackFraction(local, strokes, 0, 0, half);
    const globalFlood = blackFraction(global, strokes, 0, 0, half);
    // Not "a bit better" — more than an order of magnitude less blank paper wrongly inked.
    expect(globalFlood).toBeGreaterThan(localFlood * 10);
    expect(globalFlood - localFlood).toBeGreaterThan(0.45);
  });

  test("blank paper does not come out as speckle", () => {
    // The Sauvola-over-Niblack case, isolated: a page with a lamp gradient and NO strokes at all must
    // threshold to entirely white. Niblack's `m + k*s` would put roughly half the sensor noise below
    // the threshold and produce a page of dirt.
    const blank = plane(240, 180, (x) => (70 + (185 * x) / 239) * 0.92);
    const result = sauvolaThreshold(blank);
    let dark = 0;
    for (let index = 0; index < result.data.length; index += 1) if (result.data[index] < 128) dark += 1;
    expect(dark).toBe(0);
  });

  test("the window is odd and comfortably wider than a pencil stroke", () => {
    // The constraint SAUVOLA_WINDOW_FRACTION exists to satisfy — a window narrower than a stroke makes
    // the local mean equal the ink and erases the stroke it was supposed to find.
    const window = sauvolaWindow(plane(1200, 900, () => 200));
    expect(window % 2).toBe(1);
    expect(window).toBeGreaterThan(6 * 3);
  });
});

// ---------------------------------------------------------------------------
// The automatic corner guess
// ---------------------------------------------------------------------------

test.describe("sketchRectify — automatic corner guess", () => {
  /** A bright convex quadrilateral (the sheet) on a dark background (the table). */
  function sheetOnTable(corners: Point[], width = 480, height = 360): GreyPlane {
    const inside = (x: number, y: number): boolean => {
      let sign = 0;
      for (let index = 0; index < corners.length; index += 1) {
        const a = corners[index];
        const b = corners[(index + 1) % corners.length];
        const cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x);
        const current = cross > 0 ? 1 : cross < 0 ? -1 : 0;
        if (current === 0) continue;
        if (sign === 0) sign = current;
        else if (sign !== current) return false;
      }
      return true;
    };
    return plane(width, height, (x, y) => (inside(x, y) ? 225 : 55));
  }

  test("finds a tilted sheet and reports it as confident", () => {
    const truth: Point[] = [
      { x: 70, y: 40 },
      { x: 420, y: 70 },
      { x: 400, y: 320 },
      { x: 50, y: 290 }
    ];
    const guess = guessSheetCorners(sheetOnTable(truth));
    expect(guess).not.toBeNull();
    expect(guess!.fill).toBeGreaterThan(GUESS_MIN_FILL);
    // Located to a few pixels. The guess is a starting position for four draggable handles, not a
    // committed answer, so this tolerance is the honest one — see guessSheetCorners.
    guess!.corners.forEach((corner, index) => {
      expect(Math.hypot(corner.x - truth[index].x, corner.y - truth[index].y)).toBeLessThan(12);
    });
  });

  /**
   * A sheet with the drawing ON it, which is the only kind there is.
   *
   * THIS CASE WAS A REAL BUG AND IT WAS INVISIBLE TO EVERY OTHER TEST HERE, because every other
   * fixture in this block is a blank bright quadrilateral. The sheet is found as the largest
   * CONNECTED bright region, and a line ruled from one edge of the paper to the other — a border, a
   * fold, a construction line — cuts that region in two. The search then returned the larger BAND as
   * the sheet: a confident, plausible, wrong answer that rectified a slice of the drawing. It was
   * caught by running the guess against a generated photograph of an actual sketch
   * (e2e/fixtures/sketch-on-table.png) rather than against a rectangle.
   *
   * `closeBrightMask` is the fix, and this is the test that fails without it.
   */
  test("finds the sheet even when a stroke is ruled right across it", () => {
    const truth: Point[] = [
      { x: 70, y: 40 },
      { x: 420, y: 70 },
      { x: 400, y: 320 },
      { x: 50, y: 290 }
    ];
    const withDrawing = sheetOnTable(truth);
    // Two dark strokes spanning the whole sheet — one horizontal, one vertical — each three pixels
    // wide, exactly as a ruled line lands after the guess plane's downscale.
    for (let y = 0; y < withDrawing.height; y += 1) {
      for (let x = 0; x < withDrawing.width; x += 1) {
        const onStroke = Math.abs(y - 180) < 1.5 || Math.abs(x - 240) < 1.5;
        if (onStroke && withDrawing.data[y * withDrawing.width + x] > 100) {
          withDrawing.data[y * withDrawing.width + x] = 45;
        }
      }
    }

    const guess = guessSheetCorners(withDrawing);
    expect(guess).not.toBeNull();
    // The WHOLE sheet, not the largest band between two strokes. Without the closing the returned
    // quadrilateral is roughly half this tall and every corner is far outside the tolerance.
    guess!.corners.forEach((corner, index) => {
      expect(Math.hypot(corner.x - truth[index].x, corner.y - truth[index].y)).toBeLessThan(12);
    });
  });

  test("stays silent when there is no sheet, so the manual path remains the default", () => {
    // A frame with no bright region worth the name: the guess must decline rather than propose
    // something. Uncertain means silent — a confidently-drawn wrong quadrilateral is worse than none.
    expect(guessSheetCorners(plane(480, 360, () => 40))).toBeNull();

    // A small bright object on a big table: real, connected, convex — and only ~4% of the frame, so
    // it is not the sketch somebody photographed.
    const speck = sheetOnTable([
      { x: 200, y: 160 },
      { x: 260, y: 160 },
      { x: 260, y: 210 },
      { x: 200, y: 210 }
    ]);
    expect(guessSheetCorners(speck)).toBeNull();
  });

  test("declines a bright region that is not a quadrilateral", () => {
    // Two separate bright sheets bridged by nothing: the largest component is one of them, and the
    // fill test then passes for that one — so the case that must fail is a single component whose
    // shape is NOT convex. An L covers a little over half of its own corner quadrilateral.
    const ell = plane(480, 360, (x, y) => {
      const inArm = x > 60 && x < 420 && y > 60 && y < 160;
      const inLeg = x > 60 && x < 180 && y > 60 && y < 300;
      return inArm || inLeg ? 225 : 55;
    });
    const guess = guessSheetCorners(ell);
    expect(guess).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// The whole operation
// ---------------------------------------------------------------------------

test.describe("sketchRectify — makePlate", () => {
  test("produces a bilevel plate by default and a grey one on request", () => {
    const { page } = lampLitPage();
    const corners: Corners = [
      { x: 5, y: 5 },
      { x: 394, y: 5 },
      { x: 394, y: 194 },
      { x: 5, y: 194 }
    ];

    const lineArt = makePlate(page, corners);
    expect(lineArt.ok).toBe(true);
    if (!lineArt.ok) return;
    // Bilevel: every pixel is exactly 0 or exactly 255, which is what makes it printable line art and
    // what makes it the natural input for the dormant vectoriser.
    const values = new Set<number>();
    for (let index = 0; index < lineArt.plane.data.length; index += 1) values.add(lineArt.plane.data[index]);
    expect([...values].sort((a, b) => a - b)).toEqual([0, 255]);

    // "Straighten it but leave the tones alone" is a first-class outcome, not a debug switch: a faint
    // construction line lives in the greys and thresholding destroys it.
    const grey = makePlate(page, corners, { lineArt: false });
    expect(grey.ok).toBe(true);
    if (!grey.ok) return;
    const greyValues = new Set<number>();
    for (let index = 0; index < grey.plane.data.length; index += 1) greyValues.add(grey.plane.data[index]);
    expect(greyValues.size).toBeGreaterThan(2);
  });

  test("refuses with a sentence a designer can act on", () => {
    const result = makePlate(plane(50, 50, () => 200), [
      { x: 0, y: 0 },
      { x: 10, y: 10 },
      { x: 20, y: 20 },
      { x: 30, y: 30 }
    ]);
    expect(result.ok).toBe(false);
    if (result.ok) return;
    expect(result.reason).toMatch(/handle/i);
  });

  test("stays well inside a frame budget on a plate the size the report prints", () => {
    // Not a benchmark with a pass mark tuned to this machine — a ceiling loose enough that only a
    // change of algorithmic order trips it. The point is that the arithmetic below is linear in the
    // pixel count and the pixel count is bounded, so nothing here can grow into a frozen tab.
    const big = plane(1200, 900, (x, y) => (70 + (185 * x) / 1199) * (y % 37 < 3 ? 0.38 : 0.92));
    const corners: Corners = [
      { x: 10, y: 10 },
      { x: 1189, y: 10 },
      { x: 1189, y: 889 },
      { x: 10, y: 889 }
    ];
    const started = Date.now();
    const plate = makePlate(big, corners);
    const elapsed = Date.now() - started;
    expect(plate.ok).toBe(true);
    expect(elapsed).toBeLessThan(4000);
    console.log(`makePlate on ${big.width}x${big.height}: ${elapsed} ms`);
  });
});
