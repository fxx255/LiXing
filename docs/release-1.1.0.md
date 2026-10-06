# 1.1.0 发布记录

- 版本：1.1.0 / versionCode 56；包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64；数据库版本不变。
- 发布源码提交：`d1af176db933aa7257ccc5972963510ebc5f1e4e`，已推送至 `main`；远端 `v1.1.0` 精确指向该提交，APK 内的版本控制元数据也指向同一提交。
- [GitHub Release](https://github.com/fxx255/LiXing/releases/tag/v1.1.0) 已公开并成为 latest，ID `404303715`，发布时间为 2026-10-06 12:12:54（北京时间）。
- [APK](https://github.com/fxx255/LiXing/releases/download/v1.1.0/LiXing-1.1.0-arm64.apk)：45,587,216 字节，SHA-256 `4c64909ca1ff64dadfc55e57cc0dc6d64ba432cf1489fbcc8ae069c117e778f6`，与 GitHub 资产独立 digest 一致。
- 签名证书 SHA-256 `9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与 1.0.54 一致，可覆盖安装。
- [update.json](https://github.com/fxx255/LiXing/releases/download/v1.1.0/update.json)：1,462 字节，SHA-256 `c3883bb5c2260dce3c10e8861c6700c5b40e5483cd14a4588f4dd8e561f52976`，与 GitHub 资产独立 digest 一致；`force=false`。R8 mapping 已归档至本机被忽略的 `docs/mappings/mapping-v1.1.0.txt`。

## 验证

- JDK 21 下执行 `:app:testDebugUnitTest :app:assembleRelease :app:lintVitalRelease` 成功；138 个测试类、1,152 项测试通过，0 失败、0 错误、0 跳过。
- 构建源码逐文件校验保持一致；正式包内元数据确认版本 1.1.0 / 56、原包名、minSdk 26、targetSdk 35、仅 `arm64-v8a`。
- 使用应用实际支持的 `ghfast.top` 下载源取得公开 latest 清单及完整 APK，重新校验大小、SHA-256 和 APK 签名，与本机构建及 GitHub 独立 digest 一致。
- 本机直接请求 GitHub APK 地址时 HEAD 连接超时，不能声称本机直连下载已验收；应用使用的镜像下载链路已验收。
- 公开 `/update/check?versionCode=55` 返回 `ok=true`、1.1.0 / 56、`cached=false`；版本、APK 地址、大小、SHA-256 与 `force` 均与正式清单一致，无需修改云函数。现有云服务省略可选 `minSdk` 字段，客户端沿用兼容默认值；APK 最低版本要求仍为 26。
- 本次未进行真机或模拟器安装、音频试听验收；联网发音地址已实测返回英式、美式单词及常用短语音频。句子朗读仍依赖系统已安装语音包。

## Release 轮转

- 新版 APK、update.json、公开下载链路和 latest 验证通过后，按 `published_at` 从新到旧保留最近 5 个已发布版本：`v1.1.0`、`v1.0.54`、`v1.0.53`、`v1.0.52`、`v1.0.51`。
- 仅删除超出的 `v1.0.50` Release（ID `398004685`）及附件；保留其 Git 标签、分支和源码提交。原有 `v1.0.22` 草稿不参与轮转，保持不变。
- 清理前在本机 `build/release-1.1.0/` 保存完整 Release 元数据、latest、原始标签列表与清理计划；清理后核对保留列表、草稿、latest 及所有原始标签均通过，`cleanup-result.json` 的 `complete` 为 `true`。

## 发布范围

- 包含英语悬浮按钮左滑评分、三色圆环及免费有道英美发音与缓存。
- 包含 AI 模型上下文窗口配置、完整请求预算与占用圆环，以及相关持久化和回归测试。
- 本地图片样本、签名备份、构建校验资料和 R8 mapping 均未提交至源码仓库；Release 仅包含 APK 与更新清单两项附件。
