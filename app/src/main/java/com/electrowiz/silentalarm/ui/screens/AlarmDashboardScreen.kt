package com.electrowiz.silentalarm.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.ui.components.GitHubRepoCard
import com.electrowiz.silentalarm.ui.components.LanguageSettingsCard
import com.electrowiz.silentalarm.ui.components.SearchableSelectSheet
import com.electrowiz.silentalarm.ui.components.SelectOption
import com.electrowiz.silentalarm.ui.viewmodel.AlarmViewModel
import com.electrowiz.silentalarm.util.TimezoneFormatter
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.launch

/**
 * Main dashboard: test/stop → alarm list → settings → process keeping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDashboardScreen(
    viewModel: AlarmViewModel,
    onPickRingtone: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryExemption: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val earphoneVolume by viewModel.earphoneVolume.collectAsStateWithLifecycle()
    val speakerVolume by viewModel.speakerVolume.collectAsStateWithLifecycle()
    val noEarphoneAction by viewModel.noEarphoneAction.collectAsStateWithLifecycle()
    val globalRingtoneUri by viewModel.globalRingtoneUri.collectAsStateWithLifecycle()
    val timeoutSeconds by viewModel.timeoutSeconds.collectAsStateWithLifecycle()
    val timeoutAction by viewModel.timeoutAction.collectAsStateWithLifecycle()
    val showTimePicker by viewModel.showTimePicker.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var deletingAlarmId by remember { mutableStateOf<String?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var emptyStateHiding by remember { mutableStateOf(false) }
    val cultureQuote = remember { timeQuotes.random() }
    val listState = rememberLazyListState()
    val quoteVisible by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            !listState.canScrollForward && info.visibleItemsInfo.isNotEmpty()
        }
    }
    val quoteAlpha by animateFloatAsState(
        targetValue = if (quoteVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "quoteAlpha"
    )

    val visibleAlarms = remember(alarms, searchActive, searchQuery, viewModel) {
        if (searchActive && searchQuery.isNotBlank()) {
            alarms.filter { alarm ->
                alarm.label.contains(searchQuery, ignoreCase = true) ||
                    viewModel.formatAlarmTime(alarm).contains(searchQuery) ||
                    viewModel.formatAlarmLocalTime(alarm).contains(searchQuery)
            }
        } else {
            alarms
        }
    }

    val keepAliveEnabled by viewModel.keepAliveEnabled.collectAsStateWithLifecycle()
    val privilegedEnabled by viewModel.privilegedEnabled.collectAsStateWithLifecycle()
    val shellReady by viewModel.shellReady.collectAsStateWithLifecycle()
    val shizukuPermissionNeeded by viewModel.shizukuPermissionNeeded.collectAsStateWithLifecycle()
    val exactAlarmAllowed by viewModel.exactAlarmAllowed.collectAsStateWithLifecycle()
    val batteryOptimizationIgnored by viewModel.batteryOptimizationIgnored.collectAsStateWithLifecycle()
    val notificationsAllowed by viewModel.notificationsAllowed.collectAsStateWithLifecycle()
    val alarmActive by viewModel.alarmActive.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // First-launch entrance: the whole dashboard fades in and rises
    // slightly; alarm cards additionally pop in individually below.
    val loadAlpha = remember { Animatable(0f) }
    val loadOffset = remember { Animatable(0f) }
    val density = LocalDensity.current.density
    LaunchedEffect(Unit) {
        launch { loadAlpha.animateTo(1f, tween(durationMillis = 450)) }
        launch {
            loadOffset.animateTo(1f, tween(durationMillis = 450, easing = FastOutSlowInEasing))
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { event ->
            val result = if (event.actionLabel != null) {
                snackbarHostState.showSnackbar(
                    message = event.message,
                    actionLabel = event.actionLabel,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long
                )
            } else {
                snackbarHostState.showSnackbar(event.message)
            }
            if (result == SnackbarResult.ActionPerformed) {
                event.onAction()
            }
            viewModel.clearSnackbar()
        }
    }

    val showEmptyState = alarms.isEmpty() ||
        (searchActive && searchQuery.isNotBlank() && visibleAlarms.isEmpty())

    LaunchedEffect(showEmptyState) {
        if (!showEmptyState) {
            emptyStateHiding = true
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            val source = remember { MutableInteractionSource() }
            val scale = rememberPressScale(source, pressedScale = 0.94f)
            FloatingActionButton(
                onClick = { viewModel.showAddTimePicker() },
                interactionSource = source,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.add_alarm)
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .graphicsLayer {
                        alpha = loadAlpha.value
                        translationY = (1f - loadOffset.value) * 16f * density
                    },
                contentPadding = PaddingValues(bottom = 84.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            item(key = "header") {
                DashboardHeader(
                    searchActive = searchActive,
                    onToggleSearch = { searchActive = !searchActive },
                    modifier = itemAnim()
                )
            }

            item(key = "buttons") {
                TestStopButtons(
                    alarmActive = alarmActive,
                    onTest = { viewModel.testAlarm() },
                    onStop = { viewModel.stopAlarm() },
                    modifier = itemAnim()
                )
            }

            // Conditional items: a hidden zero-height item would still consume
            // a spacedBy() slot on both sides, making the surrounding gaps
            // look twice as large. Add/remove them with the visibility state.
            if (searchActive) {
                item(key = "search-field") {
                    DashboardSearchField(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        modifier = itemAnim()
                    )
                }
            }

            if (showEmptyState || emptyStateHiding) {
                item(key = "empty-state") {
                    EmptyAlarmState(
                        message = if (searchActive && searchQuery.isNotBlank()) {
                            stringResource(R.string.no_alarms_found)
                        } else {
                            stringResource(R.string.no_alarms)
                        },
                        hiding = emptyStateHiding,
                        onHidingFinished = { emptyStateHiding = false },
                        modifier = itemAnim()
                    )
                }
            }

            itemsIndexed(visibleAlarms, key = { _, it -> it.id }) { _, alarm ->
                AlarmListItem(
                    alarm = alarm,
                    deletingAlarmId = deletingAlarmId,
                    onDeleteRequest = { pendingDeleteId = alarm.id },
                    onDelete = { viewModel.deleteAlarm(it) },
                    onDeleteAnimationFinished = { deletingAlarmId = null },
                    onToggle = { viewModel.toggleAlarm(alarm.id, it) },
                    onEditTime = { viewModel.showEditTimePicker(alarm.id) },
                    onToggleDay = { day ->
                        val newDays = if (day in alarm.daysOfWeek)
                            alarm.daysOfWeek - day else alarm.daysOfWeek + day
                        viewModel.updateAlarm(alarm.copy(daysOfWeek = newDays))
                    },
                    formatTime = { viewModel.formatAlarmTime(alarm) },
                    formatSchedule = { viewModel.formatSchedule(alarm) },
                    timezoneText = viewModel.timezoneLabelForAlarm(alarm),
                    localTimeText = viewModel.localTimeCaption(alarm),
                    modifier = itemAnim()
                )
            }

            item(key = "volume-settings") {
                VolumeSettingsCard(
                    earphoneVolume = earphoneVolume,
                    speakerVolume = speakerVolume,
                    onEarphoneVolumeChange = { viewModel.setEarphoneVolume(it) },
                    onSpeakerVolumeChange = { viewModel.setSpeakerVolume(it) },
                    modifier = itemAnim()
                )
            }

            item(key = "alarm-timeout") {
                AlarmTimeoutCard(
                    timeoutSeconds = timeoutSeconds,
                    timeoutAction = timeoutAction,
                    onSecondsChange = { viewModel.setTimeoutSeconds((it / 10) * 10) },
                    onActionChange = { viewModel.setTimeoutAction(it) },
                    modifier = itemAnim()
                )
            }

            item(key = "no-earphone") {
                NoEarphoneCard(
                    noEarphoneAction = noEarphoneAction,
                    onActionChange = { viewModel.setNoEarphoneAction(it) },
                    modifier = itemAnim()
                )
            }

            item(key = "ringtone") {
                RingtoneCard(
                    globalRingtoneUri = globalRingtoneUri,
                    onPickRingtone = onPickRingtone,
                    modifier = itemAnim()
                )
            }

            item(key = "process-keeping") {
                ProcessKeepingCard(
                    notificationsAllowed = notificationsAllowed,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    exactAlarmAllowed = exactAlarmAllowed,
                    batteryOptimizationIgnored = batteryOptimizationIgnored,
                    onRequestBatteryExemption = onRequestBatteryExemption,
                    keepAliveEnabled = keepAliveEnabled,
                    onKeepAliveChange = viewModel::setKeepAliveEnabled,
                    privilegedEnabled = privilegedEnabled,
                    onPrivilegedChange = viewModel::setPrivilegedEnabled,
                    shellReady = shellReady,
                    shizukuPermissionNeeded = shizukuPermissionNeeded,
                    onRequestPrivilegedPermission = viewModel::requestPrivilegedPermission,
                    modifier = itemAnim()
                )
            }

            item(key = "language") { LanguageSettingsCard(modifier = itemAnim()) }

            item(key = "github") { GitHubRepoCard(modifier = itemAnim()) }

            }

            Text(
                text = cultureQuote,
                style = MaterialTheme.typography.bodySmall.copy(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            MaterialTheme.colorScheme.primary
                        )
                    )
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, end = 88.dp, bottom = 12.dp)
                    .alpha(quoteAlpha)
            )
        }
    }

    // ── Time Picker Dialog ───────────────────────────────────────────────
    if (showTimePicker) {
        val editing = viewModel.editingAlarm()
        // New alarms default to the next full hour. Existing alarms are shown
        // as their absolute trigger time converted to the current timezone.
        val editingTime = editing?.let { viewModel.alarmPickerHourMinute(it) }
        var label by remember(editing?.id) { mutableStateOf(editing?.label.orEmpty()) }
        var timeZoneId by remember(editing?.id) {
            mutableStateOf(
                editing?.timeZoneId?.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id
            )
        }
        val nextHour = remember { (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) + 1) % 24 }
        val pickerState = rememberTimePickerState(
            initialHour = editingTime?.first ?: nextHour,
            initialMinute = editingTime?.second ?: 0,
            is24Hour = true
        )

        AlertDialog(
            onDismissRequest = { viewModel.hideTimePicker() },
            title = {
                Text(
                    if (editing != null) stringResource(R.string.edit_alarm)
                    else stringResource(R.string.new_alarm)
                )
            },
            text = {
                Column {
                    TimePicker(state = pickerState)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.alarm_label)) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TimezoneSelector(selectedId = timeZoneId, onSelect = { timeZoneId = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onTimeSelected(
                        pickerState.hour,
                        pickerState.minute,
                        label.trim(),
                        timeZoneId
                    )
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideTimePicker() }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    pendingDeleteId?.let { alarmId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.delete_alarm)) },
            text = { Text(stringResource(R.string.delete_alarm_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteId = null
                        deletingAlarmId = alarmId
                    }
                ) {
                    Text(stringResource(R.string.delete_alarm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Timezone picker row shown inside the add/edit alarm dialog. The selected
 * zone is stamped onto the alarm at save time; a new alarm defaults to the
 * system timezone.
 */
