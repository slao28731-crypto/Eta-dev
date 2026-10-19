---
name: re-mobile
description: Android/iOS 逆向与游戏分析方法论。APK/IPA 拆解、jadx 反编译、smali 改写回编译、Frida 动态 Hook、内存读写、Unity/UE 引擎定位、协议抓包、加固与反调试对抗。
---

# Android 逆向与游戏分析

## 先看这一条：本机环境已经装好什么

**不要教用户自己编译 apktool、不要让他去装 jadx。本机已就位。** 动手前直接按这张表选执行环境：

| 你要做的事 | 环境参数 | 用什么 | 状态 |
|---|---|---|---|
| APK 解包（`apktool d`） | `environment=linux` | `apktool 3.0.3` | ✅ 已实测可用 |
| APK 反编译成 Java | `environment=linux` | `jadx 1.5.6` | ✅ 已实测可用（6391 个 Java 文件 / 37MB） |
| dex ↔ smali 互转 | `environment=linux` | `baksmali` / `smali 3.0.10` | ✅ 已实测可用（8.9MB dex 往返成功） |
| APK 签名 | `environment=linux` | `apksigner 0.9` | ✅ 已实测可用 |
| **APK 资源回编译** | — | `aapt2 2.19` + `apktool 3.0.3` | ❌ **走不通**，版本不匹配，见第 8 步路线 B |
| 分析 `.so` / ELF | `environment=linux` | `readelf` `objdump` `nm` `strings` `file` `hexdump` `xxd` | ✅ 已实测可用（binutils 2.44） |
| 写脚本解析汇编、改 ELF | `environment=linux` | `python3 3.14` + `capstone 5.0.7` + `lief 1.0.0` | ✅ 已实测可用 |
| 抓包 / 改包 | `environment=linux` | `python3` `curl` `openssl` `node` | 部分可用 |
| Android 系统命令 | `environment=android` + `identity=root` | `pm` `am` `dumpsys` `input` `screencap` `getprop` `cmd` | ✅ 已实测可用 |
| 读写文件 / 搜代码 | 默认文件工具 | `read_file` `grep_files` `glob_files` `list_directory` | 内置 |

**动手前必须先探活**，不要假设：

```bash
# Linux 环境里确认 APK 工具链
command -v jadx apktool smali baksmali aapt2 apksigner readelf objdump
```

如果 `jadx` 不存在 → 告诉用户「在 设置 → 终端 → Linux 环境 → APK 分析工具 里安装」，**不要自己从 GitHub 下 jadx 往里塞，也不要在 Linux 环境里手动 `apt install`（会和 Eta 的 profile 校验冲突）**。

---

## 工作流程：八步决策树

按这个顺序判断，不要跳步。

### 第 1 步：先判目标类型

| 目标 | 下一步 |
|---|---|
| 普通 Android App | → 第 2 步（静态） |
| Unity 手游 | → 第 2 步，然后读 `references/game-engines.md` |
| UE4/UE5 手游 | → 第 2 步，然后读 `references/game-engines.md` |
| 有加固 / 加壳 | → 第 5 步（先脱壳），跳回第 2 步 |
| 有反调试 / 反 Frida | → 第 6 步 |
| 纯网络协议 | → 第 7 步 |
| iOS | → 读 `references/platform-mobile.md` 的 iOS 段 |

**判定方法**：

```bash
# 解包看 lib 目录就知道引擎和架构
apktool d -f -o dec target.apk
ls dec/lib/arm64-v8a/
# libil2cpp.so + libunity.so → Unity IL2CPP
# libUE4.so / libUnreal.so  → UE
# libcocos2d*.so            → Cocos
# 只有 libflutter.so         → Flutter（逆向难度陡增）
```

### 第 2 步：静态分析（APK 结构 + 代码）

**2.1 先看清单，不要一上来就反编译全部**

```bash
cd /workspace/<项目目录>
apktool d -f -o dec target.apk
# 关键信息在 AndroidManifest.xml
grep -E 'package=|android:versionName|android:debuggable' dec/AndroidManifest.xml
grep -E 'uses-permission' dec/AndroidManifest.xml | head -30
```

**看什么**：

- `android:debuggable="true"` → 可以直接 `run-as` 或 jdwp attach，逆向难度降一档
- 权限里有 `WRITE_SECURE_SETTINGS` / `SYSTEM_ALERT_WINDOW` → 大概率有反截屏/悬浮窗检测
- `android:extractNativeLibs="false"` → `.so` 在 APK 里是压缩的，要手动解
- 有 `application android:name="...StubApp"` / `...AppWrapper` → **加固特征**，跳到第 5 步

**2.2 反编译 Java 层**

