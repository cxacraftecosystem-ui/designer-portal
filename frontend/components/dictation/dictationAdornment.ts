export function dictateButtonLabel(listening: boolean, transcribing = false): string {
  if (transcribing) return "Transcribing";
  return listening ? "Stop dictating" : "Dictate";
}

export function dictateButtonDescription(fieldLabel: string, languageName: string): string {
  return `${fieldLabel}, ${languageName}`;
}

export function dictationLanguageButtonLabel(languageName: string): string {
  return `Dictation language: ${languageName}`;
}

export const DICTATE_BUTTON_BASE =
  "relative grid h-11 w-11 shrink-0 place-items-center rounded-full transition disabled:cursor-not-allowed disabled:opacity-50";
export const DICTATE_BUTTON_IDLE =
  "text-ink-500 hover:bg-purple-50 hover:text-purple-700 dark:hover:bg-purple-950 dark:hover:text-purple-200";
export const DICTATE_BUTTON_RECORDING = "bg-purple-700 text-white ring-4 ring-purple-600/20";

export const DICTATION_LANGUAGE_BUTTON =
  "relative grid h-11 w-6 shrink-0 place-items-center rounded-full text-ink-500 transition hover:text-purple-700 dark:hover:text-purple-200 disabled:opacity-50 after:absolute after:-inset-x-2.5 after:inset-y-0 after:content-['']";

export const DICTATION_FIELD_WRAPPER = "relative min-w-0";
export const DICTATION_FIELD_PADDED = "[&_.field-input]:min-h-11 [&_.field-input]:pe-[4.75rem]";

export const DICTATION_BESIDE_ROW = "flex min-w-0 items-center gap-1";

export function adornmentPosition(multiline: boolean): string {
  return multiline
    ? "absolute end-1 top-1 flex items-center"
    : "absolute inset-y-0 end-1 flex items-center";
}
