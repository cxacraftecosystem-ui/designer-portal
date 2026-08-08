---
name: motion
description: Declarative animation in this repo — the shared motion vocabulary, the two reduced-motion switches that must both be honoured, springs and easing tied to the design tokens, variants/stagger, layout animation, and the hydration rules. framer-motion v12 IS Motion. Load before adding or changing ANY declarative animation in the web client.
---

# Motion (framer-motion v12) — the web client's declarative animation

`framer-motion@12.40.0` is installed and **is** Motion — the library was renamed to `motion` at v12,
and the `framer-motion` package remains a published alias of the same code. Do **not** add the
`motion` package alongside it: two copies of one library means two `MotionConfig` contexts, two
reduced-motion subscriptions and layout animations that fight. Import from `framer-motion` — that
is what the 23 files already using it import from, and consistency is the point.

GSAP is installed too and owns exactly one animation. See the `gsap` skill before reaching for it.

---

## 1. The rule that has already shipped as a bug: there are TWO reduced-motion switches

**Never call framer-motion's `useReducedMotion()` in a component.** It subscribes to the OS media
query `prefers-reduced-motion: reduce` and to nothing else. This app has a *second* switch — the
Settings toggle owned by `ThemeProvider`, which stamps `data-reduced-motion="true"` on `<html>` —
and framer cannot see it. A user who turned reduced motion on in Settings still got the full scroll
spring, pointer tracking and layout animations.

Use the union:

```ts
import { useAppReducedMotion } from "@/components/guide/useAppReducedMotion";

const reduce = useAppReducedMotion(); // OS preference OR the app's Settings toggle
```

CSS already honours both, because `app/globals.css` pairs the `@media (prefers-reduced-motion:
reduce)` block with a `:root[data-reduced-motion="true"]` block — a media query cannot be OR-ed with
a selector, so it is written twice. JavaScript animation has no such twin, which is why the hook
exists.

`lib/preferences.ts` is explicit that the app preference **ORs** with the OS one and can never
switch the OS preference off. Honour that direction: reduced motion is a floor, never a ceiling.

---

## 2. Use the shared vocabulary — do not hand-roll a transition

`components/guide/guideMotion.ts` holds the factories. **Every one takes `reduce` and collapses to a
zero-duration, zero-displacement version of itself.** Gating lives at the source precisely so a new
animation cannot ship without honouring the preference — which is the failure mode a per-call-site
check invites.

| Factory | Use for |
|---|---|
| `springy(reduce)` | every interactive/press response (`stiffness: 380, damping: 30, mass: 0.7`) |
| `layoutSpring(reduce)` | layout changes — a card growing as it expands (softer: `260 / 32 / 0.9`) |
| `staggerParent(reduce, stagger?)` | a parent that releases children one after another |
| `riseItem(reduce, distance?)` | the standard rise-and-fade for a revealed element |
| `slideItem(reduce, distance?)` | where a rise would fight the reading direction (chips, rails) |

```tsx
<motion.ul variants={staggerParent(reduce)} initial="hidden" animate="show">
  {items.map((item) => (
    <motion.li key={item.id} variants={riseItem(reduce)}>{item.label}</motion.li>
  ))}
</motion.ul>
```

Adding a sixth kind of entrance is almost always wrong. If a surface genuinely needs one, add a
factory to `guideMotion.ts` with the `reduce` parameter — never an inline `transition={{ ... }}` that
the preference cannot reach.

---

## 3. Easing and springs come from the design tokens

`EASE_OUT = [0.16, 1, 0.3, 1]` is not a taste choice — it is the design system's `ease-out`, read
off `tailwind.config.ts → transitionTimingFunction.out`. The spring in `springy()` matches the feel
of the `ease-spring` curve used elsewhere. **If you change a curve here, change the token, not the
copy**, or CSS transitions and JS animations on the same surface will drift apart.

Durations in use: `0.5s` for a rise, `0.4s` for a slide, `0.05s` `delayChildren`, `0.06s`
`staggerChildren`. Match them rather than inventing neighbours.

---

## 4. Client components, hydration, and the first render

Anything using `motion.*` or these hooks needs `"use client"`.

On the server and on the **first** client render, both reduced-motion sources read false — the OS
query is unknown and stored preferences have not been read yet — so the markup matches and a mount
effect corrects it a tick later. This is the same sequence every themed surface follows. Do **not**
try to "fix" the flash by reading `localStorage` during render: that is a hydration mismatch, and
the theming code deliberately does not do it.

Consequence: never make an element's *existence* depend on `reduce`. Gate the motion, not the
markup, or the server and client trees differ.

---

## 5. Layout animation

`layout` / `layoutId` are the expensive ones. Rules that hold here:

- Pair them with `layoutSpring(reduce)`, not the default transition.
- A `layout` element must not also animate `width`/`height` in `animate` — the two fight and the
  result stutters.
- `layoutId` must be unique across the whole tree at a given moment. Two mounted elements sharing
  one id is how a card appears to fly to the wrong place.
- Under `reduce`, `layoutSpring` is `{ duration: 0 }`, which is correct: the element still moves to
  the right place, instantly.

---

## 6. Scroll-linked motion

Smooth scrolling **is** motion and is gated too — under either switch the jump is instant. The
`scroll-mt-*` utility on each card supplies clearance for the floating header pill; if a scrolled-to
element hides under the nav, that is a missing `scroll-mt-*`, not a scroll-offset bug.

For `useScroll`/`useTransform`, pass the reduced-motion flag through: collapse the output range to a
constant rather than skipping the hook, so hook order stays stable across renders.

---

## 7. Traps

- **`useReducedMotion()` from framer-motion** — sees only the OS switch. Always `useAppReducedMotion()`.
- **Adding the `motion` package** — it is the same library as the installed `framer-motion@12`.
- **Inline `transition={{...}}`** — invisible to the preference; use a `guideMotion` factory.
- **Animating layout and size at once** — pick one.
- **`whileHover` on touch surfaces** — it sticks after a tap on mobile. Prefer `whileTap` +
  `springy(reduce)` for press feedback.
- **`will-change` left on permanently** — set it while animating and clear it after, as
  `useGsapHeadline` does for its word spans.
- **Motion on a value a screen reader announces** — animate the container, never re-render the text
  in pieces, or the accessible name changes mid-animation.
