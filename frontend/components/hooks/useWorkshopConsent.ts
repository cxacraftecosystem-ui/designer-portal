"use client";

import { useEffect, useState } from "react";

import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";

export type WorkshopConsent = {
  decision: string;
  ready: boolean;
  serverId: string | null;
};

export function useWorkshopConsent(workshopId: string | null | undefined): WorkshopConsent {
  const [state, setState] = useState<WorkshopConsent>({ decision: "NOT_RECORDED", ready: false, serverId: null });

  useEffect(() => {
    if (!workshopId) {
      setState({ decision: "NOT_RECORDED", ready: true, serverId: null });
      return;
    }
    let cancelled = false;
    setState({ decision: "NOT_RECORDED", ready: false, serverId: null });
    void loadDraft(workshopId).then((draft) => {
      if (cancelled) return;
      setState({
        decision: draft?.consent?.decision ?? "NOT_RECORDED",
        ready: true,
        serverId: draft?.remoteId ?? (isLocalWorkshopId(workshopId) ? null : workshopId)
      });
    });
    return () => {
      cancelled = true;
    };
  }, [workshopId]);

  return state;
}
