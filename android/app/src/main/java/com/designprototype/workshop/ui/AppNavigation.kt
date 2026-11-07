package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockPerson
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.IconButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics

import com.designprototype.workshop.data.UserDto

object FieldPermissions {
    const val RANK_CROWDSOURCE_VOLUNTEER = 10
    const val RANK_FIELD_CONTRIBUTOR = 20
    const val RANK_RESEARCHER = 30

    const val RANK_DESIGNER = 35

    const val RANK_INSPECTOR = 37
    const val RANK_PROFESSOR = 40

    const val RANK_ASSISTANT_DIRECTOR = 42

    const val RANK_REGIONAL_DIRECTOR = 45

    const val RANK_MINISTRY_ADMIN = 48

    const val RANK_ADMIN = 50
    const val RANK_MASTER_ADMIN = 60

    private val RANKS = mapOf(
        "CROWDSOURCE_VOLUNTEER" to RANK_CROWDSOURCE_VOLUNTEER,
        "FIELD_CONTRIBUTOR" to RANK_FIELD_CONTRIBUTOR,
        "RESEARCHER" to RANK_RESEARCHER,
        "DESIGNER" to RANK_DESIGNER,
        "INSPECTOR" to RANK_INSPECTOR,
        "PROFESSOR" to RANK_PROFESSOR,
        "ASSISTANT_DIRECTOR" to RANK_ASSISTANT_DIRECTOR,
        "REGIONAL_DIRECTOR" to RANK_REGIONAL_DIRECTOR,
        "MINISTRY_ADMIN" to RANK_MINISTRY_ADMIN,
        "ADMIN" to RANK_ADMIN,
        "MASTER_ADMIN" to RANK_MASTER_ADMIN
    )

    private val LABELS = mapOf(
        "CROWDSOURCE_VOLUNTEER" to "Crowdsource Volunteer",
        "FIELD_CONTRIBUTOR" to "Field Contributor",
        "RESEARCHER" to "Researcher",
        "DESIGNER" to "Designer",
        "INSPECTOR" to "Inspector / Reviewer",
        "PROFESSOR" to "Professor",
        "ASSISTANT_DIRECTOR" to "Assistant Director",
        "REGIONAL_DIRECTOR" to "Regional Director",
        "MINISTRY_ADMIN" to "Ministry Admin",
        "ADMIN" to "Admin",
        "MASTER_ADMIN" to "Master Admin"
    )

    fun rank(role: String?): Int = RANKS[role] ?: 0
    fun label(role: String?): String = LABELS[role] ?: role.orEmpty()

    fun isAdmin(user: UserDto): Boolean = rank(user.role) >= RANK_ADMIN

    fun isMasterAdmin(user: UserDto): Boolean = user.role == "MASTER_ADMIN"

    val CONTENT_ADMIN_ROLES: Set<String> = setOf("MINISTRY_ADMIN", "ADMIN", "MASTER_ADMIN")

    fun isContentAdmin(user: UserDto?): Boolean = user?.role?.let { it in CONTENT_ADMIN_ROLES } == true

    fun canCreateRecords(user: UserDto): Boolean = rank(user.role) >= RANK_RESEARCHER

