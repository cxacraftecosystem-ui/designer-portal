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
