"use client";

/**
 * The administrative half of an address, inside a stage — state, district and PIN code.
 *
 * WHY THIS EXISTS. `FieldInput` rendered all eleven of the registry's address boxes
 * (`participant.state`/`district`/`pincode`, `workshopSetup.state`/`district`,
 * `existingProduct.recordState`/`recordDistrict`/`recordPincode`, `tool.recordState`/
 * `recordDistrict`/`recordPincode`) as bare `<input type="text">` with a dictation button beside
 * them, while the record page gives the same facts two dependent closed lists and a digits-only PIN
 * box. Both halves of that mattered:
 *
 * - THE CLOSED LIST. `Location.state`'s own column docstring says the canonical names exist because
 *   "free text here would split one state across four spellings in every group-by and export, the
 *   way craft names did before title-casing", and the district's adds "several names are shared by
 *   two states — so the pair is validated together, never apart". A stage entry is a copy frozen at
 *   save time that nothing re-resolves (invariant 1), so a hand-typed "Rajastan" or a district under
 *   the wrong state is what the ministry's document says for good.
 * - THE KEYBOARD. `FieldInput`'s TEXT branch already argues it for phone numbers — "a phone field
 *   that opens the alphabetic keyboard is a phone field a designer mistypes on a handset in a
 *   courtyard" — and a PIN code is the same field with the same failure, on the one box in the row
 *   with an exact machine-checkable format.
 *
 * ONE CONTROL, NOT A SECOND COPY. The lists, the offline fallback, the zone check and the six-digit
 * rule all come from `components/forms/LocationFields` — the same module, the same functions and the
 * same shared module-level `/reference/address` promise the record pages use. A second state list
 * here would be the drift `loadAddressReference`'s own doc block refuses.
 *
 * WHAT IS DELIBERATELY *NOT* COPIED FROM THE RECORD PAGE:
 *
 * - No `required`, on any of the three. Completeness is judged by `stage_completeness` when a report
 *   is generated, never by the browser at save time — see `FieldInput`'s DATE branch, which declines
 *   `required` for the same reason. A required dropdown that has not loaded its options is a stage
 *   nobody can submit.
 * - No enforcement of the zone check or the six-digit rule. Surfaced only, exactly as the record page
 *   has it: "The zone digit can prove the pair wrong but never prove it right", and a designer
 *   standing in the village outranks the table.
 * - No dictation button. A recogniser hands back words, and "double oh three" for 003 is a
 *   guaranteed correction — the same ground on which `FieldInput` already withholds the button from
 *   URL, EMAIL and PHONE.
 */

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

/** The served district lists, keyed by state, or null until the shared request lands. */
type DistrictsByState = Record<string, readonly string[]> | null;

/**
 * The served address reference for this tab, through the SHARED module-level promise.
 *
 * One state per mounted field is the honest cost of `FieldInput` having no place to hang a stage-wide
 * fetch; the REQUEST is not repeated, because `loadAddressReference` memoises it, so eleven address
 * boxes on one stage still issue exactly one `/reference/address` call between them.
 */
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
        // Offline, or the endpoint is unhappy, which out here is a normal minute of the morning.
        // Swallowed on purpose: the state list falls back to OFFLINE_STATES and stays answerable and
        // the district box falls back to a plain text input, so nothing about a failed reference
        // fetch can cost a stage an answer.
      });
    return () => {
      live = false;
    };
  }, []);

  return { states, districts };
}

