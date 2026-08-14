package com.electrowiz.silentalarm.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.ui.components.SettingsCardHeader

/**
 * Process keeping card: read-only system status on top (notification, exact
 * alarm, battery optimization, Shizuku backend — weakest to strongest), then
 * the two optional toggles below.
 *
 * Both toggles are independent of the core scheduler: alarms still fire from
 * AlarmManager with either or both turned off.
 */
@Composable
internal fun ProcessKeepingCard(
    notificationsAllowed: Boolean,
    onRequestNotificationPermission: () -> Unit,
    exactAlarmAllowed: Boolean,
    batteryOptimizationIgnored: Boolean,
    onRequestBatteryExemption: () -> Unit,
    keepAliveEnabled: Boolean,
    onKeepAliveChange: (Boolean) -> Unit,
    privilegedEnabled: Boolean,
    onPrivilegedChange: (Boolean) -> Unit,
    shellReady: Boolean,
    shizukuPermissionNeeded: Boolean,
    onRequestPrivilegedPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SettingsCardHeader(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.process_keeping)
            )

            // ── System status (read-only) ────────────────────────────────

            StatusRow(
                label = stringResource(R.string.notifications_title),
                ok = notificationsAllowed,
                statusText = if (notificationsAllowed) {
                    stringResource(R.string.exact_alarm_allowed)
                } else {
                    stringResource(R.string.exact_alarm_denied)
                },
                actionText = if (notificationsAllowed) {
                    null
                } else {
                    stringResource(R.string.exact_alarm_request)
                },
                onAction = if (notificationsAllowed) {
                    null
                } else {
                    onRequestNotificationPermission
                }
            )

            StatusRow(
                label = stringResource(R.string.exact_alarm),
                ok = exactAlarmAllowed,
                statusText = if (exactAlarmAllowed) {
                    stringResource(R.string.exact_alarm_allowed)
                } else {
                    stringResource(R.string.exact_alarm_denied)
                },
                actionText = null,
                onAction = null
            )

            StatusRow(
                label = stringResource(R.string.disable_battery_optimization),
                ok = batteryOptimizationIgnored,
                statusText = if (batteryOptimizationIgnored) {
                    stringResource(R.string.battery_already_exempt)
                } else {
                    stringResource(R.string.battery_not_exempt)
                },
                actionText = if (batteryOptimizationIgnored) {
                    null
                } else {
                    stringResource(R.string.disable_battery_optimization)
                },
                onAction = if (batteryOptimizationIgnored) {
                    null
                } else {
                    onRequestBatteryExemption
                }
            )

            StatusRow(
                label = stringResource(R.string.shizuku),
                ok = shellReady,
                statusText = when {
                    shellReady -> stringResource(R.string.shizuku_connected)
                    shizukuPermissionNeeded ->
                        stringResource(R.string.shizuku_waiting_permission)
                    else -> stringResource(R.string.shizuku_not_installed)
                },
                actionText = when {
                    shizukuPermissionNeeded -> stringResource(R.string.shizuku_authorize)
                    else -> null
                },
                onAction = when {
                    shizukuPermissionNeeded -> onRequestPrivilegedPermission
                    else -> null
                }
            )

            HorizontalDivider()

            // ── Optional toggles ─────────────────────────────────────────

            SwitchRow(
                title = stringResource(R.string.keep_alive_title),
                description = stringResource(R.string.keep_alive_desc),
                checked = keepAliveEnabled,
                onCheckedChange = onKeepAliveChange
            )

            HorizontalDivider()

            SwitchRow(
                title = stringResource(R.string.shizuku),
                description = stringResource(R.string.shizuku_desc),
                checked = privilegedEnabled,
                onCheckedChange = onPrivilegedChange
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
