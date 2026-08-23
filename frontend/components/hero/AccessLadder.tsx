"use client";

import { motion, type Variants } from "framer-motion";

import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";
import { ROLE_LABELS, ROLES_BY_RANK } from "@/lib/permissions";
import type { UserRole } from "@/lib/types";

/**
 * What each tier ADDS, keyed by role, because the ladder is strictly inclusive: every rank inherits
 * everything below it, and a grantable capability can lift a single power for a lower tier without
 * moving them up. The one power that is NOT inherited upwards is running a design & prototype
 * workshop, and the section says so in its own paragraph below.
 *
 * TWO OF THESE LINES WERE ON THE WRONG ROWS, and it was a statement about who may create records —
 * which is the difference between the two tiers most visitors are choosing between. Field
 * Contributor was credited with "create artisans, products, processes and tools" and Researcher with
 * "review the work of field contributors and volunteers"; the predicates say the opposite way round.
 * `canCreateRecords` is `hasRank(user, "RESEARCHER")` and `canReview` is
 * `hasRank(user, "FIELD_CONTRIBUTOR")`, both in lib/permissions.ts, both mirroring `deps.py` — the
 * two tiers below Researcher POPULATE records rather than open them, which is the entire reason they
 * exist. docs/PERMISSIONS.md records that README.md, SECURITY.md and RESEARCHER_GUIDE.md all made
 * this same mistake once; the landing page was still making it.
 *
 * THE ROWS THEMSELVES ARE NO LONGER WRITTEN HERE, AND THAT IS THE POINT OF THIS REWRITE. This file
 * used to hold a literal array of six `{ role, adds }` objects whose header claimed "the exact
 * labels of ROLE_LABELS in lib/permissions.ts" — and it had no Designer row, on the marketing page
 * for a product whose primary user is a designer. A hand-copied list cannot be wrong about the
 * labels for long without somebody noticing; it can be wrong about the MEMBERSHIP for months,
 * because nothing is missing from the screen, there is simply one fewer row than there are roles.
 *
 * A ROLE LADDER IS AN ACCESS-CONTROL ARTEFACT, NOT A MARKETING LIST. It is the public statement of
 * who may do what, and the repository's own permission matrix did not cover DESIGNER in its
 * LADDER-WIDE tests until 2026-08-22 — `ALL_ROLES` in `backend/tests/test_permission_matrix.py` was
 * a six-entry tuple, though the `BELOW_ADMIN` block in the same file always drove the tier. Stated
 * that narrowly on purpose: overstating a coverage gap is the same defect as understating one. A
 * page that tells a reader there are six tiers is how the seventh keeps getting forgotten.
 *
 * So the ORDER and the NAMES come from `ROLES_BY_RANK` / `ROLE_LABELS`, the COUNT in the heading and
 * the `aria-label` is spelled from `TIERS_LOW_TO_HIGH.length` (see `NUMBER_WORD`), and only the
 * sentence of copy for each tier lives here. Typed as `Record<UserRole, string>`, an eighth tier
 * added to `UserRole` fails `tsc` in this file until somebody writes its line.
 *
 * THE FIRST DRAFT OF THIS REWRITE DERIVED THE ROWS AND LEFT THE COUNT WRITTEN OUT BY HAND, in the
 * heading and the `aria-label`, under this same paragraph promising the miscount was now a build
 * error. It was not: writing the one missing `TIER_COPY` line would have satisfied `tsc` and shipped
 * a headline reading "Seven tiers" over eight rows — which is, word for word, the defect this file
 * was opened to fix ("Six tiers, and each one inherits the last."). `lib/permissions.ts` carries a
 * correction for the same genre one directory away: a hand-check dressed as a guarantee is worse
 * than no claim, because the next reader stops checking.
 *
 * WHAT THE IMPORT COSTS THIS PUBLIC PAGE, MEASURED RATHER THAN GUESSED. Deriving from
 * `@/lib/permissions` puts that module in the landing page's client bundle for the first time — every
 * other importer of it sits under `app/(protected)/`. `next build` (Next 16.2.9, Turbopack), then
 * reading the chunk list out of the prerendered `.next/server/app/index.html`: the page references 16
 * chunks, and one of them is the 20,177-byte chunk that holds this module — ROUTE_GUARDS' fifteen
 * user-facing refusal `message` strings included, none of which the ladder reads. (That figure
 * counts `message:` keys, NOT routes — three of the eighteen guards share one message through the
 * `RECORD_CREATOR_GUARD` spread. It said fifteen while there were fourteen until 2026-08-23, when
 * the `/sketches-and-prototypes` guard made it true by accident. Nothing pins it, so read it as the
 * order of magnitude the byte count rests on rather than as a current count.) So the module is
 * NOT tree-shaken down to the three consts used here. Against the 870,866 uncompressed bytes summed
 * over the 15 of those chunks present on disk when this was measured, it is about 2%.
 *
 * IT IS STILL WORTH IT, AND THE OBVIOUS SPLIT IS NOT FREE. Lifting `ROLE_RANK` / `ROLE_LABELS` /
 * `ROLES_BY_RANK` into a small module that `permissions.ts` re-exports would trade the 20 KB for a
 * broken guard: `docs/tools/check-docs.mjs::checkRoleParity` reads `frontend/lib/permissions.ts` off
 * disk and FAILS if it cannot find a `ROLE_RANK` block in that file's own text, which is the check
 * that keeps this ladder in step with `deps.py`. Twenty kilobytes on a marketing page against a
 * rendered access-control ladder that cannot silently lose a tier is not a close call; if it ever
 * becomes one, move the declarations AND teach that checker where they went, in the same change.
 */
