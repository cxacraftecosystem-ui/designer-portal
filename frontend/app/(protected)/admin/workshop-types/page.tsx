"use client";

/**
 * /admin/workshop-types — the list behind the "Type of workshop" dropdown on every record form.
 *
 * ── WHAT AN ADMINISTRATOR IS ACTUALLY EDITING HERE ───────────────────────────────────────────────
 *
 * Every record form (artisan, product, process, tool, craft, questionnaire) carries TWO workshop
 * controls and never three:
 *
 *     "Type of workshop"  → this list
 *     "Workshop"          → the workshops OF THAT TYPE, most recent first
 *
 * The rows on this screen are the members of the first dropdown, and each row's **Where a workshop
 * saves** column is the rule the second one follows. A type marked "Design & prototype workshop"
 * fills the workshop dropdown from the 22-stage `DesignWorkshop` table and saves the chosen workshop
 * to the record's `designWorkshopId`; every other type fills it from the ordinary `Workshop` table
 * and saves to `workshopId`. That is the whole of the routing rule, it lives on the type rather than
 * on the record, and it is why this screen exists at all.
 *
 * **THE TYPE IS NOT SAVED ON THE RECORD.** The workshop a researcher picks already knows its own
 * type, so storing the type beside it would be a second copy that can disagree with the first — and
 * nothing would ever read them together to notice. Said here as well as in `lib/workshopTypes.ts`
 * because an administrator reading this screen reasonably assumes they are editing a field on a
 * record, and they are not.
 *
 * ── TWO GATES SIT ABOVE THIS PAGE, AND NEITHER OF THEM LIVES HERE ────────────────────────────────
 *
 * `/admin/workshop-types` is nested under `/admin`, so `ROUTE_GUARDS`' `/admin` row (`isAdmin`,
 * mirroring `require_admin`) already refuses everyone below admin above this component, and
 * `ADMIN_CHROME_ROUTES`' `/admin` rule already makes the whole path admin chrome — an admin browsing
 * with admin view off gets `AppShell`'s "hidden while admin view is off" panel and this component
 * never mounts. That is the same arrangement `/admin/analytics`, `/admin/designers` and
 * `/admin/access` sit in, and NO new rule is needed for this one: those three carry their own
 * `ROUTE_GUARDS` rows only because their server gates are DIFFERENT predicates
 * (`require_designer_roster_manager`, `require_access_manager`) that could one day move away from
 * `require_admin` and silently disagree. This route's server gate IS `require_admin`, the identical
 * predicate the `/admin` row already mirrors, so a second row would be a second copy of one rule.
 *
 * The `permitted` check below is the third mirror of the same thing, and it is not redundant either:
 * a client-side route guard that only hides a link is not a guard, and this component must refuse on
 * its own the way every other admin surface does. The boundary is none of these three — it is
 * `Depends(require_admin)` on the write routes in `backend/app/api/routes/workshop_types.py`. A
 * designer who types this URL is refused by the server, not merely by a hidden button.
 *
 * ── THE TWO RULES THE SCREEN IS SHAPED AROUND ────────────────────────────────────────────────────
 *
 * **A KEY IS PERMANENT AND A LABEL IS NOT.** The key is a token written into
 * `DesignWorkshop.workshopKind`, into `AnnualPlanEntry.workshopKind`, into stage documents and into
 * every dataset export ever taken — none of which this screen can reach. So the key box is offered
 * ONCE, when a type is created, and the edit form does not carry it at all: the API refuses it (422)
 * and the form does not draw it. Editing a label is the common case, it is a single-column write,
 * and it is safe by construction rather than by care.
 *
 * **DELETING IS REFUSED WHEN ANYTHING IS FILED UNDER THE TYPE.** There is no foreign key onto the
 * type table, so Postgres cannot make that refusal for us; the server counts the workshops and
 * answers 409 with a finished sentence naming how many and telling the administrator to deactivate
 * instead. That sentence is rendered VERBATIM — it is the only thing on screen that says what to do
 * next, and replacing it with "Unable to delete" would leave somebody trying again tomorrow.
 * Deactivating is the remedy and it is one click away in the same row: the type leaves every
 * dropdown and every workshop already filed under it keeps its type and its label.
 */

