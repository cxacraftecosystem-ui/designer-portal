/**
 * The pure half of the dictation control's look: the microphone as an icon button in the input's
 * end adornment, and the language as a small menu beside it (owner ruling, 2026-10-17, audit DW-14).
 *
 * Before, every prose field carried a separate "Dictate" button under the box and an underlined
 * "English (India)" link beside it — two controls and a line of text per field, on a stage that
 * already read as a wall of copy on a phone. Android draws the same shape in `DwDictation.kt`
 * (mic in the trailing icon, language menu from the caret), with the same names.
 *
 * Split out because this repository has no React renderer in its devDependencies:
 * `e2e/stage-help-dictate-unit.spec.ts` reads these directly.
 */

/** The microphone's accessible name. Short on purpose; the field and language are its description. */
export function dictateButtonLabel(listening: boolean, transcribing = false): string {
  if (transcribing) return "Transcribing";
  return listening ? "Stop dictating" : "Dictate";
}

/** What the microphone is describing: which box, in which language. */
export function dictateButtonDescription(fieldLabel: string, languageName: string): string {
  return `${fieldLabel}, ${languageName}`;
}

/** The caret that opens the language menu. Says the current choice, so it is never hidden. */
export function dictationLanguageButtonLabel(languageName: string): string {
  return `Dictation language: ${languageName}`;
}

/**
 * The 44px press target, and the two looks. Recording is a FILLED purple disc with a white stop
 * square and a static ring — the state is visible without the pulse, which reduced motion removes.
 */
export const DICTATE_BUTTON_BASE =
  "relative grid h-11 w-11 shrink-0 place-items-center rounded-full transition disabled:cursor-not-allowed disabled:opacity-50";
export const DICTATE_BUTTON_IDLE = "text-ink-500 hover:bg-purple-50 hover:text-purple-700";
export const DICTATE_BUTTON_RECORDING = "bg-purple-700 text-white ring-4 ring-purple-600/20";

/** The language caret: as tall as the microphone, narrow, and widened to 44px by its `after:` box. */
export const DICTATION_LANGUAGE_BUTTON =
  "relative grid h-11 w-6 shrink-0 place-items-center rounded-full text-ink-500 transition hover:text-purple-700 disabled:opacity-50 after:absolute after:-inset-x-2.5 after:inset-y-0 after:content-['']";

/**
 * The wrapper the input sits in when the microphone is drawn, and the padding the box needs so its
 * text never runs under the two buttons. Applied to the `.field-input` inside by an arbitrary
 * variant, which as a utility beats the recipe class.
 */
export const DICTATION_FIELD_WRAPPER = "relative min-w-0";
export const DICTATION_FIELD_PADDED = "[&_.field-input]:min-h-11 [&_.field-input]:pe-[4.75rem]";

/** Where the adornment sits: centred on a one-line box, top-right on a textarea. */
export function adornmentPosition(multiline: boolean): string {
  return multiline
    ? "absolute end-1 top-1 flex items-center"
    : "absolute inset-y-0 end-1 flex items-center";
}
