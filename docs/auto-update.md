# 力行 LiXing · 应用内自动更新

## 数据流

```
                    ┌──────────────────┐
                    │ GitHub Releases  │  (APK + Release Notes)
                    └────────┬─────────┘
                             │  gh release create
                             ▼
                   ┌────────────────────┐
                   │ Cloudbase 云函数   │  GET /update-check
                   │ update-check       │  → 缓存 10 分钟
                   │ 返回 update.json   │  → 代理 GitHub Release API
                   └────────┬───────────┘
                            │  https://lixing-...tcloudbase.com/update-check
                            ▼
   ┌───────────────────────────────────────────────────────────┐
   │  App                                                     │
   │  ┌────────────────────┐                                   │
   │  │ LiXingApplication  │ → startSilentCheck(manual=false)  │
   │  │ 启动静默触发       │                                   │
   │  └─────────┬──────────┘                                   │
   │            ▼                                             │
   │  ┌────────────────────┐                                   │
   │  │ AppUpdateController│ (单例 + StateFlow)               │
   │  │  decideCheckPolicy │ 是否跳过（Wi-Fi/每天一次/总开关）  │
   │  │  fetchManifest     │ UpdateRepository                 │
   │  │  isUpgradeFor      │ UpdateManifest (纯函数)          │
   │  └─────────┬──────────┘                                   │
   │            ▼ Available (manifest)                         │
   │  ┌────────────────────┐  ┌──────────────┐                  │
   │  │ MainActivity       │  │ Settings页   │                │
   │  │ UpdateDialogHost   │  │ UpdateSection│                │
   │  │ AlertDialog        │  │ 卡片式状态   │                │
   │  └─────────┬──────────┘  └──────┬───────┘                  │
   │            ▼ startDownload()   ▼                           │
   │  ┌────────────────────────────────┐                       │
   │  │ UpdateDownloader               │                       │
   │  │ enqueue(url) → DownloadManager │                       │
   │  └─────────┬──────────────────────┘                       │
   │            ▼ ACTION_DOWNLOAD_COMPLETE                     │
   │  ┌──────────────────────┐                                 │
   │  │ DownloadComplete     │ @AndroidEntryPoint              │
   │  │ Receiver             │ → controller.onDownloaded      │
   │  └─────────┬────────────┘                                 │
   │            ▼                                             │
   │  ┌──────────────────────┐                                 │
   │  │ verifyAndInstall     │ SHA-256 校验                    │
   │  └─────────┬────────────┘                                 │
   │            ▼ ReadyToInstall(file)                         │
   │  ┌──────────────────────┐                                 │
   │  │ UpdateInstaller      │ Intent(VIEW) + FileProvider     │
   │  └──────────────────────┘                                 │
   └───────────────────────────────────────────────────────────┘
```

## 三重节流

设置项 `[UserPreferencesRepository.updateLastCheckAt]`：启动静默检查前会判定：
1. 用户总开关 `updateAutoEnabled` 关 → 跳过；
2. 距上次自动检查 < 24 小时 → 跳过；
3. Wi-Fi 限制 `updateWifiOnly` 开且当前不是 Wi-Fi/非计费网络 → 跳过；
4. 设置页「立即检查」按钮 `manual=true` 跳过以上全部节流。

## 安全 / 签名

- APK 放 GitHub Releases（免费 2GB/文件，2026/9 实测下载峰值 ~10 MB/s）。
- 下载完成后用云端清单给的 SHA-256 校验本地 APK，**不一致直接删除并回到 Failed**。
- 安装使用 FileProvider（已在 Manifest 注册）+ `Intent.ACTION_VIEW`，无需直接 `Uri.fromFile`（Android 7+ 禁用）。
- Android 8+ 第一次安装需用户在「安装未知应用」页授权；提供专门的引导按钮 `openInstallPermissionSettings`。

## 适配 / 兼容性

| 系统 | 处理 |
|------|------|
| ≤ Android 7 | `Uri.fromFile` + `ACTION_VIEW`（FileProvider 也兼容） |
| ≥ Android 8 | `FileProvider.getUriForFile` + 申请 `REQUEST_INSTALL_PACKAGES` 授权 |
| ≥ Android 10 | Scoped Storage：写到 `getExternalFilesDir(Download)/apk/` 完全合规 |

## 出包流程

```bash
# 一次性：把发布脚本放到 PATH 或 docs/release.sh
./gradlew :app:assembleRelease
# 算出 SHA-256、生成 update.json、把 APK 上传 GitHub Releases
docs/release.sh v1.0.2
# 然后把 update.json 内容粘到 Cloudbase 云函数返回值（或刷新云函数缓存）
```

## 已知风险

- **R8**：release 构建依赖 R8 与资源压缩。Compose/Room/ONNX/OpenCV 等反射路径已由 `app/proguard-rules.pro` 现成规则覆盖（沿用现有规则）。
- **小米/MIUI**：MIUI 会在「自动安装」环节弹独立对话框，应用无法拦截，只能等待用户手动点「继续安装」。
- **签名一致性**：当前 release 与 debug 共用 `app/debug.keystore`（`signingConfigs.debug`），所以能覆盖安装。**未来若要做正式签名**，必须保留 debug.keystore 的 cert fingerprint，或设计签名迁移方案。
- **包名一致性（踩过坑）**：`applicationIdSuffix = ".debug"` 放在 `defaultConfig` 里，debug/release 最终包名都是 `com.example.lixing.debug`。曾把后缀只挂在 debug 上，导致首个 release 包（`com.example.lixing`）被系统当作另一个 App 并排安装而不是覆盖升级。**任何包名调整都必须先对齐设备上已装版本的包名**，否则自动更新链路会「装出新 App」。
- **体积构成（v1.0.1 实测）**：assets 模型 212MB（原样保留）+ arm64 so 69MB + R8 后 dex 5MB ≈ 284MB。省出的 126MB 来自删除 x86_64 模拟器库（107MB）与 dex 从 71MB 砍到 5MB，**OCR 模型没有被裁剪**。
