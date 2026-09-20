"use client";

/**
 * A MEGA CARD — one collapsible, colour-coded section of a screen that has too many sections.
 *
 * ── WHY THIS EXISTS, IN THE OWNER'S WORDS ──────────────────────────────────────────────────────
 *
 * 2026-09-20: *"Each of the megacard is supposed to be a larger card that would stay minimized unless
 * it is clicked upon, colour code so that it is easier for people to understand and navigate, there
 * should be two cards in a row for a megacard only on the larger screens, and 1 card on mobile
 * screens."* The dashboard grid had reached twenty-one tiles and four headed sections; the headings
 * grouped them but the page was still one column of everything, and a reader looking for one thing
 * scrolled past twenty.
 *
 * ── WHY IT IS NOT `components/ui/Accordion.tsx`, WHICH ALREADY COLLAPSES THINGS ─────────────────
 *
 * `Accordion` is the right primitive for a panel whose contents are expensive and disposable — its
 * own header says so: it UNMOUNTS its children when closed, which is what lets the completion matrix
 * and the questionnaire builder cost nothing while shut. Three things make it the wrong primitive
 * here:
 *
 *   1. **It ships a fixed `.panel mb-5` shell.** A mega card sits in a two-column rail and must not
 *      carry its own bottom margin or the rail's `gap` is applied twice down one column and once
 *      across it.
 *   2. **It has no tone.** Colour coding is the whole point of this component, and bolting a colour
 *      prop onto the app's generic disclosure would spread a dashboard decision to the questionnaire
 *      builder and the workshop panels, which have no groups to colour.
 *   3. **It has no `aria-controls`,** because with the panel unmounted there is nothing for it to
 *      point at. This card keeps the relationship wired while the panel is mounted and drops the
 *      attribute when it is not — an `aria-controls` naming an element that does not exist is worse
 *      than none, because a screen reader announces a control that leads nowhere.
 *
 * The shape below is `components/guide/GuideStepCard.tsx`'s, deliberately: it is the app's one
 * worked example of a collapsible card whose toggle is a full-bleed control, and its header records
 * the two traps this file inherits rather than rediscovers.
 *
 * ── THE CARD IS NOT `overflow-hidden`, AND THAT IS LOad-BEARING ────────────────────────────────
 *
 * The toggle is a full-width button flush with the card's edges, and the global keyboard focus ring
 * (`app/globals.css`) is an `outline` drawn OUTSIDE the border box at `outline-offset: 2px`.
 * Clipping the card erases that ring on three sides and the primary control of every mega card looks
 * unfocused. The only thing that needs clipping is the height-animated panel, which clips ITSELF.
 * `GuideStepCard`, `UnfiledRecordCard`, `DropCard`, `FramePanel`, `MediaLightbox` and
 * `WorkshopMappingPanel` all carry this same note; it has been rediscovered six times.
 *
 * ── THE TONE IS NEVER THE ONLY CHANNEL ─────────────────────────────────────────────────────────
 *
 * Non-negotiable 5: *a signal that only exists as colour is a signal some readers never get.* Every
 * mega card carries its TITLE and its one-line NOTE as the primary channel, and the tone is spent on
 * an `aria-hidden` icon chip and a hover border — exactly where `MinistryDeskCard` spends the
 * `ministry` ramp, and nowhere else. `tailwind.config.ts`'s group-tone block argues the boundary at
 * length: no button, no input, no focus ring, no left-edge rule, no tinted card ground.
 *
 * ── GLASS MAY NOT NEST ─────────────────────────────────────────────────────────────────────────
 *
 * `GlassSurface.tsx` and `MinistryDeskCard.tsx:81` both state it: an ancestor with its own
 * `backdrop-filter` becomes the backdrop root, and a glass surface inside it refracts a flat wash
 * instead of the page. Every `DashboardCard` IS a `GlassSurface`, so this card is `.panel` — an
 * opaque recipe — and must stay that way.
 *
 * ── REDUCED MOTION NEEDS THE JS PATH TOO ───────────────────────────────────────────────────────
 *
 * The two global CSS blocks in `globals.css` cannot reach framer-motion's inline styles and there is
 * no `MotionConfig reducedMotion="user"` in this app. So the transition branches on
 * `useAppReducedMotion()` and collapses to `{ duration: 0 }`, which is what every other animated
 * surface here does.
 */

import { AnimatePresence, motion } from "framer-motion";
import { ChevronDown } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useId } from "react";

import { layoutSpring, springy } from "@/components/guide/guideMotion";
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

/**
 * The tone names are the RAMP names from `tailwind.config.ts`, not colour words.
 *
 * ⚠ THE CLASS STRINGS BELOW ARE WRITTEN OUT IN FULL AND MAY NOT BE INTERPOLATED. Tailwind's
 * scanner reads source text; `bg-${tone}-100` produces no CSS at all and the chip renders
 * transparent with no error anywhere. This is the single most common way a themed component ships
 * broken, so the map is explicit and the type below makes a missing entry a compile error.
 */
export type MegaTone = "purple" | "archive" | "errand" | "steward" | "mango";

const TONE_CHIP: Record<MegaTone, string> = {
  // Purple keeps the product's own weights: a filled 800 chip with white ink, as every
  // `DashboardCard` and `GuideStepCard` already draws. The other four are pale-ground chips,
  // because a filled 800 at those hues reads as a status badge rather than as a heading mark.
  purple: "bg-purple-800 text-white",
  archive: "bg-archive-100 text-archive-700 dark:bg-archive-950/40 dark:text-archive-300",
  errand: "bg-errand-100 text-errand-700 dark:bg-errand-950/40 dark:text-errand-300",
  steward: "bg-steward-100 text-steward-700 dark:bg-steward-950/40 dark:text-steward-300",
  mango: "bg-mango-100 text-mango-700 dark:bg-mango-950/40 dark:text-mango-300"
};

