#!/usr/bin/env bash
# 带自动重试的资产上传（因为这条链路会随机失败）。
#
# 为什么要手写循环：`curl --retry` 默认**只重试 GET/HEAD**，POST 不重试；
# 要它重试 POST 得配 `--retry-all-errors`（curl 7.71+）。而且实测这条
# uploads.github.com 链路会以两种方式失败：
#   1. 数据全部发完但服务端 504 网关超时（数据被丢弃）
#   2. 传了一半连接中断（curl 报 HTTP=100，size_upload 明显小于文件大小）
# 所以这里做「失败 → 检查是否其实已落库 → 没落就再来一次」的循环。
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

GH_BIN="C:/Users/17611/.workbuddy/tools/gh/bin/gh.exe"
export APPDATA="C:/Users/17611/AppData/Roaming"

RELEASE_ID="${1:?用法: upload-with-retry.sh <releaseId> <assetPath> <assetName>}"
ASSET_PATH="${2:?}"
ASSET_NAME="${3:?}"
MAX_TRIES="${4:-6}"

TOKEN=$("$GH_BIN" auth token)
[ -n "$TOKEN" ] || { echo "拿不到 token" >&2; exit 1; }
[ -f "$ASSET_PATH" ] || { echo "找不到 $ASSET_PATH" >&2; exit 1; }

SIZE=$(stat -c %s "$ASSET_PATH")
echo "==> 上传 $ASSET_NAME（$SIZE 字节）到 release $RELEASE_ID"

asset_present() {
  "$GH_BIN" api "repos/fxx255/LiXing/releases/$RELEASE_ID" \
    --jq ".assets[] | select(.name==\"$ASSET_NAME\") | .size" 2>/dev/null | tr -d '[:space:]'
}

for i in $(seq 1 "$MAX_TRIES"); do
  echo "=== 尝试 $i / $MAX_TRIES ==="
  CODE=$(curl -sS --noproxy '*' -X POST \
    -H "Authorization: token $TOKEN" \
    -H "Content-Type: application/octet-stream" \
    --data-binary "@$ASSET_PATH" \
    "https://uploads.github.com/repos/fxx255/LiXing/releases/$RELEASE_ID/assets?name=$ASSET_NAME" \
    -w "%{http_code}" -o /tmp/upload_try.json 2>/dev/null || echo "000")
  echo "    HTTP=$CODE"

  if [ "$CODE" = "201" ]; then
    echo "==> 上传成功"
    exit 0
  fi

  # 504 之类的响应可能实际已落库（GitHub 处理完了但响应超时），先核对再决定重试
  PRESENT=$(asset_present)
  if [ -n "$PRESENT" ]; then
    echo "==> 服务端其实已收到该资产（size=$PRESENT），视为成功"
    exit 0
  fi

  echo "    资产未落库，${i} 次失败"
  if [ "$i" -lt "$MAX_TRIES" ]; then
    sleep 15
  fi
done

echo "==> $MAX_TRIES 次尝试后仍失败" >&2
exit 1
