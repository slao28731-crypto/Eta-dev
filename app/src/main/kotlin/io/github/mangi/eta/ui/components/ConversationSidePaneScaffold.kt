package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object ConversationPaneMetrics {
    /**
     * 展开时的侧栏宽度，不超过屏幕宽度的 [PaneWidthFraction]。
     *
     * 取 326.dp / 0.76f 是为了与上游原版对齐：这样在 393dp 屏上抽屉宽 298.7dp，
     * 前景卡片左边缘落在 304.7dp，右侧能露出 88.3dp 的聊天页左边缘，叠层感才成立。
     * 之前用的 340.dp / 0.84f 只露出 56.9dp，视觉上接近一条缝。
     */
    val PaneTargetWidth = 326.dp
    val PaneWidthFraction = 0.76f
    /**
     * 前景卡片展开时的阴影，用边缘层次代替黑色遮罩。
     *
     * 保持上游原值 12.dp。曾被提到 16dp，理由是"深色下卡片与抽屉同色，只能靠阴影分层"；
     * 但那个前提已经不存在了：现在抽屉 #0F0F0F、页面 #1A1A1A，颜色本身就分得开。
     * 16dp 在浅色下会在卡片下缘糊出一大片 #C6C6C6 的灰带，与 scaleY 0.97 露出的
     * 12dp 边叠在一起，看起来像多层叠影，所以退回原值。
     */
    val ForegroundShadow = 12.dp
    /** 允许轻微过冲，手感比临界阻尼更"活"。 */
    const val SettleDampingRatio = 0.9f
    const val SettleStiffness = 300f
    const val SettleVisibilityThresholdPx = 0.5f
    const val SettlePositionThresholdFraction = 0.5f
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val paneWidth = minOf(maxWidth * ConversationPaneMetrics.PaneWidthFraction, ConversationPaneMetrics.PaneTargetWidth)
        val paneWidthPx = with(density) { paneWidth.toPx() }
        val anchors = remember(paneWidthPx) {
            DraggableAnchors {
                ConversationPaneAnchor.Closed at 0f
                ConversationPaneAnchor.Open at paneWidthPx
            }
        }
        val paneDragState = remember {
            AnchoredDraggableState(
                initialValue = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed,
                anchors = anchors,
            )
        }
        val settleAnimation = remember {
            spring<Float>(
                dampingRatio = ConversationPaneMetrics.SettleDampingRatio,
                stiffness = ConversationPaneMetrics.SettleStiffness,
                visibilityThreshold = ConversationPaneMetrics.SettleVisibilityThresholdPx,
            )
        }
        val flingBehavior = AnchoredDraggableDefaults.flingBehavior(
            state = paneDragState,
            positionalThreshold = { distance ->
                distance * ConversationPaneMetrics.SettlePositionThresholdFraction
            },
            animationSpec = settleAnimation,
        )
        val currentVisible by rememberUpdatedState(visible)
        val currentOnOpen by rememberUpdatedState(onOpen)
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        val hapticFeedback = LocalHapticFeedback.current
        val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
        val dragInteractions = remember { MutableInteractionSource() }
        val isDragging by dragInteractions.collectIsDraggedAsState()
        LaunchedEffect(dragInteractions) {
            dragInteractions.interactions.collect { interaction ->
                if (interaction is DragInteraction.Stop) {
                    currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                }
            }
        }
        val isRevealed by remember(paneDragState) {
            derivedStateOf {
                val offset = paneDragState.offset
                !offset.isNaN() && offset > 0.5f
            }
        }
        // 展开进度（0 = 关闭，1 = 完全展开）。NaN / 未初始化时按 0 处理，避免首帧颜色跳变。
        val paneProgress by remember(paneDragState, paneWidthPx) {
            derivedStateOf {
                val offset = paneDragState.offset
                if (offset.isNaN() || paneWidthPx <= 0f) 0f else (offset / paneWidthPx).coerceIn(0f, 1f)
            }
        }
        val panelListState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        // 卡片底色直接取页面底色。
        // 历史上这里做过「按展开进度向抽屉色插值」的 lerp，目的是消除"纯黑卡片浮在
        // #242424 抽屉上像挖洞"。现在层次改由三级配色承担（抽屉 #0F0F0F / 页面 #1A1A1A /
        // 卡片 #343434），插值既没必要，还会让页面在开合过程中自己变色，已删除。
        val pageBackgroundColor = MiuixTheme.colorScheme.background
        // 卡片边缘的高光色，仅深色下使用。
        // 浅色下上游本来就没有这条描边，加了只会让卡片四边多出一圈浅灰细线，
        // 与「浅色保持上游原样」的目标冲突，所以浅色不画（见下方 border 的 if 判断）。
        val pageEdgeColor = MiuixTheme.colorScheme.onSurface
        val isLightTheme = pageBackgroundColor.luminance() > 0.5f
        val closePane: () -> Unit = {
            currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            focusManager.clearFocus()
            keyboard?.hide()
            if (currentVisible) {
                currentOnDismiss()
            } else {
                scope.launch { paneDragState.animateTo(ConversationPaneAnchor.Closed, settleAnimation) }
            }
        }
        LaunchedEffect(isRevealed) {
            if (isRevealed) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }

        SideEffect {
            paneDragState.updateAnchors(anchors)
        }

        LaunchedEffect(visible, paneWidthPx) {
            if (!visible && isRevealed) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            val target = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed
            if (paneDragState.targetValue != target || paneDragState.settledValue != target) {
                paneDragState.animateTo(target, settleAnimation)
            }
        }

        LaunchedEffect(paneDragState) {
            conversationPaneSettledValues(paneDragState) { isDragging }.collectLatest { settledValue ->
                val settledOpen = settledValue == ConversationPaneAnchor.Open
                if (settledOpen != currentVisible) {
                    if (settledOpen) {
                        currentOnOpen()
                    } else {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        currentOnDismiss()
                    }
                }
            }
        }

        // NavDisplay 的退出条目在转场期间仍会保留组合；仅允许已稳定显示的首页
        // 处理侧栏返回，避免它抢先消费二级页面的第一次返回事件。
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = isRevealed &&
                backHandlerEnabled &&
                sceneLifecycleState == Lifecycle.State.RESUMED,
            onBackCompleted = closePane,
        )

        Box(
            modifier = Modifier.fillMaxSize()
                .clipToBounds()
                .background(conversationPaneContainerColor())
                // 手势由共同容器拥有，侧栏和聊天舞台均可拖动；子级先处理滚动与选择。
                .anchoredDraggable(
                    state = paneDragState,
                    reverseDirection = false,
                    orientation = Orientation.Horizontal,
                    enabled = backHandlerEnabled && sceneLifecycleState == Lifecycle.State.RESUMED,
                    flingBehavior = flingBehavior,
                    interactionSource = dragInteractions,
                ),
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            // 两个槽位并排测量和放置，由同一位移推移；不在聊天内容下面叠放侧栏。
            // 侧栏保留组合，避免每次起手重新创建列表和搜索框。
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    ConversationPanePanel(
                        state = state,
                        width = paneWidth,
                        listState = panelListState,
                        onSearchChange = onSearchChange,
                        onConversationSelected = onConversationSelected,
                        onConversationRename = onConversationRename,
                        onConversationExport = onConversationExport,
                        onConversationDelete = onConversationDelete,
                        onOpenSettings = onOpenSettings,
                        onOpenModelProviders = onOpenModelProviders,
                        onOpenTools = onOpenTools,
                        onOpenSkills = onOpenSkills,
                        onOpenCharacters = onOpenCharacters,
                        onOpenPermissions = onOpenPermissions,
                        modifier = Modifier
                            .focusProperties { onEnter = { if (!isRevealed) cancelFocusChange() } }
                            .focusGroup()
                            .then(if (!isRevealed) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(pageBackgroundColor)
                            // 卡片底色与抽屉同色后，圆角与缝隙都是"同色对同色"，
                            // 边界只能靠这条 0.5dp hairline 表达。alpha 乘 progress：
                            // 关闭时不画，避免屏幕四边露出线条。深色用亮色 hairline，
                            // 因为深色下 shadowElevation 常被 ROM 削弱，描边是更可靠的层次来源。
                            .border(
                                width = 0.5.dp,
                                // 只在深色下画：浅色上游没有这条线，加了会让卡片四边多出一圈浅灰细线。
                                // 深色下 shadowElevation 常被 ROM 削弱，这条 hairline 是更可靠的层次来源。
                                color = if (isLightTheme) Color.Transparent
                                else pageEdgeColor.copy(alpha = 0.10f * paneProgress),
                                shape = RoundedCornerShape(FOREGROUND_CORNER_DP),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .focusProperties {
                                    onEnter = { if (isRevealed) cancelFocusChange() }
                                }
                                .focusGroup()
                                .then(if (isRevealed) Modifier.clearAndSetSemantics {} else Modifier),
                        ) {
                            // 页面自己的 Scaffold 会再画一层不透明底色，必须把当前底色传下去，
                            // 否则这一层会被纯黑盖掉，下面的插值等于没做。
                            CompositionLocalProvider(LocalPageBackgroundColor provides pageBackgroundColor) {
                                content()
                            }
                        }
                        if (isRevealed) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClickLabel = stringResource(R.string.action_close),
                                    onClick = closePane,
                                ),
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val panel = measurables[0].measure(
                    Constraints.fixed(paneWidthPx.roundToInt(), constraints.maxHeight),
                )
                val page = measurables[1].measure(
                    Constraints.fixed(constraints.maxWidth, constraints.maxHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // 物理左侧是会话区；不随 RTL 改变两个槽位的关系。
                    val motion = conversationPaneMotion(paneDragState.offset, paneWidthPx)
                    val displacement = motion.foregroundOffset.roundToInt()
                    // 抽屉贴住位移位置不动。
                    panel.placeWithLayer(displacement - panel.width, 0)
                    // 卡片在位移基础上再右移一点，在抽屉与卡片之间留出间隙。
                    val shiftPx = with(density) { motion.foregroundShift.toPx() }
                    val pageOffset = displacement + shiftPx.roundToInt()
                    // 前景页随展开缩小并加圆角，形成"退到下一层"的层次感。
                    // 缩放锚在左侧中位，右边缘才会随位移一起收进去。
                    // 不用黑色遮罩压暗（用户要求保持白底），改用卡片阴影做层次。
                    val cornerPx = with(density) { motion.foregroundCorner.toPx() }
                    page.placeWithLayer(
                        x = pageOffset,
                        y = 0,
                    ) {
                        scaleX = motion.foregroundScale
                        scaleY = motion.foregroundScaleY
                        transformOrigin = TransformOrigin(0f, 0.5f)
                        // 圆角始终 > 0（关闭时即系统屏幕圆角），不需要条件分支。
                        shape = RoundedCornerShape(cornerPx)
                        clip = true
                        shadowElevation = with(density) { ConversationPaneMetrics.ForegroundShadow.toPx() }
                    }
                }
            }
        }
    }
}
