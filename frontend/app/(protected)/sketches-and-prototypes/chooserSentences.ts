/**
 * THE THREE ANSWERS THE SKETCHES & PROTOTYPES CHOOSER CAN GIVE, NAMED SO THEY CAN BE HELD APART.
 *
 * ── THE DEFECT THESE EXIST FOR ──────────────────────────────────────────────────────────────────
 *
 * The handset's copy of this screen wrote a FAILED list as an empty list, which fell into the
 * `isEmpty()` branch, which renders the answered-and-none sentence. So a designer on twelve design
 * workshops, standing in a courtyard with no signal, was told "You are not on any design workshop
 * yet. Once an administrator adds you to one…" — told they had none, and sent to ask an
 * administrator for the twelve they already had. `SketchesAndPrototypesScreen.kt` carries the full
 * write-up; `DwSketchChooserSentenceTest` pins the property that came out of it.
 *
 * The browser has never had that bug: `page.tsx` leaves `workshops` NULL on both failures precisely
 * so the empty state cannot win a race with an error, and its header argues that at length. What it
 * did not have was anything STOPPING the bug — three inline string literals in three JSX branches
 * cannot be compared with each other by any test, and the property that matters is a relationship
 * BETWEEN them:
 *
 *     ONLY THE STATE THAT ACTUALLY GOT AN ANSWER MAY NAME AN ADMIN.
 *
 * A sentence about a request that never landed sends a designer on an errand invented out of a
 * failure. Written the other way round — one sentence reused for two states — the branch order in
 * `page.tsx` stops mattering and the defect can come back through any of them.
 *
 * ── WHY A MODULE OF ITS OWN, BESIDE `page.tsx` ──────────────────────────────────────────────────
 *
 * So a test can read them without mounting React. `e2e/sketch-chooser-sentences-unit.spec.ts` runs
 * under `npm run test:unit` with no browser and no server, and imports these directly — the same
 * arrangement `report/reportTarget.ts` and `e2e/report-target-unit.spec.ts` already use two routes
 * over. Next only treats `page`/`layout`/`route` files as routes, so a plain `.ts` sitting beside
 * one is a module and nothing else.
 *
 * ── WHAT IS DELIBERATELY NOT THE SAME AS THE HANDSET'S ──────────────────────────────────────────
 *
 * The handset's `DW_SKETCH_CHOOSER_NOTHING_LOST` says, under BOTH failures, that nothing is lost
 * because the screen only reads. The browser says less: its offline panel names the route that
 * still works and leaves it there. That is not an oversight to be tidied by copying the Kotlin
 * sentence across — the two clients are answering different worries. The handset holds a fortnight
 * of fieldwork in a local outbox and a designer who sees a failure there has to be told their work
 * is safe; this page holds nothing and mounts nothing that writes, so the reassurance would be
 * about a risk the reader does not have. Recorded here rather than closed, so the next person to
 * compare the two surfaces finds an argument instead of a gap.
 */

/**
 * ANSWERED, AND THE ANSWER IS NONE. The ordinary state of a newly onboarded designer.
 *
 * THE ONLY SENTENCE ON THIS SCREEN THAT MAY NAME AN ADMIN, because it is the only one that knows
 * the answer: the repository was asked, it replied, and the reply was an empty page. Reached by any
 * other route it is an errand invented out of a failed request.
 *
 * It also must not hedge into failure language — no "could not", no "try again". A newly onboarded
 * designer being told something failed reads a correct, ordinary answer as a broken app, and keeps
 * pressing.
 */
export const CHOOSER_NO_WORKSHOPS_TITLE = "No design workshops to open yet";

/** The body of the same state: why there is no way to start one here, who to ask, what follows. */
export const CHOOSER_NO_WORKSHOPS_BODY =
  "Sketches and prototypes belong to a workshop, and the workshops you have access to will appear" +
  " in the chooser here. Starting a new one is done by an admin or the master admin — ask them to" +
  " create it for your cluster and give you access, and it will show up ready for all 22 stages.";

/**
 * COULD NOT ASK, with no signal — `isUnreachable`'s half of the split.
 *
 * NO CHOOSER, AND NOTHING MOUNTED UNDERNEATH IT. The only list that may fill the chooser is the
 * server's, because access is decided by rows this client never sees and can be withdrawn without
 * it hearing, so a cache of a past answer may not be turned into an offer.
 */
export const CHOOSER_OFFLINE_TITLE = "The repository could not be reached";

/**
 * The distinction said out loud, because a reader cannot infer it: an empty list and an unaskable
 * one look identical on screen unless one of them says which it is.
 */
export const CHOOSER_OFFLINE_BODY =
  "This is not an empty archive — it is a list that could not be loaded. Which workshops you can" +
  " open is decided by the repository and can change while a browser is away, so this chooser is" +
  " not offered from a saved copy: an old list would offer a workshop that may no longer be yours," +
  " and anything filed against it would be refused when the connection returns.";

/**
 * WHERE THE WORK CAN STILL BE DONE, in three parts because the middle one is a link.
 *
 * "The repository could not be reached" is cold comfort to somebody who came here to open a stage,
 * so the panel names the route that is unaffected. Split rather than interpolated: the page renders
 * a real `<Link>` between the two halves, and a test that wants the sentence reads
 * {@link CHOOSER_OFFLINE_ROUTE_NOTE}, which is composed from these and cannot drift from them.
 */
export const CHOOSER_OFFLINE_ROUTE_LEAD = "A workshop you are already inside is unaffected — open it from ";
export const CHOOSER_OFFLINE_ROUTE_LABEL = "Design workshops";
export const CHOOSER_OFFLINE_ROUTE_TAIL = " and work on its Sketches & prototypes page there.";

/** The whole of that sentence, for a reader with no DOM. Derived, never typed out a second time. */
export const CHOOSER_OFFLINE_ROUTE_NOTE =
  CHOOSER_OFFLINE_ROUTE_LEAD + CHOOSER_OFFLINE_ROUTE_LABEL + CHOOSER_OFFLINE_ROUTE_TAIL;

/**
 * COULD NOT ASK, and the repository said why — answered-and-refused, which is a different fact from
 * unreachable and calls for a different next move.
 *
 * The heading is always this; the body is whatever the repository said, with
 * {@link CHOOSER_REFUSED_FALLBACK} standing in when it said nothing usable.
 */
export const CHOOSER_REFUSED_TITLE = "Your design workshops could not be listed";
export const CHOOSER_REFUSED_FALLBACK = "The repository could not list your design workshops.";
