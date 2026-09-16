"use client";

/**
 * THE OFFICER'S SANCTION REGISTER — five fields in, a workshop and a designer out.
 *
 * ══ WHY THE FORM IS STILL THIS SHORT ═══════════════════════════════════════════════════════════
 *
 * Order number, order date, sanctioned amount, and the designer or designers it names. That is the
 * whole form, because it is the whole requirement: an officer initiating a project types what is
 * printed on the paper in front of them and nothing else. Everything a workshop needs beyond that —
 * the state, the district, the craft, the cluster, the venue, the dates — belongs to the DESIGNER,
 * who is the person who knows it, and the list below is how an officer sees which of their
 * sanctions are still waiting on those answers.
 *
 * **THIS HEADING SAID "WHY THERE ARE ONLY FIVE BOXES" UNTIL 0.0.12**, and the fifth was a single
 * designer's name typed by hand. A sanction order is routinely issued for a TEAM: while the form
 * took one name the second and third designers were either left off the instrument entirely — no
 * account, no empanelment, unable to open the workshop their own order paid for — or recorded as a
 * second sanction order under a number the ministry never issued.
 *
 * ══ AND YET EXACTLY ONE NAME REACHES THE REPORT ════════════════════════════════════════════════
 *
 * Several people may OPEN the workshop; one name is ON it. Stage 1 declares a single `designerName`
 * box and the .docx's `dc:creator` is a single-author field the file format cannot express as a
 * list. So the picker resolves a LEAD and says on screen who it is — whose name lands on a ministry
 * document must not be decided by a tick order nobody can see. The rule is `namedDesignerTeam`,
 * shared with the submit, so the sentence under the picker and the body on the wire cannot
 * disagree.
 *
 * ══ PRESSING "RECORD" WRITES SEVEN ROWS PLUS FOUR PER DESIGNER ═════════════════════════════════
 *
 * An allow-list admission, an empanelment, an account (only where the mailbox has none), a designer
 * profile and a viewer row for EACH named designer, plus the workshop, the order itself and one
 * join row per name — all in ONE transaction, so a refusal leaves nothing behind.
 * `backend/app/services/sanction_orders.py` is where that is argued. Two refusals are worth knowing
 * at the screen: an address an admin has BARRED and an empanelment an admin has ENDED are both 422s
 * naming what to do, because a sanction order must not quietly re-admit somebody an administrator
 * showed the door. Since 0.0.12 each is asked of EVERY named designer, and a team whose third name
 * is barred is refused whole.
 *
 * ══ OR UPLOAD A SHEET OF THEM, IN TWO STEPS ════════════════════════════════════════════════════
 *
 * The first POST reads the workbook and writes NOTHING: it answers three lists — what it will
 * record, what it needs a decision about, and what it refuses whatever the officer says. The second
 * carries the answers back. `SanctionImportReview.tsx` holds the argument for which rows get a
 * question and which do not, and it is the one that matters: a confirmation that asks about
 * everything is as useless as one that asks about nothing.
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

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { Download, FileSignature, Upload } from "lucide-react";

import { WorkshopDesignerPicker } from "@/components/designworkshop/WorkshopDesignerPicker";
import { refreshAwaitingSanctionCount } from "@/components/hooks/useAwaitingSanctionCount";
import { EmptyState } from "@/components/EmptyState";
import { Field, TextArea, TextInput } from "@/components/FormControls";
import { PageHeader } from "@/components/PageHeader";
import { Pagination } from "@/components/Pagination";
import { SearchInput } from "@/components/SearchInput";
import { describeApiDetail } from "@/lib/api";
import { namedDesignerTeam } from "@/lib/designWorkshops";
import {
  confirmSanctionImport,
  downloadSanctionProForma,
  formatLinkExpiry,
  formatSanctionAmount,
  issueSanctionCredentialLink,
  listSanctionDesigners,
  listSanctionOrders,
  readinessSentence,
  recordSanctionOrder,
  revokeSanctionCredentialLink,
  sanctionMessageFor,
  type SanctionImportPreview,
  type SanctionImportReport as ImportReport,
  type SanctionOrder,
  type SanctionOrderCredentialLink,
  type SanctionOrderPage
} from "@/lib/sanctionOrders";

import { SanctionImportReport } from "./SanctionImportReport";
import { SanctionImportReview } from "./SanctionImportReview";
import { UploadSanctionDialog } from "./UploadSanctionDialog";

const PAGE_SIZE = 25;

/**
 * The link panel's subject, so the message can name the order it belongs to.
 *
 * ``signInEmail`` TRAVELS SEPARATELY FROM THE ORDER because they are not the same address. The
 * order's ``designerEmail`` is the CANONICAL mailbox the register was written under; the sign-in
 * door looks ``User.email`` up literally, and for a dotted or ``+tagged`` Gmail the two differ ON
 * PURPOSE. Naming the register's spelling in a prewritten message is a designer who cannot sign
 * in and an officer who cannot see why.
 *
 * ``designerName`` likewise: with several designers on one order, the panel is about ONE of them
 * and the order's lead scalar would name the wrong person on the second panel of three.
 */
