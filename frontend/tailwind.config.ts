import type { Config } from "tailwindcss";

/**
 * Design Prototype Workshop design tokens on Tailwind v3. THIS FILE IS THE SOURCE OF TRUTH for
 * every colour, radius, shadow and gradient the web app uses.
 *
 * Purple ramp: OKLCH, hue locked at 305°; purple-700 is THE action color.
 * Tinted neutrals (ink/line/surface/bg-0) replace grey. Gold is a marketing
 * accent (hero + auth only). Shadows are purple-tinted.
 *
 * THERE ARE NOW THREE LITERAL RAMPS AND ONLY ONE OF THEM IS AN ACTION COLOUR. `ministry` (below)
 * is the four ministry-only screens' SURFACE accent — grounds, borders, the header chip, desk
 * tiles — and is spent on no button, input or focus ring anywhere. Its own block says why.
 *
 * The legacy `field`/`thread` scales are kept as aliases onto the new ramp so
 * existing pages restyle without a rewrite (field-500/600/700 → purple actions,
 * field-50/100/200/300 → surfaces, field-900 → ink).
 *
 * Theming: every SEMANTIC neutral resolves through a CSS custom property declared in
 * app/globals.css as a bare "R G B" triplet, so `data-theme="dark"` on <html> repaints the whole
 * app without a single page edit. `<alpha-value>` keeps `bg-card/70`-style modifiers working.
 * The purple, gold and ministry ramps stay literal — brand colour does not invert.
 */
const neutral = (token: string) => `rgb(var(--${token}) / <alpha-value>)`;

const purple = {
  50: "oklch(0.977 0.013 305 / <alpha-value>)",
  100: "oklch(0.946 0.03 305 / <alpha-value>)",
  200: "oklch(0.9 0.058 305 / <alpha-value>)",
  300: "oklch(0.828 0.1 305 / <alpha-value>)",
  400: "oklch(0.738 0.15 305 / <alpha-value>)",
  500: "oklch(0.648 0.19 305 / <alpha-value>)",
  600: "oklch(0.56 0.205 305 / <alpha-value>)",
  700: "oklch(0.47 0.198 305 / <alpha-value>)",
  800: "oklch(0.4 0.18 305 / <alpha-value>)",
  900: "oklch(0.34 0.15 305 / <alpha-value>)",
  950: "oklch(0.255 0.108 305 / <alpha-value>)"
};

const gold = {
  100: "oklch(0.95 0.045 90 / <alpha-value>)",
  200: "oklch(0.9 0.08 88 / <alpha-value>)",
  300: "oklch(0.85 0.11 86 / <alpha-value>)",
  400: "oklch(0.78 0.135 84 / <alpha-value>)",
  500: "oklch(0.7 0.145 80 / <alpha-value>)",
  600: "oklch(0.6 0.13 75 / <alpha-value>)",
  700: "oklch(0.5 0.11 70 / <alpha-value>)"
};

