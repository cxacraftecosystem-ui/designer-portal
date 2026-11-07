package com.designprototype.workshop.data

import android.content.Context

internal fun dwHeldDraftAsks(otherAccount: Boolean, kept: Boolean): Boolean = otherAccount && !kept

internal fun dwHeldDraftKey(signedInUserId: String?, workshopId: String): String? =
    signedInUserId?.takeIf { it.isNotBlank() }?.let { "$it$DW_HELD_DRAFT_SEPARATOR$workshopId" }

internal fun dwHeldDraftDiscardTitle(): String = "Discard this draft?"

internal fun dwHeldDraftDiscardBody(title: String): String =
    "“$title” was saved by another account. Discarding removes it from this phone."

private const val DW_HELD_DRAFT_SEPARATOR = "|"

object DwHeldDrafts {
    private const val PREFS = "dw_held_drafts"
    private const val KEPT = "kept"

    fun kept(context: Context, signedInUserId: String?, workshopId: String): Boolean {
        val key = dwHeldDraftKey(signedInUserId, workshopId) ?: return false
        return key in read(context)
    }

    fun keep(context: Context, signedInUserId: String?, workshopId: String) {
        val key = dwHeldDraftKey(signedInUserId, workshopId) ?: return
        write(context, read(context) + key)
    }

    suspend fun discard(context: Context, signedInUserId: String?, workshopId: String): Boolean {
        val draft = WorkshopDraftStore.load(context, workshopId) ?: return false
        if (!dwDraftIsForAnotherAccount(draft.ownerUserId, signedInUserId)) return false
        val removed = WorkshopDraftStore.delete(context, workshopId) != null
        if (removed) forget(context, workshopId)
        return removed
    }

    private fun forget(context: Context, workshopId: String) {
        write(context, read(context).filterNot { it.substringAfter(DW_HELD_DRAFT_SEPARATOR) == workshopId }.toSet())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): Set<String> =
        prefs(context).getStringSet(KEPT, emptySet()).orEmpty().toSet()

    private fun write(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(KEPT, keys).apply()
    }
}
