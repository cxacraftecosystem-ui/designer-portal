"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";

import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, type DropdownOption } from "@/components/ui/Dropdown";
import {
  ELIGIBLE_VIEWER_SEARCH_MAX,
  eligibleViewerNotice,
  listEligibleDesignWorkshopViewers,
  viewerAdministrationMissing,
  type DwEligibleViewer
} from "@/lib/designWorkshopViewers";
import { roleLabel } from "@/lib/permissions";

/**
 * Matches the viewers panel's own debounce. An `ILIKE '%term%'` over `User` is a scan no index can
 * answer, so every keystroke that escapes this is a full scan of the largest table in the database.
 */
const SEARCH_DEBOUNCE_MS = 300;

/**
 * WHO THE WORKSHOP IS FOR, CHOSEN BEFORE IT EXISTS — the create form's designer picker.
 *
 * ── THE DEFECT IT CLOSES ────────────────────────────────────────────────────────────────────────
 * `seed_designer_prefill` copies a `DesignerProfile` into stage 1 and stage 3, and until this
 * control existed the profile it copied was always the CREATOR'S. For an admin opening a workshop
 * on somebody else's behalf that is the wrong person's name on a ministry document, and it is not a
 * hypothetical: `require_designer` admits ADMIN, `GET /designers/me/profile` upserts a row for any
 * admin who so much as opens the Designer Profile screen, and `prefill_from_profile`'s tail
 * fallback then writes `profile.user.name` — so an admin who has never filled anything in still
 * lands their own account name on the promoted `designerName` column.
 *
 * The server grew `designerUserId` for this, with `assert_designer_may_be_named` and
 * `attach_the_named_designer` behind it. **Neither client could send it**, so the repair was
 * unreachable on every real request. This is the web half of reaching it.
 *
 * ── THE SAME ENDPOINT AS THE VIEWERS PANEL, DELIBERATELY ────────────────────────────────────────
 * `GET /design-workshops/eligible-viewers`, not a new eligibility set. `assert_designer_may_be_named`
 * delegates to the same `_assert_every_id_may_be_granted` the viewers PUT uses, so offering an
 * account here that the create would refuse is impossible by construction rather than by agreement.
 * A second endpoint would be a second copy of a rule that spans two rosters — the designer roster is
 * a guest list, the platform allow-list is a cut list — and it would drift within one release.
 *
 * Naming somebody here also PUTS THEM ON THE WORKSHOP: the create route grants their viewer row in
 * the same call. That is why the hint says so. The two admin steps this replaces were "create, then
 * remember to add the designer", and forgetting the second is how a designer ends up locked out of
 * the workshop whose stage 1 already carries their name.
 *
 * ── THE SEARCH BOX IS THE SERVER'S, AND THE PICKER'S OWN FILTER IS OFF ──────────────────────────
 * The house rule for a server-truncated list (`.claude/skills/field-repo-frontend`, §11.5): the
 * endpoint answers at most 2000 accounts and the cap is reached on a real repository, so a
 * client-side filter box would search only the part of the alphabet that fitted and answer "No
 * matches" for a colleague who is eligible and merely sorts late — absence reading as
 * non-existence. So: one box, above the control, wired to the server, and `searchable={false}` on
 * the `Dropdown` beneath it. `searchable={false}` does NOT switch the render cap off, which is why
 * `capHint` names the box that DOES reach the rest rather than letting the default sentence tell an
 * admin to type into a filter that is not on screen.
 *
 * **The order is the server's and is never re-sorted here** — `name` then `id`, a total order, so
 * which accounts fell inside the ceiling is stable between two identical requests.
 *
 * ── WHAT IT DOES NOT DO ─────────────────────────────────────────────────────────────────────────
 * It never writes `designerName`. That column is DENORMALISED from stage 1 by `promoted_values()`
 * and is display-only on both clients; writing it by hand from a picker is how the JSON and the
 * column come to disagree about the same fact, and the name the report prints would then depend on
 * which of the two a screen happened to read.
 */
/**
 * One account as a row.
 *
 * `hint` is drawn after the label and is searched by the control's own filter — which is off here,
 * so it is doing only the first job. Two people share a display name more often than this repository
 * would like; the email is what tells them apart.
 */
function rowFor(person: DwEligibleViewer): DropdownOption {
  return {
    value: person.id,
    label: person.name || person.email,
    hint: [person.email, roleLabel(person.role)].filter(Boolean).join(" · ")
  };
}

