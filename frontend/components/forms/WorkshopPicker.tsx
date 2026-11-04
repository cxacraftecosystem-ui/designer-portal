"use client";

import { useCallback, useEffect, useId, useMemo, useState } from "react";

import { useAuth } from "@/components/AuthProvider";
import { Field } from "@/components/FormControls";
import {
  DesignWorkshopSelect,
  useDesignWorkshopSelection,
  type DesignWorkshopSelectState
} from "@/components/forms/DesignWorkshopSelect";
import { WorkshopSelect, useWorkshopSelection, type WorkshopSelection } from "@/components/forms/WorkshopSelect";
import { Dropdown } from "@/components/ui/Dropdown";
import { WORKSHOP_KIND_FLOOR } from "@/lib/designWorkshops";
import { canRunDesignWorkshops } from "@/lib/permissions";
import { NO_FIELD_WORKSHOP } from "@/lib/workshopOptions";
import type { Workshop } from "@/lib/types";
import {
  DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY,
  defaultWorkshopType,
  listWorkshopTypes,
  sortWorkshopTypes,
  type WorkshopTypeOption
} from "@/lib/workshopTypes";

export const WORKSHOP_TYPE_FLOOR: readonly WorkshopTypeOption[] = WORKSHOP_KIND_FLOOR.map((option, index) => ({
  id: `floor:${option.value}`,
  key: option.value,
  label: option.label,
  sortOrder: (index + 1) * 10,
  isActive: true,
  routesToDesignWorkshop: option.value === DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY,
  createdAt: null,
  updatedAt: null
}));

const WORKSHOP_TYPES_TTL_MS = 60_000;
let typesRequest: Promise<WorkshopTypeOption[]> | null = null;
let typesAskedAt = 0;
let typesCache: WorkshopTypeOption[] | null = null;

function peekWorkshopTypes(): WorkshopTypeOption[] | null {
  return typesCache;
}

function loadWorkshopTypes(): Promise<WorkshopTypeOption[]> {
  if (typesRequest && Date.now() - typesAskedAt > WORKSHOP_TYPES_TTL_MS) typesRequest = null;
  if (!typesRequest) {
    typesAskedAt = Date.now();
    typesRequest = listWorkshopTypes()
      .then((rows) => {
        const sorted = sortWorkshopTypes(rows);
        typesCache = sorted;
        return sorted;
      })
      .catch((error) => {
        typesRequest = null;
        typesAskedAt = 0;
        throw error;
      });
  }
  return typesRequest;
}

export function storedRoutingOf(workshopId: string | null | undefined, designWorkshopId: string | null | undefined) {
  if (designWorkshopId) return true;
  if (workshopId) return false;
  return null;
}

function firstTypeRouting(types: readonly WorkshopTypeOption[], toDesignWorkshop: boolean) {
  return (
    sortWorkshopTypes(types.filter((type) => type.isActive)).find(
      (type) => type.routesToDesignWorkshop === toDesignWorkshop
    ) ?? null
  );
}

export function openingTypeKey({
  types,
  storedRouting,
  preferDesignWorkshops
}: {
  types: readonly WorkshopTypeOption[];
  storedRouting: boolean | null;
  preferDesignWorkshops: boolean;
}): string {
  if (storedRouting !== null) {
    const match = firstTypeRouting(types, storedRouting);
    if (match) return match.key;
    return storedRouting ? DESIGN_PROTOTYPE_WORKSHOP_TYPE_KEY : "";
  }
  return defaultWorkshopType([...types], { preferDesignWorkshops })?.key ?? "";
}

export function typeOptionsFor(types: readonly WorkshopTypeOption[], currentKey: string) {
  const active = sortWorkshopTypes(types.filter((type) => type.isActive));
  const rows = !currentKey || active.some((type) => type.key === currentKey) ? active : [...active, retired(currentKey)];
  return rows.map((type) => ({ value: type.key, label: type.label }));
}

function retired(key: string): WorkshopTypeOption {
  const floor = WORKSHOP_TYPE_FLOOR.find((type) => type.key === key);
  return {
    id: `retired:${key}`,
    key,
    label: floor?.label ?? key,
    sortOrder: Number.MAX_SAFE_INTEGER,
    isActive: false,
    routesToDesignWorkshop: floor?.routesToDesignWorkshop ?? false,
    createdAt: null,
    updatedAt: null
  };
}

export type WorkshopPickerState = {
  typeKey: string;
  routesToDesignWorkshop: boolean;
  workshopId: string;
  designWorkshopId: string;
  loading: boolean;
  workshops: Workshop[];
  touched: boolean;
  setWorkshopId: (workshopId: string) => void;
  confirmSubmission: () => Promise<boolean>;
  view: {
    types: readonly WorkshopTypeOption[];
    typesServed: boolean;
    setTypeKey: (key: string) => void;
    field: WorkshopSelection;
    design: DesignWorkshopSelectState;
    designInitial: string | null | undefined;
  };
};