const TIER_COPY: Record<UserRole, string> = {
  CROWDSOURCE_VOLUNTEER: "Take interviews, upload media, comment on records.",
  FIELD_CONTRIBUTOR:
    "Fill in and correct existing records, and review a volunteer's work. Cannot open a new record.",
  RESEARCHER:
    "Create artisans, products, processes, tools and interviews; edit your own and review the tiers below.",
  DESIGNER:
    "Run design & prototype workshops and sign the report — the stages, the custom sections, the AI layers, the exports.",
  PROFESSOR: "Crafts, workshops, the questionnaire builder, promotions, full dataset download.",
  ADMIN: "Settings hub, task assignment, workshop access grants, accounts.",
  MASTER_ADMIN: "Everything, plus managed API keys and global app settings."
};

/**
 * Lowest tier first, which is the reading order of a ladder. `ROLES_BY_RANK` is highest-first and is
 * a module-level array shared with the pickers, so it is COPIED before reversing — `.reverse()`
 * mutates in place, and reversing the export itself would silently flip every role picker in the
 * signed-in app the moment this prerendered page's module was evaluated.
 */
const TIERS_LOW_TO_HIGH: UserRole[] = [...ROLES_BY_RANK].reverse();

/**
 * The rung's length and opacity ramp across however many tiers there are, rather than off fixed
 * per-step increments. The old literals (`2 + index * 1.6`rem, `0.35 + index * 0.13`) were tuned so
 * the sixth and last row landed exactly on 10rem and opacity 1; with a seventh row they run past
 * both, and since CSS clamps opacity at 1 the top two rungs would have rendered identically — the
 * gradient that carries the whole "rank" reading would stop one row short of the top.
 */
const RUNG_SPAN = Math.max(TIERS_LOW_TO_HIGH.length - 1, 1);

/**
 * The count, spelled. Prose wants a word rather than a digit, so the word is looked up from the
 * derived length instead of typed into the heading — and the lookup FALLS BACK to the numeral rather
 * than to a stale word, so an unmapped count renders "8 tiers" and never the wrong word. There is
 * deliberately no `as const` cast here: casting the length to a key of a literal map would make the
 * heading depend on a hand-kept type again, which is the thing this file keeps getting wrong.
 *
 * ONE CONSEQUENCE FOR WHOEVER GREPS THE BUILD: interpolating the word makes React emit it as its own
 * text node, so the prerendered HTML reads `Seven<!-- --> tiers, and each one inherits the last.` and
 * a search for the phrase as one string finds nothing. The page is unchanged for a reader.
 */
const NUMBER_WORD: Record<number, string | undefined> = {
  1: "One",
  2: "Two",
  3: "Three",
  4: "Four",
  5: "Five",
  6: "Six",
  7: "Seven",
  8: "Eight",
  9: "Nine",
  10: "Ten",
  11: "Eleven",
  12: "Twelve"
};
const TIER_COUNT = TIERS_LOW_TO_HIGH.length;

/**
 * EXPORTED because the same count is spoken twice on this one page: this section's heading, and the
 * `ShieldCheck` trust badge at the top of `HeroLanding`. Two hand-written counts on one page do not
 * rot together — the ladder would say seven while the badge above it said six, which is worse than
 * either being wrong alone, because a visitor can see both at once.
 */
