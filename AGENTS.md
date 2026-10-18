# AGENTS.md — Eta（二改版）交接契约

> 本文件是本仓库所有 AI Agent 的**第一入口**。开工前必读，收工前必更新。
> 代码图谱由 Graphify 维护，先查图再读源码。

## 0. 这是什么项目

Eta 是 Android 系统级 AI Agent（上游 `Mangi-11/Eta` v3.3.1，PolyForm Noncommercial 1.0.0）。
当前仓库是**个人二改版**，`applicationId = io.github.mangi.eta`，签名自建。

规模：**543 个 Kotlin 文件 / 105,111 行**，807 个可解析代码文件，11899 个符号节点。

## 1. 开工前：先查图谱，别盲搜

仓库根已有 `graphify-out/graph.json`（11899 节点 / 36925 边 / 436 社区）。
**不要一上来就 grep 或逐个读文件**，先查图：

```bash
# 我要改 X，谁受影响？
graphify affected "AgentModelClient" --depth 2

# A 和 B 怎么连的？
graphify path "BreenoHooks" "AgentModelClient" --undirected

# 这个类到底是什么、连了谁？
graphify explain "AgentRunController"

# 用自然语言问：这段逻辑在哪
graphify query "how does the agent runtime execute tool calls" --budget 3000

# 架构枢纽（最该先理解的类）
graphify god-nodes --top 15
```

代码改动后刷新图谱（**零成本、零 LLM**）：

```bash
graphify update .          # 增量，只重解析改动文件
```

## 2. 架构地图

```
app/src/main/kotlin/io/github/mangi/eta/
├── agent/    51,543 行 / 280 文件   ← 心脏：Agent Runtime、工具、Provider、MCP
│   ├── model/         Agent Loop、Provider 协议、上下文压缩、transcript
│   ├── runtime/       AgentRuntimeService/Wire/Client/Policy（跨进程）
│   ├── tool/          Tool Calling 执行器、Schema、文件工具
│   ├── accessibility/ GUI Agent（无障碍树、控件定位、截图）
│   ├── terminal/      PTY、PRoot/chroot、守护任务
│   ├── voice/         语音助手、识别、播报、浮窗
│   ├── browser/       WebView 内嵌浏览器
│   ├── mcp/           Streamable HTTP MCP 客户端
│   ├── memory/        长期记忆 MEMORY.md
│   ├── skill/         Skills 加载
│   └── roleplay/      角色卡
├── hook/      9,584 行 /  35 文件   ← Xposed：接管厂商助手入口
│   ├── breeno/        小布（OPPO/OnePlus）
│   ├── xiaoai/        超级小爱（小米）
│   ├── colordirect/   一圈即搜（ColorOS）
│   ├── hyperos/       HyperOS 系统入口
│   ├── google/        Gemini 解锁、Google 系统化
│   ├── aimemory/      小布记忆
│   └── system/        通用系统 hook 工具
├── ui/       35,251 行 / 158 文件   ← Compose + Miuix
├── data/      6,948 行 /  50 文件   ← Room + DataStore + Repository
├── core/      1,036 行 /  14 文件   ← HookRegistrar、DexKit、日志
├── config/      204 行 /   2 文件
└── systemizer/  251 行 /   1 文件
```

### 核心枢纽（God Nodes，改之前必看受影响面）

| 类 | 边数 | 职责 | 改它的风险 |
|---|---|---|---|
| `AgentModelClient` | 243 | 模型请求中枢，所有 Provider 协议在此 | **极高**，被 hook/ 与 ui/ 双向依赖 |
| `AgentRunController` | 137 | 单次 run 的生命周期控制 | 高，关系取消/steering/恢复 |
| `ModuleLogger` | 124 | Xposed 侧日志 | 中，签名被大量 hook 引用 |
| `AgentAccessibilityService` | 109 | GUI Agent 主服务 | 高，无障碍树变化影响全部 GUI 工具 |
| `AgentLocalTools` | 101 | 本地工具注册与分发 | 高，工具 Schema 改动影响模型行为 |
| `AgentRuntimeWire` | 100 | App ↔ Hook 进程通信协议 | **极高**，改协议要两端同时改 |
| `BreenoHooks` | 90 | 小布接管入口 | 高，与 ROM 类名强绑定 |
| `AgentAppState` | 86 | 全局 UI 状态 | 高，Compose 全量重组 |

