# 进度与待办

> 每次任务结束更新此文件。日期 + 一句话说明改了什么、为什么。

## 基线

| 项 | 值 |
|---|---|
| 上游版本 | Eta v3.3.1（commit `2314aa90`） |
| 导入日期 | 2026-10-18 |
| 仓库 | `slao28731-crypto/Eta-dev`（public） |
| 签名 | 固定证书 `CN=Eta Dev`，RSA 4096，SHA384withRSA，有效期至 2056-10-10（keystore 存于 Actions Secrets） |
| 代码量 | 543 个 Kotlin 文件 / 105,111 行 |

## 已完成

- [x] **2026-10-18** 导入上游 v3.3.1 全部源码（954 文件，66MB）
- [x] **2026-10-18** 建立自签名 CI：`.github/workflows/build-apk.yml`，push 到 main 自动编译 debug + release
- [x] **2026-10-18** 首次云端构建成功，产物 `Eta-v3.3.1+2314aa9.apk`（11MB）/ `Eta-v3.3.1-debug+2314aa9.apk`（82MB）
- [x] **2026-10-18** 清理仓库：删除 `.backup` 会话快照（含凭据），历史改为干净单提交
- [x] **2026-10-18** 删除账号下其余 4 个仓库（hello-actions、Scene-Port-Hider-by-eBPF、ABK、1）
- [x] **2026-10-18** 引入 Graphify 0.9.92，建立代码图谱（11899 节点 / 36925 边 / 436 社区，零 API 费用）
- [x] **2026-10-18** 安装 graphify skill 到 `.agents/skills/graphify/`
- [x] **2026-10-18** 编写交接文档：`AGENTS.md` + `docs/ARCHITECTURE.md`、`PITFALLS.md`、`MODULES.md`、`CONVENTIONS.md`、`PROGRESS.md`
- [x] **2026-10-18** 固定签名证书：生成 `eta-release.jks`（RSA 4096 / SHA384withRSA / 有效期至 2056-10-10），四个 Secret 已写入仓库，workflow 改为从 Secret 还原，支持覆盖升级
- [x] **2026-10-18** 文档体系补强：新增 `docs/START_HERE.md`（新 Agent 三步对齐）、`docs/STATE.md`（一页状态卡）；AGENTS.md 收工协议改为 8 项强制检查清单；新增 pre-commit hook 自动刷新图谱 + `scripts/install-hooks.sh` 一键安装
- [x] **2026-10-18** 新增 `docs/USER_RULES.md`（用户协作规则，最高优先级）：接需求先摸清完整链路 → 反向提问 → 用户确认后才动代码；用户只说人话，Agent 负责翻译；含提问模板与 7 项检查清单
- [x] **2026-10-18** 抽屉叠层几何重构：`ConversationPaneMotion` 新增 `foregroundScale/ScaleY/Corner/Shift` 四个字段；卡片展开时 `scaleX 0.95` / `scaleY 0.97` 退层 + `36dp` 圆角 + `6dp` 缝隙；去掉 `ChatScrimAlpha 0.16` 黑色遮罩改用 `12dp` 阴影（用户要求保持白底）；`SettleDampingRatio` 从 `1f` 降到 `0.9f` 让回弹更活
- [x] **2026-10-18** 侧栏宽度对齐上游原版：`PaneMaxWidth 340.dp` → `PaneTargetWidth 326.dp`，`PaneWidthFraction 0.84f` → `0.76f`。缘由：393dp 屏上抽屉从 330.1dp 收窄到 298.7dp，卡片露出从 56.9dp 增到 88.3dp，叠层感才成立
- [x] **2026-10-18** 补 `ConversationPaneMotionTest` 4 个用例：缩放/圆角随进度、圆角全程恒定、`foregroundShift` 端点值、缩放单调性
- [x] **2026-10-18** 建本地 `demo/` 目录（**已删除、现不入库**），4 份抽屉 HTML 原型（v5 叠层尝试 / v7 A-B 对比 / suture 照抄源码 / 旧版），供后续 UI 改动先看效果再落 Kotlin
- [x] **2026-10-18** 深色卡片底色修复（方案 B）：展开时按 `paneProgress` 把卡片从 `background`(`#000000`) lerp 到 `surfaceContainer`(`#242424`)，关闭时系数归 0 保持原底色。同时 `ForegroundShadow` 12→16dp、加 0.5dp hairline 描边（alpha 随 progress），因为两层同色后只能靠圆角+阴影+描边表达层次。浅色零影响（两色本来就是同一档浅色）
- [x] **2026-10-18** 建 `demo/dark-card-color.html`（**已删除**）：四档提亮比例（0% / 50% / 72% / 100%）可实时切换的深色对比页，含灰阶差、色带位置、缺陷标注
- [x] **2026-10-18** 修 P074 第一次修复无效的问题：`AgentAppShell` 的 `Scaffold` 缺 `containerColor`，默认 `background`（纯黑）盖住了侧栏画的底色。新增 `LocalPageBackgroundColor` CompositionLocal，侧栏下传当前底色、壳层 Scaffold 接收，三处联动才生效。排查靠截图取色（卡片主体 `#000000`、底部输入栏 `#242424`）
- [x] **2026-10-18** 深色三级配色定稿（用户用本地 `demo/dark-palette.html` 调色台拍板（**该目录后续已删除**））：抽屉 `#0F0F0F` / 页面 `#1A1A1A` / 卡片 `#343434`，ΔL 10.5%。新增统一入口 `appCardSurfaceColor(fallback)`，改 7 个文件：主题构造覆写 `background`、聊天气泡 / 建议卡片 / 思考卡 / 运行记录卡全部走统一入口、抽屉压暗。层次方向纠正为「后暗前亮」
- [x] **2026-10-18** 修浅色回归：`appCardSurfaceColor()` 初版无参、浅色回落 `surfaceContainerHigh`，把建议卡片刷成浅灰、与页面分不开。改为接受 `fallback` 参数，各卡片回落**各自的上游层级**（气泡 `surfaceContainerHigh`、建议/思考/运行记录 `surface`）。底部输入框撤出统一入口，恢复上游 `surfaceContainer`（浅色 `#E8E8E8`）
- [x] **2026-10-18** 修抽屉卡片底部灰带（P076）：`ForegroundShadow` 16dp 退回上游 12dp；0.5dp hairline 描边改为仅深色绘制（上游本来没有这条线）；删除已失效的 `pageBackgroundColor` lerp 与 `DarkPageLift` 常量。用户真机验收通过
- [x] **2026-10-19** 顶栏图标改版（第二轮，用户真机验收通过）：① 会话历史图标从 `Icons.AutoMirrored.Outlined.ShortText`（三条线）换成自定义 `ic_conversation_history.xml`（两条圆头横线，上长下短：上线 `3.5→19.0`、下线 `3.5→12.3`、gap 9dp、线粗 2.4、`strokeLineCap=round`），参数由用户在 `demo/topbar-v2.html` 调定（**该目录后续已删除**）；② 新建对话从溢出菜单提到顶栏，插在「更多」左侧，图标用用户指定 SVG 转 `ic_new_conversation.xml`（`fill` 从固定深蓝 `#060A26` 改为 `#FF000000`，由 Compose `tint` 按主题着色，否则深色下不可见）；③ 菜单删除「新建对话」，索引同步从 `0..4` 改为 `0..3`
- [x] **2026-10-19** 建 `demo/topbar-icons.html`（**已删除**）：顶栏全部图标总览（实际布局模拟 + 图标清单 + 菜单内图标 + 圆润候选 + 间距尺），可点选标记
- [x] **2026-10-19** 建 `demo/topbar-v2.html`（**已删除**）：会话历史图标调参台，四滑块（间距/下线长度/线粗/总长）+ 六预设 + 24px 真实尺寸预览 + 一键复制参数
- [x] **2026-10-19** Agent 自助管理 MCP（第三轮，用户真机验收通过）：新增 6 个工具 `mcp_list_servers` / `mcp_add_server` / `mcp_remove_server` / `mcp_refresh_server` / `mcp_set_server_enabled` / `mcp_set_tool_enabled`，全部常开（`mcpManagement = true`）。改动 11 个文件：新建 `AgentMcpToolCatalog`（Schema）与 `McpManagementToolCatalogTest`（契约测试）；`AgentToolCatalog` 加开关；`AgentModelClient` 打开开关；`AgentLocalTools` 6 个实现；`AgentToolRequirements` 注册；`McpServerManager` 加 `enableAllDiscoveredTools`；`McpRunContext` 把工具名公式提取为唯一实现；`AgentSensitiveToolPolicy` 豁免管理工具脱敏；两个既有测试补开关
- [x] **2026-10-19** 排掉 MCP 功能的两个隐藏坑：① `discover()` 的 `intersect` 会让新服务器的 `enabledToolNames` 为空集，`activeTools` 变成 0 个，加完等于白加 —— 用 `enableAllDiscoveredTools` 只在 Agent 路径启用全部工具；② `AgentSensitiveToolPolicy` 的 `mcp_` 前缀把管理工具也判成敏感，结果被 transcript 替换成「敏感工具未写入持久会话」，连带 `modelTools`（下一轮要调的工具名）一起丢 —— 加 `mcpManagementTools` 精确白名单豁免，远端 MCP 工具仍照旧敏感

