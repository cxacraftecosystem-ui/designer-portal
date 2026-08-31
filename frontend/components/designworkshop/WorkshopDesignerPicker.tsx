"use client";

import { useEffect, useId, useMemo, useRef, useState } from "react";

import { SearchInput } from "@/components/SearchInput";
import { FieldBlock } from "@/components/tasks/TaskPrimitives";
import { Dropdown, MultiSelectDropdown, type DropdownOption } from "@/components/ui/Dropdown";
import { MAX_NAMED_DESIGNERS, namedDesignerTeam } from "@/lib/designWorkshops";
import {
  ELIGIBLE_VIEWER_SEARCH_MAX,
  eligibleViewerNotice,
  listEligibleDesignWorkshopViewers,
  viewerAdministrationMissing,
  type DwEligibleViewer
} from "@/lib/designWorkshopViewers";
import { roleLabel } from "@/lib/permissions";
import {
  deviceLooksOffline,
  workshopEmptyLabel,
  type WorkshopListState,
  type WorkshopListVoice
} from "@/lib/workshopOptions";

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
 * The server grew `designerUserId` for this, with `assert_every_designer_may_be_named` and
 * `attach_the_named_designers` behind it. **Neither client could send it**, so the repair was
 * unreachable on every real request. This is the web half of reaching it.
 *
 * ── IT IS A MULTI-SELECT, AND THAT IS A SECURITY BOUNDARY RATHER THAN A CONVENIENCE ─────────────
 *
 * A design workshop is visible ONLY to its creator, to admins, and to whoever holds a
 * `DesignWorkshopViewer` row — enforced IN THE QUERY on the list (`visible_to_clause`) and in the
 * loader on the single read, which refuses with a 404 identical to a nonexistent id so the refusal
 * cannot say whether the workshop is there. A DESIGNER cannot create a workshop at all, so
 * `createdById` never matches for them: the workshops a designer can see are exactly the ones they
 * hold a row on, and nothing else. Naming somebody here is therefore not a nicety — it is the whole
 * of how they get in, and the create writes one row per name in the same call.
 *
 * A REAL WORKSHOP HAS MORE THAN ONE DESIGNER. It is a fortnight of work by two designers alongside
 * a master craftsperson and a reviewing officer, and every one of them has to read the same 22
 * stages. With one name on the create, the second designer had to be added afterwards from
 * "Designers on a workshop" — and an admin who forgot left a designer who could not open the
 * workshop their own stage 1 already named. That gap is what this control closes.
 *
 * ── AND YET EXACTLY ONE NAME REACHES THE REPORT ─────────────────────────────────────────────────
 *
 * Several people may OPEN it; one name is ON it. Stage 1 and stage 3 declare a SINGLE designer
 * block — one `designerName`, one `designerProfile`, one signature — and `report_meta` feeds that
 * name into the .docx's `dc:creator`, a single-author field the file format cannot express as a
 * list. So the picker also resolves a LEAD, and says on screen who it is: whose name lands on a
 * ministry document must not be decided by a tick order nobody can see. The rule itself is
 * {@link namedDesignerTeam}, shared with the form's submit so the sentence under the picker and the
 * body on the wire cannot disagree.
 *
 * ── THE SAME ENDPOINT AS THE VIEWERS PANEL, DELIBERATELY ────────────────────────────────────────
 * `GET /design-workshops/eligible-viewers`, not a new eligibility set. The create's own
 * `assert_every_designer_may_be_named` delegates to the same `_assert_every_id_may_be_granted` the
 * viewers PUT uses, so offering an account here that the create would refuse is impossible by
 * construction rather than by agreement. A second endpoint would be a second copy of a rule that
 * spans two rosters — the designer roster is a guest list, the platform allow-list is a cut list —
 * and it would drift within one release.
 *
 * ── THE SEARCH BOX IS THE SERVER'S, AND THE PICKER'S OWN FILTER IS OFF ──────────────────────────
 * The house rule for a server-truncated list (`.claude/skills/field-repo-frontend`, §11.5): the
 * endpoint answers at most 2000 accounts and the cap is reached on a real repository, so a
 * client-side filter box would search only the part of the alphabet that fitted and answer "No
 * matches" for a colleague who is eligible and merely sorts late — absence reading as
 * non-existence. So: one box, above the control, wired to the server, and `searchable={false}` on
 * the picker beneath it. `searchable={false}` does NOT switch the render cap off, which is why
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
 * which of the two a screen happened to read. The lead line below NAMES the designer whose profile
 * the server will seed; it does not send a name anywhere.
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

