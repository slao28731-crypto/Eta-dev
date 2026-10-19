# Eta 多并发 Agent · 受影响功能清单

> 生成日期：2026-10-19
> 对应改动：把「同一时刻只能跑一个 Agent」改成「多个对话可并发执行」
> 用途：你以后要动这些功能时，先看这份，知道会牵连什么

---

## 零、一句话背景

改动前，`AgentRuntimeService` 里只有**一个** `activeSession` 字段：

```kotlin
// AgentRuntimeService.kt:86
@Volatile
private var activeSession: AgentRuntimeSession? = null
```

而 `startRun()` 的第一行就是：

```kotlin
// AgentRuntimeService.kt:369
activeSession?.controller?.cancel()   // 开新任务先把旧任务杀掉
```

**所以你在新对话里发消息，等于直接掐死上一条对话正在跑的任务。**

这就是你问的那个现象的根因。

---

## 一、必须改的核心（第一批）

### 1.1 AgentRuntimeService 的会话容器

| 项 | 值 |
|---|---|
| 文件 | `agent/runtime/AgentRuntimeService.kt` |
| 总行数 | **1304 行** |
| `activeSession` 出现次数 | **20 处** |
| 风险等级 | **极高**（Runtime 核心） |

**要改的 4 处致命点**

| 行号 | 原代码 | 改后 | 为什么 |
|---|---|---|---|
| 369 | `activeSession?.controller?.cancel()` | 删除（不再无条件杀旧） | 这是「互相顶掉」的直接原因 |
| 468 / 478 / 529 | `if (activeSession !== session) return` | `if (activeSessions[session.runId] !== session) return` | 否则旧任务的事件会被丢弃 |
| 531 | `activeSession = null` | `activeSessions.remove(runId)` | 否则一个跑完全家清空 |
| 750 | `if (activeSession != null) return` | 移除该守卫 | 否则拒绝第二个任务 |

**建议改法**：

```kotlin
private val activeSessions = ConcurrentHashMap<String, AgentRuntimeSession>()
```

**动它之前必须测的**：
- 一个任务跑完，另一个还在跑 → 另一个不能受影响
- 两个都跑完 → `activeSessions` 必须为空（否则内存泄漏）

---

### 1.2 attachRun 只认唯一那个 session

| 项 | 值 |
|---|---|
| 文件 | 同 1.1，第 670-681 行 |
| 风险 | 中 |

```kotlin
// 现在
val session = activeSession
val attached = ... && session?.runId == runId && ...

// 改成
val session = activeSessions[runId]
```

**影响的功能**：App 被杀掉重开后「恢复正在跑的任务」（`AgentPendingResultRecovery`）。
**改错了会怎样**：重开 App 后看不到正在跑的任务，或者看到错的任务。

---

### 1.3 UI 侧 currentRunId 是单值

| 项 | 值 |
|---|---|
| 文件 | `ui/app/AgentAppState.kt` |
| 行号 | 第 99 行声明，另有多处赋值（491 / 514 / 523 / 1209 / 1347 / 1661） |
| 风险 | **极高**（2000+ 行的大管家） |

```kotlin
// 现在
private var currentRunId: String? = null
```

**改法**：`private val currentRunIds = mutableSetOf<String>()`，
或者干脆只用现有的 `runConversationIds: Map<String, String>`（runId → conversationId），它本来就能反查。

**受影响的函数**（逐个都要看）：

| 函数 | 依赖方式 |
|---|---|
| `stopCurrentRun()` | 拿 `currentRunId` 去 cancel |
| `applyRunResult()` | 判断 `runId == currentRunId` 才清空 |
| `sendQueuedMessageNow()` | 读 `currentRunId` 时读到的可能不是目标会话的 |
| `launchConversationRun()` | 设置 `currentRunId = runId` |

**改错了会怎样**：A 对话的回复显示到 B 对话里；或者点了停止停错任务。

---

### 1.4 Wire 协议（好消息：几乎不用改）

| 项 | 值 |
|---|---|
| 文件 | `agent/runtime/AgentRuntimeWire.kt` |
| 现状 | `runId` 在协议里出现 **15 次**，消息本来就带 runId |

