package com.designprototype.workshop.ui.designworkshop

import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.ui.FieldPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkshopViewerAdminGateTest {

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
        assertFalse(mayAdministerViewers(user("DESIGNER")))
        assertTrue(
            "the wrong predicate is one word away — this is what the other design-workshop screens use",
            FieldPermissions.canRunDesignWorkshops(user("DESIGNER"))
        )
    }

    @Test
    fun `a professor is refused, which the rank ladder would not have done`() {
        assertFalse(mayAdministerViewers(user("PROFESSOR")))
        assertTrue(
            "if this ever ranks below a designer the test above stops proving anything",
            FieldPermissions.rank("PROFESSOR") > FieldPermissions.rank("DESIGNER")
        )
    }

    @Test
    fun `nobody signed in is nobody`() {
        assertFalse(mayAdministerViewers(null))
    }

    @Test
    fun `an unknown role is refused rather than ranked into the set`() {
        assertFalse(mayAdministerViewers(user("SUPER_ADMIN")))
    }
}
