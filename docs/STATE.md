# 当前状态卡

> 一页看完。新 Agent 读完这个就知道从哪接。
> **每次收工必须更新本文的「下一步」段。** 这是最重要的一段。

## 现在做到哪

| 阶段 | 状态 |
|---|---|
| 上游导入 | ✅ v3.3.1（954 文件 / 66MB），commit `2314aa90` |
| 云端 CI | ✅ 可用，push main 自动出 debug + release 双 APK，约 10 分钟 |
| 签名 | ✅ 固定 keystore（至 2056-10-10），支持覆盖升级 |
| 代码图谱 | ✅ Graphify 0.9.92，11899 节点 / 436 社区 |
| 交接文档 | ✅ AGENTS.md + docs 六件套 + 3 条 ADR |
| **二改功能开发** | ✅ **已验收五轮**：抽屉/配色轮 + 顶栏图标轮 + MCP 自助管理轮 + MCP 额度修复轮 + 删除 demo 轮 |
| **逆向 skill 内置** | 🟡 已实现待真机验收：`re-mobile`（默认开）+ `re-pc`（默认关），33 个 reference 全部 ≤ 49,504 字 |

## 逆向 skill 内置（新增能力，待验收）

两个内置 skill 已进 `app/src/main/assets/builtin_skills/`，走既有 `manifest.json` + `BuiltinSkillAssetStore` + `SkillRegistryStore` 通道，用户在「设置 → Skills」里用开关控制。

| skill | 默认 | 体积 | 定位 |
|---|---|---|---|
| `re-mobile` | **开** | 215KB | Android/iOS 逆向与游戏分析，14 个 reference |
| `re-pc` | **关** | 339KB | PC/主机端逆向与游戏安全，18 个 reference |

**三个必须记住的设计约束**

1. **`description` 必须 ≤180 字**：`AgentPromptBuilder.kt:211-215` 对 description 做 `.take(180)`，超出直接砍掉且会在句子中间截断。当前 5 个 skill 分别是 140/122/84/122/129 字，都在安全线内。**触发词表放正文，不要塞 description。**
2. **单文件必须 ≤64,000 字符**：`skills_read` / `skills_read_resource` 的 `maxChars` 上限是 64,000。原 `cheatsheet.md` 274,602 字（只能读 23%）、原 `reverse-engineering/SKILL.md` 26,717 字（默认 16,000 只读 60%），已按主题切成 11 份，全部 11,616~49,504 字。**加新 reference 前先量字符数。**
3. **`defaultEnabled` 默认 true**：`SkillRuntime.kt` 的 `BuiltinSkillAsset` 新增该字段，manifest 不写就是 true（保持既有三个内置 skill 行为不变），`re-pc` 显式写 `false`。`seedMissingBuiltins` 已从写死 `enabled = true` 改为读该字段。

**APK 分析工具链（本轮补齐）**

`LinuxApkAnalysisInstaller` 原本只装 jadx/apktool/smali/baksmali，且 wrapper 写死 `b|build` 直接报 `APKTOOL_BUILD_UNAVAILABLE`。现已解掉：

- wrapper 不再拦截 build，改为自动追加 `--aapt /usr/local/bin/aapt2-wrapper`
- `aapt2-wrapper` 是新增文件：apktool 会把 aapt2 复制到 `/tmp` 再执行，复制丢 x 位，报 `Syntax error: "(" unexpected`，wrapper 用 `exec /usr/bin/aapt2` 绕过
- 安装命令追加 `aapt2 apksigner`（Debian）/ `aapt2`（Alpine）
- `APK_ANALYSIS_REVISION` 1 → 2，已装环境会重走安装补齐
- 验证步骤新增 `aapt2-wrapper version` 检查（失败码 87）

另外两类工具已手工装进用户的 Debian rootfs，**重装 APK 不会丢，但换设备要重装**：

```bash
binutils 2.44          readelf/objdump/nm/strings/addr2line/size
bsdextrautils 2.41     hexdump
xxd 9.1                xxd
aapt2 2.19 + apksigner 0.9
capstone 5.0.7         pip --break-system-packages
lief 1.0.0             pip --break-system-packages
```

**已知限制**