`MSG_EVENT` / `MSG_RESULT` / `MSG_CANCEL` / `MSG_ATTACH_RUN` **全部已按 runId 设计**。
**这一层基本不用动，只需要 Service 侧按 runId 路由。**

---

## 二、全局单例资源（第二批，真难点）

### 2.1 无障碍服务 —— 手机上只有一个

| 项 | 值 |
|---|---|
| 文件 | `agent/accessibility/AgentAccessibilityService.kt` |
| 本质 | Android 系统只允许**一个**无障碍服务实例 |
| 风险 | **极高** |

**冲突场景**：
- Agent A 想点「确定」，Agent B 想点「取消」
- 两者拿到的是**同一棵**无障碍树、**同一个**屏幕坐标
- 结果：A 的点击可能落在 B 刚改过的界面上，两边都做错

**结论：GUI 操作只能串行。加锁 = 串行，这是物理约束，绕不过。**

**要改的地方**：所有 `accessibility` 相关的 tool 调用前，加一个**全局互斥队列**。

**受影响的工具**（全部 GUI 类）：
- 点击、长按、滑动
- 输入文字
- 读取界面结构 / 截图
- 查找控件

**改错了会怎样**：两个 agent 互踩，点击错乱，甚至误操作（比如该点取消却点了确定）。

---

### 2.2 终端 / PTY —— 共享同一个工作区

| 项 | 值 |
|---|---|
| 文件 | `agent/terminal/TerminalRuntime.kt` |
| 本质 | `internal object`（全局单例） |
| 风险 | 中高 |

```kotlin
internal object TerminalRuntime {
    const val ROOT_WORKSPACE_PATH = "/data/local/tmp/eta"
    const val LINUX_WORKSPACE_PATH = "/workspace"
    private var backgroundJobs: BackgroundTerminalJobs? = null   // 单个
}
```

**冲突场景**：
- A：`cd /workspace && rm -rf build`
- B：`cd /workspace/build && make`

**当前决定：共享同一个工作区**（你选的方案 A）

| 好处 | 风险 |
|---|---|
| 两个 agent 能看到同一份文件，可以协作 | A 删了 B 要用的文件 |

**如果以后想改成隔离**：改 `TerminalRuntime.userWorkspacePath`，按 conversationId 拼接子目录。
代价：两个 agent 看不见对方的文件，「帮我改刚才那个」会失效。

**受影响的功能**：
- 所有 `terminal` / `shell` 工具
- 后台命令（`BackgroundTerminalJobs`）
- 文件读写工具（`FileToolDispatcher` 等）

---

### 2.3 浏览器会话 —— 全 App 一个 WebView

| 项 | 值 |
|---|---|
| 文件 | `agent/browser/AgentBrowserSession.kt` |
| 本质 | `internal object`（全局单例） |
| 风险 | 中 |

```kotlin
internal object AgentBrowserSession {
    val snapshots: StateFlow<BrowserSessionSnapshot>
}
```

**两个 agent 同时开网页 → 抢同一个 WebView**，后开的覆盖前面的页面。

**结论：浏览器操作也要串行化**（跟 GUI 一样排队）。

**受影响的功能**：所有 `browser_use` 工具、Kimi Web、内嵌浏览器面板。

---

### 2.4 Frida —— 固定端口 + 固定路径

| 项 | 值 |
|---|---|
| 文件 | `agent/terminal/FridaInstaller.kt` |
| 固定值 | `RUNTIME_DIR = "/data/local/tmp/eta-frida"`、端口 **8899** |
| 风险 | 低 |

**两个 agent 同时用 Frida** → 端口占用冲突。

**改法**：端口从 8899 起动态分配，或者加互斥。
**受影响的功能**：Frida 启动/停止/attach 全部。

---

### 2.5 AgentExecutionService 的 lease（好消息）

| 项 | 值 |
|---|---|
| 文件 | `agent/runtime/AgentExecutionService.kt` |
| 现状 | 已经是按字符串 id 分配的多 lease |

```kotlin
fun acquire(context, id, allowBoundFallback, onStop)   // id 是键
```

