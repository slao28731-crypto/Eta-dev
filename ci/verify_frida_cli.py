#!/usr/bin/env python3
"""Frida 安装命令的静态 + 分支校验，一条命令跑完。

用法（仓库根目录）：
    python3 ci/verify_frida_cli.py

它做三件事：
1. 从 `FridaInstaller.kt` 反解出真实的 shell 串（Kotlin 几十段字符串拼接，肉眼核对不可靠）
2. `bash -n` / `dash -n` / `sh -n` 三份语法校验
3. 在沙箱里跑两个分支场景，确认「老用户升级会补装 dexdump」「已装全不会重复下载」

为什么放进 ci/：这条命令一改就可能整段丢失或者少一个分号，
只有把它当代码来测，才不会出现「文档说有这个能力、实际命令是空」的脱节。
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

KT = Path("app/src/main/kotlin/io/github/mangi/eta/agent/terminal/FridaInstaller.kt")

# Kotlin 里 `\$` 是字面美元符，用哨兵避开模板替换，最后再还原
DOLLAR = "\x00D\x00"
LITERAL = re.compile(r'"((?:\\.|[^"\\])*)"')
TEMPLATE = re.compile(r"\$\{([^}]+)\}")

CONSTANTS = {
    "FridaPaths.VERSION": "17.24.2",
    "FridaPaths.TOOLS_VERSION": "14.11.0",
    "FridaPaths.DEXDUMP_VERSION": "2.0.1",
}

FAKE_PY = """#!/bin/sh
case "$*" in
  *"import frida_dexdump"*) exit "$DEXDUMP_IMPORT_RC" ;;
  *"import frida,sys"*) exit 0 ;;
  *"print(frida.__version__)"*) echo 17.24.2; exit 0 ;;
  *"-m pip install"*) echo "PIP_CALLED" >&2; exit 0 ;;
  *) exit 0 ;;