- **Frida 未装**：`frida-server` 需常驻后台、需版本匹配目标 gadget、会被省电策略杀，单独一轮做。当前 skill 里明确写了「先 `command -v frida` 探活，没有就问用户」。
- **aapt2 回编译有已知坑**：`public.xml` 里 `$` 开头的资源名（apktool 解包副作用）会导致编译失败，需改用 `--copy-original`。已写进 SKILL.md 的「两个已知陷阱」。
- **PC 端 skill 是方案性质**：Eta 跑在 Android 上，`.sys` 驱动、DLL 注入、x64dbg attach 都做不了。`re-pc/SKILL.md` 开头已用 ✅/❌ 明写能做与做不到，定位是「给电脑端可执行方案 + 审回传结果」。静态分析（PE/ELF）在手机上真能做。

## MCP 自助管理（新增能力，已验收）

Agent 自己就能添加 MCP 服务器，不需要用户进设置页。6 个工具全部常开（`AgentModelClient` 里硬编码 `mcpManagement = true`）。

| 工具 | 作用 |
|---|---|
| `mcp_list_servers` | 列出已配置服务器 + 每个服务器下的工具名与 `modelName` |
| `mcp_add_server` | 添加服务器、连一次拿工具清单、**默认启用全部工具**、落库 + Keystore 存 token |
| `mcp_remove_server` | 删除服务器及凭据 |
| `mcp_refresh_server` | 重连重读工具，返回 `addedTools` / `droppedTools` |
| `mcp_set_server_enabled` | 整体开关 |
| `mcp_set_tool_enabled` | 单个工具开关 |

### 三个必须记住的设计约束

1. **新服务器默认全启用**：`McpServerManager.discover(enableAllDiscoveredTools = true)` 只由 Agent 路径传 `true`。不传的话 `enabledToolNames` 是空集，`activeTools` 就是 0 个，功能等于没做。设置页仍走 `false`，行为完全不变。
2. **工具名公式只允许一份**：`mcpModelToolName()` 定义在 `agent/mcp/McpRunContext.kt`，`AgentLocalTools` 只调用不复制。名字格式是 `mcp_<serverId前8位>_<工具名>_<sha256前8位>`，两处漂移会直接导致 `UNKNOWN_TOOL`。
3. **管理工具必须排除出脱敏**：`AgentSensitiveToolPolicy` 里 `mcp_` 前缀会触发脱敏，结果被替换成「敏感工具未写入持久会话」，连带 `modelTools` 一起丢。已在 `mcpManagementTools` 精确白名单里豁免，远端 MCP 工具仍照旧敏感。

### 已知限制

- **单轮 MCP 工具总额度 256（全局共享，非每服务）**：超过部分下一轮不会进目录，调用返回 `TOOL_NOT_AVAILABLE`。`mcp_add_server` / `mcp_list_servers` 会如实报出 `registeredToolCount` / `overLimit`，模型可自行用 `mcp_set_tool_enabled` 精简。
- **发现上限 512、翻页上限 16**：`MAX_DISCOVERED_TOOLS` 必须 ≥ `MAX_RUN_TOOLS`，否则工具连数据库都进不来。改上限时两个常量要一起动。
- 加完当轮用不了：`McpRunSnapshot` 在 run 开始时一次性加载，工具下一轮才出现（同一对话的下一条消息即可，无需重启 App）。
- token 会以明文进对话历史（工具参数的既有行为，改不了），只在返回值里打码。

详细契约见 `McpManagementToolCatalogTest`（4 个用例锁死开关/必填项/上限/脱敏豁免）、`McpRunContextTest`（额度常量关系 + 目录描述截断）。

## 顶栏图标（用户已定稿）

| 项 | 内容 |
|---|---|
| 会话历史图标 | 两条圆头横线，上长下短。`res/drawable/ic_conversation_history.xml` |
| 线参数 | 上线 `M3.5,7.5 L19,7.5`、下线 `M3.5,16.5 L12.3,16.5`、`strokeWidth=2.4`、`strokeLineCap=round` |
| 新建对话 | 从溢出菜单提到顶栏，插在「更多」左侧；图标 `res/drawable/ic_new_conversation.xml`（用户提供的 SVG） |
| SVG fill 处理 | 原 `#060A26` 深蓝改为 `#FF000000`，再由 Compose `tint = onSurface` 按主题着色。**否则深色下看不见** |
| 菜单项 | 已删除「新建对话」，剩：终端 / Kimi Web / 浏览器 / 停止 Kimi（条件） |
| 菜单索引 | 0 终端 / 1 Kimi Web / 2 浏览器 / 3 停止 Kimi。**改菜单顺序必须同步改 `onSelectedIndexChange`** |

