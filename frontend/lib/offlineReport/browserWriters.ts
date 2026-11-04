"use client";

import { browserFigureRasteriser, deviceImageLoader } from "@/lib/offlineReport/images";
import { REPORT_FONT_BASE, fetchAsset } from "@/lib/offlineReport/offlineAssets";
import type { OfflineWriters } from "@/lib/offlineReport/generate";
import type { PdfMakeLike } from "@/lib/offlineReport/pdf";
import type { ReportPalette } from "@/lib/reportTheme";

export function browserWriters(userId: string | null, palette: ReportPalette): OfflineWriters {
  return {
    loadImage: deviceImageLoader(userId),
    rasterise: browserFigureRasteriser(palette),
    pdfmake: async () => {
      const loaded = await import("pdfmake/build/pdfmake");
      return ((loaded as { default?: unknown }).default ?? loaded) as PdfMakeLike;
    },
    fontSource: (file) => fetchAsset(`${REPORT_FONT_BASE}${file}`)
  };
}
