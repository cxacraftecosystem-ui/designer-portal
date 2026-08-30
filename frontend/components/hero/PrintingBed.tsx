"use client";

import { useEffect, useId, useRef, useState } from "react";
import { motion, useMotionValueEvent, useScroll, useSpring, type Variants } from "framer-motion";

import { BUTI_FILL_RULE, BUTI_PATH, BUTI_VIEWBOX } from "@/components/hero/buti";
import {
  CENSUS_ROWS,
  CORPUS_CENSUS,
  formatCensusDate,
  type CorpusCensus
} from "@/components/hero/corpusCensus";
import {
  CLOTH_CELL,
  CLOTH_COLS,
  CLOTH_MASK,
  INDIA_HEIGHT,
  INDIA_PATH,
  INDIA_VIEWBOX,
  INDIA_WIDTH
} from "@/components/hero/indiaOutline";
import { useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * The printing bed — the page's one signature moment.
 *
 * THE IDEA. This repository is a hand-registered repeat: one carved set of forms, stamped over and
 * over, each impression slightly off-register. The bed prints itself as you read it — it starts as
 * bare ground and fills with a block-print repeat as the section crosses the reading line, one
 * buti at a time, row-major, left to right, the way a hand walks a printing table. Every
 * impression is misregistered — rotated, nudged, inked a little heavier or lighter than its
 * neighbour — because a perfect CSS `background-repeat` reads as wallpaper and a hand-registered
 * one reads as cloth. That imperfection IS the idea; take it away and this is a pattern background
 * any product could wear.
 *
 * WHY THE BED IS THE SHAPE OF INDIA
 * ---------------------------------
 * It used to be a rectangle. A rectangle of cloth and a map of India on the same page would have
 * been two signatures competing, and two signatures are worth less than one — so they are the same
 * object. The cloth is cut to the country: the repeat tiles inside the national outline and the
 * coastline is the selvedge. The craft metaphor and the geography stop being two decorations that
 * happen to share a section and become one statement, which is also the truest thing this product
 * can say about itself — this is Indian craft, recorded in India.
 *
 * The map's accuracy is not a detail. See `indiaOutline.ts`: the boundary follows the official
 * Government of India depiction, it was verified by measurement rather than by trusting a README,
 * and it must not be swapped for a convenient npm dataset.
 *
 * WHAT THE MAP CLAIMS, AND WHAT IT DELIBERATELY NO LONGER DOES
 * ------------------------------------------------------------
 * IT NAMES NOWHERE. This drawing used to carry four site marks — Bagru as a gold impression, the
 * block that had come down, with Dehradun, Jammu and Akola as empty printers' registration
 * targets — and a caption that explained the difference. All four are gone by direction, and the
 * data behind them (`LIVE_SITE`, `NEXT_SITES`, the `FieldSite` type in `indiaOutline.ts`) went
 * with them rather than being left exported and unimported, which is precisely the state this
 * file's own history proves nobody notices for releases at a time.
 *
 * THE DELETION IS ALLOWED TO BE TOTAL ONLY BECAUSE IT TAKES THE CLAIM WITH IT. A map that printed
 * one place and outlined three had to keep saying which was which, in words, at every width — four
 * identical dots would have read as four live sites and been a lie told in graphics. A map that
 * names nowhere asserts nothing about where work happens and needs no sentence to qualify it.
 * ⚠ SO DO NOT PUT A SINGLE MARK BACK ON ITS OWN. The instant one lands here the drawing starts
 * making that claim again, and everything the old caption carried — the printed/unprinted
 * distinction, the place names as text for a reader who cannot see them, the region for each —
 * has to come back with it. One dot is not a small change; it is the whole apparatus.
 *
 * The CLOTH makes no numeric claim either. 97 impressions do not represent 925 media files and
 * must never be labelled as if they did — the moment it tries, it stops being cloth and becomes a
 * dot-matrix infographic. The <dl> states the numbers; the cloth states nothing.
 *
 * MOTION. One `useScroll` on the band at the same ["start 65%", "end 65%"] reading line HowItWorks
 * uses, the same useSpring(140/30/0.4), and integer state via `useMotionValueEvent` so this
 * re-renders about 97 times across the whole band rather than once per scroll frame. There are no
 * per-impression motion values — 97 scroll subscribers on a frame is exactly how this would jank.
 * Each impression is a static `<g>` carrying its misregistration as an attribute, wrapping a `<use>`
 * whose opacity and stamp-scale are plain CSS transitions. Only opacity and transform ever change.
 *
 * GOLD — THE WHOLE CLOTH NOW, AND THE OLD BUDGET DIED WITH THE MARKS.
 * This paragraph used to read: "the budget is one gold impression with a soft halo … the map
 * itself is NOT a gold shape: the country is white at a few percent, and the gold marks the one
 * place that is real." Every clause of that rule existed to serve the site marks — gold was
 * rationed so that ONE gold thing could be found among ninety-seven white siblings. With no mark
 * to find, rationing gold protects nothing; it just leaves a white map on a purple band. The
 * cloth is printed in gold throughout, which is what a block-printed length actually is: one ink,
 * laid down by one block, over and over. This is still a dark marketing band, which is the only
 * kind of surface the gold ramp is allowed on at all (hero + auth), and the rest of that rule
 * stands: no gold on a data screen, and gold never becomes the action colour.
 *
 * ⚠ YOU CANNOT GET GOLD BY WASHING GOLD OVER PURPLE. MEASURED, NOT ASSUMED. Composite `gold-300`
 * (#EEC976) onto this band's own `grad-brand` stops (#762CB1 → #4B1674) and the result crosses a
 * dead zone on the way: at the OLD ink range it lands at hue 311-324° in OKLCH, which is purple
 * wearing a warm hint, and around half opacity the chroma bottoms out at 0.056 (#A17575 at
 * α 0.53, hue 19°) — a muddy rose, the least golden thing that could be on this page. The hue
 * does not enter the gold arc until roughly α 0.7 (61° on the dark stop) and is unambiguous only
 * past 0.85. THAT is why `JITTER.inkMin`/`inkRange` moved in the same change as the fill and not
 * after it: the fill swap ALONE turns #59277E into #572374, eleven units out of 255, and this band
 * would have shipped looking exactly as it did the day before while the commit claimed it was gold.
 *
 * ⚠ ONE PARAGRAPH OF THIS HEADER HAS ALREADY SHIPPED AS A DEFECT, AND IT IS THE LICENCE ONE.
 * For several releases everything this header said about the marks was described here and rendered
 * nowhere: `LIVE_SITE` and `NEXT_SITES` had zero importers anywhere in `frontend/`, the
 * `<figcaption>` did not exist, the `radialGradient` the halo needed was defined and referenced by
 * nothing, and `.fr-mark` / `.fr-map-label` styled elements that were never emitted. Two of the
 * three consequences that recorded are now moot — there are no marks to be missing, and no place
 * names to be absent from the accessibility tree. THE THIRD OUTLIVED EVERY OTHER THING ON THIS
 * BAND AND IS THE ONE TO READ TWICE:
 *
 *   THE BOUNDARY CREDIT IS GONE FROM THE PAGE ALTOGETHER, AND THE PARAGRAPH THAT STOOD HERE
 *   ARGUING IT MUST NEVER BE IS KEPT BELOW, because it was right on every point except the one
 *   that turned out to decide it.
 *
 *   It read: "THE BOUNDARY CREDIT IS A LICENCE OBLIGATION, NOT A CAPTION… it now lives in the page
 *   footer in `HeroLanding.tsx`… Do not delete it from the footer to tidy the footer, and do not
 *   'move it into a comment next to the path data': the build strips comments and no visitor has
 *   ever read one." Every clause of that is sound WHILE AN OBLIGATION EXISTS. What it never did was
 *   settle whether one does. `indiaOutline.ts` recorded two readings — CC-0 by the dataset's own
 *   README, CC BY 4.0 by the repository's blanket clause for "anything not explicitly licensed" —
 *   and satisfied the stricter one rather than resolving them. A file-level declaration IS explicit
 *   licensing, so the blanket clause does not reach this file: it is CC-0, and CC-0 waives
 *   attribution. The notice was removed by direction on 2026-08-30 on that basis.
 *
 *   WHAT IS UNCHANGED IS THE GEOMETRY'S REACH, and it is the reason this note stays on THIS band
 *   rather than only in the footer. `INDIA_PATH` still cuts the cloth's clip, fills the bare ground
 *   and strokes the selvedge, and `CLOTH_MASK` — which cells get an impression at all — is computed
 *   off that same path, so all 97 impressions are DataMeet-derived as well. 100% of the boundary
 *   geometry is still on screen. If the path is ever swapped for one from a CC BY, ODbL or
 *   share-alike source, the credit comes back as visible text and the quoted paragraph above
 *   becomes live guidance again — which is why it is quoted rather than deleted.
 */

/**
 * Seeded PRNG (mulberry32), evaluated ONCE at module scope.
 *
 * This is load-bearing, not a nicety. `Math.random()` in render would (a) make the server and the
 * client disagree about every transform, which is a hydration error, and (b) re-roll the jitter on
 * every re-render — the cloth would twitch each time the printed count ticked. Freezing the table
 * at module scope means the SSR'd markup and the client's are byte-identical and the
 * misregistration is a property of the block, which is what it is in the real thing.
 */
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * MISREGISTRATION IS THE WHOLE IDEA, SO IT HAS TO BE VISIBLE.
 *
 * An earlier pass used offsets so small the bed read as wallpaper, which is precisely the failure
 * this direction exists to avoid. These are the tuned values — enough that no two impressions line
 * up, not so much that the cloth looks broken. Shift is a FRACTION OF THE CELL rather than a fixed
 * distance, so the same hand shows at 360px and at 1280px.
 *
 * ⚠ `inkMin`/`inkRange` ARE HALF OF "THE MOTIFS ARE GOLD" — THEY ARE NOT A TASTE KNOB. They read
 * 0.075/0.155 while the cloth was white, which is where a background texture wants to sit: white
 * at 7.5-23% over the purple bed is a weave you register without naming. Gold at those alphas is
 * not gold. Composited on `grad-brand`'s dark stop, α 0.075 gold is #572374 against α 0.075
 * white's #59277E — eleven units apart out of 255 — and everything from there to about α 0.6 comes
 * out mauve rather than warm (the header carries the full measurement). 0.72-1.00 is the range
 * whose BOTH ENDS sit inside the gold arc: a thinly-inked impression is #C09775 (hue 61°, 4.76:1
 * on the bed) and a well-pressed one is #EEC976 (hue 86°, 7.95:1), so under-inking reads as thin
 * gold instead of as pink. Lower either number and the band quietly stops being gold while still
 * passing every gate in this repository; re-measure before you touch them.
 */
const JITTER = { shift: 0.055, rotate: 5, scaleMin: 0.9, scaleRange: 0.14, inkMin: 0.72, inkRange: 0.28 };

/** The motif is 24 units across its own box and spans 22 of them; this fits it to one cell. */
const MOTIF_SCALE = CLOTH_CELL / 24;

type Impression = {
  /** Where the block came down, in viewBox units, misregistration already folded in. */
  cx: number;
  cy: number;
  rot: number;
  scale: number;
  /** Ink density — how hard it was pressed. */
  ink: number;
};

/**
 * The impressions, in printing order: row-major, left to right, top to bottom.
 *
 * Only the cells CLOTH_MASK marks as touching the outline exist at all. The bounding box of India
 * is two-thirds sea, and printing into it would be ~98 elements of pure clipped-away cost.
 */
const IMPRESSIONS: readonly Impression[] = (() => {
  const rand = mulberry32(925_074_016);
  const out: Impression[] = [];
  for (let index = 0; index < CLOTH_MASK.length; index += 1) {
    if (CLOTH_MASK[index] !== "1") continue;
    const col = index % CLOTH_COLS;
    const row = Math.floor(index / CLOTH_COLS);
    out.push({
      cx: Number(((col + 0.5 + (rand() * 2 - 1) * JITTER.shift) * CLOTH_CELL).toFixed(3)),
      cy: Number(((row + 0.5 + (rand() * 2 - 1) * JITTER.shift) * CLOTH_CELL).toFixed(3)),
      rot: Number(((rand() * 2 - 1) * JITTER.rotate).toFixed(2)),
      scale: Number((JITTER.scaleMin + rand() * JITTER.scaleRange).toFixed(3)),
      ink: Number((JITTER.inkMin + rand() * JITTER.inkRange).toFixed(3))
    });
  }
  return out;
})();

const TOTAL = IMPRESSIONS.length;

/** Row-major print order, so each row wipes left to right rather than the whole cloth at once. */
const columnOf = (index: number) => index % CLOTH_COLS;

function Cloth({ printed, spriteId }: { printed: number; spriteId: string }) {
  return (
    <>
      {IMPRESSIONS.map((im, index) => {
        const on = index < printed;
        return (
          <g
            key={index}
            transform={`translate(${im.cx} ${im.cy}) rotate(${im.rot}) scale(${(im.scale * MOTIF_SCALE).toFixed(4)}) translate(-12 -12)`}
          >
            {/*
              Layout lives in the `transform` ATTRIBUTE above and never changes; only this element
              animates. `transform-box: fill-box` (set in globals.css) makes the stamp scale about
              the motif's own centre wherever it sits, which is the one thing the SVG and CSS
              coordinate systems otherwise disagree about across browsers.
            */}
            <use
              href={`#${spriteId}`}
              className="fr-buti"
              style={{
                opacity: on ? im.ink : 0,
                // The scale-down onto the impression's own weight IS the stamp: the block meeting
                // the cloth.
                transform: on ? "scale(1)" : "scale(1.07)",
                transitionDelay: `${columnOf(index) * 16}ms`,
                ["--fr-ink" as string]: im.ink
              }}
            />
          </g>
        );
      })}
    </>
  );
}

export default function PrintingBed({ census = CORPUS_CENSUS }: { census?: CorpusCensus }) {
  const reduce = useHeroReducedMotion();
  const sectionRef = useRef<HTMLElement>(null);

  // One sprite per instance, so two beds on one page can never collide on an element id. useId is
  // stable across server and client; the non-alphanumerics it wraps ids in are stripped because
  // they would have to be escaped in the `href` fragment.
  const uid = useId().replace(/[^a-zA-Z0-9]/g, "");
  const spriteId = `fr-buti-${uid}`;
  const clipId = `fr-india-${uid}`;

  const { scrollYProgress } = useScroll({ target: sectionRef, offset: ["start 65%", "end 65%"] });
  const smoothed = useSpring(scrollYProgress, { stiffness: 140, damping: 30, mass: 0.4 });
  const progress = reduce ? scrollYProgress : smoothed;

  const [printed, setPrinted] = useState(0);

  useMotionValueEvent(progress, "change", (latest) => {
    // Under reduced motion the cloth is a finished length, not something that builds — the effect
    // below has already printed it, and scrolling must not un-print it.
    if (reduce) return;
    const next = Math.max(0, Math.min(TOTAL, Math.round(latest * TOTAL)));
    setPrinted((current) => (current === next ? current : next));
  });

  useEffect(() => {
    if (reduce) setPrinted(TOTAL);
  }, [reduce]);

  const item: Variants = {
    hidden: { opacity: 0, y: 16 },
    show: { opacity: 1, y: 0, transition: { duration: reduce ? 0 : 0.5, ease: [0.16, 1, 0.3, 1] } }
  };

  const asOf = formatCensusDate(census.asOf);

  return (
    <section
      ref={sectionRef}
      className="relative overflow-hidden grad-brand"
      aria-label="The pilot collection"
    >
      {/* The band's own quiet mesh echo — inherited unchanged from the stats band this replaces. */}
      <div
        aria-hidden
        className="pointer-events-none absolute inset-0 opacity-60"
        style={{
          background:
            "radial-gradient(32rem 32rem at 88% 10%, oklch(0.7 0.145 80 / 0.14), transparent 60%), radial-gradient(36rem 36rem at 8% 95%, oklch(0.255 0.108 305 / 0.55), transparent 62%)"
        }}
      />

      <motion.div
        initial="hidden"
        whileInView="show"
        viewport={{ once: true, amount: 0.2 }}
        variants={{ hidden: {}, show: { transition: { staggerChildren: reduce ? 0 : 0.08 } } }}
        className="relative mx-auto grid max-w-6xl items-center gap-12 px-6 py-24 lg:grid-cols-[0.95fr_1.05fr] lg:gap-16"
      >
        {/* ── The accession ledger ──────────────────────────────────────── */}
        <div className="fr-ledger">
          <motion.p variants={item} className="eyebrow mb-3 !text-gold-300">
            The pilot collection
          </motion.p>
          <motion.h2
            variants={item}
            className="font-display text-3xl font-bold tracking-tight text-white sm:text-4xl"
          >
            What the repository holds today.
          </motion.h2>
          <motion.p variants={item} className="mt-4 text-sm leading-relaxed text-white/60">
            Records held, as of {asOf}.
          </motion.p>

          {/*
            THE TYPE SCALE HERE IS A RULE, NOT A PREFERENCE — do not "improve" 925 back into a big
            gold numeral. The band this replaced was four extrabold gold numerals on a gradient,
            which is the default marketing template and says nothing. Scholars trust a small honest
            census far more than a vague big claim, so the numbers are set as a ledger: values at
            text-base in tabular figures, labels at text-sm/60, hairline rules between. The size of
            the corpus is stated; it is not performed.
          */}
          <motion.dl variants={item} className="mt-8 grid grid-cols-2 gap-x-5 sm:gap-x-8">
            {CENSUS_ROWS.map(({ key, one, many }) => {
              const value = census.counts[key];
              return (
                <div
                  key={key}
                  className="flex items-baseline justify-between gap-3 border-t border-white/10 py-2.5"
                >
                  <dt className="text-sm text-white/60">{value === 1 ? one : many}</dt>
                  <dd className="font-display text-base font-semibold tabular-nums text-white">{value}</dd>
                </div>
              );
            })}
          </motion.dl>
        </div>

        {/* ── The cloth, cut to the country ─────────────────────────────── */}
        <motion.figure variants={item} className="fr-cloth relative m-0">
          {/*
            `role="img"` WITH A NAME, NOT `aria-hidden`. This was `aria-hidden` for exactly as long
            as the <figcaption> below it repeated every fact the drawing carried — the country, the
            one printed site, the three that were next. That caption is gone by direction, and
            `aria-hidden` left on its own would have handed a screen-reader user a band whose whole
            right-hand column is silence: not "a decorative image I can skip past", but nothing
            announced at all, on the page's signature graphic.

            `role="img"` keeps the half `aria-hidden` was there for and repairs the half it broke.
            Children of an `img` role are presentational, so the 97 <use> elements are still never
            read out one at a time; the element itself finally gets the one-line name it now needs.
            The name says what the drawing IS and makes no claim about places, because the drawing
            no longer does — see the header. If a site mark is ever restored, this label is NOT
            where its name goes: real text beside the map is.
          */}
          <svg
            viewBox={INDIA_VIEWBOX}
            className="block w-full overflow-visible"
            style={{ aspectRatio: `${INDIA_WIDTH} / ${INDIA_HEIGHT}` }}
            role="img"
            aria-label="India, drawn as a length of block-printed cloth"
            focusable="false"
          >
            <defs>
              {/* The carved block, defined once for this instance. */}
              <path id={spriteId} d={BUTI_PATH} fillRule={BUTI_FILL_RULE} />
              <clipPath id={clipId}>
                <path d={INDIA_PATH} clipRule="evenodd" />
              </clipPath>
            </defs>

            {/*
              Bare ground. The country is visible from the first frame at a few percent white, so
              the section never renders as an empty box waiting for script, and the shape a reader
              recognises is there before the cloth arrives to fill it.
            */}
            <path d={INDIA_PATH} fillRule="evenodd" fill="rgba(255,255,255,0.045)" />

            {/*
              ONE FILL FOR NINETY-SEVEN IMPRESSIONS, AND IT IS THE TOKEN RATHER THAN A LITERAL.
              `fill` is an inherited property, so this group's colour reaches every <use> below it
              and, through the use shadow tree, the sprite <path> in <defs> that carries no fill of
              its own — the same mechanism the `fill="#ffffff"` attribute used, one layer up the
              cascade. A Tailwind utility is reachable HERE when it was not for the gold site marks
              this replaces: their ink had to be spelled out as a literal OKLCH string because
              `stopColor` on a gradient stop has no utility to reach for, and the two copies then
              had to be kept identical by hand or the halo was a different gold from the impression
              it haloed. One class on one element cannot drift from itself.

              The colour lives on this <g> and NOT on the 97 <use> elements, for the same reason
              their transforms live in an attribute: a scroll tick must touch opacity and transform
              and nothing else, and 97 fills would be 97 more declarations for the style recalc to
              walk on a change that only ever alters how many of them are switched on.
            */}
            <g clipPath={`url(#${clipId})`} className="fill-gold-300">
              <Cloth printed={printed} spriteId={spriteId} />
            </g>

            {/*
              The coast. `non-scaling-stroke` keeps this a true one-pixel hairline at 360px and at
              1280px alike — a stroke width in viewBox units would be four times heavier on desktop.
            */}
            <path
              d={INDIA_PATH}
              fillRule="evenodd"
              fill="none"
              stroke="rgba(255,255,255,0.28)"
              strokeWidth={1}
              strokeLinejoin="round"
              vectorEffect="non-scaling-stroke"
            />
          </svg>

          {/*
            THERE IS NO <figcaption> ANY MORE, AND THAT IS THE WHOLE REASON THE SITE MARKS HAD TO
            GO WITH IT. The caption was never decoration: it was the accessible twin of an
            `aria-hidden` graphic, and it was the only place the four place names existed below
            640px, where `.fr-map-label` withholds them rather than render them under 9px. Delete
            the caption and keep the marks and this band reproduces, verbatim, a bug it has already
            shipped once — names drawn on screen for sighted desktop readers and absent for
            everybody else. So the two deletions are one coherent change rather than two that
            fight: no marks, no names to lose, and the drawing now carries its own accessible name
            on the <svg> above.

            What the caption ALSO carried, the DataMeet boundary credit, is a licence obligation
            and did not go with it. It now sits in the page footer in `HeroLanding.tsx`; read the
            licence paragraph in this file's header before touching either end of that.

            The <figure> stays a <figure> with no caption, which is valid HTML and is what
            `.fr-cloth` and the <noscript> rule below both address by name.
          */}

          {/*
            With scripting off, neither the scroll machinery nor framer-motion's reveal ever runs:
            the country would stay bare ground forever, and the ledger — the accessible content, and
            the only place the corpus figures are stated — would sit at the opacity 0 that
            `whileInView` renders into the server HTML.

            So: print the whole cloth and show the census. The `.fr-ledger > *` rule is scoped to
            the copy column deliberately, because a blanket `opacity: 1` on the section would also
            un-hide the unprinted impressions and beat the `.fr-buti` rule on specificity.

            The `.fr-mark{opacity:1!important}` clause that used to sit in this rule went with the
            site marks. `.fr-mark` and `.fr-map-label` are still declared in `globals.css` and now
            match nothing — dead, harmless, and outside this change's territory to delete.

            (The rest of the page reveals on scroll the same way and behaves the same with scripting
            off. That is pre-existing and page-wide — every section is built this way, including
            HowItWorks, which is out of scope to rewrite — so this fixes the band it can reach
            rather than pretending to fix all of them.)
          */}
          <noscript>
            <style>{`.fr-buti{opacity:var(--fr-ink)!important;transform:none!important}.fr-ledger>*,.fr-cloth{opacity:1!important;transform:none!important}`}</style>
          </noscript>
        </motion.figure>
      </motion.div>
    </section>
  );
}
