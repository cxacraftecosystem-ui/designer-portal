package com.designprototype.workshop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE WALKTHROUGH'S CONTENT, HELD TO THE WEB'S JOURNEY AND TO ITS OWN RULES.
 *
 * ── WHY THIS FILE EXISTS, WHICH IS NOT A GENERAL ARGUMENT FOR TESTING COPY ───────────────────────
 *
 * The list this suite reads replaces twelve steps whose opening card said "Ten steps, in this order"
 * and "it is the same ten steps in the same order on the web" — while the list held twelve and the
 * web taught nineteen. One sentence, wrong in two directions, on the first thing a new researcher
 * ever reads, and it stayed wrong for months. Nothing caught it and nothing could: the steps were
 * `private` inside a seventeen-thousand-line `MainActivity`, and while a shelf of `*ParityTest`
 * files already held the two clients together on dashboards, money, rounding and text formatting,
 * not one of them could see this list — a `private` value is invisible to the whole test source
 * set. `WalkthroughSteps.kt` is `internal` rather than `private` for exactly one reason — so
 * that this file can read it — and the codebase has been here before: `internal fun navBadge` was
 * widened for a suite that then went unwritten, and its own KDoc records that a named test which
 * does not exist is worse than no citation, because the next reader takes the rule as covered.
 *
 * ── THE FOUR FAILURES BEING GUARDED, ALL OF THEM INVISIBLE BY INSPECTION ─────────────────────────
 *
 * 1. THE HANDSET FALLING BEHIND THE WEB AGAIN. Nine web steps — the entire design-workshop arc —
 *    had no counterpart here at all, and the way that happened was not a decision: a step was added
 *    on one client and the other was never opened. `every subject the web walkthrough teaches has a
 *    step here` fails the build instead.
 * 2. A NUMBER TYPED INTO A TITLE. "1. Workshop" … "10. View Data" were literals, so inserting a step
 *    silently renumbered nothing and every title after the insertion became a lie. Pinned by
 *    `no step title carries its own number`.
 * 3. A COUNT TYPED INTO A SENTENCE — the original defect, from the other end. Pinned by
 *    `the opening card counts the journey rather than claiming a number`.
 * 4. A STEP POINTING AT A DOOR THAT IS NO LONGER THERE. Each step carries a [NavDestination] and the
 *    dialog borrows that row's label for its "Open …" button. Drop a row from `FIELD_NAV_ITEMS` and
 *    the button silently degrades to "Open the screen this step teaches" on a surface whose entire
 *    job is teaching a newcomer what things are called. Pinned by `every step that offers a door
 *    names a menu row that exists`.
 *
 * ── THE WEB'S STEPS ARE READ OFF `steps.ts`, AND THIS FILE USED TO ARGUE THE OPPOSITE ────────────
 *
 * [WEB_GUIDE_STEPS] was a hand-copied array of nineteen ids under a paragraph explaining why reading
 * the web's own file would be worse. THE SNAPSHOT ROTTED, in the exact shape the paragraph did not
 * consider, and it is worth setting out because it is the third time this repository has paid for
 * the same mistake:
 *
 *   By the time anybody looked, the web taught TWENTY-TWO. The three it had gained were `scan`,
 *   `design-workshop-questionnaires` and `design-workshop-inspection` — and those were precisely the
 *   three that `WalkthroughSteps.kt` had shipped under ids of its own invention (`scan-code`,
 *   `questionnaires`, `design-workshop-inspections`), believing them handset-only. So the snapshot
 *   was missing exactly the three ids that would have exposed the divergence. Both files agreed, in
 *   writing, on a fact neither had rechecked. The one-directional assertion below covered nineteen
 *   of twenty-two, and all three could have been DELETED from the handset with the suite still green.
 *
 * A snapshot is correct on the day it is typed and silently rots after it — which is the very defect
 * `the opening card counts the journey rather than claiming a number` exists to catch, one file over,
 * applied to a count instead of to a list. The register now has ONE copy, and it is the web's.
 *
 * ── THE TWO OBJECTIONS THAT PARAGRAPH RAISED, ANSWERED WITH WHAT IS ACTUALLY IN THE TREE ─────────
 *
 * "The failure mode of a frontend refactor is an Android test that cannot compile" — it is not. This
 * is a file read at RUNTIME, not an import; nothing here is on the compiler's path, and a moved or
 * renamed `steps.ts` fails one assertion with a message naming the path it looked for.
 *
 * "A CI job that builds only `android/` breaks for reasons nothing in `android/` can fix" — the job
 * does not build only `android/`. `.github/workflows/android-build.yml` runs a plain
 * `actions/checkout@v4` with no sparse filter, so the whole monorepo is on disk and this path
 * resolves there exactly as it does locally.
 *
 * And the pattern is already settled in this module rather than being introduced here:
 * `DashboardTileParityTest` reads `frontend/app/(protected)/dashboard/page.tsx` at runtime through
 * the same walk-up helper, for the same reason, and its own KDoc records what that costs. This file
 * borrows both the helper and the honesty.
 *
 * ── THE ONE HONEST GAP, WHICH IS REAL AND IS NOT CLOSED HERE ─────────────────────────────────────
 *
 * A pull request touching only `frontend/` does not run this suite: `android-build.yml`'s
 * `pull_request` filter is the `android` tree, its own path, and a few named frontend files. So a
 * web-side step added in a frontend-only PR is caught when the Android job next runs on `main`, not
 * on the PR that added it. That is strictly better than the snapshot, which never caught it at all,
 * and widening the filter is that workflow's decision rather than this file's.
 *
 * ── AND THE ASSERTION IS STILL ONE-DIRECTIONAL, FOR THE ORIGINAL REASON ──────────────────────────
 *
 * Every subject the web teaches must be taught here; this handset may teach MORE. That is the
 * direction the damage runs in — a handset behind the web sends a designer looking for a screen they
 * were never told about — and equality would make the web catching up an Android failure. What is no
 * longer true is the old parenthetical listing four Android-only subjects: three of them were the
 * drift described above, and exactly ONE is genuinely this app's own, `offline`, which is the one
 * subject a browser cannot teach.
 */