/*
 * MINISTRY — the surface accent for the four ministry-only screens, and NOT a second action colour.
 *
 * ── WHAT IT IS SPENT ON, AND WHAT IT IS NEVER SPENT ON ────────────────────────────────────────
 *
 * Pale grounds, borders, the page header's icon chip and the ministry desk's tiles. `.field-button`,
 * `.field-input`, the focus ring, `--purple-700` and `shadow-cta` are UNTOUCHED, so non-negotiable 1
 * ("purple-700 is the only action colour, no second accent on a data screen, ever") is not broken:
 * a ministry page's buttons and inputs are the same purple as every other page's.
 *
 * That restraint is not taste, it is arithmetic. `ministry-700` computes to #923e0d and `amber-800`
 * — the "Withdrawn" pill — is #92400e: ΔE 0.004 in OKLab, i.e. THE SAME COLOUR, and amber is drawn
 * on all four of these screens. An orange "Upload the plan" button would sit two inches from an
 * amber pill in a colour nobody could tell apart, and `hover:shadow-cta` would throw a saturated
 * PURPLE glow off it into the bargain (see boxShadow.cta below — it is a literal oklch at hue 305).
 * Spent on grounds and borders instead, the collision cannot arise: a pale wash and a dark pill's
 * text are different jobs and are never asked to be told apart.
 *
 * ── WHY IT IS CALLED `ministry` AND NOT `orange` ──────────────────────────────────────────────
 *
 * `orange` is a stock Tailwind scale, so declaring it here would DEEP-MERGE with stock exactly as
 * `amber` does below — `orange-50`, `orange-400` and the rest would silently resolve to Tailwind's
 * values, which do not pair with these rungs. The name is also the scoping rule, the way `thread`,
 * `logo` and `gold` carry theirs: a scale called `orange` invites use on a data screen.
 *
 * ── THE LADDER IS PURPLE'S, THE CHROMA IS NOT ─────────────────────────────────────────────────
 *
 * Every LIGHTNESS is purple's rung-for-rung, so `purple-300` → `ministry-300` swaps 1:1 and every
 * existing pairing keeps its relationship. The CHROMA had to be re-derived: purple's 0.19–0.205 at
 * these lightnesses is outside the sRGB gamut at every orange hue, and a browser clips it silently —
 * the ladder would stop holding with nothing on screen to say so. Each rung here is
 * min(purple's chroma, 0.94 × the sRGB gamut maximum at hue 45) and all eleven were checked in
 * gamut; the hex beside each is what it resolves to.
 *
 * ── DARK MODE IS THE EXPOSURE, AND IT IS PURPLE'S EXPOSURE EXACTLY ────────────────────────────
 *
 * This ramp is LITERAL and does not invert, for the reason purple does not. `text-ministry-700` on a
 * dark `bg-card` measures 2.44:1 — under the 4.5:1 floor. That is not a regression (`text-purple-700`
 * is already 2.32:1 in the same position) but it multiplies across every new site, so EVERY ministry
 * text site on a card carries a `dark:` pair: `dark:text-ministry-300` is 10.06:1. The precedents are
 * `components/forms/CarryContextBanner.tsx` and `components/ui/SearchableSelect.tsx`, whose comment
 * already names this exact failure for purple.
 *
 * High contrast does NOT reach this ramp, exactly as it does not reach purple: the
 * `[data-high-contrast]` blocks in globals.css re-point neutrals only. Consistent, and said out loud
 * here so it is not filed as a bug.
 */
const ministry = {
  50: "oklch(0.977 0.011 45 / <alpha-value>)", /* #fef5f1 */
  100: "oklch(0.946 0.027 45 / <alpha-value>)", /* #fee8df */
  200: "oklch(0.9 0.053 45 / <alpha-value>)", /* #fdd4c2 */
  300: "oklch(0.828 0.096 45 / <alpha-value>)", /* #fcb393 */
  400: "oklch(0.738 0.15 45 / <alpha-value>)", /* #f68854 */
  500: "oklch(0.648 0.175 45 / <alpha-value>)", /* #e1631a */
  600: "oklch(0.56 0.151 45 / <alpha-value>)", /* #b95014 */
  700: "oklch(0.47 0.127 45 / <alpha-value>)", /* #923e0d — surface accent, never an action colour */
  800: "oklch(0.4 0.109 45 / <alpha-value>)", /* #753007 */
  900: "oklch(0.34 0.093 45 / <alpha-value>)", /* #5d2404 */
  950: "oklch(0.255 0.072 45 / <alpha-value>)" /* #3e1400 */
};