调参原型：历史版本曾在仓库 `demo/` 下（`topbar-v2.html` 会话历史图标四滑块 + 六预设、`topbar-icons.html` 顶栏全部图标总览），**现已删除并加入 `.gitignore`，预览页只在本地生成、不入库**。

## 深色配色方案（用户已定稿）

用户用本地生成的深色调色台（原 `demo/dark-palette.html`，**该目录已删除、预览页不入库**）逐档试色后拍板，这套值已成为深色模式的基准：

| 层级 | 色值 | 亮度 | 常量 | 用途 |
|---|---|---|---|---|
| 抽屉（最底层） | `#0F0F0F` | 5.9% | `DarkPaneContainer` | 侧栏面板、推移容器底 |
| 页面（中间层） | `#1A1A1A` | 10.2% | `DarkPageBackground` | 顶栏、聊天区、底栏、二级页 Scaffold |
| 卡片（最上层） | `#343434` | 20.4% | `DarkCardSurface` | 聊天气泡、建议卡片、输入框、思考卡片 |

- 卡片与页面 **ΔL = 10.5%**，是用户在调色台里定下的档位（低于 3% 气泡消失，6~12% 最舒服）
- 层次方向是「后暗前亮」：抽屉 < 页面 < 卡片。抽屉比页面亮会立刻出现"挖洞"观感，这是踩过的坑
- 三个常量全部定义在 `ui/components/ConversationPanePanel.kt`，主题侧反向引用
- **纯黑模式优先级最高**：`pureBlackEnabled` 开启时以上全部让位，回到 `#000000`
- **浅色模式零影响**：所有新色值只在 `isDark && !pureBlackEnabled` 分支生效

## 下一步（最重要）

**五轮二改已验收，第六轮（逆向 skill 内置）已实现待真机验收。当前等用户装包验证。**

第五轮验收（删除 demo 目录）用户原话：「刚刚我去Github仓库看了一下，demo文件夹里面都是之前用的HTML预览图，请你删除这个文件夹和内容，以后预览图只在本地生成」。
第六轮用户定调：「按照你推荐进行」= 530KB 全量切分 + A/C 工具组 + 解掉回编译限制。

**第六轮已做（2026-10-19）**

| # | 内容 | 关键点 |
|---|---|---|
| 1 | 建 `re-mobile`（215KB / 14 reference，默认开） | SKILL.md 写八步决策树，素材原文搬运不删 |
| 2 | 建 `re-pc`（339KB / 18 reference，默认关） | SKILL.md 开头用 ✅/❌ 明写手机做不了什么 |
| 3 | 切 `cheatsheet.md` 274,602 → 11 份 | 按主题分组，最大 49,504 字，全部 ≤64,000 |
| 4 | `SKILL.md` 切分 | 原 26,717 字 → 拆成流程层 + 分散到 reference |
| 5 | `manifest.json` 加 2 条 + `defaultEnabled` | `SkillRuntime.kt` 新增字段，默认 true 不影响旧 skill |
| 6 | 装 A 组工具 | binutils 2.44 / bsdextrautils / xxd / capstone / lief |
| 7 | 装 C 组工具 | aapt2 2.19 / apksigner 0.9 |
| 8 | 解掉回编译限制 | wrapper 不再拒绝 build，改用 `aapt2-wrapper` |
| 9 | `APK_ANALYSIS_REVISION` 1→2 | 已装环境重走安装补齐 aapt2 |
| 10 | 改 `AgentPromptBuilder` 提示 | 删掉「不支持回编译」，改成具体命令 |

**真机验收要点**

1. 装新 APK → 设置 → Skills，应看到 `re-mobile` 已启用、`re-pc` 已关闭
2. 打开 `re-pc` 开关 → 索引里出现，description 不被截断
3. 对 agent 说「帮我分析这个 APK」，应直接 `apktool d` + `jadx`，不再教用户装工具
4. 说「改完帮我回编译」，应走 `--aapt /usr/local/bin/aapt2-wrapper` + `apksigner`
5. **需要重进一次 设置 → 终端 → Linux 环境 → APK 分析工具**，让 revision 2 重装补上 aapt2

