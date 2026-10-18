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