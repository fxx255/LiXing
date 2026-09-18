# 1.0.42 发布记录

- 版本：1.0.42 / versionCode 43，构建提交 `783545b89dfa3575ddd3cca466c9b61801eb98fd`，标签 `v1.0.42`。
- JDK 21、Gradle 8.14.3：`:app:testDebugUnitTest :app:assembleRelease --offline --no-daemon` 成功；581 项单元测试通过，失败、错误、跳过均为 0。发布 lint 与 R8 压缩通过。
- 包名：`com.example.lixing.debug`，仅包含 ARM64 库。APK 内已无数学 OCR 模型、ONNX Runtime、OpenCV；饮食识别模型仍在。
- 签名证书 SHA-256：`9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与旧版一致。
- 安装包大小：31,517,950 字节；SHA-256：`3d5806935a41c3dd7445629e8e36f4e18ec61d5e2638de1ace97492959952aac`。GitHub 的独立 digest 与本地一致。
- R8 mapping 已归档至本机 `docs/mappings/mapping-v1.0.42.txt`（Git 忽略）。
- 替换了现有 1.0.42 草稿中的旧 APK，上传匹配的新 update.json，并将未发布的旧标签以精确旧值校验更新到最终构建提交。
- [GitHub Release](https://github.com/fxx255/LiXing/releases/tag/v1.0.42) 已公开并确认为 latest；资产下载 URL 均包含 `/download/v1.0.42/`。
- APK 原始下载地址与 `ghfast.top` 镜像均实测返回 HTTP 206，前 1024 字节为 APK/ZIP 文件头，响应总大小与清单一致。
- 云函数沿用现有最新清单来源，无需重新部署代码；公开 `/update/check` 已返回 `ok=true`、`versionCode=43`、`versionName=1.0.42`，APK 地址、大小与 SHA-256 均匹配新清单，未使用旧缓存或兜底。
- 本轮未进行设备安装测试：MuMu模拟器12-1 检查时处于关闭状态。旧图像是已保存的 PNG，需重新生成才能看到渲染修复。
