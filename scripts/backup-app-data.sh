#!/usr/bin/env bash
# 一键备份 Eta App 的本地数据（聊天记录、Provider 配置、角色卡、记忆、数据库）。
#
# 用法：
#   bash scripts/backup-app-data.sh              # 备份到 /sdcard/eta-backup/<时间戳>
#   bash scripts/backup-app-data.sh /mnt/backup  # 指定输出目录
#
# 说明：
#   - 有 Root：直接拷 /data/data/io.github.mangi.eta 下的 databases / shared_prefs / files
#   - 无 Root：只能拷 /sdcard 上 App 自己导出的文件（需先在 App 设置里导出）
set -u

PKG="io.github.mangi.eta"
SRC="/data/data/$PKG"
STAMP="$(date +%Y%m%d-%H%M%S)"
DEST_ROOT="${1:-/sdcard/eta-backup}"
DEST="$DEST_ROOT/$STAMP"

echo "== Eta 数据备份 =="
echo "包名: $PKG"
echo "目标: $DEST"

# 判断权限
if [ -r "$SRC" ] && [ -d "$SRC/databases" ]; then
  MODE="root"
else
  MODE="user"
fi
echo "权限模式: $MODE"
echo

mkdir -p "$DEST"

if [ "$MODE" = "root" ]; then
  # databases 最关键：聊天记录、记忆、角色卡都在这里
  for sub in databases shared_prefs files; do
    if [ -d "$SRC/$sub" ]; then
      echo "  拷贝 $sub ..."
      cp -a "$SRC/$sub" "$DEST/" 2>/dev/null || echo "    (部分文件无权限，已尽力)"
    fi
  done

  # 记录 App 版本，方便以后判断能不能回滚
  VERSION=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d ' ')
  VERSION_CODE=$(dumpsys package "$PKG" 2>/dev/null | grep -m1 versionCode | tr -d ' ')
  {
    echo "package=$PKG"
    echo "backup_at=$STAMP"
    echo "$VERSION"
    echo "$VERSION_CODE"
    echo "db_version=$(sqlite3 "$DEST/databases/eta.db" 'PRAGMA user_version;' 2>/dev/null || echo unknown)"
  } > "$DEST/BACKUP_INFO.txt"
else
  # 无 Root：从 sdcard 上找 App 自己导出的数据文件
  # 只认明确的导出命名（eta-backup / eta-export / 带 .eta 或 .json 备份后缀），
  # 不要按 "eta" 模糊匹配，否则会误抓 eta-apk.zip 之类的构建产物。
  echo "  无 Root 权限，尝试从 /sdcard 收集已导出的数据文件..."
  found=0
  for d in /sdcard/Download /sdcard/Documents /sdcard/eta-export; do
    [ -d "$d" ] || continue
    while IFS= read -r f; do
      [ -f "$f" ] || continue
      cp "$f" "$DEST/" 2>/dev/null && found=1 && echo "    收集 $(basename "$f")"
    done < <(find "$d" -maxdepth 1 -type f \
               \( -iname 'eta-backup*.json' -o -iname 'eta-backup*.zip' \
                  -o -iname 'eta-export*' -o -iname '*-eta-backup.*' \) 2>/dev/null)
  done
  if [ "$found" = "0" ]; then
    echo "  没有找到 App 导出的数据文件。"
    echo "  请在 App 里操作：设置 → 数据管理 → 导出，导出到 /sdcard/Download 后再跑本脚本。"
    echo "  （若有 Root，直接用 su 跑本脚本可自动备份 databases / shared_prefs / files）"
  fi
  echo "backup_at=$STAMP" > "$DEST/BACKUP_INFO.txt"
fi

SIZE=$(du -sh "$DEST" 2>/dev/null | cut -f1)
echo
echo "完成。大小: $SIZE"
echo "路径: $DEST"
echo
echo "恢复方式（有 Root）："
echo "  su -c 'cp -a $DEST/databases/. /data/data/$PKG/databases/'"
echo "  su -c 'chown -R $(stat -c %u:%g /data/data/$PKG) /data/data/$PKG/databases'"
echo "  然后重启 App"