class WalkthroughStepsTest {

    /**
     * The subjects `frontend/components/guide/steps.ts` teaches, in its own order, read from the file.
     *
     * IDS AND NEVER SENTENCES. `WalkStep.id` is documented as the one field stable enough to compare
     * while the prose on both clients is still being edited; pin a test to a title and it fails the
     * next time somebody improves a sentence, which trains everybody to ignore the test. The ids on
     * this side are kept equal to the web's expressly so this list can be the join — and when three
     * of them were NOT, this array being a hand-copy is what hid it. See the KDoc above.
     *
     * ── THE ANCHOR, AND WHY IT IS THIS ONE ───────────────────────────────────────────────────────
     *
     * `GuideStep` objects are elements of one array literal, so their `id` sits at exactly four
     * spaces of indent. Nothing else in that file does: a nested field is deeper, a top-level
     * declaration is shallower, and a line of prose inside a comment block starts with a space and an
     * asterisk. That is not a lucky coincidence to lean on quietly — it is the same anchor the web's
     * own documentation uses to count its steps, quoted in `steps.ts` itself as the way to answer
     * "how many are there", so it is a shape that file has already committed to keeping.
     *
     * The scan starts at the `GUIDE_STEPS` declaration rather than at the top of the file, so a
     * helper array declared above it can never contribute an id. The test named "the parser found
     * the web's step list" fails loudly if either half of that stops holding, because a regex that
     * silently matches nothing would make every assertion in this file vacuously green — which is
     * the failure mode of every source-reading test and the one that wastes a morning.
     */
    private val WEB_GUIDE_STEPS: List<String> by lazy {
        val source = webGuideSource()
        val start = source.indexOf("export const GUIDE_STEPS")
        check(start >= 0) { "GUIDE_STEPS is no longer declared in $WEB_GUIDE_PATH" }
        Regex("""^ {4}id: "([^"]+)"""", RegexOption.MULTILINE)
            .findAll(source.substring(start))
            .map { it.groupValues[1] }
            .toList()
    }

    // ── Parity with the web ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the parser found the web's step list`() {
        /*
         * WITHOUT THIS, EVERY PARITY ASSERTION BELOW IS VACUOUSLY GREEN the day `steps.ts` is
         * reformatted, renamed or moved: `WEB_GUIDE_STEPS` comes back empty, "every subject the web
         * teaches has a step here" iterates nothing and passes, and the suite reports parity while
         * checking none. `WalkthroughSurfaceTest` opens with the same guard for the same reason and
         * says what it costs: the failure would be read as "the wiring is fine" when the parser is
         * the thing that is broken.
         *
         * The floor is a floor and not the current count, deliberately. Pinning the exact number
         * would put a THIRD copy of the register in this file — the very defect this file was
         * rewritten to remove — and would fail every time the web legitimately adds a step, which is
         * the one event this suite is supposed to welcome rather than resist. Ten is comfortably
         * below the ten-step records arc alone, so it can only trip on a parser that has genuinely
         * stopped working.
         */
        assertTrue(
            "$WEB_GUIDE_PATH parsed to ${WEB_GUIDE_STEPS.size} step ids — the anchor this file " +
                "scans for has stopped matching, so every parity assertion below is now checking " +
                "nothing at all",
            WEB_GUIDE_STEPS.size >= 10
        )
        assertEquals(
            "the web's guide has two steps with the same id, so it cannot be the join",
            WEB_GUIDE_STEPS.size,
            WEB_GUIDE_STEPS.toSet().size
        )
    }