export const TIER_COUNT_WORD = NUMBER_WORD[TIER_COUNT] ?? String(TIER_COUNT);

/**
 * The access ladder as a diagram rather than a paragraph: one row per tier whose accent bar
 * lengthens with rank, so the inclusive shape of the hierarchy is legible before a word is read.
 * Purple only — the ladder is a system diagram, and gold stays on the hero and auth surfaces.
 */
export default function AccessLadder() {
  const reduce = useHeroReducedMotion();

  const container: Variants = {
    hidden: {},
    show: { transition: { staggerChildren: reduce ? 0 : 0.07 } }
  };
  const item: Variants = {
    hidden: { opacity: 0, x: -12 },
    show: { opacity: 1, x: 0, transition: { duration: reduce ? 0 : 0.45, ease: [0.16, 1, 0.3, 1] } }
  };

  return (
    <section
      id="access"
      className="mx-auto max-w-6xl px-6 py-24"
      aria-label={`The ${TIER_COUNT_WORD.toLowerCase()}-tier access ladder`}
    >
      <motion.div initial="hidden" whileInView="show" viewport={{ once: true, amount: 0.2 }} variants={container}>
        <motion.p variants={item} className="eyebrow mb-3">
          Access is a ladder
        </motion.p>
        <motion.h2
          variants={item}
          className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl"
        >
          {TIER_COUNT_WORD} tiers, and each one inherits the last.
        </motion.h2>
        {/* THE LADDER IS THE SECOND GATE, AND SAYING SO IS THE POINT OF THE FIRST SENTENCE. It read
            "New accounts start at the bottom and are raised by an admin", which described the whole
            of admission when signing in was open to anyone who could authenticate. It is now the
            second half of a two-step: the allow-list decides whether an address may sign in at all,
            and only then does the ladder decide what it may do. A reader who knows only the ladder
            reads a refusal at the door as a bug. */}
        <motion.p variants={item} className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          An administrator admits your address to the platform first; the ladder then decides what
          the account may do. Newly admitted accounts start at the bottom unless the admitting
          administrator chose otherwise, and are raised by an admin from there. Individual
          capabilities — dataset download, review, craft and workshop creation, the questionnaire
          builder — can also be granted one at a time, without moving anyone up the ladder.
        </motion.p>
        {/* THE ONE PLACE THE LADDER IS NOT A LADDER, said here because the heading above promises
            that every tier inherits the last. Running a design & prototype workshop is decided by a
            SET — designer, admin, master admin — and not by rank, so a professor outranks a designer
            and still cannot run one. A visitor who reads only the inheritance rule concludes their
            professor account covers it, and finds out at a refusal. `canRunDesignWorkshops` in
            lib/permissions.ts carries the same set, mirroring `can_run_design_workshops`. */}
        <motion.p variants={item} className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
          One power is an exception. Running a design &amp; prototype workshop belongs to designers,
          admins and the master admin specifically — not to everyone above a rank — because a
          workshop is a fortnight of a named designer&apos;s work ending in a report submitted under
          their name, and outranking a designer is not the same as being one.
        </motion.p>

        <ol className="mt-12 space-y-2.5">
          {TIERS_LOW_TO_HIGH.map((role, index) => (
            <motion.li
              key={role}
              variants={item}
              className="flex items-center gap-4 rounded-md border border-line-200 bg-card p-4 shadow-sm transition hover:shadow-md sm:gap-5"
            >
              <span
                aria-hidden
                className="grid h-8 w-8 shrink-0 place-items-center rounded-md bg-purple-50 font-display text-sm font-bold text-purple-700"
              >
                {index + 1}
              </span>
              <div className="min-w-0 flex-1 sm:flex sm:items-baseline sm:gap-5">
                <h3 className="font-display text-sm font-bold text-ink-900 sm:w-52 sm:shrink-0">{ROLE_LABELS[role]}</h3>
                <p className="mt-1 text-sm leading-relaxed text-ink-500 sm:mt-0">{TIER_COPY[role]}</p>
              </div>
              {/* The rung: width tracks rank, so the ladder reads as a shape as well as a list. */}
              <span
                aria-hidden
                className="hidden h-1.5 shrink-0 rounded-full bg-purple-700 lg:block"
                style={{ width: `${2 + (index * 8) / RUNG_SPAN}rem`, opacity: 0.35 + (index * 0.65) / RUNG_SPAN }}
              />
            </motion.li>
          ))}
        </ol>
      </motion.div>
    </section>
  );
}