import { useCallback, useEffect, useState } from "react";
import { ArrowDown, ArrowUp, Lock, Tags } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { RestrictedPanel } from "@/components/settings/RestrictedPanel";
import { RowActions, rowAction } from "@/components/RowActions";
import { moveIndex } from "@/components/hooks/useDragReorder";
import { readableError } from "@/components/review/reviewErrors";
import { useAuth } from "@/components/AuthProvider";
import { useConfirm } from "@/components/dialogs/ConfirmDialog";
import { isAdmin } from "@/lib/permissions";
import {
  createWorkshopType,
  deleteWorkshopType,
  listWorkshopTypes,
  reorderWorkshopTypes,
  sortWorkshopTypes,
  updateWorkshopType,
  type WorkshopTypeOption
} from "@/lib/workshopTypes";

/**
 * The two answers the "Where a workshop saves" control offers, as words rather than as a checkbox.
 *
 * A TICKBOX LABELLED "routesToDesignWorkshop" WOULD BE THE WRONG CONTROL, and not because the name
 * is technical. A tickbox has one visible state and one invisible one — unticked reads as "not
 * decided" — and the consequence of getting this wrong is that a researcher's record is attached to
 * the wrong table's workshop. Two named radio options make both answers legible, and both of them
 * say which TABLE and which COLUMN, because that is what an administrator will be asked about when
 * somebody's record turns up in the wrong place.
 */
const ROUTING_CHOICES = [
  {
    value: false,
    title: "Ordinary workshop",
    detail: "The Workshop dropdown lists field-documentation workshops, and the record saves to workshopId."
  },
  {
    value: true,
    title: "Design & prototype workshop",
    detail:
      "The Workshop dropdown lists 22-stage design workshops, and the record saves to designWorkshopId."
  }
] as const;

/**
 * What the create form and the edit form both hold. `key` is only ever SENT by the create form; the
 * edit form carries it to print and never posts it.
 *
 * CONTROLLED REACT STATE, AND THE NEIGHBOURING ADMIN SCREEN DOES THE OPPOSITE — worth naming,
 * because `/admin/designers` reads its form with `new FormData(event.currentTarget)` and every
 * RECORD form in this app is uncontrolled by rule (the unsaved-changes guard, the zero-size mirror
 * inputs and the Enter-walker all hang off that). None of those three exists here: there is no
 * leave guard on this screen, no themed dropdown to mirror, and four fields.
 *
 * What decides it is the two fields that are not text. "Where a workshop saves" is a radio pair and
 * "offer this type" is a checkbox, and BOTH have to be seeded from the row being edited — which
 * uncontrolled inputs can only do through `defaultChecked` plus a `key` remount, i.e. a second
 * mechanism that has to be remembered every time a field is added. Controlled state also removes
 * the `event.currentTarget` hazard outright: React nulls it across an await, which is the first
 * rule §12.1 states and the one this form now cannot break.
 */
type Draft = {
  key: string;
  label: string;
  isActive: boolean;
  routesToDesignWorkshop: boolean;
};

const EMPTY_DRAFT: Draft = { key: "", label: "", isActive: true, routesToDesignWorkshop: false };

