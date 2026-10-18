# 工程约定

## 1. 代码风格

沿用项目既有风格（Kotlin official）：

- `val` 优先，不可变集合优先
- 不用 `!!`，用 `?:` / `requireNotNull` / 提前 return
- 数据类承载状态，不用可变字段
- Composable 大驼峰；耗时计算放 `remember` 或 ViewModel
- 命名直白，不用缩写
- 注释解释**为什么**，不解释**是什么**
- 不写未使用的参数、导入、TODO 占位符

**包结构**（沿用上游，不改成 feature 分包）：

```
io.github.mangi.eta.<模块>.<子模块>
```

## 2. 协程

- 用结构化并发，**禁止 `GlobalScope`**
- IO 用 `Dispatchers.IO`，主线程不做磁盘/网络
- `viewModelScope` 里不做阻塞调用
- 可取消：长任务要响应取消信号

## 3. Compose / UI

- **Compose + Miuix**（`top.yukonga.miuix.kmp`），不是 Material3。新 UI 跟随 Miuix 组件
- 单向数据流：`AgentAppState` 持有状态 → Composable 渲染 → 事件回传
- `LazyColumn` 必须提供稳定 key
- 避免嵌套滚动
- 可点击元素提供 `contentDescription`
- 支持动态字体与深色模式
- 字符串进 `res/values*/strings.xml`，**三种 locale**：`values`（en）、`values-b+zh+Hans`、`values-b+zh+Hant`

## 4. 依赖管理

**版本只从 `gradle/libs.versions.toml` 取，不在 `build.gradle.kts` 里写死版本号。**

当前关键版本（改动需评估连锁影响）：

| 依赖 | 版本 | 备注 |
|---|---|---|
| AGP | 9.4.1 | |
| Kotlin | 2.4.20 | |
| KSP | 2.3.12 | Room 编译器 |
| Gradle | 9.7.1 | |
| JDK | 25 | toolchain |
| NDK | 30.0.16248370 | |
| compileSdk / targetSdk | 37 | |
| minSdk | 33 | |
| Miuix | 0.9.4 | ui / blur / nav / preference |
| libxposed api/service | 102.0.0 | |
| DexKit | 2.3.0 | |
| Room | 2.8.5 | |
| OkHttp | 5.5.0 | + sse |
| kotlinx-serialization | 1.11.0 | |
| kotlinx-coroutines | 1.11.0 | |
| Compose activity | 1.13.0 | |
| lifecycle | 2.11.0 | |
| desugar_jdk_libs_nio | 2.1.5 | |

新增依赖：先查官方文档或 Maven 确认当前稳定版，说明选择理由，再写进 toml。

## 5. 安全 / 密钥

- **API Key、Token、keystore 绝不进仓库**
- 签名走环境变量：`ETA_RELEASE_STORE_FILE` / `ETA_RELEASE_STORE_PASSWORD` / `ETA_RELEASE_KEY_ALIAS` / `ETA_RELEASE_KEY_PASSWORD`
- CI 里从 Actions Secrets 读，本地用 `local.properties` 或 shell export
- 日志中敏感信息必须过 `core/LogSafety.kt`
- `local.properties` 已在 `.gitignore`

## 6. 构建

```bash
# 环境：JDK 25 + SDK 37（含 platforms/android-37.0）+ NDK 30.0.16248370
echo "sdk.dir=/path/to/android-sdk" > local.properties

# Debug
./gradlew :app:assembleDebug

# Release（需四个签名环境变量）
export ETA_RELEASE_STORE_FILE=/abs/path/dev.jks
export ETA_RELEASE_STORE_PASSWORD=xxx
export ETA_RELEASE_KEY_ALIAS=eta
export ETA_RELEASE_KEY_PASSWORD=xxx
./gradlew :app:assembleRelease

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug
```

产物命名由 Gradle 控制：`Eta-v<versionName>[-debug][+<sha>].apk`。

**配置缓存已开启**（`org.gradle.configuration-cache=true`）。CI 里为稳定性加了 `--no-configuration-cache`。

## 7. 发版

`versionCode` 规则：`yyyyMMdd` + 两位当日序号（01 起），**只增不减**。

```kotlin
versionCode = 2026101401   // 2026-10-14 第 01 个
versionName = "3.3.1"
```

修改时两者同步更新。lint 里有 `HighAppVersionCode` 已禁用（日期编码会超常规上限，2100000000 覆盖到 2099 年）。

## 8. CI

`.github/workflows/build-apk.yml`（自建，已启用）：

```
push main / 手动触发
  → JDK 25 (temurin)
  → Gradle (cache-provider: basic)
  → Android SDK: licenses + platforms;android-37.0 (--channel=3) + ndk;30.0.16248370
  → keytool 临时生成 JKS（RSA 4096，10 年）
  → ./gradlew :app:assembleDebug :app:assembleRelease
  → apksigner verify
  → upload-artifact: eta-apk-<sha>（保留 30 天）
```

`.github/workflows/pr-check.yml`（上游保留）：PR 时跑 lintDebug + testDebugUnitTest + assembleDebug。

`.github/workflows/android-release.yml`（**已删除**）：上游的发版流水线依赖 4 个签名 Secret，本仓库不适用。

## 9. 测试

- 单元测试：`app/src/test/kotlin/`，JUnit4 + Robolectric
- Room 测试用 `room-testing`
- Robolectric 需要 JVM 参数 `--add-opens=...`，已在 `app/build.gradle.kts` 的 `testOptions` 里配好

## 10. 提交规范

```
<type>: <subject>

<why, 不是 what>
```

`type`：`feat` / `fix` / `refactor` / `docs` / `chore` / `ci` / `perf` / `test`

## 11. 文档维护

- `AGENTS.md` 是唯一入口，新 agent 必读
- 每次任务后更新 `docs/PROGRESS.md`
- 踩坑记 `docs/PITFALLS.md`（现象 → 根因 → 解法）
- 架构决策记 `docs/adr/NNN-标题.md`
- 改完代码跑 `graphify update .`

## 12. 工具链速查

| 工具 | 用途 | 命令 |
|---|---|---|
| graphify | 代码图谱 | `graphify update .` / `graphify affected "X"` / `graphify explain "X"` |
| graphify skill | agent 集成 | 已在 `.agents/skills/graphify/` |
| adb | 装包/看日志 | `adb install -r xxx.apk` / `adb logcat -s Eta` |
| apksigner | 验签 | `apksigner verify --print-certs xxx.apk` |
| aapt2 | 看包信息 | `aapt2 dump badging xxx.apk` |