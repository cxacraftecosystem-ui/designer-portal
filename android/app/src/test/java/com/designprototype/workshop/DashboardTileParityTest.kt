package com.designprototype.workshop

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FIELD_NAV_ITEMS
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.NavDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE TWO DASHBOARD TILE REGISTERS, HELD TO EACH OTHER.
 *
 * ── WHY THIS FILE EXISTS ────────────────────────────────────────────────────────────────────────
 *
 * The dashboard is the screen both clients open on, and its grid is the whole of what a designer
 * scans before deciding what this application is for. It is also the one register nobody enumerates:
 * a destination can arrive with a screen, a route, a menu row, a permission predicate and a line in
 * `docs/PERMISSIONS.md` and still be invisible to the person who asked for it, because every
 * register a reviewer thinks to check had it and the grid did not. That has now happened on both
 * clients. `frontend/e2e/dashboard-tile-parity-unit.spec.ts` opens with the web instance of it —
 * everything shipped, and "the owner still reported the feature as still not there" — and the
 * Sketches & prototypes card this file was written beside is the handset's.
 *
 * Nothing mechanical has ever compared the two grids. The web array carries a long comment promising
 * parity tile by tile, and that comment records, IN ITS OWN WORDS, having been wrong about this more
 * than once. "this comment said the opposite", "two of the four lines below said the wrong thing
 * until" and "the feature does not exist on Android in any form" are all its own sentences, and
 * each of them is a claim about THIS client (true as of 2026-08-27; check
 * `grep -n "said the opposite" "frontend/app/(protected)/dashboard/page.tsx"`). A
 * register written down twice and checked by nobody drifts, and it drifts SILENTLY and in the worst
 * direction: the next reader either hunts a gap that is already closed or deletes a working tile
 * believing the other client never had it.
 *
 * ── WHAT IS DELIBERATELY DIFFERENT, AND THEREFORE NOT A FAILURE ─────────────────────────────────
 *
 *  1. TWO TILES ARE WEB-ONLY: "Design review" and "My designer profile". Both are real, working
 *     destinations on this handset — `NavDestination.DESIGN_REVIEW` and
 *     `NavDestination.DESIGNER_PROFILE`, with a screen behind each. What they lack is a CARD, and
 *     the reason is structural: this grid is the bespoke cards plus `EntryMode.entries` plus the
 *     admin card, and neither of those two destinations is an `EntryMode`. Say the missing thing
 *     precisely and never a tier more than that — the version of this claim that said "no feature"
 *     was wrong about sketches for a whole release, and an over-claim sends every reader looking
 *     for a gap that is not there. Design review is the next card to add, and it is deliberately a
 *     separate change: one at a time, so the grid row each costs on a 360dp handset is argued on
 *     its own evidence. Moving it means editing [WEB_ONLY] and writing the reason there.
 *  2. SETTINGS SITS LAST HERE AND MID-GRID ON THE WEB. This grid appends the admin card after
 *     Workshop; the web array lists it between Users and Craft. Already recorded in that array's own
 *     comment ("The ORDER already diverges too"), so it is pinned as the one exception rather than
 *     left to fail. Everything else is asserted to stand in one order on both clients.
 *  3. HREFS, GLYPHS AND PREDICATES ARE NOT COMPARED ACROSS CLIENTS, on purpose. A destination is
 *     `/artisans/new` there and a `Screen.Create` here; a glyph is lucide there and Material here; a
 *     predicate is TypeScript there and Kotlin here. Comparing any of them would be comparing two
 *     languages' spellings. What genuinely crosses between the two grids is what a designer READS —
 *     the tile's label, the word on its button, and whether there is a second button at all — and
 *     that is what this file compares. §1 of the frontend contract is the rule being enforced:
 *     Android owns the words, and a researcher moves between the two apps mid-workshop.
 *
 * ── WHAT IT ASSERTS ABOUT THE HANDSET ALONE ─────────────────────────────────────────────────────
 *
 * Three things the web has no opinion about: that the Sketches & prototypes card is offered to
 * exactly the roles the API lets run a workshop (the same role walk `DesignWorkshopCardTest` makes,
 * for the same reason — a rank ladder and the set {DESIGNER, ADMIN, MASTER_ADMIN} agree on six roles
 * out of seven, so only walking all seven can tell them apart); that the card and its menu row read
 * one predicate and one label; and that no two cards on the grid draw the same glyph, which is why
 * the Sketches card does not take `Icons.Filled.Brush` from its own menu row — Craft already holds
 * Brush here and an admin is offered both cards.
 *
 * ── WHY IT READS SOURCE ─────────────────────────────────────────────────────────────────────────
 *
 * Neither register can be imported. `EntryMode`, `DashboardTile`, `DashboardScreen` and the grid are
 * all file-private to `MainActivity.kt` — deliberately; they are one screen's furniture — and the
 * web `tiles` array is a local inside a React component, with no renderer in the frontend's
 * devDependencies (`dashboard-tile-parity-unit.spec.ts` and `web-surface-gaps-unit.spec.ts` both say
 * so and both read source for it). Source is also the stronger instrument for the defect being
 * defended against, which is somebody EDITING one of these arrays: a bespoke card added with a label
 * this file cannot resolve fails here BY NAME, and a reorder — the one property of either array that
 * no type, lint or compiler has an opinion about, and which is invisible in review — fails here too.
 *
 * COMMENTS ARE STRIPPED FIRST, and that is not housekeeping. Both arrays are surrounded by prose
 * naming tiles that are not tiles and paths that are not hrefs, and an assertion that something is
 * "in the grid" must not be satisfiable by a sentence about it. The two strippers differ in one
 * respect that matters and is a documented trap in this tree: KOTLIN BLOCK COMMENTS NEST and
 * TypeScript's do not, so a Kotlin file may legally carry a quoted comment opener inside a comment,
 * and a non-nesting scanner would end that comment early and mistake the rest of the file for code.
 * [stripComments] takes the difference as a flag and a test below pins both halves of it.
 *
 * THE TRAP HAS ALREADY BITTEN THIS FILE, WHICH IS WHY THE GAP PARAGRAPH BELOW SPELLS ITS GLOB IN
 * WORDS. The workflow filter was written out literally as an `android` path followed by a
 * recursive wildcard; the slash and the first star of that wildcard are a `/` and a `*` side by
 * side, so they opened a SECOND block comment inside this KDoc, this comment's own closer shut
 * only that one, and the parser stayed inside a comment until the next closer in the file, which
 * is the one buried in the `nested` string literal of the stripper test below. Everything
 * between was swallowed, and the module did not compile — which takes the whole unit-test
 * source set with it, since Kotlin compiles it as one unit.
 *
 * HOW THAT WAS ESTABLISHED, since a compile error cannot be re-observed once it is fixed: a
 * Kotlin-aware scan of this file (nesting block comments, honouring line comments and the three
 * string forms) ended INSIDE a string literal with newlines in it, which Kotlin does not admit,
 * and after the one-line reword below it ends in code at nesting depth 0. The scan is five
 * minutes to rewrite and is the check to repeat if this ever looks wrong again; the compiler's
 * own error count is not quoted here because this session never saw it.
 * Corrected 2026-08-27. The same rule governs this very paragraph: neither a comment OPENER nor
 * a comment CLOSER may be typed out anywhere in this KDoc, which is why both are named in words.
 * Name the tree instead of globbing it, or the module stops building again.
 *
 * ── ONE HONEST GAP ──────────────────────────────────────────────────────────────────────────────
 *
 * A pull request touching only `frontend/` does not run this suite. `.github/workflows/
 * android-build.yml`'s `pull_request` filter is the whole `android` tree, its own path, and three
 * backend registry files — so a web-side edit to the tile array is caught when the Android job
 * next runs, on `main`, where the workflow is stage 3 of the deploy chain, and not on the pull
 * request that made it. Worth knowing rather than hiding. Widening that filter is that
 * workflow's decision, and its
 * own comment already sets the test for it — "does a change to this path alter what a Kotlin test
 * asserts?" — which this file answers yes to.
 */
