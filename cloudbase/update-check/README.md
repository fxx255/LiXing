# 力行版本清单服务：腾讯云 CloudBase 部署

`update-check/` 提供 Node.js 18 云函数源码，无任何 npm 依赖。两个版本**二选一**：

| 文件 | 函数类型 | 适用 |
|---|---|---|
| `index.js` | **普通事件函数**（推荐，与 baidu-oauth 同类型） | 控制台「空白函数」创建；`exports.main` 写法，测试按钮可用、日志直观 |
| `web-index.js` | Web 服务型函数（备用） | 对应「HTTP Node.js Hello World」模板创建；部署时用它覆盖模板的 `index.js`，`scf_bootstrap` 保持原样 |

App 端 `BuildConfig.UPDATE_CHECK_URL` 已写死指向本服务：
`https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com/update/check`

## 清单来源（三选一，环境变量控制，两个版本行为一致）

| 优先级 | 环境变量 | 说明 | 适用 |
|---|---|---|---|
| 1 | `UPDATE_MANIFEST_JSON` | 直接内联清单 JSON 字符串 | 首次部署、还没建 GitHub 仓库时；临时改 versionCode 测弹窗也最方便 |
| 2 | `UPDATE_MANIFEST_URL` | update.json 的直链 | 长期推荐：GitHub Release 附件，建议用 `…/releases/latest/download/update.json` 固定链接 |
| 3 | `GITHUB_REPO` | `user/repo`，代理 Releases API | 兜底：无 update.json 附件时自动从 Release 信息组装（此时无 SHA-256，App 会拒绝安装） |

三者可共存，按优先级生效；换来源时把不用的变量清空，避免歧义。

## 部署步骤（普通事件函数，推荐）

1. 打开 [CloudBase 控制台](https://console.cloud.tencent.com/tcb) → 环境 `lixing-d7g243r7kcad67750`（上海）；
2. 「云函数/托管」→「创建云函数」→ 创建方式选「**空白函数**」（不要选「通过模板创建」里的 HTTP 模板）：
   - 函数名称：`update-check`
   - 运行时：**Node.js 18**
   - 内存 128MB、执行超时 10 秒、允许公网访问
3. 创建后进入在线编辑器，用 `index.js`（事件版）的内容**整体覆盖**默认代码 → 保存 → 部署；
4. 「函数配置」→ 环境变量：
   - `UPDATE_MANIFEST_JSON` = `{"versionCode":2,"versionName":"1.0.1","apkUrl":"…","sizeBytes":296797253,"sha256":"…","changelog":"…","force":false}`
5. 左侧「HTTP 访问服务」→ 路由（新建或编辑已有）：
   - 路径前缀：`/update/check`
   - 关联函数：`update-check`
   - 鉴权：**免鉴权**（App 拿清单不能带凭证）
6. 验证：浏览器或 curl 访问上面的 URL（见下）。

> 如果之前已经按 HTTP 模板建过同名函数：函数类型创建后不可转换，需删掉旧的重建，
> 或换个名字新建后把 `/update/check` 路由的关联函数改过来。环境变量要重新配一遍。

## 验证

```bash
curl -s "https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com/update/check"
```

期望返回：

```json
{"ok":true,"versionCode":2,"versionName":"1.0.1","apkUrl":"…","sha256":"…","sizeBytes":296797253,"changelog":"…","force":false,"cached":false}
```

## 测试 App 弹窗（不必真发新版）

把 `UPDATE_MANIFEST_JSON` 里的 `versionCode` 临时改成 `99` → 保存 → 在手机 App「设置 → 更新」点「立即检查」→ 应弹出「发现新版本」对话框。测完把 `versionCode` 改回真实值。

## 日常发版怎么更新清单

- **内联模式**：每次发版后把新的 update.json 值粘进环境变量，保存即生效（实例复用时 10 分钟内可能返回旧值，可在函数管理页「测试」强制拉起新实例）。
- **GitHub 模式**：`release.sh` 每次把新 update.json 传到 Release 附件，`latest/download/update.json` 永远指向最新版，**云函数一次都不用再动**。
