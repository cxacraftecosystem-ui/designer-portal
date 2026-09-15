package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **TWO WALKTHROUGHS OVER ONE RENDERER, AND THE ROLE PICKS WHICH ONE OPENS.**
 *
 * ── WHAT THIS FILE IS FOR, WHICH IS NOT "THE DECKS EXIST" ───────────────────────────────────────
 *
 * The walkthrough taught one deck — a designer's fortnight — to every signed-in account, including
 * the Inspector / Reviewer tier, whose entire surface is one card sitting second from last in it.
 * `WalkthroughSteps.kt` now registers two decks and `walkthroughDeckFor` chooses a default from the
 * reader's role. Three things about that arrangement can go wrong quietly, and each is a test below:
 *
 *  1. **THE SECOND DECK DRIFTING FROM THE WEB, WITH NOTHING ABLE TO SEE IT.**
 *     `WalkthroughStepsTest` reads `frontend/components/guide/steps.ts`, which is the register for
 *     the DESIGNER's deck and for no other — the web keeps its three decks in three files. So the
 *     inspector deck inherited exactly none of that suite's parity, and the honest fix is not to
 *     widen it (a deck the web keeps elsewhere cannot be held to `steps.ts`) but to read the file
 *     that does own those ids. That is what [WEB_INSPECTOR_STEPS] is.
 *  2. **THE OMISSION BECOMING AN ACCIDENT.** This handset teaches three of the web's four inspector
 *     cards. `inspection-feedback` is left out because the capability is absent — no client method,
 *     no panel, and no screen anywhere under `ui/` that reads the `inspectionFeedback` rows the
 *     payload already carries — and that decision is argued where the deck is declared. What a
 *     decision needs and an accident does not is a REGISTER: `walkthroughInspectorOmissions`, held
 *     here to the web's own list so that a second card quietly dropped, or a feedback box quietly
 *     shipped, is a red test rather than a file that looks the same either way.
 *  3. **A DEFAULT TURNING INTO A GATE.** The web is explicit that the role picks which deck OPENS
 *     and takes nothing away: every deck stays in the bundle and the switcher reaches all of them.
 *     This handset has one nav row into the walkthrough, ungated for all eleven tiers
 *     (`WalkthroughSurfaceTest` walks them), and one dialog. So "reachable" here means the deck is in
 *     [WALKTHROUGH_DECKS] — that list is the switcher's whole source — and the assertion is that
 *     every registered deck is reachable by every tier, not merely that the default is right.
 *
 * ── WHY THE TIERS ARE WRITTEN OUT AND NOT DERIVED ───────────────────────────────────────────────
 *
 * [everyRole] is a hand-kept tuple, which is the same kind of artefact as everything it polices —
 * correct on the day it is typed and silent afterwards. `backend/tests/test_role_ladder_parity.py`
 * registers it as a `closed` mirror and diffs it against the server's `ROLE_RANK`, so a tier added
 * to the ladder and not to this list fails there rather than making the sweep below pass while
 * asking nothing at all about the new tier. That registration is not optional: the same file sweeps
 * every Kotlin and TypeScript source in both client trees and fails on anything naming five or more
 * tiers that is neither a registered mirror nor a listed non-mirror — "there is no third state".
 *
 * ── AND THE RANK LADDER GIVES THE WRONG ANSWER FOR THIS ROW, WHICH IS WHY THE SWEEP IS EXHAUSTIVE ─
 *
 * INSPECTOR is rank 37, BETWEEN designer (35) and professor (40). Every threshold instinct therefore
 * admits the six tiers above it — professor, the three directorate posts, admin and master admin —
 * and the inspection surface refuses all six BY NAME, because `INSPECTION_ROLES` is a set with one
 * member. A default written as `rank >= RANK_INSPECTOR` would open an inspector's deck for the
 * master admin and look entirely reasonable in review. Walking every tier is what makes that a
 * failure with a name on it instead of a deck nobody notices they were handed.
 */
