# 1.0.43 发布记录

- 版本：1.0.43 / versionCode 44，构建提交 `742a0a69a248e8bdce79bec8e6340e0ec72250fa`，标签 `v1.0.43`。
- 本版包含免费英语词典与复习、照片顺时针旋转、绘图颜色及样式扩展、坐标轴上数据点修复、区域阴影，以及本地 OCR 遗留设置和助手输入区清理。
- JDK 21、Gradle 8.14.3：609 项完整单元测试通过，失败、错误、跳过均为 0；最终照片/图片回归 5 项通过。Debug、Release、AndroidTest 构建和串行 `lintDebug` 通过，Release R8 与发布 lint 通过。
- MuMu模拟器12-1（`127.0.0.1:16416`）设备测试通过：真实 Canvas 的原点标记与区域阴影、旋转后整张照片保存。最终正式包覆盖安装成功，versionCode 44、versionName 1.0.43，冷启动正常且无启动崩溃；保留模拟器应用数据。
- 包名：`com.example.lixing.debug`，仅包含 ARM64 库。无数学 OCR 模型、ONNX Runtime、OpenCV；饮食识别模型仍在。
- 签名证书 SHA-256：`9c5f21a4e1923d3f290339cccbe4b3cb3fba3c849d385e867f5781fb8b1a8de2`，与旧版一致。
- 安装包大小：45,308,604 字节（45.31 MB）；SHA-256：`239973adb3386758761a54185fea1dac7ed6d72c66c109ad5f5b239d622e6edb`。
- 正式 APK 中词库相关资源压缩大小合计 13,724,135 字节（13.72 MB / 13.09 MiB）。整包相较 1.0.42 的 31,517,950 字节增加 13,790,654 字节（13.79 MB）；整包增量也包含其他代码与资源变化。
- R8 mapping 已归档至本机 `docs/mappings/mapping-v1.0.43.txt`（Git 忽略）。
- [GitHub Release](https://github.com/fxx255/LiXing/releases/tag/v1.0.43) 已公开并确认为 latest。新标签直接指向构建提交，无标签强制覆盖；APK 与 update.json 的 GitHub 独立 digest 均与本地一致，下载 URL 均包含 `/download/v1.0.43/`。
- APK 的 `ghfast.top` 镜像返回 HTTP 206，前 1024 字节为 APK/ZIP 文件头，响应总大小为 45,308,604 字节。
- 本机直接请求 GitHub APK 与 latest 清单时发生连接/读取超时，使用另一 HTTP 客户端重试仍超时；直链连通性本轮未能验证。GitHub API 的 latest、标签与资产信息正常，应用所用镜像下载及云端清单已分别验证。
- 公开云端 `/update/check` 返回 HTTP 200、`ok=true`、`versionCode=44`、`versionName=1.0.43`，地址、大小与 SHA-256 均匹配本版；`cached=false`，未使用兜底清单。现有云函数已自动获取 latest 清单，本次无需重新部署代码。
- 已有对话图像为保存的 PNG，需要重新生成才能使用新增的颜色、线型与阴影效果。
