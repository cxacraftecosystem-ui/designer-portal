"use client";

import Link from "next/link";
import { useRef } from "react";
import { motion, useScroll, useTransform } from "framer-motion";
import {
  Brush,
  ChevronDown,
  ClipboardList,
  ExternalLink,
  FileOutput,
  GitBranch,
  Images,
  Languages,
  Mic,
  Package,
  ShieldCheck,
  User as UserIcon,
  UsersRound,
  Wifi,
  Wrench
} from "lucide-react";

import { WorkshopLogo } from "@/components/WorkshopLogo";
import { useAuth } from "@/components/AuthProvider";
import AccessLadder, { TIER_COUNT_WORD } from "@/components/hero/AccessLadder";
import DesignerWorkbench from "@/components/hero/DesignerWorkbench";
import DesignWorkshopSection from "@/components/hero/DesignWorkshopSection";
import HeroFAQ from "@/components/hero/HeroFAQ";
import HowItWorks from "@/components/hero/HowItWorks";
import type { CorpusCensus } from "@/components/hero/corpusCensus";
import PrintingBed from "@/components/hero/PrintingBed";
import ReportEngine from "@/components/hero/ReportEngine";
import TeamSection from "@/components/hero/TeamSection";
import WalkthroughCallout from "@/components/hero/WalkthroughCallout";
import { STAGE_COUNT_WORD_LOWER } from "@/components/hero/workshopArc";
import { butiTileUrl } from "@/components/hero/buti";
import { heroEntrance, useHeroReducedMotion } from "@/components/hero/useHeroMotion";

/**
 * The cloth, in three states, is the page's one structural idea.
 *
 *   1. THE HERO is the bare ground — the buti at 96px and 3.2% white, a weave you register without
 *      naming. It replaces the 24px dot grain that used to sit here (that recipe moved onto the
 *      printing bed, so nothing was lost).
 *   2. THE PRINTING BED is the printing itself — 72 individually misregistered impressions that
 *      build as you scroll, with one gold head. That is the signature and the only place with
 *      bespoke geometry or motion.
 *   3. THE CLOSING BAND is the finished length — the same tile, fully present, STATIC, and with no
 *      gold at all. The moment it moves or takes gold, the bed stops being the one signature
 *      moment and becomes a repeated effect.
 *
 * Both static states are the CSS tile, which carries four differently-rotated impressions per
 * 96px repeat so it does not read as machine-perfect wallpaper. Only the bed stamps individual
 * impressions; that is what earns it the attention.
 */
const BUTI_TILE = butiTileUrl();

const TRUST_ITEMS = [
  // The count comes from the ladder itself (`AccessLadder`, which derives it from `ROLES_BY_RANK`),
  // because this badge and that section's heading are the same claim seen at two scroll positions on
  // one page. Typed by hand, they drift apart the next time a tier is added and contradict each
  // other in front of the visitor.
  { icon: ShieldCheck, label: `${TIER_COUNT_WORD}-tier access control` },
  { icon: Wifi, label: "Works offline in the field" },
  { icon: Languages, label: "Transcribed & translated to English" },
  // The fourth badge is the one thing on this list no comparable tool does, and the page said
  // nothing about it: `android/…/report/ReportExport.kt` is "the public entry point for exporting a
  // [ReportDocument] from the device, entirely offline". Deliberately "on the device" and not
  // "offline" — badge two already owns that word, and repeating it would spend a line saying
  // nothing.
  { icon: FileOutput, label: "Reports written on the device" }
];

/**
 * The eight record types, named EXACTLY as the Android dashboard and the web menu name them
 * (MainActivity EntryMode.label) — an academic who has seen the app should recognise every word.
 */
const RECORD_TYPES = [
  { icon: UserIcon, title: "Artisan", copy: "The maker: craft, lineage, place, identity, provenance." },
  { icon: Brush, title: "Craft", copy: "The tradition itself — technique, origin, regional identity." },
  // PRODUCT AND TOOL BOTH SAY "measured from a photograph" because both forms mount the same
  // control — `components/media/RecordPhotoMeasure.tsx` at `ProductForm.tsx:1076` and
  // `ToolForm.tsx:1242`. Saying it on the Product card alone would have been the cheaper sentence
  // and it would have read as "the tool form does not have this", which is the shape of claim this
  // page has already been wrong in twice: an omission on a list of eight is indistinguishable from
  // an absence. The words are the app's own ("Document using grid", `GridMeasurement.tsx:321`)
  // rather than a paraphrase, so a reader who signs in recognises the checkbox they were promised.
  {
    icon: Package,
    title: "Product",
    copy:
      "What is made: materials, pricing, imagery — and length, breadth and height read off a photograph taken against a measuring grid."
  },
  { icon: GitBranch, title: "Process", copy: "How it is made, step by ordered step, with media per step." },
  {
    icon: Wrench,
    title: "Tool",
    copy: "The toolkit, which artisans use each tool, and its dimensions measured the same way."
  },
  // "The standard form" and "reuse" are the app's own words, not descriptions of them:
  // `questionnaires/[id]/page.tsx:597` toggles between "Publish as the standard form" and "Withdraw
  // as the standard form", and `questionnaires/ReuseDialog.tsx` is titled "Reuse at another
  // workshop". The dialog is emphatic that reuse COPIES — two rows, two question trees, two
  // histories — so "reusable at the next" is deliberately not "shared with the next".
  {
    icon: ClipboardList,
    title: "Questionnaire",
    copy:
      "Structured interviews, recorded and auto-transcribed — on the repository's standard form or one a designer wrote and can reuse at the next workshop."
  },
  { icon: Images, title: "Miscellaneous Media", copy: "Audio, video and photographs that belong to no one record." },
  { icon: UsersRound, title: "Workshop", copy: "Field expeditions: assignments, date windows, approvals." }
];

