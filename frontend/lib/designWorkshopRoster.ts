import { apiFetch } from "@/lib/api";
import { fetchFile } from "@/lib/fileDownload";
import type {
  ArtisanImportReport,
  DwArtisanImportList
} from "@/app/(protected)/officers/oversight";
import { exportFilename } from "@/lib/naming";

const base = (workshopId: string) => `/design-workshops/${encodeURIComponent(workshopId)}/roster`;

export function downloadRosterProForma(workshopId: string) {
  return fetchFile(`${base(workshopId)}/pro-forma`, exportFilename("Artisan", "Pro-Forma", "xlsx", { dated: false }));
}

export function uploadRoster(workshopId: string, file: File) {
  const body = new FormData();
  body.append("file", file);
  return apiFetch<ArtisanImportReport>(`${base(workshopId)}/upload`, { method: "POST", body });
}

export function listRosterImports(workshopId: string) {
  return apiFetch<DwArtisanImportList>(`${base(workshopId)}/imports`);
}
