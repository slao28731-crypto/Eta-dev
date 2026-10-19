#!/usr/bin/env python3
"""给转换后的技能目录生成一份可读清单（含每条的 description 摘要）。

为什么要单独生成：Eta 设置页只显示 name + description，
86 个技能散在一个文件管理器目录里，你没法快速判断哪个值得装。
这份清单把「id / 原始来源 / 一句话说明 / 体积」排成表。
"""

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else "/sdcard/Download/eta-skills/skills")
OUT = Path(sys.argv[2] if len(sys.argv) > 2 else "/sdcard/Download/eta-skills/00-清单.md")

FRONTMATTER = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n", re.S)


def read_meta(skill_file: Path):
    raw = skill_file.read_text(encoding="utf-8", errors="replace")
    match = FRONTMATTER.match(raw)
    if not match:
        return "", "", len(raw)
    meta = {}
    for line in match.group(1).splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            meta[key.strip()] = value.strip()
    return meta.get("name", ""), meta.get("description", ""), len(raw)


def main() -> int:
    rows = []
    for skill_dir in sorted(ROOT.iterdir()):
        skill_file = skill_dir / "SKILL.md"
        if not skill_file.is_file():
            continue
        name, desc, size = read_meta(skill_file)
        rows.append((name or skill_dir.name, desc, size))

    lines = [
        "# Eta 可导入技能清单",
        "",
        f"共 **{len(rows)}** 个，全部已通过 Eta 的 `SkillPackageInstaller` 规则校验。",
        "",
        "导入方式：设置 → Skills → 导入 ZIP，一次选一个。",
        "",
        "| # | name | 说明 | 字节 |",
        "|---|---|---|---|",
    ]
    for index, (name, desc, size) in enumerate(rows, 1):
        short = desc if len(desc) <= 90 else desc[:88].rstrip() + "…"
        short = short.replace("|", "\\|")
        lines.append(f"| {index} | `{name}` | {short} | {size} |")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"写出 {OUT}（{len(rows)} 条）")
    return 0


if __name__ == "__main__":
    sys.exit(main())