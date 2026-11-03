"use client";

import { use } from "react";
import Link from "next/link";
import { Layers } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { AiLayersPanel } from "@/components/designworkshop/AiLayersPanel";
import { HeldPostNotice, heldPostReason, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";

export default function DesignWorkshopAiLayersPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const postRefusal = useHeldPostRefusal(id);

  return (
    <>
      <PageHeader
        title="AI layers"
        description="AI text from this workshop, and who accepted it."
        icon={<Layers className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All 22 stages
          </Link>
        }
      />

      <HeldPostNotice refusal={postRefusal} />
      <AiLayersPanel workshopId={id} readOnlyReason={heldPostReason(postRefusal)} />
    </>
  );
}
