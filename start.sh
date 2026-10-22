#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "========================================"
echo "  布莱克 Black Launcher"
echo "========================================"
echo

if ! command -v java >/dev/null 2>&1; then
  echo "[错误] 未检测到 Java，请先安装 JDK 17 或更高版本。"
  echo "下载地址: https://adoptium.net/"
  exit 1
fi

if [[ ! -f "./gradlew" ]]; then
  echo "[错误] 找不到 gradlew，请确认脚本位于项目根目录。"
  exit 1
fi

chmod +x ./gradlew 2>/dev/null || true
echo "正在启动..."
./gradlew run --quiet
