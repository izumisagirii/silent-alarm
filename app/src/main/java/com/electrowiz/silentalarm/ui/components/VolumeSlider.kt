package com.electrowiz.silentalarm.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Labeled slider with a live value readout.
 *
 * The readout follows the thumb while dragging, but [onValueChange] is only
 * called once when the gesture ends — DataStore gets one write per gesture
 * instead of one per pixel of movement.
 */
@Composable
fun VolumeSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    displayText: (Int) -> String = { "$it%" }
) {
    var pending by remember(label) { mutableStateOf(value) }
    LaunchedEffect(value) { pending = value }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(8.dp))
            Text(displayText(pending), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = pending.toFloat(),
            onValueChange = { pending = it.roundToInt() },
            onValueChangeFinished = { onValueChange(pending) },
            valueRange = valueRange,
            steps = 0,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
