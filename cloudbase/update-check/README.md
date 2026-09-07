# 力行版本清单服务：腾讯云 CloudBase 部署

`update-check/` 提供 Node.js 18 云函数源码，无任何 npm 依赖。两个版本**二选一**：

| 文件 | 函数类型 | 适用 |
|---|---|---|
| `index.js` | **普通事件函数**（推荐，与 baidu-oauth 同类型） | 控制台「空白函数」创建；`exports.main` 写法，测试按钮可用、日志直观 |
| `web-index.js` | Web 服务型函数（备用） | 对应「HTTP Node.js Hello World」模板创建；部署时用它覆盖模板的 `index.js`，`scf_bootstrap` 保持原样 |

App 端 `BuildConfig.UPDATE_CHECK_URL` 已写死指向本服务：
`https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com/update/check`

## 清单来源（环境变量控制，两个版本行为一致）

取数顺序是「**网络源 → 内联兜底 → GitHub API**」：

| 顺序 | 环境变量 | 说明 | 适用 |
|---|---|---|---|
| 1 | `UPDATE_MANIFEST_URL` | update.json 的直链，可用**英文逗号分隔多个源**（主源 + 加速镜像，逐个回退） | 长期推荐：GitHub Release 附件，用 `…/releases/latest/download/update.json` 固定链接，发版不用改 |
| 2 | `UPDATE_MANIFEST_JSON` | 内联清单 JSON 字符串（**兜底**，零网络、毫秒级返回） | 免费版 3 秒超时下必填：回源一慢就顶爆函数，有它才不会报错 |
| 3 | `GITHUB_REPO` | `user/repo`，代理 Releases API | 最后兜底：无 update.json 附件时自动从 Release 信息组装（此时无 SHA-256，App 会拒绝安装） |

回源失败时响应里会带 `"fallback": true`，可从调用日志判断是不是一直在走兜底。

### ⚠️ 免费版执行超时只能选 3 秒（重要）

CloudBase **免费版**的 `update-check` 函数执行超时最多只能选 **3 秒**（同环境的老函数可能是 30 秒，那是历史配置），而 GitHub 直链从云函数网络经常连不通（本机实测 github.com 21 秒超时失败；gh-proxy.com ≈ 1.4s、ghfast.top ≈ 3.5s），回源一慢整个函数就被判定超时，App 端表现为「检查更新没反应」。

**对策（必须做第 2 条）**：

1. `UPDATE_MANIFEST_URL` 只填**一个最快**的镜像（多源会叠加耗时，反而更容易顶破 3 秒）：

   ```
   https://gh-proxy.com/https://github.com/fxx255/LiXing/releases/latest/download/update.json
   ```

2. `UPDATE_MANIFEST_JSON` 填当前版本的完整清单（**兜底**）。回源超时/失败时函数在几十毫秒内返回它，
   **永远不会触发 3 秒超时**。滞后不更新只会「检测不到更新的版本」，不会误报
   （App 只在 `versionCode > 当前版本` 时才提示）。建议每次发版顺手更新一次。

3. 可选：`MANIFEST_FETCH_TIMEOUT_MS` 调整单源回源超时（默认 1200ms；3 秒上限下最多设 2000）。

每次发版后，把 `build/update.json` 的内容整段粘进 `UPDATE_MANIFEST_JSON` 即可（就是一行压缩过的 JSON）。

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
