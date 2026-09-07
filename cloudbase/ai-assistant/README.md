# 砺行 AI 学习助手网关：腾讯云 CloudBase 部署

> 状态：可选方案。当前 App 已改为「直连 OpenAI 兼容接口」模式，接口地址、模型名、API 密钥都在 App 设置页配置，
> 不需要部署本函数。本目录保留给希望把密钥放在云端、不落手机的用户，启用需要自行改造 App 端调用。


`ai-assistant/` 是 Node.js 18 云函数源码，提供以下 HTTP 路由：

- `GET /health` 连通性检查（需要 Bearer 令牌）
- `POST /ai/chat` 对话转发（需要 Bearer 令牌）

## 设计原则

- 大模型 API Key（`DEEPSEEK_API_KEY`）只存在云函数环境变量中，不写进 `index.js`、APK、提交记录或聊天消息；
- App 端只保存一个可随时撤销的访问令牌，用 Android Keystore 加密，不进入版本化备份；
- 每次请求校验令牌、长度和每日次数；上下文由 App 端按用户勾选裁剪，云端不做任何存储（除每日计数）。

## 部署步骤

1. 在 CloudBase 创建 HTTP 访问服务云函数（例如 `lixing-ai-assistant`），运行时选 Node.js 18 或更高，允许访问公网；
2. 上传本目录代码（保持 `index.js` 为入口、`index.main` 为处理函数），安装依赖；
3. 创建权限为「仅管理员可读写」的数据库集合 `ai_usage`（用于每日限流）；
4. 配置环境变量：
   - `DEEPSEEK_API_KEY`：DeepSeek 开放平台（platform.deepseek.com）创建的密钥
   - `AI_ACCESS_TOKEN`：自己生成的一串随机字符串，稍后填进 App 设置页
   - `AI_MODEL`：可选，默认 `deepseek-chat`，也可填 `deepseek-reasoner`
   - `AI_MAX_INPUT_CHARS`：可选，默认 12000
   - `AI_MAX_OUTPUT_TOKENS`：可选，默认 2000
   - `AI_DAILY_LIMIT`：可选，默认 60
5. 拿到函数的 HTTP 触发地址，在 App「设置 → AI 学习助手」填入网关地址与访问令牌，点「保存并测试连接」。

注意：DeepSeek 接口为 OpenAI 兼容格式，走 `https://api.deepseek.com/chat/completions`，并开启 JSON 输出模式；
DeepSeek 目前没有图像理解能力，未来「拍题识图」需要另选带视觉的模型。

## 与百度 OAuth 函数的关系

这是独立函数，不复用 `baidu-oauth/`。两者共用同一个 CloudBase 环境即可，互不影响。
修改函数地址后，只需更新 App 设置页里的网关地址，无需改 APK。