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
| **逆向 skill 内置** | ✅ 第六轮已验收：`re-mobile`（默认开）+ `re-pc`（默认关），33 个 reference 全部 ≤ 49,504 字 |
| **Frida 内置** | ✅ **第七轮已验收**：server 跑在 `/data/local/tmp/eta-frida`（755），attach 实测成功。详见下方「第七轮」段 |
| **脱壳 dexdump** | ✅ **第八轮已改**：`frida-dexdump 2.0.1` 随 Frida 一起装；SKILL 删掉不存在的 ARM64 aapt2 路线、加 Root 前置判定。真机未验 |
| **逆向分析工具组** | ✅ **第九轮已编译通过（run #35）**：z3/unicorn/apkid/objection/mitmproxy/radare2 一键装；angr 独立开关。真机未验 |
| **外部 skill 导入** | ✅ 76+1 个单技能 ZIP 已生成到 `/sdcard/Download/eta-skills/zips/`，全部通过 Eta 安装器规则校验 |
| **Android NDK 导入** | ✅ 第十一轮已编译通过（run #41）：支持自定义导入用户自己的 NDK tgz，多版本并存。真机未验 |
| **Android SDK + Gradle** | ✅ 第十二轮已编译通过（run #44）：aapt2/aapt/aidl/zipalign/dexdump/adb + android.jar + Gradle 9.8.1。真机未验 |
| **渗透工具 7 组** | ✅ **第十四轮已验收**：侦察/Web/网络/凭据/云审计/内网AD/无线，各自独立 marker。AD 组含 Rust 工具链（现场编 aardwolf） |

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

**APK 分析工具链（本轮补齐，含一次真实故障修正）**

`LinuxApkAnalysisInstaller` 原本只装 jadx/apktool/smali/baksmali，且 wrapper 写死 `b|build` 直接报 `APKTOOL_BUILD_UNAVAILABLE`。

**第一次提交 `a8ab78a` 引入了故障**：我把 `aapt2` 当独立包名写进 apt 命令，但 Debian 里 **`aapt2` 不是包名**，它由 `android-sdk-build-tools` 提供。整条 `eta-apt install openjdk-25-jdk-headless aapt2 apksigner` 因 `Unable to locate package aapt2` 失败，用户看到的现象是「安装 OpenJDK 25 失败」——但 `apt-cache policy` 显示 **java 25 其实早已装成功**（`25.0.4.1+1-1~deb13u1`），失败的只是同一条命令里的后续步骤。

**已修（`c0` 之后的提交）**：包名改为 `android-sdk-build-tools`。

**实测结论（重要，别再走错）**

| 能力 | 状态 | 说明 |
|---|---|---|
| `apktool d` 解包 | ✅ 跑通 | 拿当前 APK 实测，Manifest + smali + res 全部正常产出 |
| `jadx` 反编译 | ✅ 跑通 | 6391 个 Java 文件 / 37MB |
| `baksmali` dex→smali | ✅ 跑通 | 8.9MB dex 成功反汇编 |
| `smali` smali→dex | ✅ 跑通 | 重组回 8.9MB dex |
| `apksigner` 签名 | ✅ 跑通 | keystore 自签 + 14MB APK 产出 |
| **`apktool b` 资源回编译** | ❌ **走不通** | Debian `aapt2` 只有 **2.19**，apktool 3.0.3 传 `--no-compile-sdk-metadata` 报 `unknown option` |

**所以逆向闭环走这条**（已实测）：

```
apktool d 解包 → baksmali 反汇编 → 改 smali → smali 重组 dex
→ zip 替换进 APK → apksigner 签名 → pm install
```

**不要**教用户用 `apktool b` 回编译资源——版本链不匹配，硬试必失败。这条已写进 `re-mobile/SKILL.md` 第 8 步的「路线 A / 路线 B」和 `AgentPromptBuilder` 的提示里。