```bash
jadx -d jout --no-res target.apk          # 只要代码不要资源，快
jadx -d jout target.apk                    # 要资源
jadx -d jout --show-bad-code target.apk    # 有反编译失败时加这个
```

**输出很大时不要 `cat`**，用文件工具搜索：

```
grep_files   pattern="class .*GameManager"  path=jout
grep_files   pattern="native .*("           path=jout   # 找 JNI 入口
grep_files   pattern="getSignature|checkSign|verifySign"  path=jout  # 找签名校验
```

**2.3 定位关键逻辑的三个锚点**

1. **JNI 入口**：`grep_files pattern="native " path=jout` → 得到 native 方法名，去 `.so` 里找
2. **加密/校验**：`grep_files pattern="AES|DES|MD5|SHA|Base64|XOR" path=jout`
3. **网络**：`grep_files pattern="http|api/|/v1/|token|sign=" path=jout`

**2.4 需要看 smali 时**

jadx 反编译失败、或被混淆到看不出来，才下到 smali 层：

```bash
ls dec/smali*/                     # smali / smali_classes2 / smali_classes3...
# 寄存器(寄存器宽度)：v0-vN 是局部，p0-pN 是参数（p0 通常是 this）
# 常用操作码：invoke-virtual / invoke-static / iget / iput / const / if-eq
```

细节读 `references/decompilation.md`。

### 第 3 步：Native 层分析（`.so`）

**绝大多数手游的核心逻辑在这里。** 不要停在 Java 层就下结论。

```bash
# 3.1 先认架构
file dec/lib/arm64-v8a/libil2cpp.so
readelf -h dec/lib/arm64-v8a/libil2cpp.so | head -20

# 3.2 看导出了什么
readelf -sW --dyn-syms dec/lib/arm64-v8a/*.so | grep -E 'FUNC|OBJECT' | head -50

# 3.3 找字符串（最有效的第一步）
strings -n 8 dec/lib/arm64-v8a/libil2cpp.so | grep -iE 'key|secret|token|encrypt|sign|verify' | head -30

# 3.4 找关键函数地址
nm -D --defined-only dec/lib/arm64-v8a/*.so | head -50
```

**遇到 stripped 的 `.so`**（`nm` 无输出）：

```bash
# 用字符串交叉引用定位
strings -t x libgame.so | grep -i 'invalid signature'   # -t x 给十六进制偏移
# 拿着这个偏移去 objdump 里反汇编附近
objdump -d --start-address=0xXXXX --stop-address=0xYYYY libgame.so
```

**写脚本批量分析**（capstone 已装）：

```python
import capstone
md = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
# 读 .text 段字节 → md.disasm(code, base_addr)
```

**改 ELF**（lief 已装）：

```python
import lief
lib = lief.parse("libgame.so")
# 改导出、打补丁、加段都在这里做
lib.write("libgame_patched.so")
```

细节读 `references/binary-re.md` 和 `references/apk-static.md`。

### 第 4 步：动态分析（Frida）

**Frida 当前未预装，动手前先确认**：

```bash
command -v frida && frida --version
# 如果没有，先问用户是否要装，不要自己静默下载 110MB 的 frida-server
```

**Frida 能用了之后的常用姿势**：

```javascript
// 4.1 枚举进程与模块
frida-ps -U
Process.enumerateModules()

// 4.2 Hook Java 方法（不需要 root，只要 frida-server 或 gadget）
Java.perform(function () {
  var cls = Java.use("com.game.Player");
  cls.getGold.implementation = function () {
    var r = this.getGold();
    console.log("getGold =", r);
    return r;
  };
});

// 4.3 Hook Native 函数
var addr = Module.findExportByName("libgame.so", "check_sign");
Interceptor.attach(addr, {
  onEnter: function (args) { console.log("arg0 =", args[0]); },
  onLeave: function (retval) { console.log("ret =", retval); retval.replace(ptr(1)); }
});

// 4.4 内存扫描（找血量、金币这类值）
// 用 Memory.scan，pattern 是十六进制串
Memory.scan(Module.findBaseAddress("libgame.so"), 0x100000, "00 00 00 00", {
  onMatch: function (address, size) { console.log("hit", address); },
  onComplete: function () {}
});

// 4.5 Stalker 跟踪执行流（找算法最有效，但也最慢）
Stalker.follow(Process.getCurrentThreadId(), {
  events: { call: true, ret: true },
  onReceive: function (events) { /* 解析 events */ }
});
```

细节读 `references/hook-techniques.md` 和 `references/platform-mobile.md`。

### 第 5 步：脱壳（有加固时）

**判定加固**：`AndroidManifest.xml` 里 `application android:name` 指向第三方 Stub，或 `assets/` 下有不认识的 `.dex`/`.so`，或 `lib/` 里有 `libjiagu*.so`、`libDexHelper*.so`、`libshell*.so`。