**下一步可选方向**（用户未指定，不要主动开工）

1. **Frida 组（B 组，本轮明确推迟）**：`frida-server` 推送 + `frida-tools` CLI + 开机自启（`/data/adb/service.d/`）+ 版本匹配。做完后 `re-mobile` 的第 4 步（动态 Hook）和第 5 步（脱壳）才能真正跑通。
2. **aapt2 `$` 资源名问题**：`public.xml` 里 `$ic_splash_animated__N` 这类是 apktool 解包副作用，目前靠 `--copy-original` 绕过（等于放弃资源重编）。根治要在解包后清洗 `public.xml`。
3. 若 `re-pc` 使用中觉得 PC 内容用不上，可考虑再拆一个 `re-hardware`（DMA/驱动）单独关掉。

### 已完成并验收的完整清单

| # | 内容 | commit |
|---|---|---|
| 1 | 抽屉叠层几何对齐上游原版（326dp / 0.76f、scale 0.95/0.97、36dp 圆角、正向平移 6dp） | `79083d5` |
| 2 | 深色三级配色：抽屉 `#0F0F0F` / 页面 `#1A1A1A` / 卡片 `#343434` | `d64864e` |
| 3 | 浅色各卡片回落各自上游层级、输入卡恢复 `#E8E8E8` | `24e30f7` |
| 4 | 抽屉卡片底部灰带修复（阴影退回 12dp、hairline 仅深色） | `38fe2a3` |
| 5 | 顶栏会话历史换两线图标 + 新建对话提到顶栏（更多左侧，用户 SVG） | `4f2b4aa` |
| 6 | **Agent 自助管理 MCP**：6 个工具（list/add/remove/refresh/开关机/单工具开关） | `4378e83` |

### 后续可做的二改方向（尚未确认）

- 改 UI 其他页面（换主题、改布局、加页面）
- 加工具（新的设备 API / MCP 适配）
- 换 Provider（接入自建模型网关）
- 改 hook 目标（适配新 ROM 版本 / 新增厂商助手）
- MCP 支持 stdio 传输（当前只有 Streamable HTTP）
- MCP 支持 OAuth 动态客户端注册（当前只有 Bearer Token）

### 若要继续微调，改哪里

| 想改什么 | 改哪个常量 / 文件 | 位置 |
|---|---|---|
| 深色气泡太暗/太亮 | `DarkCardSurface`（`#343434`） | `ui/components/ConversationPanePanel.kt` |
| 深色页面底色 | `DarkPageBackground`（`#1A1A1A`） | 同上 |
| 抽屉退后感 | `DarkPaneContainer`（`#0F0F0F`） | 同上 |
| 抽屉阴影浓淡 | `ForegroundShadow`（`12.dp`，上游原值） | `ui/components/ConversationSidePaneScaffold.kt` |
| 卡片上下露边高度 | `FOREGROUND_MIN_VERTICAL_SCALE`（`0.97f`，上游原值） | `ui/components/ConversationPaneMotion.kt` |
| 抽屉宽度 | `PaneTargetWidth` / `PaneWidthFraction` | `ConversationSidePaneScaffold.kt` |
| 会话历史图标线形 | `ic_conversation_history.xml` 的两条 path | `app/src/main/res/drawable/` |
| 新建对话图标 | `ic_new_conversation.xml` | 同上 |
| 顶栏图标顺序/增删 | `AgentTopBar` 的 `actions` 块 | `ui/app/AgentAppShell.kt` |
| MCP 工具是否常开 | `mcpManagement` 参数（生产路径写死 `true`） | `agent/model/AgentModelClient.kt` |
| MCP 管理工具 Schema | `AgentMcpToolCatalog` | `agent/model/AgentMcpToolCatalog.kt` |
| MCP 工具名格式 | `mcpModelToolName()`（**唯一实现，别复制**） | `agent/mcp/McpRunContext.kt` |
| 新 MCP 默认启用策略 | `enableAllDiscoveredTools` 参数 | `agent/mcp/McpServerManager.kt` |
| MCP 脱敏豁免名单 | `mcpManagementTools` | `agent/model/AgentSensitiveToolPolicy.kt` |