另外两类工具已手工装进用户的 Debian rootfs，**重装 APK 不会丢，但换设备要重装**：

```bash
binutils 2.44          readelf/objdump/nm/strings/addr2line/size
bsdextrautils 2.41     hexdump
xxd 9.1                xxd
aapt2 2.19 + apksigner 0.9    （android-sdk-build-tools 提供）
capstone 5.0.7         pip --break-system-packages
lief 1.0.0             pip --break-system-packages
```

**已知限制**

- **Frida 已内置（第七轮）**：`frida-server` 由 App 自己下载校验起在 `/data/local/tmp/eta-frida`，UI 有 Start/Stop。**不做开机自启**（常驻耗电，且回到「本机手工配置」老路）。详见下方「第七轮」段
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

**第十四轮真机验收通过**（CI run #51，commit `e9abd58` 产物）。渗透组 7 组全部装上可用。

**这一轮修掉的四个根因（供后人查）：**

| 问题 | 根因 | 修法 |
|---|---|---|
| AD 组 netexec 失败 | `aardwolf` 需 Rust 编译，而 `TMPDIR` 指向 chroot 看不到的 Android 路径 | 装 `rustc`+`cargo`，所有 pip 步骤加 `export TMPDIR=/tmp` |
| CLOUD 组一直转 | `scoutsuite+pacu` 合成一步，且互相覆盖 protobuf / urllib3 | 拆成两步 + 加「修复依赖」步拉回版本 |
| （连带）mitmproxy 挂 | NetExec 把 cryptography 降到 42.0.8 | 拉回 `cryptography==47.0.0` |
| （连带）angr 挂 | scoutsuite 把 protobuf 降到 3.20.3 | 拉回 `protobuf>=6.33` |

**第十五轮已完成并编译通过（CI run #60，commit `95348c8`）；卡片美化 + 角色对话插话已验收。**
**⚠️ 进行中：多并发 Agent 改造（用户要求方案乙）**

**第 1 批已完成并编译通过（CI run #64，commit `6bdd0cdf`），等真机验收。**

用户已拍板三项决策：
1. **终端工作区共享**同一个 `/workspace`（不按会话隔离）
2. **GUI 操作接受串行排队**（无障碍/浏览器/Frida 物理上只能一个）
3. **分 3 批做**，每批真机验一次

**影响面清单**：`docs/CONCURRENT_AGENTS.md`（也放在 `/sdcard/Download/Eta并发改造-受影响功能清单.md`）

**第 1 批做了什么（多 run 会话 + 主任务浮层）**：
- `AgentRuntimeService`：`activeSession` 单值 → `activeSessions: ConcurrentHashMap`
- `startRun()` 不再无条件 `cancel()`（这是「互相顶死」的根因）
- 新增 `primaryRunId`：浮层只能对应一个 run，其余安静跑
- `AgentAppState`：`currentRunId` 拆成 `observedRunIds` + `latestRunId`，
  新增 `hasActiveRun` / `stopRun(runId)`

**第 1 批验收方法**：
1. 装 run #64 的产物（`eta-apk-6bdd0cdf…`，34,632,139 字节）
2. 对话 A 里让 agent 跑一个长命令（如 `sleep 120`）
3. 切到对话 B，也发一条长命令
4. **预期：A 不再被掐死，两条都在跑**
5. 看完 A 的回复 → 切回 A 应能看到它在继续输出
6. 点 B 的停止 → **只停 B，A 不受影响**

**已知限制（第 2/3 批才做）**：
- 两个 agent 同时操作界面/浏览器/Frida 会打架（串行化未做）
- 浮层只显示主任务，看不出「还有几个在跑」



**功能：运行中可继续发消息（待发送队列，方案甲，对齐 Operit）**