### 进程模型（关键）

- **主进程**：UI + Agent Runtime + Room
- **`:voice`**：语音交互服务（`EtaVoiceInteractionService`、浮窗）
- **`:recognition`**：语音识别
- **Hook 进程**：注入到 `com.miui.voiceassist` / `com.heytap.speechassist` / `com.coloros.colordirectservice` / `com.google.android.googlequicksearchbox` / `system`
- App 与 Hook 之间走 `AgentRuntimeWire`，**协议改动必须两端同步**

## 3. 改代码的红线（踩了就出事）

### 3.1 工具链版本硬锁

```
compileSdk = 37    minSdk = 33    targetSdk = 37
JDK 25             AGP 9.4.1      Kotlin 2.4.20    KSP 2.3.12
NDK 30.0.16248370  Gradle 9.7.1
```

**任何一个降版本都会连锁炸**：Miuix 0.9.4、libxposed 102、Compose 1.13、NavigationEvent 1.1.2 全部按这套编译。不要"为了兼容"去降 SDK 或 JDK。

### 3.2 jniLibs 里的 .so 是预编译产物

```
app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}/
  libeta_pty.so      ← PTY 终端
  libproot_exec.so   ← PRoot 执行器（仅 arm64-v8a / x86_64）
  libproot_loader.so ← PRoot 加载器（仅 arm64-v8a / x86_64）
```

源码在 `app/src/main/cpp/eta_pty.c` 与 `scripts/native/`。
**改了 C 代码必须手动重跑 `scripts/build-terminal-native.sh` 并替换 .so，Gradle 不会自动编译它们。** 只改 Kotlin 不用管。

### 3.3 META-INF/xposed/ 三件套不能丢

```
app/src/main/resources/META-INF/xposed/
  module.prop       minApiVersion=102 / targetApiVersion=102 / staticScope=true / exceptionMode=protective
  scope.list        注入范围：system, com.android.systemui, googlequicksearchbox, colordirectservice,
                    com.heytap.speechassist, oplus.aimemory, com.miui.voiceassist, com.miui.home, mi.android.globallauncher
  java_init.list    Hook 入口类清单
```

R8 混淆后若丢失，**模块直接失效且无任何报错**。`app/build.gradle.kts` 里的
`packaging.resources.merges += "META-INF/xposed/*"` 就是为此存在，不要删。
**新增 hook 目标时必须同步改 `scope.list`**，否则 hook 装不上。

### 3.4 签名不能换

`app/build.gradle.kts` 从环境变量读四个值，缺任一个 release 就不签名：

```
ETA_RELEASE_STORE_FILE / ETA_RELEASE_STORE_PASSWORD
ETA_RELEASE_KEY_ALIAS  / ETA_RELEASE_KEY_PASSWORD
```

当前 CI 使用**固定 keystore**（base64 存于 Actions Secrets），每次构建签名完全一致，可覆盖升级。

```
Secret 名                         值
ETA_RELEASE_KEYSTORE_BASE64      eta-release.jks 的 base64
ETA_RELEASE_STORE_PASSWORD       <- 见本地备份>
ETA_RELEASE_KEY_ALIAS            eta
ETA_RELEASE_KEY_PASSWORD         <- 见本地备份>
```

