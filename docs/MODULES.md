# 模块清单

> 每条写：**干什么 · 依赖谁 · 对外提供什么 · 改它的注意事项**。
> 不写"这个文件实现了 XXX 功能"这种从名字就能猜到的内容。

## agent/ — 51,543 行 / 280 文件

### agent/model/ — Agent Loop 与 Provider（50 文件，边最密）

| 文件/类 | 职责 | 关键注意事项 |
|---|---|---|
| `AgentModelClient.kt` | 模型请求中枢 | **243 条边，全项目枢纽**。三条 SSE 解析路径（Chat Completions / Responses / Anthropic）不可合并 |
| `AgentLoop.kt` | 单轮循环编排 | 与 `AgentRunController` 强耦合，改循环要同步改取消/steering 逻辑 |
| `AgentProvider.kt` | Provider 抽象与 `ProviderRequest` | 新增 Provider 类型要同时改 `AgentModelClient` 的分派 |
| `AgentToolSchema.kt` | 工具 Schema 生成 | Schema 改动直接影响模型行为，改完必须实测 tool call |
| `AgentConversationCodec.kt` | transcript 编解码 | **敏感工具参数不能落盘**，改这里要守住这条线 |
| `AgentContextCompactor.kt` | 上下文压缩 | ⚠️ tree-sitter 解析不全（3 符号），查信息直接读源文件 |
| `AgentToolBatchRecovery.kt` | 工具批次恢复 | ⚠️ 解析不全（2 符号） |
| `AgentTraceFormatter.kt` | 轨迹格式化 | 被 `AgentModelClient` 调用（INFERRED 边） |

### agent/runtime/ — 跨进程运行控制

| 类 | 职责 | 注意 |
|---|---|---|
| `AgentRuntimeService.kt` | 运行时服务端 | 76 条边 |
| `AgentRuntimeWire.kt` | **App ↔ Hook 通信协议** | **100 条边，改协议必须两端同步** |
| `AgentRuntimeClient.kt` | App 侧客户端 | 与 Wire 配对 |
| `AgentRuntimePolicy.kt` | 权限/策略检查 | 执行前重新校验，不用缓存 |
| `AgentRuntimeRunExecutor.kt` | run 执行器 | `.execute()` 是工具执行入口 |
| `AgentExecutionService.kt` | 前台服务（specialUse） | 长任务保活 |

### agent/tool/ — 工具执行层

| 类 | 职责 |
|---|---|
| `AgentLocalTools.kt` | **101 条边**，工具注册与分发总入口 |
| `AgentFileToolCatalog.kt` | 文件工具目录与 Schema |
| 文件工具实现 | 读、写、精确编辑、元数据、分页、路径匹配、文本搜索；单次写入与编辑限 512 KiB |

### agent/accessibility/ — GUI Agent

| 类 | 职责 | 注意 |
|---|---|---|
| `AgentAccessibilityService.kt` | **109 条边** | 无障碍树变化影响全部 GUI 工具 |
| `AgentAccessibilityHealthProvider.kt` | 健康检查 ContentProvider | `authority = io.github.mangi.eta.accessibility.health`，受 `MANAGE_ACCESSIBILITY` 保护 |
| 控件定位/截图 | 按原始尺寸传像素，不缩放不转 JPEG | 截图质量直接影响识别率 |

### agent/terminal/ — 终端

40 个文件，是全项目第二大子包。

- PTY 控制台（默认形态），支持 Claude Code / Codex / Kimi Code 等 TUI
- Alpine / Debian，普通设备用 PRoot，Root 设备可选 chroot
- `terminal`（前台命令）/ `terminal_job`（后台任务，完成后通知）/ `terminal_daemon`（跨任务服务）
- 文件路径在所选 Linux 环境内部解析，**不用 Android 宿主 rootfs 路径**
- 依赖 `libeta_pty.so` / `libproot_exec.so` / `libproot_loader.so`（预编译，改 C 要手动重编）

### agent/voice/ — 语音

38 个文件。

| 类 | 职责 |
|---|---|
| `EtaVoiceInteractionService` | VoiceInteractionService，`:voice` 进程 |
| `EtaVoiceAssistActivity` | ASSIST intent 入口，`showWhenLocked` |
| `EtaAssistantOverlayService` | 浮窗，foregroundServiceType=microphone\|mediaPlayback |
| `EtaRecognitionService` | 语音识别，`:recognition` 进程 |
| `ReadAloudPlaybackService` | 朗读播报 |

### agent/mcp/ — MCP 客户端

**只支持 Streamable HTTP + Bearer Token，不支持 stdio**。工具逐项启用，与本机工具共同参与任务。

