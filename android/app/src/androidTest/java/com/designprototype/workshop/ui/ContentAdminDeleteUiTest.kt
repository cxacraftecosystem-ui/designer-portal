package com.designprototype.workshop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.designprototype.workshop.data.EntryCommentDto
import com.designprototype.workshop.data.UserDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A Ministry Admin sees Delete in admin chrome (REQUIREMENTS_2026-10-20 §5.4), drawn on a device.
 *
 * The comment row is the record screen's real delete control: its Remove is offered on
 * [LocalMayDelete], which `MainActivity` provides as [mayDelete] — so a Ministry Admin sees it with
 * admin view on AND off (owner, 2026-10-21), and a designer never does. The author's Edit (the PATCH the web already had) is held here too.
 */
@RunWith(AndroidJUnit4::class)
class ContentAdminDeleteUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun user(id: String, role: String) = UserDto(id = id, email = "$id@example.org", name = id, role = role)

    private val comment = EntryCommentDto(
        id = "c1",
        recordType = "artisan",
        recordId = "a1",
        authorId = "author",
        body = "Loom count checked twice.",
        createdAt = "2026-10-20T09:00:00Z"
    )

    private fun show(viewer: UserDto, adminView: Boolean, onEdit: () -> Unit = {}, onRemove: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                // As MainActivity provides them: chrome follows admin view, delete does not.
                CompositionLocalProvider(
                    LocalContentAdminChrome provides contentAdminChrome(viewer, adminView),
                    LocalMayDelete provides mayDelete(viewer),
                ) {
                    EntryCommentRow(comment = comment, viewerId = viewer.id, busy = false, onEdit = onEdit, onRemove = onRemove)
                }
            }
        }
    }

    @Test
    fun aMinistryAdminInAdminViewSeesRemoveAndItFires() {
        var removed = 0
        show(user("ma", "MINISTRY_ADMIN"), adminView = true, onRemove = { removed++ })
        compose.onNodeWithText("Remove").assertIsDisplayed().performClick()
        // Asked first: nothing is removed until the confirmation.
        compose.onNodeWithText("Delete this comment?").assertIsDisplayed()
        assertEquals(0, removed)
        compose.onNodeWithText("Delete").performClick()
        assertEquals(1, removed)
        // Not the author: no Edit.
        compose.onNodeWithText("Edit").assertDoesNotExist()
    }

    @Test
    fun aMinistryAdminWithAdminViewOffStillSeesRemove() {
        show(user("ma", "MINISTRY_ADMIN"), adminView = false)
        compose.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun aDesignerNeverSeesRemove() {
        show(user("d1", "DESIGNER"), adminView = true)
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    @Test
    fun theAuthorSeesEditAndNotRemove() {
        var edited = 0
        show(user("author", "DESIGNER"), adminView = false, onEdit = { edited++ })
        compose.onNodeWithText("Edit").assertIsDisplayed().performClick()
        assertEquals(1, edited)
        compose.onNodeWithText("Remove").assertDoesNotExist()
    }

    // ── The record page's "Danger zone" card (owner, 2026-10-21) ─────────────────────────────────

    private fun showDangerZone(viewer: UserDto, adminView: Boolean, delete: () -> Unit = {}, deleted: () -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                // `adminView` is what MainActivity would hold; the card does not read it (2026-10-21).
                if (RecordDangerZone.visible(viewer)) {
                    RecordDangerZoneCard(noun = "artisan", delete = { delete() }, onDeleted = deleted, onError = {})
                }
            }
        }
    }

    @Test
    fun aMinistryAdminSeesTheDangerZoneAndDeletesOnlyAfterConfirming() {
        var calls = 0
        var done = 0
        showDangerZone(user("ma", "MINISTRY_ADMIN"), adminView = true, delete = { calls++ }, deleted = { done++ })
        compose.onNodeWithText("Danger zone").assertIsDisplayed()
        compose.onNodeWithText("Delete this artisan").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete artisan?").assertIsDisplayed()
        assertEquals("nothing is deleted before the confirmation", 0, calls)
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
        assertEquals(1, done)
    }

    @Test
    fun aMinistryAdminWithAdminViewOffStillSeesTheDangerZone() {
        var calls = 0
        showDangerZone(user("ma", "MINISTRY_ADMIN"), adminView = false, delete = { calls++ })
        compose.onNodeWithText("Danger zone").assertIsDisplayed()
        compose.onNodeWithText("Delete this artisan").assertIsDisplayed().performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertEquals(1, calls)
    }

    @Test
    fun aDesignerNeverSeesTheDangerZone() {
        showDangerZone(user("d1", "DESIGNER"), adminView = true)
        compose.onNodeWithText("Danger zone").assertDoesNotExist()
        compose.onNodeWithText("Delete this artisan").assertDoesNotExist()
    }
}
