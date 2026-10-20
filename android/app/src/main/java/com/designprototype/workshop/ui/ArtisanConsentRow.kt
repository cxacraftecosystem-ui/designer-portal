package com.designprototype.workshop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.designprototype.workshop.data.ArtisanConsentCopy
import com.designprototype.workshop.data.ArtisanConsentDto
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "18 Oct 2026" from the server's ISO timestamp, or null when it cannot be read. */
internal fun artisanConsentDate(recordedAt: String?): String? {
    val raw = recordedAt?.takeIf { it.isNotBlank() } ?: return null
    val day = runCatching { OffsetDateTime.parse(raw).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        ?: return null
    return day.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
}

/**
 * "Recordings may be transcribed" on the artisan edit form: the web's `ArtisanConsentToggle`. A
 * setting, saved the moment it is pressed and never part of the form's unsaved changes: Discard on
 * the form cannot take back an artisan's refusal, and Save does not have to carry it.
 */
@Composable
fun ArtisanConsentRow(repository: WorkshopRepository, artisanId: String) {
    val scope = rememberCoroutineScope()
    var consent by remember(artisanId) { mutableStateOf<ArtisanConsentDto?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId) {
        try {
            consent = repository.artisanConsent(artisanId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = ArtisanConsentCopy.LOAD_FAILED
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        consent?.let { current ->
            AppToggle(
                label = ArtisanConsentCopy.LABEL,
                description = ArtisanConsentCopy.DESCRIPTION,
                status = ArtisanConsentCopy.status(current, artisanConsentDate(current.recordedAt)),
                checked = ArtisanConsentCopy.granted(current),
                busy = busy,
                onCheckedChange = { next ->
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            consent = repository.recordArtisanConsent(artisanId, next)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.userMessage(ArtisanConsentCopy.SAVE_FAILED)
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
