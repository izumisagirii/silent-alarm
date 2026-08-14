package com.electrowiz.silentalarm.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import com.electrowiz.silentalarm.R

private val ALL_DAYS = 1..7

/**
 * A centered row of day buttons with fixed spacing.
 * Selected days get a filled primary-color circle.
 * Tapping toggles the day on/off.
 */
@Composable
internal fun DayCircleRow(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dayLetters = stringArrayResource(R.array.day_letters)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
    ) {
        ALL_DAYS.forEach { day ->
            DayCircle(
                letter = dayLetters.getOrNull(day) ?: "",
                selected = day in selectedDays,
                onClick = { onToggleDay(day) }
            )
        }
    }
}

/**
 * One day toggle: fills/empties with a short color crossfade and a light
 * spring pop when (de)selected.
 */
@Composable
private fun DayCircle(
    letter: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "dayCircleBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary
                      else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 180),
        label = "dayCircleText"
    )
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            selected -> 1.04f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "dayCircleScale"
    )

    Surface(
        onClick = {
            runCatching { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
            onClick()
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        color = bgColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
        shadowElevation = if (selected) 3.dp else 0.dp,
        modifier = Modifier
            .size(36.dp)
            .scale(scale)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelLarge,
                color = textColor
            )
        }
    }
}
