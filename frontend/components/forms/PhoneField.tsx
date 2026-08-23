"use client";

import { useId, useMemo, useState } from "react";

import { Dropdown } from "@/components/ui/Dropdown";
import { useConfirm } from "@/components/dialogs";
import {
  COUNTRIES,
  DEFAULT_DIAL_CODE,
  DEFAULT_ISO2,
  countryByIso2,
  countryForDialCode,
  flagEmoji,
  splitDialPrefix
} from "@/lib/countries";

const FOREIGN_CONFIRM = "This marks the artisan as a foreign resident. Continue?";

/** Split a stored phone ("+CC rest", "+CCrest", or bare 10 digits) into prefix + national digits. */
function parsePhone(raw: string | null | undefined): { iso2: string; digits: string } {
  const value = (raw ?? "").trim();
  if (!value) return { iso2: DEFAULT_ISO2, digits: "" };
  if (value.startsWith("+")) {
    const space = value.indexOf(" ");
    if (space > 0) {
      const country = countryForDialCode(value.slice(0, space));
      if (country) return { iso2: country.iso2, digits: value.slice(space + 1).replace(/\D/g, "") };
    }
    const match = splitDialPrefix(value);
    if (match) return { iso2: match.country.iso2, digits: match.rest };
    return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
  }
  // Bare numbers (legacy rows) are Indian nationals: 10 digits under +91.
  return { iso2: DEFAULT_ISO2, digits: value.replace(/\D/g, "") };
}

/**
 * Phone input with an ISD-prefix picker (flag emoji + dial code, default +91). Submits ONE string
 * ("+91 9876543210") under the existing `phone` field name via a zero-size mirror input, so the
 * FormData handlers and API payload are unchanged. +91 requires exactly 10 digits; any other code
 * 4-14 digits (both blocked natively via the mirror's pattern AND surfaced as an inline error).
 * Switching away from +91 asks for confirmation — it marks the artisan as a foreign resident.
 *
 * ALSO MOUNTED BY THE DESIGN WORKSHOP, on every registry field the registry typed `PHONE`
 * (`participant.phone`, `surveyResponse.contact`). That is why the two props below exist and why
 * neither has a default that changes anything for the record forms:
 *
 * - `disabled` — a stage that has been submitted is read-only, and a control with no way to say so
 *   is a control a designer can edit after the report has gone to the ministry.
 * - `mirror` — a stage form is not a `<form>` and reads nothing from FormData, so the zero-size
 *   mirror there would contribute a stray `name="phone"` and a `pattern` to any form a later change
 *   happened to wrap the page in. Native constraint validation is deliberately absent from the
 *   workshop (see `FieldInput`'s DATE branch on why `required` is never passed): completeness is
 *   judged by `stage_completeness` when a report is generated, not by the browser at save time.
 *
 * The handset makes the same choice and states it: `FieldRenderer.kt` gives `DwFieldType.PHONE` its
 * own arm and calls `ArtisanPhoneField`, the record form's control reused whole, "because the
 * alternative is a second phone field without the measured dial column, the country search and the
 * foreign-resident confirmation this one already carries."
 */