**Agent 可以自己管理 MCP 服务器**（用户只需说一句话）。工具实现分两层：

| 层 | 位置 | 职责 |
|---|---|---|
| Schema | `agent/model/AgentMcpToolCatalog.kt` | 6 个管理工具的 JSON Schema，由 `mcpManagement` 开关控制 |
| 执行 | `agent/tool/AgentLocalTools.kt` 的 `mcp*` 函数 | 校验 → `discover()` 探测 → `McpServerRepository` 落库 |

三条硬约束（违反就会静默失效）：

1. **新服务器默认全启用**：`McpServerManager.discover(enableAllDiscoveredTools = true)` 只由 Agent 路径传。默认 `false` 时 `enabledToolNames` 是空集，`activeTools` 恒为 0。
2. **工具名公式单一定义**：`mcpModelToolName()` 在 `McpRunContext.kt`，`AgentLocalTools` 只调用不复制。格式 `mcp_<serverId前8位>_<工具名>_<sha256前8位>`。
3. **管理工具需豁免脱敏**：`AgentSensitiveToolPolicy.mcpManagementTools` 白名单，否则结果被 transcript 替换，`modelTools` 一起丢。

工具在 run 开始时一次性加载（`McpRunSnapshot.load()`），**加完下一轮才可用**（同一对话的下一条消息即可，无需重启 App）。

四条额度约束（改任何一个都要连带检查其余）：

1. **`MAX_RUN_TOOLS = 256`**（`McpRunContext.kt`）：单轮进目录的 MCP 工具**总数，所有服务共享**，不是每服务 256。多个服务按 `sort_order` 依次占用额度，前面占满后面一个也进不来。
2. **`MAX_DISCOVERED_TOOLS = 512`**（`McpHttpClient.kt`）：tools/list 一次最多记录多少。**必须 ≥ `MAX_RUN_TOOLS`**，否则工具连数据库都进不来。
3. **`MAX_LIST_PAGES = 16`**：分页翻页上限，与上一条配套。
4. **`MAX_CATALOG_DESCRIPTION_CHARS = 400`**：拼进模型目录时的描述截断。数据库仍保留完整 2000 字供设置页展示。

截断必须显式：`load()` 用 `take(remaining)` 而不是在 `forEach` 里 `return@forEach`（后者只跳过当前元素、外层继续空转，是"看起来有上限、实际静默丢数据"的来源）。
对外要如实报数：`mcp_add_server` 返回 `registeredToolCount` + `unavailableModelTools`，`mcp_list_servers` 返回 `enabledToolTotal` / `runToolLimit` / `overLimit`。**模型只能调用 `modelTools` 里出现过的名字**，谎报"全部启用"会让它去喊不存在的工具名（`TOOL_NOT_AVAILABLE`）。

### agent/browser/ — 内嵌浏览器

WebView 加载 JS 页面、读正文、操作 DOM、截图。用户可接管同一会话。

### agent/memory/ — 长期记忆

`MEMORY.md` 保存跨对话背景，核心内容按预算加入上下文，其余按需读取。

### agent/skill/ — Skills

按需加载任务方法、参考资料、脚本资源。支持公开 GitHub 仓库安装和本地 ZIP 导入。**安装不会执行脚本或开权限**。

### agent/roleplay/ — 角色卡

兼容酒馆 PNG / JSON 角色卡，支持用户人设、内嵌世界书、独立剧情记忆。角色正文可原位编辑，重新生成只改写回复不重复执行设备动作。

### agent/phone/ — 通讯类工具

`PhoneCommandMain` 在 proguard 里有专门的 keep 规则。

### agent/device/ — 设备 API

闹钟、媒体控制、音量、应用版本/安装信息/权限、系统日志（Root）。设置类操作会读回结果，未确认时明确报告。

### agent/context/ — 个人上下文

通知、应用使用情况、位置。相册、日历、短信、录音、健康摘要、聊天图片等需 Root，部分还需 ROM 支持。

---

## hook/ — 9,584 行 / 35 文件

`ModuleMain.onPackageReady()` 按包名分派，**这就是 hook 的总入口**：

| 目标包 | Hook 类 | 用途 |
|---|---|---|
| `com.heytap.speechassist` | `BreenoHooks`（90 边） | 小布接管（OPPO/OnePlus） |
| `com.miui.voiceassist` | `XiaoAiHooks` | 超级小爱接管（小米） |
| `com.coloros.colordirectservice` | `ColorDirectHooks` | 一圈即搜解锁 |
| `com.google.android.googlequicksearchbox` | `GoogleEligibilityHooks` + `GoogleAppHooks` | Gemini 解锁、Google 系统化 |
| `system` / `com.android.systemui` / `com.miui.home` 等 | `hyperos/`、`system/` | 电源键入口、HyperOS 系统集成 |
| `com.oplus.aimemory` | `aimemory/` | 小布记忆数据检索 |

