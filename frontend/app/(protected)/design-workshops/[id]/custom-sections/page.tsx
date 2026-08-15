"use client";

/**
 * The one screen where a workshop's own questions are written.
 *
 * WHY IT IS A WORKSHOP-LEVEL ROUTE AND NOT A PANEL ON A STAGE. A definition is a WHOLE-SET PUT: the
 * server replaces it in one write, deliberately, because "one definition, one digest" has to be atomic
 * and a definition assembled from six independent PATCHes has six intermediate digests, each of which
 * some handset can fetch and cache as though it were the definition. So it must be composed somewhere
 * with the whole set in hand — which a stage form, by construction, is not: it holds one stage.
 *
 * It is also where the answer state has to be in the same tab. A replace is not a delete, and which of
 * retire / remove / supersede a given edit becomes depends on whether the question has been answered.
 * The editor reads the workshop's stage buckets for exactly that, and says what each edit will cost
 * BEFORE the button is pressed — see `CustomSectionsEditor`'s own header for the failure that prevents.
 *
 * A SIBLING OF `ai-layers`, `readiness`, `photos` AND `codes`, and the same argument places all five:
 * they belong to the WORKSHOP rather than to any one of its 22 stages. A designer's own questions span
 * the fortnight — a section on stage 11 and another on stage 17 are one instrument — and the digest they
 * share is what every client compares its cached copy against.
 *
 * NO ROUTE GUARD ROW IS ADDED, AND THAT IS NOT AN OVERSIGHT. `routeMatches` in `lib/permissions.ts`
 * matches whole segments and their descendants, so the existing `/design-workshops` guard
 * (`canRunDesignWorkshops`, mirroring `can_run_design_workshops` in `deps.py`) already covers this URL.
 * Writing needs `_require_designer` plus edit rights on the workshop — the same pair `save_stage_data`
 * uses, with nothing added, because a definition decides what a stage asks and is therefore a stage
 * write. A second rule with the same predicate would be one more place to forget when the predicate
 * changes.
 */

import { use } from "react";
import Link from "next/link";
import { ListPlus } from "lucide-react";

import { CustomSectionsEditor } from "@/components/designworkshop/CustomSectionsEditor";
import { PageHeader } from "@/components/PageHeader";

export default function DesignWorkshopCustomSectionsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);

  return (
    <>
      <PageHeader
        title="This workshop's own questions"
        description="Questions you add to a stage of this workshop only, answered on the stage form beside the standard ones, counted towards that stage's completeness and printed in the report. A question that has been answered keeps its wording — an answer means what it meant when it was given."
        icon={<ListPlus className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={`/design-workshops/${id}`} className="field-button-secondary">
            All 22 stages
          </Link>
        }
      />

      <CustomSectionsEditor workshopId={id} />
    </>
  );
}