**顺序**：

1. **先试 Frida dump dex**（`frida-dexdump`，需 frida 可用）——能过大部分一代壳
2. Frida 被检测 → 用 root + 内存 dump 整个 dex
3. 还不行 → 上 Xposed/LSPosed 模块级 Hook（本机 LSPosed 已可用）
4. 企业级壳 → 基本只能从 `.so` 下手，`.dex` 放弃

**知识不在 skill 里解决**，这是工具问题。如果工具不具备，**直接告诉用户缺什么**，不要假装能做。

### 第 6 步：反调试 / 反 Frida 对抗

**先判有没有**，不要盲目套壳：

```bash
# 找反调试特征字符串
strings libgame.so | grep -iE 'ptrace|TracerPid|frida|gum-js|Magisk|Xposed|ro.debuggable'
```

**检测到之后的顺序**：

1. `TracerPid` 检测 → Hook `fopen`/`read` 伪造返回值
2. `ptrace(PTRACE_TRACEME)` 自陷 → Hook `ptrace` 直接返回 0
3. `frida-*` 端口/线程名扫描 → `frida-server` 改名 + 改端口
4. `Magisk`/`Xposed` 检测 → 用 DenyList 或 Shamiko
5. 完整性校验（`/proc/self/maps` 有异常库）→ Hook 读取函数过滤

**完整对照表**读 `references/anti-cheat.md`。那份文件里有 2024-2026 的检测手段和对应绕过，**遇到具体检测先查它再动手**。

### 第 7 步：协议分析

**先判是明文还是加密**：

```bash
# 7.1 抓明文 HTTP
# 看 App 有没有 http:// 直连
grep_files pattern="http://" path=jout

# 7.2 HTTPS 需要中间人
# 判断是否 pinning
grep_files pattern="CertificatePinner|TrustManager|X509TrustManager|pin" path=jout
```

**有 pinning 时**，优先用 Frida 解开，而不是改 APK：

```javascript
Java.perform(function () {
  var Pinner = Java.use("okhttp3.CertificatePinner");
  Pinner.check.overload("java.lang.String", "java.util.List").implementation = function () {
    return;   // 直接放行
  };
});
```

**协议格式还原**：抓到的二进制先 `xxd` 看头部魔数，再判是不是 protobuf（字段 tag 低 3 位是 wire type）或自定义头。

细节读 `references/protocol-capture.md`。

### 第 8 步：回编译与验证（改完必须闭环）

**这一步已实测跑通，但要注意走哪条路。** 按你改了什么来选：

#### 路线 A：只改 smali 逻辑（最常见，推荐）

**不要走 apktool 全量回编译**。实测 Debian 的 `aapt2` 只有 2.19 版，apktool 3.0.3 会传 `--no-compile-sdk-metadata` 参数，老版本 aapt2 不认识，直接报 `unknown option`。这条路当前不通。

走 dex 直换，**已实测完整跑通**（8.9MB dex 反汇编 → 重汇编 → 替换 → 签名 → 14MB APK 产出）：

```bash
cd /workspace/<项目>/

# 8A.1 解包拿 dex（apktool d 能正常跑，只是 b 不回编译）
apktool d -f -o dec target.apk
cp dec/build/apk/classes*.dex .

# 8A.2 dex → smali
baksmali d classes.dex -o smali_out

# 8A.3 改 smali（用 edit_file 或 sed）
#     改完记得核对寄存器数：.registers 不够会 smali 编译失败

# 8A.4 smali → dex
smali a smali_out -o classes_new.dex

# 8A.5 替换进 APK（-j 去路径，注意文件名要一致，否则会变成新增 dex）
cp target.apk patched.apk
zip -q -j patched.apk classes_new.dex    # 若原 dex 就叫 classes.dex，这里需重命名后替换

# 8A.6 签名
keytool -genkeypair -keystore t.jks -alias k -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass 123456 -keypass 123456 -dname "CN=t"
apksigner sign --ks t.jks --ks-pass pass:123456 --out signed.apk patched.apk

# 8A.7 验签 + 装回
apksigner verify --print-certs signed.apk
pm install -r /sdcard/xxx/signed.apk
```

#### 路线 B：要改资源（`res/` 里的东西）

当前**走不通**，原因是版本链不匹配：

| 组件 | 版本 | 问题 |
|---|---|---|
| Debian `aapt2` | **2.19**（`aapt` 包提供） | 缺 `--no-compile-sdk-metadata` 等新参数 |
| apktool | 3.0.3 | 依赖新版 aapt2 的参数 |
| Google 官方 aapt2 | 需 ≥ 8.0 | 才能配 apktool 3.x |