class DashboardTileParityTest {

    private fun user(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
        "INSPECTOR",
        "PROFESSOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    @Test
    fun `the parsers found the two arrays they are aimed at`() {
        // Without this the whole file is vacuously green the day either array is renamed, extracted
        // into a hook or moved to a module: every lookup below would come back empty and every
        // failure would be reported as "the tile is missing" when the tile is fine and the parser
        // is not. It is the failure mode of every source-reading test.
        assertTrue("the web tile array parsed to ${WEB.size} tiles", WEB.size > 15)
        assertTrue("the handset grid parsed to ${ANDROID.size} cards", ANDROID.size > 15)
        assertTrue(WEB.map { it.label }.contains("Miscellaneous Media"))
        assertTrue(ANDROID.map { it.label }.contains("Miscellaneous Media"))
        // The admin card is the last thing this grid appends, and the assertion about where
        // Settings stands is worth nothing if it was never parsed at all.
        assertTrue(ANDROID.map { it.label }.contains(SETTINGS))
        // Every card on both sides carries a real word on its button. A blank here means a
        // `newLabel` or a `primaryLabel` resolved to nothing, and the word comparisons below would
        // then be comparing nothing to nothing.
        assertTrue((WEB + ANDROID).all { it.label.isNotBlank() && it.button.isNotBlank() })
    }

    @Test
    fun `the comment stripper knows that Kotlin block comments nest and TypeScript's do not`() {
        // The trap in words: an unbalanced opener inside a KDoc — a mime wildcard written out in
        // prose is the one that bites in this tree — opens a SECOND comment in Kotlin, and the
        // block's own closer then closes only that one. A scanner that did not nest would treat the
        // tail of MainActivity.kt as code and find "tiles" inside a sentence about them.
        val nested = "A/*outer/*inner*/still outer*/B"
        assertEquals("AB", stripComments(nested, nesting = true))
        assertEquals("Astill outer*/B", stripComments(nested, nesting = false))
        // A quote inside a comment must not open a string...
        assertEquals("xy", stripComments("x/*it's a comment*/y", nesting = true))
        // ...and a comment opener inside a string must not open a comment. Both registers are full
        // of string values with slashes in them.
        assertEquals("\"/artisans/new\"", stripComments("\"/artisans/new\"", nesting = false))
        assertEquals("a\nc", stripComments("a//b\nc", nesting = true))
    }

    @Test
    fun `every card the handset draws is on the web, with the same words`() {
        val web = WEB.associateBy { it.label }
        val wrong = mutableListOf<String>()

        for (tile in ANDROID) {
            val twin = web[tile.label]
            if (twin == null) {
                wrong += "the handset draws a \"${tile.label}\" card and the web grid has no tile " +
                    "with that label — either the web is missing the tile, or one of the two " +
                    "invented a spelling the other cannot match"
                continue
            }
            // Rule 2 of the web array's parity note: a tile's `newLabel` IS
            // `EntryMode.createButtonLabel()`. A researcher moves between the handset and the
            // laptop mid-workshop, so the same button has to carry the same word.
            if (twin.button != tile.button) {
                wrong += "\"${tile.label}\" says \"${tile.button}\" on the handset and " +
                    "\"${twin.button}\" on the web"
            }
            // The second button is a claim about the DESTINATION rather than about styling: it says
            // this thing can be opened again for editing. One client offering it and the other not
            // means the two disagree about what the tile leads to.
            if (twin.hasSecondButton != tile.hasSecondButton) {
                wrong += "\"${tile.label}\" has a second button on " +
                    (if (tile.hasSecondButton) "the handset only" else "the web only")
            }
        }

        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `the tiles the web has and the handset does not are exactly the ones written down here`() {
        val onHandset = ANDROID.map { it.label }.toSet()
        assertEquals(
            "a web tile has no card here and this file does not know about it — add the card, or " +
                "add its label to WEB_ONLY together with the reason it belongs there",
            WEB_ONLY,
            WEB.map { it.label }.filterNot { it in onHandset }
        )
        // And nothing runs the other way. A card here with no web tile would be a destination a
        // designer can find on their phone and cannot find on the laptop they write the report on.
        val onWeb = WEB.map { it.label }.toSet()
        assertEquals(emptyList<String>(), ANDROID.map { it.label }.filterNot { it in onWeb })
    }

    @Test
    fun `the shared tiles stand in one order, and Settings is the single exception`() {
        // Rule 4 of the web array's parity note. A tile nineteenth in a grid of twenty is not
        // discoverable on a phone — it is below the fold, under six kinds of reference data the
        // designer did not open the app for — and nothing else in either project has an opinion
        // about the order of these two literals.
        val webShared = WEB.map { it.label }.filterNot { it in WEB_ONLY || it == SETTINGS }
        val handsetShared = ANDROID.map { it.label }.filterNot { it == SETTINGS }
        assertEquals(webShared, handsetShared)

        // The exception, pinned from both ends so that it stays THIS exception rather than becoming
        // cover for the next one. This grid is built as the bespoke cards, then `EntryMode.entries`
        // in declaration order, then the admin card; the web array simply lists Settings where an
        // admin would look for it.
        assertEquals("Settings is no longer the handset's last card", SETTINGS, ANDROID.last().label)
        val webLabels = WEB.map { it.label }
        val at = webLabels.indexOf(SETTINGS)
        assertEquals("Users", webLabels[at - 1])
        assertEquals("Craft", webLabels[at + 1])
    }

    @Test
    fun `the design workshop block leads both grids`() {
        // FIRST AND SECOND, and deliberately tighter than "somewhere in the grid". This is the
        // product: a designer opens the app to run a design and prototype workshop, and everything
        // below — artisans, products, tools, the questionnaire — is the reference data a workshop
        // draws on. Both grids make that call, and both say so in a comment above the tile.
        assertEquals(
            listOf(DesignWorkshopCard.LABEL, SketchesAndPrototypesCard.LABEL),
            ANDROID.take(2).map { it.label }
        )
        // The web leads with the same two, then the third member of the block — the card this
        // handset has not grown yet — then Artisan, so the block cannot be padded from below
        // without somebody deciding here that the new tile belongs inside it.
        assertEquals(
            listOf(DesignWorkshopCard.LABEL, SketchesAndPrototypesCard.LABEL, "Design review", "Artisan"),
            WEB.take(4).map { it.label }
        )
    }

    @Test
    fun `the sketches card is offered to exactly the roles the server lets run a workshop`() {
        val offered = everyRole.filter { SketchesAndPrototypesCard.visibleTo(user(it)) }.toSet()
        assertEquals(
            "the Sketches & prototypes card must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf("DESIGNER", "ADMIN", "MASTER_ADMIN"),
            offered
        )
        // The mistake worth walking every role for: the rest of this grid is filtered by
        // `canCreate(mode)`, a RANK ladder, and a professor outranks a designer everywhere else in
        // this app while sitting outside this set. Copying that habit — or the web tile's old
        // `visible: creator` — puts the card in front of two tiers whose only destination is the
        // "Designer access required" refusal.
        for (role in listOf("RESEARCHER", "PROFESSOR")) {
            val account = user(role)
            assertTrue("$role can create records", FieldPermissions.canCreateRecords(account))
            assertFalse("$role must not be offered the card", SketchesAndPrototypesCard.visibleTo(account))
        }
    }

    @Test
    fun `the sketches card and its menu row carry one predicate and one label`() {
        val row = FIELD_NAV_ITEMS.first { it.destination == NavDestination.SKETCHES_AND_PROTOTYPES }

        val disagreements = everyRole.filter { role ->
            row.can(user(role)) != SketchesAndPrototypesCard.visibleTo(user(role))
        }
        assertEquals(
            "the dashboard card and the Sketches & prototypes menu row must never disagree",
            emptyList<String>(),
            disagreements
        )

        // ONE SPELLING, which is the OPPOSITE of [DesignWorkshopCard] — whose card is singular
        // against a plural row, and which asserts that difference by hand next door. That pairing is
        // a one-off carried by a bespoke card on both clients. Here neither the card nor the row is
        // an `EntryMode`, so there is no `label` against `actionTitle` to differ by and the card
        // takes the row's own words. The destination already answers to a second spelling on the web
        // ("Sketches and Prototypes", that page's title); a third invented here would be found by
        // nobody's grep and would read to a designer as a different feature.
        assertEquals("Sketches & prototypes", SketchesAndPrototypesCard.LABEL)
        assertEquals(SketchesAndPrototypesCard.LABEL, row.label)
    }

    @Test
    fun `an Open button draws an arrow and a New button draws a plus`() {
        // `DashboardCard.tsx` picks its glyph off these two literal words — "a plus on a button that
        // only navigates is a lie" — and then claims "Android draws the same distinction".
        // [dashboardPrimaryIcon] is what finally makes that sentence true; before it, five cards on
        // the first screen of this app drew a plus beside the word "Open".
        assertEquals("AutoMirrored.Filled.ArrowForward", dashboardPrimaryIcon("Open").name)
        assertEquals("AutoMirrored.Filled.ArrowForward", dashboardPrimaryIcon("Manage").name)
        // Not a general rule about verbs: those two words create nothing and every other word on
        // this grid does, so "Upload" and "New interview" keep the plus on both clients.
        for (word in listOf("New", "New workshop", "New interview", "Upload")) {
            assertEquals(
                "\"$word\" creates something, so it keeps the plus",
                "Filled.Add",
                dashboardPrimaryIcon(word).name
            )
        }
        // The web half of the pair, READ rather than remembered. Renaming that branch turns every
        // "Open" button on that client back into a lie about what it does, and this is the only
        // place the two implementations are checked against each other.
        val card = repoFile(
            "../frontend/components/DashboardCard.tsx",
            "frontend/components/DashboardCard.tsx",
        ).readText(Charsets.UTF_8)
        assertTrue(
            "DashboardCard.tsx no longer branches on the words this grid derives its glyphs from",
            card.contains("newLabel === \"Open\" || newLabel === \"Manage\"")
        )
    }

    @Test
    fun `no two cards on this grid draw the same glyph`() {
        // A grid is scanned side by side, two cards to a row on a 360dp handset, so a repeated glyph
        // is two tiles a designer cannot tell apart at a glance. This is why the Sketches &
        // prototypes card does NOT take `Icons.Filled.Brush` from its own menu row: Craft already
        // holds Brush on this grid, and an admin passes both predicates and sees both cards. The
        // drawer keeps Brush for that row and that is correct — there the two sit under different
        // group headings and are never scanned together.
        val shared = ANDROID.groupBy { it.glyph }.filterValues { it.size > 1 }
        assertEquals(
            emptyMap<String, List<String>>(),
            shared.mapValues { (_, tiles) -> tiles.map { it.label } }
        )
        // ...and every card really did carry one, rather than the glyph column parsing to blanks
        // that then all compare equal to each other and to nothing.
        assertTrue(ANDROID.all { it.glyph.isNotBlank() })
    }

    @Test
    fun `the two menu-only modes stay off both grids`() {
        // `onDashboard = false` is the flag, and Search and the Data Browser are the whole of what
        // carries it — both sit in the web nav's Browse group and off its tile array for the same
        // reason. A third one appearing here means somebody hid a card instead of removing it.
        assertEquals(
            listOf("Search", "Data Browser"),
            MODES.filterNot { it.onDashboard }.map { it.label }
        )
        for (label in listOf("Search", "Data Browser")) {
            val handset = ANDROID.map { it.label }
            val web = WEB.map { it.label }
            assertFalse("$label is a menu row, not a card, on the handset", label in handset)
            assertFalse("$label is a menu row, not a tile, on the web", label in web)
        }
    }
}

/* ─────────────────────────────────────────────────────────────────────────────────────────────────
 * Reading the two registers. Everything below is file-private: it exists to feed the assertions
 * above and nothing else in this module should grow a second copy of it.
 * ───────────────────────────────────────────────────────────────────────────────────────────────── */

/** See point 1 of the header. In the web array's own order, so a failure names them in that order. */
private val WEB_ONLY = listOf("Design review", "My designer profile")

private const val SETTINGS = "Settings"

/**
 * The bespoke cards' strings, resolved from the objects that OWN them rather than from source text,
 * so a rewording fails the comparison instead of quietly passing the parse.
 *
 * A closed list on purpose. A third bespoke card — Design review is the one that is coming — reaches
 * [resolve] with an expression this map has no entry for and fails BY NAME, which forces its author
 * to come here and say what the new card is. Same mechanism, for the same reason, as the closed
 * `FAMILY` list in `frontend/e2e/dashboard-tile-parity-unit.spec.ts`: the defect this family keeps
 * producing is a member arriving in one register and no other.
 */
private val CONSTANTS = mapOf(
    "DesignWorkshopCard.LABEL" to DesignWorkshopCard.LABEL,
    "DesignWorkshopCard.PRIMARY_LABEL" to DesignWorkshopCard.PRIMARY_LABEL,
    "SketchesAndPrototypesCard.LABEL" to SketchesAndPrototypesCard.LABEL,
    "SketchesAndPrototypesCard.PRIMARY_LABEL" to SketchesAndPrototypesCard.PRIMARY_LABEL,
)

/** What a reader of either grid actually sees. Nothing platform-shaped crosses this type. */
private class Tile(
    val label: String,
    val button: String,
    val hasSecondButton: Boolean,
    /** Handset only, compared only against its own siblings. Empty for a web tile — see point 3. */
    val glyph: String,
)

private class Mode(
    val name: String,
    val label: String,
    val editable: Boolean,
    val onDashboard: Boolean,
)

private val KOTLIN_SOURCE: String by lazy {
    val source = repoFile(
        "src/main/java/com/designprototype/workshop/MainActivity.kt",
        "app/src/main/java/com/designprototype/workshop/MainActivity.kt",
        "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt",
    ).readText(Charsets.UTF_8).replace("\r\n", "\n")
    // The scanners below understand one kind of Kotlin string literal. A raw string would open and
    // close three times over and desynchronise everything after it, so say THAT out loud on the day
    // one arrives rather than reporting it as a missing tile.
    assertFalse(
        "MainActivity.kt has grown a raw string; stripComments does not understand one yet",
        source.contains("\"\"\"")
    )
    stripComments(source, nesting = true)
}

private val WEB_SOURCE: String by lazy {
    repoFile(
        "../frontend/app/(protected)/dashboard/page.tsx",
        "frontend/app/(protected)/dashboard/page.tsx",
    ).readText(Charsets.UTF_8).replace("\r\n", "\n").let { stripComments(it, nesting = false) }
}

private val MODES: List<Mode> by lazy { entryModes(KOTLIN_SOURCE) }
private val ANDROID: List<Tile> by lazy { handsetGrid(KOTLIN_SOURCE, MODES) }
private val WEB: List<Tile> by lazy { webTiles(WEB_SOURCE) }

/**
 * A file of this repository, found by walking up from wherever the test runner started.
 *
 * The working directory of a Gradle test worker is not something to depend on, and a test that
 * skipped when it could not find its subject would prove nothing on the day somebody moves it.
 * Missing is a failure, loudly. Same helper and same reasoning as `DesignWorkshopCardTest` and
 * `DwWorkshopSearchRegistryTest` — and the `..`-prefixed candidates are what let it reach OUT of
 * `android/` and into `frontend/`, which is the whole point of this file.
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

/**
 * Drop line and block comments, keeping string contents intact.
 *
 * `nesting` is the one difference between the two languages read here, and getting it backwards does
 * not fail loudly — it silently hands the parser a slab of prose or a slab of code. A Kotlin block
 * comment NESTS, so an opener written inside a KDoc opens a second comment that the block's own
 * closer then closes instead of ending the block. TypeScript's does not nest, so the same text ends
 * at the first closer.
 */
private fun stripComments(source: String, nesting: Boolean): String {
    val out = StringBuilder()
    var i = 0
    var quote: Char? = null
    while (i < source.length) {
        val c = source[i]
        val next = if (i + 1 < source.length) source[i + 1] else ' '
        if (quote != null) {
            out.append(c)
            if (c == '\\') {
                if (i + 1 < source.length) out.append(next)
                i += 2
                continue
            }
            if (c == quote) quote = null
            i += 1
            continue
        }
        if (c == '"' || c == '\'' || c == '`') {
            quote = c
            out.append(c)
            i += 1
            continue
        }
        if (c == '/' && next == '/') {
            while (i < source.length && source[i] != '\n') i += 1
            continue
        }
        if (c == '/' && next == '*') {
            var depth = 1
            i += 2
            while (i < source.length && depth > 0) {
                if (nesting && source[i] == '/' && i + 1 < source.length && source[i + 1] == '*') {
                    depth += 1
                    i += 2
                    continue
                }
                if (source[i] == '*' && i + 1 < source.length && source[i + 1] == '/') {
                    depth -= 1
                    i += 2
                    continue
                }
                i += 1
            }
            continue
        }
        out.append(c)
        i += 1
    }
    return out.toString()
}

/** The text between the first `opener` at or after `from` and the bracket that closes it. */
private fun balanced(source: String, from: Int, opener: Char): String {
    val start = source.indexOf(opener, from)
    assertTrue("no '$opener' after offset $from", start >= 0)
    var i = start + 1
    var depth = 1
    var quote: Char? = null
    val out = StringBuilder()
    while (i < source.length) {
        val c = source[i]
        if (quote != null) {
            out.append(c)
            if (c == '\\') {
                out.append(source[i + 1])
                i += 2
                continue
            }
            if (c == quote) quote = null
            i += 1
            continue
        }
        if (c == '"' || c == '\'' || c == '`') {
            quote = c
            out.append(c)
            i += 1
            continue
        }
        if (c == '(' || c == '[' || c == '{') depth += 1
        if (c == ')' || c == ']' || c == '}') {
            depth -= 1
            if (depth == 0) return out.toString()
        }
        out.append(c)
        i += 1
    }
    throw AssertionError("unbalanced '$opener' from offset $from")
}

/** Split on separators standing at nesting depth zero, outside strings. */
private fun splitTop(body: String, separator: Char = ','): List<String> {
    val parts = mutableListOf<String>()
    val cur = StringBuilder()
    var depth = 0
    var quote: Char? = null
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (quote != null) {
            cur.append(c)
            if (c == '\\') {
                cur.append(body[i + 1])
                i += 2
                continue
            }
            if (c == quote) quote = null
            i += 1
            continue
        }
        if (c == '"' || c == '\'' || c == '`') {
            quote = c
            cur.append(c)
            i += 1
            continue
        }
        if (c == '(' || c == '[' || c == '{') depth += 1
        if (c == ')' || c == ']' || c == '}') depth -= 1
        if (c == separator && depth == 0) {
            parts.add(cur.toString().trim())
            cur.setLength(0)
            i += 1
            continue
        }
        cur.append(c)
        i += 1
    }
    parts.add(cur.toString().trim())
    return parts.filter { it.isNotEmpty() }
}

/** `key: value` (a TypeScript object) or `key = value` (Kotlin named arguments); first one wins. */
private fun properties(inner: String, separator: Char): Map<String, String> {
    val props = LinkedHashMap<String, String>()
    for (field in splitTop(inner)) {
        val at = field.indexOf(separator)
        if (at < 0) continue
        props[field.substring(0, at).trim()] = field.substring(at + 1).trim()
    }
    return props
}

private fun unquote(raw: String?): String? =
    if (raw != null && raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
        raw.substring(1, raw.length - 1)
    } else {
        null
    }

private fun requireQuoted(raw: String?, what: String): String {
    val value = unquote(raw)
    assertNotNull("$what is not a plain string literal: $raw", value)
    return value!!
}

/** A bespoke card's label or button word: a literal, or one of the [CONSTANTS] it may name. */
private fun resolve(expression: String?, what: String): String {
    assertNotNull("a bespoke dashboard card has no `$what`", expression)
    val literal = unquote(expression)
    if (literal != null) return literal
    val known = CONSTANTS[expression]
    assertNotNull(
        "a bespoke dashboard card's `$what` is `$expression`, which this file cannot resolve — " +
            "add it to CONSTANTS and say in the header which card it belongs to",
        known
    )
    return known!!
}

/* ── the web array ──────────────────────────────────────────────────────────────────────────── */

private fun webTiles(source: String): List<Tile> {
    val declaration = "const tiles: Tile[] = ["
    val at = source.indexOf(declaration)
    assertTrue("the web dashboard no longer declares `$declaration`", at >= 0)
    // Past the WHOLE declaration, not to the next bracket — which is the one in `Tile[]`, whose
    // closing bracket is one character later. The web spec records losing every assertion in its own
    // file to exactly that off-by-one.
    val body = balanced(source, at + declaration.length - 1, '[')
    return splitTop(body).map { entry ->
        assertTrue(
            "a web tile is not an object literal: ${entry.take(60)}",
            entry.startsWith("{") && entry.endsWith("}")
        )
        val props = properties(entry.substring(1, entry.length - 1), ':')
        Tile(
            label = requireQuoted(props["label"], "a web tile's label"),
            // `newLabel` is optional in the web `Tile` type and `DashboardCard` defaults it to
            // "New" — the same default `EntryMode.createButtonLabel()`'s `else` arm carries.
            button = props["newLabel"]?.let { requireQuoted(it, "a web tile's newLabel") } ?: "New",
            hasSecondButton = props.containsKey("updateHref"),
            glyph = "",
        )
    }
}

/* ── the handset grid ───────────────────────────────────────────────────────────────────────── */

private fun entryModes(source: String): List<Mode> {
    val head = "private enum class EntryMode("
    val at = source.indexOf(head)
    assertTrue("MainActivity.kt no longer declares `$head`", at >= 0)
    val constructor = balanced(source, at + head.length - 1, '(')
    val body = balanced(source, at + head.length + constructor.length, '{')
    return splitTop(body).map { entry ->
        val open = entry.indexOf('(')
        assertTrue("an EntryMode member carries no arguments: ${entry.take(40)}", open > 0)
        val name = entry.substring(0, open).trim()
        val args = splitTop(balanced(entry, open, '('))
        Mode(
            name = name,
            // The TILE word, which is the first constructor argument. The second is `actionTitle`,
            // the MENU row's word, and it is not a dashboard string at all.
            label = requireQuoted(args.firstOrNull(), "$name's label"),
            editable = args.any { it.replace(" ", "") == "editable=true" },
            onDashboard = args.none { it.replace(" ", "") == "onDashboard=false" },
        )
    }
}

/** `EntryMode.X -> "y"` arms keyed by member name, plus whatever the `else` arm says. */
private fun whenArms(source: String, header: String): Pair<Map<String, String>, String?> {
    val at = source.indexOf(header)
    assertTrue("MainActivity.kt no longer declares `$header`", at >= 0)
    val body = balanced(source, at + header.length - 1, '{')
    val arms = LinkedHashMap<String, String>()
    var fallback: String? = null
    for (raw in body.lines()) {
        val line = raw.trim()
        val arrow = line.indexOf("->")
        if (arrow < 0) continue
        val result = line.substring(arrow + 2).trim()
        for (key in line.substring(0, arrow).split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            if (key == "else") {
                fallback = result
            } else {
                arms[key.substringAfterLast('.')] = result
            }
        }
    }
    return arms to fallback
}

private fun bespokeCards(fragment: String): List<Tile> {
    val construction = "DashboardTile("
    val out = mutableListOf<Tile>()
    var at = fragment.indexOf(construction)
    while (at >= 0) {
        val props = properties(balanced(fragment, at + construction.length - 1, '('), '=')
        out.add(
            Tile(
                label = resolve(props["label"], "label"),
                button = resolve(props["primaryLabel"], "primaryLabel"),
                hasSecondButton = props.containsKey("onUpdate"),
                glyph = props["icon"] ?: "",
            )
        )
        at = fragment.indexOf(construction, at + 1)
    }
    return out
}

/**
 * The grid a master admin sees: every bespoke card declared before the loop, then every dashboard
 * `EntryMode` in declaration order, then every bespoke card declared after it.
 *
 * Reconstructed with all the `if (show…)` guards TAKEN, which is the right basis for the comparison:
 * the web `tiles` array is likewise the full list before `visible` filters it. What this file
 * compares is the two registers; who sees which row of them is a per-tile question, and the role
 * tests above answer it for the one card this change added.
 */
private fun handsetGrid(source: String, modes: List<Mode>): List<Tile> {
    val head = "val tiles = buildList {"
    val at = source.indexOf(head)
    assertTrue("DashboardScreen no longer builds its grid with `$head`", at >= 0)
    val grid = balanced(source, at + head.length - 1, '{')

    val loopAt = grid.indexOf("actions.forEach")
    assertTrue("the grid no longer splices EntryMode in with `actions.forEach`", loopAt >= 0)
    assertTrue(
        "the grid splices EntryMode in more than once and this parser assumes a single block",
        grid.indexOf("actions.forEach", loopAt + 1) < 0
    )
    val braceAt = grid.indexOf('{', loopAt)
    val loop = balanced(grid, loopAt, '{')
    val lead = grid.substring(0, loopAt)
    val trail = grid.substring(braceAt + loop.length + 1)
    // The loop body is the one place `entry` is in scope, so this is what proves the cut landed
    // where it was aimed rather than in the middle of some other lambda.
    assertTrue("the EntryMode loop is not where this parser cut", loop.contains("entry.label"))
    assertFalse("the EntryMode loop leaked past the cut", trail.contains("entry.label"))

    val (words, wordFallback) = whenArms(
        source, "private fun EntryMode.createButtonLabel(): String = when (this) {"
    )
    val (glyphs, glyphFallback) = whenArms(
        source, "private fun EntryMode.icon(): ImageVector = when (this) {"
    )
    assertNotNull("createButtonLabel has lost its `else` arm", wordFallback)
    // `icon()` is exhaustive over the enum on purpose: a mode with no glyph must be a compile error
    // there rather than a silent default here.
    assertNull("EntryMode.icon() has grown an `else` arm", glyphFallback)

    val fromModes = modes.filter { it.onDashboard }.map { mode ->
        Tile(
            label = mode.label,
            button = requireQuoted(words[mode.name] ?: wordFallback, "${mode.name}'s button word"),
            hasSecondButton = mode.editable,
            glyph = glyphs[mode.name] ?: "",
        )
    }
    return bespokeCards(lead) + fromModes + bespokeCards(trail)
}