/**
 * The row's own stored value kept at the front of the options, in or out of the served list.
 *
 * The record page treats this as a rule and the workshop needs it more, not less: hydration copies a
 * value from a record whose district may predate a lineage change, and a dropdown showing "Select"
 * over a district the entry really holds reads as "not answered" and invites the designer to answer
 * it again — which is how the same fact ends up stored twice under two spellings.
 */
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
  /**
   * The state this row is IN — what the district list is keyed by and what the zone check is checked
   * against. Derived per role, and never `own` as a fall-through.
   *
   * `role.role === "state"` is the only case where this box IS the state, so it is the only case
   * where `own` is one. The expression used to end `: own`, which meant a `pincode` field on an
   * entity declaring no state sibling would have handed the PIN CODE ITSELF to `postalZoneMismatch`
   * as a state name. Nothing does that today — all three pincode fields have a state sibling on
   * their own entity, and `postalZoneMismatch` returns null for a name its table does not know — so
   * this is the expression being made to read as what it means before a new address field inherits
   * it. An absent sibling now means "no state to check against", which is what both callers want.
   */
  const stateName = role.role === "state" ? own : role.stateField ? inputValue(row[role.stateField.key]) : "";

  if (role.role === "pincode") {
    /*
     * Advisory, both of them, and in the record page's own words. The shape check is the one the
     * API also runs (same three sentences, same order, `services/address.py`), and
     * `postalZoneMismatch` is the one that catches a real mistake — a PIN that belongs to a
     * different state from the one on the row above it.
     *
     * ── `pincodeOrSpacedValidationError` AND NOT `pincodeValidationError`, AND THE DIFFERENCE IS
     *    A RED LINE UNDER A VALUE THE REPOSITORY ACCEPTS ─────────────────────────────────────────
     *
     * This line used to read `pincodeValidationError(own)` — the RAW stored value. That function
     * refuses anything which is not six bare digits, which is right where it lives, because the
     * record page's box strips non-digits as they are typed. THIS box is a hydration target: it
     * shows whatever `participant.pincode` holds, and that field's own comment in
     * `stage_definitions.py` names "768 029" — typed exactly that way by somebody reading an address
     * aloud — as a value already in the column. The server normalises before it checks
     * (`_pincode_format_error`) and so does the handset (`DwTextFormats.pincodeError`), so all three
     * of them ACCEPT that value while this box drew "Pincode must be 6 digits — remove any letters
     * or symbols." under it.
     *
     * And it drew it alone, which is what made it worse than a wrong message: `formatShownByControl`
     * returns true for this role, so `FieldInput`'s wrapper deliberately stays quiet and lets this
     * control speak — while `aria-invalid` and the "N to fix" count on a collapsed disclosure are
     * both driven from `fieldFormatError`, which said the value was fine. A red sentence with no
     * `aria-invalid`, inside a group whose header said nothing, about a value nobody could correct.
     * One function now answers for all three, and it is the one the server's dispatch matches.
     */
    const shape = pincodeOrSpacedValidationError(own);
    const zone = postalZoneMismatch(stateName, own);
    return (
      <div className="grid gap-1">
        <input
          className="field-input"
          type="text"
          // The content is six digits. `inputMode` opens the numeric keypad on the handset this form
          // is filled on, `autoComplete` lets the browser offer the one the designer has typed
          // before, and the strip below means a letter cannot be entered at all — which is exactly
          // what the record page's own box does.
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
          /*
           * Surfaced, never enforced — see the file header. It names the contradiction and leaves the
           * designer, who was standing there, to say which half of it is the mistake.
           *
           * `amber-100` behind `amber-800`, which is the pairing `LocationFields`' own `CardNotice`
           * settled on and wrote down: 50 and 200 are not in this project's amber ramp
           * (tailwind.config.ts defines 100/500/800), so `bg-amber-50` would leave dark-brown text on
           * whatever the card is and vanish on the dark theme. A fixed light chip is legible in both.
           */
          <p className="flex items-start gap-2 rounded-md border border-amber-500 bg-amber-100 px-3 py-2 text-xs text-amber-800">
            <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
            <span>{zone}</span>
          </p>
        ) : null}
      </div>
    );
  }

  if (role.role === "state") {
    // The served list wins whenever it exists, so the day the register changes the deployed API is
    // still the authority and no client needs shipping. OFFLINE_STATES stands in until then — the 36
    // names are bundled precisely so this box is answerable with no signal.
    const options = withOwnValue(own, states ?? OFFLINE_STATES);
    return (
      <div className="grid gap-1">
        <Dropdown
          value={own}
          onChange={(next) => {
            /*
             * RE-PICKING THE STATE ALREADY SHOWING IS NOT A CHANGE, and without this line it wiped
             * the district. `SearchableSelect.choose` fires `onChange` unconditionally — there is no
             * equality guard there and there should not be, since a caller that wants one can say so
             * — so opening the list and tapping the value already selected, which is how a designer
             * confirms what a hydrated row says, ran the clear below.
             *
             * The record page loses the same gesture (`FormControls`' Select is a wrapper over this
             * same component), but it can afford to: an artisan record can be re-edited against the
             * live district list. A stage entry cannot. Hydration only re-fills on a re-point to a
             * DIFFERENT record (invariant 2), and re-answering the district by hand needs the network
             * the district list may not have.
             */
            if (next === own) return;
            if (!role.districtField) {
              onChange(next || null);
              return;
            }
            // ONE COMMIT, both keys. A district only means anything inside its own state, so
            // changing the state invalidates the answer below it — cleared rather than kept, for the
            // reason the record page gives. `onPatch` and not two `onChange` calls because a render
            // between them would see a row naming one state and a district from another, which is
            // the state hydration goes to some trouble never to expose.
            onPatch({ [field.key]: next || null, [role.districtField.key]: null });
          }}
          options={options.map((entry) => ({ value: entry, label: entry }))}
          placeholder="Select state"
          /* `searchable` by provenance: reference data, not a vocabulary written in this file. The
             state list never falls under the threshold, so this changes nothing today — it is here
             so both halves of the state/district pair are governed by one rule. See the district
             below, where the same rule IS a behaviour change. */
          searchable
          disabled={disabled}
          ariaLabel={field.label}
          describedBy={describedBy}
        />
      </div>
    );
  }

  // DISTRICT. 795 names, revised several times a year and meaningful only per state, so unlike the
  // states they genuinely cannot be bundled.
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
    /*
     * THE FALLBACK IS A TEXT BOX, and it is the one place this control declines to close the gap.
     *
     * The district list needs the network, and a closed dropdown with no members is a box that
     * cannot be answered at all — on a stage being filled in a courtyard that is a fact the designer
     * simply loses. So until the list arrives the plain input the workshop had all along is still
     * offered, and it says why. Never leave a designer with an unanswerable box.
     *
     * IT FALLS BACK EVEN WHEN THE ROW ALREADY HOLDS A DISTRICT, which is the subtler half. A
     * dropdown offering exactly one option — the hydrated value merged in by `withOwnValue` — looks
     * answerable and is not: a designer who can see the copied district is wrong has no way to say
     * so until there is signal, and this is a frozen copy nothing will re-resolve later.
     */
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
          The district list has not loaded — it needs a connection, unlike the states. Type the district for now and
          re-pick it from the list when there is signal.
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
        /* ── `searchable` HERE IS A FIX ─────────────────────────────────────────────────────────
           795 names, and PER STATE the list straddles `SEARCH_THRESHOLD` in both directions: Goa
           2, Sikkim 6, Uttar Pradesh 75. Left to the count, this box grew a filter box for one
           state and lost it for the next one on the same stage form — the defect `ProcessForm`
           names in so many words, and the reason the rule on `SearchableSelectProps.searchable` is
           about where the options CAME FROM and not how many there are today. */
        searchable
        disabled={disabled}
        ariaLabel={field.label}
        describedBy={describedBy}
      />
      {options.length === 0 ? (
        <p className="text-xs text-ink-500">
          No districts are listed for {stateName} — leave this blank and report the gap.
        </p>
      ) : null}
    </div>
  );
}