/**
 * ── THE FOUR GROUP TONES, ADDED 2026-09-20 — NAVIGATION COLOUR, AND NEVER AN ACTION COLOUR ──────
 *
 * Owner ruling: the dashboard's mega cards must be "colour coded so that it is easier for people to
 * understand and navigate", and the ministry one must wear the mango from
 * `https://transaction-flow-analyser.vercel.app/`.
 *
 * ── WHAT THESE MAY AND MAY NOT PAINT, WHICH IS THE WHOLE OF WHY THEY ARE SAFE ───────────────────
 *
 * Non-negotiable 1 still stands: **purple-700 is the only action colour**, with the five
 * `ministry: true` routes as the one scoped exception. These ramps are spent EXACTLY where
 * `components/dashboard/MinistryDeskCard.tsx` already spends `ministry`, and nowhere else:
 *
 *   • an `aria-hidden` icon chip — `bg-X-100 text-X-700 dark:bg-X-950/40 dark:text-X-300`
 *   • a hover border — `hover:border-X-300`
 *   • the collapsed card's chevron ink, which is the same ink as the chip
 *
 * They may NOT paint a button, an input, a focus ring, a left-edge accent rule (globals.css:642-661
 * is a tombstone for exactly that) or a full card ground that reads as a page canvas. And by
 * non-negotiable 5 the tone is never the only channel: every mega card keeps its GROUP TITLE and its
 * one-line note as the primary signal, so a reader who cannot separate teal from indigo loses
 * nothing at all.
 *
 * ── WHY FOUR NEW NAMES AND NOT `teal` / `indigo` / `rose` / `orange` ────────────────────────────
 *
 * A key that collides with a stock Tailwind scale DEEP-MERGES with it. `amber` in this very file is
 * the standing proof: only 100/500/800 are brand, and `amber-50` / `amber-200` silently resolve to
 * stock values that do not pair with them. So each ramp is named for its SCOPE exactly as `ministry`
 * is — `ministry` is not called "orange" for the same reason.
 *
 * ── THE DERIVATION IS PURPLE'S, RUNG FOR RUNG ──────────────────────────────────────────────────
 *
 * Same eleven lightnesses as `purple`; chroma is `min(purple's chroma at that rung, 0.94 × the sRGB
 * gamut maximum at this hue and lightness)` — the identical rule the `ministry` ramp above was
 * derived with, so a 700 is the same *weight* in every family and a chip swap changes hue only.
 *
 * ── THE MANGO IS MEASURED FROM THE SITE THE OWNER NAMED, NOT EYEBALLED ─────────────────────────
 *
 * That site's stylesheet carries `--primary: 39 100% 50%` light and `39 80% 40%` dark, with
 * `--accent: 25 95% 55%`. Converted:
 *
 *     light  primary  hsl(39 100% 50%) = #FFA600 = oklch(0.794 0.171 71.2)
 *     dark   primary  hsl(39 80% 40%)  = #B87E14 = oklch(0.637 0.129 75.1)
 *     light  accent   hsl(25 95% 55%)  = #F97A1F = oklch(0.715 0.180 49.5)
 *
 * Hue 71 is therefore the mango, and `mango-500` below — `oklch(0.648 0.131 71)` — reproduces that
 * site's DARK primary to about ΔL 0.011 / ΔC 0.002. Its light primary sits between rungs 300 and 400
 * and is not pinned to a rung, because a ladder with one rung off it is a ladder nobody can reason
 * about. Its accent lands at hue 49.5, which is this repo's EXISTING `ministry` ramp (hue 45) — the
 * two palettes already agree, which is why mango EXTENDS the ministry surface rather than replacing
 * it.
 *
 * ⚠ `mango` DOES NOT REPLACE `ministry`, AND MUST NOT. The hue-45 ramp is what every `dark:` pair,
 * the `cta-ministry` shadow, `docs/DECISION-ministry-orange-action-controls.md` and three test files
 * are written against, and mango's bright rungs cannot carry white text (#FFA600 against white is
 * 1.96:1). `ministry` stays the ACTION colour on the five ministry routes; `mango` is the ministry
 * mega card's navigation MARK, and it is only ever a chip, an ink or a hover border.
 */
const archive = {
  50: "oklch(0.977 0.013 195 / <alpha-value>)", /* #eefafa */
  100: "oklch(0.946 0.03 195 / <alpha-value>)", /* #d7f4f3 */
  200: "oklch(0.9 0.058 195 / <alpha-value>)", /* #b1ebea */
  300: "oklch(0.828 0.1 195 / <alpha-value>)", /* #6fdbdb */
  400: "oklch(0.738 0.118 195 / <alpha-value>)", /* #2bc1c1 */
  500: "oklch(0.648 0.104 195 / <alpha-value>)", /* #21a2a2 */
  600: "oklch(0.56 0.09 195 / <alpha-value>)", /* #198585 */
  700: "oklch(0.47 0.075 195 / <alpha-value>)", /* #136868 — Records' chip ink on a light card */
  800: "oklch(0.4 0.064 195 / <alpha-value>)", /* #0c5252 */
  900: "oklch(0.34 0.055 195 / <alpha-value>)", /* #064141 */
  950: "oklch(0.255 0.041 195 / <alpha-value>)" /* #032929 */
};

const errand = {
  50: "oklch(0.977 0.01 255 / <alpha-value>)", /* #f3f8fe */
  100: "oklch(0.946 0.025 255 / <alpha-value>)", /* #e2eefe */
  200: "oklch(0.9 0.046 255 / <alpha-value>)", /* #cae0fd */
  300: "oklch(0.828 0.082 255 / <alpha-value>)", /* #a3cafc */
  400: "oklch(0.738 0.129 255 / <alpha-value>)", /* #70adfa */
  500: "oklch(0.648 0.18 255 / <alpha-value>)", /* #338ef9 */
  600: "oklch(0.56 0.173 255 / <alpha-value>)", /* #1673d6 */
  700: "oklch(0.47 0.146 255 / <alpha-value>)", /* #0e59aa — Miscellaneous' chip ink */
  800: "oklch(0.4 0.124 255 / <alpha-value>)", /* #094788 */
  900: "oklch(0.34 0.106 255 / <alpha-value>)", /* #05376d */
  950: "oklch(0.255 0.08 255 / <alpha-value>)" /* #022248 */
};