- [x] **2026-10-19** 修 P082 单轮 MCP 工具额度截断：`MAX_RUN_TOOLS` 64 → 256（全局共享，多服务累加）、`MAX_DISCOVERED_TOOLS` 128 → 512、`MAX_LIST_PAGES` 8 → 16。把 `server.activeTools.forEach { if (size>=MAX) return@forEach }` 这种「假截断（只跳过当前元素，外层空转）」换成 `take(remaining)` 显式截断。目录内单工具描述截到 400 字（数据库仍存 2000）。`mcp_add_server` 改为返回 `registeredToolCount` + `unavailableModelTools`，不再谎报「全部启用」；`mcp_list_servers` 补 `enabledToolTotal` / `runToolLimit` / `overLimit` + 超额精简提示。新增 2 条契约测试
- [x] **2026-10-19** 调研 Operit（`AAswordman/Operit`）的 MCP 处理方式作参照：其 `MCPPackage.toToolPackage()` 把工具直接 `map` 成 `ToolPackage`，`listTools()` 无数量上限，靠「包制」（模型先选包再选工具）从结构上避免平铺目录爆炸。Eta 是平铺架构，照搬需重做工具层，因此本轮只放宽额度 + 对齐文案
- [x] **2026-10-19** 第四轮真机验收通过（用户原话：「好了，验收通过」）：51 + 49 两个服务共 100 个工具全部进入单轮目录，原先被裁掉的 36 个恢复可调，`TOOL_NOT_AVAILABLE` 不再复现。提交 `b4de086`（代码）+ `59a78c2`（文档）
- [x] **2026-10-19** 按用户要求删除 `demo/` 目录（8 个 HTML 预览原型，204K）并写入 `.gitignore`：**UI 预览图/调参台只在本地生成，不入库**。文档中对 `demo/` 的历史引用改为「原路径 + 已删除」的说明式写法，保留可追溯性
- [x] **2026-10-19** 内置逆向 skill（第六轮，待验收）：用户上传 `外挂逆向skills.zip`，内含 `game-hacking`（19 文件 489KB）与 `reverse-engineering`（5 文件 89KB）。拆成两个内置 skill：`re-mobile`（215KB / 14 reference / 默认开）与 `re-pc`（339KB / 18 reference / 默认关）。**内容 100% 保留**，只做两件必要的事：① 原 `reverse-engineering/SKILL.md` 26,717 字拆成「流程层 SKILL.md（9,584 字）+ 分散到 reference」；② 原 `cheatsheet.md` 274,602 字按主题切成 11 份（最大 49,504 字）。所有 33 个文件均在 `skills_read_resource` 的 64,000 字符上限内
- [x] **2026-10-19** 排掉 skill 内置的三个硬约束：① `AgentPromptBuilder.kt:211-215` 对 description 做 `.take(180)`，超长会在句子中间截断（原 `game-hacking` description 311 字被砍 42%，`DLL注入`/`Frida`/`il2cpp` 全丢；原 `reverse-engineering` 505 字被砍 64%，28 个中文触发词一个不剩）→ 新 SKILL.md 的 description 控制在 122/129 字，触发词表移到正文；② `skills_read`/`skills_read_resource` 的 `maxChars` 上限 64,000、默认仅 16,000 → 按主题切分而非删内容；③ `SkillRuntime.kt` 的 `seedMissingBuiltins` 写死 `enabled = true`，无法做「默认关闭」→ `BuiltinSkillAsset` 新增 `defaultEnabled` 字段（默认 true 保持旧行为），`re-pc` 在 manifest 显式写 `false`
- [x] **2026-10-19** 补齐逆向工具链（A 组）：Debian rootfs 装 `binutils 2.44`（readelf/objdump/nm/strings/addr2line/size）、`bsdextrautils 2.41`（hexdump）、`xxd 9.1`；Python 3.14 装 `capstone 5.0.7` + `lief 1.0.0`（需 `--break-system-packages` 绕 PEP 668）。实测 `readelf` 能读 `.so`、`capstone` 能反汇编、`lief` 能解析 ELF/PE
- [x] **2026-10-19** 解掉 APK 回编译限制（C 组）：装 `aapt2 2.19` + `apksigner 0.9`；`LinuxApkAnalysisInstaller` 的 `APKTOOL_WRAPPER` 原本写死 `b|build` 直接报 `APKTOOL_BUILD_UNAVAILABLE`，改为自动追加 `--aapt /usr/local/bin/aapt2-wrapper`。**踩坑**：apktool 会把 aapt2 复制到 `/tmp` 再执行，复制丢 x 位，报 `Syntax error: "(" unexpected` → 新增 `AAPT2_WRAPPER`（`exec /usr/bin/aapt2`），复制后仍能跑。`APK_ANALYSIS_REVISION` 1→2 让已装环境重走安装；验证步骤新增 `aapt2-wrapper version` 检查
- [x] **2026-10-19** 改 `AgentPromptBuilder.kt:122` 的过时提示：原文写死「当前 apktool 不支持回编译」，等于每次 run 都告诉模型这条路走不通。改为「jadx/apktool/smali/baksmali/aapt2/apksigner 已就位 + 回编译用 `--aapt /usr/local/bin/aapt2-wrapper` + 签名用 apksigner」
- [x] **2026-10-19** 第六轮真机验收通过：用户重装 APK 分析工具后 `profile=2` 生效，六项验证（java/jadx/apktool/smali/baksmali/aapt2-wrapper）全 OK。补装 `pyelftools` + `pyaxmlparser` 并加 `apkinfo` 软链（不解包即可读包名/权限）
- [x] **2026-10-19** 修正第六轮引入的故障：`linuxApkJavaInstallCommand` 把 `aapt2` 当独立 apt 包名，Debian 无此包（由 `android-sdk-build-tools` 提供），整条命令 `Unable to locate package` 失败，UI 却报「安装 OpenJDK 25 失败」（实际 java 早已装好）。改为 `android-sdk-build-tools`，提交 `a493d33`
- [x] **2026-10-19** 实测澄清 APK 工具链真实能力：`apktool d` / `jadx`（6391 文件 37MB）/ `baksmali`（8.9MB dex）/ `smali` / `apksigner` 全部跑通；**`apktool b` 资源回编译走不通**（Debian aapt2 仅 2.19，apktool 3.0.3 传 `--no-compile-sdk-metadata` 报 `unknown option`）。逆向闭环改走 dex 直换（`apktool d → baksmali → 改 smali → smali → zip 替换 → apksigner`），该链路已实测完整跑通

