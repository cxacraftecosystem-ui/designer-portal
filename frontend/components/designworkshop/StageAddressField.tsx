"use client";

import { useEffect, useMemo, useState } from "react";
import { TriangleAlert } from "lucide-react";

import { Dropdown } from "@/components/ui/Dropdown";
import {
  OFFLINE_STATES,
  PINCODE_LENGTH,
  loadAddressReference,
  postalZoneMismatch
} from "@/components/forms/LocationFields";
import type { AddressFieldRole } from "@/components/designworkshop/stageFieldRoles";
import { pincodeOrSpacedValidationError } from "@/components/designworkshop/stageFieldFormats";
import { inputValue, type DwEntryData, type DwField, type DwValue } from "@/lib/designWorkshops";

type DistrictsByState = Record<string, readonly string[]> | null;

function useAddressReference(): { states: readonly string[] | null; districts: DistrictsByState } {
  const [states, setStates] = useState<readonly string[] | null>(null);
  const [districts, setDistricts] = useState<DistrictsByState>(null);

  useEffect(() => {
    let live = true;
    loadAddressReference()
      .then((payload) => {
        if (!live) return;
        setStates(payload.statesAndUnionTerritories ?? null);
        setDistricts(payload.districts?.byState ?? {});
      })
      .catch(() => {
      });
    return () => {
      live = false;
    };
  }, []);

  return { states, districts };
}

function withOwnValue(own: string, served: readonly string[]): string[] {
  return own && !served.includes(own) ? [own, ...served] : [...served];
}

export function StageAddressField({
  field,
  role,
  value,
  row,
  onChange,
  onPatch,
  labelId,
  describedBy,
  invalid,
  disabled
}: {
  field: DwField;
  role: AddressFieldRole;
  value: DwValue | undefined;
  row: DwEntryData;
  onChange: (next: DwValue) => void;
  onPatch: (values: Record<string, DwValue>) => void;
  labelId: string;
  describedBy?: string;
  invalid?: boolean;
  disabled?: boolean;
}) {
  const { states, districts } = useAddressReference();
  const own = inputValue(value);
  const stateName = role.role === "state" ? own : role.stateField ? inputValue(row[role.stateField.key]) : "";

  if (role.role === "pincode") {
    const shape = pincodeOrSpacedValidationError(own);
    const zone = postalZoneMismatch(stateName, own);
    return (
      <div className="grid gap-1">
        <input
          className="field-input"
          type="text"
          inputMode="numeric"
          autoComplete="postal-code"
          placeholder="303007"
          aria-labelledby={labelId}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          value={own}
          disabled={disabled}
          onChange={(event) => {
            const next = event.target.value.replace(/\D/g, "").slice(0, PINCODE_LENGTH);
            onChange(next || null);
          }}
        />
        {shape ? <p className="text-xs text-error-600">{shape}</p> : null}
        {zone ? (
          <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs text-amber-800">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>{zone}</span>
          </p>
        ) : null}
      </div>
    );
  }

  if (role.role === "state") {
    const options = withOwnValue(own, states ?? OFFLINE_STATES);
    return (
      <div className="grid gap-1">
        <Dropdown
          value={own}
          onChange={(next) => {
            if (next === own) return;
            if (!role.districtField) {
              onChange(next || null);
              return;
            }
            onPatch({ [field.key]: next || null, [role.districtField.key]: null });
          }}
          options={options.map((entry) => ({ value: entry, label: entry }))}
          placeholder="Select state"
          searchable
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describedBy}
        />
      </div>
    );
  }

  const served = (stateName && districts?.[stateName]) || [];
  const options = withOwnValue(own, served);
  const stateLabel = role.stateField?.label ?? "State";

  if (!stateName) {
    return (
      <div className="grid gap-1">
        <Dropdown
          value=""
          onChange={() => undefined}
          options={[]}
          placeholder={`Choose a ${stateLabel.toLowerCase()} first`}
          disabled
          ariaLabel={field.label}
          describedBy={describedBy}
        />
      </div>
    );
  }

  if (districts === null) {
    return (
      <div className="grid gap-1">
        <input
          className="field-input"
          type="text"
          aria-labelledby={labelId}
          aria-describedby={describedBy}
          aria-invalid={invalid}
          value={own}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value || null)}
        />
        <p className="text-xs text-ink-500">
          Districts need a connection. Type it for now.
        </p>
      </div>
    );
  }

  return (
    <div className="grid gap-1">
      <Dropdown
        value={own}
        onChange={(next) => onChange(next || null)}
        options={options.map((entry) => ({ value: entry, label: entry }))}
        placeholder="Select district"
        searchable
        disabled={disabled}
        ariaLabel={field.label}
        describedBy={describedBy}
      />
      {options.length === 0 ? (
        <p className="text-xs text-ink-500">
          No districts are listed for {stateName} — leave this blank and let an administrator know.
        </p>
      ) : null}
    </div>
  );
}
