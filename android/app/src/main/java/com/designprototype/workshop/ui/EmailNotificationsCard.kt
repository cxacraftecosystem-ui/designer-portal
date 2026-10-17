package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.EmailSwitch
import com.designprototype.workshop.data.HandInMail
import com.designprototype.workshop.data.NotificationPreferencesDto
import com.designprototype.workshop.data.NotificationPreferencesUpdate
import com.designprototype.workshop.data.WorkshopRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * This account's e-mail choices (2026-10-18) — the web's `EmailNotificationsCard`, same switches, same
 * words. Draws NOTHING until the server says mail is on, and nothing at all offline or on an older
 * server that cannot be read: a switch that cannot be saved is worse than none. The hand-in choice is
 * drawn only for an inspector or the approving authority.
 */
@Composable
fun EmailNotificationsCard(repository: WorkshopRepository) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<NotificationPreferencesDto?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        state = try {
            repository.notificationPreferences()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    val current = state ?: return
    if (!current.available) return

    fun save(optimistic: NotificationPreferencesDto, change: NotificationPreferencesUpdate) {
        val before = state
        state = optimistic
        saving = true
        error = null
        scope.launch {
            try {
                state = repository.saveNotificationPreferences(change)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = before
                error = "Couldn't save. Try again."
            } finally {
                saving = false
            }
        }
    }

    PreferenceCard {
        PreferenceCardHeading(icon = Icons.Filled.Email, title = "E-mail")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EmailSwitch.entries.forEach { item ->
                AppToggle(
                    label = item.label,
                    description = item.description,
                    checked = item.read(current),
                    onCheckedChange = { next -> save(item.applied(current, next), item.update(next)) }
                )
            }
        }
        if (current.reviewsHandIns) {
            SearchableSelectField(
                label = "Reports handed in for review",
                options = HandInMail.OPTIONS.map { (value, label) -> SelectOption(value, label) },
                selectedValue = HandInMail.normalised(current.emailHandInNotices),
                includeNone = false,
                onSelect = { value ->
                    val mode = HandInMail.normalised(value)
                    save(current.copy(emailHandInNotices = mode), NotificationPreferencesUpdate(emailHandInNotices = mode))
                }
            )
            Text(
                "“Once a day” sends one summary at 9 am.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.field.muted
            )
        }
        Text(
            error ?: if (saving) "Saving…" else "Saved automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.field.muted
        )
    }
}
