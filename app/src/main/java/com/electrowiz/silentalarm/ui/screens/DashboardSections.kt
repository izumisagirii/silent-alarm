package com.electrowiz.silentalarm.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.HeadsetOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.data.AlarmItem
import com.electrowiz.silentalarm.data.NoEarphoneAction
import com.electrowiz.silentalarm.data.TimeoutAction
import com.electrowiz.silentalarm.ui.components.SettingsCardHeader
import com.electrowiz.silentalarm.ui.components.VolumeSlider
import kotlinx.coroutines.flow.first

@Composable
internal fun DashboardHeader(
    searchActive: Boolean,
    onToggleSearch: () -> Unit,
    nextAlarmText: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                stringResource(R.string.dashboard_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (nextAlarmText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    nextAlarmText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Surface(
            onClick = onToggleSearch,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shadowElevation = 2.dp,
            modifier = Modifier.size(40.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_alarms)
                )
            }
        }
    }
}

@Composable
internal fun DashboardSearchField(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = if (searchQuery.isNotEmpty()) {
            {
                IconButton(onClick = { onSearchQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear_search))
                }
            }
        } else {
            null
        },
        placeholder = { Text(stringResource(R.string.search_alarms)) }
    )
}

@Composable
internal fun TestStopButtons(
    alarmActive: Boolean,
    onTest: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val testSource = remember { MutableInteractionSource() }
    val stopSource = remember { MutableInteractionSource() }
    val testScale = rememberPressScale(testSource, pressedScale = 0.97f)
    val stopScale = rememberPressScale(stopSource, pressedScale = 0.97f)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onTest,
            interactionSource = testSource,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    scaleX = testScale
                    scaleY = testScale
                }
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null,
                modifier = Modifier.padding(end = 4.dp))
            Text(
                stringResource(R.string.test_alarm),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
        Button(
            onClick = onStop,
            enabled = alarmActive,
            interactionSource = stopSource,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer {
                    scaleX = stopScale
                    scaleY = stopScale
                },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(Icons.Default.Close, contentDescription = null,
                modifier = Modifier.padding(end = 4.dp))
            Text(stringResource(R.string.stop))
        }
    }
}

@Composable
internal fun EmptyAlarmState(
    message: String,
    hiding: Boolean,
    onHidingFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleState = remember { MutableTransitionState(false) }

    LaunchedEffect(Unit) {
        visibleState.targetState = true
    }

    LaunchedEffect(hiding) {
        if (hiding) {
            visibleState.targetState = false
            snapshotFlow { visibleState.isIdle && !visibleState.currentState }
                .first { it }
            onHidingFinished()
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(durationMillis = 320, easing = FastOutSlowInEasing)) +
            scaleIn(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                initialScale = 0.95f
            ) +
            slideInVertically(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight / 4 }
            ),
        exit = fadeOut(tween(durationMillis = 240, easing = FastOutSlowInEasing)),
        modifier = modifier
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp)
        )
    }
}

@Composable
internal fun AlarmListItem(
    alarm: AlarmItem,
    deletingAlarmIds: Set<String>,
    onDeleteRequest: () -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAnimationFinished: (String) -> Unit,
    onToggle: (Boolean) -> Unit,
    onEditTime: () -> Unit,
    onToggleDay: (Int) -> Unit,
    formatTime: () -> String,
    formatSchedule: () -> String,
    timezoneText: String,
    localTimeText: String? = null,
    modifier: Modifier = Modifier
) {
    val visibleState = remember(alarm.id) {
        MutableTransitionState(false)
    }

    LaunchedEffect(alarm.id) {
        visibleState.targetState = true
    }

    LaunchedEffect(deletingAlarmIds, alarm.id) {
        if (alarm.id in deletingAlarmIds) {
            visibleState.targetState = false
            snapshotFlow { visibleState.isIdle && !visibleState.currentState }
                .first { it }
            onDelete(alarm.id)
            onDeleteAnimationFinished(alarm.id)
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(tween(durationMillis = 260, easing = FastOutSlowInEasing)) +
            scaleIn(
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                initialScale = 0.92f
            ) +
            slideInVertically(
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                initialOffsetY = { fullHeight -> fullHeight / 4 }
            ),
        exit = fadeOut(tween(durationMillis = 240, easing = FastOutSlowInEasing)) +
            slideOutHorizontally(
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> fullWidth }
            ),
        modifier = modifier
    ) {
        AlarmCard(
            modifier = Modifier.fillMaxWidth(),
            alarm = alarm,
            onToggle = onToggle,
            onDelete = onDeleteRequest,
            onEditTime = onEditTime,
            onToggleDay = onToggleDay,
            formatTime = formatTime,
            formatSchedule = formatSchedule,
            timezoneText = timezoneText,
            localTimeText = localTimeText
        )
    }
}

