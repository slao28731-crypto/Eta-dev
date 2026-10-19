#!/usr/bin/env python3
"""把 zhaoxuya520/reverse-skill 里手机端可用的技能补进 Eta 转换产物。

这个仓库共 89 个 SKILL.md，但和已转换的 122 个 dslsdzc 技能大量重叠，
真正新增且手机端可用的只有 radare2 一个（14KB，含完整 rabin2 侦察流程
与 r2pipe 脚本化，全 CLI 用法，手机能跑）。

其余不同点：
- skills/ 里 44 个都是 PC/云/CTF（ida-reverse、ghidra-reverse、windows-ad…）
- CTF-Sandbox-Orchestrator/ 41 个是比赛编排场景（AD 证书滥用、K8s 逃逸…）
  跟手机逆向无关

处理要点（与主转换器一致）：
1. name 压成 ^[a-z0-9]+(-[a-z0-9]+)*$
2. description 截到 1024
3. 去掉 PowerShell 优先的写法，改成 POSIX sh，否则手机上照着抄会失败
4. 去掉它对 ../tool-index.md / ../field-journal/ 的跨技能引用
   （那些文件不在这个技能目录里，Eta 装上后会指到不存在的路径）
"""

import re
import sys
import zipfile
from pathlib import Path

SRC = Path("/tmp/rs/skills/radare2")
OUT_ROOT = Path("/sdcard/Download/eta-skills")
NAME = "radare2"
MAX_DESC = 1024
NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")

# 跨技能引用：这些路径在单技能包里不存在，留着会让 Agent 去找不存在的文件
CROSS_REFS = [
    (r"1\. `NOW`: 读取 `\.\./field-journal/precedent-reverse\.md`[^\n]*\n", ""),
    (r"2\. `NOW`: 确认当前任务是否命中本 skill 的适用范围\n", ""),
    (r"3\. `NEXT`: 读取 `\.\./tool-index\.md`[^\n]*\n", ""),
    (r"4\. `NEXT`: 缺工具时调用 bootstrap，不要猜路径\n", ""),
    (r"5\. `ACT`: 进入", "1. `ACT`: 进入"),
    (r"`\.\./[a-z-]+/[a-z.-]+\.md`", "`references/`"),
    (r"（见 `reverse-engineering/references/[a-z.-]+`[^）]*）", ""),
]

# PowerShell 段整块删除：手机 Linux 环境里没有 powershell
PS_BLOCK = re.compile(r"```powershell\n(?:.*?\n)*?```\n", re.S)


def main() -> int:
    skill_file = SRC / "SKILL.md"
    if not skill_file.is_file():
        print(f"找不到 {skill_file}")
        return 1

    raw = skill_file.read_text(encoding="utf-8", errors="replace")
    match = re.match(r"\A---\r?\n(.*?)\r?\n---\r?\n", raw, re.S)
    if not match:
        print("没有 frontmatter")
        return 1
    front, body = match.group(1), raw[match.end():]

    # 取 description（可能是 | 块）
    desc_lines = []
    for line in front.splitlines():
        if line.startswith("description:"):
            desc_lines.append(line.partition(":")[2].strip())
        elif desc_lines and line.startswith("  "):
            desc_lines.append(line.strip())
    description = re.sub(r"^\|?\s*", "", " ".join(desc_lines)).strip()
    description = re.sub(r"\s+", " ", description)
    if len(description) > MAX_DESC:
        description = description[: MAX_DESC - 1].rstrip() + "…"

    # 正文清洗
    body = PS_BLOCK.sub("", body)
    for pattern, repl in CROSS_REFS:
        body = re.sub(pattern, repl, body)
    # PowerShell 专用行内引用
    body = body.replace("powershell -File", "sh")

    # 明确写清「这个技能在手机端怎么用」
    header_note = (
        "\n> **手机端说明**：本技能的命令全部是 CLI，Eta 的 Linux 环境（Debian）"
        "可直接执行。`r2` / `rabin2` / `rasm2` / `radiff2` 由「设置 → 终端 → Linux 环境 → "
        "逆向分析工具组」安装（apt 装 radare2 包）。原文里的 PowerShell 段落已删除，"
        "改用 `sh` 或直接执行命令。`r2pipe` 需要 `python3 -m pip install r2pipe`。\n\n"
    )

    content = f"---\nname: {NAME}\ndescription: {description}\n---\n" + header_note + body.lstrip("\n")

    if not NAME_RE.match(NAME):
        print("name 不合规")
        return 1

    target = OUT_ROOT / "skills" / NAME
    target.mkdir(parents=True, exist_ok=True)
    (target / "SKILL.md").write_text(content, encoding="utf-8")

    # 带上它自带的 references（cheatsheet 有实用价值）
    refs = SRC / "references"
    if refs.is_dir():
        (target / "references").mkdir(exist_ok=True)
        for item in refs.iterdir():
            if item.is_file():
                (target / "references" / item.name).write_bytes(item.read_bytes())

    zip_path = OUT_ROOT / "zips" / f"{NAME}.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(target.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(target).as_posix())

    print(f"写出 {target/'SKILL.md'}（{len(content)} 字）")
    print(f"打包 {zip_path}（{zip_path.stat().st_size} 字节）")
    return 0


if __name__ == "__main__":
    sys.exit(main())