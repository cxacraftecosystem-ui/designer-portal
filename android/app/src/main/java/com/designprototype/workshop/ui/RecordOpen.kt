package com.designprototype.workshop.ui

import com.designprototype.workshop.data.UserDto

/**
 * WHAT A TAP ON A RECORD DOES, AND WHO IS OFFERED "UPDATE" — the owner's ruling of 2026-10-16, the
 * web's `lib/recordView.ts` on the handset.
 *
 * The ruling: a tap anywhere on a record's card opens that record READ-ONLY. Editing is a second,
 * explicit act behind a button labelled "Update". Before it, every record list on this app — the
 * "Update existing …" picker, search, the map, Recent submissions, My Activity — dropped the
 * designer straight into the edit FORM, where a thumb resting on the screen while reading is a
 * change to the record and leaving asks about unsaved work nobody meant to make.
 *
 * Pure, and keyed on the `EntryMode` NAME rather than the enum, because `EntryMode` is file-private
 * to `MainActivity.kt` and this has to be reachable from a unit test. `RecordOpenTest` holds the
 * names to the enum by reading the source.
 *
 * NOTHING HERE IS A NEW PERMISSION. [mayUpdate] restates the server's PATCH gate for each type —
 * `require_craft_manager`, `require_workshop_manager`, and the plain sign-in the four field records
 * and interviews take (where `guard_record_edit` decides field by field). It decides whether the
 * button is DRAWN; the server still decides whether the save lands.
 */
object RecordOpen {
    /** The label of the one control that leads from a record's page to its form. Never "Edit". */
    const val UPDATE_LABEL = "Update"

    /** The accessibility verb a whole-card tap announces ("Open", then the record's name). */
    const val OPEN_LABEL = "Open"

    /** The record kinds that have a read-only page (`Screen.View`). */
    val VIEWABLE: Set<String> = setOf(
        "ARTISAN", "PRODUCT", "PROCESS", "TOOL", "QUESTIONNAIRE", "MEDIA", "CRAFT", "WORKSHOP"
    )

    fun hasView(modeName: String): Boolean = modeName in VIEWABLE

    /**
     * Whether "Update" is drawn on this record kind's page for this account. Signed out, a stored
     * file (which has no form), and anything outside [VIEWABLE] are never offered it.
     */
    fun mayUpdate(modeName: String, user: UserDto?): Boolean {
        if (user == null) return false
        return when (modeName) {
            "CRAFT" -> FieldPermissions.canManageCrafts(user)
            "WORKSHOP" -> FieldPermissions.canManageWorkshops(user)
            "ARTISAN", "PRODUCT", "PROCESS", "TOOL", "QUESTIONNAIRE" -> true
            else -> false
        }
    }

    /** What a whole-card tap announces to TalkBack: the verb, then the record. */
    fun openClickLabel(title: String): String =
        title.trim().takeIf { it.isNotEmpty() }?.let { "$OPEN_LABEL $it" } ?: OPEN_LABEL
}
