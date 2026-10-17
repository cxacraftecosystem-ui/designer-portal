"use client";

import Link from "next/link";
import { motion, type Variants } from "framer-motion";
import { ChevronDown } from "lucide-react";

import { TIER_COUNT_WORD } from "@/components/hero/AccessLadder";
import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";

/**
 * The tier count, mid-sentence.
 *
 * TWO OF THESE ANSWERS TYPED "eight-tier" BY HAND while the badge at the top of the page and the
 * ladder's own heading interpolated the derived constant — so a ninth role would have moved two of
 * the four and left this section contradicting the section it is describing, on one page, to one
 * reader. That is the identical defect `AccessLadder` carries a paragraph about and the identical
 * one `permissions.ts:220` records costing twenty-two hand-kept copies of the ladder a correction
 * when INSPECTOR landed. The constant already existed and was already exported for exactly this.
 *
 * `.toLowerCase()` rather than a second export, matching what `AccessLadder.tsx:168` does for its
 * own `aria-label`: the word is capitalised where it opens a heading and lower-cased where it sits
 * inside a sentence, and one constant with a call site's own casing beats two constants that can
 * disagree.
 */
const TIER_COUNT_WORD_LOWER = TIER_COUNT_WORD.toLowerCase();

/**
 * THE THREE WORKSHOP ANSWERS ARE NEW, and they are here because this list is where a reader goes
 * with the objection a marketing section cannot answer without becoming defensive. All three were
 * checked against the code rather than written from the brief:
 *
 *  - who may start one: `DESIGN_WORKSHOP_CREATOR_ROLES` is `["ADMIN", "MASTER_ADMIN"]` and
 *    `DESIGN_WORKSHOP_ROLES` is `["DESIGNER", "ADMIN", "MASTER_ADMIN"]` in `lib/permissions.ts`,
 *    mirroring `deps.py`. The wording follows `DESIGN_WORKSHOP_CREATE_REFUSAL` in that file, which
 *    is the sentence a designer actually reads when they try — a FAQ that answered this differently
 *    from the product would be the more expensive kind of wrong.
 *  - what "offline" means for a workshop: `lib/designWorkshopStore.ts` (drafts and media blobs in
 *    IndexedDB, "all 22 stages … in a shape the app can READ BACK") and `WorkshopDraftStore.kt`;
 *    the report half is `android/…/report/ReportExport.kt` ("entirely offline") against the browser,
 *    whose preview and download are both API calls.
 *  - what refuses a submit: `api/routes/design_workshops.py`'s `submit=true` refuses ONE stage, and
 *    `app/(protected)/design-workshops/[id]/readiness/page.tsx` states in as many words that
 *    unfilled Basic fields "do NOT refuse the workshop's status".
 *
 * The existing eight answers are untouched.
 */
