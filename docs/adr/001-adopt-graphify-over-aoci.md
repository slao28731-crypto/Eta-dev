# ADR 001 · 放弃 AOCI-CODE，采用 Graphify 作为项目认知方案

- **状态**：已采纳
- **日期**：2026-10-18

## 背景

需求是：让 AI Agent在开发中既能理解全项目，又能产出可交接的文档，降低"换人 / 换 agent / 隔几个月回来"的成本。

评估了两个方案：

### AOCI-CODE（v0.1.0-rc21）

模型为每个受管文件写一条 FRAS 条目（F 职责 / R 强关系 / A 接口 / S 不可推断约束），存成纯文本 `aoci.code.txt`，随 git 版本化，带完整治理（Baseline、Receipt、CAS、Recovery、漂移检测、面板）。

**实测数据（在本仓库副本上跑通）**：

| 项 | 数值 |
|---|---|
| git 追踪文件 | 954 |
| 进入索引 | 946 |
| 需写条目 | 895 |
| 预估索引 token | 105,860 |
| 写入批次 | 约 39 轮 Maintain（每批 ≤50 条，响应预算 24KiB） |
| 估算输入 token | 150~180 万 |

**放弃原因**：

1. **成本过高**：39 批长链路，需在同一上下文连续跑完，中途断要 resume。估算 150 万+ 输入 token。
2. **执行通道受阻**：官方推荐 Codex 宿主，但本地模型网关（workbuddy）对 agent 类请求有渠道校验，直接 400 拒绝。绕不过。
3. **产出质量风险大**：AI 写的"文件说明书"极易退化成"这个文件实现了 XXX 功能"这类从文件名就能猜到的废话，S 字段（真正有价值的部分）反而写不实。
4. **治理复杂度不匹配**：Baseline / Receipt / CAS / Recovery 这套重型机制是为多人长期协作设计的，单人改项目承担不起这个复杂度。
5. **维护成本**：代码改动后要重新走一遍创作流程。

### Graphify（v0.9.92）

tree-sitter AST 解析，把代码变成节点+边的知识图，产出 `graph.json` / `graph.html` / `GRAPH_REPORT.md`。支持 `query` / `path` / `explain` / `affected` / `god-nodes`。

**实测数据（在本仓库上跑通）**：

| 项 | 数值 |
|---|---|
| 解析代码文件 | 807（75 个非代码跳过） |
| 耗时 | 约 2 分钟（8 workers） |
| API 费用 | **0 输入 / 0 输出** |
| 产出 | 11899 节点 / 36925 边 / 436 社区 |
| 边可信度 | 96% EXTRACTED（源码直读）+ 4% INFERRED（解析推断） |
| 产物体积 | graph.json 21MB / graph.html 742KB / GRAPH_REPORT.md 59KB |
| 增量更新 | `graphify update .`，同样零成本 |

**实际查询效果**：

```
$ graphify explain 'AgentModelClient'
  Degree: 243
  <-- BreenoHooks.kt [imports] [EXTRACTED] hook/breeno/BreenoHooks.kt:L4
```

这条跨层依赖（hook 直接引用模型请求层）用 grep 翻不出来，图里一眼可见。

## 决策

**采用 Graphify，放弃 AOCI-CODE。**

分工：

| 需求 | 由谁解决 |
|---|---|
| 理解全项目、查调用链、评估改动影响 | **Graphify**（自动、零成本、可增量） |
| 交接文档、"为什么这么做"、"踩过什么坑" | **手写 markdown**（`AGENTS.md` + `docs/`） |

## 理由

1. Graphify 2 分钟零成本出结果，AOCI 要 39 批几十万 token
2. Graphify 的 `affected` / `path` / `explain` 是可执行命令，agent 能直接调用；AOCI 的产物是一大坨文本，agent 还得通读
3. Graphify 维护成本近乎为零，AOCI 每次代码变更要重走治理流程
4. **最关键**：AOCI 试图自动化"写文档"这件事，但文档的价值恰恰在"从 AST 推不出来的那部分"（ROM 适配、签名约束、jniLibs 预编译、Room 迁移风险）。这些东西只能人写或有人味的模型写，自动化出来的是废话。

## 后果

**正面**：

- Agent 开工先 `graphify query`，不用盲搜
- 改代码前 `graphify affected "X"` 立刻知道影响面
- `graphify update .` 增量维护，零成本
- 交接文档由我手写，具体到"改 hook 类名要同步改 scope.list"这种粒度

**负面 / 需承担**：

- 4 个 Kotlin 文件 tree-sitter 解析不全（`AgentContextCompactor.kt`、`AgentToolBatchRecovery.kt`、`MarkdownInline.kt`、`AgentRuntimeWireTest.kt`），这些只能直接读源码
- graph.json 21MB 不能入库，换机器要重建（2 分钟）
- 436 个社区是 `Community N` 占位名（用了 `--no-label`，命名需 LLM）

## 相关

- `AGENTS.md` 第 1 节（开工先查图）、第 5 节（Graphify 用法）
- `docs/PITFALLS.md` P003 / P004 / P060 / P061