#!/usr/bin/env python3
"""校验 LinuxPackageProfiles.kt 的声明顺序与结构。

存在的原因：Kotlin 的 `object` 属性按**声明顺序**初始化。
`val A = ... B ...` 如果 B 在 A 之后才声明，编译直接报
`Variable 'B' must be initialized`。这个错本地看不出来（aapt2 是 x86_64，
aarch64 上跑不了 Gradle），只能靠 CI，一次往返十分钟，已经踩过两次
（P081：ALL 写在 RE_LAB 之前；本轮的 RE_LAB_DEBIAN_STEPS 引用未声明的
RE_LAB_PIP_STEPS）。

本脚本只做静态文本分析，不依赖 Kotlin 编译器。
"""

import re
import sys
from pathlib import Path

TARGET = Path(
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt"
)

# 只认「顶层成员」：object 体内的成员恰好缩进 4 个空格。
# 函数内部的局部变量缩进更深（8+），不能当成员，否则会被误判成顺序错误
# （实际踩过：reLabArtifacts 里的局部 `val official` 被当成成员）。
MEMBER_DECL = re.compile(
    r"^    (?:private\s+)?(?:val|fun)\s+([A-Za-z_][A-Za-z0-9_]*)",
    re.MULTILINE,
)

# 只检查 object LinuxPackageProfiles 内部；外面的 class 不受此限。
OBJECT_START = re.compile(r"^internal object LinuxPackageProfiles\s*\{", re.MULTILINE)
# 完整的 Kotlin 标识符；用整词匹配避免 `reLabArtifacts` 里的 `LAB` 被当成 `RE_LAB`。
IDENT = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\b")


def strip_noise(chunk: str) -> str:
    """去掉注释与字符串字面量，只留代码本体。

    必要性：`failureHint = "radare2 安装失败..."` 这类文案里会出现大写词，
    不加处理会当成成员引用，产生大量假阳性。
    """
    chunk = re.sub(r'"""(?:.|\n)*?"""', '""', chunk)
    chunk = re.sub(r"/\*(?:.|\n)*?\*/", " ", chunk)
    chunk = re.sub(r"//[^\n]*", " ", chunk)
    chunk = re.sub(r'"(?:\\.|[^"\\])*"', '""', chunk)
    chunk = re.sub(r"'(?:\\.|[^'\\])*'", "''", chunk)
    return chunk


def cut_function_body(chunk: str) -> str:
    """只保留属性声明的「值部分」或函数的「签名部分」，去掉函数体 / lambda 体。

    为什么需要：`private fun foo()` 的函数体里会引用局部变量、参数、receiver 成员
    （例如 `val marker = File(rootfs, ...)` 里的 `rootfs`）。这些不是 object 成员，
    参与顺序判定只会产生假阳性。
    """
    # 函数：从 `fun name(...)` 到第一个 `{` 之前都算签名
    fun_match = re.search(r"\b(?:private\s+|internal\s+)?fun\b", chunk)
    if fun_match:
        brace = chunk.find("{", fun_match.start())
        return chunk[:brace] if brace >= 0 else chunk
    return chunk