export function useWorkshopPicker({
  initialWorkshopId,
  initialDesignWorkshopId,
  isEdit = false,
  resetKey = null
}: {
  initialWorkshopId?: string | null;
  initialDesignWorkshopId?: string | null;
  isEdit?: boolean;
  resetKey?: string | null;
} = {}): WorkshopPickerState {
  const { user } = useAuth();

  const field = useWorkshopSelection({ initialWorkshopId, isEdit, resetKey });
  const design = useDesignWorkshopSelection(initialDesignWorkshopId ?? null, resetKey);

  const designInitial = isEdit ? (initialDesignWorkshopId ?? null) : undefined;

  const [served, setServed] = useState<WorkshopTypeOption[] | null>(() => peekWorkshopTypes());
  useEffect(() => {
    let cancelled = false;
    void loadWorkshopTypes()
      .then((rows) => {
        if (!cancelled) setServed(rows);
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const types = served ?? WORKSHOP_TYPE_FLOOR;

  const [chosenTypeKey, setChosenTypeKey] = useState<string | null>(null);
  const [seenResetKey, setSeenResetKey] = useState<string | null>(resetKey);
  if (seenResetKey !== resetKey) {
    setSeenResetKey(resetKey);
    setChosenTypeKey(null);
  }
  const storedRouting = storedRoutingOf(initialWorkshopId, initialDesignWorkshopId);
  const preferDesignWorkshops = canRunDesignWorkshops(user);
  const autoTypeKey = useMemo(
    () => openingTypeKey({ types, storedRouting, preferDesignWorkshops }),
    [types, storedRouting, preferDesignWorkshops]
  );
  const typeKey = chosenTypeKey ?? autoTypeKey;

  const routesToDesignWorkshop =
    types.find((type) => type.key === typeKey)?.routesToDesignWorkshop ?? storedRouting ?? false;

  const setTypeKey = useCallback((key: string) => setChosenTypeKey(key), []);

  const fieldSetWorkshopId = field.setWorkshopId;
  const setWorkshopId = useCallback(
    (next: string) => {
      fieldSetWorkshopId(next);
      const target = firstTypeRouting(types, false);
      if (target) setChosenTypeKey(target.key);
    },
    [fieldSetWorkshopId, types]
  );

  const fieldConfirmSubmission = field.confirmSubmission;
  const confirmSubmission = useCallback(
    () => (routesToDesignWorkshop ? Promise.resolve(true) : fieldConfirmSubmission()),
    [routesToDesignWorkshop, fieldConfirmSubmission]
  );

  return {
    typeKey,
    routesToDesignWorkshop,
    workshopId: routesToDesignWorkshop ? "" : field.workshopId,
    designWorkshopId: routesToDesignWorkshop ? design.workshopId : "",
    loading: field.loading,
    workshops: field.workshops,
    touched: field.touched || design.touched || chosenTypeKey !== null,
    setWorkshopId,
    confirmSubmission,
    view: { types, typesServed: served !== null, setTypeKey, field, design, designInitial }
  };
}

export function WorkshopPicker({
  state,
  onDirty,
  saving,
  disabled = false
}: {
  state: WorkshopPickerState;
  onDirty?: () => void;
  saving?: boolean;
  disabled?: boolean;
}) {
  const { typeKey, routesToDesignWorkshop, view } = state;
  const held = Boolean(saving) || disabled;
  const baseId = useId();
  const hintId = `${baseId}-type-hint`;
  const options = useMemo(() => typeOptionsFor(view.types, typeKey), [view.types, typeKey]);

  return (
    <div className="grid min-w-0 content-start gap-3" onInput={(event) => event.stopPropagation()}>
      <Field label="Type of workshop">
        <Dropdown
          value={typeKey}
          onChange={(next) => {
            view.setTypeKey(next);
            onDirty?.();
          }}
          options={options}
          emptyLabel="No types of workshop are set up. An administrator can add them."
          ariaLabel="Type of workshop"
          disabled={held}
          describedBy={hintId}
          advanceOnSelect={false}
        />
      </Field>
      <p id={hintId} className="text-xs text-ink-500">
        {view.typesServed
          ? "Narrows the list below. It is not saved on this record."
          : "These are the standard types of workshop until you connect; they are not saved on this record."}
      </p>
      {routesToDesignWorkshop ? (
        <DesignWorkshopSelect
          state={view.design}
          initial={view.designInitial}
          label="Workshop"
          noneLabel={NO_FIELD_WORKSHOP}
          onDirty={onDirty}
          saving={held}
        />
      ) : (
        <WorkshopSelect state={view.field} label="Workshop" onDirty={onDirty} saving={held} />
      )}
    </div>
  );
}