export function PhoneField({
  name = "phone",
  defaultValue,
  onValueChange,
  disabled,
  mirror = true
}: {
  name?: string;
  defaultValue?: string | null;
  onValueChange?: (value: string) => void;
  disabled?: boolean;
  mirror?: boolean;
}) {
  const [{ iso2, digits }, setState] = useState(() => parsePhone(defaultValue));
  const errorId = useId();
  const country = countryByIso2(iso2) ?? countryByIso2(DEFAULT_ISO2)!;
  const dialCode = country.dialCode;
  const combined = digits ? `${dialCode} ${digits}` : "";

  // Android parity (ui/PhoneField.kt artisanPhoneValidationError): same rule, same two sentences,
  // so a researcher who corrects a number on the phone reads the same instruction on the laptop.
  const error = !digits
    ? null
    : dialCode === DEFAULT_DIAL_CODE && digits.length !== 10
      ? "Enter a 10-digit number for +91."
      : dialCode !== DEFAULT_DIAL_CODE && (digits.length < 4 || digits.length > 14)
        ? "Enter a valid phone number (4–14 digits)."
        : null;

  /**
   * THE NAME IS ON THE ROW AND IT IS WHAT THE FILTER BOX SEARCHES.
   *
   * ── WHAT THIS FIXED ─────────────────────────────────────────────────────────────────────────────
   * The label used to be the flag and the dial code and nothing else, while `lib/countries.ts`
   * carries `name` for all ~246 rows. This is the longest list in the application, so it is well
   * past `SEARCH_THRESHOLD` and DID get a filter box — over text that contained no country name. So
   * typing "india", "uruguay" or "nepal" into the country picker answered "No matches", and the only
   * thing that matched was the dial code, which the reader is opening this control to look up. Dial
   * codes are not unique either: "+1" is shared by the United States and Canada and prefixes about
   * twenty more territories, so a dozen rows read identically apart from an emoji nobody can search.
   * `RENDER_CAP` compounds it — 80 of 246 rows are drawn, so scrolling does not reach the rest and
   * the notice says "keep typing", which was the one thing that did not work.
   *
   * ── WHY THE NAME IS THE `hint` AND NOT THE `label`, WHICH IS THE OTHER WAY ROUND FROM ANDROID ───
   * `SearchableSelect` renders the option's LABEL in the closed trigger, and this trigger is a 9rem
   * column beside the number box — the dial code is the whole of what it has room to say, and the
   * dial code is what the composed value `+91 9876543210` is built from. Android does not have that
   * constraint: `PhoneField.kt` keeps a separate read-only "Code" box always showing the prefix, so
   * its rows are free to lead with the name (`SelectOption(value = dialCode, label = it.name, hint =
   * it.dialCode)`). Same two columns on both clients, same searchability on both clients, opposite
   * order — because on one of them the row's first column is also the collapsed control's only text.
   * Stated here rather than left to look like a divergence nobody noticed.
   */
  const options = useMemo(
    () =>
      COUNTRIES.map((entry) => ({
        value: entry.iso2,
        label: `${flagEmoji(entry.iso2)} ${entry.dialCode}`,
        hint: entry.name
      })),
    []
  );

  function emit(next: { iso2: string; digits: string }) {
    setState(next);
    const nextCountry = countryByIso2(next.iso2);
    onValueChange?.(next.digits && nextCountry ? `${nextCountry.dialCode} ${next.digits}` : "");
  }

  const confirm = useConfirm();
  async function handleCountry(nextIso2: string) {
    if (nextIso2 === iso2) return;
    const next = countryByIso2(nextIso2);
    if (!next) return;
    if (dialCode === DEFAULT_DIAL_CODE && next.dialCode !== DEFAULT_DIAL_CODE) {
      // Not destructive — a "did you mean" check — so this warns rather than alarms.
      // Cancel keeps the +91 prefix untouched.
      const ok = await confirm({
        title: "Use an international number?",
        body: FOREIGN_CONFIRM,
        confirmLabel: `Use ${next.dialCode}`,
        cancelLabel: "Keep +91",
        tone: "warning"
      });
      if (!ok) return;
    }
    emit({ iso2: nextIso2, digits });
  }

  return (
    <div className="relative grid gap-1">
      <div className="flex gap-2">
        <Dropdown
          className="w-36 shrink-0"
          value={iso2}
          onChange={handleCountry}
          options={options}
          disabled={disabled}
          ariaLabel="Country dial code"
        />
        <input
          className="field-input min-w-0 flex-1"
          type="text"
          inputMode="numeric"
          autoComplete="tel-national"
          placeholder={dialCode === DEFAULT_DIAL_CODE ? "10-digit mobile number" : "Phone number"}
          value={digits}
          /*
           * NAMED HERE AND NOT BY THE SURROUNDING LABEL. The caller wraps this component in a block
           * whose heading names the FIELD ("Phone"), and the first labelable element inside it is
           * the dial-code trigger, not this box — so without a name of its own the number input fell
           * back to its own placeholder, which is the one piece of text that disappears the instant
           * somebody types into it. A reader who tabbed back to check a number they had already
           * entered heard an unnamed edit box holding ten digits.
           */
          aria-label="Phone number"
          aria-invalid={!!error}
          // `aria-invalid` was already set and pointed at nothing: the browser said "invalid" and
          // the REASON — which of the two length rules was broken — was painted in red under the
          // box and announced nowhere. The two halves only work as a pair.
          aria-describedby={error ? errorId : undefined}
          disabled={disabled}
          onChange={(event) => emit({ iso2, digits: event.target.value.replace(/\D/g, "").slice(0, 15) })}
        />
      </div>
      {/* `role="alert"`: the message appears as the researcher types, so it must reach them at the
          moment the number stops being valid rather than when they next happen to pass the box. */}
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-error-600">
          {error}
        </p>
      ) : null}
      {/* Zero-size (not hidden) mirror input: submits the single combined value under the existing
          field name AND participates in native constraint validation via the pattern. Suppressed by
          `mirror={false}` for a caller that reads the value through `onValueChange` and has no form
          to submit — see the component doc block. */}
      {mirror ? (
        <input
          type="text"
          name={name}
          value={combined}
          pattern={digits ? (dialCode === DEFAULT_DIAL_CODE ? "\\+91 \\d{10}" : "\\+\\d{1,4} \\d{4,14}") : undefined}
          onChange={() => undefined}
          tabIndex={-1}
          aria-hidden="true"
          className="pointer-events-none absolute h-0 w-0 border-0 p-0 opacity-0"
        />
      ) : null}
    </div>
  );
}