def main() -> int:
    if not TARGET.is_file():
        print(f"FAIL: 找不到 {TARGET}")
        return 1
    text = TARGET.read_text(encoding="utf-8")

    match = OBJECT_START.search(text)
    if not match:
        print("FAIL: 找不到 internal object LinuxPackageProfiles {")
        return 1

    # 取 object 体：从 { 开始做花括号配对（文件内没有嵌套字符串大括号的陷阱，
    # 因为 raw string 里的 ${...} 在本文件中只出现在 append 调用里，
    # 这里只做「声明位置」统计，粗配对足够）。
    start = match.end()
    depth = 1
    i = start
    body_end = len(text)
    in_raw = False
    in_block = False
    in_line = False
    in_str = False
    in_char = False
    while i < len(text):
        ch = text[i]
        if in_line:
            if ch == "\n":
                in_line = False
            i += 1
            continue
        if in_block:
            if text.startswith("*/", i):
                in_block = False
                i += 2
                continue
            i += 1
            continue
        if in_raw:
            if text.startswith('"""', i):
                in_raw = False
                i += 3
                continue
            i += 1
            continue
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if in_char:
            if ch == "\\":
                i += 2
                continue
            if ch == "'":
                in_char = False
            i += 1
            continue
        if text.startswith("//", i):
            in_line = True
            i += 2
            continue
        if text.startswith("/*", i):
            in_block = True
            i += 2
            continue
        if text.startswith('"""', i):
            in_raw = True
            i += 3
            continue
        if ch == '"':
            in_str = True
            i += 1
            continue
        if ch == "'":
            in_char = True
            i += 1
            continue
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                body_end = i
                break
        i += 1

    body = text[start:body_end]

    decls = {}
    for m in MEMBER_DECL.finditer(body):
        name = m.group(1)
        if name not in decls:
            decls[name] = m.start()

    if not decls:
        print("FAIL: object 里没解析到任何声明")
        return 1

    errors = []

    # 检查 1：ALL 必须声明在最后（在所有 profile 之后）。
    if "ALL" not in decls:
        errors.append("找不到 val ALL")
    else:
        all_pos = decls["ALL"]
        for name, pos in decls.items():
            if name == "ALL":
                continue
            if not name.startswith(("PYTHON", "NODE", "SSH", "KIMI", "RE_LAB", "ANGR")):
                continue
            if pos > all_pos:
                errors.append(f"ALL 声明在第 {all_pos} 字符，而 {name} 在 {pos}，应把 ALL 放到最后")

    # 检查 2：每个**顶层**声明引用的同 object 成员，必须在它之前声明。
    # 只扫「真的引用」：用整词匹配，且在比对前先把声明自身的名字、注释和字符串剔除，
    # 否则 `reLabArtifacts` 里的 `LAB` 片段、注释里的 `RE_LAB` 都会变成假阳性。
    for name, pos in sorted(decls.items(), key=lambda kv: kv[1]):
        next_positions = [p for n, p in decls.items() if p > pos]
        end = min(next_positions) if next_positions else len(body)
        chunk = body[pos:end]
        chunk = strip_noise(chunk)
        # 这个声明可能是函数：函数体内的局部变量和参数不算「成员引用」。
        # 把签名之后的函数体整体挖掉，只留签名本身来做引用扫描。
        signature_only = cut_function_body(chunk)
        for ref in sorted(set(IDENT.findall(signature_only))):
            if ref == name or ref in {"ALL", "OK"}:
                continue
            if ref not in decls:
                continue
            if decls[ref] > pos:
                errors.append(
                    f"{name} 引用了 {ref}，但 {ref} 声明在后面（P081：属性按声明顺序初始化）"
                )

    # 检查 3：结构完整性 —— 两个子步骤列表都要被 profile 真正用上。
    for required in ("RE_LAB_PIP_STEPS", "RE_LAB_DEBIAN_STEPS", "ANGR_STEPS"):
        if required not in decls:
            errors.append(f"缺少 {required}")
    if "used" not in body and "steps = RE_LAB_PIP_STEPS" not in body:
        errors.append("RE_LAB 的 Alpine spec 没有引用 RE_LAB_PIP_STEPS")
    if "steps = RE_LAB_DEBIAN_STEPS" not in body:
        errors.append("RE_LAB 的 Debian spec 没有引用 RE_LAB_DEBIAN_STEPS")
    if "artifacts = ::reLabArtifacts" not in body:
        errors.append("RE_LAB 的 Debian spec 没有挂 artifacts，radare2 会装不上")
    if body.count("steps = ANGR_STEPS") != 2:
        errors.append("ANGR 的 Alpine/Debian spec 都应引用 ANGR_STEPS")

    # 检查 4：radare2 不能再出现在 packages 里（Debian trixie 没有这个包）。
    for m in re.finditer(r"packages\s*=\s*listOf\(([^)]*)\)", body):
        items = m.group(1)
        if "radare2" in items:
            # Alpine 允许，Debian 不允许 —— 检查这一段上文 200 字符里有没有 DEBIAN
            ctx = body[max(0, m.start() - 220):m.start()]
            if "DEBIAN" in ctx:
                errors.append("Debian 的 packages 里不能出现 radare2（trixie 源里没有）")

    # 检查 5：sha256 必须是 64 位十六进制。
    for m in re.finditer(r'sha256\s*=\s*"([^"]*)"', body):
        digest = m.group(1)
        if not re.fullmatch(r"[0-9a-f]{64}", digest):
            errors.append(f"sha256 格式不对：{digest}")

    if errors:
        print("FAIL:")
        for e in errors:
            print("  -", e)
        return 1

    print(f"PASS: 声明顺序正确，{len(decls)} 个成员，无 P081 风险")
    return 0


if __name__ == "__main__":
    sys.exit(main())
