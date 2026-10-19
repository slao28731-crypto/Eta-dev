#!/usr/bin/env python3
"""校验 RE_LAB / ANGR 两个 profile：分步结构 + shell 语法 + 资源名 + 依赖。

为什么要专门验：
1. 每一步的 command 都是 Kotlin 字符串拼出来的，一个引号错了整步就废
2. `LinuxPackageProfileInstaller` 会在 chroot 的 `/bin/sh`（dash）里跑这些命令，
   所以必须 dash 也能解析，不能只用 bash 语法
3. `R.string` 名字写错在编译期才报，这里先对一遍
4. radare2 只能走官方 deb —— Debian trixie 源里没有这个包，
   一旦有人又把它写回 `packages`，真机上会直接 `has no installation candidate`
"""

import re
import subprocess
import sys
from pathlib import Path

KT = Path("app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt")
RES_EN = Path("app/src/main/res/values/strings.xml")
RES_ZH = Path("app/src/main/res/values-b+zh+Hans/strings.xml")
UI = Path("app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt")


def unescape_kotlin(raw: str) -> str:
    return raw.replace('\\"', '"').replace("\\$", "$").replace("\\\\", "\\")


def extract_steps(const_name: str) -> list:
    """从 `private val X = listOf(...)` 里抽出每条 LinuxPackageStep 的 command。"""
    src = KT.read_text(encoding="utf-8")
    marker = f"val {const_name} = listOf("
    if marker not in src:
        return []
    start = src.index(marker)
    i = src.index("(", start)
    depth = 0
    end = len(src)
    in_str = False
    while i < len(src):
        ch = src[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
        i += 1
    block = src[start:end]

    commands = []
    for m in re.finditer(r"LinuxPackageStep\(", block):
        i = m.end()
        depth = 1
        in_str = False
        buf_start = i
        end = len(block)
        while i < len(block):
            ch = block[i]
            if in_str:
                if ch == "\\":
                    i += 2
                    continue
                if ch == '"':
                    in_str = False
                i += 1
                continue
            if ch == '"':
                in_str = True
                i += 1
                continue
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    end = i
                    break
            i += 1
        args = block[buf_start:end]

        cmd = None
        m2 = re.search(r"command\s*=\s*", args)
        if m2:
            cmd = collect_concat(args[m2.end():])
        else:
            parts = split_top_level(args)
            if len(parts) >= 2:
                cmd = collect_concat(parts[1])
        if cmd is not None:
            commands.append(cmd)
    return commands


def collect_concat(text: str) -> str:
    """把 `"a" + "b" + ...` 或裸字符串拼成一个完整命令。"""
    text = text.strip()
    if text.startswith("renderCliLinksCommand("):
        # 这个函数在 Kotlin 里生成软链命令，这里用等价 shell 片段代替做语法检查
        return (
            "eta_bin=$(python3 -c \"import sysconfig; print(sysconfig.get_path('scripts'))\")"
            " || exit 78; [ -d \"$eta_bin\" ] || exit 78; true"
        )
    pieces = re.findall(r'"((?:\\.|[^"\\])*)"', text)
    return unescape_kotlin("".join(pieces))


def split_top_level(args: str) -> list:
    parts = []
    depth = 0
    in_str = False
    buf = []
    i = 0
    while i < len(args):
        ch = args[i]
        if in_str:
            buf.append(ch)
            if ch == "\\":
                if i + 1 < len(args):
                    buf.append(args[i + 1])
                    i += 2
                    continue
            elif ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            buf.append(ch)
        elif ch in "([{":
            depth += 1
            buf.append(ch)
        elif ch in ")]}":
            depth -= 1
            buf.append(ch)
        elif ch == "," and depth == 0:
            parts.append("".join(buf).strip())
            buf = []
        else:
            buf.append(ch)
        i += 1
    if "".join(buf).strip():
        parts.append("".join(buf).strip())
    return parts


def check_syntax(label: str, script: str) -> list:
    failures = []
    path = Path(f"/tmp/{label}.sh")
    path.write_text(script + "\n", encoding="utf-8")
    for shell in ("bash", "dash", "sh"):
        try:
            proc = subprocess.run([shell, "-n", str(path)], capture_output=True, text=True)
        except FileNotFoundError:
            continue
        status = "ok" if proc.returncode == 0 else "FAIL"
        print(f"  [{status}] {shell} -n  {label}")
        if proc.returncode != 0:
            failures.append(f"{label} {shell}: {proc.stderr.strip()}")
    return failures


def check_resources() -> list:
    failures = []
    ui = UI.read_text(encoding="utf-8")
    used = set(re.findall(r"R\.string\.([a-z0-9_]+)", ui))
    wanted = {
        n for n in used
        if n.startswith(("linux_re_lab", "linux_angr", "linux_profile_stage_"))
    }

    for res_file in (RES_EN, RES_ZH):
        defined = set(re.findall(r'<string name="([a-z0-9_]+)"', res_file.read_text(encoding="utf-8")))
        missing = sorted(wanted - defined)
        if missing:
            for name in missing:
                failures.append(f"{res_file.name} 缺少 {name}")
        else:
            print(f"  [ok] {res_file.name}: {len(wanted)} 条全部存在")
    return failures


def check_profiles() -> list:
    failures = []
    src = KT.read_text(encoding="utf-8")

    # ALL 列表现在跨多行且含 14 个 profile，逐字匹配已不适用。
    # 改为检查：每个我们关心的 profile 名字都出现在 ALL 的括号内。
    import re as _re
    all_match = _re.search(r"val ALL = listOf\((.*?)\n    \)", src, _re.S)
    all_block = all_match.group(1) if all_match else ""
    required = ("RE_LAB", "ANGR", "ANDROID_SDK", "PENTEST_RECON", "PENTEST_WIFI")
    missing = [n for n in required if n not in all_block]
    if not all_match:
        failures.append("找不到 val ALL = listOf(...) 定义")
    elif missing:
        failures.append(f"ALL 缺少：{', '.join(missing)}")
    else:
        print("  [ok] ALL 含 RE_LAB / ANGR / ANDROID_SDK / PENTEST_*")

    for name in ("RE_LAB", "ANGR"):
        block_start = src.index(f"val {name} = LinuxPackageProfile(")
        block_end = src.index("\n    )", block_start)
        block = src[block_start:block_end]
        if "dependsOn = PYTHON" not in block:
            failures.append(f"{name} 没有 dependsOn = PYTHON")
        else:
            print(f"  [ok] {name} dependsOn = PYTHON")
        if "id = " not in block:
            failures.append(f"{name} 缺少 id")
    return failures


def check_radare2_source() -> list:
    """Debian 的 packages 里绝不能出现 radare2。"""
    failures = []
    src = KT.read_text(encoding="utf-8")
    for m in re.finditer(r"packages\s*=\s*listOf\(([^)]*)\)", src):
        items = m.group(1)
        if "radare2" not in items:
            continue
        ctx = src[max(0, m.start() - 260):m.start()]
        if "DEBIAN" in ctx:
            failures.append(
                "Debian 的 packages 里出现 radare2（trixie 源没有这个包），必须走官方 deb"
            )
    if not failures:
        print("  [ok] Debian 侧没有把 radare2 写进 packages")
    return failures


def main() -> int:
    print("[1/5] 结构检查")
    failures = check_profiles()

    print("[2/5] radare2 来源检查")
    failures += check_radare2_source()

    print("[3/5] 分步命令语法校验（dash 也跑得通）")
    total_steps = 0
    for const_name in ("RE_LAB_PIP_STEPS", "RE_LAB_DEBIAN_STEPS", "ANGR_STEPS"):
        commands = extract_steps(const_name)
        if not commands:
            failures.append(f"没能从 {const_name} 抽出任何命令")
            continue
        print(f"  {const_name}: {len(commands)} 步")
        total_steps += len(commands)
        for i, cmd in enumerate(commands, 1):
            failures += check_syntax(f"{const_name}_{i}", cmd)

    print("[4/5] 资源名对照")
    failures += check_resources()

    print("[5/5] 声明顺序")
    src_text = KT.read_text(encoding="utf-8")
    idx_all = src_text.index("val ALL = listOf(")
    for name in ("RE_LAB", "ANGR", "PYTHON"):
        idx_def = src_text.index(f"val {name} = LinuxPackageProfile(")
        if idx_def > idx_all:
            failures.append(f"{name} 的声明在 ALL 之后，会导致初始化顺序错误")
        else:
            print(f"  [ok] {name} 声明在 ALL 之前")

    if failures:
        print("\n[FAIL]")
        for item in failures:
            print(" -", item)
        return 1
    print(f"\n[PASS] 结构、radare2 来源、{total_steps} 步语法、资源名、声明顺序全部正确")
    return 0


if __name__ == "__main__":
    sys.exit(main())