#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成 update.json 版本清单。

为什么单独抽成脚本：release.sh 里用 printf + python -c 拼 JSON，一旦 changelog
含引号/换行/反斜杠就容易写出非法 JSON，而清单不合法会让云函数与 App 的检查更新
**全线失效**（v1.0.20 踩过）。这里统一用 json.dumps 转义，并顺带做一致性校验。

用法：
    python docs/make-update-json.py <apk路径> <versionName> <versionCode> <minSdk> <changelog文件>
"""
import hashlib
import json
import os
import sys

REPO = "fxx255/LiXing"
# ⚠️ apkUrl 必须是干净的 GitHub 直链，绝不能带镜像前缀：
# 客户端自己会在镜像列表上逐层拼接，清单里若已带前缀就会被拼成
# ghfast.top/https://ghfast.top/https://github.com/... ⇒ 403。
# 症状：「检查更新正常、下载必定失败」。
MIRROR = ""


def main() -> int:
    if len(sys.argv) != 6:
        print(__doc__, file=sys.stderr)
        return 2
    apk, version_name, version_code, min_sdk, changelog_file = sys.argv[1:6]

    if not os.path.isfile(apk):
        print(f"错误：找不到 APK {apk}", file=sys.stderr)
        return 1

    size = os.path.getsize(apk)
    sha = hashlib.sha256()
    with open(apk, "rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            sha.update(chunk)
    sha256 = sha.hexdigest()

    with open(changelog_file, encoding="utf-8") as f:
        changelog = f.read()

    apk_name = f"LiXing-{version_name}-arm64.apk"
    manifest = {
        "versionCode": int(version_code),
        "versionName": version_name,
        "apkUrl": f"{MIRROR}https://github.com/{REPO}/releases/download/v{version_name}/{apk_name}",
        "sha256": sha256,
        "sizeBytes": size,
        "changelog": changelog,
        "minSdk": int(min_sdk),
        "force": False,
    }

    out = "build/update.json"
    os.makedirs("build", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    # 写回读一遍：确认落盘的确实是合法 JSON（防止写入被截断）
    with open(out, encoding="utf-8") as f:
        reloaded = json.load(f)
    assert reloaded["versionCode"] == int(version_code), "versionCode 回读不一致"
    assert reloaded["sha256"] == sha256, "sha256 回读不一致"
    assert reloaded["apkUrl"].startswith("https://github.com/"), "apkUrl 必须是不带镜像前缀的直链"

    print(f"==> 已生成 {out}")
    print(f"    versionCode = {version_code}")
    print(f"    versionName = {version_name}")
    print(f"    sizeBytes   = {size}")
    print(f"    sha256      = {sha256}")
    print(f"    apkUrl      = {reloaded['apkUrl']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
