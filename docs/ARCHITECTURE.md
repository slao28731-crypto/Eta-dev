# 架构说明

## 1. 整体分层

```
┌─────────────────────────────────────────────────────────┐
│  UI 层（ui/）  Compose + Miuix                          │
│  MainActivity / AgentAppState / screens / components    │
└────────────────────┬────────────────────────────────────┘
                     │ UiEvent / UiState（单向）
┌────────────────────▼────────────────────────────────────┐
│  Agent Runtime（agent/runtime/ + agent/model/）         │
│  Agent Loop：模型调用 → 工具执行 → 结果回填 → 再决策     │
└────────────────────┬────────────────────────────────────┘
                     │
      ┌──────────────┼──────────────┬─────────────┐
      ▼              ▼              ▼             ▼
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│ 工具层    │  │ Provider │  │  数据层   │  │ 系统接口  │
│ tool/    │  │ model/   │  │  data/   │  │ device/  │
│ accessi- │  │ SSE 三种 │  │ Room +   │  │ terminal │
│ bility/  │  │ 协议     │  │ DataStore│  │ voice    │
│ terminal │  │          │  │          │  │ browser  │
└──────────┘  └──────────┘  └──────────┘  └──────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│  Hook 层（hook/）  Xposed 注入厂商助手进程               │
│  breeno / xiaoai / colordirect / hyperos / google        │
│  通过 AgentRuntimeWire 与主进程通信                       │
└─────────────────────────────────────────────────────────┘
```

## 2. Agent Loop 数据流

一次完整 run 的生命周期：

```
用户输入（聊天 / 语音 / 系统助手入口）
    │
    ▼
AgentRunController.start()
    │  创建 RuntimeRun 记录（Room）
    ▼
AgentModelClient.request()
    │  按 Provider 类型选协议：
    │  ├─ OpenAI Chat Completions → SSE 解析 delta.content / delta.tool_calls
    │  ├─ OpenAI Responses        → SSE 解析 response.output_text.delta /
    │  │                            response.output_item.done(function_call)
    │  └─ Anthropic Messages      → SSE 解析 content_block_delta /
    │                               content_block_start(tool_use)
    ▼
模型返回 tool_calls
    │
    ▼
AgentLocalTools.execute()
    │  按权限策略检查 → 分派到具体执行器
    │  工具类型：文件 / 终端 / 无障碍 GUI / 浏览器 / 设备 API /
    │           MCP 远程 / 记忆 / Skill
    ▼
执行结果写回 transcript
    │  敏感工具的原始参数与结果不写持久会话（只留在内存）
    ▼
回到 AgentModelClient.request()（下一轮）
    │  直到模型不再调用工具，或达到轮次上限，或用户取消
    ▼
AgentRunController.finish()
    │  写回最终回复、更新 UI 状态、清理资源
    ▼
UI 通过 AgentAppState 观察结果
```

**关键点**：`steering`（追加指令）在当前 turn 完成后才进入下一轮，不会打断正在执行的工具。

## 3. 进程模型

| 进程 | 组件 | 职责 |
|---|---|---|
| 主进程 | `MainActivity`、`AgentRuntimeService`、`AgentExecutionService` | UI、Agent Runtime、Room 读写 |
| `:voice` | `EtaVoiceInteractionService`、`EtaVoiceAssistActivity`、`EtaAssistantOverlayService`、`ReadAloudPlaybackService` | 语音交互、浮窗、播报 |
| `:recognition` | `EtaRecognitionService` | 语音识别 |
| Hook 进程 | `ModuleMain` + 各 `*Hooks` | 注入目标 App，接管入口 |

### 跨进程通信

```
App 主进程                        Hook 进程（注入到厂商 App）
    │                                    │
    │  AgentRuntimeWire（协议层）         │
    ├──── 请求（工具执行 / 状态查询）───►│
    │◄─── 结果（回传 / 事件）────────────┤
```

`AgentRuntimeWire` 是 100 条边的枢纽，**改协议必须两端同步**，否则 Hook 与 App 版本错配会静默失败。

## 4. 启动流程

### App 冷启动

```
EtaApp.onCreate()
  ├─ 初始化 Room（EtaDatabase）
  ├─ 初始化 DataStore（Provider / Model / 配置）
  ├─ 加载长期记忆（MEMORY.md）
  └─ 恢复上次会话

MainActivity
  ├─ 检查 Root 状态 → 决定默认执行身份
  ├─ 检查无障碍服务 / 通知监听 / 使用统计 授权状态
  ├─ 检查 Xposed 模块激活状态（通过 IContentProvider 健康检查）
  └─ 渲染主界面
```

### Hook 侧启动

```
目标 App 启动
  └─ LSPosed 加载模块（读取 java_init.list）
       └─ ModuleMain.onPackageReady(PackageReadyParam)
            └─ 按包名分派：
                 com.google.android.googlequicksearchbox → Google*
                 com.coloros.colordirectservice          → ColorDirectHooks
                 com.heytap.speechassist                  → BreenoHooks
                 com.miui.voiceassist                     → XiaoAiHooks
                 system                                   → 系统级 hook
```

## 5. 数据层结构

```
data/
├── db/            Room：会话、消息、Run、Provider、Model、角色卡、记忆
├── datastore/     DataStore Preferences：当前选中模型、开关状态
├── repository/    Repository 层，隔离 Room/DataStore 与 UI
├── provider/      Provider/Model 目录与能力解析（ReasoningEffort 等）
└── settings/      工具权限、敏感开关
```

**敏感数据边界**：

- 敏感工具与 MCP 的原始参数/结果**不进持久会话**
- 通知历史授权后保存最近 7 天、最多 1000 条
- MCP 认证令牌加密保存
- 备份包含 API Key，分享角色卡不含私人对话

## 6. 工具系统

工具分四类，统一由 `AgentLocalTools` 注册与分发：

| 类别 | 位置 | 示例 |
|---|---|---|
| 设备 API | `agent/device/` | 闹钟、媒体控制、音量、应用信息、系统日志（Root） |
| GUI Agent | `agent/accessibility/` | 无障碍树、控件定位、点击/滚动/输入、截图 |
| 终端/文件 | `agent/terminal/`、`agent/tool/` | Root Shell、Alpine/Debian、PTY、文件读写编辑搜索 |
| 扩展 | `agent/mcp/`、`agent/browser/`、`agent/memory/`、`agent/skill/` | MCP 远程工具、WebView、MEMORY.md、Skills |

**执行前必查权限**：工具开关 + 系统授权 + Root 状态。撤权后 Runtime 会重新检查，不会用缓存配置放行。

## 7. UI 层

- `ui/app/AgentAppState.kt`（86 条边）是全局状态中心，持有所有 UI 状态
- `ui/navigation/` 路由
- `ui/screens/` 页面级 Composable
- `ui/components/` 可复用组件（41 个文件）
- `ui/markdown/` 自建 Markdown 渲染，按块冻结 + 逐字显现
- `ui/pages/` 设置类页面（Provider、工具、Skills、角色卡等）

**重组注意**：`AgentAppState` 变化会触发大面积重组，新增字段谨慎；`LazyColumn` 必须提供稳定 key。