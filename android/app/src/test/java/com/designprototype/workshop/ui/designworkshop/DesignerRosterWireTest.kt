package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.DesignerDirectoryEntryDto
import com.designprototype.workshop.data.DesignerRosterDto
import com.designprototype.workshop.data.PageResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The designer roster must arrive, and a row must be able to name the account behind it.
 *
 * ── THE TWO DEFECTS THIS PINS, BOTH OF THEM SHIPPED ────────────────────────────────────────────────
 *
 * ONE. `GET /designers/roster` returns `page_payload(...)` — the object
 * `{items,total,page,pageSize,pages}` built in `backend/app/services/pagination.py` — and the Retrofit
 * signature asked for `List<DesignerRosterDto>`. kotlinx.serialization cannot read a JSON object into
 * a list, and neither leniency in `ApiClient` bridges it: `isLenient` reads a quoted number as a
 * number, `coerceInputValues` substitutes a default for a null, and an array/object mismatch is
 * neither. So every open of the screen landed in `.onFailure` and drew "Could not load the designer
 * roster." over an empty list, with the screen, its nav entry, its admin-only permission check and
 * all four mutations shipping and correct behind it.
 *
 * TWO. `roster_payload` (`backend/app/services/designers.py:107-121`) sends eleven keys and `userId`
 * is not among them — the `DesignerRoster` table has no user column, because the row is created for
 * an EMAIL before any account exists. The DTO carried a nullable `userId` anyway and the row's
 * "Open designer profile" action was rendered only when it was present, so it was rendered for
 * nobody, and the admin profile editor it is the only route to could not be reached at all. The
 * account is resolved by lower-cased email against `GET /designers/directory`, which is what the
 * web's roster page does with the same endpoint.
 *
 * The JSON in the first test is the server's own shape, key for key, so a change to `page_payload` or
 * to `roster_payload` fails here rather than in a courtyard.
 */
class DesignerRosterWireTest {

    /** `ApiClient.retrofit`'s configuration, copied so the test decodes exactly as the app does. */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    /** One page of the roster, exactly as `page_payload([roster_payload(r) …], …)` serialises it. */
    private val servedPage = """
        {
          "items": [
            {
              "id": "cmsroster0001",
              "email": "a.sharma@example.org",
              "fullName": "A. Sharma",
              "institution": "NID",
              "notes": null,
              "isActive": true,
              "revokedAt": null,
              "firstSeenAt": "2026-03-02T09:14:00+00:00",
              "createdAt": "2026-02-27T11:00:00+00:00",
              "updatedAt": "2026-03-02T09:14:00+00:00",
              "addedById": "cmsadmin0001"
            },
            {
              "id": "cmsroster0002",
              "email": "b.rao@example.org",
              "fullName": null,
              "institution": null,
              "notes": "Standing in for the Bagru cluster",
              "isActive": false,
              "revokedAt": "2026-07-19T05:00:00+00:00",
              "firstSeenAt": null,
              "createdAt": "2026-01-04T08:00:00+00:00",
              "updatedAt": "2026-07-19T05:00:00+00:00",
              "addedById": "cmsadmin0001"
            }
          ],
          "total": 2,
          "page": 1,
          "pageSize": 50,
          "pages": 1
        }
    """.trimIndent()

    // ── The wire ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the served roster decodes as a page`() {
        val page = json.decodeFromString<PageResponse<DesignerRosterDto>>(servedPage)