**先告诉用户这个限制，不要硬试。** 如果一定要改资源，两条出路：
1. 在电脑上用 Android SDK 的 aapt2 回编译，再把产物拷回来
2. 等 Eta 侧换成从 Google Maven 拉 ARM64 版 aapt2（`com.android.tools.build:aapt2:*-linux.jar` 里有 `aapt2` 二进制）

#### apktool 解码阶段的两个坑（这个环节是能用的）

1. **`$` 开头的资源名**：`public.xml` 里会有 `$ic_splash_animated__0` ~ `__9`，这是 Compose 动画的编译产物。aapt2 编译时会报 `invalid entry name`。**实测 `--copy-original` 也绕不过**（它仍然要编译 `res/`）。只影响回编译，不影响解码。
2. **`--aapt` 必须传 wrapper**：apktool 会把 aapt2 复制到 `/tmp` 再执行，复制丢 x 位，报 `Syntax error: "(" unexpected`。wrapper 里 `exec /usr/bin/aapt2` 才稳。

---

## 失败判定表：什么时候该停手

| 现象 | 含义 | 该做什么 |
|---|---|---|
| `jadx` 输出大量 `// Decompiled failed` | 混淆 + 控制流平坦化 | 转 smali 层，别硬啃 Java |
| `.so` 里 `strings` 全是乱码 | 字符串加密 | 上 Frida 在运行时 dump 解密后的字符串 |
| Frida `attach` 报 `unable to find process` | 进程有反调试或 frida-server 版本不匹配 | 查版本、查 `/proc/<pid>/status` 的 TracerPid |
| `ptrace` 一直被拒绝 | 已有别的调试器占用 / 内核限制 | 换个方式，别在同一进程上叠加 |
| `apktool b` 报 `unknown option '--no-compile-sdk-metadata'` | Debian aapt2 2.19 太老，配不上 apktool 3.0.3 | 改走路线 A（dex 直换），别在资源回编译上耗 |
| `apktool b` 报 `invalid entry name '$ic_splash_animated__N'` | Compose 动画的资源名，`$` 不被 aapt2 接受 | 同样走路线 A；`--copy-original` 绕不过 |
| `apktool b` 报 `Syntax error: "(" unexpected` | aapt2 被复制到 /tmp 丢了 x 位 | `--aapt` 传 wrapper，不要传二进制本体 |
| `smali` 编译报寄存器越界 | 改 smali 时加了指令但没加 `.registers` | 把 `.registers` 数字调大（多余寄存器无害） |
| 装回手机报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 先卸载旧的，或换回原签名 |
| 应用启动就闪退 | 改了 smali 破坏逻辑 / 校验没绕过 | 先只改一处最小改动验证链路通不通 |

---

## 参考文件索引

**按需读，不要一次全读。** 每个文件都已按单次可读上限切好。

| 文件 | 什么时候读 |
|---|---|
| `apk-static.md` | APK 结构、jadx-gui 用法、apktool 工作流、smali 寄存器与操作码、IPA 分析、Frida CLI 完整参考 |
| `binary-re.md` | ELF 分析、反汇编、IDA/Ghidra 操作、二进制补丁 |
| `platform-mobile.md` | Android 工具链全景、Frida Hook Java/Native、Unity IL2CPP、iOS、UE4 手游、腾讯 MTP/ACE 分析 |
| `decompilation.md` | 反编译策略、混淆识别、dex 结构 |
| `hook-techniques.md` | Frida/Xposed/LSPosed 全套 Hook 技巧、inline hook、PLT hook |
| `memory-edit.md` | 内存扫描、指针链、值搜索、libmem 用法 |
| `anti-cheat.md` | 反作弊检测手段与绕过（**遇到检测先查这里**） |
| `game-engines.md` | Unity/UE/Cocos/Flutter 各引擎的逆向入口 |
| `protocol-capture.md` | 抓包、pinning 绕过、协议格式还原、加解密定位 |
| `automation.md` | 自动化脚本、UI 自动操作、批量测试 |
| `advanced-techniques.md` | 高级技巧合集 |
| `cheat-android.md` | Android/iOS 手游专项（提取自大 cheatsheet） |
| `cheat-memory.md` | 内存分析专项（同上） |
| `cheat-engines.md` | 引擎逆向专项（同上） |

---

## 三条硬规则

1. **先探活再动手**。`command -v` 确认工具存在，不存在就告诉用户去设置里装，不要静默下载几百 MB。
2. **改完必须验证闭环**。改了 smali 就回编译 → 签名 → 装回 → 看能不能跑。只改不验等于没改。
3. **不确定就说不知道**。加固壳、私有协议、企业级反作弊这三类，工具不到位时直接说「这一步当前做不到，原因是 X」，不要编一条看起来合理的路径。
