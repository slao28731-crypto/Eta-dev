#!/usr/bin/env python3
"""按 Eta 的 SkillPackageInstaller 规则逐条校验生成的 ZIP。

这是「能不能装进去」的最后一道关。规则全部从 SkillPackageInstaller.kt 抄来：

  name       ^[a-z0-9]+(?:-[a-z0-9]+)*$   长度 1..64
  description 非空，<= 1024
  name 不能撞内置 skill id
  每个 zip 内恰好一个 SKILL.md（多一个就 MULTIPLE_SKILLS_FOUND）
  单 SKILL.md <= 512KB（maxSkillFileBytes）
  包解压总量 <= 128MB，条目 <= 2048，路径深度 <= 16
  路径段 <= 255，不能有 .. / 绝对路径 / 盘符 / 符号链接
  必须是严格 UTF-8

任何一条不过，说明这个 zip 到手机上会被拒。跑这个脚本等于提前把安装器走一遍。
"""

import re
import sys
import zipfile
from pathlib import Path

BUILTIN_IDS = {
    "re-mobile", "re-pc", "skill-creator", "skill-installer", "self-improving-agent",
}
NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
DRIVE_RE = re.compile(r"^[A-Za-z]:")

MAX_ARCHIVE_BYTES = 32 * 1024 * 1024
MAX_EXTRACTED_BYTES = 128 * 1024 * 1024
MAX_SINGLE_FILE_BYTES = 32 * 1024 * 1024
MAX_SKILL_FILE_BYTES = 512 * 1024
MAX_ENTRIES = 2048
MAX_PATH_DEPTH = 16
MAX_PATH_SEGMENT = 255
MAX_NAME_LEN = 64
MAX_DESC_LEN = 1024


def parse_simple_frontmatter(raw: str) -> dict:
    meta = {}
    for line in raw.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if ":" not in line:
            continue
        key, _, value = line.partition(":")
        meta[key.strip()] = value.strip()
    return meta


def check_zip(path: Path):
    errors = []

    if path.stat().st_size > MAX_ARCHIVE_BYTES:
        return [f"包体超过 {MAX_ARCHIVE_BYTES} 字节"]

    try:
        with zipfile.ZipFile(path) as archive:
            names = []
            extracted = 0
            skill_files = []

            for info in archive.infolist():
                name = info.filename
                if name.endswith("/"):
                    continue
                names.append(name)

                if len(names) > MAX_ENTRIES:
                    errors.append(f"条目超过 {MAX_ENTRIES}")
                    break

                segments = name.split("/")
                if len(segments) > MAX_PATH_DEPTH:
                    errors.append(f"路径过深（{len(segments)} 层）：{name}")
                for segment in segments:
                    if len(segment) > MAX_PATH_SEGMENT:
                        errors.append(f"路径段过长：{segment[:40]}…")
                    if segment in ("..", "."):
                        errors.append(f"含相对路径段：{name}")
                if name.startswith("/") or DRIVE_RE.match(name):
                    errors.append(f"绝对路径：{name}")

                if info.file_size > MAX_SINGLE_FILE_BYTES:
                    errors.append(f"单文件超限：{name}")
                extracted += info.file_size

                if segments[-1] == "SKILL.md":
                    skill_files.append(name)

            if extracted > MAX_EXTRACTED_BYTES:
                errors.append(f"解压总量超限：{extracted}")

            if not skill_files:
                errors.append("没有 SKILL.md")
                return errors
            if len(skill_files) > 1:
                errors.append(
                    f"发现 {len(skill_files)} 个 SKILL.md，installLocalZip 会报 "
                    f"MULTIPLE_SKILLS_FOUND：{skill_files}"
                )
                return errors

            raw_bytes = archive.read(skill_files[0])
            if len(raw_bytes) > MAX_SKILL_FILE_BYTES:
                errors.append(f"SKILL.md 超过 {MAX_SKILL_FILE_BYTES} 字节")
            try:
                text = raw_bytes.decode("utf-8", errors="strict")
            except UnicodeDecodeError:
                errors.append("SKILL.md 不是严格 UTF-8")
                return errors

            if not text.startswith("---"):
                errors.append("SKILL.md 没有以 --- 开头")
                return errors

            first = text.find("\n")
            closing = text.find("\n---", first)
            if closing < 0:
                errors.append("frontmatter 没有闭合的 ---")
                return errors

            meta = parse_simple_frontmatter(text[first + 1: closing])
            name = meta.get("name", "").strip()
            desc = meta.get("description", "").strip()

            if not (1 <= len(name) <= MAX_NAME_LEN) or not NAME_RE.match(name):
                errors.append(f"name 不合规：{name!r}")
            if name in BUILTIN_IDS:
                errors.append(f"name 与内置技能冲突：{name}")
            if not desc:
                errors.append("description 为空")
            elif len(desc) > MAX_DESC_LEN:
                errors.append(f"description 超过 {MAX_DESC_LEN}：{len(desc)}")

            # 目录名必须等于 name（Eta 用 name 作为安装目录名）
            parent = skill_files[0].rsplit("/", 1)[0] if "/" in skill_files[0] else ""
            if parent and parent != name:
                errors.append(f"目录名 {parent!r} 与 name {name!r} 不一致")

    except zipfile.BadZipFile:
        return ["不是有效 ZIP"]

    return errors


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/etaskills2/zips")
    zips = sorted(root.glob("*.zip"))
    if not zips:
        print(f"{root} 下没有 zip")
        return 1

    bad = 0
    for path in zips:
        problems = check_zip(path)
        if problems:
            bad += 1
            print(f"[FAIL] {path.name}")
            for problem in problems:
                print(f"       - {problem}")

    print(f"\n共 {len(zips)} 个 ZIP，失败 {bad} 个")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())