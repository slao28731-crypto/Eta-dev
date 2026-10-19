package io.github.mangi.eta.agent.tool

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import io.github.mangi.eta.core.AndroidAgentLogger

/**
 * 全局独占资源的串行化闸门（并发改造第 2 批）。
 *
 * ## 为什么需要这个东西
 *
 * 第 1 批让多个 run 可以同时跑，但有三类资源在**物理上只有一个**：
 *
 * | 资源 | 为什么只有一个 |
 * |---|---|
 * | 无障碍服务 | Android 系统只允许注册一个 AccessibilityService，拿到的是同一棵树、同一个屏幕坐标 |
 * | 浏览器 WebView | `AgentBrowserSession` 是 object，全 App 共用一个 |
 * | Frida server | 固定端口 8899 + 固定目录 `/data/local/tmp/eta-frida` |
 *
 * 两个 run 同时操作它们会**互相踩**：A 想点「确定」、B 想点「取消」，
 * 但屏幕只有一个，谁后下手谁生效，两边都做错。
 *
 * ## 怎么串
 *
 * 一把按资源分组的公平锁。同一资源同一时刻只允许一个 run 进入，
 * 其他人**阻塞等待**（不失败、不重试），拿到锁后按顺序执行。
 *
 * 为什么可以用阻塞锁：`AgentLocalTools.execute()` 是同步方法，
 * 由各 run 自己的 `agent-runtime` 线程调用（见 `AgentRuntimeService.startRun`
 * 里的 `thread(name = "agent-runtime")`），阻塞一个线程不会卡住主线程，
 * 也不会和别的 run 死锁 —— 锁的获取顺序固定为单把，不存在嵌套。
 *
 * ## 不做什么
 *
 * - **不做超时失败**：等不到锁就一直等，因为「另一个 run 正在点屏幕」是正常状态，
 *   失败会让 agent 误以为工具坏了。真要打断，用户去停那个 run。
 * - **不串行化终端/文件**：那些天然可以并发（各自独立进程），串行化只会白等。
 *   工作区共享的踩踏风险由用户自行承担（第 1 批已确认）。
 */
internal object ExclusiveResourceGate {

    /** 独占资源类别。新增时先确认该工具名真实存在（见文件末尾注释）。 */
    enum class Resource(val label: String) {
        /** 无障碍：点击、滑动、输入、读界面 —— 全部共用同一个 AccessibilityService。 */
        ACCESSIBILITY("无障碍"),

        /** 浏览器：全 App 一个 WebView（`AgentBrowserSession` 是 object）。 */
        BROWSER("浏览器"),
    }

    /**
     * 每个资源一把公平锁。公平模式保证先到先得，避免某个 run 一直抢不到。
     */
    private val locks: Map<Resource, ReentrantLock> = Resource.entries.associateWith {
        ReentrantLock(true)
    }

    /** 当前持锁者（仅用于日志与排障，不参与同步）。 */
    private val holders = java.util.concurrent.ConcurrentHashMap<Resource, String>()

    /** 等待超过这个时长就打一条日志，方便排查「agent 看起来卡住了」。 */
    private const val SLOW_WAIT_LOG_MS = 3_000L

    /**
     * 在该资源上独占执行 [block]。
     *
     * @param runId 调用方 run，仅用于日志归属；同一 run 可重入（同一把锁同一线程）。
     */
    fun <T> withExclusive(resource: Resource, runId: String?, block: () -> T): T {
        val lock = locks.getValue(resource)
        if (lock.isHeldByCurrentThread) return block()

        if (!lock.tryLock(SLOW_WAIT_LOG_MS, TimeUnit.MILLISECONDS)) {
            AndroidAgentLogger.info(
                "exclusive resource busy: ${resource.label} run=${runId.orEmpty()} " +
                    "heldBy=${holders[resource].orEmpty()} — waiting"
            )
            lock.lockInterruptibly()
        }
        val previous = holders.put(resource, runId.orEmpty())
        try {
            return block()
        } finally {
            if (previous != null) holders[resource] = previous else holders.remove(resource)
            lock.unlock()
        }
    }

    /** 某个工具名是否需要独占资源；不需要则返回 null（可并发）。 */
    fun resourceFor(toolName: String): Resource? = when (toolName) {
        // ── 无障碍：所有会读界面或动手指的工具 ──────────────────────
        "observe_screen",
        "tap", "tap_area", "tap_element",
        "long_press", "long_press_element",
        "swipe", "scroll", "scroll_element",
        "type_text", "input_text", "replace_text", "clear_text", "paste_text",
        "set_clipboard", "get_clipboard",
        "press_key",
        "wait_for_text", "wait_for_package",
        "open_system_panel",
        -> Resource.ACCESSIBILITY

        // ── 浏览器：全 App 一个 WebView ────────────────────────────
        // 注意：`web_search` / `fetch_url` **不在此列** —— 它们走
        // AgentWebTools 的 HTTP 传输，不用 WebView，可以并发。
        "browser_use" -> Resource.BROWSER

        else -> null
    }

    /**
     * 注意：不要在这里补「看起来像 GUI」但实际不存在的工具名。
     * `screenshot` 只是 `observe_screen` 的一个参数值（`space`），不是独立工具；
     * `get_ui_tree` / `find_element` 在 Eta 里根本没有这两个工具名。
     * 工具名的唯一事实来源是 `AgentLocalTools.execute()` 里那个 `when` 的分支。
     */
}