    @Test
    fun `every subject the web walkthrough teaches has a step here`() {
        val here = walkthroughJourney.map { it.id }.toSet()
        val missing = WEB_GUIDE_STEPS.filterNot { it in here }
        assertTrue(
            "the web walkthrough teaches $missing and this handset does not. A designer who read " +
                "the guide on a laptop and then opened it in a courtyard would find the subject " +
                "gone — which is how the whole design-workshop arc went untaught here for months. " +
                "Add the step to WalkthroughSteps.kt. This list is READ FROM the web's own file, " +
                "so there is nothing here to edit and no way to make this pass by agreeing with " +
                "the handset — which is the entire point of it no longer being a hand-copy. If the " +
                "id merely CHANGED on one side, this reports it as missing rather than as renamed: " +
                "check the web's spelling before adding anything.",
            missing.isEmpty()
        )
    }

    @Test
    fun `the web's steps appear here in the web's own order`() {
        // Filtered rather than compared whole, because this app teaches one subject of its own that
        // the web has no counterpart for — `offline`. What must hold is that the SHARED subjects
        // read in the same sequence, since the order is the actual lesson: a stage's reference
        // pickers are empty if the records were never made, and a tag tied on at the end is a tag
        // tied on from memory.
        //
        // This assertion was passing while the two clients genuinely disagreed. The snapshot it
        // filtered against did not contain `design-workshop-questionnaires`, so the step Android
        // taught eighth and the web teaches fifteenth was excluded from the comparison by the very
        // omission that made it wrong. Reading the web's file is what turned that from an invisible
        // divergence into a red test, and the handset's list was reordered to match.
        val shared = walkthroughJourney.map { it.id }.filter { it in WEB_GUIDE_STEPS }
        assertEquals(
            "the two clients teach the same subjects in different orders, which is worse than a " +
                "missing step: both look complete and only one of them is the order the work " +
                "happens in",
            WEB_GUIDE_STEPS,
            shared
        )
    }

    // ── The rules the list sets itself ───────────────────────────────────────────────────────────

    @Test
    fun `no two steps share an id`() {
        // Everything above and `walkthroughStepNumber` below join on the id. A duplicate does not
        // fail anything loudly; it makes `indexOfFirst` answer for the wrong step, so a parity
        // check quietly stops covering one of the two and reports a pass.
        val ids = walkthroughSteps.map { it.id }
        assertEquals(
            "a duplicated id makes every id-based check answer for the wrong step, silently",
            ids.size,
            ids.toSet().size
        )
        assertTrue("an id is the join key and cannot be blank", ids.none { it.isBlank() })
    }

