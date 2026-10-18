# START HERE — 新 Agent 三步对齐

> 你刚接手这个项目。**按顺序做完这三步再动手**，15 分钟对齐，不要跳过。

---

## 第 1 步：读四份文件（约 12 分钟）

```bash
cat docs/USER_RULES.md # 用户要求：怎么干活（最高优先级）
cat AGENTS.md          # 契约：架构、红线、收工协议
cat docs/STATE.md      # 现状：做到哪、下一步、有坑没
cat docs/PITFALLS.md   # 17 条已踩过的坑
```

**`USER_RULES.md` 必须先读**。它规定了：接到需求不许直接改代码，要先摸清链路、反向提问、等用户确认。

**不要读全部源码。不要一上来就 grep。** 你不需要，图谱和分析文档已经覆盖了。

---

## 第 2 步：确认图谱可用（1 分钟）

```bash
graphify god-nodes --top 10
```

有输出 → 图谱在，直接能用。
报 `graph.json not found` → 重建（2 分钟，零成本）：

```bash
graphify extract . --code-only
graphify cluster-only . --no-label
```

---

## 第 3 步：确认工作区干净（1 分钟）

```bash
git status --short
git log --oneline -3
```

有未提交改动 → **先问用户这些改动是什么**，不要直接提交或回滚。

装一次 git hook（新机器 clone 后必做，否则图谱会和代码脱节）：

```bash
bash scripts/install-hooks.sh
```

装好后，每次提交只要涉及 `.kt`/`.java`，会自动跑 `graphify update .`。文档提交不受影响。

---

## 然后才能开始接需求

接到需求后，**先查影响面，再动手**：

```bash
# 需求涉及哪个类？先看它连了谁
graphify explain "AgentModelClient"

# 要改的类会影响哪些地方？
graphify affected "AgentModelClient" --depth 2

# 不知道逻辑在哪？用自然语言问
graphify query "how does tool calling work" --budget 3000
```

---

## 收工前必做（不可跳过）

按 `AGENTS.md` 第 6 节的清单逐项打勾。**最常被跳过的两项**：

1. `docs/STATE.md` 的「下一步」段没更新 → 下一个 Agent 不知道从哪接
2. `graphify update .` 没跑 → 图谱与代码脱节，`affected` 结果会骗人

判断标准：**如果明天换一个完全不认识这个项目的 Agent 来，他读你的更新能不能接着干？** 不能就是没写完。

---

## 三条最容易犯的错

| 错法 | 后果 |
|---|---|
| 一上来 `grep -r` 全项目找代码 | 浪费时间，而且找不到跨层依赖 |
| 改完代码只 commit 不更新文档 | 文档腐烂，下一个 Agent 被误导 |
| 在 `docs/` 里写「本文件实现了 XXX 功能」 | 废话，等于没写 |

---

## 项目一句话

Eta 是 Android 系统级 AI Agent（Xposed 注入厂商语音助手 + 无障碍 GUI 操作 + Root 终端），
当前是个人二改版，`applicationId = io.github.mangi.eta`，543 个 Kotlin 文件 / 10.5 万行。
**云端编译**（GitHub Actions），**本地不编译**（aapt2 是 x86_64，跑不了）。