"use client";

/**
 * THE OFFICER'S SANCTION REGISTER — five fields in, a workshop and a designer out.
 *
 * ══ WHY THERE ARE ONLY FIVE BOXES ══════════════════════════════════════════════════════════════
 *
 * Order number, order date, sanctioned amount, the designer's name and their Gmail address. That is
 * the whole form, because it is the whole requirement: an officer initiating a project types what
 * is printed on the paper in front of them and nothing else. Everything a workshop needs beyond
 * that — the state, the district, the craft, the cluster, the venue, the dates — belongs to the
 * DESIGNER, who is the person who knows it, and the list below is how an officer sees which of
 * their sanctions are still waiting on those answers.
 *
 * ══ PRESSING "RECORD" WRITES SEVEN ROWS ════════════════════════════════════════════════════════
 *
 * An allow-list admission, an empanelment, an account (only where the mailbox has none), a designer
 * profile, a workshop, the designer's viewer row on it, and the order itself — all in ONE
 * transaction, so a refusal leaves nothing behind. `backend/app/services/sanction_orders.py` is
 * where that is argued. Two refusals are worth knowing at the screen: an address an admin has
 * BARRED and an empanelment an admin has ENDED are both 422s naming what to do, because a sanction
 * order must not quietly re-admit somebody an administrator showed the door.
 *
 * ══ NOTHING IS EMAILED. THE OFFICER IS THE TRANSPORT, AND THIS SCREEN SAYS SO ══════════════════
 *
 * There is no mailer in this product. The sign-in link comes back once, in the response to the
 * create, and nothing anywhere can show it again — the server stores only a SHA-256 digest. So the
 * panel below offers the link to copy and a prewritten message to paste, and it says in words that
 * nothing has been sent. An officer who records ten sanctions and copies none has ten designers who
 * cannot sign in; the re-issue action on each row is the remedy, and the badge on the nav entry is
 * the only thing that will tell them.
 *
 * ══ NO `.filter()` AND NO `.sort()` OVER `data.items`, ANYWHERE ON THIS PAGE ════════════════════
 *
 * Every narrowing is a query parameter. A list filtered in the browser is the right SIZE and has
 * silently dropped whatever it excluded, which on a register reads as "that order was never
 * recorded" — and the officer's next move is to record it again, into a 409 they cannot explain.
 *
 * ══ AND THERE IS NO DELETE ON THIS SCREEN ══════════════════════════════════════════════════════
 *
 * A sanction order is the record that money was authorised. Nothing in this product that records an
 * institution's decision is ever deleted. A withdrawn order has, today, only `notes` — which is a
 * real gap and an open question for the owner rather than an oversight.
 */

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { FileSignature } from "lucide-react";

import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { SearchInput } from "@/components/SearchInput";
import { describeApiDetail } from "@/lib/api";
import {
  formatLinkExpiry,
  formatSanctionAmount,
  issueSanctionCredentialLink,
  listSanctionOrders,
  readinessSentence,
  recordSanctionOrder,
  revokeSanctionCredentialLink,
  sanctionMessageFor,
  type SanctionOrder,
  type SanctionOrderCredentialLink,
  type SanctionOrderPage
} from "@/lib/sanctionOrders";

const PAGE_SIZE = 25;

