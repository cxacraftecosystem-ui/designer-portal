"use client";

import { use, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { DraftingCompass, RefreshCw } from "lucide-react";

import { DesignWorkshopHeaderForm } from "@/components/designworkshop/DesignWorkshopHeaderForm";
import { HeldPostNotice, useHeldPostRefusal } from "@/components/designworkshop/HeldPostNotice";
import { PageHeader } from "@/components/PageHeader";
import { ApiError } from "@/lib/api";
import { getDesignWorkshop, type DwSummary } from "@/lib/designWorkshops";
import { isLocalWorkshopId, loadDraft } from "@/lib/designWorkshopStore";
import { isUnreachable } from "@/lib/offline";
import { ActionGroup } from "@/components/ui/ActionGroup";

type LoadState =
  | { kind: "loaded"; record: DwSummary }
  | { kind: "offline" }
  | { kind: "local-only" }
  | { kind: "unopenable" }
  | { kind: "failed"; message: string };

export default function EditDesignWorkshopPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const [state, setState] = useState<LoadState | null>(null);
  const [attempt, setAttempt] = useState(0);

  const retry = useCallback(() => {
    setState(null);
    setAttempt((previous) => previous + 1);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      let serverId = id;
      if (isLocalWorkshopId(id)) {
        const local = await loadDraft(id);
        if (cancelled) return;
        if (!local?.remoteId) {
          setState({ kind: "local-only" });
          return;
        }
        serverId = local.remoteId;
      }

      try {
        const record = await getDesignWorkshop(serverId);
        if (cancelled) return;
        setState({ kind: "loaded", record });
      } catch (err) {
        if (cancelled) return;
        if (isUnreachable(err)) {
          setState({ kind: "offline" });
          return;
        }
        if (err instanceof ApiError && err.status === 404) {
          setState({ kind: "unopenable" });
          return;
        }
        setState({
          kind: "failed",
          message: err instanceof Error && err.message.trim() ? err.message : "Unable to load this design workshop."
        });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [id, attempt]);

  const recordHref = `/design-workshops/${id}`;
  const postRefusal = useHeldPostRefusal(state?.kind === "loaded" ? state.record.id : null);

  return (
    <>
      <PageHeader
        title="Update workshop details"
        description={
          state?.kind === "loaded"
            ? state.record.title
            : "Workshop details"
        }
        icon={<DraftingCompass className="h-5 w-5" aria-hidden />}
        actions={
          <Link href={recordHref} className="field-button-secondary">
            Back to the workshop
          </Link>
        }
      />

      {state === null ? <div className="text-sm text-ink-700">Loading...</div> : null}

      {state?.kind === "loaded" ? (
        <>
          <HeldPostNotice refusal={postRefusal} sayPending />
          {postRefusal === null ? (
            <DesignWorkshopHeaderForm initial={state.record} />
          ) : postRefusal ? (
            <ActionGroup>
              <Link href={recordHref} className="field-button-secondary">
                Back to the workshop
              </Link>
            </ActionGroup>
          ) : null}
        </>
      ) : null}

      {state?.kind === "offline" ? (
        <section className="panel grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">You&rsquo;re offline</h2>
          <p className="text-sm leading-6 text-ink-700">
            Details can only be changed online.
          </p>
          <p className="text-sm leading-6 text-ink-700">
            Stages and photos still work offline.
          </p>
          <ActionGroup>
            <button type="button" className="field-button" onClick={retry}>
              <RefreshCw className="h-4 w-4" aria-hidden />
              Try again
            </button>
            <Link href={recordHref} className="field-button-secondary">
              Back to the workshop
            </Link>
          </ActionGroup>
        </section>
      ) : null}

      {state?.kind === "local-only" ? (
        <section className="panel grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">This workshop is still only on this device</h2>
          <p className="text-sm leading-6 text-ink-700">
            Not uploaded yet. Sync it from the workshops list to edit details.
          </p>
          <ActionGroup>
            <Link href="/design-workshops" className="field-button">
              All design workshops
            </Link>
            <Link href={recordHref} className="field-button-secondary">
              Back to the workshop
            </Link>
          </ActionGroup>
        </section>
      ) : null}

      {state?.kind === "unopenable" ? (
        <section className="panel grid gap-3 p-4">
          <h2 className="text-sm font-medium text-ink-900">
            Workshop not found
          </h2>
          <p className="text-sm leading-6 text-ink-700">
            It doesn&rsquo;t exist or hasn&rsquo;t been shared with you. Ask its designer or an admin to add you.
          </p>
          <Link href="/design-workshops" className="field-button-secondary">
            All design workshops
          </Link>
        </section>
      ) : null}

      {state?.kind === "failed" ? (
        <div className="grid gap-3">
          <div
            role="alert"
            className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm leading-6 text-red-700"
          >
            {state.message}
          </div>
          <ActionGroup>
            <button type="button" className="field-button-secondary" onClick={retry}>
              <RefreshCw className="h-4 w-4" aria-hidden />
              Try again
            </button>
            <Link href={recordHref} className="field-button-secondary">
              Back to the workshop
            </Link>
          </ActionGroup>
        </div>
      ) : null}
    </>
  );
}
