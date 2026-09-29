# 1.0.52 发布记录

- 版本：1.0.52 / versionCode 53；包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 发布提交及标签：`6197cd8c5827f243cb5f9fcda5146ad969f0682e`，远端 `v1.0.52` 精确指向该提交。
- [GitHub Release](https://github.com/fxx255/LiXing/releases/tag/v1.0.52) 已公开并成为 latest，ID `399298211`。
- [APK](https://github.com/fxx255/LiXing/releases/download/v1.0.52/LiXing-1.0.52-arm64.apk)：45,554,448 字节，SHA-256 `1d770ca222d7bdf3741885b109c22b87fc330662f0c9ecda0d7ca4f1fb6d0deb`，与 GitHub 资产独立 digest 一致。签名证书 SHA-256 `9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，延续前版签名。R8 mapping 保存在本机被忽略的 `docs/mappings/mapping-v1.0.52.txt`。
- [update.json](https://github.com/fxx255/LiXing/releases/download/v1.0.52/update.json) 已公开；`releases/latest/download/update.json` 返回 versionCode 53、正确的 APK 地址、大小与 SHA-256，`force=false`。

## 验证

- JDK 21 下完整 1073 项单元测试通过，0 失败、0 错误、0 跳过；Debug、DebugAndroidTest 及 Release 构建成功，R8 与 lintVitalRelease 通过。
- MuMu 12-2 上 Debug 包完成 Room v14→v15 升级、HTTP 模型列表与流式回答测试，以及具体日期任务和旧周期任务并存的界面冒烟。正式 ARM64 包未在 MuMu 12-2 上安装验收。
- GitHub APK 直链的本机 HEAD 请求超时；`ghfast.top` 镜像返回 HTTP 206，取得前 1024 字节，文件头为 `PK\x03\x04`。此处只验证下载入口，完整资产一致性由 GitHub digest 与本地 SHA-256 对照确认。
- 公开 `/update/check?versionCode=52` 返回 `ok=true`、1.0.52 / 53，APK 地址、大小与 SHA-256 均与清单一致。第一次请求短暂返回 502，随后重试成功。

## 使用边界

- 同步格式升至 2、数据库升至 15，参与同步的设备需全部升级。并发修改同一天安排时，本机记录与冲突提示会保留；跨设备冲突的一键合并、撤销后的解决批次、长期计划自动顺延和多设备真实同步验收仍待后续版本。
- 用户配置 HTTP 模型地址时，请求正文与 API 密钥以明文传输。
