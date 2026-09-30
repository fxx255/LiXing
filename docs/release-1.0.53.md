# 1.0.53 发布记录

- 版本：1.0.53 / versionCode 54；包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 发布源码提交及标签：`4e1ba5138159e98743aa6e095741c221f58a2682`，远端 `v1.0.53` 精确指向该提交。
- [GitHub Release](https://github.com/fxx255/LiXing/releases/tag/v1.0.53) 已公开并成为 latest，ID `399727381`。
- [APK](https://github.com/fxx255/LiXing/releases/download/v1.0.53/LiXing-1.0.53-arm64.apk)：45,554,448 字节，SHA-256 `eb777bab15cf914a8e9f15875278c8deef66c02c1be1e88baeb94a7f6e17d368`，与 GitHub 资产独立 digest 一致。签名证书 SHA-256 `9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与 v1.0.52 一致。
- [update.json](https://github.com/fxx255/LiXing/releases/download/v1.0.53/update.json)：1,061 字节，SHA-256 `704b4cdf9744717d823166b87ce8a7a4c353659abd27f960088c624f4b8899b2`，与 GitHub 资产独立 digest 一致；`force=false`。R8 mapping 保存在本机被忽略的 `docs/mappings/mapping-v1.0.53.txt`。

## 验证

- JDK 21 下 `:app:testDebugUnitTest :app:assembleRelease :app:lintVitalRelease` 成功；1,090 项单元测试通过，0 失败、0 错误、0 跳过。
- APK 元数据确认 versionCode 54、versionName 1.0.53、minSdk 26、targetSdk 35、仅 `arm64-v8a`；签名与上版一致。
- 公开 `releases/latest/download/update.json` 返回 1.0.53 / 54、正确 APK 地址、大小与 SHA-256。公开 `/update/check?versionCode=53` 返回 `ok=true`、1.0.53 / 54，且地址、大小与 SHA-256 均与清单一致，未使用旧缓存或兜底。
- 本次未进行真机或模拟器安装验收。
