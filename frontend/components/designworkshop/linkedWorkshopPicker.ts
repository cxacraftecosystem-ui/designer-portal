import type { SelectOption } from "@/components/ui/selectFilter";
import {
  fieldWorkshopOptions,
  workshopCutSentence,
  workshopEmptyLabel,
  workshopListNotice,
  workshopListStandsDown,
  type FieldWorkshopRow,
  type WorkshopListState,
  type WorkshopListVoice,
  type WorkshopOptionSet
} from "@/lib/workshopOptions";

export const LINKED_WORKSHOP_SCOPE_SENTENCE =
  "Design & Prototype workshops you can access.";

export const LINKED_WORKSHOP_KIND_GAP =
  "This list also only holds workshops whose Kind is “Design & Prototype Development Workshop”, and " +
  "that Kind is set on the Workshops page by a professor or an administrator — a designer account cannot " +
  "set it. So an empty list here can equally mean the workshops exist and have not been marked yet. Ask a " +
  "professor or an admin to mark the right one, or to give you access to it.";

export const UNRESOLVED_LINK_LABEL =
  "Linked workshop (no access)";

export const LOADING_PLACEHOLDER = "Loading workshops…";

export const READY_PLACEHOLDER = "Select or type to search";

export type LinkedWorkshopPickerInput = {
  list: WorkshopListState<FieldWorkshopRow>;
  searchApplied: string;
  term: string;
  pending: boolean;
  storedId: string;
  storedRow: FieldWorkshopRow | null;
  online: boolean;
};

export type LinkedWorkshopView = {
  set: WorkshopOptionSet;
  options: SelectOption[];
  notice: string;
  gap: string;
  emptyLabel: string;
  cut: string;
  standingDown: boolean;
  placeholder: string;
  unresolved: boolean;
};

export function linkedWorkshopView(input: LinkedWorkshopPickerInput): LinkedWorkshopView {
  const voice: WorkshopListVoice = { table: "field", scoped: true, online: input.online };

  const stored = input.storedId.trim();
  const listed = input.list.kind === "ok" ? input.list.rows : [];
  const onPage = stored !== "" && listed.some((row) => row.id === stored);
  const unresolved = stored !== "" && !onPage && input.storedRow === null;

  const recovered: FieldWorkshopRow | null =
    stored === "" || onPage ? null : (input.storedRow ?? { id: stored, title: UNRESOLVED_LINK_LABEL });

  const set = fieldWorkshopOptions(input.list, {
    group: true,
    offPage: { mode: "recover", row: recovered }
  });

  const stateNotice = input.list.kind === "ok" && input.searchApplied ? "" : workshopListNotice(input.list, voice);
  const hasRows = input.list.kind === "ok" && input.list.rows.length > 0;
  const notice = stateNotice || (hasRows ? LINKED_WORKSHOP_SCOPE_SENTENCE : "");

  const gap =
    input.list.kind === "ok" && !input.searchApplied && input.list.rows.length === 0
      ? LINKED_WORKSHOP_KIND_GAP
      : "";

  const standingDown = !input.pending && !input.term.trim() && workshopListStandsDown(set);

  return {
    set,
    options: set.options,
    notice,
    gap,
    emptyLabel: workshopEmptyLabel(input.list, voice),
    cut: workshopCutSentence(set, { term: input.searchApplied, searchable: true }),
    standingDown,
    placeholder: input.list.kind === "loading" ? LOADING_PLACEHOLDER : READY_PLACEHOLDER,
    unresolved
  };
}