        assertEquals(2, page.items.size)
        assertEquals(2, page.total)
        assertEquals("a.sharma@example.org", page.items[0].email)
        // The suspended row is the one an admin opens this screen to find, so it must survive the
        // decode with its state and its date intact — that pair is what the card reads together.
        assertFalse(page.items[1].isActive)
        assertNotNull(page.items[1].revokedAt)
        // Null firstSeenAt is "the invitation is outstanding", never an error and never "never".
        assertNull(page.items[1].firstSeenAt)
    }

    @Test
    fun `reading the same payload as a bare list fails — the defect this replaces`() {
        // Pinned with its consequence named, so a future "simplification" back to `List<T>` fails
        // here instead of shipping a screen that can only ever show its error message.
        var thrown: Throwable? = null
        try {
            json.decodeFromString<List<DesignerRosterDto>>(servedPage)
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(
            "an object cannot decode into a list; isLenient and coerceInputValues do not bridge it",
            thrown is SerializationException || thrown is IllegalArgumentException
        )
    }

    @Test
    fun `a server that has not learned a column yet does not take the screen down`() {
        // A field phone updates over the air and may be older or newer than the API it is talking
        // to. Every field on the DTO is defaulted for this reason; the test is here so a
        // non-defaulted field added later fails by name rather than as a blank roster in a village.
        val page = json.decodeFromString<PageResponse<DesignerRosterDto>>(
            """{"items":[{"id":"r1","email":"c.iyer@example.org"}],"total":1,"page":1,"pageSize":50,"pages":1}"""
        )

        assertEquals(1, page.items.size)
        assertTrue("a row with no isActive must read as able to sign in", page.items[0].isActive)
        assertNull(page.items[0].fullName)
    }

    // ── ONE PAGE, NOT A WALK — the five tests that stood here are deleted with the walk ─────────
    //
    // What was here asserted that `designerRoster()` gathered every page up to a 500-row ceiling,
    // that a short read kept the newest rows and lost the oldest, and that the screen said so. All of
    // that was TRUE and all of it described a design that DROPDOWN_DESIGN §4.6 rules out: the walk's
    // ceiling stood against a roster of about 1,300, so the device-side search box over its result
    // answered "no match" about designers who exist, and the end it dropped was the oldest
    // empanelments — the row this screen is opened for.
    //
    // The tests went with the walk rather than after it. A suite that still asserted "the roster is
    // walked past the server's default page" would be a green test describing behaviour nobody could
    // find in the code, which is worse than no test: the next reader trusts it.
    //
    // What replaces them lives in `ui/RosterFilterWireTest.kt` — one page, every filter on the wire,
    // and a source sweep asserting that the walk, the device-side sort and the device-side filter are
    // all gone. The envelope those pages arrive in is `RosterPageDto`, and its own decode tests are
    // there too, including the three-state read of `roleMatchTruncated` that lets this client tell a
    // server that filters from one that silently does not.

    // ── The email -> account join ────────────────────────────────────────────────────────────────

    @Test
    fun `the account behind a row is found whatever case the address was typed in`() {
        // The roster lower-cases on write; `User.email` carries whatever the identity provider sent,
        // and Google returns the address as the person typed it at sign-up. Comparing verbatim drops
        // the profile action from exactly the rows whose owner capitalised their own name, with
        // nothing on screen to suggest why one row has the button and the next does not.
        val accounts = accountsByEmail(
            listOf(
                DesignerDirectoryEntryDto(id = "u-1", email = "A.Sharma@Example.org", name = "A. Sharma"),
                DesignerDirectoryEntryDto(id = "u-2", email = "  d.nair@example.org  ", name = "D. Nair")
            )
        )

        assertEquals("u-1", accounts["a.sharma@example.org"])
        assertEquals("u-2", accounts["d.nair@example.org"])
    }

    @Test
    fun `a row whose invitation is outstanding offers no profile`() {
        // The ordinary state of a fresh empanelment: the email is on the roster and no account
        // exists yet. There is no profile to open, so the action must be absent — never rendered
        // disabled, and never pointed at an id fabricated from the email, which would answer 404 on
        // a control the admin was invited to tap.
        val accounts = accountsByEmail(
            listOf(DesignerDirectoryEntryDto(id = "u-1", email = "a.sharma@example.org"))
        )

        assertNull(accounts["b.rao@example.org"])
    }

    @Test
    fun `a directory row with no id or no address is skipped rather than keyed blank`() {
        val accounts = accountsByEmail(
            listOf(
                DesignerDirectoryEntryDto(id = "", email = "ghost@example.org"),
                DesignerDirectoryEntryDto(id = "u-3", email = ""),
                DesignerDirectoryEntryDto(id = "u-4", email = "e.pillai@example.org")
            )
        )

        assertEquals(mapOf("e.pillai@example.org" to "u-4"), accounts)
    }

    @Test
    fun `the directory cap this screen reports is the one the server enforces`() {
        // `take=500` in `designer_directory`. Mirrored rather than guessed, because the screen says
        // the number out loud when it is reached: a missing profile action on a row whose account
        // exists is the kind of silence that reads as "this designer never signed up".
        assertEquals(500, DESIGNER_DIRECTORY_CAP)
    }

}
