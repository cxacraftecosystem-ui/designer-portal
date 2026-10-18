import { ArtisanForm } from "@/components/forms/ArtisanForm";
import { PageHeader } from "@/components/PageHeader";

export default async function NewArtisanPage({
  searchParams
}: {
  /** `?card=` — a Field Repository artisan card scanned elsewhere (lib/artisanCardScan.ts). */
  searchParams: Promise<{ card?: string | string[] }>;
}) {
  const { card } = await searchParams;
  const fromCard = typeof card === "string" && card.trim() ? card.trim().slice(0, 64) : null;
  return (
    <>
      <PageHeader title="New Artisan" description="Capture artisan identity, craft linkage, place, notes and optional GPS details." />
      <ArtisanForm fromCard={fromCard} />
    </>
  );
}