type IssuedLink = {
  order: SanctionOrder;
  link: SanctionOrderCredentialLink;
  designerName: string;
  signInEmail: string;
};

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
  /**
   * Every sign-in link the last act produced, drawn one panel each.
   *
   * A LIST AND NOT A SINGLE VALUE, because one order can mint several accounts and each link is
   * shown exactly once. The re-issue button on a row puts ONE entry here; recording an order that
   * created four accounts puts four.
   */
  const [issuedLinks, setIssuedLinks] = useState<IssuedLink[]>([]);
  /**
   * Which panel's button last said "Copied", keyed by designer as well as by which button.
   *
   * With one panel a bare `"link" | "message"` was enough. With four, it would light up all four
   * Copy buttons at once and tell an officer they had copied three credentials they had not
   * touched — on the one screen where a link that was not copied is a designer who cannot sign in.
   */
  const [copied, setCopied] = useState<string | null>(null);
  const [linkBusy, setLinkBusy] = useState(false);
  const [linkNotice, setLinkNotice] = useState<string | null>(null);
  const formRef = useRef<HTMLFormElement | null>(null);

  /**
   * THE PICKER'S STATE, and the free-text box beside it.
   *
   * ── WHY BOTH CONTROLS EXIST ──────────────────────────────────────────────────────────────
   *
   * The picker offers the designers this platform already knows. **The ordinary case for a
   * sanction order is a designer it does NOT** — that is the whole reason recording one mints an
   * account, an empanelment and a first sign-in link. A picker alone would be a form that cannot
   * express the commonest thing an officer does; a text box alone is what this screen had, and it
   * is why an officer naming a colleague they have sanctioned before had to retype their address
   * from memory into a column that decides which account a ministry workshop is filed under.
   *
   * ── THE TWO ARE ONE LIST ON THE WIRE ─────────────────────────────────────────────────────
   *
   * ``chosen``/``lead`` go through ``namedDesignerTeam`` — the shared rule, so an unticked lead is
   * demoted rather than re-added and a lead standing alone IS the team — and the typed pair is
   * appended after them. The FIRST entry of the result is the lead, which is the one name that
   * reaches the report cover.
   */
  const [chosen, setChosen] = useState<string[]>([]);
  const [lead, setLead] = useState("");
  /**
   * Every account the picker has served this mount, so a ticked id can be turned back into a name
   * and an address WITHOUT a second request.
   *
   * MERGED AND NEVER REPLACED, which is the same rule the picker keeps for its own label cache and
   * for the same reason: a designer ticked under one search term must not vanish from this map
   * under the next, or the submit would post a bare cuid as somebody's name. The picker resolves
   * ids to LABELS for display; this resolves them to the two FIELDS the create body takes, which
   * is why the page keeps its own copy rather than reading the control's.
   */
  const [seen, setSeen] = useState<Record<string, { name: string; email: string }>>({});

  const [uploadOpen, setUploadOpen] = useState(false);
  const [preview, setPreview] = useState<SanctionImportPreview | null>(null);
  const [importReport, setImportReport] = useState<ImportReport | null>(null);
  const [importBusy, setImportBusy] = useState(false);

  /**
   * The picker's door, and the reason this page has one at all.
   *
   * ``GET /sanction-orders/designers`` is the FIFTH designer directory in this product, and it
   * exists because the other four all refuse an Assistant Director — which is the FLOOR of this
   * screen's own gate. ``PromoteDialog.tsx`` documents that trap one tier up: a picker that 403s,
   * on the one screen built for the people it refuses.
   *
   * WRAPPED SO THE CONTROL SEES ``{users, truncated}`` — the shape all three of its doors answer,
   * four keys per row. The ``seen`` map is filled on the way through, which is the one place a
   * ticked id can be resolved to the name and address the create body needs.
   */
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

  /**
   * WHOSE NAME REACHES THE REPORT, decided by the same function the submit reads.
   *
   * The sentence on screen and the body on the wire must not be able to disagree about this — the
   * discipline ``WorkshopDesignerPicker`` states for its own lead line, and the defect `PromoteDialog`
   * shipped when its paragraph branched on ``chosen.length`` while the submit branched on the
   * resolver. The typed pair is deliberately NOT eligible to lead: a designer chosen from the
   * roster is somebody this platform can already name on a document, and a half-typed address is
   * not.
   */
  const team = useMemo(() => namedDesignerTeam({ chosen, lead }), [chosen, lead]);

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
      /*
        THE TEAM, LEAD FIRST, AND THE TWO CONTROLS FOLDED INTO ONE LIST.

        `namedDesignerTeam` is the shared rule and it runs on the TICKED set alone: an unticked
        lead is demoted rather than re-added, and a lead standing alone IS the team. The typed pair
        is appended after it, never before — the first entry is the lead, and a half-typed address
        must not become the name on a ministry document just because it was the last thing touched.

        A TICKED ID THIS MOUNT HAS NOT SEEN IS DROPPED RATHER THAN SENT AS A NAME. `seen` is filled
        by every read the picker makes, so a miss is not reachable from this screen — but sending
        `{ name: <cuid>, email: "" }` if it ever were would put a database id on a financial
        instrument, and `EmailStr` would 422 the whole order at the last moment for it.
      */
      const picked = team.team
        .map((id) => seen[id])
        .filter((person): person is { name: string; email: string } => Boolean(person?.email));
      const typedName = String(form.get("designerName") ?? "").trim();
      const typedEmail = String(form.get("designerEmail") ?? "").trim();
      /*
        ⚠ A NAME WITH NO ADDRESS IS REFUSED, BECAUSE THE LINE BELOW KEYS THE WHOLE TYPED PAIR OFF
        THE EMAIL BOX AND WOULD OTHERWISE DROP IT WITHOUT A WORD.

        `designerEmail` is what identifies a person to this register — it is the mailbox the account
        is minted against, the allow-list row, the empanelment and the dedup key — so the pair can
        only travel when it is filled. That makes the omission SILENT rather than merely lossy: a
        name typed with no address contributed nothing, the `everybody.length === 0` guard below did
        not fire because a designer was also ticked in the picker, the order was created naming one
        person, and `formRef.current?.reset()` then wiped the name so there was nothing left on
        screen to notice. There is no delete on this register and no route that adds a designer to
        an existing order, so the only correction available was recording a second order under a
        number the ministry never issued — the exact failure this file's header says the
        multi-designer work exists to remove.

        ONE DIRECTION ONLY, AND THE MIRROR CASE IS DELIBERATELY LEFT ALONE. An address with no name
        is already handled and is a reasonable thing to type: `first!.name || first!.email` and
        `person.name || person.email` below both fall back to the address, so the designer is named
        on the document by the one thing we actually know about them. Refusing it would turn a
        working path into an error message for no gain.

        SAID HERE RATHER THAN AS A `required` MARK, for the reason the comment on the two controls
        gives: the browser's own validation cannot express "one of these two", and marking either
        box required makes the picker unusable on its own.
      */
      if (typedName && !typedEmail) {
        setError(
          `“${typedName}” has no address beside it. Fill in “Their Gmail ID”, or clear the name — this register identifies a designer by their Gmail address, and a name on its own cannot be recorded.`
        );
        setSaving(false);
        return;
      }
      const everybody = [...picked, ...(typedEmail ? [{ name: typedName, email: typedEmail }] : [])];
      if (everybody.length === 0) {
        // SAID HERE RATHER THAN LEFT TO THE 422. The server refuses a body with no designer, but
        // its sentence is about `designerEmail` being required — a field name, on a screen that no
        // longer has a box called that. This one names the two controls that are on it.
        setError(
          "This order names nobody. Tick a designer above, or type the name and Gmail address of the designer it was issued to."
        );
        setSaving(false);
        return;
      }
      const [first, ...rest] = everybody;
      const created = await recordSanctionOrder({
        sanctionOrderNo: String(form.get("sanctionOrderNo") ?? "").trim(),
        sanctionOrderDate: String(form.get("sanctionOrderDate") ?? ""),
        // A STRING, START TO FINISH. The box is `inputMode="decimal"` and not `type="number"` for
        // the reason the registry's MONEY control is a separate control: a number input eats a
        // trailing zero and normalises what the officer typed, on a figure that has to match a
        // ministry document to the paisa.
        sanctionAmount: String(form.get("sanctionAmount") ?? "").trim(),
        // THE LEAD IS TWO SCALARS AND THE REST ARE A LIST, which is the shape the server's body
        // has always had — a one-designer order is byte-for-byte the request this form used to
        // send. `namedDesignerTeam` decided which of them is first; this only spells it.
        designerName: first!.name || first!.email,
        designerEmail: first!.email,
        coDesigners: rest.map((person) => ({ name: person.name || person.email, email: person.email })),
        notes: String(form.get("notes") ?? "").trim() || null
      });
      formRef.current?.reset();
      setChosen([]);
      setLead("");
      /*
        ONE PANEL PER ACCOUNT THIS ORDER MINTED, AND NOT ONE PER ORDER.

        A link is shown once and nothing can show it again — the server stores only a SHA-256
        digest. An order naming four designers that created four accounts and surfaced one link
        would leave three of them unable to sign in, with nothing on any screen saying so. So the
        list is drawn in full, and `credentialLink` (the lead's, kept on the wire for the tests and
        clients that read it) is deliberately NOT what this branches on.
      */
      const links = created.credentialLinks ?? [];
      // THE ORDER IS ATTACHED HERE AND IS NOT ON THE WIRE ENTRY, deliberately: it is the same order
      // for every entry, and repeating a whole `SanctionOrder` per designer on a 201 that may carry
      // a hundred of them would be a payload that grows with the square of nothing useful. The panel
      // needs it because `sanctionMessageFor` names the order the link belongs to.
      const issuedNow = links
        .filter((entry) => entry.link !== null)
        .map((entry) => ({
          order: created.sanctionOrder,
          link: entry.link!,
          designerName: entry.designerName,
          signInEmail: entry.signInEmail
        }));
      if (issuedNow.length > 0) {
        setIssuedLinks(issuedNow);
        setCopied(null);
      }
      const problems = links.map((entry) => entry.problem).filter(Boolean) as string[];
      if (problems.length > 0) {
        setLinkNotice(problems.join(" "));
      } else if (issuedNow.length === 0) {
        const names = created.sanctionOrder.designers.map((designer) => designer.designerName);
        setLinkNotice(
          `${names.join(", ") || "That designer"} already ${names.length === 1 ? "has an account" : "have accounts"} — they sign in as they always do, and the workshop is on their list now.`
        );
      }
      setPage(1);
      await refresh();
      /*
        THE BADGE, WHICH WOULD OTHERWISE GO ON CLAIMING THE OLD NUMBER FOR UP TO A MINUTE.

        `useAwaitingSanctionCount` refreshes on the two events that mean "this officer has come back
        to the app" — a mount with a stale value, and the tab regaining focus — and neither of them
        fires here: the officer never left. Recording an order creates exactly one more workshop with
        nothing filled in yet, so "awaiting" is one higher the instant this resolves.

        AWAITED DELIBERATELY, AFTER `refresh()` RATHER THAN INSTEAD OF IT. The hook dedupes by module
        state and caps itself at one request per 60s per tab, so this is not a second list read; and
        putting it after the register's own refresh means the count is re-asked against a server that
        has already committed the order.
      */
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
        description="The ministry's sanction register — number, date, amount and the designers it names. Recording one opens the workshop, creates any account it needs and issues the first sign-in links."
        icon={<FileSignature className="h-5 w-5" aria-hidden />}
        actions={
          <>
            {/*
              ⚠ A BEARER FETCH AND NOT AN `<a href>`. Every arm of this prefix is gated, the
              pro-forma included, so a plain link sends no Authorization header and an officer gets
              a 401 where they expected a download. The failure is reported rather than swallowed:
              a download button that does nothing, silently, is the one outcome that leaves an
              officer typing orders into a sheet of their own invention.
            */}
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

      {/*
          ONE PANEL PER LINK. A link is shown once and nothing can show it again — the server keeps
          only a SHA-256 digest — so an order that minted four accounts draws four panels. Each names
          the designer it belongs to AND the address they sign in with, which for a dotted or
          `+tagged` Gmail is NOT the address on the register. Four panels that all said "the
          designer" would be four credentials an officer cannot tell apart.
      */}
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
              <button
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
              </button>
            </div>

            {/* THE PREWRITTEN MESSAGE — the one thing the users screen does not have, and the reason
                it is here is that on this screen the officer IS the transport. Composing this
                sentence fifteen times on a Monday morning is where the sign-in address gets left out
                and the designer cannot get in. `sanctionMessageFor` words it once, and it is handed
                the SIGN-IN address rather than the register's. */}
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

            {/* NOT DECORATION. Nothing can show this link again, and no email has been sent — saying
                so is the difference between an officer who forwards it and a designer who waits for a
                message that is never coming. */}
            <p className="text-xs leading-5 text-ink-500">
              Nothing has been emailed — send this yourself, by whatever you already use. The link is
              shown once, works once, and expires {formatLinkExpiry(entry.link.expiresAt)}.
            </p>
          </div>
        );
      })}

      {/* ── THE BULK IMPORT, WHICH IS TWO STEPS AND NEVER ONE ─────────────────────────────────── */}
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
                // THE ROWS THAT COULD NOT TRAVEL. A row whose date or amount was unreadable has no
                // legal shape in a confirm row, so it is not sent — and without this count the
                // report's `rowsRead = recorded + skipped + refused` would silently stop summing on
                // a panel whose whole value is that an officer can check it.
                refusedBeforeConfirm: preview.refused.length,
                rows
              });
              setPreview(null);
              setImportReport(report);
              setPage(1);
              await refresh();
              // THE SAME HAND-OFF, AND IT MATTERS MORE HERE: an import can record two hundred orders
              // at once, every one of which is a workshop with nothing filled in. A badge left at
              // its old number after that is not stale by one, it is wrong by two hundred.
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

      {/*
        SIX COLUMNS AND NOT FIVE, BECAUSE THE FORM IS NO LONGER FIVE BOXES. The three facts of the
        instrument take two columns each and fill one row exactly; the two designer controls take the
        full width, because a picker squeezed into a fifth of a row is a picker nobody uses. Five
        columns with three fields in them left two visible gaps in the top row of a ministry form.
      */}
      <form ref={formRef} onSubmit={submit} className="panel mb-5 grid gap-3 p-4 md:grid-cols-6">
        <div className="md:col-span-2">
          <Field label="Sanction order number" required>
            <TextInput name="sanctionOrderNo" required maxLength={120} placeholder="SO/2026/42" />
          </Field>
        </div>
        <div className="md:col-span-2">
          <Field label="Sanction date" required>
            <TextInput name="sanctionOrderDate" type="date" required />
          </Field>
        </div>
        <div className="md:col-span-2">
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
        </div>
        {/*
          ── THE TWO HALVES OF "WHO IS THIS ORDER FOR", AND BOTH ARE NEEDED ──────────────────

          The picker offers the designers this platform already knows; the pair of boxes beside it
          takes one it does not. That second case is not an edge — it is the ORDINARY one, and the
          reason recording an order mints an account, an empanelment and a first sign-in link at
          all. A picker alone would be a form that cannot express the commonest thing an officer
          does on this screen.

          NEITHER IS `required` ANY MORE, AND THAT IS THE ONE THING TO BE CAREFUL ABOUT. The
          browser's own validation cannot express "one of these two", so the submit handler checks
          it and says which controls to use. Marking the text box required would have made the
          picker unusable on its own; marking neither and saying nothing would have posted an
          order naming nobody into a 422 about a field this screen no longer has.

          THE HANDLER MAKES TWO CHECKS AND NOT ONE, and the second is the less obvious half: these
          two boxes travel as a PAIR keyed off the address, so a name typed with no Gmail beside it
          used to be dropped in silence whenever a designer was also ticked above. Both refusals are
          argued at the call site in `submit`.
        */}
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
          <Field label="Or a designer who is not on the list yet — their name">
            <TextInput name="designerName" maxLength={160} />
            {/* UNDER THE CONTROL AND NOT IN A `hint` PROP: `Field` in this codebase takes `label`,
                `children` and `required` and nothing else, and the sentence is load-bearing — it is
                what tells an officer that typing an unknown address here is the ORDINARY act rather
                than a workaround. */}
            <p className="mt-1 text-xs leading-5 text-ink-500">
              The order creates their account, empanels them and issues their first sign-in link.
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
                {/*
                  EVERY DESIGNER THE ORDER NAMES, LEAD FIRST AND NEVER RE-SORTED. The three lead
                  scalars are still on the wire and are still correct, but a row that drew only
                  them would be quietly wrong about who a ministry instrument was issued to the
                  moment an order names two people. The server's order is the officer's own.
                */}
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
                  /* WHICH SHEET RECORDED THIS. NULL means "typed on this form", permanently —
                     not a value waiting to be backfilled — so the line is absent rather than
                     saying "typed by hand", which would be a claim about every row predating the
                     importer that nothing checked. */
                  <p className="text-xs leading-5 text-ink-500">
                    Imported from {order.sourceFilename}
                    {order.sheetRow != null ? `, row ${order.sheetRow}` : null}
                  </p>
                ) : null}
                <p className="text-sm text-ink-500">
                  <Link
                    href={`/design-workshops/${order.designWorkshopId}`}
                    /* S0's ministry hand-off row for this folder. BOTH HALVES ARE REQUIRED: the
                       ramp is literal and does not invert, so `text-ministry-700` on a dark card
                       is 2.44:1; `dark:text-ministry-300` is 10.06:1. It is body-copy ink on a
                       link and not an action control, so it is inside OQ-2's surface accent —
                       every `.field-button` and `.field-input` on this page stays purple-700. */
                    className="text-ministry-700 underline underline-offset-2 dark:text-ministry-300"
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
                          // THE LEAD, BECAUSE THAT IS WHO THIS ROUTE MINTS FOR. The re-issue arm
                          // reads `SanctionOrder.designerUserId` — the lead scalar — so a
                          // co-designer's link cannot be re-issued from here, and the panel must
                          // not imply otherwise by naming the wrong person. Raising that gap is
                          // this comment's job; closing it is a route change.
                          const first = order.designers[0];
                          setIssuedLinks([
                            {
                              order,
                              link,
                              designerName: first?.designerName || order.designerName,
                              signInEmail: first?.signInEmail || order.designerEmail
                            }
                          ]);
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