**这一层不用改**，天然支持多个任务同时持有 lease。

---

## 三、界面与通知（第三批）

### 3.1 Overlay 胶囊 / 状态显示

| 项 | 值 |
|---|---|
| 文件 | `AgentRuntimeService.kt` 内 `state.value`，共 **14 处** |
| 风险 | 中 |

现在只有一个全局 `state.value`（胶囊显示「正在执行…」）。

**并发后的问题**：两个任务都在跑，胶囊显示谁的？

**改法**：胶囊显示「2 个任务执行中」，点开能看列表。
**受影响的功能**：悬浮胶囊、状态栏通知、`AgentOverlayState`。

---

### 3.2 通知栏

| 项 | 值 |
|---|---|
| 文件 | `AgentExecutionService` 的通知、`BackgroundCommandNotification` |
| 风险 | 中 |

**并发后**：需要显示多个任务，或者合成一条「2 个任务」。

---

### 3.3 会话列表

**需要新增**：哪个对话正在跑的小标记（否则你不知道另一条还在跑）。

---

## 四、工具调用层

### 4.1 AgentLocalTools

| 项 | 值 |
|---|---|
| 文件 | `agent/tool/AgentLocalTools.kt` |
| 风险 | 中 |

工具执行时需要知道「我是哪个 run 在调」——因为要判断该不该排队（GUI 类要排队）。

**改法**：工具调用上下文带上 runId。

---

## 五、分批实施计划

| 批次 | 内容 | 你能验证什么 |
|---|---|---|
| **第 1 批** | Service 改 Map + UI 多 run + 事件按 runId 分流 | 两个对话能**同时跑终端命令**（GUI 还没弄） |
| **第 2 批** | GUI / 浏览器 / Frida 串行化 | 两个 agent 都点屏幕时**不打架** |
| **第 3 批** | overlay / 通知栏显示多任务 | 界面能看到「2 个在跑」 |

**每批都要装一次、验一次，不通过就停下修。**

---

## 六、并发能力的真实边界

### 能真正并发的

- 终端命令（共享工作区，各自独立进程）
- 文件读写（只要不是同一个文件）
- 网络请求
- 模型推理（本来就在云端）
- 装包、编译、下载

### 只能串行的

- **任何点屏幕的操作**（无障碍，物理限制）
- **任何用浏览器的操作**（单个 WebView）
- **任何用 Frida 的操作**（单端口）

**说白了**：如果你让两个 agent 都去点界面，实际上还是排队。
只有「一个跑命令 + 一个回答问题」这种才有真并发。

---

## 七、改坏了会出什么症状（排查用）

| 症状 | 大概率是哪一块 |
|---|---|
| 一条对话跑完，另一条也停了 | Service 的 `activeSessions.remove` 位置错，或 `cancel()` 没删干净 |
| A 的回复出现在 B 的对话框里 | UI 侧 `currentRunId` 还是单值，事件路由错 |
| 两个 agent 点击错乱 | 无障碍没加排队 |
| 浏览器页面被覆盖 | 浏览器没加排队 |
| Frida 启动失败 | 端口冲突 |
| 任务跑完但胶囊一直转 | overlay `state.value` 没按 run 计数 |
| App 重开后看不到正在跑的任务 | `attachRun` 没改成按 runId 查 |

---

## 八、改动前必读

1. **先备份**：`bash scripts/backup-app-data.sh`（改序列化/数据库时必须）
2. **走分支**：`git checkout -b feature/concurrent-runs`
3. **一次一批**：三批分开提交，每批可单独 revert
4. **每批都要真机验**：这个改动没法靠编译通过就认为成功

---

## 九、相关文档索引

| 文件 | 内容 |
|---|---|
| `docs/PITFALLS.md` | 踩坑记录（本次改动追加 P096） |
| `docs/STATE.md` | 当前进度 |
| `docs/MODULES.md` | 各模块职责 |
| `AGENTS.md` | 架构与红线 |

---

_本文件由 Agent 生成，记录并发化改动的完整影响面。改动任何一条相关内容前，先读这里。_