@Composable
internal fun VolumeSettingsCard(
    earphoneVolumeFlow: StateFlow<Int>,
    speakerVolumeFlow: StateFlow<Int>,
    onEarphoneVolumeChange: (Int) -> Unit,
    onSpeakerVolumeChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val earphoneVolume by earphoneVolumeFlow.collectAsStateWithLifecycle()
    val speakerVolume by speakerVolumeFlow.collectAsStateWithLifecycle()
    var showInfo by remember { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Fixed 24dp header row: same height as every other card header.
            Row(
                modifier = Modifier.height(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SettingsCardHeader(
                    icon = Icons.AutoMirrored.Outlined.VolumeUp,
                    title = stringResource(R.string.volume_settings),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.expand_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable { showInfo = true }
                        .padding(3.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            VolumeSlider(stringResource(R.string.earphone), earphoneVolume,
                onValueChange = onEarphoneVolumeChange)
            Spacer(modifier = Modifier.height(4.dp))
            VolumeSlider(stringResource(R.string.speaker), speakerVolume,
                onValueChange = onSpeakerVolumeChange)
            if (earphoneVolume == 0 || speakerVolume == 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.volume_zero_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }

    if (showInfo) {
        AlertDialog(
            onDismissRequest = { showInfo = false },
            icon = {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(stringResource(R.string.volume_settings)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.volume_dnd_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(R.string.volume_restore_hint),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

@Composable
internal fun AlarmTimeoutCard(
    timeoutSecondsFlow: StateFlow<Int>,
    timeoutActionFlow: StateFlow<TimeoutAction>,
    onSecondsChange: (Int) -> Unit,
    onActionChange: (TimeoutAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val timeoutSeconds by timeoutSecondsFlow.collectAsStateWithLifecycle()
    val timeoutAction by timeoutActionFlow.collectAsStateWithLifecycle()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsCardHeader(
                icon = Icons.Outlined.Timer,
                title = stringResource(R.string.alarm_timeout)
            )
            Spacer(modifier = Modifier.height(8.dp))
            val minuteFormat = stringResource(R.string.duration_min)
            VolumeSlider(
                label = stringResource(R.string.duration),
                value = timeoutSeconds,
                onValueChange = { onSecondsChange((it / 10) * 10) },
                valueRange = 30f..1800f,
                displayText = { formatDuration((it / 10) * 10, minuteFormat) }
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.timeout_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.after_earphone_timeout),
                style = MaterialTheme.typography.titleSmall
            )
            TimeoutAction.entries.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = timeoutAction == action,
                            onClick = { onActionChange(action) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = timeoutAction == action, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        timeoutActionLabel(action),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (timeoutAction == TimeoutAction.FALLBACK) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.timeout_fallback_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun NoEarphoneCard(
    noEarphoneActionFlow: StateFlow<NoEarphoneAction>,
    onActionChange: (NoEarphoneAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val noEarphoneAction by noEarphoneActionFlow.collectAsStateWithLifecycle()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsCardHeader(
                icon = Icons.Outlined.HeadsetOff,
                title = stringResource(R.string.no_earphone_title)
            )
            Spacer(modifier = Modifier.height(4.dp))
            NoEarphoneAction.entries.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = noEarphoneAction == action,
                            onClick = { onActionChange(action) },
                            role = Role.RadioButton
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = noEarphoneAction == action, onClick = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        noEarphoneActionLabel(action),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
internal fun RingtoneCard(
    globalRingtoneUriFlow: StateFlow<String>,
    onPickRingtone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val globalRingtoneUri by globalRingtoneUriFlow.collectAsStateWithLifecycle()
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SettingsCardHeader(
                icon = Icons.Outlined.MusicNote,
                title = stringResource(R.string.ringtone)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (globalRingtoneUri.isNotBlank()) {
                    stringResource(R.string.custom_ringtone_set)
                } else {
                    stringResource(R.string.system_default_alarm)
                },
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onPickRingtone) {
                Text(stringResource(R.string.pick_ringtone))
            }
        }
    }
}
