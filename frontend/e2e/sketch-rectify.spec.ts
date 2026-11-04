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

const TILT = [1.1, 0.22, 140, 0.13, 1.05, 90, 0.00042, 0.00026, 1] as const;

test.describe("sketchRectify — corner ordering", () => {
  test("puts any click order into TL, TR, BR, BL", () => {
    const marks: Point[] = [
      { x: 320, y: 40 },
      { x: 30, y: 220 },
      { x: 20, y: 30 },
      { x: 350, y: 260 }
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

test.describe("sketchRectify — bilinear resampling", () => {
  const tiny: GreyPlane = { data: new Uint8ClampedArray([0, 100, 200, 255]), width: 2, height: 2 };

  test("reproduces the sample points exactly", () => {
    expect(bilinearSample(tiny, 0, 0)).toBe(0);
    expect(bilinearSample(tiny, 1, 0)).toBe(100);
    expect(bilinearSample(tiny, 0, 1)).toBe(200);
    expect(bilinearSample(tiny, 1, 1)).toBe(255);
  });

  test("matches hand-computed interpolations", () => {
    expect(bilinearSample(tiny, 0.5, 0)).toBeCloseTo(50, 12);
    expect(bilinearSample(tiny, 0, 0.5)).toBeCloseTo(100, 12);
    expect(bilinearSample(tiny, 0.5, 0.5)).toBeCloseTo(138.75, 12);
    expect(bilinearSample(tiny, 0.25, 0)).toBeCloseTo(25, 12);
    expect(bilinearSample(tiny, 0.75, 0.25)).toBeCloseTo(116.5625, 12);
  });

  test("keeps a thin diagonal stroke continuous instead of dashing it", () => {
    const source = plane(300, 300, (x, y) => (Math.abs(x - y) < 1.5 ? 0 : 255));
    const marked: Corners = [
      { x: 30, y: 12 },
      { x: 268, y: 34 },
      { x: 262, y: 276 },
      { x: 18, y: 258 }
    ];
    const rectified = rectifyPlane(source, marked, 220, 220)!;

    let emptyRows = 0;
    for (let y = 25; y < 195; y += 1) {
      let darkest = 255;
      for (let x = 0; x < 220; x += 1) darkest = Math.min(darkest, at(rectified, x, y));
      if (darkest > 128) emptyRows += 1;
    }
    expect(emptyRows).toBe(0);
  });

  test("reads paper white outside the photograph rather than smearing the edge", () => {
    expect(bilinearSample(tiny, -0.6, 0)).toBe(255);
    expect(bilinearSample(tiny, 1.6, 0)).toBe(255);
    expect(bilinearSample(tiny, 0, -0.51)).toBe(255);
    expect(bilinearSample(tiny, 0, 1.51)).toBe(255);
    expect(bilinearSample(tiny, Number.NaN, 0)).toBe(255);
    expect(bilinearSample(tiny, -0.5, 0)).toBeCloseTo(0, 12);
    expect(bilinearSample(tiny, -0.25, 0)).toBeCloseTo(0, 12);
  });
});

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

  test("recovers a known projective transform at a point that took no part in the fit", () => {
    const width = 210;
    const height = 297;
    const plateCorners: Point[] = [
      { x: 0, y: 0 },
      { x: width - 1, y: 0 },
      { x: width - 1, y: height - 1 },
      { x: 0, y: height - 1 }
    ];
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

test.describe("sketchRectify — resampling", () => {
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
    expect(worst).toBeLessThanOrEqual(1);
  });

  test("straightens a tilted rectangle back into a rectangle", () => {
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

test.describe("sketchRectify — plate sizing", () => {
  test("takes the longer of each pair of opposite edges", () => {
    const corners: Corners = [
      { x: 0, y: 0 },
      { x: 400, y: 0 },
      { x: 300, y: 200 },
      { x: 0, y: 200 }
    ];
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

function lampLitPage(): { page: GreyPlane; strokes: Uint8Array } {
  const width = 400;
  const height = 200;
  const paperReflectance = 0.92;
  const inkReflectance = 0.38;
  const strokes = new Uint8Array(width * height);
  const page = plane(width, height, (x, y) => {
    const illumination = 70 + (185 * x) / (width - 1);
    const isStroke = x % 40 >= 18 && x % 40 <= 20 && y > 10 && y < height - 10;
    if (isStroke) strokes[y * width + x] = 1;
    return illumination * (isStroke ? inkReflectance : paperReflectance);
  });
  return { page, strokes };
}

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

    expect(blackFraction(local, strokes, 1, 0, half)).toBeGreaterThan(0.9);
    expect(blackFraction(local, strokes, 1, half, page.width)).toBeGreaterThan(0.9);
    expect(blackFraction(local, strokes, 0, 0, half)).toBeLessThan(0.05);
    expect(blackFraction(local, strokes, 0, half, page.width)).toBeLessThan(0.05);
  });

  test("the best global threshold turns the dark half of the page black", () => {
    const { page, strokes } = lampLitPage();
    const half = page.width / 2;
    const global = globalThreshold(page, otsuThreshold(page));

    expect(blackFraction(global, strokes, 0, 0, half)).toBeGreaterThan(0.5);
  });

  test("the difference between the two methods is the feature", () => {
    const { page, strokes } = lampLitPage();
    const half = page.width / 2;
    const local = sauvolaThreshold(page);
    const global = globalThreshold(page, otsuThreshold(page));

    const localFlood = blackFraction(local, strokes, 0, 0, half);
    const globalFlood = blackFraction(global, strokes, 0, 0, half);
    expect(globalFlood).toBeGreaterThan(localFlood * 10);
    expect(globalFlood - localFlood).toBeGreaterThan(0.45);
  });

  test("blank paper does not come out as speckle", () => {
    const blank = plane(240, 180, (x) => (70 + (185 * x) / 239) * 0.92);
    const result = sauvolaThreshold(blank);
    let dark = 0;
    for (let index = 0; index < result.data.length; index += 1) if (result.data[index] < 128) dark += 1;
    expect(dark).toBe(0);
  });

  test("the window is odd and comfortably wider than a pencil stroke", () => {
    const window = sauvolaWindow(plane(1200, 900, () => 200));
    expect(window % 2).toBe(1);
    expect(window).toBeGreaterThan(6 * 3);
  });
});

test.describe("sketchRectify — automatic corner guess", () => {
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
    guess!.corners.forEach((corner, index) => {
      expect(Math.hypot(corner.x - truth[index].x, corner.y - truth[index].y)).toBeLessThan(12);
    });
  });

  test("finds the sheet even when a stroke is ruled right across it", () => {
    const truth: Point[] = [
      { x: 70, y: 40 },
      { x: 420, y: 70 },
      { x: 400, y: 320 },
      { x: 50, y: 290 }
    ];
    const withDrawing = sheetOnTable(truth);
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
    guess!.corners.forEach((corner, index) => {
      expect(Math.hypot(corner.x - truth[index].x, corner.y - truth[index].y)).toBeLessThan(12);
    });
  });

  test("stays silent when there is no sheet, so the manual path remains the default", () => {
    expect(guessSheetCorners(plane(480, 360, () => 40))).toBeNull();

    const speck = sheetOnTable([
      { x: 200, y: 160 },
      { x: 260, y: 160 },
      { x: 260, y: 210 },
      { x: 200, y: 210 }
    ]);
    expect(guessSheetCorners(speck)).toBeNull();
  });

  test("declines a bright region that is not a quadrilateral", () => {
    const ell = plane(480, 360, (x, y) => {
      const inArm = x > 60 && x < 420 && y > 60 && y < 160;
      const inLeg = x > 60 && x < 180 && y > 60 && y < 300;
      return inArm || inLeg ? 225 : 55;
    });
    const guess = guessSheetCorners(ell);
    expect(guess).toBeNull();
  });
});

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
    const values = new Set<number>();
    for (let index = 0; index < lineArt.plane.data.length; index += 1) values.add(lineArt.plane.data[index]);
    expect([...values].sort((a, b) => a - b)).toEqual([0, 255]);

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
