#!/usr/bin/env python3
"""
校验「Compose 参数逐层透传」是否完整。

背景（P095）：AgentChatBody.kt 里输入栏被拆成三层独立函数
    AgentChatBody → AgentChatScaffold → AgentChatBottomBar
给某一层加了参数，却没在下一层签名里补、或没在调用点传下去，
编译期才报 `Unresolved reference`，一次往返十分钟。run #54~#59 连踩六次。

本脚本不依赖编译器：把每个 fun 的参数名收集起来，把每个调用点传的实参名
收集起来，比对「被调函数需要的参数」是否在调用点全部传齐。

只查参数名，不查类型——够用，且不会误报。
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 需要校验的文件（新增多层 Composable 时往里加）
TARGETS = [
    "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatBody.kt",
    "app/src/main/kotlin/io/github/mangi/eta/ui/components/AgentChatInputBar.kt",
    "app/src/main/kotlin/io/github/mangi/eta/ui/components/PendingMessageQueuePanel.kt",
]

# 重点关注的参数（本轮新增的队列参数）；
# 设成 None 表示「校验全部参数」，但那样会因默认值/泛型噪声大，这里只盯关键组。
WATCHED = {
    "queuedMessages",
    "queueExpanded",
    "onQueueExpandedChange",
    "onRemoveQueuedMessage",
    "onSendQueuedMessageNow",
    "onQueueMessage",
}


def split_params(text: str) -> dict:
    """返回 {函数名: 参数名集合}，只处理顶层 fun。"""
    funcs = {}
    for m in re.finditer(r"^(?:internal |private |public )?fun\s+(\w+)\s*\(", text, re.M):
        name = m.group(1)
        start = m.end() - 1
        depth, end = 0, None
        for i in range(start, len(text)):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        if end is None:
            continue
        params = text[start + 1 : end]
        names = {pm.group(1) for pm in re.finditer(r"^\s*(\w+)\s*:", params, re.M)}
        funcs[name] = names
    return funcs


def call_sites(text: str, callee: str) -> list:
    """返回该函数每个调用点传入的具名实参集合。跳过定义行本身。"""
    out = []
    for m in re.finditer(re.escape(callee) + r"\s*\(", text):
        line_start = text.rfind("\n", 0, m.start()) + 1
        prefix = text[line_start : m.start()]
        # 定义行：`fun XXX(` / `private fun XXX(`
        if "fun" in prefix:
            continue
        depth, end = 0, None
        start = m.end() - 1
        for i in range(start, len(text)):
            if text[i] == "(":
                depth += 1
            elif text[i] == ")":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        if end is None:
            continue
        args = text[start + 1 : end]
        passed = {am.group(1) for am in re.finditer(r"(?:^|,)\s*(\w+)\s*=", args)}
        out.append(passed)
    return out


def main() -> int:
    failures = []
    for rel in TARGETS:
        path = ROOT / rel
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        funcs = split_params(text)
        # 只校验真正带这批参数的函数，避免对无关函数报噪声
        for fname, params in funcs.items():
            need = params & WATCHED
            if not need:
                continue
            for passed in call_sites(text, fname):
                missing = need - passed
                if missing:
                    failures.append(
                        f"{path.name}: 调用 {fname}() 少传 {sorted(missing)}"
                    )
    if failures:
        print("[FAIL] Compose 参数透传不完整：")
        for f in failures:
            print(" -", f)
        return 1
    print("[PASS] Compose 参数透传完整")
    return 0


if __name__ == "__main__":
    sys.exit(main())
