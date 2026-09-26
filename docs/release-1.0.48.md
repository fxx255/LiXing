# 1.0.48 发布记录

- 版本：1.0.48 / versionCode 49，包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 发布提交：`d70e8a78e4b53b07b2db55b3d85390c686ec4f98`；远端标签 `v1.0.48` 精确指向该提交。
- GitHub Release：[`v1.0.48`](https://github.com/fxx255/LiXing/releases/tag/v1.0.48)，ID `397216495`，已发布且为 latest。
- APK：45,423,344 字节；SHA-256 `c7565eadaa4d1ec28e975e60c6d492e5417bff7cf97614fa474d203630793145`。GitHub 资产独立 digest 一致。
- 签名证书 SHA-256：`9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与旧版一致。R8 mapping 已存于本地 `docs/mappings/mapping-v1.0.48.txt`。

## 验证

- JDK 21 的 Debug、DebugAndroidTest 和 Release 构建成功；Release 的 R8 和 lintVital 通过。
- 教材框图及助手状态定向单测通过；真实 Android Canvas 样图已核对浅色、深色和 C 点水平走线。
- 全量单测共 1028 项，其中 1027 项通过；`AssistantViewModelStoredMergeTest` 中 1 项在测试开始前收到 Robolectric 后台 SQLite 连接异常，该测试类隔离复跑通过。Debug lint 分析测试源码时遇到 Kotlin FIR 内部异常；Release lintVital 正常。
- 当前 MuMu 设备 ADB 端口未启动，因此未在本次最终 APK 上复跑设备测试；先前生成样图使用了真实 Android Canvas。

## 更新服务

- 公开 `releases/latest/download/update.json` 返回 1.0.48 / 49，APK 地址、大小和 SHA-256 均与发布资产一致，`minSdk=26`、`force=false`。
- 公开 `/update/check` 在 `versionCode=48` 和 `49` 两种请求下均返回 `ok=true`、1.0.48 / 49、正确的 APK 地址与 SHA-256，未使用旧版兜底清单。