    @Test
    fun `no step title carries its own number`() {
        // The shipped list read "1. Workshop · Record workshop" … "10. View Data · Browse records"
        // as literals. Insert a step at position three and the seven titles after it are wrong, the
        // compiler is happy, the screen renders, and only a human re-reading the whole file finds
        // it. The position is derived from the list instead — see `walkthroughStepNumber` — so this
        // asserts that nobody has quietly gone back to typing it.
        val numbered = Regex("""^\s*\d+\s*[.)]""")
        walkthroughSteps.forEach { step ->
            assertFalse(
                "“${step.title}” has a step number typed into it. Inserting a step ahead of it " +
                    "would make that number wrong with nothing to catch it; the dialog derives " +
                    "the position from the list instead.",
                numbered.containsMatchIn(step.title)
            )
        }
    }

    @Test
    fun `the opening card counts the journey rather than claiming a number`() {
        // The original defect, pinned from both ends: the sentence must contain the REAL size, and
        // it must not contain the size of the deck it is part of. If somebody re-types the number
        // and the list then grows, the first assertion fails on the very next step that is added.
        val intro = walkthroughSteps.first()
        assertNull("the opening card is not one of the numbered steps", walkthroughStepNumber(intro))
        assertTrue(
            "the opening card must state how many steps the journey has, and state the true " +
                "number: it read “Ten steps, in this order” over a list of twelve while the web " +
                "taught nineteen",
            intro.body.contains("${walkthroughJourney.size} steps")
        )
        assertFalse(
            "the opening card must count the JOURNEY and not the deck — a reader on a card whose " +
                "first sentence says there are ${walkthroughJourney.size} steps must not be told " +
                "there are ${walkthroughSteps.size}",
            intro.body.contains("${walkthroughSteps.size} steps")
        )
    }

    @Test
    fun `the deck is the journey with one card at each end`() {
        assertEquals(
            "the deck is the opening card, the journey, and the closing checklist",
            walkthroughJourney.size + 2,
            walkthroughSteps.size
        )
        assertEquals(
            "the journey must reach the screen in its own order, unedited",
            walkthroughJourney,
            walkthroughSteps.subList(1, walkthroughSteps.size - 1)
        )
    }

    @Test
    fun `only the journey is numbered, and it is numbered from one`() {
        // Two denominators, deliberately different: a numbered step says which STEP it is, the two
        // ends say which PAGE they are. Collapsing them would put a reader at "Step 1 of
        // ${walkthroughSteps.size}" on a card that says there are ${walkthroughJourney.size}.
        assertNull(walkthroughStepNumber(walkthroughSteps.first()))
        assertNull(walkthroughStepNumber(walkthroughSteps.last()))
        walkthroughJourney.forEachIndexed { index, step ->
            assertEquals(
                "“${step.title}” must report itself as step ${index + 1}",
                index + 1,
                walkthroughStepNumber(step)
            )
        }
    }

