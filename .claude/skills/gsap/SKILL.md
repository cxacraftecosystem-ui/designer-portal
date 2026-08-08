---
name: gsap
description: GSAP in this repo — the one thing it owns and why, the timeline-overlap primitive framer-motion cannot express, the mandatory dynamic import that keeps 70 KB out of every protected page, reduced-motion and cleanup rules, and the trap of two animation systems fighting over one property. Load before adding, changing or removing any GSAP animation.
---

# GSAP — the imperative exception

`gsap@3.15.0` is installed and is used by **exactly one file**:
`frontend/components/guide/useGsapHeadline.ts`. That is deliberate and worth preserving.

## 1. When GSAP is the right answer here — and when it is not

The web client is declarative: a component enters, a value tracks scroll, an element springs on
hover. `framer-motion` states all of that better than an imperative library would, and the `motion`
skill covers it. **Reach for GSAP only for a timeline whose tweens deliberately OVERLAP.**

That is the one thing framer cannot say. Its `staggerChildren` is a fixed delay between siblings that
each run their own transition, so a word's rise begins only *after* the previous word's delay has
elapsed. The headline wants each word starting **while the one before it is still moving** — a
negative relative offset — which is a timeline primitive:

```ts
timeline.fromTo(
  word,
  { yPercent: 108, opacity: 0, rotate: 1.5 },
  { yPercent: 0, opacity: 1, rotate: 0 },
  // Each word starts 0.28s after the previous STARTED, while the previous is still 0.34s
  // from finishing. `"<"` is "the previous tween's start"; the number is the offset from it.
  index === 0 ? 0 : "<0.28"
);
```

If what you want can be written as "these enter one after another, each fully", it is
`staggerParent`/`riseItem` from `guideMotion.ts`, not a timeline.

**Never let both libraries touch the same property on the same element.** They each write
`transform` on their own schedule and the result is jitter that looks like a rendering bug. GSAP owns
that headline's word spans; framer owns everything else on the page.

## 2. The dynamic import is not optional

GSAP is ~70 KB. Only this one component needs it, so it must not sit in the bundle every protected
page loads:

```ts
import type { gsap as GsapNamespace } from "gsap";   // type-only: erased at build
// ...
import("gsap").then(({ gsap }) => { /* build the timeline */ });
```

A top-level `import { gsap } from "gsap"` ships it to every route that transitively imports the
component. Keep the value import inside the effect and the namespace import type-only.

Guard the async race: the effect can be torn down before the module resolves.

```ts
let cancelled = false;
import("gsap").then(({ gsap }) => { if (cancelled) return; /* ... */ });
return () => { cancelled = true; timeline?.kill(); };
```

## 3. Reduced motion: build no timeline at all

Under either switch — the OS preference **or** the app's Settings toggle, unioned by
`useAppReducedMotion()` — **do not build a fast timeline. Build none.** Leave the elements exactly as
rendered:

```ts
if (reduce || words.length === 0) {
  words.forEach((word) => { word.style.transform = ""; word.style.opacity = ""; });
  return;
}
```

Clearing the inline styles matters: it prevents a flash of transformed text if the preference changes
after a run. A "quick" animation is still animation and does not satisfy the preference.

## 4. Cleanup

Always `timeline.kill()` in the effect's teardown. An orphaned timeline keeps writing to detached
nodes after unmount, holds them from GC, and — in React strict mode's double-invoked effects — a
second timeline starts on elements the first is still tweening.

`gsap.context()` / `ctx.revert()` is the idiomatic scoping tool if a future animation grows beyond a
single timeline; for one timeline, `kill()` is enough and is what the existing hook does.

## 5. Splitting text without breaking accessibility

`useGsapHeadline` wraps words in spans so each can be transformed. The rules it follows:

- **Split ONCE**, guarded by `node.dataset.split`. Re-splitting on every run nests spans in spans.
- `display: inline-block` on the span, so a transform applies at all.
- The trailing space stays **outside** the span, so the line still breaks and the text copies as
  normal prose.
- The heading's `textContent` and accessible name are unchanged — a screen reader reads the
  sentence, not the pieces. Any new splitting must preserve that.
- `will-change: transform, opacity` is set on the spans while they animate.

Do not use GSAP's `SplitText` plugin: it is a paid Club plugin and is not a dependency here.

## 6. Traps

- **A top-level `import { gsap } from "gsap"`** — puts 70 KB in every protected page's bundle.
- **GSAP and framer-motion on the same element** — both write `transform`; pick one owner.
- **A fast timeline under reduced motion** — build none.
- **No `kill()` in teardown** — leaks and double-runs under strict mode.
- **Re-splitting text on every effect run** — nested spans, and the accessible name degrades.
- **Reaching for GSAP for an ordinary entrance** — that is `guideMotion.ts`; using GSAP there means
  two animation systems where one would do.