/** What to call somebody the picker has already served, or the bare id when it has not. */
function nameOf(seen: Map<string, DwEligibleViewer>, id: string): string {
  const person = seen.get(id);
  if (!person) return id;
  return person.name || person.email;
}

export function WorkshopDesignerPicker({
  values,
  onChange,
  lead,
  onLeadChange,
  disabled,
  offline
}: {
  /**
   * The chosen account ids, in the order they were ticked. Empty is "not decided yet" — a real and
   * common answer, and the reason there is no placeholder row inside the panel offering it: an
   * empty selection already says it, and a row that also said it would be two controls for one
   * answer, one of which the reader would have to untick the other to reach.
   */
  values: string[];
  onChange: (userIds: string[]) => void;
  /**
   * The designer whose profile is seeded and whose name reaches the report cover, or "" to let it
   * be derived. Derived means the FIRST TICKED — never the admin who pressed create.
   */
  lead: string;
  onLeadChange: (userId: string) => void;
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
   * here that the server did not offer first, and the only rows it can add are the picks already
   * made. It is also what lets the lead line print a NAME rather than a cuid.
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
    const rows: DropdownOption[] = [];
    const offered = new Set<string>();
    for (const person of eligible ?? []) {
      rows.push(rowFor(person));
      offered.add(person.id);
    }
    /*
      ── AND EVERY PICK THE CURRENT ANSWER NO LONGER CONTAINS, APPENDED LAST ──────────────────────

      THE DEFECT THIS CLOSES. `eligible` is REPLACED by every search, so an admin who searches "kam",
      ticks Kamla, then types a second surname leaves this control holding values no row can
      resolve. `SearchableMultiSelect` counts what it was given, so the trigger would go on reading
      "2 selected" while the panel showed neither of them — and the ids are still what `submit()`
      sends. The same gap opens with no typing at all: a failed refresh clears `eligible`, and every
      tick vanishes from the panel while remaining the thing that gets sent. That is a form that has
      quietly stopped agreeing with itself, on the one field that decides who may open the workshop.

      EVERY SELECTED ID, NOT JUST ONE. The singular version of this control only ever had to rescue
      the single `value`; a multi-select can hold ticks from four different searches at once, and
      rescuing only the newest would be a control that forgets three of them.

      A SNAPSHOT OF EVERYONE THIS MOUNT HAS BEEN SHOWN, merged and never replaced — the same device
      Android uses (`seenDesigners` in `WorkshopListScreen.kt`), because the two clients must not
      disagree about whether a pick survives a second search.

      APPENDED LAST rather than merged into place: they are not part of the answer to the term
      currently typed, and threading them back into the server's order would move rows the admin is
      looking at. The server's order is `name` then `id` and is never re-sorted here. (The panel
      pins ticked rows to the top of what it DRAWS, which is a different mechanism and does not
      reorder this array.)
    */
    for (const id of values) {
      if (!id || offered.has(id)) continue;
      offered.add(id);
      const remembered = seen.get(id);
      rows.push(
        remembered
          ? rowFor(remembered)
          : // Never seen by this mount — only reachable if a caller seeds a value the picker did not
            // hand out. Named as an id rather than dropped, because a silent absence is the very
            // failure this block exists to stop.
            { value: id, label: "A designer already chosen", hint: id }
      );
    }
    return rows;
  }, [eligible, seen, values]);

  const searchTerm = search.trim();

  /**
   * THE LEAD, RESOLVED THE SAME WAY THE WIRE RESOLVES IT.
   *
   * Read through {@link namedDesignerTeam} rather than from `lead` directly, so the sentence on
   * screen is produced by the function the submit uses. An admin who names a lead and then unticks
   * them sees the promotion happen; a picker that kept printing the untucked name would be telling
   * them one thing while the create did another, on the one field that decides whose name a
   * ministry reads.
   */
  const resolved = useMemo(() => namedDesignerTeam({ chosen: values, lead }), [values, lead]);

  /** The ticked designers, as rows for the lead chooser. Names where known, ids where not. */
  const leadOptions = useMemo<DropdownOption[]>(
    () => resolved.team.map((id) => ({ value: id, label: nameOf(seen, id), hint: seen.get(id)?.email })),
    [resolved.team, seen]
  );

  const atCap = values.length >= MAX_NAMED_DESIGNERS;

  /*
    WHICH OF THE FOUR EMPTY STATES THE PANEL IS IN.

    The notice above already names three of them on the PAGE -- offline, an older deployment, a
    failed read -- and this is the same three said in the other place a reader meets them, inside
    the panel. `emptyLabel` was the flat literal "No account on this repository may be named as this
    workshop's designer.", which is a claim about the empanelment roster and was drawn just as
    readily while the first read was still outstanding and after one had failed. Rule 10 does not
    stop at the page: a panel that asserts non-existence is the same defect one layer in.

    Read only for its KIND in the failed and loading arms -- the genuinely-empty sentence stays this
    file's own, because it goes on to name the next move (empanel a designer first) and the shared
    "No designers have been recorded yet" does not. `accessList` keeps its default: this is a
    permissions control, and section 3.3 rules those "disable with a reason, never cache".
  */
  const eligibleList: WorkshopListState<DwEligibleViewer> =
    offline || featureMissing || loadError
      ? { kind: "failed" }
      : eligible === null
        ? { kind: "loading" }
        : { kind: "ok", rows: eligible, total: eligible.length };
  const eligibleVoice: WorkshopListVoice = {
    table: "field",
    noun: "designers",
    scoped: true,
    online: !deviceLooksOffline(),
    // True as written here, and unusually so: the hint below says in as many words that the field
    // may be left empty and stage 1 will carry whoever creates the workshop.
    reassurance: "Nothing you have entered is at risk — the workshop can be started without it."
  };

  /**
   * AT MOST ONE LINE, EVER: what the search is doing, or the single sentence that says the list is
   * incomplete, or why there is no list at all. Nothing when the list is whole — a standing note
   * about pagination on every visit is padding, and silence is the common and correct answer.
   */
  const notice = offline
    ? "There is no connection, so the list of designers cannot be read. Start the workshop now and name its designers once this device is back online — nothing is lost by leaving it."
    : featureMissing
      ? "This server does not offer the designer list yet. The workshop can still be started; stage 1 will carry whoever created it."
      : loadError
        ? loadError
        : searching && searchTerm
          ? "Searching…"
          : /*
              THE EMPTY REPOSITORY, SAID HERE AS WELL AS THROUGH `emptyLabel`.

              `emptyLabel` is only drawn inside the panel, i.e. only to somebody who has already
              opened a control that appears to offer something. This line is on the page. A
              repository whose empanelment roster is empty would otherwise present a picker that
              simply never yields anybody — indistinguishable from a list that has not loaded, and
              exactly the silent-emptiness state rule 10 of the frontend contract forbids. Android
              says it as a live notice line for the same reason.

              Asked of `eligible` and not of `options`, because `options` also carries the ticks
              rescued from earlier searches: with a pick already made, a repository that has since
              emptied would have a non-empty `options` and nothing to say about it.
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
      label="Designers this workshop is for"
      hint={
        <p className="text-xs leading-5 text-ink-500">
          Everybody named here can open this workshop and fill in its stages — a design workshop is
          visible only to the designers on it, and to admins. One of them, named below, is the one whose
          designer profile is copied into stage 1 and stage 3 and whose name the report carries. Leave it
          empty if you do not know yet — stage 1 then carries whoever creates the workshop, and designers
          can be added later from &ldquo;Designers on a workshop&rdquo;.
        </p>
      }
    >
      <div className="grid gap-2">
        {/*
          The one search box, and it asks the SERVER — the only thing that can see past the
          2000-account ceiling. Capped at the length the endpoint accepts so a long paste narrows
          the list instead of coming back a 422 the admin can do nothing with.

          `onInput` IS STOPPED HERE, AND THAT IS NOT TIDINESS. This control sits inside the create
          form, which arms its unsaved-changes prompt from the form's own `onInput`. A search box is
          a real text input, so without the firewall merely TYPING to look somebody up would mark the
          form dirty — and an admin who searched, ticked nothing and pressed Cancel could not leave
          the page. It is the same firewall `components/forms/WorkshopSelect` puts around its
          ComboBox, for the same reason.
        */}
        {offline ? null : (
          <div onInput={(event) => event.stopPropagation()}>
            <SearchInput
              onChange={(next) => setSearch(next.slice(0, ELIGIBLE_VIEWER_SEARCH_MAX))}
              placeholder="Search designers by name or email"
              value={search}
            />
          </div>
        )}
        {notice ? (
          <p className="text-xs leading-5 text-ink-500" id={noticeId}>
            {notice}
          </p>
        ) : null}
        <MultiSelectDropdown
          ariaLabel="Designers this workshop is for"
          confirmLabel="Done"
          // Pointed at the notice only while it is on screen: `aria-describedby` naming an id that
          // is not in the document is worse than naming nothing at all.
          describedBy={notice ? noticeId : undefined}
          disabled={disabled || offline}
          emptyLabel={
            eligibleList.kind === "ok"
              ? "No account on this repository may be named as this workshop's designer."
              : workshopEmptyLabel(eligibleList, eligibleVoice)
          }
          onChange={(next) => {
            /*
              TRIMMED HERE, AND SAID ON SCREEN — never silently.

              The server caps a create at `MAX_NAMED_DESIGNERS` and refuses a longer list outright,
              rather than keeping the first hundred; a cap only the server knows about is a cap an
              admin meets as a 422 after building a selection by hand. "Select all matching" can
              cross it in one click, so the trim has to live on the change rather than on the tick.
              The sentence below fires whenever the cap is reached, on both routes in.
            */
            onChange(next.slice(0, MAX_NAMED_DESIGNERS));
          }}
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
          values={values}
        />
        {atCap ? (
          // Tinted rather than bare amber text: `amber-800` is a LITERAL that does not invert, so on
          // its own over the page canvas it is unreadable in dark mode. §3.5 — inside a tinted card,
          // `amber-100` with `amber-800`, and the pair travels together.
          <p className="rounded-md border border-amber-500/30 bg-amber-100 px-3 py-2 text-xs leading-5 text-amber-800">
            {MAX_NAMED_DESIGNERS} designers is the most one workshop can be opened for, so nothing further is
            kept. Anybody else can be added afterwards from &ldquo;Designers on a workshop&rdquo;.
          </p>
        ) : null}
        {/*
          WHOSE NAME IS ON IT, PRINTED — the half of this control that is not a permission.

          A multi-select renders ticks in the SERVER'S name order, so "the first one you ticked" is
          invisible to the person ticking, and it is what the server promotes to lead when no lead is
          sent. Leaving it implicit would let a tick order nobody can see decide which designer's
          profile is copied into stage 1 and whose name reaches the .docx's `dc:creator`.

          The chooser appears only from two designers upward: with one, there is nothing to choose
          and a dropdown of one row is a question with a single answer.
        */}
        {resolved.lead ? (
          <div className="grid gap-1 rounded-md border border-line-200 bg-surface-50 px-3 py-2">
            <p className="text-xs leading-5 text-ink-700">
              Stage 1, stage 3 and the report will carry{" "}
              <span className="font-medium text-ink-900">{nameOf(seen, resolved.lead)}</span> — their designer
              profile is the one copied in. Everyone ticked can open the workshop.
            </p>
            {resolved.team.length > 1 ? (
              <Dropdown
                ariaLabel="The designer whose name the report carries"
                disabled={disabled || offline}
                onChange={onLeadChange}
                options={leadOptions}
                // The ticked set is at most `MAX_NAMED_DESIGNERS` rows and every one of them is
                // already on this screen, so the control's own filter box is the right one here —
                // unlike the picker above, nothing has been truncated by a server.
                searchable
                value={resolved.lead}
              />
            ) : null}
          </div>
        ) : null}
      </div>
    </FieldBlock>
  );
}
