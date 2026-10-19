#!/usr/bin/env python3
"""自测 verify_profile_decl_order.py 的灵敏度：制造两种典型事故，都必须报 FAIL。

这是给校验脚本本身做反向验证。只断言「有问题时必须 FAIL」，不关心具体文案。
"""
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/kotlin/io/github/mangi/eta/agent/terminal/LinuxPackageProfiles.kt"
CHECKER = ROOT / "ci/verify_profile_decl_order.py"

CASES = []


def case(name, mutate):
    CASES.append((name, mutate))


def all_too_early(text: str) -> str:
    """事故 A：ALL 提到 object 最前面（P081 原始事故）。"""
    return text.replace(
        "internal object LinuxPackageProfiles {",
        "internal object LinuxPackageProfiles {\n    val ALL = listOf(PYTHON, RE_LAB)",
        1,
    )


def steps_declared_late(text: str) -> str:
    """事故 B：RE_LAB_DEBIAN_STEPS 被挪到 RE_LAB_PIP_STEPS 之前（本轮真实风险）。"""
    marker = "    private val RE_LAB_DEBIAN_STEPS = listOf("
    idx = text.find(marker)
    if idx < 0:
        raise SystemExit("找不到 RE_LAB_DEBIAN_STEPS，脚本与源码不同步")
    end = text.find("    ) + RE_LAB_PIP_STEPS\n", idx)
    if end < 0:
        raise SystemExit("找不到 RE_LAB_DEBIAN_STEPS 的结束标记")
    block = text[idx:end + len("    ) + RE_LAB_PIP_STEPS\n")]
    rest = text[:idx] + text[idx + len(block):]
    anchor = "    /** pip 侧逐包一步"
    pos = rest.find(anchor)
    if pos < 0:
        raise SystemExit("找不到插入锚点")
    return rest[:pos] + block + "\n" + rest[pos:]


def artifacts_removed(text: str) -> str:
    """事故 C：忘记挂 artifacts，radare2 会装不上。"""
    return text.replace("                artifacts = ::reLabArtifacts,\n", "", 1)


case("ALL 提前到 object 开头", all_too_early)
case("RE_LAB_DEBIAN_STEPS 早于 RE_LAB_PIP_STEPS", steps_declared_late)
case("RE_LAB 忘记挂 artifacts", artifacts_removed)


def main() -> int:
    original = SRC.read_text(encoding="utf-8")
    failures = []
    with tempfile.TemporaryDirectory() as tmp:
        tmp_root = Path(tmp)
        target = tmp_root / "app/src/main/kotlin/io/github/mangi/eta/agent/terminal"
        target.mkdir(parents=True)
        shutil.copy(CHECKER, tmp_root / "ci_check.py")
        for name, mutate in CASES:
            mutated = mutate(original)
            if mutated == original:
                failures.append(f"{name}: 变异未生效，无法验证")
                continue
            (target / "LinuxPackageProfiles.kt").write_text(mutated, encoding="utf-8")
            proc = subprocess.run(
                [sys.executable, str(tmp_root / "ci_check.py")],
                cwd=tmp_root,
                capture_output=True,
                text=True,
            )
            if proc.returncode == 0:
                failures.append(f"{name}: 校验器没报错（漏检）")
            else:
                print(f"OK  {name} → 正确报 FAIL")

    # 原始文件必须是 PASS，否则说明校验器本身太严，会阻塞正常提交。
    proc = subprocess.run(
        [sys.executable, str(CHECKER)], cwd=ROOT, capture_output=True, text=True
    )
    if proc.returncode != 0:
        failures.append("当前源码被判定为 FAIL，但它是正确的：" + proc.stdout.strip())

    if failures:
        print("FAIL:")
        for item in failures:
            print("  -", item)
        return 1
    print("PASS: 三种事故全部被拦住，当前源码通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())