    fun canManageCrafts(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    fun canManageWorkshops(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    fun canManageUsers(user: UserDto): Boolean = rank(user.role) >= RANK_PROFESSOR

    private val DESIGN_WORKSHOP_ROLES = setOf(
        "DESIGNER",
        "MINISTRY_ADMIN",
        "REGIONAL_DIRECTOR",
        "ASSISTANT_DIRECTOR",
        "ADMIN",
        "MASTER_ADMIN",
    )

    fun canRunDesignWorkshops(user: UserDto): Boolean = user.role in DESIGN_WORKSHOP_ROLES

    fun canViewDesignWorkshopData(user: UserDto): Boolean =
        user.role in DESIGN_WORKSHOP_DATA_VIEW_ROLES

    private val DESIGN_WORKSHOP_DATA_VIEW_ROLES =
        setOf(
            "PROFESSOR",
            "ASSISTANT_DIRECTOR",
            "REGIONAL_DIRECTOR",
            "MINISTRY_ADMIN",
            "ADMIN",
            "MASTER_ADMIN",
        )

    fun canCreateDesignWorkshops(user: UserDto): Boolean =
        com.designprototype.workshop.data.canCreateDesignWorkshops(user.role)

    fun canInspectDesignWorkshops(user: UserDto): Boolean =
        com.designprototype.workshop.data.canInspectDesignWorkshops(user.role)

    fun canManageDesignerRoster(user: UserDto): Boolean = isAdmin(user)

    fun canManageAccessRoster(user: UserDto): Boolean = isAdmin(user)

    fun canReview(user: UserDto): Boolean =
        rank(user.role) >= RANK_FIELD_CONTRIBUTOR || user.canReview

    fun canDownloadDataset(user: UserDto): Boolean =
        rank(user.role) >= RANK_PROFESSOR || user.canDownloadDataset
}

enum class NavGroup(val label: String) {
    RECORD("Record"),
    BROWSE("Browse"),
    ADMIN("Admin"),
    ACCOUNT("Account")
}

enum class NavDestination {
    DASHBOARD,
    WALKTHROUGH,
    RECORD_ARTISAN,
    RECORD_PRODUCT,
    DOCUMENT_PROCESS,
    RECORD_TOOL,
    TAKE_INTERVIEW,
    UPLOAD_MEDIA,
    ADD_CRAFT,
    RECORD_WORKSHOP,
    DESIGN_WORKSHOPS,
    SKETCHES_AND_PROTOTYPES,
    DESIGN_REVIEW,
    DESIGN_WORKSHOP_INSPECTIONS,
    CUSTOM_QUESTIONNAIRES,
    DESIGNER_PROFILE,
    DESIGNER_ROSTER,
    MY_ACTIVITY,
    TASKS,
    BROWSE_RECORDS,
    SCAN_CODE,
    MAP,
    VIEW_DATA,
    CONSOLIDATED_QUESTIONNAIRE,
    SHARE_DATA_ACCESS,
    ASSIGN_TOOLS,
    REVIEW,
    SETTINGS_HUB,
    MANAGE_USERS,
    ACCESS_ROSTER,
    NOTIFICATIONS,
    SETTINGS,
    GIVE_FEEDBACK,
    TERMS
}

data class NavEntry(
    val destination: NavDestination,
    val label: String,
    val icon: ImageVector,
    val group: NavGroup?,
    val can: (UserDto) -> Boolean,
    val gate: String,
    val adminSurface: Boolean = false
)

private val everyone: (UserDto) -> Boolean = { true }

val FIELD_NAV_ITEMS: List<NavEntry> = listOf(
    NavEntry(NavDestination.DASHBOARD, "Dashboard", Icons.Filled.Dashboard, null, everyone, "get_current_user"),
    NavEntry(NavDestination.WALKTHROUGH, "Walkthrough", Icons.Filled.Explore, null, everyone, "none (static page)"),

    NavEntry(NavDestination.RECORD_ARTISAN, "Record artisan", Icons.Filled.Person, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_PRODUCT, "Record product", Icons.Filled.Inventory2, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.DOCUMENT_PROCESS, "Document process", Icons.Filled.AccountTree, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.RECORD_TOOL, "Record tool", Icons.Filled.Build, NavGroup.RECORD, FieldPermissions::canCreateRecords, "require_record_creator"),
    NavEntry(NavDestination.TAKE_INTERVIEW, "Take interview", Icons.Filled.Quiz, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.UPLOAD_MEDIA, "Upload media", Icons.Filled.PermMedia, NavGroup.RECORD, everyone, "get_current_user"),
    NavEntry(NavDestination.ADD_CRAFT, "Add craft", Icons.Filled.Brush, NavGroup.RECORD, FieldPermissions::canManageCrafts, "require_craft_manager"),
    NavEntry(NavDestination.RECORD_WORKSHOP, "Record workshop", Icons.Filled.Groups, NavGroup.RECORD, FieldPermissions::canManageWorkshops, "require_workshop_manager"),
    NavEntry(NavDestination.DESIGN_WORKSHOPS, "Design workshops", Icons.Filled.DesignServices, NavGroup.RECORD, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops"),
    NavEntry(NavDestination.DESIGN_REVIEW, "Design review", Icons.Filled.Star, NavGroup.BROWSE, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops (load_ratable_workshop_or_404 on GET /design-ratings/rounds/{round}; get_current_user + visible_to_clause on the picker's list)"),
    NavEntry(NavDestination.SKETCHES_AND_PROTOTYPES, "Sketches & prototypes", Icons.Filled.Brush, NavGroup.BROWSE, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops (load_workshop_or_404 on the chosen workshop; get_current_user + visible_to_clause on the picker's list)"),
    NavEntry(NavDestination.DESIGN_WORKSHOP_INSPECTIONS, "Workshops to inspect", Icons.Filled.FindInPage, NavGroup.BROWSE, FieldPermissions::canInspectDesignWorkshops, "assert_inspection_surface (INSPECTION_ROLES = {INSPECTOR}; GET /design-workshop-inspections and /{id})"),
    NavEntry(NavDestination.CUSTOM_QUESTIONNAIRES, "Questionnaires", Icons.AutoMirrored.Filled.ListAlt, NavGroup.RECORD, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops"),

    NavEntry(NavDestination.MY_ACTIVITY, "My Activity", Icons.Filled.Timeline, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.TASKS, "Tasks", Icons.AutoMirrored.Filled.Assignment, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.BROWSE_RECORDS, "Browse records", Icons.Filled.Search, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.SCAN_CODE, "Scan a code", Icons.Filled.QrCodeScanner, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.MAP, "Map", Icons.Filled.Map, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.VIEW_DATA, "Data", Icons.Filled.Storage, NavGroup.BROWSE, FieldPermissions::canDownloadDataset, "require_dataset_downloader"),
    NavEntry(NavDestination.CONSOLIDATED_QUESTIONNAIRE, "Consolidated questionnaire", Icons.Filled.Layers, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.SHARE_DATA_ACCESS, "Share data access", Icons.Filled.Share, NavGroup.BROWSE, everyone, "get_current_user"),
    NavEntry(NavDestination.ASSIGN_TOOLS, "Assign tools to artisans", Icons.Filled.Handyman, NavGroup.BROWSE, FieldPermissions::canCreateRecords, "get_current_user + owner/EDIT-grant/admin per artisan"),
    NavEntry(NavDestination.REVIEW, "Review", Icons.Filled.Visibility, NavGroup.BROWSE, FieldPermissions::canReview, "require_reviewer"),

    NavEntry(NavDestination.SETTINGS_HUB, "Settings hub", Icons.Filled.Tune, NavGroup.ADMIN, FieldPermissions::isAdmin, "require_admin", adminSurface = true),
    NavEntry(NavDestination.MANAGE_USERS, "Manage users", Icons.Filled.ManageAccounts, NavGroup.ADMIN, FieldPermissions::canManageUsers, "require_professor", adminSurface = true),
    NavEntry(NavDestination.DESIGNER_ROSTER, "Designer roster", Icons.Filled.Badge, NavGroup.ADMIN, FieldPermissions::canManageDesignerRoster, "can_manage_designer_roster", adminSurface = true),
    NavEntry(NavDestination.ACCESS_ROSTER, "Who may sign in", Icons.Filled.LockPerson, NavGroup.ADMIN, FieldPermissions::canManageAccessRoster, "require_access_manager", adminSurface = true),

    NavEntry(NavDestination.DESIGNER_PROFILE, "My designer profile", Icons.Filled.Badge, NavGroup.ACCOUNT, FieldPermissions::canRunDesignWorkshops, "can_run_design_workshops"),
    NavEntry(NavDestination.NOTIFICATIONS, "Notifications", Icons.Filled.Notifications, NavGroup.ACCOUNT, everyone, "get_current_user (GET /notifications)"),
    NavEntry(NavDestination.SETTINGS, "Settings", Icons.Filled.Settings, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /preferences/me)"),
    NavEntry(NavDestination.GIVE_FEEDBACK, "Give app feedback", Icons.Filled.RateReview, NavGroup.ACCOUNT, everyone, "get_current_user (PUT /feedback/me)"),
    NavEntry(NavDestination.TERMS, "Terms and conditions", Icons.Filled.Gavel, NavGroup.ACCOUNT, everyone, "none (static clauses; GET /usage/consent/notice for clause 10, unauthenticated)")
)

fun isNavItemVisible(item: NavEntry, user: UserDto?, adminMode: Boolean): Boolean {
    if (user == null || !item.can(user)) return false
    if (item.adminSurface && FieldPermissions.isAdmin(user)) return adminMode
    return true
}

fun visibleNavItems(user: UserDto?, adminMode: Boolean): List<NavEntry> =
    FIELD_NAV_ITEMS.filter { isNavItemVisible(it, user, adminMode) }

@Composable
fun AppNavigationDrawerContent(
    user: UserDto,
    adminMode: Boolean,
    onNavigate: (NavDestination) -> Unit,
    onToggleAdminView: () -> Unit,
    onLogout: () -> Unit,
    currentDestination: NavDestination? = null,
    pushingUpdate: Boolean = false,
    onPushUpdate: () -> Unit = {},
    pendingAccessCount: Int = 0,
    awaitingInspectionCount: Int = 0,
    unreadNotifications: Int = 0
) {
    val items = visibleNavItems(user, adminMode)
    val rootItems = items.filter { it.group == null }
    val groups = NavGroup.entries
        .map { group -> group to items.filter { it.group == group } }
        .filter { (_, entries) -> entries.isNotEmpty() }
    val scrollAll = LocalDensity.current.fontScale >= DRAWER_SCROLL_ALL_SCALE
    var confirmPush by remember { mutableStateOf(false) }
    val showFooter = FieldPermissions.isContentAdmin(user)

    val header: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .testTag("nav.header"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WorkshopLogo(modifier = Modifier.size(36.dp), cornerRadius = 10.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                BasicText(
                    "Design Workshop",
                    style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleMedium.fontSize),
                    modifier = Modifier.testTag("nav.appName")
                )
                Text(
                    "${user.name} · ${FieldPermissions.label(user.role)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.field.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (scrollAll) {
                IconButton(onClick = onLogout, modifier = Modifier.testTag("nav.logout")) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Logout", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                OutlinedButton(
                    onClick = onLogout,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.testTag("nav.logout")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Logout", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    }

    val footer: @Composable () -> Unit = {
        if (showFooter) {
            Column(Modifier.fillMaxWidth().testTag("nav.footer")) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                if (FieldPermissions.isContentAdmin(user)) {
                    AppToggle(
                        label = "Admin view",
                        checked = adminMode,
                        onCheckedChange = { onToggleAdminView() },
                        description = "Shows the admin pages and controls. Off only hides them; it never takes access away.",
                        modifier = Modifier
                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                            .padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

    val listItems: @Composable () -> Unit = {
        rootItems.forEach { entry ->
            NavRow(entry, entry.destination == currentDestination, onNavigate, navBadge(entry, pendingAccessCount, awaitingInspectionCount, unreadNotifications))
        }
        groups.forEach { (group, entries) ->
            NavGroupHeading(group.label)
            entries.forEach { entry ->
                NavRow(entry, entry.destination == currentDestination, onNavigate, navBadge(entry, pendingAccessCount, awaitingInspectionCount, unreadNotifications))
            }
        }
        if (FieldPermissions.isMasterAdmin(user)) {
            NavGroupHeading("Admin actions")
            NavigationDrawerItem(
                label = {
                    Text(
                        if (pushingUpdate) "Publishing update…" else "Push update to all",
                        style = MaterialTheme.typography.labelLarge
                    )
                },
                selected = false,
                icon = { Icon(Icons.Filled.SystemUpdate, contentDescription = null) },
                onClick = { if (!pushingUpdate) confirmPush = true },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).testTag("nav.pushUpdate")
            )
        }
        Spacer(Modifier.padding(bottom = 8.dp))
    }

    ModalDrawerSheet(
        modifier = Modifier
            .widthIn(max = appMenuDrawerMaxWidth())
            .semantics { paneTitle = "Menu" },
        drawerShape = APP_MENU_DRAWER_SHAPE
    ) {
        if (scrollAll) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("nav.list")
            ) {
                header()
                listItems()
                footer()
            }
        } else {
            header()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .testTag("nav.list")
            ) {
                listItems()
            }
            footer()
        }
    }

    if (confirmPush) {
        AlertDialog(
            onDismissRequest = { confirmPush = false },
            title = { Text("Push this update to everyone?") },
            text = { Text("Every signed-in phone is offered this version the next time it opens the app.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmPush = false; onPushUpdate() },
                    modifier = Modifier.testTag("nav.pushUpdate.confirm")
                ) { Text("Push update") }
            },
            dismissButton = { TextButton(onClick = { confirmPush = false }) { Text("Cancel") } },
            modifier = Modifier.testTag("nav.pushUpdate.dialog")
        )
    }
}

@Composable
private fun NavGroupHeading(label: String) {
    Text(
        text = label.uppercase(),
        style = FieldTextStyles.FieldLabel,
        color = MaterialTheme.field.muted,
        modifier = Modifier.padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
    )
}

internal fun navBadge(entry: NavEntry, pendingAccessCount: Int, awaitingInspectionCount: Int = 0, unreadNotifications: Int = 0): String? =
    when {
        entry.destination == NavDestination.ACCESS_ROSTER && pendingAccessCount > 0 -> pendingAccessCount.toString()
        entry.destination == NavDestination.DESIGN_WORKSHOP_INSPECTIONS && awaitingInspectionCount > 0 ->
            awaitingInspectionCount.toString()
        entry.destination == NavDestination.NOTIFICATIONS && unreadNotifications > 0 ->
            if (unreadNotifications > 99) "99+" else unreadNotifications.toString()
        else -> null
    }

@Composable
private fun NavRow(
    entry: NavEntry,
    selected: Boolean,
    onNavigate: (NavDestination) -> Unit,
    badge: String? = null
) {
    NavigationDrawerItem(
        label = { Text(entry.label, style = MaterialTheme.typography.labelLarge) },
        selected = selected,
        icon = { Icon(entry.icon, contentDescription = null) },
        badge = badge?.let { count -> { Text(count, style = MaterialTheme.typography.labelLarge) } },
        onClick = { onNavigate(entry.destination) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).testTag("nav.${entry.destination.name.lowercase()}")
    )
}

val LocalContentAdminChrome = staticCompositionLocalOf { false }

val LocalMayDelete = staticCompositionLocalOf { false }

const val DRAWER_SCROLL_ALL_SCALE = 1.3f