esac
"""

STUB = "#!/bin/sh\nexit 0\n"


def unescape(raw: str) -> str:
    out, i = [], 0
    while i < len(raw):
        ch = raw[i]
        if ch == "\\" and i + 1 < len(raw):
            nxt = raw[i + 1]
            mapping = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", '"': '"', "$": DOLLAR}
            out.append(mapping.get(nxt, nxt))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def extract_command() -> str:
    src = KT.read_text(encoding="utf-8")
    start = src.index("internal fun installCliCommand(): String =")
    tail = src[start:]
    end = tail.index("\n    /**", 1)
    body = tail[:end]

    pieces = []
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("internal fun"):
            continue
        if not stripped.startswith('"'):
            continue
        for raw in LITERAL.findall(stripped):
            pieces.append(unescape(raw))

    command = "".join(pieces)

    def sub(match: "re.Match[str]") -> str:
        expr = match.group(1).strip()
        if expr not in CONSTANTS:
            raise SystemExit(f"未登记的 Kotlin 模板常量: {expr}")
        return CONSTANTS[expr]

    command = TEMPLATE.sub(sub, command)
    if "${" in command:
        raise SystemExit("仍有未解析的 Kotlin 模板占位符")
    return command.replace(DOLLAR, "$")


def check_syntax(command: str) -> list:
    failures = []
    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False, encoding="utf-8") as handle:
        handle.write(command + "\n")
        path = handle.name
    try:
        for shell in ("bash", "dash", "sh"):
            if shutil.which(shell) is None:
                print(f"  [skip] {shell} 不在本机")
                continue
            proc = subprocess.run([shell, "-n", path], capture_output=True, text=True)
            print(f"  [{'ok' if proc.returncode == 0 else 'fail'}] {shell} -n rc={proc.returncode}")
            if proc.returncode != 0:
                failures.append(f"{shell} -n 语法错误: {proc.stderr.strip()}")
    finally:
        os.unlink(path)
    return failures


def rewrite(command: str, sandbox: Path) -> str:
    # 原命令第一句就是 `PY=; for c in ...`，预设会被清空，所以必须整段替换
    head = re.compile(r"PY=; for c in .*?; do ")
    command, count = head.subn(f"PY={sandbox}/bin/python3; for c in /nonexistent; do ", command, count=1)
    if count != 1:
        raise SystemExit("候选解释器段没匹配上，FridaInstaller 结构可能变了")
    command = command.replace("/usr/local/bin", f"{sandbox}/localbin")
    command = command.replace("/usr/bin/", f"{sandbox}/nohost/")
    return command.replace(f"{sandbox}/nohost/python3", f"{sandbox}/bin/python3")


def run_scenario(label: str, sandbox: Path, command: str, dexdump_present: bool, import_rc: int) -> str:
    for sub in ("bin", "localbin"):
        target = sandbox / sub
        if target.exists():
            shutil.rmtree(target)
        target.mkdir(parents=True)

    fake = sandbox / "bin" / "python3"
    fake.write_text(FAKE_PY, encoding="utf-8")
    fake.chmod(0o755)

    # 必须与解释器同目录：命令的候选清单第一项就是 "${PY%/*}"
    for name in ("frida-ps", "frida-dexdump"):
        tool = sandbox / "bin" / name
        tool.write_text(STUB, encoding="utf-8")
        tool.chmod(0o755)

    script = sandbox / "run.sh"
    script.write_text(rewrite(command, sandbox) + "\n", encoding="utf-8")

    env = dict(os.environ)
    env["DEXDUMP_IMPORT_RC"] = str(import_rc)
    # mkdir/ln 仍需系统 PATH，否则软链步骤假失败
    env["PATH"] = f"{sandbox}/bin:/usr/bin:/bin"

    proc = subprocess.run(["/bin/sh", str(script)], capture_output=True, text=True, env=env)
    out = (proc.stdout + proc.stderr).strip()
    print(f"===== {label} =====")
    print("  " + out.replace("\n", " | "))
    print(f"  rc={proc.returncode}")
    return out


def check_branches(command: str) -> list:
    failures = []
    sandbox = Path(tempfile.mkdtemp(prefix="frida-verify-"))
    try:
        upgrade = run_scenario("老用户：frida 在、dexdump 缺", sandbox, command, False, 1)
        complete = run_scenario("已装全：两者都在", sandbox, command, True, 0)
    finally:
        shutil.rmtree(sandbox, ignore_errors=True)

    if "PIP_CALLED" not in upgrade:
        failures.append("升级场景没走 pip：老用户永远补不上 dexdump")
    if "FRIDA_CLI_OK" not in upgrade:
        failures.append("升级场景没收敛到 FRIDA_CLI_OK")
    if "PIP_CALLED" in complete:
        failures.append("已装全仍然跑 pip：每次都多下一次 30MB")
    if "FRIDA_CLI_ALREADY=17.24.2" not in complete:
        failures.append("已装全没走 FRIDA_CLI_ALREADY 分支")
    if "FRIDA_CLI_OK" not in complete:
        failures.append("已装全场景没收敛到 FRIDA_CLI_OK")
    return failures


def main() -> int:
    if not KT.is_file():
        raise SystemExit(f"找不到 {KT}，请在仓库根目录运行")

    print("[1/3] 从 Kotlin 反解 shell 命令")
    command = extract_command()
    print(f"  length={len(command)}")
    for keyword in ("frida-dexdump", "FRIDA_DEXDUMP_OK", "FRIDA_CLI_OK"):
        if keyword not in command:
            print(f"[FAIL] 命令里缺少关键串: {keyword}")
            return 1

    print("[2/3] shell 语法校验")
    failures = check_syntax(command)

    print("[3/3] 分支行为校验")
    failures += check_branches(command)

    if failures:
        print("\n[FAIL]")
        for item in failures:
            print(" -", item)
        return 1
    print("\n[PASS] 命令完整、语法正确、两个分支都对")
    return 0


if __name__ == "__main__":
    sys.exit(main())