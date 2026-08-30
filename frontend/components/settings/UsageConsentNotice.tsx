"use client";

/**
 * THE RECORDING NOTICE — the whole text a person is agreeing to, expandable in place.
 *
 * ── WHY ONE COMPONENT, RENDERED ON TWO VERY DIFFERENT SCREENS ───────────────────────────────────
 *
 * The sign-in card at `/login` and the consent card on `/settings` show the SAME text, and they
 * have to, because they are two views of one decision: the turnstile that asks and the door that
 * lets you take it back. Two components would be two copies of the ordering, the emphasis and the
 * headings — and the moment one of them was edited, a person would have agreed to one description
 * of this system and be reading a different one when they came to withdraw. So there is one, and it
 * lives beside the settings card because that is where its second consumer is; `app/login/page.tsx`
 * imports it across, which is deliberate and is noted at the import.
 *
 * **AND NOT ONE WORD OF THE TEXT IS WRITTEN HERE.** Every sentence comes off
 * `GET /api/usage/consent/notice` and is rendered verbatim, in the order the server sent it. That
 * order is not cosmetic: what is collected, then what is not, then — before anything else — that
 * agreeing is REQUIRED, because a person who reads two paragraphs of reassurance and then discovers
 * the choice was not a choice has been handled rather than asked. The server computes the "collects"
 * list from the policy actually in force, so a deployment that changed what it records publishes a
 * changed notice on the same deploy. Writing this copy in TSX (and again in Kotlin on the handset)
 * is how one decision comes to be described two ways, and here that would not be an inconsistency —
 * it would be two different consents. `usage.NOTICE_VERSION` travels with the text and is sent back
 * with the answer, so the record says which words were on screen.
 *
 * ── THE EXPANDABLE REGION, AND WHY IT IS NOT `components/ui/Accordion` ──────────────────────────
 *
 * `Accordion` is a `.panel mb-5` section with an 18px display heading — correct on a settings page,
 * far too heavy inside a 448px glass sign-in card, and it publishes no `aria-controls` at all. What
 * this needs is smaller and stricter: a real `<button>` carrying `aria-expanded` and, WHILE THE
 * PANEL IS MOUNTED, `aria-controls` pointing at it (the attribute referencing an element that is not
 * in the document is worse than its absence — it promises a relationship a screen reader then cannot
 * follow). Ids come from `useId` so two of these on one page cannot collide.
 *
 * **IT IS NOT A LINK AWAY, AND THAT IS THE REQUIREMENT.** A notice behind a link is read by nobody;
 * worse, on the sign-in screen it would be a link that navigates away from a half-typed password.
 * The text expands in place, under the checkbox, and the page stays where it was.
 */

import { useId, useState } from "react";
import { ChevronDown, ShieldQuestion } from "lucide-react";

import type { UsageConsentNotice } from "@/lib/usage";

/**
 * One titled block of the notice. A heading and a list, or a heading and a paragraph.
 *
 * `<h3>` throughout rather than a level chosen per caller: on the sign-in card the `<h1>` is
 * "Welcome back" and this sits two levels under it; on the settings card the panel's own `<h2>` is
 * directly above. One level, correct in both, and never `<h2>` — that would tie the settings card
 * with its own heading and make the outline read as two sibling sections.
 */
function Block({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="grid gap-1.5">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-ink-500">{title}</h3>
      {children}
    </div>
  );
}

function Lines({ items }: { items: string[] }) {
  return (
    <ul className="grid list-disc gap-1.5 pl-5 text-sm leading-6 text-ink-700">
      {items.map((line) => (
        <li key={line}>{line}</li>
      ))}
    </ul>
  );
}

/**
 * The notice itself, in the server's order and nothing else.
 *
 * The one piece of chrome added is the amber band around `requiredSentence`. That is not decoration
 * and it is not the message either — the sentence says in words that agreeing is a condition of
 * access, and the band exists so a person skimming does not reach the checkbox having read only the
 * reassuring half. Colour never carries a fact here that the text does not already carry.
 */