- 运行中打字按发送 → 进「待发送」卡片（**在输入框外面**、上方），不打断 agent
- agent 跑完 → 自动发队首第一条
- 每条可点发送箭头**立即插队**（走取消旧 run 再补发）、点 × 删除
- 输入栏三态：运行中+有文字=入队（另给独立停止键）/ 运行中无文字=停止 / 空闲=发送

**验收要点**：

1. 装 run #60 的产物
2. 让 agent 跑一个长任务（比如解压大包）
3. 运行中打字 → 按钮应变成**入队箭头**，点它 → 消息进上方卡片，agent 不中断
4. 等任务跑完 → 队列那条约 1 秒后自动发出，变成正常对话
5. 再试一次：运行中点卡片里的**发送箭头** → 当前任务被中断，这条立刻发出

**本轮踩的坑（P095，必须记住）**：输入栏在 `AgentChatBody.kt` 里是**三层函数**
（`AgentChatBody` → `AgentChatScaffold` → `AgentChatBottomBar`），
参数要逐层透传，漏一层就 `Unresolved reference`。**连踩六轮 CI 才定位到。**

- 之前未验收的仍待验：Android SDK + Gradle、NDK 导入、77 个 skill ZIP

第五轮验收（删除 demo 目录）用户原话：「刚刚我去Github仓库看了一下，demo文件夹里面都是之前用的HTML预览图，请你删除这个文件夹和内容，以后预览图只在本地生成」。
第六轮用户定调：「按照你推荐进行」= 530KB 全量切分 + A/C 工具组 + 解掉回编译限制。**已验收**。
第七轮用户定调：「我要的是内置到App当中去，而不是装在我的本机上，你装在我的本机上别人如何使用呢？」——这是本轮最重要的纠偏。**已验收**，见下方「第七轮」段的终态记录。
第八轮用户定调：「做，做完停下来我还有事情」——按我列出的性价比排序只做了前 3 项（都是小改动），第 4 项 mitmproxy、第 6 项 aarch64 aapt2 未做，见下方「第八轮」。

### 第八轮做了什么（2026-10-19）

| # | 改动 | 文件 |
|---|---|---|
| 1 | 装 `frida-dexdump 2.0.1`，跳过 pip 的条件加入 `import frida_dexdump` 判定 | `FridaInstaller.kt` |
| 2 | SKILL.md 删掉「从 Google Maven 拉 ARM64 aapt2」这条**不存在**的路线，改成「资源回编译只能去电脑」 | `re-mobile/SKILL.md` 第 8 步 |
| 3 | SKILL.md 开头新增「第 0 步：先确认有没有 Root」，无 Root 明确降级为纯静态 | `re-mobile/SKILL.md`、`AgentPromptBuilder.kt` |
| 4 | Unity IL2CPP 段加边界说明（Il2CppDumper 是 .NET 程序，手机装不上） | `game-engines.md` |
| 5 | 新增 `ci/verify_frida_cli.py` 并接入 `build-apk.yml` 第一步 | `ci/`、`.github/workflows/build-apk.yml` |
| 6 | 中英文 Frida 文案补上 `frida-dexdump` | `strings.xml` × 2 |

**起因**：用户问「内置的工具和 skill 够不够用」，我逐条比对源码后发现四处缺口，其中两处是**文档比代码跑得更远**——SKILL 写「先试 frida-dexdump」但工具没装；SKILL 写「等 Eta 换成 ARM64 aapt2」但那个包根本不存在。这类脱节比缺功能更危险，因为它会让 Agent 走进死路。

**本轮踩的坑（必须记住）**

第一次用 `edit_file` 改 `installCliCommand()` 时，old 块匹配失败但**整个字符串体被删空，只留下一个 `android` 字样**。肉眼读 diff 完全看不出问题，只有把 Kotlin 反解成真实 shell 串才能发现。所以才写了 `ci/verify_frida_cli.py`——它会：

