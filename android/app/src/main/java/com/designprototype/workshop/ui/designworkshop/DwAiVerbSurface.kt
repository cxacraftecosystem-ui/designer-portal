package com.designprototype.workshop.ui.designworkshop

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.designprototype.workshop.data.DwAiVerbAllowancePreflight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.designprototype.workshop.data.DW_VERB_CONSENT_LINK_LABEL
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.designprototype.workshop.data.ConnectivityObserver
import com.designprototype.workshop.data.DwAiVerbAllowanceStore
import com.designprototype.workshop.data.DwAiVerbCapView
import com.designprototype.workshop.data.DwDictationRun
import com.designprototype.workshop.data.DwTier3Consent
import com.designprototype.workshop.data.DwVerbConditions
import com.designprototype.workshop.data.DwVerbGate
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.dwAiVerbCapView
import com.designprototype.workshop.data.dwDictationIstDay
import com.designprototype.workshop.data.dwVerbGate

@Immutable
class DwAiVerbSurface(
    val repository: WorkshopRepository?,
    val serverWorkshopId: String?,
    val consent: DwTier3Consent,
    val today: String,
    val cap: DwAiVerbCapView,
) {
    fun gate(context: Context): DwVerbGate = dwVerbGate(
        DwVerbConditions(
            draftRead = true,
            workshopOnServer = !serverWorkshopId.isNullOrBlank(),
            consent = consent,
            online = ConnectivityObserver.isOnline(context),
            capSpent = cap.spent,
            capRefusal = null,
        )
    )
}

internal val LocalDwOpenConsent = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
internal fun DwRecordConsentLink(consent: DwTier3Consent) {
    if (consent != DwTier3Consent.REFUSED) return
    val open = LocalDwOpenConsent.current ?: return
    TextButton(onClick = open, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        Text(DW_VERB_CONSENT_LINK_LABEL, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun dwVerbSurface(runs: Int = 0): DwAiVerbSurface {
    val context = LocalContext.current
    val published = DwDictationRun.publishedWorkshop()
    val preflight by DwAiVerbAllowancePreflight.version.collectAsState()
    LaunchedEffect(Unit) {
        val repository = DwDictationRun.repository() ?: return@LaunchedEffect
        val userId = runCatching { TokenStore(context).getUser()?.id }.getOrNull()
        if (DwAiVerbAllowancePreflight.shouldAsk(userId, dwDictationIstDay())) {
            repository.refreshAiVerbAllowance(context.applicationContext)
            DwAiVerbAllowancePreflight.answered()
        }
    }
    return remember(published.serverId, published.consent, runs, preflight) {
        val userId = runCatching { TokenStore(context).getUser()?.id }.getOrNull()
        val today = dwDictationIstDay()
        DwAiVerbSurface(
            repository = DwDictationRun.repository(),
            serverWorkshopId = published.serverId,
            consent = published.consent,
            today = today,
            cap = dwAiVerbCapView(
                stored = DwAiVerbAllowanceStore.read(context, userId),
                userId = userId,
                today = today,
            ),
        )
    }
}