export function UsageConsentNoticeBody({ notice }: { notice: UsageConsentNotice }) {
  return (
    <div className="grid gap-4">
      <Block title="What is recorded">
        <Lines items={notice.collects} />
      </Block>

      <Block title="What is never recorded">
        <Lines items={notice.doesNotCollect} />
      </Block>

      {/* THE REQUIRED SENTENCE, THIRD, EXACTLY WHERE THE SERVER PUT IT. Not buried at the bottom and
          not implied by a disabled button — a greyed-out control is a fact about a widget, and the
          fact a person needs is about their choice. */}
      <p className="rounded-md border border-amber-500/40 bg-amber-100 px-3 py-2.5 text-sm font-medium leading-6 text-amber-900">
        {notice.requiredSentence}
      </p>

      <Block title="What “duration” is not">
        <p className="text-sm leading-6 text-ink-700">{notice.durationCaveat}</p>
      </Block>

      <Block title="Who can read it">
        {/* Keyed by route and rendered as the server sent it. A read route missing from this map
            would make the notice false for everybody who has already answered, which is why the
            server walks its own router against this dict in a test rather than trusting a comment. */}
        <dl className="grid gap-1.5 text-sm leading-6">
          {Object.entries(notice.readableBy).map(([route, who]) => (
            <div key={route} className="grid gap-0.5 sm:grid-cols-[minmax(0,14rem)_1fr] sm:gap-3">
              <dt className="font-mono text-xs text-ink-500">{route}</dt>
              <dd className="text-ink-700">{who}</dd>
            </div>
          ))}
        </dl>
      </Block>

      <Block title="Taking it back">
        <p className="text-sm leading-6 text-ink-700">
          <span className="font-medium text-ink-900">{notice.withdrawal.where}</span> {notice.withdrawal.costsNothing}
        </p>
        <Lines items={notice.withdrawal.does} />
        <Lines items={notice.withdrawal.doesNot} />
      </Block>

      <Block title="How long it is kept">
        <p className="text-sm leading-6 text-ink-700">{notice.retention}</p>
      </Block>

      <p className="text-xs leading-5 text-ink-500">
        Version <span className="font-mono">{notice.version}</span>. The answer you give is stored against this exact
        version, so a record always says which words were on screen. Full argument:{" "}
        <span className="font-mono">{notice.document}</span>.
      </p>
    </div>
  );
}

/**
 * The disclosure: a button that expands the notice in place.
 *
 * `defaultOpen` exists for the settings card, where the notice is the point of the panel and there
 * is nothing else competing for the space. On the sign-in card it stays collapsed — a 400-word
 * legal text unfurled over a password form is how a person learns to scroll past it.
 */
export function UsageConsentDisclosure({
  notice,
  defaultOpen = false,
  tone = "card"
}: {
  notice: UsageConsentNotice;
  defaultOpen?: boolean;
  /** `card` on a plain panel; `inset` inside the glass sign-in card, where the notice needs its own
   *  surface to separate it from the form it is sitting on. */
  tone?: "card" | "inset";
}) {
  const [open, setOpen] = useState(defaultOpen);
  const regionId = useId();
  const buttonId = useId();

  return (
    <div className={tone === "inset" ? "rounded-md border border-line-200 bg-surface-50" : ""}>
      <button
        type="button"
        id={buttonId}
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        // ONLY WHILE THE PANEL IS MOUNTED. The region below is `{open ? … : null}`, and
        // `aria-controls` pointing at an id that is not in the document promises a relationship a
        // screen reader then cannot follow — which is worse than not claiming one.
        aria-controls={open ? regionId : undefined}
        className="flex w-full items-center gap-2 rounded-md px-3 py-2.5 text-left text-sm font-medium text-purple-700 transition hover:bg-purple-50"
      >
        <ShieldQuestion className="h-4 w-4 shrink-0" aria-hidden />
        <span className="min-w-0 flex-1">
          {open ? "Hide what is recorded" : "Read what is recorded, and what is not"}
        </span>
        <ChevronDown className={`h-4 w-4 shrink-0 transition-transform ${open ? "rotate-180" : ""}`} aria-hidden />
      </button>
      {open ? (
        <div
          id={regionId}
          role="region"
          aria-labelledby={buttonId}
          className="border-t border-line-200 px-3 py-3.5"
        >
          <UsageConsentNoticeBody notice={notice} />
        </div>
      ) : null}
    </div>
  );
}
