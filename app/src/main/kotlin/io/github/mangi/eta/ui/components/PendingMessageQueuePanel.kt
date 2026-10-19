package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.QueuedMessageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 运行中排队的待发送消息面板。
 *
 * 位置约束（用户明确要求）：**在输入框之外**，浮在输入框上方的独立卡片，
 * 不嵌进 TextField 容器。理由是这个面板只在运行中出现，尺寸会随条目数变化，
 * 放进输入框会把主输入区高度顶得来回跳。
 *
 * 交互对齐 Operit 的 PendingMessageQueuePanel：
 * 可折叠标题 + 每条一行（编辑按钮在输入区、发送/删除在面板内），单行省略。
 */
@Composable
internal fun PendingMessageQueuePanel(
    messages: List<QueuedMessageUi>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRemove: (String) -> Unit,
    onSendNow: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 没有排队消息时整个面板不占位，避免输入区上下抖动。
    if (messages.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainerHigh,
                cornerRadius = 16.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 16.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.chat_queue_title, messages.size),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_queue_collapse else R.string.chat_queue_expand
                ),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp),
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(140)) + expandVertically(tween(160)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    // 上限压住高度：运营商/多行排队时不能把聊天区挤没。
                    .heightIn(max = 168.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = messages, key = { it.id }) { item ->
                    QueuedMessageRow(
                        message = item,
                        onRemove = { onRemove(item.id) },
                        onSendNow = { onSendNow(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuedMessageRow(
    message: QueuedMessageUi,
    onRemove: () -> Unit,
    onSendNow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 10.dp,
            )
            .padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message.text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        QueueRowAction(
            icon = Icons.AutoMirrored.Rounded.Send,
            contentDescription = stringResource(R.string.chat_queue_send_now),
            tint = MiuixTheme.colorScheme.primary,
            onClick = onSendNow,
        )
        QueueRowAction(
            icon = Icons.Rounded.Close,
            contentDescription = stringResource(R.string.chat_queue_remove),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            onClick = onRemove,
        )
    }
}

@Composable
private fun QueueRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
    }
}
