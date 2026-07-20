package com.example.silentalarm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.silentalarm.data.AlarmItem
import com.example.silentalarm.data.NoEarphoneAction
import com.example.silentalarm.ui.components.GitHubRepoCard
import com.example.silentalarm.ui.components.StatusBanner
import com.example.silentalarm.ui.components.VolumeSlider
import com.example.silentalarm.ui.viewmodel.AlarmViewModel

/**
 * Main dashboard: status banner → test/stop → alarm list → settings → process keeping.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmDashboardScreen(
    viewModel: AlarmViewModel,
    onPickRingtone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alarms by viewModel.alarms.collectAsState()
    val earphoneVolume by viewModel.earphoneVolume.collectAsState()
    val speakerVolume by viewModel.speakerVolume.collectAsState()
    val noEarphoneAction by viewModel.noEarphoneAction.collectAsState()
    val globalRingtoneUri by viewModel.globalRingtoneUri.collectAsState()
    val showTimePicker by viewModel.showTimePicker.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()

    val shizukuOk by viewModel.shizukuConnected.collectAsState()
    val shizukuPerm by viewModel.shizukuPermitted.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    // Auto-refresh system status every time the screen recomposes (e.g. on resume)
    LaunchedEffect(Unit) { viewModel.refreshStatus() }

    // Show snackbar when message changes
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showAddTimePicker() }) {
                Icon(Icons.Default.Add, contentDescription = "Add alarm")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Earphone Alarm", style = MaterialTheme.typography.headlineMedium)
                Text("Plays through earphones only. Never wakes others.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // ── Status Banner ───────────────────────────────────────────
            item {
                StatusBanner(shizukuOk = shizukuOk && shizukuPerm)
            }

            // ── Test / Stop Buttons ─────────────────────────────────────
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.testAlarm() },
                        modifier = Modifier.weight(1f)
                    ) { Text("Test Alarm") }
                    Button(
                        onClick = { viewModel.stopAlarm() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null,
                            modifier = Modifier.padding(end = 4.dp))
                        Text("Stop")
                    }
                }
            }

            // ── Alarm List ──────────────────────────────────────────────
            if (alarms.isEmpty()) {
                item {
                    Text(
                        "No alarms set. Tap + to add one.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            } else {
                items(alarms, key = { it.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onToggle = { viewModel.toggleAlarm(alarm.id, it) },
                        onDelete = { viewModel.deleteAlarm(alarm.id) },
                        onToggleDay = { day ->
                            val newDays = if (day in alarm.daysOfWeek)
                                alarm.daysOfWeek - day else alarm.daysOfWeek + day
                            viewModel.updateAlarm(alarm.copy(daysOfWeek = newDays))
                        },
                        formatTime = { viewModel.formatTime(alarm.hour, alarm.minute) },
                        formatSchedule = { viewModel.formatSchedule(alarm) }
                    )
                }
            }

            // ── Volume Settings ─────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Volume Settings", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        VolumeSlider("Earphone", earphoneVolume,
                            onValueChange = { viewModel.setEarphoneVolume(it) })
                        Spacer(modifier = Modifier.height(4.dp))
                        VolumeSlider("Speaker", speakerVolume,
                            onValueChange = { viewModel.setSpeakerVolume(it) })
                    }
                }
            }

            // ── No-Earphone Action ──────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("When No Earphones", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        NoEarphoneAction.entries.forEach { action ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(
                                        selected = noEarphoneAction == action,
                                        onClick = { viewModel.setNoEarphoneAction(action) },
                                        role = Role.RadioButton
                                    )
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = noEarphoneAction == action, onClick = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(action.displayName, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

            // ── Ringtone Picker (global, applies to all alarms) ─────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.PlayArrow, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Ringtone", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            if (globalRingtoneUri.isNotBlank()) "Custom ringtone set"
                            else "System default alarm",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onPickRingtone,
                        ) { Text("Pick Ringtone") }
                    }
                }
            }

            // ── Process Keeping ─────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Settings, null,
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Process Keeping", style = MaterialTheme.typography.titleMedium)
                        }

                        // ── Row 1: Shizuku ─────────────────────────────────
                        StatusRow(
                            label = "Shizuku",
                            ok = shizukuOk && shizukuPerm,
                            statusText = when {
                                shizukuOk && shizukuPerm -> "Connected & Authorized"
                                shizukuOk && !shizukuPerm -> "Waiting for permission"
                                else -> "Not installed"
                            },
                            actionText = when {
                                !shizukuOk -> "Install Shizuku"
                                !shizukuPerm -> "Authorize"
                                else -> null
                            },
                            onAction = when {
                                !shizukuOk -> null // no action, informational
                                !shizukuPerm -> { { viewModel.requestShizukuPermission() } }
                                else -> null
                            }
                        )

                        // ── Battery Optimization ──────────────────────────
                        TextButton(
                            onClick = { viewModel.requestBatteryExemption() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Disable Battery Optimization") }
                    }
                }
            }

            // ── GitHub Repo ─────────────────────────────────────────
            item { GitHubRepoCard() }

            // Bottom spacing
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }

    // ── Time Picker Dialog ───────────────────────────────────────────────
    if (showTimePicker) {
        val editing = viewModel.editingAlarm()
        val pickerState = rememberTimePickerState(
            initialHour = editing?.hour ?: 8,
            initialMinute = editing?.minute ?: 0,
            is24Hour = true
        )

        AlertDialog(
            onDismissRequest = { viewModel.hideTimePicker() },
            title = { Text(if (editing != null) "Edit Alarm" else "New Alarm") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onTimeSelected(pickerState.hour, pickerState.minute)
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.hideTimePicker() }) { Text("Cancel") }
            }
        )
    }
}

// ── Status Row ───────────────────────────────────────────────────────────

/**
 * A single row in the Process Keeping card: colored dot + label + status text
 * on the left, and an optional action button on the right.
 */