1. 从 Kotlin 源码反解真实命令（含 `\$` 字面美元符用哨兵处理，避免被模板正则误吃）
2. `bash -n` / `dash -n` / `sh -n` 三份语法校验
3. 沙箱跑两个分支：老用户（frida 在、dexdump 缺）必须走 pip；已装全必须走 `FRIDA_CLI_ALREADY`

**改这个文件之后一定要跑 `python3 ci/verify_frida_cli.py`。**

**CI 已验证（2026-10-19，run #31 / commit `2d5c231`）**：`conclusion: success`，其中「校验 Frida 安装命令」步骤单独通过，编译产出双 APK。用户可直接下 Artifacts 装。

### 第八轮没做的（性价比排序里排 4、6 的两项）

| 未做项 | 内容 | 建议 |
|---|---|---|
| mitmproxy 抓 HTTPS | `protocol-capture.md` 只有 pinning 绕过思路，没有抓包器安装 + 系统证书落地步骤 | 用户要真抓加密流量时再做 |
| 资源回编译根治 | 自建 aarch64 aapt2（交叉编译 AOSP） | 大工程，工期以天计，除非确有改资源硬需求 |

---

## ✅ 第七轮：Frida 内置（已验收，终态见下）

### 用户的核心要求（务必遵守）

**Frida 必须做成 App 内置能力，随 APK 分发，别人装上点一下就能用。**

绝对不要再走「手工装到本机」的路线。上一轮我犯了这个错，把 84MB 的 `frida-server` 手工推到了用户手机的 `/data/local/tmp/.eta/etad`，用户明确指出：**「你装在我的本机上别人如何使用呢？」**

**该残留已于本轮清理完毕**：`kill -9 28082` + 删除 `/data/local/tmp/.eta`，端口 8899 已释放。

### 本轮改了什么（2026-10-19 第二轮）

| # | 改动 | 为什么 |
|---|---|---|
| 1 | **四个架构的 sha256 + sizeBytes 全部补齐** | 原来 arm / x86_64 / x86 是空串和 0，下载器校验必失败。数据从 `https://api.github.com/repos/frida/frida/releases/tags/17.24.2` 拉的，不是猜的 |
| 2 | `installCli()` 改为**运行时探测 python** | 实测 rootfs 里 `/usr/bin/python3` **不存在**，`python3` 是 `/usr/local/bin/python3` → uv 托管的 `/opt/eta/python/cpython-3.14.7-linux-aarch64-gnu/bin/python3.14`。路径随架构/版本变，写死必炸 |
| 3 | `installCliCommand()` 加**版本已对就跳过 pip** | 重装一次要下 30MB，按钮不该每次点都卡半天 |
| 4 | 装完 CLI 后统一 `ln -sf` 到 `/usr/local/bin` | chroot 的 PATH 是调用方注入的，不保证含 uv 的 bin 目录 |
| 5 | 连通复核改用**绝对路径** `/usr/local/bin/frida-ps` | 原先用 `command -v`，实测在 chroot 里直接误报失败（rc=69） |
| 6 | 判活主据改成 **TCP 探测 127.0.0.1:8899** | Android 10+ 的 `/proc` 挂 hidepid，App 身份读不到 root 进程的 `/proc/<pid>/stat`，原判活永远返回「已停止」 |
| 7 | pid 定位改在 **root shell 里扫 `/proc/*/cmdline`** | `setsid ... &` 的 `$!` 是短命父进程，pid 文件是脏数据；且 cmdline 是 NUL 分隔，必须先 `tr '\000' ' '` 再匹配 |
| 8 | 新增 `ensureRunning()`，Start 按钮走它 | server 在但 CLI 被清掉时，只 `startServer()` 会得到「起来了却连不上」的死状态 |
| 9 | `install()` 不再「server 在就直接 return」 | 同上：会永远停在「已安装但连不上」 |
| 10 | `verifyConnection()` 去掉 `\| head -5` 管道 | 管道退出码是 `head` 的，永远 0，`timeout` 打到 124 也看不出来 |
| 11 | 安装前预检 **Root 是否已授权** | 没有 Root 时提前返回 `RootRequired`，不再等到 STARTING 才报含糊错误 |
| 12 | 删掉 `ProcessHandleCompat` | 改成 hidepid 安全的 TCP 探测后已无引用 |

