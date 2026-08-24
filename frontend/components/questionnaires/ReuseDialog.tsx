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
 * `workshops` is the list the calling page already holds from `listDesignWorkshops({ pageSize: 100 })`
 * → `GET /design-workshops`. For a NON-ADMIN the server scopes it with `visible_to_clause`
 * (`createdById = me OR viewers.some(userId = me)`) — the same door `load_workshop_or_404` opens for
 * the attachment check, which is what makes the picker's contents and the server's answer agree.
 * Fetching a list here would be a second, unscoped source of truth for "which workshops may this
 * account write to", exactly as `UploadDialog` avoids by taking its own workshops as a prop.
 *
 * FOR AN ADMIN THAT CLAUSE IS NOT APPLIED AT ALL. `list_design_workshops` runs it under
 * `elif not is_admin(current_user)`, so an admin's list is the newest 100 of the WHOLE archive rather
 * than a scoped set. Harmless for authorization — an admin may write to every one of them — and
 * stated here because it is the account class for which this picker is the archive AND truncated.
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

export type ReuseTarget = { id: string; title: string };

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
  workshops
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
          {workshops.length ? (
            <div className="grid gap-2">
              <Dropdown
                value={designWorkshopId}
                onChange={setDesignWorkshopId}
                options={[
                  // NAMED, not left as a blank first row. This is a real and useful outcome — a
                  // template the designer owns and attaches later — and an unlabelled empty option
                  // reads as "nothing chosen yet".
                  { value: "", label: "Don't attach it yet" },
                  ...workshops.map((workshop) => ({
                    value: workshop.id,
                    // ANNOTATED, NOT REMOVED, following `attachedElsewhere` on the options endpoint.
                    label:
                      workshop.id === sourceWorkshopId
                        ? `${workshop.title} — where this one already is`
                        : workshop.title
                  }))
                ]}
                ariaLabel="Design workshop for the copy"
                searchable
                disabled={busy}
                advanceOnSelect={false}
              />
              {/*
                DRAWN BESIDE A FULL DROPDOWN, NOT ONLY INSTEAD OF AN EMPTY ONE. This list is ONE page
                of 100 ordered `createdAt desc`, and the dropdown filters it CLIENT-SIDE as you type —
                so a workshop on page two answers "No matches", and absence in a picker reads as "I am
                not allowed to reuse into that workshop". The same page also omits soft-deleted
                workshops an admin may still edit. The sentence is cheap and the wrong conclusion is
                not.
              */}
              <p className="text-xs leading-5 text-ink-500">
                This list is one page of the newest workshops, so a workshop you may write to can be missing from it —
                including a deleted one an admin can still edit. If the one you want is not here, leave this as
                &ldquo;Don&rsquo;t attach it yet&rdquo; and attach the copy from its own page afterwards: that asks the
                server the same question this dropdown would have.
              </p>
            </div>
          ) : (
            // A SENTENCE, NOT AN EMPTY SELECT. The workshop list is one page ordered by creation date
            // and excludes soft-deleted workshops that an admin may still edit, so "not in this list"
            // does not mean "not allowed" — and an unattached copy is attachable from its own page,
            // which is the same PATCH with the same check on it.
            <p className="text-sm leading-6 text-ink-700">
              No design workshops are listed for this account here, so the copy is made unattached. You can attach it from
              its own page afterwards — that asks the server the same question this dropdown would have.
            </p>
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