/**
 * The hover border. `ministry-desk-unit.spec.ts:459` exempts a BORDER from the `dark:` obligation
 * that every ink carries — *"a border is decoration rather than text, it reads against both
 * grounds"* — so these are deliberately single-valued while `TONE_CHIP` above is paired.
 */
const TONE_BORDER: Record<MegaTone, string> = {
  purple: "hover:border-purple-300",
  archive: "hover:border-archive-300",
  errand: "hover:border-errand-300",
  steward: "hover:border-steward-300",
  mango: "hover:border-mango-300"
};

export function MegaCard({
  title,
  note,
  icon: Icon,
  tone,
  count,
  countLabel,
  expanded,
  onToggle,
  span = "half",
  headerRight,
  children
}: {
  title: string;
  /** One line under the title. The card's second non-colour channel; never optional in practice. */
  note: string;
  icon: LucideIcon;
  tone: MegaTone;
  /**
   * How many things are inside. Printed on the collapsed card so a shut mega card still says what it
   * holds — a collapsed card that says only "Records" is the silent-emptiness bug wearing a layout
   * change, which is the failure `docs/OPEN_FINDINGS.md` records most often in this repository.
   */
  count: number;
  /** The singular noun for `count`; pluralised with a bare "s", which is right for every caller. */
  countLabel: string;
  expanded: boolean;
  onToggle: () => void;
  /**
   * How much of a two-column rail this card takes.
   *
   * `"full"` exists for content that genuinely cannot be halved rather than for content that would
   * merely prefer not to be. On `/questionnaire` the capture form is ONE `<form>` element by
   * contract — two parsers slice from its opening tag to the first `</form>` — and the recorded
   * interviews table carries `min-w-[980px]`, so squeezing either into half a page produces a
   * horizontal scrollbar inside a card inside a rail. Everything else halves.
   */
  span?: "half" | "full";
  /**
   * Sits OUTSIDE the toggle button, so a control here does not also collapse the panel — the same
   * contract `components/ui/Accordion.tsx` offers under the same name. A status word belongs here
   * rather than in `note`, because `note` describes the card and this describes right now.
   */
  headerRight?: React.ReactNode;
  children: React.ReactNode;
}) {
  const reduce = useAppReducedMotion();
  const panelId = useId();
  const headingId = useId();

  return (
    <section
      aria-labelledby={headingId}
      /*
        `.panel` is the opaque recipe (rounded-lg, border-line-200, bg-card, shadow-sm). `h-fit` keeps
        a collapsed card from stretching to its taller neighbour's height inside the two-column rail —
        without it, `grid` stretches both cells and a shut card becomes a tall empty box beside an
        open one.
      */
      className={`panel h-fit transition-[border-color,box-shadow] hover:shadow-md ${TONE_BORDER[tone]} ${
        span === "full" ? "lg:col-span-2" : ""
      }`}
    >
      <div className="flex items-start gap-3">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={expanded}
        // Only while the panel is mounted. See the header: a control pointing at nothing is worse
        // than a control pointing at nowhere.
        aria-controls={expanded ? panelId : undefined}
        className="flex min-w-0 flex-1 items-start gap-3 p-4 text-left"
      >
        <span aria-hidden className={`grid h-10 w-10 shrink-0 place-items-center rounded-md ${TONE_CHIP[tone]}`}>
          <Icon className="h-5 w-5" aria-hidden />
        </span>

        <span className="min-w-0 flex-1">
          <span id={headingId} className="block font-display text-lg font-bold leading-tight text-ink-900">
            {title}
          </span>
          <span className="mt-1 block text-xs leading-5 text-ink-500">{note}</span>
          {/*
            THE COUNT IS A WORD, NOT A BADGE, and it is outside the tone. It is the third channel —
            after the title and the note — by which a reader who cannot separate teal from indigo
            still knows which card holds what.
          */}
          <span className="mt-1.5 block text-xs font-medium text-ink-700">
            {count} {countLabel}
            {count === 1 ? "" : "s"}
          </span>
        </span>

        <span
          aria-hidden
          className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-full border border-line-200 text-ink-500"
        >
          <motion.span
            initial={false}
            animate={{ rotate: expanded ? 180 : 0 }}
            transition={springy(reduce)}
            className="grid place-items-center"
          >
            <ChevronDown className="h-4 w-4" aria-hidden />
          </motion.span>
        </span>
      </button>
      {headerRight ? <div className="shrink-0 p-4 pl-0">{headerRight}</div> : null}
      </div>

      <AnimatePresence initial={false}>
        {expanded ? (
          <motion.div
            key="panel"
            id={panelId}
            initial={{ height: 0, opacity: 0 }}
            animate={{ height: "auto", opacity: 1 }}
            exit={{ height: 0, opacity: 0 }}
            transition={reduce ? { duration: 0 } : { height: layoutSpring(false), opacity: { duration: 0.18 } }}
            // Clips ITSELF, and rounds its own bottom — so the body stays inside the card's corners
            // while the card leaves the toggle's focus ring unclipped.
            className="overflow-hidden rounded-b-lg"
          >
            <div className="border-t border-line-200 p-4">{children}</div>
          </motion.div>
        ) : null}
      </AnimatePresence>
    </section>
  );
}
