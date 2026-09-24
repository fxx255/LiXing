# 1.0.45 发布记录

- 版本：1.0.45 / versionCode 46，包名 `com.example.lixing.debug`，minSdk 26 / targetSdk 35，仅 ARM64。
- 发布提交：`1f9c6a414774a4cd6ee9888456d514a6f9f02f99`；标签 `v1.0.45` 精确指向该提交，已推送 `main`。
- 本版包含：助手占位回答“见上”恢复、思考区与输出气泡布局调整、隐藏思考期间空回答气泡、流式公式与表格稳定显示、英语积累确认恢复、教材式水平通信框图、拍摄与图库图片逆时针旋转 90°。

## 验收结果

- MuMu 12-1（`127.0.0.1:16416`）框图/公式设备测试：5 项通过。
- MuMu 12-1 UI 回归：12 项通过；助手运行时：3 项通过；草稿：2 项通过。
- 进程终止恢复：prepare / recover 各 1 项通过；图片逆时针旋转验证通过。
- 发布包覆盖安装并冷启动成功，无启动崩溃。验收后设备已恢复原 1.0.43 APK 与数据，网络隔离、ADB reverse、root 状态均已撤销。

## 发布资产

- GitHub Release：[`v1.0.45`](https://github.com/fxx255/LiXing/releases/tag/v1.0.45)，Release ID `395628155`，已发布且为 latest。
- APK：[`LiXing-1.0.45-arm64.apk`](https://github.com/fxx255/LiXing/releases/download/v1.0.45/LiXing-1.0.45-arm64.apk)
  - 大小：45,406,908 字节
  - SHA-256：`d0ba2c38f32d806fe24b8155f77c9a7b7787968a10b4748fa19c22ea979dad87`
  - GitHub 资产 digest 与本地计算一致；经 `ghfast.top` 下载复核哈希一致。
- 清单：[`update.json`](https://github.com/fxx255/LiXing/releases/download/v1.0.45/update.json)
  - versionCode 46、versionName 1.0.45、`force=false`、minSdk 26。
- APK 签名证书与旧版一致。

## 云函数核验

- 地址：`https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com/update/check`
- 无参数、`versionCode=45`、`versionCode=46` 三次请求均返回 HTTP 200 与 `ok=true`。
- 返回 `versionCode=46`、`versionName=1.0.45`、APK URL、SHA-256、大小均与 Release 清单一致；后续请求命中 `cached=true`。
- 云函数源码使用 `UPDATE_MANIFEST_URL` 指向 GitHub `releases/latest/download/update.json`，本次发布后自动切换到 1.0.45，因此无需再次部署或修改函数代码。
