# 1.0.54 发布记录

- 版本：1.0.54 / versionCode 55；包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64；数据库版本不变。
- 发布源码提交：`605488bae9b325a02c0d8ba9e1f85e3a400c00c2`，已推送至 `main`；远端 `v1.0.54` 精确指向该提交。
- [GitHub Release](https://github.com/fxx255/LiXing/releases/tag/v1.0.54) 已公开并成为 latest，ID `401737988`，发布时间为 2026-10-02 18:53:48（北京时间）。
- [APK](https://github.com/fxx255/LiXing/releases/download/v1.0.54/LiXing-1.0.54-arm64.apk)：45,570,828 字节，SHA-256 `021fad7e1263f809ea5753ba02133a84b79d9836526ff6e482f6678e1727be9c`，与 GitHub 资产独立 digest 一致。
- 签名证书 SHA-256 `9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与 1.0.53 一致，可覆盖安装。
- [update.json](https://github.com/fxx255/LiXing/releases/download/v1.0.54/update.json)：1,060 字节，SHA-256 `21f99f6111e0e9211ada3ff6af064869d9aa7468ae22f04672a7728c2470ffd1`，与 GitHub 资产独立 digest 一致；`force=false`。R8 mapping 已归档至本机被忽略的 `docs/mappings/mapping-v1.0.54.txt`。

## 验证

- JDK 21 下执行 `:app:testDebugUnitTest :app:assembleRelease :app:lintVitalRelease` 成功；1,103 项单元测试通过，0 失败、0 错误、0 跳过。
- 真实公式与多宽度 Markwon 链路通过；共享保护下 800 次并发公式构建及尺寸一致性检查通过。未保护的并发构建曾复现 `RowAtom.createBox` 内部空指针，不能将其归因于 `\Bigl` / `\Bigr` 不受支持。
- APK 元数据、签名与版本清单相互一致；源码提交前逐文件核对构建源码校验值，提交包含同一批修复、测试和版本设置，不包含本地图片样本。
- 通过应用使用的 `ghfast.top` 镜像下载公开 latest 清单与完整 APK，重新校验 SHA-256；下载后的 APK 签名与本机构建一致。
- 本机直连 GitHub 下载出现连接重置/连接超时，不能声称直连下载已验收；应用镜像下载链路已验收。
- 公开 `/update/check?versionCode=54` 返回 `ok=true`、1.0.54 / 55、`cached=false`；`versionCode=55` 返回同一清单、`cached=true`。两次响应的 APK 地址、大小、SHA-256 与 `force` 均与正式清单一致，无需修改云函数。
- 本次未进行真机或模拟器安装验收，公式裁切与思考状态仍需实际设备回归。

## 发布范围

- 包含公式并发保护、公式基线和流式布局调整，以及新问题临时思考状态清理。
- 本地音频播放器仅为路线图调整，本版未实现。
- 发版过程中临时上传的源码增量归档已移除，正式源码以发布提交及 `v1.0.54` 标签为准；Release 保留 APK 与版本清单两项资产。