/**
 * What a finished transcript is attached to. Not decoration: "nothing arrives as a loose file" is
 * the product's central claim, and every recording really does land linked to these three.
 */
const TRANSCRIPT_LINKS = [
  { icon: UserIcon, label: "Artisan" },
  { icon: Brush, label: "Craft" },
  { icon: UsersRound, label: "Workshop" }
];

/**
 * The three headline lines, masked and flown up one after another. Only the last is gold — the
 * gradient is a single accent, not a treatment applied to the whole headline.
 */
// Must stay in step with the page title in `app/page.tsx` — that metadata title is the same
// sentence, and a visitor who arrives from a search result reads the tab and the headline
// together. They drifted apart once already, at the rebrand.
const HEADLINE = [
  { text: "The workshop ends.", gold: false },
  { text: "The report is", gold: false },
  { text: "already written.", gold: true }
];

/**
 * The cross-cutting surfaces that sit on top of the records, in the app's own vocabulary.
 *
 * "Scan a code" IS THE APP'S LABEL AND NOT A DESCRIPTION OF ONE — `app/(protected)/scan/page.tsx`
 * renders `PageHeader title="Scan a code"`, and the same three words are the dashboard tile and
 * the Android nav row, held to each other by `e2e/dashboard-tile-parity-unit.spec.ts` and
 * `DashboardTileParityTest`. It belongs on THIS list rather than in the sketches or workshop
 * sections because what it does is find a RECORD from something printed — a card, a prototype tag,
 * a screenshot of one — which is exactly what "built on top of the eight" means. It was previously
 * three deliberate steps down a menu named after reading a list, which is the reason it became a
 * destination at all; a chip here is the public half of that same fix.
 */
const SURFACES = [
  "View Data",
  "Review & approvals",
  "Sharing & access grants",
  "Assigned tasks",
  "Scan a code",
  "CSV & full-dataset export",
  "Edit history & provenance"
];

/**
 * ── THE INSTITUTIONAL BAND (requirements 15, 16 and 17) ────────────────────────────────────────
 *
 * Two marks and one outbound link, sitting between the closing call to action and the footer.
 *
 * WHY HERE AND NOWHERE ELSE. The masthead is 100px of dark purple carrying a wordmark and one
 * button; three marks up there would need three light plates in the most visible part of the page
 * and would compete with the single conversion this page has. The closing band is worse for the
 * same reason — a link AWAY from the page, printed next to the sign-in button. Above the footer is
 * where a reader looks for provenance, and it is the only place on this page where LEAVING is the
 * expected gesture. It shares the footer's `bg-card`, so the footer's own `border-t` is the hairline
 * between them and no second rule is drawn.
 *
 * ⚠ NO MOTION ON THIS BAND, AND THAT IS THE POINT RATHER THAN AN OMISSION. Every other section
 * below the fold is `whileInView`, which means framer-motion writes `opacity: 0` into the
 * server-rendered HTML and only the printing bed ships the `<noscript>` override that undoes it. An
 * attribution and affiliation band is the single worst member of that class to leave invisible when
 * JavaScript does not run, so this one renders statically and has nothing to lose. Do not "bring it
 * in line" with its neighbours by adding a variant.
 *
 * ⚠ NO GOLD. Gold is permitted on this page and its budget is already spent on the hero, the
 * printing bed and the closing band (`DesignWorkshopSection.tsx` records the rule). A fourth gold
 * surface stops the bed being the signature.
 *
 * ── THE LIGHT PLATE, WHICH IS A DARK-MODE FIX AND NOT A DECORATION ─────────────────────────────
 *
 * `iit-kharagpur.svg` is 110 paths sharing ONE fill, `#291973`, on transparency. Measured against
 * this app's real tokens it is 14.18:1 on the light `--card` and **1.24:1** on the dark one — the
 * ring lettering, the "1951" and the motto are not merely dim in dark mode, they are gone. So it
 * needs a light ground under it, and the repository already has exactly one answer for a mark that
 * must keep its own colours on a surface that would swallow it: `WorkshopLogo.tsx:4-5` — "keep its
 * native colours even on purple surfaces (put it in a cream rounded tile there)". That cream is
 * `#FAF9F5`, and the plate below reaches it through `bg-logo-cream` — a REAL TOKEN, declared in
 * `tailwind.config.ts` under the comment "Brand-native logo colors (Android launcher icon) — never
 * re-themed", beside `logo-terracotta` and `logo-ink`. So this plate is not an exception to "never
 * hardcode a neutral" (§1.2) at all; it is the one ladder in the config whose whole purpose is to
 * NOT invert, and naming it is what makes that legible. `bg-[#FAF9F5]` would have rendered the
 * identical pixels and read as somebody eyedroppering a colour, which is exactly the thing a later
 * reader would have "fixed" into `bg-card` and broken in dark mode. `tile={false}` has no call site
 * anywhere in the app, so the cream tile is the only precedent there is.
 *
 * BOTH MARKS GET THE PLATE, THOUGH ONLY ONE NEEDS IT. `dc-handicrafts.png` is full colour — a blue,
 * a yellow, a red and a white counter inside the letterform — and it survives both themes on its
 * own. A plate behind one mark and a bare mark beside it reads as a mistake rather than as a
 * treatment, and the alternative (a `dark:`-conditional plate on one of the two) is new theming
 * machinery on a prerendered page for a problem a shared plate solves with one class. It also gives
 * the DC mark's white counter a warm ground to sit on rather than the page's own white.
 *
 * ⚠ THAT PNG IS INDEXED COLOUR, not RGBA — 256 palette entries with a 182-entry `tRNS` alpha table
 * (checked on disk, not assumed). It renders correctly and its quantisation is invisible at this
 * size, but two things follow. Do not apply a CSS filter to it expecting straight-alpha RGBA
 * behaviour, and do not read a brand hex out of it: each of its three colours is spread over
 * several near-identical palette entries, so the file is not the authority on what the mark's blue
 * IS. If an exact colour is ever needed, take it from the institution rather than from this file.
 *
 * ── OPTICAL SIZING: THE HEIGHTS ARE NOT EQUAL, AND THAT IS THE CORRECTION ──────────────────────
 *
 * The seal is 268 × 300 (portrait, 0.89:1) and the DC mark is 600 × 253 (landscape, 2.37:1). Set to
 * one height the landscape mark covers nearly three times the area and visibly dominates the row.
 * So mass is equalised instead of height: the seal at `h-16` is 64 × 57 ≈ 3,650px², and the DC mark
 * at `h-12` is 48 × 114 of BOX — but its ink occupies only rows 24…235 of its 253, so the ink is
 * 40 × 109 ≈ 4,380px². The wordmark ends ~20% larger by area, which is right rather than sloppy: a
 * wordmark is mostly the space between letters where a seal is solid ink. The two plates are one
 * fixed size and the marks are centred in them, so the row has a real shared baseline instead of
 * two marks agreeing by coincidence at one viewport width.
 *
 * ── `<img>` AND NOT `next/image` ───────────────────────────────────────────────────────────────
 *
 * `next.config.ts` states it in its own header: "Nothing renders through `next/image` today (media
 * is shown with plain `<img>`/`<audio>` tags…)". Its `remotePatterns` allowlist is about REMOTE
 * hosts and has nothing to say about a file in `public/`, so introducing the optimiser for two
 * static marks on the one prerendered route would be new machinery for no gain. The eight existing
 * `<img>` call sites all carry the same eslint suppression; this follows them.
 *
 * ── ACCESSIBILITY: WHY `alt=""` IS CORRECT HERE AND IS NOT THE `alt="logo"` TRAP ───────────────
 *
 * A logo link whose ONLY content is an image must carry alt text naming where the link goes. These
 * links carry the institution's full name as REAL TEXT inside the same anchor, which is strictly
 * better — it is visible, selectable, translatable, and it survives an image that fails to load.
 * Giving the image the name as well would make a screen reader announce the institution twice per
 * link. The visible text is therefore the accessible name, and `(opens in a new tab)` is appended
 * `sr-only` AFTER it so the visible label is still a prefix of the accessible one (WCAG 2.5.3,
 * Label in Name). The suffix is the wording this repository already uses in
 * `designworkshop/StageReferenceField.tsx:444`.
 *
 * `target="_blank" rel="noreferrer"` is the house form for an outbound anchor — roughly eleven call
 * sites against one each of the three alternatives — and `noreferrer` implies `noopener` in every
 * browser this app supports, so the pair is not needed.
 */
