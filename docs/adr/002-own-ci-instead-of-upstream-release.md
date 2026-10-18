# ADR 002 · 自建 CI，不复用上游发版流水线

- **状态**：已采纳
- **日期**：2026-10-18

## 背景

上游 `Mangi-11/Eta` 自带两条 workflow：

- `.github/workflows/android-release.yml` —— 构建 + 发版草稿
- `.github/workflows/pr-check.yml` —— PR 检查

`android-release.yml` 的签名步骤要求四个 Secret：

```
ETA_RELEASE_KEYSTORE_BASE64
ETA_RELEASE_STORE_PASSWORD
ETA_RELEASE_KEY_ALIAS
ETA_RELEASE_KEY_PASSWORD
```

缺任一个就 `::error::` 退出。且发版 job 会 `gh release create --draft --verify-tag`，只在 tag 上跑。

## 问题

1. 我们没有上游的签名证书（也不应该有）
2. 上游流水线在 main push 时也会跑，缺 Secret 直接失败
3. 上游的 tag 校验逻辑（`$GITHUB_REF_NAME == v$version_name`）对二改版没意义
4. 我们不需要 GitHub Release 草稿，只需要能装到手机上的 APK

## 决策

**删除 `android-release.yml`，新建 `.github/workflows/build-apk.yml`。**

保留 `pr-check.yml`（不依赖 Secret，只跑 lint / test / debug 构建）。

新建的 workflow 特点：

| 特性 | 做法 |
|---|---|
| 零 Secret 依赖 | 每次构建用 `keytool` 临时生成 JKS（RSA 4096，10 年，`CN=Eta Dev`） |
| 双产物 | `assembleDebug` + `assembleRelease` 都编译 |
| 自动校验 | `apksigner verify --print-certs` 打印证书指纹 |
| 产物命名 | 保留 Gradle 的 `Eta-v<version>+<sha>.apk` |
| 上传 | `upload-artifact`，保留 30 天 |

## 后果

**正面**：

- 开箱即用，不需要配置任何 Secret
- push 就出包，约 10 分钟
- debug（82MB，不混淆）和 release（11MB，R8）都有

**负面**：

- **签名每次构建都变** → 无法覆盖升级，每次装新版要卸载（丢数据）
- 没有 GitHub Release，只能从 Actions Artifacts 手动下载

## 后续

要解决签名问题（见 `docs/PROGRESS.md` 高优先级待办）：

1. 本地生成固定 keystore：
   ```bash
   keytool -genkeypair -keystore eta-release.jks -storetype JKS \
     -alias eta -keyalg RSA -keysize 4096 -validity 10950 \
     -dname "CN=Eta Dev, O=EtaDev, C=CN"
   ```
2. base64 编码：`base64 -w0 eta-release.jks`
3. 存进 Actions Secret：`ETA_RELEASE_KEYSTORE_BASE64`
4. 修改 workflow，把"生成临时证书"步骤换成"从 Secret 还原"
5. **把 keystore 文件本身离线备份好，丢了就永久无法覆盖升级**

## 相关

- `.github/workflows/build-apk.yml`
- `docs/CONVENTIONS.md` 第 8 节
- `docs/PITFALLS.md` P040 / P041