### 已实测验证的部分（有据可查）

| 项 | 验证方式 | 结果 |
|---|---|---|
| `installCliCommand()` 的 shell 语法 | 从 Kotlin 源码反解出真实命令串，`bash -n` / `dash -n` / `sh -n` 三种 shell 校验 | 三者 rc=0 |
| 同上，端到端实跑 | `cp` 进真实 Debian rootfs，`chroot <rootfs> /bin/sh /tmp/fv.sh` | `FRIDA_PYTHON=/usr/local/bin/python3` / `FRIDA_CLI_ALREADY=17.24.2` / `FRIDA_CLI=/usr/local/bin/frida-ps` / `FRIDA_CLI_OK`，rc=0 |
| `detectServerPid()` 的命令 | 在 Android root shell 里原样执行，目标换 `/system/bin/sh` | `PID=14259`，匹配正确 |
| 本机已装组合 | 直接读 rootfs 的 site-packages | `frida 17.24.2` + `frida_tools 14.11.0` + `pip 26.2.1`，与代码锁定值一致 |
| 残留清理 | `ps -A` / `ss -lntp` | 无 etad 进程、8899 已释放 |

### 🔴 第七轮第二轮：真机报 signal 11 —— 根因已定位并修复（2026-10-19 晚）

**症状（真机实测）**

| 能力 | 结果 |
|---|---|
| RPC 连接 | ✅ 可用，`frida-ps -H 127.0.0.1:8899` 能列出全部进程 |
| attach 后台 App（GitHub） | ❌ `unexpectedly timed out while waiting for stop` |
| attach 前台 App（DeepSeek） | ❌ 同样超时 |
| **attach root 同域进程（`busybox sleep`）** | ❌ **`bootstrapper crashed with signal 11`** |
| attach MT管理器 | ❌ `the connection is closed` |
| spawn App | ❌ `connection is closed` |

**为什么排除「frida 17.x 与 Android 16 兼容性」**

`busybox sleep` 是 root 启动、极简、无任何反调试的进程，连它都 signal 11 —— 崩溃点跟目标进程无关，在注入器本身。版本错配、SELinux、进程冻结、server 权限（uid 0 / `CapEff=000001ffffffffff` / `Seccomp=0`）、server 实例问题全部已逐项排除。

**根因：server 二进制跑在 App 私有目录（`drwx------` 0700）里。**

同一份 84,805,800 字节的 etad，唯一变量是放在哪：

| 位置 | 权限 | attach 结果 |
|---|---|---|
| `/data/data/io.github.mangi.eta/files/frida/etad` | 0700 `u0_a456` | ❌ signal 11 |
| `/data/local/tmp/eta-frida/etad` | 755 `root` | ✅ **成功** |

搬到 755 后实测输出（Agent 在 App 内跑的）：

```
[*] attached: pid=18385
[MSG] {'type': 'send', 'payload': {'pid': 18385, 'arch': 'arm64',
       'platform': 'linux', 'module_count': 13, 'first_module': 'toybox'}}
[*] done
=== exit=0 ===
```

`Process.enumerateModules()` 返回 13 个模块，首个 `toybox`，native attach 与 JS 执行全部正常。

**推断机理**：注入后 frida 有一段要以目标进程身份沿 server 侧路径取资源，0700 目录下拿不到，于是段错误。具体哪一步没抓到栈，但**变量已经被隔离到只剩路径**这一个。

**本轮改动（第二轮）**

