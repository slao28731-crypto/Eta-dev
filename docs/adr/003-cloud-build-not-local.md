# ADR 003 · 用 GitHub Actions 云编译，不在本地编译

- **状态**：已采纳
- **日期**：2026-10-18

## 背景

开发环境是 Android 手机上的 PRoot Ubuntu（aarch64，15GB RAM，其中约 10GB 已占用）。要编译 Eta 需要：

```
JDK 25
Android SDK Platform 37（platforms;android-37.0，预览通道）
NDK 30.0.16248370
AGP 9.4.1 + Kotlin 2.4.20 + KSP 2.3.12
543 个 Kotlin 文件 + Room KSP 注解处理 + R8
```

## 实测障碍

### 障碍 1 · aapt2 是 x86_64 二进制

```
$ readelf -h $ANDROID_HOME/build-tools/36.0.0/aapt2
  Class:   ELF64
  Machine: Advanced Micro Devices X86-64
```

build-tools 里的 `aapt2`、`aapt`、`aidl` 全部是 x86_64 ELF。在 aarch64 PRoot 上**不能直接执行**。

可选解法：

| 方案 | 状态 |
|---|---|
| box64 / qemu-x86_64 二进制翻译 | 未安装，需要额外搭建 |
| Termux 的 aarch64 aapt2（16.0.0.4） | 版本太老，不兼容 SDK 37 的资源格式 |
| 换 build-tools 的 arm64 版本 | Google 官方不提供 |

### 障碍 2 · 内存不足

15GB 总内存中约 10GB 已被系统占用，可用约 5GB。

`gradle.properties` 要求：

```
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
kotlin.daemon.jvmargs=-Xmx4g
```

Gradle daemon 4GB + Kotlin daemon 4GB = 8GB，加上 AGP、KSP、R8 常驻，**必然 OOM**。

### 障碍 3 · 耗时

543 个 Kotlin 文件 + Room KSP + R8 混淆，在 4 vCPU / 16GB 的 GitHub runner 上实测约 9 分钟（11:10→11:19）。
在手机 aarch64 PRoot（性能约为同级 x86 的 1/3~1/2）上，保守估计 30 分钟起步，且随时可能因为内存被系统杀掉。

## 决策

**用 GitHub Actions `ubuntu-latest` 云编译。**

已实现的 `.github/workflows/build-apk.yml`：

```
runs-on: ubuntu-latest
timeout-minutes: 90
  → JDK 25 (temurin)
  → Gradle setup (cache-provider: basic)
  → android-actions/setup-android
  → sdkmanager --channel=3 "platforms;android-37.0"
  → sdkmanager "ndk;30.0.16248370"
  → keytool 临时签名
  → ./gradlew :app:assembleDebug :app:assembleRelease
  → apksigner verify
  → upload-artifact
```

## 实测效果

| 项 | 数值 |
|---|---|
| 触发 | push main |
| 环境准备 | JDK25 / SDK37 / NDK30 / 签名 全部 success |
| 编译耗时 | 约 9 分钟 |
| 产物 | `Eta-v3.3.1-debug+2314aa9.apk`（82MB）/ `Eta-v3.3.1+2314aa9.apk`（11MB） |
| 签名验证 | `CN=Eta Dev, OU=Modified, O=EtaDev` / SHA-256 `02464e69...` |
| 费用 | GitHub 公开仓库 Actions 免费 |

## 后果

**正面**：

- 不需要在手机上搭 NDK / SDK 37 / JDK 25 全套环境
- 不占用手机内存，不影响日常使用
- 构建稳定可复现，环境每次干净重建
- 免费（public repo）

**负面**：

- 改动 → push → 等 10 分钟 → 下载，比本地增量编译慢
- 需要联网
- 本地无法快速验证编译错误（只能靠 `graphify` 静态分析 + 代码审查）

## 备选方案（未采用）

| 方案 | 否决理由 |
|---|---|
| 装 box64 + qemu 本地编译 | 搭环境成本高，且内存问题解决不了 |
| 只装 JDK 25 + SDK 37，不编 release | debug 也要 aapt2，同样卡在 x86_64 |
| 用 Termux 的 aapt2 | 版本 16.0.0.4，不支持 SDK 37 资源 |
| 租云服务器自己搭 | 需要付费 + 维护，Actions 免费且够用 |

## 相关

- `.github/workflows/build-apk.yml`
- `docs/CONVENTIONS.md` 第 6、8 节
- `docs/PROGRESS.md` 基线