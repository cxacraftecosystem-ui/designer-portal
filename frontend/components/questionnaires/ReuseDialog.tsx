"use client";

/**
 * "Reuse at another workshop" — the questions of an existing questionnaire, at a second workshop.
 *
 * THE OWNER'S REQUEST, verbatim: questionnaires "would usually be scoped to the workshops, but the
 * designers would have the permission to use the same questionnaire later on for a different
 * workshop as well in case they want to reuse the same template."
 *
 * IT COPIES, AND THE DIALOG SAYS SO BEFORE THE PRESS RATHER THAN REPORTING IT AFTERWARDS. A designer
 * who thinks they are pointing one questionnaire at a second workshop will later fix a typo on one
 * copy and expect the other to change. Two rows, two question trees, two histories: editing one does
 * not touch the other, and that is the sentence that has to be on screen while the target is being
 * picked. (The server rejected the alternative — one questionnaire attached to many workshops — for a
 * harder reason than divergence: a SITTING has no workshop and the report annexure selects purely on
 * `designWorkshopId`, so one workshop's named respondents would have printed inside another
 * workshop's ministry submission.)
 *
 * NO SITTING AND NO ANSWER COMES ACROSS, said here too. "Reuse" is a word a designer could reasonably
 * read as "carry on where I left off", and the fieldwork staying behind is the single fact most worth
 * being sure of before the copy exists.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * THE TARGET LIST IS PASSED IN, NEVER FETCHED HERE
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * `workshops` is the list the calling page already holds from
 * `listDesignWorkshops({ pageSize: WORKSHOP_OPTION_PAGE_SIZE })` → `GET /design-workshops`. For a
 * NON-ADMIN the server scopes it with `visible_to_clause`
 * (`createdById = me OR viewers.some(userId = me)`) — the same door `load_workshop_or_404` opens for
 * the attachment check, which is what makes the picker's contents and the server's answer agree.
 * Fetching a list here would be a second, unscoped source of truth for "which workshops may this
 * account write to", exactly as `UploadDialog` avoids by taking its own workshops as a prop.
 *
 * THAT PAGE SIZE USED TO BE A ROUND HUNDRED, and the number is worth naming rather than rounding:
 * `SearchableSelect` draws at most `RENDER_CAP` (80) rows, so a hundred-row page handed this dialog
 * twenty workshops it silently would not draw, in a band where nothing on screen said anything at
 * all. `WORKSHOP_OPTION_PAGE_SIZE` IS `RENDER_CAP`, so one number governs the fetch and the render
 * and two truncation sentences with two different totals cannot both be true at once.
 *
 * FOR AN ADMIN THE SCOPE CLAUSE IS NOT APPLIED AT ALL. `list_design_workshops` runs it under
 * `elif not is_admin(current_user)`, so an admin's list is the newest page of the WHOLE archive
 * rather than a scoped set. Harmless for authorization — an admin may write to every one of them —
 * and stated here because it is the account class for which this picker is the archive AND
 * truncated.
 *
 * AND THE LIST IS NEVER THE REFUSAL. Three documented gaps mean a workshop the account genuinely may
 * write to can be missing from it: it is ONE page ordered `createdAt desc`, `list_design_workshops`
 * hardcodes `deletedAt: None` while `load_workshop_or_404` still admits an admin to a soft-deleted
 * workshop, and a client-side filter over a truncated page answers "No matches" for a workshop that
 * is merely on page two — trap 1 of the repo's searchable-dropdown rule, where absence reads as "I
 * may not reuse into that workshop". So the SENTENCE explaining the alternative route — make the copy
 * unattached, then attach it from its own page — is drawn WHATEVER the list length: beside the
 * dropdown when there is one, instead of it when there is not. It used to be drawn only for a list of
 * length ZERO, which is the single case where a designer needs no telling that the picker is not the
 * whole truth.
 *
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * SAME WORKSHOP: CAUTIONED, NEVER REFUSED
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Reusing an instrument at the workshop that already has it is legitimate — a baseline round and a
 * follow-up round, which a sitting has no notion of — and a refusal would be walkable in two clicks
 * anyway ("Download question set", then upload it), producing the identical row with no provenance
 * recorded anywhere. So: the targets that already hold a same-titled form are ANNOTATED and left
 * selectable, following `attachedElsewhere` on the options endpoint, which annotates rather than
 * removes for the same reason. There is no `?force=` and no `confirm` boolean — `question-set.xlsx`'s
 * own docstring argues against putting a decision inside a parameter that defaults.
 */

