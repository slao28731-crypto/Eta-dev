#!/usr/bin/env bash
# 一键安装仓库的 git hooks。
# 新机器 clone 后跑一次：bash scripts/install-hooks.sh
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

src="scripts/hooks/pre-commit"
dst=".git/hooks/pre-commit"

if [ ! -f "$src" ]; then
  echo "找不到 $src"
  exit 1
fi

cp "$src" "$dst"
chmod +x "$dst"
echo "已安装 pre-commit hook -> $dst"
echo "作用：提交涉及 .kt/.java 源码时自动跑 graphify update ."
