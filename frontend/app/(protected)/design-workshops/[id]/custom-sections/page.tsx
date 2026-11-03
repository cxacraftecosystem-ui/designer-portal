"use client";

import { use, useId } from "react";
import Link from "next/link";
import { ListPlus } from "lucide-react";

import { CustomSectionsEditor } from "@/components/designworkshop/CustomSectionsEditor";
import { HeldPostNotice, heldPostReason, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";
import { PageHeader } from "@/components/PageHeader";

export default function DesignWorkshopCustomSectionsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const postRefusal = useHeldPostRefusal(id);
  const heldNoticeId = useId();

  return (
    <>
      <PageHeader
        title="This workshop's own questions"
        description="Extra questions for this workshop's stages."
        icon={<ListPlus className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All 22 stages
          </Link>
        }
      />

      <HeldPostNotice refusal={postRefusal} id={heldNoticeId} sayPending />
      <CustomSectionsEditor
        workshopId={id}
        readOnlyReason={heldPostReason(postRefusal)}
        describedBy={postRefusal ? heldNoticeId : undefined}
      />
    </>
  );
}