class WalkthroughDecksTest {

    /**
     * Every role the server's `ROLE_RANK` knows, so a new tier cannot be added without a decision.
     *
     * Registered in `backend/tests/test_role_ladder_parity.py` as a `closed` mirror — see the KDoc.
     */
    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
        "INSPECTOR",
        "PROFESSOR",
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
        "ADMIN",
        "MASTER_ADMIN",
    )

    private fun account(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    /**
     * The ids `frontend/components/guide/inspectorSteps.ts` teaches, in its own order, read from the
     * file at runtime.
     *
     * SAME ANCHOR AND SAME REASONING AS `WalkthroughStepsTest`: a `GuideStep` object is an element of
     * one array literal, so its `id` sits at exactly four spaces of indent, and nothing else in that
     * file does — a nested field is deeper, a top-level declaration is shallower, and a line of prose
     * inside a comment block starts with a space and an asterisk. The scan starts at the declaration
     * rather than at the top of the file so that a helper array above it can never contribute an id.
     *
     * IDS AND NEVER SENTENCES. `WalkStep.id` is documented as the one field stable enough to compare
     * while the prose on both clients is still being edited; the prose on this side is deliberately
     * NOT the web's — it is written from this app's own Kotlin, because the handset's inspection
     * screens are not the web's and a copied sentence is how a false sentence gets a second home.
     */
    private val WEB_INSPECTOR_STEPS: List<String> by lazy {
        val source = repoFile(
            // The `..`-prefixed candidate is what lets this reach OUT of `android/` and into
            // `frontend/`. A Gradle test worker's working directory is not something to depend on,
            // so both are tried at every level of the walk.
            "../$WEB_INSPECTOR_PATH",
            WEB_INSPECTOR_PATH,
        ).readText(Charsets.UTF_8).replace("\r\n", "\n")
        val start = source.indexOf("export const INSPECTOR_STEPS")
        check(start >= 0) { "INSPECTOR_STEPS is no longer declared in $WEB_INSPECTOR_PATH" }
        Regex("""^ {4}id: "([^"]+)"""", RegexOption.MULTILINE)
            .findAll(source.substring(start))
            .map { it.groupValues[1] }
            .toList()
    }

    // ── Parity with the web's inspector deck ─────────────────────────────────────────────────────

    @Test
    fun `the parser found the web's inspector deck`() {
        // WITHOUT THIS, EVERY ASSERTION BELOW IS VACUOUSLY GREEN the day `inspectorSteps.ts` is
        // reformatted, renamed or merged into another file: the list comes back empty, "the web
        // teaches nothing this handset is missing" passes, and the omission register is asserted
        // equal to an empty set it would then also be. It is the failure mode of every
        // source-reading test and the one that wastes a morning, because the report reads as parity.
        assertTrue(
            "$WEB_INSPECTOR_PATH parsed to ${WEB_INSPECTOR_STEPS.size} step ids — the anchor this " +
                "file scans for has stopped matching, so every assertion below is now checking " +
                "nothing at all",
            WEB_INSPECTOR_STEPS.size >= 3
        )
        assertEquals(
            "the web's inspector deck has two steps with the same id, so it cannot be the join",
            WEB_INSPECTOR_STEPS.size,
            WEB_INSPECTOR_STEPS.toSet().size
        )
    }

    @Test
    fun `the handset teaches the web's inspector subjects in the web's own order`() {
        // Filtered rather than compared whole, because this deck deliberately teaches FEWER — see
        // the next test, which is what makes "fewer" a decision instead of a gap. What must hold is
        // that the shared subjects read in the same sequence: the order is the actual lesson, and
        // two clients teaching the same subjects in different orders is worse than a missing step,
        // because both look complete and only one of them is the order the work happens in.
        val here = walkthroughInspectorJourney.map { it.id }
        assertEquals(
            "the handset's inspector deck teaches the web's subjects in a different order",
            WEB_INSPECTOR_STEPS.filter { it in here },
            here
        )
    }

    @Test
    fun `the cards this handset leaves out are exactly the ones it says it leaves out`() {
        // BOTH DIRECTIONS, AND THEY FAIL FOR DIFFERENT REASONS.
        //
        // A web card missing here and NOT in the register is a subject that went untaught without
        // anybody deciding — which is precisely how the whole design-workshop arc went missing from
        // this handset for months, and the failure `WalkthroughStepsTest` exists to prevent for the
        // designer's deck.
        //
        // An entry in the register that the web no longer teaches, or that this deck now teaches
        // after all, is the opposite and is the likelier of the two: the day somebody adds a
        // feedback box to `InspectionDetailScreen` and writes the card, this line is the thing that
        // reminds them the omission was written down somewhere. A register nobody prunes is a
        // register that ends up describing a product that no longer exists.
        val here = walkthroughInspectorJourney.map { it.id }.toSet()
        assertEquals(
            "the web's inspector deck teaches ${WEB_INSPECTOR_STEPS.filterNot { it in here }} and " +
                "this handset does not. `walkthroughInspectorOmissions` is the one register of " +
                "that decision and it says ${walkthroughInspectorOmissions.sorted()}. If a card " +
                "was dropped, argue it where the deck is declared and add it there; if a " +
                "capability has landed on this handset, write the step and take the id OUT of the " +
                "register. Never edit one side alone to make this green — that is the register " +
                "agreeing with itself about a product neither half is describing.",
            walkthroughInspectorOmissions.sorted(),
            WEB_INSPECTOR_STEPS.filterNot { it in here }.sorted()
        )
    }

    @Test
    fun `nothing is in the omission register that the web does not teach`() {
        // The register is a statement ABOUT the web's deck, so an id the web has never had is not an
        // omission at all — it is a typo, and the assertion above would still pass with one in it if
        // a second id were missing from this deck. Pinned separately so the failure names which of
        // the two happened.
        assertEquals(
            "these ids are registered as omissions and the web's inspector deck does not teach " +
                "them at all: ${walkthroughInspectorOmissions - WEB_INSPECTOR_STEPS.toSet()}",
            emptySet<String>(),
            walkthroughInspectorOmissions - WEB_INSPECTOR_STEPS.toSet()
        )
    }

    // ── The register of decks ────────────────────────────────────────────────────────────────────

    @Test
    fun `every deck this build carries is registered, and each is the object it claims to be`() {
        // A deck declared and not registered is a deck the role could open and no reader could ever
        // leave or reach, because `WALKTHROUGH_DECKS` is the switcher's whole source — the "it is a
        // default and not a gate" argument would be quietly false. Written as a set of ids rather
        // than a length so that a third deck left out fails BY NAME instead of by an off-by-one
        // somebody has to go and diff.
        assertEquals(
            "a deck is declared and not registered, so nothing can switch to it",
            listOf("designer", "inspector"),
            WALKTHROUGH_DECKS.map { it.id }
        )
        assertEquals(
            "two decks share an id, so the switcher's tick would land on whichever comes first",
            WALKTHROUGH_DECKS.size,
            WALKTHROUGH_DECKS.map { it.id }.toSet().size
        )
        // AND THE DESIGNER'S DECK IS THE LIST ITSELF, NOT A COPY OF IT. `WalkthroughStepsTest` and
        // `backend/tests/test_walkthrough_fields_parity.py` both read `walkthroughJourney` by name;
        // if the deck ever stopped BEING that list — a copy, a filter, a slice — both would keep
        // passing while the screen rendered something else. The web pins the identical property of
        // its own registry, in its own words, for this reason.
        assertSame(
            "the designer's deck must be the journey those suites read, not a copy of it",
            walkthroughJourney,
            WALKTHROUGH_DESIGNER_DECK.journey
        )
        assertSame(
            "the designer's deck must be the deck the screen has always rendered",
            walkthroughSteps,
            WALKTHROUGH_DESIGNER_DECK.steps
        )
        assertSame(
            "the inspector's deck must be the journey this file's parity assertions read",
            walkthroughInspectorJourney,
            WALKTHROUGH_INSPECTOR_DECK.journey
        )
    }

    @Test
    fun `no deck is empty, because the journey draws nothing under three cards`() {
        // `WalkthroughJourney` returns early on a deck shorter than three and renders a blank
        // full-screen window with a Skip button on it. Nothing else in either suite would report
        // that as a failure — it is not a crash, and every other assertion about a deck's contents
        // iterates an empty list happily.
        WALKTHROUGH_DECKS.forEach { deck ->
            assertTrue("“${deck.id}” has no numbered steps", deck.journey.isNotEmpty())
            assertTrue(
                "“${deck.id}” has ${deck.steps.size} cards and the journey draws nothing under three",
                deck.steps.size >= 3
            )
            assertTrue("“${deck.id}” has no name for the switcher", deck.name.isNotBlank())
            assertTrue("“${deck.id}” does not say who it is for", deck.audience.isNotBlank())
        }
    }

    // ── The selection ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the role picks the deck that opens, tier by tier`() {
        // THE INSPECTOR / REVIEWER TIER AND NOBODY ELSE. Written as a sweep over every tier rather
        // than as two assertions, because the interesting half is the ten answers that are NOT the
        // inspector's deck: the rank ladder puts six tiers ABOVE 37, every threshold instinct admits
        // them, and the inspection surface refuses all six by name.
        everyRole.forEach { role ->
            val expected = if (role == "INSPECTOR") "inspector" else "designer"
            assertEquals(
                "a $role opens on the wrong walkthrough. The inspection surface is set membership " +
                    "on {INSPECTOR} and is refused to an admin, the master admin and a professor " +
                    "by name, so this default is a set and never a rank floor.",
                expected,
                walkthroughDeckFor(account(role)).id
            )
        }
    }

    @Test
    fun `a ministry post opens on the designer's deck, and that is the ruling rather than an oversight`() {
        // The web gives the three directorate tiers a deck of their own. THIS HANDSET HAS NO SUCH
        // DECK, and the reason is written out with the greps beside `WalkthroughDeck`: four of that
        // deck's five screens do not exist here in any form — `grep -rniE 'annual[ _-]?plan'`,
        // `'monitored|monitoring'`, `'/officers'` and `'AnnualPlanScreen|SanctionOrderScreen|
        // OversightScreen'` all return zero over `android/app/src/main --include=*.kt` — and the
        // fifth, Design workshops, is already taught by the designer's deck under `design-workshop`.
        //
        // So a ministry post falls through to the deck that describes the one design-workshop
        // surface they can genuinely open and write in on this phone. Asserted separately from the
        // sweep above so that the failure names the decision rather than a tier in a loop, and so
        // that the day the handset grows those screens this test is the thing that has to be
        // rewritten deliberately.
        listOf("ASSISTANT_DIRECTOR", "REGIONAL_DIRECTOR", "MINISTRY_ADMIN").forEach { role ->
            assertEquals(role, "designer", walkthroughDeckFor(account(role)).id)
        }
    }

    @Test
    fun `a null account gets the designer's deck rather than nothing`() {
        // Not reachable from the dialog — the app renders the sign-in screen until there is a user —
        // but the function is pure and exported, and answering null for an unknown account would
        // push the null check onto every caller.
        assertEquals("designer", walkthroughDeckFor(null).id)
    }

    @Test
    fun `choosing a deck by role takes nothing away from anybody`() {
        // THE WHOLE DESIGN RESTS ON THIS AND IT IS THE HALF THAT WOULD ROT SILENTLY. A role-chosen
        // deck is a DEFAULT: the walkthrough's menu row is ungated for every tier
        // (`WalkthroughSurfaceTest` walks all eleven asserting it), every deck ships in the APK for
        // everybody, and the opening card of whichever deck opens offers the others. If this ever
        // becomes a filter over which decks EXIST for an account, the handset has quietly become
        // narrower than the web on the client with fewer screens.
        //
        // Asserted as "the register does not depend on the account", which is what
        // `WalkthroughJourney` is handed: the switcher draws `WALKTHROUGH_DECKS`, unfiltered, and
        // the deck that opens is one member of it.
        val menu = FIELD_NAV_ITEMS.first { it.destination == NavDestination.WALKTHROUGH }
        everyRole.forEach { role ->
            val user = account(role)
            assertTrue("the walkthrough is hidden from $role", menu.can(user))
            assertTrue(
                "a $role opens on “${walkthroughDeckFor(user).id}”, which is not a registered deck",
                WALKTHROUGH_DECKS.any { it.id == walkthroughDeckFor(user).id }
            )
            assertEquals(
                "the deck register is being narrowed for $role. It is a default and not a gate: " +
                    "the role picks which deck OPENS and every deck stays reachable for everybody.",
                listOf("designer", "inspector"),
                WALKTHROUGH_DECKS.map { it.id }
            )
        }
    }

    // ── The inspector deck's own doors ───────────────────────────────────────────────────────────

    @Test
    fun `every inspector step opens a screen this handset actually has`() {
        // The deck exists at all because three of the web's four cards name a surface that is really
        // here. This is the assertion that keeps it that way: `WalkthroughStepsTest` already checks
        // that a step's destination is a row in `FIELD_NAV_ITEMS`, and what is added here is that
        // NONE of these three is null. A card with no door on this deck would be the shape the
        // fourth one was deliberately not written in — prose about a screen with no way to reach it
        // — and it must be a decision with a register entry, never a step that quietly lost its
        // destination.
        walkthroughInspectorJourney.forEach { step ->
            assertTrue(
                "“${step.id}” has no destination. A card on this deck either opens a real screen " +
                    "or is not a card: the capability that has none is recorded in " +
                    "`walkthroughInspectorOmissions` and taught on the closing card instead.",
                step.destination != null
            )
        }
        // AND THE LIST AND THE READ SHARE ONE DOOR, ON PURPOSE. `InspectionDetailScreen` is reached
        // by tapping a card in `InspectionListScreen` and has no menu row of its own, so the read
        // step carries the nearest door and its body says which control to press — the same rule the
        // five design-workshop steps already follow. A button that lands you one tap away is worth
        // having; a button that does nothing is the first defect a reviewer finds.
        assertEquals(
            "the inspection read must point at the list it is reached from",
            NavDestination.DESIGN_WORKSHOP_INSPECTIONS,
            walkthroughInspectorJourney.first { it.id == "inspection-read" }.destination
        )
        // AND THE REVIEW CARD'S ADDRESS IS REWRITTEN RATHER THAN INVENTED. The web's `/review` is a
        // page of its own; on this handset the same nav row opens the record browser, which is what
        // `MainActivity`'s router arm says in as many words. The precedent is the designer deck's
        // own `review` step, against this same destination.
        assertEquals(
            "the review card must name the row this handset really has",
            NavDestination.REVIEW,
            walkthroughInspectorJourney.first { it.id == "inspection-review-queue" }.destination
        )
    }
}

// ── Reading the web's own inspector deck ────────────────────────────────────────────────────────

/** The one place this path is written. Named in failure messages so a move reports itself. */
private const val WEB_INSPECTOR_PATH = "frontend/components/guide/inspectorSteps.ts"

/**
 * A file of this repository, found by walking up from wherever the test runner started.
 *
 * MISSING IS A FAILURE, LOUDLY, AND NEVER A SKIP. A test that quietly passed when it could not find
 * its subject would prove nothing on the day somebody moves that subject — which is the one day it
 * is most needed, and the day its silence would be read as parity. Same helper and same reasoning as
 * `WalkthroughStepsTest` and `DashboardTileParityTest`; copied rather than shared because both of
 * those are private to their own files and lifting one would put a test helper into the production
 * tree for the benefit of three callers.
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
