package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FieldPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one gate on the viewer roster, walked role by role.
 *
 * ── WHAT THIS PINS, AND WHY READING THE CODE IS NOT ENOUGH ───────────────────────────────────────
 *
 * All three routes behind [WorkshopViewersScreen] are `Depends(require_admin)` in
 * `backend/app/api/routes/design_workshop_viewers.py`, and `require_admin` is `deps.is_admin` —
 * `role_value(user) in {"MASTER_ADMIN", "ADMIN"}`, nothing else. Reproduced against the running API
 * by `backend/tests/test_design_workshop_viewers.py::test_only_an_admin_may_administer_viewers`,
 * which drives GET viewers, GET eligible-viewers and PUT viewers as the workshop's own CREATOR, as a
 * granted colleague, as a researcher and as a professor, and asserts 403 on all twelve.
 *
 * TWO DIFFERENT WRONG PREDICATES ARE WITHIN ONE WORD OF THE RIGHT ONE, and each fails silently:
 *
 *  - `canRunDesignWorkshops` is the predicate every OTHER design-workshop screen uses, so it is the
 *    one a reader reaches for by habit. It admits a DESIGNER, and a designer may not administer
 *    viewers even on the workshop they created — deliberately, because access that is in its owner's
 *    gift freezes the day the owner leaves, which is the handover problem the feature exists to
 *    solve. Using it here would put a live button in front of a 403 and, worse, would state a rule
 *    the server does not have.
 *  - `rank(role) >= RANK_DESIGNER`, the ladder, admits a PROFESSOR as well. The professor case is the
 *    one that survives review: the ladder and the set differ in exactly one cell of a seven-row
 *    table, which is how the identical mistake shipped once already in [FieldPermissions] itself
 *    (see FieldPermissionsTest).
 *
 * Neither mistake is visible on the phone. The screen would draw its form, the picker would fill
 * from a call that 403s, and the admin-shaped sentence explaining who to ask would never be shown to
 * the person who needs it.
 *
 * ── THE DIRECTION OF THE RISK, STATED PLAINLY ────────────────────────────────────────────────────
 *
 * This is a UI mirror of a server rule, and it can only ever be a mirror. A UI guard over an OPEN
 * endpoint has shipped as a security bug in this repository twice; that is not this shape — the
 * endpoint is closed and tested closed above. What a wrong predicate here costs is the other
 * failure: a control offered to somebody the API refuses, or a capability hidden from somebody
 * entitled to it. So the assertion below is EQUALITY with the server's set, not "at least admin".
 */
class WorkshopViewerAdminGateTest {

    private fun user(role: String) =
        UserDto(id = "u-$role", email = "$role@example.org", name = role, role = role)

    /** Every role the server's `ROLE_RANK` knows, so a new tier cannot be added without a decision. */
    private val everyRole = listOf(
        "CROWDSOURCE_VOLUNTEER",
        "FIELD_CONTRIBUTOR",
        "RESEARCHER",
        "DESIGNER",
        "PROFESSOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    @Test
    fun `administering viewers is require_admin exactly, for every role at once`() {
        val allowed = everyRole.filter { mayAdministerViewers(user(it)) }.toSet()

        assertEquals(
            "mayAdministerViewers must equal deps.is_admin — require_admin gates all three routes",
            setOf("ADMIN", "MASTER_ADMIN"),
            allowed
        )
    }

    @Test
    fun `a designer may not hand out access to their own workshop`() {
        // The rule most likely to be argued with, and the one the sibling predicate would break.
        // `services/design_workshop_viewers` argues it at length: an owner's grant has nobody behind
        // it once the owner leaves.
        assertFalse(mayAdministerViewers(user("DESIGNER")))
        assertTrue(
            "the wrong predicate is one word away — this is what the other design-workshop screens use",
            FieldPermissions.canRunDesignWorkshops(user("DESIGNER"))
        )
    }

    @Test
    fun `a professor is refused, which the rank ladder would not have done`() {
        // PROFESSOR ranks 40, above DESIGNER's 35, so every "this tier and above" spelling of this
        // gate admits them. The server does not: `is_admin` is a set membership test.
        assertFalse(mayAdministerViewers(user("PROFESSOR")))
        assertTrue(
            "if this ever ranks below a designer the test above stops proving anything",
            FieldPermissions.rank("PROFESSOR") > FieldPermissions.rank("DESIGNER")
        )
    }

    @Test
    fun `nobody signed in is nobody`() {
        // `cachedUser()` is null before the first sign-in and after a sign-out, and this screen asks
        // it again at the moment of the write. A null must refuse rather than NPE or fall through.
        assertFalse(mayAdministerViewers(null))
    }

    @Test
    fun `an unknown role is refused rather than ranked into the set`() {
        // A role this build has never heard of ranks 0 in `FieldPermissions.RANKS`. That is below a
        // crowdsource volunteer, so it stays out — asserted because a future tier added to the server
        // and not to the phone must fail CLOSED on an access-granting screen.
        assertFalse(mayAdministerViewers(user("SUPER_ADMIN")))
    }
}
