"use client";

import { Toggle } from "@/components/ui/Toggle";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Download, FileSignature, Upload } from "lucide-react";

import { OpenableCard } from "@/components/data/OpenableRow";
import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { refreshAwaitingSanctionCount } from "@/components/hooks/useAwaitingSanctionCount";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { RecordDetailsDialog } from "@/components/records/RecordDetailsDialog";
import { SearchInput } from "@/components/SearchInput";
import { describeApiDetail } from "@/lib/api";
import { fetchNotificationPreferences } from "@/lib/notifications";
import { namedDesignerTeam } from "@/lib/designWorkshops";
import { CalendarField } from "@/components/ui/CalendarField";
import {
  confirmSanctionImport,
  deleteSanctionOrder,
  downloadSanctionProForma,
  formatLinkExpiry,
  formatSanctionAmount,
  issueSanctionCredentialLink,
  linkEmailWords,
  listSanctionDesigners,
  listSanctionOrders,
  readinessSentence,
  recordSanctionOrder,
  remindSanctionDesigners,
  reminderSentence,
  revokeSanctionCredentialLink,
  sanctionMessageFor,
  SANCTION_SEED_FAILED_NOTE,
  type SanctionImportPreview,
  type SanctionImportReport as ImportReport,
  type SanctionOrder,
  type SanctionOrderCredentialLink,
  type SanctionOrderPage,
  type PlannedWorkshopCandidate
} from "@/lib/sanctionOrders";

import { SanctionImportReport } from "./SanctionImportReport";
import { SanctionImportReview } from "./SanctionImportReview";
import { UploadSanctionDialog } from "./UploadSanctionDialog";
import { PlannedWorkshopPicker } from "./PlannedWorkshopPicker";
import { ActionGroup } from "@/components/ui/ActionGroup";
import { useAdminView } from "@/components/AdminViewProvider";
import { deleteConfirm, useConfirm } from "@/components/dialogs/ConfirmDialog";

const PAGE_SIZE = 25;

type IssuedLink = {
  order: SanctionOrder;
  link: SanctionOrderCredentialLink & { link: string };
  designerName: string;
  signInEmail: string;
};

function copyable(link: SanctionOrderCredentialLink | null | undefined): (SanctionOrderCredentialLink & { link: string }) | null {
  return link && typeof link.link === "string" && link.link ? (link as SanctionOrderCredentialLink & { link: string }) : null;
}

function reminderMoment(iso: string): string {
  const moment = new Date(iso);
  if (Number.isNaN(moment.getTime())) return "";
  const time = moment.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit" });
  const today = new Date().toDateString() === moment.toDateString();
  return today ? time : `${moment.toLocaleDateString("en-IN", { day: "numeric", month: "short" })}, ${time}`;
}

function reissuable(order: SanctionOrder): {
  designerUserId: string;
  designerName: string;
  signInEmail: string;
}[] {
  if (order.designers.length > 0) {
    return order.designers
      .filter((designer) => designer.accountCreated)
      .map((designer) => ({
        designerUserId: designer.designerUserId,
        designerName: designer.designerName,
        signInEmail: designer.signInEmail || designer.designerEmail
      }));
  }
  return order.accountCreated
    ? [{ designerUserId: order.designerUserId, designerName: order.designerName, signInEmail: order.designerEmail }]
    : [];
}

function problemOf(error: unknown, fallback: string): string {
  const detail = (error as { payload?: { detail?: unknown } } | null)?.payload?.detail;
  if (detail !== undefined) return describeApiDetail(detail, fallback);
  return error instanceof Error && error.message ? error.message : fallback;
}

