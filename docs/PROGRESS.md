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

- [x] **2026-10-19** 第七轮第二轮（**真机故障修复**）：真机实测 RPC 通但 attach 全部失败，`busybox sleep` 也报 `bootstrapper crashed with signal 11`。排除版本错配/SELinux/进程冻结/server 权限后，把变量隔离到**唯一一个：etad 放在哪个目录**。手工把同一份 84,805,800 字节的 etad 从 `filesDir`（0700）复制到 `/data/local/tmp/eta-frida/`（755）再起 server，Agent 在 App 内 attach 成功：`attached: pid=18385` / `module_count: 13` / `first_module: toybox` / `exit=0`。
  - 代码改动：`FridaPaths` 新增 `RUNTIME_DIR` + `runtimeServerFile()`；新增 `stageServer()`（起之前复制 + chmod 755，大小一致时只补权限）；`startServer()` / `detectServerPid()` 改用 runtime 路径并先 `cd`；新增 `purgeStaleServer()` 清掉老版本跑在 filesDir 的残留（**只比 cmdline 第一个 token，整体 contains 会自杀**）；`ensureRunning()` 加 `staleForeign` 判定；`stopServer()` 也能清野生进程
  - 顺带修：中文 `values-b+zh+Hans/strings.xml` 补 7 条 `linux_frida_*` 并同步 `linux_apk_tools_ready/summary`（上一轮只改了英文）；`AgentPromptBuilder` 与 `re-mobile/SKILL.md` 的路径说明全部改成 runtime 目录
  - **已验证**：编译（CI 通过）、`stageServer()` 端到端（点「停止」→「启动」后 attach 成功）、`purgeStaleServer()`（未自伤、未报错）
- [x] **2026-10-19** 第十二轮：Android SDK + Gradle（手机真能编 APK）+ dex2jar + angr CLI（CI run #44 绿）
  - **纠正上一轮的错误结论**：我说「Android SDK 装不了」是错的。错在把「Google 不发 aarch64 版」直接推成「没得装」。看了用户给的两个真实项目（`AAswordman/Operit`、`jieapi/aicode`）才打通
  - **两个项目的做法**：Operit 下 x86_64 cmdline-tools + 自带 `aapt2-arm64-v8a` 替换，还解压改写 Gradle 缓存里的 `aapt2-*-linux.jar`；aicode 直接用 `lzhiyong/android-sdk-tools` 的 `android-sdk-tools-static-aarch64.zip` 覆盖原生二进制，并用 `android.aapt2FromMavenOverride`。**本实现走后者**（覆盖 + override），因为不碰缓存、升级 Gradle 不失效
  - **真机实测（PRoot 里，不是推测）**：`aapt2 version` → `2.19-OS3.0.308.0`；`adb version` → `1.0.41/35.0.2`；`gradle --version` → `9.8.1`；`gradle hi` → **BUILD SUCCESSFUL in 9s**；`aapt2 link -I android.jar` → **linked.apk (1107 字节)**
  - **装什么**：ARM64 工具包 14MB（aapt2/aapt/aidl/zipalign/dexdump/adb/fastboot/sqlite3）+ android.jar 27MB（官方 `platform-35_r02.zip`，纯 Java 存根）+ Gradle 9.8.1 151MB（`services.gradle.org` 独立包，sha256 校验）
  - **不装 apt 的 gradle 4.4.1**：2017 年版，不认 `plugins {}`、不认 Kotlin DSL、不认现代 AGP，只能 `--version`
  - **dex2jar 并入 RE_LAB**：19MB，纯 Java（`d2j-dex2jar.sh` 最后一行就是 `java -classpath`）。实测 `classes.dex(880B) → out.jar(402B)`
  - **angr CLI 接通**：angr 自带 `angr` / `angr-mcp` 两个命令，只是不在 PATH。实测 `angr decompile libz.so.1` 出完整 C 伪代码，不用写 Python
  - **又修两处 P081**：`ANDROID_SDK_STEPS` 原本声明在 profile 之后（前向引用）→ 用脚本做块移动；`verify_re_lab_profiles.py` 的 ALL 硬编码误报
  - **run #44 绿**，产物 `eta-apk-19b5f55…`（34,571,265 字节）
