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
# ⚠️ 清单里的 apkUrl 必须是**干净的 GitHub 直链**，绝不能带镜像前缀：
# 客户端 DownloadMirrors 会在自己的镜像列表上逐个拼接重试，清单里若已带一层
# 前缀，客户端再拼一层就变成 `ghfast.top/https://ghfast.top/https://github.com/...`
# ⇒ 服务端直接 403。症状是「检查更新正常、但下载必定失败」，很难查。
# 镜像前缀只由客户端负责，这里恒为空。
MIRROR=""

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
# ⚠️ 必须用 JDK 21：JDK 25 会让 JLatexMath 的 TeXFormula 静态初始化失败
# （测试侧表现为 8 项假红，且失败是粘性的），构建侧行为也不保证一致。
export JAVA_HOME="${JAVA_HOME:-D:/tools/jdk-21.0.12+8}"
echo "    JAVA_HOME=$JAVA_HOME"
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

# ⚠️ 284MB 资产**绝不能**用 `gh release create` / `gh release upload` 直接传：
# 实测会卡死（>15min 无进展）。正确做法是先建一个**不带资产的 draft**，
# 拿到 id 后再用 curl 直传（实测 6~20min，HTTP 201）。
"$GH_BIN" release create "v${VERSION_NAME}" \
  --repo "$REPO" \
  --draft \
  --title "砺行 ${VERSION_NAME}" \
  --notes "$CHANGELOG"

# draft 的 git tag 尚未创建，按 tag 查 id 会 404 ⇒ 只能在列表里按 tag_name 找
RELEASE_ID=$("$GH_BIN" api "repos/${REPO}/releases?per_page=20" \
  --jq ".[] | select(.tag_name==\"v${VERSION_NAME}\") | .id" 2>/dev/null || true)
if [ -z "$RELEASE_ID" ]; then
  echo "错误：找不到 v${VERSION_NAME} 的 release id" >&2
  exit 1
fi
echo "==> release id = ${RELEASE_ID}"

TOKEN=$("$GH_BIN" auth token)
echo "==> 上传资产（curl 直传）"
for ASSET in "${APK_NAME}" update.json; do
  curl --fail -sS --noproxy '*' -X POST \
    -H "Authorization: token ${TOKEN}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@build/${ASSET}" \
    "https://uploads.github.com/repos/${REPO}/releases/${RELEASE_ID}/assets?name=${ASSET}" \
    -o /dev/null -w "    ${ASSET} → HTTP=%{http_code}\n" \
    || { echo "错误：${ASSET} 上传失败" >&2; exit 1; }
done

echo "==> 校验资产（GitHub 独立算出的 digest 必须等于本地 sha256）"
"$GH_BIN" api "repos/${REPO}/releases/${RELEASE_ID}" \
  --jq '.assets[] | "    \(.name)  \(.size)  \(.state)  \(.digest)"'
if ! "$GH_BIN" api "repos/${REPO}/releases/${RELEASE_ID}" \
      --jq -r '.assets[] | select(.name=="'"${APK_NAME}"'") | .digest' \
      | grep -qx "sha256:${SHA256}"; then
  echo "错误：GitHub 算出的 sha256 与本地 ${SHA256} 不一致，已中止发布" >&2
  exit 1
fi
echo "    sha256 一致 ✓"

echo "==> 关闭 draft（**必须 JSON 体**：`gh api -f draft=false` 是空操作）"
# `-f` 发的是 form 编码，GitHub 对 draft/prerelease 这类布尔字段要求 JSON 体，
# form 形式会被静默忽略（HTTP 200 但字段不变）⇒ latest 指针不切 ⇒ 云函数一直返回旧版。
curl -sS -X PATCH -H "Authorization: token ${TOKEN}" \
  -H "Content-Type: application/json" -d '{"draft":true}' \
  "https://api.github.com/repos/${REPO}/releases/${RELEASE_ID}" -o /dev/null
sleep 3
curl -sS -X PATCH -H "Authorization: token ${TOKEN}" \
  -H "Content-Type: application/json" -d '{"draft":false}' \
  "https://api.github.com/repos/${REPO}/releases/${RELEASE_ID}" -o /dev/null
sleep 3

