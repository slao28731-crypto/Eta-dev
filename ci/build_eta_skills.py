#!/usr/bin/env python3
"""把 AoRly 格式的逆向 skill 合集转成 Eta 可导入的格式（v2，规则版）。

v1 用关键词打分，被证明不可靠：`types` / `annotations` / `functions` / `xrefs` /
`disassembly` / `data` / `connect` 这 7 份正文全讲 idasql（IDA 的 SQL 插件），
只因为顺带提了 android 就被判成手机端可用，实际手机上跑不了（没有 IDA）。

v2 改成三条硬规则，宁可漏判也不误判：

  R1 强排除：正文出现 IDA/idasql/x64dbg/WinDbg/PE 等 PC 专属锚点，
     且手机锚点不占优 → 剔除
  R2 强纳入：目录名在人工确认过的手机端白名单里
  R3 其余：手机锚点 >= 3 且 PC 锚点占比 <= 1/4 才收

Eta 的导入规则（来自 SkillPackageInstaller.kt，逐条核实）：
  - SKILL.md 必须以 --- 包围的 YAML frontmatter 开头
  - frontmatter `name` 即技能 id / 目录名，正则 ^[a-z0-9]+(?:-[a-z0-9]+)*$，长度 1..64
  - `description` 必填，<= 1024
  - installLocalZip 只接受「ZIP 里恰好一个 SKILL.md」，多一个就 MULTIPLE_SKILLS_FOUND
  - id 不能与内置 skill 冲突

用法：
    python3 ci/build_eta_skills.py --out /sdcard/Download/eta-skills
"""

import argparse
import re
import shutil
import sys
import zipfile
from pathlib import Path

SRC = Path("/storage/emulated/0/Download/逆向开发破解skill清单")

BUILTIN_IDS = {
    "re-mobile", "re-pc", "skill-creator", "skill-installer", "self-improving-agent",
}

NAME_RE = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
MAX_NAME_LEN = 64
MAX_DESC_LEN = 1024
MAX_SKILL_BYTES = 512 * 1024
FRONTMATTER = re.compile(r"\A---\r?\n(.*?)\r?\n---\r?\n", re.S)

# R2：人工确认过的手机端技能，这些直接收，不再打分
MOBILE_WHITELIST = {
    "ad-removal", "ai-reverse-workflow", "android-unpacking", "android-pentest",
    "android-reverse-engineering", "android-release", "apk-reverse", "apktool",
    "deobfuscation-ollvm", "frida-scripts", "hybrid-h5-reverse", "jadx",
    "kernel-system-hook", "memory-integrity-bypass", "noroot-hook",
    "packer-identification", "performing-dynamic-analysis-of-android-app",
    "protocol-crypto-analysis", "rev-dex-dumper", "rev-frida", "rev-u3d-dump",
    "reverse-patch-techniques", "signature-bypass", "smali-repack",
    "svc-instruction-trace", "unity-il2cpp-reverse", "xposed-module-builder",
    "flutter-reverse", "mobile-reverse", "re-android-crypto", "re-android-native",
    "re-apk", "re-flutter", "re-frida", "re-frida-script-author",
    "re-hybrid-app", "re-ios", "re-ios-jb", "re-mobile-pack",
    "re-mobile-forensics", "re-harmonyos", "re-unpack-simple", "re-packer-id",
    "re-deobfuscate", "re-cracking", "re-license", "re-keygen", "re-drm",
    "re-crypto-id", "re-crypto-decrypt", "re-crypto-keys", "re-protocol",
    "re-netcap", "rev-ios-dump", "rev-struct", "rev-symbol",
    "mt-mcp-apk-analyzer", "android-emulator-skill", "android-gradle-logic",
    "apk-clues-extract", "re-source", "src-hunter",
}


def _reject_pc_anchors(body: str) -> bool:
    """R1a：只要出现这些强特征，无论白名单与否一律剔除。

    白名单也会被污染：`data` / `decompiler` 这类目录名看着通用，
    正文其实是 idasql（IDA 的 SQL 插件）文档，手机上根本没有 IDA。
    """
    low = body.lower()
    return any(anchor in low for anchor in STRONG_PC_ANCHORS)