    // ── The doors ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every step that offers a door names a menu row that exists`() {
        // The dialog borrows the row's own label — never a string typed here — so that the button
        // and the drawer row a designer then goes looking for say the same words by construction.
        // A destination dropped from the menu does not break the build: the button falls back to
        // "Open the screen this step teaches", which is a third name for a screen on the one
        // surface whose whole job is teaching a newcomer the first two.
        walkthroughSteps.forEach { step ->
            val destination = step.destination ?: return@forEach
            assertNotNull(
                "“${step.title}” opens $destination, which is no longer a row in FIELD_NAV_ITEMS. " +
                    "Its “Open …” button would render without the screen's name on it.",
                FIELD_NAV_ITEMS.firstOrNull { it.destination == destination }
            )
        }
    }

    @Test
    fun `the two ends open nothing`() {
        // Null means exactly one thing on this field: there is no screen to open. The opening card
        // and the closing checklist are not features and must not grow a button that implies they
        // are — and neither may point at the walkthrough itself, which would be a door back into
        // the room the reader is standing in.
        assertNull("the opening card is not a feature", walkthroughSteps.first().destination)
        assertNull("the closing checklist is not a feature", walkthroughSteps.last().destination)
        assertTrue(
            "no step may open the walkthrough from inside the walkthrough",
            walkthroughSteps.none { it.destination == NavDestination.WALKTHROUGH }
        )
    }

    // ── The shape of a step ──────────────────────────────────────────────────────────────────────

    @Test
    fun `every step of the journey ends on the thing that goes wrong`() {
        // The body's contract is four blocks in one paragraph — what you are doing, why the dataset
        // needs it, what the screen asks for, and the caution — and the caution is the half that
        // costs a return trip when it is missed. It is also the half that gets dropped when a step
        // is written in a hurry, because the other three can be read off the form and this one
        // cannot: it has to come from something that actually went wrong for somebody.
        walkthroughJourney.forEach { step ->
            assertTrue(
                "“${step.title}” has no “Watch out” — the one part of a step that cannot be " +
                    "reconstructed by reading the screen it describes",
                step.body.contains("watch out", ignoreCase = true)
            )
        }
    }

    @Test
    fun `every step wears a glyph and says what it is`() {
        // The icon is how a designer finds the drawer row they just read about — they are looking
        // for the picture — so a step without one sends them reading thirty-one labels instead.
        walkthroughSteps.forEach { step ->
            assertNotNull("“${step.title}” has no icon to find its row by", step.icon)
            assertTrue("a step with no words is not a step", step.title.isNotBlank())
            assertTrue("“${step.title}” has no body", step.body.isNotBlank())
        }
    }
}

// ── Reading the web's own step list ─────────────────────────────────────────────────────────────

/** The one place this path is written. Named in failure messages so a move reports itself. */
private const val WEB_GUIDE_PATH = "frontend/components/guide/steps.ts"

/**
 * `steps.ts`, read from wherever the test runner started.
 *
 * NOT STRIPPED OF COMMENTS, and that is a decision rather than an omission. The two Kotlin-reading
 * suites in this module strip first, because they search for code that is also DISCUSSED in prose
 * above it and a count of call sites would otherwise come back several times too high. Nothing of
 * the kind applies here: the anchor is an id at exactly four spaces of indent inside an array
 * literal, and a comment line in that file begins with a space and an asterisk or with two slashes,
 * so no comment can present a line in that shape. Adding a TypeScript comment stripper would be a
 * second parser to keep correct in exchange for nothing — and `DashboardTileParityTest` records that
 * getting the nesting rule backwards between the two languages "does not fail loudly: it silently
 * hands the parser a slab of prose or a slab of code".
 */
private fun webGuideSource(): String =
    repoFile(
        // The `..`-prefixed candidate is what lets this reach OUT of `android/` and into `frontend/`.
        // A Gradle test worker's working directory is not something to depend on, so both are tried
        // at every level of the walk rather than assuming which one the runner started in.
        "../$WEB_GUIDE_PATH",
        WEB_GUIDE_PATH,
    ).readText(Charsets.UTF_8).replace("\r\n", "\n")

/**
 * A file of this repository, found by walking up from wherever the test runner started.
 *
 * MISSING IS A FAILURE, LOUDLY, AND NEVER A SKIP. A test that quietly passed when it could not find
 * its subject would prove nothing on the day somebody moves that subject — which is the one day it
 * is most needed, and the day its silence would be read as parity. Same helper and same reasoning as
 * `DashboardTileParityTest`, which reads the dashboard grid out of `frontend/` the same way; it is
 * copied rather than shared because that one is private to its own file and lifting it would put a
 * test helper into the production tree for the benefit of two callers.
 */
private fun repoFile(vararg relative: String): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        for (path in relative) {
            val candidate = File(dir, path)
            if (candidate.isFile) return candidate
        }
        dir = dir.parentFile
    }
    throw AssertionError("none of ${relative.toList()} found from ${File(".").absolutePath}")
}
