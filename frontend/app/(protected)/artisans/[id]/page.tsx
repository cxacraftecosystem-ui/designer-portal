"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { Boxes, ClipboardList, Hammer, Users } from "lucide-react";

import { ArtisanCardPanel } from "@/components/artisans/ArtisanCardPanel";
import { ArtisanQuestionnairePanel } from "@/components/ArtisanQuestionnairePanel";
import { ArtisanQuestionnaireRecord } from "@/components/questionnaire/ArtisanQuestionnaireRecord";
import { useQuestionnaireConfig } from "@/components/questionnaire/useQuestionnaireConfig";
import { useAuth } from "@/components/AuthProvider";
import { MERGED_FROM_FLAG, MergedIntoToast, PossibleDuplicateBanner } from "@/components/duplicates/ArtisanDuplicateNotices";
import { ExistingMedia } from "@/components/media/ExistingMedia";
import { RecordFieldList, RecordViewPage } from "@/components/records/RecordView";
import { artisanFields } from "@/components/records/recordFields";
import { mergedSurvivorId } from "@/lib/duplicates";
import { canCreateRecords } from "@/lib/permissions";
import type { Artisan } from "@/lib/types";

function entryHref(path: string, artisan: Artisan) {
  const params = new URLSearchParams({ artisanId: artisan.id, artisanName: artisan.name, place: artisan.place });
  if (artisan.craftId) params.set("craftId", artisan.craftId);
  if (artisan.craft?.name) params.set("craftName", artisan.craft.name);
  return `${path}?${params.toString()}`;
}

export default function ArtisanViewPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const router = useRouter();
  const creator = canCreateRecords(user);
  const v2 = Boolean(useQuestionnaireConfig()?.v2Enabled);
  return (
    <RecordViewPage<Artisan>
      kind="artisan"
      id={id}
      endpoint={`/artisans/${encodeURIComponent(id)}`}
      noun="artisan"
      icon={<Users className="h-5 w-5" aria-hidden />}
      titleOf={(artisan) => artisan.name}
      collabType="artisan"
      onLoadError={(error) => {
        const survivor = mergedSurvivorId(error);
        if (!survivor) return false;
        router.replace(`/artisans/${encodeURIComponent(survivor)}?${MERGED_FROM_FLAG}=1`);
        return true;
      }}
    >
      {(artisan) => (
        <>
          <MergedIntoToast name={artisan.name} />
          <PossibleDuplicateBanner artisanId={artisan.id} />
          <RecordFieldList rows={artisanFields(artisan)} />
          <ArtisanCardPanel artisanId={artisan.id} canReissue={creator} />
          <section className="grid gap-3 md:grid-cols-3" aria-label="Make an entry for this artisan">
            {[
              ...(creator
                ? [
                    { href: entryHref("/tools/new", artisan), title: "Make a tool entry", body: "Document a tool used by this artisan.", icon: Hammer },
                    { href: entryHref("/products/new", artisan), title: "Make a product entry", body: "Record an object, product or sample.", icon: Boxes }
                  ]
                : []),
              {
                href: v2 ? `/questionnaire/sessions/new?artisanId=${encodeURIComponent(artisan.id)}` : entryHref("/questionnaire", artisan),
                title: "Start questionnaire",
                body: "Open a session with this artisan filled in.",
                icon: ClipboardList
              }
            ].map((item) => (
              <Link key={item.href} href={item.href} className="panel group flex min-h-28 items-start gap-3 p-4 transition hover:-translate-y-0.5 hover:shadow-panel">
                <span className="grid h-11 w-11 shrink-0 place-items-center rounded-lg bg-field-200 text-field-700 dark:text-purple-300">
                  <item.icon className="h-5 w-5" aria-hidden />
                </span>
                <span>
                  <span className="block font-display text-lg font-bold text-ink">{item.title}</span>
                  <span className="mt-1 block text-sm leading-6 text-ink-muted">{item.body}</span>
                </span>
              </Link>
            ))}
          </section>
          <ExistingMedia linkedRecordType="artisan" linkedRecordId={artisan.id} recordHold={null} title="Media" readOnly />
          {v2 ? (
            <section id="questionnaire" aria-label="Questionnaire" className="scroll-mt-24">
              <ArtisanQuestionnaireRecord artisanId={artisan.id} artisanName={artisan.name} />
            </section>
          ) : (
            <ArtisanQuestionnairePanel artisanId={artisan.id} />
          )}
        </>
      )}
    </RecordViewPage>
  );
}
