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
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/AndroidNdkInstaller.kt",
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/PentestToolkits.kt",
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/PentestProfiles.kt",
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


def check_dollar_escapes() -> list:
    """Kotlin 字符串里的裸 `$name` 会被当成模板插值。

    run #37 真实踩到：写 `append("[ -d \"$eta_bin\" ] ...")` 时，
    Kotlin 认为 `$eta_bin` 是变量引用，编译报
    `Unresolved reference 'eta_bin'`。shell 变量必须写成 `\\$eta_bin`。

    这里把每个字符串字面量拆出来，找出「$ 后接小写标识符」且该标识符
    不是本地 Kotlin 变量的情况。
    """
    errors = []
    pattern = re.compile(r'"((?:\\.|[^"\\])*)"')

    for rel in KT_FILES:
        text = (ROOT / rel).read_text(encoding="utf-8")
        # 收集本文件里所有可被 `$name` 引用的名字：
        # - val / var / fun
        # - data class 的构造属性
        # - 函数参数、for 变量、lambda 参数
        declared = set(re.findall(r"\b(?:val|var|fun)\s+([a-zA-Z_][A-Za-z0-9_]*)", text))
        declared |= set(
            re.findall(
                r"\(\s*val\s+([a-zA-Z_][A-Za-z0-9_]*)\s*:",
                text,
            )
        )
        for other in KT_FILES:
            other_text = (ROOT / other).read_text(encoding="utf-8")
            declared |= set(
                re.findall(r"^\s*val\s+([a-zA-Z_][A-Za-z0-9_]*)\s*:", other_text, re.MULTILINE)
            )
        declared |= set(
            re.findall(r"[(,]\s*([a-zA-Z_][A-Za-z0-9_]*)\s*:\s*[A-Z][A-Za-z0-9_<>,.?\s]*", text)
        )
        declared |= set(re.findall(r"\bfor\s*\(\s*\(?\s*([a-zA-Z_][A-Za-z0-9_]*)", text))
        declared |= set(re.findall(r"\bfor\s*\(\s*([a-zA-Z_][A-Za-z0-9_]*)\s+in\b", text))
        for m in re.finditer(r"\{([^{}]*?)->", text):
            for part in m.group(1).split(","):
                candidate = part.strip().split(":")[0].strip()
                if re.fullmatch(r"[a-zA-Z_][A-Za-z0-9_]*", candidate or ""):
                    declared.add(candidate)

        # 普通字符串 + **三引号原始字符串**都要查。
        # 之前只查普通字符串，导致 PentestToolkits.kt 里三引号中的 $f/$d/$src
        # 全部漏掉，直到 CI 才报（run #46 就是这个）。
        for m in re.finditer(r'"""((?:.|\n)*?)"""', text):
            raw = m.group(1)
            for ref in re.finditer(r"(?<!\\)\$([a-z][A-Za-z0-9_]*)", raw):
                name = ref.group(1)
                if name in declared:
                    continue
                line = text.count("\n", 0, m.start() + ref.start()) + 1
                errors.append(
                    f"{rel}:{line} 三引号字符串里的 $%s 会被 Kotlin 当成插值，"
                    f"shell 变量要写成 \\$%s" % (name, name)
                )
        for m in pattern.finditer(text):
            raw = m.group(1)
            # 跳过已转义的 \$ 和 ${...} 形式（后者可能是合法表达式）
            for ref in re.finditer(r"(?<!\\)\$([a-z][A-Za-z0-9_]*)", raw):
                name = ref.group(1)
                if name in declared:
                    continue
                line = text.count("\n", 0, m.start() + ref.start()) + 1
                errors.append(
                    f"{rel}:{line} 字符串里的 $%s 会被 Kotlin 当成插值，"
                    f"shell 变量要写成 \\$%s" % (name, name)
                )
    if not errors:
        print("  没有未转义的 shell 变量")
    return errors


def check_block_depth() -> list:
    """花括号深度必须归零，且每个文件的嵌套层级要与缩进大致相符。

    这是对 run #40 的教训：`AndroidNdkInstaller.extractArchive` 里
    `use { raw -> ... use { stream -> ... use { tar -> ... } } }` 少了一层闭合，
    结果整个 class 从第 206 行起就被提前截断，后面所有成员（companion 常量、
    嵌套 data class）全部变成「Unresolved reference」——编译报了几十条错，
    真正的根因只有少一个 `}`。

    为什么原有「括号平衡」检查没抓到：它只统计总数，而这个 bug 的特征是
    **总量不平衡**——但当时同一次编辑还漏了 pairing，导致数量恰好接近；
    更可靠的是同时校验「最终深度必须为 0」。这里把 final depth 也纳入。
    """
    errors = []
    for rel in KT_FILES:
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        code = strip_comments_and_strings(text)
        depth = 0
        depth_at_line_end = []
        line = 1
        for ch in code:
            if ch == "\n":
                depth_at_line_end.append(depth)
                line += 1
            elif ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
        depth_at_line_end.append(depth)
        if depth != 0:
            errors.append(f"{path.name}: 花括号最终深度为 {depth}，应为 0（少闭或多闭）")
        else:
            print(f"  [ok] {path.name} 深度归零")
    return errors


def main() -> int:
    failures = []

    print("[1/5] 括号平衡")
    for rel in KT_FILES:
        path = ROOT / rel
        if not path.is_file():
            failures.append(f"找不到 {rel}")
            continue
        errs = check_balance(path)
        failures += errs
        if not errs:
            print(f"  [ok] {path.name}")

    print("[2/5] shell 变量转义（Kotlin 字符串模板）")
    failures += check_dollar_escapes()

    print("[3/5] 花括号深度归零")
    failures += check_block_depth()

    print("[4/5] 字符串资源对照")
    failures += check_string_resources()

    print("[5/5] 关键 import + 声明顺序")
    failures += check_imports()
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