- [x] **2026-10-19** 第十一轮：Android NDK 导入入口（方案 B）+ d8/r8 + build-tools 接 PATH（CI run #41 绿）
  - **NDK 是独立机制**：`AndroidNdkInstaller`，用户自备压缩包，**不内置任何资产**（728MB 包 / 解压 2.5GB，内置会让 APK 膨胀十倍）
  - **三条死路实测**（决定了架构）：Debian chroot 无 `/system/bin/linker64`；PRoot 看不到 `/system` 且无 bionic；sdcard 是 noexec。只有 `/data` 分区可执行（nosuid,nodev 但无 noexec）→ 装 `context.filesDir/android-ndk`
  - **关键实现**：先落盘再解压（SAF 流不支持 mark/reset，判不了 xz/gz 魔数）；双段进度（复制 / 解压分开回报）；3.2 倍膨胀比做空间预检；归档多套一层目录自动摊平；clang 探测**不能**用 NDK 版本号拼（Pkg.Revision=29 但二进制是 clang-21）；tar 路径穿越防护；多版本槽 + `.active` 指针
  - **UI 位置**：放在 `selectedToolsReady` **之外**——NDK 跟 Debian rootfs 无关，不该因为没装 Linux 环境而藏起来
  - **d8/r8**：Debian 没有 d8/dx 包，但 r8 是纯 Java 程序（JVM 即可，与架构无关）。固定 9.5.26（Maven 上大量 `-dev`），下载到 `/opt/eta/re-tools/`，生成 `/usr/local/bin/d8` 与 `r8` 包装。实测 `javac → d8 → classes.dex 880 字节`，`dexdump` 验出 `DEX version '035'`
  - **build-tools**：`android-sdk-build-tools` 在 aarch64 上真实存在（29.0.3），但装在 `/usr/lib/android-sdk/build-tools/<ver>/` 不在 PATH——这就是「工具缺失」错觉的来源。动态找版本目录 + 软链 + `apksigner.jar` 补链
  - **angr 修好**：z3 必须锁 `5.1.0.0`（angr 元数据写死），装最新 5.3 会 `ImportError: libz3.so.5.1`
  - **run #40 踩坑（P085）**：`extractArchive` 三层 `use{}` 少闭合一层 → class 从 206 行被提前截断 → 报 20+ 条 `Unresolved reference`。**根因只是一个 `}`**。新增「花括号深度归零」检查 + 自测（注入事故必须报错）
  - **run #41 绿**，产物 `eta-apk-f9be503…`（34,565,189 字节）
- [x] **2026-10-19** 第十轮：修掉「安装逆向分析工具组失败」+ 安装进度可视化（用户真机报错后查证）
  - **根因 1（radare2）**：`apt-cache policy radare2` → `Candidate: (none)`，`apt-cache search radare` 零结果。**Debian trixie 源里没有 radare2**。第九轮把它写进 `packages` 走 `eta-apt install`，apt 报 `has no installation candidate`，**整条 profile 第一步就死，后面 5 个 pip 包一个都没跑**。→ 改走 GitHub Releases 的 `radare2_6.2.4_arm64.deb`（ELF aarch64、`Depends: libc6`、sha256 `bbf48ee7…32ef1` 已对官方 checksums.txt），由 `VerifiedArtifactDownloader` 落到 rootfs/tmp 再 `dpkg -i`
  - **根因 2（PATH）**：`sysconfig.get_path("scripts")` = `/opt/eta/python/cpython-3.14.7-linux-aarch64-gnu/bin`，**不在 PATH 里**，所以 `pip install mitmproxy` 成功了但 `command -v mitmdump` 找不到。→ 新增一步统一在 `/usr/local/bin` 建软链
  - **进度可视化**：引入 `LinuxPackageStep`（每组件一步、单独标签、单独判失败），RE_LAB 拆 8 步、ANGR 拆 2 步；`PackageProfileInstallProgress` 加 `item/stepIndex/stepTotal`；状态卡加 `LinearProgressIndicator`（下载按字节、安装按步数）；`Failed` 加 `detail` 把 apt/pip 输出尾部直接展示给用户
  - **真机实测**：chroot 内 `r2 -v` → `radare2 6.2.4 +1 abi:152 @ linux-arm_64`；apkid 3.1.0 / mitmproxy 12.2.3 / objection 1.12.5 三个包均装成功
  - **run #37 又踩一坑（P082）**：Kotlin 字符串里写 `"$eta_bin"` 被当模板插值 → `Unresolved reference 'eta_bin'`。必须写 `"\$eta_bin"`。**四个校验脚本全没拦住**，因为它们只查行/结构，不查字符串内容。→ `ci/static_check.py` 新增第 2 项「shell 变量转义」+ `ci/self_test_static_check.py` 反向验证（注入事故必须报错且指向 eta_bin）
  - **run #38 绿**，产物 `eta-apk-2024f51…`（34,515,521 字节）
