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
    a: `Only addresses an administrator has admitted. Signing in — by password or with Google — checks your address against the platform allow-list first; if it is not on the list, no account is created and your request goes to the administrators as a pending approval. Everyone already using the repository when the allow-list was introduced was carried onto it, so nothing changed for existing accounts. Once you are admitted you join the ${TIER_COUNT_WORD_LOWER}-tier ladder, and an admin raises you up it (field contributor, researcher, designer, inspector/reviewer, professor, admin) as your role in the project grows.`
  },
  {
    q: "I signed in with Google and was told I need approval. Why?",
    a: "Because a verified Google address is proof of who you are, not permission to be here. Google sign-in used to create an account for any address that could authenticate; now it is checked against the same allow-list as a password, so an address nobody has admitted gets no account and no token. Your request is queued for an administrator, and you will be able to sign in once they approve it. A refused password and an address awaiting approval are answered differently, so you are never left guessing which of the two you are looking at."
  },
  {
    q: "What happens to my recordings?",
    a: "They upload to secure storage and join the transcription queue, where a chain of three speech-to-text providers with automatic failover transcribes them and translates them into English. The finished transcript is linked back to the artisan, craft, and workshop it belongs to."
  },
  {
    q: "How does review work?",
    a: "Every record enters a peer-review ladder. A reviewer can approve it, reject it, or send it back for revision with mandatory comments — and each tier reviews the work of those ranked below it, with the master admin able to review everyone's."
  },
  {
    q: "Who can download the data?",
    a: "The full dataset opens at Professor and above. Anyone below that needs the dataset-download permission granted explicitly, or a per-record share from the owner. Sharing between researchers is tiered too: download, comment, or edit — requested by one side and granted, changed, or revoked by the other."
  },
  {
    q: "What can a brand-new account actually do?",
    a: "A newly admitted account starts at the bottom of the ladder — Crowdsource Volunteer unless the administrator who admitted it chose a higher tier — and can take interviews, upload media, and comment on existing records. Creating artisans, products, processes and tools begins at Researcher — the two tiers below it fill in records rather than open them, which is deliberate and the thing people are most often surprised by. A Field Contributor adds the extra power of reviewing a volunteer's work. An admin raises the tier when the person's role in the project does."
  },
  {
    q: "What is a design & prototype workshop?",
    a: `A sanctioned fortnight in a craft cluster, recorded as ${STAGE_COUNT_WORD_LOWER} stages: setup, the administrative papers and who is in the room; the cluster's craft background, its traditional process and the products already being made; a market survey and the design direction it produces; a brief, sketches and their shortlisting; prototypes through iteration, testing and validation; the final product documented; costing, packaging and market linkage; outcomes, inspection and closing; then the report, a data-quality pass and the follow-up. The stages are the report — every section of the printed document is one of them — so there is no writing-up phase after the workshop ends.`
  },
  {
    q: "Who can start a design workshop, and who does the work inside it?",
    a: `Admins and the master admin start one; designers, admins and the master admin work inside it. That is a set rather than a rank, which is the one place this app's ladder is not a ladder: a professor outranks a designer and still cannot run a workshop, because the document is submitted under a named designer's name and outranking one is not the same as being one. If you are a designer, ask an admin to create the workshop for your cluster and give you access — you can then fill in all ${STAGE_COUNT_WORD_LOWER} stages, add artisans, products and photographs, and generate the report. Any workshop you already have access to is open to you now.`
  },
  {
    q: "Will an unfilled field stop me submitting a workshop?",
    a: "No. A workshop is submitted when the designer says it is, and an empty field never refuses it — a readiness screen ranks what is still outstanding and links straight to the box holding each gap. It is built from the local draft, so the question can be asked on the last afternoon with no signal. One thing does refuse: a single stage's own “Save and check required fields”, and it refuses that stage alone. Standard- and Advanced-tier fields are depth and block nothing at all, which is deliberate — a thin stage should be a decision rather than an oversight."
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
    a: "The Android app is offline-first — capture interviews, media, and GPS positions with no signal at all, and everything syncs when you are back online. Corrections queue as well as new records, with one trade worth knowing: an offline edit replays the whole record, so a colleague's later change to the same row is overwritten rather than merged. Anything the queue cannot deliver is listed with its own reason and retried one at a time, instead of failing silently behind a banner that still says “uploading”. The handset also builds a workshop's report on itself, from its own draft. The web portal complements it for review, browsing, and administration, and it keeps a design & prototype workshop's draft in the browser too, so its stages can be filled in, scored and checked for readiness with no connection; generating the report is the one part of the web half that needs the server. Creating your own questionnaire works with no signal as far as it honestly can — the form is banked with its name and its workshop and lands the moment there is signal, and its questions are written afterwards. An existing questionnaire opens offline so you can read the questions you are about to ask; recording the answers still needs a connection, deliberately, because a question can be retired between opening the screen and pressing save."
  },
  {
    q: "What about privacy?",
    a: `Access is governed by the ${TIER_COUNT_WORD_LOWER}-tier role ladder, cross-researcher sharing is opt-in per grant, and every edit carries an audited revision history. Media lives in private cloud storage that only signed-in, authorized users can reach. National identifiers are masked wherever a record leaves its owner: an artisan's Aadhaar number is used to make sure the same person documented at two workshops becomes one record, not two, but it renders as XXXX XXXX 9012 on every shared and exported surface — the data browser, CSV, and the .xlsx report — and only the researcher who recorded that artisan, or a professor and above, can read it in full.`
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
          <Link href="/guide" className="font-medium text-purple-700 underline-offset-2 hover:underline">
            The walkthrough
          </Link>{" "}
          covers every screen in the order you will meet them.
        </motion.p>
      </motion.div>
    </section>
  );
}
