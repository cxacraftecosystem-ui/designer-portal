package com.designprototype.workshop.ui.questionnaires

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.CustomQuestionnaireSummaryDto
import com.designprototype.workshop.data.FormBindingDto
import com.designprototype.workshop.data.isTempId
import com.designprototype.workshop.ui.Text

/*
 * THE FORMS LIBRARY'S RULES (UNIFY §4.2, Android "Forms"), kept out of the composables so the JVM
 * tests hold them: which tab a form sits in, what the form's overflow menu offers to whom, and how a
 * form's workshop bindings read. Admin authoring (share with everyone, the default form, type
 * defaults, binding writes) is web-only on the designer portal and has no row here at all.
 */

/** The library's tabs, in order. Deleted is shown only to someone who may restore. */
enum class FormsTab(val label: String) {
    MINE("Mine"),
    SHARED("Shared"),
    WORKSHOP("Workshop"),
    DELETED("Deleted"),
}

/** The tabs this account sees: Deleted only when it may list deleted forms. */
fun formsTabs(mayListDeleted: Boolean): List<FormsTab> =
    FormsTab.entries.filter { it != FormsTab.DELETED || mayListDeleted }

/**
 * Which live tab [row] belongs to, for [meId]. One tab per form, in this order: a form you authored
 * (or one still only on this phone) is Mine; a form shared with everyone is Shared; anything else you
 * can see is there because it is on a workshop you work on.
 */
fun formsTabOf(row: CustomQuestionnaireSummaryDto, meId: String?): FormsTab = when {
    isTempId(row.id) -> FormsTab.MINE
    !meId.isNullOrBlank() && row.ownerId == meId -> FormsTab.MINE
    row.isShared -> FormsTab.SHARED
    else -> FormsTab.WORKSHOP
}

/** The rows of one live tab, in the server's order. [FormsTab.DELETED] is read separately. */
fun formsInTab(rows: List<CustomQuestionnaireSummaryDto>, tab: FormsTab, meId: String?): List<CustomQuestionnaireSummaryDto> =
    if (tab == FormsTab.DELETED) emptyList() else rows.filter { formsTabOf(it, meId) == tab }

/** What a tab says when it has nothing in it. */
fun formsTabEmpty(tab: FormsTab): String = when (tab) {
    FormsTab.MINE -> "No forms of yours yet. Tap New form, or upload a workbook."
    FormsTab.SHARED -> "No shared forms."
    FormsTab.WORKSHOP -> "No forms on your workshops."
    FormsTab.DELETED -> "Nothing deleted."
}

// ── The form's overflow menu ────────────────────────────────────────────────────────────────────

enum class FormMenuItem(val label: String) {
    WORKBOOK_EXPORT("Download workbook"),
    QUESTION_SET("Download questions"),
    PRO_FORMA("Download pro-forma"),
    REUPLOAD("Re-upload workbook"),
    REUSE("Reuse as template"),
    SEND_TO_PHONE("Send to phone"),
    BINDINGS("Workshops"),
    KIND("Kind"),
    DELETE("Delete"),
    RESTORE("Restore"),
}

/**
 * The overflow menu of one form. Edits (re-upload, kind) are its author's or a content admin's
 * ([mayEdit]); delete and restore the same ([mayDelete], owner 22 Oct 2026). A deleted form offers
 * Restore and nothing that would change it. A form only on this phone has nothing server-side yet.
 */
fun formMenuItems(mayEdit: Boolean, mayDelete: Boolean, deleted: Boolean, onPhoneOnly: Boolean): List<FormMenuItem> {
    if (onPhoneOnly) return emptyList()
    if (deleted) return listOfNotNull(FormMenuItem.RESTORE.takeIf { mayDelete })
    return buildList {
        add(FormMenuItem.WORKBOOK_EXPORT)
        add(FormMenuItem.QUESTION_SET)
        add(FormMenuItem.PRO_FORMA)
        if (mayEdit) add(FormMenuItem.REUPLOAD)
        add(FormMenuItem.REUSE)
        add(FormMenuItem.SEND_TO_PHONE)
        add(FormMenuItem.BINDINGS)
        if (mayEdit) add(FormMenuItem.KIND)
        if (mayDelete) add(FormMenuItem.DELETE)
    }
}

/** P16 (UNIFY §4.2). */
object FormDeleteCopy {
    const val TITLE = "Delete this form?"
    const val BODY = "Admins can restore it from Deleted."
    const val DELETED_BANNER = "This form is deleted. Restore it to use it again."
}

// ── Bindings (A9, display only) ──────────────────────────────────────────────────────────────────

data class BindingLine(val designWorkshopId: String, val title: String, val primary: Boolean)

/**
 * A form's design-workshop bindings as lines, primary first. With no bindings from the server the
 * form's own `designWorkshopId` stands as its one (primary) binding, which is what the unification
 * migration turns it into. [titleOf] names a workshop; an unknown one shows its id.
 */
fun bindingLines(
    bindings: List<FormBindingDto>?,
    fallbackDesignWorkshopId: String?,
    titleOf: (String) -> String?,
): List<BindingLine> {
    val design = bindings.orEmpty().filter { !it.designWorkshopId.isNullOrBlank() }
    val lines = if (design.isNotEmpty()) {
        design.map { BindingLine(it.designWorkshopId!!, titleOf(it.designWorkshopId)?.ifBlank { null } ?: it.designWorkshopId, it.isPrimary) }
    } else {
        listOfNotNull(fallbackDesignWorkshopId?.takeIf { it.isNotBlank() }?.let { BindingLine(it, titleOf(it)?.ifBlank { null } ?: it, true) })
    }
    return lines.distinctBy { it.designWorkshopId }.sortedByDescending { it.primary }
}

/** "Primary" after the one a session starts on by default. */
fun bindingLabel(line: BindingLine): String = if (line.primary) "${line.title} · Primary" else line.title

// ── Composables ──────────────────────────────────────────────────────────────────────────────────

/** The library's tabs as pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormsTabRow(tabs: List<FormsTab>, selected: FormsTab, counts: Map<FormsTab, Int>, onSelect: (FormsTab) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        tabs.forEach { tab ->
            val count = counts[tab]
            FilterChip(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                label = { Text(if (count != null && count > 0) "${tab.label} ($count)" else tab.label) },
            )
        }
    }
}

/** One library action as a pill. */
internal data class LibraryAction(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/** The library's four actions: New form · Upload workbook · Download pro-forma · Open bundle, as pills. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LibraryActionPills(actions: List<LibraryAction>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        actions.forEach { a ->
            AssistChip(
                onClick = a.onClick,
                enabled = a.enabled,
                label = { Text(a.label) },
                leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

internal object LibraryIcons {
    val NEW: ImageVector get() = Icons.Filled.Add
    val UPLOAD: ImageVector get() = Icons.Filled.Upload
    val DOWNLOAD: ImageVector get() = Icons.Filled.Download
    val OPEN: ImageVector get() = Icons.Filled.FolderOpen
}