@Composable
private fun StatusRow(
    label: String,
    ok: Boolean,
    statusText: String,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    val dotColor = if (ok) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                   else androidx.compose.ui.graphics.Color(0xFFFF5252)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.size(8.dp)
                .clip(CircleShape)
                .background(dotColor))
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (actionText != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionText, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ── Day-of-Week Constants ────────────────────────────────────────────────

/** Single-letter day labels indexed by Calendar.DAY_OF_WEEK (1=Sun..7=Sat). */
private val DAY_LETTERS = arrayOf("", "S", "M", "T", "W", "T", "F", "S")
private val ALL_DAYS = 1..7

// ── Day Circle Picker ────────────────────────────────────────────────────

/**
 * A row of 7 circular day buttons, each with a single letter.
 * Selected days get a filled primary-color circle; unselected are outlined.
 * Tapping toggles the day on/off.
 *
 * When no days are selected the alarm is treated as a one-shot.
 */
@Composable
private fun DayCircleRow(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        ALL_DAYS.forEach { day ->
            val isSelected = day in selectedDays
            androidx.compose.material3.Surface(
                onClick = { onToggleDay(day) },
                shape = CircleShape,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Text(
                        text = DAY_LETTERS[day],
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Alarm Card ───────────────────────────────────────────────────────────

/**
 * A single alarm card with time, day-of-week circle picker,
 * enable/disable switch, and delete button.
 */
@Composable
private fun AlarmCard(
    alarm: AlarmItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onToggleDay: (Int) -> Unit,
    formatTime: () -> String,
    formatSchedule: () -> String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.enabled)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(formatTime(), style = MaterialTheme.typography.headlineSmall)
                    if (alarm.label.isNotBlank()) {
                        Text(alarm.label, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(formatSchedule(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Delete alarm",
                        tint = MaterialTheme.colorScheme.error)
                }
            }

            // Circular day-of-week picker
            DayCircleRow(
                selectedDays = alarm.daysOfWeek,
                onToggleDay = onToggleDay,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
