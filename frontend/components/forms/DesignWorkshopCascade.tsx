"use client";

import { useEffect, useMemo, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { Field } from "@/components/FormControls";
import { DesignWorkshopSelect, type DesignWorkshopSelectState } from "@/components/forms/DesignWorkshopSelect";
import {
  fetchStageRegistry,
  peekStageRegistry,
  workshopKindOptions,
  type DwEnumOption,
  type DwRegistry
} from "@/lib/designWorkshops";

/**
 * The KIND box and the WORKSHOP box, mounted as one control, in that order.
 *
 * ── WHY ONE COMPONENT AND NOT TWO LINES IN EACH FORM ────────────────────────────────────────────
 *
 * Six forms mount `DesignWorkshopSelect` today. Pairing it with a kind box at each of them would be
 * six copies of a decision that has a right answer and five wrong ones — which default kind, what
 * happens to a chosen workshop when the kind changes, and whether the kind is sent with the record.
 * The field repository learned this the expensive way with its tracer: it was wired form by form,
 * four of nine mounts were missed, and a researcher reported the feature simply absent. One mount,
 * one decision, argued here.
 *
 * ── THE DEFAULT IS `DESIGN_PROTOTYPE_DEVELOPMENT`, AND IT IS A FILTER AND NOT AN ANSWER ─────────
 *
 * The owner's instruction is that the first box "should be design and prototype workshop by default
 * for all with the privileges for the same". Everyone who can reach a record form can reach this
 * control, so there is no separate privilege to test here: the capability question was already
 * answered upstream by `canRunDesignWorkshops` before this form rendered at all, and re-asking it
 * here would be a second, drifting copy of a set `test_role_ladder_parity.py` exists to keep
 * singular. What the default DOES is narrow the list to the kind nearly every record belongs to.
 *
 * ── NOTHING HERE IS SAVED, AND THAT IS THE PROPERTY TO KEEP ─────────────────────────────────────
 *
 * The kind is a LENS OVER THE LIST. The record stores `designWorkshopId` and nothing else; the kind
 * lives in this component's state and never reaches a payload. A record's kind is a fact about the
 * workshop it is filed under (`DesignWorkshop.workshopKind`, answered in stage 1), so storing a
 * second copy beside the record would be a denormalisation that can disagree with its own source
 * the first time a workshop's stage 1 is corrected.
 *
 * ── CHANGING THE KIND DOES NOT CLEAR A CHOSEN WORKSHOP, AND THAT IS DELIBERATE ──────────────────
 *
 * The obvious behaviour is "new kind, blank the workshop". It is wrong here, and the reason is the
 * edit case: a product filed last season under a Skill Upgradation workshop opens with that
 * workshop already chosen, and the kind box — which cannot know what it was until the registry and
 * the record have both arrived — must never be the thing that silently detaches a filed record from
 * its workshop. `DesignWorkshopSelect` already recovers an off-page selection and draws it under
 * "Already on this record" (DROPDOWN_DESIGN §2.9), so a workshop that is not of the chosen kind
 * stays visible and stays selected until a PERSON picks another one. Narrowing a list is not the
 * same act as discarding an answer, and a control that conflates them loses work.
 *
 * ── IT SAYS WHERE THE OPTIONS CAME FROM, BECAUSE R3 ─────────────────────────────────────────────
 *
 * `workshopKindOptions` returns `served`, and a browser that has never been online gets the
 * built-in floor list. DROPDOWN_DESIGN R3 — "the control must say WHICH it is doing" — is why the
 * hint below the box distinguishes "these are the kinds the server knows" from "these are this
 * app's built-in kinds"; a silently short list reads as "there are only these", which is the
 * failure that document names as the most repeated bug class in the repository.
 */
export function DesignWorkshopCascade({
  state,
  initial,
  saving,
  onDirty,
  label,
  kindLabel = "Type of workshop"
}: {
  state: DesignWorkshopSelectState;
  initial?: string | null;
  saving?: boolean;
  onDirty?: () => void;
  label?: string;
  kindLabel?: string;
}) {
  /**
   * SEEDED FROM THE IN-MEMORY CACHE ON THE FIRST RENDER, exactly as `design-workshops/page.tsx`
   * seeds its own. `peekStageRegistry` is synchronous and returns whatever a previous screen
   * already fetched, so a form opened second in a session draws the served vocabulary on its FIRST
   * paint instead of flashing the floor list and replacing it a moment later.
   */
  const [registry, setRegistry] = useState<DwRegistry | null>(() => peekStageRegistry());

  const [kind, setKind] = useState<string>(DEFAULT_WORKSHOP_KIND);

  useEffect(() => {
    let cancelled = false;
    // A REGISTRY THAT DOES NOT ARRIVE IS A FLOOR LIST, NOT A BROKEN FORM. `workshopKindOptions`
    // falls back on its own, so the catch arm here has nothing to do but stop the rejection —
    // the same shape `design-workshops/page.tsx` uses for the same call.
    void fetchStageRegistry()
      .then((next) => {
        if (!cancelled) setRegistry(next);
      })
      .catch(() => {
        /* the floor list is already on screen */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Memoised on the registry OBJECT, which is what `fetchStageRegistry`'s identity guarantee is
   * for: it hands back the previously cached object when the version has not moved, so a refresh
   * that changed nothing does not rebuild this list or re-render the box that reads it.
   */
  const registryOptions = useMemo<{ options: readonly DwEnumOption[]; served: boolean }>(
    () => workshopKindOptions(registry),
    [registry]
  );

  const options = useMemo(
    () => registryOptions.options.map((o) => ({ value: o.value, label: o.label })),
    [registryOptions]
  );

  /**
   * THE DEFAULT IS ONLY APPLIED IF THE SERVER STILL OFFERS IT.
   *
   * A vocabulary the server has retired must not be sent back to it as a filter: `workshopKind` is
   * validated server-side, and a token it no longer knows is a 422 on a read the designer did not
   * ask for. If the registry arrives without `DESIGN_PROTOTYPE_DEVELOPMENT`, the box falls back to
   * showing every kind rather than to a token nothing can answer.
   */
  useEffect(() => {
    if (!registryOptions.served) return;
    const stillOffered = registryOptions.options.some((o) => o.value === kind);
    if (!stillOffered) setKind("");
  }, [registryOptions, kind]);

  return (
    <div className="space-y-3">
      <Field label={kindLabel}>
        <Dropdown
          value={kind}
          onChange={(next) => setKind(next)}
          /*
            THE "ANY TYPE" ROW IS FIRST AND CARRIES "", spelled out here rather than reached for
            through a `noneLabel` prop — the same shape the list filter on `design-workshops/page`
            uses, and the same absence-means-everything rule (R1). "" makes `workshopKind` omitted
            from the query entirely; an empty string on the wire would be a kind nothing matches.
          */
          options={[{ value: "", label: "Any type of workshop" }, ...options]}
          ariaLabel={kindLabel}
          disabled={saving}
          advanceOnSelect={false}
        />
        <p className="mt-1 text-xs text-ink-700">
          {registryOptions.served
            ? "Narrows the workshops below. It is not saved on this record."
            : "These are this app's built-in workshop types — connect once to refresh them. Narrows the workshops below; it is not saved on this record."}
        </p>
      </Field>

      <DesignWorkshopSelect
        state={state}
        initial={initial}
        saving={saving}
        onDirty={onDirty}
        label={label}
        workshopKind={kind || undefined}
      />
    </div>
  );
}

/**
 * The kind the first box opens on.
 *
 * Exported so the test can assert the ruling rather than restate it, and so a future change to the
 * default is one edit with one place to look. It is a `WORKSHOP_KIND` token and must stay one of
 * `WORKSHOP_KIND_FLOOR`'s values — `backend/app/services/stage_schema.ENUMS["WORKSHOP_KIND"]` is
 * the one definition of that vocabulary.
 */
export const DEFAULT_WORKSHOP_KIND = "DESIGN_PROTOTYPE_DEVELOPMENT";