const steward = {
  50: "oklch(0.977 0.011 15 / <alpha-value>)", /* #fff5f5 */
  100: "oklch(0.946 0.026 15 / <alpha-value>)", /* #fee7e7 */
  200: "oklch(0.9 0.049 15 / <alpha-value>)", /* #fdd2d4 */
  300: "oklch(0.828 0.091 15 / <alpha-value>)", /* #fcafb4 */
  400: "oklch(0.738 0.15 15 / <alpha-value>)", /* #fa7f8b */
  500: "oklch(0.648 0.19 15 / <alpha-value>)", /* #eb5068 */
  600: "oklch(0.56 0.205 15 / <alpha-value>)", /* #d1234c */
  700: "oklch(0.47 0.177 15 / <alpha-value>)", /* #a71439 — Admin's chip ink */
  800: "oklch(0.4 0.151 15 / <alpha-value>)", /* #860d2c */
  900: "oklch(0.34 0.128 15 / <alpha-value>)", /* #6a0922 */
  950: "oklch(0.255 0.096 15 / <alpha-value>)" /* #460413 */
};

const mango = {
  50: "oklch(0.977 0.013 71 / <alpha-value>)", /* #fdf6ee */
  100: "oklch(0.946 0.03 71 / <alpha-value>)", /* #faead8 */
  200: "oklch(0.9 0.058 71 / <alpha-value>)", /* #f7d8b5 */
  300: "oklch(0.828 0.1 71 / <alpha-value>)", /* #f0bc7d — the ministry chip's ink in dark theme */
  400: "oklch(0.738 0.149 71 / <alpha-value>)", /* #e49824 */
  500: "oklch(0.648 0.131 71 / <alpha-value>)", /* #c07f1c — the named site's dark primary */
  600: "oklch(0.56 0.113 71 / <alpha-value>)", /* #9d6716 */
  700: "oklch(0.47 0.095 71 / <alpha-value>)", /* #7c500e — the ministry chip's ink on a light card */
  800: "oklch(0.4 0.081 71 / <alpha-value>)", /* #633f09 */
  900: "oklch(0.34 0.069 71 / <alpha-value>)", /* #4e3105 */
  950: "oklch(0.255 0.052 71 / <alpha-value>)" /* #321e02 */
};

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}", "./lib/**/*.{ts,tsx}"],
  // ThemeProvider stamps data-theme onto <html>; the "class" strategy keeps `dark:` usable too.
  darkMode: ["class", '[data-theme="dark"]'],
  theme: {
    extend: {
      fontFamily: {
        sans: ["var(--font-inter)", "ui-sans-serif", "system-ui", "sans-serif"],
        display: ["var(--font-jakarta)", "var(--font-inter)", "ui-sans-serif", "sans-serif"],
        // Legacy slots — everything resolves to the two brand faces.
        serif: ["var(--font-jakarta)", "var(--font-inter)", "ui-sans-serif", "sans-serif"]
      },
      colors: {
        purple,
        gold,
        ministry,
        archive,
        errand,
        steward,
        mango,
        ink: {
          DEFAULT: neutral("ink-900"),
          900: neutral("ink-900"),
          700: neutral("ink-700"),
          500: neutral("ink-500"),
          300: neutral("ink-300"),
          // Legacy aliases used across existing pages.
          body: neutral("ink-700"),
          muted: neutral("ink-500"),
          soft: neutral("ink-300")
        },
        line: { 200: neutral("line-200") },
        surface: { 50: neutral("surface-50") },
        "bg-0": neutral("bg-0"),
        // Legacy `field` scale → mapped onto the new system. 50–300 are the tinted surface ladder
        // (themed); 400–700 are the purple ramp (brand, never inverted); 900 is heading ink.
        field: {
          50: neutral("surface-50"),
          100: neutral("surface-100"),
          200: neutral("surface-200"),
          300: neutral("surface-300"),
          400: purple[400],
          500: purple[600],
          600: purple[700],
          700: purple[800],
          900: neutral("ink-900")
        },
        thread: { DEFAULT: gold[500], soft: gold[200] },
        // Brand-native logo colors (Android launcher icon) — never re-themed.
        logo: { cream: "#FAF9F5", terracotta: "#CC785C", ink: "#181715" },
        amber: { 100: "#fef3c7", 500: "#f59e0b", 800: "#92400e" },
        success: { 100: "#dcfce7", 600: "#15803d" },
        error: { 100: "#fee2e2", 600: "#dc2626" },
        background: neutral("bg-0"),
        foreground: neutral("ink-900"),
        // `card` is the themed stand-in for the old literal bg-white on every panel/card surface.
        // Tailwind's built-in `white` is deliberately untouched: text-white on purple stays white.
        card: neutral("card"),
        popover: neutral("card"),
        border: neutral("line-200"),
        input: neutral("line-200"),
        ring: purple[600],
        accent: purple[50],
        "accent-foreground": purple[700],
        primary: purple[700],
        "primary-foreground": "#ffffff",
        secondary: neutral("ink-500"),
        "secondary-foreground": neutral("card"),
        destructive: "#dc2626",
        "destructive-foreground": "#ffffff",
        muted: neutral("surface-50"),
        "muted-foreground": neutral("ink-500")
      },
      borderRadius: {
        sm: "8px",
        md: "12px",
        lg: "16px",
        xl: "24px"
      },
      boxShadow: {
        sm: "0 1px 2px rgba(46, 16, 101, 0.06)",
        soft: "0 1px 2px rgba(46, 16, 101, 0.06)",
        md: "0 4px 16px rgba(46, 16, 101, 0.08)",
        lg: "0 8px 32px rgba(46, 16, 101, 0.12)",
        panel: "0 8px 32px rgba(46, 16, 101, 0.12)",
        island: "0 4px 16px rgba(46, 16, 101, 0.12), 0 1px 2px rgba(46, 16, 101, 0.06)",
        cta: "0 8px 24px oklch(0.47 0.198 305 / 0.28)",
        glow: "0 8px 24px oklch(0.47 0.198 305 / 0.28)",
        "glow-soft": "0 4px 16px oklch(0.47 0.198 305 / 0.16)",
        /*
         * THE MINISTRY TWIN OF `cta`, AT HUE 45, AND IT EXISTS BECAUSE A SCOPED SELECTOR CANNOT
         * REACH THE ORIGINAL.
         *
         * `.field-button` carries `hover:shadow-cta`, and that is a Tailwind TOKEN compiled into a
         * utility class — not a CSS custom property — so no `[data-surface="ministry"]` rule can
         * re-point it. Before this rung existed, the ministry block's own header named the
         * consequence as a reason not to repaint the button at all: an orange primary would
         * "through `hover:shadow-cta`, throw a saturated PURPLE glow while doing it". The owner
         * overruled the no-orange-buttons ruling on 2026-09-20; the glow is the half of that
         * refusal which was a MEASUREMENT rather than a judgement, so it is answered rather than
         * waived.
         *
         * THE ARITHMETIC. Geometry is `cta`'s, untouched — same offset, same blur — so the two
         * shadows sit at the same visual depth and a reader moving between a ministry page and any
         * other meets one elevation language. Lightness is `ministry-700`'s own 0.47, identical to
         * `purple-700`'s, and the chroma is the ramp's own gamut-clipped 0.127 at this hue rather
         * than purple's 0.198, which is outside sRGB at hue 45 and would be silently clipped by the
         * browser — the same re-derivation every rung of the ramp above went through.
         *
         * ALPHA 0.29 AND NOT 0.28, WHICH IS THE ONE NUMBER THAT MOVED. `ministry-700` is 8.6%
         * lighter in relative luminance than `purple-700`, so at equal alpha the orange glow reads
         * weaker against the same page. Raising it by one hundredth restores equal perceived weight.
         * It is a small correction and it is written down because an unexplained 0.29 beside an 0.28
         * reads as a typo and would be "fixed" back.
         */
        "cta-ministry": "0 8px 24px oklch(0.47 0.127 45 / 0.29)"
      },
      transitionTimingFunction: {
        out: "cubic-bezier(0.16, 1, 0.3, 1)",
        spring: "cubic-bezier(0.34, 1.56, 0.64, 1)"
      },
      keyframes: {
        "accordion-down": {
          from: { height: "0" },
          to: { height: "var(--radix-accordion-content-height)" }
        },
        "accordion-up": {
          from: { height: "var(--radix-accordion-content-height)" },
          to: { height: "0" }
        }
      },
      animation: {
        "accordion-down": "accordion-down 0.2s ease-out",
        "accordion-up": "accordion-up 0.2s ease-out"
      }
    }
  },
  plugins: []
};

export default config;
