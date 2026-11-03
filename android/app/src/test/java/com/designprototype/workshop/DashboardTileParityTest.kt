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
        "ASSISTANT_DIRECTOR",
        "REGIONAL_DIRECTOR",
        "MINISTRY_ADMIN",
        "ADMIN",
        "MASTER_ADMIN",
    )

    @Test
    fun `the parsers found the two arrays they are aimed at`() {
        assertTrue("the web tile array parsed to ${WEB.size} tiles", WEB.size > 15)
        assertTrue("the handset grid parsed to ${ANDROID.size} cards", ANDROID.size > 15)
        assertTrue(WEB.map { it.label }.contains("Miscellaneous Media"))
        assertTrue(ANDROID.map { it.label }.contains("Miscellaneous Media"))
        assertTrue(ANDROID.map { it.label }.contains(SETTINGS))
        assertTrue((WEB + ANDROID).all { it.label.isNotBlank() && it.button.isNotBlank() })
        assertTrue("the web group array parsed to ${WEB_GROUPS.size} groups", WEB_GROUPS.size >= 4)
        assertTrue("DashGroup parsed to ${GROUPS.size} members", GROUPS.size >= 4)
        assertTrue(
            "a parsed group came back with a blank id, title or note",
            (WEB_GROUPS + GROUPS).all { it.id.isNotBlank() && it.title.isNotBlank() && it.note.isNotBlank() }
        )
        assertTrue((WEB + ANDROID).all { it.group.isNotBlank() })
    }

    @Test
    fun `the four mega cards are the web's four, in its order and in its words`() {
        assertEquals(
            "DashGroup and TILE_GROUPS no longer name the same groups in the same order",
            WEB_GROUPS.map { it.id },
            GROUPS.map { it.id }
        )
        val wrong = mutableListOf<String>()
        for ((handset, web) in GROUPS.zip(WEB_GROUPS)) {
            if (handset.title != web.title) {
                wrong += "the ${web.id} mega card is titled \"${handset.title}\" on the handset " +
                    "and \"${web.title}\" on the web"
            }
            if (handset.note != web.note) {
                wrong += "the ${web.id} mega card's note reads \"${handset.note}\" on the handset " +
                    "and \"${web.note}\" on the web"
            }
        }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun `every card is filed in the same mega card on both clients`() {
        val web = WEB.associateBy { it.label }
        val wrong = mutableListOf<String>()
        for (tile in ANDROID) {
            val twin = web[tile.label] ?: continue
            if (twin.group != tile.group) {
                wrong += "\"${tile.label}\" is filed under ${tile.group} on the handset and " +
                    "${twin.group} on the web"
            }
        }
        assertEquals(emptyList<String>(), wrong)

        val known = GROUPS.map { it.id }.toSet()
        assertEquals(
            "a card is filed under a heading neither TILE_GROUPS nor DashGroup carries",
            emptySet<String>(),
            (ANDROID.map { it.group } + WEB.map { it.group }).toSet() - known
        )
    }

    @Test
    fun `a mode is filed in a mega card exactly when it has a card to file`() {
        val wrong = MODES
            .filter { (it.group != "null") != it.onDashboard }
            .map {
                if (it.onDashboard) {
                    "${it.name} has a dashboard card and no mega card to draw it in"
                } else {
                    "${it.name} is a menu row with no card, but names the ${it.group} mega card"
                }
            }
        assertEquals(emptyList<String>(), wrong)
        assertTrue("no mode is filed at all", MODES.any { it.group != "null" })
        assertTrue("no mode is menu-only", MODES.any { it.group == "null" })
    }

    @Test
    fun `the comment stripper knows that Kotlin block comments nest and TypeScript's do not`() {
        val nested = "A/*outer/*inner*/still outer*/B"
        assertEquals("AB", stripComments(nested, nesting = true))
        assertEquals("Astill outer*/B", stripComments(nested, nesting = false))
        assertEquals("xy", stripComments("x/*it's a comment*/y", nesting = true))
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
            if (twin.button != tile.button) {
                wrong += "\"${tile.label}\" says \"${tile.button}\" on the handset and " +
                    "\"${twin.button}\" on the web"
            }
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
        val onWeb = WEB.map { it.label }.toSet()
        assertEquals(emptyList<String>(), ANDROID.map { it.label }.filterNot { it in onWeb })
    }

    @Test
    fun `the shared tiles stand in one order, and Settings is the single exception`() {
        val webShared = WEB.map { it.label }.filterNot { it in WEB_ONLY || it == SETTINGS }
        val handsetShared = ANDROID.map { it.label }.filterNot { it == SETTINGS }
        assertEquals(webShared, handsetShared)

        assertEquals("Settings is no longer the handset's last card", SETTINGS, ANDROID.last().label)
        val webLabels = WEB.map { it.label }
        val at = webLabels.indexOf(SETTINGS)
        assertEquals("Users", webLabels[at - 1])
        assertEquals("Craft", webLabels[at + 1])
    }

    @Test
    fun `the design workshop block leads both grids`() {
        val block = listOf(
            DesignWorkshopCard.LABEL,
            SketchesAndPrototypesCard.LABEL,
            DesignReviewCard.LABEL,
            "Artisan",
        )
        assertEquals(block, ANDROID.take(4).map { it.label })
        assertEquals(block, WEB.take(4).map { it.label })
    }

    @Test
    fun `the two design-workshop cards added on 2026-08-28 read one predicate and one label`() {
        val pairs = listOf(
            Triple(NavDestination.DESIGN_REVIEW, DesignReviewCard.LABEL, DesignReviewCard::visibleTo),
            Triple(
                NavDestination.DESIGNER_PROFILE,
                DesignerProfileCard.LABEL,
                DesignerProfileCard::visibleTo,
            ),
        )
        for ((destination, label, visibleTo) in pairs) {
            val row = FIELD_NAV_ITEMS.first { it.destination == destination }
            assertEquals("$destination's card and menu row must carry one spelling", row.label, label)
            val disagreements = everyRole.filter { role -> row.can(user(role)) != visibleTo(user(role)) }
            assertEquals(
                "$destination's card and menu row must never disagree about who may reach it",
                emptyList<String>(),
                disagreements
            )
            assertEquals(
                "$destination must be offered to exactly deps.DESIGN_WORKSHOP_ROLES",
                setOf(
                "DESIGNER",
                "MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR",
                "ADMIN", "MASTER_ADMIN"
            ),
                everyRole.filter { visibleTo(user(it)) }.toSet()
            )
        }
    }

    @Test
    fun `the sketches card is offered to exactly the roles the server lets run a workshop`() {
        val offered = everyRole.filter { SketchesAndPrototypesCard.visibleTo(user(it)) }.toSet()
        assertEquals(
            "the Sketches & prototypes card must equal deps.DESIGN_WORKSHOP_ROLES exactly",
            setOf(
                "DESIGNER",
                "MINISTRY_ADMIN", "REGIONAL_DIRECTOR", "ASSISTANT_DIRECTOR",
                "ADMIN", "MASTER_ADMIN"
            ),
            offered
        )
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

        assertEquals("Sketches & prototypes", SketchesAndPrototypesCard.LABEL)
        assertEquals(SketchesAndPrototypesCard.LABEL, row.label)
    }

    @Test
    fun `an Open button draws an arrow and a New button draws a plus`() {
        assertEquals("AutoMirrored.Filled.ArrowForward", dashboardPrimaryIcon("Open").name)
        assertEquals("AutoMirrored.Filled.ArrowForward", dashboardPrimaryIcon("Manage").name)
        for (word in listOf("New", "New workshop", "New interview", "Upload")) {
            assertEquals(
                "\"$word\" creates something, so it keeps the plus",
                "Filled.Add",
                dashboardPrimaryIcon(word).name
            )
        }
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
        val shared = ANDROID.groupBy { it.glyph }.filterValues { it.size > 1 }
        assertEquals(
            emptyMap<String, List<String>>(),
            shared.mapValues { (_, tiles) -> tiles.map { it.label } }
        )
        assertTrue(ANDROID.all { it.glyph.isNotBlank() })
    }

    @Test
    fun `the two menu-only modes stay off both grids`() {
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

private val WEB_ONLY = emptyList<String>()

private const val SETTINGS = "Settings"

private val CONSTANTS = mapOf(
    "DesignWorkshopCard.LABEL" to DesignWorkshopCard.LABEL,
    "DesignWorkshopCard.PRIMARY_LABEL" to DesignWorkshopCard.PRIMARY_LABEL,
    "SketchesAndPrototypesCard.LABEL" to SketchesAndPrototypesCard.LABEL,
    "SketchesAndPrototypesCard.PRIMARY_LABEL" to SketchesAndPrototypesCard.PRIMARY_LABEL,
    "DesignReviewCard.LABEL" to DesignReviewCard.LABEL,
    "DesignReviewCard.PRIMARY_LABEL" to DesignReviewCard.PRIMARY_LABEL,
    "DesignerProfileCard.LABEL" to DesignerProfileCard.LABEL,
    "DesignerProfileCard.PRIMARY_LABEL" to DesignerProfileCard.PRIMARY_LABEL,
    "ScanCodeCard.LABEL" to ScanCodeCard.LABEL,
    "ScanCodeCard.PRIMARY_LABEL" to ScanCodeCard.PRIMARY_LABEL,
)

private class Tile(
    val label: String,
    val button: String,
    val hasSecondButton: Boolean,
    val glyph: String,
    val group: String,
)

private class Mode(
    val name: String,
    val label: String,
    val editable: Boolean,
    val onDashboard: Boolean,
    val group: String,
)

private class Group(val id: String, val title: String, val note: String)

private val KOTLIN_SOURCE: String by lazy {
    val source = repoFile(
        "src/main/java/com/designprototype/workshop/MainActivity.kt",
        "app/src/main/java/com/designprototype/workshop/MainActivity.kt",
        "android/app/src/main/java/com/designprototype/workshop/MainActivity.kt",
    ).readText(Charsets.UTF_8).replace("\r\n", "\n")
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
private val GROUPS: List<Group> by lazy { dashGroups(KOTLIN_SOURCE) }
private val WEB_GROUPS: List<Group> by lazy { webGroups(WEB_SOURCE) }
private val ANDROID: List<Tile> by lazy { handsetGrid(KOTLIN_SOURCE, MODES) }
private val WEB: List<Tile> by lazy { webTiles(WEB_SOURCE) }

private const val MISC_GROUP = "misc"

private fun kotlinGroup(expression: String?, what: String): String {
    assertNotNull("$what carries no `group` argument", expression)
    val raw = expression!!.trim()
    if (raw == "null") return MISC_GROUP
    assertTrue(
        "$what files its card under `$raw`, which this file cannot resolve — it reads a plain " +
            "`DashGroup.X` constant, and an expression here is a grouping no test can compare",
        raw.startsWith("DashGroup.")
    )
    return raw.removePrefix("DashGroup.").lowercase()
}

private fun webGroup(raw: String?, known: Set<String>): String {
    val value = raw?.let { unquote(it) }
    return if (value != null && value in known) value else MISC_GROUP
}

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

private fun webTiles(source: String): List<Tile> {
    val declaration = "const tiles: Tile[] = ["
    val at = source.indexOf(declaration)
    assertTrue("the web dashboard no longer declares `$declaration`", at >= 0)
    val body = balanced(source, at + declaration.length - 1, '[')
    return splitTop(body).map { entry ->
        assertTrue(
            "a web tile is not an object literal: ${entry.take(60)}",
            entry.startsWith("{") && entry.endsWith("}")
        )
        val props = properties(entry.substring(1, entry.length - 1), ':')
        Tile(
            label = requireQuoted(props["label"], "a web tile's label"),
            button = props["newLabel"]?.let { requireQuoted(it, "a web tile's newLabel") } ?: "New",
            hasSecondButton = props.containsKey("updateHref"),
            glyph = "",
            group = webGroup(props["group"], WEB_GROUPS.map { it.id }.toSet()),
        )
    }
}

private fun webGroups(source: String): List<Group> {
    val declaration = "const TILE_GROUPS = ["
    val at = source.indexOf(declaration)
    assertTrue("the web dashboard no longer declares `$declaration`", at >= 0)
    val body = balanced(source, at + declaration.length - 1, '[')
    return splitTop(body).map { entry ->
        assertTrue(
            "a web group is not an object literal: ${entry.take(60)}",
            entry.startsWith("{") && entry.endsWith("}")
        )
        val props = properties(entry.substring(1, entry.length - 1), ':')
        Group(
            id = requireQuoted(props["id"]?.removeSuffix("as const")?.trim(), "a web group's id"),
            title = requireQuoted(props["title"], "a web group's title"),
            note = requireQuoted(props["note"], "a web group's note"),
        )
    }
}

private fun dashGroups(source: String): List<Group> {
    val head = "private enum class DashGroup("
    val at = source.indexOf(head)
    assertTrue("MainActivity.kt no longer declares `$head`", at >= 0)
    val constructor = balanced(source, at + head.length - 1, '(')
    val body = balanced(source, at + head.length + constructor.length, '{')
    return splitTop(body).map { entry ->
        val open = entry.indexOf('(')
        assertTrue("a DashGroup member carries no arguments: ${entry.take(40)}", open > 0)
        val args = splitTop(balanced(entry, open, '('))
        Group(
            id = entry.substring(0, open).trim().lowercase(),
            title = requireQuoted(args.getOrNull(0), "a DashGroup title"),
            note = requireQuoted(args.getOrNull(1), "a DashGroup note"),
        )
    }
}

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
        val group = args.firstOrNull { it.substringBefore('=').trim() == "group" }
            ?.substringAfter('=')
            ?.trim()
        assertNotNull("$name declares no `group`, so nothing here knows where its card is drawn", group)
        Mode(
            name = name,
            label = requireQuoted(args.firstOrNull(), "$name's label"),
            editable = args.any { it.replace(" ", "") == "editable=true" },
            onDashboard = args.none { it.replace(" ", "") == "onDashboard=false" },
            group = group!!,
        )
    }
}

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
                group = kotlinGroup(props["group"], "the bespoke card ${props["label"]}"),
            )
        )
        at = fragment.indexOf(construction, at + 1)
    }
    return out
}

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
    assertTrue("the EntryMode loop is not where this parser cut", loop.contains("entry.label"))
    assertFalse("the EntryMode loop leaked past the cut", trail.contains("entry.label"))
    assertTrue(
        "the EntryMode loop no longer files its card with `group = entry.group ?: DashGroup.MISC`",
        loop.contains("group = entry.group ?: DashGroup.MISC")
    )

    val (words, wordFallback) = whenArms(
        source, "private fun EntryMode.createButtonLabel(): String = when (this) {"
    )
    val (glyphs, glyphFallback) = whenArms(
        source, "private fun EntryMode.icon(): ImageVector = when (this) {"
    )
    assertNotNull("createButtonLabel has lost its `else` arm", wordFallback)
    assertNull("EntryMode.icon() has grown an `else` arm", glyphFallback)

    val spliced = splicedCards(loop)
    val onGrid = modes.filter { it.onDashboard }
    assertEquals(
        "a card is spliced after an EntryMode that has no tile on this grid, so it would never be " +
            "drawn — name a mode that carries `onDashboard = true`",
        emptySet<String>(),
        spliced.keys - onGrid.map { it.name }.toSet()
    )

    val fromModes = buildList {
        for (mode in onGrid) {
            add(
                Tile(
                    label = mode.label,
                    button = requireQuoted(words[mode.name] ?: wordFallback, "${mode.name}'s button word"),
                    hasSecondButton = mode.editable,
                    glyph = glyphs[mode.name] ?: "",
                    group = kotlinGroup(mode.group, mode.name),
                )
            )
            spliced[mode.name]?.let { addAll(it) }
        }
    }
    return bespokeCards(lead) + fromModes + bespokeCards(trail)
}

private fun splicedCards(loop: String): Map<String, List<Tile>> {
    val construction = "DashboardTile("
    val guardPattern = Regex("""entry\s*==\s*EntryMode\.([A-Z_]+)""")
    val out = LinkedHashMap<String, MutableList<Tile>>()
    var at = loop.indexOf(construction)
    while (at >= 0) {
        val props = properties(balanced(loop, at + construction.length - 1, '('), '=')
        if (props["label"] != "entry.label") {
            val guard = guardPattern.findAll(loop.substring(0, at)).lastOrNull()
            assertNotNull(
                "a DashboardTile inside `actions.forEach` is not guarded by an `entry == " +
                    "EntryMode.X` test, so it would be added once per mode: ${props["label"]}",
                guard
            )
            out.getOrPut(guard!!.groupValues[1]) { mutableListOf() }.add(
                Tile(
                    label = resolve(props["label"], "label"),
                    button = resolve(props["primaryLabel"], "primaryLabel"),
                    hasSecondButton = props.containsKey("onUpdate"),
                    glyph = props["icon"] ?: "",
                    group = kotlinGroup(props["group"], "the spliced card ${props["label"]}"),
                )
            )
        }
        at = loop.indexOf(construction, at + 1)
    }
    return out
}