- [x] **2026-10-19** 第九轮：内置逆向分析工具组（用户给了 17 项待内置清单，逐条核实后只做能跑的）
  - **查证推翻了 4 条（其中第 1 条在第十轮被自己纠正）**：① 当时判断「radare2 GitHub Releases 没有 linux-arm64」——**这是错的**，6.2.4 实际有 `radare2_6.2.4_arm64.deb`，第十轮改用这个；② `dex2jar` **PyPI 上不存在**（`/pypi/dex2jar/json` 返回 404）→ 不做，jadx 已覆盖；③ Android NDK **Google 从不发 aarch64 Linux 版**（r30 只有 windows/darwin/linux-x86_64）→ 不做；④ gradle + SDK cmdline-tools 无 aarch64 官方版、>2GB → 不做。另外 blutter（要手机编 Dart 运行时）、KSU 内核模块（要内核头）也排除
  - **新增 `RE_LAB` profile（约 400MB）**：z3-solver / unicorn / apkid / objection / mitmproxy / radare2。五条 pip 各自 `|| exit <code>`，最后逐个 `command -v` 与 `import` 探活，缺一个都不写 marker
  - **新增 `ANGR` profile（约 900MB，默认不装）**：依赖树太大，做成用户按需点
  - **配套**：`SKILL.md` 补 radare2 / apkid / z3 / unicorn 的实战用法与 mitmproxy 完整抓包流程（含 Android 7+ 系统证书必踩的坑）；`AgentPromptBuilder` 同步告知 Agent
  - **CI 踩坑（P081）**：`val ALL = listOf(..., RE_LAB, ANGR)` 写在 RE_LAB/ANGR 声明之前 → Kotlin `object` 属性按声明顺序初始化，编译报 `Variable 'RE_LAB' must be initialized`。run #34 红，修到 run #35 绿
  - **Skill 转换（另一件事）**：把用户给的 227 个 AoRly 技能按 Eta 规则转成 **77 个单技能 ZIP**（`/sdcard/Download/eta-skills/zips/`），全部通过重写的安装器规则校验；后续又从 `zhaoxuya520/reverse-skill` 补了 `radare2` 一个。**判断用了三版规则才干净**：v1 关键词打分把 7 份 idasql（IDA 插件）文档误判成手机端；v2 目录名硬排除解决；v3 把优先级改成「目录名 > 人工白名单 > 内容锚点」，避免误杀 `ai-reverse-workflow`
- [x] **2026-10-19** 第七轮终验通过（用户原话「成功 + 13」）：用户装 CI 产物 → 点「停止」→「启动」→ Agent 在内置终端里 `attach` pid 15851，输出 `Connected to 127.0.0.1:8899` / `Attaching...` 无报错 / `Process.enumerateModules().length` → `13`。**attach 链路彻底通了，Frida 内置功能交付完成。** 全程无电脑、无 adb、无手工推文件，「别人装上也能用」达成
- [x] **2026-10-19** 第八轮：补齐逆向工具链的四个缺口（用户只说「做」，按上一轮列出的 1→3 项执行）
  - **1. 装 `frida-dexdump 2.0.1`**：`re-mobile/SKILL.md` 第 5 步原来写「先试 frida-dexdump」，但工具根本没装——frida-tools 里没有这个子命令，脱壳第一步是空的。`FridaPaths` 加 `DEXDUMP_VERSION`，pip 命令追加该包，软链清单加 `frida-dexdump`。**关键：跳过 pip 的条件从「frida 版本对上」改成「frida 版本对上 **且** `import frida_dexdump` 成功」**，否则老用户升级后版本判断通过、永远补不上脱壳工具。装不上只 `echo FRIDA_DEXDUMP_MISSING` 不判死，避免整条 Frida 退回不可用
  - **2. 删掉一条不存在的技术路线**：原 SKILL.md 写「等 Eta 侧从 Google Maven 拉 ARM64 版 aapt2」——**Google 官方 Maven 只发 linux(x86_64)/osx/windows 三份，没有 aarch64**。改成明确结论：资源回编译只能去电脑上做，别在手机上找
  - **3. 加 Root 前置判定**：SKILL.md 开头新增「第 0 步」——无 Root 时 Frida 起不来、改完 dex 也签不上，只能做静态分析。`AgentPromptBuilder` 同步补这段，避免 Agent 逐条试必然失败的步骤
  - **顺带**：`game-engines.md` 的 Unity IL2CPP 段加一段明确边界（Il2CppDumper 是 .NET 程序，手机装不上，只能电脑跑完回传 `dump.cs` / `script.json`），并给出手机端实际能做的四件事
  - **防回归**：新增 `ci/verify_frida_cli.py`，从 Kotlin 源码反解真实 shell 串 → `bash/dash/sh -n` 三份语法校验 → 沙箱跑「老用户升级」与「已装全」两个分支。**起因是本轮第一次编辑就把整条命令删空只留 `android` 一行，肉眼完全看不出**。已接入 `build-apk.yml` 作为第一个校验步骤
  - **未验证**：编译与真机（本地不能编译，走 CI）；pip 实际能否装上 `frida-dexdump` 需在真机点一次安装确认

## 进行中

_暂无_

## 待办

### 高优先级

- [ ] **校验固定签名的首次云端构建**：推送后确认 Actions 日志里打印的 SHA256 指纹为 `C9:73:FE:...:1F:0A`，且 apksigner verify 通过
- [ ] **keystore 离线三份备份**：`eta-release.jks` + 密码文本，分别存手机本地、网盘、邮箱（文件不可再生）
- [ ] **真机功能验证**：在目标设备上验证 Xposed 模块加载、无障碍服务、终端、MCP 连通
- [ ] **真机验证 frida-dexdump**：装新 APK → 设置 → Frida 点一次安装 → 确认日志出现 `FRIDA_DEXDUMP_OK` 且 `/usr/local/bin/frida-dexdump` 可执行。这是第八轮唯一没在真机跑过的项
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