import { useCallback, useEffect, useMemo, useState } from "react";
import { CopyPlus } from "lucide-react";

import { FieldDialog } from "@/components/dialogs";
import { Field, TextInput } from "@/components/FormControls";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown } from "@/components/ui/Dropdown";
import { listQuestionnaires, reuseQuestionnaire, type QFormReuseResult } from "@/lib/questionnaireForms";
import { ATTACH_LATER, designWorkshopOptions, type DesignWorkshopRow } from "@/lib/workshopOptions";

/**
 * A design workshop the copy could be made at.
 *
 * ── IT USED TO BE `{ id, title }`, AND A BARE TITLE IS NOT ENOUGH TO PICK BY ────────────────────
 *
 * Two workshops in the same craft, a fortnight apart, drew as two identical rows here — and this is
 * the control where picking the wrong one is discovered a month later, in a report annexure, by
 * somebody who is not the designer who pressed the button. `DesignWorkshopRow` is the nine fields
 * `lib/workshopOptions` reads, so the picker gains the craft, the cluster and the day the workshop
 * ran, in the same shape as every other workshop picker in the app. A `DwSummary` satisfies it, so
 * the two pages that mount this dialog hand their own scoped rows straight over — which is the rule
 * this file's header states and which has not changed: the list is never fetched here.
 */
export type ReuseTarget = DesignWorkshopRow;

/** The suffix the SERVER appends when no title is sent. Mirrored so the pre-filled box shows the
 * name the row will actually get — a placeholder that guessed differently would be a field a
 * designer "corrects" into the very collision the default avoids.
 *
 * PINNED TO THE SERVER'S OWN LITERAL, not merely commented as matching it. `REUSE_TITLE_SUFFIX` in
 * `backend/app/services/questionnaire_forms.py` is read out of that file and compared with this line
 * by `questionnaire-reuse-unit.spec.ts`. Every other test on both sides asserts the counting SHAPE
 * rather than the word — the client spec builds its expectation from `REUSED` itself, and the backend
 * test asserts "(reused)" server-side only — so changing either constant alone would have drifted the
 * placeholder, and the amber warning built on it, with nothing failing. */
const REUSED = "reused";

/**
 * `reuse_title` in services/questionnaire_forms.py, mirrored INCLUDING ITS COUNTING.
 *
 * MIRRORING THE SUFFIX BUT NOT THE COUNT WAS A BUG, and precisely the one the constant above claims
 * to prevent. The server only names the copy when no title is sent, and when it does it counts up —
 * "X (reused)", then "X (reused 2)" — against the titles already at the target. A placeholder that
 * stopped at "X (reused)" therefore showed a name the row would NOT get, and the amber warning built
 * on it told the designer their copy would collide at exactly the moment the server was about to
 * number it so that it could not. Both halves are fixed by counting here too.
 *
 * The fallback and the 200-character trim are the server's, in the server's order, so a
 * whitespace-only title yields "Questionnaire (reused)" on both sides rather than " (reused)" here.
 */
function reusedTitle(sourceTitle: string, taken: string[]): string {
  const base = (sourceTitle || "").trim().slice(0, 200).trimEnd() || "Questionnaire";
  const lowered = new Set(taken.map((existing) => existing.trim().toLowerCase()));
  let candidate = `${base} (${REUSED})`;
  let n = 2;
  while (lowered.has(candidate.toLowerCase())) {
    candidate = `${base} (${REUSED} ${n})`;
    n += 1;
  }
  return candidate;
}

