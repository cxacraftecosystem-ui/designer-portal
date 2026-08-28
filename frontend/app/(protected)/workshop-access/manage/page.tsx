"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { UsersRound } from "lucide-react";

import { PageHeader } from "@/components/PageHeader";
import { useAuth } from "@/components/AuthProvider";
import { DesignWorkshopInspectorsPanel } from "@/components/settings/DesignWorkshopInspectorsPanel";
import { DesignWorkshopViewersPanel } from "@/components/settings/DesignWorkshopViewersPanel";
import { WorkshopAccessQueuePanel } from "@/components/settings/WorkshopAccessQueuePanel";
import { WorkshopRosterPanel } from "@/components/settings/WorkshopRosterPanel";
import { routeRedirectFor } from "@/lib/permissions";

/**
 * /workshop-access/manage — ADMIN and MASTER ADMIN. Formerly /settings/workshop-access.
 *
 * The two halves of the same conversation on one screen: the cross-workshop queue of people ASKING,
 * and the per-workshop roster of people who HAVE it. They share one WorkshopAssignment row per
 * (workshop, user), so approving in the queue changes what the roster shows and vice versa —
 * `refreshToken` is bumped by either panel to make the other re-read rather than go stale.
 *
 * The THIRD panel answers the same question for a different record. A design & prototype workshop
 * is not a `Workshop` and carries no WorkshopAssignment rows at all: it is visible to whoever
 * pressed "create" and to nobody else, because `load_workshop_or_404` 404s it for everyone but its
 * creator and the admins. So it needs its own list of readers, and it belongs HERE rather than on a
 * route of its own — this page is already where an admin comes to answer "who may work on what",
 * and it already carries both gates that answer needs (the ROUTE_REDIRECTS rule that sends
 * below-admin to the request page, and the ADMIN_CHROME_ROUTES rule that gives an admin with admin
 * view off a panel instead of a redirect). A second route would have been a second copy of both.
 * `refreshToken` reaches it too, so a reload of the page's other halves reloads its lists as well.
 *
 * THE FOURTH PANEL ANSWERS THE OPPOSITE QUESTION ABOUT THE SAME RECORD, and it belongs beside the
 * third rather than anywhere else because the two are one decision taken twice. `DesignWorkshopViewer`
 * says who may WORK on a design workshop — that grant reaches the stage writes, because
 * `load_workshop_or_404(for_edit=True)` performs no role check at all. `DesignWorkshopInspector` says
 * who may READ one in order to examine it, and reaches nothing else: every route on its prefix is a
 * GET. The two sets are disjoint by construction and the server refuses an overlap by name — an
 * independent review by somebody who worked on it is not a review — so an admin needs to see both
 * lists for one workshop in one place, or they will assign an inspector who is already a co-designer
 * and meet the refusal with no idea where it came from.
 *
 * The inspector's own surface is NOT on this page and cannot be: `assert_inspection_surface` 403s an
 * admin, deliberately. This panel is the whole of what an admin does with the feature.
 *
 * Two gates, and they answer differently on purpose:
 *
 *  - Below admin: sent to /workshop-access/request, from ROUTE_REDIRECTS. The old page met these
 *    users with "Admin access required", which is true and useless — they were looking for workshop
 *    access, and the half that is theirs is one route away. A rule that leaves someone nowhere to go
 *    is a bug even when its refusal is correct.
 *  - An ADMIN with admin view off: handled above this page by AppShell, because the path is listed
 *    in ADMIN_CHROME_ROUTES. That branch must stay a panel rather than a redirect — the admin holds
 *    the access and turned it off themselves, and silently rerouting them would look like a demotion.
 *
 * The server is the real boundary either way: the queue and every roster mutation sit behind
 * `require_admin` (backend/app/api/routes/workshops.py), so this page hides nothing it also unlocks.
 */
export default function WorkshopAccessManagePage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  const [refreshToken, setRefreshToken] = useState(0);

  const redirect = loading ? null : routeRedirectFor(user, "/workshop-access/manage");

  useEffect(() => {
    if (redirect) router.replace(redirect);
  }, [redirect, router]);

  // Nothing of the console renders while the account is unknown or on its way out — a flash of the
  // queue is a leak of who is asking for what, however brief.
  if (loading || redirect) {
    return <section className="panel p-6 text-sm text-ink-500">Opening workshop access…</section>;
  }

  const bump = () => setRefreshToken((current) => current + 1);

  return (
    <>
      <PageHeader
        title="Manage workshop access"
        description="Approve or decline requests to work in a workshop, manage each workshop's roster and access levels, choose which designers may see a design & prototype workshop, and choose who inspects one."
        icon={<UsersRound className="h-5 w-5" aria-hidden />}
      />
      <div className="grid gap-4">
        <WorkshopAccessQueuePanel onChanged={bump} refreshToken={refreshToken} />
        <WorkshopRosterPanel onChanged={bump} refreshToken={refreshToken} />
        <DesignWorkshopViewersPanel refreshToken={refreshToken} />
        <DesignWorkshopInspectorsPanel refreshToken={refreshToken} />
      </div>
    </>
  );
}
