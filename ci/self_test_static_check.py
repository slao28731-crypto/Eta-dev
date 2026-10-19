#!/usr/bin/env python3
"""自测 static_check.py 的关键检查是否真能拦住已踩过的坑。

覆盖两个真实事故：
1. run #37：Kotlin 字符串里裸写 `$eta_bin`
2. run #40：`use { ... }` 少闭合一层，class 被提前截断

做法：把源码复制到临时目录，注入事故，跑检查，必须返回非 0。
"""

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NDK = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/AndroidNdkInstaller.kt"
PROFILES = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt"
CHECKER = ROOT / "ci/static_check.py"

COPY_FILES = [
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt",
    "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/AndroidNdkInstaller.kt",
    "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt",
    "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentSections.kt",
]
COPY_SCRIPTS = [
    "ci/verify_profile_decl_order.py",
    "ci/verify_re_lab_profiles.py",
]


def with_temp_copy(overrides: dict) -> subprocess.CompletedProcess:
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        for rel in COPY_FILES:
            dst = tmp_root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            if rel in overrides:
                dst.write_text(overrides[rel], encoding="utf-8")
            else:
                shutil.copy(ROOT / rel, dst)
        shutil.copytree(ROOT / "app/src/main/res", tmp_root / "app/src/main/res")
        (tmp_root / "ci").mkdir(parents=True)
        for rel in COPY_SCRIPTS:
            shutil.copy(ROOT / rel, tmp_root / rel)
        shutil.copy(CHECKER, tmp_root / "ci/static_check.py")
        return subprocess.run(
            [sys.executable, "ci/static_check.py"],
            cwd=tmp_root,
            capture_output=True,
            text=True,
        )


def main() -> int:
    failures = []

    # --- 事故 1：未转义的 shell 变量 ---
    profiles = PROFILES.read_text(encoding="utf-8")
    pattern = re.compile(r'append\("\[ -d \\"\\\$eta_bin\\" \] \|\| exit 78; "\)')
    if not pattern.search(profiles):
        failures.append("事故1：找不到已转义的 eta_bin 那行，脚本与源码不同步")
    else:
        broken = pattern.sub('append("[ -d \\"$eta_bin\\" ] || exit 78; ")', profiles, count=1)
        proc = with_temp_copy({COPY_FILES[0]: broken})
        if proc.returncode == 0:
            failures.append("事故1：注入未转义 $eta_bin 后仍通过（漏检）")
        elif "eta_bin" not in proc.stdout:
            failures.append("事故1：报错但没指向 eta_bin")
        else:
            print("OK  事故1（未转义 $eta_bin）被拦下")

    # --- 事故 2：少闭合一层 use {} ---
    ndk = NDK.read_text(encoding="utf-8")
    needle = """                }
            }
        }

        // 链接放到最后建"""
    if needle not in ndk:
        failures.append("事故2：找不到 extractArchive 的闭合锚点，脚本与源码不同步")
    else:
        broken = ndk.replace(
            needle,
            """                }
        }

        // 链接放到最后建""",
            1,
        )
        proc = with_temp_copy({COPY_FILES[1]: broken})
        if proc.returncode == 0:
            failures.append("事故2：少闭合一层后仍通过（漏检）")
        elif "深度" not in proc.stdout:
            failures.append("事故2：报错但不是深度检查触发")
        else:
            print("OK  事故2（少闭合一层 use{}）被拦下")

    # 原始源码必须通过
    proc = subprocess.run(
        [sys.executable, str(CHECKER)], cwd=ROOT, capture_output=True, text=True
    )
    if proc.returncode != 0:
        failures.append("当前源码被判定失败：" + proc.stdout.strip()[-400:])
    else:
        print("OK  当前源码通过")

    if failures:
        print("FAIL:")
        for item in failures:
            print("  -", item)
        return 1
    print("PASS: 两项关键检查都有灵敏度，且当前源码通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())