**共同注意**：类名与方法签名与 ROM 版本强绑定；`scope.list` 必须含目标包；R8 后 `META-INF/xposed/` 三件套不能丢。

---

## core/ — 1,036 行 / 14 文件

虽然只有 14 个文件，但全是关键基础设施。

| 文件 | 职责 | 注意 |
|---|---|---|
| `HookRegistrar.kt` | Hook 注册与安装 | 所有 `*Hooks.install()` 走这里 |
| `HookSupport.kt` | Hook 辅助工具 | |
| `DexKitTargets.kt` / `DexKitTargetCache.kt` | DexKit 目标定位与缓存 | ROM 变化时靠它兜底，**不要简化** |
| `DexKitNativeLibrary.kt` / `DexKitNativeLibraryFile.kt` | DexKit native 库加载 | |
| `ModuleConfig.kt` | 模块配置（RemotePreferences） | UI 侧通过 XposedService 写，Hook 侧用 `getRemotePreferences` 读 |
| `ModuleLogger.kt` | **124 条边**，Hook 侧日志 | 签名被大量 hook 引用，改签名影响面大 |
| `AgentLogger.kt` | App 侧日志 | proguard 里配了 `assumenosideeffects`，release 会剥掉 |
| `LogSafety.kt` / `LogThrottle.kt` | 日志脱敏与限流 | 敏感信息必须过这里 |
| `AssistantContextFlags.kt` | 助手上下文标志 |
| `ColorOsMemoryBridgeProtocol.kt` / `ColorOsMemoryDatabaseFormat.kt` | 小布记忆桥接协议与数据库格式 | 与 `aimemory/` 配套 |
| `HookRegistrar` 相关 | | |

---

## data/ — 6,948 行 / 50 文件

```
db/          Room：会话、消息、Run、Provider、Model、角色卡、记忆、通知历史
datastore/   DataStore Preferences：当前选中模型、开关状态
repository/  Provider / Model / RuntimeConfig / CommunityCatalog 等仓储
             └─ CommunityCatalogRepository 会被 UI 的 ProviderModelsTab 直接调用
provider/    Provider/Model 能力解析（ReasoningCapabilityResolver 等）
```

**必守红线**：改 Entity 必须写 Migration，**不加 `fallbackToDestructiveMigration()`**。用户聊天记录、记忆、Provider 配置全在这里。

---

## ui/ — 35,251 行 / 158 文件

| 目录 | 文件数 | 职责 |
|---|---|---|
| `app/` | 40 | `AgentAppState`（86 边，全局状态中心）、`AgentConversationStore`、主题、导航容器 |
| `components/` | 41 | 可复用组件；`EtaFonts.kt` 图谱无符号 |
| `screens/` | — | 页面级 Composable |
| `pages/` | — | 设置类页面（providers、tools、skills、characters 等） |
| `markdown/` | — | 自建 Markdown 渲染；`MarkdownInline.kt` 解析不全（25/部分） |
| `voice/` | — | 语音相关 UI |
| `navigation/` | — | 路由 |
| `model/` | — | UI 层状态模型（`AgentChatUiState`、`AgentChatMessageUi`） |
| `layout/` | — | 布局辅助 |
| `preview/` | — | Compose Preview |
| `MainActivity.kt` / `SettingsScreen.kt` / `AppearanceSettingsScreen.kt` | | 顶层入口 |

**注意**：`AgentAppState` 是全项目最大状态对象，新增字段会触发大面积重组；`LazyColumn` 必须给稳定 key。

---

## config/ — 204 行 / 2 文件

小配置，无复杂依赖。

## systemizer/ — 251 行 / 1 文件

Google App 系统化（把 Google Quick Search Box 变成系统应用），产物是 `assets/googlequicksearchbox-systemizer.zip`。

---

## 构建配置

| 文件 | 作用 |
|---|---|
| `app/build.gradle.kts` | 模块构建、签名、R8、打包规则、APK 命名 |
| `gradle/libs.versions.toml` | 全部依赖版本（Version Catalog） |
| `gradle.properties` | Gradle/Kotlin 堆内存、AndroidX、配置缓存 |
| `gradle/gradle-daemon-jvm.properties` | Daemon JDK 25 的 toolchain URL |
| `app/proguard-rules.pro` | R8 规则：keep 入口类、剥日志、剥离 Android Log |
| `.github/workflows/build-apk.yml` | 自建 CI：JDK25 + SDK37 + NDK30 + 临时签名 → 两个 APK |