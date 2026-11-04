import { languageOptions } from "@/lib/standardValues";

export const INTERVIEW_LANGUAGES: readonly string[] = languageOptions(null).map((option) => option.label);

export const INTERVIEW_LANGUAGE_PLACEHOLDER = "Select language";

export type InterviewLanguageOption = { value: string; label: string };

export function interviewLanguageOptions(current?: string | null): InterviewLanguageOption[] {
  return languageOptions(current);
}
