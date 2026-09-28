# 1.0.50 发布记录

- 版本：1.0.50 / versionCode 51，包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 发布提交：`1e1966d0a8df8377aeede0d6bd89e45b73d17139`；远端标签 `v1.0.50` 指向该提交。
- GitHub Release：[v1.0.50](https://github.com/fxx255/LiXing/releases/tag/v1.0.50)，ID `398004685`，已发布并切换为 latest。
- APK：45,439,728 字节；SHA-256 `8c19c365ce25a6358554342a29ae533b7d24daa2a81ebbdd742fbccc07a96e6c`。GitHub 资产独立 digest 一致。
- 签名证书 SHA-256：`9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与上一版一致。R8 mapping 已保存到本机 `docs/mappings/mapping-v1.0.50.txt`（按仓库规则不入 Git）。

## 验证

- JDK 21 的 `:app:assembleRelease`、R8 与 `lintVitalRelease` 通过；APK 元数据确认 versionCode 51、versionName 1.0.50、仅含 arm64-v8a。
- 全量单测 1045 项中 1044 项通过，1 项在测试开始前收到 Robolectric 后台 SQLite 连接异常；其所在的 `AssistantViewModelStoredMergeTest` 测试类隔离复跑 33 项全部通过。
- 公开 `releases/latest/download/update.json` 和云函数 `/update/check?versionCode=50` 均返回 1.0.50 / 51、正确 APK 地址与 SHA-256。
- 本次没有连接 Android 真机，滚动显示修复仍需设备复验。
