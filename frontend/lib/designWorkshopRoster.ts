/**
 * The designer's own artisan-list import (owner decision C11, 2026-10-17).
 *
 * The same pipeline the officers use on `/officers` (`/design-workshop-oversight/{id}/artisans/upload`),
 * reached through the workshop's own door: `/design-workshops/{id}/roster/*`, which asks the stage-write
 * question (`load_workshop_or_404(for_edit=True)`). The report and ledger shapes are the officers' types,
 * imported rather than restated, because the server answers both doors from one function.
 */

import { apiFetch } from "@/lib/api";
import { fetchFile } from "@/lib/fileDownload";
import type {
  ArtisanImportReport,
  DwArtisanImportList
} from "@/app/(protected)/officers/oversight";
import { exportFilename } from "@/lib/naming";

const base = (workshopId: string) => `/design-workshops/${encodeURIComponent(workshopId)}/roster`;

/** The pro-forma with this workshop's Details sheet already filled in. */
export function downloadRosterProForma(workshopId: string) {
  return fetchFile(`${base(workshopId)}/pro-forma`, exportFilename("Artisan", "Pro-Forma", "xlsx", { dated: false }));
}

/** One multipart part, no scalars. Never set `Content-Type` — the browser writes the boundary. */
export function uploadRoster(workshopId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<ArtisanImportReport>(`${base(workshopId)}/upload`, { method: "POST", body });
}

export function listRosterImports(workshopId: string) {
  return apiFetch<DwArtisanImportList>(`${base(workshopId)}/imports`);
}