| # | 改动 | 为什么 |
|---|---|---|
| 1 | `FridaPaths` 新增 `RUNTIME_DIR = "/data/local/tmp/eta-frida"` 与 `runtimeServerFile()` | 运行位置与下载落地位置分离 |
| 2 | 新增 `stageServer()`：起 server 前把 etad 复制到 runtime 目录并 `chmod 755` | 大小一致时只补权限不重拷，省掉每次 Start 写 84MB |
| 3 | `startServer()` 改用 `runtimeServerFile()`，并先 `cd` 到该目录再 setsid | 相对路径拿到的 argv[0] 也要可解析 |
| 4 | `detectServerPid()` 按 runtime 路径匹配 | 否则扫不到自己起的进程 |
| 5 | 新增 `purgeStaleServer()`：只比 cmdline **第一个 token** 是否以 `/etad` 结尾 | 老版本把 etad 跑在 filesDir，端口被它占着，新代码会误判「没在跑」；**不能整体 contains("etad")，这条 shell 自己的 cmdline 里就带它，会自杀** |
| 6 | `ensureRunning()` 新增 `staleForeign` 判定：端口通但 runtime 路径扫不到 → 先清再起 | 升级路径必需，否则用户点 Start 永远停在旧 server 上 |
| 7 | `stopServer()` 在扫不到 pid 但端口仍通时也调 `purgeStaleServer()` | Stop 要真能停住野生进程 |
| 8 | 中文 `values-b+zh+Hans/strings.xml` 补 7 条 `linux_frida_*` | 之前只改了英文，中文用户看不到 Frida 那一项 |
| 9 | 中文 `linux_apk_tools_ready/summary` 同步成 aapt2 + apksigner 版本 | 上一轮遗留的文案不一致 |
| 10 | `AgentPromptBuilder` 与 `re-mobile/SKILL.md` 改成 runtime 路径 | 提示词还在教 agent 用 filesDir 路径手起 server |

### ✅ 终态：第七轮已验收（2026-10-19）

**用户真机复验通过，原话「成功 + 13」。**

```
     ____
    / _  |   Frida 17.24.2 - A world-class dynamic instrumentation toolkit
   | (_| |
    > _  |   Commands:
   /_/ |_|       help      -> Displays the help system
   . . . .       object?   -> Display information about 'object'
   . . . .       exit/quit -> Exit
   . . . .
   . . . .   Prefer a GUI? Luma is the official Frida app, with a live REPL,
   . . . .   persistent sessions & collaboration. https://luma.frida.re/
   . . . .
   . . . .   Connected to 127.0.0.1:8899 (id=socket@127.0.0.1:8899)
Attaching...
[Remote::PID::15851 ]-> Process.enumerateModules().length
13
[Remote::PID::15851 ]->
Thank you for using Frida!
```

**这条输出同时证明了四件事**，缺一不可：

| 证据 | 说明 |
|---|---|
| `Connected to 127.0.0.1:8899` | App 代码起的 server 在 listen，不是手工推的 |
| `Attaching...` 无报错 | **attach 链路通了**，`signal 11` 已消失 |
| `Process.enumerateModules().length` → **13** | 注入的 JS 在目标进程里真的执行了 |
| `[Remote::PID::15851]` | 换了个新 pid 依然成功，不是偶然 |

**验收结论**：Frida 内置功能完成。用户装 APK → 点一次按钮 → server 起来 → Agent 直接 attach，全程不需要电脑、不需要 adb、不需要手工推文件。**「别人装上也能用」这个核心要求达成。**

**本轮唯一未能事后复核的项**：`stageServer()` 用的是「大小一致就跳过复制」的优化。用户这次点的「停止 → 启动」走的哪条分支，事后没单独抓日志确认（因为结果已经是成功的，再跑一遍属于无效消耗）。若将来出现「明明更新了 APK 但 server 行为没变」，第一件事就是查这个跳过逻辑。

### 历史记录：排查过程（保留，是这一轮最有价值的部分）

