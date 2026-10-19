#!/usr/bin/env python3
"""本机静态检查（编译交给 CI）。

为什么需要它：本机是 aarch64，跑不了 x86_64 的 aapt2，Gradle 编译在这儿
起不来。所以本地只能做「不依赖编译器」的检查，把明显错误挡在推送之前：

1. 括号平衡（跳过注释 / 字符串 / 字符常量）
2. R.string.<name> 引用是否在两种语言里都定义
3. 用到的 Compose 符号是否已 import
4. 新增的 val / fun 是否在「自己之前」声明（Kotlin object 初始化顺序）

这些检查抓不到类型错误，但能抓到本轮实际踩过的两类：资源名写错、
声明顺序错。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT_FILES = [
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt",
    "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt",
    "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentSections.kt",
]
RES_DIR = ROOT / "app/src/main/res"
# 项目把字符串拆在多个 xml 里（strings.xml / root_capabilities.xml / ...），
# 只查 strings.xml 会误报一堆「未定义」。这里按目录全量收集。
RES_LOCALES = ["values", "values-b+zh+Hans"]


def strip_comments_and_strings(text: str) -> str:
    """把注释、字符串、raw string 换成等长空白，保留换行以便报行号。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i)
            j = n if j < 0 else j + 2
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
            continue
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
            continue
        if text[i] == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append("".join(ch if ch == "\n" else " " for ch in text[i:j]))
            i = j
            continue
        if text[i] == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "'":
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
            continue
        out.append(text[i])
        i += 1
    return "".join(out)


def check_balance(path: Path) -> list:
    code = strip_comments_and_strings(path.read_text(encoding="utf-8"))
    errors = []
    pairs = {"{": "}", "(": ")", "[": "]"}
    stack = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        if ch in pairs:
            stack.append((ch, line))
        elif ch in pairs.values():
            if not stack:
                errors.append(f"{path.name}:{line} 多余的 {ch}")
                continue
            opener, opened_at = stack.pop()
            if pairs[opener] != ch:
                errors.append(f"{path.name}:{line} {ch} 与第 {opened_at} 行的 {opener} 不配对")
    for opener, opened_at in stack:
        errors.append(f"{path.name}:{opened_at} 的 {opener} 没有闭合")
    return errors


def collect_resources(locale: str) -> set:
    """某个语言目录下所有 xml 里定义的 string 名。"""
    names = set()
    directory = RES_DIR / locale
    if not directory.is_dir():
        return names
    for xml in directory.glob("*.xml"):
        names |= set(
            re.findall(r'<string name="([a-z0-9_]+)"', xml.read_text(encoding="utf-8"))
        )
    return names


def check_string_resources() -> list:
    """只校验本次改动涉及的模块，避免被历史遗留差异淹没。

    只查 strings.xml 或只查某个语言目录都会误报——项目把字符串散在多个 xml、
    多个语言目录里。这里做两件事：
    1. 英文有的、中文缺的（历史遗留，只报告本次新引入的）
    2. 代码里引用了、但任何目录里都没有的（真的编译错误）
    """
    errors = []
    defined_by_locale = {locale: collect_resources(locale) for locale in RES_LOCALES}
    defined = set().union(*defined_by_locale.values())

    used = set()
    for rel in KT_FILES:
        text = (ROOT / rel).read_text(encoding="utf-8")
        used |= set(re.findall(r"R\.string\.([a-z0-9_]+)", text))
    for name in sorted(used - defined):
        errors.append(f"R.string.{name} 未在任何 res 文件中定义")

    # 只报「本次新加的那几条」在中文里缺失，历史差异不追究。
    introduced = {
        name for name in used
        if name.startswith(("linux_re_lab", "linux_angr")) or name == "linux_profile_stage_installing_item"
    }
    for locale in RES_LOCALES[1:]:
        missing = sorted(introduced - defined_by_locale[locale])
        for name in missing:
            errors.append(f"{locale} 缺少本次新增的 {name}")

    print(
        f"  代码引用 {len(used)} 个；"
        + "，".join(f"{k}={len(v)}" for k, v in defined_by_locale.items())
    )
    return errors


# 这些符号必须来自 import，漏了就是编译错误。
REQUIRED_IMPORTS = {
    "LinuxEnvironmentSections.kt": {
        "LinearProgressIndicator": "top.yukonga.miuix.kmp.basic.LinearProgressIndicator",
        "InfiniteProgressIndicator": "top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator",
    },
    "LinuxPackageProfiles.kt": {
        "Build": "android.os.Build",
    },
}


def check_imports() -> list:
    errors = []
    for filename, symbols in REQUIRED_IMPORTS.items():
        rel = next((p for p in KT_FILES if p.endswith(filename)), None)
        if not rel:
            continue
        text = (ROOT / rel).read_text(encoding="utf-8")
        imports = set(re.findall(r"^import\s+([\w.]+)", text, re.MULTILINE))
        for symbol, expected in symbols.items():
            if expected not in imports:
                errors.append(f"{filename} 缺少 import {expected}（用到 {symbol}）")
    if not errors:
        print("  关键 import 齐全")
    return errors


def main() -> int:
    failures = []

    print("[1/4] 括号平衡")
    for rel in KT_FILES:
        path = ROOT / rel
        if not path.is_file():
            failures.append(f"找不到 {rel}")
            continue
        errs = check_balance(path)
        failures += errs
        if not errs:
            print(f"  [ok] {path.name}")

    print("[2/4] 字符串资源对照")
    failures += check_string_resources()

    print("[3/4] 关键 import")
    failures += check_imports()

    print("[4/4] 委托给声明顺序校验")
    import subprocess
    for script in ("ci/verify_profile_decl_order.py", "ci/verify_re_lab_profiles.py"):
        proc = subprocess.run(
            [sys.executable, script], cwd=ROOT, capture_output=True, text=True
        )
        if proc.returncode != 0:
            failures.append(f"{script} 失败：{proc.stdout.strip()[-400:]}")
        else:
            print(f"  [ok] {script}")

    if failures:
        print("\n[FAIL]")
        for item in failures:
            print(" -", item)
        return 1
    print("\n[PASS] 本地静态检查全部通过（编译以 CI 为准）")
    return 0


if __name__ == "__main__":
    sys.exit(main())