# 1.0.46 发布记录

- 版本：1.0.46 / versionCode 47，包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅发布 ARM64。
- 发布提交：`6f5299a`；标签 `v1.0.46` 已推送并精确指向该提交。
- GitHub Release：[`v1.0.46`](https://github.com/fxx255/LiXing/releases/tag/v1.0.46)，Release ID `395973716`，已发布且为 latest。

## 本版内容

- 助手占位终答“见上”不再作为成功正文保存；无正文时进入可重试失败路径。
- 思考面板保持在回答上方；思考期间隐藏空回答和占位气泡；流式回答复用同一文本视图，已闭合公式保持渲染，未闭合公式/表格保持稳定纯文本尾部。
- 通信框图采用固定的水平双支路教材布局，A～G 测试点定位在线段，宽图使用有限视口后横向滚动；备份恢复后的宽图也按尺寸识别。
- 图片拍摄与图库入口保持逆时针 90°旋转。
- 更新清单透传 `minSdk`，严格解析 `force`，并限制云函数回源总预算和公开错误信息。

## 验证

- JDK 21 定向单测：52 项通过（StreamingMarkdownTokenizer、AssistantContinuation、DiagramLayoutAcceptance、DiagramParser、UpdateManifest）。
- MuMu 12-1（Android 15，`127.0.0.1:16416`）：`DiagramDeviceTest` 6/6 通过，`AssistantAnswerDeviceTest` 2/2 通过。
- Debug、Release、DebugAndroidTest 均构建成功；Release 通过 R8 与 lintVital。
- Release APK：45,423,344 字节；SHA-256 `9eff492dbb88310f4653c36566499c328d83239a3b8818b2c59a13cdb0d8d4a6`。
- Release 签名证书 SHA-256：`9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`。
- GitHub 独立资产 digest 与本地 APK SHA-256 一致；公开 `update.json` 返回 versionCode 47、minSdk 26、force false。

## 设备数据恢复

发布前后均保留并核对了 MuMu 12-1 的原 1.0.43 APK 和数据备份；测试完成后已恢复，私有数据 13 项、外部数据 18 项的文件集合和哈希核对通过。ADB 已恢复非 root，reverse 映射为空。

## 云函数状态

本地 `cloudbase/update-check/index.js` 已修复并通过 Node 语法检查。线上函数当前仍为旧代码，公开响应尚未透传 `minSdk`；本机没有 CloudBase 登录凭证，设备码登录因授权会话过期未完成，因此本轮未部署云函数。GitHub latest 清单已更新，现有函数会自动看到 1.0.46，但 `minSdk` 字段要等函数部署后才在线生效。