const INSTITUTIONS = [
  {
    /** The formal name, verbatim: it is both the visible label and the link's accessible name. */
    name: "Indian Institute of Technology Kharagpur",
    href: "https://www.iitkgp.ac.in/",
    src: "/logos/iit-kharagpur.svg",
    // Intrinsic dimensions, so the browser reserves the right box before the file arrives. The
    // `w-auto` in `markClass` is what makes the rendered width follow from the height.
    width: 268,
    height: 300,
    markClass: "h-12 w-auto sm:h-16"
  },
  {
    // The office's name as this repository already writes it — `report_templates.py:371` sets
    // `organisation="Office of the Development Commissioner (Handicrafts)"` on the DCH_STANDARD
    // template. The Ministry line is in the paragraph above rather than repeated in the label,
    // where it would push this caption to five wrapped lines on a phone.
    name: "Office of the Development Commissioner (Handicrafts)",
    href: "https://handicrafts.nic.in/",
    src: "/logos/dc-handicrafts.png",
    width: 600,
    height: 253,
    markClass: "h-9 w-auto sm:h-12"
  }
];

/**
 * Requirement 17, and the decision behind it: the Centre of Excellence is a REDIRECT, never an
 * import and never an iframe.
 *
 * The Centre's site is a finished Next.js application with its own CMS — pages and typed sections
 * in Postgres, an editorial studio, revision history and a draft-leak check. This application has
 * one public route and no content model at all, so "bring the content over" is really "rebuild a
 * CMS", and a half-built copy of an institutional site is worse than a link to the real one. The
 * Centre's own site already names this product as one of its three platform pillars, so a link
 * preserves a deliberate parent/child relationship that collapsing the two would destroy.
 *
 * The host is printed in the link text on purpose. A reader about to leave an application they are
 * being asked to sign in to should be able to see where they are going before they press it.
 */
const CENTRE_OF_EXCELLENCE_HREF = "https://cxa-cms.vercel.app/";

