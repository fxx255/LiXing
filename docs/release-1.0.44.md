# 1.0.44 发布记录

- 版本：1.0.44 / versionCode 45，包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 本版包含：助手正文流式显示与思考分离、中断重试、会话草稿、通信框图、长公式修复、请求时间排序修复，以及坚果云多端同步修复。详见 CHANGELOG 与发布说明。
- 验收（发布前，协调者复核）：完整单元测试 994 项通过（0 失败 / 0 错误 / 0 跳过）；串行 testDebugUnitTest、assembleDebug、assembleDebugAndroidTest、assembleRelease、lintDebug 全部成功（lint 0 errors / 72 warnings / 4 hints）。
- MuMu 12-1 设备测试通过：草稿 2 项、助手 UI 12 项、runtime 3 项（真实触摸点击重试、ID 不重复、完成前流式可见、切页/后台/锁屏恢复、图文与方案/英语恢复、防重复确认）、重启跨进程 prepare→force-stop→recover 通过，重开不自动发请求。
- 实际 Release 包覆盖安装并冷启动成功，无启动崩溃；验收后设备已精确恢复原 1.0.43 APK 与数据（27 文件逐一核对），网络隔离/reverse 撤销、fake 服务停止、adb unroot。
- 最终发布 APK：SHA-256 `c16902867d343d95204a5c2bae9dd54b955797b942e165ce9a409844fab04dba`，大小 45,406,904 字节；发布前逐一复核全部源码指纹（release-evidence.json）与 APK 哈希一致。
- 签名证书 SHA-256：`9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与 1.0.43 一致。
- R8 mapping 已归档至本机 `docs/mappings/mapping-v1.0.44.txt`（Git 忽略）。
- 数据库增量升级至 14，不清空数据；临时请求记录与凭证快照不参与跨端同步。
- GitHub Release 与云端核验结果见下方补充（发布执行时填写）。
- GitHub Release：标签 `v1.0.44` 精确指向发布提交 `8f5ae64520d693983403d28d2b646ad8984a5db3`（与 main 一致，无标签覆盖）。draft 创建于 `--verify-tag`，上传两资产后以 `gh release edit --draft=false --latest` 一次发布（无 draft 反复开关），已确认为仓库 latest。
- 资产与 digest（GitHub 独立计算，与本地一致）：
  - `LiXing-1.0.44-arm64.apk`：45,406,904 字节，`sha256:c16902867d343d95204a5c2bae9dd54b955797b942e165ce9a409844fab04dba`，
    [下载](https://github.com/fxx255/LiXing/releases/download/v1.0.44/LiXing-1.0.44-arm64.apk)
  - `update.json`：2,544 字节，`sha256:a681746a3901bddfa5cbd769a6a395be75b9d215b44329a70ca741d38e8ccc2d`，
    [下载](https://github.com/fxx255/LiXing/releases/download/v1.0.44/update.json)
- 清单 apkUrl 为干净 GitHub 直链（无镜像前缀），`force=false`，minSdk 26。
- 下载链路核验：GitHub 直链本机连接超时（直链下载未完成，如实记录）；经应用所用 `ghfast.top` 镜像完整下载 45,406,904 字节，SHA-256 与发布包一致（非仅页面/文件头验证）。
- 云端核验（只读请求公开 `/update/check`，未改动任何云配置/函数）：无参数、`versionCode=44`、`versionCode=45` 三场景均返回 `ok=true`、versionCode 45、versionName 1.0.44，apkUrl/SHA-256/sizeBytes 与清单一致，`force=false`，无 fallback；函数已自动从 latest 清单更新，本次未重新部署。响应 `cached=true`（函数缓存命中，内容已是新清单），等待 60 秒后有界重查结果不变。内联兜底 `UPDATE_MANIFEST_JSON` 未更新（无云管理登录），兜底滞后只会"检测不到更新"，不会误报。
