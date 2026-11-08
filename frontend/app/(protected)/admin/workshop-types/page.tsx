"use client";

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
import { Toggle } from "@/components/ui/Toggle";
import {
  createWorkshopType,
  deleteWorkshopType,
  listWorkshopTypes,
  reorderWorkshopTypes,
  sortWorkshopTypes,
  updateWorkshopType,
  type WorkshopTypeOption
} from "@/lib/workshopTypes";
import { ActionGroup } from "@/components/ui/ActionGroup";

const ROUTING_CHOICES = [
  {
    value: false,
    title: "Ordinary workshop",
    detail: "The Workshop dropdown lists field-documentation workshops."
  },
  {
    value: true,
    title: "Design & prototype workshop",
    detail: "The Workshop dropdown lists 22-stage design workshops."
  }
] as const;

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

  const [types, setTypes] = useState<WorkshopTypeOption[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);

  const load = useCallback(async () => {
    try {
      const rows = await listWorkshopTypes({ includeInactive: true });
      setTypes(sortWorkshopTypes(rows));
      setError(null);
    } catch (err) {
      setError(readableError(err, "Unable to load the workshop types"));
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
      description="Options for “Type of workshop”."
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
          body="Admins only."
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
    const next = moveIndex(rows, index, target);
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
          ? `“${updated.label}” is offered again.`
          : `“${updated.label}” retired. Existing workshops keep it.`
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
      body: "It's removed from every dropdown.",
      note: "Types in use can't be deleted — retire them instead.",
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
        <h2 className="font-display text-base font-bold text-ink-900">How a type decides which workshops are listed</h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-500">
          The type marked <strong>Design &amp; prototype workshop</strong> lists design workshops; others list field
          workshops.
        </p>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-ink-500">
          The <strong>code</strong> is fixed once created.
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
              <Field label="Code (permanent)" required>
                <TextInput
                  value={draft.key}
                  onChange={(event) => setDraft((d) => ({ ...d, key: event.target.value.toUpperCase() }))}
                  maxLength={64}
                  required
                  pattern="[A-Z][A-Z0-9_]*"
                  title="Capitals, digits and underscores, starting with a letter."
                  placeholder="DESIGN_PROTOTYPE_DEVELOPMENT"
                />
              </Field>
            ) : (
              <div className="grid min-w-0 gap-1">
                <span className="field-label">Code</span>
                <p className="text-sm text-ink-700">
                  <code className="rounded bg-surface-50 px-1.5 py-1 text-xs">{draft.key}</code>
                </p>
                <p className="text-xs leading-5 text-ink-500">
                  Fixed once created.
                </p>
              </div>
            )}
          </div>

          <fieldset className="grid gap-2">
            <legend className="field-label">Which workshops this type lists</legend>
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

          <Toggle
            checked={draft.isActive}
            onChange={(next) => setDraft((d) => ({ ...d, isActive: next }))}
            label="Offer this type in the “Type of workshop” dropdown"
            description="Switch off to retire it. Existing workshops keep it."
          />

          <ActionGroup>
            <button type="submit" className="field-button" disabled={busy}>
              {busy ? "Saving…" : editing === "new" ? "Add type" : "Save changes"}
            </button>
            <button type="button" className="field-button-secondary" onClick={cancel} disabled={busy}>
              Cancel
            </button>
          </ActionGroup>
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
            Use the arrows to reorder. Retired types are greyed.
          </p>
        </div>

        {!types ? (
          <div className="p-6 text-sm text-ink-500">Loading…</div>
        ) : rows.length === 0 ? (
          <EmptyState
            title="No types of workshop"
            body="Add one so records can be filed."
          />
        ) : (
          <>
          <ul className="divide-y divide-line-200 lg:hidden" data-workshop-type-cards>
            {rows.map((type, index) => (
              <li key={type.id} className={`grid min-w-0 gap-3 px-4 py-3 ${type.isActive ? "" : "bg-surface-50"}`} data-workshop-type={type.key}>
                <div className="flex min-w-0 items-start justify-between gap-3">
                  <div className="grid min-w-0 gap-1">
                    <span className="font-medium text-ink-900">{type.label}</span>
                    <code className="w-fit max-w-full rounded bg-surface-50 px-1.5 py-0.5 text-xs text-ink-700 [overflow-wrap:anywhere]">{type.key}</code>
                  </div>
                  <div className="flex shrink-0 gap-1.5">
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
                </div>
                <p className="text-sm text-ink-700">
                  {type.routesToDesignWorkshop ? "Design & prototype workshop" : "Ordinary workshop"}
                  {" · "}
                  {type.isActive ? (
                    <span className="text-ink-700">Offered</span>
                  ) : (
                    <span className="inline-flex items-center gap-1 text-ink-500">
                      <Lock className="h-3.5 w-3.5" aria-hidden />
                      Retired
                    </span>
                  )}
                </p>
                <ActionGroup>
                  <button type="button" className={rowAction("edit")} onClick={() => openEdit(type)} disabled={busy}>
                    Update
                  </button>
                  <button type="button" className={rowAction("neutral")} onClick={() => void toggleActive(type)} disabled={busy}>
                    {type.isActive ? "Retire" : "Offer again"}
                  </button>
                  <button type="button" data-danger className={rowAction("danger")} onClick={() => void remove(type)} disabled={busy}>
                    Delete
                  </button>
                </ActionGroup>
              </li>
            ))}
          </ul>
          <div className="relative hidden overflow-x-auto lg:block">
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
                    Code
                  </th>
                  <th scope="col" className="px-4 py-2 font-medium">
                    Workshops listed
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
                      <ActionGroup>
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
                      </ActionGroup>
                    </td>
                    <td className="px-4 py-3 align-top font-medium text-ink-900">{type.label}</td>
                    <td className="px-4 py-3 align-top">
                      <code className="whitespace-nowrap rounded bg-surface-50 px-1.5 py-0.5 text-xs text-ink-700">{type.key}</code>
                    </td>
                    <td className="px-4 py-3 align-top text-ink-700">
                      {type.routesToDesignWorkshop ? (
                        <>
                          Design &amp; prototype workshop
                        </>
                      ) : (
                        <>
                          Ordinary workshop
                        </>
                      )}
                    </td>
                    <td className="px-4 py-3 align-top">
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
                          Update
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
                          data-danger className={rowAction("danger")}
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
          </>
        )}
      </section>
    </>
  );
}