/**
 * The public hero — the product's signature dark-purple mesh treatment applied to
 * Design Prototype Workshop: gold-gradient headline line, a framer-motion line-mask entrance,
 * ambient orbs, and a live-transcript preview card in place of the note card.
 *
 * (This line said "GSAP line-mask entrance" for one refactor longer than it was true, three lines
 * above a paragraph correctly stating the entrance is framer-motion. There is no GSAP anywhere in
 * `components/hero/` — `grep -rn gsap components/hero/` returns nothing — and the reason to care is
 * that GSAP in this repository lives behind a mandatory dynamic import whose whole job is keeping
 * 70 KB off pages that do not use it. A comment claiming a page loads it is how somebody comes
 * looking for a timeline to reduced-motion-gate, or "restores" one that was never here.)
 *
 * Gold is permitted here (and on auth) and nowhere else. Everything below the dark hero band is
 * built from the themed tokens — `bg-card`, `ink-*`, `line-200` — so the page reads correctly in
 * both light and dark; a hardcoded white card would turn into white-on-white in dark mode.
 *
 * Motion is framer-motion throughout — the same library the rest of the page already uses — and
 * every duration passes through heroEntrance(), which honours the OR of the OS preference and the
 * in-app Settings toggle. The entrance is declarative on purpose: an imperative timeline that has
 * to re-select the DOM after React has rendered it can leave an element stranded at its start
 * state (this hero's call-to-action row did exactly that), whereas these props ARE the state.
 */