STRONG_PC_ANCHORS = [
    # 只留「用来判断这份文档本身在讲什么工具」的强锚点。
    # 不放在这里的是 .exe / .dll / 驱动 这类：手机端技能里常拿它们做对比举例
    # （例如 ai-reverse-workflow 说「.exe 那种要回电脑上做」），
    # 一出现就剔除会把手机端技能误杀。
    "idasql", "idapython", "ida pro", "x64dbg", "windbg", "ollydbg",
    "ghidra script", "ghidra headless", "binary ninja", ".idb", ".i64",
    "sysinternals", "amsi", "lolbin", "themida", "vmprotect",
    "scylla", "imprec", "esp 定律", "ntqueryinformationprocess",
    "beingdebugged", "pe header", "pe 结构", "iat 表",
]

# R1：出现这些锚点 = PC 专属工具/格式
PC_ANCHORS = [
    "idasql", "ida pro", "idapython", "x64dbg", "windbg", "ollydbg",
    "ghidra script", "pe 文件", "pe文件", ".idb", ".i64", "sysinternals",
    "amsi", "etw provider", "lolbin", "themida", "vmprotect",
    "scylla", "imprec", "oep", "esp 定律", "驱动签名",
    "ntqueryinformationprocess", "beingdebugged",
]

MOBILE_SIGNALS = ["android", "apk", "smali", "dex ", "frida", "jni", "zygote"]


def slugify(raw: str) -> str:
    value = raw.strip().lower().replace("_", "-").replace(" ", "-")
    value = re.sub(r"[^a-z0-9-]", "", value)
    value = re.sub(r"-+", "-", value).strip("-")
    return value[:MAX_NAME_LEN].strip("-")


def parse_frontmatter(text: str):
    match = FRONTMATTER.match(text)
    if not match:
        return {}, text
    raw, body = match.group(1), text[match.end():]
    meta, key, buffer = {}, None, []
    for line in raw.splitlines():
        if re.match(r"^[A-Za-z_][A-Za-z0-9_-]*\s*:", line):
            if key:
                meta[key] = " ".join(buffer).strip()
            key, _, rest = line.partition(":")
            key, buffer = key.strip(), [rest.strip()]
        elif key:
            buffer.append(line.strip())
    if key:
        meta[key] = " ".join(buffer).strip()
    for k, v in list(meta.items()):
        meta[k] = re.sub(r"^[>|][-+]?\s*", "", v).strip()
    return meta, body


def unlink_brackets(body: str) -> str:
    """AoRly 的 [[双链]] Eta 不认识，改成行内代码。"""
    body = re.sub(r"\[\[([^\]|]+)\|([^\]]+)\]\]", r"`\2`", body)
    return re.sub(r"\[\[([^\]]+)\]\]", r"`\1`", body)


def mobile_score(body: str) -> int:
    low = body.lower()
    return sum(low.count(w) for w in MOBILE_SIGNALS)


# idasql 那套技能的目录名：它们全是 IDA 插件/数据库操作手册，
# 正文里可能一次都不写 "idasql"，只能靠目录名认。
IDA_PLUGIN_DIRS = {
    "types", "annotations", "functions", "data", "connect", "xrefs",
    "disassembly", "decompiler", "analysis", "storage", "ui-context",
    "ida-decompile", "idapython", "rev-idapython", "re-ida", "re-ghidra",
    "re-binaryninja", "binary-ninja-reverse", "ida-reverse", "ida-pro-skill",
    "IDA-Skill", "Smart Patch (IDA Pro)", "radare2", "re-radare2",
}


def _is_ida_plugin(src_name: str, body: str) -> bool:
    """判断这份文档是不是 IDA/反汇编器的插件手册。

    为什么不能只查 "idasql" 整词：`data` / `decompiler` 正文里一次都没出现
    这个词，只写 "ida"、"sql"、"function" 这类通用词；`decompiler` 里
    "ida" 只出现 5 次，也不到词频阈值。目录名是最可靠的信号。
    """
    if src_name in IDA_PLUGIN_DIRS:
        return True
    low = body.lower()
    ida_hits = len(re.findall(r"\bida\b", low))
    sql_hits = low.count("sql")
    mob_hits = mobile_score(body)
    if ida_hits >= 3 and ida_hits >= mob_hits:
        return True
    if ida_hits + sql_hits >= 8 and mob_hits <= 3:
        return True
    return False


