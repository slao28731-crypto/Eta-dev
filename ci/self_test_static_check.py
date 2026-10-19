#!/usr/bin/env python3
"""自测 static_check.py 的关键检查是否真的能拦住本轮踩过的坑。

只验证一件事：`ci/static_check.py` 的 shell 变量转义检查，
能不能抓到 run #37 的真实错误（Kotlin 字符串里裸写 `$eta_bin`）。

做法：把源码复制到临时目录，注入 `$eta_bin` 不加反斜杠的写法，
跑检查脚本，必须返回非 0。
"""

import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt"
CHECKER = ROOT / "ci/static_check.py"


def main() -> int:
    original = SRC.read_text(encoding="utf-8")
    # 定位已经修好的那行（带了 \$eta_bin），把它改回事故写法
    pattern = re.compile(r'append\("\[ -d \\"\\\$eta_bin\\" \] \|\| exit 78; "\)')
    if not pattern.search(original):
        print("FAIL: 源码结构与预期不符，找不到已转义的 eta_bin 那一行")
        return 1
    broken = pattern.sub('append("[ -d \\"$eta_bin\\" ] || exit 78; ")', original, count=1)
    if broken == original:
        print("FAIL: 注入未生效")
        return 1

    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        rel = Path("app/src/main/kotlin/io/github/mangi/eta/agent/terminal")
        (tmp_root / rel).mkdir(parents=True)
        (tmp_root / rel / "LinuxPackageProfiles.kt").write_text(broken, encoding="utf-8")
        # 其余两个文件原样复制，避免因为「文件不存在」而误判
        for extra in (
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentScreen.kt",
            "app/src/main/kotlin/io/github/mangi/eta/ui/screens/terminal/LinuxEnvironmentSections.kt",
        ):
            dst = tmp_root / extra
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(ROOT / extra, dst)
        # 资源目录与依赖脚本也复制过去
        shutil.copytree(ROOT / "app/src/main/res", tmp_root / "app/src/main/res")
        (tmp_root / "ci").mkdir()
        for script in (
            "ci/verify_profile_decl_order.py",
            "ci/verify_re_lab_profiles.py",
        ):
            shutil.copy(ROOT / script, tmp_root / script)
        shutil.copy(CHECKER, tmp_root / "ci/static_check.py")

        proc = subprocess.run(
            [sys.executable, "ci/static_check.py"],
            cwd=tmp_root,
            capture_output=True,
            text=True,
        )
        if proc.returncode == 0:
            print("FAIL: 注入未转义的 $eta_bin 后，检查仍然通过（漏检）")
            return 1
        if "eta_bin" not in proc.stdout:
            print("FAIL: 报错了但没指向 eta_bin，检查逻辑可能误伤别处")
            print(proc.stdout[-500:])
            return 1
        print("OK  未转义的 $eta_bin 被正确拦下")

    # 原始源码必须通过
    proc = subprocess.run(
        [sys.executable, str(CHECKER)], cwd=ROOT, capture_output=True, text=True
    )
    if proc.returncode != 0:
        print("FAIL: 当前源码被判定失败：", proc.stdout.strip()[-400:])
        return 1
    print("PASS: 转义检查有灵敏度，且当前源码通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())