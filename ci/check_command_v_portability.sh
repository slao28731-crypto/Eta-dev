#!/bin/sh
# 对照：dash 的 command -v 是否只处理第一个操作数
echo '--- dash: command -v 多参数 ---'
dash -c 'command -v sh ls nosuch_cmd_xyz; echo "rc=$?"'
echo '--- bash: command -v 多参数 ---'
bash -c 'command -v sh ls nosuch_cmd_xyz; echo "rc=$?"'
echo '--- 循环写法（两种 shell 都应列全）---'
for t in sh ls nosuch_cmd_xyz; do
  printf '%-16s %s\n' "$t" "$(command -v "$t" 2>/dev/null || echo MISSING)"
done