export default function WorkshopTypesPage() {
  const { user, loading: authLoading } = useAuth();
  const permitted = isAdmin(user);
  const confirm = useConfirm();

  /**
   * `null` = still loading, `[]` = loaded and empty. Two different sentences on screen, and they are
   * not interchangeable: "no workshop types" during a fetch would tell an administrator the seed had
   * failed, on the one screen whose whole job is to show them the seed.
   */
  const [types, setTypes] = useState<WorkshopTypeOption[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  /** The id being edited, or `"new"` while the create form is open, or null. */
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);

  const load = useCallback(async () => {
    try {
      // `includeInactive` — this is the screen that MANAGES the list, so it has to show the retired
      // rows. Every picker asks without it, and gets only what it may offer.
      const rows = await listWorkshopTypes({ includeInactive: true });
      setTypes(sortWorkshopTypes(rows));
      setError(null);
    } catch (err) {
      setError(readableError(err, "Unable to load the workshop types"));
      // The list is NOT emptied on a failed refresh: what is on screen is still the last thing the
      // server said, and replacing it with "no types" would turn a network blip into a claim about
      // the repository. Only the first load has nothing to keep.
      setTypes((current) => current ?? []);
    }
  }, []);

  useEffect(() => {
    if (authLoading || !permitted) return;
    void load();
  }, [authLoading, permitted, load]);

  const header = (
    <PageHeader
      title="Types of workshop"
      description="The first dropdown on every record form — and, for each type, which table its workshops come from and where a record saves the one it is given."
      icon={<Tags className="h-5 w-5" aria-hidden />}
    />
  );

  if (authLoading) {
    return (
      <>
        {header}
        <section className="panel p-6 text-sm text-ink-500">Checking access…</section>
      </>
    );
  }

  if (!permitted) {
    return (
      <>
        {header}
        <RestrictedPanel
          title="Admin access required"
          body="The types of workshop are a shared vocabulary — renaming one renames it under everybody — so admins and the master admin manage them. The list itself is readable by every signed-in account, because every record form draws it."
        />
      </>
    );
  }

  const rows = types ?? [];

  const openCreate = () => {
    setEditing("new");
    setDraft(EMPTY_DRAFT);
    setError(null);
    setNotice(null);
  };

  const openEdit = (type: WorkshopTypeOption) => {
    setEditing(type.id);
    // The key is carried into the draft so the edit form can PRINT it; it is never sent back. See
    // the form below, which renders it as text rather than as a box.
    setDraft({
      key: type.key,
      label: type.label,
      isActive: type.isActive,
      routesToDesignWorkshop: type.routesToDesignWorkshop
    });
    setError(null);
    setNotice(null);
  };

  const cancel = () => {
    setEditing(null);
    setDraft(EMPTY_DRAFT);
  };

  const save = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!editing || busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      if (editing === "new") {
        const created = await createWorkshopType({
          key: draft.key.trim().toUpperCase(),
          label: draft.label.trim(),
          isActive: draft.isActive,
          routesToDesignWorkshop: draft.routesToDesignWorkshop
        });
        setNotice(`Added “${created.label}”.`);
      } else {
        // NO `key` IN THIS BODY, EVER. The server would answer 422 (the field is not on the update
        // schema and extras are forbidden), which is the guarantee; leaving it out here is what
        // makes the guarantee invisible to the administrator, who only ever wanted to fix a label.
        const updated = await updateWorkshopType(editing, {
          label: draft.label.trim(),
          isActive: draft.isActive,
          routesToDesignWorkshop: draft.routesToDesignWorkshop
        });
        setNotice(`Saved “${updated.label}”.`);
      }
      cancel();
      await load();
    } catch (err) {
      setError(readableError(err, "Unable to save that workshop type"));
    } finally {
      setBusy(false);
    }
  };

  const move = async (index: number, delta: number) => {
    if (busy) return;
    const target = index + delta;
    if (target < 0 || target >= rows.length) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    // `moveIndex` rather than a hand-rolled splice: it is the same pure function the drag reorder and
    // the arrow buttons on `CustomSectionsEditor` and `EntityForm` commit through, so "move up" means
    // one thing in this repository rather than four.
    const next = moveIndex(rows, index, target);
    // Painted before the request answers, and reverted by `load()` if the server refuses. The whole
    // order is one request, so there is no half-applied state to reconcile.
    setTypes(next);
    try {
      const saved = await reorderWorkshopTypes(next.map((type) => type.id));
      setTypes(sortWorkshopTypes(saved));
    } catch (err) {
      setError(readableError(err, "Unable to reorder the workshop types"));
      await load();
    } finally {
      setBusy(false);
    }
  };

  const toggleActive = async (type: WorkshopTypeOption) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const updated = await updateWorkshopType(type.id, { isActive: !type.isActive });
      setNotice(
        updated.isActive
          ? `“${updated.label}” is offered again. Every workshop filed under it is unchanged.`
          : `“${updated.label}” is retired — it has left every “Type of workshop” dropdown. Every workshop filed under it keeps its type and its label.`
      );
      await load();
    } catch (err) {
      setError(readableError(err, "Unable to change that workshop type"));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (type: WorkshopTypeOption) => {
    if (busy) return;
    const ok = await confirm({
      title: `Delete “${type.label}”?`,
      body: "It leaves every “Type of workshop” dropdown and is gone from this list.",
      // The note is where the refusal is announced BEFORE it happens, so an administrator with
      // twelve workshops under this type learns the rule from the dialog rather than from a red
      // banner after pressing the red button.
      note: "A type with workshops filed under it cannot be deleted — the workshops would be left holding a type nothing can resolve. If that is the case here, this will be refused and will say how many are in the way; retire the type instead and it leaves the dropdowns with nothing else touched.",
      confirmLabel: "Delete",
      tone: "danger"
    });
    if (!ok) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await deleteWorkshopType(type.id);
      setNotice(`Deleted “${type.label}”.`);
      await load();
    } catch (err) {
      // VERBATIM. The server's 409 is a finished sentence naming how many workshops are in the way
      // and naming the remedy; a generic fallback here would throw away the only useful half of it.
      setError(readableError(err, "Unable to delete that workshop type"));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      {header}

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div>
      ) : null}
      {notice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-surface-50 px-3 py-2 text-sm text-ink-700">
          {notice}
        </div>
      ) : null}

      <section className="panel mb-5 p-4">
        <h2 className="font-display text-base font-bold text-ink-900">How a type decides where a record saves</h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-500">
          A record form asks for a type first, then for a workshop of that type. Exactly one of these
          types is normally marked <strong>Design &amp; prototype workshop</strong>: its workshops come
          from the 22-stage design workshop table, and a record filed under it saves to{" "}
          <code className="rounded bg-surface-50 px-1 py-0.5 text-xs">designWorkshopId</code>. Every
          other type lists ordinary field-documentation workshops and saves to{" "}
          <code className="rounded bg-surface-50 px-1 py-0.5 text-xs">workshopId</code>. The type
          itself is not stored on the record — the workshop it points at already knows its own type.
        </p>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-500">
          A type&rsquo;s <strong>key</strong> is permanent. It is the token stored on every workshop
          already filed under it and in every export ever taken, so it is chosen once and only the
          label changes afterwards.
        </p>
      </section>

      {editing ? (
        <form onSubmit={save} className="panel mb-5 grid gap-4 p-4">
          <h2 className="font-display text-base font-bold text-ink-900">
            {editing === "new" ? "New type of workshop" : `Edit “${draft.key}”`}
          </h2>

          <div className="grid gap-3 md:grid-cols-2">
            <Field label="Label" required>
              <TextInput
                value={draft.label}
                onChange={(event) => setDraft((d) => ({ ...d, label: event.target.value }))}
                maxLength={120}
                required
                placeholder="Design & Prototype Development"
              />
            </Field>

            {editing === "new" ? (
              <Field label="Key (permanent)" required>
                <TextInput
                  value={draft.key}
                  onChange={(event) => setDraft((d) => ({ ...d, key: event.target.value.toUpperCase() }))}
                  maxLength={64}
                  required
                  // Mirrors the server's `_KEY_PATTERN` exactly. The browser's own refusal arrives
                  // before the request does, and `title` is what it prints — so the two refusals say
                  // the same thing rather than one of them being a regular expression.
                  pattern="[A-Z][A-Z0-9_]*"
                  title="Capital letters, digits and underscores, starting with a letter — for example DESIGN_PROTOTYPE_DEVELOPMENT."
                  placeholder="DESIGN_PROTOTYPE_DEVELOPMENT"
                />
              </Field>
            ) : (
              <div className="grid min-w-0 gap-1">
                <span className="field-label">Key</span>
                <p className="text-sm text-ink-700">
                  <code className="rounded bg-surface-50 px-1.5 py-1 text-xs">{draft.key}</code>
                </p>
                <p className="text-xs leading-5 text-ink-500">
                  Permanent. Workshops already filed under this type store this token, so it cannot be
                  changed here. To use a different one, add a new type and retire this one — both keys
                  stay resolvable, which is what the existing workshops need.
                </p>
              </div>
            )}
          </div>

          <fieldset className="grid gap-2">
            <legend className="field-label">Where a workshop chosen under this type saves</legend>
            {ROUTING_CHOICES.map((choice) => (
              <label
                key={String(choice.value)}
                className="flex cursor-pointer items-start gap-3 rounded-md border border-line-200 bg-card p-3"
              >
                <input
                  type="radio"
                  name="routesToDesignWorkshop"
                  className="mt-1"
                  checked={draft.routesToDesignWorkshop === choice.value}
                  onChange={() => setDraft((d) => ({ ...d, routesToDesignWorkshop: choice.value }))}
                />
                <span className="min-w-0">
                  <span className="block text-sm font-semibold text-ink-900">{choice.title}</span>
                  <span className="block text-xs leading-5 text-ink-500">{choice.detail}</span>
                </span>
              </label>
            ))}
          </fieldset>

          <label className="flex items-start gap-3 text-sm text-ink-700">
            <input
              type="checkbox"
              className="mt-1"
              checked={draft.isActive}
              onChange={(event) => setDraft((d) => ({ ...d, isActive: event.target.checked }))}
            />
            <span>
              Offer this type in the &ldquo;Type of workshop&rdquo; dropdown.
              <span className="block text-xs leading-5 text-ink-500">
                Unticking retires it. It leaves every dropdown and every workshop already filed under
                it keeps its type and its label.
              </span>
            </span>
          </label>

          <div className="flex flex-wrap gap-2">
            <button type="submit" className="field-button" disabled={busy}>
              {busy ? "Saving…" : editing === "new" ? "Add type" : "Save changes"}
            </button>
            <button type="button" className="field-button-secondary" onClick={cancel} disabled={busy}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className="mb-5">
          <button type="button" className="field-button" onClick={openCreate}>
            New type of workshop
          </button>
        </div>
      )}

      <section className="panel overflow-hidden">
        <div className="border-b border-line-200 px-4 py-3">
          <h2 className="font-display font-bold text-ink-900">The list, in the order a form draws it</h2>
          <p className="text-sm text-ink-500">
            Use the arrows to reorder. Retired types stay here, greyed, so an administrator can see
            what has been taken out of the dropdowns and put it back.
          </p>
        </div>

        {!types ? (
          <div className="p-6 text-sm text-ink-500">Loading…</div>
        ) : rows.length === 0 ? (
          <EmptyState
            title="No types of workshop"
            body="This list is seeded with six types when the database migration runs, so an empty list means the migration has not been applied to this server. Add one here to unblock the record forms in the meantime."
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-line-200 bg-surface-50 text-xs uppercase tracking-wide text-ink-500">
                <tr>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Order
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Label
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Key
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Where a workshop saves
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    In the dropdown
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium text-right">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((type, index) => (
                  <tr
                    key={type.id}
                    className={`border-b border-line-200 last:border-0 ${type.isActive ? "" : "bg-surface-50"}`}
                  >
                    <td className="px-4 py-3 align-top">
                      <div className="flex gap-1">
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => void move(index, -1)}
                          disabled={busy || index === 0}
                          aria-label={`Move ${type.label} up`}
                        >
                          <ArrowUp className="h-3.5 w-3.5" aria-hidden />
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => void move(index, 1)}
                          disabled={busy || index === rows.length - 1}
                          aria-label={`Move ${type.label} down`}
                        >
                          <ArrowDown className="h-3.5 w-3.5" aria-hidden />
                        </button>
                      </div>
                    </td>
                    <td className="px-4 py-3 align-top font-medium text-ink-900">{type.label}</td>
                    <td className="px-4 py-3 align-top">
                      <code className="rounded bg-surface-50 px-1.5 py-0.5 text-xs text-ink-700">{type.key}</code>
                    </td>
                    <td className="px-4 py-3 align-top text-ink-700">
                      {/*
                        The column prints the COLUMN NAME as well as the words, because when a record
                        turns up attached to the wrong kind of workshop this is the cell somebody will
                        be asked to read out.
                      */}
                      {type.routesToDesignWorkshop ? (
                        <>
                          Design &amp; prototype workshop
                          <span className="block text-xs text-ink-500">designWorkshopId</span>
                        </>
                      ) : (
                        <>
                          Ordinary workshop
                          <span className="block text-xs text-ink-500">workshopId</span>
                        </>
                      )}
                    </td>
                    <td className="px-4 py-3 align-top">
                      {/*
                        A word and an icon, never a colour alone — the same rule every status tone in
                        this app follows, so the judgement survives greyscale and colour-blindness.
                      */}
                      {type.isActive ? (
                        <span className="inline-flex items-center gap-1 text-ink-700">Offered</span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-ink-500">
                          <Lock className="h-3.5 w-3.5" aria-hidden />
                          Retired
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 align-top">
                      <RowActions>
                        <button
                          type="button"
                          className={rowAction("edit")}
                          onClick={() => openEdit(type)}
                          disabled={busy}
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          className={rowAction("neutral")}
                          onClick={() => void toggleActive(type)}
                          disabled={busy}
                        >
                          {type.isActive ? "Retire" : "Offer again"}
                        </button>
                        <button
                          type="button"
                          className={rowAction("danger")}
                          onClick={() => void remove(type)}
                          disabled={busy}
                        >
                          Delete
                        </button>
                      </RowActions>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}
