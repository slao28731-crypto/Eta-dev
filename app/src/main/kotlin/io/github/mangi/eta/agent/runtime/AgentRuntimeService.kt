package io.github.mangi.eta.agent.runtime

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.app.Service
import android.app.ActivityOptions
import android.app.PendingIntent
import io.github.mangi.eta.ui.MainActivity
import android.os.Build
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.window.OnBackInvokedDispatcher
import android.window.OnBackInvokedCallback
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.overlay.AgentHapticFeedback
import io.github.mangi.eta.agent.overlay.AgentOverlayGlow
import io.github.mangi.eta.agent.overlay.ScreenCornerRadii
import io.github.mangi.eta.agent.overlay.AgentOverlayCapsule
import io.github.mangi.eta.agent.overlay.AgentOverlayClearance
import io.github.mangi.eta.agent.overlay.AgentResultCard
import io.github.mangi.eta.agent.overlay.AgentOverlayPhase
import io.github.mangi.eta.agent.overlay.AgentOverlayState
import io.github.mangi.eta.agent.overlay.AgentOverlayStatus
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.overlay.applyEvent
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.ModuleConfig
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 模块进程内的通用 Agent Runtime。
 *
 * Hook 入口只发送请求和接收结果；模型调用、工具执行、运行状态浮窗都在本服务中完成。
 */
