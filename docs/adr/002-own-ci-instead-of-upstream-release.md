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
| 零硬编码 | keystore 以 base64 存于 Actions Secret `ETA_RELEASE_KEYSTORE_BASE64`，另三个口令各存一个 Secret |
| 签名稳定 | 同一 keystore 长期复用，支持覆盖升级 |
| 双产物 | `assembleDebug` + `assembleRelease` 都编译 |
| 自动校验 | `apksigner verify --print-certs` 打印证书指纹 |
| 产物命名 | 保留 Gradle 的 `Eta-v<version>+<sha>.apk` |
| 上传 | `upload-artifact`，保留 30 天 |

## 后果

**正面**：

- 无需在本地搭 JDK25 + SDK37 + NDK30 全套环境，push 就出包，约 10 分钟
- debug（82MB，不混淆）和 release（11MB，R8）都有
- 签名稳定，可覆盖升级

**负面**：

- 签名依赖 Secret 完整性：keystore Secret 被误删或覆盖，就会造出签名不同的包
- 没有 GitHub Release，只能从 Actions Artifacts 手动下载

## 签名方案（已落地）

一次性生成固定 keystore：

```bash
keytool -genkeypair -keystore eta-release.jks -storetype JKS \
  -alias eta -keyalg RSA -keysize 4096 -validity 10950 \
  -storepass "<口令>" -keypass "<口令>" \
  -dname "CN=Eta Dev, OU=Modified, O=EtaDev, L=Unknown, ST=Unknown, C=CN"
```

四个 Secret 已写入仓库：

| Secret | 内容 |
|---|---|
| `ETA_RELEASE_KEYSTORE_BASE64` | `base64 -w0 eta-release.jks`，5212 字符 |
| `ETA_RELEASE_STORE_PASSWORD` | keystore 口令 |
| `ETA_RELEASE_KEY_ALIAS` | `eta` |
| `ETA_RELEASE_KEY_PASSWORD` | key 口令 |

证书指纹 `SHA256: C9:73:FE:C3:B2:DD:29:6F:23:A5:54:1A:FA:B9:1C:B5:49:2B:5C:7A:14:70:4E:07:8B:AA:96:AA:1E:42:1F:0A`，有效期至 2056-10-10。

**keystore 不可再生**：离线至少备份三份（本地 `/sdcard/Download/eta_out/eta-release.jks`、网盘、邮箱）。丢了就只能卸载重装。

## 相关

- `.github/workflows/build-apk.yml`
- `docs/CONVENTIONS.md` 第 8 节
- `docs/PITFALLS.md` P040 / P041