# 🔴 **`--draft` 创建的 release 不会创建 git tag**，之后关闭 draft 也**不会补建**。
# 而且实测更麻烦：**PATCH `draft:false` 会把 tag 关联重置成 `untagged-<sha>`**
# ——即使事前已经 `git tag` 并 push 过（v1.0.40 实测：建 release 时 tag_name 正确，
# 关完 draft 就变成 untagged-105bd2d5...）。
# 后果：资产的下载 URL 变成 `.../download/untagged-<sha>/xxx.apk`，而清单里的 apkUrl
# 写的是 `.../download/v<版本>/xxx.apk` ⇒ **检查更新正常、下载必定 404**。
# 之所以隐蔽：`releases/latest/download/` 仍能取到清单，云函数与「检查更新」全是好的，
# **只有真正下载时才炸**。
# ⇒ 所以「补建 tag + PATCH tag_name」必须放在**关闭 draft 之后**，作为最后一步。
echo "==> 补建 git tag v${VERSION_NAME}（必须在关闭 draft 之后）"
git tag -f "v${VERSION_NAME}" "$(git rev-parse HEAD)"
git push -f origin "v${VERSION_NAME}"
curl -sS -X PATCH -H "Authorization: token ${TOKEN}" \
  -H "Content-Type: application/json" -d "{\"tag_name\":\"v${VERSION_NAME}\"}" \
  "https://api.github.com/repos/${REPO}/releases/${RELEASE_ID}" -o /dev/null
sleep 4

echo "==> 校验资产下载 URL 指向正确的 tag"
"$GH_BIN" api "repos/${REPO}/releases/${RELEASE_ID}" \
  --jq -r '.assets[].browser_download_url' | tee /tmp/_release_urls.txt
if ! grep -q "/download/v${VERSION_NAME}/" /tmp/_release_urls.txt; then
  echo "错误：下载 URL 未包含 v${VERSION_NAME}，清单里的 apkUrl 会 404" >&2
  exit 1
fi

# ⚠️ 发布后必须确认 GitHub 的 latest 指针真的切到新版本。
# 云函数的 UPDATE_MANIFEST_URL 指向 `…/releases/latest/download/update.json`，
# 而 `latest` 由 GitHub 内部按 published_at 维护 —— 若它没切过来，App 的
# 检查更新会一直拿到旧清单（症状：release 页明明有新版，App 却说已是最新）。
#
# 注意：`gh api -X PATCH -f draft=false` 是**空操作**！`-f` 发的是 form 编码，
# GitHub 对 draft/prerelease 这类布尔字段要求 JSON 体，form 形式会被静默忽略
# （HTTP 200 但字段不变），所以必须用 curl + JSON 体。
LATEST=$("$GH_BIN" api "repos/${REPO}/releases/latest" --jq '.tag_name' 2>/dev/null || echo "unknown")
if [ "$LATEST" != "v${VERSION_NAME}" ]; then
  echo "⚠️  latest 指针仍指向 ${LATEST}，用 JSON 体强制重发一次…"
  RELEASE_ID=$("$GH_BIN" api "repos/${REPO}/releases?per_page=20" \
    --jq ".[] | select(.tag_name==\"v${VERSION_NAME}\") | .id" 2>/dev/null || true)
  if [ -z "$RELEASE_ID" ]; then
    echo "错误：找不到 v${VERSION_NAME} 的 release id" >&2
    exit 1
  fi
  TOKEN=$("$GH_BIN" auth token)
  for BODY in '{"draft":true}' '{"draft":false}'; do
    curl -sS -X PATCH \
      -H "Authorization: token ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "$BODY" \
      "https://api.github.com/repos/${REPO}/releases/${RELEASE_ID}" >/dev/null || true
    sleep 5
  done
  sleep 5
  LATEST=$("$GH_BIN" api "repos/${REPO}/releases/latest" --jq '.tag_name' 2>/dev/null || echo "unknown")
fi
if [ "$LATEST" = "v${VERSION_NAME}" ]; then
  echo "==> latest 指针已切到 v${VERSION_NAME}"
else
  echo "错误：latest 指针仍为 ${LATEST}，云函数会返回旧版本，请到 GitHub 网页端手动 Republish" >&2
  exit 1
fi

echo "==> 完成。别忘了把 update.json 的直链配置到 Cloudbase 云函数的 UPDATE_MANIFEST_URL："
echo "    https://github.com/${REPO}/releases/download/v${VERSION_NAME}/update.json"