export function WorkshopDesignerPicker({
  value,
  onChange,
  disabled,
  offline
}: {
  /** The chosen account id, or "" for "not decided yet". */
  value: string;
  onChange: (userId: string) => void;
  disabled?: boolean;
  /**
   * There is no connection, so the eligible set cannot be read.
   *
   * The create form works offline — that is the whole reason it mints a local id — and the picker
   * is the one control on it that CANNOT, because eligibility is two roster reads on the server and
   * no useful part of it can be answered from this device. Rule 10 governs what happens then: an
   * empty picker with nothing said is indistinguishable from a repository with no eligible
   * designers, so the control stands down and says why instead of offering an empty list.
   */
  offline?: boolean;
}) {
  const [search, setSearch] = useState("");
  const [eligible, setEligible] = useState<DwEligibleViewer[] | null>(null);
  /**
   * Every eligible account this mount has been shown, across every search it has run.
   *
   * MERGED, NEVER REPLACED — see the append in `options` for the defect it closes. It is a mount-life
   * cache of names already served, not a second source of eligibility: nothing is ever OFFERED from
   * here that the server did not offer first, and the one row it can add is the pick already made.
   */
  const [seen, setSeen] = useState<Map<string, DwEligibleViewer>>(() => new Map());
  /** The server's own word for "this answer is not the whole eligible set". Rendered once, when true. */
  const [truncated, setTruncated] = useState(false);
  const [searching, setSearching] = useState(false);
  /** The server has no such route — an older deployment. Says so rather than showing an empty list. */
  const [featureMissing, setFeatureMissing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const noticeId = useId();

  /**
   * A GENERATION COUNTER, NOT A `cancelled` FLAG, for the reason the viewers panel gives: `apiFetch`
   * takes no `AbortSignal`, so two searches are in flight whenever somebody types quickly, and a
   * slow answer for "kam" landing after the fast one for "kamla" would leave the wrong list under
   * the typed word — which is exactly the moment somebody picks the first row without reading it.
   */
  const generation = useRef(0);

  useEffect(() => {
    if (offline) {
      setEligible(null);
      setTruncated(false);
      setSearching(false);
      return;
    }
    const term = search.trim();
    const current = generation.current + 1;
    generation.current = current;
    setSearching(true);
    const timer = window.setTimeout(
      () => {
        listEligibleDesignWorkshopViewers(term)
          .then((result) => {
            if (generation.current !== current) return;
            const users = result.users ?? [];
            setEligible(users);
            setSeen((previous) => {
              const next = new Map(previous);
              for (const person of users) next.set(person.id, person);
              return next;
            });
            // Coerced rather than trusted: a deployment predating the field leaves it `undefined`,
            // and an unknown flag must say nothing rather than cry truncation at a complete list.
            setTruncated(Boolean(result.truncated));
            setFeatureMissing(false);
            setLoadError(null);
            setSearching(false);
          })
          .catch((err: unknown) => {
            if (generation.current !== current) return;
            setSearching(false);
            setTruncated(false);
            setEligible([]);
            if (viewerAdministrationMissing(err)) {
              setFeatureMissing(true);
              return;
            }
            setLoadError("Unable to load the designers this workshop could be for.");
          });
      },
      // No debounce on the first, empty read: it is one request on open, and making an admin wait
      // 300ms to see the list they just opened is a delay with nothing to pay for it.
      term ? SEARCH_DEBOUNCE_MS : 0
    );
    return () => window.clearTimeout(timer);
  }, [search, offline]);

  const options = useMemo<DropdownOption[]>(() => {
    /*
      "" IS A REAL ANSWER AND IT IS THE DEFAULT ONE. The field is optional on the server ("OPTIONAL,
      AND ABSENT MEANS UNCHANGED" — `DesignWorkshopCreate`), because a workshop is opened in a room
      on day one and the admin may genuinely not know yet who will run it. The route reads
      `(payload.designerUserId or "").strip() or None`, so an empty pick is "nobody named" and not
      an account whose id is the empty string. Offering the row explicitly rather than relying on
      the placeholder is what lets an admin UNDO a pick without reloading the form.
    */
    const rows: DropdownOption[] = [{ value: "", label: "Not decided yet" }];
    for (const person of eligible ?? []) {
      rows.push(rowFor(person));
    }
    /*
      ── AND THE PICK THE CURRENT ANSWER NO LONGER CONTAINS, APPENDED LAST ────────────────────────

      THE DEFECT THIS CLOSES. `eligible` is REPLACED by every search, so an admin who searches "kam",
      picks Kamla, then types a second surname leaves this control holding a `value` no row can
      resolve. `SearchableSelect` falls back to the placeholder — the trigger reads "Not decided yet"
      — while `designerUserId` on the page still holds Kamla's id and `submit()` still sends it. The
      same gap opens with no typing at all: a failed refresh clears `eligible`, and the pick vanishes
      from the trigger while remaining the thing that gets sent. That is a form that has quietly
      stopped agreeing with itself, on the one field that decides whose name the report prints.

      A SNAPSHOT OF EVERYONE THIS MOUNT HAS BEEN SHOWN, merged and never replaced — the same device
      Android uses (`seenDesigners` in `WorkshopListScreen.kt`), because the two clients must not
      disagree about whether a pick survives a second search.

      APPENDED LAST rather than merged into place: it is not part of the answer to the term currently
      typed, and threading it back into the server's order would move a row the admin is looking at.
      The server's order is `name` then `id` and is never re-sorted here.
    */
    if (value && !(eligible ?? []).some((person) => person.id === value)) {
      const remembered = seen.get(value);
      rows.push(
        remembered
          ? rowFor(remembered)
          : // Never seen by this mount — only reachable if a caller seeds a value the picker did not
            // hand out. Named as an id rather than dropped, because a silent placeholder is the very
            // failure this block exists to stop.
            { value, label: "The designer already chosen", hint: value }
      );
    }
    return rows;
  }, [eligible, seen, value]);

  const searchTerm = search.trim();

  /**
   * AT MOST ONE LINE, EVER: what the search is doing, or the single sentence that says the list is
   * incomplete, or why there is no list at all. Nothing when the list is whole — a standing note
   * about pagination on every visit is padding, and silence is the common and correct answer.
   */
  const notice = offline
    ? "There is no connection, so the list of designers cannot be read. Start the workshop now and name the designer once this device is back online — nothing is lost by leaving it."
    : featureMissing
      ? "This server does not offer the designer list yet. The workshop can still be started; stage 1 will carry whoever created it."
      : loadError
        ? loadError
        : searching && searchTerm
          ? "Searching…"
          : /*
              THE EMPTY REPOSITORY, SAID HERE AND NOT THROUGH `emptyLabel`.

              `SearchableSelect` draws `emptyLabel` only when it has NO rows to render, and this
              control always has one — "Not decided yet" is pushed before anything the server sent.
              So the empty-repository sentence passed as `emptyLabel` is unreachable text, and
              `eligibleViewerNotice` answers "" for {truncated: false, offered: 0, searched: false}
              because a complete list has nothing to explain. Between them a repository whose
              empanelment roster is empty drew a lone "Not decided yet" with NOTHING said — which is
              indistinguishable from a list that has not loaded, and is exactly the silent-emptiness
              state rule 10 of the frontend contract forbids. Android says it as a live notice line
              for the same reason.

              Asked of `eligible` and not of `options`, since `options` is never empty by construction.
            */
            !searchTerm && !truncated && eligible?.length === 0
            ? "No account on this repository may be named as this workshop's designer yet. A designer has to be empanelled on the roster before a workshop can be opened for them; this one can still be started, and stage 1 will carry whoever creates it."
            : eligibleViewerNotice({
                truncated,
                offered: eligible?.length ?? 0,
                searched: Boolean(searchTerm)
              });

  return (
    <FieldBlock
      label="Designer this workshop is for"
      hint={
        <p className="text-xs leading-5 text-ink-500">
          Their designer profile is copied into stage 1 and stage 3, and they are given access to this workshop in the
          same step. Leave it as <span className="font-medium text-ink-700">Not decided yet</span> if you do not know —
          stage 1 then carries whoever creates the workshop, and a designer can be added later from
          &ldquo;Designers on a workshop&rdquo;.
        </p>
      }
    >
      <div className="grid gap-2">
        {/* The one search box, and it asks the SERVER — the only thing that can see past the
            2000-account ceiling. Capped at the length the endpoint accepts so a long paste narrows
            the list instead of coming back a 422 the admin can do nothing with. */}
        {offline ? null : (
          <SearchInput
            onChange={(next) => setSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX))}
            placeholder="Search designers by name or email"
            value={search}
          />
        )}
        {notice ? (
          <p className="text-xs leading-5 text-ink-500" id={noticeId}>
            {notice}
          </p>
        ) : null}
        <Dropdown
          ariaLabel="Designer this workshop is for"
          // Pointed at the notice only while it is on screen: `aria-describedby` naming an id that
          // is not in the document is worse than naming nothing at all.
          describedBy={notice ? noticeId : undefined}
          disabled={disabled || offline}
          // Kept as a backstop only. It is unreachable today — this control always renders at least
          // the "Not decided yet" row, so `SearchableSelect` never reaches its empty branch — and the
          // empty repository is spoken by the notice above instead, where it can actually be read.
          emptyLabel="No account on this repository may be named as this workshop's designer."
          onChange={onChange}
          options={options}
          placeholder="Not decided yet"
          // OFF, deliberately — see this file's header. The box above is the search and it reaches
          // the whole table; this control's own filter would search only the accounts that fitted
          // under the server's ceiling.
          searchable={false}
          // And therefore this sentence: `searchable={false}` does not switch the RENDER CAP off, so
          // on a real repository the cap notice fires here and its default last clause would tell an
          // admin to type into a filter box this control deliberately does not have.
          capHint="Use the search box above to reach the rest — it asks the repository, so it sees every eligible account."
          value={value}
        />
      </div>
    </FieldBlock>
  );
}
