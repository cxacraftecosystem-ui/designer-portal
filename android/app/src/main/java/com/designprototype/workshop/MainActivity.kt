package com.designprototype.workshop

import com.designprototype.workshop.transcript.data.TranscribeChoices
import com.designprototype.workshop.transcript.ui.TranscribeChoiceItem
import com.designprototype.workshop.transcript.ui.TranscribeChoiceRows
import com.designprototype.workshop.data.workshopDisplayTitle
import com.designprototype.workshop.data.toolRowLabel
import com.designprototype.workshop.data.plural
import com.designprototype.workshop.data.interviewAnswersToSend
import com.designprototype.workshop.ui.ActionRow
import com.designprototype.workshop.ui.destructiveAction
import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import com.designprototype.workshop.ui.FieldLinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Timeline
import com.designprototype.workshop.ui.CachedCopyNote
import com.designprototype.workshop.ui.FormDraftHandle
import com.designprototype.workshop.ui.FormDraftRestoredNote
import com.designprototype.workshop.ui.draftDate
import com.designprototype.workshop.ui.draftList
import com.designprototype.workshop.ui.parseDraftList
import com.designprototype.workshop.ui.rememberFormDraft
import com.designprototype.workshop.ui.FieldDateField
import com.designprototype.workshop.ui.parseFieldDate
import com.designprototype.workshop.ui.FieldIslandNav
import com.designprototype.workshop.ui.accountLastSignIn
import com.designprototype.workshop.ui.accountSignInMethod
import com.designprototype.workshop.ui.accountSignInStatus
import com.designprototype.workshop.ui.FieldAccent
import com.designprototype.workshop.ui.FieldPermissions
import com.designprototype.workshop.ui.LocalAppPreferences
import com.designprototype.workshop.ui.PRODUCT_MEASURE_DIMENSIONS
import com.designprototype.workshop.ui.RecordMeasureField
import com.designprototype.workshop.ui.TOOL_MEASURE_DIMENSIONS
import com.designprototype.workshop.ui.NavGroup
import com.designprototype.workshop.ui.visibleNavItems
import com.designprototype.workshop.ui.IslandEntry
import com.designprototype.workshop.ui.IslandGroup
import com.designprototype.workshop.ui.IslandScaffold
import com.designprototype.workshop.ui.APP_SEARCH_NAME
import com.designprototype.workshop.ui.AppMenuFocusEffect
import com.designprototype.workshop.ui.AppSearchHeader
import com.designprototype.workshop.ui.QuickReturnHeader
import com.designprototype.workshop.ui.rememberQuickReturnHeader
import com.designprototype.workshop.ui.rememberIslandImeVisible
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import com.designprototype.workshop.ui.LocalContentAdminChrome
import com.designprototype.workshop.ui.LocalMayDelete
import com.designprototype.workshop.ui.EntryCommentRow
import com.designprototype.workshop.ui.contentAdminChrome
import com.designprototype.workshop.ui.mayDelete
import com.designprototype.workshop.ui.readAdminView
import com.designprototype.workshop.ui.restoredAdminView
import com.designprototype.workshop.ui.writeAdminView
import com.designprototype.workshop.ui.bannerAlreadySays
import com.designprototype.workshop.ui.bannerDrawn
import com.designprototype.workshop.ui.AppMessageTone
import com.designprototype.workshop.data.ArtisanCardCode
import com.designprototype.workshop.data.PendingCardOutcome
import com.designprototype.workshop.data.resolvePendingCards
import com.designprototype.workshop.data.serverDidNotAnswer
import com.designprototype.workshop.data.sessionExpired
import com.designprototype.workshop.data.shouldReprobeServer
import com.designprototype.workshop.data.ServerReachability
import com.designprototype.workshop.data.connectionNotice
import com.designprototype.workshop.ui.BANNER_STACK_GAP
import com.designprototype.workshop.ui.LocalBannerClearance
import com.designprototype.workshop.ui.LocalIslandClearance
import com.designprototype.workshop.ui.OfflineNotice
import com.designprototype.workshop.ui.OutboxDeviceBannerCard
import com.designprototype.workshop.ui.attachedUploadsStatus
import com.designprototype.workshop.ui.onTileBorder
import com.designprototype.workshop.ui.onTileButtonColors
import com.designprototype.workshop.ui.bannerClearance
import com.designprototype.workshop.ui.pageBottomReserve
import com.designprototype.workshop.ui.AppSearchItem
import com.designprototype.workshop.ui.AppSearchPanel
import com.designprototype.workshop.ui.AppSearchSettingTarget
import com.designprototype.workshop.ui.appMenuBleed
import com.designprototype.workshop.ui.appMenuHidesPage
import com.designprototype.workshop.ui.appMenuShowing
import com.designprototype.workshop.ui.appSearchCachedItems
import com.designprototype.workshop.ui.appSearchRecordingItems
import com.designprototype.workshop.ui.appSearchRemoteItems
import com.designprototype.workshop.ui.RemoteRecordScreen
import com.designprototype.workshop.ui.appSearchScreenItems
import com.designprototype.workshop.ui.appSearchSettingItems
import com.designprototype.workshop.ui.closeAppMenu
import com.designprototype.workshop.ui.openAppMenu
import com.designprototype.workshop.ui.WorkshopLogo
import com.designprototype.workshop.ui.requiredMarked
import com.designprototype.workshop.ui.Text
import com.designprototype.workshop.ui.RecordDangerZone
import com.designprototype.workshop.ui.RecordDangerZoneCard
import com.designprototype.workshop.ui.OfflineOutboxTray
import com.designprototype.workshop.ui.AppToggle
import com.designprototype.workshop.ui.CalendarField
import com.designprototype.workshop.ui.MediaActionButton
import com.designprototype.workshop.ui.MediaDownloadButton
import com.designprototype.workshop.ui.MediaRetryButton
import com.designprototype.workshop.ui.MediaDownloadNotice
import com.designprototype.workshop.ui.rememberMediaDownloader
import com.designprototype.workshop.data.mediaDisplayNameOf
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.LocalContentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.encodeToString
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import com.designprototype.workshop.ui.PasswordRevealIcon
import com.designprototype.workshop.ui.passwordTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.designprototype.workshop.data.ApiClient
import com.designprototype.workshop.data.OfflineQueueResult
import com.designprototype.workshop.data.ReadCache
import com.designprototype.workshop.data.CompletenessDto
import com.designprototype.workshop.data.CompletenessFilter
import com.designprototype.workshop.data.CompletenessKind
import com.designprototype.workshop.data.RecordCompletenessStore
import com.designprototype.workshop.data.completenessSentence
import com.designprototype.workshop.data.evaluateCompleteness
import com.designprototype.workshop.data.liveCompletenessCounts
import com.designprototype.workshop.ui.CompletenessAnchor
import com.designprototype.workshop.ui.CompletenessFilterChips
import com.designprototype.workshop.ui.CompletenessFilterMemory
import com.designprototype.workshop.ui.CompletenessMeter
import com.designprototype.workshop.ui.CompletenessRing
import com.designprototype.workshop.ui.LiveCompletenessLine
import com.designprototype.workshop.ui.ProvideCompletenessFocus
import com.designprototype.workshop.ui.rememberCompletenessRules
import com.designprototype.workshop.ui.rememberImageCount
import com.designprototype.workshop.data.readCacheNotice
import com.designprototype.workshop.data.unfiledLinkReason
import com.designprototype.workshop.data.UNFILED_BY_CHOICE
import com.designprototype.workshop.data.workshopUnfiledReasons
import com.designprototype.workshop.data.DwReferenceStore
import com.designprototype.workshop.data.DwReferenceList
import com.designprototype.workshop.data.DwReferenceOption
import com.designprototype.workshop.data.interviewArtisanScope
import com.designprototype.workshop.data.loadAllottedFieldWorkshops
import com.designprototype.workshop.data.OutboxCounts
import com.designprototype.workshop.data.offlineSavedMessage
import com.designprototype.workshop.data.outboxDeviceBanner
import com.designprototype.workshop.data.ArtisanIdentity
import com.designprototype.workshop.data.ARTISAN_FORM_CONTRACT
import com.designprototype.workshop.data.ArtisanCreateRequest
import com.designprototype.workshop.data.DuplicateCandidateDto
import com.designprototype.workshop.data.DuplicateCheckRequest
import com.designprototype.workshop.data.PossibleDuplicates
import com.designprototype.workshop.data.peekErrorBody
import com.designprototype.workshop.ui.PossibleDuplicateSheet
import com.designprototype.workshop.data.EXPERIENCE_MONTH_OPTIONS
import com.designprototype.workshop.data.EXPERIENCE_NOT_STATED
import com.designprototype.workshop.data.experienceMonthsLabel
import com.designprototype.workshop.data.missingArtisanDatesSentence
import com.designprototype.workshop.data.CarryContext
import com.designprototype.workshop.data.CarryContextStore
import com.designprototype.workshop.data.CarryNode
import com.designprototype.workshop.data.CompletionCellDto
import com.designprototype.workshop.data.CompletionMatrixDto
import com.designprototype.workshop.data.CraftCatalogueCache
import com.designprototype.workshop.data.CraftCreateRequest
import com.designprototype.workshop.data.catalogueCraftDto
import com.designprototype.workshop.ui.CraftPickerField
import com.designprototype.workshop.data.DataAccessGrantDto
import com.designprototype.workshop.data.DataAccessScopeItemDto
import com.designprototype.workshop.data.DataAccessTierInfo
import com.designprototype.workshop.data.DwMeasurementMarkers
import com.designprototype.workshop.data.DwMeasurementReading
import com.designprototype.workshop.data.DwStageFocus
import com.designprototype.workshop.data.dwGeometryMarker
import com.designprototype.workshop.data.dwVisionMarker
import com.designprototype.workshop.data.DwTextFormats
import com.designprototype.workshop.data.EntryCommentDto
import com.designprototype.workshop.data.MyGrantsDto
import com.designprototype.workshop.data.RecordRevisionDto
import com.designprototype.workshop.data.DashboardRecentSubmissionDto
import com.designprototype.workshop.data.DashboardStats
import com.designprototype.workshop.data.DashboardStatsMine
import com.designprototype.workshop.data.WeeklyDigestSwitch
import com.designprototype.workshop.data.WorkshopRepository
import com.designprototype.workshop.data.GoogleAuthClient
import com.designprototype.workshop.data.OidcAuthClient
import com.designprototype.workshop.data.OidcLoginRequest
import com.designprototype.workshop.data.OidcProvider
import com.designprototype.workshop.data.OidcProviderId
import com.designprototype.workshop.data.oidcCallbackErrorMessage
import com.designprototype.workshop.ui.OidcProviderMark
import com.designprototype.workshop.data.LocationRequest
import com.designprototype.workshop.data.ProductCreateRequest
import com.designprototype.workshop.data.QuestionnaireInterviewCreateRequest
import com.designprototype.workshop.data.QuestionnaireInterviewDetailDto
import com.designprototype.workshop.data.QuestionnaireInterviewUpdateRequest
import com.designprototype.workshop.data.isQuestionnaireBundleDelivery
import com.designprototype.workshop.data.QuestionnaireQuestionCreateRequest
import com.designprototype.workshop.data.QuestionnaireQuestionDto
import com.designprototype.workshop.data.QuestionnaireQuestionUpdateRequest
import com.designprototype.workshop.data.QuestionnaireResponseRequest
import com.designprototype.workshop.data.QuestionnaireSectionCreateRequest
import com.designprototype.workshop.data.QuestionnaireSectionDto
import com.designprototype.workshop.data.questionnaireSaveRefusal
import com.designprototype.workshop.data.QuestionnaireSectionUpdateRequest
import com.designprototype.workshop.data.TokenStore
import com.designprototype.workshop.data.LocalAlerts
import com.designprototype.workshop.data.NotificationInboxRules
import com.designprototype.workshop.data.NotificationState
import com.designprototype.workshop.data.notificationWorkshopId
import com.designprototype.workshop.ui.NotificationsScreen
import com.designprototype.workshop.data.apiHostNeedsLocalNetwork
import com.designprototype.workshop.data.ToolCreateRequest
import com.designprototype.workshop.data.UserDto
import com.designprototype.workshop.data.WorkshopCreateRequest
import com.designprototype.workshop.data.AccessRefusal
import com.designprototype.workshop.data.accessRefusal
import com.designprototype.workshop.data.IssuedPasswordLinkDto
import com.designprototype.workshop.data.SignInHint
import com.designprototype.workshop.data.signInHint
import com.designprototype.workshop.data.apiErrorMessage
import com.designprototype.workshop.data.recordLoadFailure
import com.designprototype.workshop.data.userMessage
import com.designprototype.workshop.ui.AppMessageBanner
import com.designprototype.workshop.ui.AppMessageState
import com.designprototype.workshop.ui.LocalAppNotice
import com.designprototype.workshop.ui.rememberPermissionGate
import com.designprototype.workshop.ui.holdsLocation
import com.designprototype.workshop.ui.LOCATION_PERMISSIONS
import com.designprototype.workshop.ui.CAMERA_PERMISSION
import com.designprototype.workshop.ui.CAMERA_REFUSED_SENTENCE
import com.designprototype.workshop.ui.MICROPHONE_PERMISSION
import com.designprototype.workshop.ui.MICROPHONE_REFUSED_SENTENCE
import com.designprototype.workshop.ui.LoadFailedCard
import com.designprototype.workshop.data.DW_DICTATION_MAX_BYTES
import com.designprototype.workshop.data.dwDictationServerAnswerSentence
import com.designprototype.workshop.data.signInErrorMessage
import com.designprototype.workshop.data.PasswordChangeSignal
import com.designprototype.workshop.data.SessionEndedSignal
import com.designprototype.workshop.data.SessionVerdict
import com.designprototype.workshop.data.sessionVerdict
import com.designprototype.workshop.data.occurrenceDate
import com.designprototype.workshop.ui.AccessRosterScreen
import com.designprototype.workshop.ui.accessRefusalChrome
import com.designprototype.workshop.ui.signInHintHeading
import com.designprototype.workshop.ui.DwInductionFlusher
import com.designprototype.workshop.ui.IssuedPasswordLinkPanel
import com.designprototype.workshop.ui.PasswordGateScreen
import com.designprototype.workshop.ui.SESSION_ENDED_SENTENCE
import com.designprototype.workshop.ui.SetPasswordLinkScreen
import com.designprototype.workshop.ui.mustChangePasswordBlocks
import com.designprototype.workshop.ui.passwordLinkOffered
import com.designprototype.workshop.ui.isSetPasswordLinkAddress
import com.designprototype.workshop.ui.WorkshopAccessQueueFailure
import com.designprototype.workshop.ui.WorkshopAccessQueueView
import com.designprototype.workshop.ui.workshopAccessQueueFailure
import com.designprototype.workshop.ui.workshopAccessQueueNotice
import com.designprototype.workshop.ui.ApiKeysScreen
import com.designprototype.workshop.ui.MyAiKeysScreen
import com.designprototype.workshop.ui.UsageConsentDoor
import com.designprototype.workshop.ui.UsageConsentGateScreen
import com.designprototype.workshop.ui.UsageDoorState
import com.designprototype.workshop.ui.UsageRecordingScreen
import com.designprototype.workshop.ui.TwoStepChallengeForm
import com.designprototype.workshop.ui.TwoStepVerificationScreen
import com.designprototype.workshop.ui.AccountPasswordScreen
import com.designprototype.workshop.ui.ForgotPasswordScreen
import com.designprototype.workshop.ui.HelpThemBackInActions
import com.designprototype.workshop.ui.AddPersonCard
import com.designprototype.workshop.data.SignInOutcome
import com.designprototype.workshop.ui.aadhaar.AadhaarSelfScreen
import com.designprototype.workshop.ui.aadhaar.AadhaarUserBadge
import com.designprototype.workshop.ui.aadhaar.AadhaarVerifyCard
import com.designprototype.workshop.data.AadhaarFeatures
import com.designprototype.workshop.data.AadhaarSubject
import com.designprototype.workshop.ui.UsageScreen
import com.designprototype.workshop.ui.rememberUsageDoorState
import com.designprototype.workshop.ui.usageAnswerAtTheDoor
import com.designprototype.workshop.ui.usageConsentBlocks
import com.designprototype.workshop.ui.TermsMenuDialog
import com.designprototype.workshop.ui.AppPreferences
import com.designprototype.workshop.ui.AppPreferencesStore
import com.designprototype.workshop.ui.AppNavigationDrawerContent
import com.designprototype.workshop.ui.AppearanceScreen
import com.designprototype.workshop.ui.SpeechAndAiScreen
import com.designprototype.workshop.ui.Body
import com.designprototype.workshop.ui.StatusError
import com.designprototype.workshop.ui.StatusSuccess
import com.designprototype.workshop.ui.StatusWarning
import com.designprototype.workshop.ui.StatusEmpty
import com.designprototype.workshop.ui.OnStatusError
import com.designprototype.workshop.ui.OnStatusSuccess
import com.designprototype.workshop.ui.OnStatusWarning
import com.designprototype.workshop.ui.NavDestination
import com.designprototype.workshop.ui.ArtisanPhoneField
import com.designprototype.workshop.ui.artisanPhoneValidationError
import com.designprototype.workshop.ui.Canvas
import com.designprototype.workshop.ui.CarryPrefillBanner
import com.designprototype.workshop.ui.CarryPrefillDefaults
import com.designprototype.workshop.ui.CarryPrefillState
import com.designprototype.workshop.ui.CarryScope
import com.designprototype.workshop.ui.CarryScopeState
import com.designprototype.workshop.ui.carryScope
import com.designprototype.workshop.ui.rememberCarryPrefill
import com.designprototype.workshop.ui.Coral
import com.designprototype.workshop.ui.DataBrowserScreen
import com.designprototype.workshop.ui.DesignWorkshopField
import com.designprototype.workshop.ui.DesignWorkshopPickerState
import com.designprototype.workshop.ui.rememberDesignWorkshopPicker
import com.designprototype.workshop.ui.WorkshopTypePickerState
import com.designprototype.workshop.ui.rememberWorkshopTypePicker
import com.designprototype.workshop.ui.WorkshopTypeField
import com.designprototype.workshop.ui.WorkshopListState
import com.designprototype.workshop.ui.WorkshopListKind
import com.designprototype.workshop.ui.workshopListNotice
import com.designprototype.workshop.ui.listIsAnswerable
import com.designprototype.workshop.ui.fieldWorkshopOptions
import com.designprototype.workshop.ui.unscopedEmptyLine
import com.designprototype.workshop.ui.loadingListLine
import com.designprototype.workshop.ui.couldNotListLine
import com.designprototype.workshop.ui.offlineListLine
import com.designprototype.workshop.ui.atWorkshopEmptyLine
import com.designprototype.workshop.ui.RegisterLoad
import com.designprototype.workshop.ui.RegisterSource
import com.designprototype.workshop.ui.registerListNotice
import com.designprototype.workshop.ui.BUNDLED_LIST_HAS_NO_SENTENCE
import com.designprototype.workshop.ui.NO_FIELD_WORKSHOP
import com.designprototype.workshop.ui.NO_DESIGN_WORKSHOP
import com.designprototype.workshop.ui.workshopCapLine
import com.designprototype.workshop.ui.RecordCodeLookupPanel
import com.designprototype.workshop.ui.ArtisanConsentRow
import com.designprototype.workshop.ui.DesignerScanControls
import com.designprototype.workshop.ui.TopBarScanSheet
import com.designprototype.workshop.ui.designerScanServices
import com.designprototype.workshop.ui.runDesignerScanAction
import com.designprototype.workshop.ui.designerScanOutcome
import com.designprototype.workshop.ui.SCAN_ADD_ARTISAN_NOT_ALLOWED
import com.designprototype.workshop.data.ScanCopy
import com.designprototype.workshop.data.ScanTarget
import com.designprototype.workshop.data.dispatchScan
import com.designprototype.workshop.ui.RecordCodeSection
import com.designprototype.workshop.ui.RecordOpen
import com.designprototype.workshop.ui.OpenableCard
import com.designprototype.workshop.ui.openable
import com.designprototype.workshop.ui.RecordEditHistorySection
import com.designprototype.workshop.ui.RecordProseField
import com.designprototype.workshop.ui.NumberedListInput
import com.designprototype.workshop.ui.joinNumbered
import com.designprototype.workshop.ui.splitNumbered
import com.designprototype.workshop.data.DwWorkshopRecordType
import com.designprototype.workshop.ui.designworkshop.DwIdentityCardControl
import com.designprototype.workshop.ui.designworkshop.DwIdentityKind
import com.designprototype.workshop.ui.designworkshop.DwInlineRecordHost
import com.designprototype.workshop.ui.designworkshop.DwInlineSeed
import com.designprototype.workshop.ui.designworkshop.DwInlineRecordOutcome
import com.designprototype.workshop.ui.designworkshop.DwProvenanceScreen
import com.designprototype.workshop.ui.designworkshop.DwAiLayersScreen
import com.designprototype.workshop.ui.designworkshop.InspectionAwaitingSection
import com.designprototype.workshop.ui.designworkshop.InspectionDetailScreen
import com.designprototype.workshop.ui.designworkshop.InspectionListScreen
import com.designprototype.workshop.ui.designworkshop.DwReportHistoryScreen
import com.designprototype.workshop.ui.designworkshop.DesignerProfileScreen
import com.designprototype.workshop.ui.designworkshop.DesignerRosterScreen
import com.designprototype.workshop.ui.designworkshop.PhotoIntakeScreen
import com.designprototype.workshop.ui.designworkshop.ReportScreen
import com.designprototype.workshop.ui.designworkshop.DesignReviewScreen
import com.designprototype.workshop.ui.designworkshop.SketchesAndPrototypesScreen
import com.designprototype.workshop.ui.designworkshop.StageIndexScreen
import com.designprototype.workshop.ui.designworkshop.StageScreen
import com.designprototype.workshop.ui.designworkshop.WorkshopCodesScreen
import com.designprototype.workshop.ui.designworkshop.WorkshopListScreen
import com.designprototype.workshop.ui.designworkshop.WorkshopInspectorsScreen
import com.designprototype.workshop.ui.designworkshop.WorkshopViewersScreen
import com.designprototype.workshop.questionnaire.portal.DesignerArtisanQuestionnaireRecords
import com.designprototype.workshop.questionnaire.portal.DesignerQuestionnaireHome
import com.designprototype.workshop.questionnaire.portal.questionnaireSessionsOn
import com.designprototype.workshop.questionnaire.portal.questionnaireViewportHeight
import com.designprototype.workshop.questionnaire.ui.QuestionnaireTab
import com.designprototype.workshop.questionnaire.ui.QuestionnaireV2Nav
import com.designprototype.workshop.questionnaire.ui.LocalScreenMemory
import com.designprototype.workshop.questionnaire.ui.ScreenMemories
import com.designprototype.workshop.questionnaire.ui.rememberKept
import com.designprototype.workshop.ui.questionnaires.QuestionnaireAnswerBox
import com.designprototype.workshop.ui.questionnaires.QuestionnaireAnswerScreen
import com.designprototype.workshop.ui.questionnaires.questionnaireAnswerPlain
import com.designprototype.workshop.ui.questionnaires.QUESTIONNAIRE_CLIP_MAX_MILLIS
import com.designprototype.workshop.ui.questionnaires.QuestionnaireQuickTranscript
import com.designprototype.workshop.ui.questionnaires.QuestionnaireTranscriptActions
import com.designprototype.workshop.ui.questionnaires.TRANSCRIPT_DOCUMENT_MIME
import com.designprototype.workshop.ui.questionnaires.QuestionnaireTranscriptOutcome
import com.designprototype.workshop.ui.questionnaires.QUESTIONNAIRE_RECORDED_ANSWER_HINT
import com.designprototype.workshop.ui.questionnaires.QuestionnaireAnswerDisplay
import com.designprototype.workshop.ui.questionnaires.QuestionnaireMergeConflictReport
import com.designprototype.workshop.ui.questionnaires.QuestionnaireMergeOffer
import com.designprototype.workshop.ui.questionnaires.questionnaireAcceptOffer
import com.designprototype.workshop.ui.questionnaires.questionnaireAnswerDisplay
import com.designprototype.workshop.ui.questionnaires.questionnaireCaptionBelongsToSection
import com.designprototype.workshop.ui.questionnaires.questionnaireMergeConflictReport
import com.designprototype.workshop.ui.questionnaires.questionnaireMergeOffer
import com.designprototype.workshop.ui.questionnaires.questionnaireMergedSentence
import com.designprototype.workshop.ui.questionnaires.questionnaireSectionsToReveal
import com.designprototype.workshop.ui.questionnaires.questionnaireClipCapLine
import com.designprototype.workshop.ui.questionnaires.questionnaireClipTooLongLine
import com.designprototype.workshop.ui.questionnaires.questionnaireClipTooLongToDictate
import com.designprototype.workshop.ui.questionnaires.questionnaireNoWorkshopLine
import com.designprototype.workshop.ui.questionnaires.questionnaireSectionClipKey
import com.designprototype.workshop.ui.questionnaires.questionnaireTranscriptOutcome
import com.designprototype.workshop.ui.questionnaires.QuestionnaireDetailScreen
import com.designprototype.workshop.ui.questionnaires.QuestionnaireListScreen
import com.designprototype.workshop.ui.DesignWorkshopTheme
import com.designprototype.workshop.ui.MapScreen
import com.designprototype.workshop.ui.WorkshopScopeSelect
import com.designprototype.workshop.ui.rememberWorkshopScope
import com.designprototype.workshop.ui.ProvideAppPreferences
import com.designprototype.workshop.ui.SystemBarsInsetsRoot
import com.designprototype.workshop.ui.SearchRecordTypes
import com.designprototype.workshop.ui.SearchMemory
import com.designprototype.workshop.ui.SearchScreen
import com.designprototype.workshop.ui.SearchableMultiSelectField
import com.designprototype.workshop.ui.SearchableSelectField
import com.designprototype.workshop.ui.SelectOption
import com.designprototype.workshop.ui.asSelectOptions
import com.designprototype.workshop.ui.cmTextFromInches
import com.designprototype.workshop.ui.craftsChangeClearsArtisans
import com.designprototype.workshop.ui.englishNameMirrorArmed
import com.designprototype.workshop.ui.inchesTextFromCm
import com.designprototype.workshop.ui.initialLinkIds
import com.designprototype.workshop.ui.mergeSelection
import com.designprototype.workshop.ui.offPageLinkRow
import com.designprototype.workshop.ui.propagateDimension
import com.designprototype.workshop.ui.toolArtisanOptions
import com.designprototype.workshop.ui.GrantAccessFields
import com.designprototype.workshop.ui.RequestAccessFields
import com.designprototype.workshop.ui.TaskAdminScreen
import com.designprototype.workshop.ui.field
import com.designprototype.workshop.ui.Muted
import com.designprototype.workshop.ui.resolveDarkTheme
import com.designprototype.workshop.ui.reviewQueueCutNotice
import com.designprototype.workshop.ui.REVIEW_QUEUE_PAGE_SIZE
import com.designprototype.workshop.ui.syncAppPreferences
import com.designprototype.workshop.ui.SurfaceCard
import com.designprototype.workshop.ui.WalkthroughDialog
import com.designprototype.workshop.ui.markWalkthroughSeen
import com.designprototype.workshop.ui.walkthroughSeen
import kotlinx.coroutines.launch
import coil3.compose.AsyncImage
import retrofit2.HttpException
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DesignServices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.designprototype.workshop.data.ArtisanDto
import com.designprototype.workshop.data.artisanCardData
import com.designprototype.workshop.data.withCardData
import com.designprototype.workshop.ui.ArtisanCardScanButton
import com.designprototype.workshop.ui.ArtisanCardScanSheet
import com.designprototype.workshop.data.ArtisanAnswerDto
import com.designprototype.workshop.data.ArtisanDetailDto
import com.designprototype.workshop.data.ArtisanQuestionnaireDto
import com.designprototype.workshop.data.CraftDto
import com.designprototype.workshop.data.AnswerDrafts
import com.designprototype.workshop.ui.AnswerDraftsCard
import com.designprototype.workshop.data.CrossPortalClient
import com.designprototype.workshop.data.CrossPortalCopy
import com.designprototype.workshop.data.CrossPortalMatchDto
import com.designprototype.workshop.data.CrossPortalPrefill
import com.designprototype.workshop.ui.CrossPortalFindCard
import com.designprototype.workshop.ui.CrossPortalLinkCard
import com.designprototype.workshop.data.CreatedRecordDto
import com.designprototype.workshop.data.AppScope
import com.designprototype.workshop.data.AddressReferenceDto
import com.designprototype.workshop.data.LocationDto
import com.designprototype.workshop.data.AppReleaseDto
import com.designprototype.workshop.data.DW_UPDATE_DOWNLOAD_RETRY_MESSAGE
import com.designprototype.workshop.BuildConfig
import com.designprototype.workshop.data.FeedbackDto
import com.designprototype.workshop.data.FeedbackReportCreateRequest
import com.designprototype.workshop.data.FeedbackReportDto
import com.designprototype.workshop.data.FeedbackUpsertRequest
import com.designprototype.workshop.data.FeedbackVocabularyDto
import com.designprototype.workshop.data.readableStamp
import com.designprototype.workshop.data.MEASUREMENT_GRID_PURPOSE
import com.designprototype.workshop.data.mediaPurposeMetadata
import com.designprototype.workshop.data.MediaFileDto
import com.designprototype.workshop.data.AppSettingUpdateRequest
import com.designprototype.workshop.data.PendingReviewDto
import com.designprototype.workshop.data.PendingReviewListDto
import com.designprototype.workshop.data.StagedMedia
import com.designprototype.workshop.data.TaskDto
import com.designprototype.workshop.data.WorkshopAccessLevelDto
import com.designprototype.workshop.data.WorkshopAssignmentDto
import com.designprototype.workshop.data.DW_INTERVIEW_LANGUAGE_PLACEHOLDER
import com.designprototype.workshop.data.DW_INTERVIEW_LANGUAGES
import com.designprototype.workshop.data.StandardValues
import com.designprototype.workshop.data.dwInterviewLanguageOptions
import com.designprototype.workshop.data.WorkshopMappingRowDto
import com.designprototype.workshop.data.WorkshopMappingWindowDto
import com.designprototype.workshop.data.WorkshopMappingPlanDto
import com.designprototype.workshop.data.WorkshopSubmissionCheckDto
import com.designprototype.workshop.data.titleCasePreview
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.designprototype.workshop.data.ProductDetailDto
import com.designprototype.workshop.data.ProcessCreateRequest
import com.designprototype.workshop.data.ProcessDetailDto
import com.designprototype.workshop.data.ProcessStepDto
import com.designprototype.workshop.data.ProcessStepRequest
import com.designprototype.workshop.data.ToolDetailDto
import com.designprototype.workshop.data.WorkshopDetailDto
import com.designprototype.workshop.data.PageResponse
import androidx.compose.runtime.mutableStateListOf
import com.designprototype.workshop.ui.ArtisanQuestionnairePanel
import com.designprototype.workshop.ui.LocationFieldsSection
import com.designprototype.workshop.ui.artisanLocationRequirementError
import com.designprototype.workshop.ui.MediaThumb
import com.designprototype.workshop.ui.MediaViewerDialog
import com.designprototype.workshop.ui.ProvenanceSection
import com.designprototype.workshop.ui.RecordingIndicator
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import com.designprototype.workshop.ui.FieldCircularProgressIndicator
import com.designprototype.workshop.ui.TranscriptInsightsCard
import com.designprototype.workshop.transcript.ui.TranscriptEntry
import com.designprototype.workshop.ui.FieldProgressDefaults

class MainActivity : ComponentActivity() {
    private val incomingQuestionnaire = mutableStateOf<Uri?>(null)

    private val incomingPasswordLink = mutableStateOf<String?>(null)

    private fun takePasswordLink(intent: Intent?, restored: Boolean = false) {
        if (restored || intent == null) return
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        if (!isSetPasswordLinkAddress(uri.scheme, uri.host, uri.path)) return
        incomingPasswordLink.value = uri.toString()
    }

    @Suppress("DEPRECATION")
    private fun takeQuestionnaireDelivery(intent: Intent?) {
        val candidate = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        if (!isQuestionnaireBundleDelivery(intent?.action, intent?.type, candidate.lastPathSegment)) return
        incomingQuestionnaire.value = candidate
    }

    private val localNetworkPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askForLocalNetworkWhenTheApiNeedsIt() {
        if (!BuildConfig.DEBUG) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        if (!apiHostNeedsLocalNetwork(BuildConfig.DEFAULT_API_BASE_URL)) return
        val permission = Manifest.permission.ACCESS_LOCAL_NETWORK
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) return
        localNetworkPermission.launch(permission)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeQuestionnaireDelivery(intent)
        takePasswordLink(intent)
        LocalAlerts.takeTap(intent)
        com.designprototype.workshop.explorer.data.ExplorerDownloadsTap.take(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        takeQuestionnaireDelivery(intent)
        takePasswordLink(intent, restored = savedInstanceState != null)
        if (savedInstanceState == null) LocalAlerts.takeTap(intent)
        if (savedInstanceState == null) com.designprototype.workshop.explorer.data.ExplorerDownloadsTap.take(intent)
        askForLocalNetworkWhenTheApiNeedsIt()
        val tokenStore = TokenStore(applicationContext)
        val repository = WorkshopRepository(ApiClient.create(tokenStore), tokenStore)
        val googleAuthClient = GoogleAuthClient(this)
        val oidcAuthClient = OidcAuthClient(this)
        val preferencesStore = AppPreferencesStore(applicationContext)
        val storedPreferences = preferencesStore.read()
        setContent {
            var preferences by remember { mutableStateOf(storedPreferences) }
            DesignWorkshopTheme(darkTheme = resolveDarkTheme(preferences.theme, isSystemInDarkTheme())) {
                ProvideAppPreferences(preferences) {
                    SystemBarsInsetsRoot {
                        RepositoryApp(
                            repository = repository,
                            googleAuthClient = googleAuthClient,
                            oidcAuthClient = oidcAuthClient,
                            incomingQuestionnaire = incomingQuestionnaire.value,
                            onIncomingQuestionnaireConsumed = { incomingQuestionnaire.value = null },
                            incomingPasswordLink = incomingPasswordLink.value,
                            onIncomingPasswordLinkConsumed = { incomingPasswordLink.value = null },
                            preferences = preferences,
                            onPreferencesChanged = { next ->
                                preferences = next
                                preferencesStore.write(next)
                            }
                        )
                    }
                }
            }
        }
    }
}

private enum class DashGroup(
    val title: String,
    val note: String
) {
    DESIGNERS(
        "For designers",
        "Design & prototype workshops, sketches and reviews."
    ),
    RECORDS(
        "Records",
        "Artisans, products, processes and tools."
    ),
    MISC(
        "Miscellaneous",
        "Browse, map, scan and share."
    ),
    ADMIN(
        "Admin",
        "Users, access and settings."
    )
}

private fun DashGroup.icon(): ImageVector = when (this) {
    DashGroup.DESIGNERS -> Icons.Filled.Gesture
    DashGroup.RECORDS -> Icons.Filled.Folder
    DashGroup.MISC -> Icons.Filled.Apps
    DashGroup.ADMIN -> Icons.Filled.AdminPanelSettings
}

@Composable
private fun DashGroup.accent(): FieldAccent = when (this) {
    DashGroup.DESIGNERS -> MaterialTheme.field.groupDesigners
    DashGroup.RECORDS -> MaterialTheme.field.groupRecords
    DashGroup.MISC -> MaterialTheme.field.groupMisc
    DashGroup.ADMIN -> MaterialTheme.field.groupAdmin
}

private enum class EntryMode(
    val label: String,
    val actionTitle: String,
    val editable: Boolean = false,
    val group: DashGroup?,
    val onDashboard: Boolean = true
) {
    ARTISAN("Artisan", "Record artisan", editable = true, group = DashGroup.RECORDS),
    PRODUCT("Product", "Record product", editable = true, group = DashGroup.RECORDS),
    PROCESS("Process", "Document process", editable = true, group = DashGroup.RECORDS),
    TOOL("Tool", "Record tool", editable = true, group = DashGroup.RECORDS),
    QUESTIONNAIRE("Questionnaire", "Take interview", editable = true, group = DashGroup.RECORDS),
    MEDIA("Miscellaneous Media", "Upload media", group = DashGroup.RECORDS),
    VIEW_DATA("View Data", "Browse records", group = DashGroup.MISC),
    SEARCH("Search", "Search", onDashboard = false, group = null),
    DATA_BROWSER("Data Browser", "Data Browser", onDashboard = false, group = null),
    MAP("Map", "Map", group = DashGroup.MISC),
    CONSOLIDATED_QUESTIONNAIRE("Consolidated questionnaire", "Consolidated questionnaire", group = DashGroup.MISC),
    TASKS("Tasks", "My tasks", group = DashGroup.MISC),
    SHARING("Sharing", "Share data access", group = DashGroup.MISC),
    WORKSHOP_ACCESS("Workshop access", "Request workshop access", group = DashGroup.MISC),
    USERS("Users", "Manage users", group = DashGroup.ADMIN),
    CRAFT("Craft", "Add craft", editable = true, group = DashGroup.RECORDS),
    WORKSHOP("Workshop", "Record workshop", editable = true, group = DashGroup.RECORDS)
}

private fun Screen.memoryKey(): Screen = if (this is Screen.DesignWorkshops) Screen.DesignWorkshops() else this

private sealed interface Screen {
    data object Dashboard : Screen
    data class Create(
        val mode: EntryMode,
        val prefill: Prefill? = null,
        val searchFocus: String? = null,
        val fromCard: String? = null,
        val from: Screen? = null
    ) : Screen
    data class Browse(val mode: EntryMode) : Screen
    data class View(
        val mode: EntryMode,
        val recordId: String,
        val from: Screen? = null
    ) : Screen
    data class Edit(
        val mode: EntryMode,
        val recordId: String,
        val from: Screen? = null,
        val focus: String? = null
    ) : Screen
    data object MyActivity : Screen
    data object ToolAssign : Screen
    data object Feedback : Screen
    data object Notifications : Screen
    data object Appearance : Screen

    data object MyAiKeys : Screen
    data object SpeechAndAi : Screen
    data object UsageRecording : Screen
    data object TwoStepVerification : Screen
    data object AccountPassword : Screen
    data object Aadhaar : Screen
    data object DataBrowser : Screen
    data class AdminHub(val section: AdminHubEntry? = null) : Screen

    data class DesignWorkshops(val startCreating: Boolean = false) : Screen
    data class DesignWorkshopStages(
        val workshopId: String,
        val focusConsent: Boolean = false,
        val from: Screen? = null
    ) : Screen
    data class DesignWorkshopStage(
        val workshopId: String,
        val stageKey: String,
        val focus: DwStageFocus? = null,
    ) : Screen
    data class DesignWorkshopReport(val workshopId: String) : Screen

    data class DesignWorkshopReportHistory(val workshopId: String) : Screen

    data object SketchesAndPrototypes : Screen

    data object DesignReview : Screen

    data object ScanCode : Screen

    data object ReviewQueue : Screen

    data class DesignWorkshopCodes(val workshopId: String) : Screen

    data class DesignWorkshopPhotos(val workshopId: String) : Screen

    data class DesignWorkshopViewers(val workshopId: String) : Screen

    data class DesignWorkshopInspectors(val workshopId: String) : Screen

    data object DesignWorkshopInspections : Screen

    data class DesignWorkshopInspection(val workshopId: String) : Screen

    data class DesignWorkshopProvenance(val workshopId: String) : Screen
    data class DesignWorkshopAiLayers(val workshopId: String) : Screen

    data class DesignerProfile(val userId: String? = null, val label: String? = null) : Screen

    data object DesignerRoster : Screen

    data object AccessRoster : Screen

    data object Questionnaires : Screen
    data class QuestionnaireDetail(
        val questionnaireId: String,
        val from: Screen? = null
    ) : Screen
    data class QuestionnaireAnswers(val questionnaireId: String, val entryId: String) : Screen
    data class RemoteRecord(val type: String, val id: String, val from: Screen? = null) : Screen
}

private typealias OnRecordCreated = (id: String, label: String, hint: String) -> Unit

private data class InlineRecordRequest(
    val model: String,
    val recordId: String?,
    val seed: DwInlineSeed,
    val onFinished: (DwInlineRecordOutcome) -> Unit,
)

private data class Prefill(
    val artisanId: String? = null,
    val artisanName: String? = null,
    val place: String? = null,
    val craftId: String? = null,
    val craftName: String? = null
)

private fun Prefill.toCarryContext(): CarryContext = CarryContext(
    artisanId = artisanId,
    artisanName = artisanName,
    place = place,
    craftId = craftId,
    craftName = craftName
)

@Composable
private fun rememberFormCarry(
    repository: WorkshopRepository,
    enabled: Boolean,
    applies: Set<CarryNode>,
    scopes: List<CarryScope>,
    handoff: Prefill? = null,
    onApply: (CarryContext) -> Unit
): CarryPrefillState {
    val appContext = LocalContext.current.applicationContext
    val store = remember(appContext) { CarryContextStore(appContext) }
    val userId = remember(repository) { repository.cachedUser()?.id }
    return rememberCarryPrefill(
        store = store,
        userId = userId,
        enabled = enabled,
        handoff = handoff?.toCarryContext(),
        applies = applies,
        scopes = scopes,
        onApply = onApply
    )
}

private fun String.exceptCarried(carried: String?): String = if (carried != null && this == carried) "" else this

private fun EntryMode.icon(): ImageVector = when (this) {
    EntryMode.ARTISAN -> Icons.Filled.Person
    EntryMode.PRODUCT -> Icons.Filled.Inventory2
    EntryMode.PROCESS -> Icons.Filled.AccountTree
    EntryMode.TOOL -> Icons.Filled.Build
    EntryMode.QUESTIONNAIRE -> Icons.Filled.Quiz
    EntryMode.WORKSHOP -> Icons.Filled.Groups
    EntryMode.CRAFT -> Icons.Filled.Brush
    EntryMode.MEDIA -> Icons.Filled.PermMedia
    EntryMode.VIEW_DATA -> Icons.Filled.Visibility
    EntryMode.SEARCH -> Icons.Filled.Search
    EntryMode.DATA_BROWSER -> Icons.Filled.Storage
    EntryMode.MAP -> Icons.Filled.Map
    EntryMode.CONSOLIDATED_QUESTIONNAIRE -> Icons.Filled.Layers
    EntryMode.TASKS -> Icons.AutoMirrored.Filled.Assignment
    EntryMode.SHARING -> Icons.Filled.Share
    EntryMode.WORKSHOP_ACCESS -> Icons.Filled.LockOpen
    EntryMode.USERS -> Icons.Filled.ManageAccounts
}

@Composable
private fun RepositoryApp(
    repository: WorkshopRepository,
    googleAuthClient: GoogleAuthClient,
    oidcAuthClient: OidcAuthClient,
    incomingQuestionnaire: Uri?,
    onIncomingQuestionnaireConsumed: () -> Unit,
    incomingPasswordLink: String?,
    onIncomingPasswordLinkConsumed: () -> Unit,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Unit
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(repository.cachedUser()) }
    var loading by remember { mutableStateOf(user == null && repository.hasToken()) }
    val consentDoor = rememberUsageDoorState()
    var error by remember { mutableStateOf<String?>(null) }
    var refusal by remember { mutableStateOf(AccessRefusal.NOT_REFUSED) }
    var signInHint by remember { mutableStateOf(SignInHint.NONE) }
    var doorPassword by remember { mutableStateOf("") }
    var redeemingLink by remember { mutableStateOf(false) }
    var forgotPassword by remember { mutableStateOf(false) }
    var forgotPasswordEmail by remember { mutableStateOf("") }
    var pastedLink by remember { mutableStateOf("") }
    var twoStepChallenge by rememberSaveable { mutableStateOf<String?>(null) }

    val appContext = LocalContext.current.applicationContext

    var pendingOidc by rememberSaveable { mutableStateOf<String?>(null) }
    val oidcLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val started = pendingOidc?.split('|', limit = 2)
        pendingOidc = null
        val provider = started?.getOrNull(0)?.let { name -> OidcProviderId.entries.firstOrNull { it.name == name } }
        val rawNonce = started?.getOrNull(1)
        if (provider == null || rawNonce == null) {
            loading = false
            return@rememberLauncherForActivityResult
        }
        when (val outcome = oidcAuthClient.finish(result.data)) {
            is OidcAuthClient.Outcome.Failed -> {
                refusal = AccessRefusal.NOT_REFUSED
                signInHint = SignInHint.NONE
                error = oidcCallbackErrorMessage(provider, outcome.error)
                loading = false
            }
            is OidcAuthClient.Outcome.Code -> scope.launch {
                runCatching {
                    repository.loginWithOidc(
                        OidcLoginRequest(
                            oidcProvider = provider.name,
                            oidcCode = outcome.code,
                            oidcCodeVerifier = outcome.codeVerifier,
                            oidcRedirectUri = outcome.redirectUri,
                            oidcNonce = rawNonce,
                        )
                    )
                }
                    .onSuccess { outcome ->
                        when (outcome) {
                            is SignInOutcome.SignedIn ->
                                user = usageAnswerAtTheDoor(appContext, repository, outcome.user, consentDoor)
                            is SignInOutcome.TwoStepRequired -> twoStepChallenge = outcome.challengeToken
                        }
                    }
                    .onFailure { failure ->
                        refusal = failure.accessRefusal()
                        signInHint = failure.signInHint()
                        error = failure.signInErrorMessage()
                    }
                loading = false
            }
        }
    }

    LaunchedEffect(incomingPasswordLink) {
        val link = incomingPasswordLink ?: return@LaunchedEffect
        if (user == null) {
            pastedLink = link
            redeemingLink = true
        } else {
            Toast.makeText(
                appContext,
                "Sign out first to use a set-password link.",
                Toast.LENGTH_LONG
            ).show()
        }
        onIncomingPasswordLinkConsumed()
    }

    suspend fun refreshSession() {
        runCatching { repository.refreshUser() }
            .onSuccess { user = it }
            .onFailure { err ->
                when (err.sessionVerdict()) {
                    SessionVerdict.CHOOSE_NEW_PASSWORD -> {
                        val held = repository.holdForPasswordChange()
                        if (held != null) {
                            user = held
                        } else {
                            repository.logout(appContext)
                            user = null
                            refusal = AccessRefusal.NOT_REFUSED
                            error = err.signInErrorMessage()
                        }
                    }
                    SessionVerdict.REFUSED -> {
                        repository.logout(appContext)
                        user = null
                        refusal = err.accessRefusal()
                        error = err.signInErrorMessage()
                    }
                    SessionVerdict.EXPIRED -> {
                        repository.logout(appContext)
                        user = null
                        refusal = AccessRefusal.NOT_REFUSED
                        error = SESSION_ENDED_SENTENCE
                    }
                    SessionVerdict.KEEP -> if (user == null) {
                        error = if (err is HttpException) {
                            "Couldn't sign in. Try again."
                        } else {
                            "Couldn't connect. Check your connection and try again."
                        }
                    }
                }
            }
    }
    LaunchedEffect(Unit) {
        if (repository.hasToken()) refreshSession()
        loading = false
    }

    LaunchedEffect(Unit) {
        PasswordChangeSignal.raises.collect { raised ->
            val current = user
            if (raised > 0L && current != null && !mustChangePasswordBlocks(current)) refreshSession()
        }
    }

    LaunchedEffect(Unit) {
        SessionEndedSignal.raises.collect { raised ->
            val current = user
            if (raised > 0L && current != null && !mustChangePasswordBlocks(current)) refreshSession()
        }
    }

    val passwordHeld = mustChangePasswordBlocks(user)
    val latestPreferences by rememberUpdatedState(preferences)
    LaunchedEffect(user?.id, passwordHeld) {
        if (user == null || passwordHeld) return@LaunchedEffect
        onPreferencesChanged(syncAppPreferences(repository, latestPreferences, AppPreferencesStore(appContext)))
    }

    LaunchedEffect(user?.id, passwordHeld) {
        if (user == null || passwordHeld) return@LaunchedEffect
        repository.refreshCraftCatalogueIfStale(appContext)
    }

    var outboxCounts by remember { mutableStateOf(OutboxCounts(waiting = 0, refused = 0)) }
    var showOutboxTray by remember { mutableStateOf(false) }
    var pendingUploads by remember { mutableStateOf(0) }
    var online by remember { mutableStateOf(repository.isOnline(appContext)) }
    val serverUnreachable by ServerReachability.unreachable.collectAsState()
    val islandClearance = remember { mutableStateOf(0.dp) }
    var bannerStackHeight by remember { mutableStateOf(0.dp) }
    val bannerReserve = remember { mutableStateOf(0.dp) }
    var appMenuShowing by remember { mutableStateOf(false) }
    var pendingAccessRequests by remember { mutableIntStateOf(0) }
    var awaitingInspections by remember { mutableIntStateOf(0) }
    LaunchedEffect(user) {
        if (user == null) return@LaunchedEffect
        if (mustChangePasswordBlocks(user)) {
            DwInductionFlusher.holdForPasswordGate()
            return@LaunchedEffect
        }
        DwInductionFlusher.resumeAfterPasswordGate(appContext)
        while (true) {
            runCatching { repository.syncOutbox(appContext) }
            outboxCounts = runCatching { repository.outboxCounts(appContext) }.getOrDefault(outboxCounts)
            pendingUploads = outboxCounts.waiting
            if (user?.let { FieldPermissions.canManageAccessRoster(it) } == true) {
                runCatching { repository.pendingAccessCount() }
                    .onSuccess { pendingAccessRequests = it.pending }
            }
            if (user?.let { FieldPermissions.canInspectDesignWorkshops(it) } == true) {
                runCatching { repository.awaitingInspectionCount() }
                    .onSuccess { awaitingInspections = it.awaiting }
            }
            runCatching { repository.syncNotifications(appContext) }
            if (ServerReachability.unreachable.value && online) {
                runCatching { repository.currentUser() }
            }
            delay(if (pendingUploads > 0 || ServerReachability.unreachable.value) 12_000L else 45_000L)
        }
    }
    DisposableEffect(user) {
        if (user == null || mustChangePasswordBlocks(user)) return@DisposableEffect onDispose {}
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java)
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            fun settle(nowOnline: Boolean) {
                val wasOnline = online
                online = nowOnline
                if (shouldReprobeServer(wasOnline, nowOnline, ServerReachability.unreachable.value)) {
                    scope.launch { runCatching { repository.currentUser() } }
                }
            }

            override fun onAvailable(network: android.net.Network) {
                scope.launch {
                    settle(repository.isOnline(appContext))
                    runCatching { repository.syncOutbox(appContext) }
                    outboxCounts = runCatching { repository.outboxCounts(appContext) }.getOrDefault(outboxCounts)
                    pendingUploads = outboxCounts.waiting
                }
            }

            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                val usable = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                scope.launch { settle(usable) }
            }

            override fun onLost(network: android.net.Network) {
                scope.launch { online = false }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    val bannersHeld = user == null || passwordHeld || usageConsentBlocks(user)
    val banner = if (!bannersHeld) {
        outboxDeviceBanner(outboxCounts, online = online, serverReachable = !serverUnreachable)
    } else {
        null
    }
    val connectionLine = if (!bannersHeld) connectionNotice(online, serverUnreachable) else null
    val rootImeOpen by rememberIslandImeVisible()
    val bannersShown = !rootImeOpen && (banner != null || connectionLine != null)
    bannerReserve.value = bannerClearance(bannerStackHeight, bannersShown)
    val rootDensity = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .padding(16.dp)
    ) {
        when {
            loading -> Text("Loading…", color = Muted, modifier = Modifier.align(Alignment.Center))
            user == null && forgotPassword -> ForgotPasswordScreen(
                repository = repository,
                initialEmail = forgotPasswordEmail,
                onBack = {
                    forgotPassword = false
                    forgotPasswordEmail = ""
                }
            )
            user == null && redeemingLink -> SetPasswordLinkScreen(
                repository = repository,
                initialLink = pastedLink,
                onDone = {
                    redeemingLink = false
                    pastedLink = ""
                    error = null
                    refusal = AccessRefusal.NOT_REFUSED
                    signInHint = SignInHint.NONE
                },
                onCancel = {
                    redeemingLink = false
                    pastedLink = ""
                }
            )
            user == null && twoStepChallenge != null -> TwoStepChallengeForm(
                repository = repository,
                challengeToken = twoStepChallenge!!,
                onSignedIn = { signedIn ->
                    user = usageAnswerAtTheDoor(appContext, repository, signedIn, consentDoor)
                    twoStepChallenge = null
                    if (!mustChangePasswordBlocks(user)) doorPassword = ""
                },
                onCancel = {
                    twoStepChallenge = null
                    doorPassword = ""
                }
            )
            user == null -> LoginScreen(
                error = error,
                refusal = refusal,
                hint = signInHint,
                busy = loading,
                consentDoor = consentDoor,
                oidcProviders = oidcAuthClient.providers,
                onOidcLogin = { provider ->
                    loading = true
                    error = null
                    refusal = AccessRefusal.NOT_REFUSED
                    signInHint = SignInHint.NONE
                    doorPassword = ""
                    runCatching { oidcAuthClient.start(provider) }
                        .onSuccess { started ->
                            pendingOidc = "${provider.id.name}|${started.rawNonce}"
                            oidcLauncher.launch(started.intent)
                        }
                        .onFailure {
                            loading = false
                            error = oidcCallbackErrorMessage(provider.id, null)
                        }
                },
                onOpenSetPasswordLink = { redeemingLink = true },
                onForgotPassword = { typed ->
                    forgotPasswordEmail = typed
                    forgotPassword = true
                },
                onLogin = { email, password ->
                    scope.launch {
                        loading = true
                        error = null
                        refusal = AccessRefusal.NOT_REFUSED
                        signInHint = SignInHint.NONE
                        doorPassword = password
                        runCatching { repository.login(email, password) }
                            .onSuccess { outcome ->
                                when (outcome) {
                                    is SignInOutcome.SignedIn -> {
                                        user = usageAnswerAtTheDoor(appContext, repository, outcome.user, consentDoor)
                                        if (!mustChangePasswordBlocks(user)) doorPassword = ""
                                    }
                                    is SignInOutcome.TwoStepRequired -> twoStepChallenge = outcome.challengeToken
                                }
                            }
                            .onFailure { failure ->
                                refusal = failure.accessRefusal()
                                signInHint = failure.signInHint()
                                error = failure.signInErrorMessage()
                                doorPassword = ""
                            }
                        loading = false
                    }
                },
                onGoogleLogin = {
                    scope.launch {
                        loading = true
                        error = null
                        refusal = AccessRefusal.NOT_REFUSED
                        signInHint = SignInHint.NONE
                        doorPassword = ""
                        runCatching {
                            val idToken = googleAuthClient.getIdToken()
                            repository.loginWithGoogle(idToken)
                        }
                            .onSuccess { outcome ->
                                when (outcome) {
                                    is SignInOutcome.SignedIn ->
                                        user = usageAnswerAtTheDoor(appContext, repository, outcome.user, consentDoor)
                                    is SignInOutcome.TwoStepRequired -> twoStepChallenge = outcome.challengeToken
                                }
                            }
                            .onFailure { failure ->
                                refusal = failure.accessRefusal()
                                signInHint = failure.signInHint()
                                error = failure.signInErrorMessage()
                            }
                        loading = false
                    }
                }
            )
            usageConsentBlocks(user) -> UsageConsentGateScreen(
                repository = repository,
                user = user!!,
                onSatisfied = { user = it },
                onSignOut = {
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout(appContext)
                        consentDoor.reset()
                        doorPassword = ""
                        user = null
                    }
                }
            )
            mustChangePasswordBlocks(user) -> PasswordGateScreen(
                repository = repository,
                user = user!!,
                doorPassword = doorPassword,
                onSatisfied = {
                    user = it
                    doorPassword = ""
                },
                onSignOut = {
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout(appContext)
                        consentDoor.reset()
                        doorPassword = ""
                        user = null
                    }
                },
                onSessionEnded = { message ->
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout(appContext)
                        consentDoor.reset()
                        doorPassword = ""
                        refusal = AccessRefusal.NOT_REFUSED
                        signInHint = SignInHint.NONE
                        error = message
                        user = null
                    }
                }
            )
            else -> CompositionLocalProvider(
                LocalIslandClearance provides islandClearance,
                LocalBannerClearance provides bannerReserve
            ) { HomeScreen(
                repository = repository,
                user = user!!,
                preferences = preferences,
                onPreferencesChanged = onPreferencesChanged,
                onAppMenuShowing = { appMenuShowing = it },
                pendingAccessRequests = pendingAccessRequests,
                onPendingAccessRequests = { pendingAccessRequests = it },
                awaitingInspections = awaitingInspections,
                incomingQuestionnaire = incomingQuestionnaire,
                onIncomingQuestionnaireConsumed = onIncomingQuestionnaireConsumed,
                onLogout = {
                    scope.launch {
                        runCatching { googleAuthClient.clear() }
                        repository.logout(appContext)
                        consentDoor.reset()
                        doorPassword = ""
                        user = null
                    }
                }
            ) }
        }
        if (bannersShown && !appMenuShowing) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = BANNER_STACK_GAP + islandClearance.value)
                    .onSizeChanged { bannerStackHeight = with(rootDensity) { it.height.toDp() } },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                connectionLine?.let { OfflineNotice(text = it, modifier = Modifier.fillMaxWidth()) }
                banner?.let {
                    OutboxDeviceBannerCard(
                        banner = it,
                        modifier = Modifier.fillMaxWidth(),
                        onOpen = if (it.actionable) { { showOutboxTray = true } } else null
                    )
                }
            }
        }
        if (showOutboxTray && !passwordHeld) {
            OfflineOutboxTray(
                repository = repository,
                onClose = { showOutboxTray = false },
                onChanged = {
                    scope.launch {
                        outboxCounts = runCatching { repository.outboxCounts(appContext) }
                            .getOrDefault(outboxCounts)
                        pendingUploads = outboxCounts.waiting
                    }
                },
                online = online,
                serverReachable = !serverUnreachable,
            )
        }
    }
}

@Composable
private fun LoginScreen(
    error: String?,
    busy: Boolean,
    onLogin: (String, String) -> Unit,
    onGoogleLogin: () -> Unit,
    oidcProviders: List<OidcProvider> = emptyList(),
    onOidcLogin: (OidcProvider) -> Unit = {},
    consentDoor: UsageDoorState,
    refusal: AccessRefusal = AccessRefusal.NOT_REFUSED,
    hint: SignInHint = SignInHint.NONE,
    onOpenSetPasswordLink: () -> Unit = {},
    onForgotPassword: (String) -> Unit = {}
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Design Workshop",
            display = true,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "One archive for field visits, artisans, crafts, objects, tools and conversations.",
            color = Body,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
        )
        ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = Canvas)) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email, phone or empanelment number") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = passwordTransformation(showPassword),
                    trailingIcon = {
                        PasswordRevealIcon(
                            revealed = showPassword,
                            onToggle = { showPassword = !showPassword }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!error.isNullOrBlank()) {
                    val chrome = accessRefusalChrome(refusal)
                    val hintHeading = signInHintHeading(hint)
                    if (hintHeading != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                hintHeading,
                                display = true,
                                color = MaterialTheme.field.onWarningContainer,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(error, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else if (chrome != null) {
                        val container =
                            if (chrome.waiting) MaterialTheme.field.warningContainer
                            else MaterialTheme.colorScheme.errorContainer
                        val onContainer =
                            if (chrome.waiting) MaterialTheme.field.onWarningContainer
                            else MaterialTheme.colorScheme.onErrorContainer
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(container, RoundedCornerShape(10.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                chrome.heading,
                                display = true,
                                color = onContainer,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(error, color = onContainer, style = MaterialTheme.typography.bodyMedium)
                            Text(chrome.advice, color = onContainer, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                UsageConsentDoor(door = consentDoor)
                Button(
                    enabled = !busy && email.isNotBlank() && password.isNotBlank() && consentDoor.mayProceed,
                    onClick = { onLogin(email, password) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (busy) "Signing in..." else "Login")
                }
                OutlinedButton(
                    enabled = !busy && consentDoor.mayProceed,
                    onClick = onGoogleLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_google_g),
                            contentDescription = null,
                            tint = Color.Unspecified,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "Please wait..." else "Sign in with Google")
                    }
                }
                for (provider in oidcProviders) {
                    OutlinedButton(
                        enabled = !busy && consentDoor.mayProceed,
                        onClick = { onOidcLogin(provider) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            OidcProviderMark(provider.id)
                            Spacer(Modifier.width(8.dp))
                            Text(if (busy) "Please wait..." else "Sign in with ${provider.label}")
                        }
                    }
                }
                TextButton(
                    onClick = { onForgotPassword(email.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape
                ) {
                    Text("Forgot your password?", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = onOpenSetPasswordLink,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RectangleShape
                ) {
                    Text("I have a set-password link", style = MaterialTheme.typography.labelMedium)
                }
                if (accessRefusalChrome(refusal) == null && signInHintHeading(hint) == null) {
                    Text(
                        "Researchers: use “Sign in with Google” above. Email and password are only for accounts an admin set up.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

internal const val REGISTER_CRAFT = "RecordFormCraft"
internal const val REGISTER_ARTISAN = "RecordFormArtisan"
internal const val REGISTER_PRODUCT = "RecordFormProduct"
internal const val REGISTER_TOOL = "RecordFormTool"

internal fun craftToOption(c: CraftDto): DwReferenceOption =
    DwReferenceOption(id = c.id, label = c.name, hint = c.place.orEmpty())

internal fun optionToCraft(o: DwReferenceOption): CraftDto? =
    o.id.takeIf { it.isNotBlank() }?.let { CraftDto(id = it, name = o.label, place = o.hint.ifBlank { null }) }

internal fun artisanToOption(a: ArtisanDto): DwReferenceOption =
    DwReferenceOption(id = a.id, label = a.name, hint = a.place, filterValue = a.craftId.orEmpty(), data = artisanCardData(a))

internal fun optionToArtisan(o: DwReferenceOption): ArtisanDto? =
    o.id.takeIf { it.isNotBlank() }
        ?.let {
            withCardData(
                ArtisanDto(id = it, name = o.label, place = o.hint, status = "", craftId = o.filterValue.ifBlank { null }),
                o.data,
            )
        }

internal fun productToOption(p: ProductDetailDto): DwReferenceOption =
    DwReferenceOption(id = p.id, label = p.productName, hint = p.artisanName, filterValue = p.artisanId.orEmpty())

internal fun optionToProduct(o: DwReferenceOption): ProductDetailDto? =
    o.id.takeIf { it.isNotBlank() }
        ?.let { ProductDetailDto(id = it, productName = o.label, artisanName = o.hint, artisanId = o.filterValue.ifBlank { null }) }

internal fun toolToOption(t: ToolDetailDto): DwReferenceOption =
    DwReferenceOption(id = t.id, label = t.toolkitName, hint = t.craftName)

internal fun optionToTool(o: DwReferenceOption): ToolDetailDto? =
    o.id.takeIf { it.isNotBlank() }?.let { ToolDetailDto(id = it, toolkitName = o.label, craftName = o.hint) }

internal suspend fun <T> loadCachedRegister(
    context: Context,
    model: String,
    decode: (DwReferenceOption) -> T?,
    encode: (T) -> DwReferenceOption,
    fetch: suspend () -> List<T>,
    isTransient: (Throwable) -> Boolean,
    onList: (List<T>) -> Unit,
): RegisterLoad {
    val key = DwReferenceStore.cacheKey(model, "ALL", "", "")
    var load = RegisterLoad(source = RegisterSource.NONE)
    DwReferenceStore.load(context, key)?.let { cached ->
        onList(cached.items.mapNotNull(decode))
        load = RegisterLoad(source = RegisterSource.CACHED, fetchedAt = cached.fetchedAt)
    }
    val attempt = runCatching { fetch() }
    val fetched = attempt.getOrNull()
    if (fetched != null) {
        onList(fetched)
        load = RegisterLoad(source = RegisterSource.LIVE, online = true)
        DwReferenceStore.store(context, key, DwReferenceList(model = model, items = fetched.map(encode)))
    } else {
        val cause = attempt.exceptionOrNull()
        load = load.copy(online = cause != null && !isTransient(cause))
    }
    return load
}

internal suspend fun loadCraftRegister(
    context: Context,
    repository: WorkshopRepository,
    onList: (List<CraftDto>) -> Unit,
): RegisterLoad {
    var load = RegisterLoad(source = RegisterSource.NONE)
    withContext(Dispatchers.IO) { CraftCatalogueCache.cached(context) }?.let { cached ->
        onList(cached.crafts.map(::catalogueCraftDto))
        load = RegisterLoad(
            source = RegisterSource.CACHED,
            fetchedAt = CraftCatalogueCache.fetchedAt()?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() },
        )
    }
    val attempt = runCatching { repository.craftCatalogue(context) }
    val fresh = attempt.getOrNull()
    if (fresh != null && CraftCatalogueCache.lastRefreshReachedServer) {
        onList(fresh.crafts.map(::catalogueCraftDto))
        load = RegisterLoad(source = RegisterSource.LIVE, online = true)
    } else {
        val cause = attempt.exceptionOrNull()
        load = load.copy(online = cause != null && !repository.isTransient(cause))
    }
    return load
}

internal suspend fun loadArtisanRegister(
    context: Context,
    repository: WorkshopRepository,
    onList: (List<ArtisanDto>) -> Unit,
): RegisterLoad = loadCachedRegister(
    context = context,
    model = REGISTER_ARTISAN,
    decode = ::optionToArtisan,
    encode = ::artisanToOption,
    fetch = { repository.artisans() },
    isTransient = repository::isTransient,
    onList = onList,
)

internal suspend fun loadProductRegister(
    context: Context,
    repository: WorkshopRepository,
    onList: (List<ProductDetailDto>) -> Unit,
): RegisterLoad = loadCachedRegister(
    context = context,
    model = REGISTER_PRODUCT,
    decode = ::optionToProduct,
    encode = ::productToOption,
    fetch = { repository.products() },
    isTransient = repository::isTransient,
    onList = onList,
)

internal suspend fun loadToolRegister(
    context: Context,
    repository: WorkshopRepository,
    onList: (List<ToolDetailDto>) -> Unit,
): RegisterLoad = loadCachedRegister(
    context = context,
    model = REGISTER_TOOL,
    decode = ::optionToTool,
    encode = ::toolToOption,
    fetch = { repository.tools() },
    isTransient = repository::isTransient,
    onList = onList,
)

internal suspend fun loadArtisanProductFallback(
    repository: WorkshopRepository,
    artisanId: String,
    artisanName: String?,
    broadProducts: List<ProductDetailDto>,
): List<ProductDetailDto> {
    val linked = runCatching { repository.productsForArtisan(artisanId, artisanName) }.getOrDefault(emptyList())
    val byName = broadProducts.filter { p ->
        (p.artisanId != null && p.artisanId == artisanId) ||
            (!artisanName.isNullOrBlank() && p.artisanName.trim().equals(artisanName, ignoreCase = true))
    }
    return (linked + byName).distinctBy { it.id }
}

@Composable
private fun HomeScreen(
    repository: WorkshopRepository,
    user: UserDto,
    preferences: AppPreferences,
    onPreferencesChanged: (AppPreferences) -> Unit,
    onLogout: () -> Unit,
    pendingAccessRequests: Int = 0,
    onPendingAccessRequests: (Int) -> Unit = {},
    awaitingInspections: Int = 0,
    incomingQuestionnaire: Uri? = null,
    onIncomingQuestionnaireConsumed: () -> Unit = {},
    onAppMenuShowing: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf<DashboardStats?>(null) }
    var sections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Dashboard) }
    var questionnaireNewForm by remember { mutableIntStateOf(0) }
    var dataBrowserPath by remember { mutableStateOf("") }
    val searchMemory = remember { SearchMemory() }
    val listScroll = remember { mutableMapOf<Screen, Int>() }
    var restoreScrollFor by remember { mutableStateOf<Screen?>(null) }
    val screenMemories = remember { ScreenMemories<Screen>() }
    val workshopOrigins = remember { com.designprototype.workshop.ui.designworkshop.WorkshopOrigins<Screen>() }
    fun workshopIndex(workshopId: String, focusConsent: Boolean = false): Screen =
        Screen.DesignWorkshopStages(workshopId, focusConsent = focusConsent, from = workshopOrigins.of(workshopId))

    LaunchedEffect(incomingQuestionnaire) {
        if (incomingQuestionnaire != null && screen !is Screen.Questionnaires) {
            screen = Screen.Questionnaires
        }
    }
    val notificationOpenRequest by NotificationState.openRequests.collectAsState()
    LaunchedEffect(notificationOpenRequest) {
        if (notificationOpenRequest > 0L) screen = Screen.Notifications
    }
    val downloadsTap by com.designprototype.workshop.explorer.data.ExplorerDownloadsTap.requests.collectAsState()
    LaunchedEffect(downloadsTap) {
        if (downloadsTap > com.designprototype.workshop.explorer.ui.ExplorerTapMemory.handled && screen !is Screen.DataBrowser) screen = Screen.DataBrowser
    }
    val notificationInbox by NotificationState.inbox.collectAsState()
    val unreadNotifications = notificationInbox?.takeIf { it.userId == user.id }?.let { NotificationInboxRules.unread(it) } ?: 0
    var carryForward by remember { mutableStateOf<Prefill?>(null) }
    var inlineRecord by remember { mutableStateOf<InlineRecordRequest?>(null) }
    val inlineRecordHost = remember {
        DwInlineRecordHost { model, recordId, seed, onFinished ->
            inlineRecord = InlineRecordRequest(model, recordId, seed, onFinished)
        }
    }
    var lookupState by remember { mutableStateOf(CarryScopeState.PENDING) }
    var craftRegister by remember { mutableStateOf(RegisterLoad()) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val menuShowing = drawerState.appMenuShowing
    SideEffect { onAppMenuShowing(menuShowing) }
    DisposableEffect(Unit) { onDispose { onAppMenuShowing(false) } }
    val bannerClearanceState = LocalBannerClearance.current
    val bannerReserve = bannerClearanceState?.value ?: 0.dp
    val reducedMotion = preferences.reducedMotion
    var searchOpen by remember { mutableStateOf(false) }
    var scanOpen by remember { mutableStateOf(false) }
    val scanContext = LocalContext.current
    val messageState = remember { AppMessageState() }
    var message by messageState
    val legacyStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        requiredAndroidPermissions().takeIf { it.isNotEmpty() }?.let { legacyStorage.launch(it) }
    }
    val isAdmin = user.isAdminUser()
    val isMasterAdmin = user.isMasterAdminUser()
    val isQuestionnaireManager = user.canManageTheQuestionnaire()
    val canReview = user.canAccessReview()
    val canViewProvenance = isAdmin || user.canViewProvenance
    val canDownloadDataset = user.canDownloadTheDataset()
    val canToggleAdminView = isAdmin || FieldPermissions.isContentAdmin(user)

    val adminViewContext = LocalContext.current.applicationContext
    var adminViewRequested by remember(user.id) {
        mutableStateOf(restoredAdminView(readAdminView(adminViewContext, user.id), isMasterAdmin))
    }
    fun requestAdminView(on: Boolean) {
        adminViewRequested = on
        writeAdminView(adminViewContext, user.id, on)
    }
    val adminView = canToggleAdminView && adminViewRequested

    val adminChrome = !isAdmin || adminView
    val canDelete = mayDelete(user)

    val unsavedGuard = remember { UnsavedGuard() }
    var pendingExit by remember { mutableStateOf<(() -> Unit)?>(null) }

    val context = LocalContext.current
    var pendingUpdate by remember { mutableStateOf<AppReleaseDto?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var pushingUpdate by remember { mutableStateOf(false) }
    suspend fun checkForUpdate() {
        runCatching {
            val latest = repository.latestAppRelease()
            if (latest.versionCode > repository.installedVersionCode(context) && !latest.url.isNullOrBlank()) {
                pendingUpdate = latest
            }
        }
    }
    LaunchedEffect(Unit) { checkForUpdate() }
    val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch { checkForUpdate() }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }
    var showWalkthrough by remember { mutableStateOf(!walkthroughSeen(context)) }
    var showTerms by remember { mutableStateOf(false) }
    fun finishWalkthrough() {
        showWalkthrough = false
        markWalkthroughSeen(context)
    }

    fun offlineLineSays(text: String): Boolean =
        bannerAlreadySays(text, AppMessageTone.ERROR, bannerDrawn(bannerClearanceState?.value ?: 0.dp))

    fun showMessage(text: String?) {
        if (text.isNullOrBlank()) return
        val lower = text.lowercase()
        if ("left the composition" in lower || "was cancelled" in lower || "job was cancelled" in lower) return
        if (offlineLineSays(text)) return
        message = text
    }

    fun showError(text: String?) {
        if (text.isNullOrBlank()) return
        val lower = text.lowercase()
        if ("left the composition" in lower || "was cancelled" in lower || "job was cancelled" in lower) return
        if (offlineLineSays(text)) return
        messageState.showError(text)
    }

    val openDesignWorkshopFromSearch: ((String) -> Unit)? =
        if (SearchDesignWorkshopRoute.offeredTo(user)) {
            { workshopId -> message = null; workshopOrigins.opened(workshopId, screen); screen = Screen.DesignWorkshopStages(workshopId, from = screen) }
        } else {
            null
        }

    val openSearchRecord: (recordType: String, recordId: String) -> Unit = { recordType, recordId ->
        if (recordType == SearchRecordTypes.DESIGN_WORKSHOP) {
            val open = openDesignWorkshopFromSearch
            if (open != null) open(recordId) else showMessage(unroutableRecordLine(recordType))
        } else {
            val mode = searchRecordEntryMode(recordType)
            if (mode == null) {
                showMessage(unroutableRecordLine(recordType))
            } else {
                message = null
                screen = Screen.View(mode, recordId, from = screen.takeUnless { it is Screen.Edit })
            }
        }
    }
    val reviewOpenRecord: (recordType: String, recordId: String) -> Unit = { recordType, recordId ->
        openSearchRecord(recordType.lowercase(), recordId)
    }

    fun canCreate(mode: EntryMode): Boolean = when (mode) {
        EntryMode.CRAFT -> user.canManageTheCrafts()
        EntryMode.WORKSHOP -> user.canOpenAWorkshop()
        EntryMode.USERS -> user.canManageUsers() && adminChrome
        EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL -> user.canCreateRecords()
        EntryMode.DATA_BROWSER -> canDownloadDataset
        EntryMode.QUESTIONNAIRE, EntryMode.MEDIA, EntryMode.VIEW_DATA, EntryMode.TASKS,
        EntryMode.SHARING, EntryMode.WORKSHOP_ACCESS, EntryMode.SEARCH, EntryMode.MAP,
        EntryMode.CONSOLIDATED_QUESTIONNAIRE -> true
    }

    val dashboardModes = remember(user.role, user.canManageQuestionnaire) {
        EntryMode.entries.filter { it != EntryMode.USERS || user.canManageUsers() }
    }

    val openScanTarget: (ScanTarget) -> Unit = { target ->
        when (target) {
            is ScanTarget.Record -> openSearchRecord(target.recordType, target.id)
            is ScanTarget.DesignWorkshop -> openSearchRecord(SearchRecordTypes.DESIGN_WORKSHOP, target.id)
            ScanTarget.DesignWorkshops -> screen = Screen.DesignWorkshops()
            ScanTarget.Questionnaires -> screen = Screen.Questionnaires
            is ScanTarget.AddFromPeer ->
                if (canCreate(EntryMode.ARTISAN)) {
                    screen = Screen.Create(EntryMode.ARTISAN, fromCard = target.card)
                } else {
                    showMessage(SCAN_ADD_ARTISAN_NOT_ALLOWED)
                }
            is ScanTarget.Session -> showMessage(ScanCopy.SESSION_ELSEWHERE)
        }
    }

    fun screenFor(mode: EntryMode): Screen =
        if (mode == EntryMode.DATA_BROWSER) {
            dataBrowserPath = ""
            Screen.DataBrowser
        } else {
            if (mode == EntryMode.SEARCH) searchMemory.clear()
            Screen.Create(mode)
        }

    fun refresh() {
        scope.launch {
            runCatching { repository.stats() }
                .onSuccess { stats = it }
                .onFailure { showError(it.userMessage("Couldn't load the dashboard numbers.")) }
        }
    }

    suspend fun loadLookups() {
        val gotCrafts = loadCraftRegister(context, repository) { crafts = it }
        val gotArtisans = loadArtisanRegister(context, repository) { artisans = it }
        craftRegister = gotCrafts
        lookupState = if (gotCrafts.loaded && gotArtisans.loaded) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    }

    fun refreshLookups() {
        scope.launch { loadLookups() }
    }

    LaunchedEffect(Unit) {
        refresh()
        loadLookups()
        runCatching { repository.questionnaireSections() }
            .onSuccess { sections = it }
            .onFailure { showError(it.userMessage("Couldn't load the questionnaire sections.")) }
    }

    fun attemptExit(leave: () -> Unit) {
        if (unsavedGuard.dirty && unsavedGuard.onSave != null) {
            pendingExit = leave
        } else {
            leave()
        }
    }

    fun goDashboard() {
        message = null
        screen = Screen.Dashboard
    }

    fun openDestination(destination: NavDestination) {
        message = null
        when (destination) {
            NavDestination.DASHBOARD -> goDashboard()
            NavDestination.WALKTHROUGH -> showWalkthrough = true
            NavDestination.TERMS -> showTerms = true
            NavDestination.RECORD_ARTISAN -> screen = screenFor(EntryMode.ARTISAN)
            NavDestination.RECORD_PRODUCT -> screen = screenFor(EntryMode.PRODUCT)
            NavDestination.DOCUMENT_PROCESS -> screen = screenFor(EntryMode.PROCESS)
            NavDestination.RECORD_TOOL -> screen = screenFor(EntryMode.TOOL)
            NavDestination.TAKE_INTERVIEW -> screen = screenFor(EntryMode.QUESTIONNAIRE)
            NavDestination.UPLOAD_MEDIA -> screen = screenFor(EntryMode.MEDIA)
            NavDestination.ADD_CRAFT -> screen = screenFor(EntryMode.CRAFT)
            NavDestination.RECORD_WORKSHOP -> screen = screenFor(EntryMode.WORKSHOP)
            NavDestination.DESIGN_WORKSHOPS -> screen = Screen.DesignWorkshops()
            NavDestination.SKETCHES_AND_PROTOTYPES -> screen = Screen.SketchesAndPrototypes
            NavDestination.DESIGN_REVIEW -> screen = Screen.DesignReview
            NavDestination.DESIGN_WORKSHOP_INSPECTIONS -> screen = Screen.DesignWorkshopInspections
            NavDestination.CUSTOM_QUESTIONNAIRES -> screen = Screen.Questionnaires
            NavDestination.DESIGNER_PROFILE -> screen = Screen.DesignerProfile()
            NavDestination.DESIGNER_ROSTER -> screen = Screen.DesignerRoster
            NavDestination.ACCESS_ROSTER -> screen = Screen.AccessRoster
            NavDestination.MY_ACTIVITY -> screen = Screen.MyActivity
            NavDestination.TASKS -> screen = screenFor(EntryMode.TASKS)
            NavDestination.BROWSE_RECORDS -> screen = screenFor(EntryMode.SEARCH)
            NavDestination.SCAN_CODE -> screen = Screen.ScanCode
            NavDestination.MAP -> screen = screenFor(EntryMode.MAP)
            NavDestination.VIEW_DATA -> screen = screenFor(EntryMode.DATA_BROWSER)
            NavDestination.CONSOLIDATED_QUESTIONNAIRE -> screen = screenFor(EntryMode.CONSOLIDATED_QUESTIONNAIRE)
            NavDestination.SHARE_DATA_ACCESS -> screen = screenFor(EntryMode.SHARING)
            NavDestination.ASSIGN_TOOLS -> screen = Screen.ToolAssign
            NavDestination.REVIEW -> screen = Screen.ReviewQueue
            NavDestination.SETTINGS_HUB -> screen = Screen.AdminHub()
            NavDestination.MANAGE_USERS -> screen = screenFor(EntryMode.USERS)
            NavDestination.SETTINGS -> screen = Screen.Appearance
            NavDestination.GIVE_FEEDBACK -> screen = Screen.Feedback
            NavDestination.NOTIFICATIONS -> screen = Screen.Notifications
        }
    }

    fun navigate(destination: NavDestination) {
        scope.launch { drawerState.closeAppMenu(reducedMotion) }
        if (destination == NavDestination.WALKTHROUGH || destination == NavDestination.TERMS) {
            openDestination(destination)
        } else {
            attemptExit { openDestination(destination) }
        }
    }

    fun goBack() {
        message = null
        val leaving = screen
        screen = when (val s = screen) {
            is Screen.Edit -> s.from ?: Screen.View(s.mode, s.recordId)
            is Screen.View -> (s.from ?: Screen.Browse(s.mode)).also { restoreScrollFor = it }
            is Screen.Browse -> Screen.Dashboard
            is Screen.Create -> s.from ?: Screen.Dashboard
            is Screen.MyActivity -> Screen.Dashboard
            is Screen.ToolAssign -> Screen.Dashboard
            is Screen.Feedback -> Screen.Dashboard
            is Screen.Notifications -> Screen.Dashboard
            is Screen.Appearance -> Screen.Dashboard
            is Screen.SpeechAndAi -> Screen.Appearance
            is Screen.MyAiKeys -> Screen.Appearance
            is Screen.UsageRecording -> Screen.Appearance
            is Screen.TwoStepVerification -> Screen.Appearance
            is Screen.AccountPassword -> Screen.Appearance
            is Screen.Aadhaar -> Screen.Appearance
            is Screen.DataBrowser -> Screen.Dashboard
            is Screen.AdminHub -> if (s.section != null) Screen.AdminHub() else Screen.Dashboard
            is Screen.DesignWorkshops -> Screen.Dashboard
            is Screen.DesignWorkshopStages -> s.from ?: Screen.DesignWorkshops()
            is Screen.DesignWorkshopStage -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopReport -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopReportHistory -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopCodes -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopPhotos -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopViewers -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopInspectors -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopInspections -> Screen.Dashboard
            is Screen.DesignWorkshopInspection -> Screen.DesignWorkshopInspections
            is Screen.DesignWorkshopProvenance -> workshopIndex(s.workshopId)
            is Screen.DesignWorkshopAiLayers -> workshopIndex(s.workshopId)
            is Screen.SketchesAndPrototypes -> Screen.Dashboard
            is Screen.DesignReview -> Screen.Dashboard
            is Screen.ScanCode -> Screen.Dashboard
            is Screen.ReviewQueue -> Screen.Dashboard
            is Screen.DesignerProfile -> if (s.userId != null) Screen.DesignerRoster else Screen.Dashboard
            is Screen.DesignerRoster -> Screen.Dashboard
            is Screen.AccessRoster -> Screen.Dashboard
            is Screen.Questionnaires -> Screen.Dashboard
            is Screen.QuestionnaireDetail -> s.from ?: Screen.Questionnaires
            is Screen.QuestionnaireAnswers -> Screen.QuestionnaireDetail(s.questionnaireId)
            is Screen.RemoteRecord -> s.from ?: Screen.Dashboard
            is Screen.Dashboard -> Screen.Dashboard
        }
        if (leaving is Screen.View || leaving is Screen.Edit || leaving is Screen.DesignWorkshopStages || leaving is Screen.QuestionnaireDetail ||
            leaving is Screen.DesignWorkshopInspection || leaving is Screen.DesignerProfile || leaving is Screen.RemoteRecord ||
            (leaving is Screen.Create && leaving.from != null)
        ) restoreScrollFor = screen
    }

    val headerTitle: String? = when (val s = screen) {
        is Screen.Dashboard -> null
        is Screen.Create -> s.mode.actionTitle
        is Screen.Browse -> "Update ${s.mode.label.lowercase()}"
        is Screen.View -> s.mode.label
        is Screen.Edit -> if (s.mode.editable) "${RecordOpen.UPDATE_LABEL} ${s.mode.label.lowercase()}" else s.mode.label
        is Screen.MyActivity -> "My Activity"
        is Screen.ToolAssign -> "Assign tools to artisans"
        is Screen.Feedback -> "App feedback"
        is Screen.Notifications -> "Notifications"
        is Screen.Appearance -> "Appearance & accessibility"
        is Screen.SpeechAndAi -> null
        is Screen.MyAiKeys -> null
        is Screen.UsageRecording -> null
        is Screen.TwoStepVerification -> null
        is Screen.AccountPassword -> null
        is Screen.Aadhaar -> null
        is Screen.DataBrowser -> "Data Browser"
        is Screen.DesignWorkshops -> null
        is Screen.DesignWorkshopStages -> null
        is Screen.DesignWorkshopStage -> null
        is Screen.DesignWorkshopReport -> null
        is Screen.DesignWorkshopReportHistory -> null
        is Screen.DesignWorkshopCodes -> null
        is Screen.DesignWorkshopPhotos -> null
        is Screen.DesignWorkshopViewers -> null
        is Screen.DesignWorkshopInspectors -> null
        is Screen.DesignWorkshopInspections -> null
        is Screen.DesignWorkshopInspection -> null
        is Screen.DesignWorkshopProvenance -> null
        is Screen.DesignWorkshopAiLayers -> null
        is Screen.SketchesAndPrototypes -> null
        is Screen.DesignReview -> null
        is Screen.ScanCode -> "Scan a code"
        is Screen.ReviewQueue -> "Review"
        is Screen.DesignerProfile -> null
        is Screen.DesignerRoster -> null
        is Screen.AccessRoster -> null
        is Screen.Questionnaires -> null
        is Screen.QuestionnaireDetail -> null
        is Screen.QuestionnaireAnswers -> null
        is Screen.RemoteRecord -> null
        is Screen.AdminHub -> if (s.section == AdminHubEntry.REVIEWS && !(isAdmin && adminChrome)) {
            AdminHubEntry.REVIEWS.label
        } else {
            "Admin tools"
        }
    }

    val currentDestination: NavDestination? = when (val s = screen) {
        is Screen.Dashboard -> NavDestination.DASHBOARD
        is Screen.MyActivity -> NavDestination.MY_ACTIVITY
        is Screen.ToolAssign -> NavDestination.ASSIGN_TOOLS
        is Screen.Feedback -> NavDestination.GIVE_FEEDBACK
        is Screen.Notifications -> NavDestination.NOTIFICATIONS
        is Screen.Appearance -> NavDestination.SETTINGS
        is Screen.SpeechAndAi -> NavDestination.SETTINGS
        is Screen.MyAiKeys -> NavDestination.SETTINGS
        is Screen.UsageRecording -> NavDestination.SETTINGS
        is Screen.TwoStepVerification -> NavDestination.SETTINGS
        is Screen.AccountPassword -> NavDestination.SETTINGS
        is Screen.Aadhaar -> NavDestination.SETTINGS
        is Screen.DataBrowser -> NavDestination.VIEW_DATA
        is Screen.AdminHub -> NavDestination.SETTINGS_HUB
        is Screen.DesignWorkshops,
        is Screen.DesignWorkshopStages,
        is Screen.DesignWorkshopStage,
        is Screen.DesignWorkshopReport,
        is Screen.DesignWorkshopReportHistory,
        is Screen.DesignWorkshopCodes,
        is Screen.DesignWorkshopPhotos -> NavDestination.DESIGN_WORKSHOPS
        is Screen.SketchesAndPrototypes -> NavDestination.SKETCHES_AND_PROTOTYPES
        is Screen.DesignReview -> NavDestination.DESIGN_REVIEW
        is Screen.ScanCode -> NavDestination.SCAN_CODE
        is Screen.ReviewQueue -> NavDestination.REVIEW
        is Screen.DesignWorkshopViewers -> NavDestination.DESIGN_WORKSHOPS
        is Screen.DesignWorkshopInspectors -> NavDestination.DESIGN_WORKSHOPS
        is Screen.DesignWorkshopInspections -> NavDestination.DESIGN_WORKSHOP_INSPECTIONS
        is Screen.DesignWorkshopInspection -> NavDestination.DESIGN_WORKSHOP_INSPECTIONS
        is Screen.DesignWorkshopProvenance -> NavDestination.DESIGN_WORKSHOPS
        is Screen.DesignWorkshopAiLayers -> NavDestination.DESIGN_WORKSHOPS
        is Screen.Questionnaires,
        is Screen.QuestionnaireDetail,
        is Screen.QuestionnaireAnswers -> NavDestination.CUSTOM_QUESTIONNAIRES
        is Screen.RemoteRecord -> null
        is Screen.DesignerRoster -> NavDestination.DESIGNER_ROSTER
        is Screen.AccessRoster -> NavDestination.ACCESS_ROSTER
        is Screen.DesignerProfile -> if (s.userId == null) NavDestination.DESIGNER_PROFILE else null
        is Screen.Browse, is Screen.View, is Screen.Edit -> null
        is Screen.Create -> when (s.mode) {
            EntryMode.ARTISAN -> NavDestination.RECORD_ARTISAN
            EntryMode.PRODUCT -> NavDestination.RECORD_PRODUCT
            EntryMode.PROCESS -> NavDestination.DOCUMENT_PROCESS
            EntryMode.TOOL -> NavDestination.RECORD_TOOL
            EntryMode.QUESTIONNAIRE -> NavDestination.TAKE_INTERVIEW
            EntryMode.MEDIA -> NavDestination.UPLOAD_MEDIA
            EntryMode.CRAFT -> NavDestination.ADD_CRAFT
            EntryMode.WORKSHOP -> NavDestination.RECORD_WORKSHOP
            EntryMode.TASKS -> NavDestination.TASKS
            EntryMode.SEARCH -> NavDestination.BROWSE_RECORDS
            EntryMode.MAP -> NavDestination.MAP
            EntryMode.CONSOLIDATED_QUESTIONNAIRE -> NavDestination.CONSOLIDATED_QUESTIONNAIRE
            EntryMode.SHARING -> NavDestination.SHARE_DATA_ACCESS
            EntryMode.VIEW_DATA -> NavDestination.REVIEW
            EntryMode.USERS -> NavDestination.MANAGE_USERS
            EntryMode.DATA_BROWSER -> NavDestination.VIEW_DATA
            EntryMode.WORKSHOP_ACCESS -> null
        }
    }

    fun searchLocalItems(): List<AppSearchItem> =
        appSearchScreenItems(visibleNavItems(user, adminView), ::navigate) +
            appSearchSettingItems { target ->
                attemptExit {
                    message = null
                    screen = when (target) {
                        AppSearchSettingTarget.APPEARANCE -> Screen.Appearance
                        AppSearchSettingTarget.SPEECH_AND_AI -> Screen.SpeechAndAi
                        AppSearchSettingTarget.AI_KEYS -> Screen.MyAiKeys
                        AppSearchSettingTarget.USAGE_RECORDING -> Screen.UsageRecording
                        AppSearchSettingTarget.TWO_STEP -> Screen.TwoStepVerification
                    }
                }
            } +
            appSearchCachedItems(artisans, crafts) { type, id -> attemptExit { openSearchRecord(type, id) } }

    val recordingsRequest by QuestionnaireV2Nav.showRecordings.collectAsState()
    LaunchedEffect(recordingsRequest) {
        if (recordingsRequest != null) { message = null; screen = screenFor(EntryMode.QUESTIONNAIRE) }
    }
    val sessionRequest by QuestionnaireV2Nav.openServerSession.collectAsState()
    LaunchedEffect(sessionRequest) {
        if (sessionRequest != null && (screen as? Screen.Create)?.mode != EntryMode.QUESTIONNAIRE) { message = null; screen = screenFor(EntryMode.QUESTIONNAIRE) }
    }

    suspend fun searchRemote(query: String): List<AppSearchItem> =
        appSearchRecordingItems(context, query) { filters -> attemptExit { QuestionnaireV2Nav.showRecordings.value = filters } } +
        appSearchRemoteItems(
            repository = repository,
            query = query,
            includeCustomQuestionnaires = FieldPermissions.canRunDesignWorkshops(user),
            openRecord = { type, id -> attemptExit { openSearchRecord(type, id) } },
            openCustomQuestionnaire = { id -> attemptExit { message = null; screen = Screen.QuestionnaireDetail(id) } },
            openCluster = { attemptExit { message = null; screen = Screen.DesignWorkshops() } },
            openRemote = { type, id ->
                attemptExit { message = null; screen = Screen.RemoteRecord(type, id, from = screen) }
            }
        )

    BackHandler(enabled = drawerState.appMenuShowing) {
        scope.launch { drawerState.closeAppMenu(reducedMotion) }
    }
    BackHandler(enabled = !drawerState.appMenuShowing && screen !is Screen.Dashboard) {
        attemptExit { goBack() }
    }
    AppMenuFocusEffect(drawerState)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            modifier = Modifier.appMenuBleed(16.dp),
            gesturesEnabled = drawerState.appMenuShowing,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    AppNavigationDrawerContent(
                        user = user,
                        adminMode = adminView,
                        currentDestination = currentDestination,
                        onNavigate = ::navigate,
                        pendingAccessCount = pendingAccessRequests,
                        awaitingInspectionCount = awaitingInspections,
                        unreadNotifications = unreadNotifications,
                        pushingUpdate = pushingUpdate,
                        onToggleAdminView = { requestAdminView(!adminView) },
                        onPushUpdate = {
                            scope.launch { drawerState.closeAppMenu(reducedMotion) }
                            if (!pushingUpdate) {
                                pushingUpdate = true
                                Toast.makeText(context, "Publishing this update for everyone…", Toast.LENGTH_SHORT).show()
                                scope.launch {
                                    runCatching { repository.publishAppUpdate(context) }
                                        .onSuccess { rel ->
                                            Toast.makeText(
                                                context,
                                                "Update v${rel.versionName} published. Others get it on next open.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        .onFailure {
                                            Toast.makeText(context, it.userMessage("Unable to publish the update"), Toast.LENGTH_LONG).show()
                                        }
                                    pushingUpdate = false
                                }
                            }
                        },
                        onLogout = { scope.launch { drawerState.closeAppMenu(reducedMotion) }; onLogout() }
                    )
                }
            }
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalUnsavedGuard provides unsavedGuard
            ) {
            val ownsViewport = screen is Screen.DataBrowser || screen is Screen.Appearance ||
                screen is Screen.SpeechAndAi || screen is Screen.MyAiKeys || screen is Screen.Aadhaar ||
                screen is Screen.UsageRecording || screen is Screen.TwoStepVerification ||
                screen is Screen.AccountPassword
            Box(
                modifier = Modifier
                    .padding(16.dp)
                    .padding(bottom = if (ownsViewport) bannerReserve else 0.dp)
                    .appMenuHidesPage(drawerState.appMenuShowing)
            ) {
                when (screen) {
                    is Screen.DataBrowser -> DataBrowserScreen(
                        repository = repository,
                        onBack = { attemptExit { dataBrowserPath = ""; goBack() } },
                        onMessage = { showMessage(it) },
                        onOpenMedia = { mediaId ->
                            message = null
                            screen = Screen.View(EntryMode.MEDIA, mediaId, from = Screen.DataBrowser)
                        },
                        startPath = dataBrowserPath,
                        onPathChange = { dataBrowserPath = it },
                        onOpenRemote = { type, id -> screen = Screen.RemoteRecord(type, id, from = Screen.DataBrowser) },
                        onRecordNow = { path, name ->
                            val artisanId = com.designprototype.workshop.ui.dataFolderArtisan(path)
                            screen = Screen.Create(EntryMode.MEDIA, artisanId?.let { Prefill(artisanId = it, artisanName = name) }, from = Screen.DataBrowser)
                        }
                    )

                    is Screen.Appearance -> AppearanceScreen(
                        repository = repository,
                        current = preferences,
                        onChanged = onPreferencesChanged,
                        onBack = { attemptExit { goBack() } },
                        onOpenSpeechAndAi = { screen = Screen.SpeechAndAi },
                        onOpenMyAiKeys = { screen = Screen.MyAiKeys },
                        onOpenUsageRecording = { screen = Screen.UsageRecording },
                        onOpenTwoStepVerification = { screen = Screen.TwoStepVerification },
                        onOpenPassword = { screen = Screen.AccountPassword },
                        onOpenAadhaar = if (AadhaarFeatures.of(user).enabled) {
                            { screen = Screen.Aadhaar }
                        } else {
                            null
                        },
                        onOpenWalkthrough = { openDestination(NavDestination.WALKTHROUGH) }
                    )

                    is Screen.SpeechAndAi -> SpeechAndAiScreen(
                        onBack = { attemptExit { goBack() } }
                    )

                    is Screen.MyAiKeys -> MyAiKeysScreen(
                        repository = repository,
                        onBack = { attemptExit { goBack() } }
                    )

                    is Screen.Aadhaar -> AadhaarSelfScreen(
                        viewer = user,
                        onBack = { attemptExit { goBack() } }
                    )

                    is Screen.UsageRecording -> UsageRecordingScreen(
                        repository = repository,
                        onBack = { attemptExit { goBack() } }
                    )

                    is Screen.TwoStepVerification -> TwoStepVerificationScreen(
                        repository = repository,
                        onBack = { attemptExit { goBack() } }
                    )

                    is Screen.AccountPassword -> AccountPasswordScreen(
                        repository = repository,
                        onBack = { attemptExit { goBack() } }
                    )

                    else -> {
                val pageScroll = key(screen) { rememberScrollState() }
                val screenMemory = key(screen) {
                    val here = screen.memoryKey()
                    remember { screenMemories.arrive(here, restoring = restoreScrollFor?.memoryKey() == here) }
                }
                key(screen) {
                    val here = screen.memoryKey()
                    DisposableEffect(Unit) { onDispose { listScroll[here] = pageScroll.value } }
                    LaunchedEffect(Unit) {
                        if (restoreScrollFor?.memoryKey() != here) return@LaunchedEffect
                        restoreScrollFor = null
                        val target = listScroll[here] ?: 0
                        if (target <= 0) return@LaunchedEffect
                        withTimeoutOrNull(5_000) {
                            snapshotFlow { pageScroll.maxValue }.first { it >= target }
                        }
                        pageScroll.scrollTo(target)
                    }
                }
                IslandScaffold(island = { islandModifier ->
        val navItems = visibleNavItems(user, adminView)
        FieldIslandNav(
            modifier = islandModifier,
            onOpenDrawer = { scope.launch { drawerState.openAppMenu(reducedMotion) } },
            visible = !searchOpen,
            currentLabel = navItems.firstOrNull { it.destination == currentDestination }?.label,
            roots = navItems.filter { it.group == null }
                .map { entry -> IslandEntry(entry.label, entry.icon) { navigate(entry.destination) } },
            groups = NavGroup.entries.map { group ->
                val entries = navItems.filter { it.group == group }
                    .map { entry -> IslandEntry(entry.label, entry.icon) { navigate(entry.destination) } }
                IslandGroup(
                    group.label,
                    if (group == NavGroup.ACCOUNT) {
                        entries + IslandEntry("Open the web portal", Icons.AutoMirrored.Filled.OpenInNew) { openWebPortal(context) }
                    } else {
                        entries
                    }
                )
            }
        )
                }) { islandReserve ->
        val quickReturn = rememberQuickReturnHeader(pageScroll, pinned = searchOpen)
        val pageImeOpen by rememberIslandImeVisible()
        val clearance = LocalIslandClearance.current
        SideEffect { clearance?.value = islandReserve }
        DisposableEffect(clearance) { onDispose { clearance?.value = 0.dp } }
        val pageReserve = pageBottomReserve(islandReserve, bannerReserve)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(quickReturn.connection)
                .verticalScroll(pageScroll)
                .padding(top = with(LocalDensity.current) { quickReturn.reservePx.toDp() })
                .padding(bottom = pageReserve + if (messageState.text != null) 88.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

        val showBack = screen !is Screen.Dashboard
        if (showBack || headerTitle != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (showBack) {
                    IconButton(onClick = { attemptExit { goBack() } }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                headerTitle?.let { title ->
                    Text(
                        title,
                        display = true,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        }

        CompositionLocalProvider(
            LocalAppNotice provides { showMessage(it) },
            LocalContentAdminChrome provides contentAdminChrome(user, adminView),
            LocalMayDelete provides canDelete,
            LocalScreenMemory provides screenMemory,
        ) {
        ProvideCompletenessFocus((screen as? Screen.Edit)?.focus, key = screen) {
        when (val s = screen) {
            is Screen.Dashboard -> {
                carryForward?.let { prefill ->
                    CarryForwardPanel(
                        repository = repository,
                        prefill = prefill,
                        canCreateTool = canCreate(EntryMode.TOOL),
                        onSelect = { mode -> screen = Screen.Create(mode, prefill); carryForward = null },
                        onDismiss = { carryForward = null }
                    )
                }
                DashboardScreen(
                    stats = stats,
                    recentArtisans = artisans,
                    actions = dashboardModes.filter { canCreate(it) && it.onDashboard },
                    roleLabel = roleLabel(user.role),
                    canCreateRecords = user.canCreateRecords(),
                    showDesignWorkshop = DesignWorkshopCard.visibleTo(user),
                    onNewDesignWorkshop = {
                        message = null
                        screen = Screen.DesignWorkshops(startCreating = true)
                    },
                    onOpenDesignWorkshops = { message = null; screen = Screen.DesignWorkshops() },
                    showSketchesAndPrototypes = SketchesAndPrototypesCard.visibleTo(user),
                    onOpenSketchesAndPrototypes = {
                        message = null
                        screen = Screen.SketchesAndPrototypes
                    },
                    showDesignReview = DesignReviewCard.visibleTo(user),
                    onOpenDesignReview = { message = null; screen = Screen.DesignReview },
                    showDesignerProfile = DesignerProfileCard.visibleTo(user),
                    onOpenDesignerProfile = { message = null; screen = Screen.DesignerProfile() },
                    onOpenScanCode = { message = null; screen = Screen.ScanCode },
                    showAdminHub = isAdmin && adminChrome,
                    onOpenAdminHub = { message = null; screen = Screen.AdminHub() },
                    onWalkthrough = { message = null; showWalkthrough = true },
                    onNew = { selected -> message = null; screen = Screen.Create(selected) },
                    onUpdateExisting = { selected -> message = null; screen = Screen.Browse(selected) },
                    onOpenSearchFor = { recordType ->
                        message = null
                        searchMemory.clear()
                        screen = Screen.Create(EntryMode.SEARCH, searchFocus = recordType)
                    },
                    onOpenReviews = if (canReview) {
                        ({ message = null; screen = Screen.AdminHub(AdminHubEntry.REVIEWS) })
                    } else null,
                    onOpenMyActivity = { message = null; screen = Screen.MyActivity },
                    onOpenRecord = { mode, recordId -> message = null; screen = Screen.View(mode, recordId, from = Screen.Dashboard) },
                    onOpenArtisan = { artisanId -> message = null; screen = Screen.View(EntryMode.ARTISAN, artisanId, from = Screen.Dashboard) }
                )
            }

            is Screen.Browse -> RecordPickerScreen(
                repository = repository,
                mode = s.mode,
                canUpdate = RecordOpen.mayUpdate(s.mode.name, user),
                onOpen = { recordId -> message = null; screen = Screen.View(s.mode, recordId, from = s) },
                onUpdate = { recordId -> message = null; screen = Screen.Edit(s.mode, recordId, from = s) },
                onError = { showError(it) }
            )

            is Screen.View -> RecordViewScreen(
                repository = repository,
                mode = s.mode,
                recordId = s.recordId,
                canUpdate = RecordOpen.mayUpdate(s.mode.name, user),
                showProvenance = canViewProvenance,
                canDelete = canDelete,
                onUpdate = { focus -> message = null; screen = Screen.Edit(s.mode, s.recordId, from = s, focus = focus) },
                onDeleted = {
                    message = "${deleteNoun(s.mode).replaceFirstChar { it.uppercase() }} deleted"
                    refresh()
                    screen = s.from ?: Screen.Browse(s.mode)
                },
                onError = { showError(it) }
            )

            is Screen.Create -> when (s.mode) {
                EntryMode.CRAFT -> CraftForm(
                    repository = repository,
                    onDone = { message = "Craft saved"; refresh(); refreshLookups(); goDashboard() },
                    onError = { showError(it) }
                )
                EntryMode.ARTISAN -> ArtisanForm(
                    repository = repository,
                    crafts = crafts,
                    lookupState = lookupState,
                    craftRegister = craftRegister,
                    prefill = s.prefill,
                    fromCard = s.fromCard,
                    adminView = adminView,
                    onArtisanCreated = { prefill ->
                        message = "Artisan saved"
                        refresh(); refreshLookups()
                        carryForward = prefill
                        goDashboard()
                    },
                    onOpenArtisan = { artisanId ->
                        message = null
                        screen = Screen.View(EntryMode.ARTISAN, artisanId, from = Screen.Dashboard)
                    },
                    onDone = { message = "Artisan saved"; refresh(); refreshLookups(); goDashboard() },
                    onError = { showError(it) }
                )
                EntryMode.WORKSHOP -> if (canCreate(EntryMode.WORKSHOP)) {
                    WorkshopForm(
                        repository = repository,
                        artisans = artisans,
                        prefill = s.prefill,
                        adminView = adminView,
                        onDone = { message = "Workshop saved"; refresh(); goDashboard() },
                        onError = { showError(it) }
                    )
                } else {
                    WorkshopCreateRefusedCard(
                        onOpenExisting = if (FieldPermissions.canManageWorkshops(user)) {
                            ({ message = null; screen = Screen.Browse(EntryMode.WORKSHOP) })
                        } else null
                    )
                }
                EntryMode.PRODUCT -> ProductForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    adminView = adminView,
                    onDone = { message = "Product saved"; refresh(); goDashboard() },
                    onError = { showError(it) }
                )
                EntryMode.PROCESS -> ProcessForm(
                    repository = repository,
                    adminView = adminView,
                    onDone = { message = "Process saved"; refresh(); goDashboard() },
                    onError = { showError(it) }
                )
                EntryMode.VIEW_DATA -> ViewDataScreen(
                    repository = repository,
                    canReview = canReview,
                    masterAdmin = isMasterAdmin,
                    isAdmin = isAdmin,
                    adminChrome = adminChrome,
                    showProvenance = canViewProvenance,
                    canDownloadDataset = canDownloadDataset,
                    onOpenDataBrowser = { message = null; screen = screenFor(EntryMode.DATA_BROWSER) },
                    onError = { showError(it) }
                )
                EntryMode.SEARCH -> {
                    s.searchFocus?.let { focus ->
                        Text(
                            "All ${searchFocusLabel(focus)} records, newest first.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    SearchScreen(
                        repository = repository,
                        onOpenRecord = openSearchRecord,
                        onOpenDesignWorkshop = openDesignWorkshopFromSearch,
                        onBack = { attemptExit { goBack() } },
                        initialRecordType = s.searchFocus,
                        memory = searchMemory,
                        onError = { showError(it) }
                    )
                }
                EntryMode.DATA_BROWSER -> DataBrowserEntryCard(
                    onOpen = { message = null; screen = screenFor(EntryMode.DATA_BROWSER) }
                )
                EntryMode.MAP -> MapScreen(
                    repository = repository,
                    onError = { showError(it) },
                    onOpenRecord = openSearchRecord
                )
                EntryMode.CONSOLIDATED_QUESTIONNAIRE -> DesignerArtisanQuestionnaireRecords(
                    artisans = artisans,
                    onStartSession = { message = null; screen = screenFor(EntryMode.QUESTIONNAIRE) },
                    onOpenSession = { message = null; screen = screenFor(EntryMode.QUESTIONNAIRE) },
                    onMessage = { showMessage(it) }
                )
                EntryMode.TOOL -> ToolForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    lookupState = lookupState,
                    prefill = s.prefill,
                    adminView = adminView,
                    onDone = { message = "Tool saved"; refresh(); goDashboard() },
                    onError = { showError(it) }
                )
                EntryMode.MEDIA -> AndroidMediaForm(
                    repository = repository,
                    onUploaded = { count ->
                        message = "$count file${if (count == 1) "" else "s"} uploaded"
                        refresh()
                    },
                    onError = { showError(it) }
                )
                EntryMode.QUESTIONNAIRE -> DesignerQuestionnaireHome(
                    artisans = artisans,
                    user = user,
                    initialTab = QuestionnaireTab.SESSIONS,
                    formsTab = { onStartSession ->
                        QuestionnaireListScreen(
                            repository = repository,
                            onOpen = { id -> message = null; screen = Screen.QuestionnaireDetail(id, from = s) },
                            onMessage = { showMessage(it) },
                            onError = { showError(it) },
                            incoming = incomingQuestionnaire,
                            onIncomingConsumed = onIncomingQuestionnaireConsumed,
                            newFormRequest = questionnaireNewForm,
                            onStartSession = onStartSession,
                        )
                    },
                    onNewForm = { questionnaireNewForm++ },
                    onError = { showError(it) },
                    modifier = Modifier.fillMaxWidth().height(questionnaireViewportHeight()),
                    onLeaveSession = s.from?.let { origin -> { message = null; restoreScrollFor = origin; screen = origin } }
                )
                EntryMode.TASKS -> MyTasksScreen(
                    repository = repository,
                    canAssign = isAdmin && adminChrome,
                    onOpenAssignmentBoard = { message = null; screen = Screen.AdminHub(AdminHubEntry.TASKS) },
                    onMessage = { showMessage(it) },
                    onError = { showError(it) }
                )
                EntryMode.SHARING -> SharingForm(
                    repository = repository,
                    isAdmin = isAdmin,
                    onError = { showError(it) }
                )
                EntryMode.WORKSHOP_ACCESS -> WorkshopAccessScreen(
                    repository = repository,
                    onMessage = { showMessage(it) },
                    onError = { showError(it) }
                )
                EntryMode.USERS -> if (adminChrome) {
                    UserManagementForm(
                        repository = repository,
                        isMasterAdmin = isMasterAdmin,
                        onError = { showError(it) }
                    )
                } else {
                    AdminViewHiddenCard(
                        label = "User management",
                        blurb = "Roles, permissions and accounts.",
                        canToggle = canToggleAdminView,
                        onEnable = { requestAdminView(true) }
                    )
                }
            }

            is Screen.Edit -> if (s.mode == EntryMode.QUESTIONNAIRE) {
                LaunchedEffect(s.recordId) {
                    QuestionnaireV2Nav.openServerSession.value = s.recordId
                    screen = Screen.Create(EntryMode.QUESTIONNAIRE)
                }
            } else EditScreen(
                repository = repository,
                mode = s.mode,
                recordId = s.recordId,
                crafts = crafts,
                artisans = artisans,
                adminView = adminView,
                canDelete = canDelete,
                onDone = { message = "${s.mode.label} updated"; refresh(); refreshLookups(); goDashboard() },
                onError = { showError(it) }
            )

            is Screen.MyActivity -> MyActivityScreen(
                repository = repository,
                userId = user.id,
                onOpen = { mode, recordId -> message = null; screen = Screen.View(mode, recordId, from = Screen.MyActivity) },
                onError = { showError(it) }
            )

            is Screen.ToolAssign -> ToolAssignScreen(
                repository = repository,
                onError = { showError(it) }
            )

            is Screen.Feedback -> FeedbackScreen(
                repository = repository,
                onError = { showError(it) }
            )

            is Screen.Notifications -> NotificationsScreen(
                api = repository.notificationApi(),
                userId = user.id,
                onOpenLink = { link ->
                    val workshopId = notificationWorkshopId(link)
                    when {
                        workshopId != null -> { message = null; screen = workshopIndex(workshopId) }
                        link == "/settings" -> screen = Screen.Appearance
                        WeeklyDigestSwitch.isDigestLink(link) -> Unit
                    }
                }
            )

            is Screen.AdminHub -> when {
                isAdmin && adminChrome -> AdminHubScreen(
                    repository = repository,
                    isMasterAdmin = isMasterAdmin,
                    canReview = canReview,
                    section = s.section,
                    onSectionChange = { next -> screen = Screen.AdminHub(next) },
                    onMessage = { showMessage(it) },
                    onError = { showError(it) }
                )
                s.section == AdminHubEntry.REVIEWS && canReview -> ReviewApprovalCard(
                    repository = repository,
                    onError = { showError(it) },
                    onOpenRecord = reviewOpenRecord
                )
                else -> AdminViewHiddenCard(
                    label = "The settings hub",
                    blurb = "Reviews, recovered recordings, assignments, rosters, access requests and transcription providers.",
                    canToggle = canToggleAdminView,
                    onEnable = { requestAdminView(true) }
                )
            }

            is Screen.DesignWorkshops -> WorkshopListScreen(
                repository = repository,
                startCreating = s.startCreating,
                onOpen = { id -> message = null; workshopOrigins.openedFromList(id); screen = Screen.DesignWorkshopStages(id) },
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.DesignWorkshopStages -> StageIndexScreen(
                repository = repository,
                workshopId = s.workshopId,
                focusConsent = s.focusConsent,
                onOpenStage = { key, focus ->
                    message = null
                    screen = Screen.DesignWorkshopStage(s.workshopId, key, focus)
                },
                onOpenReport = { message = null; screen = Screen.DesignWorkshopReport(s.workshopId) },
                onOpenReportHistory = {
                    message = null
                    screen = Screen.DesignWorkshopReportHistory(s.workshopId)
                },
                onOpenCodes = { message = null; screen = Screen.DesignWorkshopCodes(s.workshopId) },
                onOpenPhotos = { message = null; screen = Screen.DesignWorkshopPhotos(s.workshopId) },
                onOpenViewers = { message = null; screen = Screen.DesignWorkshopViewers(s.workshopId) },
                onOpenInspectors = { message = null; screen = Screen.DesignWorkshopInspectors(s.workshopId) },
                onOpenProvenance = { message = null; screen = Screen.DesignWorkshopProvenance(s.workshopId) },
                onOpenAiLayers = { message = null; screen = Screen.DesignWorkshopAiLayers(s.workshopId) },
                onError = { showError(it) },
                canDelete = canDelete,
                onDeleted = { message = "Design workshop deleted"; screen = Screen.DesignWorkshops() },
            )

            is Screen.DesignWorkshopStage -> StageScreen(
                repository = repository,
                workshopId = s.workshopId,
                stageKey = s.stageKey,
                focus = s.focus,
                onMessage = { showMessage(it) },
                onError = { showError(it) },
                inlineRecords = if (canCreate(EntryMode.ARTISAN)) inlineRecordHost else null,
                onOpenConsent = {
                    message = null
                    screen = workshopIndex(s.workshopId, focusConsent = true)
                },
            )

            is Screen.DesignWorkshopReport -> ReportScreen(
                repository = repository,
                workshopId = s.workshopId,
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.DesignWorkshopReportHistory -> DwReportHistoryScreen(
                repository = repository,
                workshopId = s.workshopId
            )

            is Screen.DesignWorkshopCodes -> WorkshopCodesScreen(
                repository = repository,
                workshopId = s.workshopId,
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.DesignWorkshopPhotos -> PhotoIntakeScreen(
                repository = repository,
                workshopId = s.workshopId,
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.DesignWorkshopViewers -> WorkshopViewersScreen(
                repository = repository,
                workshopId = s.workshopId,
                onMessage = { showMessage(it) }
            )

            is Screen.DesignWorkshopInspectors -> WorkshopInspectorsScreen(
                repository = repository,
                workshopId = s.workshopId,
                onMessage = { showMessage(it) }
            )

            is Screen.DesignWorkshopInspections -> InspectionListScreen(
                repository = repository,
                onOpenWorkshop = { id ->
                    message = null
                    screen = Screen.DesignWorkshopInspection(workshopId = id)
                }
            )

            is Screen.DesignWorkshopInspection -> InspectionDetailScreen(
                repository = repository,
                workshopId = s.workshopId
            )

            is Screen.DesignWorkshopProvenance -> DwProvenanceScreen(
                repository = repository,
                workshopId = s.workshopId
            )

            is Screen.DesignWorkshopAiLayers -> DwAiLayersScreen(
                repository = repository,
                workshopId = s.workshopId
            )

            is Screen.SketchesAndPrototypes -> SketchesAndPrototypesScreen(
                repository = repository,
                onOpenStage = { workshopId, stageKey ->
                    message = null
                    screen = Screen.DesignWorkshopStage(workshopId = workshopId, stageKey = stageKey)
                },
                onError = { showError(it) }
            )

            is Screen.DesignReview -> DesignReviewScreen(
                repository = repository,
                onOpenStage = { workshopId, stageKey ->
                    message = null
                    screen = Screen.DesignWorkshopStage(workshopId = workshopId, stageKey = stageKey)
                },
            )

            is Screen.ReviewQueue -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                InspectionAwaitingSection(
                    repository = repository,
                    onOpenWorkshop = { id ->
                        message = null
                        screen = Screen.DesignWorkshopInspection(workshopId = id)
                    }
                )
                if (canReview) {
                    ReviewApprovalCard(repository = repository, onError = { showError(it) }, onOpenRecord = reviewOpenRecord)
                }
            }

            is Screen.ScanCode -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            ) {
                Text(
                    "Scan a card, tag or picture to open its record.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 17.sp
                )
                RecordCodeLookupPanel(
                    repository = repository,
                    onOpen = openSearchRecord,
                    onTarget = openScanTarget,
                    canAddArtisan = canCreate(EntryMode.ARTISAN)
                )
                Text(
                    "Cards & tags inside a workshop also scans offline.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp
                )
            }

            is Screen.DesignerProfile -> DesignerProfileScreen(
                repository = repository,
                targetUserId = s.userId,
                targetLabel = s.label,
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.DesignerRoster -> DesignerRosterScreen(
                repository = repository,
                onOpenProfile = { userId, label ->
                    message = null
                    screen = Screen.DesignerProfile(userId = userId, label = label)
                },
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.AccessRoster -> AccessRosterScreen(
                repository = repository,
                onMessage = { showMessage(it) },
                onError = { showError(it) },
                onPendingCount = onPendingAccessRequests,
            )

            is Screen.Questionnaires -> DesignerQuestionnaireHome(
                artisans = artisans,
                user = user,
                initialTab = QuestionnaireTab.FORMS,
                formsTab = { onStartSession ->
                    QuestionnaireListScreen(
                        repository = repository,
                        onOpen = { id -> message = null; screen = Screen.QuestionnaireDetail(id, from = s) },
                        onMessage = { showMessage(it) },
                        onError = { showError(it) },
                        incoming = incomingQuestionnaire,
                        onIncomingConsumed = onIncomingQuestionnaireConsumed,
                        newFormRequest = questionnaireNewForm,
                        onStartSession = onStartSession,
                    )
                },
                onNewForm = { questionnaireNewForm++ },
                onError = { showError(it) },
                modifier = Modifier.fillMaxWidth().height(questionnaireViewportHeight())
            )

            is Screen.QuestionnaireDetail -> QuestionnaireDetailScreen(
                repository = repository,
                questionnaireId = s.questionnaireId,
                onOpenEntry = { entryId ->
                    message = null
                    screen = Screen.QuestionnaireAnswers(s.questionnaireId, entryId)
                },
                onMessage = { showMessage(it) },
                onError = { showError(it) },
                onResolvedId = { real -> screen = Screen.QuestionnaireDetail(real, from = s.from) },
                onDeleted = { message = null; screen = Screen.Questionnaires },
                onStartSession = if (questionnaireSessionsOn()) { id ->
                    message = null
                    QuestionnaireV2Nav.startOnForm.value = id
                    screen = screenFor(EntryMode.QUESTIONNAIRE)
                } else null,
                onOpenSessions = if (questionnaireSessionsOn()) { _ -> message = null; screen = Screen.Create(EntryMode.QUESTIONNAIRE, from = s) } else null,
            )

            is Screen.QuestionnaireAnswers -> QuestionnaireAnswerScreen(
                repository = repository,
                questionnaireId = s.questionnaireId,
                entryId = s.entryId,
                onMessage = { showMessage(it) },
                onError = { showError(it) }
            )

            is Screen.RemoteRecord -> RemoteRecordScreen(repository = repository, type = s.type, id = s.id)

            is Screen.Appearance, is Screen.SpeechAndAi, is Screen.MyAiKeys,
            is Screen.UsageRecording, is Screen.TwoStepVerification, is Screen.AccountPassword,
            is Screen.Aadhaar, is Screen.DataBrowser -> Unit
        }
        } }

                }
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = pageReserve),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    AppMessageBanner(messageState)
                }
                QuickReturnHeader(quickReturn, hidden = pageImeOpen && !searchOpen) { elevated ->
                    AppSearchHeader(
                        appName = APP_SEARCH_NAME,
                        logo = { m -> WorkshopLogo(modifier = m, cornerRadius = 10.dp) },
                        onOpenSearch = { searchOpen = true },
                        onScan = { scanOpen = true },
                        elevated = elevated
                    )
                }
                if (scanOpen) {
                    val scanServices = remember(repository) { designerScanServices(scanContext, repository) }
                    TopBarScanSheet(
                        dispatch = { raw -> designerScanOutcome(dispatchScan(raw, scanServices), canCreate(EntryMode.ARTISAN)) },
                        onAction = { action ->
                            runDesignerScanAction(scanContext, action) { target ->
                                scanOpen = false
                                attemptExit { openScanTarget(target) }
                            }
                        },
                        onDismiss = { scanOpen = false },
                        scanner = { enabled, onText, onRefusal -> DesignerScanControls(enabled, onText, onRefusal) }
                    )
                }
                if (searchOpen) {
                    AppSearchPanel(
                        appName = APP_SEARCH_NAME,
                        logo = { m -> WorkshopLogo(modifier = m, cornerRadius = 7.dp) },
                        localItems = searchLocalItems(),
                        remoteSearch = { q -> searchRemote(q) },
                        onClose = { searchOpen = false },
                        onError = { showMessage(it) }
                    )
                }
                }
                }
                }

                inlineRecord?.let { request ->
                    DwInlineRecordDialog(
                        repository = repository,
                        model = request.model,
                        recordId = request.recordId,
                        seed = request.seed,
                        crafts = crafts,
                        artisans = artisans,
                        lookupState = lookupState,
                        craftRegister = craftRegister,
                        adminView = adminView,
                        onOutcome = { outcome ->
                            inlineRecord = null
                            if (outcome.saved) {
                                refresh()
                                refreshLookups()
                            }
                            request.onFinished(outcome)
                        },
                        onError = { showError(it) }
                    )
                }

                if (showWalkthrough && pendingUpdate == null) {
                    WalkthroughDialog(
                        user = user,
                        onFinish = { finishWalkthrough() },
                        onOpen = { destination ->
                            finishWalkthrough()
                            navigate(destination)
                        },
                    )
                }

                if (showTerms && pendingUpdate == null) {
                    TermsMenuDialog(onDismiss = { showTerms = false })
                }

                pendingUpdate?.let { release ->
                    AlertDialog(
                        onDismissRequest = { },
                        title = { Text("Update required") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Version ${release.versionName} is required to continue.")
                                release.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                                updateError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                                if (updateBusy) {
                                    FieldLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("Downloading and preparing the installer…", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !updateBusy,
                                onClick = {
                                    updateBusy = true
                                    updateError = null
                                    scope.launch {
                                        runCatching {
                                            if (!canInstallUpdates(context)) {
                                                requestInstallPermission(context)
                                                throw IllegalStateException("Allow “Install unknown apps” for Design Workshop, then tap Update now again.")
                                            }
                                            val apk = repository.downloadApk(
                                                context,
                                                release.url!!,
                                                release.versionCode,
                                                expectedBytes = release.sizeBytes,
                                            )
                                            launchApkInstaller(context, apk)
                                        }.onFailure { updateError = it.message ?: DW_UPDATE_DOWNLOAD_RETRY_MESSAGE }
                                        updateBusy = false
                                    }
                                }
                            ) { Text(if (updateBusy) "Updating…" else "Update now") }
                        }
                    )
                }

                pendingExit?.let { exit ->
                    AlertDialog(
                        onDismissRequest = { pendingExit = null },
                        title = { Text("Unsaved changes") },
                        text = {
                            Text(
                                "You have unsaved changes, including any new media. Save or discard?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                pendingExit = null
                                unsavedGuard.onSave?.invoke()
                            }) { Text("Save") }
                        },
                        dismissButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { pendingExit = null }) { Text("Keep editing") }
                                TextButton(onClick = {
                                    pendingExit = null
                                    unsavedGuard.discard()
                                    exit()
                                }) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    )
                }
            }
            }
        }
    }
}

private fun canInstallUpdates(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

private fun requestInstallPermission(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun launchApkInstaller(context: Context, apk: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private fun openWebPortal(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://designer-repository.vercel.app"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, "No app on this device can open web links.", Toast.LENGTH_LONG).show()
    }
}

private fun searchFocusLabel(recordType: String): String = when (recordType) {
    SearchRecordTypes.ARTISAN -> "artisans"
    SearchRecordTypes.WORKSHOP -> "workshops"
    SearchRecordTypes.PRODUCT -> "products"
    SearchRecordTypes.TOOL -> "tools"
    SearchRecordTypes.MEDIA -> "media"
    else -> "records"
}

private fun searchRecordEntryMode(recordType: String): EntryMode? = when (recordType) {
    SearchRecordTypes.ARTISAN -> EntryMode.ARTISAN
    SearchRecordTypes.WORKSHOP -> EntryMode.WORKSHOP
    SearchRecordTypes.PRODUCT -> EntryMode.PRODUCT
    SearchRecordTypes.TOOL -> EntryMode.TOOL
    SearchRecordTypes.MEDIA -> EntryMode.MEDIA
    DwWorkshopRecordType.CRAFT.wire -> EntryMode.CRAFT
    DwWorkshopRecordType.PROCESS.wire -> EntryMode.PROCESS
    DwWorkshopRecordType.QUESTIONNAIRE.wire -> EntryMode.QUESTIONNAIRE
    SearchRecordTypes.DESIGN_WORKSHOP -> null
    else -> null
}

internal fun unroutableRecordLine(recordType: String): String =
    if (recordType == SearchRecordTypes.DESIGN_WORKSHOP) {
        DESIGN_WORKSHOP_NO_ROUTE_LINE
    } else {
        "Update the app to open this record, or use the web portal."
    }

internal const val DESIGN_WORKSHOP_NO_ROUTE_LINE: String =
    "You can read design-workshop data but can't open a workshop."

internal object SearchDesignWorkshopRoute {
    fun offeredTo(user: UserDto): Boolean = FieldPermissions.canRunDesignWorkshops(user)
}

@Composable
private fun AdminViewHiddenCard(
    label: String,
    blurb: String,
    canToggle: Boolean,
    onEnable: () -> Unit
) {
    val title = if (canToggle) "$label is hidden while admin view is off" else "$label is not part of your role"
    RecordCard(title = title, icon = Icons.Filled.VisibilityOff) {
        Text(
            if (canToggle) {
                "$blurb Admin view is off."
            } else {
                "$blurb These tools are for admins."
            },
            color = Body,
            style = MaterialTheme.typography.bodyMedium
        )
        if (canToggle) {
            Text(
                "Your access hasn't changed — this is just your view setting.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
                Text("Turn admin view back on")
            }
        }
    }
}

@Composable
private fun DataBrowserEntryCard(onOpen: () -> Unit) {
    RecordCard(title = "Data Browser", icon = Icons.Filled.Storage) {
        Text(
            "Browse records as folders, preview media, and download as zip.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Storage, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open the Data Browser")
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: WorkshopRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("REFINED_TRANSLATED") }
    var windowEnabled by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf("02:00") }
    var endTime by remember { mutableStateOf("05:00") }

    var loadError by remember { mutableStateOf<String?>(null) }
    var loadAttempt by remember { mutableStateOf(0) }
    LaunchedEffect(loadAttempt) {
        loading = true
        loadError = null
        runCatching { repository.appSettings() }
            .onSuccess { s ->
                mode = s.transcriptionMode
                windowEnabled = s.batchWindowEnabled
                startTime = s.batchWindowStart
                endTime = s.batchWindowEnd
            }
            .onFailure { loadError = it.userMessage("Couldn't load the settings.") }
        loading = false
    }

    RecordCard(title = "Settings") {
        if (loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading settings…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            return@RecordCard
        }
        loadError?.let { reason ->
            LoadFailedCard(
                message = "$reason Settings can't be saved until they load.",
                onRetry = { loadAttempt++ }
            )
            return@RecordCard
        }
        Text("Transcription output", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "How audio becomes text after upload. AI refinement always waits for your approval.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Column(modifier = Modifier.selectableGroup()) {
        SettingsRadioRow("Raw transcript only", "Fastest and cheapest.", mode == "RAW") { mode = "RAW" }
        SettingsRadioRow("Refined transcript", "Cleaned into a readable dialogue.", mode == "REFINED") { mode = "REFINED" }
        SettingsRadioRow("Refined + translated to English", "Refined, then translated to English (default).", mode == "REFINED_TRANSLATED") { mode = "REFINED_TRANSLATED" }
        }

        HorizontalDivider()
        AppToggle(
            label = "Process during an off-peak window",
            description = "When on, processing runs only in the window below (IST). When off, it runs immediately.",
            checked = windowEnabled,
            onCheckedChange = { windowEnabled = it }
        )
        if (windowEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) { TimePickerField("Start", startTime) { startTime = it } }
                Box(modifier = Modifier.weight(1f)) { TimePickerField("End", endTime) { endTime = it } }
            }
            Text("India Standard Time.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = {
                scope.launch {
                    saving = true
                    runCatching {
                        repository.updateAppSettings(
                            AppSettingUpdateRequest(
                                transcriptionMode = mode,
                                batchWindowEnabled = windowEnabled,
                                batchWindowStart = startTime,
                                batchWindowEnd = endTime
                            )
                        )
                    }.onSuccess {
                        mode = it.transcriptionMode
                        windowEnabled = it.batchWindowEnabled
                        startTime = it.batchWindowStart
                        endTime = it.batchWindowEnd
                        onMessage("Settings saved")
                    }.onFailure { onError(it.userMessage("Couldn't save settings")) }
                    saving = false
                }
            },
            enabled = !saving,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (saving) "Saving…" else "Save settings")
        }
    }
}

@Composable
private fun SettingsRadioRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val parts = remember(value) { value.split(":").mapNotNull { it.toIntOrNull() } }
    val hour = parts.getOrNull(0)?.coerceIn(0, 23) ?: 0
    val minute = parts.getOrNull(1)?.coerceIn(0, 59) ?: 0
    OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
        Text("$label: $value")
    }
    if (show) {
        val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    onChange("%02d:%02d".format(state.hour, state.minute))
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) }
        )
    }
}

@Composable
private fun CarryForwardPanel(
    repository: WorkshopRepository,
    prefill: Prefill,
    canCreateTool: Boolean,
    onSelect: (EntryMode) -> Unit,
    onDismiss: () -> Unit
) {
    val appContext = LocalContext.current.applicationContext
    LaunchedEffect(prefill) {
        CarryContextStore(appContext).remember(repository.cachedUser()?.id, prefill.toCarryContext())
    }
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Saved ${prefill.artisanName ?: "artisan"} ✓", display = true, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("Continue for the same artisan — details are pre-filled.", color = Muted, style = MaterialTheme.typography.bodySmall)
            ActionRow {
                Button(onClick = { onSelect(EntryMode.PRODUCT) }) { Text("Add product") }
                if (canCreateTool) {
                    Button(onClick = { onSelect(EntryMode.TOOL) }) { Text("Add tool") }
                }
            }
            ActionRow {
                OutlinedButton(onClick = { onSelect(EntryMode.QUESTIONNAIRE) }) { Text("Take interview") }
                OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
            }
        }
    }
}

internal object DesignWorkshopCard {
    const val LABEL = "Design workshop"

    const val PRIMARY_LABEL = "New workshop"

    fun visibleTo(user: UserDto): Boolean = FieldPermissions.canRunDesignWorkshops(user)
}

internal object SketchesAndPrototypesCard {
    const val LABEL = "Sketches & prototypes"

    const val PRIMARY_LABEL = "Open"

    fun visibleTo(user: UserDto): Boolean = FieldPermissions.canRunDesignWorkshops(user)
}

internal object DesignReviewCard {
    const val LABEL = "Design review"

    const val PRIMARY_LABEL = "Open"

    fun visibleTo(user: UserDto): Boolean = FieldPermissions.canRunDesignWorkshops(user)
}

internal object ScanCodeCard {
    const val LABEL = "Scan a code"

    const val PRIMARY_LABEL = "Open"
}

internal object DesignerProfileCard {
    const val LABEL = "My designer profile"

    const val PRIMARY_LABEL = "Open"

    fun visibleTo(user: UserDto): Boolean = FieldPermissions.canRunDesignWorkshops(user)
}

@Composable
private fun DashboardScreen(
    stats: DashboardStats?,
    recentArtisans: List<ArtisanDto>,
    actions: List<EntryMode>,
    roleLabel: String,
    canCreateRecords: Boolean,
    showDesignWorkshop: Boolean = false,
    onNewDesignWorkshop: () -> Unit = {},
    onOpenDesignWorkshops: () -> Unit = {},
    showSketchesAndPrototypes: Boolean = false,
    onOpenSketchesAndPrototypes: () -> Unit = {},
    showDesignReview: Boolean = false,
    onOpenDesignReview: () -> Unit = {},
    showDesignerProfile: Boolean = false,
    onOpenDesignerProfile: () -> Unit = {},
    onOpenScanCode: () -> Unit = {},
    showAdminHub: Boolean = false,
    onOpenAdminHub: () -> Unit = {},
    onWalkthrough: () -> Unit = {},
    onNew: (EntryMode) -> Unit,
    onUpdateExisting: (EntryMode) -> Unit,
    onOpenSearchFor: (String) -> Unit,
    onOpenReviews: (() -> Unit)?,
    onOpenMyActivity: () -> Unit,
    onOpenRecord: (EntryMode, String) -> Unit,
    onOpenArtisan: (String) -> Unit
) {
    val configuration = LocalConfiguration.current
    val megaColumns = dashboardMegaColumns(configuration.screenWidthDp)
    val innerColumns = dashboardInnerColumns(configuration.screenWidthDp)
    val tiles = buildList {
        if (showDesignWorkshop) {
            add(
                DashboardTile(
                    label = DesignWorkshopCard.LABEL,
                    icon = Icons.Filled.DesignServices,
                    primaryLabel = DesignWorkshopCard.PRIMARY_LABEL,
                    onPrimary = onNewDesignWorkshop,
                    onUpdate = onOpenDesignWorkshops,
                    group = DashGroup.DESIGNERS
                )
            )
        }
        if (showSketchesAndPrototypes) {
            add(
                DashboardTile(
                    label = SketchesAndPrototypesCard.LABEL,
                    icon = Icons.Filled.Architecture,
                    primaryLabel = SketchesAndPrototypesCard.PRIMARY_LABEL,
                    onPrimary = onOpenSketchesAndPrototypes,
                    group = DashGroup.DESIGNERS
                )
            )
        }
        if (showDesignReview) {
            add(
                DashboardTile(
                    label = DesignReviewCard.LABEL,
                    icon = Icons.Filled.Star,
                    primaryLabel = DesignReviewCard.PRIMARY_LABEL,
                    onPrimary = onOpenDesignReview,
                    group = DashGroup.DESIGNERS
                )
            )
        }
        actions.forEach { entry ->
            add(
                DashboardTile(
                    label = entry.label,
                    icon = entry.icon(),
                    primaryLabel = entry.createButtonLabel(),
                    onPrimary = { onNew(entry) },
                    onUpdate = if (entry.editable) ({ onUpdateExisting(entry) }) else null,
                    group = entry.group ?: DashGroup.MISC
                )
            )
            if (showDesignerProfile && entry == EntryMode.WORKSHOP_ACCESS) {
                add(
                    DashboardTile(
                        label = DesignerProfileCard.LABEL,
                        icon = Icons.Filled.Badge,
                        primaryLabel = DesignerProfileCard.PRIMARY_LABEL,
                        onPrimary = onOpenDesignerProfile,
                        group = DashGroup.DESIGNERS
                    )
                )
            }
            if (entry == EntryMode.MEDIA) {
                add(
                    DashboardTile(
                        label = ScanCodeCard.LABEL,
                        icon = Icons.Filled.QrCodeScanner,
                        primaryLabel = ScanCodeCard.PRIMARY_LABEL,
                        onPrimary = onOpenScanCode,
                        group = DashGroup.MISC
                    )
                )
            }
        }
        if (showAdminHub) {
            add(
                DashboardTile(
                    label = "Settings",
                    icon = Icons.Filled.Tune,
                    primaryLabel = "Open",
                    onPrimary = onOpenAdminHub,
                    group = DashGroup.ADMIN
                )
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text("What would you like to do?", display = true, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        var openGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
        val reduceMotion = LocalAppPreferences.current.reducedMotion
        val filled = DashGroup.entries
            .map { group -> group to tiles.filter { it.group == group } }
            .filter { (_, members) -> members.isNotEmpty() }
        filled.chunked(megaColumns).forEach { rowGroups ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(DASH_GRID_GAP_DP.dp),
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth()
            ) {
                rowGroups.forEach { (group, members) ->
                    DashboardMegaCard(
                        group = group,
                        members = members,
                        innerColumns = innerColumns,
                        expanded = group.name in openGroups,
                        reduceMotion = reduceMotion,
                        onToggle = {
                            openGroups =
                                if (group.name in openGroups) openGroups - group.name
                                else openGroups + group.name
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(megaColumns - rowGroups.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        val filedCount = filled.sumOf { (_, members) -> members.size }
        if (filedCount != tiles.size) {
            Text(
                "${tiles.size - filedCount} more in the menu.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 17.sp
            )
        }
        if (!canCreateRecords) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You're signed in as $roleLabel: you can answer interviews, upload media and comment. Creating records needs Researcher access — ask an admin.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    ActionRow {
                        TextButton(onClick = onWalkthrough, contentPadding = PaddingValues(0.dp)) {
                            Text("Open the walkthrough")
                        }
                    }
                }
            }
        }
        StatsCard(stats = stats, onOpenSearchFor = onOpenSearchFor, onOpenReviews = onOpenReviews)
        MyContributionCard(mine = stats?.mine, onOpenMyActivity = onOpenMyActivity)
        RecentSubmissionsCard(stats = stats, onOpenRecord = onOpenRecord)
        if (recentArtisans.isNotEmpty()) {
            Text("Recent artisans", display = true, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            recentArtisans.take(6).forEach { artisan ->
                val interaction = remember(artisan.id) { MutableInteractionSource() }
                val pressed by interaction.collectIsPressedAsState()
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = if (pressed) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f).compositeOver(SurfaceCard)
                        } else {
                            SurfaceCard
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interaction,
                                indication = LocalIndication.current,
                                onClickLabel = RecordOpen.openClickLabel(artisan.name),
                                role = Role.Button
                            ) { onOpenArtisan(artisan.id) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = "${artisan.name}. Opens this artisan."
                            }
                            .padding(12.dp)
                    ) {
                        Text(artisan.name, display = true, fontWeight = FontWeight.SemiBold)
                        Text("${artisan.craft?.name ?: "No craft"} · ${artisan.place}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun EntryMode.createButtonLabel(): String = when (this) {
    EntryMode.MEDIA -> "Upload"
    EntryMode.QUESTIONNAIRE -> "New interview"
    EntryMode.USERS -> "Manage"
    EntryMode.VIEW_DATA -> "Open"
    EntryMode.TASKS -> "Open"
    EntryMode.WORKSHOP_ACCESS -> "Open"
    EntryMode.SEARCH, EntryMode.DATA_BROWSER -> "Open"
    EntryMode.MAP, EntryMode.CONSOLIDATED_QUESTIONNAIRE -> "Open"
    else -> "New"
}

internal fun dashboardTileClickLabel(primaryLabel: String, label: String): String = "$primaryLabel $label"

internal fun dashboardPrimaryIcon(primaryLabel: String): ImageVector =
    if (primaryLabel == "Open" || primaryLabel == "Manage") Icons.AutoMirrored.Filled.ArrowForward
    else Icons.Filled.Add

private class DashboardTile(
    val label: String,
    val icon: ImageVector,
    val primaryLabel: String,
    val onPrimary: () -> Unit,
    val onUpdate: (() -> Unit)? = null,
    val group: DashGroup
) {
    val primaryIcon: ImageVector = dashboardPrimaryIcon(primaryLabel)
}

@Composable
private fun DashboardActionCard(tile: DashboardTile, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.large
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = shape,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .openable(title = tile.label, shape = shape, clickLabel = dashboardTileClickLabel(tile.primaryLabel, tile.label)) { tile.onPrimary() }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color = MaterialTheme.field.brandTile, shape = MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    tile.icon,
                    contentDescription = null,
                    tint = MaterialTheme.field.onBrandTile,
                    modifier = Modifier.size(22.dp)
                )
            }
            Text(
                tile.label,
                display = true,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = tile.onPrimary,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
            ) { CardButtonLabel(tile.primaryIcon, tile.primaryLabel) }
            tile.onUpdate?.let { update ->
                OutlinedButton(
                    onClick = update,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) { CardButtonLabel(Icons.Filled.Edit, "Update") }
            }
        }
    }
}

@Composable
private fun CardButtonLabel(icon: ImageVector, text: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
}

internal fun dashboardMegaColumns(screenWidthDp: Int): Int = if (screenWidthDp >= 840) 2 else 1

internal fun dashboardInnerColumns(screenWidthDp: Int): Int {
    val mega = dashboardMegaColumns(screenWidthDp)
    val megaWidthDp = (screenWidthDp - 2 * DASH_PAGE_GUTTER_DP - (mega - 1) * DASH_GRID_GAP_DP) / mega
    return if (megaWidthDp - 2 * DASH_MEGA_PAD_DP >= 2 * DASH_MIN_CARD_DP + DASH_GRID_GAP_DP) 2 else 1
}

internal const val DASH_PAGE_GUTTER_DP = 16
internal const val DASH_GRID_GAP_DP = 12
internal const val DASH_MEGA_PAD_DP = 14
internal const val DASH_MIN_CARD_DP = 140

@Composable
private fun DashboardMegaCard(
    group: DashGroup,
    members: List<DashboardTile>,
    innerColumns: Int,
    expanded: Boolean,
    reduceMotion: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = group.accent()
    val countLabel = if (members.size == 1) "1 card" else "${members.size} cards"
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(DASH_MEGA_PAD_DP.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Hide ${group.title}" else "Show ${group.title}",
                        onClick = onToggle
                    )
                    .heightIn(min = 48.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(color = accent.chip, shape = MaterialTheme.shapes.medium),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        group.icon(),
                        contentDescription = null,
                        tint = accent.ink,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        group.title,
                        display = true,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        group.note,
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 15.sp
                    )
                    Text(
                        countLabel,
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription =
                        if (expanded) "Hide the $countLabel under ${group.title}"
                        else "Show the $countLabel under ${group.title}",
                    tint = MaterialTheme.field.muted
                )
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = accent.ink)
            AnimatedVisibility(
                visible = expanded,
                enter = if (reduceMotion) {
                    expandVertically(snap(), expandFrom = Alignment.Top) + fadeIn(snap())
                } else {
                    expandVertically(tween(220), expandFrom = Alignment.Top) + fadeIn(tween(180))
                },
                exit = if (reduceMotion) {
                    shrinkVertically(snap(), shrinkTowards = Alignment.Top) + fadeOut(snap())
                } else {
                    shrinkVertically(tween(180), shrinkTowards = Alignment.Top) + fadeOut(tween(120))
                },
                label = "dashboard-mega-${group.name}"
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(DASH_GRID_GAP_DP.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = DASH_GRID_GAP_DP.dp)
                ) {
                    members.chunked(innerColumns).forEach { rowItems ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(DASH_GRID_GAP_DP.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min)
                        ) {
                            rowItems.forEach { tile ->
                                DashboardActionCard(
                                    tile = tile,
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                )
                            }
                            repeat(innerColumns - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    stats: DashboardStats?,
    onOpenSearchFor: (String) -> Unit,
    onOpenReviews: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.field.brandTile),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text("At a glance", display = true, color = MaterialTheme.field.onBrandTile, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Across the whole portal.",
                color = MaterialTheme.field.onBrandTileMuted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            if (stats == null) {
                Text("Loading...", color = MaterialTheme.field.onBrandTileMuted)
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Stat(
                        "Artisans", stats.totalArtisans, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.ARTISAN) },
                        openDescription = "Opens a search of the artisans"
                    )
                    Stat(
                        "Workshops", stats.totalWorkshops, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.WORKSHOP) },
                        openDescription = "Opens a search of the workshops"
                    )
                    Stat(
                        "Products", stats.totalProductRecords, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.PRODUCT) },
                        openDescription = "Opens a search of the products"
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    Stat(
                        "Tools", stats.totalToolRecords, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.TOOL) },
                        openDescription = "Opens a search of the tools"
                    )
                    Stat(
                        "Media files", stats.totalMediaFiles, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = { onOpenSearchFor(SearchRecordTypes.MEDIA) },
                        openDescription = "Opens a search of the media"
                    )
                    Stat(
                        "Pending review", stats.pendingSubmissions, Modifier.weight(1f).fillMaxHeight(),
                        onOpen = onOpenReviews,
                        openDescription = "Opens reviews and approvals"
                    )
                }
            }
        }
    }
}

@Composable
private fun MyContributionCard(mine: DashboardStatsMine?, onOpenMyActivity: () -> Unit) {
    if (mine == null) return
    val tiles = listOf(
        Triple("Artisans", mine.totalArtisans, Icons.Filled.Person),
        Triple("Workshops", mine.totalWorkshops, Icons.Filled.Groups),
        Triple("Products", mine.totalProductRecords, Icons.Filled.Inventory2),
        Triple("Tools", mine.totalToolRecords, Icons.Filled.Build),
        Triple("Media files", mine.totalMediaFiles, Icons.Filled.PermMedia),
        Triple("Awaiting review", mine.pendingSubmissions, Icons.Filled.RateReview)
    )
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Your contribution",
                    display = true,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                TextButton(onClick = onOpenMyActivity, contentPadding = PaddingValues(0.dp)) {
                    Text("Open My Activity", style = MaterialTheme.typography.labelMedium)
                }
            }
            tiles.chunked(3).forEach { rowTiles ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                ) {
                    rowTiles.forEach { (label, value, icon) ->
                        MineStat(
                            label = label,
                            value = value,
                            icon = icon,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onOpen = onOpenMyActivity
                        )
                    }
                    repeat(3 - rowTiles.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MineStat(
    label: String,
    value: Int,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(
                MaterialTheme.colorScheme.primary
                    .copy(alpha = if (pressed) 0.16f else 0.07f)
                    .compositeOver(Canvas)
            )
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClickLabel = "Opens My Activity"
            ) { onOpen() }
            .semantics(mergeDescendants = true) {
                contentDescription = "Your ${label.lowercase()}: $value. Opens My Activity."
            }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                label,
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            value.toString(),
            display = true,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RecentSubmissionsCard(stats: DashboardStats?, onOpenRecord: (EntryMode, String) -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Recent submissions",
                display = true,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Newest entries across the portal.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            when {
                stats == null -> Text("Loading...", color = Muted, style = MaterialTheme.typography.bodySmall)
                stats.recentSubmissions.isEmpty() -> {
                    Text(
                        "No submissions yet",
                        display = true,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "New records will appear here.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                else -> stats.recentSubmissions.forEach { item ->
                    RecentSubmissionRow(item = item, onOpenRecord = onOpenRecord)
                }
            }
        }
    }
}

@Composable
private fun RecentSubmissionRow(
    item: DashboardRecentSubmissionDto,
    onOpenRecord: (EntryMode, String) -> Unit
) {
    val mode = remember(item.type) { recentSubmissionEntryMode(item.type) }
    val typeLabel = item.type.replaceFirstChar { it.uppercase() }
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: "Untitled ${item.type.ifBlank { "record" }}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(
                if (mode == null) Modifier
                else Modifier.clickable(onClickLabel = RecordOpen.openClickLabel(title), role = Role.Button) {
                    onOpenRecord(mode, item.id)
                }
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            title,
            display = true,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
            color = if (mode == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        val place = item.place?.takeIf { it.isNotBlank() }
        Text(
            listOfNotNull(typeLabel.takeIf { it.isNotBlank() }, place).joinToString(" · ").ifBlank { "Record" },
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Text(
            "Recorded by ${item.createdByName?.takeIf { it.isNotBlank() } ?: "-"}" +
                " · ${item.status.ifBlank { "-" }}" +
                " · ${formatIsoDate(item.createdAt) ?: "-"}",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun recentSubmissionEntryMode(type: String): EntryMode? = when (type.lowercase()) {
    "artisan" -> EntryMode.ARTISAN
    "product" -> EntryMode.PRODUCT
    "process" -> EntryMode.PROCESS
    "tool" -> EntryMode.TOOL
    "workshop" -> EntryMode.WORKSHOP
    "craft" -> EntryMode.CRAFT
    "questionnaire", "interview" -> EntryMode.QUESTIONNAIRE
    "media" -> EntryMode.MEDIA
    else -> null
}

@Composable
private fun Stat(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    openDescription: String? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = MaterialTheme.shapes.medium
    Column(
        modifier = modifier
            .clip(shape)
            .background(color = MaterialTheme.field.accentOnBrandTile.copy(alpha = if (pressed) 0.34f else 0.18f))
            .then(
                if (onOpen == null) Modifier else Modifier
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClickLabel = openDescription
                    ) { onOpen() }
                    .semantics(mergeDescendants = true) {
                        contentDescription = listOfNotNull("$label: $value", openDescription).joinToString(". ")
                    }
            )
            .padding(12.dp)
    ) {
        Text(value.toString(), display = true, color = MaterialTheme.field.onBrandTile, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(label, color = MaterialTheme.field.onBrandTileMuted, style = MaterialTheme.typography.bodySmall)
    }
}

private val productTypeOptions = listOf("FINISHED_GOOD", "SAMPLE", "RAW_MATERIAL", "COMPONENT", "PACKAGING", "OTHER")
private val marketDemandOptions = listOf("LOW", "MEDIUM", "HIGH", "SEASONAL", "UNKNOWN")
private val makerOptions = listOf("ARTISAN", "LOCAL_BLACKSMITH", "CARPENTER", "WORKSHOP", "FACTORY", "UNKNOWN", "OTHER")
private val statusOptions = listOf("DRAFT", "PENDING", "APPROVED", "REJECTED")

private val workshopTypeOptions = listOf(
    "DESIGN_PROTOTYPE" to "Design & Prototype Development Workshop",
    "OTHER" to "Other workshop"
)

private val ROLE_RANK = mapOf(
    "CROWDSOURCE_VOLUNTEER" to 10,
    "FIELD_CONTRIBUTOR" to 20,
    "RESEARCHER" to 30,
    "DESIGNER" to 35,
    "INSPECTOR" to 37,
    "PROFESSOR" to 40,
    "ASSISTANT_DIRECTOR" to 42,
    "REGIONAL_DIRECTOR" to 45,
    "MINISTRY_ADMIN" to 48,
    "ADMIN" to 50,
    "MASTER_ADMIN" to 60
)
private const val RANK_FIELD_CONTRIBUTOR = 20
private const val RANK_RESEARCHER = 30
private const val RANK_PROFESSOR = 40
private const val RANK_MINISTRY_ADMIN = 48
private const val RANK_ADMIN = 50

private val ROLE_LABELS = mapOf(
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

private fun roleLabel(role: String?): String = ROLE_LABELS[role] ?: role.orEmpty()
private fun roleRank(role: String?): Int = ROLE_RANK[role] ?: 0
private fun canSetRecordStatus(role: String?): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun UserDto.isAdminUser(): Boolean = roleRank(role) >= RANK_ADMIN

private fun UserDto.isMasterAdminUser(): Boolean = role == "MASTER_ADMIN"

private fun UserDto.canCreateRecords(): Boolean = roleRank(role) >= RANK_RESEARCHER

private fun UserDto.canManageDesignerRoster(): Boolean = roleRank(role) >= RANK_ADMIN

private fun UserDto.canAccessReview(): Boolean = roleRank(role) >= RANK_FIELD_CONTRIBUTOR || canReview

private fun UserDto.canDownloadTheDataset(): Boolean = roleRank(role) >= RANK_PROFESSOR || canDownloadDataset

private fun UserDto.canManageTheQuestionnaire(): Boolean =
    roleRank(role) >= RANK_PROFESSOR || canManageQuestionnaire

private fun UserDto.canManageTheCrafts(): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun UserDto.canOpenAWorkshop(): Boolean = roleRank(role) >= RANK_MINISTRY_ADMIN

private fun UserDto.canManageUsers(): Boolean = roleRank(role) >= RANK_PROFESSOR

private fun defaultCreateStatus(role: String?): String = if (canSetRecordStatus(role)) "APPROVED" else "PENDING"
private val genderOptions = StandardValues.GENDER_LABELS

internal class UnsavedGuard {
    var dirty by mutableStateOf(false)
    var onSave: (() -> Unit)? = null
    var onDiscard: (() -> Unit)? = null

    fun clear() {
        dirty = false
        onSave = null
        onDiscard = null
    }

    fun discard() {
        runCatching { onDiscard?.invoke() }
        clear()
    }
}

private val LocalUnsavedGuard = staticCompositionLocalOf<UnsavedGuard?> { null }

@Composable
internal fun RegisterUnsavedGuard(dirty: Boolean, draft: FormDraftHandle?, onSave: () -> Unit) {
    val guard = LocalUnsavedGuard.current ?: return
    val currentSave by rememberUpdatedState(onSave)
    val currentDraft by rememberUpdatedState(draft)
    LaunchedEffect(dirty) { guard.dirty = dirty }
    DisposableEffect(Unit) {
        guard.onSave = { currentSave() }
        guard.onDiscard = { currentDraft?.discard() }
        onDispose { guard.clear() }
    }
}

private class MediaCaptureState {
    var uris by mutableStateOf<List<Uri>>(emptyList())
    var location by mutableStateOf<LocationRequest?>(null)

    val stagedDeferred = mutableMapOf<Uri, Deferred<StagedMedia?>>()
    var staged by mutableStateOf<Map<Uri, StagedMedia>>(emptyMap())
    var stagedProgress by mutableStateOf<Map<Uri, Float>>(emptyMap())
    var stagedFailed by mutableStateOf<Set<Uri>>(emptySet())

    var purposes by mutableStateOf<Map<Uri, String>>(emptyMap())

    fun forget(uri: Uri) {
        stagedDeferred.remove(uri)
        staged = staged - uri
        stagedProgress = stagedProgress - uri
        stagedFailed = stagedFailed - uri
        purposes = purposes - uri
        TranscribeChoices.forget(uri.toString())
    }

    fun reset() {
        uris = emptyList()
        location = null
        stagedDeferred.clear()
        staged = emptyMap()
        stagedProgress = emptyMap()
        stagedFailed = emptySet()
        purposes = emptyMap()
    }
}

@Composable
private fun rememberMediaCaptureState(): MediaCaptureState = remember { MediaCaptureState() }

private fun MediaCaptureState.draftFields(slot: String): Map<String, String> = mapOf(
    "${com.designprototype.workshop.data.URI_FIELD_PREFIX}$slot" to uris.joinToString("\n"),
    "purposes:$slot" to purposes.entries.joinToString("\n") { "${it.key}\t${it.value}" },
    "transcribe:$slot" to uris.filter { TranscribeChoices.isOn(it.toString()) }.joinToString("\n"),
    "location:$slot" to (location?.let { offlineFormJson.encodeToString(it) } ?: ""),
)

private fun MediaCaptureState.restoreDraft(slot: String, values: Map<String, String>) {
    values["${com.designprototype.workshop.data.URI_FIELD_PREFIX}$slot"]?.let { text ->
        uris = com.designprototype.workshop.data.formDraftUriList(text).map(Uri::parse)
    }
    values["purposes:$slot"]?.let { text ->
        purposes = text.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) Uri.parse(parts[0]) to parts[1] else null
        }.filter { it.first in uris }.toMap()
    }
    values["transcribe:$slot"]?.let { text ->
        val ticked = text.split('\n').filter { it.isNotBlank() }.toSet()
        uris.forEach { uri -> if (uri.toString() in ticked) TranscribeChoices.set(uri.toString(), true) }
    }
    values["location:$slot"]?.let { text ->
        location = text.takeIf { it.isNotBlank() }?.let {
            runCatching { offlineFormJson.decodeFromString<LocationRequest>(it) }.getOrNull()
        }
    }
}

private fun startEagerUpload(
    repository: WorkshopRepository,
    context: Context,
    media: MediaCaptureState,
    uri: Uri,
    uiScope: CoroutineScope
) {
    media.stagedDeferred.remove(uri)
    media.staged = media.staged - uri
    media.stagedFailed = media.stagedFailed - uri
    media.stagedProgress = media.stagedProgress + (uri to 0f)
    val deferred = AppScope.io.async {
        var lastPct = -1
        runCatching {
            repository.preuploadObject(context, uri) { sent, total ->
                val pct = if (total > 0L) ((sent * 100) / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    media.stagedProgress = media.stagedProgress + (uri to pct / 100f)
                }
            }
        }.getOrNull()
    }
    media.stagedDeferred[uri] = deferred
    uiScope.launch {
        val result = runCatching { deferred.await() }.getOrNull()
        if (result != null) {
            media.staged = media.staged + (uri to result)
            media.stagedProgress = media.stagedProgress + (uri to 1f)
            media.stagedFailed = media.stagedFailed - uri
        } else {
            media.stagedFailed = media.stagedFailed + uri
        }
    }
}

private fun LocalDate.toIsoInstant(): String =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

private fun parseIsoToLocalDate(value: String?): LocalDate? {
    if (value.isNullOrBlank()) return null
    return runCatching { java.time.OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { Instant.parse(value).atZone(ZoneId.systemDefault()).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

private fun formatIsoDate(value: String?): String? {
    val date = parseIsoToLocalDate(value) ?: return null
    return runCatching {
        date.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"))
    }.getOrNull()
}

private fun formatIsoDay(value: String?, daysBefore: Long = 0): String {
    val date = parseIsoToLocalDate(value) ?: return value.orEmpty()
    return formatIsoDate(date.minusDays(daysBefore).toString()) ?: value.orEmpty()
}

private fun numToText(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    val number = raw.toDoubleOrNull() ?: return raw
    return if (number % 1.0 == 0.0) number.toLong().toString() else raw
}

private fun numToText(value: Double?): String {
    if (value == null) return ""
    return if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}

private fun locationForBody(isEdit: Boolean, current: LocationRequest?, original: LocationDto?): LocationRequest? {
    if (!isEdit) return current
    if (current == null) return null
    if (original != null &&
        kotlin.math.abs(current.latitude - original.latitude) < 1e-6 &&
        kotlin.math.abs(current.longitude - original.longitude) < 1e-6 &&
        current.state == original.state &&
        current.pincode == original.pincode &&
        current.district == original.district &&
        current.village == original.village &&
        current.subjectLatitude == original.subjectLatitude &&
        current.subjectLongitude == original.subjectLongitude &&
        current.extraMetadata == original.extraMetadata
    ) {
        return null
    }
    return current
}

private fun newRecordLocationError(isEdit: Boolean, current: LocationRequest?): String? =
    if (isEdit) null else artisanLocationRequirementError(current)

private fun LocationDto.toRequest(): LocationRequest =
    LocationRequest(
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        accuracy = accuracy,
        address = address,
        placeName = placeName ?: address,
        state = state,
        district = district,
        village = village,
        pincode = pincode,
        subjectLatitude = subjectLatitude,
        subjectLongitude = subjectLongitude,
        capturedAt = capturedAt,
        extraMetadata = extraMetadata
    )

internal const val PINCODE_LENGTH = 6

internal fun pincodeValidationError(value: String?): String? {
    val digits = value?.trim().orEmpty()
    if (digits.isEmpty()) return null
    if (!digits.all { it in '0'..'9' }) return "Pincode must be 6 digits — remove any letters or symbols."
    if (digits.length != PINCODE_LENGTH) return "Pincode must be exactly 6 digits (this one has ${digits.length})."
    if (digits[0] == '0') return "Pincodes never start with 0 — please re-check the first digit."
    return null
}

private fun foldStateName(value: String): String =
    value.lowercase().replace("&", "and").filter { it in 'a'..'z' || it in '0'..'9' }

internal fun matchIndianState(text: String, states: List<String>): String {
    val wanted = foldStateName(text)
    if (wanted.isEmpty()) return ""
    states.firstOrNull { foldStateName(it) == wanted }?.let { return it }
    return states.firstOrNull { entry ->
        val name = foldStateName(entry)
        (name.length >= 5 && wanted.contains(name)) || (wanted.length >= 5 && name.contains(wanted))
    }.orEmpty()
}

@Composable
private fun LocationAddressEditor(
    repository: WorkshopRepository,
    value: LocationRequest?,
    @Suppress("UNUSED_PARAMETER") onUseGps: () -> LocationRequest?,
    onChange: (LocationRequest?) -> Unit,
    onMessage: (String) -> Unit = {},
    required: Boolean = true,
    isEdit: Boolean = false,
    showRequirementError: Boolean = false
) {
    LocationFieldsSection(
        repository = repository,
        value = value,
        onChange = onChange,
        required = required,
        isEdit = isEdit,
        showRequirementError = showRequirementError,
        onMessage = onMessage
    )
}

internal fun sameCoordinate(next: LocationRequest, previous: LocationRequest?): Boolean =
    previous != null &&
        kotlin.math.abs(next.latitude - previous.latitude) < 1e-6 &&
        kotlin.math.abs(next.longitude - previous.longitude) < 1e-6

private suspend fun uploadAttachments(
    repository: WorkshopRepository,
    context: Context,
    media: MediaCaptureState,
    recordType: String,
    recordId: String,
    titleHint: String?,
    caption: String?,
    customSegment: String? = null
) {
    val failures = mutableListOf<String>()
    val stranded = mutableListOf<IndexedValue<Uri>>()
    media.uris.forEachIndexed { index, uri ->
        val staged = media.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() } ?: media.staged[uri]
        val purpose = mediaPurposeMetadata(media.purposes[uri])
        val result = runCatching {
            if (staged != null) {
                repository.completeStaged(
                    staged = staged,
                    linkedRecordType = recordType,
                    linkedRecordId = recordId,
                    recordName = titleHint,
                    caption = caption,
                    location = media.location,
                    batchIndex = index + 1,
                    customSegment = customSegment,
                    processingRequests = TranscribeChoices.processingFor(uri.toString(), staged.mediaType),
                    extraMetadata = purpose
                )
            } else {
                repository.uploadMedia(
                    context = context,
                    uri = uri,
                    linkedRecordType = recordType,
                    linkedRecordId = recordId,
                    caption = caption,
                    location = media.location,
                    titleHint = titleHint,
                    batchIndex = index + 1,
                    customSegment = customSegment,
                    extraMetadata = purpose
                )
            }
        }
        if (result.isFailure) {
            val error = result.exceptionOrNull()
            if (error is kotlinx.coroutines.CancellationException) throw error
            failures.add(uri.lastPathSegment ?: "file ${index + 1}")
            stranded.add(IndexedValue(index, uri))
        }
    }
    if (failures.isNotEmpty()) {
        val rescued = runCatching {
            repository.queueMediaForSavedRecord(
                context = context,
                recordType = recordType,
                recordId = recordId,
                label = titleHint?.takeIf { it.isNotBlank() } ?: "Attachments for a saved record",
                items = stranded.map { (index, uri) ->
                    com.designprototype.workshop.data.OfflineMediaSpec(
                        uri = uri,
                        caption = caption,
                        recordName = titleHint,
                        customSegment = customSegment,
                        batchIndex = index + 1,
                        purpose = media.purposes[uri],
                    )
                },
            )
        }.getOrNull()

        val allFailed = failures.size == media.uris.size
        val prefix = if (allFailed) "All ${plural(failures.size, "file")} failed to upload" else "${plural(failures.size, "file")} failed to upload"
        throw IllegalStateException(
            when {
                rescued != null && rescued.allFilesQueued ->
                    "$prefix (${failures.joinToString(", ")}). The record is saved; the files are kept here and attach automatically when online."
                rescued != null ->
                    "$prefix (${failures.joinToString(", ")}). Saved; ${rescued.queuedFiles} will attach when online, but ${rescued.unreadableFiles.size} couldn't be read (${rescued.unreadableFiles.joinToString(", ")}). Retake those."
                else ->
                    "$prefix (${failures.joinToString(", ")}). The record is saved, but the files couldn't be kept (storage full?). Re-attach them before leaving."
            }
        )
    }
}

@Composable
private fun GridMeasurementSection(
    repository: WorkshopRepository,
    media: MediaCaptureState,
    includeHeight: Boolean = true,
    onLengthBreadth: (length: Double?, breadth: Double?, marker: JsonObject) -> Unit,
    onHeight: (value: Double, marker: JsonObject) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(setOf<String>()) }
    var capturedUris by remember { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var status by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var pendingGroup by remember { mutableStateOf<String?>(null) }
    val gridCamera = rememberPermissionGate { sentence -> pendingGroup?.let { status = status + (it to sentence) } }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var proposedLengthBreadth by remember { mutableStateOf<DwMeasurementReading?>(null) }
    var proposedHeight by remember { mutableStateOf<DwMeasurementReading?>(null) }

    fun analyze(group: String, uri: Uri) {
        capturedUris[group]?.let { previous ->
            if (previous != uri) {
                val deferred = media.stagedDeferred[previous]
                media.forget(previous)
                media.uris = media.uris.filterNot { it == previous }
                AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
            }
        }
        capturedUris = capturedUris + (group to uri)
        if (uri !in media.uris) media.uris = media.uris + uri
        media.purposes = media.purposes + (uri to MEASUREMENT_GRID_PURPOSE)
        status = status + (group to "Analyzing…")
        if (group == "lengthBreadth") proposedLengthBreadth = null else proposedHeight = null
        scope.launch {
            if (group == "lengthBreadth") {
                runCatching { repository.analyzeMeasurementLengthBreadth(context, uri) }
                    .onSuccess { reading ->
                        val length = reading.lengthInches
                        val breadth = reading.breadthInches
                        val parts = buildList {
                            if (length != null && length > 0) add("L ${"%.2f".format(length)}\"")
                            if (breadth != null && breadth > 0) add("B ${"%.2f".format(breadth)}\"")
                        }
                        if (parts.isEmpty()) {
                            status = status + (group to "Couldn't read a value — enter it manually")
                        } else {
                            proposedLengthBreadth = reading
                            status = status + (group to "Read ${parts.joinToString(" · ")} — check against the object")
                        }
                    }
                    .onFailure { status = status + (group to "Analysis failed — enter it manually") }
            } else {
                runCatching { repository.analyzeMeasurement(context, uri, "height") }
                    .onSuccess { reading ->
                        val value = reading.valueInches
                        if (value != null && value > 0.0) {
                            proposedHeight = reading
                            status = status + (group to "Read ${"%.2f".format(value)} in — check against the object")
                        } else {
                            status = status + (group to "Couldn't read a value — enter it manually")
                        }
                    }
                    .onFailure { status = status + (group to "Analysis failed — enter it manually") }
            }
        }
    }

    fun discardGroup(group: String) {
        val uri = capturedUris[group] ?: return
        val deferred = media.stagedDeferred[uri]
        media.forget(uri)
        media.uris = media.uris.filterNot { it == uri }
        AppScope.io.launch { runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } } }
        capturedUris = capturedUris - group
        status = status - group
        if (group == "lengthBreadth") proposedLengthBreadth = null else proposedHeight = null
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val g = pendingGroup
        if (uri != null && g != null) analyze(g, uri)
        pendingGroup = null
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        val g = pendingGroup
        if (success && uri != null && g != null) analyze(g, uri)
        pendingCaptureUri = null
        pendingGroup = null
    }

    @Composable
    fun GridGroupRow(key: String, label: String, hint: String) {
        AppToggle(
            label = label,
            checked = enabled.contains(key),
            onCheckedChange = { checked -> enabled = if (checked) enabled + key else enabled - key }
        )
        if (enabled.contains(key)) {
            Text(hint, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
            ActionRow {
                OutlinedButton(
                    onClick = {
                        pendingGroup = key
                        gridCamera.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "grid-$key-", ".jpg")
                            pendingGroup = key
                            pendingCaptureUri = uri
                            takePhoto.launch(uri)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Capture photo", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = { pendingGroup = key; pickImage.launch("image/*") },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Pick photo", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
            }
            status[key]?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            if (key == "lengthBreadth") {
                proposedLengthBreadth?.let { reading ->
                    val length = reading.lengthInches
                    val breadth = reading.breadthInches
                    val parts = buildList {
                        if (length != null && length > 0) add("L ${"%.2f".format(length)}\"")
                        if (breadth != null && breadth > 0) add("B ${"%.2f".format(breadth)}\"")
                    }
                    OutlinedButton(
                        onClick = {
                            onLengthBreadth(length, breadth, dwVisionMarker(reading.marker))
                            proposedLengthBreadth = null
                            status = status + (key to "Filled ${parts.joinToString(" · ")} — still editable")
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) { Text("Use ${parts.joinToString(" · ")}", style = MaterialTheme.typography.labelMedium) }
                }
            } else {
                proposedHeight?.let { reading ->
                    val value = reading.valueInches ?: return@let
                    OutlinedButton(
                        onClick = {
                            onHeight(value, dwVisionMarker(reading.marker))
                            proposedHeight = null
                            status = status + (key to "Filled ${"%.2f".format(value)} in — still editable")
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                    ) { Text("Use ${"%.2f".format(value)} in", style = MaterialTheme.typography.labelMedium) }
                }
            }
            capturedUris[key]?.let { uri ->
                AndroidUriPreview(context = context, uri = uri, onRemove = { discardGroup(key) })
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Document using grid", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Place the object on a 1-inch grid. One top-down photo gives length and breadth; a side photo gives height. Needs a connection; you confirm before anything is filled.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        GridGroupRow("lengthBreadth", "Length & breadth (one photo)", "Top-down photo on the grid — offers length and breadth.")
        if (includeHeight) {
            GridGroupRow("height", "Height (one photo)", "Side-on photo against the grid — offers a height.")
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    emptyMessage: String? = null,
    searchable: Boolean? = null,
    onSelect: (String) -> Unit
) {
    SearchableSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selectedValue = selectedValue,
        placeholder = placeholder,
        includeNone = includeNone,
        enabled = enabled,
        emptyMessage = emptyMessage,
        searchable = searchable,
        onSelect = onSelect
    )
}

@Composable
private fun RegisterDropdownField(
    label: String,
    noun: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    load: RegisterLoad,
    placeholder: String = "Select",
    includeNone: Boolean = true,
    enabled: Boolean = true,
    emptyLine: String? = null,
    onSelect: (String) -> Unit
) {
    val notice = registerListNotice(noun, options.size, load, emptyLine)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DropdownField(
            label = label,
            options = options,
            selectedValue = selectedValue,
            placeholder = placeholder,
            includeNone = includeNone,
            enabled = enabled && options.isNotEmpty(),
            emptyMessage = notice,
            searchable = true,
            onSelect = onSelect
        )
        if (notice != null && options.isNotEmpty()) {
            Text(notice, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusDropdown(value: String, onSelect: (String) -> Unit) {
    DropdownField(
        label = "Status",
        options = statusOptions.map { it to it },
        selectedValue = value,
        includeNone = false,
        onSelect = onSelect
    )
}

@Composable
private fun StatusControl(canSetStatus: Boolean, value: String, onSelect: (String) -> Unit) {
    if (canSetStatus) {
        StatusDropdown(value = value, onSelect = onSelect)
    } else {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Status", color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(SurfaceCard, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                val dot = when (value) {
                    "APPROVED" -> StatusSuccess
                    "REJECTED" -> StatusError
                    else -> StatusWarning
                }
                Box(modifier = Modifier.size(8.dp).background(dot, CircleShape))
                Text(
                    value.ifBlank { "PENDING" }.lowercase().split('_').joinToString(" ").replaceFirstChar { it.uppercase() },
                    color = Body,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                if (value.isBlank() || value == "PENDING") "New records are reviewed before publishing."
                else "Your edit goes to review when you save.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private const val DEFAULT_PROBE_LIMIT = 5

private class WorkshopPickerState(private val repository: WorkshopRepository, initialId: String) {
    var workshops by mutableStateOf<List<WorkshopDetailDto>>(emptyList())
    var selectedId by mutableStateOf(initialId)

    var baselineId by mutableStateOf(initialId)
        private set

    var listState by mutableStateOf<WorkshopListState>(WorkshopListState.Loading)
        private set

    var online by mutableStateOf(true)
        private set

    var cached by mutableStateOf(RegisterLoad())
        private set

    var total by mutableStateOf(0)
        private set

    var check by mutableStateOf<WorkshopSubmissionCheckDto?>(null)
        private set

    var pendingConfirm by mutableStateOf<WorkshopSubmissionCheckDto?>(null)
        private set

    private var awaitingConfirm: CompletableDeferred<Boolean>? = null

    private val answers = mutableMapOf<String, WorkshopSubmissionCheckDto>()

    fun value(): String? = selectedId.ifBlank { null }

    fun unfiledReason(): String? = unfiledLinkReason(
        selectedId = selectedId,
        baselineId = baselineId,
        hadOptions = workshops.isNotEmpty(),
    )

    fun isDirty(): Boolean = selectedId != baselineId

    fun applyDefault(id: String) {
        selectedId = id
        baselineId = id
    }

    suspend fun applyMostRecentSubmittable(list: List<WorkshopDetailDto>) {
        if (list.isEmpty()) return
        for (workshop in list.take(DEFAULT_PROBE_LIMIT)) {
            val answer = answerFor(workshop.id)
            if (selectedId.isNotBlank()) return
            if (answer == null || answer.canSubmit) {
                applyDefault(workshop.id)
                return
            }
        }
        if (selectedId.isBlank()) applyDefault(list.first().id)
    }

    fun markSaved() {
        baselineId = selectedId
    }

    private suspend fun answerFor(workshopId: String): WorkshopSubmissionCheckDto? {
        if (workshopId.isBlank()) return null
        answers[workshopId]?.let { return it }
        return repository.workshopSubmissionCheck(workshopId)?.also { answers[workshopId] = it }
    }

    suspend fun refreshCheck() {
        val asked = selectedId
        check = null
        if (asked.isBlank()) return
        val answer = answerFor(asked)
        if (selectedId == asked) check = answer
    }

    suspend fun confirmSubmission(): Boolean {
        val asked = selectedId
        if (asked.isBlank()) return true
        val answer = answerFor(asked) ?: return true
        if (!answer.outOfWindow && !answer.isOver) return true
        val gate = CompletableDeferred<Boolean>()
        awaitingConfirm = gate
        pendingConfirm = answer
        return gate.await()
    }

    fun settleConfirm(confirmed: Boolean) {
        pendingConfirm = null
        awaitingConfirm?.complete(confirmed)
        awaitingConfirm = null
    }

    internal fun markCached(rows: List<WorkshopDetailDto>, load: RegisterLoad) {
        workshops = rows
        cached = load
    }

    internal fun markListed(page: PageResponse<WorkshopDetailDto>) {
        workshops = page.items
        total = page.total
        listState = WorkshopListState.Listed(count = page.items.size, total = page.total)
    }

    internal fun markFailed(transient: Boolean) {
        online = !transient
        listState = WorkshopListState.Failed
    }
}

@Composable
private fun rememberWorkshopPicker(
    repository: WorkshopRepository,
    isEdit: Boolean,
    initialId: String?,
    resetKey: Any? = null
): WorkshopPickerState {
    val state = remember(resetKey) { WorkshopPickerState(repository, initialId.orEmpty()) }
    val context = LocalContext.current
    LaunchedEffect(resetKey) {
        val half = repository.loadAllottedFieldWorkshops(
            context = context,
            fetch = { repository.workshopsIMaySubmitToPage() },
            rowsOf = { it.items },
            onCached = { rows, load -> state.markCached(rows, load) },
        )
        val page = half.page
        if (page != null) {
            state.markListed(page)
            if (!isEdit && state.selectedId.isBlank()) {
                state.applyMostRecentSubmittable(page.items)
            }
        } else {
            state.markFailed(transient = !half.load.online)
        }
    }
    LaunchedEffect(state, state.selectedId) { state.refreshCheck() }
    return state
}

@Composable
private fun WorkshopField(state: WorkshopPickerState, saving: Boolean = false) {
    val selected = state.workshops.firstOrNull { it.id == state.selectedId }
    val check = state.check
    val blocked = check != null && !check.canSubmit
    val late = if (check != null) {
        check.outOfWindow || check.isOver
    } else {
        state.selectedId.isNotBlank() && workshopEndedLocally(selected)
    }
    val endLabel = formatIsoDate(check?.endDate ?: selected?.endDate ?: selected?.date)

    val options = remember(state.workshops, state.selectedId) {
        fieldWorkshopOptions(rows = state.workshops, offPageId = state.selectedId)
    }

    val notice = workshopListNotice(
        state = state.listState,
        kind = WorkshopListKind.FIELD,
        online = state.online,
        cached = state.cached,
        cachedRows = state.workshops.size,
    )

    val listEnabled = !saving && listIsAnswerable(options)

    val standDown = options.isEmpty() && !listEnabled

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SearchableSelectField(
            label = "Workshop",
            options = options,
            selectedValue = state.selectedId,
            placeholder = NO_FIELD_WORKSHOP,
            includeNone = true,
            enabled = listEnabled,
            searchable = false,
            emptyMessage = notice,
            onSelect = { state.selectedId = it },
        )
        workshopCapLine(state.workshops.size, state.total, WorkshopListKind.FIELD)?.let { cap ->
            Text(cap, color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }
        if (!standDown) notice?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp) }
        if (options.isNotEmpty()) {
            Text(
                "Only workshops you can access. Ask an admin if one is missing.",
                color = Muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp,
            )
        }
        if (blocked) {
            Text(
                "You're not assigned to this workshop. Ask an admin, or pick another.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        } else if (late) {
            Text(
                (if (endLabel == null) "This workshop has already ended." else "This workshop ended on $endLabel.") +
                    " " +
                    if (check == null || check.needsAdminApproval) {
                        "This counts as a late submission and needs admin approval."
                    } else {
                        "Saving now is recorded as a late submission."
                    },
                color = Coral,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    state.pendingConfirm?.let { pending ->
        LateSubmissionDialog(
            workshopTitle = pending.title ?: selected?.title,
            endDate = pending.endDate ?: selected?.endDate ?: selected?.date,
            needsAdminApproval = pending.needsAdminApproval,
            saving = saving,
            onConfirm = { state.settleConfirm(true) },
            onCancel = { state.settleConfirm(false) }
        )
    }
}

private class RecordWorkshopLink(
    val type: WorkshopTypePickerState,
    val field: WorkshopPickerState,
    val design: DesignWorkshopPickerState,
) {
    val routesToDesignWorkshop: Boolean get() = type.routesToDesignWorkshop

    fun workshopId(): String? = if (routesToDesignWorkshop) null else field.value()

    fun designWorkshopId(): String? = if (routesToDesignWorkshop) design.value() else null

    fun unfiledReasons(): Map<String, String> = workshopUnfiledReasons(
        designWorkshop = if (routesToDesignWorkshop) design.unfiledReason() else displaced(design.baselineId),
        workshop = if (routesToDesignWorkshop) displaced(field.baselineId) else field.unfiledReason(),
    )

    private fun displaced(baselineId: String): String? =
        if (baselineId.isNotBlank()) UNFILED_BY_CHOICE else null

    fun isDirty(): Boolean = type.touched || field.isDirty() || design.isDirty()

    suspend fun confirmSubmission(): Boolean =
        if (routesToDesignWorkshop) true else field.confirmSubmission()

    fun applyCarriedWorkshop(workshopId: String) {
        if (field.isDirty()) return
        field.applyDefault(workshopId)
        type.applyCarriedRouting()
    }

    fun markSaved() {
        field.markSaved()
    }
}

@Composable
private fun rememberRecordWorkshopLink(
    repository: WorkshopRepository,
    isEdit: Boolean,
    initialWorkshopId: String?,
    initialDesignWorkshopId: String?,
    resetKey: Any? = null,
): RecordWorkshopLink {
    val preferDesignWorkshops = remember(resetKey) {
        repository.cachedUser()?.let { FieldPermissions.canRunDesignWorkshops(it) } ?: false
    }
    val type = rememberWorkshopTypePicker(
        repository = repository,
        storedWorkshopId = initialWorkshopId,
        storedDesignWorkshopId = initialDesignWorkshopId,
        preferDesignWorkshops = preferDesignWorkshops,
        resetKey = resetKey,
    )
    val field = rememberWorkshopPicker(repository, isEdit, initialWorkshopId, resetKey)
    val design = rememberDesignWorkshopPicker(repository, isEdit, initialDesignWorkshopId, resetKey)
    return remember(type, field, design) { RecordWorkshopLink(type, field, design) }
}

@Composable
private fun RecordWorkshopField(link: RecordWorkshopLink, saving: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkshopTypeField(state = link.type, saving = saving)
        if (link.routesToDesignWorkshop) {
            DesignWorkshopField(state = link.design, saving = saving)
        } else {
            WorkshopField(state = link.field, saving = saving)
        }
    }
}

@Composable
private fun LateSubmissionDialog(
    workshopTitle: String?,
    endDate: String?,
    needsAdminApproval: Boolean,
    saving: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val name = workshopTitle?.trim().orEmpty().ifBlank { "This workshop" }
    val ended = formatIsoDate(endDate)?.let { " ended on $it" } ?: " has already ended"
    AlertDialog(
        onDismissRequest = { if (!saving) onCancel() },
        title = { Text("Late submission") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "$name$ended. " +
                        if (needsAdminApproval) {
                            "Saved as a late submission — it stays Pending until an admin approves it."
                        } else {
                            "Saved as a late submission."
                        }
                )
                Text(
                    "Pick another workshop if this belongs to one still running.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = onConfirm) {
                Text(if (saving) "Saving…" else "Submit anyway")
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onCancel) { Text("Go back") }
        }
    )
}

private fun workshopEndedLocally(workshop: WorkshopDetailDto?): Boolean {
    val raw = workshop?.endDate ?: workshop?.date ?: workshop?.startDate ?: return false
    val end = parseIsoToLocalDate(raw) ?: return false
    return LocalDate.now(ZoneId.systemDefault()).isAfter(end)
}

private fun workshopOptionLabel(workshop: WorkshopDetailDto): String {
    if (workshop.title.isBlank()) return workshopDisplayTitle(null, date = workshop.occurrenceDate(), place = workshop.place)
    val title = workshop.title
    val day = workshop.occurrenceDate().take(10)
    return if (day.isBlank()) title else "$title · $day"
}

private const val AADHAAR_LENGTH = ArtisanIdentity.AADHAAR_LENGTH

private fun aadhaarValidationError(value: String?): String? = ArtisanIdentity.aadhaarError(value)

private fun maskAadhaar(value: String?): String? = ArtisanIdentity.mask(value)

private object AadhaarGroupingTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val grouped = digits.chunked(4).joinToString(" ")
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                (offset + ((offset - 1) / 4).coerceIn(0, 2)).coerceIn(0, grouped.length)

            override fun transformedToOriginal(offset: Int): Int =
                (offset - (offset / 5).coerceIn(0, 2)).coerceIn(0, digits.length)
        }
        return TransformedText(AnnotatedString(grouped), mapping)
    }
}

@Composable
private fun ArtisanAadhaarField(
    value: String,
    error: String?,
    warning: String?,
    required: Boolean,
    focusRequester: FocusRequester,
    onRecord: String? = null,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = { input -> onValueChange(input.filter { it in '0'..'9' }.take(AADHAAR_LENGTH)) },
            label = { Text(requiredMarked(if (required) "Aadhaar number *" else "Aadhaar number")) },
            placeholder = { Text("1234 5678 9012") },
            isError = error != null,
            supportingText = {
                if (error != null) {
                    Text(error)
                } else if (onRecord != null) {
                    Text("On record: $onRecord. Type a new number only to replace it.", color = Muted, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        if (required) {
                            "Required. 12 digits from the card — prevents duplicate artisans. Shown masked everywhere else."
                        } else {
                            "Optional for this older record. Add only if the artisan agrees."
                        },
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = AadhaarGroupingTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
        )
        warning?.let { msg -> Text(msg, color = Coral, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ArtisanMultiSelectField(
    label: String,
    artisans: List<ArtisanDto>,
    selectedIds: Set<String>,
    repository: WorkshopRepository? = null,
    onToggle: (String) -> Unit
) {
    var scanning by remember { mutableStateOf(false) }
    var scanNote by remember { mutableStateOf<String?>(null) }
    val cardsOn = repository != null && artisans.any { it.card != null }
    val pendingCards = selectedIds.filter { ArtisanCardCode.isPending(it) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("$label (${selectedIds.size} selected)", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (cardsOn) ArtisanCardScanButton(onClick = { scanNote = null; scanning = true })
        }
        scanNote?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        if (scanning && repository != null) {
            ArtisanCardScanSheet(
                repository = repository,
                onDismiss = { scanning = false },
                onResolved = { artisanId, name ->
                    scanning = false
                    scanNote = when {
                        artisans.none { it.id == artisanId } -> "$name isn't in this list."
                        artisanId in selectedIds -> "$name is already selected."
                        else -> { onToggle(artisanId); "$name selected." }
                    }
                },
                acceptPending = true,
                onPending = { value ->
                    scanning = false
                    scanNote = null
                    if (value !in selectedIds) onToggle(value)
                }
            )
        }
        pendingCards.forEach { value ->
            InputChip(
                selected = true,
                onClick = { onToggle(value) },
                label = { Text(ArtisanCardCode.pendingLabel(value), style = MaterialTheme.typography.bodySmall) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove this card") },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
        if (artisans.isEmpty()) {
            Text("No artisans yet. Create one first.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                artisans.forEach { artisan ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(artisan.id) }
                    ) {
                        Checkbox(checked = selectedIds.contains(artisan.id), onCheckedChange = { onToggle(artisan.id) })
                        Text("${artisan.name} · ${artisan.place}", color = Body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckboxMultiSelectField(
    label: String,
    options: List<Pair<String, String>>,
    selectedIds: Set<String>,
    emptyMessage: String = "No options available.",
    onToggle: (String) -> Unit
) {
    SearchableMultiSelectField(
        label = label,
        options = remember(options) { options.asSelectOptions() },
        selected = selectedIds,
        placeholder = "Select",
        emptyMessage = emptyMessage,
        onSelectedChange = { next ->
            (next - selectedIds).forEach(onToggle)
            (selectedIds - next).forEach(onToggle)
        }
    )
}

@Composable
private fun DatePickerField(label: String, value: LocalDate?, onChange: (LocalDate) -> Unit) {
    CalendarField(label = label, value = value, onValueChange = { day -> day?.let(onChange) }, clearable = false)
}

private enum class RecPhase { IDLE, RECORDING, PAUSED, RECORDED }

private enum class ActionStatus { IDLE, SUCCESS, ERROR }

private val SuccessGreen: Color get() = StatusSuccess
private val FailureRed: Color get() = StatusError

@Composable
private fun AutoResetStatus(status: ActionStatus, onReset: () -> Unit) {
    LaunchedEffect(status) {
        if (status != ActionStatus.IDLE) {
            kotlinx.coroutines.delay(5000)
            onReset()
        }
    }
}

@Composable
private fun AudioClipRecorder(
    clips: List<Uri>,
    onAddClip: (Uri) -> Unit,
    onRemoveLast: () -> Unit,
    onError: (String) -> Unit,
    idleLabel: String = "Record ●",
    onRecorded: (File) -> Unit = {}
) {
    val context = LocalContext.current
    var phase by remember { mutableStateOf(RecPhase.IDLE) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var capNotice by remember { mutableStateOf<String?>(null) }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        uris.forEach { onAddClip(it) }
    }

    val stopFromLimit = remember { arrayOfNulls<() -> Unit>(1) }

    fun startNew() {
        runCatching {
            val file = createAppFile(context, "question-audio-", ".m4a")
            recorder = createAudioRecorder(context, file).also { rec ->
                runCatching { rec.setMaxDuration(QUESTIONNAIRE_CLIP_MAX_MILLIS.toInt()) }
                rec.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        capNotice = questionnaireClipCapLine()
                        stopFromLimit[0]?.invoke()
                    }
                }
                rec.start()
            }
            recordingFile = file
            capNotice = null
            phase = RecPhase.RECORDING
        }.onFailure { onError(it.userMessage("Unable to start recording")); phase = RecPhase.IDLE }
    }

    fun stopAndSave() {
        if (phase != RecPhase.RECORDING && phase != RecPhase.PAUSED) return
        var finished: File? = null
        runCatching {
            recorder?.stop()
            recorder?.release()
            recordingFile?.let {
                onAddClip(uriForFile(context, it))
                finished = it
            }
        }.onFailure { onError(it.userMessage("Unable to stop recording")) }
        recorder = null
        recordingFile = null
        phase = RecPhase.RECORDED
        finished?.let(onRecorded)
    }

    stopFromLimit[0] = ::stopAndSave

    DisposableEffect(Unit) {
        onDispose { runCatching { recorder?.stop(); recorder?.release() }; recorder = null }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        capNotice?.let {
            Text(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = Muted,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 16.sp
            )
        }
        when (phase) {
            RecPhase.IDLE -> OutlinedButton(
                onClick = { startNew() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = pad
            ) { Text(if (clips.isNotEmpty()) "Record another ●" else idleLabel, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }

            RecPhase.RECORDING -> {
                ActionRow {
                    OutlinedButton(
                        onClick = { runCatching { recorder?.pause(); phase = RecPhase.PAUSED }.onFailure { onError(it.userMessage("Unable to pause")) } },
                        contentPadding = pad
                    ) {
                        Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pause", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(onClick = { stopAndSave() }, contentPadding = pad) {
                        StopSquareLabel("Stop")
                    }
                }
            }

            RecPhase.PAUSED -> {
                ActionRow {
                    OutlinedButton(
                        onClick = { runCatching { recorder?.resume(); phase = RecPhase.RECORDING }.onFailure { onError(it.userMessage("Unable to resume")) } },
                        contentPadding = pad
                    ) { Text("Resume ●", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
                    OutlinedButton(onClick = { stopAndSave() }, contentPadding = pad) {
                        StopSquareLabel("Stop")
                    }
                }
            }

            RecPhase.RECORDED -> {
                Button(onClick = { onRemoveLast(); startNew() }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Re-record", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { startNew() }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Record another", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = { onRemoveLast(); phase = RecPhase.IDLE }, modifier = Modifier.fillMaxWidth(), contentPadding = pad) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Discard", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (phase != RecPhase.RECORDING && phase != RecPhase.PAUSED) {
            OutlinedButton(
                onClick = { pickAudio.launch("audio/*") },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = pad
            ) {
                Icon(Icons.Filled.PermMedia, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add audio file", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
            }
            if (phase == RecPhase.IDLE && clips.isNotEmpty()) {
                TextButton(
                    onClick = onRemoveLast,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = pad
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove last clip", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (phase == RecPhase.RECORDING || phase == RecPhase.PAUSED) {
            RecordingIndicator(
                getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) },
                paused = phase == RecPhase.PAUSED
            )
        }
        Text(plural(clips.size, "clip"), color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StopSquareLabel(text: String = "Stop") {
    Box(
        modifier = Modifier
            .size(13.dp)
            .background(Color(0xFFD13438), RoundedCornerShape(3.dp))
    )
    Spacer(Modifier.width(6.dp))
    Text(text, maxLines = 1, softWrap = false, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MediaCaptureSection(
    repository: WorkshopRepository,
    media: MediaCaptureState,
    emphasizeVideo: Boolean = false,
    beforeLocation: (@Composable () -> Unit)? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                startEagerUpload(repository, context, media, uri, scope)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (media.uris.isNotEmpty()) {
                val pending = media.stagedDeferred.values.toList()
                AppScope.io.launch {
                    pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val capturePermission = rememberPermissionGate(onError)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) media.uris = media.uris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) media.uris = media.uris + uri
        pendingCaptureUri = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) media.uris = media.uris + uri
        pendingCaptureUri = null
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Attach media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Media links to this record automatically; audio is transcribed after upload.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        ActionRow {
            OutlinedButton(onClick = { pickMedia.launch("*/*") }) { Text("Pick files") }
            OutlinedButton(
                onClick = {
                    capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                        val uri = createAppFileUri(context, "field-photo-", ".jpg")
                        pendingCaptureUri = uri
                        takePhoto.launch(uri)
                    }
                },
            ) { Text("Take photo") }
        }
        if (emphasizeVideo) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = MaterialTheme.field.muted, modifier = Modifier.size(18.dp))
                Text("Video preferred — capture the action as it happens.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        ActionRow {
            if (emphasizeVideo) {
                Button(
                    onClick = {
                        capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "field-video-", ".mp4")
                            pendingCaptureUri = uri
                            takeVideo.launch(uri)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { CardButtonLabel(Icons.Filled.Videocam, "Record video") }
            } else {
                OutlinedButton(
                    onClick = {
                        capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                            val uri = createAppFileUri(context, "field-video-", ".mp4")
                            pendingCaptureUri = uri
                            takeVideo.launch(uri)
                        }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Record video", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
            }
            OutlinedButton(
                onClick = {
                    if (!recording && !capturePermission.holds(MICROPHONE_PERMISSION)) {
                        capturePermission.ask(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE)
                    } else if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "field-audio-", ".m4a")
                            recorder = createAudioRecorder(context, file).also { it.start() }
                            recordingFile = file
                            recording = true
                            onMessage("Recording audio...")
                        }.onFailure { onError(it.userMessage("Unable to start audio recording")) }
                    } else {
                        runCatching {
                            recorder?.stop()
                            recorder?.release()
                            recordingFile?.let { file -> media.uris = media.uris + uriForFile(context, file) }
                        }.onFailure { onError(it.userMessage("Unable to stop audio recording")) }
                        recorder = null
                        recordingFile = null
                        recording = false
                        onMessage("Audio recording added")
                    }
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)
            ) { if (recording) StopSquareLabel("Stop audio") else Text("Record audio ●", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge) }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        beforeLocation?.invoke()
        LocationAddressEditor(
            repository = repository,
            value = media.location,
            onUseGps = {
                if (!holdsLocation(context)) permissionLauncher.launch(LOCATION_PERMISSIONS)
                readLastKnownLocation(context)
            },
            onChange = { media.location = it },
            onMessage = onMessage
        )
        if (media.uris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tileInk = MaterialTheme.field.onBrandTile
                Text("${plural(media.uris.size, "file")} attached", color = tileInk, fontWeight = FontWeight.SemiBold)
                val overall = media.uris.map { (media.stagedProgress[it] ?: 0f).coerceIn(0f, 1f) }.average().toFloat()
                val status = attachedUploadsStatus(
                    total = media.uris.size,
                    done = media.uris.count { media.staged.containsKey(it) },
                    failed = media.uris.count { it in media.stagedFailed && !media.staged.containsKey(it) },
                    fraction = overall
                )
                if (status.showProgress) {
                    FieldLinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = FieldProgressDefaults.onBrandTileIndicator,
                        trackColor = FieldProgressDefaults.onBrandTileTrack
                    )
                }
                Text(
                    status.line,
                    color = if (status.failed) MaterialTheme.field.errorOnBrandTile else MaterialTheme.field.onBrandTileMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                var attachmentsExpanded by remember { mutableStateOf(false) }
                val attachmentsCap = 6
                val shownAttachments = if (attachmentsExpanded) media.uris else media.uris.take(attachmentsCap)
                shownAttachments.forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        progress = media.stagedProgress[uri],
                        failed = uri in media.stagedFailed,
                        onRetry = { startEagerUpload(repository, context, media, uri, scope) },
                        downloadable = true,
                        onTile = tileInk,
                        onRemove = {
                            val deferred = media.stagedDeferred[uri]
                            media.forget(uri)
                            media.uris = media.uris.filterNot { it == uri }
                            AppScope.io.launch {
                                runCatching { deferred?.await()?.let { repository.deleteStaged(it.objectKey) } }
                            }
                        }
                    )
                }
                ActionRow {
                    if (media.uris.size > attachmentsCap) {
                        OutlinedButton(
                            onClick = { attachmentsExpanded = !attachmentsExpanded },
                            colors = onTileButtonColors(tileInk),
                            border = onTileBorder(tileInk)
                        ) {
                            Text(if (attachmentsExpanded) "Show fewer" else "+${media.uris.size - attachmentsCap} more")
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.destructiveAction(),
                        colors = onTileButtonColors(tileInk),
                        border = onTileBorder(tileInk),
                        onClick = {
                            val pending = media.stagedDeferred.values.toList()
                            media.stagedDeferred.clear()
                            media.staged = emptyMap()
                            media.stagedProgress = emptyMap()
                            media.stagedFailed = emptySet()
                            media.uris = emptyList()
                            AppScope.io.launch {
                                pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                            }
                        }
                    ) { Text("Clear attachments") }
                }
            }
            UriTranscribeChoices(media.uris)
        }
    }
}

@Composable
private fun UriTranscribeChoices(uris: List<Uri>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items = remember(uris) {
        uris.map { uri ->
            TranscribeChoiceItem(
                key = uri.toString(),
                name = com.designprototype.workshop.data.mediaDisplayNameOf(context, uri) ?: uri.lastPathSegment ?: "File",
                typeOrMime = context.contentResolver.getType(uri),
            )
        }
    }
    TranscribeChoiceRows(items, modifier)
}

@Composable
private fun MediaStagingEffect(repository: WorkshopRepository, media: MediaCaptureState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(media.uris) {
        media.uris.forEach { uri ->
            if (!media.stagedDeferred.containsKey(uri)) {
                startEagerUpload(repository, context, media, uri, scope)
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (media.uris.isNotEmpty()) {
                val pending = media.stagedDeferred.values.toList()
                AppScope.io.launch {
                    pending.forEach { d -> runCatching { d.await()?.let { repository.deleteStaged(it.objectKey) } } }
                }
            }
        }
    }
}

@Composable
private fun AttachedUploadsCard(
    context: Context,
    media: MediaCaptureState,
    label: String,
    repository: WorkshopRepository,
    uris: List<Uri> = media.uris,
    onRemove: (Uri) -> Unit
) {
    if (uris.isEmpty()) return
    val scope = rememberCoroutineScope()
    val doneCount = uris.count { media.staged.containsKey(it) }
    val failedCount = uris.count { it in media.stagedFailed && !media.staged.containsKey(it) }
    val tileInk = MaterialTheme.field.onBrandTile
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("${plural(uris.size, label)} attached", color = tileInk, fontWeight = FontWeight.SemiBold)
        val overall = uris.map { (media.stagedProgress[it] ?: 0f).coerceIn(0f, 1f) }.average().toFloat()
        val status = attachedUploadsStatus(total = uris.size, done = doneCount, failed = failedCount, fraction = overall)
        if (status.showProgress) {
            FieldLinearProgressIndicator(
                progress = { overall },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = FieldProgressDefaults.onBrandTileIndicator,
                trackColor = FieldProgressDefaults.onBrandTileTrack
            )
        }
        Text(
            status.line,
            color = if (status.failed) MaterialTheme.field.errorOnBrandTile else MaterialTheme.field.onBrandTileMuted,
            style = MaterialTheme.typography.bodySmall
        )
        var expanded by remember { mutableStateOf(false) }
        val cap = 8
        val shown = if (expanded) uris else uris.take(cap)
        shown.forEach { uri ->
            AndroidUriPreview(
                context = context,
                uri = uri,
                progress = media.stagedProgress[uri],
                failed = uri in media.stagedFailed,
                onRetry = { startEagerUpload(repository, context, media, uri, scope) },
                downloadable = true,
                onTile = tileInk,
                onRemove = { onRemove(uri) }
            )
        }
        if (uris.size > cap) {
            ActionRow {
                OutlinedButton(
                    onClick = { expanded = !expanded },
                    colors = onTileButtonColors(tileInk),
                    border = onTileBorder(tileInk)
                ) {
                    Text(if (expanded) "Show fewer" else "+${uris.size - cap} more")
                }
            }
        }
    }
}

@Composable
private fun LoadingCard(mode: EntryMode, failed: String? = null, onRetry: () -> Unit = {}) {
    if (failed != null) {
        RecordCard(title = "Couldn't open this ${mode.label.lowercase()}") {
            LoadFailedCard(message = failed, onRetry = onRetry)
        }
        return
    }
    RecordCard(title = "Loading ${mode.label.lowercase()}") {
        Text("Fetching the latest saved values…", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@kotlinx.serialization.Serializable
private data class PickerRow(
    val id: String,
    val label: String,
    val completeness: CompletenessDto? = null,
    val chips: com.designprototype.workshop.questionnaire.data.QChips? = null,
    val preview: com.designprototype.workshop.transcript.data.TranscriptPreviewDto? = null
)

private suspend fun pickerReadCached(
    context: Context,
    mode: EntryMode,
    search: String?,
    completeness: String? = null,
    onCached: (String) -> Unit,
    fetch: suspend () -> Pair<Int, List<PickerRow>>,
): Pair<Int, List<PickerRow>> {
    if (search != null) return fetch()
    val read = ReadCache.read(
        context,
        "picker2:${mode.name}" + (completeness?.let { ":$it" } ?: ""),
        kotlinx.serialization.builtins.PairSerializer(
            kotlinx.serialization.serializer<Int>(),
            kotlinx.serialization.builtins.ListSerializer(PickerRow.serializer()),
        ),
        fetch,
    )
    if (read.fromCache) onCached(readCacheNotice(read.cachedAt))
    return read.value
}

@Composable
private fun RecordPickerScreen(
    repository: WorkshopRepository,
    mode: EntryMode,
    canUpdate: Boolean,
    onOpen: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onError: (String) -> Unit
) {
    var query by rememberKept("picker.${mode.name}.query") { "" }
    var loading by remember(mode) { mutableStateOf(true) }
    var loadError by remember(mode) { mutableStateOf<String?>(null) }
    var attempt by remember(mode) { mutableStateOf(0) }
    var rows by rememberKept("picker.${mode.name}.rows") { emptyList<PickerRow>() }
    val options = remember(rows) { rows.map { it.id to it.label } }
    var total by rememberKept("picker.${mode.name}.total") { 0 }
    var selected by rememberKept("picker.${mode.name}.selected") { "" }

    val pickerContext = LocalContext.current.applicationContext
    val completenessKind = CompletenessKind.forMode(mode.name)
    var completenessFilter by remember(mode) { mutableStateOf(CompletenessFilterMemory.read(pickerContext, mode.name)) }
    LaunchedEffect(Unit) { runCatching { repository.completenessRules(pickerContext) } }
    var cachedNote by remember(mode) { mutableStateOf<String?>(null) }
    val mediaV2 = remember { com.designprototype.workshop.questionnaire.data.QuestionnaireV2.get(pickerContext) }
    val (mediaFilters, setMediaFilters) = com.designprototype.workshop.questionnaire.ui.rememberKeptFilters(mediaV2, com.designprototype.workshop.questionnaire.data.FilterStore.MEDIA)
    val mediaNarrowed = mode == EntryMode.MEDIA && mediaFilters.mediaParams().isNotEmpty()
    LaunchedEffect(mode, query, attempt, completenessFilter, mediaFilters) {
        if (query.isNotBlank()) delay(350)
        loading = true
        loadError = null
        val search = query.trim().ifBlank { null }
        cachedNote = null
        runCatching {
          val wire = completenessFilter.wire.takeIf { completenessKind != null }
          pickerReadCached(pickerContext, mode, search ?: "".takeIf { mediaNarrowed }, wire, onCached = { cachedNote = it }) {
            when (mode) {
                EntryMode.ARTISAN -> repository.artisanPage(search, wire).let { p -> p.total to p.items.map { PickerRow(it.id, "${it.name} · ${it.place}", it.completeness) } }
                EntryMode.CRAFT -> repository.craftPage(search).let { p -> p.total to p.items.map { PickerRow(it.id, it.name + (it.place?.let { pl -> " · $pl" } ?: "")) } }
                EntryMode.PRODUCT -> repository.productPage(search, wire).let { p -> p.total to p.items.map { PickerRow(it.id, "${it.productName} · ${it.artisanName}", it.completeness) } }
                EntryMode.PROCESS -> repository.processPage(search).let { p -> p.total to p.items.map { PickerRow(it.id, it.name + (it.product?.productName?.let { pr -> " · $pr" } ?: "")) } }
                EntryMode.TOOL -> repository.toolPage(search, wire).let { p -> p.total to p.items.map { PickerRow(it.id, it.toolRowLabel(), it.completeness) } }
                EntryMode.WORKSHOP -> repository.workshopPage(search, wire).let { p -> p.total to p.items.map { PickerRow(it.id, workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place), it.completeness) } }
                EntryMode.MEDIA -> repository.mediaPage(search, if (mediaNarrowed) mediaFilters.mediaParams() else emptyMap()).let { p ->
                    p.total to p.items.map { m -> PickerRow(m.id, m.caption?.takeIf { it.isNotBlank() } ?: m.originalFilename, chips = m.questionnaireChips, preview = m.transcriptPreview) }
                }
                EntryMode.QUESTIONNAIRE -> repository.interviewPage(search, wire).let { p ->
                    p.total to p.items.groupBy { interviewGroupKey(it) }.values.map { group ->
                        val rep = representativeInterview(group)
                        val artisanNames = rep.artisans.mapNotNull { it.artisan?.name }.distinct().joinToString(", ")
                        val parts = listOfNotNull(
                            artisanNames.ifBlank { null },
                            rep.title.takeIf { it.isNotBlank() },
                            if (group.size > 1) "${group.size} sessions" else null
                        )
                        PickerRow(rep.id, parts.joinToString(" · ").ifBlank { "Untitled interview" }, rep.completeness)
                    }
                }
                else -> 0 to emptyList()
            }
          }
        }.onSuccess { (count, fetched) ->
            rows = fetched
            total = count
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            loadError = it.userMessage("Couldn't load ${mode.label.lowercase()} records.")
        }
        loading = false
    }

    val noun = mode.label.lowercase()
    RecordCard(title = "Existing $noun records") {
        Text(
            "Tap a record to read it. ${RecordOpen.UPDATE_LABEL} opens its form.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search ${noun} records") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth()
        )
        if (completenessKind != null) {
            CompletenessFilterChips(value = completenessFilter) { next ->
                completenessFilter = next
                CompletenessFilterMemory.write(pickerContext, mode.name, next)
            }
        }
        if (mode == EntryMode.MEDIA) com.designprototype.workshop.questionnaire.ui.FilterBar(
            mediaFilters, setMediaFilters, "",
            options = { facet, q, ids -> mediaV2.filterOptions(facet, q, ids) },
            fields = com.designprototype.workshop.questionnaire.ui.FilterField.MEDIA, showSearch = false
        )
        cachedNote?.let { CachedCopyNote(it) }
        when {
            loadError != null -> LoadFailedCard(message = loadError.orEmpty(), onRetry = { attempt++ })
            loading && options.isEmpty() -> Text("Loading $noun records…", color = Muted)
            options.isEmpty() && query.isNotBlank() -> Text("No $noun record matches “${query.trim()}”.", color = Muted)
            options.isEmpty() && completenessFilter != CompletenessFilter.ALL && completenessKind != null ->
                Text("Every $noun record has these fields.", color = Muted)
            options.isEmpty() -> Text("There are no $noun records yet.", color = Muted)
            else -> {
                DropdownField(
                    label = "Select $noun",
                    options = options,
                    selectedValue = selected,
                    placeholder = "Select a record",
                    includeNone = false,
                    onSelect = { selected = it }
                )
                Button(
                    onClick = { if (selected.isNotBlank()) onOpen(selected) },
                    enabled = selected.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text(RecordOpen.OPEN_LABEL) }
                if (total > options.size) {
                    Text(
                        "Newest ${options.size} of $total. Type a name to find others.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(if (query.isBlank()) "Recently added" else "Matches", color = Muted, style = MaterialTheme.typography.bodySmall)
                rows.take(if (query.isBlank()) 12 else 50).forEach { row ->
                    val id = row.id
                    val label = row.label
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(onClickLabel = RecordOpen.openClickLabel(label), role = Role.Button) { onOpen(id) }
                                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                row.preview?.let { com.designprototype.workshop.transcript.ui.TranscriptPreviewText(it, maxLines = 2) }
                                com.designprototype.workshop.questionnaire.ui.RecordingChips(row.chips, filters = mediaFilters, onChip = if (mode == EntryMode.MEDIA) ({ f: com.designprototype.workshop.questionnaire.data.ChipFilter, l: String ->
                                    if (mediaFilters.appliesToMedia(f)) setMediaFilters(mediaFilters.applyChip(f, l))
                                }) else null)
                            }
                            row.completeness?.let { block ->
                                CompletenessRing(
                                    percent = block.percent,
                                    size = 18.dp,
                                    stroke = 3.dp,
                                    showLabel = false,
                                    description = completenessSentence(block),
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                            if (canUpdate) {
                                TextButton(onClick = { onUpdate(id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(RecordOpen.UPDATE_LABEL, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordViewScreen(
    repository: WorkshopRepository,
    mode: EntryMode,
    recordId: String,
    canUpdate: Boolean,
    showProvenance: Boolean,
    canDelete: Boolean = false,
    onUpdate: (String?) -> Unit,
    onDeleted: () -> Unit = {},
    onError: (String) -> Unit
) {
    var completeness by remember(recordId) { mutableStateOf<CompletenessDto?>(null) }
    if (canUpdate) {
        Button(
            onClick = { onUpdate(null) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("${RecordOpen.UPDATE_LABEL} this ${mode.label.lowercase()}")
        }
    }
    completeness?.let { block ->
        CompletenessMeter(block = block, canUpdate = canUpdate, onUpdate = { key -> onUpdate(key) })
    }
    ViewDataDetail(
        repository = repository,
        mode = mode,
        recordId = recordId,
        onCompleteness = { completeness = it },
        canOverrideCompletion = false,
        showProvenance = showProvenance,
        onError = onError,
        readOnly = true
    )
    DwWorkshopRecordType.entries.firstOrNull { it.name == mode.name }?.let { type ->
        RecordCodeSection(repository, type, recordId)
    }
    if (canDelete && mode in DELETABLE_MODES) {
        DeleteRecordSection(repository, mode, recordId, onDeleted = onDeleted, onError = onError)
    }
}

private suspend fun deleteByMode(repository: WorkshopRepository, mode: EntryMode, id: String) {
    when (mode) {
        EntryMode.ARTISAN -> repository.deleteArtisan(id)
        EntryMode.PRODUCT -> repository.deleteProduct(id)
        EntryMode.PROCESS -> repository.deleteProcess(id)
        EntryMode.TOOL -> repository.deleteTool(id)
        EntryMode.WORKSHOP -> repository.deleteWorkshop(id)
        EntryMode.CRAFT -> repository.deleteCraft(id)
        EntryMode.QUESTIONNAIRE -> repository.deleteInterview(id)
        EntryMode.MEDIA -> repository.deleteMedia(id)
        else -> throw IllegalArgumentException("This record type cannot be deleted")
    }
}

private val DELETABLE_MODES = setOf(
    EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL, EntryMode.WORKSHOP,
    EntryMode.CRAFT, EntryMode.QUESTIONNAIRE, EntryMode.MEDIA,
)

private fun deleteNoun(mode: EntryMode): String =
    if (mode == EntryMode.MEDIA) "media file" else mode.label.lowercase()

@Composable
private fun DeleteRecordSection(
    repository: WorkshopRepository,
    mode: EntryMode,
    recordId: String,
    onDeleted: () -> Unit,
    onError: (String) -> Unit
) {
    RecordDangerZoneCard(
        noun = deleteNoun(mode),
        delete = { deleteByMode(repository, mode, recordId) },
        onDeleted = onDeleted,
        onError = { onError(it.userMessage(RecordDangerZone.FAILED)) },
        body = if (mode == EntryMode.QUESTIONNAIRE) RecordDangerZone.INTERVIEW_BODY else null,
        confirmBody = if (mode == EntryMode.QUESTIONNAIRE) RecordDangerZone.INTERVIEW_CONFIRM_BODY else null,
    )
}

@Composable
private fun DwInlineRecordDialog(
    repository: WorkshopRepository,
    model: String,
    recordId: String?,
    seed: DwInlineSeed = DwInlineSeed.NONE,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    lookupState: CarryScopeState,
    craftRegister: RegisterLoad = RegisterLoad(),
    adminView: Boolean,
    onOutcome: (DwInlineRecordOutcome) -> Unit,
    onError: (String) -> Unit
) {
    val mode = when (model) {
        "Artisan" -> EntryMode.ARTISAN
        "ProductDocumentation" -> EntryMode.PRODUCT
        "ToolDocumentation" -> EntryMode.TOOL
        "Process" -> EntryMode.PROCESS
        else -> null
    }
    val noun = when (mode) {
        EntryMode.ARTISAN -> "artisan"
        EntryMode.PRODUCT -> "product"
        EntryMode.TOOL -> "tool"
        EntryMode.PROCESS -> "process"
        else -> "record"
    }
    val editing = !recordId.isNullOrBlank()
    val prefill = if (editing || seed.isEmpty) {
        null
    } else {
        Prefill(
            artisanId = seed.artisanId.ifBlank { null },
            artisanName = seed.artisanName.ifBlank { null },
        )
    }
    val abandon = { onOutcome(DwInlineRecordOutcome(saved = false)) }
    val saved = { onOutcome(DwInlineRecordOutcome(saved = true)) }

    Dialog(
        onDismissRequest = abandon,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(color = Canvas, modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (editing) "Update $noun" else "New $noun",
                            display = true,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (editing) {
                                "Saved. Your stage stays open."
                            } else {
                                "$noun saved and linked. Your stage stays open."
                            },
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    IconButton(onClick = abandon) {
                        Icon(Icons.Filled.Close, contentDescription = "Close without saving")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CompositionLocalProvider(LocalUnsavedGuard provides null) {
                        when {
                            mode == null -> Unit
                            editing -> EditScreen(
                                repository = repository,
                                mode = mode,
                                recordId = recordId.orEmpty(),
                                crafts = crafts,
                                artisans = artisans,
                                adminView = adminView,
                                canDelete = false,
                                onDone = saved,
                                onError = onError
                            )
                            mode == EntryMode.ARTISAN -> ArtisanForm(
                                repository = repository,
                                crafts = crafts,
                                lookupState = lookupState,
                                craftRegister = craftRegister,
                                adminView = adminView,
                                onArtisanCreated = { prefill ->
                                    onOutcome(
                                        DwInlineRecordOutcome(
                                            saved = true,
                                            createdId = prefill.artisanId.orEmpty(),
                                            createdLabel = prefill.artisanName.orEmpty(),
                                            createdHint = prefill.place.orEmpty()
                                        )
                                    )
                                },
                                onDone = saved,
                                onError = onError
                            )
                            mode == EntryMode.PRODUCT -> ProductForm(
                                repository = repository,
                                crafts = crafts,
                                artisans = artisans,
                                lookupState = lookupState,
                                adminView = adminView,
                                prefill = prefill,
                                onCreated = { id, label, hint ->
                                    onOutcome(DwInlineRecordOutcome(true, id, label, hint))
                                },
                                onDone = saved,
                                onError = onError
                            )
                            mode == EntryMode.TOOL -> ToolForm(
                                repository = repository,
                                crafts = crafts,
                                artisans = artisans,
                                lookupState = lookupState,
                                adminView = adminView,
                                prefill = prefill,
                                onCreated = { id, label, hint ->
                                    onOutcome(DwInlineRecordOutcome(true, id, label, hint))
                                },
                                onDone = saved,
                                onError = onError
                            )
                            else -> ProcessForm(
                                repository = repository,
                                adminView = adminView,
                                onCreated = { id, label, hint ->
                                    onOutcome(DwInlineRecordOutcome(true, id, label, hint))
                                },
                                onDone = saved,
                                onError = onError
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun <T> cachedRecordForCorrection(
    context: Context,
    kind: String,
    id: String,
    serializer: kotlinx.serialization.KSerializer<T>,
    onNotice: (String) -> Unit,
    fetch: suspend () -> T,
): T {
    val read = ReadCache.read(context, "record:$kind:$id", serializer, fetch)
    if (read.fromCache) {
        onNotice(
            readCacheNotice(read.cachedAt) + " Your correction is checked against the latest version when sent."
        )
    }
    return read.value
}

@Composable
private fun EditScreen(
    repository: WorkshopRepository,
    mode: EntryMode,
    recordId: String,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    adminView: Boolean,
    canDelete: Boolean,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val editContext = LocalContext.current.applicationContext
    var recordCachedNote by remember(recordId) { mutableStateOf<String?>(null) }
    recordCachedNote?.let { CachedCopyNote(it) }
    var loadFailed by remember(recordId) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(recordId) { mutableStateOf(0) }
    var loaded by remember(recordId) { mutableStateOf(false) }
    when (mode) {
        EntryMode.ARTISAN -> {
            var detail by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            var answers by remember(recordId) { mutableStateOf<List<ArtisanAnswerDto>>(emptyList()) }
            var answersLoading by remember(recordId) { mutableStateOf(true) }
            LaunchedEffect(recordId, loadAttempt) {
                loadFailed = null
                runCatching { cachedRecordForCorrection(editContext, "artisan", recordId, ArtisanDetailDto.serializer(), { recordCachedNote = it }) { repository.artisan(recordId) } }
                    .onSuccess { detail = it; loaded = true }
                    .onFailure { loadFailed = it.recordLoadFailure("artisan") }
                runCatching { repository.artisanQuestionnaire(recordId) }
                    .onSuccess { answers = it.answered }
                answersLoading = false
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadFailed) { loadAttempt++ }
            } else {
                ArtisanForm(
                    repository = repository,
                    crafts = crafts,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                RecordCodeSection(repository, DwWorkshopRecordType.ARTISAN, recordId, d.name)
                ArtisanQuestionnairePanel(answers = answers, loading = answersLoading)
            }
        }
        EntryMode.PRODUCT -> {
            var detail by remember(recordId) { mutableStateOf<ProductDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                loadFailed = null
                runCatching { cachedRecordForCorrection(editContext, "product", recordId, ProductDetailDto.serializer(), { recordCachedNote = it }) { repository.product(recordId) } }
                    .onSuccess { detail = it; loaded = true }
                    .onFailure { loadFailed = it.userMessage("Unable to load product") }
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadFailed) { loadAttempt++ }
            } else {
                ProductForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                RecordCodeSection(repository, DwWorkshopRecordType.PRODUCT, recordId, d.productName)
            }
        }
        EntryMode.PROCESS -> {
            var detail by remember(recordId) { mutableStateOf<ProcessDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                loadFailed = null
                runCatching { cachedRecordForCorrection(editContext, "process", recordId, ProcessDetailDto.serializer(), { recordCachedNote = it }) { repository.process(recordId) } }
                    .onSuccess { detail = it; loaded = true }
                    .onFailure { loadFailed = it.userMessage("Unable to load process") }
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadFailed) { loadAttempt++ }
            } else {
                ProcessForm(
                    repository = repository,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                RecordCodeSection(repository, DwWorkshopRecordType.PROCESS, recordId, d.name)
            }
        }
        EntryMode.TOOL -> {
            var detail by remember(recordId) { mutableStateOf<ToolDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                loadFailed = null
                runCatching { cachedRecordForCorrection(editContext, "tool", recordId, ToolDetailDto.serializer(), { recordCachedNote = it }) { repository.tool(recordId) } }
                    .onSuccess { detail = it; loaded = true }
                    .onFailure { loadFailed = it.userMessage("Unable to load tool") }
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadFailed) { loadAttempt++ }
            } else {
                ToolForm(
                    repository = repository,
                    crafts = crafts,
                    artisans = artisans,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                RecordCodeSection(repository, DwWorkshopRecordType.TOOL, recordId, d.toolkitName)
            }
        }
        EntryMode.WORKSHOP -> {
            var detail by remember(recordId) { mutableStateOf<WorkshopDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                loadFailed = null
                runCatching { cachedRecordForCorrection(editContext, "workshop", recordId, WorkshopDetailDto.serializer(), { recordCachedNote = it }) { repository.workshop(recordId) } }
                    .onSuccess { detail = it; loaded = true }
                    .onFailure { loadFailed = it.userMessage("Unable to load workshop") }
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadFailed) { loadAttempt++ }
            } else {
                WorkshopForm(
                    repository = repository,
                    artisans = artisans,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                RecordCodeSection(repository, DwWorkshopRecordType.WORKSHOP, recordId, d.title)
            }
        }
        EntryMode.CRAFT -> {
            var detail by remember(recordId) { mutableStateOf<CraftDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) {
                loadFailed = null
                runCatching { cachedRecordForCorrection(editContext, "craft", recordId, CraftDto.serializer(), { recordCachedNote = it }) { repository.craft(recordId) } }
                    .onSuccess { detail = it; loaded = true }
                    .onFailure { loadFailed = it.userMessage("Unable to load craft") }
            }
            val d = detail
            if (d == null) {
                LoadingCard(mode, loadFailed) { loadAttempt++ }
            } else {
                CraftForm(
                    repository = repository,
                    editing = d,
                    adminView = adminView,
                    onDone = onDone,
                    onError = onError
                )
                RecordCodeSection(repository, DwWorkshopRecordType.CRAFT, recordId, d.name)
            }
        }
        EntryMode.MEDIA -> {
            ViewDataDetail(
                repository = repository,
                mode = EntryMode.MEDIA,
                recordId = recordId,
                onError = onError
            )
            RecordCodeSection(repository, DwWorkshopRecordType.MEDIA, recordId)
        }
        else -> Text("This record type cannot be edited here.", color = Muted)
    }
    if (canDelete && mode != EntryMode.MEDIA && mode != EntryMode.USERS && mode != EntryMode.VIEW_DATA &&
        (loaded || mode !in setOf(EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL, EntryMode.WORKSHOP, EntryMode.CRAFT))
    ) {
        DeleteRecordSection(repository, mode, recordId, onDeleted = onDone, onError = onError)
    }
}

@Composable
private fun CraftForm(
    repository: WorkshopRepository,
    editing: CraftDto? = null,
    adminView: Boolean = false,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var subCategoryId by remember(editing) { mutableStateOf(editing?.subCategoryId ?: "") }
    var pickedCategoryId by remember(editing) { mutableStateOf("") }
    var stateOfOrigin by remember(editing) { mutableStateOf(editing?.stateOfOrigin?.toSet() ?: emptySet()) }
    var stateOfPractice by remember(editing) { mutableStateOf(editing?.stateOfPractice?.toSet() ?: emptySet()) }
    var giStatus by remember(editing) { mutableStateOf(editing?.giStatus ?: "NON_GI") }
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }
    val craftCatalogue by CraftCatalogueCache.catalogue.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { CraftCatalogueCache.cached(context) }
        runCatching { repository.craftCatalogue(context) }
    }
    var subCategoryError by remember { mutableStateOf<String?>(null) }
    val workshop = rememberWorkshopPicker(repository, isEdit, editing?.workshopId, editing)
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val draft = rememberFormDraft(
        key = if (isEdit) "craft:edit:${editing!!.id}" else "craft:new",
        snapshot = {
            mapOf(
                "name" to name, "localName" to localName, "subCategoryId" to subCategoryId,
                "stateOfOrigin" to stateOfOrigin.joinToString("|"), "stateOfPractice" to stateOfPractice.joinToString("|"),
                "giStatus" to giStatus, "description" to description,
            ) + media.draftFields("media")
        },
        restore = { v ->
            name = v["name"] ?: name
            localName = v["localName"] ?: localName
            subCategoryId = v["subCategoryId"] ?: subCategoryId
            v["stateOfOrigin"]?.let { stateOfOrigin = it.split("|").filter(String::isNotBlank).toSet() }
            v["stateOfPractice"]?.let { stateOfPractice = it.split("|").filter(String::isNotBlank).toSet() }
            giStatus = v["giStatus"] ?: giStatus
            description = v["description"] ?: description
            media.restoreDraft("media", v)
        },
    )

    fun submit(forceOffline: Boolean = false, retry: OnlineAttempt<CraftCreateRequest>? = null) {
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus)
            ))) { onError("Fill in the highlighted field."); return }
        if (subCategoryId.isBlank()) {
            subCategoryError = "Choose a sub-category."
            onError("Fill in the highlighted field.")
            return
        }
        subCategoryError = null
        scope.launch {
            if (!forceOffline && !workshop.confirmSubmission()) return@launch
            saving = true
            val createKey = retry?.key ?: java.util.UUID.randomUUID().toString()
            val body = retry?.body ?: CraftCreateRequest(
                name = name.trim(),
                localName = localName.blankToNull(),
                subCategoryId = subCategoryId,
                stateOfOrigin = stateOfOrigin.toList(),
                stateOfPractice = stateOfPractice.toList(),
                giStatus = giStatus,
                description = description.blankToNull(),
                workshopId = workshop.value(),
                recordedAt = if (isEdit) null else Instant.now().toString()
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "craft", offlineFormJson.encodeToString(body),
                    name.trim(), media, name.trim(), "Field media for ${name.trim()}",
                    editingId = editing?.id,
                    unfiled = workshopUnfiledReasons(workshop = workshop.unfiledReason()),
                    force = forceOffline,
                    idempotencyKey = createKey,
                    base = recordBaseJson(CraftDto.serializer(), editing))
            }.getOrNull()
            if (queuedOffline != null) {
                draft.discard()
                media.reset()
                onError(offlineSavedMessage(queuedOffline, isCorrection = isEdit))
                onDone()
                saving = false
                return@launch
            }
            if (forceOffline) {
                onError("Couldn't save this craft. It's still in the form.")
                saving = false
                return@launch
            }
            var landed = false
            runCatching {
                val craftId = if (isEdit) {
                    repository.updateCraft(editing!!.id, body).id
                } else {
                    repository.createCraft(body, createKey).id
                }
                landed = true
                CraftCatalogueCache.invalidate()
                uploadAttachments(repository, context, media, "craft", craftId, name, "Field media for ${name.trim()}")
            }.onSuccess {
                draft.discard()
                media.reset()
                onDone()
            }.onFailure {
                if (!landed && repository.isTransient(it)) {
                    saving = false
                    submit(forceOffline = true, retry = OnlineAttempt(body, createKey))
                    return@launch
                }
                onError(it.userMessage("Unable to save craft"))
            }
            saving = false
        }
    }
    val initialSig = remember(editing) {
        listOf(name, localName, subCategoryId, stateOfOrigin.sorted().joinToString(), stateOfPractice.sorted().joinToString(), giStatus, description).joinToString("")
    }
    val dirty = !saving && (
        listOf(name, localName, subCategoryId, stateOfOrigin.sorted().joinToString(), stateOfPractice.sorted().joinToString(), giStatus, description)
            .joinToString("") != initialSig ||
            workshop.isDirty() || media.uris.isNotEmpty()
    )

    RecordCard(title = if (isEdit) "Update craft" else "Add craft") {
        RegisterUnsavedGuard(dirty = dirty, draft = draft) { submit() }
        FormDraftRestoredNote(draft)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        WorkshopField(state = workshop, saving = saving)
        RequiredInput("Craft name", name, nameError, nameFocus, titleCased = true) { name = it }
        TextInput("Local name", localName) { localName = it }
        val categories = craftCatalogue?.categories.orEmpty().sortedBy { it.sortOrder }
        val categoryId = craftCatalogue?.subCategoryParent(subCategoryId) ?: pickedCategoryId
        DropdownField(
            label = "Category *",
            options = categories.map { it.id to it.name },
            selectedValue = categoryId,
            placeholder = "Choose a category",
            includeNone = false,
            emptyMessage = CraftCatalogueCache.OFFLINE_FIRST_RUN,
        ) { picked ->
            pickedCategoryId = picked
            if (picked != categoryId) subCategoryId = ""
        }
        DropdownField(
            label = "Sub-category *",
            options = categories.firstOrNull { it.id == categoryId }?.subCategories.orEmpty()
                .sortedBy { it.sortOrder }.map { it.id to it.name },
            selectedValue = subCategoryId,
            placeholder = if (categoryId.isBlank()) "Choose a category first" else "Choose a sub-category",
            includeNone = false,
            enabled = categoryId.isNotBlank(),
        ) { subCategoryId = it; subCategoryError = null }
        subCategoryError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        SearchableMultiSelectField(
            label = "State of origin",
            options = CraftCatalogueCache.STATE_OPTIONS.map { SelectOption(it, it) },
            selected = stateOfOrigin,
            placeholder = "Choose states",
            searchable = true,
            bulk = false,
            onSelectedChange = { stateOfOrigin = it }
        )
        SearchableMultiSelectField(
            label = "State of practice",
            options = CraftCatalogueCache.STATE_OPTIONS.map { SelectOption(it, it) },
            selected = stateOfPractice,
            placeholder = "Choose states",
            searchable = true,
            bulk = false,
            onSelectedChange = { stateOfPractice = it }
        )
        DropdownField(
            label = "GI status *",
            options = listOf("GI" to "GI", "NON_GI" to "Non GI"),
            selectedValue = giStatus,
            includeNone = false,
            searchable = false,
        ) { giStatus = it }
        TextInput("Description", description, minLines = 3, rich = true) { description = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "craft", recordId = editing!!.id, onError = onError)
        }
        MediaCaptureSection(repository = repository, media = media, onMessage = LocalAppNotice.current, onError = onError)
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update craft" else "Save craft"
        ) { submit() }
    }
}

@Composable
private fun ArtisanForm(
    repository: WorkshopRepository,
    crafts: List<CraftDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    craftRegister: RegisterLoad = RegisterLoad(),
    editing: ArtisanDetailDto? = null,
    prefill: Prefill? = null,
    fromCard: String? = null,
    adminView: Boolean = false,
    onArtisanCreated: (Prefill) -> Unit = {},
    onOpenArtisan: ((String) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    val canReadIdentityCards = remember {
        repository.cachedUser()?.let { FieldPermissions.canRunDesignWorkshops(it) } == true
    }
    val keepIdentityPhotograph: (suspend (android.net.Uri) -> String?)? =
        if (isEdit && canReadIdentityCards) {
            { uri ->
                repository.uploadMedia(
                    context = context,
                    uri = uri,
                    linkedRecordType = "artisan",
                    linkedRecordId = editing!!.id,
                    caption = "Identity card — kept on this record by the designer's decision",
                    location = null,
                    titleHint = editing.name,
                    overrideBaseName = "identity-card",
                ).id
            }
        } else {
            null
        }
    var name by remember(editing) { mutableStateOf(editing?.name ?: prefill?.artisanName ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var gender by remember(editing) { mutableStateOf(editing?.gender?.takeIf { it.isNotBlank() } ?: "") }
    var phone by remember(editing) { mutableStateOf(StandardValues.phoneForEditing(editing?.phoneCountryCode, editing?.phone)) }
    var email by remember(editing) { mutableStateOf(editing?.email ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var address by remember(editing) { mutableStateOf(editing?.address ?: "") }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    val storedAadhaarMask = editing?.aadhaarNumber?.takeIf { it.isNotBlank() }
    var aadhaar by remember(editing) {
        mutableStateOf(editing?.aadhaarNumber?.takeIf { ArtisanIdentity.isAadhaar(it) }.orEmpty())
    }
    var pehchanAvailable by remember(editing) { mutableStateOf<Boolean?>(editing?.pehchanCardAvailable) }
    var pehchanNumber by remember(editing) { mutableStateOf(editing?.pehchanCardNumber.orEmpty()) }
    var crossMatch by remember(editing) { mutableStateOf<CrossPortalMatchDto?>(null) }
    var dateOfBirth by remember(editing) {
        mutableStateOf<java.time.LocalDate?>(
            editing?.dateOfBirth?.take(10)?.let { parseFieldDate(it) }
        )
    }
    var craftStartDate by remember(editing) {
        mutableStateOf<java.time.LocalDate?>(
            editing?.craftStartDate?.take(10)?.let { parseFieldDate(it) }
        )
    }
    var experienceYears by remember(editing) {
        mutableStateOf(editing?.experienceYears?.toString().orEmpty())
    }
    var experienceMonths by remember(editing) {
        mutableStateOf(editing?.experienceMonths?.toString().orEmpty())
    }
    var dosItems by remember(editing) { mutableStateOf(splitNumbered(editing?.dos)) }
    var dontsItems by remember(editing) { mutableStateOf(splitNumbered(editing?.donts)) }
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }
    val link = rememberRecordWorkshopLink(
        repository = repository,
        isEdit = isEdit,
        initialWorkshopId = editing?.workshopId,
        initialDesignWorkshopId = editing?.designWorkshopId,
        resetKey = editing,
    )
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.ARTISAN_FORM,
        scopes = listOf(carryScope(CarryNode.CRAFT, lookupState, crafts) { it.id }),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        carried.workshopId?.let { link.applyCarriedWorkshop(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var saving by remember { mutableStateOf(false) }
    val hasCraft = craftId.isNotBlank()
    var nameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var craftError by remember { mutableStateOf<String?>(null) }
    var dosError by remember { mutableStateOf<String?>(null) }
    var dontsError by remember { mutableStateOf<String?>(null) }
    var phoneError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var aadhaarError by remember { mutableStateOf<String?>(null) }
    var pehchanError by remember { mutableStateOf<String?>(null) }
    var dateOfBirthError by remember { mutableStateOf<String?>(null) }
    var craftStartDateError by remember { mutableStateOf<String?>(null) }
    var aadhaarDuplicate by remember(editing) { mutableStateOf<String?>(null) }
    var duplicateShown by remember(editing) { mutableStateOf<List<DuplicateCandidateDto>?>(null) }
    var duplicateAcked by remember(editing) { mutableStateOf<Set<String>>(emptySet()) }
    val draft = rememberFormDraft(
        key = if (isEdit) "artisan:edit:${editing!!.id}" else "artisan:new:${prefill?.craftId.orEmpty()}",
        snapshot = {
            mapOf(
                "name" to name, "localName" to localName, "gender" to gender, "phone" to phone,
                "email" to email, "place" to place, "address" to address, "notes" to notes,
                "aadhaar" to aadhaar, "pehchanAvailable" to pehchanAvailable.toString(),
                "pehchanNumber" to pehchanNumber, "dateOfBirth" to (dateOfBirth?.toString() ?: ""),
                "craftStartDate" to (craftStartDate?.toString() ?: ""),
                "experienceYears" to experienceYears, "experienceMonths" to experienceMonths,
                "dos" to draftList(dosItems), "donts" to draftList(dontsItems), "craftId" to craftId,
                "status" to status,
            ) + media.draftFields("media")
        },
        restore = { v ->
            name = v["name"] ?: name
            localName = v["localName"] ?: localName
            gender = v["gender"] ?: gender
            phone = v["phone"] ?: phone
            email = v["email"] ?: email
            place = v["place"] ?: place
            address = v["address"] ?: address
            notes = v["notes"] ?: notes
            aadhaar = v["aadhaar"] ?: aadhaar
            v["pehchanAvailable"]?.let { pehchanAvailable = it.toBooleanStrictOrNull() }
            pehchanNumber = v["pehchanNumber"] ?: pehchanNumber
            v["dateOfBirth"]?.let { dateOfBirth = draftDate(it) }
            v["craftStartDate"]?.let { craftStartDate = draftDate(it) }
            experienceYears = v["experienceYears"] ?: experienceYears
            experienceMonths = v["experienceMonths"] ?: experienceMonths
            v["dos"]?.let { dosItems = parseDraftList(it).ifEmpty { listOf("") } }
            v["donts"]?.let { dontsItems = parseDraftList(it).ifEmpty { listOf("") } }
            craftId = v["craftId"] ?: craftId
            status = v["status"] ?: status
            media.restoreDraft("media", v)
        },
    )
    val keepsStoredAadhaar = isEdit && storedAadhaarMask != null && aadhaar.isBlank()
    val aadhaarRequired = (!isEdit || !editing?.aadhaarNumber.isNullOrBlank()) && !keepsStoredAadhaar
    val nameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val craftFocus = remember { FocusRequester() }
    val dosFocus = remember { FocusRequester() }
    val dontsFocus = remember { FocusRequester() }
    val dateOfBirthFocus = remember { FocusRequester() }
    val craftStartFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }
    val aadhaarFocus = remember { FocusRequester() }
    val pehchanFocus = remember { FocusRequester() }

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    LaunchedEffect(aadhaar, editing?.id) {
        aadhaarDuplicate = null
        if (aadhaar.isBlank() || aadhaarValidationError(aadhaar) != null) return@LaunchedEffect
        delay(400)
        val match = runCatching { repository.lookupArtisanByAadhaar(aadhaar) }.getOrNull()?.artisan
        if (match == null || match.id == editing?.id) return@LaunchedEffect
        val where = listOfNotNull(
            match.place?.takeIf { it.isNotBlank() },
            match.craft?.takeIf { it.isNotBlank() }
        ).joinToString(", ")
        aadhaarDuplicate = buildString {
            append(match.name.ifBlank { "Another artisan" })
            if (where.isNotEmpty()) append(" ($where)")
            append(" already has this Aadhaar number. Open that artisan instead of ")
            append("creating a duplicate.")
        }
    }

    fun submit(forceOffline: Boolean = false, retry: OnlineAttempt<ArtisanCreateRequest>? = null) {
        val dosText = joinNumbered(dosItems)
        val dontsText = joinNumbered(dontsItems)
        if (!validateRequired(listOf(
                RequiredCheck(name.isBlank(), { nameError = it }, nameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus),
                RequiredCheck(!hasCraft, { craftError = it }, craftFocus),
                RequiredCheck(dosText.isBlank(), { dosError = it }, dosFocus),
                RequiredCheck(dontsText.isBlank(), { dontsError = it }, dontsFocus)
            ))) { onError("Fill in the highlighted field."); return }
        dateOfBirthError = if (dateOfBirth == null) "Enter the date of birth." else null
        craftStartDateError = if (craftStartDate == null) "Enter the craft start date." else null
        missingArtisanDatesSentence(dateOfBirth?.toString(), craftStartDate?.toString())?.let { sentence ->
            onError(sentence); return
        }
        artisanPhoneValidationError(phone)?.let { msg -> phoneError = msg; onError("Fix the phone number highlighted above."); return }
        phoneError = null
        DwTextFormats.emailError(email)?.let { message ->
            emailError = message
            runCatching { emailFocus.requestFocus() }
            onError("Fix the email highlighted above."); return
        }
        emailError = null
        if (aadhaarRequired && aadhaar.isBlank()) {
            aadhaarError = "Enter the 12-digit Aadhaar number."
            runCatching { aadhaarFocus.requestFocus() }
            onError("Aadhaar number required — see the highlighted field."); return
        }
        aadhaarValidationError(aadhaar)?.let { msg ->
            aadhaarError = msg
            runCatching { aadhaarFocus.requestFocus() }
            onError("Fix the Aadhaar number highlighted above."); return
        }
        aadhaarError = null
        if (pehchanAvailable == null) {
            pehchanError = "Answer whether the artisan has a Pehchan card."
            runCatching { pehchanFocus.requestFocus() }
            onError("Answer the Pehchan card question."); return
        }
        if (pehchanAvailable == true && pehchanNumber.isBlank()) {
            pehchanError = "Enter the Pehchan Card number, or set the card to 'No'."
            runCatching { pehchanFocus.requestFocus() }
            onError("Fix the highlighted Pehchan Card details."); return
        }
        pehchanError = null
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!isEdit && !forceOffline && retry == null && repository.isOnline(context)) {
                saving = true
                val found = runCatching {
                    repository.checkDuplicates(
                        DuplicateCheckRequest(
                            name = name.trim(),
                            localName = localName.blankToNull(),
                            gender = gender.blankToNull(),
                            dateOfBirth = dateOfBirth?.toString(),
                            phone = phone.blankToNull(),
                            place = place.trim(),
                            state = media.location?.state,
                            district = media.location?.district,
                            village = media.location?.village,
                            craftId = craftId.ifBlank { null },
                            craftName = null,
                            aadhaar = if (keepsStoredAadhaar) null else aadhaar.blankToNull(),
                            pehchanCardNumber = if (pehchanAvailable == true) pehchanNumber.blankToNull() else null,
                        )
                    )
                }.getOrNull().orEmpty()
                saving = false
                val show = PossibleDuplicates.toShow(found, duplicateAcked)
                if (show.isNotEmpty()) {
                    duplicateShown = show
                    return@launch
                }
            }
            if (!forceOffline && !link.confirmSubmission()) return@launch
            saving = true
            val createKey = retry?.key ?: java.util.UUID.randomUUID().toString()
            val body = retry?.body ?: ArtisanCreateRequest(
                name = name.trim(),
                localName = localName.blankToNull(),
                gender = gender.blankToNull(),
                phone = StandardValues.normalizePhone(null, phone).digits ?: phone.blankToNull(),
                phoneCountryCode = StandardValues.normalizePhone(null, phone).code,
                email = email.blankToNull(),
                place = place.trim(),
                address = address.blankToNull(),
                notes = notes.blankToNull(),
                aadhaarNumber = if (keepsStoredAadhaar) null else aadhaar.trim(),
                pehchanCardAvailable = pehchanAvailable,
                pehchanCardNumber = if (pehchanAvailable == true) pehchanNumber.blankToNull() else null,
                dateOfBirth = dateOfBirth?.toString(),
                craftStartDate = craftStartDate?.toString(),
                experienceYears = experienceYears.toIntOrNull(),
                experienceMonths = experienceMonths.toIntOrNull(),
                dos = dosText,
                donts = dontsText,
                craftId = craftId.ifBlank { null },
                workshopId = link.workshopId(),
                designWorkshopId = link.designWorkshopId(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location),
                formContract = ARTISAN_FORM_CONTRACT,
                duplicateAcknowledged = if (isEdit) null else PossibleDuplicates.acknowledged(duplicateAcked)
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "artisan", offlineFormJson.encodeToString(body),
                    name.trim(), media, name.trim(), "Field media for ${name.trim()}",
                    editingId = editing?.id,
                    unfiled = link.unfiledReasons(),
                    force = forceOffline,
                    idempotencyKey = createKey,
                    base = recordBaseJson(ArtisanDetailDto.serializer(), editing))
            }.getOrNull()
            if (queuedOffline != null) {
                draft.discard(); media.reset()
                val linkNote = if (!isEdit && crossMatch != null) " ${CrossPortalCopy.LINK_AFTER_SYNC}" else ""
                onError(offlineSavedMessage(queuedOffline, isCorrection = isEdit) + linkNote)
                onDone()
                saving = false
                return@launch
            }
            if (forceOffline) {
                onError("Couldn't save this artisan. It's still in the form.")
                saving = false
                return@launch
            }
            var landed = false
            runCatching {
                val artisanId = if (isEdit) {
                    repository.updateArtisan(editing!!.id, body).id
                } else {
                    repository.createArtisan(body, createKey).id
                }
                landed = true
                if (!isEdit) {
                    crossMatch?.let { match ->
                        runCatching { CrossPortalClient.create(context).createLink(artisanId, match.remoteArtisanId, fromCard) }
                    }
                }
                uploadAttachments(repository, context, media, "artisan", artisanId, name, "Field media for ${name.trim()}")
                artisanId
            }.onSuccess { artisanId ->
                if (isEdit) {
                    draft.discard(); media.reset()
                    onDone()
                } else {
                    val resolvedCraftName = crafts.firstOrNull { it.id == craftId }?.name
                    val prefillOut = Prefill(
                        artisanId = artisanId,
                        artisanName = name.trim(),
                        place = place.trim(),
                        craftId = craftId.ifBlank { null },
                        craftName = resolvedCraftName
                    )
                    draft.discard(); media.reset()
                    onArtisanCreated(prefillOut)
                }
            }.onFailure {
                if (!landed && repository.isTransient(it)) {
                    saving = false
                    submit(forceOffline = true, retry = OnlineAttempt(body, createKey))
                    return@launch
                }
                val held = if (!isEdit && it is HttpException) {
                    PossibleDuplicates.heldCandidates(it.code(), peekErrorBody(it))
                } else {
                    null
                }
                if (held != null) {
                    duplicateShown = PossibleDuplicates.toShow(held, duplicateAcked).ifEmpty { held.take(PossibleDuplicates.SHOWN_MAX) }
                    saving = false
                    return@launch
                }
                onError(it.apiErrorMessage("Unable to save artisan"))
            }
            saving = false
        }
    }
    duplicateShown?.let { shown ->
        PossibleDuplicateSheet(
            candidates = shown,
            saving = saving,
            onOpen = onOpenArtisan?.let { open -> { id: String -> duplicateShown = null; open(id) } },
            onDifferentPerson = {
                duplicateAcked = duplicateAcked + shown.map { it.artisan.id }
                duplicateShown = null
                submit()
            },
            onCancel = { duplicateShown = null },
        )
    }
    fun formSignature(): String = listOf(
        name, localName, gender, phone, email, place, address, notes, aadhaar,
        pehchanAvailable.toString(), pehchanNumber, joinNumbered(dosItems), joinNumbered(dontsItems),
        craftId.exceptCarried(carry.offer?.context?.craftId ?: prefill?.craftId), status
    ).joinToString("\u0000")
    val initialSig = remember(editing) { formSignature() }
    val dirty = !saving && (
        formSignature() != initialSig ||
            link.isDirty() || media.uris.isNotEmpty()
    )

    val completenessRules = rememberCompletenessRules()
    val liveCompleteness = completenessRules?.let { rules ->
        evaluateCompleteness(
            rules,
            CompletenessKind.ARTISAN,
            mapOf("dateOfBirth" to dateOfBirth, "craftStartDate" to craftStartDate, "craftId" to craftId, "dos" to joinNumbered(dosItems), "donts" to joinNumbered(dontsItems), "locationId" to (media.location ?: editing?.location)),
            liveCompletenessCounts(rules, CompletenessKind.ARTISAN, editing?.completeness, mapOf("photos" to rememberImageCount(media.uris))),
        )
    }

    RecordCard(title = if (isEdit) "Update artisan" else "Add artisan") {
        liveCompleteness?.let { LiveCompletenessLine(it) }
        RegisterUnsavedGuard(dirty = dirty, draft = draft) { submit() }
        FormDraftRestoredNote(draft)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        RecordWorkshopField(link = link, saving = saving)
        RequiredInput("Name", name, nameError, nameFocus, titleCased = true) { name = it }
        TextInput("Local name", localName) { localName = it }
        CompletenessAnchor("craft", craftFocus) {
            CraftPickerField(
                label = "Craft",
                required = true,
                selectedIds = listOfNotNull(craftId.ifBlank { null }),
                fallbackNames = editing?.craft?.let { mapOf(it.id to it.name) }.orEmpty(),
                isError = craftError != null,
                supportingText = craftError,
                refresh = { repository.craftCatalogue(context) },
                modifier = Modifier.focusRequester(craftFocus),
                onChange = { ids ->
                    craftId = ids.firstOrNull().orEmpty()
                    craftError = null
                    CraftCatalogueCache.catalogue.value?.craft(craftId)?.let {
                        carry.remember(CarryContext(craftId = it.id, craftName = it.name), explicit = true)
                    }
                }
            )
        }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        DropdownField(
            label = "Gender",
            options = genderOptions.map { it to it },
            selectedValue = gender,
            placeholder = "Select…",
            includeNone = true
        ) { gender = it }
        CompletenessAnchor("dateOfBirth", dateOfBirthFocus) {
            CalendarField(
                label = "Date of birth *",
                modifier = Modifier.focusRequester(dateOfBirthFocus),
                value = dateOfBirth,
                onValueChange = { dateOfBirth = it; dateOfBirthError = null },
                maximum = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                clearable = true,
                supportingText = dateOfBirthError ?: "Shown as an age."
            )
        }
        CompletenessAnchor("craftStartDate", craftStartFocus) {
            CalendarField(
                label = "Craft start date *",
                modifier = Modifier.focusRequester(craftStartFocus),
                value = craftStartDate,
                onValueChange = { craftStartDate = it; craftStartDateError = null },
                maximum = java.time.LocalDate.now(java.time.ZoneOffset.UTC),
                clearable = true,
                supportingText = craftStartDateError ?: "Shown as years of experience."
            )
        }
        OutlinedTextField(
            value = experienceYears,
            onValueChange = { typed -> experienceYears = typed.filter { it.isDigit() }.take(2) },
            label = { Text("Experience (years)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        DropdownField(
            label = "Experience (months)",
            options = EXPERIENCE_MONTH_OPTIONS.map { it.toString() to experienceMonthsLabel(it) },
            selectedValue = experienceMonths,
            placeholder = EXPERIENCE_NOT_STATED,
            includeNone = true
        ) { experienceMonths = it }
        ArtisanPhoneField(value = phone, error = phoneError) { phone = it; phoneError = null }
        OutlinedTextField(
            value = email,
            onValueChange = { email = it; emailError = null },
            label = { Text("Email") },
            isError = emailError != null,
            supportingText = emailError?.let { msg -> { Text(msg) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(emailFocus)
        )
        TextInput("Address", address, minLines = 2) { address = it }
        MultiNoteInput(value = notes) { notes = it }
        Text("Identity", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Stored securely; masked when shared or exported.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        ArtisanAadhaarField(
            value = aadhaar,
            error = aadhaarError,
            warning = aadhaarDuplicate,
            required = aadhaarRequired,
            focusRequester = aadhaarFocus,
            onRecord = storedAadhaarMask?.takeIf { aadhaar.isBlank() }
        ) { aadhaar = it; aadhaarError = null }
        if (canReadIdentityCards) {
            DwIdentityCardControl(
                targetLabel = "the Aadhaar number",
                kind = DwIdentityKind.AADHAAR,
                repository = repository,
                enabled = !saving,
                onUse = { digits -> aadhaar = digits; aadhaarError = null },
                onError = onError,
                onKeepPhotograph = keepIdentityPhotograph,
            )
        }
        DropdownField(
            label = "Artisan Pehchan Card available *",
            options = listOf("yes" to "Yes", "no" to "No"),
            selectedValue = when (pehchanAvailable) { true -> "yes"; false -> "no"; null -> "" },
            placeholder = "Has the artisan a Pehchan card?",
            includeNone = false
        ) { choice ->
            pehchanAvailable = choice == "yes"
            pehchanError = null
            if (pehchanAvailable == false) pehchanNumber = ""
        }
        OutlinedTextField(
            value = pehchanNumber,
            onValueChange = { pehchanNumber = it.uppercase(); pehchanError = null },
            label = {
                Text(
                    requiredMarked(
                        if (pehchanAvailable == true) "Artisan Pehchan Card number *" else "Artisan Pehchan Card number"
                    )
                )
            },
            placeholder = { Text(if (pehchanAvailable == true) "As printed on the card" else "No card on record") },
            enabled = pehchanAvailable == true,
            isError = pehchanError != null,
            supportingText = {
                val hint = pehchanError
                    ?: if (pehchanAvailable == true) {
                        "The PM Vishwakarma artisan ID printed on the card."
                    } else {
                        "Set \"available\" to Yes to enter a number."
                    }
                Text(hint, color = if (pehchanError != null) MaterialTheme.colorScheme.error else Muted, style = MaterialTheme.typography.bodySmall)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(pehchanFocus)
        )
        if (canReadIdentityCards && pehchanAvailable == true) {
            DwIdentityCardControl(
                targetLabel = "the Pehchan card number",
                kind = DwIdentityKind.PEHCHAN,
                repository = repository,
                enabled = !saving,
                onUse = { value -> pehchanNumber = value; pehchanError = null },
                onError = onError,
                onKeepPhotograph = keepIdentityPhotograph,
            )
        }
        if (isEdit) {
            CrossPortalLinkCard(artisanId = editing!!.id) { artisan, changed ->
                val values = CrossPortalPrefill.fromRefreshed(artisan, changed)
                values.name?.let { name = it }
                values.localName?.let { localName = it }
                values.gender?.let { gender = it }
                values.pehchanCardAvailable?.let { pehchanAvailable = it }
                values.pehchanCardNumber?.let { pehchanNumber = it }
                values.dateOfBirth?.let { parseFieldDate(it) }?.let { dateOfBirth = it }
            }
        } else {
            CrossPortalFindCard(
                aadhaar = aadhaar,
                pehchanNumber = pehchanNumber,
                filled = crossMatch,
                cardInput = fromCard,
                onOpenArtisan = onOpenArtisan,
            ) { match ->
                val values = CrossPortalPrefill.keepTypedPehchan(CrossPortalPrefill.from(match), pehchanNumber)
                values.name?.let { name = it }
                values.localName?.let { localName = it }
                values.gender?.let { gender = it }
                values.phone?.let { phone = it }
                values.place?.let { place = it }
                values.pehchanCardAvailable?.let { pehchanAvailable = it }
                values.pehchanCardNumber?.let { pehchanNumber = it }
                values.dateOfBirth?.let { parseFieldDate(it) }?.let { dateOfBirth = it }
                values.craftStartDate?.let { parseFieldDate(it) }?.let { craftStartDate = it }
                values.experienceYears?.let { experienceYears = it.toString() }
                values.experienceMonths?.let { experienceMonths = it.toString() }
                values.craftId?.let { craftId = it }
                match.statedAddress?.let { stated ->
                    media.location?.let { here ->
                        media.location = CrossPortalPrefill.withStatedAddress(here, stated)
                    }
                }
                crossMatch = match
            }
        }
        CompletenessAnchor("dos", dosFocus) {
            NumberedListInput(
                label = "Do's",
                items = dosItems,
                error = dosError,
                focusRequester = dosFocus,
                helper = "What the artisan has learnt to do. One point per line."
            ) { dosItems = it; dosError = null }
        }
        CompletenessAnchor("donts", dontsFocus) {
            NumberedListInput(
                label = "Don'ts",
                items = dontsItems,
                error = dontsError,
                focusRequester = dontsFocus,
                helper = "What the artisan avoids. One point per line."
            ) { dontsItems = it; dontsError = null }
        }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        if (isEdit) {
            ArtisanConsentRow(repository = repository, artisanId = editing!!.id)
            RecordMediaSection(repository = repository, context = context, linkedType = "artisan", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            CompletenessAnchor("location") {
                MediaCaptureSection(repository = repository, media = media, onMessage = LocalAppNotice.current, onError = onError)
            }
        }
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update artisan" else "Save artisan"
        ) { submit() }
    }
}

internal const val WORKSHOP_CREATE_REFUSAL =
    "Workshops are created by the ministry — a Ministry Admin, an admin or the master admin. Ask " +
        "them to open the workshop for your cluster; it will appear in your list as soon as you are " +
        "added to it, and you can then record artisans, products, tools, processes and interviews " +
        "against it exactly as before. Any workshop you already have access to is open to you now."

@Composable
private fun WorkshopCreateRefusedCard(onOpenExisting: (() -> Unit)? = null) {
    RecordCard(title = "Add workshop") {
        Text(WORKSHOP_CREATE_REFUSAL, color = Muted, style = MaterialTheme.typography.bodySmall)
        if (onOpenExisting != null) {
            ActionRow {
                TextButton(onClick = onOpenExisting, contentPadding = PaddingValues(0.dp)) {
                    Text("Open an existing workshop")
                }
            }
        }
    }
}

@Composable
private fun WorkshopForm(
    repository: WorkshopRepository,
    artisans: List<ArtisanDto>,
    editing: WorkshopDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onOpenRecord: ((EntryMode, String) -> Unit)? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var workshopType by remember(editing) { mutableStateOf(editing?.workshopType ?: "OTHER") }
    var title by remember(editing) { mutableStateOf(editing?.title ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var description by remember(editing) { mutableStateOf(editing?.description ?: "") }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    var startDate by remember(editing) { mutableStateOf(parseIsoToLocalDate(editing?.startDate)) }
    var endDate by remember(editing) { mutableStateOf(parseIsoToLocalDate(editing?.endDate)) }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var selectedArtisans by remember(editing) {
        mutableStateOf(
            editing?.artisans?.map { it.artisanId }?.toSet()
                ?: prefill?.artisanId?.let { setOf(it) }
                ?: emptySet()
        )
    }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var selectedCrafts by remember(editing) {
        mutableStateOf(editing?.crafts?.map { it.craftId }?.toSet() ?: emptySet())
    }
    var saving by remember { mutableStateOf(false) }
    var titleError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val draft = rememberFormDraft(
        key = if (isEdit) "workshop:edit:${editing!!.id}" else "workshop:new:${prefill?.artisanId.orEmpty()}",
        snapshot = {
            mapOf(
                "workshopType" to workshopType, "title" to title, "place" to place,
                "description" to description, "notes" to notes,
                "startDate" to (startDate?.toString() ?: ""), "endDate" to (endDate?.toString() ?: ""),
                "status" to status, "artisans" to draftList(selectedArtisans.sorted()),
                "crafts" to draftList(selectedCrafts.sorted()),
            ) + media.draftFields("media")
        },
        restore = { v ->
            workshopType = v["workshopType"] ?: workshopType
            title = v["title"] ?: title
            place = v["place"] ?: place
            description = v["description"] ?: description
            notes = v["notes"] ?: notes
            v["startDate"]?.let { startDate = draftDate(it) }
            v["endDate"]?.let { endDate = draftDate(it) }
            status = v["status"] ?: status
            v["artisans"]?.let { selectedArtisans = parseDraftList(it).toSet() }
            v["crafts"]?.let { selectedCrafts = parseDraftList(it).toSet() }
            media.restoreDraft("media", v)
        },
    )

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }
    LaunchedEffect(Unit) {
        loadCraftRegister(context, repository) { crafts = it }
    }

    fun submit(forceOffline: Boolean = false, retry: OnlineAttempt<WorkshopCreateRequest>? = null) {
        if (!validateRequired(listOf(
                RequiredCheck(title.isBlank(), { titleError = it }, titleFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Fill in the highlighted field."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            saving = true
            val start = (startDate ?: LocalDate.now()).toIsoInstant()
            val end = (endDate ?: startDate ?: LocalDate.now()).toIsoInstant()
            val originalArtisans = editing?.artisans?.map { it.artisanId }?.toSet() ?: emptySet()
            val originalCrafts = editing?.crafts?.map { it.craftId }?.toSet() ?: emptySet()
            var queueForCards = false
            val cardValues = selectedArtisans.filter { ArtisanCardCode.isPending(it) }.toSet()
            if (retry == null && cardValues.isNotEmpty()) {
                when (val linked = resolvePendingCards(cardValues) { input -> repository.resolveArtisanCard(input, emptyList()) }) {
                    is PendingCardOutcome.Resolved -> selectedArtisans = selectedArtisans.map { linked.ids[it] ?: it }.toSet()
                    PendingCardOutcome.Offline -> queueForCards = true
                    is PendingCardOutcome.Held -> {
                        onError(linked.message)
                        saving = false
                        return@launch
                    }
                }
            }
            val mustQueue = forceOffline || queueForCards
            val artisanIdsParam = if (!isEdit || selectedArtisans != originalArtisans) selectedArtisans.toList() else null
            val craftIdsParam = if (!isEdit || selectedCrafts != originalCrafts) selectedCrafts.toList() else null
            val createKey = retry?.key ?: java.util.UUID.randomUUID().toString()
            val body = retry?.body ?: WorkshopCreateRequest(
                title = title.trim(),
                workshopType = workshopType,
                date = start,
                startDate = start,
                endDate = end,
                place = place.trim(),
                description = description.blankToNull(),
                notes = notes.blankToNull(),
                artisanIds = artisanIdsParam,
                craftIds = craftIdsParam,
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "workshop", offlineFormJson.encodeToString(body),
                    title.trim(), media, title.trim(), "Field media for ${title.trim()}",
                    editingId = editing?.id,
                    force = mustQueue,
                    idempotencyKey = createKey,
                    base = recordBaseJson(WorkshopDetailDto.serializer(), editing))
            }.getOrNull()
            if (queuedOffline != null) {
                draft.discard(); media.reset()
                onError(offlineSavedMessage(queuedOffline, isCorrection = isEdit))
                onDone()
                saving = false
                return@launch
            }
            if (mustQueue) {
                onError("Couldn't save this workshop. It's still in the form.")
                saving = false
                return@launch
            }
            var landed = false
            runCatching {
                val workshopId = if (isEdit) {
                    repository.updateWorkshop(editing!!.id, body).id
                } else {
                    repository.createWorkshop(body, createKey).id
                }
                landed = true
                uploadAttachments(repository, context, media, "workshop", workshopId, title, "Field media for ${title.trim()}")
            }.onSuccess {
                draft.discard(); media.reset()
                onDone()
            }.onFailure {
                if (!landed && repository.isTransient(it)) {
                    saving = false
                    submit(forceOffline = true, retry = OnlineAttempt(body, createKey))
                    return@launch
                }
                onError(it.userMessage("Unable to save workshop"))
            }
            saving = false
        }
    }
    val initialSig = remember(editing) {
        listOf(workshopType, title, place, description, notes, status, startDate?.toString() ?: "", endDate?.toString() ?: "",
            selectedArtisans.sorted().joinToString(","), selectedCrafts.sorted().joinToString(",")).joinToString("")
    }
    val dirty = !saving && (
        listOf(workshopType, title, place, description, notes, status, startDate?.toString() ?: "", endDate?.toString() ?: "",
            selectedArtisans.sorted().joinToString(","), selectedCrafts.sorted().joinToString(",")).joinToString("") != initialSig ||
            media.uris.isNotEmpty()
    )

    if (!isEdit && repository.cachedUser()?.isAdminUser() == true) {
        WorkshopMappingCard(repository = repository, onOpenRecord = onOpenRecord, onError = onError)
    }

    val completenessRules = rememberCompletenessRules()
    val liveCompleteness = completenessRules?.let { rules ->
        evaluateCompleteness(
            rules,
            CompletenessKind.WORKSHOP,
            mapOf("startDate" to startDate, "endDate" to endDate),
            liveCompletenessCounts(rules, CompletenessKind.WORKSHOP, editing?.completeness, mapOf("artisans" to selectedArtisans.size, "crafts" to selectedCrafts.size, "photos" to rememberImageCount(media.uris))),
        )
    }

    RecordCard(title = if (isEdit) "Update workshop" else "Add workshop") {
        liveCompleteness?.let { LiveCompletenessLine(it) }
        RegisterUnsavedGuard(dirty = dirty, draft = draft) { submit() }
        FormDraftRestoredNote(draft)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        DropdownField(
            label = "Kind of workshop",
            options = workshopTypeOptions,
            selectedValue = workshopType,
            includeNone = false
        ) { workshopType = it }
        Text(
            "Design & prototype workshops can use this for their cover details.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        RequiredInput("Workshop title", title, titleError, titleFocus, titleCased = true) { title = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        Text("Workshop duration", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        CompletenessAnchor("startDate") {
            CompletenessAnchor("endDate") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) {
                        DatePickerField("Start date", startDate) { picked ->
                            startDate = picked
                            if (endDate == null || endDate!!.isBefore(picked)) endDate = picked
                        }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DatePickerField("End date", endDate) { endDate = it }
                    }
                }
            }
        }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        TextInput("Description", description, minLines = 3, rich = true) { description = it }
        MultiNoteInput(value = notes) { notes = it }
        CompletenessAnchor("artisans") {
            ArtisanMultiSelectField(
                label = "Linked artisans",
                artisans = artisans,
                selectedIds = selectedArtisans,
                repository = repository
            ) { id ->
                selectedArtisans = if (selectedArtisans.contains(id)) selectedArtisans - id else selectedArtisans + id
            }
        }
        CompletenessAnchor("crafts") {
            CraftPickerField(
                label = "Crafts covered",
                multiple = true,
                selectedIds = selectedCrafts.toList(),
                fallbackNames = editing?.crafts?.mapNotNull { link -> link.craft?.let { it.id to it.name } }?.toMap().orEmpty(),
                refresh = { repository.craftCatalogue(context) },
            ) { next -> selectedCrafts = next.toSet() }
        }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "workshop", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            MediaCaptureSection(repository = repository, media = media, onMessage = LocalAppNotice.current, onError = onError)
        }
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update workshop" else "Save workshop"
        ) { submit() }
    }
}

@Composable
private fun ProductForm(
    repository: WorkshopRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    editing: ProductDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onCreated: OnRecordCreated? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val isEdit = editing != null
    var productName by remember(editing) { mutableStateOf(editing?.productName ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var craftName by remember(editing) { mutableStateOf(editing?.craftName ?: prefill?.craftName ?: "") }
    var artisanName by remember(editing) { mutableStateOf(editing?.artisanName ?: prefill?.artisanName ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var craftId by remember(editing) { mutableStateOf(editing?.craftId ?: prefill?.craftId ?: "") }
    var artisanId by remember(editing) { mutableStateOf(editing?.artisanId ?: prefill?.artisanId ?: "") }
    var productType by remember(editing) { mutableStateOf(editing?.productType ?: "OTHER") }
    var marketDemand by remember(editing) { mutableStateOf(editing?.marketDemand ?: "UNKNOWN") }
    var timeTaken by remember(editing) { mutableStateOf(editing?.timeTakenToCompleteProduct ?: "") }
    var size by remember(editing) { mutableStateOf(editing?.size ?: "") }
    var length by remember(editing) { mutableStateOf(numToText(editing?.lengthInches)) }
    var breadth by remember(editing) { mutableStateOf(numToText(editing?.breadthInches)) }
    var height by remember(editing) { mutableStateOf(numToText(editing?.heightInches)) }
    val markers = remember(editing) { DwMeasurementMarkers() }
    var costOfMaking by remember(editing) { mutableStateOf(numToText(editing?.costOfMaking)) }
    var sellingPrice by remember(editing) { mutableStateOf(numToText(editing?.sellingPrice)) }
    var rawMaterials by remember(editing) { mutableStateOf(editing?.rawMaterialsUsed ?: "") }
    var mainTools by remember(editing) { mutableStateOf(editing?.mainToolsUsed ?: "") }
    var functionUse by remember(editing) { mutableStateOf(editing?.productFunctionUse ?: "") }
    var remarks by remember(editing) { mutableStateOf(editing?.remarks ?: "") }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    val link = rememberRecordWorkshopLink(
        repository = repository,
        isEdit = isEdit,
        initialWorkshopId = editing?.workshopId,
        initialDesignWorkshopId = editing?.designWorkshopId,
        resetKey = editing,
    )
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.PRODUCT_FORM,
        scopes = listOf(
            carryScope(CarryNode.ARTISAN, lookupState, artisans) { it.id },
            carryScope(CarryNode.CRAFT, lookupState, crafts) { it.id }
        ),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { craftId = it }
        carried.craftName?.let { craftName = it }
        carried.artisanId?.let { artisanId = it }
        carried.artisanName?.let { artisanName = it }
        carried.place?.let { place = it }
        carried.workshopId?.let { link.applyCarriedWorkshop(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        craftId = ""
        craftName = ""
        artisanId = ""
        artisanName = ""
        place = ""
    }
    var saving by remember { mutableStateOf(false) }
    var productNameError by remember { mutableStateOf<String?>(null) }
    var craftNameError by remember { mutableStateOf<String?>(null) }
    var artisanNameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val productNameFocus = remember { FocusRequester() }
    val craftNameFocus = remember { FocusRequester() }
    val artisanNameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val draft = rememberFormDraft(
        key = if (isEdit) "product:edit:${editing!!.id}" else "product:new:${prefill?.artisanId.orEmpty()}",
        snapshot = {
            mapOf(
                "productName" to productName, "localName" to localName, "craftName" to craftName,
                "artisanName" to artisanName, "place" to place, "craftId" to craftId,
                "artisanId" to artisanId, "productType" to productType, "marketDemand" to marketDemand,
                "timeTaken" to timeTaken, "size" to size, "length" to length, "breadth" to breadth,
                "height" to height, "costOfMaking" to costOfMaking, "sellingPrice" to sellingPrice,
                "rawMaterials" to rawMaterials, "mainTools" to mainTools, "functionUse" to functionUse,
                "remarks" to remarks, "status" to status, "markers" to markers.toDraft(),
            ) + media.draftFields("media")
        },
        restore = { v ->
            productName = v["productName"] ?: productName
            localName = v["localName"] ?: localName
            craftName = v["craftName"] ?: craftName
            artisanName = v["artisanName"] ?: artisanName
            place = v["place"] ?: place
            craftId = v["craftId"] ?: craftId
            artisanId = v["artisanId"] ?: artisanId
            productType = v["productType"] ?: productType
            marketDemand = v["marketDemand"] ?: marketDemand
            timeTaken = v["timeTaken"] ?: timeTaken
            size = v["size"] ?: size
            length = v["length"] ?: length
            breadth = v["breadth"] ?: breadth
            height = v["height"] ?: height
            costOfMaking = v["costOfMaking"] ?: costOfMaking
            sellingPrice = v["sellingPrice"] ?: sellingPrice
            rawMaterials = v["rawMaterials"] ?: rawMaterials
            mainTools = v["mainTools"] ?: mainTools
            functionUse = v["functionUse"] ?: functionUse
            remarks = v["remarks"] ?: remarks
            status = v["status"] ?: status
            v["markers"]?.let { markers.restoreDraft(it) }
            media.restoreDraft("media", v)
        },
    )

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit(forceOffline: Boolean = false, retry: OnlineAttempt<ProductCreateRequest>? = null) {
        if (!validateRequired(listOf(
                RequiredCheck(productName.isBlank(), { productNameError = it }, productNameFocus),
                RequiredCheck(craftId.isBlank(), { craftNameError = it }, craftNameFocus),
                RequiredCheck(artisanName.isBlank(), { artisanNameError = it }, artisanNameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Fill in the highlighted field."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!forceOffline && !link.confirmSubmission()) return@launch
            saving = true
            val createKey = retry?.key ?: java.util.UUID.randomUUID().toString()
            val body = retry?.body ?: ProductCreateRequest(
                productName = productName.trim(),
                localName = localName.blankToNull(),
                craftName = craftName.trim(),
                artisanName = artisanName.trim(),
                place = place.trim(),
                productType = productType,
                timeTakenToCompleteProduct = timeTaken.blankToNull(),
                size = size.blankToNull(),
                lengthInches = length.toDoubleOrNull(),
                breadthInches = breadth.toDoubleOrNull(),
                heightInches = height.toDoubleOrNull(),
                measurementMethods = markers.body(
                    mapOf("lengthInches" to length, "breadthInches" to breadth, "heightInches" to height)
                ),
                costOfMaking = costOfMaking.toDoubleOrNull(),
                sellingPrice = sellingPrice.toDoubleOrNull(),
                marketDemand = marketDemand,
                rawMaterialsUsed = rawMaterials.blankToNull(),
                mainToolsUsed = mainTools.blankToNull(),
                productFunctionUse = functionUse.blankToNull(),
                remarks = remarks.blankToNull(),
                artisanId = artisanId.ifBlank { null },
                craftId = craftId.ifBlank { null },
                workshopId = link.workshopId(),
                designWorkshopId = link.designWorkshopId(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.trim(),
                place = place.trim(),
                craftId = craftId.ifBlank { null },
                craftName = craftName.trim(),
                workshopId = link.workshopId(),
                workshopName = link.field.workshops.firstOrNull { it.id == link.workshopId() }?.title
            )
            val queuedOffline = runCatching {
                trySaveOffline(repository, context, isEdit, "product", offlineFormJson.encodeToString(body),
                    productName.trim(), media, productName.trim(), "Field media for ${productName.trim()}",
                    editingId = editing?.id,
                    unfiled = link.unfiledReasons(),
                    force = forceOffline,
                    idempotencyKey = createKey,
                    base = recordBaseJson(ProductDetailDto.serializer(), editing))
            }.getOrNull()
            if (queuedOffline != null) {
                if (!isEdit) carry.prune(CarryNode.PRODUCT)
                carry.remember(sitting)
                draft.discard(); media.reset()
                onError(offlineSavedMessage(queuedOffline, isCorrection = isEdit))
                onDone()
                saving = false
                return@launch
            }
            if (forceOffline) {
                onError("Couldn't save this product. It's still in the form.")
                saving = false
                return@launch
            }
            var landed = false
            runCatching {
                val productId = if (isEdit) {
                    repository.updateProduct(editing!!.id, body).id
                } else {
                    repository.createProduct(body, createKey).id
                }
                landed = true
                uploadAttachments(repository, context, media, "product", productId, productName, "Field media for ${productName.trim()}")
                productId
            }.onSuccess { productId ->
                carry.remember(sitting.copy(productId = productId, productName = productName.trim()))
                draft.discard(); media.reset()
                if (!isEdit && onCreated != null) onCreated(productId, productName.trim(), place.trim())
                else onDone()
            }.onFailure {
                if (!landed && repository.isTransient(it)) {
                    saving = false
                    submit(forceOffline = true, retry = OnlineAttempt(body, createKey))
                    return@launch
                }
                onError(it.userMessage("Unable to save product"))
            }
            saving = false
        }
    }
    val productSig: () -> String = {
        val carried = carry.offer?.context ?: prefill?.toCarryContext()
        listOf(productName, localName, craftName.exceptCarried(carried?.craftName),
            artisanName.exceptCarried(carried?.artisanName), place.exceptCarried(carried?.place),
            productType, marketDemand, timeTaken, size,
            length, breadth, height, costOfMaking, sellingPrice, rawMaterials, mainTools, functionUse, remarks,
            status, craftId.exceptCarried(carried?.craftId), artisanId.exceptCarried(carried?.artisanId)).joinToString("")
    }
    val initialSig = remember(editing) { productSig() }
    val dirty = !saving && (
        productSig() != initialSig || link.isDirty() || media.uris.isNotEmpty()
    )

    val completenessRules = rememberCompletenessRules()
    val liveCompleteness = completenessRules?.let { rules ->
        evaluateCompleteness(
            rules,
            CompletenessKind.PRODUCT,
            mapOf("artisanId" to artisanId, "sellingPrice" to sellingPrice, "costOfMaking" to costOfMaking, "timeTakenToCompleteProduct" to timeTaken, "lengthInches" to length, "breadthInches" to breadth, "heightInches" to height, "marketDemand" to marketDemand),
            liveCompletenessCounts(rules, CompletenessKind.PRODUCT, editing?.completeness, mapOf("photos" to rememberImageCount(media.uris))),
        )
    }

    RecordCard(title = if (isEdit) "Update product" else "Add product") {
        liveCompleteness?.let { LiveCompletenessLine(it) }
        RegisterUnsavedGuard(dirty = dirty, draft = draft) { submit() }
        FormDraftRestoredNote(draft)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        RecordWorkshopField(link = link, saving = saving)
        RequiredInput("Product name", productName, productNameError, productNameFocus, titleCased = true) { productName = it }
        TextInput("Local name", localName) { localName = it }
        DropdownField("Product type", productTypeOptions.map { it to it }, productType, includeNone = false) { productType = it }
        CraftPickerField(
            label = "Craft",
            required = true,
            selectedIds = listOfNotNull(craftId.ifBlank { null }),
            fallbackNames = if (craftId.isNotBlank() && craftName.isNotBlank()) mapOf(craftId to craftName) else emptyMap(),
            isError = craftNameError != null,
            supportingText = craftNameError,
            refresh = { repository.craftCatalogue(context) },
            modifier = Modifier.focusRequester(craftNameFocus),
        ) { ids ->
            val id = ids.firstOrNull().orEmpty()
            craftId = id
            craftName = CraftCatalogueCache.catalogue.value?.craft(id)?.name.orEmpty()
            craftNameError = null
            if (id.isNotBlank() && artisanId.isNotBlank() && artisans.none { it.id == artisanId && it.craftId == id }) {
                artisanId = ""
            }
        }
        val artisanOptionsForCraft = if (craftId.isNotBlank()) {
            artisans.filter { it.craftId == craftId || it.id == artisanId }
        } else {
            artisans
        }
        CompletenessAnchor("artisan") {
            DropdownField(
                label = "Linked artisan (fills artisan + place)",
                options = artisanOptionsForCraft.map { it.id to "${it.name} · ${it.place}" },
                selectedValue = artisanId,
                placeholder = if (craftId.isBlank()) "Select a linked craft first" else "Unlinked / type below",
                enabled = craftId.isNotBlank()
            ) { id ->
                artisanId = id
                artisans.firstOrNull { it.id == id }?.let {
                    artisanName = it.name
                    place = it.place
                    carry.remember(
                        CarryContext(
                            artisanId = it.id,
                            artisanName = it.name,
                            place = it.place,
                            craftId = craftId.ifBlank { null },
                            craftName = craftName.blankToNull()
                        ),
                        explicit = true
                    )
                }
            }
        }
        if (craftId.isNotBlank() && artisanOptionsForCraft.isEmpty()) {
            Text("No artisans are linked to this craft yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus, titleCased = true) { artisanName = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        CompletenessAnchor("timeTaken") {
            TextInput("Time taken to complete", timeTaken) { timeTaken = it }
        }
        TextInput("Size", size) { size = it }
        CompletenessAnchor("dimensions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length, keyboardType = KeyboardType.Decimal, dictate = false) { length = it } }
                Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth, keyboardType = KeyboardType.Decimal, dictate = false) { breadth = it } }
            }
        }
        TextInput("Height (inches)", height, keyboardType = KeyboardType.Decimal, dictate = false) { height = it }
        RecordMeasureField(
            dimensions = PRODUCT_MEASURE_DIMENSIONS,
            current = mapOf("lengthInches" to length, "breadthInches" to breadth, "heightInches" to height),
            photos = media.uris,
            purposes = media.purposes,
            enabled = !saving,
            onPropose = { column, text, technique ->
                when (column) {
                    "lengthInches" -> length = text
                    "breadthInches" -> breadth = text
                    "heightInches" -> height = text
                }
                markers.accept(column, text, dwGeometryMarker(technique))
            },
        )
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b, marker ->
                if (l != null && l > 0) { length = numToText(l); markers.accept("lengthInches", length, marker) }
                if (b != null && b > 0) { breadth = numToText(b); markers.accept("breadthInches", breadth, marker) }
            },
            onHeight = { value, marker -> height = numToText(value); markers.accept("heightInches", height, marker) }
        )
        CompletenessAnchor("sellingPrice") {
            CompletenessAnchor("costOfMaking") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f)) { TextInput("Cost of making", costOfMaking, keyboardType = KeyboardType.Decimal, dictate = false) { costOfMaking = it } }
                    Box(modifier = Modifier.weight(1f)) { TextInput("Selling price", sellingPrice, keyboardType = KeyboardType.Decimal, dictate = false) { sellingPrice = it } }
                }
            }
        }
        CompletenessAnchor("marketDemand") {
            DropdownField("Market demand", marketDemandOptions.map { it to it }, marketDemand, includeNone = false) { marketDemand = it }
        }
        TextInput("Raw materials used", rawMaterials, minLines = 2, rich = true) { rawMaterials = it }
        TextInput("Main tools used", mainTools, minLines = 2, rich = true) { mainTools = it }
        TextInput("Function or use", functionUse, minLines = 2, rich = true) { functionUse = it }
        TextInput("Remarks", remarks, minLines = 3, rich = true) { remarks = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "product", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            MediaCaptureSection(repository = repository, media = media, onMessage = LocalAppNotice.current, onError = onError)
        }
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update product" else "Save product"
        ) { submit() }
    }
}

@Composable
private fun ToolForm(
    repository: WorkshopRepository,
    crafts: List<CraftDto>,
    artisans: List<ArtisanDto>,
    lookupState: CarryScopeState = CarryScopeState.PENDING,
    editing: ToolDetailDto? = null,
    prefill: Prefill? = null,
    adminView: Boolean = false,
    onCreated: OnRecordCreated? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val media = rememberMediaCaptureState()
    val stages = rememberMediaCaptureState()
    val isEdit = editing != null
    var toolkitName by remember(editing) { mutableStateOf(editing?.toolkitName ?: "") }
    var localName by remember(editing) { mutableStateOf(editing?.localName ?: "") }
    var englishName by remember(editing) { mutableStateOf(editing?.englishName ?: "") }
    var craftName by remember(editing) { mutableStateOf(editing?.craftName ?: prefill?.craftName ?: "") }
    var artisanName by remember(editing) { mutableStateOf(editing?.artisanName ?: prefill?.artisanName ?: "") }
    var place by remember(editing) { mutableStateOf(editing?.place ?: prefill?.place ?: "") }
    var mirrorArmed by remember(editing) { mutableStateOf(englishNameMirrorArmed(editing?.toolkitName, editing?.englishName)) }
    var craftIds by remember(editing) {
        mutableStateOf(initialLinkIds(editing?.craftLinks?.map { it.craftId }, editing?.craftId ?: prefill?.craftId))
    }
    var artisanIds by remember(editing) {
        mutableStateOf(initialLinkIds(editing?.artisanLinks?.map { it.artisanId }, editing?.artisanId ?: prefill?.artisanId))
    }
    val craftId = craftIds.firstOrNull().orEmpty()
    val artisanId = artisanIds.firstOrNull().orEmpty()
    var processUsedIn by remember(editing) { mutableStateOf(editing?.processUsedIn ?: "") }
    var material by remember(editing) { mutableStateOf(editing?.material ?: "") }
    var materialFamily by remember(editing) { mutableStateOf(editing?.materialFamily.orEmpty()) }
    var yearsInUse by remember(editing) { mutableStateOf(editing?.yearsInUse?.toString() ?: "") }
    var height by remember(editing) { mutableStateOf(numToText(editing?.height)) }
    var width by remember(editing) { mutableStateOf(numToText(editing?.width)) }
    var length by remember(editing) { mutableStateOf(numToText(editing?.lengthInches)) }
    var breadth by remember(editing) { mutableStateOf(numToText(editing?.breadthInches)) }
    var heightInches by remember(editing) { mutableStateOf(numToText(editing?.heightInches)) }
    val markers = remember(editing) { DwMeasurementMarkers() }
    var thickness by remember(editing) { mutableStateOf(numToText(editing?.thickness)) }
    var weight by remember(editing) { mutableStateOf(numToText(editing?.weight)) }
    var radius by remember(editing) { mutableStateOf(numToText(editing?.radius)) }
    var maker by remember(editing) { mutableStateOf(editing?.maker ?: "UNKNOWN") }
    var traditionType by remember(editing) { mutableStateOf(StandardValues.traditionChoice(editing?.traditionType)) }
    var replacementCost by remember(editing) { mutableStateOf(numToText(editing?.replacementCost)) }
    var suggestions by remember(editing) { mutableStateOf(editing?.suggestionsForToolImprovement ?: "") }
    var remarks by remember(editing) { mutableStateOf(editing?.remarks ?: "") }
    val link = rememberRecordWorkshopLink(
        repository = repository,
        isEdit = isEdit,
        initialWorkshopId = editing?.workshopId,
        initialDesignWorkshopId = editing?.designWorkshopId,
        resetKey = editing,
    )
    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.TOOL_FORM,
        scopes = listOf(
            carryScope(CarryNode.ARTISAN, lookupState, artisans) { it.id },
            carryScope(CarryNode.CRAFT, lookupState, crafts) { it.id }
        ),
        handoff = prefill
    ) { carried ->
        carried.craftId?.let { id -> craftIds = craftIds.ifEmpty { listOf(id) } }
        carried.craftName?.let { craftName = it }
        carried.artisanId?.let { id -> artisanIds = artisanIds.ifEmpty { listOf(id) } }
        carried.artisanName?.let { artisanName = it }
        carried.place?.let { place = it }
        carried.workshopId?.let { link.applyCarriedWorkshop(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        craftIds = emptyList()
        craftName = ""
        artisanIds = emptyList()
        artisanName = ""
        place = ""
    }
    fun applyToolkitName(next: String) {
        toolkitName = next
        if (mirrorArmed) englishName = next
    }
    val knownCraftNames: Map<String, String> = remember(crafts, editing) {
        buildMap {
            editing?.craftLinks?.forEach { link ->
                link.craft?.name?.takeIf { it.isNotBlank() }?.let { put(link.craftId, it) }
            }
            crafts.forEach { put(it.id, it.name) }
        }
    }
    val knownArtisans: List<ArtisanDto> = remember(artisans, editing) {
        val listed = artisans.map { it.id }.toSet()
        editing?.artisanLinks.orEmpty().mapNotNull { it.artisan }.filter { it.id !in listed } + artisans
    }
    fun joinCraftNames(ids: List<String>): String =
        ids.mapNotNull { knownCraftNames[it]?.takeIf { name -> name.isNotBlank() } }.joinToString(", ")
    fun adoptFirstArtisan(previousFirst: String?) {
        val first = artisanIds.firstOrNull() ?: return
        if (first == previousFirst) return
        val known = knownArtisans.firstOrNull { it.id == first } ?: return
        artisanName = known.name
        place = known.place
    }
    fun onCraftsChanged(next: List<String>) {
        val removed = craftIds.filterNot { it in next }
        val dropped = craftsChangeClearsArtisans(next, removed, artisanIds, knownArtisans).toSet()
        if (dropped.isNotEmpty()) {
            val previousFirst = artisanIds.firstOrNull()
            artisanIds = artisanIds.filterNot { it in dropped }
            adoptFirstArtisan(previousFirst)
        }
        craftIds = next
        if (next.all { knownCraftNames[it]?.isNotBlank() == true }) craftName = joinCraftNames(next)
    }
    fun onArtisansChanged(next: List<String>) {
        val previousFirst = artisanIds.firstOrNull()
        artisanIds = next
        adoptFirstArtisan(previousFirst)
        val first = next.firstOrNull()?.let { id -> knownArtisans.firstOrNull { it.id == id } }
        if (first != null) {
            carry.remember(
                CarryContext(
                    artisanId = first.id,
                    artisanName = first.name,
                    place = first.place,
                    craftId = craftIds.firstOrNull(),
                    craftName = craftIds.firstOrNull()?.let { knownCraftNames[it] } ?: craftName.blankToNull()
                ),
                explicit = true
            )
        }
    }
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var saving by remember { mutableStateOf(false) }
    var toolkitNameError by remember { mutableStateOf<String?>(null) }
    var craftNameError by remember { mutableStateOf<String?>(null) }
    var artisanNameError by remember { mutableStateOf<String?>(null) }
    var placeError by remember { mutableStateOf<String?>(null) }
    val toolkitNameFocus = remember { FocusRequester() }
    val craftNameFocus = remember { FocusRequester() }
    val artisanNameFocus = remember { FocusRequester() }
    val placeFocus = remember { FocusRequester() }
    val draft = rememberFormDraft(
        key = if (isEdit) "tool:edit:${editing!!.id}" else "tool:new:${prefill?.artisanId.orEmpty()}",
        snapshot = {
            mapOf(
                "toolkitName" to toolkitName, "localName" to localName, "englishName" to englishName,
                "craftName" to craftName, "artisanName" to artisanName, "place" to place,
                "mirrorArmed" to mirrorArmed.toString(), "craftIds" to draftList(craftIds),
                "artisanIds" to draftList(artisanIds), "processUsedIn" to processUsedIn,
                "material" to material, "yearsInUse" to yearsInUse, "height" to height, "width" to width,
                "length" to length, "breadth" to breadth, "heightInches" to heightInches,
                "thickness" to thickness, "weight" to weight, "radius" to radius, "maker" to maker,
                "traditionType" to traditionType, "replacementCost" to replacementCost,
                "suggestions" to suggestions, "remarks" to remarks, "status" to status,
                "markers" to markers.toDraft(),
            ) + media.draftFields("media") + stages.draftFields("stages")
        },
        restore = { v ->
            toolkitName = v["toolkitName"] ?: toolkitName
            localName = v["localName"] ?: localName
            englishName = v["englishName"] ?: englishName
            craftName = v["craftName"] ?: craftName
            artisanName = v["artisanName"] ?: artisanName
            place = v["place"] ?: place
            mirrorArmed = v["mirrorArmed"]?.toBooleanStrictOrNull() ?: mirrorArmed
            v["craftIds"]?.let { craftIds = parseDraftList(it) }
            v["artisanIds"]?.let { artisanIds = parseDraftList(it) }
            processUsedIn = v["processUsedIn"] ?: processUsedIn
            material = v["material"] ?: material
            yearsInUse = v["yearsInUse"] ?: yearsInUse
            height = v["height"] ?: height
            width = v["width"] ?: width
            length = v["length"] ?: length
            breadth = v["breadth"] ?: breadth
            heightInches = v["heightInches"] ?: heightInches
            thickness = v["thickness"] ?: thickness
            weight = v["weight"] ?: weight
            radius = v["radius"] ?: radius
            maker = v["maker"] ?: maker
            traditionType = v["traditionType"] ?: traditionType
            replacementCost = v["replacementCost"] ?: replacementCost
            suggestions = v["suggestions"] ?: suggestions
            remarks = v["remarks"] ?: remarks
            status = v["status"] ?: status
            v["markers"]?.let { markers.restoreDraft(it) }
            media.restoreDraft("media", v)
            stages.restoreDraft("stages", v)
        },
    )

    LaunchedEffect(editing) {
        val existing = editing?.location
        if (existing != null && media.location == null) media.location = existing.toRequest()
    }

    fun submit(forceOffline: Boolean = false, retry: OnlineAttempt<ToolCreateRequest>? = null) {
        if (!validateRequired(listOf(
                RequiredCheck(toolkitName.isBlank(), { toolkitNameError = it }, toolkitNameFocus),
                RequiredCheck(craftIds.isEmpty(), { craftNameError = it }, craftNameFocus),
                RequiredCheck(artisanName.isBlank(), { artisanNameError = it }, artisanNameFocus),
                RequiredCheck(place.isBlank(), { placeError = it }, placeFocus)
            ))) { onError("Fill in the highlighted field."); return }
        newRecordLocationError(isEdit, media.location)?.let { onError(it); return }
        scope.launch {
            if (!forceOffline && !link.confirmSubmission()) return@launch
            saving = true
            val createKey = retry?.key ?: java.util.UUID.randomUUID().toString()
            val body = retry?.body ?: ToolCreateRequest(
                toolkitName = toolkitName.trim(),
                localName = localName.blankToNull(),
                englishName = englishName.blankToNull(),
                craftName = craftName.trim(),
                artisanName = artisanName.trim(),
                place = place.trim(),
                processUsedIn = processUsedIn.blankToNull(),
                material = material.blankToNull(),
                materialFamily = materialFamily.blankToNull(),
                yearsInUse = yearsInUse.toIntOrNull(),
                height = height.toDoubleOrNull(),
                width = width.toDoubleOrNull(),
                lengthInches = length.toDoubleOrNull(),
                breadthInches = breadth.toDoubleOrNull(),
                heightInches = heightInches.toDoubleOrNull(),
                measurementMethods = markers.body(
                    mapOf("lengthInches" to length, "breadthInches" to breadth, "heightInches" to heightInches)
                ),
                thickness = thickness.toDoubleOrNull(),
                weight = weight.toDoubleOrNull(),
                radius = radius.toDoubleOrNull(),
                maker = maker,
                traditionType = traditionType.ifBlank { StandardValues.TRADITION_UNANSWERED },
                replacementCost = replacementCost.toDoubleOrNull(),
                suggestionsForToolImprovement = suggestions.blankToNull(),
                remarks = remarks.blankToNull(),
                artisanId = artisanId.ifBlank { null },
                craftId = craftId.ifBlank { null },
                craftIds = craftIds,
                artisanIds = artisanIds,
                workshopId = link.workshopId(),
                designWorkshopId = link.designWorkshopId(),
                status = status,
                recordedAt = if (isEdit) null else Instant.now().toString(),
                location = locationForBody(isEdit, media.location, editing?.location)
            )
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = artisanName.trim(),
                place = place.trim(),
                craftId = craftId.ifBlank { null },
                craftName = craftIds.firstOrNull()?.let { knownCraftNames[it] } ?: craftName.trim(),
                workshopId = link.workshopId(),
                workshopName = link.field.workshops.firstOrNull { it.id == link.workshopId() }?.title
            )
            if ((!isEdit || editing?.id != null) && (forceOffline || !repository.isOnline(context))) {
                val queued = runCatching {
                    val items = media.uris.mapIndexed { i, uri ->
                        com.designprototype.workshop.data.OfflineMediaSpec(uri = uri, caption = "Field media for ${toolkitName.trim()}", recordName = toolkitName.trim(), batchIndex = i + 1, purpose = media.purposes[uri])
                    } + stages.uris.mapIndexed { i, uri ->
                        com.designprototype.workshop.data.OfflineMediaSpec(uri = uri, caption = "Process stage step ${i + 1} for ${toolkitName.trim()}", recordName = toolkitName.trim(), stageStep = i + 1, purpose = stages.purposes[uri])
                    }
                    repository.queueOfflineEntry(
                        context, "tool", offlineFormJson.encodeToString(body), toolkitName.trim(), items,
                        targetId = if (isEdit) editing?.id else null,
                        unfiled = link.unfiledReasons(),
                        idempotencyKey = if (isEdit) null else createKey,
                        correctionBase = if (isEdit) {
                            correctionBaseFor(offlineFormJson.encodeToString(body), recordBaseJson(ToolDetailDto.serializer(), editing))
                        } else null,
                    )
                }.getOrNull()
                if (queued != null) {
                    if (!isEdit) carry.prune(CarryNode.TOOL)
                    carry.remember(sitting)
                    draft.discard(); media.reset(); stages.reset()
                    onError(offlineSavedMessage(queued, isCorrection = isEdit))
                    onDone(); saving = false; return@launch
                } else {
                    onError(
                        "Couldn't save the toolkit (storage full?). It's still in the form."
                    )
                }
                saving = false; return@launch
            }
            var landed = false
            runCatching {
                val toolId = if (isEdit) {
                    repository.updateTool(editing!!.id, body).id
                } else {
                    repository.createTool(body, createKey).id
                }
                landed = true
                uploadAttachments(repository, context, media, "tool", toolId, toolkitName, "Field media for ${toolkitName.trim()}")
                stages.uris.forEachIndexed { index, uri ->
                    repository.uploadMedia(
                        context = context,
                        uri = uri,
                        linkedRecordType = "tool",
                        linkedRecordId = toolId,
                        caption = "Process stage step ${index + 1} for ${toolkitName.trim()}",
                        location = stages.location,
                        titleHint = toolkitName,
                        batchIndex = 1,
                        stageStep = index + 1
                    )
                }
                toolId
            }.onSuccess { toolId ->
                carry.remember(sitting.copy(toolId = toolId, toolName = toolkitName.trim()))
                draft.discard()
                media.reset()
                stages.reset()
                if (!isEdit && onCreated != null) onCreated(toolId, toolkitName.trim(), place.trim())
                else onDone()
            }.onFailure {
                if (!landed && repository.isTransient(it)) {
                    saving = false
                    submit(forceOffline = true, retry = OnlineAttempt(body, createKey))
                    return@launch
                }
                onError(it.userMessage("Unable to save tool"))
            }
            saving = false
        }
    }
    val toolSig: () -> String = {
        val carried = carry.offer?.context ?: prefill?.toCarryContext()
        listOf(toolkitName, localName, englishName, craftName.exceptCarried(carried?.craftName),
            artisanName.exceptCarried(carried?.artisanName), place.exceptCarried(carried?.place),
            processUsedIn, material,
            yearsInUse, height, width, length, breadth, heightInches, thickness, weight, radius, maker, traditionType,
            replacementCost, suggestions, remarks, status,
            craftIds.joinToString(",").exceptCarried(carried?.craftId),
            artisanIds.joinToString(",").exceptCarried(carried?.artisanId)).joinToString("")
    }
    val initialSig = remember(editing) { toolSig() }
    val dirty = !saving && (
        toolSig() != initialSig || link.isDirty() ||
            media.uris.isNotEmpty() || stages.uris.isNotEmpty()
    )

    val completenessRules = rememberCompletenessRules()
    val liveCompleteness = completenessRules?.let { rules ->
        evaluateCompleteness(
            rules,
            CompletenessKind.TOOL,
            mapOf("artisanId" to artisanId, "localName" to localName, "material" to material, "lengthInches" to length, "breadthInches" to breadth, "heightInches" to heightInches, "maker" to maker, "traditionType" to traditionType, "replacementCost" to replacementCost),
            liveCompletenessCounts(rules, CompletenessKind.TOOL, editing?.completeness, mapOf("photos" to rememberImageCount(media.uris))),
        )
    }

    RecordCard(title = if (isEdit) "Update tool" else "Add tool") {
        liveCompleteness?.let { LiveCompletenessLine(it) }
        RegisterUnsavedGuard(dirty = dirty, draft = draft) { submit() }
        FormDraftRestoredNote(draft)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        RecordWorkshopField(link = link, saving = saving)
        RequiredInput("Toolkit name", toolkitName, toolkitNameError, toolkitNameFocus, titleCased = true) { applyToolkitName(it) }
        CompletenessAnchor("localName") {
            TextInput("Local name", localName) { localName = it }
        }
        TextInput("English name", englishName, titleCased = true) { englishName = it; mirrorArmed = false }
        val craftOptions: List<SelectOption> =
            craftIds.filter { id -> crafts.none { it.id == id } }
                .map { id -> offPageLinkRow(id, knownCraftNames[id], "craft") } +
                crafts.map { SelectOption(it.id, it.name) }
        CraftPickerField(
            label = "Linked crafts",
            required = true,
            multiple = true,
            selectedIds = craftIds,
            fallbackNames = craftOptions.associate { it.value to it.label },
            isError = craftNameError != null,
            supportingText = craftNameError,
            refresh = { repository.craftCatalogue(context) },
            modifier = Modifier.focusRequester(craftNameFocus),
        ) { next ->
            craftNameError = null
            onCraftsChanged(next)
        }
        val artisanPool = knownArtisans.filter { it.craftId in craftIds || it.id in artisanIds }
        val artisanOptions: List<SelectOption> =
            artisanIds.filter { id -> artisanPool.none { it.id == id } }
                .map { id -> offPageLinkRow(id, null, "artisan") } +
                toolArtisanOptions(artisanPool, craftIds.mapNotNull { id -> knownCraftNames[id]?.let { id to it } }.toMap())
        CompletenessAnchor("artisan") {
            SearchableMultiSelectField(
                label = "Linked artisans (fills artisan + place)",
                options = artisanOptions,
                selected = artisanIds.toSet(),
                placeholder = if (craftIds.isEmpty()) "Select a linked craft first" else "Unlinked / type below",
                emptyMessage = if (craftIds.isEmpty()) {
                    "Choose crafts above to list their artisans."
                } else {
                    "No artisans for these crafts."
                },
                enabled = craftIds.isNotEmpty(),
            ) { next -> onArtisansChanged(mergeSelection(artisanIds, next, artisanOptions.map { it.value })) }
        }
        RequiredInput("Artisan name", artisanName, artisanNameError, artisanNameFocus, titleCased = true) { artisanName = it }
        RequiredInput("Place", place, placeError, placeFocus, titleCased = true) { place = it }
        TextInput("Process used in", processUsedIn) { processUsedIn = it }
        CompletenessAnchor("material") {
            TextInput("Material", material) { material = it }
        }
        DropdownField("Material type", StandardValues.MATERIAL_FAMILY_OPTIONS, materialFamily, placeholder = "Select material type") { materialFamily = it }
        TextInput("Years in use", yearsInUse, keyboardType = KeyboardType.Number, dictate = false) { yearsInUse = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Height (cm)", height, keyboardType = KeyboardType.Decimal, dictate = false) { height = it; propagateDimension(it, ::inchesTextFromCm) { v -> heightInches = v } } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Width (cm)", width, keyboardType = KeyboardType.Decimal, dictate = false) { width = it; propagateDimension(it, ::inchesTextFromCm) { v -> breadth = v } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Length (inches)", length, keyboardType = KeyboardType.Decimal, dictate = false) { length = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Breadth (inches)", breadth, keyboardType = KeyboardType.Decimal, dictate = false) { breadth = it; propagateDimension(it, ::cmTextFromInches) { v -> width = v } } }
        }
        CompletenessAnchor("dimensions") {
            TextInput("Height (inches)", heightInches, keyboardType = KeyboardType.Decimal, dictate = false) { heightInches = it; propagateDimension(it, ::cmTextFromInches) { v -> height = v } }
        }
        Text(
            "Height (cm) and Height (in) fill each other, as do Width (cm) and Breadth (in). Older records may disagree — fix the wrong one.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Thickness", thickness, keyboardType = KeyboardType.Decimal, dictate = false) { thickness = it } }
            Box(modifier = Modifier.weight(1f)) { TextInput("Weight", weight, keyboardType = KeyboardType.Decimal, dictate = false) { weight = it } }
        }
        TextInput("Radius", radius, keyboardType = KeyboardType.Decimal, dictate = false) { radius = it }
        RecordMeasureField(
            dimensions = TOOL_MEASURE_DIMENSIONS,
            current = mapOf("lengthInches" to length, "breadthInches" to breadth, "heightInches" to heightInches),
            photos = media.uris,
            purposes = media.purposes,
            enabled = !saving,
            onPropose = { column, text, technique ->
                when (column) {
                    "lengthInches" -> length = text
                    "breadthInches" -> { breadth = text; propagateDimension(text, ::cmTextFromInches) { v -> width = v } }
                    "heightInches" -> { heightInches = text; propagateDimension(text, ::cmTextFromInches) { v -> height = v } }
                }
                markers.accept(column, text, dwGeometryMarker(technique))
            },
        )
        GridMeasurementSection(
            repository = repository,
            media = media,
            includeHeight = true,
            onLengthBreadth = { l, b, marker ->
                if (l != null && l > 0) { length = numToText(l); markers.accept("lengthInches", length, marker) }
                if (b != null && b > 0) {
                    breadth = numToText(b)
                    markers.accept("breadthInches", breadth, marker)
                    propagateDimension(breadth, ::cmTextFromInches) { v -> width = v }
                }
            },
            onHeight = { value, marker ->
                heightInches = numToText(value)
                markers.accept("heightInches", heightInches, marker)
                propagateDimension(heightInches, ::cmTextFromInches) { v -> height = v }
            }
        )
        CompletenessAnchor("maker") {
            DropdownField("Maker", makerOptions.map { it to it }, maker, includeNone = false) { maker = it }
        }
        CompletenessAnchor("traditionType") {
            DropdownField("Tradition type", StandardValues.TRADITION_OPTIONS, traditionType, placeholder = "Select tradition type") { traditionType = it }
        }
        CompletenessAnchor("replacementCost") {
            TextInput("Replacement cost", replacementCost, keyboardType = KeyboardType.Decimal, dictate = false) { replacementCost = it }
        }
        TextInput("Suggestions for improvement", suggestions, minLines = 2, rich = true) { suggestions = it }
        TextInput("Remarks", remarks, minLines = 3, rich = true) { remarks = it }
        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }
        ToolStagesSection(stages = stages, onMessage = LocalAppNotice.current, onError = onError)
        if (isEdit) {
            RecordMediaSection(repository = repository, context = context, linkedType = "tool", recordId = editing!!.id, onError = onError)
        }
        CompletenessAnchor("photo") {
            MediaCaptureSection(repository = repository, media = media, onMessage = LocalAppNotice.current, onError = onError)
        }
        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update tool" else "Save tool"
        ) { submit() }
    }
}

@Composable
private fun ToolStagesSection(
    stages: MediaCaptureState,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val capturePermission = rememberPermissionGate(onError)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) stages.uris = stages.uris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) stages.uris = stages.uris + uri
        pendingCaptureUri = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) stages.uris = stages.uris + uri
        pendingCaptureUri = null
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Process stages", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        Text(
            "Document each step of making or using this tool, in order.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        ActionRow {
            OutlinedButton(onClick = { pickMedia.launch("*/*") }) { Text("Add files") }
            OutlinedButton(
                onClick = {
                    capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                        val uri = createAppFileUri(context, "stage-photo-", ".jpg")
                        pendingCaptureUri = uri
                        takePhoto.launch(uri)
                    }
                },
            ) { Text("Photo step") }
        }
        ActionRow {
            OutlinedButton(
                onClick = {
                    capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                        val uri = createAppFileUri(context, "stage-video-", ".mp4")
                        pendingCaptureUri = uri
                        takeVideo.launch(uri)
                    }
                },
            ) { Text("Video step") }
            OutlinedButton(
                onClick = {
                    if (!recording && !capturePermission.holds(MICROPHONE_PERMISSION)) {
                        capturePermission.ask(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE)
                    } else if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "stage-audio-", ".m4a")
                            recorder = createAudioRecorder(context, file).also { it.start() }
                            recordingFile = file
                            recording = true
                            onMessage("Recording stage audio…")
                        }.onFailure { onError(it.userMessage("Unable to start stage audio")) }
                    } else {
                        runCatching {
                            recorder?.stop(); recorder?.release()
                            recordingFile?.let { file -> stages.uris = stages.uris + uriForFile(context, file) }
                        }.onFailure { onError(it.userMessage("Unable to stop stage audio")) }
                        recorder = null; recordingFile = null; recording = false
                        onMessage("Stage audio step added")
                    }
                },
            ) { if (recording) StopSquareLabel("Stop") else Text("Audio step ●") }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        if (stages.uris.isNotEmpty()) {
            Text("${plural(stages.uris.size, "stage step")} captured", color = Muted, style = MaterialTheme.typography.bodySmall)
            stages.uris.forEachIndexed { index, uri ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Step ${index + 1}", color = Body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                    AndroidUriPreview(context = context, uri = uri)
                }
            }
            ActionRow {
                TextButton(modifier = Modifier.destructiveAction(), onClick = { stages.uris = emptyList() }) { Text("Clear stages") }
            }
        }
    }
}

private class ProcessStepUi(
    val key: String,
    serverId: String?,
    name: String,
    val stepType: String,
    existingMedia: List<MediaFileDto> = emptyList(),
    notes: String? = null
) {
    var serverId by mutableStateOf(serverId)
    var name by mutableStateOf(name)
    var existingMedia by mutableStateOf(existingMedia)
    var nameError by mutableStateOf<String?>(null)
    val nameFocus = FocusRequester()
    val media = MediaCaptureState()
    var recordAdditional by mutableStateOf(!notes.isNullOrBlank())
    var notes by mutableStateOf(notes ?: "")
}

private fun ProcessStepUi.stepTypeLabel(): String =
    if (stepType == "SEQUENTIAL") "Sequential" else "Group of activities"

private fun processStepSegment(stepNumber: Int, stepType: String, fileIndex: Int): String =
    if (stepType == "SEQUENTIAL") "STEP_${stepNumber}${'A' + (fileIndex % 26)}"
    else "STEP_${stepNumber}_G${fileIndex + 1}"

@Composable
private fun ProcessForm(
    repository: WorkshopRepository,
    editing: ProcessDetailDto? = null,
    adminView: Boolean = false,
    onCreated: OnRecordCreated? = null,
    onDone: () -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEdit = editing != null
    val preMedia = rememberMediaCaptureState()

    var products by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var artisanListState by remember { mutableStateOf(CarryScopeState.PENDING) }
    var artisanRegister by remember { mutableStateOf(RegisterLoad()) }
    var productRegister by remember { mutableStateOf(RegisterLoad()) }
    var name by remember(editing) { mutableStateOf(editing?.name ?: "") }
    var artisanId by remember(editing) { mutableStateOf(editing?.product?.artisanId ?: "") }
    var productId by remember(editing) { mutableStateOf(editing?.productId ?: "") }
    var artisanError by remember { mutableStateOf<String?>(null) }
    var preProcessAvailable by remember(editing) { mutableStateOf(editing?.preProcessAvailable ?: false) }
    var notes by remember(editing) { mutableStateOf(editing?.notes ?: "") }
    val link = rememberRecordWorkshopLink(
        repository = repository,
        isEdit = isEdit,
        initialWorkshopId = editing?.workshopId,
        initialDesignWorkshopId = editing?.designWorkshopId,
        resetKey = editing,
    )
    val canSetStatus = remember { canSetRecordStatus(repository.cachedUser()?.role) }
    var status by remember(editing) { mutableStateOf(editing?.status ?: defaultCreateStatus(repository.cachedUser()?.role)) }
    var saving by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var productError by remember { mutableStateOf<String?>(null) }
    var stepsError by remember { mutableStateOf<String?>(null) }
    var preMediaError by remember { mutableStateOf<String?>(null) }
    var existingPreMedia by remember(editing) { mutableStateOf(editing?.media ?: emptyList()) }
    val nameFocus = remember { FocusRequester() }
    var addMenu by remember { mutableStateOf(false) }

    val steps = remember(editing) {
        mutableStateListOf<ProcessStepUi>().apply {
            editing?.steps?.forEach { add(ProcessStepUi(java.util.UUID.randomUUID().toString(), it.id, it.name, it.stepType, it.media, it.notes)) }
        }
    }

    LaunchedEffect(Unit) {
        productRegister = loadProductRegister(context, repository) { products = it }
        val gotArtisans = loadArtisanRegister(context, repository) { artisans = it }
        artisanRegister = gotArtisans
        artisanListState = if (gotArtisans.loaded) CarryScopeState.LOADED else CarryScopeState.UNAVAILABLE
    }

    val carriedProduct = remember { mutableStateOf<String?>(null) }

    val draft = rememberFormDraft(
        key = if (isEdit) "process:edit:${editing!!.id}" else "process:new",
        snapshot = {
            val stepMeta = kotlinx.serialization.json.buildJsonArray {
                steps.forEach { step ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("serverId", kotlinx.serialization.json.JsonPrimitive(step.serverId))
                        put("name", kotlinx.serialization.json.JsonPrimitive(step.name))
                        put("stepType", kotlinx.serialization.json.JsonPrimitive(step.stepType))
                        put("notes", kotlinx.serialization.json.JsonPrimitive(step.notes))
                        put("recordAdditional", kotlinx.serialization.json.JsonPrimitive(step.recordAdditional))
                    })
                }
            }
            mapOf(
                "name" to name, "artisanId" to artisanId, "productId" to productId,
                "preProcessAvailable" to preProcessAvailable.toString(), "notes" to notes,
                "status" to status, "steps" to stepMeta.toString(),
            ) + preMedia.draftFields("pre") +
                steps.withIndex().fold(emptyMap()) { acc, (i, step) -> acc + step.media.draftFields("step$i") }
        },
        restore = { v ->
            name = v["name"] ?: name
            artisanId = v["artisanId"] ?: artisanId
            productId = v["productId"] ?: productId
            preProcessAvailable = v["preProcessAvailable"]?.toBooleanStrictOrNull() ?: preProcessAvailable
            notes = v["notes"] ?: notes
            status = v["status"] ?: status
            preMedia.restoreDraft("pre", v)
            v["steps"]?.let { text ->
                val restored = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonArray
                }.getOrNull()
                if (restored != null) {
                    fun kotlinx.serialization.json.JsonObject.str(k: String): String? =
                        (this[k] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
                    val rebuilt = restored.mapIndexedNotNull { i, element ->
                        val o = element as? kotlinx.serialization.json.JsonObject ?: return@mapIndexedNotNull null
                        val serverId = o.str("serverId")
                        val existing = editing?.steps?.firstOrNull { it.id == serverId }
                        ProcessStepUi(
                            java.util.UUID.randomUUID().toString(), serverId, o.str("name").orEmpty(),
                            o.str("stepType") ?: "SEQUENTIAL", existing?.media ?: emptyList(), o.str("notes"),
                        ).also { step ->
                            step.recordAdditional = o.str("recordAdditional")?.toBooleanStrictOrNull() ?: step.recordAdditional
                            step.media.restoreDraft("step$i", v)
                        }
                    }
                    steps.clear()
                    steps.addAll(rebuilt)
                }
            }
        },
    )

    val carry = rememberFormCarry(
        repository = repository,
        enabled = !isEdit,
        applies = CarryPrefillDefaults.PROCESS_FORM,
        scopes = listOf(carryScope(CarryNode.ARTISAN, artisanListState, artisans) { it.id })
    ) { carried ->
        carried.artisanId?.let { artisanId = it }
        carried.productId?.let { carriedProduct.value = it }
        carried.workshopId?.let { link.applyCarriedWorkshop(it) }
    }
    fun clearCarriedContext() {
        carry.change()
        carriedProduct.value = null
        artisanId = ""
        productId = ""
    }

    var artisanProducts by remember { mutableStateOf<List<ProductDetailDto>>(emptyList()) }
    var productsLoading by remember { mutableStateOf(false) }
    var productLoadError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(artisanId, artisans, products) {
        productLoadError = null
        if (artisanId.isBlank()) {
            artisanProducts = emptyList()
            productsLoading = false
            return@LaunchedEffect
        }
        productsLoading = true
        val selectedArtisanName = artisans.firstOrNull { it.id == artisanId }?.name?.trim()
        val result = runCatching {
            val broad = if (products.isNotEmpty()) products else runCatching { repository.products() }.getOrDefault(emptyList())
            loadArtisanProductFallback(repository, artisanId, selectedArtisanName, broad)
        }
        productsLoading = false
        result.onSuccess { fetched ->
            artisanProducts = fetched
            carriedProduct.value?.let { carried ->
                carriedProduct.value = null
                if (fetched.any { it.id == carried }) productId = carried else carry.prune(CarryNode.PRODUCT)
            }
            if (productId.isNotBlank() && fetched.none { it.id == productId }) productId = ""
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            artisanProducts = emptyList()
            productLoadError = it.userMessage("Couldn't load this artisan's products.") + " Tap the artisan again to retry."
        }
    }

    fun submit(forceOffline: Boolean = false, retry: OnlineAttempt<ProcessCreateRequest>? = null) {
        nameError = null; productError = null; stepsError = null; preMediaError = null; artisanError = null
        var firstInvalid = false
        if (name.isBlank()) { nameError = "This field cannot be empty"; if (!firstInvalid) { firstInvalid = true; runCatching { nameFocus.requestFocus() } } }
        if (artisans.isNotEmpty() && artisanId.isBlank()) artisanError = "Please select an artisan"
        if (artisanProducts.isNotEmpty() && productId.isBlank()) productError = "Please select a product"
        if (preProcessAvailable && preMedia.uris.isEmpty() && (editing?.media?.isEmpty() != false)) {
            preMediaError = "Attach pre-process media or uncheck the box"
        }
        if (steps.isEmpty()) stepsError = "Add at least one step"
        steps.forEach { step ->
            step.nameError = null
            if (step.name.isBlank()) {
                step.nameError = "This field cannot be empty"
                if (!firstInvalid) { firstInvalid = true; runCatching { step.nameFocus.requestFocus() } }
            }
        }
        val blocked = nameError != null || productError != null || stepsError != null ||
            preMediaError != null || artisanError != null || steps.any { it.name.isBlank() }
        if (blocked) { onError("Fill in the highlighted fields."); return }

        scope.launch {
            if (!forceOffline && !link.confirmSubmission()) return@launch
            saving = true
            val stepRequests = steps.mapIndexed { i, s ->
                ProcessStepRequest(
                    id = s.serverId,
                    name = s.name.trim(),
                    stepType = s.stepType,
                    sortOrder = i + 1,
                    notes = if (s.recordAdditional) s.notes.trim().ifBlank { null } else null
                )
            }
            val createKey = retry?.key ?: java.util.UUID.randomUUID().toString()
            val body = retry?.body ?: ProcessCreateRequest(
                name = name.trim(),
                productId = productId,
                preProcessAvailable = preProcessAvailable,
                notes = notes.blankToNull(),
                status = status,
                steps = stepRequests,
                workshopId = link.workshopId(),
                designWorkshopId = link.designWorkshopId(),
                recordedAt = if (isEdit) null else Instant.now().toString()
            )
            val savedArtisan = artisans.firstOrNull { it.id == artisanId }
            val sitting = CarryContext(
                artisanId = artisanId.ifBlank { null },
                artisanName = savedArtisan?.name,
                place = savedArtisan?.place,
                craftId = savedArtisan?.craftId,
                craftName = savedArtisan?.craft?.name,
                productId = productId.ifBlank { null },
                productName = artisanProducts.firstOrNull { it.id == productId }?.productName,
                workshopId = link.workshopId(),
                workshopName = link.field.workshops.firstOrNull { it.id == link.workshopId() }?.title
            )
            if ((!isEdit || editing?.id != null) && (forceOffline || !repository.isOnline(context))) {
                val queued = runCatching {
                    val items = mutableListOf<com.designprototype.workshop.data.OfflineMediaSpec>()
                    if (preProcessAvailable) {
                        preMedia.uris.forEachIndexed { i, uri ->
                            items.add(com.designprototype.workshop.data.OfflineMediaSpec(
                                uri = uri, caption = "Pre-process media for ${name.trim()}", recordName = name.trim(),
                                customSegment = "PRE", batchIndex = i + 1, linkedType = "process",
                                purpose = preMedia.purposes[uri]))
                        }
                    }
                    steps.forEachIndexed { index, local ->
                        local.media.uris.forEachIndexed { fileIndex, uri ->
                            items.add(com.designprototype.workshop.data.OfflineMediaSpec(
                                uri = uri, caption = "Process step ${local.name.trim()}", recordName = name.trim(),
                                customSegment = processStepSegment(index + 1, local.stepType, fileIndex),
                                batchIndex = fileIndex + 1, linkedType = "processstep", stepIndex = index,
                                purpose = local.media.purposes[uri]))
                        }
                    }
                    repository.queueOfflineEntry(
                        context, "process", offlineFormJson.encodeToString(body), name.trim(), items,
                        targetId = if (isEdit) editing?.id else null,
                        unfiled = link.unfiledReasons(),
                        idempotencyKey = if (isEdit) null else createKey,
                        correctionBase = if (isEdit) {
                            correctionBaseFor(offlineFormJson.encodeToString(body), recordBaseJson(ProcessDetailDto.serializer(), editing))
                        } else null,
                    )
                }.getOrNull()
                if (queued != null) {
                    carry.remember(sitting)
                    draft.discard(); preMedia.reset(); steps.forEach { it.media.reset() }
                    onError(offlineSavedMessage(queued, isCorrection = isEdit))
                    onDone()
                } else {
                    onError(
                        "Couldn't save the process (storage full?). It's still in the form."
                    )
                }
                saving = false
                return@launch
            }
            var landed = false
            runCatching {
                val detail = if (isEdit) repository.updateProcess(editing!!.id, body) else repository.createProcess(body, createKey)
                landed = true
                if (preProcessAvailable) {
                    uploadAttachments(repository, context, preMedia, "process", detail.id, name, "Pre-process media for ${name.trim()}", customSegment = "PRE")
                }
                detail.steps.forEachIndexed { index, serverStep ->
                    val local = steps.getOrNull(index) ?: return@forEachIndexed
                    local.media.uris.forEachIndexed { fileIndex, uri ->
                        val segment = processStepSegment(index + 1, serverStep.stepType, fileIndex)
                        val staged = local.media.stagedDeferred[uri]?.let { runCatching { it.await() }.getOrNull() } ?: local.media.staged[uri]
                        if (staged != null) {
                            repository.completeStaged(
                                staged = staged,
                                linkedRecordType = "processstep",
                                linkedRecordId = serverStep.id,
                                recordName = name,
                                caption = "Process step ${serverStep.name}",
                                location = local.media.location,
                                batchIndex = fileIndex + 1,
                                customSegment = segment,
                                processingRequests = TranscribeChoices.processingFor(uri.toString(), staged.mediaType)
                            )
                        } else {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = "processstep",
                                linkedRecordId = serverStep.id,
                                caption = "Process step ${serverStep.name}",
                                location = local.media.location,
                                titleHint = name,
                                batchIndex = fileIndex + 1,
                                customSegment = segment
                            )
                        }
                    }
                }
                detail.id
            }.onSuccess { processId ->
                carry.remember(sitting)
                draft.discard()
                preMedia.reset()
                steps.forEach { it.media.reset() }
                if (!isEdit && onCreated != null) {
                    onCreated(
                        processId,
                        name.trim(),
                        artisanProducts.firstOrNull { it.id == productId }?.productName.orEmpty()
                    )
                } else {
                    onDone()
                }
            }.onFailure {
                if (!landed && repository.isTransient(it)) {
                    saving = false
                    submit(forceOffline = true, retry = OnlineAttempt(body, createKey))
                    return@launch
                }
                onError(it.userMessage("Unable to save process"))
            }
            saving = false
        }
    }
    val procSig: () -> String = {
        val carried = carry.offer?.context
        listOf(name, artisanId.exceptCarried(carried?.artisanId), productId.exceptCarried(carried?.productId),
            notes, status, preProcessAvailable.toString(),
            steps.joinToString("|") { "${it.serverId}~${it.name}~${it.stepType}~${it.notes}~${it.media.uris.size}" }).joinToString("")
    }
    val initialSig = remember(editing) { procSig() }
    val dirty = !saving && (
        procSig() != initialSig || link.isDirty() ||
            preMedia.uris.isNotEmpty() || steps.any { it.media.uris.isNotEmpty() }
    )

    RecordCard(title = if (isEdit) "Update process" else "Document process") {
        RegisterUnsavedGuard(dirty = dirty, draft = draft) { submit() }
        FormDraftRestoredNote(draft)
        if (adminView && editing != null) {
            ProvenanceSection(meta = editing.extraMetadata, createdByName = editing.createdBy?.name)
        }
        Text(
            "Capture how a product is made, step by step.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        CarryPrefillBanner(state = carry, onChange = { clearCarriedContext() })
        RecordWorkshopField(link = link, saving = saving)
        RequiredInput("Name of the process", name, nameError, nameFocus, titleCased = true) { name = it }
        RegisterDropdownField(
            label = "Artisan *",
            noun = "artisans",
            options = artisans.map { it.id to "${it.name} · ${it.place}" },
            selectedValue = artisanId,
            load = artisanRegister,
            placeholder = "Select the artisan",
            includeNone = false
        ) { picked ->
            if (picked != artisanId) {
                artisanId = picked
                productId = ""
                carriedProduct.value = null
                artisans.firstOrNull { it.id == picked }?.let {
                    carry.remember(
                        CarryContext(artisanId = it.id, artisanName = it.name, place = it.place),
                        explicit = true
                    )
                }
            }
        }
        if (artisanError != null) Text(artisanError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        RegisterDropdownField(
            label = "Product *",
            noun = "products",
            options = artisanProducts.map { it.id to it.productName },
            selectedValue = productId,
            load = productRegister,
            placeholder = when {
                artisanId.isBlank() -> "Select an artisan first"
                productsLoading -> "Loading products…"
                artisanProducts.isEmpty() -> "No products for this artisan"
                else -> "Select the product this process makes"
            },
            includeNone = false,
            enabled = artisanId.isNotBlank() && !productsLoading && artisanProducts.isNotEmpty()
        ) { picked ->
            productId = picked
            artisanProducts.firstOrNull { it.id == picked }?.let { product ->
                carry.prune(CarryNode.PRODUCT)
                val artisan = artisans.firstOrNull { it.id == artisanId }
                carry.remember(
                    CarryContext(
                        artisanId = artisanId.ifBlank { null },
                        artisanName = artisan?.name,
                        place = artisan?.place,
                        craftId = artisan?.craftId,
                        productId = product.id,
                        productName = product.productName
                    )
                )
            }
        }
        when {
            productsLoading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading this artisan's products…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            productLoadError != null -> Text(productLoadError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            artisanId.isNotBlank() && artisanProducts.isEmpty() ->
                Text("No products for this artisan yet. Create one first.", color = Muted, style = MaterialTheme.typography.bodySmall)
            artisanId.isNotBlank() && artisanProducts.isNotEmpty() ->
                Text("${plural(artisanProducts.size, "product")} for this artisan.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (productError != null) Text(productError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        TextInput("What happens in this process", notes, minLines = 3, rich = true, resetKey = editing?.id) { notes = it }

        AppToggle(
            label = "Pre-processes available",
            checked = preProcessAvailable,
            onCheckedChange = { preProcessAvailable = it }
        )
        if (preProcessAvailable) {
            Text("Attach the pre-process media (required).", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (existingPreMedia.isNotEmpty()) {
                Text("Already attached:", color = Muted, style = MaterialTheme.typography.bodySmall)
                existingPreMedia.forEach { saved ->
                    AndroidSavedMediaPreview(
                        context = context,
                        media = saved,
                        onDelete = {
                            scope.launch {
                                runCatching { repository.deleteMedia(saved.id) }
                                    .onSuccess { existingPreMedia = existingPreMedia.filterNot { it.id == saved.id } }
                                    .onFailure { error -> onError(error.userMessage("Unable to remove media")) }
                            }
                        },
                        repository = repository
                    )
                }
            }
            MediaCaptureSection(repository = repository, media = preMedia, emphasizeVideo = true, onMessage = LocalAppNotice.current, onError = onError)
            if (preMediaError != null) Text(preMediaError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Steps", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        if (stepsError != null) Text(stepsError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

        steps.forEachIndexed { index, step ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text("Step ${index + 1} · ${step.stepTypeLabel()}", color = Body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { steps.removeAt(index) }) { Text("Remove") }
                    }
                    RequiredInput("Name of the step", step.name, step.nameError, step.nameFocus) { step.name = it }
                    if (step.existingMedia.isNotEmpty()) {
                        Text("Already attached:", color = Muted, style = MaterialTheme.typography.bodySmall)
                        step.existingMedia.forEach { saved ->
                            AndroidSavedMediaPreview(
                                context = context,
                                media = saved,
                                onDelete = {
                                    scope.launch {
                                        runCatching { repository.deleteMedia(saved.id) }
                                            .onSuccess { step.existingMedia = step.existingMedia.filterNot { it.id == saved.id } }
                                            .onFailure { error -> onError(error.userMessage("Unable to remove media")) }
                                    }
                                },
                                repository = repository
                            )
                        }
                    }
                    MediaCaptureSection(
                        repository = repository,
                        media = step.media,
                        emphasizeVideo = true,
                        beforeLocation = {
                            AppToggle(
                                label = "Record additional information",
                                checked = step.recordAdditional,
                                onCheckedChange = { step.recordAdditional = it; if (!it) step.notes = "" }
                            )
                            if (step.recordAdditional) {
                                MultiNoteInput(label = "Additional context for this step", value = step.notes, resetKey = step.key) { step.notes = it }
                            }
                        },
                        onMessage = LocalAppNotice.current,
                        onError = onError
                    )
                }
            }
        }

        Box {
            OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add Another Step")
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Sequential") },
                    onClick = { steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "SEQUENTIAL")); addMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("Group of activities") },
                    onClick = { steps.add(ProcessStepUi(java.util.UUID.randomUUID().toString(), null, "", "GROUP")); addMenu = false }
                )
            }
        }

        StatusControl(canSetStatus = canSetStatus, value = status) { status = it }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.field.surface100, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                "Each researcher should document processes separately to keep different perspectives.",
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall
            )
        }

        SaveButton(
            state = if (saving) SaveState.SAVING else SaveState.IDLE,
            idleLabel = if (isEdit) "Update process" else "Save process"
        ) { submit() }
    }
}

private val viewDataModes = listOf(
    EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.TOOL,
    EntryMode.QUESTIONNAIRE, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.MEDIA
)

private fun EntryMode.linkedRecordType(): String = when (this) {
    EntryMode.ARTISAN -> "artisan"
    EntryMode.PRODUCT -> "product"
    EntryMode.TOOL -> "tool"
    EntryMode.WORKSHOP -> "workshop"
    EntryMode.CRAFT -> "craft"
    EntryMode.QUESTIONNAIRE -> "questionnaire"
    EntryMode.PROCESS -> "process"
    else -> name.lowercase()
}

private fun interviewGroupKey(iv: QuestionnaireInterviewDetailDto): String {
    val ids = iv.artisans.map { it.artisanId }.toSortedSet()
    val scope = "${iv.workshopId.orEmpty()}|${iv.designWorkshopId.orEmpty()}"
    return if (ids.isEmpty()) "iv:${iv.id}" else "set:$scope|${ids.joinToString(",")}"
}

private fun representativeInterview(group: List<QuestionnaireInterviewDetailDto>): QuestionnaireInterviewDetailDto =
    group.maxByOrNull { it.createdAt ?: "" } ?: group.first()

private suspend fun loadViewEntriesCached(
    context: Context,
    repository: WorkshopRepository,
    mode: EntryMode,
    onCached: (String) -> Unit = {},
): List<Pair<String, String>> {
    val read = ReadCache.read(
        context.applicationContext,
        "view-entries:${mode.name}",
        kotlinx.serialization.builtins.ListSerializer(
            kotlinx.serialization.builtins.PairSerializer(
                kotlinx.serialization.serializer<String>(),
                kotlinx.serialization.serializer<String>(),
            )
        ),
    ) { loadViewEntries(repository, mode) }
    if (read.fromCache) onCached(readCacheNotice(read.cachedAt))
    return read.value
}

private suspend fun loadViewEntries(repository: WorkshopRepository, mode: EntryMode): List<Pair<String, String>> = when (mode) {
    EntryMode.ARTISAN -> repository.artisans().sortedByDescending { it.createdAt ?: "" }.map { it.id to "${it.name} · ${it.place}" }
    EntryMode.CRAFT -> repository.crafts().sortedByDescending { it.createdAt ?: "" }.map { it.id to (it.name + (it.place?.let { p -> " · $p" } ?: "")) }
    EntryMode.PRODUCT -> repository.products().sortedByDescending { it.createdAt ?: "" }.map { it.id to "${it.productName} · ${it.artisanName}" }
    EntryMode.PROCESS -> repository.processes().sortedByDescending { it.createdAt ?: "" }.map { it.id to (it.name + (it.product?.productName?.let { p -> " · $p" } ?: "")) }
    EntryMode.TOOL -> repository.tools().sortedByDescending { it.createdAt ?: "" }.map { it.id to it.toolRowLabel() }
    EntryMode.WORKSHOP -> repository.workshops().sortedByDescending { it.createdAt ?: "" }.map { it.id to workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place) }
    EntryMode.QUESTIONNAIRE -> repository.interviews().sortedByDescending { it.createdAt ?: "" }.map { it.id to it.title.ifBlank { "Untitled interview" } }
    EntryMode.MEDIA -> repository.mediaList().sortedByDescending { it.createdAt ?: "" }.map { m ->
        val tag = m.linkedRecordType?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        m.id to (m.originalFilename.ifBlank { "Media" } + " · " + listOfNotNull(m.mediaType, tag).joinToString(" · "))
    }
    else -> emptyList()
}

@kotlinx.serialization.Serializable
private data class CachedActivityRow(
    val mode: String,
    val id: String,
    val title: String,
    val subtitle: String,
    val createdAt: String? = null,
)

private data class ActivityItem(
    val mode: EntryMode,
    val id: String,
    val title: String,
    val subtitle: String,
    val createdAt: String?
)

private data class MyActivityResult(val items: List<ActivityItem>, val failedLists: Int, val totalLists: Int) {
    val allFailed: Boolean get() = failedLists == totalLists
}

private suspend fun loadMyActivity(repository: WorkshopRepository, userId: String): MyActivityResult {
    val items = mutableListOf<ActivityItem>()
    var failed = 0
    var total = 0
    fun mine(createdById: String?) = createdById != null && createdById == userId
    suspend fun <T> attempt(block: suspend () -> List<T>): List<T> {
        total++
        return try {
            block()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            failed++
            emptyList()
        }
    }
    attempt { repository.artisans(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.ARTISAN, it.id, it.name, "Artisan · ${it.place}", it.createdAt)) }
    attempt { repository.products(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.PRODUCT, it.id, it.productName, "Product · ${it.craftName}", it.createdAt)) }
    attempt { repository.tools(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.TOOL, it.id, it.toolkitName, "Tool · ${it.craftName}", it.createdAt)) }
    attempt { repository.processes(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.PROCESS, it.id, it.name, "Process" + (it.product?.productName?.let { p -> " · $p" } ?: ""), it.createdAt)) }
    attempt { repository.crafts(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.CRAFT, it.id, it.name, "Craft", it.createdAt)) }
    attempt { repository.workshops(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.WORKSHOP, it.id, workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place), "Workshop", it.createdAt)) }
    attempt { repository.interviews(createdBy = userId) }.filter { mine(it.createdById) }
        .forEach { items.add(ActivityItem(EntryMode.QUESTIONNAIRE, it.id, it.title.ifBlank { "Untitled interview" }, "Interview", it.createdAt)) }
    attempt { repository.mediaList(uploadedBy = userId) }.filter { it.uploadedBy?.id == userId }
        .forEach { media ->
            val tag = media.linkedRecordType?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
            items.add(
                ActivityItem(
                    EntryMode.MEDIA,
                    media.id,
                    media.originalFilename.ifBlank { "Media" },
                    listOfNotNull("Media", media.mediaType.takeIf { it.isNotBlank() }, tag).joinToString(" · "),
                    media.createdAt
                )
            )
        }
    return MyActivityResult(items.sortedByDescending { it.createdAt ?: "" }, failedLists = failed, totalLists = total)
}

@Composable
private fun MyActivityScreen(
    repository: WorkshopRepository,
    userId: String,
    onOpen: (EntryMode, String) -> Unit,
    onError: (String) -> Unit
) {
    var loaded by rememberKept("myActivity.loaded") { null as MyActivityResult? }
    val activityContext = LocalContext.current.applicationContext
    var cachedNote by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            var partial: MyActivityResult? = null
            val read = ReadCache.read(
                activityContext,
                "my-activity:$userId",
                kotlinx.serialization.builtins.ListSerializer(CachedActivityRow.serializer()),
            ) {
                val result = loadMyActivity(repository, userId)
                if (result.allFailed) throw java.io.IOException("No list answered")
                partial = result
                result.items.map { CachedActivityRow(it.mode.name, it.id, it.title, it.subtitle, it.createdAt) }
            }
            if (read.fromCache) {
                cachedNote = readCacheNotice(read.cachedAt)
                MyActivityResult(
                    read.value.mapNotNull { row ->
                        EntryMode.entries.firstOrNull { it.name == row.mode }?.let {
                            ActivityItem(it, row.id, row.title, row.subtitle, row.createdAt)
                        }
                    },
                    failedLists = 0,
                    totalLists = 1,
                )
            } else {
                partial ?: loadMyActivity(repository, userId)
            }
        }
            .onSuccess { loaded = it }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.userMessage("Couldn't load your activity"))
                loaded = MyActivityResult(emptyList(), failedLists = 1, totalLists = 1)
            }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "Your records, newest first.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        cachedNote?.let { CachedCopyNote(it) }
        val current = loaded
        if (current != null && current.failedLists > 0 && !current.allFailed) {
            Text(
                "Some record types could not be loaded, so this list may be incomplete.",
                color = Coral,
                style = MaterialTheme.typography.bodySmall
            )
        }
        when {
            current == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading your activity…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            current.allFailed -> Text(
                "Your activity couldn't be loaded. Anything you have recorded is safe; open this again " +
                    "when you have a connection.",
                color = Coral,
                style = MaterialTheme.typography.bodyMedium
            )
            current.items.isEmpty() -> Text(
                "You haven't recorded anything yet. Create a record from the menu and it will appear here.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> current.items.forEach { item ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .openable(item.title, RoundedCornerShape(14.dp)) { onOpen(item.mode, item.id) }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(item.title, display = true, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            item.subtitle + (formatIsoDate(item.createdAt)?.let { " · $it" } ?: ""),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolAssignScreen(
    repository: WorkshopRepository,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf<List<ToolDetailDto>>(emptyList()) }
    var crafts by remember { mutableStateOf<List<CraftDto>>(emptyList()) }
    var artisans by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var toolRegister by remember { mutableStateOf(RegisterLoad()) }
    var toolId by remember { mutableStateOf("") }
    var craftIds by remember { mutableStateOf(setOf<String>()) }
    var artisanIds by remember { mutableStateOf(setOf<String>()) }
    var assigned by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        toolRegister = loadToolRegister(context, repository) { tools = it }
        if (!toolRegister.loaded) {
            onError("Failed to load tools")
        }
        loadCraftRegister(context, repository) { crafts = it }
        loadArtisanRegister(context, repository) { artisans = it }
    }
    LaunchedEffect(toolId) {
        if (toolId.isBlank()) { assigned = emptyList(); return@LaunchedEffect }
        runCatching { repository.toolArtisans(toolId) }.onSuccess { assigned = it }.onFailure { assigned = emptyList() }
    }

    val artisansForCrafts = remember(artisans, craftIds) {
        artisans.filter { it.craftId != null && craftIds.contains(it.craftId) }
    }
    LaunchedEffect(artisansForCrafts) {
        artisanIds = artisanIds.filter { id -> artisansForCrafts.any { it.id == id } }.toSet()
    }

    RecordCard(title = "Assign a tool to multiple artisans") {
        Text(
            "Link one tool to several artisans instead of re-entering it.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        RegisterDropdownField(
            label = "Tool *",
            noun = "toolkits",
            options = tools.map { it.id to "${it.toolkitName} · ${it.craftName}" },
            selectedValue = toolId,
            load = toolRegister,
            placeholder = "Select a tool",
            includeNone = false
        ) { toolId = it }
        CraftPickerField(
            label = "Crafts",
            multiple = true,
            selectedIds = craftIds.toList(),
            refresh = { repository.craftCatalogue(context) },
        ) { next -> craftIds = next.toSet() }
        CheckboxMultiSelectField(
            label = "Artisans of selected crafts",
            options = artisansForCrafts.map { it.id to "${it.name} · ${it.place}" },
            selectedIds = artisanIds,
            emptyMessage = if (craftIds.isEmpty()) "Select one or more crafts first." else "No artisans for the selected crafts.",
            onToggle = { id -> artisanIds = if (artisanIds.contains(id)) artisanIds - id else artisanIds + id }
        )
        info?.let { Text(it, color = Coral, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                info = null
                if (toolId.isBlank() || artisanIds.isEmpty()) { onError("Pick a tool and at least one artisan."); return@Button }
                scope.launch {
                    saving = true
                    runCatching { repository.assignToolArtisans(toolId, artisanIds.toList()) }
                        .onSuccess { result -> assigned = result; artisanIds = emptySet(); info = "Linked to ${plural(result.size, "artisan")}." }
                        .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onError(it.userMessage("Assignment failed")) }
                    saving = false
                }
            },
            enabled = !saving && toolId.isNotBlank() && artisanIds.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (saving) "Assigning…" else "Assign tool to ${plural(artisanIds.size, "artisan")}") }

        if (toolId.isNotBlank()) {
            HorizontalDivider()
            Text("Currently assigned to", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            if (assigned.isEmpty()) {
                Text("Not assigned to any additional artisans yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                assigned.forEach { artisan ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${artisan.name}${artisan.craft?.name?.let { " · $it" } ?: ""}",
                            color = Body,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { repository.unassignToolArtisan(toolId, artisan.id) }
                                    .onSuccess { assigned = assigned.filter { it.id != artisan.id } }
                                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; onError(it.userMessage("Could not remove")) }
                            }
                        }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (!value.isNullOrBlank()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(120.dp))
            Text(value, color = Body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun MediaWithTranscript(
    context: Context,
    media: MediaFileDto,
    repository: WorkshopRepository? = null,
    readOnly: Boolean = false
) {
    AndroidSavedMediaPreview(context = context, media = media, repository = repository)
    val uploader = media.uploadedBy?.name
    val uploadedWhen = formatIsoDate(media.createdAt)
    if (uploader != null || uploadedWhen != null) {
        Text(
            "Uploaded by ${uploader ?: "Unknown"}" + (uploadedWhen?.let { " · $it" } ?: ""),
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
    com.designprototype.workshop.questionnaire.ui.RecordingChips(media.questionnaireChips, onChip = QuestionnaireV2Nav::openRecordings)
    val scope = rememberCoroutineScope()
    val isAudio = media.mediaType.equals("AUDIO", ignoreCase = true)
    val processing = setOf("QUEUED", "PROCESSING", "PENDING", "RUNNING")
    val done = setOf("COMPLETED", "EMPTY", "DONE")
    var transcriptText by remember(media.id) { mutableStateOf(media.transcriptText) }
    var liveStatus by remember(media.id) { mutableStateOf(media.transcriptStatus) }
    var transcribing by remember(media.id) { mutableStateOf(false) }
    val status = liveStatus?.uppercase()
    val isAdmin = !readOnly && repository?.cachedUser()?.role in setOf("ADMIN", "MASTER_ADMIN")

    fun transcribeNow() {
        val repo = repository ?: return
        transcribing = true
        liveStatus = "PROCESSING"
        scope.launch {
            runCatching { repo.transcribeNow(media.id) }
                .onSuccess { updated ->
                    transcriptText = updated.transcriptText
                    liveStatus = updated.transcriptStatus ?: "COMPLETED"
                }
                .onFailure { liveStatus = media.transcriptStatus }
            transcribing = false
        }
    }

    val isVideo = media.mediaType.equals("VIDEO", ignoreCase = true)
    val isTimed = isAudio || isVideo
    val legacyTranscript: @Composable () -> Unit = {
    when {
        !transcriptText.isNullOrBlank() -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceCard, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                QuestionnaireTranscriptActions(
                    text = transcriptText!!,
                    filenameBase = "${media.originalFilename}-transcript",
                    onSave = repository?.let { repo ->
                        { fileName: String ->
                            saveTranscriptToDownloads(context, repo, scope, fileName, transcriptText.orEmpty())
                        }
                    },
                    leading = {
                        Text("Transcript", color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                        if (!media.transcriptEditedAt.isNullOrBlank()) {
                            Spacer(Modifier.width(6.dp))
                            Row(
                                modifier = Modifier
                                    .background(MaterialTheme.field.warningContainer, RoundedCornerShape(999.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = null,
                                    tint = MaterialTheme.field.onWarningContainer,
                                    modifier = Modifier.size(10.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "Edited",
                                    color = MaterialTheme.field.onWarningContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                )
                MarkdownText(markdown = transcriptText!!, color = Body)
            }
            if (repository != null && status == "COMPLETED") {
                TranscriptInsightsCard(
                    mediaId = media.id,
                    load = { id -> repository.transcriptInsight(id) },
                    formatDate = ::formatIsoDate,
                    actions = if (readOnly) null else repository.transcriptInsightActions
                )
            }
            if (repository != null && !readOnly) {
                TranscriptRefineControls(
                    context = context,
                    media = media,
                    repository = repository,
                    onApplied = { newText -> transcriptText = newText }
                )
            }
            if (isAdmin && isTimed && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = true) { transcribeNow() }
            }
        }
        transcribing || (isTimed && (status in processing || (status == null && media.transcribeRequested == true))) -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(if (transcribing) "Transcribing now…" else "Transcribing audio…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        isTimed && status in done -> {
            Text("Transcript complete — no speech detected.", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
        isTimed && status == null -> {
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
        isTimed -> {
            Text(
                "Transcript: ${liveStatus ?: "—"}" + (media.transcriptError?.let { " — $it" } ?: ""),
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (isAdmin && repository != null) {
                TranscribeNowButton(transcribing = transcribing, hasExisting = false) { transcribeNow() }
            }
        }
    }
    Unit
    }
    if (isTimed) TranscriptEntry(mediaId = media.id, audio = null, legacy = legacyTranscript) else legacyTranscript()
}

@Composable
private fun TranscribeNowButton(transcribing: Boolean, hasExisting: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !transcribing) {
        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(if (transcribing) "Transcribing…" else if (hasExisting) "Re-transcribe now" else "Transcribe now", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ArtisanQuestionnaireData(repository: WorkshopRepository, artisanId: String) {
    val context = LocalContext.current
    var data by remember(artisanId) { mutableStateOf<ArtisanQuestionnaireDto?>(null) }
    var loading by remember(artisanId) { mutableStateOf(true) }
    LaunchedEffect(artisanId) {
        runCatching { repository.artisanQuestionnaire(artisanId) }.onSuccess { data = it }
        loading = false
    }
    val loaded = data
    ArtisanQuestionnairePanel(answers = loaded?.answered ?: emptyList(), loading = loading)
    val interviews = loaded?.interviews.orEmpty().filter { it.media.isNotEmpty() || !it.notes.isNullOrBlank() }
    if (interviews.isNotEmpty()) {
        RecordCard(title = "Questionnaire recordings", icon = Icons.Filled.Quiz) {
            Text(
                "Recordings and notes from every interview with this artisan.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            interviews.forEach { interview ->
                HorizontalDivider()
                Text(interview.title.ifBlank { "Interview" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(
                    formatIsoDate(interview.interviewDate),
                    if (interview.coArtisans.isNotEmpty()) "with ${interview.coArtisans.joinToString(", ")}" else null
                ).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Muted, style = MaterialTheme.typography.bodySmall)
                interview.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall) }
                if (interview.media.isEmpty()) Text("No recordings.", color = Muted, style = MaterialTheme.typography.bodySmall)
                interview.media.forEach { MediaWithTranscript(context, it, repository) }
            }
        }
    }
}

private const val APP_PREFS_NAME = "fieldrepo_prefs"
private const val PREF_AI_COST_ACK = "ai_refine_cost_ack"

private fun aiCostReminderSuppressed(context: Context): Boolean =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).getBoolean(PREF_AI_COST_ACK, false)

private fun suppressAiCostReminder(context: Context) {
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(PREF_AI_COST_ACK, true).apply()
}

@Composable
private fun TranscriptRefineControls(
    context: Context,
    media: MediaFileDto,
    repository: WorkshopRepository,
    onApplied: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var refining by remember(media.id) { mutableStateOf(false) }
    var refined by remember(media.id) { mutableStateOf<String?>(null) }
    var refinedTranslated by remember(media.id) { mutableStateOf(false) }
    var refineError by remember(media.id) { mutableStateOf<String?>(null) }
    var applying by remember(media.id) { mutableStateOf(false) }
    var appliedNote by remember(media.id) { mutableStateOf<String?>(null) }
    var pendingTranslate by remember { mutableStateOf<Boolean?>(null) }
    var dontRemind by remember { mutableStateOf(false) }
    val pad = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 10.dp)

    fun runRefine(translate: Boolean) {
        scope.launch {
            refining = true
            refineError = null
            runCatching { repository.refineTranscript(media.id, translate) }
                .onSuccess { resp ->
                    when {
                        !resp.refined.isNullOrBlank() -> { refined = resp.refined; refinedTranslated = translate }
                        resp.available == false -> refineError = resp.message ?: "AI refinement is not configured."
                        else -> refineError = resp.message ?: "No transcript content to refine."
                    }
                }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) refineError = it.userMessage("Couldn't refine the transcript.")
                }
            refining = false
        }
    }

    fun onRefineClick(translate: Boolean) {
        if (aiCostReminderSuppressed(context)) runRefine(translate) else pendingTranslate = translate
    }

    ActionRow {
        OutlinedButton(onClick = { onRefineClick(false) }, enabled = !refining, contentPadding = pad) {
            Text("Refine transcript", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(onClick = { onRefineClick(true) }, enabled = !refining, contentPadding = pad) {
            Text("Refine & translate", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium)
        }
    }
    if (refining) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Text("Refining with AI…", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
    refineError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    refined?.let { md ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ColorCompat.darkElevated, RoundedCornerShape(8.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                if (refinedTranslated) "Refined conversation (English)" else "Refined conversation",
                color = Coral,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            MarkdownText(markdown = md, color = Canvas)
            Text(
                "Replace the current transcript with this refined version?",
                color = SurfaceCard,
                style = MaterialTheme.typography.bodySmall
            )
            ActionRow {
                Button(
                    onClick = {
                        scope.launch {
                            applying = true
                            refineError = null
                            runCatching { repository.applyTranscript(media.id, md) }
                                .onSuccess {
                                    onApplied(md)
                                    appliedNote = "Saved as the transcript ✓"
                                    refined = null
                                }
                                .onFailure {
                                    if (it !is kotlinx.coroutines.CancellationException)
                                        refineError = it.userMessage("Couldn't save the refined transcript.")
                                }
                            applying = false
                        }
                    },
                    enabled = !applying,
                    contentPadding = pad
                ) { Text(if (applying) "Saving…" else "Approve", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(modifier = Modifier.destructiveAction(), 
                    onClick = { refined = null },
                    enabled = !applying,
                    contentPadding = pad
                ) { Text("Reject", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    appliedNote?.let { Text(it, color = SuccessGreen, style = MaterialTheme.typography.bodySmall) }

    val mode = pendingTranslate
    if (mode != null) {
        AlertDialog(
            onDismissRequest = { pendingTranslate = null },
            title = { Text("This uses AI and costs extra") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Refining" + (if (mode) " and translating" else "") + " uses an AI model at a small cost. Continue?"
                    )
                    AppToggle(
                        label = "Do not remind me again",
                        checked = dontRemind,
                        onCheckedChange = { dontRemind = it }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontRemind) suppressAiCostReminder(context)
                    pendingTranslate = null
                    runRefine(mode)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { pendingTranslate = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun MarkdownText(markdown: String, color: Color = Body) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        markdown.replace("\r\n", "\n").split("\n").forEach { raw ->
            val line = raw.trim()
            val isRule = line.length >= 3 && (line.all { it == '-' } || line.all { it == '*' } || line.all { it == '_' })
            when {
                line.isEmpty() -> Spacer(Modifier.height(2.dp))
                isRule -> HorizontalDivider(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), color = Muted)
                line.startsWith("### ") -> Text(parseInlineMarkdown(line.removePrefix("### ")), display = true, color = color, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                line.startsWith("## ") -> Text(parseInlineMarkdown(line.removePrefix("## ")), display = true, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                line.startsWith("# ") -> Text(parseInlineMarkdown(line.removePrefix("# ")), display = true, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                line.startsWith("- ") || line.startsWith("* ") -> Row(modifier = Modifier.fillMaxWidth()) {
                    Text("•  ", color = color, style = MaterialTheme.typography.bodyMedium)
                    Text(parseInlineMarkdown(line.drop(2)), color = color, style = MaterialTheme.typography.bodyMedium)
                }
                else -> Text(parseInlineMarkdown(line), color = color, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun parseInlineMarkdown(text: String) = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                } else { append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                } else { append(text[i]); i++ }
            }
            else -> { append(text[i]); i++ }
        }
    }
}

@Composable
private fun RecordMediaSection(
    repository: WorkshopRepository,
    context: Context,
    linkedType: String,
    recordId: String,
    onError: (String) -> Unit,
    readOnly: Boolean = false
) {
    var media by remember(linkedType, recordId) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    var loading by remember(linkedType, recordId) { mutableStateOf(true) }
    LaunchedEffect(linkedType, recordId) {
        loading = true
        runCatching { repository.mediaForRecord(linkedType, recordId) }
            .onSuccess { media = it }
            .onFailure { onError(it.userMessage("Unable to load media")) }
        loading = false
    }
    HorizontalDivider()
    Text("Media & transcripts", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    when {
        loading -> Text("Loading media…", color = Muted, style = MaterialTheme.typography.bodySmall)
        media.isEmpty() -> Text("No media attached.", color = Muted, style = MaterialTheme.typography.bodySmall)
        else -> media.forEach { MediaWithTranscript(context, it, repository, readOnly) }
    }
}

private val StarGold: Color get() = StatusWarning

@Composable
private fun StarRatingInput(rating: Int, max: Int = 5, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.selectableGroup()) {
        (1..max).forEach { i ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .selectable(
                        selected = rating == i,
                        role = androidx.compose.ui.semantics.Role.RadioButton,
                        onClick = { onChange(if (rating == i) 0 else i) }
                    )
                    .semantics { stateDescription = if (rating == i) "$i of $max selected" else "$i of $max" }
            ) {
                Icon(
                    imageVector = if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "$i star${if (i == 1) "" else "s"}",
                    tint = if (i <= rating) StarGold else Muted,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun StarRatingDisplay(rating: Int, max: Int = 5) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..max).forEach { i ->
            Icon(
                imageVector = if (i <= rating) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (i <= rating) StarGold else Muted,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text("$rating / $max", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LabeledStarRating(label: String, rating: Int, onChange: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        StarRatingInput(rating = rating, onChange = onChange)
    }
}

@Composable
private fun LabeledRatingDisplay(label: String, rating: Int?) {
    if (rating == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        StarRatingDisplay(rating)
    }
}

@Composable
private fun QualitativeRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        Text(value, color = Body, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun dwFeedbackPlatform(): String =
    "Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        .take(300)

private fun dwFeedbackClientVersion(): String =
    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})".take(100)

@Composable
private fun dwFeedbackStatusColour(status: String): androidx.compose.ui.graphics.Color = when (status) {
    "RESOLVED" -> MaterialTheme.colorScheme.primary
    "ACKNOWLEDGED" -> StarGold
    else -> Muted
}

@Composable
private fun DwFeedbackReportCard(report: FeedbackReportDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.field.hairline, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(report.subject, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(report.kindLabel.ifBlank { report.kind }, color = Muted, style = MaterialTheme.typography.bodySmall)
            if (report.severityLabel.isNotBlank()) Text("· ${report.severityLabel}", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (report.areaLabel.isNotBlank()) Text("· ${report.areaLabel}", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            report.statusLabel.ifBlank { report.status },
            color = dwFeedbackStatusColour(report.status),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(report.details, color = Body, style = MaterialTheme.typography.bodyMedium)
        val seen = readableStamp(report.acknowledgedAt)
        val done = readableStamp(report.resolvedAt)
        if (seen != null) {
            Text("Seen by ${report.acknowledgedBy?.name ?: "an administrator (account since removed)"} on $seen",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (done != null) {
            Text("Resolved by ${report.resolvedBy?.name ?: "an administrator (account since removed)"} on $done",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        report.responseNote?.takeIf { it.isNotBlank() }?.let { Text(it, color = Body, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun FeedbackReportSection(repository: WorkshopRepository, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()

    var vocabulary by remember { mutableStateOf<FeedbackVocabularyDto?>(null) }
    var kind by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(ActionStatus.IDLE) }
    AutoResetStatus(status) { status = ActionStatus.IDLE }

    var mine by remember { mutableStateOf<List<FeedbackReportDto>?>(null) }
    var mineTotal by remember { mutableStateOf(0) }

    suspend fun reloadMine() {
        runCatching { repository.myFeedbackReports() }
            .onSuccess { page -> mine = page.items; mineTotal = page.total }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) mine = mine ?: emptyList() }
    }

    val draft = rememberFormDraft(
        key = "feedback-report",
        snapshot = {
            if (subject.isBlank() && details.isBlank()) emptyMap()
            else mapOf("kind" to kind, "severity" to severity, "area" to area, "subject" to subject, "details" to details)
        },
        restore = { v ->
            kind = v["kind"] ?: kind
            severity = v["severity"] ?: severity
            area = v["area"] ?: area
            subject = v["subject"] ?: subject
            details = v["details"] ?: details
        },
    )
    val reportContext = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        runCatching { repository.feedbackVocabularyCached(reportContext).value }
            .onSuccess { served ->
                vocabulary = served
                if (kind.isBlank()) kind = served.kind.firstOrNull()?.value.orEmpty()
            }
            .onFailure { if (it !is kotlinx.coroutines.CancellationException) onError(it.userMessage("Could not load the feedback options")) }
        reloadMine()
    }

    RecordCard(title = "Report something") {
        FormDraftRestoredNote(draft)
        Text(
            "A grievance, suggestion or bug report. You can see who reads it.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        val served = vocabulary
        if (served == null) {
            Text("Loading…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            DropdownField(
                label = "What is this?",
                options = served.kind.map { it.value to it.label },
                selectedValue = kind,
                includeNone = false,
                enabled = !sending
            ) { kind = it }
            DropdownField(
                label = "How pressing is it?",
                options = served.severity.map { it.value to it.label },
                selectedValue = severity,
                placeholder = "Not saying",
                enabled = !sending
            ) { severity = it }
            DropdownField(
                label = "Which part of the app?",
                options = served.area.map { it.value to it.label },
                selectedValue = area,
                placeholder = "Not sure",
                enabled = !sending
            ) { area = it }

            TextInput("In one line", subject) { subject = it }
            TextInput("What happened, and what you expected", details, minLines = 4) { details = it }

            Text(
                "Sends your app version and device too.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )

            ActionRow {
                Button(
                    onClick = {
                        if (subject.isBlank() || details.isBlank()) {
                            onError("Add a summary and description first.")
                            return@Button
                        }
                        scope.launch {
                            sending = true
                            runCatching {
                                repository.fileFeedbackReportOrQueue(
                                    reportContext,
                                    FeedbackReportCreateRequest(
                                        kind = kind,
                                        severity = severity.blankToNull(),
                                        area = area.blankToNull(),
                                        subject = subject.trim(),
                                        details = details.trim(),
                                        client = "ANDROID",
                                        clientVersion = dwFeedbackClientVersion(),
                                        platform = dwFeedbackPlatform(),
                                        pagePath = "Give app feedback"
                                    )
                                )
                            }
                                .onSuccess { filed ->
                                    draft.discard()
                                    if (filed == null) {
                                        onError(
                                            "Offline — saved on this phone; it sends automatically."
                                        )
                                    }
                                    status = ActionStatus.SUCCESS
                                    subject = ""
                                    details = ""
                                    severity = ""
                                    area = ""
                                    reloadMine()
                                }
                                .onFailure {
                                    if (it !is kotlinx.coroutines.CancellationException) {
                                        status = ActionStatus.ERROR
                                        onError(it.userMessage("Could not send that report"))
                                    }
                                }
                            sending = false
                        }
                    },
                    enabled = !sending,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (status) {
                            ActionStatus.SUCCESS -> SuccessGreen
                            ActionStatus.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                ) {
                    Text(
                        when {
                            sending -> "Sending…"
                            status == ActionStatus.SUCCESS -> "Sent"
                            status == ActionStatus.ERROR -> "Could not send"
                            else -> "Send report"
                        }
                    )
                }
            }
        }
    }

    RecordCard(title = "Your reports") {
        val filed = mine
        when {
            filed == null -> Text("Loading…", color = Muted, style = MaterialTheme.typography.bodySmall)
            filed.isEmpty() -> Text(
                "Your reports and replies appear here.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> {
                if (mineTotal > filed.size) {
                    Text("Latest ${filed.size} of $mineTotal.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
                filed.forEach { DwFeedbackReportCard(it) }
            }
        }
    }
}

@Composable
private fun FeedbackScreen(repository: WorkshopRepository, onError: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var rating by remember { mutableStateOf(0) }
    var easeOfUse by remember { mutableStateOf(0) }
    var reliability by remember { mutableStateOf(0) }
    var performance by remember { mutableStateOf(0) }
    var design by remember { mutableStateOf(0) }
    var features by remember { mutableStateOf(0) }
    var recommend by remember { mutableStateOf(0) }
    var role by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var likeMost by remember { mutableStateOf("") }
    var improve by remember { mutableStateOf("") }
    var bugs by remember { mutableStateOf("") }
    var featureRequests by remember { mutableStateOf("") }

    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<FeedbackDto?>(null) }
    var status by remember { mutableStateOf(ActionStatus.IDLE) }
    AutoResetStatus(status) { status = ActionStatus.IDLE }
    val context = LocalContext.current

    val draft = rememberFormDraft(
        key = if (loading) null else "app-feedback",
        snapshot = {
            mapOf(
                "rating" to rating.toString(), "easeOfUse" to easeOfUse.toString(),
                "reliability" to reliability.toString(), "performance" to performance.toString(),
                "design" to design.toString(), "features" to features.toString(),
                "recommend" to recommend.toString(), "role" to role, "comment" to comment,
                "likeMost" to likeMost, "improve" to improve, "bugs" to bugs,
                "featureRequests" to featureRequests,
            )
        },
        restore = { v ->
            rating = v["rating"]?.toIntOrNull() ?: rating
            easeOfUse = v["easeOfUse"]?.toIntOrNull() ?: easeOfUse
            reliability = v["reliability"]?.toIntOrNull() ?: reliability
            performance = v["performance"]?.toIntOrNull() ?: performance
            design = v["design"]?.toIntOrNull() ?: design
            features = v["features"]?.toIntOrNull() ?: features
            recommend = v["recommend"]?.toIntOrNull() ?: recommend
            role = v["role"] ?: role
            comment = v["comment"] ?: comment
            likeMost = v["likeMost"] ?: likeMost
            improve = v["improve"] ?: improve
            bugs = v["bugs"] ?: bugs
            featureRequests = v["featureRequests"] ?: featureRequests
        },
    )

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.myFeedback() }
            .onSuccess { fb ->
                if (fb.id.isNotBlank()) {
                    existing = fb
                    rating = fb.rating ?: 0
                    easeOfUse = fb.easeOfUse ?: 0
                    reliability = fb.reliability ?: 0
                    performance = fb.performance ?: 0
                    design = fb.design ?: 0
                    features = fb.features ?: 0
                    recommend = fb.recommend ?: 0
                    role = fb.role.orEmpty()
                    comment = fb.comment.orEmpty()
                    likeMost = fb.likeMost.orEmpty()
                    improve = fb.improve.orEmpty()
                    bugs = fb.bugs.orEmpty()
                    featureRequests = fb.featureRequests.orEmpty()
                }
            }
            .onFailure { onError(it.userMessage("Unable to load your feedback")) }
        loading = false
    }

    FeedbackReportSection(repository = repository, onError = onError)

    RecordCard(title = "Rate the app") {
        Text(
            "One rating per person; change it any time. Everything is optional.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (loading) {
            Text("Loading your feedback…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            FormDraftRestoredNote(draft)
            Text("Ratings", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text("Overall rating", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            StarRatingInput(rating = rating) { rating = it }
            Text(
                when (rating) {
                    0 -> "Tap a star to rate"
                    1 -> "Poor"
                    2 -> "Fair"
                    3 -> "Good"
                    4 -> "Very good"
                    else -> "Excellent"
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            LabeledStarRating("Ease of use", easeOfUse) { easeOfUse = it }
            LabeledStarRating("Reliability / stability", reliability) { reliability = it }
            LabeledStarRating("Speed / performance", performance) { performance = it }
            LabeledStarRating("Design / look & feel", design) { design = it }
            LabeledStarRating("Features / completeness", features) { features = it }
            LabeledStarRating("How likely you'd recommend it", recommend) { recommend = it }

            HorizontalDivider()
            Text("In your words", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            TextInput("Your role (e.g. researcher, field documenter)", role) { role = it }
            TextInput("What do you like most?", likeMost, minLines = 2) { likeMost = it }
            TextInput("What should we improve?", improve, minLines = 2) { improve = it }
            TextInput("Any bugs or issues you hit?", bugs, minLines = 2) { bugs = it }
            TextInput("Features you'd like to see", featureRequests, minLines = 2) { featureRequests = it }
            TextInput("Anything else (general comments)", comment, minLines = 3) { comment = it }

            val anyProvided = rating > 0 || easeOfUse > 0 || reliability > 0 || performance > 0 ||
                design > 0 || features > 0 || recommend > 0 ||
                listOf(role, comment, likeMost, improve, bugs, featureRequests).any { it.isNotBlank() }
            Button(
                onClick = {
                    if (!anyProvided) {
                        onError("Add a rating or a written answer first.")
                        return@Button
                    }
                    scope.launch {
                        saving = true
                        runCatching {
                            repository.saveMyFeedback(
                                context,
                                FeedbackUpsertRequest(
                                    rating = rating.takeIf { it > 0 },
                                    easeOfUse = easeOfUse.takeIf { it > 0 },
                                    reliability = reliability.takeIf { it > 0 },
                                    performance = performance.takeIf { it > 0 },
                                    design = design.takeIf { it > 0 },
                                    features = features.takeIf { it > 0 },
                                    recommend = recommend.takeIf { it > 0 },
                                    role = role.blankToNull(),
                                    comment = comment.blankToNull(),
                                    likeMost = likeMost.blankToNull(),
                                    improve = improve.blankToNull(),
                                    bugs = bugs.blankToNull(),
                                    featureRequests = featureRequests.blankToNull()
                                )
                            )
                        }
                            .onSuccess { saved ->
                                draft.discard()
                                status = ActionStatus.SUCCESS
                                if (saved != null) {
                                    existing = saved
                                } else {
                                    onError(
                                        "Offline — saved on this phone; it sends automatically."
                                    )
                                }
                            }
                            .onFailure {
                                if (it !is kotlinx.coroutines.CancellationException) {
                                    status = ActionStatus.ERROR
                                    onError(it.userMessage("Unable to save your feedback"))
                                }
                            }
                        saving = false
                    }
                },
                enabled = !saving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (status) {
                        ActionStatus.SUCCESS -> SuccessGreen
                        ActionStatus.ERROR -> FailureRed
                        ActionStatus.IDLE -> MaterialTheme.colorScheme.primary
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    when {
                        saving -> "Saving…"
                        status == ActionStatus.SUCCESS -> "Saved"
                        existing != null -> "Update feedback"
                        else -> "Submit feedback"
                    }
                )
            }
            existing?.updatedAt?.let { formatIsoDate(it)?.let { d -> Text("Last updated $d", color = Muted, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

@Composable
private fun MasterFeedbackCard(repository: WorkshopRepository, onError: (String) -> Unit) {
    var feedback by remember { mutableStateOf<List<FeedbackDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedUserId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.allFeedback() }
            .onSuccess { feedback = it }
            .onFailure { onError(it.userMessage("Unable to load feedback")) }
        loading = false
    }

    RecordCard(title = "User feedback") {
        Text("Feedback from the team. Pick a user to read theirs.", color = Muted, style = MaterialTheme.typography.bodySmall)
        when {
            loading -> Text("Loading feedback…", color = Muted, style = MaterialTheme.typography.bodySmall)
            feedback.isEmpty() -> Text("No feedback submitted yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> {
                val avg = feedback.mapNotNull { it.rating }.takeIf { it.isNotEmpty() }?.average()
                Text(
                    "${plural(feedback.size, "user")} gave feedback" + (avg?.let { " · average rating ${"%.1f".format(it)} / 5" } ?: ""),
                    color = Body,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                val options = feedback
                    .sortedBy { (it.user?.name ?: it.userId).lowercase() }
                    .map { fb ->
                        val name = fb.user?.name ?: "Unknown user"
                        fb.userId to (name + (fb.rating?.let { " · $it★" } ?: ""))
                    }
                DropdownField(
                    label = "Feedback by user",
                    options = options,
                    selectedValue = selectedUserId,
                    placeholder = "Select a user",
                    includeNone = false
                ) { selectedUserId = it }
                feedback.firstOrNull { it.userId == selectedUserId }?.let { fb ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(fb.user?.name ?: "Unknown user", display = true, color = Body, fontWeight = FontWeight.SemiBold)
                        fb.user?.email?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        if (!fb.role.isNullOrBlank()) Text("Role: ${fb.role}", color = Muted, style = MaterialTheme.typography.bodySmall)

                        if (fb.rating != null) {
                            Text("Overall", color = Muted, style = MaterialTheme.typography.bodySmall)
                            StarRatingDisplay(fb.rating!!)
                        }
                        LabeledRatingDisplay("Ease of use", fb.easeOfUse)
                        LabeledRatingDisplay("Reliability", fb.reliability)
                        LabeledRatingDisplay("Performance", fb.performance)
                        LabeledRatingDisplay("Design", fb.design)
                        LabeledRatingDisplay("Features", fb.features)
                        LabeledRatingDisplay("Would recommend", fb.recommend)

                        val hasText = listOf(fb.likeMost, fb.improve, fb.bugs, fb.featureRequests, fb.comment).any { !it.isNullOrBlank() }
                        if (hasText) HorizontalDivider()
                        QualitativeRow("Likes most", fb.likeMost)
                        QualitativeRow("To improve", fb.improve)
                        QualitativeRow("Bugs / issues", fb.bugs)
                        QualitativeRow("Feature requests", fb.featureRequests)
                        QualitativeRow("General comments", fb.comment)

                        val nothing = fb.rating == null && fb.easeOfUse == null && fb.reliability == null &&
                            fb.performance == null && fb.design == null && fb.features == null &&
                            fb.recommend == null && !hasText
                        if (nothing) Text("No details provided.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        formatIsoDate(fb.updatedAt)?.let { Text("Updated $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrphanRecordingsCard(repository: WorkshopRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var orphans by remember { mutableStateOf<List<MediaFileDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var targetOptions by remember { mutableStateOf<Map<String, List<Pair<String, String>>>>(emptyMap()) }
    val selections = remember { mutableStateMapOf<String, String>() }
    var busy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<MediaFileDto?>(null) }

    suspend fun loadOptionsFor(types: Set<String>) {
        val map = targetOptions.toMutableMap()
        types.forEach { t ->
            if (map.containsKey(t)) return@forEach
            runCatching {
                when (t.lowercase()) {
                    "questionnaire", "questionnaireinterview" -> repository.interviews().map { it.id to it.title.ifBlank { "Untitled interview" } }
                    "product" -> repository.products().map { it.id to "${it.productName} · ${it.artisanName}" }
                    "tool" -> repository.tools().map { it.id to it.toolRowLabel() }
                    "artisan" -> repository.artisans().map { it.id to it.name }
                    "craft" -> repository.crafts().map { it.id to it.name }
                    "workshop" -> repository.workshops().map { it.id to workshopDisplayTitle(it.title, date = it.occurrenceDate(), place = it.place) }
                    else -> emptyList()
                }
            }.onSuccess { map[t] = it }
        }
        targetOptions = map
    }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { repository.orphanedMedia() }
            .onSuccess {
                orphans = it
                loadOptionsFor(it.mapNotNull { m -> m.linkedRecordType }.toSet())
            }
            .onFailure { onError(it.userMessage("Unable to load recovered recordings")) }
        loading = false
    }

    RecordCard(title = "Recovered recordings") {
        Text(
            "Recordings whose record was deleted. Play them or re-link to an existing record.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        when {
            loading -> Text("Loading…", color = Muted, style = MaterialTheme.typography.bodySmall)
            orphans.isEmpty() -> Text("None — every recording is attached.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(plural(orphans.size, "recovered file"), color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                orphans.forEach { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(SurfaceCard, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val typeLabel = m.linkedRecordType?.replaceFirstChar { it.uppercase() } ?: "Record"
                        Text("From a deleted $typeLabel", color = Muted, style = MaterialTheme.typography.bodySmall)
                        m.uploadedBy?.name?.let { Text("Recorded by $it", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        MediaWithTranscript(context, m, repository)
                        val opts = targetOptions[m.linkedRecordType].orEmpty()
                        if (opts.isEmpty()) {
                            Text("No ${typeLabel.lowercase()} to re-link to.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        } else {
                            DropdownField(
                                label = "Re-link to a ${typeLabel.lowercase()}",
                                options = opts,
                                selectedValue = selections[m.id].orEmpty(),
                                placeholder = "Select a record",
                                includeNone = false
                            ) { selections[m.id] = it }
                            Button(
                                onClick = {
                                    val target = selections[m.id]
                                    if (target.isNullOrBlank()) { onError("Pick a record to re-link to first."); return@Button }
                                    scope.launch {
                                        busy = busy + m.id
                                        runCatching { repository.relinkMedia(m.id, m.linkedRecordType ?: "", target) }
                                            .onSuccess { orphans = orphans.filterNot { it.id == m.id }; selections.remove(m.id) }
                                            .onFailure { onError(it.userMessage("Unable to re-link this recording")) }
                                        busy = busy - m.id
                                    }
                                },
                                enabled = m.id !in busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (m.id in busy) "Re-linking…" else "Re-link this recording") }
                        }
                        OutlinedButton(
                            onClick = { pendingDelete = m },
                            enabled = m.id !in busy,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (m.id in busy) "Working…" else "Permanently delete")
                        }
                    }
                }
            }
        }
    }
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Permanently delete recording?") },
            text = {
                Text(
                    "Permanently delete “${toDelete.originalFilename}”? It can't be re-linked afterwards."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        scope.launch {
                            busy = busy + toDelete.id
                            runCatching { repository.deleteMedia(toDelete.id) }
                                .onSuccess { orphans = orphans.filterNot { it.id == toDelete.id }; selections.remove(toDelete.id) }
                                .onFailure { onError(it.userMessage("Unable to delete this recording")) }
                            busy = busy - toDelete.id
                        }
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private const val COMPLETION_OPTION_ID = "__completion__"

internal enum class AdminHubEntry(
    val label: String,
    val description: String,
    val icon: ImageVector,
    val masterOnly: Boolean = false,
    val reviewGated: Boolean = false
) {
    REVIEWS("Reviews & approvals", "Approve or reject submitted records.", Icons.Filled.ManageAccounts, reviewGated = true),
    RECOVERED("Recovered recordings", "Play and re-link orphaned recordings.", Icons.Filled.RecordVoiceOver),
    FEEDBACK("User feedback", "Everyone's app feedback, grouped by user.", Icons.Filled.RateReview, masterOnly = true),
    TOOLS("Assign tools to artisans", "Link tools to the artisans who use them.", Icons.Filled.Build),
    TASKS(
        "Task assignment",
        "Assign documentation work and track it.",
        Icons.AutoMirrored.Filled.Assignment
    ),
    WORKSHOPS("Workshop assignments", "Choose who can submit to each workshop.", Icons.Filled.Groups),
    ACCESS_REQUESTS("Workshop access requests", "Approve or deny workshop access requests.", Icons.Filled.LockOpen),
    API_KEYS(
        "API keys",
        "Rank the transcription providers, and — for the master admin — rotate, test and reveal keys.",
        Icons.Filled.VpnKey
    ),
    USAGE(
        "Usage",
        "Which screens are reached, how often, how fast, and how often broken — aggregated across every account.",
        Icons.Filled.Insights
    ),
    SETTINGS("Settings", "Transcription output and off-peak processing.", Icons.Filled.Tune, masterOnly = true)
}

internal fun adminHubEntriesFor(isMasterAdmin: Boolean, canReview: Boolean): List<AdminHubEntry> =
    AdminHubEntry.entries.filter { (!it.masterOnly || isMasterAdmin) && (!it.reviewGated || canReview) }

@Composable
private fun AdminHubScreen(
    repository: WorkshopRepository,
    isMasterAdmin: Boolean,
    canReview: Boolean,
    section: AdminHubEntry? = null,
    onSectionChange: (AdminHubEntry?) -> Unit,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    var directory by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    LaunchedEffect(Unit) { runCatching { repository.userDirectory() }.onSuccess { directory = it } }

    val entries = adminHubEntriesFor(isMasterAdmin = isMasterAdmin, canReview = canReview)
    val current = section

    if (current == null) {
        RecordCard(title = "Admin tools", icon = Icons.Filled.Tune) {
            Text("Administrative tools and settings.", color = Muted, style = MaterialTheme.typography.bodySmall)
            entries.forEach { entry ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceCard, RoundedCornerShape(12.dp))
                        .openable(entry.label, RoundedCornerShape(12.dp)) { onSectionChange(entry) }
                        .padding(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(entry.icon, contentDescription = null, tint = Canvas, modifier = Modifier.size(20.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.label, display = true, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text(entry.description, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    Text("›", color = Muted, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    } else {
        when (current) {
            AdminHubEntry.REVIEWS -> ReviewApprovalCard(repository = repository, onError = onError)
            AdminHubEntry.RECOVERED -> OrphanRecordingsCard(repository = repository, onError = onError)
            AdminHubEntry.FEEDBACK -> MasterFeedbackCard(repository = repository, onError = onError)
            AdminHubEntry.TOOLS -> ToolAssignScreen(repository = repository, onError = onError)
            AdminHubEntry.TASKS -> TaskAdminScreen(
                repository = repository,
                onBack = null,
                onMessage = onMessage,
                onError = onError
            )
            AdminHubEntry.WORKSHOPS -> WorkshopAssignmentCard(repository = repository, directory = directory, onMessage = onMessage, onError = onError)
            AdminHubEntry.ACCESS_REQUESTS -> WorkshopAccessQueueCard(repository = repository, onMessage = onMessage, onError = onError)
            AdminHubEntry.API_KEYS -> ApiKeysScreen(
                repository = repository,
                isMasterAdmin = isMasterAdmin,
                onBack = null,
                onMessage = onMessage,
                onError = onError
            )
            AdminHubEntry.USAGE -> UsageScreen(repository = repository, onBack = null)
            AdminHubEntry.SETTINGS -> SettingsScreen(repository = repository, onMessage = onMessage, onError = onError)
        }
    }
}

@Composable
private fun ViewDataScreen(
    repository: WorkshopRepository,
    canReview: Boolean = false,
    masterAdmin: Boolean = false,
    isAdmin: Boolean = false,
    adminChrome: Boolean = true,
    showProvenance: Boolean = false,
    canDownloadDataset: Boolean = false,
    onOpenDataBrowser: (() -> Unit)? = null,
    onError: (String) -> Unit
) {
    var mode by remember { mutableStateOf(EntryMode.ARTISAN) }
    var options by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selectedId by remember { mutableStateOf("") }
    var loadingList by remember { mutableStateOf(false) }

    var interviewsDetailed by remember { mutableStateOf<List<QuestionnaireInterviewDetailDto>>(emptyList()) }
    var artisanFilterList by remember { mutableStateOf<List<ArtisanDto>>(emptyList()) }
    var selectedArtisanIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var questionnaireSections by remember { mutableStateOf<List<QuestionnaireSectionDto>>(emptyList()) }

    val questionnaireOptions = remember(interviewsDetailed, selectedArtisanIds, artisanFilterList, questionnaireSections) {
        if (selectedArtisanIds.isEmpty()) emptyList()
        else {
            val nameById = artisanFilterList.associate { it.id to it.name }
            val codeByQuestionId = questionnaireSections.flatMap { sec ->
                sec.questions.map { q -> q.id to "${sec.code}${q.sortOrder}" }
            }.toMap()
            interviewsDetailed
                .filter { iv -> iv.artisans.any { it.artisanId in selectedArtisanIds } }
                .groupBy { interviewGroupKey(it) }
                .map { (_, group) ->
                    val representative = representativeInterview(group)
                    val artisanNames = representative.artisans
                        .mapNotNull { link -> link.artisan?.name ?: nameById[link.artisanId] }
                        .distinct()
                        .joinToString(", ")
                        .ifBlank { "Unknown artisan" }
                    val answeredCodes = group
                        .flatMap { iv -> iv.responses }
                        .mapNotNull { codeByQuestionId[it.questionId] }
                        .distinct()
                        .sorted()
                    val qSpan = when {
                        answeredCodes.isEmpty() -> null
                        answeredCodes.size <= 4 -> answeredCodes.joinToString(" ")
                        else -> "${answeredCodes.first()}–${answeredCodes.last()} (${answeredCodes.size})"
                    }
                    val researchers = group.mapNotNull { it.createdBy?.name }.distinct().joinToString(", ")
                    val rest = listOfNotNull(
                        qSpan,
                        representative.title.takeIf { it.isNotBlank() },
                        representative.place?.takeIf { it.isNotBlank() },
                        researchers.ifBlank { null }?.let { "by $it" },
                        if (group.size > 1) "${group.size} sessions" else null
                    ).joinToString(" · ")
                    representative.id to (if (rest.isBlank()) artisanNames else "$artisanNames · $rest")
                }
                .sortedBy { it.second.lowercase() }
        }
    }

    val viewContext = LocalContext.current.applicationContext
    var listCachedNote by remember(mode) { mutableStateOf<String?>(null) }
    LaunchedEffect(mode) {
        loadingList = true
        selectedId = ""
        selectedArtisanIds = emptySet()
        if (mode == EntryMode.QUESTIONNAIRE) {
            runCatching {
                val interviewsRead = ReadCache.read(
                    viewContext,
                    "view-interviews",
                    kotlinx.serialization.builtins.ListSerializer(QuestionnaireInterviewDetailDto.serializer()),
                ) { repository.interviews() }
                val artisansRead = ReadCache.read(
                    viewContext,
                    "view-artisans",
                    kotlinx.serialization.builtins.ListSerializer(ArtisanDto.serializer()),
                ) { repository.artisans() }
                listCachedNote = if (interviewsRead.fromCache || artisansRead.fromCache) {
                    readCacheNotice(interviewsRead.cachedAt ?: artisansRead.cachedAt)
                } else {
                    null
                }
                val interviews = interviewsRead.value
                val arts = artisansRead.value
                questionnaireSections = runCatching { repository.questionnaireSections() }.getOrDefault(emptyList())
                interviews to arts
            }.onSuccess { (interviews, arts) ->
                interviewsDetailed = interviews
                val byId = arts.associateBy { it.id }
                val involved = LinkedHashMap<String, ArtisanDto>()
                interviews.forEach { iv ->
                    iv.artisans.forEach { link -> (link.artisan ?: byId[link.artisanId])?.let { involved.putIfAbsent(it.id, it) } }
                }
                artisanFilterList = involved.values.sortedBy { it.name.lowercase() }
            }.onFailure { onError(it.userMessage("Unable to load questionnaires")) }
        } else {
            listCachedNote = null
            runCatching { loadViewEntriesCached(viewContext, repository, mode, onCached = { listCachedNote = it }) }
                .onSuccess { options = it }
                .onFailure { onError(it.userMessage("Unable to load list")) }
        }
        loadingList = false
    }
    LaunchedEffect(questionnaireOptions, mode) {
        if (mode == EntryMode.QUESTIONNAIRE && selectedId.isNotBlank() && selectedId != COMPLETION_OPTION_ID && questionnaireOptions.none { it.first == selectedId }) {
            selectedId = ""
        }
    }

    RecordCard(title = "View data") {
        listCachedNote?.let { CachedCopyNote(it) }
        Text("Pick a record type, then an entry.", color = Muted, style = MaterialTheme.typography.bodySmall)
        DropdownField(
            label = "Record type",
            options = viewDataModes.map { it.name to it.label },
            selectedValue = mode.name,
            includeNone = false
        ) { picked -> viewDataModes.firstOrNull { it.name == picked }?.let { mode = it } }
        when {
            loadingList -> Text("Loading ${mode.label.lowercase()}…", color = Muted)
            mode == EntryMode.QUESTIONNAIRE -> {
                ArtisanMultiSelectField(
                    label = "Involved artisans",
                    artisans = artisanFilterList,
                    selectedIds = selectedArtisanIds
                ) { id ->
                    selectedArtisanIds = if (selectedArtisanIds.contains(id)) selectedArtisanIds - id else selectedArtisanIds + id
                }
                val hasArtisan = selectedArtisanIds.isNotEmpty()
                val secondaryOptions = listOf(COMPLETION_OPTION_ID to "▦ Check completion (all artisans)") +
                    (if (hasArtisan) questionnaireOptions else emptyList())
                DropdownField(
                    label = "Select questionnaire",
                    options = secondaryOptions,
                    selectedValue = selectedId,
                    placeholder = "Check completion, or pick an artisan and questionnaire",
                    includeNone = false,
                    enabled = true
                ) { selectedId = it }
                if (hasArtisan && questionnaireOptions.isEmpty()) {
                    Text("No questionnaires for these artisans yet. “Check completion” still shows the matrix.", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            options.isEmpty() -> Text("No ${mode.label.lowercase()} records yet.", color = Muted)
            else -> DropdownField(
                label = "Select ${mode.label.lowercase()}",
                options = options,
                selectedValue = selectedId,
                placeholder = "Select a record",
                includeNone = false
            ) { selectedId = it }
        }
    }
    val canOverrideCompletion = isAdmin && adminChrome
    if (selectedId == COMPLETION_OPTION_ID && mode == EntryMode.QUESTIONNAIRE) {
        CompletionMatrixCard(repository = repository, artisanId = null, canEdit = canOverrideCompletion, onError = onError)
    } else if (selectedId.isNotBlank()) {
        ViewDataDetail(
            repository = repository,
            mode = mode,
            recordId = selectedId,
            canOverrideCompletion = canOverrideCompletion,
            showProvenance = showProvenance,
            onError = onError
        )
    }
    if (canDownloadDataset) {
        onOpenDataBrowser?.let { DataBrowserEntryCard(onOpen = it) }
        DatasetDownloadCard(repository = repository, onError = onError)
    }
    if (canReview && !(isAdmin && adminChrome)) {
        ReviewApprovalCard(repository = repository, onError = onError)
    }
}

private data class ReviewField(
    val key: String,
    val label: String,
    val multiline: Boolean = false,
    val vocabulary: String? = null,
    val readOnly: Boolean = false,
)

private const val REVIEW_VOCABULARY_INTERVIEW_LANGUAGE = "interviewLanguage"

private fun reviewEditableFields(recordType: String): List<ReviewField> = when (recordType.lowercase()) {
    "artisan" -> listOf(
        ReviewField("name", "Name"),
        ReviewField("localName", "Local name"),
        ReviewField("place", "Place"),
        ReviewField("address", "Address", multiline = true),
        ReviewField("notes", "Notes", multiline = true),
        ReviewField("dos", "Do's", multiline = true),
        ReviewField("donts", "Don'ts", multiline = true)
    )
    "workshop" -> listOf(
        ReviewField("title", "Workshop title"),
        ReviewField("place", "Place"),
        ReviewField("description", "Description", multiline = true),
        ReviewField("notes", "Notes", multiline = true)
    )
    "product" -> listOf(
        ReviewField("productName", "Product name"),
        ReviewField("localName", "Local name"),
        ReviewField("craftName", "Craft", readOnly = true),
        ReviewField("artisanName", "Artisan name"),
        ReviewField("place", "Place"),
        ReviewField("rawMaterialsUsed", "Raw materials used", multiline = true),
        ReviewField("mainToolsUsed", "Main tools used", multiline = true),
        ReviewField("productFunctionUse", "Function / use", multiline = true),
        ReviewField("remarks", "Remarks", multiline = true)
    )
    "tool" -> listOf(
        ReviewField("toolkitName", "Toolkit name"),
        ReviewField("localName", "Local name"),
        ReviewField("englishName", "English name"),
        ReviewField("craftName", "Craft", readOnly = true),
        ReviewField("artisanName", "Artisan name"),
        ReviewField("place", "Place"),
        ReviewField("processUsedIn", "Process used in", multiline = true),
        ReviewField("material", "Material"),
        ReviewField("suggestionsForToolImprovement", "Suggestions for improvement", multiline = true),
        ReviewField("remarks", "Remarks", multiline = true)
    )
    "process" -> listOf(
        ReviewField("name", "Name of the process"),
        ReviewField("notes", "Notes", multiline = true)
    )
    "questionnaire" -> listOf(
        ReviewField("title", "Interview title"),
        ReviewField("place", "Place"),
        ReviewField("language", "Language", vocabulary = REVIEW_VOCABULARY_INTERVIEW_LANGUAGE),
        ReviewField("notes", "Notes", multiline = true)
    )
    "media" -> listOf(
        ReviewField("caption", "Caption", multiline = true),
        ReviewField("transcriptText", "Transcript", multiline = true)
    )
    else -> emptyList()
}

private suspend fun loadReviewRecordValues(
    repository: WorkshopRepository,
    recordType: String,
    recordId: String
): Map<String, String> = when (recordType.lowercase()) {
    "artisan" -> repository.artisan(recordId).let {
        mapOf(
            "name" to it.name, "localName" to it.localName.orEmpty(), "place" to it.place,
            "address" to it.address.orEmpty(), "notes" to it.notes.orEmpty(),
            "dos" to it.dos.orEmpty(), "donts" to it.donts.orEmpty()
        )
    }
    "workshop" -> repository.workshop(recordId).let {
        mapOf(
            "title" to it.title, "place" to it.place,
            "description" to it.description.orEmpty(), "notes" to it.notes.orEmpty()
        )
    }
    "product" -> repository.product(recordId).let {
        mapOf(
            "productName" to it.productName, "localName" to it.localName.orEmpty(),
            "craftName" to it.craftName, "artisanName" to it.artisanName, "place" to it.place,
            "rawMaterialsUsed" to it.rawMaterialsUsed.orEmpty(),
            "mainToolsUsed" to it.mainToolsUsed.orEmpty(),
            "productFunctionUse" to it.productFunctionUse.orEmpty(),
            "remarks" to it.remarks.orEmpty()
        )
    }
    "tool" -> repository.tool(recordId).let {
        mapOf(
            "toolkitName" to it.toolkitName, "localName" to it.localName.orEmpty(),
            "englishName" to it.englishName.orEmpty(), "craftName" to it.craftName,
            "artisanName" to it.artisanName, "place" to it.place,
            "processUsedIn" to it.processUsedIn.orEmpty(), "material" to it.material.orEmpty(),
            "suggestionsForToolImprovement" to it.suggestionsForToolImprovement.orEmpty(),
            "remarks" to it.remarks.orEmpty()
        )
    }
    "process" -> repository.process(recordId).let {
        mapOf("name" to it.name, "notes" to it.notes.orEmpty())
    }
    "questionnaire" -> repository.interview(recordId).let {
        mapOf(
            "title" to it.title, "place" to it.place.orEmpty(),
            "language" to it.language.orEmpty(), "notes" to it.notes.orEmpty()
        )
    }
    "media" -> repository.mediaItem(recordId).let {
        mapOf("caption" to it.caption.orEmpty(), "transcriptText" to it.transcriptText.orEmpty())
    }
    else -> emptyMap()
}

@Composable
private fun ReviewApprovalCard(
    repository: WorkshopRepository,
    onError: (String) -> Unit,
    onOpenRecord: ((recordType: String, recordId: String) -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf(PendingReviewListDto()) }
    val pending: List<PendingReviewDto> = queue.items
    var loading by remember { mutableStateOf(true) }
    var loadingOlder by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            loadError = null
            runCatching { repository.pendingReviews(pageSize = REVIEW_QUEUE_PAGE_SIZE) }
                .onSuccess { queue = it }
                .onFailure { loadError = it.userMessage("The review queue could not be read.") }
            loading = false
        }
    }

    fun loadOlder() {
        val cursor = queue.nextBefore ?: return
        scope.launch {
            loadingOlder = true
            runCatching { repository.pendingReviews(pageSize = REVIEW_QUEUE_PAGE_SIZE, before = cursor) }
                .onSuccess { older ->
                    val seen = queue.items.map { it.recordType to it.id }.toSet()
                    val merged = queue.items + older.items.filterNot { (it.recordType to it.id) in seen }
                    queue = older.copy(items = merged, shown = merged.size)
                }
                .onFailure { onError(it.userMessage("Older records could not be loaded.")) }
            loadingOlder = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    RecordCard(title = "Reviews & approvals") {
        Text(
            "Pending records. Approve, edit a small mistake, send back with comments, or reject.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldCircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Loading pending records…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            loadError != null -> LoadFailedCard(message = loadError.orEmpty(), onRetry = { refresh() })
            pending.isEmpty() -> Text("Nothing is waiting for review.", color = Muted, style = MaterialTheme.typography.bodySmall)
            else -> {
                Text(if (queue.total == 1) "1 record awaiting review" else "${queue.total} records awaiting review", color = Body, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                reviewQueueCutNotice(
                    truncated = queue.truncated,
                    shown = pending.size,
                    total = queue.total,
                    cap = queue.cap,
                )?.let { Text(it, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall) }
                info?.let { Text(it, color = SuccessGreen, style = MaterialTheme.typography.bodySmall) }
                pending.forEach { item ->
                    key(item.recordType, item.id) {
                        PendingReviewRow(
                            repository = repository,
                            item = item,
                            onOpen = onOpenRecord?.let { open -> { open(item.recordType, item.id) } },
                            onResolved = { message ->
                                val remaining = queue.items.filterNot {
                                    it.recordType == item.recordType && it.id == item.id
                                }
                                queue = queue.copy(
                                    items = remaining,
                                    shown = remaining.size,
                                    total = maxOf(queue.total - 1, remaining.size),
                                )
                                info = message
                            },
                            onInfo = { info = it },
                            onError = onError
                        )
                    }
                }
                if (queue.nextBefore != null) {
                    OutlinedButton(
                        onClick = { loadOlder() },
                        enabled = !loadingOlder,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (loadingOlder) "Loading older records…" else "Load older") }
                }
            }
        }
    }
}

@Composable
private fun PendingReviewRow(
    repository: WorkshopRepository,
    item: PendingReviewDto,
    onOpen: (() -> Unit)? = null,
    onResolved: (String) -> Unit,
    onInfo: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<String?>(null) }
    var confirmReject by remember { mutableStateOf(false) }
    var reviseNote by remember { mutableStateOf("") }
    var editNote by remember { mutableStateOf("") }
    var original by remember { mutableStateOf<Map<String, String>?>(null) }
    val edited = remember { mutableStateMapOf<String, String>() }
    val fields = remember(item.recordType) { reviewEditableFields(item.recordType) }

    fun act(ok: String, resolves: Boolean, block: suspend () -> Unit) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { if (resolves) onResolved(ok) else onInfo(ok) }
                .onFailure { onError(it.apiErrorMessage("That review action didn't go through")) }
            busy = false
        }
    }

    LaunchedEffect(panel) {
        if (panel != "edit" || original != null || fields.isEmpty()) return@LaunchedEffect
        runCatching { loadReviewRecordValues(repository, item.recordType, item.id) }
            .onSuccess { values ->
                original = values
                edited.clear()
                edited.putAll(values)
            }
            .onFailure {
                panel = null
                onError(it.apiErrorMessage("Unable to open ${item.label} for editing"))
            }
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .openable(item.label, RoundedCornerShape(8.dp), enabled = onOpen != null) { onOpen?.invoke() },
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
            Text(item.label, color = if (onOpen != null) MaterialTheme.colorScheme.primary else Body, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    item.recordType.replaceFirstChar { it.uppercase() },
                    item.place?.takeIf { it.isNotBlank() },
                    item.createdBy?.name?.takeIf { it.isNotBlank() }?.let { "by $it" },
                    formatIsoDate(item.createdAt)
                ).joinToString(" · "),
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            }
            if (item.needsAdminApproval) {
                Text(
                    "Late submission — only an admin can edit or approve it.",
                    color = Coral,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            ActionRow {
                Button(
                    onClick = { act("Approved ${item.label}", resolves = true) { repository.approveRecord(item.recordType, item.id) } },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(modifier = Modifier.destructiveAction(), 
                    onClick = { confirmReject = true },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text("Reject", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
            }
            ActionRow {
                OutlinedButton(
                    onClick = { panel = if (panel == "edit") null else "edit" },
                    enabled = !busy && fields.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (panel == "edit") "Close" else RecordOpen.UPDATE_LABEL, maxLines = 1, style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(
                    onClick = { panel = if (panel == "revise") null else "revise" },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
                ) { Text(if (panel == "revise") "Close" else "Send for revision", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }

            when (panel) {
                "revise" -> {
                    Text(
                        "Comments are required. The record returns to its creator to fix.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextInput("What needs to change?", reviseNote, minLines = 2) { reviseNote = it }
                    Button(
                        enabled = !busy && reviseNote.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            act("Sent ${item.label} back for revision", resolves = true) {
                                repository.reviseRecord(item.recordType, item.id, reviseNote.trim())
                            }
                        }
                    ) { Text(if (busy) "Sending…" else "Send back with comments") }
                }
                "edit" -> {
                    val baseline = original
                    if (baseline == null) {
                        Text("Loading ${item.recordType}…", color = Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "Fix values directly. This doesn't approve it; changes are recorded under your name.",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        fields.forEach { field ->
                            val value = edited[field.key].orEmpty()
                            if (field.readOnly) {
                                OutlinedTextField(
                                    value = value.ifBlank { "—" },
                                    onValueChange = {},
                                    label = { Text(field.label) },
                                    readOnly = true,
                                    enabled = false,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else if (field.vocabulary == REVIEW_VOCABULARY_INTERVIEW_LANGUAGE) {
                                DropdownField(
                                    label = field.label,
                                    options = dwInterviewLanguageOptions(value),
                                    selectedValue = value,
                                    placeholder = DW_INTERVIEW_LANGUAGE_PLACEHOLDER,
                                    includeNone = false,
                                ) { edited[field.key] = it }
                            } else {
                                TextInput(
                                    field.label,
                                    value,
                                    minLines = if (field.multiline) 2 else 1,
                                    dictate = field.multiline,
                                ) {
                                    edited[field.key] = it
                                }
                            }
                            if (field.key in com.designprototype.workshop.data.TITLE_CASE_FIELDS) {
                                titleCasePreview(value)?.let { normalised ->
                                    Text("Will be saved as “$normalised”", color = Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        TextInput("Why? (optional, logged with the edit)", editNote) { editNote = it }
                        val changed = fields
                            .filterNot { it.readOnly }
                            .map { it.key }
                            .filter { key -> edited[key].orEmpty() != baseline[key].orEmpty() }
                            .associateWith { key -> edited[key].orEmpty() }
                        Button(
                            enabled = !busy && changed.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                act("Edited ${item.label} (${plural(changed.size, "field")})", resolves = false) {
                                    repository.editReviewedRecord(item.recordType, item.id, changed, editNote)
                                    val fresh = loadReviewRecordValues(repository, item.recordType, item.id)
                                    original = fresh
                                    edited.clear()
                                    edited.putAll(fresh)
                                    editNote = ""
                                }
                            }
                        ) {
                            Text(
                                when {
                                    busy -> "Saving…"
                                    changed.isEmpty() -> "No changes yet"
                                    else -> "Save ${plural(changed.size, "change")}"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmReject) {
        AlertDialog(
            onDismissRequest = { confirmReject = false },
            title = { Text("Reject this record?") },
            text = {
                Text(
                    "${item.label} will be rejected. To let them fix it, use Send for revision instead."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReject = false
                    act("Rejected ${item.label}", resolves = true) {
                        repository.rejectRecord(item.recordType, item.id)
                    }
                }) { Text("Reject record", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReject = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DatasetDownloadCard(repository: WorkshopRepository, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(0) }
    var total by remember { mutableStateOf(0) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var reportBusy by remember { mutableStateOf(false) }
    var reportMessage by remember { mutableStateOf<String?>(null) }

    RecordCard(title = "Download entire dataset") {
        Text(
            "Downloads every record and file as one zip, organised by workshop. May take a while.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (downloading) {
            val fraction = if (total > 0) done.toFloat() / total.toFloat() else 0f
            FieldLinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Text("Compiling $done / $total files (${(fraction * 100).toInt()}%)", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        resultMessage?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall) }
        Button(
            onClick = {
                if (downloading) return@Button
                resultMessage = null
                downloading = true
                done = 0
                total = 0
                scope.launch {
                    runCatching {
                        repository.downloadDataset(context) { d, t -> done = d; total = t }
                    }.onSuccess { res ->
                        resultMessage = "Saved to ${res.displayLocation} — ${res.saved}/${res.total} files" +
                            (if (res.failed > 0) " (${res.failed} could not be fetched)" else "") +
                            if (res.truncated) {
                                "\n\nThis export is incomplete. Ask an admin for a full extract." +
                                    if (res.skippedMedia > 0) {
                                        " (${res.skippedMedia} file${if (res.skippedMedia == 1) "" else "s"} not found.)"
                                    } else ""
                            } else ""
                    }.onFailure { onError(it.userMessage("Unable to download the dataset")) }
                    downloading = false
                }
            },
            enabled = !downloading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (downloading) "Downloading…" else "Download all data (.zip)")
        }
        HorizontalDivider()
        Text(
            "Or download a styled .xlsx with one sheet per record type.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        reportMessage?.let { Text(it, color = Body, style = MaterialTheme.typography.bodySmall) }
        OutlinedButton(
            onClick = {
                if (reportBusy) return@OutlinedButton
                reportMessage = null
                reportBusy = true
                scope.launch {
                    runCatching { repository.downloadReport(context) }
                        .onSuccess { location -> reportMessage = "Report saved to $location" }
                        .onFailure { onError(it.userMessage("Unable to download the report")) }
                    reportBusy = false
                }
            },
            enabled = !reportBusy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (reportBusy) "Preparing report…" else "Download report (.xlsx)")
        }
    }
}

private val CompletionGreen: Color get() = StatusSuccess
private val CompletionAmber: Color get() = StatusWarning
private val CompletionRed: Color get() = StatusError
private val CompletionEmpty: Color get() = StatusEmpty

private fun completionColor(status: String?, derived: Boolean): Color = when (status) {
    "COMPLETED" -> CompletionGreen
    "NEEDS_REVIEW" -> CompletionAmber
    "NEEDS_REDO" -> CompletionRed
    else -> if (derived) CompletionGreen else CompletionEmpty
}

@Composable
private fun CompletionMatrixCard(
    repository: WorkshopRepository,
    artisanId: String? = null,
    canEdit: Boolean = false,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val workshopScope = rememberWorkshopScope(repository = repository, onError = onError)
    var matrix by remember(artisanId) { mutableStateOf<CompletionMatrixDto?>(null) }
    var loading by remember(artisanId) { mutableStateOf(true) }
    var cellOverrides by remember(artisanId) { mutableStateOf<Map<Pair<String, String>, String?>>(emptyMap()) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(artisanId, workshopScope.settled, workshopScope.requestKey) {
        if (!workshopScope.settled) return@LaunchedEffect
        loading = true
        runCatching { repository.completionMatrix(artisanId, workshopScope.workshopIds) }
            .onSuccess {
                matrix = it
                cellOverrides = emptyMap()
                loading = false
            }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.userMessage("Unable to load completion"))
                loading = false
            }
    }

    RecordCard(title = "Check completion", icon = Icons.Filled.GridView) {
        Text(
            "Sections recorded per artisan; green is done. Pinch to zoom." +
                if (canEdit) " Tap a cell to set its status." else "",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        WorkshopScopeSelect(scope = workshopScope, label = "Workshops in this matrix")

        val hidden = matrix?.unassignedInterviews ?: 0
        if (hidden > 0) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.small)
                    .padding(10.dp)
            ) {
                Text(
                    "$hidden interview${if (hidden == 1) " isn't" else "s aren't"} filed under a workshop",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "So ${if (hidden == 1) "it doesn't" else "they don't"} count here. Choose All records to include ${if (hidden == 1) "it" else "them"}" +
                        if (canEdit) {
                            ", or file ${if (hidden == 1) "it" else "them"} from the Workshop screen."
                        } else ".",
                    color = MaterialTheme.field.onWarningContainer,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            CompletionLegendChip(CompletionGreen, "Completed")
            CompletionLegendChip(CompletionAmber, "Requires review")
            CompletionLegendChip(CompletionRed, "Needs redo")
            CompletionLegendChip(CompletionEmpty, "Not started")
        }
        if (matrix?.overridesAreRepositoryWide == true) {
            Text(
                "Scope filters recorded progress. Admin overrides apply under every scope.",
                color = MaterialTheme.field.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }

        val data = matrix
        when {
            loading -> Text("Loading completion…", color = Muted, style = MaterialTheme.typography.bodySmall)
            data == null || data.sections.isEmpty() -> Text("No questionnaire sections defined yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
            data.artisans.isEmpty() -> Text(
                if (!workshopScope.isAllRecords) {
                    "No artisans in these workshops. Widen the scope or choose All records."
                } else {
                    "No artisans yet."
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            else -> {
                val grid = data
                val cellByKey: Map<Pair<String, String>, CompletionCellDto> =
                    remember(grid) { grid.cells.associateBy { c -> c.artisanId to c.sectionId } }
                val nameWidth = 132.dp
                val colWidth = 52.dp
                val rowHeight = 48.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(0.5f, 3f)
                                offset += pan
                            }
                        }
                ) {
                    Column(
                        modifier = Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Row {
                            CompletionHeaderCell("Artisan", nameWidth, rowHeight, alignStart = true)
                            data.sections.forEach { section ->
                                CompletionHeaderCell(section.code, colWidth, rowHeight)
                            }
                        }
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            data.artisans.forEach { artisan ->
                                Row {
                                    Box(
                                        modifier = Modifier
                                            .width(nameWidth)
                                            .height(rowHeight)
                                            .background(Canvas)
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        Text(artisan.name, color = Body, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    data.sections.forEach { section ->
                                        val key = artisan.id to section.id
                                        val cell = cellByKey[key]
                                        val status = if (cellOverrides.containsKey(key)) cellOverrides[key] else cell?.status
                                        val derived = cell?.derived == true
                                        Box(
                                            modifier = Modifier
                                                .width(colWidth)
                                                .height(rowHeight)
                                                .padding(1.dp)
                                                .background(completionColor(status, derived), RoundedCornerShape(3.dp))
                                                .then(
                                                    if (canEdit) Modifier.clickable { editing = key } else Modifier
                                                )
                                                .semantics {
                                                    contentDescription = "${artisan.name}, ${section.code}: " + when {
                                                        status == "COMPLETED" || (status == null && derived) -> "Completed"
                                                        status == "NEEDS_REVIEW" -> "Requires review"
                                                        status == "NEEDS_REDO" -> "Needs redo"
                                                        else -> "Not started"
                                                    }
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (status == "COMPLETED" || (status == null && derived)) {
                                                Text("✓", color = OnStatusSuccess, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            } else if (status == "NEEDS_REVIEW") {
                                                Text("!", color = OnStatusWarning, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            } else if (status == "NEEDS_REDO") {
                                                Text("✗", color = OnStatusError, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val cellToEdit = editing
    val loadedMatrix = matrix
    if (cellToEdit != null && canEdit && loadedMatrix != null) {
        val section = loadedMatrix.sections.firstOrNull { s -> s.id == cellToEdit.second }
        val sectionName = if (section != null) "${section.code} · ${section.title}" else "section"
        val artisan = loadedMatrix.artisans.firstOrNull { a -> a.id == cellToEdit.first }
        val artisanName = artisan?.name ?: "artisan"
        fun apply(status: String?) {
            editing = null
            cellOverrides = cellOverrides + (cellToEdit to status)
            scope.launch {
                runCatching { repository.setCompletionCell(cellToEdit.first, cellToEdit.second, status) }
                    .onFailure {
                        onError(it.userMessage("Unable to update status"))
                        cellOverrides = cellOverrides - cellToEdit
                    }
            }
        }
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
            title = { Text("Mark $sectionName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("for $artisanName", color = Muted, style = MaterialTheme.typography.bodySmall)
                    CompletionStatusButton(CompletionGreen, "Completed") { apply("COMPLETED") }
                    CompletionStatusButton(CompletionAmber, "Requires review") { apply("NEEDS_REVIEW") }
                    CompletionStatusButton(CompletionRed, "Needs to be redone") { apply("NEEDS_REDO") }
                    ActionRow {
                        TextButton(onClick = { apply(null) }) { Text("Clear (use auto-detected)") }
                    }
                }
            }
        )
    }
}

@Composable
private fun CompletionLegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CompletionHeaderCell(text: String, width: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp, alignStart: Boolean = false) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(SurfaceCard)
            .padding(horizontal = 4.dp),
        contentAlignment = if (alignStart) Alignment.CenterStart else Alignment.Center
    ) {
        Text(text, color = Body, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CompletionStatusButton(color: Color, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(modifier = Modifier.size(18.dp).background(color, RoundedCornerShape(4.dp)))
        Text(label, color = Body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ViewDataDetail(
    repository: WorkshopRepository,
    mode: EntryMode,
    recordId: String,
    canOverrideCompletion: Boolean = false,
    showProvenance: Boolean = false,
    onError: (String) -> Unit,
    readOnly: Boolean = false,
    onCompleteness: (CompletenessDto?) -> Unit = {}
) {
    val context = LocalContext.current
    var loadFailed by remember(recordId) { mutableStateOf<String?>(null) }
    var loadAttempt by remember(recordId) { mutableStateOf(0) }
    when (mode) {
        EntryMode.PROCESS -> {
            var detail by remember(recordId) { mutableStateOf<ProcessDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null;
                runCatching { repository.process(recordId) }.onSuccess { detail = it }.onFailure { loadFailed = it.userMessage("Unable to load process") }
            }
            val d = detail ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = d.name.ifBlank { "Process" }) {
                if (showProvenance) ProvenanceSection(meta = d.extraMetadata, createdByName = d.createdBy?.name)
                DetailRow("Product", d.product?.productName)
                DetailRow("Pre-processes", if (d.preProcessAvailable) "Yes" else "No")
                DetailRow("Notes", d.notes)
                DetailRow("Status", d.status)
                if (d.media.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Pre-process media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    d.media.forEach { MediaWithTranscript(context, it, repository, readOnly) }
                }
                d.steps.forEach { step ->
                    HorizontalDivider()
                    Text("Step ${step.sortOrder} · ${if (step.stepType == "SEQUENTIAL") "Sequential" else "Group"} — ${step.name}", color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    step.notes?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (step.media.isEmpty()) Text("No media.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    step.media.forEach { MediaWithTranscript(context, it, repository, readOnly) }
                }
            }
        }
        EntryMode.ARTISAN -> {
            var d by remember(recordId) { mutableStateOf<ArtisanDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null; runCatching { repository.artisan(recordId) }.onSuccess { d = it; onCompleteness(it.completeness) }.onFailure { loadFailed = it.recordLoadFailure("artisan") } }
            val v = d ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = v.name.ifBlank { "Artisan" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craft?.name)
                DetailRow("Place", v.place)
                DetailRow("Gender", v.gender)
                DetailRow("Phone", StandardValues.formatPhone(v.phoneCountryCode, v.phone))
                DetailRow("Email", v.email)
                DetailRow("Address", v.address)
                DetailRow("Notes", v.notes)
                DetailRow("Aadhaar number", maskAadhaar(v.aadhaarNumber))
                DetailRow("Pehchan card", if (v.pehchanCardAvailable) "Yes" else "No")
                DetailRow("Pehchan card number", v.pehchanCardNumber?.takeIf { it.isNotBlank() }?.let { "On file" })
                NumberedListDisplay("Do's", v.dos)
                NumberedListDisplay("Don'ts", v.donts)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError, readOnly)
            }
            CompletionMatrixCard(repository = repository, artisanId = recordId, canEdit = canOverrideCompletion, onError = onError)
            ArtisanQuestionnaireData(repository = repository, artisanId = recordId)
            AadhaarVerifyCard(
                subject = AadhaarSubject.Artisan(recordId),
                viewer = repository.cachedUser(),
                recordName = v.name,
            )
        }
        EntryMode.PRODUCT -> {
            var d by remember(recordId) { mutableStateOf<ProductDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null; runCatching { repository.product(recordId) }.onSuccess { d = it; onCompleteness(it.completeness) }.onFailure { loadFailed = it.userMessage("Unable to load product") } }
            val v = d ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = v.productName.ifBlank { "Product" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craftName)
                DetailRow("Artisan", v.artisanName)
                DetailRow("Place", v.place)
                DetailRow("Type", v.productType)
                DetailRow("Market", v.marketDemand)
                DetailRow("Materials", v.rawMaterialsUsed)
                DetailRow("Tools", v.mainToolsUsed)
                DetailRow("Function", v.productFunctionUse)
                DetailRow("Remarks", v.remarks)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError, readOnly)
            }
        }
        EntryMode.TOOL -> {
            var d by remember(recordId) { mutableStateOf<ToolDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null; runCatching { repository.tool(recordId) }.onSuccess { d = it; onCompleteness(it.completeness) }.onFailure { loadFailed = it.userMessage("Unable to load tool") } }
            val v = d ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = v.toolkitName.ifBlank { "Tool" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Craft", v.craftName)
                DetailRow("Artisan", v.artisanName)
                DetailRow("Place", v.place)
                DetailRow("Material", v.material)
                DetailRow("Material type", StandardValues.materialFamilyLabel(v.materialFamily))
                DetailRow("Maker", v.maker)
                DetailRow("Tradition", StandardValues.traditionLabel(v.traditionType))
                DetailRow("Used in", v.processUsedIn)
                DetailRow("Remarks", v.remarks)
                DetailRow("Status", v.status)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError, readOnly)
            }
        }
        EntryMode.WORKSHOP -> {
            var d by remember(recordId) { mutableStateOf<WorkshopDetailDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null; runCatching { repository.workshop(recordId) }.onSuccess { d = it; onCompleteness(it.completeness) }.onFailure { loadFailed = it.userMessage("Unable to load workshop") } }
            val v = d ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = v.title.ifBlank { "Workshop" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                DetailRow("Notes", v.notes)
                DetailRow("Status", v.status)
                DetailRow("Artisans", v.artisans.mapNotNull { it.artisan?.name }.joinToString(", ").ifBlank { null })
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError, readOnly)
            }
        }
        EntryMode.CRAFT -> {
            var d by remember(recordId) { mutableStateOf<CraftDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null; runCatching { repository.craft(recordId) }.onSuccess { d = it }.onFailure { loadFailed = it.userMessage("Unable to load craft") } }
            val v = d ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = v.name.ifBlank { "Craft" }) {
                if (showProvenance) ProvenanceSection(meta = v.extraMetadata, createdByName = v.createdBy?.name)
                DetailRow("Local name", v.localName)
                DetailRow("Category", v.category)
                DetailRow("Place", v.place)
                DetailRow("Description", v.description)
                RecordMediaSection(repository, context, mode.linkedRecordType(), recordId, onError, readOnly)
            }
        }
        EntryMode.QUESTIONNAIRE -> {
            var members by remember(recordId) { mutableStateOf<List<QuestionnaireInterviewDetailDto>?>(null) }
            var groupMedia by remember(recordId) { mutableStateOf<List<MediaFileDto>>(emptyList()) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null;
                runCatching {
                    val all = repository.interviews()
                    val selected = all.firstOrNull { it.id == recordId } ?: repository.interview(recordId)
                    val key = interviewGroupKey(selected)
                    val group = all.filter { interviewGroupKey(it) == key }.ifEmpty { listOf(selected) }
                    val mediaById = LinkedHashMap<String, MediaFileDto>()
                    group.forEach { m ->
                        runCatching { repository.mediaForRecord("questionnaire", m.id) }.getOrDefault(emptyList())
                            .forEach { mediaById[it.id] = it }
                    }
                    group to mediaById.values.toList()
                }.onSuccess { (g, media) ->
                    members = g; groupMedia = media
                    onCompleteness(g.firstOrNull { it.id == recordId }?.completeness)
                }
                    .onFailure { loadFailed = it.userMessage("Unable to load interview") }
            }
            val g = members ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            val rep = representativeInterview(g)
            RecordCard(title = rep.title.ifBlank { "Interview" }) {
                if (showProvenance) ProvenanceSection(meta = rep.extraMetadata, createdByName = rep.createdBy?.name)
                DetailRow("Artisans", rep.artisans.mapNotNull { it.artisan?.name }.joinToString(", ").ifBlank { null })
                DetailRow("Place", rep.place)
                DetailRow("Language", StandardValues.languageName(rep.language))
                DetailRow("Notes", rep.notes)
                DetailRow("Status", rep.status)
                val researchers = g.mapNotNull { it.createdBy?.name }.distinct().joinToString(", ")
                if (researchers.isNotBlank()) DetailRow(if (researchers.contains(", ")) "Interviewers" else "Interviewer", researchers)
                if (g.size > 1) DetailRow("Merged from", "${g.size} interview records for these artisans")
                val allResponses = g.flatMap { it.responses }
                    .filter { !it.answerText.isNullOrBlank() }
                    .distinctBy { it.questionId to it.answerText }
                if (allResponses.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Answers", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    allResponses.forEach { r ->
                        DetailRow(r.answeredBy?.name ?: "Answer", questionnaireAnswerPlain(r.answerText))
                    }
                }
                HorizontalDivider()
                Text("Recordings & media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                if (groupMedia.isEmpty()) Text("No media attached.", color = Muted, style = MaterialTheme.typography.bodySmall)
                else groupMedia.forEach { MediaWithTranscript(context, it, repository, readOnly) }
            }
        }
        EntryMode.MEDIA -> {
            var d by remember(recordId) { mutableStateOf<MediaFileDto?>(null) }
            LaunchedEffect(recordId, loadAttempt) { loadFailed = null; runCatching { repository.mediaItem(recordId) }.onSuccess { d = it }.onFailure { loadFailed = it.userMessage("Unable to load media") } }
            val v = d ?: return run { LoadingCard(mode, loadFailed) { loadAttempt++ } }
            RecordCard(title = v.originalFilename.ifBlank { "Media" }) {
                DetailRow("Type", v.mediaType)
                DetailRow("Caption", v.caption)
                v.linkedRecordType?.takeIf { it.isNotBlank() }?.let { DetailRow("Linked to", it.replaceFirstChar { c -> c.uppercase() }) }
                MediaWithTranscript(context, v, repository, readOnly)
            }
        }
        else -> Text("This record type cannot be viewed here.", color = Muted)
    }
    if (mode in setOf(
            EntryMode.ARTISAN, EntryMode.PRODUCT, EntryMode.TOOL, EntryMode.WORKSHOP,
            EntryMode.PROCESS, EntryMode.QUESTIONNAIRE, EntryMode.CRAFT, EntryMode.MEDIA
        )
    ) {
        RecordCollabSection(repository, mode.linkedRecordType(), recordId, onError)
    }
}

@Composable
private fun RecordCollabSection(
    repository: WorkshopRepository,
    recordType: String,
    recordId: String,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var comments by remember(recordId) { mutableStateOf<List<EntryCommentDto>>(emptyList()) }
    var revisions by remember(recordId) { mutableStateOf<List<RecordRevisionDto>?>(null) }
    var draft by remember(recordId) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val me = remember { repository.cachedUser() }
    var editingId by remember(recordId) { mutableStateOf<String?>(null) }
    var editDraft by remember(recordId) { mutableStateOf("") }

    fun reload() {
        scope.launch {
            runCatching { comments = repository.entryComments(recordType, recordId) }
                .onFailure { onError(it.userMessage("Unable to load comments")) }
            revisions = runCatching { repository.recordRevisions(recordType, recordId) }.getOrNull()
        }
    }

    LaunchedEffect(recordType, recordId) { reload() }

    RecordCard(title = "Comments & edit history") {
        Text("Comments", display = true, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        if (comments.isEmpty()) Text("No comments yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        comments.forEach { c ->
            if (editingId == c.id) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextInput("Edit comment", editDraft, minLines = 2) { editDraft = it }
                    ActionRow {
                        OutlinedButton(enabled = !busy, onClick = { editingId = null }) { Text("Cancel") }
                        Button(
                            enabled = !busy && editDraft.isNotBlank(),
                            onClick = {
                                val next = editDraft.trim()
                                if (next == c.body) {
                                    editingId = null
                                    return@Button
                                }
                                scope.launch {
                                    busy = true
                                    runCatching { repository.updateEntryComment(c.id, next) }
                                        .onSuccess { editingId = null; reload() }
                                        .onFailure { onError(it.userMessage("Unable to save comment")) }
                                    busy = false
                                }
                            }
                        ) { Text(if (busy) "Saving…" else "Save") }
                    }
                }
                return@forEach
            }
            EntryCommentRow(
                comment = c,
                viewerId = me?.id,
                busy = busy,
                onEdit = { editingId = c.id; editDraft = c.body },
                onRemove = {
                    scope.launch {
                        busy = true
                        runCatching { repository.deleteEntryComment(c.id) }
                            .onSuccess { reload() }
                            .onFailure { onError(it.userMessage("Unable to remove the comment")) }
                        busy = false
                    }
                }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) { TextInput("Add a comment (needs comment access)", draft) { draft = it } }
            Button(
                enabled = !busy && draft.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.addEntryComment(recordType, recordId, draft.trim()); draft = "" }
                            .onSuccess { reload() }
                            .onFailure { onError(it.userMessage("Unable to post comment")) }
                        busy = false
                    }
                }
            ) { Text("Post") }
        }
        revisions?.let { revs ->
            HorizontalDivider()
            RecordEditHistorySection(revs)
        }
    }
}

private val mediaLinkModes = listOf(
    EntryMode.ARTISAN, EntryMode.WORKSHOP, EntryMode.CRAFT, EntryMode.TOOL,
    EntryMode.PRODUCT, EntryMode.PROCESS, EntryMode.QUESTIONNAIRE, EntryMode.MEDIA
)

@Composable
private fun AndroidMediaForm(
    repository: WorkshopRepository,
    onUploaded: (Int) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val savedNotice = LocalAppNotice.current
    val scope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var mediaTitle by remember { mutableStateOf("") }
    var linkedMode by remember { mutableStateOf<EntryMode?>(null) }
    var linkedEntryId by remember { mutableStateOf("") }
    var entryOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loadingEntries by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf("") }
    var location by remember { mutableStateOf<LocationRequest?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf(ActionStatus.IDLE) }
    var uploadFraction by remember { mutableStateOf(0f) }
    var uploadProgressText by remember { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }
    var savedMedia by remember { mutableStateOf<List<com.designprototype.workshop.data.MediaFileDto>>(emptyList()) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    val designWorkshop = rememberDesignWorkshopPicker(repository, isEdit = false, initialId = null)
    var jobsReload by remember { mutableStateOf(0) }
    var restoredEntryId by remember { mutableStateOf<String?>(null) }
    var entriesCachedNote by remember { mutableStateOf<String?>(null) }
    val draft = rememberFormDraft(
        key = "media:new",
        snapshot = {
            mapOf(
                "${com.designprototype.workshop.data.URI_FIELD_PREFIX}selected" to selectedUris.joinToString("\n"),
                "transcribe:selected" to selectedUris.filter { TranscribeChoices.isOn(it.toString()) }.joinToString("\n"),
                "title" to mediaTitle, "caption" to caption, "linkedMode" to (linkedMode?.name ?: ""),
                "linkedEntryId" to linkedEntryId,
                "location" to (location?.let { offlineFormJson.encodeToString(it) } ?: ""),
            )
        },
        restore = { v ->
            v["${com.designprototype.workshop.data.URI_FIELD_PREFIX}selected"]?.let {
                selectedUris = com.designprototype.workshop.data.formDraftUriList(it).map(Uri::parse)
            }
            v["transcribe:selected"]?.split('\n')?.filter { it.isNotBlank() }?.forEach { TranscribeChoices.set(it, true) }
            mediaTitle = v["title"] ?: mediaTitle
            caption = v["caption"] ?: caption
            v["linkedMode"]?.let { name -> EntryMode.entries.firstOrNull { it.name == name }?.let { linkedMode = it } }
            restoredEntryId = v["linkedEntryId"]?.takeIf { it.isNotBlank() }
            v["location"]?.let { text ->
                location = text.takeIf { it.isNotBlank() }?.let {
                    runCatching { offlineFormJson.decodeFromString<LocationRequest>(it) }.getOrNull()
                }
            }
        },
    )

    suspend fun queueForLater(
        items: List<Pair<Int, Uri>>,
        serverDown: Boolean = false,
        signInNeeded: Boolean = false
    ): Boolean {
        val result = runCatching {
            repository.queueLooseMedia(
                context = context,
                linkedRecordType = linkedMode?.linkedRecordType() ?: "",
                linkedRecordId = linkedEntryId,
                designWorkshopId = designWorkshop.value(),
                caption = caption,
                location = location,
                titleHint = mediaTitle.ifBlank { caption },
                items = items,
            )
        }
        result.onSuccess { queued ->
            val message = offlineSavedMessage(
                queued,
                isCorrection = false,
                serverDown = serverDown,
                signInNeeded = signInNeeded
            )
            localMessage = null
            if (queued.allFilesQueued) savedNotice(message) else onError(message)
        }.onFailure { error ->
            onError(error.userMessage("Couldn't keep these files here.") + " They're still selected.")
        }
        return result.isSuccess
    }
    AutoResetStatus(uploadStatus) { uploadStatus = ActionStatus.IDLE }

    fun refreshMedia() {
        scope.launch {
            runCatching { repository.media() }
                .onSuccess { savedMedia = it }
                .onFailure { error -> onError(error.userMessage("Unable to load saved media")) }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    val capturePermission = rememberPermissionGate(onError)
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) selectedUris = selectedUris + uris
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) selectedUris = selectedUris + uri
        pendingCaptureUri = null
    }
    val takeVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val uri = pendingCaptureUri
        if (success && uri != null) selectedUris = selectedUris + uri
        pendingCaptureUri = null
    }

    LaunchedEffect(Unit) {
        refreshMedia()
    }
    LaunchedEffect(linkedMode) {
        linkedEntryId = restoredEntryId ?: ""
        restoredEntryId = null
        entryOptions = emptyList()
        val mode = linkedMode ?: return@LaunchedEffect
        loadingEntries = true
        entriesCachedNote = null
        runCatching { loadViewEntriesCached(context, repository, mode, onCached = { entriesCachedNote = it }) }
            .onSuccess { entryOptions = it }
            .onFailure { error -> onError(error.userMessage("Unable to load ${mode.label} entries")) }
        loadingEntries = false
    }

    RecordCard(title = "Capture media") {
        FormDraftRestoredNote(draft)
        entriesCachedNote?.let { CachedCopyNote(it) }
        Text(
            "Media is saved online with your records; audio is transcribed after upload.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        ActionRow {
            Button(onClick = { pickMedia.launch("*/*") }) {
                Text("Pick multiple files")
            }
            OutlinedButton(
                onClick = {
                    capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                        val uri = createAppFileUri(context, "field-photo-", ".jpg")
                        pendingCaptureUri = uri
                        takePhoto.launch(uri)
                    }
                },
            ) {
                Text("Take image")
            }
            OutlinedButton(
                onClick = {
                    capturePermission.run(CAMERA_PERMISSION, CAMERA_REFUSED_SENTENCE) {
                        val uri = createAppFileUri(context, "field-video-", ".mp4")
                        pendingCaptureUri = uri
                        takeVideo.launch(uri)
                    }
                },
            ) {
                Text("Record video")
            }
            OutlinedButton(
                onClick = {
                    if (!recording && !capturePermission.holds(MICROPHONE_PERMISSION)) {
                        capturePermission.ask(MICROPHONE_PERMISSION, MICROPHONE_REFUSED_SENTENCE)
                    } else if (!recording) {
                        runCatching {
                            val file = createAppFile(context, "field-audio-", ".m4a")
                            recorder = createAudioRecorder(context, file).also { it.start() }
                            recordingFile = file
                            recording = true
                            localMessage = "Recording audio..."
                        }.onFailure { error ->
                            onError(error.userMessage("Unable to start audio recording"))
                        }
                    } else {
                        runCatching {
                            recorder?.stop()
                            recorder?.release()
                            recordingFile?.let { file -> selectedUris = selectedUris + uriForFile(context, file) }
                        }.onFailure { error ->
                            onError(error.userMessage("Unable to stop audio recording"))
                        }
                        recorder = null
                        recordingFile = null
                        recording = false
                        localMessage = "Audio recording added to batch"
                    }
                },
            ) {
                if (recording) StopSquareLabel("Stop audio recording") else Text("Record audio ●")
        }
        }
        if (recording) {
            RecordingIndicator(getAmplitude = { runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0) })
        }
        TextInput("Media title / object name", mediaTitle) { mediaTitle = it }
        DropdownField(
            label = "Linked record type *",
            options = mediaLinkModes.map { it.name to it.label },
            selectedValue = linkedMode?.name ?: "",
            placeholder = "Choose the type of record",
            includeNone = false,
            emptyMessage = BUNDLED_LIST_HAS_NO_SENTENCE
        ) { picked -> linkedMode = mediaLinkModes.firstOrNull { it.name == picked } }
        DropdownField(
            label = "Linked entry (optional)",
            options = entryOptions,
            selectedValue = linkedEntryId,
            placeholder = when {
                linkedMode == null -> "Select a record type first"
                loadingEntries -> "Loading…"
                entryOptions.isEmpty() -> "No entries for this type"
                else -> "Select an entry"
            },
            includeNone = true,
            enabled = linkedMode != null && !loadingEntries
        ) { linkedEntryId = it }
        DesignWorkshopField(
            state = designWorkshop,
            saving = uploading,
            label = "Design & prototype workshop",
            noneLabel = NO_DESIGN_WORKSHOP,
        )
        TextInput("Caption", caption, minLines = 2) { caption = it }
        LocationAddressEditor(
            repository = repository,
            value = location,
            onUseGps = {
                if (!holdsLocation(context)) permissionLauncher.launch(LOCATION_PERMISSIONS)
                readLastKnownLocation(context)
            },
            onChange = { location = it },
            onMessage = { localMessage = it }
        )
        if (selectedUris.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color = MaterialTheme.field.brandTile, shape = RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tileInk = MaterialTheme.field.onBrandTile
                Text(
                    if (selectedUris.size == 1) "1 file ready" else "${selectedUris.size} files ready",
                    color = tileInk,
                    fontWeight = FontWeight.SemiBold
                )
                var batchExpanded by remember { mutableStateOf(false) }
                val batchCap = 8
                val shownBatch = if (batchExpanded) selectedUris else selectedUris.take(batchCap)
                shownBatch.forEach { uri ->
                    AndroidUriPreview(
                        context = context,
                        uri = uri,
                        downloadable = true,
                        onRemove = { selectedUris = selectedUris.filterNot { it == uri } },
                        onTile = tileInk
                    )
                }
                ActionRow {
                    if (selectedUris.size > batchCap) {
                        OutlinedButton(
                            onClick = { batchExpanded = !batchExpanded },
                            colors = onTileButtonColors(tileInk),
                            border = onTileBorder(tileInk)
                        ) {
                            Text(if (batchExpanded) "Show fewer" else "+${selectedUris.size - batchCap} more")
                        }
                    }
                    OutlinedButton(
                        modifier = Modifier.destructiveAction(),
                        onClick = { selectedUris = emptyList() },
                        colors = onTileButtonColors(tileInk),
                        border = onTileBorder(tileInk)
                    ) {
                        Text("Clear batch")
                    }
                }
            }
            UriTranscribeChoices(selectedUris)
        }
        localMessage?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        if (uploading) {
            FieldLinearProgressIndicator(progress = { uploadFraction }, modifier = Modifier.fillMaxWidth())
            uploadProgressText?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        }
        Button(
            onClick = {
                scope.launch {
                    uploading = true
                    uploadFraction = 0f
                    uploadProgressText = null
                    val uris = selectedUris
                    val phoneOnline = repository.isOnline(context)
                    if (!phoneOnline || ServerReachability.unreachable.value) {
                        val kept = queueForLater(
                            uris.mapIndexed { index, uri -> (index + 1) to uri },
                            serverDown = phoneOnline
                        )
                        uploading = false
                        if (kept) {
                            draft.discard()
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            uploadStatus = ActionStatus.SUCCESS
                        }
                        return@launch
                    }
                    val sizes = uris.map { queryMediaSize(context, it) }
                    val totalBytes = sizes.sum().coerceAtLeast(1L)
                    val startMs = System.currentTimeMillis()
                    var completedBytes = 0L
                    var success = 0
                    val failedUris = mutableListOf<Uri>()
                    val failures = mutableListOf<Throwable>()
                    var cancelled = false
                    uris.forEachIndexed { index, uri ->
                        if (cancelled) return@forEachIndexed
                        var lastPct = -1
                        val result = runCatching {
                            repository.uploadMedia(
                                context = context,
                                uri = uri,
                                linkedRecordType = linkedMode?.linkedRecordType() ?: "",
                                linkedRecordId = linkedEntryId,
                                designWorkshopId = designWorkshop.value(),
                                caption = caption,
                                location = location,
                                titleHint = mediaTitle.ifBlank { caption },
                                batchIndex = index + 1,
                                onProgress = { sent, _ ->
                                    val done = completedBytes + sent
                                    val pct = ((done.toFloat() / totalBytes) * 100f).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        uploadFraction = pct / 100f
                                        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
                                        val rate = if (elapsed > 0) done / elapsed else 0.0
                                        val eta = if (rate > 0) ((totalBytes - done) / rate).toLong() else -1L
                                        uploadProgressText = "File ${index + 1}/${uris.size} · $pct%" +
                                            (if (eta >= 0) " · ${formatEta(eta)}" else "")
                                    }
                                }
                            )
                        }
                        when {
                            result.isSuccess -> success++
                            result.exceptionOrNull() is kotlinx.coroutines.CancellationException -> cancelled = true
                            else -> {
                                failedUris.add(uri)
                                result.exceptionOrNull()?.let { failures.add(it) }
                            }
                        }
                        completedBytes += sizes[index]
                        uploadFraction = (completedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                    }
                    uploading = false
                    uploadProgressText = null
                    uploadFraction = 0f
                    when {
                        cancelled -> Unit
                        failedUris.isEmpty() -> {
                            draft.discard()
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            localMessage = null
                            uploadStatus = ActionStatus.SUCCESS
                            refreshMedia()
                            jobsReload += 1
                            onUploaded(success)
                        }
                        (!repository.isOnline(context) || failures.all { repository.isTransient(it) }) && queueForLater(
                            failedUris.map { uri -> (uris.indexOf(uri) + 1) to uri },
                            serverDown = repository.isOnline(context) && failures.isNotEmpty() &&
                                failures.all { serverDidNotAnswer(it) },
                            signInNeeded = failures.any { sessionExpired(it) }
                        ) -> {
                            draft.discard()
                            selectedUris = emptyList()
                            mediaTitle = ""
                            caption = ""
                            uploadStatus = ActionStatus.SUCCESS
                            if (success > 0) {
                                refreshMedia()
                                jobsReload += 1
                            }
                        }
                        else -> {
                            selectedUris = failedUris.toList()
                            uploadStatus = ActionStatus.ERROR
                            val msg = if (success == 0) {
                                "All ${plural(failedUris.size, "file")} failed. Check your connection and tap Upload to retry."
                            } else {
                                "$success uploaded, ${failedUris.size} failed. Tap Upload to retry."
                            }
                            localMessage = msg
                            if (success > 0) {
                                refreshMedia()
                                jobsReload += 1
                            }
                            onError(msg)
                        }
                    }
                }
            },
            enabled = selectedUris.isNotEmpty() && linkedMode != null && !uploading && !recording,
            colors = if (uploadStatus == ActionStatus.IDLE) ButtonDefaults.buttonColors() else {
                val c = if (uploadStatus == ActionStatus.SUCCESS) SuccessGreen else FailureRed
                ButtonDefaults.buttonColors(
                    containerColor = c, contentColor = Color.White,
                    disabledContainerColor = c, disabledContentColor = Color.White
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    uploading -> "Uploading..."
                    uploadStatus == ActionStatus.SUCCESS -> "Uploaded ✓"
                    uploadStatus == ActionStatus.ERROR -> "Upload failed — tap to retry"
                    linkedMode == null -> "Choose a record type"
                    else -> "Upload batch"
                }
            )
        }
        if (savedMedia.isNotEmpty()) {
            Text("Recent saved media", display = true, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
            savedMedia.take(10).forEach { item ->
                AndroidSavedMediaPreview(
                    context = context,
                    media = item,
                    onDelete = {
                        scope.launch {
                            runCatching { repository.deleteMedia(item.id) }
                                .onSuccess {
                                    savedMedia = savedMedia.filterNot { it.id == item.id }
                                    localMessage = "Media removed"
                                }
                                .onFailure { error -> onError(error.userMessage("Unable to remove media")) }
                        }
                    },
                    repository = repository
                )
            }
        }
    }
    MediaProcessingJobsCard(repository = repository, reloadKey = jobsReload, onError = onError)
}

@Composable
private fun MediaProcessingJobsCard(
    repository: WorkshopRepository,
    reloadKey: Int,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var failedOnly by remember { mutableStateOf(true) }
    var jobs by remember { mutableStateOf<List<com.designprototype.workshop.data.MediaProcessingJobDto>?>(null) }
    var failedCount by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyJobId by remember { mutableStateOf<String?>(null) }
    var draining by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableStateOf(0) }
    val isAdmin = repository.cachedUser()?.role in setOf("ADMIN", "MASTER_ADMIN")

    LaunchedEffect(reloadKey, failedOnly, tick) {
        runCatching {
            repository.mediaJobsWithFailedCount(failedOnly = failedOnly, pageSize = 20)
        }.onSuccess { (loaded, failed) ->
            jobs = loaded
            failedCount = failed
            loadError = null
        }.onFailure { error ->
            loadError = error.userMessage("Unable to load media processing jobs")
        }
    }

    val inFlight = jobs?.any { it.status.uppercase() in setOf("QUEUED", "PROCESSING") } == true
    LaunchedEffect(inFlight) {
        if (!inFlight) return@LaunchedEffect
        while (true) {
            delay(15_000)
            tick += 1
        }
    }

    RecordCard(title = "Media processing jobs", icon = Icons.Filled.Refresh) {
        Text(
            (if (isAdmin) "Processing jobs for all accounts. " else
                "Processing jobs for your files. ") +
                if (failedCount > 0) {
                    "$failedCount failed" + if (isAdmin) " — retry below." else " — an admin can re-run them."
                } else "Nothing has failed.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = failedOnly,
                onClick = { failedOnly = true },
                label = { Text(if (failedCount > 0) "Failed ($failedCount)" else "Failed") }
            )
            FilterChip(selected = !failedOnly, onClick = { failedOnly = false }, label = { Text("All jobs") })
        }
        if (isAdmin) {
            OutlinedButton(
                onClick = {
                    draining = true
                    notice = null
                    scope.launch {
                        runCatching { repository.processMediaJobs() }
                            .onSuccess { run ->
                                notice = if (run.processed == 0) {
                                    "Nothing ran — jobs run off-peak or when a provider is free. They stay queued."
                                } else {
                                    "Ran ${plural(run.processed, "job")}: ${run.succeeded} succeeded, ${run.failed} failed."
                                }
                                tick += 1
                            }
                            .onFailure { error -> onError(error.userMessage("Unable to run the queue")) }
                        draining = false
                    }
                },
                enabled = !draining,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (draining) "Running queue…" else "Run queue now", style = MaterialTheme.typography.labelLarge)
            }
        }
        notice?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
        loadError?.let { Text(it, color = FailureRed, style = MaterialTheme.typography.bodySmall) }
        val rows = jobs
        when {
            rows == null && loadError == null -> Text("Loading processing jobs…", color = Muted, style = MaterialTheme.typography.bodySmall)
            rows != null && rows.isEmpty() -> Text(
                if (failedOnly) "No failed jobs — the queue is clear." else "No jobs yet. Audio uploads queue automatically.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            rows != null -> rows.forEach { job -> MediaProcessingJobRow(
                job = job,
                isAdmin = isAdmin,
                busy = busyJobId == job.id,
                onRetry = {
                    busyJobId = job.id
                    notice = null
                    scope.launch {
                        runCatching { repository.retryMediaJob(job.id) }
                            .onSuccess {
                                notice = "Re-queued ${job.mediaFile?.originalFilename ?: "this file"}."
                                tick += 1
                            }
                            .onFailure { error -> onError(error.userMessage("Unable to re-queue this job")) }
                        busyJobId = null
                    }
                }
            ) }
        }
    }
}

@Composable
private fun MediaProcessingJobRow(
    job: com.designprototype.workshop.data.MediaProcessingJobDto,
    isAdmin: Boolean,
    busy: Boolean,
    onRetry: () -> Unit
) {
    val status = job.status.uppercase()
    val statusColor = when (status) {
        "FAILED" -> FailureRed
        "COMPLETED" -> SuccessGreen
        else -> Muted
    }
    val typeLabel = when (job.jobType.uppercase()) {
        "TRANSCRIPTION" -> "Transcription"
        "MEASUREMENT" -> "Measurement"
        else -> job.jobType
    }
    val statusLabel = status.lowercase().replaceFirstChar { it.uppercase() }
    val retryable = status == "FAILED" || status == "CANCELLED"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            job.mediaFile?.originalFilename ?: "(file removed)",
            color = Body,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "$typeLabel · $statusLabel · attempt ${job.attempts} of ${job.maxAttempts}",
            color = statusColor,
            style = MaterialTheme.typography.bodySmall
        )
        job.error?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                (if (status == "FAILED") "Why it failed: " else "Retrying automatically: ") + reason,
                color = if (status == "FAILED") FailureRed else Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (retryable) {
            if (isAdmin) {
                ActionRow {
                    MediaActionButton(
                        label = if (busy) "Re-queuing…" else "Retry",
                        icon = Icons.Filled.Refresh,
                        onClick = onRetry,
                        enabled = !busy,
                    )
                }
            } else {
                Text("An admin can re-run this.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun queryMediaSize(context: Context, uri: Uri): Long {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0) return "almost done"
    if (seconds < 60) return "~${seconds}s left"
    return "~${seconds / 60}m ${seconds % 60}s left"
}

private fun mediaKindAndSize(context: Context, uri: Uri, mediaType: String): String {
    val kind = when (mediaType) {
        "IMAGE" -> "Photo"
        "VIDEO" -> "Video"
        "AUDIO" -> "Audio"
        "PDF" -> "PDF document"
        else -> "File"
    }
    val bytes = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else null
        }
    }.getOrNull() ?: uri.path?.let { java.io.File(it) }?.takeIf { it.isFile }?.length()
    return if (bytes == null || bytes <= 0L) kind else "$kind · ${readableBytes(bytes)}"
}

private fun readableBytes(bytes: Long): String = when {
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "${bytes / 1_000L} KB"
    else -> "$bytes bytes"
}

private fun mediaTypeFromMime(mime: String?): String = when {
    mime == null -> "DOCUMENT"
    mime.startsWith("image/") -> "IMAGE"
    mime.startsWith("video/") -> "VIDEO"
    mime.startsWith("audio/") -> "AUDIO"
    mime == "application/pdf" -> "PDF"
    else -> "DOCUMENT"
}

@Composable
private fun AndroidUriPreview(
    context: Context,
    uri: Uri,
    onRemove: (() -> Unit)? = null,
    progress: Float? = null,
    failed: Boolean = false,
    onRetry: (() -> Unit)? = null,
    downloadable: Boolean = false,
    onTile: Color? = null
) {
    val mimeType = remember(uri) { context.contentResolver.getType(uri) }
    val downloader = rememberMediaDownloader()
    val mediaType = remember(mimeType) { mediaTypeFromMime(mimeType) }
    var showViewer by remember { mutableStateOf(false) }
    val displayName = remember(uri) { mediaDisplayNameOf(context, uri) ?: "Attached file" }
    val sizeLabel = remember(uri, mediaType) { mediaKindAndSize(context, uri, mediaType) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MediaThumb(
            uri = uri,
            mediaType = mediaType,
            modifier = Modifier.weight(1f),
            title = displayName,
            subtitle = sizeLabel,
            onOpen = {
                if (mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, mimeType)
            },
            onTile = onTile
        )
        if (onRemove != null) {
            DiscardBadge(
                contentDescription = "Discard $displayName",
                onClick = onRemove,
                tint = onTile ?: MaterialTheme.colorScheme.error
            )
        }
    }
    when {
        failed -> Text(
            "Upload failed — tap Retry, or Download to keep a copy.",
            color = if (onTile != null) MaterialTheme.field.errorOnBrandTile else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        progress != null && progress < 1f -> {
            FieldLinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
            Text("Uploading ${(progress * 100).toInt()}%", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        }
        progress != null && progress >= 1f -> Text("Uploaded", color = SuccessGreen, style = MaterialTheme.typography.bodySmall)
    }
    if (onRetry != null || downloadable) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            if (failed && onRetry != null) {
                MediaRetryButton(fileName = uri.lastPathSegment, onRetry = onRetry)
            }
            if (downloadable) {
                MediaDownloadButton(
                    fileName = uri.lastPathSegment.orEmpty(),
                    onDownload = {
                        downloader.save(uri, mediaDisplayNameOf(context, uri), mimeType)
                    },
                    onTile = onTile
                )
            }
        }
        MediaDownloadNotice(downloader)
    }
    if (showViewer) {
        MediaViewerDialog(uri = uri, mediaType = mediaType, onDismiss = { showViewer = false })
    }
}

@Composable
private fun DiscardBadge(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.error
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Filled.Close, contentDescription = contentDescription, tint = tint)
    }
}

@Composable
private fun AndroidSavedMediaPreview(
    context: Context,
    media: com.designprototype.workshop.data.MediaFileDto,
    onDelete: (() -> Unit)? = null,
    repository: com.designprototype.workshop.data.WorkshopRepository? = null
) {
    val onDelete: (() -> Unit)? = onDelete.takeIf { LocalMayDelete.current }
    var refreshed by remember(media.id, media.url) { mutableStateOf<String?>(null) }
    val readableUrl = refreshed ?: media.url
    val uri = remember(readableUrl) { readableUrl?.let(Uri::parse) }
    var showViewer by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

    LaunchedEffect(media.id, media.url, repository) {
        val repo = repository ?: return@LaunchedEffect
        val stored = media.url ?: return@LaunchedEffect
        if (!com.designprototype.workshop.data.MediaUrlRefresh.isExpired(stored, System.currentTimeMillis())) {
            return@LaunchedEffect
        }
        refreshed = repo.refreshMediaUrl(context, media.id, stored)
    }

    val onThumbError: () -> Unit = {
        val repo = repository
        if (repo != null) {
            refreshScope.launch {
                val next = repo.refreshMediaUrl(context, media.id, readableUrl)
                if (next != null) refreshed = next
            }
        }
    }

    if (uri == null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(media.originalFilename, color = Body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onDelete != null) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MediaThumb(
                uri = uri,
                mediaType = media.mediaType,
                modifier = Modifier.weight(1f),
                title = media.originalFilename,
                subtitle = listOfNotNull(media.mimeType ?: media.mediaType, media.transcriptStatus?.let { "Transcript: $it" }).joinToString(" · "),
                onOpen = {
                    if (media.mediaType in IN_APP_PLAYABLE) showViewer = true else openUri(context, uri, media.mimeType)
                },
                onLoadError = onThumbError
            )
            if (onDelete != null) {
                DiscardBadge(
                    contentDescription = "Remove ${media.originalFilename}",
                    onClick = { confirmDelete = true }
                )
            }
        }
        ActionRow {
            MediaActionButton(
                label = "Download",
                icon = Icons.Filled.Download,
                onClick = { saveMediaToDevice(context, readableUrl, media.originalFilename, media.mimeType) },
                accessibleLabel = "Download ${media.originalFilename}",
            )
            if (onDelete != null) {
                TextButton(modifier = Modifier.destructiveAction(), onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (showViewer) {
            MediaViewerDialog(
                uri = uri,
                mediaType = media.mediaType,
                onSave = { saveMediaToDevice(context, readableUrl, media.originalFilename, media.mimeType) },
                onDismiss = { showViewer = false }
            )
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Remove this media?") },
            text = { Text("“${media.originalFilename}” will be permanently deleted.") }
        )
    }
}

private val IN_APP_PLAYABLE = setOf("IMAGE", "VIDEO", "AUDIO")

@Composable
private fun UserManagementForm(
    repository: WorkshopRepository,
    isMasterAdmin: Boolean,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var issuedLink by remember { mutableStateOf<Pair<String, IssuedPasswordLinkDto>?>(null) }
    var linkBusy by remember { mutableStateOf(false) }
    var confirmTwoStepReset by remember { mutableStateOf<UserDto?>(null) }

    fun refreshUsers() {
        scope.launch {
            loading = true
            runCatching { repository.users() }
                .onSuccess { users = it }
                .onFailure { onError(it.userMessage("Unable to load users")) }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshUsers()
    }
    var expandedUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    var updatingUsers by remember { mutableStateOf<Set<String>>(emptySet()) }
    val actor = remember { repository.cachedUser() }
    val actorRank = remember(actor) { roleRank(actor?.role) }
    val actorIsMaster = isMasterAdmin
    val actorIsAdmin = actorRank >= RANK_ADMIN
    val assignableRoles = remember(actorRank, actorIsMaster) {
        ROLE_RANK.entries
            .filter { it.value <= actorRank && (it.key != "MASTER_ADMIN" || actorIsMaster) }
            .sortedByDescending { it.value }
            .map { it.key to roleLabel(it.key) }
    }

    RecordCard(title = "Users and access") {
        Text(
            "Professors and above can change roles (up to their own). Admins can also grant individual permissions. Tap a user to view; ${RecordOpen.UPDATE_LABEL} to change.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        issuedLink?.let { (subject, link) ->
            IssuedPasswordLinkPanel(
                link = link,
                subject = subject,
                expires = formatIsoDate(link.expiresAt) ?: link.expiresAt,
                onWithdraw = {
                    scope.launch {
                        runCatching { repository.revokePasswordLink(link.id) }
                            .onSuccess { issuedLink = null }
                            .onFailure { onError(it.apiErrorMessage("Unable to withdraw the link")) }
                    }
                },
                onDismiss = { issuedLink = null }
            )
        }
        if (actorIsAdmin) {
            AddPersonCard(
                repository = repository,
                actorRole = actor?.role,
                actorIsMaster = actorIsMaster,
                onCreated = { refreshUsers() },
                onOpenExisting = { id ->
                    expandedUsers = expandedUsers + id
                    refreshUsers()
                }
            )
        }
        confirmTwoStepReset?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmTwoStepReset = null },
                title = { Text("Turn off two-step verification?") },
                text = {
                    Text(
                        "${target.name} will sign in without two-step and is signed out everywhere. Recovery codes stop working."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmTwoStepReset = null
                        scope.launch {
                            runCatching { repository.resetTwoFactorForUser(target.id) }
                                .onSuccess { refreshUsers() }
                                .onFailure { onError(it.apiErrorMessage("Unable to turn off two-step verification")) }
                        }
                    }) { Text("Turn off") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmTwoStepReset = null }) { Text("Cancel") }
                }
            )
        }
        if (loading) {
            Text("Loading users...", color = Muted)
        }
        users.forEach { appUser ->
            val isMaster = appUser.role == "MASTER_ADMIN"
            val targetIsProfessorPlus = roleRank(appUser.role) >= RANK_PROFESSOR
            val canManageTarget = if (actorIsMaster) !isMaster else roleRank(appUser.role) < actorRank
            val canEditGrants = actorIsAdmin && canManageTarget && !targetIsProfessorPlus
            val expanded = expandedUsers.contains(appUser.id)
            val updating = expanded && updatingUsers.contains(appUser.id)
            val mayUpdateUser = canManageTarget && ((!isMaster && assignableRoles.isNotEmpty()) || actorIsAdmin)
            val grantedCount = listOf(
                targetIsProfessorPlus || appUser.canManageQuestionnaire,
                roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview,
                roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance,
                targetIsProfessorPlus || appUser.canDownloadDataset
            ).count { it }
            val toggleUser = {
                if (expanded) {
                    expandedUsers = expandedUsers - appUser.id
                    updatingUsers = updatingUsers - appUser.id
                } else {
                    expandedUsers = expandedUsers + appUser.id
                }
            }
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (updating) Modifier
                            else Modifier.clickable(
                                onClickLabel = if (expanded) "Hide ${appUser.name}" else RecordOpen.openClickLabel(appUser.name),
                                role = Role.Button,
                                onClick = toggleUser
                            )
                        )
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .then(
                                if (updating) Modifier.clickable(onClickLabel = "Hide ${appUser.name}", role = Role.Button, onClick = toggleUser)
                                else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(appUser.name, display = true, fontWeight = FontWeight.SemiBold)
                            Text("${appUser.email} · ${roleLabel(appUser.role)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                            AadhaarUserBadge(account = appUser, viewer = repository.cachedUser())
                            Text(
                                if (isMaster) "All privileges (master admin)"
                                else "$grantedCount of 4 privileges granted",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (appUser.twoFactorEnabled) {
                                Text("Two-step on", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                            }
                            Text(accountSignInStatus(appUser), color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(if (expanded) "Hide ▲" else "Show ▼", color = Body, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                    if (expanded && !updating) {
                        HorizontalDivider()
                        DetailRow("Role", roleLabel(appUser.role))
                        DetailRow("Questionnaire builder", if (targetIsProfessorPlus || appUser.canManageQuestionnaire) "Granted" else "Not granted")
                        DetailRow("Record review & approval", if (roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview) "Granted" else "Not granted")
                        DetailRow("View provenance", if (roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance) "Granted" else "Not granted")
                        DetailRow("Download entire dataset", if (targetIsProfessorPlus || appUser.canDownloadDataset) "Granted" else "Not granted")
                        DetailRow("Signs in with", accountSignInMethod(appUser))
                        DetailRow("Last sign-in", accountLastSignIn(appUser))
                        DetailRow("Two-step verification", if (appUser.twoFactorEnabled) "Turned on" else "Not turned on")
                        if (mayUpdateUser) {
                            OutlinedButton(
                                onClick = { updatingUsers = updatingUsers + appUser.id },
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text(RecordOpen.UPDATE_LABEL) }
                        }
                    }
                    if (updating) {
                        HorizontalDivider()
                        ActionRow {
                            TextButton(
                                onClick = { updatingUsers = updatingUsers - appUser.id },
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) { Text("Done updating") }
                        }
                        if (canManageTarget && !isMaster && assignableRoles.isNotEmpty()) {
                            DropdownField(
                                label = "Role",
                                options = assignableRoles,
                                selectedValue = appUser.role,
                                includeNone = false
                            ) { nextRole ->
                                if (nextRole != appUser.role) {
                                    scope.launch {
                                        runCatching { repository.updateUserRole(appUser.id, nextRole); refreshUsers() }
                                            .onFailure { onError(it.apiErrorMessage("Unable to update role")) }
                                    }
                                }
                            }
                            Text(
                                "Professors and above already have all of these; toggles grant single powers to lower roles.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                            HorizontalDivider()
                        }
                        GrantToggleRow(
                            label = "Questionnaire builder",
                            granted = targetIsProfessorPlus || appUser.canManageQuestionnaire,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserQuestionnaireAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.userMessage("Unable to update questionnaire access")) }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Record review & approval",
                            granted = roleRank(appUser.role) >= RANK_FIELD_CONTRIBUTOR || appUser.canReview,
                            enabled = actorIsAdmin && canManageTarget && roleRank(appUser.role) < RANK_FIELD_CONTRIBUTOR,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserReviewAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.userMessage("Unable to update review access")) }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "View provenance",
                            granted = roleRank(appUser.role) >= RANK_ADMIN || appUser.canViewProvenance,
                            enabled = actorIsAdmin && canManageTarget && roleRank(appUser.role) < RANK_ADMIN,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserProvenanceAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.userMessage("Unable to update provenance access")) }
                                }
                            }
                        )
                        GrantToggleRow(
                            label = "Download entire dataset",
                            granted = targetIsProfessorPlus || appUser.canDownloadDataset,
                            enabled = canEditGrants,
                            onToggle = { grant ->
                                scope.launch {
                                    runCatching { repository.updateUserDatasetAccess(appUser.id, grant); refreshUsers() }
                                        .onFailure { onError(it.userMessage("Unable to update dataset access")) }
                                }
                            }
                        )
                        if (actorIsAdmin && canManageTarget && passwordLinkOffered(appUser)) {
                            HorizontalDivider()
                            OutlinedButton(
                                enabled = !linkBusy,
                                onClick = {
                                    scope.launch {
                                        linkBusy = true
                                        runCatching { repository.issuePasswordLink(appUser.id) }
                                            .onSuccess { issuedLink = appUser.name to it }
                                            .onFailure {
                                                onError(it.apiErrorMessage("Unable to issue a password link"))
                                            }
                                        linkBusy = false
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Issue a set-password link")
                            }
                            Text(
                                "Shown once. Copy it and send it yourself.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (actorIsAdmin && canManageTarget && appUser.twoFactorEnabled) {
                            HorizontalDivider()
                            OutlinedButton(
                                onClick = { confirmTwoStepReset = appUser },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Turn off two-step verification")
                            }
                        }
                        if (actorIsAdmin && canManageTarget) {
                            HelpThemBackInActions(
                                repository = repository,
                                target = appUser,
                                onChanged = { refreshUsers() },
                                onError = onError
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class SharingConfirm(
    val title: String,
    val body: String,
    val note: String,
    val confirmLabel: String,
    val danger: Boolean,
    val onConfirm: () -> Unit
)

@Composable
private fun SharingForm(
    repository: WorkshopRepository,
    isAdmin: Boolean,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myId = repository.cachedUser()?.id
    var tiers by remember { mutableStateOf<List<DataAccessTierInfo>>(emptyList()) }
    var grants by remember { mutableStateOf(MyGrantsDto()) }
    var directory by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<SharingConfirm?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            runCatching {
                tiers = repository.dataAccessTiers()
                grants = repository.dataAccessGrants()
                directory = repository.userDirectory().filter { it.id != myId }
            }.onFailure { onError(it.userMessage("Unable to load sharing data")) }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun run(block: suspend () -> Unit, ok: String) {
        scope.launch {
            busy = true
            runCatching { block() }
                .onSuccess { reload() }
                .onFailure { onError(it.userMessage("Action failed")) }
            busy = false
        }
    }

    RecordCard(title = "Access tiers", icon = Icons.Filled.Share) {
        if (tiers.isEmpty()) Text("Loading…", color = Muted)
        tiers.forEach { t ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when (t.tier) {
                        "DOWNLOAD" -> "Download (minimum)"
                        "COMMENT" -> "Comment (medium)"
                        else -> "Edit (maximum)"
                    },
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium
                )
                Text(t.description, color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    RecordCard(title = "Request access") {
        RequestAccessFields(
            repository = repository,
            directory = directory,
            outgoing = grants.outgoing,
            onChanged = { reload() }
        )
    }

    RecordCard(title = "Grant access to your data") {
        GrantAccessFields(
            repository = repository,
            directory = directory,
            incoming = grants.incoming,
            onChanged = { reload() },
            onError = onError
        )
    }

    RecordCard(title = "Access to your data") {
        val incoming = grants.incoming
        if (incoming.isEmpty()) Text("No requests yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        incoming.forEach { g ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.grantee?.name ?: g.granteeId, display = true, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${g.grantee?.email ?: ""} · ${tierLabel(g.tier)} · ${g.status}" +
                            (if (g.allData) " · all data" else " · ${g.scopeItems.size} records"),
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                    if (!g.requestNote.isNullOrBlank()) Text("“${g.requestNote}”", color = Muted, style = MaterialTheme.typography.bodySmall)
                    val who = g.grantee?.name ?: g.granteeId
                    ActionRow {
                        when (g.status) {
                            "PENDING" -> {
                                Button(enabled = !busy, onClick = { run({ repository.decideDataAccess(g.id, "GRANTED", g.tier) }, "Granted") }) { Text("Approve") }
                                OutlinedButton(modifier = Modifier.destructiveAction(), 
                                    enabled = !busy,
                                    onClick = {
                                        confirming = SharingConfirm(
                                            title = "Deny this request?",
                                            body = "$who won't get access and will see the request as denied.",
                                            note = "They can ask again; you can grant access any time.",
                                            confirmLabel = "Deny request",
                                            danger = false,
                                            onConfirm = { run({ repository.decideDataAccess(g.id, "DENIED", null) }, "Denied") }
                                        )
                                    }
                                ) { Text("Deny") }
                            }
                            "GRANTED" -> OutlinedButton(modifier = Modifier.destructiveAction(), 
                                enabled = !busy,
                                onClick = {
                                    confirming = SharingConfirm(
                                        title = "Revoke this access?",
                                        body = "$who loses access to your data now.",
                                        note = "Their comments and edits are kept.",
                                        confirmLabel = "Revoke access",
                                        danger = true,
                                        onConfirm = { run({ repository.revokeDataAccess(g.id) }, "Revoked") }
                                    )
                                }
                            ) { Text("Revoke") }
                            else -> OutlinedButton(modifier = Modifier.destructiveAction(), 
                                enabled = !busy,
                                onClick = {
                                    confirming = SharingConfirm(
                                        title = "Remove this sharing entry?",
                                        body = "Permanently deletes this entry and its history.",
                                        note = "$who isn't notified and can ask again.",
                                        confirmLabel = "Remove entry",
                                        danger = true,
                                        onConfirm = { run({ repository.deleteDataAccess(g.id) }, "Removed") }
                                    )
                                }
                            ) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }

    RecordCard(title = "Your access to others' data") {
        val outgoing = grants.outgoing
        if (outgoing.isEmpty()) Text("No access yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        outgoing.forEach { g ->
            ElevatedCard(colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(g.owner?.name ?: g.ownerId, display = true, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${g.owner?.email ?: ""} · ${tierLabel(g.tier)} · ${g.status}",
                        color = Muted, style = MaterialTheme.typography.bodySmall
                    )
                    val withdrawing = g.status == "PENDING"
                    val owner = g.owner?.name ?: g.ownerId
                    ActionRow {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                confirming = SharingConfirm(
                                    title = if (withdrawing) "Withdraw this request?" else "Remove this access?",
                                    body = if (withdrawing) {
                                        "Your request to $owner and its note are deleted."
                                    } else {
                                        "You give up the access $owner granted."
                                    },
                                    note = if (withdrawing) {
                                        "You can ask again at any time."
                                    } else {
                                        "To get it back, request it again."
                                    },
                                    confirmLabel = if (withdrawing) "Withdraw request" else "Remove access",
                                    danger = true,
                                    onConfirm = { run({ repository.deleteDataAccess(g.id) }, "Removed") }
                                )
                            }
                        ) {
                            Text(if (withdrawing) "Withdraw" else "Remove")
                        }
                    }
                }
            }
        }
    }
    confirming?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!busy) confirming = null },
            title = { Text(pending.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pending.body)
                    Text(pending.note, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        val act = pending.onConfirm
                        confirming = null
                        act()
                    }
                ) {
                    Text(
                        pending.confirmLabel,
                        color = if (pending.danger) MaterialTheme.colorScheme.error else Color.Unspecified
                    )
                }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirming = null }) { Text("Cancel") } }
        )
    }
}

private val workshopAccessLevels = listOf("VIEW", "CONTRIBUTE", "EDIT")

private const val DEFAULT_WORKSHOP_LEVEL = "CONTRIBUTE"

private fun workshopLevelLabel(level: String): String = when (level) {
    "VIEW" -> "View"
    "CONTRIBUTE" -> "Contribute"
    "EDIT" -> "Edit"
    else -> level
}

private fun workshopAccessStatusLabel(status: String): String = when (status) {
    "PENDING" -> "Waiting for approval"
    "GRANTED" -> "Granted"
    "DENIED" -> "Denied"
    "REVOKED" -> "Revoked"
    else -> status
}

@Composable
@ReadOnlyComposable
private fun workshopAccessStatusColor(status: String): Color = when (status) {
    "GRANTED" -> SuccessGreen
    "PENDING" -> Coral
    else -> FailureRed
}

private fun workshopLevelOptions(levels: List<WorkshopAccessLevelDto>): List<Pair<String, String>> =
    if (levels.isEmpty()) workshopAccessLevels.map { it to workshopLevelLabel(it) }
    else levels.map { it.level to workshopLevelLabel(it.level) }

private fun assignmentWorkshopLabel(row: WorkshopAssignmentDto): String =
    row.workshop?.let { workshopOptionLabel(it) } ?: "A workshop you can no longer see"

@Composable
private fun WorkshopAssignmentCard(
    repository: WorkshopRepository,
    directory: List<UserDto>,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var selectedWorkshop by remember { mutableStateOf("") }
    var roster by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var loadingRoster by remember { mutableStateOf(false) }
    var busyUserId by remember { mutableStateOf<String?>(null) }
    var addUserId by remember { mutableStateOf("") }
    var addLevel by remember { mutableStateOf(DEFAULT_WORKSHOP_LEVEL) }

    LaunchedEffect(Unit) {
        runCatching { workshops = repository.workshopsByOccurrence() }
            .onFailure { onError(it.apiErrorMessage("Unable to load workshops")) }
        runCatching { levels = repository.workshopAccessLevels() }
    }

    fun loadRoster(workshopId: String) {
        if (workshopId.isBlank()) { roster = emptyList(); return }
        scope.launch {
            loadingRoster = true
            runCatching { repository.workshopAssignments(workshopId) }
                .onSuccess { roster = it }
                .onFailure { onError(it.apiErrorMessage("Unable to load the roster")) }
            loadingRoster = false
        }
    }

    fun act(userId: String, ok: String, block: suspend () -> Unit) {
        scope.launch {
            busyUserId = userId
            runCatching { block() }
                .onSuccess { onMessage(ok); loadRoster(selectedWorkshop) }
                .onFailure { onError(it.apiErrorMessage("That change didn't go through")) }
            busyUserId = null
        }
    }

    RecordCard(title = "Workshop assignments", icon = Icons.Filled.Groups) {
        Text(
            "Pick a workshop and choose who can work in it. With nobody assigned, it's open to everyone.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        DropdownField(
            label = "Workshop",
            options = workshops.map { it.id to workshopOptionLabel(it) },
            selectedValue = selectedWorkshop,
            placeholder = "Select workshop",
            onSelect = { selectedWorkshop = it; addUserId = ""; loadRoster(it) }
        )
        if (selectedWorkshop.isBlank()) return@RecordCard

        if (loadingRoster) {
            Text("Loading the roster…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else if (roster.isEmpty()) {
            Text("Nobody assigned — open to everyone.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        val ordered = roster.sortedBy { row ->
            when (row.status) { "GRANTED" -> 0; "PENDING" -> 1; else -> 2 }
        }
        ordered.forEach { row ->
            val busy = busyUserId == row.userId
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(row.user?.name ?: row.userId, display = true, color = Body, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            row.user?.email?.takeIf { it.isNotBlank() },
                            workshopLevelLabel(row.accessLevel)
                        ).joinToString(" · "),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(workshopAccessStatusLabel(row.status), color = workshopAccessStatusColor(row.status), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                    row.requestNote?.takeIf { it.isNotBlank() }?.let { Text("Asked: “$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("Decision: “$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    DropdownField(
                        label = "Access level",
                        options = workshopLevelOptions(levels),
                        selectedValue = row.accessLevel,
                        includeNone = false,
                        enabled = !busy,
                        onSelect = { level ->
                            if (level != row.accessLevel) {
                                act(row.userId, "${row.user?.name ?: "Access"} set to ${workshopLevelLabel(level)}") {
                                    repository.updateWorkshopAccess(selectedWorkshop, row.userId, accessLevel = level)
                                }
                            }
                        }
                    )
                    ActionRow {
                        if (row.status == "GRANTED") {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    act(row.userId, "Access revoked") {
                                        repository.revokeWorkshopAccess(selectedWorkshop, row.userId)
                                    }
                                }
                            ) { Text(if (busy) "Working…" else "Revoke", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                        } else {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    act(row.userId, "Access granted") {
                                        repository.grantWorkshopAccess(selectedWorkshop, row.userId, row.accessLevel)
                                    }
                                }
                            ) { Text(if (busy) "Working…" else "Grant", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            if (row.status == "PENDING") {
                                OutlinedButton(modifier = Modifier.destructiveAction(), 
                                    enabled = !busy,
                                    onClick = {
                                        act(row.userId, "Request denied") {
                                            repository.updateWorkshopAccess(selectedWorkshop, row.userId, status = "DENIED")
                                        }
                                    }
                                ) { Text("Deny", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Text("Add a researcher", display = true, color = Body, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        val onRoster = roster.map { it.userId }.toSet()
        val addable = directory.filterNot { it.id in onRoster }
        if (addable.isEmpty()) {
            Text("Everyone already has a row here.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            DropdownField(
                label = "Researcher",
                options = addable.map { it.id to "${it.name} · ${it.email}" },
                selectedValue = addUserId,
                placeholder = "Select researcher",
                onSelect = { addUserId = it }
            )
            DropdownField(
                label = "Access level",
                options = workshopLevelOptions(levels),
                selectedValue = addLevel,
                includeNone = false,
                onSelect = { addLevel = it }
            )
            levels.firstOrNull { it.level == addLevel }?.description?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = busyUserId == null && addUserId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val target = addUserId
                    act(target, "Access granted") {
                        repository.grantWorkshopAccess(selectedWorkshop, target, addLevel)
                        addUserId = ""
                    }
                }
            ) { Text("Grant access") }
        }
    }
}

@Composable
private fun WorkshopAccessScreen(
    repository: WorkshopRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var workshops by remember { mutableStateOf<List<WorkshopDetailDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var mine by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var level by remember { mutableStateOf(DEFAULT_WORKSHOP_LEVEL) }
    var note by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    suspend fun loadMine() {
        runCatching { repository.myWorkshopAccess() }
            .onSuccess { mine = it }
            .onFailure { onError(it.apiErrorMessage("Unable to load your access")) }
    }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { workshops = repository.workshopsByOccurrence() }
            .onFailure { onError(it.apiErrorMessage("Unable to load workshops")) }
        runCatching { levels = repository.workshopAccessLevels() }
        loadMine()
        loading = false
    }

    RecordCard(title = "Request workshop access", icon = Icons.Filled.LockOpen) {
        Text(
            "Pick the workshops you need and send one request. An admin sets your level.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (loading) {
            Text("Loading workshops…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            CheckboxMultiSelectField(
                label = "Workshops",
                options = workshops.map { it.id to workshopOptionLabel(it) },
                selectedIds = selected,
                emptyMessage = unscopedEmptyLine("workshops"),
                onToggle = { id -> selected = if (id in selected) selected - id else selected + id }
            )
            DropdownField(
                label = "Access level you need",
                options = workshopLevelOptions(levels),
                selectedValue = level,
                includeNone = false,
                onSelect = { level = it }
            )
            levels.firstOrNull { it.level == level }?.description?.takeIf { it.isNotBlank() }
                ?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }
            TextInput("Why do you need access? (optional)", note, minLines = 2) { note = it }
            Button(
                enabled = !busy && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.requestWorkshopAccess(selected.toList(), level, note) }
                            .onSuccess { result ->
                                val counts = result.outcomes.groupingBy { it.outcome }.eachCount()
                                val parts = listOfNotNull(
                                    counts["CREATED"]?.let { "$it sent" },
                                    counts["RE_REQUESTED"]?.let { "$it asked again" },
                                    counts["ALREADY_PENDING"]?.let { "$it already waiting" },
                                    counts["ALREADY_GRANTED"]?.let { "$it already granted" }
                                )
                                onMessage("Workshop access: ${parts.joinToString(", ").ifBlank { "request sent" }}")
                                selected = emptySet()
                                note = ""
                                loadMine()
                            }
                            .onFailure { onError(it.apiErrorMessage("Unable to send the request")) }
                        busy = false
                    }
                }
            ) { Text(if (busy) "Sending…" else "Request access (${selected.size})") }
        }
    }

    RecordCard(title = "My workshop access") {
        Text(
            "Your access, pending requests and refusals.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (mine.isEmpty()) {
            Text("No workshop access yet.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        mine.forEach { row ->
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(assignmentWorkshopLabel(row), display = true, color = Body, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workshopAccessStatusLabel(row.status),
                            color = workshopAccessStatusColor(row.status),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("· ${workshopLevelLabel(row.accessLevel)}", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("“$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    row.decidedBy?.name?.let { Text("Decided by $it${formatIsoDate(row.decidedAt)?.let { d -> " · $d" } ?: ""}", color = Muted, style = MaterialTheme.typography.bodySmall) }
                    if (row.status == "DENIED" || row.status == "REVOKED") {
                        Text("You can ask again above.", color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkshopAccessQueueCard(
    repository: WorkshopRepository,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<WorkshopAssignmentDto>>(emptyList()) }
    var levels by remember { mutableStateOf<List<WorkshopAccessLevelDto>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<WorkshopAccessQueueFailure?>(null) }
    val chosenLevel = remember { mutableStateMapOf<String, String>() }

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { repository.workshopAccessQueue(if (showAll) "ALL" else "PENDING") }
                .onSuccess { rows = it; failure = null }
                .onFailure { error ->
                    val classified = error.workshopAccessQueueFailure()
                    failure = classified
                    onError(classified.message)
                }
            loading = false
        }
    }
    LaunchedEffect(showAll) { refresh() }
    LaunchedEffect(Unit) { runCatching { levels = repository.workshopAccessLevels() } }

    fun decide(row: WorkshopAssignmentDto, status: String) {
        scope.launch {
            busyId = row.id
            runCatching {
                repository.decideWorkshopAccess(row.id, status, chosenLevel[row.id] ?: row.accessLevel)
            }
                .onSuccess {
                    onMessage(if (status == "GRANTED") "Access granted to ${row.user?.name ?: "the researcher"}" else "Request denied")
                    refresh()
                }
                .onFailure { onError(it.apiErrorMessage("That decision didn't go through")) }
            busyId = null
        }
    }

    RecordCard(title = "Workshop access requests", icon = Icons.Filled.LockOpen) {
        Text(
            "Access requests, oldest first. Approving sets their level; it doesn't close an open workshop.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showAll, onClick = { showAll = false }, label = { Text("Pending") })
            FilterChip(selected = showAll, onClick = { showAll = true }, label = { Text("Full history") })
        }
        val notice = if (loading) null else workshopAccessQueueNotice(
            view = if (showAll) WorkshopAccessQueueView.HISTORY else WorkshopAccessQueueView.PENDING,
            failure = failure,
            rowsOnScreen = rows.size,
        )
        notice?.let { n ->
            if (n.answered) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(n.heading, display = true, color = Body, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(n.body, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.field.warningContainer, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        n.heading,
                        display = true,
                        color = MaterialTheme.field.onWarningContainer,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(n.body, color = MaterialTheme.field.onWarningContainer, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (loading) {
            Text("Loading requests…", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else {
            rows.forEach { row ->
                val busy = busyId == row.id
                val pending = row.status == "PENDING"
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(row.user?.name ?: row.userId, display = true, color = Body, fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                row.user?.email?.takeIf { it.isNotBlank() },
                                assignmentWorkshopLabel(row),
                                formatIsoDate(row.createdAt)
                            ).joinToString(" · "),
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${workshopAccessStatusLabel(row.status)} · asked for ${workshopLevelLabel(row.accessLevel)}",
                            color = workshopAccessStatusColor(row.status),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        row.requestNote?.takeIf { it.isNotBlank() }?.let { Text("“$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                        if (pending) {
                            DropdownField(
                                label = "Grant at level",
                                options = workshopLevelOptions(levels),
                                selectedValue = chosenLevel[row.id] ?: row.accessLevel,
                                includeNone = false,
                                enabled = !busy,
                                onSelect = { chosenLevel[row.id] = it }
                            )
                            ActionRow {
                                Button(
                                    enabled = !busy,
                                    onClick = { decide(row, "GRANTED") }
                                ) { Text(if (busy) "Working…" else "Approve", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                                OutlinedButton(modifier = Modifier.destructiveAction(), 
                                    enabled = !busy,
                                    onClick = { decide(row, "DENIED") }
                                ) { Text("Deny", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                            }
                        } else {
                            row.decisionNote?.takeIf { it.isNotBlank() }?.let { Text("Decision: “$it”", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            Text(
                                "Already decided — change it under Workshop assignments.",
                                color = Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

private val TASK_STATUSES = listOf("OPEN", "IN_PROGRESS", "DONE", "CANCELLED")

private fun taskStatusLabel(status: String): String = when (status) {
    "OPEN" -> "Open"
    "IN_PROGRESS" -> "In progress"
    "DONE" -> "Done"
    "CANCELLED" -> "Cancelled"
    else -> status
}

@Composable
@ReadOnlyComposable
private fun taskStatusColor(status: String): Color = when (status) {
    "DONE" -> SuccessGreen
    "IN_PROGRESS" -> Coral
    "CANCELLED" -> Muted
    else -> Body
}

private val assigneeTaskStatuses = listOf("OPEN", "IN_PROGRESS", "DONE")

private fun taskScopeSummary(task: TaskDto): String {
    val parts = mutableListOf<String>()
    if (task.recordTypeLabels.isNotEmpty()) {
        parts += task.targetCount?.let { "${task.recordTypeLabels.joinToString(", ")} (target $it)" }
            ?: task.recordTypeLabels.joinToString(", ")
    }
    if (task.sections.isNotEmpty()) {
        val codes = task.sections.joinToString(", ") { it.code }
        parts += if (task.sections.size == 1) "questionnaire section $codes" else "questionnaire sections $codes"
    }
    if (task.artisans.isNotEmpty()) {
        parts += if (task.artisans.size <= 3) {
            "for ${task.artisans.joinToString(", ") { it.name }}"
        } else {
            "for ${task.artisans.size} artisans"
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun MyTasksScreen(
    repository: WorkshopRepository,
    canAssign: Boolean,
    onOpenAssignmentBoard: (() -> Unit)? = null,
    onMessage: (String) -> Unit,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val myId = remember { repository.cachedUser()?.id }
    val appContext = LocalContext.current.applicationContext
    var cachedNote by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf("assigned") }
    LaunchedEffect(canAssign) { if (!canAssign) view = "assigned" }
    var statusFilter by remember { mutableStateOf("") }
    var tasks by remember { mutableStateOf<List<TaskDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var shownQuery by remember { mutableStateOf<Pair<String, String>?>(null) }
    val loadSeq = remember { AtomicInteger(0) }

    fun refresh() {
        val forView = view
        val forStatus = statusFilter
        val seq = loadSeq.incrementAndGet()
        scope.launch {
            loading = true
            val outcome = runCatching {
                ReadCache.read(
                    appContext,
                    "tasks:$forView:$forStatus",
                    kotlinx.serialization.builtins.ListSerializer(TaskDto.serializer()),
                ) { repository.tasks(view = forView, status = forStatus.ifBlank { null }) }
            }
            outcome.exceptionOrNull()?.let { if (it is kotlinx.coroutines.CancellationException) throw it }
            if (seq != loadSeq.get()) return@launch
            outcome
                .onSuccess { read ->
                    tasks = read.value
                    loadFailed = false
                    shownQuery = forView to forStatus
                    cachedNote = if (read.fromCache) readCacheNotice(read.cachedAt) else null
                }
                .onFailure {
                    loadFailed = true
                    onError(it.apiErrorMessage("Unable to load your tasks"))
                }
            runCatching { repository.taskCounts(view = view, statuses = TASK_STATUSES) }
                .onSuccess { counts = it }
                .onFailure { counts = emptyMap() }
            loading = false
        }
    }
    LaunchedEffect(view, statusFilter) { refresh() }

    fun update(task: TaskDto, status: String? = null, progressCount: Int? = null, ok: String) {
        scope.launch {
            busyId = task.id
            runCatching { repository.updateTaskProgress(task.id, status = status, progressCount = progressCount) }
                .onSuccess { updated ->
                    tasks = tasks.map { if (it.id == updated.id) updated else it }
                    onMessage(ok)
                }
                .onFailure { onError(it.apiErrorMessage("Unable to update the task")) }
            busyId = null
        }
    }

    RecordCard(title = "Tasks", icon = Icons.AutoMirrored.Filled.Assignment) {
        Text(
            "Your assigned work and progress. Update the status as you go. Needs a connection.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (canAssign) {
            Text(
                "Assign work from the assignment board.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            onOpenAssignmentBoard?.let { open ->
                OutlinedButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Assignment board")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = view == "assigned", onClick = { view = "assigned" }, label = { Text("Assigned to me") })
                FilterChip(selected = view == "created", onClick = { view = "created" }, label = { Text("Assigned by me") })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val knownTotal = counts.takeIf { it.isNotEmpty() }?.values?.sum()
            FilterChip(
                selected = statusFilter.isBlank(),
                onClick = { statusFilter = "" },
                label = { Text(if (knownTotal != null) "All ($knownTotal)" else "All") }
            )
            TASK_STATUSES.forEach { value ->
                val count = counts[value]
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { statusFilter = if (statusFilter == value) "" else value },
                    label = { Text(if (count != null) "${taskStatusLabel(value)} ($count)" else taskStatusLabel(value)) }
                )
            }
        }
        OutlinedButton(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (loading) "Loading tasks…" else "Refresh")
        }
        if (loadFailed && !loading) {
            val staleForAnotherFilter = tasks.isNotEmpty() && shownQuery != null && shownQuery != (view to statusFilter)
            Text(
                when {
                    tasks.isEmpty() ->
                        "Couldn't load tasks. Try again when online."
                    staleForAnotherFilter ->
                        "Offline — the new filter wasn't applied. Showing the previous list."
                    else ->
                        "Offline — this list may be out of date, and changes won't send until you're online."
                },
                color = Coral,
                style = MaterialTheme.typography.bodySmall
            )
        }
        cachedNote?.takeIf { !loading }?.let { CachedCopyNote(it) }
        when {
            loading -> Text("Loading tasks…", color = Muted, style = MaterialTheme.typography.bodySmall)
            tasks.isEmpty() -> if (!loadFailed) {
                Text(
                    if (view == "created") "You have not assigned any work yet."
                    else "Nothing is assigned to you right now.",
                    color = Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            else -> tasks.forEach { task ->
                TaskCard(
                    task = task,
                    editable = task.assigneeId != null && task.assigneeId == myId,
                    busy = busyId == task.id,
                    onStatus = { status -> update(task, status = status, ok = "Marked ${taskStatusLabel(status).lowercase()}") },
                    onProgress = { count -> update(task, progressCount = count, ok = "Progress reported") }
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskDto,
    editable: Boolean,
    busy: Boolean,
    onStatus: (String) -> Unit,
    onProgress: (Int) -> Unit
) {
    var reported by remember(task.id, task.progressCount) { mutableStateOf(task.progressCount.toString()) }
    val scopeLine = taskScopeSummary(task)

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(task.title.ifBlank { "Field task" }, display = true, color = Body, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(taskStatusLabel(task.status), color = taskStatusColor(task.status), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (task.isOverdue) Text("· Overdue", color = FailureRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                listOfNotNull(
                    task.workshopTitle?.takeIf { it.isNotBlank() },
                    formatIsoDate(task.dueAt)?.let { "due $it" },
                    if (!editable) task.assignee?.name?.takeIf { it.isNotBlank() }?.let { "for $it" } else null
                ).joinToString(" · ").ifBlank { "No workshop or due date" },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            if (scopeLine.isNotBlank()) Text(scopeLine, color = Body, style = MaterialTheme.typography.bodySmall)
            task.description?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, style = MaterialTheme.typography.bodySmall) }

            val target = task.targetCount
            val derived = task.derivedCount
            Text(
                buildString {
                    append("Reported ${task.progressCount}")
                    if (target != null) append(" of $target")
                    if (derived != null) {
                        append(" · records found $derived")
                        task.derivedTarget?.let { append(" of $it") }
                    }
                },
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            task.percentComplete?.let { percent ->
                FieldLinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }

            if (!editable) return@Column
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                assigneeTaskStatuses.forEach { value ->
                    val current = task.status == value
                    val label: @Composable () -> Unit = {
                        Text(taskStatusLabel(value), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    if (current) {
                        Button(
                            onClick = {},
                            modifier = Modifier.weight(1f).semantics { selected = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                        ) { label() }
                    } else {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { onStatus(value) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                        ) { label() }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = reported,
                    onValueChange = { raw -> reported = raw.filter { it.isDigit() }.take(6) },
                    label = { Text(if (target != null) "Done so far (of $target)" else "Done so far") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !busy && reported.toIntOrNull() != null && reported.toIntOrNull() != task.progressCount,
                    onClick = { reported.toIntOrNull()?.let(onProgress) }
                ) { Text(if (busy) "Saving…" else "Report") }
            }
        }
    }
}

private fun tierLabel(tier: String): String = when (tier) {
    "DOWNLOAD" -> "Download"
    "COMMENT" -> "Comment"
    "EDIT" -> "Edit"
    else -> tier
}

@Composable
private fun GrantToggleRow(label: String, granted: Boolean, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Body, style = MaterialTheme.typography.bodyMedium)
            Text(if (granted) "Granted" else "Not granted", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(enabled = enabled, onClick = { onToggle(!granted) }) {
            Text(if (granted) "Revoke" else "Grant")
        }
    }
}

@Composable
private fun RecordCard(title: String, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = Canvas),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                    Text(title, display = true, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                }
            } else {
                Text(title, display = true, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            }
            content()
        }
    }
}

@Composable
private fun WorkshopMappingCard(
    repository: WorkshopRepository,
    onOpenRecord: ((EntryMode, String) -> Unit)?,
    onError: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var plan by remember { mutableStateOf<WorkshopMappingPlanDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var appliedTotal by remember { mutableStateOf<Int?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        runCatching { repository.unmappedRecords() }
            .onSuccess { plan = it; loading = false }
            .onFailure {
                if (it is kotlinx.coroutines.CancellationException) throw it
                onError(it.apiErrorMessage("The unmapped records could not be read."))
                loading = false
            }
    }

    RecordCard(title = "Records not filed under a workshop", icon = Icons.Filled.Link) {
        Text(
            "Records without a workshop are hidden from every workshop scope. This files each one using its own evidence.",
            color = MaterialTheme.field.muted,
            style = MaterialTheme.typography.bodySmall
        )

        val current = plan
        when {
            loading && current == null ->
                Text("Reading every record that names no workshop…", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)

            current == null ->
                Text(
                    "Couldn't load the report. Tap Re-check.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )

            current.totals.unassigned == 0 ->
                Text(
                    "Every record is filed under a workshop.",
                    color = MaterialTheme.field.body,
                    style = MaterialTheme.typography.bodySmall
                )

            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    MappingStatTile("Not filed", current.totals.unassigned, Modifier.weight(1f))
                    MappingStatTile("Can be filed", current.totals.resolved, Modifier.weight(1f))
                    MappingStatTile("Need a person", current.totals.unresolved, Modifier.weight(1f))
                }

                current.buckets.filter { it.unassigned > 0 }.forEach { bucket ->
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
                            .padding(10.dp)
                    ) {
                        val noun = if (bucket.unassigned == 1) bucket.singular else bucket.plural
                        Text(
                            "${bucket.unassigned} $noun" +
                                if (bucket.applied != null) {
                                    " — ${bucket.applied} filed just now"
                                } else {
                                    " — ${bucket.resolved} can be filed" +
                                        if (bucket.unresolved > 0) ", ${bucket.unresolved} need a person" else ""
                                },
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (bucket.byWorkshop.isNotEmpty()) {
                            Text(
                                "Filed under " + bucket.byWorkshop.joinToString(", ") { "${it.title} (${it.count})" } + ".",
                                color = MaterialTheme.field.muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        bucket.byRung.forEach { entry ->
                            Text(
                                "${entry.count} because ${entry.copy}.",
                                color = MaterialTheme.field.body,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val stuck = bucket.rows.filter { it.workshopId == null }
                        if (stuck.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.field.warningContainer, MaterialTheme.shapes.small)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    "Left alone — choose its workshop by hand",
                                    color = MaterialTheme.field.onWarningContainer,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                stuck.forEach { row ->
                                    DwUnfiledRecordCard(
                                        repository = repository,
                                        bucket = bucket.bucket,
                                        noun = bucket.singular,
                                        row = row,
                                        workshops = current.workshops,
                                        onOpen = onOpenRecord,
                                        onFiled = { reloadKey++ },
                                        onError = onError,
                                    )
                                }
                                if (stuck.size < bucket.unresolved) {
                                    Text(
                                        "${bucket.unresolved - stuck.size} more not listed. Re-check after filing these.",
                                        color = MaterialTheme.field.onWarningContainer,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }

                if (current.totals.resolved > 0) {
                    Button(
                        onClick = {
                            scope.launch {
                                applying = true
                                runCatching { repository.mapUnmappedRecords() }
                                    .onSuccess {
                                        appliedTotal = it.totals.applied ?: 0
                                        reloadKey += 1
                                    }
                                    .onFailure { failure ->
                                        if (failure is kotlinx.coroutines.CancellationException) throw failure
                                        onError(failure.apiErrorMessage("The records could not be mapped."))
                                    }
                                applying = false
                            }
                        },
                        enabled = !applying,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (applying) {
                                "Filing…"
                            } else {
                                "File ${current.totals.resolved} record" +
                                    (if (current.totals.resolved == 1) "" else "s") + " under its workshop"
                            }
                        )
                    }
                    Text(
                        "Only fills empty workshops; nothing filed is changed.",
                        color = MaterialTheme.field.muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        appliedTotal?.let { total ->
            Text(
                if (total == 0) {
                    "Nothing left to file."
                } else {
                    "$total record${if (total == 1) "" else "s"} filed."
                },
                color = MaterialTheme.field.body,
                style = MaterialTheme.typography.bodySmall
            )
        }

        plan?.let { current ->
            if (current.workshops.isEmpty()) {
                Text(
                    "No workshop has dates, so nothing can be filed by date. Add start and end dates.",
                    color = MaterialTheme.field.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                current.workshops.forEach { window ->
                    Text(
                        "${window.title} — read as ${formatIsoDay(window.start)} to " +
                            formatIsoDay(window.end, daysBefore = 1),
                        color = MaterialTheme.field.placeholder,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        ActionRow {
            OutlinedButton(onClick = { reloadKey += 1 }, enabled = !loading && !applying) {
                Text("Re-check")
            }
        }
    }
}

@Composable
private fun DwUnfiledRecordCard(
    repository: WorkshopRepository,
    bucket: String,
    noun: String,
    row: WorkshopMappingRowDto,
    workshops: List<WorkshopMappingWindowDto>,
    onOpen: ((EntryMode, String) -> Unit)?,
    onFiled: () -> Unit,
    onError: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var chosenWorkshop by remember { mutableStateOf("") }
    var confirmingDelete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val mode = remember(bucket) { unfiledRecordEntryMode(bucket) }

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .clickable(
                onClickLabel = if (expanded) "Hide the actions for ${row.title}" else "Show the actions for ${row.title}",
                role = Role.Button,
            ) { expanded = !expanded }
            .padding(8.dp)
    ) {
        Text(
            row.title.ifBlank { "Untitled $noun" },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
        val why = listOfNotNull(
            row.reasonCopy?.takeIf { it.isNotBlank() },
            row.candidateTitles.takeIf { it.isNotEmpty() }?.joinToString(" or ")
        ).joinToString(" · ")
        if (why.isNotBlank()) {
            Text(why, color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall, lineHeight = 15.sp)
        }

        if (!expanded) {
            Text("Tap to file it, open it, or discard it.", color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
            return@Column
        }

        DropdownField(
            label = "File it under",
            options = workshops.map { it.id to it.title },
            selectedValue = chosenWorkshop,
            placeholder = "Choose a workshop",
            includeNone = false,
            enabled = !busy
        ) { chosenWorkshop = it }
        ActionRow {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { repository.fileOneUnmappedRecord(bucket, row.id, chosenWorkshop) }
                            .onFailure { error ->
                                if (error is CancellationException) throw error
                                onError(error.apiErrorMessage("This $noun could not be filed."))
                            }
                        busy = false
                        onFiled()
                    }
                },
                enabled = !busy && chosenWorkshop.isNotBlank(),
            ) { Text("File it", style = MaterialTheme.typography.labelMedium) }

            if (onOpen != null && mode != null) {
                OutlinedButton(
                    onClick = { onOpen(mode, row.id) },
                    enabled = !busy,
                ) { Text("Open", style = MaterialTheme.typography.labelMedium) }
            }
        }

        if (!confirmingDelete) {
            ActionRow {
                TextButton(modifier = Modifier.destructiveAction(), onClick = { confirmingDelete = true }, enabled = !busy) {
                    Text("Discard permanently", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        } else {
            Text(
                "Permanently delete “${row.title}”? Attached media is kept but unlinked.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                lineHeight = 15.sp
            )
            ActionRow {
                OutlinedButton(onClick = { confirmingDelete = false }, enabled = !busy) {
                    Text("Keep it", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(modifier = Modifier.destructiveAction(), 
                    onClick = {
                        scope.launch {
                            busy = true
                            runCatching { repository.discardUnmappedRecord(bucket, row.id) }
                                .onSuccess { answer ->
                                    if (answer.mediaKept > 0) {
                                        onError(
                                            "${answer.noun.ifBlank { noun }} deleted. ${plural(answer.mediaKept, "file")} kept in Miscellaneous Media."
                                        )
                                    }
                                }
                                .onFailure { error ->
                                    if (error is CancellationException) throw error
                                    onError(error.apiErrorMessage("This $noun could not be deleted."))
                                }
                            busy = false
                            confirmingDelete = false
                            onFiled()
                        }
                    },
                    enabled = !busy
                ) {
                    Text("Delete it permanently", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun unfiledRecordEntryMode(bucket: String): EntryMode? = when (bucket) {
    "artisans" -> EntryMode.ARTISAN
    "products" -> EntryMode.PRODUCT
    "tools" -> EntryMode.TOOL
    "processes" -> EntryMode.PROCESS
    "interviews" -> EntryMode.QUESTIONNAIRE
    else -> null
}

@Composable
private fun MappingStatTile(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .background(MaterialTheme.field.surface50, MaterialTheme.shapes.small)
            .border(1.dp, MaterialTheme.field.hairline, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label.uppercase(Locale.ROOT), color = MaterialTheme.field.muted, style = MaterialTheme.typography.bodySmall)
        Text(
            value.toString(),
            display = true,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

private val offlineFormJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private data class OnlineAttempt<T>(val body: T, val key: String)

private suspend fun trySaveOffline(
    repository: WorkshopRepository,
    context: Context,
    isEdit: Boolean,
    type: String,
    payloadJson: String,
    label: String,
    media: MediaCaptureState,
    recordName: String?,
    caption: String?,
    editingId: String? = null,
    unfiled: Map<String, String> = emptyMap(),
    force: Boolean = false,
    idempotencyKey: String? = null,
    base: JsonObject? = null,
): OfflineQueueResult? {
    if (!force && repository.isOnline(context)) return null
    if (isEdit && editingId.isNullOrBlank()) return null
    return repository.queueOffline(
        context, type, payloadJson, label, media.uris, recordName, caption,
        purposes = media.purposes,
        targetId = if (isEdit) editingId else null,
        unfiled = unfiled,
        idempotencyKey = if (isEdit) null else idempotencyKey,
        correctionBase = if (isEdit) correctionBaseFor(payloadJson, base) else null,
    )
}

internal fun correctionBaseFor(payloadJson: String, base: JsonObject?): com.designprototype.workshop.data.CorrectionBase? {
    if (base == null) return null
    val request = runCatching { offlineFormJson.parseToJsonElement(payloadJson) as JsonObject }.getOrNull() ?: return null
    return com.designprototype.workshop.data.CorrectionBase(
        changedKeys = com.designprototype.workshop.data.correctionChangedKeys(request, base),
        baseUpdatedAt = (base["updatedAt"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
    )
}

internal fun <T> recordBaseJson(serializer: kotlinx.serialization.KSerializer<T>, record: T?): JsonObject? =
    record?.let { runCatching { offlineFormJson.encodeToJsonElement(serializer, it) as JsonObject }.getOrNull() }

private enum class SaveState { IDLE, SAVING, SAVED }

@Composable
private fun SaveButton(
    state: SaveState,
    idleLabel: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(onClick = onClick, enabled = enabled && state == SaveState.IDLE, modifier = modifier) {
        when (state) {
            SaveState.SAVING -> {
                FieldCircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current
                )
                Spacer(Modifier.width(8.dp))
                Text("Saving…")
            }
            SaveState.SAVED -> {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Saved")
            }
            SaveState.IDLE -> Text(idleLabel)
        }
    }
}

private const val SAVED_CONFIRM_MS = 750L

@Composable
private fun TitleCaseHint(value: String) {
    val normalised = titleCasePreview(value) ?: return
    Text("Will be saved as “$normalised”", color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TextInput(
    label: String,
    value: String,
    minLines: Int = 1,
    titleCased: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    dictate: Boolean = true,
    rich: Boolean = false,
    resetKey: Any? = null,
    onValueChange: (String) -> Unit
) {
    RecordProseField(
        capitalization = if (titleCased) KeyboardCapitalization.Words else null,
        label = label,
        value = value,
        onValueChange = onValueChange,
        minLines = minLines,
        keyboardType = keyboardType,
        dictate = dictate,
        rich = rich,
        resetKey = resetKey,
        below = { if (titleCased) TitleCaseHint(value) },
    )
}

private const val NOTE_SEPARATOR = "\n\n"

private fun splitNotes(value: String?): List<String> =
    value?.split(Regex("\\n\\s*\\n"))?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

private fun joinNotes(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(NOTE_SEPARATOR)

@Composable
private fun MultiNoteInput(
    label: String = "Notes",
    value: String,
    resetKey: Any? = null,
    dictate: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    var rows by remember(resetKey) { mutableStateOf(splitNotes(value).ifEmpty { listOf("") }) }
    fun emit(updated: List<String>) {
        val next = updated.ifEmpty { listOf("") }
        rows = next
        onValueChange(joinNotes(next))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
        rows.forEachIndexed { index, note ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RecordProseField(
                    label = if (rows.size > 1) "Note ${index + 1}" else "Note",
                    value = note,
                    onValueChange = { v -> emit(rows.toMutableList().also { it[index] = v }) },
                    minLines = 2,
                    dictate = dictate,
                    resetKey = resetKey to index,
                    modifier = Modifier.weight(1f),
                )
                if (rows.size > 1) {
                    IconButton(onClick = { emit(rows.toMutableList().also { it.removeAt(index) }) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove note", tint = Muted)
                    }
                }
            }
        }
        ActionRow {
            TextButton(onClick = { emit(rows + "") }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add note")
            }
        }
    }
}

@Composable
private fun NumberedListDisplay(label: String, value: String?) {
    val items = value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    if (items.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        items.forEachIndexed { index, item ->
            Text("${index + 1}. $item", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RequiredInput(
    label: String,
    value: String,
    error: String?,
    focusRequester: FocusRequester,
    minLines: Int = 1,
    titleCased: Boolean = false,
    dictate: Boolean = true,
    onValueChange: (String) -> Unit
) {
    RecordProseField(
        capitalization = if (titleCased) KeyboardCapitalization.Words else null,
        label = "$label *",
        value = value,
        onValueChange = onValueChange,
        minLines = minLines,
        dictate = dictate,
        errorText = error,
        focusRequester = focusRequester,
        below = { if (titleCased) TitleCaseHint(value) },
    )
}

private class RequiredCheck(
    val isBlank: Boolean,
    val setError: (String?) -> Unit,
    val focus: FocusRequester
)

private fun validateRequired(checks: List<RequiredCheck>): Boolean {
    checks.forEach { it.setError(null) }
    val firstMissing = checks.firstOrNull { it.isBlank } ?: return true
    firstMissing.setError("This field cannot be empty")
    runCatching { firstMissing.focus.requestFocus() }
    return false
}

private fun requiredAndroidPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
    }
    return permissions.toTypedArray()
}

private fun createAppFile(context: Context, prefix: String, suffix: String): File {
    val directory = com.designprototype.workshop.data.FormDraftStore.capturesDir(context)
    return File.createTempFile(prefix, suffix, directory)
}

private fun createAppFileUri(context: Context, prefix: String, suffix: String): Uri {
    return uriForFile(context, createAppFile(context, prefix, suffix))
}

private fun uriForFile(context: Context, file: File): Uri {
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun saveMediaToDevice(context: Context, url: String?, filename: String, mimeType: String?) {
    if (url.isNullOrBlank()) {
        Toast.makeText(context, "This file has no downloadable URL.", Toast.LENGTH_LONG).show()
        return
    }
    val safeName = filename.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "media" }
    runCatching {
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safeName)
            .setDescription("Saving to Downloads")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.enqueue(request)
        Toast.makeText(context, "Saving \"$safeName\" to Downloads…", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, it.userMessage("Couldn't save the download."), Toast.LENGTH_LONG).show()
    }
}

private fun saveTranscriptToDownloads(
    context: Context,
    repository: WorkshopRepository,
    scope: CoroutineScope,
    fileName: String,
    text: String,
) {
    if (text.isBlank()) return
    Toast.makeText(context, "Saving \"$fileName\" to Downloads…", Toast.LENGTH_SHORT).show()
    scope.launch {
        val outcome = withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, fileName)
            runCatching {
                try {
                    tmp.writeText(text, Charsets.UTF_8)
                    repository.persistFileToDownloads(context, tmp, fileName, TRANSCRIPT_DOCUMENT_MIME)
                } finally {
                    tmp.delete()
                }
            }
        }
        outcome
            .onSuccess { Toast.makeText(context, "Saved to $it", Toast.LENGTH_SHORT).show() }
            .onFailure {
                Toast.makeText(
                    context,
                    it.userMessage("Couldn't save the file."),
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}

private fun openUri(context: Context, uri: Uri, mimeType: String?) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (mimeType.isNullOrBlank()) {
            data = uri
        } else {
            setDataAndType(uri, mimeType)
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Open media"))
    }
}

private fun questionnaireClipBaseName(
    sectionCode: String?,
    questionNumber: String?,
    interviewName: String?,
): String {
    val sequence = if (questionNumber == null || questionNumber == "SEC") {
        com.designprototype.workshop.data.Naming.sectionSequence(sectionCode)
    } else {
        com.designprototype.workshop.data.Naming.questionSequence(sectionCode, questionNumber)
    }
    return com.designprototype.workshop.data.Naming.recordingFilename(
        entity = "Interview",
        ext = "",
        capturedAt = java.time.Instant.now(),
        subject = interviewName,
        sequence = sequence,
    )
}

private fun createAudioRecorder(context: Context, file: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    return recorder.apply {
        runCatching { setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
            .onFailure { setAudioSource(MediaRecorder.AudioSource.MIC) }
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(44_100)
        setAudioEncodingBitRate(96_000)
        setOutputFile(file.absolutePath)
        prepare()
    }
}

private fun readLastKnownLocation(context: Context): LocationRequest? {
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null

    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
    val location = providers.mapNotNull { provider ->
        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }.maxByOrNull { it.time }
    return location?.let {
        LocationRequest(
            latitude = it.latitude,
            longitude = it.longitude,
            altitude = it.altitude.takeIf { _ -> it.hasAltitude() },
            accuracy = it.accuracy.toDouble().takeIf { _ -> it.hasAccuracy() }
        )
    }
}

private fun moveIds(ids: List<String>, index: Int, direction: Int): List<String> {
    val nextIndex = index + direction
    if (nextIndex !in ids.indices) return ids
    return ids.toMutableList().also {
        val item = it[index]
        it[index] = it[nextIndex]
        it[nextIndex] = item
    }
}

private fun String.blankToNull(): String? = trim().takeIf { it.isNotEmpty() }

private object ColorCompat {
    val darkElevated = androidx.compose.ui.graphics.Color(0xFF252320)
}
