# 踩坑清单

> 格式：**现象** → **根因** → **解法**。新坑往上加，不要只写结论。

---

## 构建 / 工具链

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

---

## 文档 / 图谱

### P060 · `graphify extract` 扫不到文件
**现象**：节点数远少于预期。
**根因**：graphify 按 `.gitignore` 取文件。生成物、缓存目录如果写在 ignore 里就会被跳过。
**解法**：`AGENTS.md`、`docs/` 这类需要在图谱里的文件**不要**加进 `.gitignore`。当前 `graphify-out/` 已 ignore（它本身是产物）。

### P061 · 4 个 Kotlin 文件解析不全
**现象**：`AgentContextCompactor.kt` 只有 3 个符号。
**根因**：tree-sitter 的 Kotlin 语法版本与项目 Kotlin 2.4.20 的新语法不完全兼容。
**解法**：已知缺陷，不影响主流程。这几个文件靠 `graphify explain` 查不到完整信息时，直接读源码。