/** The link panel's subject, so the message can name the order it belongs to. */
type IssuedLink = { order: SanctionOrder; link: SanctionOrderCredentialLink };

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
  const [issued, setIssued] = useState<IssuedLink | null>(null);
  const [copied, setCopied] = useState<"link" | "message" | null>(null);
  const [linkBusy, setLinkBusy] = useState(false);
  const [linkNotice, setLinkNotice] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);

  // THE GENERATION COUNTER, not an AbortController: `apiFetch` takes no signal, and what actually
  // matters is ignoring the LATE answer rather than cancelling the request. A debounce-free search
  // box plus a page change can have two reads in flight, and without this the slower one wins.
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
      // `null` stays `null` only on the FIRST failure; a later failure keeps what is on screen,
      // because an empty register and a failed read must never look the same.
      setData((current) => current ?? { items: [], total: 0, page: 1, pageSize: PAGE_SIZE, pages: 0 });
    }
  }, [page, search, mine]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    // FIRST STATEMENT, BEFORE ANY AWAIT. React nulls `event.currentTarget` across an await, and a
    // form read afterwards is empty — a create that posts blanks with no error anywhere.
    const form = new FormData(event.currentTarget);
    setSaving(true);
    setError(null);
    setLinkNotice(null);
    try {
      const created = await recordSanctionOrder({
        sanctionOrderNo: String(form.get("sanctionOrderNo") ?? "").trim(),
        sanctionOrderDate: String(form.get("sanctionOrderDate") ?? ""),
        // A STRING, START TO FINISH. The box is `inputMode="decimal"` and not `type="number"` for
        // the reason the registry's MONEY control is a separate control: a number input eats a
        // trailing zero and normalises what the officer typed, on a figure that has to match a
        // ministry document to the paisa.
        sanctionAmount: String(form.get("sanctionAmount") ?? "").trim(),
        designerName: String(form.get("designerName") ?? "").trim(),
        designerEmail: String(form.get("designerEmail") ?? "").trim(),
        notes: String(form.get("notes") ?? "").trim() || null
      });
      formRef.current?.reset();
      if (created.credentialLink) {
        setIssued({ order: created.sanctionOrder, link: created.credentialLink });
        setCopied(null);
      } else if (created.credentialLinkProblem) {
        setLinkNotice(created.credentialLinkProblem);
      } else if (!created.sanctionOrder.accountCreated) {
        setLinkNotice(
          `${created.sanctionOrder.designerName} already has an account — they sign in as they always do, and the workshop is on their list now.`
        );
      }
      setPage(1);
      await refresh();
    } catch (err) {
      setError(problemOf(err, "Unable to record the sanction order"));
    } finally {
      setSaving(false);
    }
  }

  async function copy(text: string, which: "link" | "message") {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(which);
    } catch {
      // Clipboard access can be refused outright (an insecure origin, a locked-down browser). SAY
      // NOTHING RATHER THAN CLAIM SUCCESS — the text is on screen and selectable, and a "Copied"
      // that did not copy is the one outcome that loses the credential.
      setCopied(null);
    }
  }

  const items = data?.items ?? null;

  return (
    <>
      <PageHeader
        title="Sanction orders"
        description="The ministry's sanction register — number, date, amount and the designer it names. Recording one opens the workshop and issues the designer's sign-in link."
        icon={<FileSignature className="h-5 w-5" aria-hidden />}
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

      {issued ? (
        <div className="mb-4 grid gap-3 rounded-md border border-line-200 bg-field-50 px-3 py-3">
          <p className="text-sm font-medium text-ink-900">
            Sign-in link for {issued.order.designerName} · {issued.order.designerEmail}
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <input
              readOnly
              value={issued.link.link}
              aria-label="Sign-in link"
              onFocus={(event) => event.currentTarget.select()}
              className="field-input min-w-0 flex-1 font-mono text-xs"
            />
            <button type="button" className="field-button-secondary" onClick={() => copy(issued.link.link, "link")}>
              {copied === "link" ? "Copied" : "Copy link"}
            </button>
            <button
              type="button"
              className="field-button-secondary"
              disabled={linkBusy}
              onClick={async () => {
                setLinkBusy(true);
                try {
                  await revokeSanctionCredentialLink(issued.order.id, issued.link.id);
                  setIssued(null);
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
                setIssued(null);
                setCopied(null);
              }}
            >
              Done
            </button>
          </div>

          {/* THE PREWRITTEN MESSAGE — the one thing the users screen does not have, and the reason
              it is here is that on this screen the officer IS the transport. Composing this
              sentence fifteen times on a Monday morning is where the sign-in address gets left out
              and the designer cannot get in. `sanctionMessageFor` words it once. */}
          <div className="grid gap-1">
            <span className="field-label">Message to send</span>
            <textarea
              readOnly
              rows={7}
              aria-label="Message to send to the designer"
              onFocus={(event) => event.currentTarget.select()}
              value={sanctionMessageFor(issued.order, issued.link, issued.order.designerEmail)}
              className="field-input text-xs leading-5"
            />
            <div>
              <button
                type="button"
                className="field-button-secondary"
                onClick={() =>
                  copy(
                    sanctionMessageFor(issued.order, issued.link, issued.order.designerEmail),
                    "message"
                  )
                }
              >
                {copied === "message" ? "Copied" : "Copy message"}
              </button>
            </div>
          </div>

          {/* NOT DECORATION. Nothing can show this link again, and no email has been sent — saying
              so is the difference between an officer who forwards it and a designer who waits for a
              message that is never coming. */}
          <p className="text-xs leading-5 text-ink-500">
            Nothing has been emailed — send this yourself, by whatever you already use. The link is
            shown once, works once, and expires {formatLinkExpiry(issued.link.expiresAt)}.
          </p>
        </div>
      ) : null}

      <form ref={formRef} onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-5">
        <Field label="Sanction order number" required>
          <TextInput name="sanctionOrderNo" required maxLength={120} placeholder="SO/2026/42" />
        </Field>
        <Field label="Sanction date" required>
          <TextInput name="sanctionOrderDate" type="date" required />
        </Field>
        <Field label="Sanction amount (₹)" required>
          {/* `inputMode="decimal"`, NEVER `type="number"` — see the submit handler. */}
          <TextInput
            name="sanctionAmount"
            required
            inputMode="decimal"
            pattern="[0-9]+(\.[0-9]{1,2})?"
            placeholder="450000.00"
          />
        </Field>
        <Field label="Designer's name" required>
          <TextInput name="designerName" required maxLength={160} />
        </Field>
        <Field label="Designer's Gmail ID" required>
          <TextInput name="designerEmail" type="email" required />
        </Field>
        <div className="md:col-span-4">
          <Field label="Notes">
            <TextArea name="notes" maxLength={2000} rows={2} />
          </Field>
        </div>
        <div className="flex items-end">
          <button type="submit" className="field-button w-full" disabled={saving}>
            {saving ? "Recording…" : "Record sanction order"}
          </button>
        </div>
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
        <label className="flex items-center gap-2 text-sm text-ink-700">
          <input
            type="checkbox"
            checked={mine}
            onChange={(event) => {
              setMine(event.currentTarget.checked);
              setPage(1);
            }}
          />
          Only orders I recorded
        </label>
      </div>

      {items === null ? (
        <p className="text-sm text-ink-500">Loading…</p>
      ) : items.length === 0 ? (
        <EmptyState
          title="No sanction orders here"
          body="Record one above and the workshop, the designer's account and their sign-in link are all created together."
        />
      ) : (
        <div className="panel overflow-hidden">
          <ul className="divide-y divide-line-200">
            {items.map((order) => (
              <li key={order.id} className="grid gap-2 px-4 py-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="font-display text-base font-semibold text-ink-900">
                    {order.sanctionOrderNo}
                  </span>
                  <span className="text-sm text-ink-700">
                    {order.sanctionOrderDate} · {formatSanctionAmount(order.sanctionAmount)}
                  </span>
                </div>
                <p className="text-sm text-ink-700">
                  {order.designerName} · {order.designerEmail}
                </p>
                <p className="text-sm text-ink-500">
                  <Link
                    href={`/design-workshops/${order.designWorkshopId}`}
                    className="text-purple-700 underline underline-offset-2"
                  >
                    {order.workshopTitle}
                  </Link>{" "}
                  · {order.workshopStatus}
                </p>
                <div className="flex flex-wrap items-center gap-2">
                  {/* THE READINESS PILL CARRIES A WORD AS WELL AS A COLOUR. Colour never carries
                      meaning alone here — the judgement has to survive colour-blindness, greyscale
                      printing and forced-colours mode. */}
                  <span
                    className={
                      order.readyForWork
                        ? "rounded-full bg-success-100 px-2 py-0.5 text-xs font-medium text-success-600"
                        : "rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800"
                    }
                  >
                    {readinessSentence(order)}
                  </span>
                  {order.accountCreated ? (
                    <button
                      type="button"
                      className="field-button-secondary"
                      disabled={linkBusy}
                      onClick={async () => {
                        setLinkBusy(true);
                        setError(null);
                        setLinkNotice(null);
                        try {
                          const link = await issueSanctionCredentialLink(order.id);
                          setIssued({ order, link });
                          setCopied(null);
                        } catch (err) {
                          setError(problemOf(err, "Unable to issue a sign-in link"));
                        } finally {
                          setLinkBusy(false);
                        }
                      }}
                    >
                      Re-issue sign-in link
                    </button>
                  ) : null}
                </div>
                {order.reportCopyMatches === false ? (
                  /* REPORTED, NOT BLOCKED. A designer correcting a mistyped number on their own
                     report cover is doing something legitimate; what an officer needs is to SEE
                     that the document about to be printed says something the register does not. */
                  <p className="text-xs leading-5 text-amber-800">
                    The report cover says something different from this order. The register is this
                    row; the cover is the designer&apos;s stage&nbsp;1.
                  </p>
                ) : null}
                {order.notes ? <p className="text-xs leading-5 text-ink-500">{order.notes}</p> : null}
                {!order.readyForWork && order.missingMandatory.length ? (
                  <p className="text-xs leading-5 text-ink-500">
                    Still needed on Workshop Setup: {order.missingMandatory.join(", ")}
                  </p>
                ) : null}
              </li>
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
    </>
  );
}