**keystore 是唯一且不可再生的**：丢了就无法发布可覆盖升级的新版本，老用户只能卸载重装（丢全部数据）。
离线备份必须至少三份（本地 / 网盘 / 邮箱）。证书 SHA-256：`C9:73:FE:C3:B2:DD:29:6F:23:A5:54:1A:FA:B9:1C:B5:49:2B:5C:7A:14:70:4E:07:8B:AA:96:AA:1E:42:1F:0A`，有效期至 2056-10-10。

### 3.5 Room 数据库迁移不可回滚

`data/db/` 下的 Entity 改名、删字段、改类型，会让用户的**聊天记录、记忆、Provider 配置、角色卡全丢**。
改 schema 必须写 Migration，不能靠 `fallbackToDestructiveMigration()`。

### 3.6 Xposed 类名与 ROM 强绑定

`ModuleMain.onPackageReady()` 按包名分派：

```
com.google.android.googlequicksearchbox → GoogleEligibilityHooks + GoogleAppHooks
com.coloros.colordirectservice          → ColorDirectHooks
com.heytap.speechassist (小布)          → BreenoHooks
com.miui.voiceassist (超级小爱)         → XiaoAiHooks
```

系统更新随时改类名/方法签名 → hook 静默失效。改 hook 前先确认目标 ROM 版本，用 DexKit 兜底匹配的逻辑（`core/DexKitTargets.kt`）不要随意简化。

### 3.7 MCP 只支持 Streamable HTTP

`agent/mcp/` 是 HTTP + Bearer Token 客户端，**不支持 stdio**。不要按 stdio 思路改。

### 3.8 Provider 三协议分支不能合并

`agent/model/` 里 OpenAI Chat Completions / Responses API / Anthropic Messages 的 SSE 解析路径各不相同，
Tool Calling 与推理内容的字段也不一样。**不要把三条路径抽成一个"通用"实现**，会丢功能。

### 3.9 Root / 非 Root 双路径

工具执行默认用 Root（已授权时），未授权降级 App 身份，两者**共用同一个私有工作区**，身份切换不移动文件。
显式要普通身份时传 `identity=user`。不要假设某个工具一定有 Root。

## 4. 构建与发版

```bash
# 本地（需 JDK 25 + SDK 37 + NDK 30）
./gradlew :app:assembleDebug

# 云端（已配置，push 到 main 自动触发）
# .github/workflows/build-apk.yml → 生成 debug + release 两个 APK → Actions Artifacts 下载
```

`local.properties` 写 `sdk.dir`，**不要提交**（已在 .gitignore）。
`versionCode` 用日期编码 `yyyyMMdd + 两位序号`，只增不减。

## 5. 代码图谱（Graphify）

| 项 | 说明 |
|---|---|
| 安装 | `uv tool install --link-mode=copy graphifyy`（PRoot 必须加 `--link-mode=copy`） |
| 版本 | 0.9.92 |
| 首次建图 | `graphify extract . --code-only`（约 2 分钟，**0 API 费用**） |
| 增量更新 | `graphify update .` |
| 生成报告 | `graphify cluster-only . --no-label` |
| 产物 | `graphify-out/graph.json`（21MB）、`graph.html`（浏览器打开可交互）、`GRAPH_REPORT.md` |
| 入不入库 | **不入库**，已在 .gitignore。随时可重建 |

已知解析缺陷（tree-sitter Kotlin 语法版本问题，不影响主流程）：

- `AgentContextCompactor.kt`（仅 3 个符号）
- `AgentToolBatchRecovery.kt`（仅 2 个符号）
- `MarkdownInline.kt`（25 个符号，部分）
- `AgentRuntimeWireTest.kt`（4 个符号）
- 无符号产出：`build.gradle.kts`、`settings.gradle.kts`、`AppearanceThemeLocals.kt`、`EtaFonts.kt`

## 6. 收工协议（强制，逐项打勾，不可跳过）

任务做完**不代表结束**。在 `git commit` 之前，逐项确认下面每一行。任一项该做没做，这个任务就是**未完成**。

