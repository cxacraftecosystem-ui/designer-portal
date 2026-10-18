"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { useToast } from "@/components/ui/Toast";
import { listPairs } from "@/lib/duplicates";
import { isAdmin } from "@/lib/permissions";

/** The query flag a merged id's redirect carries, so the survivor's page can say where you are. */
export const MERGED_FROM_FLAG = "merged";

/** "Possible duplicate — Review", for admins, while an open pair names this artisan. */
export function PossibleDuplicateBanner({ artisanId }: { artisanId: string }) {
  const { user } = useAuth();
  const admin = isAdmin(user);
  const [pairId, setPairId] = useState<string | null>(null);

  useEffect(() => {
    if (!admin) return;
    let cancelled = false;
    listPairs({ status: "OPEN", artisanId, pageSize: 1 })
      .then((page) => {
        if (!cancelled) setPairId(page.items[0]?.id ?? null);
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [admin, artisanId]);

  if (!admin || !pairId) return null;
  return (
    <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-sm text-amber-800">
      <span className="font-medium">Possible duplicate</span>
      <Link href={`/admin/duplicates/${pairId}`} className="font-semibold underline underline-offset-2">
        Review
      </Link>
    </div>
  );
}

/** One toast on arrival from a merged id: "Merged into {name}". */
export function MergedIntoToast({ name }: { name: string }) {
  const { toast } = useToast();
  const shown = useRef(false);
  useEffect(() => {
    if (shown.current || typeof window === "undefined") return;
    const params = new URLSearchParams(window.location.search);
    if (!params.has(MERGED_FROM_FLAG)) return;
    shown.current = true;
    toast({ tone: "info", title: `Merged into ${name}` });
    params.delete(MERGED_FROM_FLAG);
    const rest = params.toString();
    window.history.replaceState(null, "", `${window.location.pathname}${rest ? `?${rest}` : ""}`);
  }, [name, toast]);
  return null;
}