| 项 | 当时状态 |
|---|---|
| 本轮改动的编译 | ✅ CI 通过（用户装的是 CI 产物） |
| `stageServer()` 端到端 | ✅ 用户点「停止」→「启动」后 attach 成功，说明搬运生效 |
| `purgeStaleServer()` 匹配逻辑 | ✅ 未报错，且没把自己杀掉 |
| 落到 runtime 目录 | ✅ 服务在 8899 正常响应 |

### 一个必须记录的决策

**Frida 不做开机自启。** 理由：`frida-server` 常驻会明显增加耗电，且大多数逆向场景是「要用的时候开、用完就停」。UI 上给了 Start / Stop 两个按钮，由用户自己控制。如果将来用户要求自启，再写 `/data/adb/service.d/` 脚本（但那就又回到「本机手工配置」的老路，需要先和用户确认）。

---

## 第六轮已完成（已验收）

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
5. **不用重装环境**。我已直接改好现有 rootfs 里的 `apktool` wrapper 和 `aapt2-wrapper`，工具链当场可用（`apktool --version` 正常、`baksmali`/`smali`/`apksigner` 全部实测通过）。
6. 但**建议仍重装一次 APK 分析工具**，让 profile 标记从 `1` 升到 `2`，否则下次换设备还得手动补。

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
| 脱壳工具版本 | `FridaPaths.DEXDUMP_VERSION`（`2.0.1`） | `agent/terminal/FridaInstaller.kt` |
| Frida 安装命令本体 | `installCliCommand()`（**改完必须跑 `python3 ci/verify_frida_cli.py`**） | 同上 |
| 逆向 skill 的 Root 前置判定 | `re-mobile/SKILL.md` 开头「第 0 步」 | `assets/builtin_skills/re-mobile/` |

原型（**均不再入库**，需要时临时本地生成）：深色调色台（原 `demo/dark-palette.html`）、会话历史图标调参（原 `demo/topbar-v2.html`）、顶栏图标总览（原 `demo/topbar-icons.html`）。

---

## 已知风险（动手前必看）

| 风险 | 说明 |
|---|---|
| 本地不能编译 | `aapt2` 是 x86_64 ELF，aarch64 PRoot 跑不了。任何编译验证只能走 GitHub Actions |
| 4 个文件图谱不全 | `AgentContextCompactor.kt` / `AgentToolBatchRecovery.kt` / `MarkdownInline.kt` / `AgentRuntimeWireTest.kt`，查这些要直接读源码 |
| 仓库是 public | Eta 上游是 PolyForm Noncommercial 1.0.0，限制二次分发。**用户已明确决定保持 public，不要重复建议转 private** |
| 签名只有一份 | keystore 在 `/sdcard/Download/eta_out/eta-release.jks`，**尚未做离线多份备份** |
| 真机验证覆盖不全 | Frida（第七轮）、逆向 skill、MCP、APK 工具链均已真机跑过；**Xposed 模块加载 / 无障碍服务 / 终端会话仍未系统实测** |
| 无 Root 即断动态链 | Frida server 必须 root 起，无 Root 设备只能做静态分析；SKILL 第 0 步已写明，别让 Agent 逐条试 |

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
| `docs/PITFALLS.md` | 遇到问题先查这里（26 条） |
| **`docs/CONCURRENT_AGENTS.md`** | **要改并发 / Runtime / 无障碍 / 终端 / 浏览器时必读**（并发改造影响面清单） |
| `docs/MODULES.md` | 要知道某模块干什么、改它有什么风险 |
| `docs/ARCHITECTURE.md` | 要理解数据流、进程模型 |
| `docs/CONVENTIONS.md` | 要知道版本、构建、发版规则 |
| `docs/adr/` | 想质疑「为什么当初这么选」 |

---

_最后更新：2026-10-19（第十四轮验收通过：渗透 7 组全部可用）_