internal class AgentRuntimeService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val resultIo = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "agent-result-io") }
    private val serviceMessenger = Messenger(IncomingHandler())

    /**
     * 多并发改造（第 1 批）：所有在跑的 run，按 runId 索引。
     *
     * 改造前的 `activeSession: AgentRuntimeSession?` 已被彻底删除 ——
     * 它是「一条对话发消息就掐死另一条」的根源（P096）。
     *
     * [primaryRunId] 表示**当前在浮层上展示的那一个**：
     * overlay 胶囊、暂停/恢复按钮、补充指令输入框在物理上都只能对应一个 run
     * （一个窗口、一颗按钮）。所以约定：**谁是主任务，谁上浮层；其余的安静跑。**
     */
    private val activeSessions = java.util.concurrent.ConcurrentHashMap<String, AgentRuntimeSession>()

    /** 当前占据浮层的主任务 runId；null 表示没有主任务。 */
    @Volatile
    private var primaryRunId: String? = null
    /** 取主任务。所有 overlay 相关的操作都走它，保证「只有一个在画界面」。 */
    private fun primarySession(): AgentRuntimeSession? {
        val id = primaryRunId ?: return null
        return activeSessions[id]
    }

    /**
     * 除主任务之外还在跑的任务数（第 3 批）。
     *
     * 胶囊一次只能显示一个任务的状态，用户看不出「另一个还在后台干活」。
     * 这个数字渲染成状态文字右侧的 `+N` 角标，是用户在收起态唯一能看到并发的窗口。
     *
     * 只在主线程读写：所有 [activeSessions] 的增删与 [primaryRunId] 的变更都发生在主线程
     * （[startRun] 由 IncomingHandler 调用，其余在 mainHandler.post 内）。
     */
    private val otherRunCountState = mutableStateOf(0)

    /** 重算「其余在跑的任务数」。必须紧跟每一次 [activeSessions] / [primaryRunId] 变更调用。 */
    private fun refreshOtherRunCount() {
        val count = activeSessions.keys.count { it != primaryRunId }
        if (otherRunCountState.value != count) otherRunCountState.value = count
    }


    private var startRequestGeneration = 0L
    private var pendingStartRequest: PendingStartRequest? = null

    private data class PendingStartRequest(
        val generation: Long,
        val incoming: AgentRuntimeWire.IncomingRunRequest,
        val replyTo: Messenger?,
    )

    private var windowManager: WindowManager? = null
    private var glowView: ComposeView? = null
    private var capsuleView: ComposeView? = null
    private var resultCardView: ComposeView? = null
    private var glowParams: WindowManager.LayoutParams? = null
    private var capsuleParams: WindowManager.LayoutParams? = null
    private var resultCardParams: WindowManager.LayoutParams? = null
    private var resultCardBack: Pair<OnBackInvokedDispatcher, OnBackInvokedCallback>? = null

    /** 浮层与执行通知共用同一份运行状态；写入只发生在主线程。 */
    private val state = object {
        private val holder = mutableStateOf(AgentOverlayState.Initial)
        var value: AgentOverlayState
            get() = holder.value
            set(next) {
                holder.value = next
                AgentExecutionService.updateRunStatus(
                    this@AgentRuntimeService,
                    next.status.takeIf { primarySession()?.isTerminal == false },
                )
            }
    }
    private val capsuleExpanded = mutableStateOf(false)
    /** Agent 手势穿过胶囊时为 true：窗口不可触摸，胶囊淡出让路。 */
    private val capsuleYielded = mutableStateOf(false)
    private val restoreCapsule = Runnable { setCapsuleYielded(false) }
    private val clearanceHost = object : AgentOverlayClearance.Host {
        override fun capsuleTouchBounds(): Rect? {
            val view = capsuleView?.takeIf { it.isAttachedToWindow } ?: return null
            val location = IntArray(2).also(view::getLocationOnScreen)
            val slop = dpToPx(CAPSULE_CLEARANCE_SLOP_DP)
            return Rect(
                location[0] - slop,
                location[1] - slop,
                location[0] + view.width + slop,
                location[1] + view.height + slop,
            )
        }

        override fun yieldCapsule() = setCapsuleYielded(true)

        override fun scheduleCapsuleRestore(delayMs: Long) {
            mainHandler.removeCallbacks(restoreCapsule)
            mainHandler.postDelayed(restoreCapsule, delayMs)
        }

        override fun cancelRestore() = mainHandler.removeCallbacks(restoreCapsule)
    }
    private var hasExecutedForegroundTool = false
    /**
     * 结果卡片"回到 Eta"打开的会话：App 内发起的 run 带会话 ID；系统助手等外部入口
     * 带来源与会话键，由界面导入归档后打开。两者都没有时只回到首页。
     */
    private var resultConversationId: String? = null
    private var resultArchive: Pair<String, String>? = null
    private val supplementsLock = Any()
    private val activeSupplements = mutableListOf<AgentUiHandoffPayload.Supplement>()
    private var nextSupplementIndex = 1
    @Volatile
    private var lastCompletedRunContext: CompletedRunContext? = null
    private val hideToken = Any()

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        AgentOverlayClearance.attach(clearanceHost)
    }

    override fun onBind(intent: Intent): IBinder? {
        if (intent.action != AgentRuntimeWire.ACTION_BIND) return null
        return serviceMessenger.binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_KEEP_ALIVE || activeSessions.isEmpty()) {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (activeSessions.values.any { !it.isTerminal }) {
            AndroidAgentLogger.debug {
                "Agent runtime client unbound while run is active; detached run continues"
            }
        }
        return false
    }

    override fun onDestroy() {
        startRequestGeneration++
        pendingStartRequest?.let { pending ->
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, pending.incoming.request.runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = pending.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "Agent Runtime 服务已停止",
                ),
            )
        }
        pendingStartRequest = null
        activeSessions.values.forEach { it.cancel("Agent Runtime 服务已停止") }
        activeSessions.clear()
        primaryRunId = null
        refreshOtherRunCount()
        resultIo.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        AgentOverlayClearance.detach(clearanceHost)
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (!isMessageSenderAllowed(msg)) {
                if (msg.what == AgentRuntimeWire.MSG_START_RUN) {
                    AgentRuntimeWire.closeImageDescriptors(msg.data)
                }
                return
            }
            when (msg.what) {
                AgentRuntimeWire.MSG_START_RUN -> {
                    val data = msg.data
                    if (data == null) {
                        finishWithFailure("Agent Runtime 请求缺少消息体", msg.replyTo)
                        return
                    }
                    val incoming = runCatching {
                        AgentRuntimeWire.incomingRunRequestFromBundle(data)
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_invalid_start_request") {
                            "Agent runtime rejected invalid start request: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure("Agent Runtime 请求格式无效", msg.replyTo)
                        return
                    }
                    val request = incoming.request
                    if (request.runId.isBlank() || (request.operation != AgentRuntimeWire.OP_COMPACT && request.prompt.isBlank() && incoming.images.isEmpty() && !incoming.hasDeferredPrompt)) {
                        incoming.close()
                        finishWithFailure("Agent Runtime 请求缺少 runId 或用户输入", msg.replyTo)
                        return
                    }
                    ingestRunRequest(incoming, msg.replyTo)
                }

                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    dispatchResultIo { AgentRuntimeResultStore.remove(this@AgentRuntimeService, runId) }
                }

                AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                    val runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return)
                    val owner = msg.data.getString("context_owner").orEmpty()
                    val replyTo = msg.replyTo
                    dispatchResultIo {
                        val target = AgentRuntimeResultStore.readOwned(this@AgentRuntimeService, runId, owner)
                        sendResultNow(replyTo, target?.result ?: AgentRuntimeWire.RunResult(
                            runId, false, "", "完整运行结果不可用", contextSnapshotRef = runId,
                        ))
                    }
                }

                AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                    sendDrainedResults(msg.replyTo, msg.data?.getBoolean("complete_result_refs") == true)
                }

                AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN -> {
                    sendActiveRun(msg.replyTo)
                }

                AgentRuntimeWire.MSG_ATTACH_RUN -> {
                    attachRun(
                        runId = AgentRuntimeWire.runIdFromBundle(msg.data ?: return),
                        replyTo = msg.replyTo,
                    )
                }
            }
        }
    }

    private fun ingestRunRequest(
        incoming: AgentRuntimeWire.IncomingRunRequest,
        replyTo: Messenger?,
    ) {
        val generation = ++startRequestGeneration
        pendingStartRequest?.let { previous ->
            previous.incoming.close()
            sendRequestIngestedTo(previous.replyTo, previous.incoming.request.runId)
            sendResultTo(
                previous.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = previous.incoming.request.runId,
                    ok = false,
                    content = "",
                    error = "已被新的 Agent 任务替换",
                ),
            )
        }
        val pending = PendingStartRequest(generation, incoming, replyTo)
        pendingStartRequest = pending
        thread(name = "agent-runtime-image-ingest") {
            val prepared = runCatching {
                val request = AgentRuntimeImageTransfer.materialize(incoming)
                if (!AgentRuntimeRequestConfigResolver.requiresRuntimeConfig(request)) {
                    request
                } else {
                    val runtimeConfig = runBlocking {
                        RuntimeConfigRepository.currentRuntimeConfig()
                    } ?: throw RuntimeConfigUnavailableException()
                    AgentRuntimeRequestConfigResolver.applyRuntimeConfig(request, runtimeConfig)
                }
            }
            mainHandler.post {
                if (generation != startRequestGeneration || pendingStartRequest !== pending) return@post
                pendingStartRequest = null
                sendRequestIngestedTo(replyTo, incoming.request.runId)
                prepared.fold(
                    onSuccess = { request ->
                        val permissions = AgentRuntimePolicy.permissions(
                            Prefs.localAgentPreferences()
                        )
                        startRun(
                            request.copy(
                                config = AgentRuntimePolicy.constrain(request.config, permissions),
                            ),
                            replyTo,
                        )
                    },
                    onFailure = { throwable ->
                        AndroidAgentLogger.warnThrottled("runtime_request_prepare_failed") {
                            "Agent runtime request preparation failed: type=${throwable.safeLogType()}"
                        }
                        finishWithFailure(
                            when (throwable) {
                                is AgentRuntimeImageTransfer.ImageTransferException ->
                                    throwable.message ?: "Agent Runtime 无法读取图片"
                                is RuntimeConfigUnavailableException ->
                                    "请先在 Eta 中配置可用的模型"
                                else -> "Agent Runtime 无法准备请求"
                            },
                            replyTo,
                        )
                    },
                )
            }
        }
    }

    private fun startRun(
        request: AgentRuntimeWire.RunRequest,
        replyTo: Messenger? = null,
    ) {
        // 多并发改造：**不再无条件取消旧任务**。
        // 原代码这里是 `activeSession?.controller?.cancel()` —— 一条对话在跑、
        // 另开对话发消息就把前一条掐死，根因就在这一行（见 P096）。
        // 现在同一个 runId 重入才取消（重试/续跑），不同 runId 各跑各的。
        activeSessions[request.runId]?.controller?.cancel()
        val session = AgentRuntimeSession(
            runId = request.runId,
            operation = request.operation,
            eventSink = { event -> sendEventTo(replyTo, event) },
            resultSink = { result -> sendResultTo(replyTo, result) },
        )
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { session.controller.cancel() }
        if (!executionHeld && !allowBoundFallback) {
            session.complete(AgentRuntimeWire.RunResult(
                runId = request.runId, ok = false, content = "",
                error = "无法启动后台执行服务，请返回 Eta 后重试",
            )) {}
            return
        }
        activeSessions[request.runId] = session
        // 主任务选取规则：
        // - 当前没有主任务 → 这个新任务接管浮层
        // - 已有主任务在跑 → 新任务**安静在后台跑**，不抢浮层、不弹胶囊
        //   （否则用户在 A 对话盯着胶囊，B 对话一开就把 A 的状态顶掉了）
        val becamePrimary = primaryRunId == null
        if (becamePrimary) primaryRunId = request.runId
        // 第 3 批：新任务入册后重算角标。非主任务会增加「其余在跑」的数量，
        // 主任务则让这个数字归零（它就是被显示的那一个）。
        refreshOtherRunCount()
        lastCompletedRunContext = null
        runCatching {
            startService(Intent(this, AgentRuntimeService::class.java).setAction(ACTION_KEEP_ALIVE))
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_keep_alive_start_failed") {
                "Agent runtime keep-alive start failed: type=${throwable.safeLogType()}"
            }
        }
        mainHandler.removeCallbacksAndMessages(hideToken)
        // 非主任务不碰浮层：它安静在后台跑，只把自己的事件推给 UI 进程。
        // 这里整段都是「重置胶囊/进初始态/准备结果卡」的前台动作，
        // 让后台任务执行会把主任务正在展示的状态顶掉。
        if (becamePrimary) {
            state.value = AgentOverlayState.Initial
            capsuleExpanded.value = false
            setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
            // 新任务接管浮层时，上一个任务的「结果卡片」必须先撤掉：
            // 它是半屏窗口且带关闭键，关闭走的是 dismissAndStop()（会 stopSelf 并拆掉胶囊），
            // 留着它等于给后台任务埋一个「点一下就全没了」的按钮（P097）。
            removeResultCard()
        }
        hasExecutedForegroundTool = false
        resultConversationId = request.handoff
            ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { AgentUiHandoffPayload.from(it.payload).conversationId }
            ?.takeIf(String::isNotBlank)
        resultArchive = request.handoff
            ?.takeIf { it.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
            ?.let { handoff ->
                AgentExternalArchivePayload.from(handoff.payload)
                    ?.conversationKey
                    ?.takeIf(String::isNotBlank)
                    ?.let { key -> handoff.source to key }
            }
        // 补充指令（steering）队列也按 run 隔离：只有主任务用得到胶囊输入。
        // 非主任务不清空，避免把主任务已排队的补充指令抹掉。
        if (becamePrimary) {
            synchronized(supplementsLock) {
                activeSupplements.clear()
                nextSupplementIndex = 1
                if (request.handoff?.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
                    val payload = AgentUiHandoffPayload.from(request.handoff.payload)
                    activeSupplements += payload.supplements
                    nextSupplementIndex = payload.lastSupplementIndex + 1
                }
            }
        }

        thread(name = "agent-runtime") {
            try {
                executeRun(session, request)
            } finally {
                AgentExecutionService.release("run:${request.runId}")
                // 第 3 批修复：这里原本是无条件 `updateRunStatus(this, null)`。
                // 并发之后它是错的 —— A 先跑完就把 runStatus 擦成 null，
                // 通知栏里正在干活的 B 的状态文字随之消失（P097）。
                // 改为交给主线程判断：只有「最后一个任务也走了」才清空。
                mainHandler.post { refreshRunStatusIfIdle() }
            }
        }
    }

    /**
     * 只在没有任何在跑的 run 时清空通知状态。
     *
     * `release()` 是按 runId 释放的（安全），但 [AgentExecutionService.updateRunStatus]
     * 写的是一个全局单值，必须由「还有没有别人」来决定能否清（P097）。
     */
    private fun refreshRunStatusIfIdle() {
        if (activeSessions.isNotEmpty()) return
        AgentExecutionService.updateRunStatus(this, null)
    }

    private fun executeRun(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ) {
        val outcome = AgentRuntimeRunExecutor(
            context = this,
            currentPermissions = ::currentRuntimePermissions,
            snapshotRequest = { it.withActiveSupplements() },
            onAcceptedEvent = { event, entrySurfaceGuard ->
                handleAcceptedRunEvent(session, event, entrySurfaceGuard)
            },
            persistArtifacts = ::persistRunArtifacts,
        ).execute(session, request)
        // 并发改造后必须无条件摘除自己：并发场景下 outcome.shouldUpdateHost 可能为 false
        // （例如后台任务被跳过），若跟着下面那行一起 return，这个 session 会永远留在
        // activeSessions 里 —— 既泄漏，又让「还有任务在跑」的判断永远为真。
        // postTerminalOverlay 内部也会 remove，但它是幂等的（先判身份再删）。
        if (!outcome.shouldUpdateHost) {
            mainHandler.post {
                if (activeSessions[session.runId] === session) {
                    activeSessions.remove(session.runId)
                    if (primaryRunId == session.runId) {
                        primaryRunId = activeSessions.keys.firstOrNull()
                    }
                    refreshOtherRunCount()
                }
                refreshRunStatusIfIdle()
            }
            return
        }
        postTerminalOverlay(
            session = session,
            result = outcome.result,
            entrySurfaceGuard = outcome.entrySurfaceGuard,
            completedContext = outcome.response?.let { completedResponse ->
                outcome.completedRequest?.let { completedRequest ->
                    CompletedRunContext(
                        request = completedRequest,
                        response = completedResponse,
                    )
                }
            },
        )
    }

    private fun handleAcceptedRunEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        entrySurfaceGuard: EntrySurfaceGuard?,
    ) {
        // 只接受仍在册的 run 的事件；已移除的（跑完/取消）直接丢弃。
        if (activeSessions[session.runId] !== session) return
        val revealsForegroundOperation = AgentOverlayVisibilityPolicy.shouldRevealFor(event)
        val requiresEntrySurfaceDismissal =
            AgentOverlayVisibilityPolicy.shouldDismissEntrySurfaceFor(event)
        val entrySurfaceReady = if (requiresEntrySurfaceDismissal && entrySurfaceGuard != null) {
            runCatching { entrySurfaceGuard.dismissOnce() }.getOrDefault(false)
        } else {
            true
        }
        mainHandler.post {
            if (activeSessions[session.runId] !== session) return@post
            // **关键**：只有主任务才更新浮层状态。
            // 后台任务（用户在另一条对话里跑的那个）的事件仍然会经 eventSink
            // 推送到 UI 进程，去更新它自己的对话气泡，但绝不能碰共享的 overlay，
            // 否则两个任务的事件会互相涂抹状态（A 的进度被 B 顶掉，反之亦然）。
            if (primaryRunId != session.runId) return@post
            if (
                AgentOverlayVisibilityPolicy.shouldRecordForegroundExecution(
                    event,
                    entrySurfaceReady,
                )
            ) {
                hasExecutedForegroundTool = true
            }
            if (session.isTerminal) return@post
            runCatching {
                state.value = state.value.applyEvent(event)
                if (revealsForegroundOperation && entrySurfaceReady) {
                    if (capsuleView == null) {
                        AgentHapticFeedback.perform(
                            this,
                            AgentHapticFeedback.Type.RUN_STARTED,
                        )
                    }
                    ensureOverlayVisible()
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_overlay_event_failed") {
                    "Agent runtime overlay event failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun persistRunArtifacts(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>,
    ) {
        // outbox 是终态与在途 checkpoint 之间的提交点；失败时保留 checkpoint 供下次恢复。
        persistCompletedRun(request, result)
        runCatching { persistArchivedRun(request, result, events) }
            .onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime archive persistence failed: type=${throwable.safeLogType()}"
                )
            }
    }

    private fun postTerminalOverlay(
        session: AgentRuntimeSession,
        result: AgentRuntimeWire.RunResult,
        entrySurfaceGuard: EntrySurfaceGuard?,
        completedContext: CompletedRunContext? = null,
    ) {
        mainHandler.post {
            if (activeSessions[session.runId] !== session) return@post
            // 先把自己从在册表里摘掉，再决定浮层归属。
            activeSessions.remove(session.runId)
            val wasPrimary = primaryRunId == session.runId
            if (wasPrimary) {
                // 主任务退出：把浮层让给下一个还在跑的任务（如果有），
                // 否则清空主任务标记，浮层回到空闲。
                primaryRunId = activeSessions.keys.firstOrNull()
            }
            refreshOtherRunCount()
            refreshRunStatusIfIdle()
            // 后台任务结束不进终态浮层，也不改主任务状态；
            // 它的结果已经通过 resultSink 投递给 UI 进程，由那边的对话负责展示。
            if (!wasPrimary) return@post
            // 第 3 批修复：浮层让给下一个任务时，必须把渲染状态也交出去。
            // 原来只换了 primaryRunId，state 还挂着刚结束这个任务的「已返回结果」，
            // 而接手的新主任务在后台期间的事件被 primaryRunId 判断挡掉了，
            // 不重置就会出现「胶囊显示已完成、实际上还在干活」的过期状态（P097）。
            if (primaryRunId != null) {
                state.value = AgentOverlayState.Initial
                capsuleExpanded.value = false
                setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
                ensureOverlayVisible()
                return@post
            }
            lastCompletedRunContext = completedContext
            runCatching {
                if (result.ok) {
                    enterFinalState(
                        state.value.copy(
                            phase = AgentOverlayPhase.FINISHED,
                            status = AgentOverlayStatus.ResultReady,
                            detailText = result.content.trim().ifBlank { state.value.detailText },
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                } else {
                    enterFinalState(
                        AgentOverlayState(
                            phase = AgentOverlayPhase.FAILED,
                            status = if (result.error == "已停止") {
                                AgentOverlayStatus.Stopped
                            } else {
                                AgentOverlayStatus.RunFailed
                            },
                            detailText = result.error.orEmpty(),
                        ),
                        keepVisible = entrySurfaceGuard?.wasTriggered == true,
                    )
                }
            }.onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_terminal_overlay_failed") {
                    "Agent runtime terminal overlay failed: type=${throwable.safeLogType()}"
                }
            }
        }
    }

    private fun sendEventTo(
        target: Messenger?,
        event: AgentEvent,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_EVENT)
            msg.data = AgentRuntimeWire.eventToBundle(event)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_event_delivery_failed") {
                "Agent runtime event delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun dispatchResultIo(block: () -> Unit) {
        try {
            resultIo.execute {
                try { block() } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("runtime_result_io_failed") {
                        "Agent runtime result I/O failed: type=${failure.safeLogType()}"
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            AndroidAgentLogger.info("Agent runtime result delivery deferred after service stop")
        }
    }

    private fun sendResultTo(target: Messenger?, result: AgentRuntimeWire.RunResult) {
        dispatchResultIo { sendResultNow(target, result) }
    }

    private fun sendResultNow(
        target: Messenger?,
        result: AgentRuntimeWire.RunResult,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_RESULT)
            msg.data = AgentRuntimeWire.toBundle(result, cacheDir)
            AgentWireText.send(target, msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_delivery_failed") {
                "Agent runtime result delivery failed: type=${throwable.safeLogType()}"
            }
            // 不把传输失败伪装成已交付终态；引用使新客户端保留 outbox，等待完整恢复。
            val fallback = AgentRuntimeWire.RunResult(result.runId, false, "",
                "完整结果传输失败，已保存的历史未删除。请重新打开会话恢复。",
                contextSnapshotRef = result.runId, operation = result.operation)
            try {
                target?.send(Message.obtain(null, AgentRuntimeWire.MSG_RESULT).apply {
                    data = AgentRuntimeWire.toBundle(fallback)
                })
            } catch (deliveryFailure: Exception) {
                AndroidAgentLogger.warnThrottled("runtime_result_failure_notice_undelivered") {
                    "Agent runtime result notice undelivered: type=${deliveryFailure.safeLogType()}"
                }
            }
        }
    }

    private fun sendRequestIngestedTo(
        target: Messenger?,
        runId: String,
    ) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_REQUEST_INGESTED)
            msg.data = AgentRuntimeWire.ackBundle(runId)
            target?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_ingest_ack_failed") {
                "Agent runtime ingest acknowledgement failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendDrainedResults(replyTo: Messenger?, referencesOnly: Boolean) {
        dispatchResultIo { sendDrainedResultsNow(replyTo, referencesOnly) }
    }

    private fun sendDrainedResultsNow(replyTo: Messenger?, referencesOnly: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE)
            msg.data = AgentRuntimeWire.completedRunsToBundle(
                if (referencesOnly) AgentRuntimeResultStore.pendingPage(this) else AgentRuntimeResultStore.list(this).take(8)
            )
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_drain_results_failed") {
                "Agent runtime drain results failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun sendActiveRun(replyTo: Messenger?) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_QUERY_ACTIVE_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.ackBundle(primaryRunId.orEmpty())
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_active_run_delivery_failed") {
                "Agent runtime active run delivery failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun attachRun(runId: String, replyTo: Messenger?) {
        // 按 runId 精确取：并发后同时可能有多个 run，不能再用「那唯一的一个」。
        val session = activeSessions[runId]
        val attached = replyTo != null &&
            runId.isNotBlank() &&
            session?.runId == runId &&
            session.attach(
                eventSink = { event -> sendEventTo(replyTo, event) },
                resultSink = { result -> sendResultTo(replyTo, result) },
                onReplayComplete = { sendAttachRunResponse(runId, replyTo, attached = true) },
            )
        if (!attached) sendAttachRunResponse(runId, replyTo, attached = false)
    }

    private fun sendAttachRunResponse(runId: String, replyTo: Messenger?, attached: Boolean) {
        runCatching {
            val msg = Message.obtain(null, AgentRuntimeWire.MSG_ATTACH_RUN_RESPONSE)
            msg.data = AgentRuntimeWire.attachRunResponseBundle(runId, attached)
            replyTo?.send(msg)
        }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_attach_run_delivery_failed") {
                "Agent runtime attach response failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun persistCompletedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult
    ) {
        val handoff = request.handoff ?: return
        AgentRuntimeResultStore.add(
            this,
            AgentRuntimeWire.CompletedRun(
                handoff = handoff,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    private fun persistArchivedRun(
        request: AgentRuntimeWire.RunRequest,
        result: AgentRuntimeWire.RunResult,
        events: List<AgentEvent>
    ) {
        val handoff = request.handoff ?: return
        AgentExternalArchivePayload.from(handoff.payload) ?: return
        val userImagePreviews = if (
            handoff.source == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
        ) {
            request.images
                .asSequence()
                .take(MAX_ARCHIVED_USER_IMAGE_PREVIEWS)
                .mapNotNull { image ->
                    AgentImageCodec.previewFromReference(this, image)?.reference
                }
                .toList()
        } else {
            emptyList()
        }
        AgentRunArchiveStore.add(
            this,
            AgentRunArchiveStore.ArchivedRun(
                handoff = handoff,
                events = events,
                result = result,
                createdAt = System.currentTimeMillis(),
                userImagePreviews = userImagePreviews,
            )
        )
    }

    private fun finishWithFailure(
        message: String,
        replyTo: Messenger? = null,
    ) {
        sendResultTo(
            replyTo,
            AgentRuntimeWire.RunResult(runId = "", ok = false, content = "", error = message),
        )
        // 并发后不能用「有没有在跑的任务」来决定是否进失败浮层：
        // 只要还有任务在跑，就不该把浮层切到失败态（那是别人的界面）。
        if (activeSessions.isNotEmpty()) return
        enterFinalState(
            AgentOverlayState(
                phase = AgentOverlayPhase.FAILED,
                status = AgentOverlayStatus.RunFailed,
                detailText = message
            )
        )
    }

    private fun requestStop() {
        // 浮层上的停止按钮只作用于主任务。
        val session = primarySession()
        if (session == null) {
            dismissAndStop()
            return
        }
        cancelRun(session.runId)
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        pendingStartRequest?.takeIf { pending -> pending.incoming.request.runId == runId }?.let { pending ->
            startRequestGeneration++
            pendingStartRequest = null
            pending.incoming.close()
            sendRequestIngestedTo(pending.replyTo, runId)
            sendResultTo(
                pending.replyTo,
                AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = "已停止",
                ),
            )
            return
        }
        // 按 runId 找目标任务：并发后不能只认「那个唯一的」。
        // UI 传来的 runId 可能属于任意一条对话，不一定当前是主任务。
        val session = activeSessions[runId] ?: return
        if (!session.isTerminal) {
            session.controller.cancel()
            // 只有取消的是主任务时才改浮层状态，取消后台任务不该动别人的界面。
            if (primaryRunId == runId) {
                state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
            }
        }
    }

    private fun requestPause() {
        // 暂停/恢复是浮层上的按钮，只作用于主任务。
        primarySession()?.controller?.pause()
        state.value = state.value.copy(
            phase = AgentOverlayPhase.PAUSED,
            status = AgentOverlayStatus.Paused,
        )
    }

    private fun requestResume() {
        primarySession()?.controller?.resume()
        state.value = state.value.copy(
            phase = AgentOverlayPhase.RUNNING,
            status = AgentOverlayStatus.Continuing,
        )
    }

    private fun requestSupplement(text: String) {
        val supplementText = text.trim()
        if (supplementText.isBlank()) return
        setCapsuleInputMode(focusable = false)
        primarySession()?.let { session ->
            val event = session.steer(supplementText) {
                recordSupplementEvent(supplementText)
            }
            if (event == null) {
                if (!session.isTerminal) {
                    state.value = state.value.copy(
                        status = AgentOverlayStatus.Finishing,
                    )
                    return
                }
            } else {
                AndroidAgentLogger.info(
                    "Agent runtime supplement received: index=${event.index}, chars=${event.text.length}"
                )
                state.value = state.value.applyEvent(event)
                return
            }
        }

        val completed = lastCompletedRunContext ?: return
        if (completed.request.operation != AgentRuntimeWire.OP_CHAT ||
            completed.request.handoff?.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) {
            state.value = state.value.copy(status = AgentOverlayStatus.ContinuationUnavailable)
            return
        }
        val continuationRequest = AgentContinuationBuilder.build(
            request = completed.request,
            response = completed.response,
            supplement = supplementText,
        )
        startRun(continuationRequest)
    }

    private fun recordSupplementEvent(text: String): AgentEvent.UserSupplementReceived {
        val supplement = synchronized(supplementsLock) {
            AgentUiHandoffPayload.Supplement(
                index = nextSupplementIndex++,
                text = text,
                createdAt = System.currentTimeMillis(),
            ).also { activeSupplements += it }
        }
        return AgentEvent.UserSupplementReceived(
            index = supplement.index,
            text = supplement.text,
        )
    }

    private fun ensureOverlayVisible() {
        showOverlay()
    }

    private fun showOverlay() {
        if (capsuleView != null) return
        // TYPE_ACCESSIBILITY_OVERLAY 免 SYSTEM_ALERT_WINDOW 权限；仅回退态（无障碍未启用）才需检查
        if (AgentAccessibilityService.current() == null && !Settings.canDrawOverlays(this)) return
        val wm = overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        // ── 氛围光窗口：全屏触摸穿透，彩虹光圈，截图时被 takeScreenshotOfWindow 过滤 ─
        val glow = createOverlayComposeView {
            AgentOverlayGlow(state = state.value, corners = screenCornerRadii())
        }
        val glowLp = glowLayoutParams()
        runCatching { wm.addView(glow, glowLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_glow_add_view_failed") {
                "Agent runtime glow addView failed: type=${throwable.safeLogType()}"
            }
        }
        glowView = glow
        glowParams = glowLp

        // ── 状态胶囊窗口：状态栏下方居中，收起时一句状态，点击展开控制 ─────
        val capsule = createOverlayComposeView {
            AgentOverlayCapsule(
                state = state.value,
                expanded = capsuleExpanded.value,
                yielded = capsuleYielded.value,
                otherRunCount = otherRunCountState.value,
                onToggleExpanded = ::toggleCapsule,
                onCollapsedSettled = ::onCapsuleCollapsedSettled,
                onPause = ::requestPause,
                onResume = ::requestResume,
                onStop = ::requestStop,
                onSupplementModeChange = ::setCapsuleInputMode,
                onSupplement = ::requestSupplement,
            )
        }
        val capsuleLp = capsuleLayoutParams()
        runCatching { wm.addView(capsule, capsuleLp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_add_view_failed") {
                "Agent runtime capsule addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        capsuleView = capsule
        capsuleParams = capsuleLp
    }

    /**
     * 让路时同步切换触摸穿透：标志位立刻生效保证手势不被截走，视觉过渡交给胶囊内的动画。
     * 恢复时先淡入再收回触摸，两者顺序无关紧要，因为恢复发生在手势结束之后。
     */
    private fun setCapsuleYielded(yielded: Boolean) {
        if (!yielded) mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = yielded
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (yielded) {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_clearance_failed") {
                "Agent runtime capsule clearance update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun toggleCapsule() {
        val expand = !capsuleExpanded.value
        // 先把窗口扩到展开档再开始动画；收起由胶囊在动画结束后回调 onCollapsedSettled 缩窗。
        if (expand) setCapsuleWindowHeight(CAPSULE_CONTROLS_HEIGHT_DP)
        capsuleExpanded.value = expand
    }

    private fun onCapsuleCollapsedSettled() {
        if (!capsuleExpanded.value) setCapsuleWindowHeight(CAPSULE_COLLAPSED_HEIGHT_DP)
    }

    private fun setCapsuleWindowHeight(heightDp: Int) {
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val height = dpToPx(heightDp)
        if (lp.height == height) return
        lp.height = height
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_resize_failed") {
                "Agent runtime capsule resize failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun showResultCard(wm: WindowManager) {
        if (resultCardView != null) return
        val card = createOverlayComposeView {
            AgentResultCard(
                state = state.value,
                onClose = ::dismissAndStop,
                onOpenEta = ::openEtaFromResult,
            )
        }
        val lp = resultCardLayoutParams()
        runCatching { wm.addView(card, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_result_card_add_view_failed") {
                "Agent runtime result card addView failed: type=${throwable.safeLogType()}"
            }
            return
        }
        resultCardView = card
        resultCardParams = lp
        registerResultCardBack(card)
        card.requestFocus()
    }

    /**
     * 结果卡片是任务结束后的停留界面，系统返回应先关闭它，而不是退回底下的应用。
     * 窗口需可获焦才能收到返回；卡片外仍是 FLAG_NOT_TOUCH_MODAL，触摸照常穿透到下层。
     */
    /**
     * 回到 Eta 本体并定位到本次任务的会话。卡片窗口可见时属于前台可见的用户操作，
     * 允许从服务启动 Activity；启动失败时保留卡片，不让用户丢失结果。
     */
    private fun openEtaFromResult() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply {
                val conversationId = resultConversationId
                val archive = resultArchive
                when {
                    conversationId != null -> {
                        action = MainActivity.ACTION_OPEN_CONVERSATION_ID
                        putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
                    }
                    archive != null -> {
                        action = MainActivity.ACTION_OPEN_ARCHIVED_CONVERSATION
                        putExtra(MainActivity.EXTRA_HANDOFF_SOURCE, archive.first)
                        putExtra(MainActivity.EXTRA_CONVERSATION_KEY, archive.second)
                    }
                }
            }
        val options = ActivityOptions.makeBasic().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                pendingIntentBackgroundActivityStartMode = if (Build.VERSION.SDK_INT >= 36) {
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE
                } else {
                    @Suppress("DEPRECATION")
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }
            }
        }
        val launched = runCatching {
            PendingIntent.getActivity(
                this, RESULT_OPEN_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ).send(this, 0, null, null, null, null, options.toBundle())
        }
        if (launched.isFailure) {
            AndroidAgentLogger.warn("Agent runtime result open failed: type=${launched.exceptionOrNull()?.safeLogType()}")
            return
        }
        dismissAndStop()
    }

    private fun registerResultCardBack(view: View) {
        unregisterResultCardBack()
        val dispatcher = view.findOnBackInvokedDispatcher() ?: run {
            AndroidAgentLogger.warn("Agent runtime result card back dispatcher unavailable")
            return
        }
        val callback = OnBackInvokedCallback(::dismissAndStop)
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        resultCardBack = dispatcher to callback
    }

    private fun unregisterResultCardBack() {
        val (dispatcher, callback) = resultCardBack ?: return
        resultCardBack = null
        runCatching { dispatcher.unregisterOnBackInvokedCallback(callback) }
    }

    private fun createOverlayComposeView(content: @Composable () -> Unit): ComposeView =
        ComposeView(overlayContext()).apply {
            setViewTreeLifecycleOwner(this@AgentRuntimeService)
            setViewTreeSavedStateRegistryOwner(this@AgentRuntimeService)
            setContent {
                MiuixTheme(colors = if (isNightMode()) darkColorScheme() else lightColorScheme()) {
                    // 部分 ROM 会给 TYPE_ACCESSIBILITY_OVERLAY 分配软件 Canvas；Miuix 的
                    // RuntimeShader 只检查系统版本，因此系统浮层统一使用其普通圆角回退。
                    CompositionLocalProvider(LocalSquircleEnabled provides false) {
                        content()
                    }
                }
            }
        }

    /**
     * 胶囊窗口使用固定宽度与分档高度，动画只在窗口内进行。
     * WRAP_CONTENT 会让每一帧的尺寸变化都触发 WindowManager 重新布局整个窗口，这是卡顿的来源；
     * 但窗口内透明区域同样会吞掉触摸（包括 Agent 自己注入的点击），所以高度只在需要时扩大：
     * 展开前先扩到目标档，收起动画结束后再缩回，见 [setCapsuleWindowHeight]。
     */
    private fun capsuleLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            dpToPx(CAPSULE_WINDOW_WIDTH_DP),
            dpToPx(CAPSULE_COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 贴在状态栏下方居中：远离多数应用的主要点击区，也和系统胶囊、流体云的位置习惯一致。
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeightPx()
            windowAnimations = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

    private fun statusBarHeightPx(): Int {
        val metrics = (overlayContext().getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.currentWindowMetrics ?: return dpToPx(28)
        return metrics.windowInsets
            .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.displayCutout())
            .top
            .takeIf { it > 0 } ?: dpToPx(28)
    }

    private fun resultCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            resultCardWindowHeightPx(),
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 半屏底部居中，窗口外触摸穿透
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

    private fun overlayType(): Int =
        // 无障碍服务可用时用 TYPE_ACCESSIBILITY_OVERLAY（免 SYSTEM_ALERT_WINDOW 权限，且截图
        // filterValidWindows 可过滤）；需用无障碍服务 context 创建，否则 BadTokenException
        if (AgentAccessibilityService.current() != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun overlayContext(): Context =
        AgentAccessibilityService.current() ?: this

    @Suppress("DEPRECATION")
    private fun glowLayoutParams(): WindowManager.LayoutParams {
        // 真实屏幕高度（含状态栏 + 导航栏），MATCH_PARENT 在部分设备不含系统栏
        val realHeight = runCatching {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealSize(point)
            point.y
        }.getOrDefault(WindowManager.LayoutParams.MATCH_PARENT)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            realHeight,
            overlayType(),
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 全屏覆盖（含状态栏/导航栏），触摸穿透不拦截页面操作；
            // TYPE_ACCESSIBILITY_OVERLAY 让 takeScreenshotOfWindow 过滤掉，对 Agent 透明
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // 非应用窗口默认按系统栏与挖孔收缩窗口框，光带画出的圆角就会整体下移，和物理屏幕角错位。
            // 显式不避让任何 inset，窗口原点才与屏幕原点重合，Display 上报的圆角半径才对得上。
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    /**
     * 屏幕四角的物理圆角半径。光效窗口覆盖整屏，直接取 Display 上报的值；
     * 部分 ROM 不上报时用 0，兜底按常见大圆角机型估一个保守值，宁可略小也不让光带越出屏幕。
     */
    private fun screenCornerRadii(): ScreenCornerRadii {
        val display = (overlayContext().getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager)
            ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val fallback = dpToPx(FALLBACK_SCREEN_CORNER_DP).toFloat()
        fun radius(position: Int): Float =
            display?.getRoundedCorner(position)?.radius?.takeIf { it > 0 }?.toFloat() ?: fallback
        return ScreenCornerRadii(
            topLeft = radius(android.view.RoundedCorner.POSITION_TOP_LEFT),
            topRight = radius(android.view.RoundedCorner.POSITION_TOP_RIGHT),
            bottomRight = radius(android.view.RoundedCorner.POSITION_BOTTOM_RIGHT),
            bottomLeft = radius(android.view.RoundedCorner.POSITION_BOTTOM_LEFT),
        )
    }

    private fun setCapsuleInputMode(focusable: Boolean) {
        setCapsuleWindowHeight(
            if (focusable) CAPSULE_SUPPLEMENT_HEIGHT_DP
            else if (capsuleExpanded.value) CAPSULE_CONTROLS_HEIGHT_DP
            else CAPSULE_COLLAPSED_HEIGHT_DP,
        )
        val wm = windowManager ?: return
        val capsule = capsuleView ?: return
        val lp = capsuleParams ?: return
        val nextFlags = if (focusable) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (lp.flags == nextFlags) return
        lp.flags = nextFlags
        runCatching { wm.updateViewLayout(capsule, lp) }.onFailure { throwable ->
            AndroidAgentLogger.warnThrottled("runtime_capsule_focus_update_failed") {
                "Agent runtime capsule focus update failed: type=${throwable.safeLogType()}"
            }
        }
    }

    private fun resultCardWindowHeightPx(): Int =
        (resources.displayMetrics.heightPixels * RESULT_CARD_HEIGHT_RATIO).toInt()

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun enterFinalState(finalState: AgentOverlayState, keepVisible: Boolean = false) {
        state.value = finalState

        if (hasExecutedForegroundTool) {
            // 撤掉胶囊和边缘光，改显半屏结果卡片，不自动关闭，用户手动关闭
            capsuleExpanded.value = false
            removeAmbientWindows()
            windowManager?.let(::showResultCard)
            mainHandler.removeCallbacksAndMessages(hideToken)
        } else {
            dismissAndStop()
        }
    }

    private fun removeAmbientWindows() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView = null
        glowView = null
        capsuleParams = null
        glowParams = null
    }

    /**
     * 只撤掉结果卡片，保留胶囊/边缘光与服务本身。
     *
     * 与 [dismissAndStop] 的区别：那个是「任务全结束，收摊走人」（含 `stopSelf()`）；
     * 这个用于「上一个任务的结果卡还挂着，但下一个任务已经接管浮层」——
     * 卡片上的关闭键会走 `dismissAndStop()`，留着它会把新任务一起关掉（P097）。
     */
    private fun removeResultCard() {
        if (resultCardView == null) return
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        resultCardParams = null
    }

    private fun dismissAndStop() {
        mainHandler.removeCallbacks(restoreCapsule)
        capsuleYielded.value = false
        unregisterResultCardBack()
        resultCardView?.let { view -> runCatching { windowManager?.removeView(view) } }
        capsuleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        glowView?.let { view -> runCatching { windowManager?.removeView(view) } }
        resultCardView = null
        capsuleView = null
        glowView = null
        resultCardParams = null
        capsuleParams = null
        glowParams = null
        windowManager = null
        stopSelf()
    }

    private fun isMessageSenderAllowed(msg: Message): Boolean {
        val uid = msg.sendingUid
        if (uid == Process.myUid()) return true
        val packages = runCatching {
            packageManager.getPackagesForUid(uid)
        }.getOrNull().orEmpty()
        return packages.any { it in ModuleConfig.AGENT_RUNTIME_ENTRY_PACKAGES }
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return mode == Configuration.UI_MODE_NIGHT_YES
    }

    private fun currentRuntimePermissions(): AgentRuntimePolicy.Permissions =
        AgentRuntimePolicy.permissions(
            Prefs.localAgentPreferences()
        )

    private fun AgentRuntimeWire.RunRequest.withActiveSupplements(): AgentRuntimeWire.RunRequest {
        val handoff = handoff ?: return this
        if (handoff.source != AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE) return this
        val supplements = synchronized(supplementsLock) { activeSupplements.toList() }
        if (supplements.isEmpty()) return this
        val payload = AgentUiHandoffPayload.from(handoff.payload).copy(
            supplements = supplements,
        )
        return copy(
            handoff = handoff.copy(payload = payload.toJson())
        )
    }

    private companion object {
        const val ACTION_KEEP_ALIVE = "io.github.mangi.eta.agent.runtime.KEEP_ALIVE"
        const val HIDE_DELAY_MS = 2_500L
        const val RESULT_REVIEW_DELAY_MS = 120_000L
        const val RESULT_CARD_HEIGHT_RATIO = 0.5f
        private const val RESULT_OPEN_REQUEST_CODE = 1108
        private const val CAPSULE_WINDOW_WIDTH_DP = 272
        private const val CAPSULE_COLLAPSED_HEIGHT_DP = 68
        private const val CAPSULE_CONTROLS_HEIGHT_DP = 124
        private const val CAPSULE_SUPPLEMENT_HEIGHT_DP = 260
        /** 注入点击只是一个点，目标控件常贴着胶囊边缘，判定范围向外多留一点。 */
        private const val CAPSULE_CLEARANCE_SLOP_DP = 8
        private const val FALLBACK_SCREEN_CORNER_DP = 28
        const val MAX_ARCHIVED_USER_IMAGE_PREVIEWS = 4
    }

    private data class CompletedRunContext(
        val request: AgentRuntimeWire.RunRequest,
        val response: AgentModelClient.ModelResponse.Text,
    )

    private class RuntimeConfigUnavailableException : IllegalStateException()
}