@Composable
private fun TimezoneSelector(selectedId: String, onSelect: (String) -> Unit) {
    var showSheet by remember { mutableStateOf(false) }
    val zones = remember(showSheet) {
        if (showSheet) {
            TimeZone.getAvailableIDs()
                .map { TimeZone.getTimeZone(it) }
                .sortedWith(compareBy({ it.rawOffset }, { it.id }))
        } else {
            emptyList()
        }
    }
    val options = remember(zones) {
        zones.map { zone ->
            SelectOption(id = zone.id, label = zone.id, subtitle = TimezoneFormatter.offsetLabel(zone))
        }
    }

    OutlinedButton(
        onClick = { showSheet = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = timezoneDisplayLabel(selectedId),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }

    if (showSheet) {
        SearchableSelectSheet(
            title = stringResource(R.string.timezone_title),
            searchPlaceholder = stringResource(R.string.timezone_search),
            noResultsText = stringResource(R.string.timezone_no_results),
            items = options,
            selectedId = selectedId,
            onSelect = { id ->
                showSheet = false
                onSelect(id)
            },
            onDismiss = { showSheet = false }
        )
    }
}

private fun timezoneDisplayLabel(id: String): String {
    val zone = TimeZone.getTimeZone(id.takeIf { it.isNotBlank() } ?: TimeZone.getDefault().id)
    return "${zone.id} (${TimezoneFormatter.offsetLabel(zone)})"
}
