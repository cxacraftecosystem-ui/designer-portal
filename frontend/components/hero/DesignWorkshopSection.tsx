"use client";

import { motion, type Variants } from "framer-motion";
import { Plus } from "lucide-react";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import {
  arcRangeLabel,
  STAGE_COUNT_WORD,
  STAGE_COUNT_WORD_LOWER,
  WORKSHOP_ARC
} from "@/components/hero/workshopArc";

/**
 * The half of this product the landing page used to omit: it does not only hold records OF craft
 * work, it RUNS a design & prototype development workshop, stage by stage, for a fortnight.
 *
 * WHY THIS SECTION EXISTS. The headline at the top of this page has always read "The workshop ends.
 * The report is already written." — and everything below it described artisan records, interviews
 * and a review ladder. The page wrote a cheque about a workshop and then spent eight sections
 * cashing a different one. A visitor whose actual job is running a workshop in a cluster could read
 * the whole thing and not learn that the product was for them.
 *
 * WHAT IS CLAIMED HERE, AND WHERE IT WAS CHECKED. Nothing on this page may assert a feature that
 * does not exist, so every sentence below has a file behind it:
 *
 *  - the arc and the stage count: `backend/app/services/stage_definitions.py` (`STAGE_1`…`STAGE_22`,
 *    collected in `ALL_STAGES`). See `workshopArc.ts` for the full title list and for why the count
 *    is derived from the arc rather than typed beside it.
 *  - an admin opens a workshop and a designer runs it: `DESIGN_WORKSHOP_CREATOR_ROLES` is
 *    `["ADMIN", "MASTER_ADMIN"]` and `DESIGN_WORKSHOP_ROLES` is `["DESIGNER", "ADMIN",
 *    "MASTER_ADMIN"]` (`lib/permissions.ts`), mirroring `deps.py`. The refusal a designer actually
 *    reads — `DESIGN_WORKSHOP_CREATE_REFUSAL`, same file — says it in the product's own words:
 *    "Ask an admin to create it for your cluster and give you access."
 *  - saving per stage into a local draft: `lib/designWorkshopStore.ts` — "the header, all 22 stages,
 *    every row of every collection and every photograph, held in this browser in a shape the app can
 *    READ BACK", with the blobs themselves in IndexedDB. Android's is `WorkshopDraftStore.kt`.
 *  - a readiness list rather than a locked submit: unfilled Basic fields refuse ONE STAGE's
 *    `submit=true` check (`api/routes/design_workshops.py`) and explicitly "do NOT refuse the
 *    workshop's status" (`app/(protected)/design-workshops/[id]/readiness/page.tsx`).
 *  - a designer's own sections and questions: `backend/app/services/custom_sections.py` — "one
 *    workshop's own questions, added with no deployment".
 *  - printable cards and tags: `app/(protected)/design-workshops/[id]/codes`, and the join card in
 *    `backend/app/services/design_workshop_grants.py`.
 *
 * MOTION is this page's, not the guide's: `useHeroReducedMotion`, and reduced motion collapses the
 * DURATIONS while `hidden` stays exactly what the server rendered. Hover is CSS so the globals.css
 * reduced-motion rules can neutralise it.
 *
 * COLOUR. Purple only. Gold is allowed on this page but its budget is spent on the hero, the
 * printing bed and the closing band; a fourth gold surface would make the bed stop being the one
 * signature moment. The one visually distinct cell here — a designer's own sections — earns its
 * difference with a dashed line and a purple tint rather than with a second accent.
 */
