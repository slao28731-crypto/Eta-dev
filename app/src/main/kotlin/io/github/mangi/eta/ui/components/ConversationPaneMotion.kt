package io.github.mangi.eta.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal data class ConversationPaneMotion(
    val progress: Float,
    val foregroundOffset: Float,
    val panelOffset: Float,
    /** 前景页横向缩放：关闭 1.0 → 展开 [FOREGROUND_MIN_SCALE]。 */
    val foregroundScale: Float,
    /** 前景页纵向缩放：关闭 1.0 → 展开 [FOREGROUND_MIN_VERTICAL_SCALE]。 */
    val foregroundScaleY: Float,
    /** 前景页圆角：全程恒为 [FOREGROUND_CORNER_DP]。 */
    val foregroundCorner: Dp,
    /** 前景页相对抽屉右边缘的额外偏移：关闭 0 → 展开 [FOREGROUND_SHIFT_DP]（负值表示向左压住抽屉）。 */
    val foregroundShift: Dp,
)

/** 展开时前景页横向缩到的最小比例。 */
internal const val FOREGROUND_MIN_SCALE = 0.95f

/** 展开时前景页纵向缩到的最小比例；比横向小，让卡片显得更高。 */
internal const val FOREGROUND_MIN_VERTICAL_SCALE = 0.97f

/** 展开时前景页相对抽屉右边缘的额外偏移（dp）：0 表示与抽屉右边缘贴齐，负值向左压住抽屉一点。 */
internal const val FOREGROUND_SHIFT_DP = 6f

/**
 * 前景卡片圆角（dp），全程恒定，不随展开进度插值。
 *
 * 关闭时前景页恰好铺满屏幕，这个圆角会同时作用在整屏四角上；超出物理屏圆角的
 * 部分会露出容器底色（深色下可见）。
 */
internal val FOREGROUND_CORNER_DP = 36.dp

/**
 * 计算侧栏位移对应的前景页几何。
 */
internal fun conversationPaneMotion(
    offset: Float,
    paneWidth: Float,
): ConversationPaneMotion {
    val width = paneWidth.takeIf { it.isFinite() && it > 0f } ?: 0f
    val displacement = if (offset.isFinite()) offset.coerceIn(0f, width) else 0f
    val progress = if (width > 0f) displacement / width else 0f
    return ConversationPaneMotion(
        progress = progress,
        foregroundOffset = displacement,
        panelOffset = displacement - width,
        foregroundScale = 1f - (1f - FOREGROUND_MIN_SCALE) * progress,
        foregroundScaleY = 1f - (1f - FOREGROUND_MIN_VERTICAL_SCALE) * progress,
        foregroundCorner = FOREGROUND_CORNER_DP,
        // 偏移量随进度加权：关闭时必须为 0，否则屏幕左侧会露出缝隙。
        foregroundShift = (FOREGROUND_SHIFT_DP * progress).dp,
    )
}