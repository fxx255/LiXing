# 1.0.49 发布记录

- 版本：1.0.49 / versionCode 50，包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 发布提交：`fca2835`；远端标签 `v1.0.49` 指向该提交。
- GitHub Release：[v1.0.49](https://github.com/fxx255/LiXing/releases/tag/v1.0.49)，已发布并切换为 latest。
- APK：45,439,728 字节；SHA-256 `1901ca47f5230bec8521916c1842e4dce48613ed4500270cca9398c6a6de48bb`。
- `update.json` 已验证为 versionCode 50、versionName 1.0.49，下载地址与 APK 校验值一致。
- R8 mapping 已保存到本机 `docs/mappings/mapping-v1.0.49.txt`（该目录按仓库规则不入 Git）。

## 验证

- `:app:testDebugUnitTest --tests com.example.lixing.domain.diagram.IqDemodulatorLayoutTest` 通过。
- `:app:testDebugUnitTest --tests com.example.lixing.domain.diagram.* :app:assembleDebug` 通过。
- JDK 21 的 `:app:assembleRelease`、R8 与 `lintVitalRelease` 通过。
- 远端 `latest` 指向 `v1.0.49`，发布资产已上传且 GitHub 返回的 APK digest 与本地 SHA-256 一致。