def decide(src_name: str, body: str) -> str:
    """返回 keep / pc / weak。

    优先级刻意这样排，每一条的先后都有原因：

    1. IDA_PLUGIN_DIRS 目录名 —— 硬事实，最先判
    2. MOBILE_WHITELIST —— 人工确认过的手机端技能，必须压过词频
       否则 `ai-reverse-workflow` 会被误杀：它正文里 "IDA" 出现 8 次
       （在讲「这种活要回电脑上做」），词频判定直接判成 pc，
       但它本身是手机端工作流指南。
    3. 强 PC 锚点 —— 出现就是 PC 文档
    4. IDA 词频 —— 兜底，抓那些目录名看不出但通篇讲 IDA 的
    5. 手机锚点占比
    """
    if src_name in IDA_PLUGIN_DIRS:
        return "pc"
    if src_name in MOBILE_WHITELIST:
        return "keep"
    if _reject_pc_anchors(body):
        return "pc"

    low = body.lower()
    ida_hits = len(re.findall(r"\bida\b", low))
    sql_hits = low.count("sql")
    mob_hits = mobile_score(body)
    if ida_hits >= 3 and ida_hits >= mob_hits:
        return "pc"
    if ida_hits + sql_hits >= 8 and mob_hits <= 3:
        return "pc"

    pc_hits = sum(low.count(a) for a in PC_ANCHORS)
    if pc_hits > 0 and pc_hits >= mob_hits:
        return "pc"
    if mob_hits >= 3 and pc_hits * 4 <= mob_hits:
        return "keep"
    return "weak"


def convert_one(src_dir: Path, out_dir: Path):
    skill_file = src_dir / "SKILL.md"
    if not skill_file.is_file():
        return None, "no-skill-md"
    raw = skill_file.read_text(encoding="utf-8", errors="replace")
    if len(raw.encode("utf-8")) > MAX_SKILL_BYTES:
        return None, "too-large"

    meta, body = parse_frontmatter(raw)
    verdict = decide(src_dir.name, body)
    if verdict != "keep":
        return None, verdict

    name = slugify(meta.get("name") or src_dir.name)
    if not name or not NAME_RE.match(name):
        return None, "bad-name"
    if name in BUILTIN_IDS:
        return None, "builtin-conflict"

    description = re.sub(r"\s+", " ", meta.get("description") or "").strip()
    if not description:
        return None, "no-description"
    if len(description) > MAX_DESC_LEN:
        description = description[: MAX_DESC_LEN - 1].rstrip() + "…"

    body = unlink_brackets(body)
    content = f"---\nname: {name}\ndescription: {description}\n---\n" + body.lstrip("\n")

    target = out_dir / name
    target.mkdir(parents=True, exist_ok=True)
    (target / "SKILL.md").write_text(content, encoding="utf-8")

    return {"name": name, "source": src_dir.name, "chars": len(content)}, None


def make_single_zip(skill_dir: Path, zip_path: Path) -> None:
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zip_file:
        for path in sorted(skill_dir.rglob("*")):
            if path.is_file():
                zip_file.write(path, path.relative_to(skill_dir).as_posix())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="/sdcard/Download/eta-skills")
    args = parser.parse_args()

    out_root = Path(args.out)
    staging = out_root / "skills"
    zips = out_root / "zips"
    for target in (staging, zips):
        if target.exists():
            shutil.rmtree(target)
    staging.mkdir(parents=True)
    zips.mkdir(parents=True)

    kept, skipped = [], {}
    for entry in sorted(SRC.iterdir()):
        if not entry.is_dir():
            continue
        result, reason = convert_one(entry, staging)
        if result:
            kept.append(result)
        else:
            skipped.setdefault(reason, []).append(entry.name)

    print(f"=== 转换 {len(kept)} 个 ===")
    for item in sorted(kept, key=lambda x: x["name"]):
        print(f"  {item['chars']:>6}字  {item['name']:<32} <- {item['source']}")

    print("\n=== 跳过 ===")
    for reason, names in sorted(skipped.items()):
        print(f"  {reason}: {len(names)} 个 -> {', '.join(sorted(names)[:6])}"
              + (" …" if len(names) > 6 else ""))

    count = 0
    for skill_dir in sorted(staging.iterdir()):
        if skill_dir.is_dir():
            make_single_zip(skill_dir, zips / f"{skill_dir.name}.zip")
            count += 1

    total = sum(f.stat().st_size for f in zips.glob("*.zip"))
    print("\n=== 产出 ===")
    print(f"  单技能 ZIP：{count} 个 / {total / 1024:.0f} KB")
    print(f"  目录：{zips}")
    return 0


if __name__ == "__main__":
    sys.exit(main())