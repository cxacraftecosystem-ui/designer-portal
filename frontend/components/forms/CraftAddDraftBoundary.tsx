"use client";

import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ReactNode, type RefObject } from "react";

import { craftIndex, useCraftCatalogue, type CatalogueCraft } from "@/lib/craftCatalogue";
import {
  CRAFT_ADD_RESTORED,
  addCraftHref,
  applySnapshot,
  snapshotForm,
  takeCraftAddDraft,
  writeCraftAddDraft
} from "@/lib/craftAddDraft";

export function CraftAddDraftBoundary({
  formRef,
  onLeave,
  onCraftAdded,
  children
}: {
  formRef: RefObject<HTMLFormElement | null>;
  onLeave: () => void;
  onCraftAdded: (craftId: string, craft: CatalogueCraft | null) => void;
  children: ReactNode;
}) {
  const router = useRouter();
  const [restored, setRestored] = useState(false);
  const [added, setAdded] = useState<string | null>(null);
  const { catalogue, error } = useCraftCatalogue();
  const onCraftAddedRef = useRef(onCraftAdded);
  useEffect(() => {
    onCraftAddedRef.current = onCraftAdded;
  });

  useEffect(() => {
    const here = `${window.location.pathname}${window.location.search}`;
    const draft = takeCraftAddDraft(here);
    if (!draft) return;
    const frame = requestAnimationFrame(() => {
      const form = formRef.current;
      if (form) applySnapshot(form, draft.controls);
      if (draft.addedCraftId) setAdded(draft.addedCraftId);
      setRestored(true);
    });
    return () => cancelAnimationFrame(frame);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!added || (!catalogue && !error)) return;
    const craft = catalogue ? (craftIndex(catalogue).byId.get(added) ?? null) : null;
    onCraftAddedRef.current(added, craft);
    setAdded(null);
  }, [added, catalogue, error]);

  return (
    <div
      className="min-w-0"
      onClickCapture={(event) => {
        const anchor = (event.target as Element | null)?.closest?.("a[href^='/crafts?new=']");
        if (!anchor) return;
        const form = formRef.current;
        const href = anchor.getAttribute("href");
        if (!form || !href) return;
        event.preventDefault();
        event.stopPropagation();
        const returnTo = `${window.location.pathname}${window.location.search}`;
        writeCraftAddDraft({ returnTo, savedAt: Date.now(), controls: snapshotForm(form) });
        onLeave();
        router.push(addCraftHref(href, returnTo));
      }}
    >
      {children}
      {restored ? (
        <p role="status" className="mt-1 text-xs text-ink-500">
          {CRAFT_ADD_RESTORED}
        </p>
      ) : null}
    </div>
  );
}
