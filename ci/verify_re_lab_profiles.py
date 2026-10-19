#!/usr/bin/env python3
"""校验新加的两个 profile 脚本：语法 + 结构 + 依赖关系。

为什么要专门验：
1. 脚本是 Kotlin 字符串拼出来的，一个引号错了整条就废
2. `LinuxPackageProfileInstaller` 会用 `set -e` 包住 setupScript，
   所以函数定义 + `||` 短路必须能在 dash 下正常工作
3. R.string 名字写错在编译期才报，这里先对一遍
"""

import re
import subprocess
import sys
from pathlib import Path

KT = Path("app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt")
RES_EN = Path("app/src/main/res/values/strings.xml")
RES_ZH = Path("app/src/main/res/values-b+zh+Hans/strings.xml")
UI = Path("app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt")


def extract_const(name: str) -> str:
    src = KT.read_text(encoding="utf-8")
    start = src.index(f"private const val {name} =")
    end = src.index("\n\n", start)
    block = src[start:end]

    pieces = []
    for line in block.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("private const val"):
            continue
        if not stripped.startswith('"'):
            continue
        for raw in re.findall(r'"((?:\\.|[^"\\])*)"', stripped):
            pieces.append(
                raw.replace('\\"', '"').replace("\\$", "$").replace("\\\\", "\\")
            )
    return "".join(pieces)


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
    """UI 里引用的每个 R.string 都必须在两种语言里都存在。"""
    failures = []
    ui = UI.read_text(encoding="utf-8")
    used = set(re.findall(r"R\.string\.([a-z0-9_]+)", ui))
    wanted = {n for n in used if n.startswith("linux_re_lab") or n.startswith("linux_angr")}

    for res_file in (RES_EN, RES_ZH):
        defined = set(re.findall(r'<string name="([a-z0-9_]+)"', res_file.read_text(encoding="utf-8")))
        for name in sorted(wanted):
            if name not in defined:
                failures.append(f"{res_file.name} 缺少 {name}")
            else:
                print(f"  [ok] {res_file.name}: {name}")
    return failures


def check_profiles() -> list:
    failures = []
    src = KT.read_text(encoding="utf-8")

    # ALL 必须把新 profile 列进去，否则 UI 初始化时 getValue() 会抛
    if "listOf(PYTHON, NODE, SSH, KIMI, RE_LAB, ANGR)" not in src:
        failures.append("ALL 没有列出 RE_LAB / ANGR")

    # 两个 profile 都必须依赖 PYTHON（pip 是它们的前提）
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


def main() -> int:
    print("[1/4] 结构检查")
    failures = check_profiles()

    print("[2/4] 脚本语法校验")
    failures += check_syntax("re_lab",
                             extract_const("RE_LAB_PIP_SCRIPT"))
    failures += check_syntax("angr",
                             extract_const("ANGR_PIP_SCRIPT"))

    print("[3/4] 资源名对照")
    failures += check_resources()

    print("[4/4] 依赖顺序")
    # RE_LAB / ANGR 在 ALL 里必须排在 PYTHON 之后（UI 逐项构造 installer）
    src = KT.read_text(encoding="utf-8")
    order = src[src.index("val ALL = listOf("):].split(")")[0]
    idx_py = order.index("PYTHON")
    for name in ("RE_LAB", "ANGR"):
        if order.index(name) < idx_py:
            failures.append(f"{name} 排在 PYTHON 之前")
        else:
            print(f"  [ok] {name} 在 PYTHON 之后")

    if failures:
        print("\n[FAIL]")
        for item in failures:
            print(" -", item)
        return 1
    print("\n[PASS] 两个 profile 结构、语法、资源名、顺序全部正确")
    return 0


if __name__ == "__main__":
    sys.exit(main())