export default function HeroLanding({ census }: { census?: CorpusCensus }) {
  const rootRef = useRef<HTMLElement>(null);
  const { user, loading } = useAuth();
  const reduce = useHeroReducedMotion();
  const enterHref = !loading && user ? "/dashboard" : "/login";

  const { scrollYProgress } = useScroll({ target: rootRef, offset: ["start start", "end start"] });
  const yContent = useTransform(scrollYProgress, [0, 1], ["0%", reduce ? "0%" : "12%"]);
  const yOrbs = useTransform(scrollYProgress, [0, 1], ["0%", reduce ? "0%" : "22%"]);
  const fade = useTransform(scrollYProgress, [0, 0.7], [1, reduce ? 1 : 0.15]);

  /** The ambient orb drift. `initial` is always the rest state, so the server HTML matches. */
  const drift = (to: { x: string; y: string; scale: number }, seconds: number) => ({
    initial: { x: "0%", y: "0%", scale: 1 },
    animate: reduce ? { x: "0%", y: "0%", scale: 1 } : to,
    transition: reduce
      ? { duration: 0 }
      : { duration: seconds, repeat: Infinity, repeatType: "reverse" as const, ease: "easeInOut" as const }
  });

  return (
    <div className="bg-bg-0">
      {/* ── Hero ─────────────────────────────────────────────────────────── */}
      <section
        ref={rootRef}
        className="relative isolate flex min-h-[100svh] flex-col overflow-hidden bg-purple-950"
        aria-label="Design Prototype Workshop — capture to report, offline"
      >
        {/* Mesh background: two purple orbs + one faint gold, plus fine grain. */}
        <motion.div aria-hidden style={{ y: yOrbs }} className="pointer-events-none absolute inset-0">
          <motion.div
            {...drift({ x: "4%", y: "-4%", scale: 1.05 }, 14)}
            className="absolute -left-40 -top-48 h-[42rem] w-[42rem] rounded-full opacity-80 [will-change:transform]"
            style={{ background: "radial-gradient(circle, oklch(0.47 0.198 305 / 0.5), transparent 62%)" }}
          />
          <motion.div
            {...drift({ x: "-4%", y: "4%", scale: 1.03 }, 17)}
            className="absolute -right-48 top-1/4 h-[40rem] w-[40rem] rounded-full opacity-70 [will-change:transform]"
            style={{ background: "radial-gradient(circle, oklch(0.4 0.18 305 / 0.55), transparent 64%)" }}
          />
          <motion.div
            {...drift({ x: "-3%", y: "-3%", scale: 1.06 }, 21)}
            className="absolute bottom-[-12rem] left-1/3 h-[36rem] w-[36rem] rounded-full opacity-40"
            style={{ background: "radial-gradient(circle, oklch(0.7 0.145 80 / 0.28), transparent 60%)" }}
          />
          {/* State 1 of 3: bare ground. One property change on the grain layer that was already
              here — no new element, no new motion. If it ever reads as visible wallpaper behind
              the headline it is too strong; the ceiling is about 5%. */}
          <div
            className="absolute inset-0 opacity-[0.032]"
            style={{ backgroundImage: BUTI_TILE, backgroundSize: "96px 96px" }}
          />
        </motion.div>

        {/* Top bar: logo + sign in */}
        <header className="relative z-10 mx-auto flex w-full max-w-6xl items-center justify-between px-6 pt-6">
          <div className="flex items-center gap-2.5">
            <WorkshopLogo className="h-10 w-10 rounded-xl shadow-md" />
            <span className="font-display text-lg font-bold tracking-tight text-white">Design Prototype Workshop</span>
          </div>
          <Link
            href={enterHref}
            className="inline-flex h-10 items-center rounded-md border border-white/25 px-5 font-display text-sm font-bold text-white/90 transition hover:border-white/45 hover:bg-white/5 hover:text-white"
          >
            {user ? "Open the app" : "Sign in"}
          </Link>
        </header>

        <motion.div
          style={{ y: yContent, opacity: fade }}
          className="mx-auto flex w-full max-w-6xl flex-1 flex-col justify-center px-6 pb-24 pt-16"
        >
          <div className="grid items-center gap-14 lg:grid-cols-[1.05fr_0.95fr] lg:gap-10">
            {/* Copy */}
            <div className="max-w-2xl">
              <motion.p {...heroEntrance(reduce, 0.05, 0.5, { y: 18 })} className="eyebrow mb-5 !text-gold-300">
                Living craft documentation
              </motion.p>
              <h1 className="font-display text-4xl font-extrabold leading-[1.05] tracking-tight text-white sm:text-5xl lg:text-6xl">
                {HEADLINE.map((line, index) => (
                  // The mask: each line flies up out of its own overflow-hidden slot.
                  <span key={line.text} className="block overflow-hidden pb-[0.08em]">
                    <motion.span
                      {...heroEntrance(reduce, 0.15 + index * 0.09, 0.9, { yPercent: 115 })}
                      className={line.gold ? "block text-gold-gradient" : "block"}
                    >
                      {line.text}
                    </motion.span>
                  </span>
                ))}
              </h1>
              {/*
                THE HEADLINE ABOVE HAS ALWAYS BEEN ABOUT THE WORKSHOP — "The workshop ends. The
                report is already written." — and this paragraph used to describe only the
                repository half, so the page wrote a cheque here and spent every section below it
                cashing a different one. A designer whose actual job is running a workshop in a
                cluster could read the whole thing and not learn the product was for them.

                WHAT WAS TRADED AWAY TO KEEP THE LENGTH. The review ladder and the dataset export
                were dropped from this sentence, not from the page: the ladder is the whole of the
                `#access` section and export is step 4 of `#how-it-works`. "and workshops" left the
                record list for the same reason — the `#records` section names all eight. A hero
                paragraph that lists everything is one nobody finishes.

                The stage count is interpolated from `workshopArc.ts` rather than typed, because it
                is now said in three places on this page and two hand-written counts on one page do
                not rot together (see `AccessLadder`, which learned this about the tier count).
              */}
              <motion.p
                {...heroEntrance(reduce, 0.55, 0.6, { y: 20 })}
                className="mt-6 max-w-xl text-lg leading-relaxed text-white/75"
              >
                A field documentation repository for artisan crafts, and the design &amp; prototype
                workshop that runs on top of it. Record artisans, products, processes and tools, run
                structured interviews that transcribe themselves — then take a designer through{" "}
                {STAGE_COUNT_WORD_LOWER} stages in a cluster and hand over the report at the end of
                it. Captured offline, in the field, where the craft actually happens.
              </motion.p>

              <div className="mt-9 flex flex-wrap items-center gap-4">
                <motion.div {...heroEntrance(reduce, 0.7, 0.5, { y: 16 })}>
                  <Link
                    href={enterHref}
                    className="inline-flex h-12 items-center rounded-md bg-purple-700 px-8 font-display text-lg font-bold tracking-tight text-white shadow-cta transition hover:-translate-y-0.5 hover:bg-purple-600 active:translate-y-0 active:scale-[0.98]"
                  >
                    {user ? "Open the app" : "Enter the repository"}
                  </Link>
                </motion.div>
                <motion.div {...heroEntrance(reduce, 0.78, 0.5, { y: 16 })}>
                  <Link
                    href="/guide"
                    className="inline-flex h-12 items-center rounded-md border border-white/25 px-7 font-display text-lg font-bold tracking-tight text-white/90 transition hover:-translate-y-0.5 hover:border-white/45 hover:bg-white/5 hover:text-white active:translate-y-0"
                  >
                    See the walkthrough
                  </Link>
                </motion.div>
              </div>

              <ul className="mt-10 flex flex-wrap gap-x-7 gap-y-3">
                {TRUST_ITEMS.map(({ icon: Icon, label }, index) => (
                  <motion.li
                    key={label}
                    {...heroEntrance(reduce, 0.88 + index * 0.07, 0.45, { y: 12 })}
                    className="flex items-center gap-2 text-sm text-white/60"
                  >
                    <Icon className="h-4 w-4 text-gold-400" aria-hidden />
                    {label}
                  </motion.li>
                ))}
              </ul>
            </div>

            {/*
              THE TRANSCRIPT CARD — anatomy real, wording labelled.

              This card used to print an invented interview turn ("Two days in running water. My
              grandfather taught me...") attributed to an Interviewee and captioned as genuinely
              recorded, transcribed and linked to an artisan, craft and workshop. On a repository
              whose entire product is citable provenance, fabricating a primary source on the
              marketing page is the most expensive thing it could possibly do.

              What replaced it invents nothing. Every structural element here is what the pipeline
              actually produces: the speaker labels are literally the ones the refinement pass emits
              (`**Interviewer:**`, `**Interviewee:**`, and `**Interviewee 1/2:**` when it can tell
              several apart — services/ai.py), the horizontal rule is the Markdown `---` it inserts
              between distinct topics, and the linked records are real. The wording of the turns
              describes itself and is badged "Illustrative", so no sentence on this page can be
              mistaken for something an artisan said. No record id is invented either.

              The language line is worth being precise about: the system deliberately does NOT tag a
              source language. Scribe auto-detects and Deepgram runs `language=multi`, because these
              interviews code-switch mid-sentence and several are in regional languages with no code
              to name. Printing a tidy "hi-IN → en" chip here would have been a fabricated technical
              claim in place of a fabricated quote.
            */}
            <div className="relative">
              <motion.div
                {...heroEntrance(reduce, 0.5, 0.9, { y: 36, rotate: 1.2 })}
                className="glass-dark rounded-xl p-5 shadow-lg"
              >
                <div className="mb-4 flex items-center justify-between gap-3">
                  <div className="flex items-center gap-2 text-sm font-semibold text-white/85">
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-gold-500/15 text-gold-300">
                      <Mic className="h-4 w-4" aria-hidden />
                    </span>
                    Questionnaire — transcript
                  </div>
                  <span className="shrink-0 rounded-full border border-white/25 px-2.5 py-1 text-xs font-semibold text-white/70">
                    Illustrative
                  </span>
                </div>
                <div className="space-y-3 rounded-md bg-white/[0.06] p-4 text-sm leading-relaxed text-white/80">
                  <p>
                    <strong className="text-gold-200">Interviewer:</strong> Each question from the
                    questionnaire, in the order it was asked.
                  </p>
                  <p>
                    <strong className="text-white">Interviewee:</strong>{" "}
                    The artisan&rsquo;s answer, transcribed and then translated into English.
                  </p>
                  {/* The Markdown `---` the refinement pass writes between distinct topics. */}
                  <div className="h-px bg-white/10" />
                  <p>
                    <strong className="text-white">Interviewee 2:</strong> Where several artisans sit
                    in on one interview, each one gets their own label.
                  </p>
                </div>
                <ul className="mt-4 flex flex-wrap gap-2">
                  {TRANSCRIPT_LINKS.map(({ icon: Icon, label }) => (
                    <li
                      key={label}
                      className="inline-flex items-center gap-1.5 rounded-full bg-white/10 px-2.5 py-1 text-xs font-medium text-white/75"
                    >
                      <Icon className="h-3.5 w-3.5 text-white/50" aria-hidden />
                      {label}
                    </li>
                  ))}
                </ul>
                <p className="mt-4 text-xs leading-relaxed text-white/50">
                  The anatomy of a finished transcript — the wording is illustrative, not an
                  interview from the repository. The spoken language is detected rather than assumed:
                  these recordings code-switch between Hindi and English, and some are in Marwari or
                  Garhwali.
                </p>
              </motion.div>
            </div>
          </div>
        </motion.div>

        <motion.div
          {...heroEntrance(reduce, 1.4, 0.6)}
          aria-hidden
          // Hidden below sm: at 390 the hero column runs the full height of the screen and this
          // chevron sat on top of the transcript card's caption. A phone does not need to be told
          // the page scrolls, so the fix is to remove it rather than to pad around it.
          className="pointer-events-none absolute bottom-6 left-1/2 hidden -translate-x-1/2 text-white/40 sm:block"
        >
          {/* animate-bounce is CSS, so globals.css already stops it under reduced motion. */}
          <ChevronDown className="h-6 w-6 animate-bounce" />
        </motion.div>
      </section>

      {/* ── What the repository holds ────────────────────────────────────── */}
      <section id="records" className="mx-auto max-w-6xl px-6 py-24">
        <p className="eyebrow mb-3">One connected repository</p>
        <h2 className="max-w-2xl font-display text-3xl font-bold tracking-tight text-ink-900 sm:text-4xl">
          Eight record types, linked to each other from the moment they are captured.
        </h2>
        <p className="mt-4 max-w-2xl text-base leading-relaxed text-ink-500">
          An artisan carries into their products; a product carries into the process that makes it
          and the tools it takes; every interview, photograph and recording lands attached to the
          artisan, the craft and the workshop it came from. Nothing arrives as a loose file.
        </p>
        {/*
          WHAT CHANGED UNDER THIS SECTION, AND WHY IT IS A SECOND PARAGRAPH RATHER THAN A CLAUSE.
          Six of the eight forms above now also carry a "Design & prototype workshop" box
          (`forms/DesignWorkshopSelect.tsx` sets those words as the default `label`, so they are
          quoted and not paraphrased) with the workshop this account was most recently given access
          to already chosen (`lib/designWorkshopDefault.ts`). That is not one more field on a list
          of fields — it is a change to what "one connected repository" MEANS, because it is the
          join between the two halves of this page: the records above and the workshop three
          sections below. A clause bolted onto the sentence above would have buried the one fact
          that connects them.

          ⚠ SIX, NOT EIGHT, AND THIS PARAGRAPH SAID "ALL EIGHT" UNTIL IT WAS COUNTED. There are six
          `<DesignWorkshopSelect>` mount sites and the grep that finds them is
          `grep -rn "<DesignWorkshopSelect" components app`: Artisan, Product, Process and Tool in
          `components/forms/`, plus Miscellaneous Media (`app/(protected)/media/page.tsx`) and
          Questionnaire (`app/(protected)/questionnaire/page.tsx`).

          CRAFT AND WORKSHOP ARE NOT AMONG THEM, and the reason is a missing COLUMN rather than a
          missing box — so this is not a gap somebody can close by mounting the control. In
          `schema.prisma`, `designWorkshopId` is declared on Artisan, ProductDocumentation,
          ToolDocumentation, MediaFile, Process, Questionnaire and QuestionnaireInterview, and that
          column's own header counts them in its own words: "it holds for every one of these six".
          `model Craft` carries `workshopId` (the field expedition) and nothing else, and `model
          Workshop` is itself the container. A craft is a `name String @unique` — one shared
          tradition row that many artisans point at — so filing it under a single design workshop
          would be a claim about the tradition rather than about a record of it.

          THE FALSE UNIVERSAL WAS THE WORST OF THE AVAILABLE SENTENCES, which is worth stating
          because the obvious edit is to put "all eight" back for rhythm. "All eight" is the claim a
          designer checks by opening the craft form, finding no box, and concluding the feature is
          broken — where the truth is that it was never claimed for that form. That is the same
          failure the RECORD_TYPES comment above guards against, running the other way: there, an
          omission on a list of eight read as an absence; here, a universal reads as a promise.

          THE DEFAULT IS DESCRIBED AS A SUGGESTION AND NEVER AS A SCOPE, which is the distinction
          that file's own header insists on: "Nothing here narrows what a designer may pick." A page
          saying the app "files your records under your workshop" would describe a client-side scope
          the API does not have, and would read to an admin as a promise their records are fenced.
        */}
        <p className="mt-3 max-w-2xl text-base leading-relaxed text-ink-500">
          Six of the eight also file under a design &amp; prototype workshop — everything captured
          at one, which is artisans, products, processes, tools, media and questionnaires. A craft
          is a tradition many workshops document and the field workshop is itself a container, so
          neither carries the box. Where it is there it arrives already holding the workshop you
          were most recently given access to, so a fortnight of records lands in the right place
          without anybody choosing it on every record. It is a suggestion and not a fence: the list
          still offers every workshop you are on, and a record can be filed under none of them.
        </p>
        <div className="mt-12 grid grid-cols-2 gap-4 md:grid-cols-4">
          {RECORD_TYPES.map((record) => (
            <div
              key={record.title}
              className="rounded-lg border border-line-200 bg-card p-5 shadow-sm transition hover:-translate-y-0.5 hover:shadow-md"
            >
              <span className="mb-4 flex h-10 w-10 items-center justify-center rounded-md bg-purple-700 text-white">
                <record.icon className="h-5 w-5" aria-hidden />
              </span>
              <h3 className="font-display text-sm font-bold text-ink-900">{record.title}</h3>
              <p className="mt-1.5 text-sm leading-relaxed text-ink-500">{record.copy}</p>
            </div>
          ))}
        </div>
        <div className="mt-8 flex flex-wrap items-center gap-2">
          <span className="text-sm font-medium text-ink-700">Built on top:</span>
          {SURFACES.map((surface) => (
            <span
              key={surface}
              className="rounded-full border border-line-200 bg-surface-50 px-3 py-1.5 text-xs font-medium text-ink-700"
            >
              {surface}
            </span>
          ))}
        </div>
      </section>

      {/* ── How it works ─────────────────────────────────────────────────── */}
      <HowItWorks />

      {/*
        ── The designer half ─────────────────────────────────────────────────

        THREE SECTIONS, IN THIS ORDER, AND THE ORDER IS THE ARGUMENT. The two sections above are the
        repository: what it holds, and how a recording becomes a dataset. These three are the other
        half of the product, which this page did not mention at all — the workshop that is RUN rather
        than filed, the person who runs it, and the document that falls out of the end.

        THEY SIT HERE RATHER THAN HIGHER UP because "eight linked record types" is what the workshop
        stages point AT: stage 11 records sketches, stage 13 prototypes, and the reference pickers
        inside the stages choose the artisans, products and tools the sections above describe. Read
        the other way round, the stages arrive before the things they refer to.

        THEY SIT BEFORE THE WALKTHROUGH CALLOUT because that callout is the page's "and here is
        somebody to show you" — and its chapter list now names the workshop chapters `/guide`
        actually has, which only reads as a promise if the workshop has already been introduced.

        `DesignerWorkbench` is the tinted one. Three token-white sections in a row is a wall, and the
        band in the middle is also the page's own way of saying "this middle one is about a person
        rather than a mechanism".
      */}
      <DesignWorkshopSection />
      <DesignerWorkbench />
      <ReportEngine />

      {/* ── Walkthrough ──────────────────────────────────────────────────── */}
      <WalkthroughCallout />

      {/* ── The pilot collection, on cloth ───────────────────────────────── */}
      <PrintingBed census={census} />

      {/* ── The access ladder (its row count and heading derive from ROLE_RANK) ── */}
      <AccessLadder />

      {/* ── Built for the whole team ─────────────────────────────────────── */}
      <TeamSection />

      {/* ── FAQ ──────────────────────────────────────────────────────────── */}
      <HeroFAQ />

      {/* ── Final CTA ────────────────────────────────────────────────────── */}
      <section className="relative isolate overflow-hidden grad-brand px-6 py-20 text-center">
        {/* State 3 of 3: the finished length. Fully printed, completely static, and no gold —
            this band never animates, so the bed stays the page's single signature moment. The
            radial mask fades the cloth away from the centre, which both keeps every pixel of
            contrast behind the heading and the buttons untouched and lets the print run out
            toward the selvedges. */}
        <div
          aria-hidden
          className="pointer-events-none absolute inset-0 opacity-[0.05]"
          style={{
            backgroundImage: BUTI_TILE,
            backgroundSize: "104px 104px",
            maskImage: "radial-gradient(72% 66% at 50% 50%, transparent 30%, black 88%)",
            WebkitMaskImage: "radial-gradient(72% 66% at 50% 50%, transparent 30%, black 88%)"
          }}
        />
        <h2 className="relative font-display text-3xl font-bold tracking-tight text-white sm:text-4xl">
          Ready to document living craft?
        </h2>
        {/* This paragraph is the last thing a visitor reads before the sign-in button, so it is
            where the allow-list has to be said. It previously read "new accounts start as
            Crowdsource Volunteers and are elevated by an admin", which described a door that no
            longer opens: an address nobody has admitted now gets a pending request rather than an
            account. Sending somebody to a sign-in screen without telling them makes the refusal
            there read as a fault in the app. */}
        <p className="relative mx-auto mt-3 max-w-xl text-white/75">
          Sign in with your researcher account, or with Google. Access is by invitation: an
          administrator admits your address first, and a new sign-in from an address that is not yet
          on the list becomes a request for approval rather than an account.
        </p>
        <div className="relative mt-8 flex flex-wrap items-center justify-center gap-4">
          <Link
            href={enterHref}
            className="inline-flex h-12 items-center rounded-md bg-white px-8 font-display text-lg font-bold tracking-tight text-purple-800 shadow-lg transition hover:-translate-y-0.5 active:translate-y-0"
          >
            {user ? "Open the app" : "Enter the repository"}
          </Link>
          <Link
            href="/guide"
            className="inline-flex h-12 items-center rounded-md border border-white/30 px-7 font-display text-lg font-bold tracking-tight text-white transition hover:-translate-y-0.5 hover:bg-white/10 active:translate-y-0"
          >
            Take the walkthrough
          </Link>
        </div>
      </section>

      {/* ── The institutions, and the Centre's own site ──────────────────── */}
      {/* Static by design — see the INSTITUTIONS header for why this band must not be `whileInView`. */}
      <section aria-label="Institutional affiliation" className="bg-card px-6 py-14">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-9 text-center">
          <div className="max-w-2xl">
            <p className="eyebrow mb-3">The institutions behind it</p>
            {/*
              NO `<h2>` HERE, DELIBERATELY. Every other band on this page opens with one, and a
              fourteenth heading landing between "Ready to document living craft?" and the footer
              would put a fresh section in the document outline at the exact moment the page has
              finished making its argument. This is a colophon, so it reads as a rule rather than as
              a band, and the `aria-label` on the section is what names it to a screen reader.

              THE TWO HALVES OF THIS SENTENCE HAVE DIFFERENT KINDS OF EVIDENCE, and a later editor
              should know which is which. The Development Commissioner half is read out of the code:
              `report_templates.py:362-371` declares DCH_STANDARD as "the full narrative report for
              submission to the Development Commissioner (Handicrafts)" with
              `organisation="Office of the Development Commissioner (Handicrafts)"`, and
              `report_builder.py:2664` puts "Government of India • Ministry of Textiles" above it on
              the cover. The affiliation half is the owner's, who supplied both marks and all three
              destinations. Do not "verify" the first sentence by weakening the second, and do not
              strengthen the second into a claim about funding or sanction that nothing here checks.
            */}
            <p className="text-base leading-relaxed text-ink-700">
              A Centre of Excellence project at IIT Kharagpur. The document this app writes is
              addressed to a real office: its default template is a submission to the Office of the
              Development Commissioner (Handicrafts), and the cover carries the Government of India
              and Ministry of Textiles line above it.
            </p>
          </div>

          <ul className="flex flex-wrap items-start justify-center gap-x-6 gap-y-8 sm:gap-x-12">
            {INSTITUTIONS.map((institution) => (
              <li key={institution.href} className="w-36 sm:w-52">
                <a
                  href={institution.href}
                  target="_blank"
                  rel="noreferrer"
                  // Hover is CSS, never a framer prop, so the reduced-motion blocks in globals.css
                  // reach it — the same rule every card on this page follows.
                  className="group flex flex-col items-center gap-3 rounded-md transition hover:-translate-y-0.5 active:translate-y-0"
                >
                  <span className="flex h-20 w-32 items-center justify-center rounded-lg border border-line-200 bg-logo-cream shadow-sm transition group-hover:shadow-md sm:h-24 sm:w-40">
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={institution.src}
                      alt=""
                      width={institution.width}
                      height={institution.height}
                      className={institution.markClass}
                    />
                  </span>
                  <span className="text-xs font-medium leading-snug text-ink-700 transition group-hover:text-purple-700">
                    {institution.name}
                    <span className="sr-only"> (opens in a new tab)</span>
                  </span>
                </a>
              </li>
            ))}
          </ul>

          <p className="max-w-2xl text-sm leading-relaxed text-ink-500">
            The Centre keeps its own site — its account of itself, its research, and the crafts it
            holds. It is a separate application rather than a section of this one, so this link
            leaves the repository:{" "}
            <a
              href={CENTRE_OF_EXCELLENCE_HREF}
              target="_blank"
              rel="noreferrer"
              className="inline-flex items-center gap-1.5 font-medium text-purple-700 underline-offset-2 hover:underline"
            >
              Open the Centre of Excellence site at cxa-cms.vercel.app
              {/* Text, then a trailing external-link glyph — `settings/MyAiKeysPanel.tsx:176` is the
                  shape this repository already uses for an anchor that leaves the app. */}
              <ExternalLink className="h-3.5 w-3.5 shrink-0" aria-hidden />
              <span className="sr-only">(opens in a new tab)</span>
            </a>
          </p>
        </div>
      </section>

      <footer className="border-t border-line-200 bg-card px-6 py-10">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-4 text-center">
          <div className="flex items-center gap-2">
            <WorkshopLogo className="h-6 w-6 rounded-md" />
            <span className="font-display font-bold text-ink-900">Design Prototype Workshop</span>
          </div>
          <nav aria-label="Footer" className="flex flex-wrap items-center justify-center gap-x-6 gap-y-2 text-sm">
            <Link href={enterHref} className="text-ink-700 transition hover:text-purple-700">
              Sign in
            </Link>
            <a href="#records" className="text-ink-700 transition hover:text-purple-700">
              What it captures
            </a>
            <a href="#how-it-works" className="text-ink-700 transition hover:text-purple-700">
              How it works
            </a>
            {/* Two anchors for three sections: `#designer` sits between these and is reached by
                scrolling from either, and a footer that lists every heading on the page stops being
                a way of getting anywhere. */}
            <a href="#workshop" className="text-ink-700 transition hover:text-purple-700">
              The workshop
            </a>
            <a href="#report" className="text-ink-700 transition hover:text-purple-700">
              The report
            </a>
            <a href="#access" className="text-ink-700 transition hover:text-purple-700">
              Access ladder
            </a>
            <Link href="/guide" className="text-ink-700 transition hover:text-purple-700">
              Walkthrough
            </Link>
            <a href="#faq" className="text-ink-700 transition hover:text-purple-700">
              FAQ
            </a>
          </nav>
          <p className="text-xs text-ink-500">Field documentation for artisans, crafts and living knowledge.</p>
        </div>
      </footer>
    </div>
  );
}
