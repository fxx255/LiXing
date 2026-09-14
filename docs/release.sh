#!/usr/bin/env bash
#
# 一键发布新版本（本地脚本；后续可换成 GitHub Actions）。
#
# 用法：
#   ./docs/release.sh 1.0.2 "• 修复公式占位框\n• 墨墨加入自动同步"
#
# 流程：
#   1. 打 release 包（release 构建复用项目内 debug.keystore，与已装版本同签名）
#   2. 计算 APK 的 SHA-256
#   3. 生成 update.json 版本清单
#   4. 用 gh CLI 创建 GitHub Release 并上传 APK 与清单
#
# 前置：已安装 gh 并完成 gh auth login；仓库已建好（GITHUB_REPO 环境变量或命令行第三个参数）。
set -euo pipefail

VERSION_NAME="${1:?用法: release.sh <versionName> [changelog] [user/repo]}"
CHANGELOG="${2:-更新到 ${VERSION_NAME}}"
REPO="${3:-${GITHUB_REPO:-fxx255/LiXing}}"
# 国内手机连不上 GitHub 的 release 附件 CDN，清单里的下载地址必须走加速镜像。
# App 端 DownloadMirrors 还会再自动回退另外两个源，这里只负责给个可用的首选。
MIRROR="${UPDATE_MIRROR:-https://ghfast.top/}"

cd "$(dirname "$0")/.."

# changelog 需要 JSON 转义（换行 / 引号 / 反斜杠），纯 bash 做不可靠，这里探测可用的 Python。
# 找不到就明确报错退出——绝不能写出 `"changelog": ,` 这种非法 JSON，
# 那会让云函数和 App 的检查更新整体失效（v1.0.20 发版时踩过一次）。
PYTHON_BIN="${PYTHON_BIN:-}"
if [ -z "$PYTHON_BIN" ]; then
  for candidate in python3 python py; do
    if command -v "$candidate" >/dev/null 2>&1; then
      PYTHON_BIN="$candidate"
      break
    fi
  done
fi
if [ -z "$PYTHON_BIN" ]; then
  echo "错误：未找到 python3 / python / py，无法安全生成 update.json" >&2
  exit 1
fi
echo "==> 使用 $PYTHON_BIN 生成清单"

# versionCode 必须与 BuildConfig.VERSION_CODE 完全一致：
# 早先用日期生成（260907 之类），会导致装完最新版仍被判定为「有新版本」。
VERSION_CODE=$(grep -m1 '^[[:space:]]*versionCode' app/build.gradle.kts | grep -o '[0-9]\+')
MIN_SDK=$(grep -m1 '^[[:space:]]*minSdk' app/build.gradle.kts | grep -o '[0-9]\+')
VERSION_NAME_IN_CODE=$(grep -m1 '^[[:space:]]*versionName' app/build.gradle.kts | grep -o '"[^"]*"' | tr -d '"')
if [ "$VERSION_NAME" != "$VERSION_NAME_IN_CODE" ]; then
  echo "警告：传入的 $VERSION_NAME 与 build.gradle.kts 里的 $VERSION_NAME_IN_CODE 不一致，以代码为准"
  VERSION_NAME="$VERSION_NAME_IN_CODE"
fi
echo "==> versionCode=$VERSION_CODE versionName=$VERSION_NAME minSdk=$MIN_SDK"

echo "==> 构建 release 包"
GRADLE_USER_HOME="F:/APP/.gradle-local" ./gradlew :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "未找到 $APK"; exit 1; }

echo "==> 计算 SHA-256"
SHA256=$(sha256sum "$APK" | awk '{print $1}')
if [ -z "$SHA256" ]; then
  SHA256=$(certutil -hashfile "$APK" SHA256 | sed -n 2p | tr -d ' ')
fi
SIZE=$(stat -c %s "$APK")

APK_NAME="LiXing-${VERSION_NAME}-arm64.apk"

echo "==> 生成 update.json"
cat > build/update.json <<JSON
{
  "versionCode": ${VERSION_CODE},
  "versionName": "${VERSION_NAME}",
  "apkUrl": "${MIRROR}https://github.com/${REPO}/releases/download/v${VERSION_NAME}/${APK_NAME}",
  "sha256": "${SHA256}",
  "sizeBytes": ${SIZE},
  "changelog": $(printf '%s' "$CHANGELOG" | "$PYTHON_BIN" -c 'import json,sys; print(json.dumps(sys.stdin.read()))'),
  "minSdk": ${MIN_SDK},
  "force": false
}
JSON

# 发布前强制校验：清单一旦不是合法 JSON，云函数与 App 的检查更新会全线失效
"$PYTHON_BIN" -c 'import json; json.load(open("build/update.json", encoding="utf-8"))' \
  || { echo "错误：build/update.json 不是合法 JSON，已中止发布" >&2; exit 1; }
echo "==> update.json JSON 校验通过"

cp "$APK" "build/${APK_NAME}"

echo "==> 归档 R8 mapping（线上崩溃日志反混淆必需，按版本号存 docs/mappings/）"
mkdir -p docs/mappings
cp app/build/outputs/mapping/release/mapping.txt "docs/mappings/mapping-v${VERSION_NAME}.txt"

echo "==> 创建 GitHub Release v${VERSION_NAME}"

# gh 可能不在 PATH（WorkBuddy 沙箱就找不到），允许 GH_BIN 显式指定，
# 否则探测默认安装位置（gh auth status 显示的真实路径）
GH_BIN="${GH_BIN:-}"
if [ -z "$GH_BIN" ]; then
  if command -v gh >/dev/null 2>&1; then
    GH_BIN="gh"
  elif [ -f "$LOCALAPPDATA/Programs/GitHub CLI/gh.exe" ]; then
    GH_BIN="$LOCALAPPDATA/Programs/GitHub CLI/gh.exe"
  elif [ -f "C:/Users/17611/.workbuddy/tools/gh/bin/gh.exe" ]; then
    GH_BIN="C:/Users/17611/.workbuddy/tools/gh/bin/gh.exe"
  else
    echo "错误：未找到 gh CLI（可设 GH_BIN=<gh路径> 后重试）" >&2
    exit 1
  fi
fi

"$GH_BIN" release create "v${VERSION_NAME}" \
  "build/${APK_NAME}" \
  "build/update.json" \
  --repo "$REPO" \
  --title "砺行 ${VERSION_NAME}" \
  --notes "$CHANGELOG"

echo "==> 完成。别忘了把 update.json 的直链配置到 Cloudbase 云函数的 UPDATE_MANIFEST_URL："
echo "    https://github.com/${REPO}/releases/download/v${VERSION_NAME}/update.json"