export default function DesignWorkshopSection() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.045 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, y: 18 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section
      id="workshop"
      className="mx-auto max-w-6xl px-6 py-24"
      aria-label="The design and prototype workshop"
    >
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.15 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          The design &amp; prototype workshop
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-3xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          A fortnight in a cluster, carried from the first survey to a submission-ready document.
        </motion.h2>
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          A design &amp; prototype development workshop is not something you write up afterwards. It
          is {STAGE_COUNT_WORD_LOWER} stages the app walks a designer through while they are still in
          the room — and the stages <em>are</em> the report, so the document is finished at the moment
          the workshop is.
        </motion.p>
        <motion.p variants={item} className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
          An administrator opens the workshop for a cluster and puts the team on it; the designers do
          the work inside it. That division is deliberate rather than an oversight: a workshop ends in
          a document submitted under a named designer&rsquo;s name, and starting one is an
          administrative act.
        </motion.p>

        {/* THE ARC. The same four-up grid as the record-types section above, on purpose: this page
            has one way of saying "here are the parts of a thing", and a second layout for the same
            job would read as a bolt-on. It drops to ONE column on a phone rather than that
            section's two, because these cells carry two sentences where those carry one, and a
            half-width cell of six wrapped lines is a column of text pretending to be a card. The
            range badge is what keeps the order legible once a sequence is laid out as a grid.

            A `ul` AND NOT AN `ol`, WHICH IS THE OPPOSITE OF WHAT AN ARC WANTS, for one reason: the
            last cell is not a position in the sequence. Tailwind's preflight strips list markers, so
            an `ol` never rendered a number here — the ordering is carried entirely by each badge and
            by reading order, and both survive the change, while "item 8 of 8" announced over a cell
            that is not stage 23 does not. The alternative — `ol` wrapped in a grid with
            `display: contents` — costs list semantics outright in at least one screen reader, which
            is a worse trade than the one made here. */}
        <ul className="mt-12 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {WORKSHOP_ARC.map((group) => (
            <motion.li
              key={group.title}
              variants={item}
              className="rounded-lg border border-line-200 bg-card p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 inline-flex h-7 items-center justify-center rounded-md bg-purple-700 px-2.5 font-display text-xs font-bold tabular-nums text-white">
                <span className="sr-only">Stages </span>
                {arcRangeLabel(group)}
              </span>
              <h3 className="font-display text-sm font-bold text-ink-900">{group.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{group.copy}</p>
            </motion.li>
          ))}
          {/* The eighth cell is NOT a stage group, and it has to be impossible to read as one — a
              dashed edge and no shadow, rather than the card treatment the seven share. It is here
              because "add your own question" is the answer to the objection every one of those
              seven invites: my cluster records something yours does not.
              ⚠ The ground stays `bg-card` on purpose. Brand purple NEVER INVERTS (§3.1), so a
              `bg-purple-50` panel is near-white in BOTH themes — and `text-ink-900` is near-white
              in dark, which would have printed this cell white-on-white for every dark-mode
              reader. The only text colours safe on a purple tint are the non-inverting purple
              rungs, which is why `AccessLadder`'s purple-50 badge carries `text-purple-700`. */}
          <motion.li
            variants={item}
            className="rounded-lg border border-dashed border-purple-300 bg-card p-5 transition hover:-translate-y-0.5"
          >
            <span className="mb-4 inline-flex h-7 items-center justify-center rounded-md bg-purple-100 px-2.5 text-purple-700">
              <Plus className="h-4 w-4" aria-hidden />
            </span>
            <h3 className="font-display text-sm font-bold text-ink-900">And your own</h3>
            <p className="mt-1.5 text-sm leading-relaxed text-ink-700">
              Sections and questions a designer adds to their own workshop — no deployment, no
              waiting, and reorderable by arrows or by dragging.
            </p>
          </motion.li>
        </ul>

        {/* The chips echo the "Built on top:" run in the records section. Each one is a mechanism,
            not an adjective — see the header for the file each was read out of. */}
        <motion.div variants={item} className="mt-8 flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-ink-700">
            Across all {STAGE_COUNT_WORD_LOWER} stages:
          </span>
          {[
            "Every stage saves on its own, into a draft on the device",
            // Scored in BOTH places, deliberately, by two ports of one scorer — so "not on the
            // server" would have been wrong. What is true and worth saying is that the device can
            // answer without asking.
            "Completeness scored on the device, not only on the server",
            "A readiness list, not a locked submit",
            "Printable code cards and prototype tags"
          ].map((chip) => (
            <span
              key={chip}
              className="rounded-full border border-line-200 bg-surface-50 px-3 py-1.5 text-xs font-medium text-ink-700"
            >
              {chip}
            </span>
          ))}
        </motion.div>

        {/* WHY THIS SENTENCE IS HERE AND NOT IN THE FAQ. "Submitted when the designer says so" is
            the single most surprising rule in this half of the product, and it is the opposite of
            what a reader assumes from a completeness percentage. Said next to the readiness chip
            above, where the assumption is formed. */}
        <motion.p variants={item} className="mt-8 max-w-2xl text-sm leading-relaxed text-ink-500">
          A workshop is submitted when the designer says it is. An empty field is never what refuses
          it — a readiness screen lists what is still outstanding, ranked, and works from the local
          draft so the question can be asked in the courtyard where the answer changes what happens
          next. The one thing that does refuse is a single stage&rsquo;s own &ldquo;Save and check
          required fields&rdquo;, and it refuses that stage alone. {STAGE_COUNT_WORD} stages of
          nothing is a legitimate record on day one.
        </motion.p>
      </motion.div>
    </section>
  );
}
