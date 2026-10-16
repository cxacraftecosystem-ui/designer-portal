package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneOffset

/** Manage users says how an account signs in, not only whether a password is set (2026-10-17). */
class AccountSignInCopyTest {

    private val utc = ZoneOffset.UTC

    private fun user(
        provider: String? = "LOCAL",
        hasPassword: Boolean? = true,
        mustChange: Boolean? = false,
        created: String? = "2026-09-10T08:00:00Z",
        first: String? = null,
        last: String? = null,
    ) = UserDto(
        id = "u-1", email = "asha@example.org", name = "Asha", role = "DESIGNER",
        authProvider = provider, hasPassword = hasPassword, mustChangePassword = mustChange,
        createdAt = created, firstLoginAt = first, lastLoginAt = last,
    )

    @Test
    fun the_method_names_the_provider_and_whether_there_is_a_password() {
        assertEquals("Password", accountSignInMethod(user()))
        assertEquals("Google and a password", accountSignInMethod(user(provider = "GOOGLE")))
        assertEquals("Google only", accountSignInMethod(user(provider = "GOOGLE", hasPassword = false)))
        assertEquals("Microsoft only", accountSignInMethod(user(provider = "MICROSOFT", hasPassword = false)))
        assertEquals("No password set", accountSignInMethod(user(hasPassword = false)))
    }

    @Test
    fun the_last_sign_in_is_a_date_when_there_is_one() {
        assertEquals(
            "Last signed in 16 Oct 2026",
            accountLastSignIn(user(first = "2026-09-11T09:00:00Z", last = "2026-10-16T18:30:00.123Z"), utc),
        )
    }

    @Test
    fun before_the_next_sign_in_the_first_one_stands_in() {
        assertEquals("Signed in, first on 11 Sep 2026", accountLastSignIn(user(first = "2026-09-11T09:00:00Z"), utc))
    }

    @Test
    fun never_signed_in_is_said_only_where_it_can_be_known() {
        assertEquals("Never signed in", accountLastSignIn(user(created = "2026-09-10T08:00:00Z"), utc))
        // Created before first sign-ins were recorded: it may well have signed in, so it is not claimed.
        assertEquals("No sign-in recorded", accountLastSignIn(user(created = "2026-08-01T08:00:00Z"), utc))
        assertEquals("No sign-in recorded", accountLastSignIn(user(created = null), utc))
    }

    @Test
    fun the_card_line_puts_what_needs_acting_on_first() {
        assertEquals("Must choose a new password", accountSignInStatus(user(mustChange = true, last = "2026-10-16T00:00:00Z"), utc))
        assertEquals(
            "Google only · Last signed in 16 Oct 2026",
            accountSignInStatus(user(provider = "GOOGLE", hasPassword = false, last = "2026-10-16T00:00:00Z"), utc),
        )
        assertEquals("No password set", accountSignInStatus(user(hasPassword = false), utc))
        assertEquals("Last signed in 16 Oct 2026", accountSignInStatus(user(last = "2026-10-16T00:00:00Z"), utc))
    }
}