## 进行中（交接给下一个 Agent）

- [ ] **2026-10-19** 第七轮 Frida 内置（**代码已定稿，待 CI 编译 + 真机验收**）：用户要求 Frida 随 APK 分发，不要手工装本机。已写 `agent/terminal/FridaInstaller.kt` + `LinuxEnvironmentScreen.kt` 三态按钮 + `strings.xml` 8 条文案 + `re-mobile/SKILL.md` 第 4 步重写 + `AgentPromptBuilder` Frida 提示。本轮把四个架构的 sha256/size 全部补齐、修了三处会在真机上直接失效的缺陷（见下）。**待办：push 走 CI 编译 → 按报错修 → 真机验收**。详见 `docs/STATE.md` 的「第七轮」段
- [x] **2026-10-19** 清掉上一轮误装的本机残留：`kill -9 28082`（SIGTERM 被 ksu 域忽略）、`rm -f /data/local/tmp/.eta/etad /data/local/tmp/.eta/d.log && rmdir /data/local/tmp/.eta`。**84,805,800 字节**，端口 8899 已释放（原来监听 `0.0.0.0:8899`，不清掉新代码的 `startServer()` 必然端口冲突，`frida-ps` 也会连到旧进程上给出假阳性）

## 进行中

_暂无_

## 待办

