---
name: re-pc
description: PC/主机端逆向与游戏安全方法论。PE 结构分析、x64dbg/IDA 调试、DLL 注入、内核驱动、内存读写库、反作弊对抗、Web/协议逆向。
---

# PC / 主机端逆向与游戏安全

## 先认清一件事：你在 Android 手机上跑

**这条最重要，先读完再动手。**

Eta 跑在 Android 上，当前设备有 Linux 容器（Debian chroot）和 root，能做：

- ✅ **读代码、分析样本**：把 PC 的 `.exe`/`.dll` 拷到手机，用 `readelf`/`objdump`/`strings`/`lief`/`capstone` 做静态分析
- ✅ **写工具代码**：C/C++ 注入器、Python 分析脚本、IDA 插件——写出来给用户在电脑上编译运行
- ✅ **讲解与方案**：说清每一步在电脑上怎么做、用什么工具、参数是什么
- ✅ **审结果**：用户把电脑上的输出（截图、日志、dump）发过来，分析判断
- ✅ **Web/协议逆向**：纯网络侧的分析可以在这台机器上直接跑（`python3` `node` `curl`）

做不到：

- ❌ **不能直接运行 Windows 程序**。没有 Wine，没有 Windows 内核
- ❌ **不能 attach 到 PC 进程**。x64dbg/IDA 调试器在电脑上，不在这里
- ❌ **不能加载 `.sys` 驱动**。Android 内核和 Windows 内核是两套东西
- ❌ **不能注入 DLL 到 PC 游戏**。目标进程不在本机

**所以本 skill 的定位是：给出在电脑上可执行的完整方案 + 审用户回传的结果。** 不要假装能在这里跑 x64dbg，也不要给一条「先在本机 attach」的路径。

如果用户要的是「在这个手机上分析 PC 样本」，那就做静态分析（第 2 步），这是真能做的。

---

## 工作流程：六步决策树

### 第 1 步：先问清目标是什么

不要一上来就给工具名。先分清这四类，路径完全不同：

| 目标 | 典型场景 | 走哪几步 |
|---|---|---|
| **单机游戏改数值** | 改金币、改血量、解锁 | 第 2 步 → 第 4 步 |
| **在线游戏 / 有反作弊** | 竞技游戏、有 EAC/BE/Vanguard | 第 2 步 → 第 5 步 → 第 6 步 |
| **逆向算法 / 协议** | 抠加密、还原通信格式 | 第 2 步 → 第 3 步 |
| **安全研究 / CTF** | 分析样本、找漏洞 | 第 2 步 → 第 3 步 |

**关键分岔**：**有内核级反作弊（Vanguard/EAC/BE）的在线游戏，第 4 步的常规内存读写基本无效。** 必须先跟用户说明这一点，不要给一套注定失败的方案。

### 第 2 步：静态分析（这一步在手机上真能做）

把目标文件拷到手机，用已装工具分析：

```bash
# 2.1 认文件类型
file target.exe
readelf -h target.dll          # PE 用 readelf 也能看基本头

# 2.2 看导入导出（找关键 API）
objdump -p target.dll | grep -A200 "Export Address Table"
objdump -p target.dll | grep -iE 'IMPORT|DLL Name'

# 2.3 找字符串（最高效的第一步）
strings -n 6 target.exe | grep -iE 'key|license|serial|patch|debug|error' | head -40
strings -t x target.exe | grep -i 'wrong serial'     # -t x 给偏移，用于定位

# 2.4 PE 结构（lief 已装，这是最强的）
python3 -c "
import lief
pe = lief.parse('target.exe')
print('Machine:', pe.header.machine)
print('Sections:')
for s in pe.sections:
    print(f'  {s.name:8} vaddr=0x{s.virtual_address:x} vsize=0x{s.virtual_size:x} ent=0x{s.entropy:.2f}')
print('Imports:')
for lib in pe.imports:
    print(' ', lib.name, [f.name for f in lib.entries][:10])
"
```

**看点**：

- Section 里 `entropy > 7.0` → 加密或压缩段，重点目标
- 导入表里有 `IsDebuggerPresent` / `CheckRemoteDebuggerPresent` → **有反调试**，走第 5 步
- 导入表里有 `VirtualProtect` + `WriteProcessMemory` → 它自己就在做内存操作
- `.NET` 程序（导入 `mscoree.dll`）→ 换 dnSpy 路径，不是 IDA

