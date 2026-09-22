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