export function ReuseDialog({
  open,
  onClose,
  onReused,
  questionnaireId,
  sourceTitle,
  sourceWorkshopId,
  workshops,
  workshopsNotice
}: {
  open: boolean;
  onClose: () => void;
  onReused: (result: QFormReuseResult) => void;
  /** The questionnaire being copied FROM. */
  questionnaireId: string;
  sourceTitle: string;
  /** The workshop the source is attached to, so the picker can name it rather than hide it. */
  sourceWorkshopId?: string | null;
  /** Design workshops this account may write to — passed in from the page's own scoped list. */
  workshops: ReuseTarget[];
  /**
   * WHAT THE PAGE HAS TO SAY ABOUT THAT LIST — one string, chosen by the page, drawn here.
   *
   * A string and not the list's state, because this dialog does not do the read and must not be able
   * to describe it differently from the page that did. The page holds one `WorkshopListState` and
   * asks `lib/workshopOptions` which of §3.5's four sentences is true, or `cappedListNotice` for the
   * numbered cut when there are rows and some were left out. The two are never both non-empty.
   *
   * It is drawn IN ADDITION to the standing paragraph below and never instead of it — see there for
   * why that paragraph is unconditional.
   */
  workshopsNotice?: string;
}) {
  const [designWorkshopId, setDesignWorkshopId] = useState("");
  const [title, setTitle] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /**
   * Titles already where this copy is going. `null` = not looked yet or the look failed.
   *
   * READ ON OPEN AND ON EVERY PICK, AND WARNED ON BEFORE THE WRITE, which is the whole point: after
   * the press there are two rows and the designer's only remedy is to take one out of use.
   *
   * "WHERE THIS COPY IS GOING" HAS TWO CASES, and the server counts in both. With a workshop picked
   * it is `where["designWorkshopId"] = designWorkshopId`, the parameter `list_questionnaires` already
   * applies. With "Don't attach it yet" — this dialog's default — it is the caller's own UNATTACHED
   * templates, which is `{designWorkshopId: None, ownerId: me}` on the server and `mineOnly` plus a
   * NULL test here. See the effect below for why the second one cannot be a query parameter.
   */
  const [atTarget, setAtTarget] = useState<string[] | null>(null);

  /**
   * The name the copy will ACTUALLY get if the box is left empty — counted up once the look at the
   * target lands, exactly as the server counts. Before the look (or if it failed) `atTarget` is null
   * and this is the uncounted "X (reused)", which is also what the server would produce against a
   * target it found nothing at.
   */
  const defaultTitle = useMemo(() => reusedTitle(sourceTitle, atTarget ?? []), [sourceTitle, atTarget]);
  /** The same name with no counting, purely to detect that counting happened and say so. */
  const plainDefault = useMemo(() => reusedTitle(sourceTitle, []), [sourceTitle]);

  /**
   * The targets, in the one shared vocabulary, with the source's own workshop marked.
   *
   * ── THE ANNOTATION MOVED FROM THE LABEL INTO THE HINT, AND THAT IS NOT COSMETIC ────────────────
   *
   * It used to be appended to the title: `${workshop.title} — where this one already is`. The label
   * is what `SearchableSelect` prints in the collapsed trigger and what `filterOptions` ranks a
   * typed title against, so a suffix on it pushed the workshop's own name out of a one-line trigger
   * and made an exact-title match rank as a mid-word one. The hint is drawn beneath the label AND
   * searched, so the annotation is just as visible, just as findable, and no longer competing with
   * the name. `lib/workshopOptions` already puts the craft, the cluster and the day it ran there, in
   * that order, so this rides in front of them where the eye lands first.
   *
   * ANNOTATED AND NOT REMOVED, which is the older ruling and is unchanged: reusing an instrument at
   * the workshop that already holds it is legitimate — a baseline round and a follow-up round, which
   * a sitting has no notion of — and the options endpoint's `attachedElsewhere` annotates for the
   * same reason. `group: true` for the other half of the same idea: a copy made into a SUBMITTED
   * workshop is allowed and is something the reader must be able to see they are doing.
   */
  const targets = useMemo(() => {
    const built = designWorkshopOptions(
      // A prop, not a read: this dialog never fetches, so what arrives is by definition an answer.
      // The page that DID do the read owns the four sentences and hands one down as `workshopsNotice`.
      { kind: "ok", rows: workshops, total: workshops.length },
      { group: true, offPage: { mode: "refuse" } }
    );
    return built.options.map((option) =>
      option.value && option.value === sourceWorkshopId
        ? {
            ...option,
            hint: option.hint
              ? `where this one already is · ${option.hint}`
              : "where this one already is"
          }
        : option
    );
  }, [workshops, sourceWorkshopId]);

  const reset = useCallback(() => {
    setDesignWorkshopId("");
    setTitle("");
    setError(null);
    setAtTarget(null);
  }, []);

  // Re-seeded each time the dialog opens rather than held across opens. The list page mounts ONE of
  // these and swaps which row it points at, so without this the target picked for the previous
  // questionnaire would still be selected for the next one — a copy at a workshop nobody chose for
  // it, and the kind of mistake that is only visible in a report annexure a fortnight later.
  useEffect(() => {
    if (open) reset();
  }, [open, reset]);

  useEffect(() => {
    if (!open) {
      setAtTarget(null);
      return;
    }
    let cancelled = false;
    // ``activeOnly: false`` DELIBERATELY. ``reuse_title`` does not filter ``isActive`` when it counts
    // its default up, on the stated ground that "a deactivated form is hidden from the lists but its
    // title is still the title of a row somebody may bring back into use". The list endpoint defaults
    // ``activeOnly`` to TRUE, so without this the two sides would be counting against different sets
    // and the box would show a name the server was about to number differently.
    //
    // AND IT RUNS FOR "DON'T ATTACH IT YET" TOO, WHICH IS THIS DIALOG'S OWN DEFAULT. The effect used
    // to early-return whenever `designWorkshopId` was falsy, so `atTarget` stayed null for the
    // unattached case and the box previewed the UNCOUNTED name. The server counts in that case as
    // well — against `{designWorkshopId: None, ownerId: me}` — so a designer already holding one
    // unattached copy of "Loom survey" was shown "Loom survey (reused)", pressed through, and got
    // "Loom survey (reused 2)" with nothing on screen explaining the rename. The same gap silenced the
    // amber warning for a TYPED title duplicating an existing unattached template: two identically
    // named rows, no caution.
    //
    // TWO CALLS BECAUSE "ATTACHED TO NOTHING" IS NOT EXPRESSIBLE AS A FILTER. This client and
    // `list_questionnaires` both DROP `designWorkshopId` when it is falsy, so no query string means
    // NULL. `mineOnly` is the server's `ownerId = me`; the NULL half is applied to the rows.
    const look = designWorkshopId
      ? listQuestionnaires({ designWorkshopId, pageSize: 100, activeOnly: false })
      : listQuestionnaires({ mineOnly: true, pageSize: 100, activeOnly: false });
    look
      .then((result) => {
        if (cancelled) return;
        const rows = result.items ?? [];
        // BOTH SIDES ARE BOUNDED AND BOTH ARE ORDERED `createdAt desc` — the server counts the newest
        // 500 at the target, this counts the newest 100 — so the preview can miss a collision with a
        // very old row. That is the same "naming annoyance and not a data fault" the server's own
        // bound accepts, and it is why the box stays editable.
        setAtTarget(
          (designWorkshopId ? rows : rows.filter((row) => row.designWorkshopId === null)).map(
            (row) => row.title
          )
        );
      })
      // Silent, and `atTarget` stays null: this look is a COURTESY that runs before the write, and a
      // red banner for a failed courtesy would read as the reuse itself being broken. The copy is
      // still allowed — the server has no title rule at all, by design.
      .catch(() => {
        if (!cancelled) setAtTarget(null);
      });
    return () => {
      cancelled = true;
    };
  }, [open, designWorkshopId]);

  function close() {
    if (busy) return;
    reset();
    onClose();
  }

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const result = await reuseQuestionnaire(questionnaireId, {
        // Both keys are OMITTED when empty rather than sent as "" or null. An absent
        // `designWorkshopId` is "don't attach it yet" and an absent `title` is what lets the server
        // count its own default up against the titles already at the target — a "" would be a title
        // the API rejects on `min_length=1`.
        ...(designWorkshopId ? { designWorkshopId } : {}),
        ...(title.trim() ? { title: title.trim() } : {})
      });
      reset();
      onReused(result);
    } catch (err) {
      // Kept in the dialog: the target and title the designer chose are still on screen, so a 404
      // ("that workshop is not one you can write to") or a 409 ("restore it before editing") is one
      // correction away from succeeding. Both carry a sentence written to be shown as-is.
      setError(err instanceof Error ? err.message : "Unable to reuse this questionnaire");
    } finally {
      setBusy(false);
    }
  }

  const typed = title.trim();
  /**
   * ONLY A TYPED TITLE CAN COLLIDE. A title the designer sends is used by the server VERBATIM — no
   * counting, no suffix — so a collision on it is real and permanent. An EMPTY box sends no title at
   * all, which is what invites ``reuse_title`` to count its default up past every name already
   * there; warning about that case announced a collision the server was in the act of preventing.
   */
  const collides =
    Boolean(typed) && (atTarget ?? []).some((existing) => existing.trim().toLowerCase() === typed.toLowerCase());
  /**
   * WHERE THE CLASH IS, in words, because the two destinations are not the same kind of place. An
   * unattached template is in nobody's report annexure — `report_items` cannot reach a row with a NULL
   * `designWorkshopId` — so the annexure sentence, which is the whole reason a designer would bother
   * renaming, would be false for the case this dialog defaults to.
   */
  const place = designWorkshopId
    ? {
        already: "is already at that workshop",
        both: "both will appear in that workshop’s report annexure under the same name",
        apart: "in the report annexure"
      }
    : {
        already: "of yours is already waiting to be attached to a workshop",
        both: "both will sit in your questionnaire list under the same name",
        apart: "in your list"
      };
  /** The empty-box case where the server's own counting had to step in, worth saying rather than warning. */
  const numbered = !typed && defaultTitle !== plainDefault;
  const sameWorkshop = Boolean(designWorkshopId) && designWorkshopId === sourceWorkshopId;

  return (
    <FieldDialog
      open={open}
      onClose={close}
      busy={busy}
      title="Reuse this questionnaire at another workshop"
      description="You get a new questionnaire of your own carrying these questions. The original is untouched, and no recorded answer is copied."
      icon={<CopyPlus className="h-5 w-5" aria-hidden />}
      footer={
        <>
          <button type="button" className="field-button-secondary" onClick={close} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="field-button" onClick={submit} disabled={busy}>
            {busy ? "Copying the questions…" : "Create the reuse"}
          </button>
        </>
      }
    >
      <div className="grid gap-4">
        {error ? (
          <div className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
        ) : null}

        {/*
          WHAT THIS DOES AND WHAT IT DOES NOT DO, before the target is picked. The second bullet is
          the one that cannot be left out: "reuse" reads as "carry on with the same form", and a
          designer who believed the sittings came too would go looking for last month's interviews in
          a questionnaire that has none and conclude the app lost them.
        */}
        <div className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
          <ul className="grid gap-1">
            <li>
              <span className="font-medium text-ink-900">The questions come across.</span> Sections, order, help text and
              required flags, exactly as they are now. Retired questions do not — they are kept where their answers are,
              not because they are still asked.
            </li>
            <li>
              <span className="font-medium text-ink-900">The answers do not.</span> Every sitting recorded against the
              original stays on the original, under the names of the people who recorded it. Your copy starts empty.
            </li>
            <li>
              <span className="font-medium text-ink-900">The two are separate from here on.</span> Rewording a question on
              one does not change the other, so a correction made after this point has to be made in both places.
            </li>
          </ul>
        </div>

        {/* FieldBlock rather than Field: `Field` is a <label>, and a <label> wrapped round a themed
            dropdown forwards a stray click into the menu and slams it shut after one pick. */}
        <FieldBlock label="Design workshop for the copy">
          {/*
            THE BRANCH STAYS, AND THE SENTENCE IS DRAWN ON BOTH SIDES OF IT. With nothing to offer, a
            dropdown holding only its un-file row is a control whose one answer is the answer the
            field already has; a sentence says the same thing and says what to do instead. What is
            NOT allowed back is the older shape, where the way out was drawn ONLY on the empty side —
            a full list here is one page, and this box searches only the rows in it, so a workshop
            this account may genuinely write to can be missing from a dropdown that looks complete.
          */}
          {workshops.length ? (
            <div className="grid gap-2">
              <Dropdown
                value={designWorkshopId}
                onChange={setDesignWorkshopId}
                options={targets}
                /*
                  "Don't attach it yet" IS THE PRIMITIVE'S ROW NOW, and its label is the shared
                  `ATTACH_LATER` constant. It is one of the four "none" strings this app keeps, and a
                  different one from the record forms' "Not filed under a design workshop": this is a
                  COPY operation where the answer can genuinely be deferred and the copy is still
                  made, which is a fact about this dialog rather than about the field. Two layers must
                  not both build the row — a hand-built one plus `noneLabel` gives two options sharing
                  the React key "", and a control that cannot say which of the two is selected.
                */
                noneLabel={ATTACH_LATER}
                ariaLabel="Design workshop for the copy"
                searchable
                disabled={busy}
                advanceOnSelect={false}
              />
              {/*
                WHAT THE PAGE'S READ HAS TO SAY — on this branch, the numbered cut. It carries the
                NUMBER the paragraph below cannot: this dialog does not do the read and must not
                count what it did not fetch, and a cap asserted without its number is rule 10 wearing
                a hedge. The page chooses the sentence so that its own picker, this one and the upload
                dialog cannot word one read three ways.
              */}
              {workshopsNotice ? (
                <p className="text-xs leading-5 text-ink-500" aria-live="polite">
                  {workshopsNotice}
                </p>
              ) : null}
              {/*
                DRAWN BESIDE A FULL DROPDOWN, NOT ONLY INSTEAD OF AN EMPTY ONE. Three documented gaps
                mean a workshop this account may genuinely write to can be missing from a list that
                looks complete: it is ONE page ordered `createdAt desc`; `list_design_workshops`
                hardcodes `deletedAt: None` while `load_workshop_or_404` still admits an admin to a
                soft-deleted workshop; and this control's box filters the rows already fetched rather
                than asking the repository. Absence in a picker reads as "I am not allowed to reuse
                into that workshop", and the route out is one sentence.
              */}
              <p className="text-xs leading-5 text-ink-500">
                This list is one page of the newest workshops, so a workshop you may write to can be missing from it —
                including a deleted one an admin can still edit, and one this box cannot reach because it searches only
                the rows drawn here. If the one you want is not here, leave this as &ldquo;{ATTACH_LATER}&rdquo; and
                attach the copy from its own page afterwards: that asks the server the same question this dropdown
                would have.
              </p>
            </div>
          ) : (
            <div className="grid gap-2">
              {/*
                A SENTENCE, NOT AN EMPTY SELECT — and now one that says WHICH kind of empty this is.
                It used to say "No design workshops are listed for this account here" whatever the
                reason, a read that had failed included, which is a claim about a grant table made
                from a request that never arrived. `workshopsNotice` is the page's answer to that
                question; the paragraph under it is what does not change with the answer.
              */}
              {workshopsNotice ? (
                <p className="text-sm leading-6 text-ink-700" aria-live="polite">
                  {workshopsNotice}
                </p>
              ) : null}
              <p className="text-sm leading-6 text-ink-700">
                No design workshop is on offer here, so the copy is made unattached. You can attach it from its own
                page afterwards — that asks the server the same question this dropdown would have.
              </p>
            </div>
          )}
        </FieldBlock>

        <Field label="Title for the copy">
          <TextInput
            value={title}
            maxLength={220}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={defaultTitle}
          />
        </Field>

        {/*
          THE COLLISION WARNING, BEFORE THE WRITE. Said only when it is true: a warning that fired on
          every pick would be read past by the second use, and this one is the difference between two
          rounds of one instrument and two rows nobody can tell apart in the annexure.
        */}
        {collides ? (
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-sm leading-6 text-amber-800">
            A questionnaire called <span className="font-medium">“{typed}”</span> {place.already}. This is allowed — a
            second round of the same instrument is an ordinary thing to run — but the title you have typed is used as it
            stands, so {place.both}. Clear the box to have it numbered for you, or say which round this one is.
          </p>
        ) : numbered ? (
          <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            A questionnaire of that name {place.already}, so the copy will be called{" "}
            <span className="font-medium text-ink-900">“{defaultTitle}”</span> — numbered up so the two are tellable apart{" "}
            {place.apart}. Type a title to say which round it is instead.
          </p>
        ) : sameWorkshop ? (
          <p className="rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm leading-6 text-ink-700">
            That is the workshop this questionnaire is already attached to. Perfectly allowed — a follow-up round is a
            second copy, because a sitting has no notion of which round it belongs to — and both copies will print in that
            workshop&rsquo;s report annexure.
          </p>
        ) : null}
      </div>
    </FieldDialog>
  );
}
