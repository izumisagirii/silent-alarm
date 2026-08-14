package com.electrowiz.silentalarm.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.data.AlarmItem

/**
 * A single alarm card with time, day-of-week circle picker,
 * enable/disable switch, and delete button.
 */
@Composable
internal fun AlarmCard(
    modifier: Modifier = Modifier,
    alarm: AlarmItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEditTime: () -> Unit,
    onToggleDay: (Int) -> Unit,
    formatTime: () -> String,
    formatSchedule: () -> String,
    timezoneText: String,
    localTimeText: String? = null
) {
    val haptic = LocalHapticFeedback.current
    val deleteSource = remember { MutableInteractionSource() }
    val deleteScale = rememberPressScale(deleteSource, pressedScale = 0.88f)
    val cardColor by animateColorAsState(
        targetValue = if (alarm.enabled) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "alarmCardColor"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .bouncyClick(onClick = onEditTime)
            .graphicsLayer { alpha = if (alarm.enabled) 1f else 0.78f },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                formatTime(),
                                style = MaterialTheme.typography.headlineMedium
                            )
                            if (localTimeText != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    localTimeText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(bottom = 6.dp)
                                )
                            }
                        }
                        Text(
                            formatSchedule(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            timezoneText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            onClick = {
                                runCatching {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                onDelete()
                            },
                            interactionSource = deleteSource,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.error,
                            shadowElevation = 1.dp,
                            modifier = Modifier
                                .size(38.dp)
                                .graphicsLayer {
                                    scaleX = deleteScale
                                    scaleY = deleteScale
                                }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    stringResource(R.string.delete_alarm)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
                    }
                }

                DayCircleRow(
                    selectedDays = alarm.daysOfWeek,
                    onToggleDay = onToggleDay,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            LabelChip(
                label = alarm.label,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 12.dp)
            )
        }
    }
}

/**
 * Fixed-size label chip shown in the top-right corner of an alarm card.
 */
@Composable
private fun LabelChip(
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .height(26.dp)
            .widthIn(max = 150.dp)
            .alpha(if (label.isBlank()) 0f else 1f)
    ) {
        Text(
            text = label.trim(),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
        )
    }
}
