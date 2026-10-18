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
- [ ] 仓库可见性：Eta 是 PolyForm Noncommercial 许可，限制二次分发，考虑改 private

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