const FAQS = [
  {
    q: "Who can sign in?",
    a: "Only addresses an administrator has approved. Anyone else can request access when they sign in."
  },
  {
    q: "I signed in with Google and was told I need approval. Why?",
    a: "Google sign-in uses the same approved list. You can sign in once an administrator approves you."
  },
  {
    q: "What happens to my recordings?",
    a: "They upload to private storage and are transcribed and translated into English. Each transcript is linked to its artisan, craft and workshop."
  },
  {
    q: "How does review work?",
    a: "Each role reviews the roles below it. A reviewer approves, rejects, or sends a record back with comments."
  },
  {
    q: "Who can download the data?",
    a: "Professors and above. Others need a download grant or a share from the record's owner."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "Take interviews, upload media and comment on records. Creating artisans, products, processes and tools starts at Researcher."
  },
  {
    q: "What is a design & prototype workshop?",
    a: `A sanctioned fortnight in a craft cluster, recorded as ${STAGE_COUNT_WORD_LOWER} stages — from setup and market survey to prototypes, costing and follow-up. The stages are the report, so there is no writing-up afterwards.`
  },
  {
    q: "Who can start a design workshop, and who does the work inside it?",
    a: "Admins start one. Designers, ministry officers and admins work inside it — designers, ask an admin to add you."
  },
  {
    q: "Will an unfilled field stop me submitting a workshop?",
    a: "No. You submit when you're ready; a readiness screen lists what's still missing, even offline."
  },
  {
    // AMENDED, and the amendment is a correction rather than an addition. The last sentence read
    // "The web portal complements it for review, browsing, and administration", which implied the
    // browser needs a connection. It has not since `lib/designWorkshopStore.ts` landed: drafts and
    // media blobs live in IndexedDB and all the stages render from a cached registry. Leaving the
    // old sentence beside a new section about offline workshops would have made the page contradict
    // itself in two places a reader can reach from the same scroll.
    /*
      EXTENDED, and every clause is narrower than the obvious version of itself.

      "EDITS AS WELL AS NEW RECORDS" — `lib/offline.ts` types its queue `"POST" | "PATCH"`, so a
      correction made with no signal is banked like a create. The trade is stated because that file
      states it: an offline edit replays the whole record, so a colleague's later change to the same
      row is overwritten. Naming the cost is the difference between a feature and a surprise.

      "THE QUESTIONNAIRE ROW, NOT ITS QUESTIONS" — `questionnaires/page.tsx:201` banks the create
      through `saveOrQueue`, and its own comment is emphatic that this is a decision rather than a
      first instalment: a section and a question are separate creates that need the server-minted id
      this row does not have yet. "The form exists, named, attached to its workshop" is that comment
      near enough verbatim. Writing "create a questionnaire offline" flat would promise the tree.

      "OPEN … ANSWERING IS STILL REFUSED" — `lib/questionnaireFormCache.ts` is a READ cache and says
      so in capitals ("DO NOT ADD A WRITE QUEUE TO IT"). The refusal to save answers offline is
      deliberate and load-bearing: a question can be retired between opening the screen and pressing
      Save, so a queued batch would be either refused later — losing a sitting the designer believed
      was recorded — or re-attached to whatever wording replaced it, which fabricates evidence.

      "REFUSED ENTRIES ARE LISTED WITH THEIR REASONS" — the handset's `OfflineOutboxTray.kt`. Before
      it, a refusal was a one-shot toast, `syncOutbox` skipped that entry forever, and the banner
      still said "uploading": a permanent dead end that looked like progress.
    */
    q: "Does it work offline?",
    a: "Yes. The Android app and the web portal both record workshops and build reports offline, and sync when you're back online. Recording questionnaire answers needs a connection."
  },
  {
    q: "What about privacy?",
    a: `Access follows ${TIER_COUNT_WORD_LOWER} roles, every edit is audited, and media is private. Aadhaar numbers show as XXXX XXXX 9012 everywhere they are shared or exported.`
  }
];

/**
 * Marketing FAQ — native <details>/<summary> accordion styled to the tokens,
 * so it works with zero JavaScript and no extra dependencies.
 */
export default function HeroFAQ() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.06 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 14 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section id="faq" className="mx-auto max-w-3xl px-6 py-24" aria-label="Frequently asked questions">
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3 text-center">
          Questions
        </motion.p>
        <motion.h2
          variants={item}
          className="text-center font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          Answered before you ask.
        </motion.h2>

        <motion.div variants={item} className="mt-10 rounded-lg border border-line-200 bg-card px-6 shadow-sm">
          {FAQS.map((faq) => (
            <details key={faq.q} className="group border-b border-line-200 last:border-b-0">
              <summary className="flex cursor-pointer list-none items-center justify-between gap-4 py-5 font-display text-base font-semibold text-ink-900 transition hover:text-purple-700 [&::-webkit-details-marker]:hidden">
                {faq.q}
                <ChevronDown
                  className="h-4 w-4 shrink-0 text-ink-500 transition-transform duration-200 group-open:rotate-180"
                  aria-hidden
                />
              </summary>
              <p className="pb-5 text-sm leading-relaxed text-ink-700">{faq.a}</p>
            </details>
          ))}
        </motion.div>

        <motion.p variants={item} className="mt-6 text-center text-sm text-ink-500">
          Still unsure where to start?{" "}
          <Link href="/guide" className="font-medium text-purple-700 dark:text-purple-300 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