### 高优先级

- [ ] **校验固定签名的首次云端构建**：推送后确认 Actions 日志里打印的 SHA256 指纹为 `C9:73:FE:...:1F:0A`，且 apksigner verify 通过
- [ ] **keystore 离线三份备份**：`eta-release.jks` + 密码文本，分别存手机本地、网盘、邮箱（文件不可再生）
- [ ] **真机功能验证**：在目标设备上验证 Xposed 模块加载、无障碍服务、终端、MCP 连通
- [ ] **确定二改方向**：明确要改什么功能（改 UI？加工具？换 Provider？改 hook 目标 ROM？）

### 中优先级

- [ ] 补 `docs/MODULES.md` 里除 agent/hook/core 之外模块的细化条目
- [ ] 把 graphify 增量更新挂到 git pre-push hook（可选）
- [ ] 考虑给 graphify 接 LLM backend 自动命名社区（当前是 `Community 0..435` 占位名）
- [x] **2026-10-18** 用户决定仓库保持 public（不再讨论转 private）

### 低优先级

- [ ] 修复 4 个 Kotlin 文件的 tree-sitter 解析缺陷（需等上游 tree-sitter-kotlin 更新）
- [ ] 补 ADR：为什么选 Graphify 而不是 AOCI-CODE

## 技术债 / 已知问题

| 项 | 影响 | 状态 |
|---|---|---|
| CI 签名每次变 | 无法覆盖升级 | 待处理，见待办高优先级 |
| 4 个 Kotlin 文件图谱解析不全 | `graphify explain` 查不到完整信息 | 已知缺陷，直接读源码 |
| CI 里 `.github/workflows/android-release.yml` 已删除 | 上游的发版流水线不可用 | 有意为之，改用自建 build-apk.yml |
| `docs/` 下上游的截图（54MB）仍在 | 仓库体积大 | 可考虑清理，但不影响构建 |

## 决策记录

重大架构/工具选择见 `docs/adr/`。已记录：

- ADR 001：放弃 AOCI-CODE，采用 Graphify
- ADR 002：自建 CI 而非复用上游发版流水线
- ADR 003：GitHub Actions 云编译而非本地编译