```
□ 1. docs/STATE.md 的「现在做到哪」和「下一步」已更新
      └─ 这一步最关键。不动它，下一个 Agent 就是瞎子。
□ 2. docs/PROGRESS.md「已完成」加了一条（带日期）
□ 3. docs/PITFALLS.md：本轮踩坑已追加（没踩坑就跳过，但必须确认“真的没踩”）
□ 4. docs/MODULES.md：模块职责/依赖/契约变了才改
□ 5. docs/CONVENTIONS.md：构建/发版/依赖版本变了才改
□ 6. docs/adr/NNN-标题.md：做了架构选择就新建（没做就跳过）
□ 7. 跑了 graphify update .（改过 app/src/ 下任何文件就必须跑）
□ 8. git status 确认没有意外文件（.keys/ graphify-out/ 不能进）
```

### 判断标准（一句话）

> **如果明天换一个完全不认识这个项目的 Agent 来接手，他读你写的更新，能不能直接接着干？**
>
> 能 → 才算收工。不能 → 你只是写完了代码，没完成交接。

### 必须写的内容

| 写什么 | 例子 |
|---|---|
| **为什么**这么改 | 「改成轮询是因为 WebSocket 在 MIUI 后台被断开」 |
| 改了会**踩什么坑** | 「这里必须加 try，ROM 改了方法签名会抛 NoSuchMethod」 |
| **谁跟谁强耦合** | 「改了 AgentRuntimeWire 的字段必须同步改 hook 侧解析」 |
| **还没验证的部分** | 「编译过了，但真机 Xposed 加载没测」 |

### 绝对不要写

- ❌ 「本文件实现了 XXX 功能」— 从文件名就能猜到
- ❌ 贴大段代码 — 代码在 git 里，不要复制
- ❌ 「已完成优化」— 什么优化？提升了多少？不写清楚等于没说
- ❌ 「TODO: 后续完善」— 要么写清楚是什么事，要么不要写

### 为什么这套靠自觉而不是硬卡

文档需要判断力（什么值得记、什么不值得），CI 强制检查只能查「文件有没有改动」，查不出「写得有没有用」。
硬卡的结果是 Agent 敷衍着写两句糊弄过去，比不写更糟。所以这套是**契约 + 判断标准**，不是脚本。

只有一件事是硬性的：`graphify update .`。它零成本、无判断需求，改代码就必须跑。已在 pre-commit hook 里自动执行。

## 7. 文档索引

| 文件 | 内容 | 什么时候读 |
|---|---|---|
| `docs/START_HERE.md` | 新 Agent 三步对齐 | **第一个读** |
| `docs/STATE.md` | 当前状态卡（做到哪、下一步） | 第二个读 |
| `docs/ARCHITECTURE.md` | 分层、数据流、进程模型、启动流程 | 理解结构 |
| `docs/MODULES.md` | 逐模块职责、依赖、对外契约 | 改某模块前 |
| `docs/PITFALLS.md` | 踩坑清单（现象 → 根因 → 解法） | 遇到问题先查 |
| `docs/PROGRESS.md` | 完整进度流水与待办 | 查历史 |
| `docs/CONVENTIONS.md` | 代码风格、构建、发版、依赖管理 | 写代码/发版前 |
| `docs/adr/` | 架构决策记录 | 想质疑「为什么这么选」 |
| `graphify-out/GRAPH_REPORT.md` | 自动生成的图谱报告 | 快速扫架构 |

## 8. 上游与本仓库的关系

- 上游：`https://github.com/Mangi-11/Eta`（v3.3.1，commit 基线 `2314aa90`）
- 本仓库：`https://github.com/slao28731-crypto/Eta-dev`
- 许可：PolyForm Noncommercial License 1.0.0，**禁止商业使用与二次分发**
- 同步上游：`git remote add upstream https://github.com/Mangi-11/Eta && git fetch upstream`
- 二改冲突处理：优先保留本仓库的 CI 配置（`.github/workflows/build-apk.yml`）与文档，代码逻辑尽量跟随上游
