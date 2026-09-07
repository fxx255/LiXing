# 砺行百度 OAuth：腾讯云 CloudBase 部署

`baidu-oauth/` 是 Node.js 18 云函数源码，提供以下 HTTP 路由：

- `GET /health`
- `GET /oauth/baidu/start`
- `GET /oauth/baidu/callback`
- `POST /oauth/baidu/token`
- `POST /oauth/baidu/refresh`

当前线上修正版使用服务端 HMAC 签名 state，避免 OAuth 跳转后依赖数据库的即时一致性；`oauth_tickets` 仅保存五分钟有效的一次性 Token 领取票据，并对领取读取做有限重试。

需要先创建权限为“仅管理员可读写”的 CloudBase 数据库集合 `oauth_tickets`，再为函数配置：

- `BAIDU_CLIENT_ID`：百度 AppKey
- `BAIDU_CLIENT_SECRET`：轮换后的百度 SecretKey
- `BAIDU_REDIRECT_URI`：CloudBase 完整回调 URL

不要把 SecretKey 写入 `index.js`、APK、提交记录或聊天消息。函数运行时必须使用 Node.js 18 或更高版本，并允许访问公网，以便调用百度 OAuth API。

当前 Android 基础地址：

```text
https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com
```