### 第 3 步：代码分析（给方案，用户在电脑上做）

**这一步给的是操作步骤，不是本机能跑的命令。**

| 目标类型 | 用什么 | 关键操作 |
|---|---|---|
| 原生 x86/x64 | **IDA Pro / Ghidra** | 打开 → 等自动分析 → 按 `F5` 反编译 → 从字符串交叉引用（`X`）倒推 |
| .NET | **dnSpy / ILSpy** | 直接打开就能看 C#，可原地改 IL 再保存 |
| Unity (C#) | **dnSpy + AssetRipper** | `Assembly-CSharp.dll` 在 `Managed/` 目录，直接反编译 |
| UE (C++) | **IDA + UE4SS / SDK dump** | 用 `UE4SS` 或 `UEDumper` 出 SDK，再回 IDA 对照 |
| 加壳 | **PEiD / Detect It Easy** 先认壳 | 认出壳后找对应脱壳工具 |

**从字符串定位函数的标准流程**（告诉用户按这个做）：

1. IDA 里 `Shift+F12` 打开字符串窗口
2. 找到目标字符串（如 `"Invalid serial"`）
3. 双击 → 按 `X` 看交叉引用
4. 跳到引用它的函数 → `F5` 反编译
5. 往上找 `if` 判断 → 那就是校验点

细节读 `references/platform-pc.md`、`references/binary-re.md`。

### 第 4 步：内存读写（给方案，用户在电脑上做）

**先判能不能做**：

```
游戏有反作弊吗？
├── 没有 → 常规方案可行
│   ├── 单机 → Cheat Engine 手动找基址 + 指针链
│   └── 要写工具 → C++ + ReadProcessMemory/WriteProcessMemory
└── 有反作弊
    ├── 用户态反作弊（早期 BE）→ 有绕过空间，但随时失效
    └── 内核级（Vanguard/EAC/BE 新版）→ 用户态读写会被直接检测，必须上 DMA 或驱动
```

**标准步骤（CE 找基址）**：

1. 首次扫描目标值（如金币 `1000`），类型选 `4 Bytes`
2. 游戏内让数值变化（花掉 / 获得）
3. 回到 CE 用「Next Scan」筛（`1000 → 900`）
4. 反复 2-3 次，通常剩 1-3 个地址
5. 右键 → 「Find out what accesses this address」→ 看是什么指令在读写
6. 在那条指令里找基址 + 偏移组合（`[[base+0x1A0]+0x2C]` 这种形式）
7. 用这个指针链写脚本，重启游戏后仍有效

**写代码时用 libmem**（比裸 Win32 简单很多）：

```c
#include "libmem/libmem.h"
lm_process_t proc;
LM_OpenProcess(&proc, pid);
lm_address_t addr = LM_AllocMemory(size, LM_PROT_RW);
LM_WriteMemory(addr, buf, size);
```

细节读 `references/libmem-guide.md`、`references/memory-edit.md`（后者偏移动端，但指针链思路通用）。

### 第 5 步：DLL 注入（给方案）

**先判注入方式**：

| 方式 | 适用 | 检测风险 |
|---|---|---|
| `CreateRemoteThread` + `LoadLibrary` | 无保护进程 | 高（几乎所有反作弊都盯） |
| `SetWindowsHookEx` | 有消息循环的 GUI 程序 | 中 |
| **Manual Mapping** | 反作弊较弱的场景 | 低（不走 `LoadLibrary`，无模块记录） |
| `Thread Hijacking` | 已暂停的进程 | 中 |
| **驱动注入** | 强反作弊 | 最低（但要签名） |
| **DMA 硬件** | 内核级反作弊 | 极低（但成本上千元） |

**Manual Mapping 是主流选择**，因为它不出现在模块列表里。核心步骤：手动解析 PE 头 → 分配内存 → 复制 Section → 处理重定位 → 修复导入表 → 调入口点。

细节和完整代码框架读 `references/dll-injection.md`。

**驱动开发**（内核级反作弊对抗才需要）：

- 必须解决签名问题（测试签名模式 / 盗用签名 / 过期证书）
- Windows 10+ 强制 DSE，未签名驱动加载不上
- 内核里做内存读写可以绕过大多数用户态检测
- 但会触发 PatchGuard，需谨慎

读 `references/driver-dev.md`。

### 第 6 步：反作弊对抗（先判等级，再决定要不要继续）

**这一步必须诚实地告诉用户代价。**

| 反作弊 | 等级 | 用户态能否绕过 | 现实方案 |
|---|---|---|---|
| 无 | — | — | 随便做 |
| 早期 BE / 自研 | 低 | 能 | 常规注入 + 特征混淆 |
| EAC / BE(新版) | 中高 | 部分 | 驱动 / DMA |
| **Vanguard** | 极高 | 否 | 只能 DMA 硬件或虚拟机逃逸，成本极高，且随时封 |
| 腾讯 ACE / 网易 | 高 | 很难 | 基本只能 DMA |

**遇到内核级反作弊，正确做法是明确告知**：常规手段无效，能给的最优方案是什么，代价多大，成功率多少。不要给一套看起来完整但必然失败的方案。

细节读 `references/anti-cheat.md`、`references/web-re.md`。

---

## 失败判定表

| 现象 | 含义 | 该做什么 |
|---|---|---|
| CE 扫描第二次就找不到地址 | 值被加密存储（XOR/多值校验） | 找「Unknown initial value」+ 变化量扫描 |
| 找到地址但重启就失效 | 动态分配，没有固定基址 | 必须找指针链，不能写死地址 |
| 注入后进程秒退 | 有模块校验 / 线程检测 | 改 Manual Mapping，别用 `LoadLibrary` |
| 一开调试器游戏就崩 | 有反调试（`IsDebuggerPresent` / `NtQueryInformationProcess`） | 先过反调试（ScyllaHide 插件） |
| 驱动加载报 `0x000000C1`（签名问题） | DSE 拦截 | 关闭驱动签名强制 / 用测试签名 |
| .NET 反编译出来是乱码 | 被 ConfuserEx / .NET Reactor 混淆 | 用 de4dot 先脱混淆 |
| UE 游戏 IDA 里函数全是 `sub_XXXX` | 无符号 | 先跑 UE4SS/UEDumper 出 SDK，再对照 |

---

## 参考文件索引

| 文件 | 什么时候读 |
|---|---|
| `platform-pc.md` | PC 平台全景、Windows 工具链、调试器使用 |
| `binary-re.md` | ELF/PE 二进制分析、反汇编、补丁 |
| `dll-injection.md` | DLL 注入全套方法、Manual Mapping 实现 |
| `driver-dev.md` | Windows 内核驱动开发、签名、DSE 绕过 |
| `libmem-guide.md` | libmem 库用法（跨平台内存操作） |
| `cpp-game-dev.md` | C++ 游戏辅助开发框架、SDK 结构 |
| `platform-console.md` | 主机平台（PS/Xbox/Switch）逆向要点 |
| `web-re.md` | Web/JS 逆向、浏览器调试、混淆还原 |
| `protocol-re.md` | 协议逆向方法论、加密定位、格式还原 |
| `resources.md` | 工具与学习资源清单 |
| `cheat-pc-1.md` / `cheat-pc-2.md` | PC 端漏洞利用专项（提取自大 cheatsheet） |
| `cheat-injection.md` | 注入与 Shellcode 专项 |
| `cheat-anticheat.md` | 反作弊对抗专项（2024-2026 检测与绕过） |
| `cheat-network.md` | 网络协议与 P2P 专项 |
| `cheat-graphics.md` | 图形 API Hook（DX12/Vulkan/Metal） |
| `cheat-meta.md` | 工具链、学习路径、文档速查 |
| `cheat-misc.md` | 杂项：主机、云游戏、VR、区块链游戏、硬件、固件 |

---

## 三条硬规则

1. **先声明边界**。动手前说清「这一步在手机上能做 / 只能在电脑上做」，不要让用户以为能在这里跑 x64dbg。
2. **内核级反作弊要如实说**。Vanguard/EAC/ACE 这类，常规方案无效就是无效，不要给假希望。
3. **给方案要给到可执行**。说「用 IDA 分析」等于没说，要说「IDA 里 `Shift+F12` 找字符串 → 双击 → `X` 看引用 → `F5` 反编译」。