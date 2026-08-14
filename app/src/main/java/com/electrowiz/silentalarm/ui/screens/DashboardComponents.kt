package com.electrowiz.silentalarm.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.electrowiz.silentalarm.R
import com.electrowiz.silentalarm.data.NoEarphoneAction
import com.electrowiz.silentalarm.data.TimeoutAction
import com.electrowiz.silentalarm.ui.theme.StatusErrorDark
import com.electrowiz.silentalarm.ui.theme.StatusErrorLight
import com.electrowiz.silentalarm.ui.theme.StatusOkDark
import com.electrowiz.silentalarm.ui.theme.StatusOkLight

internal val timeQuotes = listOf(
    "劝君莫惜金缕衣，劝君惜取少年时。\n花开堪折直须折，莫待无花空折枝。",
    "少年易老学难成，一寸光阴不可轻。\n未觉池塘春草梦，阶前梧叶已秋声。",
    "盛年不再来，一日难再晨。\n及时当勉励，岁月不待人。",
    "读书不觉已春深，一寸光阴一寸金。\n不是道人来引笑，周情孔思正追寻。",
    "少壮不努力，老大徒伤悲。",
    "莫等闲，白了少年头，空悲切。",
    "明日复明日，明日何其多。\n我生待明日，万事成蹉跎。",
    "人生天地之间，若白驹之过隙，忽然而已。",
    "逝者如斯夫，不舍昼夜。",
    "志士惜年，贤人惜日，圣人惜时。",
    "三更灯火五更鸡，正是男儿读书时。\n黑发不知勤学早，白首方悔读书迟。",
    "青春须早为，岂能长少年。",
    "年年岁岁花相似，岁岁年年人不同。",
    "流光容易把人抛，红了樱桃，绿了芭蕉。",
    "有花堪折直须折，莫待无花空折枝。",
    "Gather ye rosebuds while ye may,\nOld Time is still a-flying.",
    "Lost time is never found again.\nYou may delay, but time will not.",
    "Time and tide wait for no man.",
    "Yesterday is gone. Tomorrow has not yet come.\nWe have only today. Let us begin.",
    "The trouble is, you think you have time.",
    "Time is free, but it is priceless.\nYou can't own it, but you can use it.",
    "The best time to plant a tree was twenty years ago.\nThe second best time is now.",
    "It is not that we have a short time to live,\nbut that we waste a lot of it.",
    "The two most powerful warriors are patience and time.",
    "You may delay, but time will not.",
    "Tempus fugit.\nCarpe diem.",
    "少年老い易く学成り難し。",
    "光陰矢の如し、\n今日の後に今日なし。",
    "시간은 금이다.\n흐르는 물은 되돌아오지 않는다.",
    "Le temps perdu ne se retrouve jamais.",
    "Ne remets pas à demain\nce que tu peux faire aujourd'hui.",
    "Verlorene Zeit kommt niemals wieder.\nNutze den Tag.",
    "El tiempo perdido no se recupera.",
    "No dejes para mañana\nlo que puedas hacer hoy.",
    "Il tempo perduto non si recupera mai.",
    "月日は百代の過客にして、\n行きかふ年もまた旅人なり。",
    "行く春や　鳥啼き魚の　目は泪",
    "夏草や　兵どもが　夢の跡",
    "時は人を待たず。",
    "A stitch in time saves nine.",
    "Take time while time is,\nfor time will away."
)

/**
 * Consistent item animation for every dashboard card. The alarm cards' own
 * pop-in is handled by an inner scale/fade animation, so the list-level
 * fade-in is disabled (tween(0)); the placement spring carries the bounce
 * when cards reflow, and removal fades out over 300ms.
 */
@SuppressLint("ModifierFactoryExtensionFunction")
internal fun LazyItemScope.itemAnim(): Modifier = Modifier.animateItem(
    fadeInSpec = tween(durationMillis = 0),
    placementSpec = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow,
        visibilityThreshold = IntOffset(1, 1)
    ),
    fadeOutSpec = tween(durationMillis = 240)
)

@Composable
internal fun rememberPressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float
): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pressScale"
    )
    return scale
}

@Composable
internal fun Modifier.bouncyClick(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val scale = rememberPressScale(source, pressedScale = 0.98f)
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = source,
        onClick = onClick
    )
}

/**
 * A single row in the Process Keeping card: colored dot + label + status text
 * on the left, and an optional action button on the right.
 */
@Composable
internal fun StatusRow(
    label: String,
    ok: Boolean,
    statusText: String,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    val dark = isSystemInDarkTheme()
    val dotColor = if (ok) {
        if (dark) StatusOkDark else StatusOkLight
    } else {
        if (dark) StatusErrorDark else StatusErrorLight
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp),
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
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.defaultMinSize(minWidth = 0.dp, minHeight = 0.dp)
            ) {
                Text(actionText, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Format a duration in seconds for display:
 *   60 → "1 min"
 *   90 → "1:30"
 *  300 → "5 min"
 */
internal fun formatDuration(seconds: Int, minuteFormat: String): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (secs == 0) minuteFormat.format(mins)
    else "${mins}:%02d".format(secs)
}

@Composable
internal fun timeoutActionLabel(action: TimeoutAction): String = when (action) {
    TimeoutAction.STOP -> stringResource(R.string.stop)
    TimeoutAction.FALLBACK -> stringResource(R.string.fallback)
}

@Composable
internal fun noEarphoneActionLabel(action: NoEarphoneAction): String = when (action) {
    NoEarphoneAction.VIBRATE_ONLY -> stringResource(R.string.vibrate_only)
    NoEarphoneAction.LOUDSPEAKER -> stringResource(R.string.loudspeaker)
}