export default function SanctionOrdersPage() {
  const [data, setData] = useState<SanctionOrderPage | null>(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState("");
  const [mine, setMine] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [issuedLinks, setIssuedLinks] = useState<IssuedLink[]>([]);
  const [copied, setCopied] = useState<string | null>(null);
  const [linkBusy, setLinkBusy] = useState(false);
  const [viewing, setViewing] = useState<SanctionOrder | null>(null);
  const { canDeleteRecords } = useAdminView();
  const confirm = useConfirm();

  async function deleteOrder(order: SanctionOrder) {
    setViewing(null);
    const ok = await confirm(deleteConfirm(`Delete sanction order ${order.sanctionOrderNo}?`, undefined, "This cannot be undone."));
    if (!ok) return;
    setError(null);
    try {
      await deleteSanctionOrder(order.id);
      void refresh();
      void refreshAwaitingSanctionCount();
    } catch (err) {
      setError(problemOf(err, "Unable to delete the sanction order"));
    }
  }
  const [linkNotice, setLinkNotice] = useState<string | null>(null);
  const [mailOn, setMailOn] = useState(false);
  const formRef = useRef<HTMLFormElement | null>(null);

  useEffect(() => {
    let live = true;
    void fetchNotificationPreferences().then((answer) => {
      if (live) setMailOn(answer.available);
    });
    return () => {
      live = false;
    };
  }, []);

  const [chosen, setChosen] = useState<string[]>([]);
  const [lead, setLead] = useState("");
  const [seen, setSeen] = useState<Record<string, { name: string; email: string }>>({});
  const [planned, setPlanned] = useState<PlannedWorkshopCandidate | null>(null);

  const [uploadOpen, setUploadOpen] = useState(false);
  const [preview, setPreview] = useState<SanctionImportPreview | null>(null);
  const [importReport, setImportReport] = useState<ImportReport | null>(null);
  const [importBusy, setImportBusy] = useState(false);

  const fetchEligible = useCallback(async (term: string) => {
    const answer = await listSanctionDesigners(term);
    setSeen((current) => {
      const next = { ...current };
      for (const person of answer.users) {
        next[person.id] = { name: person.name || person.email, email: person.email };
      }
      return next;
    });
    return answer;
  }, []);

  const team = useMemo(() => namedDesignerTeam({ chosen, lead }), [chosen, lead]);

  const load = useRef(0);

  const refresh = useCallback(async () => {
    const generation = ++load.current;
    try {
      const answer = await listSanctionOrders({
        page,
        pageSize: PAGE_SIZE,
        search: search.trim() || undefined,
        mine: mine || undefined
      });
      if (generation !== load.current) return;
      setData(answer);
    } catch (err) {
      if (generation !== load.current) return;
      setError(problemOf(err, "Unable to load the sanction register"));
      setData((current) => current ?? { items: [], total: 0, page: 1, pageSize: PAGE_SIZE, pages: 0 });
    }
  }, [page, search, mine]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError(null);
    setLinkNotice(null);
    try {
      const picked = team.team
        .map((id) => seen[id])
        .filter((person): person is { name: string; email: string } => Boolean(person?.email));
      const typedName = String(form.get("designerName") ?? "").trim();
      const typedEmail = String(form.get("designerEmail") ?? "").trim();
      if (typedName && !typedEmail) {
        setError(
          `Add a Gmail ID for “${typedName}”, or clear the name.`
        );
        setSaving(false);
        return;
      }
      const everybody = [...picked, ...(typedEmail ? [{ name: typedName, email: typedEmail }] : [])];
      if (everybody.length === 0) {
        setError(
          "Choose a designer, or enter their name and Gmail."
        );
        setSaving(false);
        return;
      }
      const [first, ...rest] = everybody;
      const created = await recordSanctionOrder({
        sanctionOrderNo: String(form.get("sanctionOrderNo") ?? "").trim(),
        sanctionOrderDate: String(form.get("sanctionOrderDate") ?? ""),
        sanctionAmount: String(form.get("sanctionAmount") ?? "").trim(),
        designerName: first!.name || first!.email,
        designerEmail: first!.email,
        coDesigners: rest.map((person) => ({ name: person.name || person.email, email: person.email })),
        notes: String(form.get("notes") ?? "").trim() || null,
        ...(planned ? { annualPlanEntryId: planned.id } : {})
      });
      formRef.current?.reset();
      setChosen([]);
      setLead("");
      setPlanned(null);
      const links = created.credentialLinks ?? [];
      const issuedNow = links.flatMap((entry) => {
        const link = copyable(entry.link);
        return link
          ? [{ order: created.sanctionOrder, link, designerName: entry.designerName, signInEmail: entry.signInEmail }]
          : [];
      });
      const emailed = links.filter((entry) => entry.link?.deliveredBy === "EMAIL");
      if (issuedNow.length > 0) {
        setIssuedLinks(issuedNow);
        setCopied(null);
      }
      const problems = links.map((entry) => entry.problem).filter(Boolean) as string[];
      const seedNote = created.reportCopySeeded === false ? `${SANCTION_SEED_FAILED_NOTE} ` : "";
      if (problems.length > 0) {
        setLinkNotice(seedNote + problems.join(" "));
      } else if (emailed.length > 0) {
        setLinkNotice(`${seedNote}Sign-in link e-mailed to ${emailed.map((entry) => entry.signInEmail).join(", ")}.`);
      } else if (issuedNow.length === 0) {
        const names = created.sanctionOrder.designers.map((designer) => designer.designerName);
        setLinkNotice(
          `${seedNote}${names.join(", ") || "That designer"} already ${names.length === 1 ? "has an account" : "have accounts"}; the workshop is on their list.`
        );
      } else if (seedNote) {
        setLinkNotice(seedNote.trim());
      }
      setPage(1);
      await refresh();
      await refreshAwaitingSanctionCount();
    } catch (err) {
      setError(problemOf(err, "Unable to record the sanction order"));
    } finally {
      setSaving(false);
    }
  }

  async function copy(text: string, which: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(which);
    } catch {
      setCopied(null);
    }
  }

  const items = data?.items ?? null;

  return (
    <>
      <PageHeader
        title="Sanction orders"
        description="Recording an order opens its workshop and invites its designers."
        icon={<FileSignature className="h-5 w-5" aria-hidden />}
        actions={
          <>
            <button
              type="button"
              className="field-button-secondary"
              onClick={async () => {
                setError(null);
                try {
                  await downloadSanctionProForma();
                } catch (err) {
                  setError(problemOf(err, "Unable to download the pro-forma"));
                }
              }}
            >
              <Download className="h-4 w-4" aria-hidden />
              Pro-forma
            </button>
            <button
              type="button"
              className="field-button-secondary"
              onClick={() => setUploadOpen(true)}
            >
              <Upload className="h-4 w-4" aria-hidden />
              Upload a sheet
            </button>
          </>
        }
      />

      {error ? (
        <div className="mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </div>
      ) : null}

      {linkNotice ? (
        <div className="mb-4 rounded-md border border-line-200 bg-field-50 px-3 py-2 text-sm text-ink-700">
          {linkNotice}
        </div>
      ) : null}

      {issuedLinks.map((entry) => {
        const message = sanctionMessageFor(entry.order, entry.link, entry.signInEmail);
        return (
          <div
            key={entry.link.id}
            className="mb-4 grid gap-3 rounded-md border border-line-200 bg-field-50 px-3 py-3"
          >
            <p className="text-sm font-medium text-ink-900">
              Sign-in link for {entry.designerName} · {entry.signInEmail}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <input
                readOnly
                value={entry.link.link}
                aria-label={`Sign-in link for ${entry.designerName}`}
                onFocus={(event) => event.currentTarget.select()}
                className="field-input min-w-0 flex-1 font-mono text-xs"
              />
              <ActionGroup className="max-sm:w-full"><button
                type="button"
                className="field-button-secondary"
                onClick={() => copy(entry.link.link, `link:${entry.link.id}`)}
              >
                {copied === `link:${entry.link.id}` ? "Copied" : "Copy link"}
              </button>
              <button
                type="button"
                className="field-button-secondary"
                disabled={linkBusy}
                onClick={async () => {
                  setLinkBusy(true);
                  try {
                    await revokeSanctionCredentialLink(entry.order.id, entry.link.id);
                    setIssuedLinks((current) =>
                      current.filter((other) => other.link.id !== entry.link.id)
                    );
                    setCopied(null);
                  } catch (err) {
                    setError(problemOf(err, "Unable to withdraw the link"));
                  } finally {
                    setLinkBusy(false);
                  }
                }}
              >
                Withdraw
              </button>
              <button
                type="button"
                className="field-button-secondary"
                onClick={() => {
                  setIssuedLinks((current) =>
                    current.filter((other) => other.link.id !== entry.link.id)
                  );
                  setCopied(null);
                }}
              >
                Done
              </button></ActionGroup>
            </div>

            <div className="grid gap-1">
              <span className="field-label">Message to send</span>
              <textarea
                readOnly
                rows={7}
                aria-label={`Message to send to ${entry.designerName}`}
                onFocus={(event) => event.currentTarget.select()}
                value={message}
                className="field-input text-xs leading-5"
              />
              <div>
                <button
                  type="button"
                  className="field-button-secondary"
                  onClick={() => copy(message, `message:${entry.link.id}`)}
                >
                  {copied === `message:${entry.link.id}` ? "Copied" : "Copy message"}
                </button>
              </div>
            </div>

            <p className="text-xs leading-5 text-ink-500">
              Send it yourself. Shown once; expires {formatLinkExpiry(entry.link.expiresAt)}.
            </p>
          </div>
        );
      })}

      <UploadSanctionDialog
        open={uploadOpen}
        onClose={() => setUploadOpen(false)}
        onPreview={(answer) => {
          setUploadOpen(false);
          setImportReport(null);
          setPreview(answer);
        }}
      />

      {preview ? (
        <SanctionImportReview
          preview={preview}
          busy={importBusy}
          onCancel={() => setPreview(null)}
          onConfirm={async (rows) => {
            setImportBusy(true);
            setError(null);
            try {
              const report = await confirmSanctionImport({
                sheet: preview.sheet,
                sourceFilename: preview.sourceFilename,
                rowsRead: preview.rowsRead,
                refusedBeforeConfirm: preview.refused.length,
                rows
              });
              setPreview(null);
              setImportReport(report);
              const imported = report.created.flatMap((order) =>
                (order.credentialLinks ?? []).flatMap((entry) => {
                  const link = copyable(entry.link);
                  return link
                    ? [{ order: order.sanctionOrder, link, designerName: entry.designerName, signInEmail: entry.signInEmail }]
                    : [];
                })
              );
              const emailedCount = report.created.reduce(
                (sum, order) =>
                  sum + (order.credentialLinks ?? []).filter((entry) => entry.link?.deliveredBy === "EMAIL").length,
                0
              );
              if (emailedCount > 0) {
                setLinkNotice(`${emailedCount === 1 ? "1 sign-in link" : `${emailedCount} sign-in links`} e-mailed.`);
              }
              setIssuedLinks(imported);
              setCopied(null);
              setPage(1);
              await refresh();
              await refreshAwaitingSanctionCount();
            } catch (err) {
              setError(problemOf(err, "The confirmation could not be recorded"));
            } finally {
              setImportBusy(false);
            }
          }}
        />
      ) : null}

      {importReport ? (
        <SanctionImportReport report={importReport} onDismiss={() => setImportReport(null)} />
      ) : null}

      <form ref={formRef} onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-6">
        <div className="md:col-span-2">
          <Field label="Sanction order number" required>
            <TextInput name="sanctionOrderNo" required maxLength={120} placeholder="SO/2026/42" />
          </Field>
        </div>
        <div className="md:col-span-2">
          <CalendarField label="Sanction date" name="sanctionOrderDate" required clearable={false} />
        </div>
        <div className="md:col-span-2">
          <Field label="Sanction amount (₹)" required>
            <TextInput
              name="sanctionAmount"
              required
              inputMode="decimal"
              pattern="[0-9]+(\.[0-9]{1,2})?"
              placeholder="450000.00"
            />
          </Field>
        </div>
        <div className="md:col-span-6">
          <PlannedWorkshopPicker picked={planned} onPick={setPlanned} disabled={saving} />
        </div>
        <div className="md:col-span-6">
          <WorkshopDesignerPicker
            values={chosen}
            onChange={setChosen}
            lead={lead}
            onLeadChange={setLead}
            disabled={saving}
            fetchEligible={fetchEligible}
          />
        </div>
        <div className="md:col-span-6 grid gap-3 md:grid-cols-2">
          <Field label="Or a new designer's name">
            <TextInput name="designerName" maxLength={160} />
            <p className="mt-1 text-xs leading-5 text-ink-500">
              Their account and sign-in link are created.
            </p>
          </Field>
          <Field label="Their Gmail ID">
            <TextInput name="designerEmail" type="email" />
          </Field>
        </div>
        <div className="md:col-span-5">
          <Field label="Notes">
            <TextArea name="notes" maxLength={2000} rows={2} />
          </Field>
        </div>
        <ActionGroup>
          <button type="submit" className="field-button w-full" disabled={saving}>
            {saving ? "Recording…" : "Record sanction order"}
          </button>
        </ActionGroup>
      </form>

      <div className="mb-3 flex flex-wrap items-center gap-3">
        <div className="min-w-60 flex-1">
          <SearchInput
            value={search}
            onChange={(value) => {
              setSearch(value);
              setPage(1);
            }}
            placeholder="Search by order number, designer or address"
            ariaLabel="Search sanction orders"
          />
        </div>
        <Toggle
          label="Only orders I recorded"
          checked={mine}
          onChange={(next) => {
            setMine(next);
            setPage(1);
          }}
        />
      </div>

      {items === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : items.length === 0 ? (
        <EmptyState
          title="No sanction orders here"
          body="Record one above."
        />
      ) : (
        <div className="panel overflow-hidden">
          <ul className="divide-y divide-line-200">
            {items.map((order) => (
              <OpenableCard as="li" key={order.id} href={null} onOpen={() => setViewing(order)} className="grid gap-2 px-4 py-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <button
                    type="button"
                    onClick={() => setViewing(order)}
                    className="rounded-sm text-left font-display text-base font-semibold text-ink-900 underline-offset-2 hover:underline"
                  >
                    {order.sanctionOrderNo}
                  </button>
                  <span className="text-sm text-ink-700">
                    {order.sanctionOrderDate} · {formatSanctionAmount(order.sanctionAmount)}
                  </span>
                </div>
                <p className="text-sm text-ink-700">
                  {order.designers.length > 0
                    ? order.designers
                        .map((designer) => `${designer.designerName} (${designer.designerEmail})`)
                        .join(", ")
                    : `${order.designerName} · ${order.designerEmail}`}
                  {order.designers.length > 1 ? (
                    <span className="text-ink-500">
                      {" "}
                      — {order.designers[0]!.designerName} leads, and is the name on the report.
                    </span>
                  ) : null}
                </p>
                {order.sourceFilename ? (
                  <p className="text-xs leading-5 text-ink-500">
                    Imported from {order.sourceFilename}
                    {order.sheetRow != null ? `, row ${order.sheetRow}` : null}
                  </p>
                ) : null}
                <p className="text-sm text-ink-500">
                  <Link
                    href={`/design-workshops/${order.designWorkshopId}`}
                    className="text-ministry-700 underline underline-offset-2 dark:text-ministry-300"
                  >
                    {order.workshopTitle}
                  </Link>{" "}
                  · {order.workshopStatus}
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  <span
                    className={
                      order.readyForWork
                        ? "rounded-full bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
                        : "rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                    }
                  >
                    {readinessSentence(order)}
                  </span>
                  {reissuable(order).map((designer) => {
                    const named = order.designers.length > 1 ? ` ${designer.designerName || designer.signInEmail}` : "";
                    const status = order.designers.find((d) => d.designerUserId === designer.designerUserId)?.linkEmail;
                    const words = linkEmailWords(status);
                    async function issue(deliverBy: "COPY_LINK" | "EMAIL") {
                      setLinkBusy(true);
                      setError(null);
                      setLinkNotice(null);
                      try {
                        const answer = await issueSanctionCredentialLink(order.id, designer.designerUserId, deliverBy);
                        const link = copyable(answer);
                        if (link) {
                          setIssuedLinks([{ order, link, designerName: designer.designerName, signInEmail: designer.signInEmail }]);
                          setCopied(null);
                        } else {
                          setLinkNotice(`New sign-in link e-mailed to ${designer.signInEmail}.`);
                          await refresh();
                        }
                      } catch (err) {
                        setError(problemOf(err, "Unable to issue a sign-in link"));
                      } finally {
                        setLinkBusy(false);
                      }
                    }
                    if (!mailOn) {
                      return (
                        <button
                          key={designer.designerUserId}
                          type="button"
                          className="field-button-secondary"
                          disabled={linkBusy}
                          onClick={() => void issue("COPY_LINK")}
                        >
                          {named ? `Re-issue${named}'s sign-in link` : "Re-issue sign-in link"}
                        </button>
                      );
                    }
                    return (
                      <span key={designer.designerUserId} className="flex flex-wrap items-center gap-2">
                        {words ? (
                          <span
                            className={
                              status?.status === "FAILED"
                                ? "rounded-full bg-error-100 px-2 py-0.5 text-xs font-medium text-error-600"
                                : status?.status === "SENT"
                                  ? "rounded-full bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
                                  : "rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                            }
                            title={status?.error ?? undefined}
                          >
                            {named ? `${named.trim()}: ` : ""}
                            {words}
                          </span>
                        ) : null}
                        <ActionGroup inline>
                          <button
                            type="button"
                            className="field-button-secondary"
                            disabled={linkBusy}
                            onClick={() => void issue("EMAIL")}
                          >
                            {named ? `Resend to${named}` : "Resend"}
                          </button>
                          <button
                            type="button"
                            className="field-button-secondary"
                            disabled={linkBusy}
                            onClick={() => void issue("COPY_LINK")}
                          >
                            Copy link instead
                          </button>
                        </ActionGroup>
                      </span>
                    );
                  })}
                  {mailOn && !order.readyForWork && order.missingMandatory.length ? (
                    <button
                      type="button"
                      className="field-button-secondary"
                      disabled={linkBusy}
                      onClick={async () => {
                        setLinkBusy(true);
                        setError(null);
                        setLinkNotice(null);
                        try {
                          setLinkNotice(reminderSentence(await remindSanctionDesigners(order.id)));
                          await refresh();
                        } catch (err) {
                          setError(problemOf(err, "The reminder could not be sent"));
                        } finally {
                          setLinkBusy(false);
                        }
                      }}
                    >
                      Remind designers
                    </button>
                  ) : null}
                  {order.lastRemindedAt ? (
                    <span className="text-xs text-ink-500">Reminded {reminderMoment(order.lastRemindedAt)}</span>
                  ) : null}
                </div>
                {order.reportCopyMatches === false ? (
                  <p className="text-xs leading-5 text-amber-800 dark:text-amber-100">
                    The report cover (stage&nbsp;1) differs from this order.
                  </p>
                ) : null}
                {order.notes ? <p className="text-xs leading-5 text-ink-500">{order.notes}</p> : null}
                {!order.readyForWork && order.missingMandatory.length ? (
                  <p className="text-xs leading-5 text-ink-500">
                    Still needed on Workshop Setup: {order.missingMandatory.join(", ")}
                  </p>
                ) : null}
              </OpenableCard>
            ))}
          </ul>
          <Pagination
            page={data?.page ?? 1}
            pages={data?.pages ?? 0}
            total={data?.total ?? 0}
            onPage={setPage}
          />
        </div>
      )}
      <RecordDetailsDialog
        open={viewing !== null}
        onClose={() => setViewing(null)}
        onDelete={viewing && canDeleteRecords ? () => void deleteOrder(viewing) : undefined}
        title={viewing ? `Sanction order ${viewing.sanctionOrderNo}` : "Sanction order"}
        details={
          viewing
            ? [
                ["Order number", viewing.sanctionOrderNo],
                ["Order date", viewing.sanctionOrderDate ?? "Not given"],
                ["Sanctioned amount", formatSanctionAmount(viewing.sanctionAmount)],
                [
                  viewing.designers.length > 1 ? "Designers" : "Designer",
                  viewing.designers.length > 0
                    ? viewing.designers.map((designer) => `${designer.designerName} (${designer.designerEmail})`).join(", ")
                    : `${viewing.designerName} (${viewing.designerEmail})`
                ],
                ["Name on the report", viewing.designers.length > 1 ? viewing.designers[0]!.designerName : null],
                ["Workshop", `${viewing.workshopTitle} · ${viewing.workshopStatus}`],
                [
                  "Planned workshop",
                  viewing.plannedWorkshop
                    ? `${viewing.plannedWorkshop.workshopNo} · ${viewing.plannedWorkshop.planYearLabel}`
                    : null
                ],
                ["Readiness", readinessSentence(viewing)],
                [
                  "Still needed on Workshop Setup",
                  !viewing.readyForWork && viewing.missingMandatory.length ? viewing.missingMandatory.join(", ") : null
                ],
                [
                  "Imported from",
                  viewing.sourceFilename
                    ? `${viewing.sourceFilename}${viewing.sheetRow != null ? `, row ${viewing.sheetRow}` : ""}`
                    : null
                ],
                ["Recorded by", viewing.createdByName ?? "Unknown"],
                ["Notes", viewing.notes]
              ]
            : []
        }
      >
        {viewing ? (
          <p className="mt-4 text-sm">
            <Link
              href={`/design-workshops/${viewing.designWorkshopId}`}
              className="text-ministry-700 underline underline-offset-2 dark:text-ministry-300"
            >
              Open the workshop
            </Link>
          </p>
        ) : null}
      </RecordDetailsDialog>
    </>
  );
}