原型（**均不再入库**，需要时临时本地生成）：深色调色台（原 `demo/dark-palette.html`）、会话历史图标调参（原 `demo/topbar-v2.html`）、顶栏图标总览（原 `demo/topbar-icons.html`）。

---

## 已知风险（动手前必看）

| 风险 | 说明 |
|---|---|
| 本地不能编译 | `aapt2` 是 x86_64 ELF，aarch64 PRoot 跑不了。任何编译验证只能走 GitHub Actions |
| 4 个文件图谱不全 | `AgentContextCompactor.kt` / `AgentToolBatchRecovery.kt` / `MarkdownInline.kt` / `AgentRuntimeWireTest.kt`，查这些要直接读源码 |
| 仓库是 public | Eta 上游是 PolyForm Noncommercial 1.0.0，限制二次分发。**用户已明确决定保持 public，不要重复建议转 private** |
| 签名只有一份 | keystore 在 `/sdcard/Download/eta_out/eta-release.jks`，**尚未做离线多份备份** |
| 无真机验证记录 | Xposed / 无障碍 / 终端 / MCP 都还没在真机上实测过 |

---

## 关键技术约束（一句话版）

```
JDK 25 + compileSdk 37 + AGP 9.4.1 + Kotlin 2.4.20 + NDK 30.0.16248370  ← 一个都不能降
jniLibs/*.so 是预编译的，改 C 代码要手动跑 scripts/build-terminal-native.sh
META-INF/xposed/ 三件套丢了 = 模块静默失效，无报错
Room 改 schema 必须写 Migration，不能 destructive
Provider 三条 SSE 路径不可合并
MCP 只支持 Streamable HTTP，不支持 stdio
```

细节见 `AGENTS.md` 第 3 节（9 条红线）。

---

## 仓库与分支

```
本地分支:  clean
远端分支:  main
推送命令:  git push origin clean:main     ← 注意分支名映射，不能只写 git push
仓库地址:  https://github.com/slao28731-crypto/Eta-dev
最新提交:  见 git log --oneline -1（本文不写死，避免过期）
```

## 还原点

| 类型 | 位置 | 用途 |
|---|---|---|
| 基线 tag | `v0.0.1-baseline` | 干净起点：上游 3.3.1 + graphify + 交接文档 + 固定签名 |
| 提交历史 | `git log --oneline` | 每个功能一个还原点 |
| App 数据备份 | `/sdcard/eta-backup/<时间戳>/` | `bash scripts/backup-app-data.sh` 生成 |

**回滚规则见 `docs/USER_RULES.md` 规则三**：代码随便回，App 数据改了就回不去，数据库结构只能升不能降。

**数据库当前版本 `22`**（`data/db/EtaDatabase.kt`）。改它之前必须先备份。

---

## 常用命令

```bash
# 查影响面
graphify affected "AgentModelClient" --depth 2
graphify explain "AgentRunController"
graphify query "how does tool calling work" --budget 3000
graphify god-nodes --top 15

# 改完代码刷图
graphify update .

# 提交推送（触发云端构建）
git add -A && git commit -m "feat: xxx"
git push origin clean:main

# 下载 APK
# GitHub → Actions → 最新 run → Artifacts → eta-apk-<sha>.zip
```

---

## 文档地图

| 文件 | 什么时候读 |
|---|---|
| `docs/USER_RULES.md` | **动代码之前必读**（先摸链路 → 反向提问 → 用户确认） |
| `docs/START_HERE.md` | **刚接手时第一个读** |
| `AGENTS.md` | 想知道架构、红线、收工要求 |
| 本文 `docs/STATE.md` | 想知道现在做到哪、下一步做什么 |
| `docs/PITFALLS.md` | 遇到问题先查这里（21 条） |
| `docs/MODULES.md` | 要知道某模块干什么、改它有什么风险 |
| `docs/ARCHITECTURE.md` | 要理解数据流、进程模型 |
| `docs/CONVENTIONS.md` | 要知道版本、构建、发版规则 |
| `docs/adr/` | 想质疑「为什么当初这么选」 |

---

_最后更新：2026-10-19_