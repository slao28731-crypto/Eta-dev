# 踩坑清单

> 格式：**现象** → **根因** → **解法**。新坑往上加，不要只写结论。

---

## 构建 / 工具链

### P077 · `edit_file` 改长字符串拼接时，整段内容被删空只留一行乱入文字
**现象**：改 `FridaInstaller.installCliCommand()`，看到的 diff 是「+1 -20」，替换结果只剩 `android` 一个字。**diff 里那一行看着像注释，肉眼读完全不会警觉**；只有把 Kotlin 反解成真实 shell 串才发现命令是空的。
**根因**：`edit_file` 的 old 块匹配失败时的降级行为，把整块换成了模型最后输出的片段。
**解法**：
1. 改完这类「几十段字符串拼接」的代码，**必须反解成最终产物再看一眼**，不能只看 diff
2. 已固化脚本：`python3 ci/verify_frida_cli.py`（反解 + 三种 shell 语法校验 + 两个分支场景），已接入 `build-apk.yml` 第一个步骤
3. 同类高危写法：`APKTOOL_WRAPPER` / `AAPT2_WRAPPER` / `JAVA_WRAPPER`（`LinuxApkAnalysisInstaller.kt`），改动后同样要反解核对

### P078 · 反解 Kotlin 命令串时，`\$` 被误当成模板占位符
**现象**：写脚本抽取 shell 命令，报「未登记的 Kotlin 模板常量: PY%/*」。
**根因**：Kotlin 里 `\$` 表示**字面美元符**，而 `${PY%/*}` 是 **shell** 的参数展开。用 `${...}` 正则直接扫，会把 shell 语法当成 Kotlin 模板。
**解法**：反解时先把 `\$` 换成哨兵字符（如 `\x00D\x00`），模板替换完再还原。见 `ci/verify_frida_cli.py` 的 `unescape()` / `DOLLAR`。

### P079 · `export PATH=...` 污染常驻终端会话，后面的 python3 全变成假脚本
**现象**：在终端里 `export PATH=/tmp/sim/pybin:$PATH` 做分支测试，之后所有 `python3` 无声无输出（连 `print('hello')` 都空），`compgen` 显示 `/tmp/sim/pybin/python3`。**终端会话是常驻的，PATH 会带下去**。
**根因**：测试沙箱里放了伪装成 `python3` 的 stub，PATH 顺序把它排在前面；且 stub 对未匹配参数静默 `exit 0`。
**解法**：测试脚本里**不要 `export PATH`**，改成在 `subprocess.run(env=...)` 里传局部 PATH；受污染后 `export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin` 复位。
**教训**：把假解释器放进 PATH 前面时，stub 的兜底分支必须是 `exit 1` 而不是 `exit 0`，否则失败会被伪装成成功。

### P001 · 改了两行 Kotlin 却编译失败，报 SDK / JDK 版本不符
**现象**：`Unsupported class file major version` 或 Compose 编译器报版本不匹配。
**根因**：项目锁死 JDK 25 + compileSdk 37 + AGP 9.4.1 + Kotlin 2.4.20。用了 JDK 17 或 SDK 35 就会炸。
**解法**：确认 `java -version` 是 25，`local.properties` 的 `sdk.dir` 指向含 `platforms/android-37` 的 SDK。

### P002 · 云端构建报 `platforms;android-37.0` 找不到
**现象**：CI 里 `sdkmanager "platforms;android-37"` 失败。
**根因**：37 是预览通道，包名带 `.0` 后缀。
**解法**：必须写 `sdkmanager --channel=3 "platforms;android-37.0"`。见 `.github/workflows/build-apk.yml`。

### P003 · PRoot 里 `uv tool install graphifyy` 报 `Operation not permitted`
**现象**：`failed to hardlink file ... os error 1`。
**根因**：PRoot 模拟层禁止 hardlink。
**解法**：`uv tool install --link-mode=copy graphifyy`。

### P004 · npm 装 codex 后运行卡死无输出
**现象**：`codex exec` 一直挂着，最后被 timeout 杀掉。
**根因**：本地模型网关（workbuddy）有渠道校验，agent 类系统提示词会被判"非官方客户端"直接 400。
**解法**：这类网关只适合普通对话，不适合 agent 长任务。别在上面折腾 Codex。

---

## Xposed / Hook

### P010 · 模块装了、LSPosed 里也显示激活，但功能完全没反应
**现象**：无障碍正常，但电源键接管、小布接管全都不生效。
**根因**：`META-INF/xposed/scope.list` 没包含目标包名，或 `java_init.list` 少了入口类。
**解法**：改 `app/src/main/resources/META-INF/xposed/`，新增目标包后必须重新打包安装。
**注意**：R8 混淆后如果这三个文件丢了，模块会静默失效，没有任何报错。

### P011 · 系统升级后 hook 失效，日志里有 NoSuchMethod
**现象**：`ModuleLogger` 输出找不到类/方法。
**根因**：厂商改了小布 / 小爱 / 一圈即搜的内部类名与方法签名。
**解法**：用 `core/DexKitTargets.kt` 的 DexKit 匹配逻辑重新定位；纯字符串匹配的 hook 要加版本分支。

### P012 · 改了 Hook 与 App 的通信字段后，功能半死不活
**现象**：调用有反应但结果为空，或 hook 侧崩溃。
**根因**：`AgentRuntimeWire` 是双向协议，只改一端会导致字段缺失。
**解法**：协议改动必须 App 与 Hook **同时**改、同时装。版本不匹配时要有显式降级，不能静默。

### P013 · release 包里 hook 全部失效，debug 包正常
**现象**：只有 release 版有问题。
**根因**：ProGuard/R8 把 hook 入口类或反射用到的类改名了。
**解法**：检查 `app/proguard-rules.pro` 里的 `-keep class io.github.mangi.eta.ModuleMain` 等规则；新增 hook 入口类要加 keep。

---

## 原生库

### P020 · 改了 C 代码，终端功能还是老行为
**现象**：`eta_pty.c` 改了没效果。
**根因**：`app/src/main/jniLibs/` 里的 `.so` 是**预编译产物**，Gradle 不会自动编译 `cpp/`。
**解法**：手动跑 `scripts/build-terminal-native.sh`，把生成的 `.so` 拷回 `jniLibs/<abi>/`，再重新打包。
**注意**：`libproot_exec.so` / `libproot_loader.so` 只有 `arm64-v8a` 和 `x86_64`，缺了对应架构会直接崩。

### P021 · release 包体积暴增 / 崩溃栈无符号
**现象**：`packaging.jniLibs.keepDebugSymbols` 里列的三个 so 没排查符号问题。
**根因**：`useLegacyPackaging = true` + `keepDebugSymbols` 对三个库例外。
**解法**：新增原生库如果也需要保留符号，要同步加进 `keepDebugSymbols`。

---

## 数据 / 存储

### P030 · 升级后用户聊天记录、Provider 配置全没了
**现象**：装新版后打开是空白。
**根因**：Room Entity 改了字段/类名，触发 destructive migration。
**解法**：改 schema 必须写 `Migration`，并在 `EtaDatabase` 里注册。**绝不加 `fallbackToDestructiveMigration()`**。

### P031 · 敏感工具的原始参数出现在导出的会话里
**现象**：导出 Markdown 时看到不该有的内容。
**根因**：`transcript` 写入路径没区分敏感工具。
**解法**：敏感工具与 MCP 的原始参数/结果只在内存保留，不写持久会话。改 transcript 编码时务必守住这条线。

---

## 签名 / 发版

### P040 · 新包装不上，提示签名冲突
**现象**：`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
**根因**：换了签名证书。本仓库现已改用固定 keystore（Actions Secret），不会再出现这个问题。若仍复现，说明 Secret 里的 base64 被替换过，或本地装的是早期临时签名版本。
**解法**：keystore 已固定在 Actions Secrets，正常不会再发生。要换证书时代价见 P040。

### P041 · release 构建出来没签名
**现象**：`apksigner verify` 失败，或产物是 unsigned。
**根因**：`app/build.gradle.kts` 里四个环境变量缺任一个，`hasReleaseSigning` 为 false，`signingConfig` 为 null。
**解法**：确认 `ETA_RELEASE_STORE_FILE` / `ETA_RELEASE_STORE_PASSWORD` / `ETA_RELEASE_KEY_ALIAS` / `ETA_RELEASE_KEY_PASSWORD` 全部存在。

---

## Provider / 模型

### P050 · 接新 Provider 后 Tool Calling 不工作
**现象**：模型能聊天但不会调用工具。
**根因**：Provider 层三条 SSE 解析路径不同，新 Provider 可能走错分支，或者模型本身不支持 tool call。
**解法**：先确认端点返回的 `tool_calls` 结构（OpenAI 是 `delta.tool_calls`，Anthropic 是 `content_block_start`）。**不要为了省事把三条路径合并**。

### P051 · 自动压缩不触发
**现象**：上下文超了也不压缩。
**根因**：Provider 配置里没填上下文窗口 tokens，或服务商没返回输入用量。
**解法**：在 Provider 设置里填 `context_length`；服务商不返回 usage 时只能手动压缩。

---

## UI / Compose

### P070 · 抽屉"叠层"看起来只有一条缝，不像卡片退层
**现象**：抽屉展开后，右侧只露出 50 多 dp 的聊天页左边缘，视觉上接近一条竖缝，没有"卡片退到下一层"的感觉。
**根因**：`ConversationPaneMetrics` 用的是 `PaneMaxWidth = 340.dp` / `PaneWidthFraction = 0.84f`。在 393dp 屏上抽屉宽 330.1dp，卡片左边缘落在 336.1dp，只露出 56.9dp。
**解法**：对齐上游原版 `326.dp` / `0.76f`。抽屉收窄到 298.7dp，卡片左边缘 304.7dp，露出 88.3dp。**代价**：会话列表可用文字宽从 274dp 降到 243dp，长标题更容易被 Ellipsis 截断。

### P071 · `scaleX` 缩放会让文字横向压扁，不是重新排版
**现象**：抽屉展开时聊天页的文字看起来变瘦，长段落更明显。
**根因**：Compose 的 `placeWithLayer { scaleX }` 缩放的是已排版好的图层，**不会触发重新布局**。宽度被压但排版宽度不变，字形就被横向压扁。
**解法**：如果只要"退层"暗示而不想文字变形，把 `FOREGROUND_MIN_SCALE` 设成 `1f`（纯平移）。当前保留 `0.95f` 是对齐上游原版的取舍，用户明确选了原版效果。

### P072 · 深色主题下抽屉展开时，聊天页上下各露一条浅色带
**现象**：深色模式下抽屉展开，聊天卡片上下边缘各有一条约 11.7dp 的浅灰带。
**根因**：`FOREGROUND_MIN_VERTICAL_SCALE = 0.97f` 让卡片纵向缩 3%（780dp 屏上下各 11.7dp），露出底下容器的 `surfaceContainer`。浅色下容器和卡片都是白，看不出来；深色下 `surfaceContainer` 比卡片底色亮一档，就显形了。
**解法**：这是原版行为。要么接受，要么把 `FOREGROUND_MIN_VERTICAL_SCALE` 改成 `1f`（一行改动）。不要在容器上加圆角或改色来"掩盖"，那会让浅色主题出现新的色差。

### P073 · `FOREGROUND_CORNER_DP` 恒定时，关闭态屏幕四角会露容器底色
**现象**：抽屉关着的时候，屏幕四角（尤其深色）能看到一小块不是聊天的底色。
**根因**：`FOREGROUND_CORNER_DP = 36.dp` 全程恒定。关闭时卡片铺满整屏，这个圆角会作用在整屏四角上，超出物理屏圆角的部分就露出容器色。源码注释里已写明这点。
**解法**：原版接受此行为。若要消除，把圆角改成随 `progress` 从 `0` 插值到 `36.dp`；但这偏离上游，需用户确认。

### P074 · 深色下展开抽屉，右侧聊天卡片是纯黑，像在抽屉上"挖了个洞"
**现象**：深色模式下抽屉展开，抽屉是 `#242424`，右侧聊天卡片是 `#000000`，后层比前层亮，层次关系反了；卡片上下还有 12dp 的同色带（即 P072）。
**根因（两层）**：
1. Miuix Spec2025 主题包里 `colorScheme.background` 本身就是纯黑 `#000000`，而抽屉用的 `conversationPaneContainerColor()` 取 `surfaceContainer`（`#242424`）。上游为此写了 `appPageBackgroundColor()` 做混色，**但工作区这份仓库没有这个函数**。
2. **更关键的一层**：`AgentAppShell` 的 `Scaffold` 没写 `containerColor`，用的是 Miuix 默认 `background`（纯黑），这层不透明背景盖在 `ConversationSidePaneScaffold` 画好的底色上面。**只在侧栏那一层改底色是无效的**——第一次修复就踩了这个坑，读者反馈"和之前没区别"。
**排查方法**：截图取色。抽屉 `#242424`、卡片主体 `#000000`、卡片底部输入栏 `#242424`。底部输入栏是 `surface`，它没变黑说明主题的 `surface` 正常；只有 `Scaffold` 的 `containerColor` 走了 `background`。
**解法**：
1. 侧栏按 `paneProgress` 把底色 lerp 到抽屉色（`ConversationPaneMetrics.DarkPageLift = 1f`），关闭时系数归 0。
2. 新增 `LocalPageBackgroundColor: CompositionLocal<Color?>`（定义在 `ConversationPanePanel.kt`），侧栏用 `CompositionLocalProvider` 把当前底色传进 `content()`。
3. `AgentAppShell` 的 `Scaffold` 改用 `containerColor = LocalPageBackgroundColor.current ?: MiuixTheme.colorScheme.background`。**这一步不可省**，否则前两步无效。
**副作用**：浅色下 `background` 与 `surfaceContainer` 都是浅色，lerp 结果不变，零影响；非侧栏路由 `LocalPageBackgroundColor` 为 `null`，回落主题默认值，行为与改动前一致。
**未做**：上游 `appPageBackgroundColor()` 仍是缺失状态，若要让整个 App 深色底色都从 `#000000` 提到 `#1A1A1A`，需改 6 个文件，属于另一项独立改动。

### P075 · 抽屉修好后，卡片顶栏和底栏仍是纯黑，与中间区域的 #242424 割裂
**现象**：侧栏展开时，卡片中间是 `#242424`（已修好），但顶部工具栏和底部输入区仍是 `#000000`，形成上下两条黑带。
**根因**：各层底色来源不统一。
- `Scaffold` 的 `containerColor` 走 `background`
- 顶栏 `TopBarBackdrop` / `topBarContainerColor` 走 `MiuixTheme.colorScheme.surface`
- 底栏 `AgentChatBottomBar` 的三个背景（模糊背板、渐隐、实底）也走 `surface`
- 聊天区 `surfaceColor` 也走 `surface`

深色 Spec2025 下 `surface` 与 `background` 都是纯黑，所以只要有一处没换，就会出现割裂。
**解法**：在 `ConversationPanePanel.kt` 定义统一入口 `appPageBackgroundColor()`（优先侧栏下传色，否则回落 `background`），然后把上述**全部**改为调用它：`AgentAppShell.Scaffold.containerColor`、`TopBarBackdrop`（两处）、`topBarContainerColor`、`AgentChatBody.surfaceColor` 及其派生的 4 处、`MiuixScaffoldPage` 的两个 `Scaffold`。
**排查方法**：截图按 y 轴逐带取主色。中间 `#242424`、上下 `#000000` 就是来源不统一。
**教训**：改"页面底色"这类全局值，必须把所有绘制底色的入口列全，漏一处就是一条黑边。逐个 grep `colorScheme.surface` / `colorScheme.background` / `containerColor` 才可靠。

### P076 · 抽屉展开时聊天卡片底部有一层层灰带，像叠了好几层
**现象**：浅色下抽屉展开，聊天卡片下缘出现大片灰色过渡带（实测 `#C6C6C6` → `#CACACA`，横向波及 139dp），看起来像多层叠影。
**根因（两项叠加）**：
1. `ForegroundShadow` 曾被从上游 `12.dp` 提到 `16.dp`，理由是"深色下卡片与抽屉同色，只能靠阴影分层"。**这个前提后来不成立了**：改成三级配色后抽屉 `#0F0F0F`、页面 `#1A1A1A`，颜色本身就能分层，阴影不再需要加粗。
2. 0.5dp hairline 描边是二改时新加的，**上游原版根本没有**。深色下它有用（ROM 常削弱 `shadowElevation`），但浅色下会在卡片四周多出一圈浅灰细线。
3. 背景因素：`FOREGROUND_MIN_VERTICAL_SCALE = 0.97f` 让卡片上下各露 12dp 容器色（见 P072），阴影带正好铺满这块区域，就被放大了。
**解法**：
1. `ForegroundShadow` 退回 `12.dp`
2. hairline 描边改为 `if (isLightTheme) Color.Transparent else ...`，浅色不画
3. 顺带删掉已失效的 `pageBackgroundColor` lerp（`DarkPageLift` 归零后是死代码）与 `DarkPageLift` 常量本身
**教训**：**给某次修复加的补丁（阴影、描边），在那个前提被推翻后必须一起撤掉**。三级配色上线后，我保留了"为同色分层而加的 16dp 阴影"，结果它在浅色下变成明显瑕疵。改配色这类根基性变更时，要回头清一遍上一轮为绕开问题而加的补丁。

### P077 · 浅色下建议卡片变成白色、与页面分不开
**现象**：深色配色改完后，浅色模式主页四个建议卡片看不见了（截图取色：卡片 `#FFFFFF`、页面 `#FFFFFF`）。
**根因**：新增的 `appCardSurfaceColor()` 初版没有参数，浅色一律回落 `surfaceContainerHigh`。但**上游各卡片本来各用不同层级**：聊天气泡 `surfaceContainerHigh`、建议卡片/思考卡/运行记录 `surface`、底部输入框 `surfaceContainer`。统一成一个值就把本该有差异的地方抹平了。
**解法**：函数签名改为 `appCardSurfaceColor(fallback: Color)`，浅色回落由**调用方各自传入自己的上游原值**，深色才收口到 `#343434`。底部输入框干脆不走这个入口，直接写死 `surfaceContainer`。
**教训**：**"统一入口"要统一的是深色，不是浅色**。浅色是用户已经认可的上游观感，任何"顺手统一"都会破坏它。做主题改动时先 `git show <基线commit>:<file>` 把每个调用点的原始值查出来，不能凭印象统一。

### P078 · 从溢出菜单移走一项后，菜单点击全部错位
**现象**：把「新建对话」从 `TopBarOverflowMenu` 移到顶栏后，点「打开终端」跳到了浏览器，点「浏览器」跳到了别的。
**根因**：`DropdownImpl` 的 `onSelectedIndexChange` 是**按下标硬编码分派**的 `when (index) { 0 -> ...; 1 -> ... }`。删掉列表第 0 项后，`menuItems` 整体前移一位，但 `when` 分支没跟着改，于是每一次点击都命中下一个功能。
**解法**：删菜单项时必须同步改 `when`。当前映射（`ui/app/AgentAppShell.kt`）：

```
index 0 -> onOpenTerminal()
index 1 -> onLaunchKimiWeb()
index 2 -> onOpenBrowser()
index 3 -> onStopKimiWeb()   // 条件项，仅 canStopKimiWeb 时存在
```

**教训**：`listOf(...)` 的顺序就是**隐式契约**，而 `when (index)` 是完全脱离列表的一句代码，编译器查不出两者是否对齐。凡是"按位置分派"的 UI（菜单、Tab、步骤条），增删项都必须把两处一起看。更好的写法是把 `onClick` 直接放进 `DropdownItem` 的数据结构里，但这需要适配 Miuix 的 `DropdownImpl` 签名，属独立改动。

### P079 · 用户给的 SVG fill 是固定深色，深色主题下会消失
**现象**：把用户提供的 SVG 转成 vector drawable 后，深色模式下图标几乎看不见。
**根因**：原 SVG 写死 `fill="#060A26"`（深蓝）。原样转换后图标颜色被钉死，不跟随主题；深蓝贴在 `#1A1A1A` 背景上对比度极低。
**解法**：转换时把 `fill` 改成 `#FF000000`（不透明黑），**并在 Compose 侧显式传 `tint = MiuixTheme.colorScheme.onSurface`**。
**注意**：Material 图标（`Icons.Rounded.*`）自带主题色不用 tint；换成 `painterResource` 后 **tint 必须手写**，否则颜色就固定成 XML 里的值了。
**教训**：任何外部 SVG 进项目，先查 `fill` 是写死还是 `currentColor`。写死的一律改成占位色 + Compose tint，这是唯一能让图标同时适配浅色/深色的做法。

### P080 · 新装的 MCP 服务工具全是关的，加完等于没加
**现象**：Agent 调 `mcp_add_server` 成功返回，服务器也进了设置页，但下一轮它的工具一个都没出现在模型目录里。
**根因**：`McpServerManager.discover()` 里 `enabledToolNames = server.enabledToolNames.intersect(availableNames)`。新建时 `draft.enabledToolNames` 是默认空集，空集取交集还是空集，而 `activeTools` 的定义是 `tools.filter { it.name in enabledToolNames }`，于是工具数量恒为 0。
**为什么设置页没暴露这个问题**：设置页新建后会让用户进详情页逐项手动打开，用户没开就是「还没配置完」，符合预期。Agent 路径没有这个中间步骤，空集就成了死路。
**解法**：`discover()` 加 `enableAllDiscoveredTools: Boolean = false`，只有 Agent 路径传 `true`，此时直接用 `availableNames` 全量启用。设置页不传，行为零变化。
**教训**：**「函数默认参数」+「集合交集」是一个静默归零的组合**。凡是 `A.intersect(B)` 的写法，都要问一句「A 为空时业务上合理吗」。这里不合理，但因为 UI 有个手动补救步骤，所以从没暴露过。

### P081 · 工具名带 `mcp_` 前缀，会被脱敏机制连带吞掉返回结果
**现象**：Agent 加完 MCP 服务器后，用户换一个会话问它「刚才加了什么」，它答不上来，也不知道下一轮该调什么工具名。
**根因**：`AgentSensitiveToolPolicy.isSensitive()` 第一条是 `toolName.startsWith("mcp_")`，本意是保护**远端 MCP 工具**的返回数据。新加的管理工具叫 `mcp_add_server` 等，正好命中同一前缀，于是被当成敏感工具：

```kotlin
// AgentConversationCodec.redactSensitiveToolData()
copy.put("content", "[敏感工具参数与原始结果仅供当前回合使用，未写入持久会话]")
call.optJSONObject("function")?.put("arguments", JSONObject().put("redacted", true).toString())
```

结果写进 transcript 时被整条替换，**连 `modelTools`（下一轮实际可用的工具名清单）也一起消失**。当前 run 内的 `messages` 是完整的，所以当轮不影响；跨会话/重载历史才炸。
**解法**：加 `mcpManagementTools` 精确白名单，命中则直接返回 `false`。远端 MCP 工具名形如 `mcp_<serverId前8位>_<工具名>_<sha256前8位>`，不会与这 6 个固定名字冲突。
**教训**：**前缀匹配是廉价的分类手段，也是廉价的误伤来源**。给新工具起名时先 grep 一遍有没有基于前缀的通用判定（脱敏、图标、日志、权限），命中就要显式豁免，不能指望「反正名字不一样」。
**验证方式**：`McpManagementToolCatalogTest.management tools stay out of the sensitive policy` 锁死这一点，同时断言远端 MCP 工具仍然是敏感的。

---

### P082 · 两个 MCP 服务一装，后一个的工具大半"不存在"，重启 App 也没用
**现象**：用户先装一个 51 工具的服务，再装一个 49 工具的服务。Agent 回复"已启用全部工具"，但下一条消息调用新服务时，一部分报 `TOOL_NOT_AVAILABLE`（工具未在本轮能力目录中提供），另一部分报 `MCP_CALL_FAILED`。用户关闭 App 重开、换新对话，结果完全一样。
**根因**：三层叠加。
1. `McpRunSnapshot.MAX_RUN_TOOLS = 64`，**这个额度是所有已启用服务共享的**。51 + 49 = 100，第二个服务只分到 13 个，后面 36 个被丢弃。
2. 丢弃发生在 `server.activeTools.forEach { if (size >= MAX) return@forEach }`。**`return@forEach` 只跳过当前元素，外层循环继续空转**——既不 `break` 也不报错，超额工具被静默吃掉。
3. `mcp_add_server` 的 `note` 写死"已保存并启用全部工具"，模型信了这句话，照着 `modelTools` 里的名字调用，被裁掉的自然失败。
`TOOL_NOT_AVAILABLE` 来自 `AgentLoop.executeTool()` 的 `toolCall.name !in availableToolNames` 前置检查；`MCP_CALL_FAILED` 来自 `McpToolExecutor` 的异常兜底——两者同轮出现，正说明"目录里有一部分、缺一部分"。
**解法**：
- `MAX_RUN_TOOLS` 64 → 256；`MAX_DISCOVERED_TOOLS` 128 → 512（发现上限必须 ≥ 运行上限，否则工具连库都进不来）；`MAX_LIST_PAGES` 8 → 16 配套翻页。
- 用 `take(remaining)` 显式截断，语义变成"我知道我在丢，丢多少可算"。
- 目录里单工具描述截到 400 字（数据库保留 2000），100 个工具能省掉大半 token。
- `mcp_add_server` 返回 `registeredToolCount` + `unavailableModelTools`，把"承诺"对齐到"实际"。
- `mcp_list_servers` 返回 `enabledToolTotal` / `runToolLimit` / `overLimit`，超额时附精简建议。
**教训**：**「上限」这件事必须同时回答三个问题：额度是全局的还是每实例的、超了之后是报错还是静默丢弃、谁负责把这个事实告诉调用方**。这次三个都没答，于是出现了"模型以为全都能用、用户以为服务坏了、重启以为能修好"的三方误判。另外 `forEach` 里做早退判断时要意识到它是 lambda，`return@forEach` 永远不是 `break`——想截断就用 `take` 或 `for` + `break`。
**附**：对比 Operit（`AAswordman/Operit`）的做法——它的 `MCPPackage.toToolPackage()` 直接把工具 `map` 成 `ToolPackage`，`listTools()` 也不设上限；它靠"包制"让模型先选包再选工具，从结构上避免了平铺目录爆炸。Eta 是平铺架构，照搬等于重做工具层，所以先放宽额度 + 对齐文案。
**验证结果（2026-10-19 真机）**：用户装包实测，51 + 49 共 100 个工具全部进入单轮目录，原被裁掉的 36 个恢复可调，`TOOL_NOT_AVAILABLE` 不再复现，验收通过。
**遗留**：`McpRunContextTest` 新增的 2 条用例未能在 PRoot 环境执行（`AAPT2 daemon startup failed`，`processDebugResources` 失败），需在正常构建环境或 CI 补跑。

---

## P083 · 内置 skill 的 description 被静默截断，触发词全部丢失

**现象**：把两个外部 skill 原样放进 `assets/builtin_skills/` 后，模型完全不会在「反编译 APK」「Frida hook」这类请求下主动加载它们。
**根因**：`AgentPromptBuilder.kt:211-215` 对每个 skill 的 description 做：
```kotlin
.let { if (it.length <= 180) it else it.take(180) + "..." }
```
实测：`game-hacking` 的 description 311 字 → 被砍 131 字（42%），截断点正落在「驱动」中间，`DLL注入`/`脱壳`/`Frida`/`Xposed`/`il2cpp`/`Unity逆向` 全丢；`reverse-engineering` 505 字 → 被砍 325 字（64%），前 180 字全是英文介绍，28 个中文触发词一个不剩，连 `disassembl` 都被腰斩成 `disassemb`。
**解法**：description 压到 180 字以内只做一句话概括，触发词表整块移到 SKILL.md 正文。当前 5 个 skill 实测 140/122/84/122/129 字。
**教训**：**description 是给模型做「要不要加载这个 skill」判断的唯一依据，不是元数据仓库。** 写进去的每一个字都必须能帮助判断，删掉之后就等于没写。截断是静默的，没有任何报错，只能靠量字符数发现。

---

## P084 · apktool 回编译报 `Syntax error: "(" unexpected`，真因是 aapt2 被复制后丢了 x 位

**现象**：装好 `aapt2` 之后执行 `apktool b dec -o out.apk --aapt /usr/bin/aapt2`，报：
```
Exception: Execution failed (exit code = 2): [/tmp/aapt2_1229....tmp, compile, --dir, dec/res, --legacy, -o, ...]
W: /tmp/aapt2_1229....tmp: 2: Syntax error: "(" unexpected
```
错误信息看起来像 shell 语法错，很容易误判成 aapt2 参数不对或二进制损坏。
**根因**：apktool 在调用 aapt2 前会把它**复制到 `TMPDIR` 再执行**。复制过程不保留可执行权限（x 位），于是系统把这个 ELF 二进制当成 shell 脚本去解析，解析到 ELF 头里的二进制字节就报「( 语法错误」。
**解法**：不把 aapt2 本体传给它，改传一个 shell wrapper：
```sh
#!/bin/sh
exec /usr/bin/aapt2 "$@"
```
apktool 复制这个脚本同样丢 x 位，但脚本本身是被 `sh` 执行的（有 shebang），而 `exec /usr/bin/aapt2` 指向的是**原位置、权限完好**的二进制。落在 `LinuxApkAnalysisInstaller.kt` 的 `AAPT2_WRAPPER` 常量，安装时写到 `bin/aapt2-wrapper` 并建软链到 `usr/local/bin/`。
**教训**：**报错信息里的路径比报错内容更值钱。** 看到 `/tmp/xxx.tmp` 就该想到「这是被复制过的副本」，而不是去怀疑原始二进制。跨进程传二进制时，权限、属主、x 位这三样在 `cp` 语义下都不保证，传 wrapper 比传本体稳。

---

## P085 · `public.xml` 里 `$` 开头的资源名会让 aapt2 编译整体失败

**现象**：同一 APK，`apktool b --aapt aapt2-wrapper` 报：
```
W: error: resource 'drawable/$ic_splash_animated__1' has invalid entry name '$ic_splash_animated__1'.
...
W: dec/res/values/public.xml: error: file failed to compile.
```
**根因**：这是 **apktool 解包时的副作用**，不是目标 APK 本身的问题。原始 APK 里这类资源名在编译后的 `resources.arsc` 里是合法的（二进制字符串池不校验），但 apktool 反解成 `public.xml` 文本时原样写了出来，aapt2 的文本解析器按 Java 标识符规则校验，`$` 开头直接拒绝。
**解法**：两种，按需要选：
1. `apktool b dec --copy-original` —— 直接拷原 `resources.arsc`，跳过资源重编。适合**只改 smali 不改资源**的场景（最常见）。
2. 改写 `public.xml`，把 `$ic_splash_animated__N` 改成合法名并同步改 `res/` 目录下的文件名和所有引用。
**教训**：**apktool 是「解包尽量忠实」而不是「解包结果可直接重编」**。凡是反解出来又被原样喂回编译器的环节，都要预期「格式合法但语义非法」的边界。先试 `--copy-original`，能过就别去改资源名。

---

## P086 · pip 装包被 PEP 668 拦（`externally-managed-environment`）

**现象**：在 Debian rootfs 里 `python3 -m pip install capstone` 直接失败，提示 `error: externally-managed-environment`，并建议用 venv。
**根因**：Debian 12+ 的 Python 标记为「由系统包管理器托管」（PEP 668），pip 默认拒绝写入 `site-packages`，防止和 `apt` 装的包打架。
**解法**：加 `--break-system-packages`。本机还额外需要 `--ignore-installed` 才能绕开 `psutil` 这类「由 debian 安装、无 RECORD 无法卸载」的包：
```bash
python3 -m pip install --break-system-packages --ignore-installed lief pyaxmlparser
```
**注意**：`--break-system-packages` 对 Eta 这种「用户自有 rootfs、不做系统升级」的场景是安全的；但如果哪天 rootfs 走 `apt upgrade`，pip 装的包可能被覆盖，需要重装。
**教训**：容器里装 Python 包，先确认是不是 PEP 668 托管环境。看到 `externally-managed-environment` 不要真去建 venv（Eta 的 `python3` 是软链到 `/opt/eta/python/`，venv 会让工具链找不到），直接 `--break-system-packages`。

---

## P087 · skill 的 reference 超过 64,000 字符只能读到一部分

**现象**：把一个 275KB 的 `cheatsheet.md` 放进 `references/` 后，模型读它只能拿到开头约 23% 的内容，后面的内容「写了但读不到」。
**根因**：`skills_read_resource` 的 `maxChars` 参数 `coerceIn(512, 64_000)`，**默认只有 16,000**。原文件 274,602 字符，即使传满 64,000 也只覆盖 23%。`skills_read` 读 SKILL.md 走同一套限制，原 `reverse-engineering/SKILL.md` 26,717 字符在默认值下只能读 60%。
**解法**：按 markdown 一二级标题（`# ` / `## `）边界切分，每份控制在 64,000 字符内，按主题命名。切分脚本要注意一个坑：**只把 `#{1,2}` 当边界，不要用 `#{1,3}`** —— 三级标题切分会让每块只剩标题行，正文全丢（第一次切分就踩了这个，8 个分组里 7 个只有几百字节，全部内容都堆进了 misc）。
**教训**：**「文件存在」和「文件可读」是两件事。** 放进 skill 的每个文件都要先量字符数，量完再决定切不切。这条和 P083 是同一类问题的两面：一个是入口（description）被截，一个是正文被截，两者都是静默的。

---

## P088 · 写错 apt 包名，导致「安装 OpenJDK 25 失败」这个误导性报错

**现象**：用户在 设置 → 终端 → Linux 环境 → APK 分析工具 点安装，界面显示「安装 OpenJDK 25 失败，可以稍后重试」。
**实际状态**：`apt-cache policy openjdk-25-jdk-headless` 显示 `Installed: 25.0.4.1+1-1~deb13u1` —— **java 25 早就装好了**，`java -version` 也正常输出。界面报的错和真实原因完全无关。
**根因**：我在 `linuxApkJavaInstallCommand` 里写了 `eta-apt install openjdk-25-jdk-headless aapt2 apksigner`。Debian 里**没有叫 `aapt2` 的包**，它由 `android-sdk-build-tools` 提供。apt 遇到不存在的包名会整条命令失败（`Unable to locate package aapt2`），但 `LinuxApkAnalysisInstaller` 的失败上报只区分阶段（`INSTALLING_JAVA`），于是把「包装不上」笼统归到「Java 装不上」。
**解法**：包名改成 `android-sdk-build-tools`。
**教训**：
1. **apt 的包名不能猜。** 写进安装命令前必须 `apt-cache policy <pkg>` 或 `apt-cache search <keyword>` 验证一遍。`aapt2` 是可执行文件名，`android-sdk-build-tools` 才是包名——这种「文件名 ≠ 包名」的错配在 Debian 里非常常见。
2. **失败上报要能定位到具体包。** 现在 `INSTALLING_JAVA` 这个阶段名把「java 装失败」和「和 java 一起装的东西失败」混在一起，用户看到的报错和真相差了一个层级。更好的做法是让 `eta-apt` 的输出透传到 UI，或者把安装命令拆成多条、逐条上报。

---

## P089 · Debian 的 aapt2 只有 2.19，配不上 apktool 3.x

**现象**：绕过 wrapper 拦截后执行 `apktool b`，报：
```
W: unknown option '--no-compile-sdk-metadata'.
W: aapt2 link [options] -o arg --manifest arg files...
```
**根因**：`apt-cache policy aapt` 显示 Debian trixie 只提供 `1:14~beta1-3`（aapt2 报自己 2.19）。apktool 3.0.3 在 link 阶段会传 `--no-compile-sdk-metadata`，这个参数是 Android SDK 8.0+ 才有的。**Debian 仓库的 aapt2 停在 2.19，无论如何 apt 都到不了 8.0。**
**已验证的部分**：`aapt2-wrapper` 本身是生效的 —— 报错的命令行是 `[/usr/local/bin/aapt2-wrapper, compile, ...]`，说明 wrapper 被正确调用、x 位问题已解。卡住的是 aapt2 版本。
**解法**：改走 dex 直换。**实测完整跑通**：
```
apktool d → baksmali d → 改 smali → smali a → zip 替换 dex → apksigner sign → pm install
```
8.9MB dex 反汇编 → 重组 → 替换 → 签名 → 14MB APK，全程无报错。
**要真正恢复资源回编译**，只能换 aapt2 来源：Google Maven 的 `com.android.tools.build:aapt2:<ver>-<build>-linux.jar` 里含 ARM64 版 `aapt2` 二进制，解出来替换 `/usr/bin/aapt2`。
**教训**：**「装着这个工具」和「这个工具版本够用」是两件事。** 装包前先 `--version`，版本对不上就换路子，不要在 `--copy-original`、改 public.xml 这些下游变量上反复试——它们改不掉版本断层。另外 `aapt` 这个包名给的是 **aapt2 2.19**，版本号体系和 Android SDK 的 aapt2 不是一套，容易误判。

---

---

## P090 · 把「App 内置能力」做成了「本机手工配置」，方向根本错了

**现象**：用户要求「Frida 内置到 App」，我下载了 84MB 的 `frida-server`，手工推到用户手机的 `/data/local/tmp/.eta/etad`，又在用户自己的 Debian rootfs 里 pip 装了 frida-tools。用户反问：**「我想让它内置到 App 当中去，而不是装在我的本机上，你装在我的本机上别人如何使用呢？」**
**根因**：我把「在用户设备上让功能跑起来」当成了目标，而用户的目标是**「让所有装了这个 APK 的人都能用」**。这两件事在开发阶段看不出差别（都能跑），但在分发阶段差一个数量级——手工装的任何东西都不会随 APK 走。
**正确做法**：照着项目已有的 `LinuxApkAnalysisInstaller` 写一个 `FridaInstaller`——App 自己下载、SHA-256 校验、解压、安装、启动、验证，用户在设置页点一个按钮。这一步项目里已经有完整范式可抄，我却没有先去看它。
**教训**：**遇到「内置 / 集成 / 支持 XX」这类需求，第一个动作应该是「找项目里已有的同类实现，照着抄」。** 这个仓库里 `LinuxApkAnalysisInstaller`、`AlpineEnvironmentInstaller`、`DebianEnvironmentInstaller`、`LinuxPackageProfiles` 四套安装器已经把「下载→校验→安装→验证→UI 接入」的范式写全了，抄一遍就能对。手工装到本机是小作坊思路，永远交付不出去。

---

## P091 · 反复在本地跑 gradlew 编译，全部卡死且返回空结果

**现象**：连续多次执行 `./gradlew :app:compileDebugKotlin --offline`，每次都超时或返回空输出，最长一次跑到 20 分钟无结果。用户明确质问：**「编译不是在云端编译吗？你本机编译什么？」**
**根因**：这个仓库的 CI（`.github/workflows/build-apk.yml`）在 push 到 main 后会自动编译 debug + release 双 APK。本地 PRoot 环境跑 Gradle 又慢又容易卡（AAPT2 daemon 起不来、Kotlin daemon 内存不足）。**本地编译在这里既慢又不可靠，且完全没必要。**
**正确做法**：改完代码 → 直接 `git push origin clean:main` → 看 CI 结果 → 按报错修。本地只做**静态检查**：`grep` 确认引用的 `R.string.xxx` 在 `strings.xml` 里真的存在、`getString` 的占位符数量对得上、import 的类真的存在。这些 grep 一秒就能查完，比跑 Gradle 快两个数量级。
**教训**：**先确认项目有没有 CI，有就别在本地编译。** 另外我在这轮还犯了一个连带错误：本地没编译，就等于没验证，却把「已写完」当成了「已完成」——交接时必须如实写「未编译验证」，不能含糊。

---

## P092 · `getString` 占位符数量不匹配，只有编译才会报

**现象**：`FridaInstallResult.InsufficientSpace` 分支写 `context.getString(R.string.linux_insufficient_space, requiredBytes.toReadableSize(context))`，只传 1 个参数。
**根因**：`strings.xml` 里该字符串是 `<string name="linux_insufficient_space">Not enough space: %1$s required, %2$s available</string>` —— **两个占位符**。少传一个会在编译期报错（`Format string ... requires 2 arguments`）。
**解法**：补成 `requiredBytes.toReadableSize(context), availableBytes.toReadableSize(context)`。同时发现并修掉另外三个**不存在**的资源名引用（`linux_environment_not_ready` / `linux_root_required` / `linux_install_failed`），改用已有的 `linux_not_installed` / `linux_root_unavailable`。
**教训**：**在不能编译的环境里改 UI 代码，必须手工核对 `R.string.*` 是否存在、占位符数量是否匹配。** 一条 `grep 'name="linux_' strings.xml` 就能拿到全部可用资源名，比等 CI 报错快得多。这轮我一开始就是靠这个 grep 才发现两个隐藏的编译错误。

---

## P093 · `frida-tools` 的版本号跟 `frida` 不是一套编号

**现象**：`FridaInstaller` 写 `pip install frida==17.24.2 frida-tools==17.24.2`，执行必挂。
**根因**：`frida`（Python 绑定）和 `frida-server`（二进制）都是 17.24.2，但 **`frida-tools` 是另一个独立编号的项目，最新只到 14.11.0**，PyPI 上根本没有 17.x 的 frida-tools。`pip` 会报 `No matching distribution found`，整个安装卡在 `INSTALLING_CLI`。本机实测通过的正确组合是 `frida 17.24.2` + `frida-tools 14.11.0`，其 setup 依赖写的是 `frida>=17.10.0,<18.0.0`——**只约束范围，不跟版本号**。
**解法**：拆成两个常量 `FridaPaths.VERSION = "17.24.2"` 与 `FridaPaths.TOOLS_VERSION = "14.11.0"`。
**教训**：**同属一个产品的多个包，版本号不一定同步。** 写死版本前先去 PyPI 的 `pypi.org/pypi/<pkg>/json` 看 `info.version` 和 `requires_dist`，别按主包版本想当然。

---

## P094 · Android 原生环境没有 `xz`，chroot 内又看不到宿主路径

**现象**：`extractXz()` 用 `TerminalEnvironment.ANDROID` 执行 `xz -dc archive.xz > out`，真机上必然失败。
**根因（两层）**：
1. **Android 侧没有 xz。** 实测 `/system/bin`、`/system/xbin`、`/vendor/bin`、`/data/adb/ksu/bin` 全都没有，`toybox | grep xz` 无结果，`command -v xz` 返回 not found。只有 PRoot/Debian rootfs 里有。
2. **改到 Debian 侧也不行。** chroot 的根是 rootfs，**看不到宿主侧的 `filesDir` / `cacheDir`**：实测在 chroot 内 `ls /data/data/io.github.mangi.eta/files/frida` 返回 `No such file or directory`。要跨边界就得先把 xz 包搬进 rootfs、解完再搬出来，多两次 84MB 拷贝。
**正确做法**：**纯 Kotlin 解压**。项目已依赖 `org.tukaani:xz 1.12`（`gradle/libs.versions.toml:27`），用 `XZInputStream(raw, 128 * 1024)` 直接解流。现成写法在 `RootlessLinuxInstaller.kt:27`，照着抄即可。frida-server 的 `.xz` 是单流压缩（不是 tar.xz），只需要解流。
**教训**：**Android 是精简 Linux，别假设 GNU 工具链齐全。** 需要压缩/解压/归档时优先找 JVM 侧库（项目已有 `commons-compress` + `tukaani:xz`），不要调用外部命令——外部命令在 Android 侧缺、在 chroot 侧又有路径隔离问题，两头都不讨好。

---

## P095 · `python3 -m pip` 装进去的 Python，和 `frida-ps` 指向的 Python 不是同一个

**现象**：把 `frida` + `frida-tools` 装完，`frida-ps` 却调不动或版本不对。
**根因（实测数据）**：Eta 的 Debian rootfs 里，`/usr/bin/python3` **根本不存在**（`ls` 返回 No such file）。真正生效的是：
```
/usr/local/bin/python3  ->  /opt/eta/python/cpython-3.14-linux-aarch64-gnu/bin/python3.14
                            （再软链到 cpython-3.14.7-linux-aarch64-gnu/）
```
`frida*` 的入口脚本全部软链在 `/usr/local/bin/`，指向 `/opt/eta/python/cpython-3.14.7-.../bin/`。也就是**只有一个 Python，但它的路径带版本号和架构号，会随升级/换架构变化**。任何写死 `/usr/bin/python3` 或写死 `cpython-3.14` 的代码，换一台设备就可能炸。
**解法**：安装脚本运行时逐个探测（`/usr/local/bin/python3` → `/usr/bin/python3` → `/opt/eta/python/*/bin/python3` → PATH 里的 `python3`），找到哪个用哪个。
**教训**：**在容器/发行版里，"python3 在哪儿" 是个运行时事实，不是编译期常量。**

---

## P096 · `command -v frida-ps` 在 chroot 里会误报「没装」

**现象**：`frida-ps` 明明存在且可执行（`test -x` 通过），但脚本里 `if ! command -v frida-ps` 判定为失败，安装报 `FRIDA_CLI_NOT_ON_PATH`，rc=69。
**根因**：chroot 内的 `PATH` 是**调用方注入的**。直接 `chroot <rootfs> /bin/sh -c 'echo $PATH'` 拿到的是宿主 Android 的 PATH：
```
/product/bin:/apex/com.android.runtime/bin:...:/system/bin:/vendor/bin:/data/adb/ksu/bin
```
**里面没有 `/usr/local/bin`**。但 Eta 正常调用时（`ShellProcessSupervisor`）会显式传 `PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin`，所以真机跑起来是对的、手工验证时是错的——两种环境不一致。
**解法**：复核一律用**绝对路径**（`[ -x /usr/local/bin/frida-ps ]`），不用 `command -v`。凡是要给用户/模型直接敲的命令，都用绝对路径。
**教训**：**在 chroot 里判定"命令在不在"，`command -v` 不可靠（取决于 PATH 怎么来），`test -x 绝对路径` 才可靠。** 验证脚本和真实调用路径不一致时，先对齐环境再下结论。

---

## P097 · Android 10+ 的 `/proc` 有 hidepid，App 身份读不到 root 进程

**现象**：frida-server 明明在跑（`ps -A | grep etad` 能看到 pid、`ss -lntp` 能看到 LISTEN），但 App 里 `isRunning()` 一直返回 false，UI 永远显示「已停止」，Stop 按钮也杀不掉。
**根因**：判活用的是「读 `/proc/<pid>/stat`」，而 server 是 root 起的、App 是普通 uid。Android 10+ 的 `/proc` 挂了 hidepid 语义，**跨 uid 读 `/proc/<pid>/stat` 会直接 FileNotFound**，不是权限错误、只是文件"不存在"，`runCatching` 里完全看不出异常。
**解法**：主判据换成 **TCP 探测 `127.0.0.1:8899`**——server 活着就必然在 listen，这个信号不依赖任何权限、也不会被 SELinux 挡。端口连不上时再退回 pid 文件 + `/proc`（同 uid 场景仍有效）。pid 定位本身则放进 **root shell** 里扫 `/proc/*/cmdline`，那里权限完整。
**教训**：**判断"别人的进程活着"不要走 `/proc`，走它必然会暴露的服务端口。** 顺带：`/proc/<pid>/cmdline` 是 NUL 分隔的，直接 `head -c` 取前缀会带上 `\0` 匹配不上，要先 `tr '\000' ' '` 再比。

---

## P098 · `setsid ... &` 的 `$!` 是个短命父进程，写进 pid 文件就是脏数据

**现象**：`startServer()` 记下的 pid 过一会儿就无效了（`/proc/<pid>` 消失），但 server 实际在跑。此时 Stop 杀不掉、状态显示错误。
**根因**：Android toybox 的 `setsid` 会先 fork 再 exec，`$!` 拿到的是那层很快就退出的父进程，不是最终 exec 出来的 server。真 pid 是它的子进程。
**解法**：pid 文件只当"提示"，真实 pid 靠起完之后**扫 `/proc/*/cmdline` 按 server 绝对路径前缀匹配**再回写一次。同理 Stop 时也不用 pid 文件，现场扫一遍。
**教训**：**凡是 daemon 化（double-fork / setsid / nohup）的启动方式，`$!` 都不可信。** 要么让程序自己写 pidfile，要么起完再去系统里找。

---

## P099 · `cmd | head -5` 顺手把退出码也"吞"了

**现象**：`verifyConnection()` 里写 `timeout 30 frida-ps ... 2>&1 | head -5`，无论 frida-ps 成功、失败还是 timeout（124），这个管道的退出码**永远是 0**（`head` 自己的退出码）。于是失败被当成成功，`isRunning` 一旦再出错就彻底查不出原因。
**根因**：POSIX 管道的退出码 = 最后一个命令的退出码。没开 `pipefail` 的话中间命令的失败全部丢失。
**解法**：去掉管道，直接跑；真需要截断就在 Kotlin 侧 `.take(240)`，同时把输出打进日志（`AndroidAgentLogger.info` 里带上 `output=`）。`InstallerShellRunner` 本身就有 64KB 输出上限，不需要 `head` 帮忙截。
**教训**：**在需要判断成败的地方，一个管道就能把错误码吃掉。** 报错路径上绝不要用 `| head`、`| tail`、`| grep` 收尾，除非显式 `set -o pipefail`。

---

## P100 · frida-server 跑在 App 私有目录（0700）里，attach 任何进程都会 `bootstrapper crashed with signal 11`

**现象**：`frida-ps -H 127.0.0.1:8899` 能正常列出全部进程（RPC 通道是通的），但所有 attach / spawn 全失败：

```
attach 后台 App     → unexpectedly timed out while waiting for stop
attach 前台 App     → 同样超时
attach MT管理器     → the connection is closed
spawn  App          → connection is closed
attach busybox sleep → bootstrapper crashed with signal 11   ← 决定性证据
```

**为什么这条证据是决定性的**：`busybox sleep` 是 root 启动、极简、无任何反调试的进程。连它都 signal 11，说明崩溃点跟目标进程无关，**在注入器本身**。所以「frida 17.x 与 Android 16 兼容性」「目标 App 反调试」「进程被冻结」这些方向全都是错的。

**逐一排除过、都不是原因**：版本错配（server 与 CLI 都是 17.24.2）、SELinux（清空 dmesg 后无 `avc: denied`）、进程冻结（`cgroup.freeze=0`）、server 权限（uid 0 / `CapEff=000001ffffffffff` / `Seccomp=0`）、server 实例坏了（另起 8898 带 `-v` 结果相同）。

**根因**：server 二进制放在 `context.filesDir/frida/etad`，而 `filesDir` 是 `drwx------`（0700、属主 App uid）。注入后有环节要以目标进程身份沿 server 侧路径取资源，0700 下拿不到 → 段错误。

**变量隔离法（唯一可信的验证方式）**：同一份 84,805,800 字节的 etad，只改位置不改别的：

| 位置 | 权限 | attach 结果 |
|---|---|---|
| `/data/data/io.github.mangi.eta/files/frida/etad` | 0700 `u0_a456` | ❌ signal 11 |
| `/data/local/tmp/eta-frida/etad` | 755 `root` | ✅ `attached: pid=18385` / `module_count: 13` |

**解法**：`filesDir` 只作下载解压的落地位置，起 server 前 `cp` 到 `/data/local/tmp/eta-frida/` 再 `chmod 755`，然后用那份跑。`/data/local/tmp` 本身是 `root:shell 0771`，App 身份写不进去，但 root shell 可以，所以搬运必须在 root 身份下做。

**教训**：**「连接层通」和「注入层通」是两件事，前者通不能推出后者没问题。** 反过来，注入失败时先找一个最无保护的 root 进程试一次——它有没有崩，直接决定该往哪个方向查。另外：**任何需要被其他进程读取的运行时二进制，都不能放在 App 私有目录里。**

---

## P101 · 清理旧进程的 shell 脚本会把自己 kill 掉（`contains("etad")` 自杀）

**现象**：写一条扫 `/proc/*/cmdline` 找 `etad` 并 `kill -9` 的清理命令，执行后 `InstallerShellRunner` 直接中断、拿不到任何输出。

**根因**：这条 shell 自己的 cmdline 里就包含 `etad` 这个字符串（命令文本本身）。按 `contains` 匹配会把执行它的 shell 一起选中，`kill -9` 打到自己身上。

**解法**：只比 cmdline 的**第一个 token**，且要求它以 `/etad` 结尾：

```sh
n=$(tr '\000' ' ' < $d/cmdline); a=${n%% *}
case "$a" in */etad|etad|./etad) kill -9 ${d#/proc/};; esac
```

**教训**：**凡是「按命令行内容找进程」的脚本，都要防住自己。** 用 argv[0] 而不是整条 cmdline，是唯一稳的写法。

---

### P060 · `graphify extract` 扫不到文件
**现象**：节点数远少于预期。
**根因**：graphify 按 `.gitignore` 取文件。生成物、缓存目录如果写在 ignore 里就会被跳过。
**解法**：`AGENTS.md`、`docs/` 这类需要在图谱里的文件**不要**加进 `.gitignore`。当前 `graphify-out/` 已 ignore（它本身是产物）。

### P061 · 4 个 Kotlin 文件解析不全
**现象**：`AgentContextCompactor.kt` 只有 3 个符号。
**根因**：tree-sitter 的 Kotlin 语法版本与项目 Kotlin 2.4.20 的新语法不完全兼容。
**解法**：已知缺陷，不影响主流程。这几个文件靠 `graphify explain` 查不到完整信息时，直接读源码。