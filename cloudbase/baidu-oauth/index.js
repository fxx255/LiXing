"use strict";

const crypto = require("crypto");
const cloudbase = require("@cloudbase/node-sdk");

const app = cloudbase.init({ env: cloudbase.SYMBOL_CURRENT_ENV });
const db = app.database();
const tickets = db.collection("oauth_tickets");

const STATE_MAX_AGE_MS = 30 * 60 * 1000;
const TICKET_TTL_MS = 5 * 60 * 1000;

/** CloudBase Node.js 18 云函数入口。 */
exports.main = async (event) => {
  try {
    const request = normalizeRequest(event || {});

    if (request.method === "GET" && (request.path === "/" || request.path.endsWith("/health"))) {
      return jsonResponse({
        ok: true,
        service: "lixing-baidu-oauth",
        platform: "tencent-cloudbase",
        configured: configurationReady(),
      });
    }

    checkConfiguration();

    if (request.method === "GET" && request.path.endsWith("/oauth/baidu/start")) {
      return startAuthorization();
    }

    if (request.method === "GET" && request.path.endsWith("/oauth/baidu/callback")) {
      return finishAuthorization(request.query);
    }

    if (request.method === "POST" && request.path.endsWith("/oauth/baidu/token")) {
      return redeemTicket(request.body);
    }

    if (request.method === "POST" && request.path.endsWith("/oauth/baidu/refresh")) {
      return refreshAccessToken(request.body);
    }

    return jsonResponse({ ok: false, error: "Not found" }, 404);
  } catch (error) {
    console.error("OAuth function failed:", safeLogError(error));
    return jsonResponse({ ok: false, error: "服务器处理失败，请稍后重试" }, 500);
  }
};

async function startAuthorization() {
  // 使用服务器签名 state，避免 OAuth 跳转后立即读取数据库时受到最终一致性影响。
  const state = createSignedState();
  cleanupExpired().catch((error) => console.warn("Cleanup skipped:", safeLogError(error)));

  const authorizeUrl = new URL("https://openapi.baidu.com/oauth/2.0/authorize");
  authorizeUrl.searchParams.set("response_type", "code");
  authorizeUrl.searchParams.set("client_id", process.env.BAIDU_CLIENT_ID);
  authorizeUrl.searchParams.set("redirect_uri", process.env.BAIDU_REDIRECT_URI);
  authorizeUrl.searchParams.set("scope", "basic,netdisk");
  authorizeUrl.searchParams.set("display", "mobile");
  authorizeUrl.searchParams.set("state", state);

  return redirectResponse(authorizeUrl.toString());
}

async function finishAuthorization(query) {
  const state = query.state;
  if (!verifySignedState(state)) {
    return resultPage(false, "授权请求无效或缺少 state 参数");
  }

  if (query.error) {
    return resultPage(false, "你取消了百度网盘授权");
  }
  if (!query.code) {
    return resultPage(false, "百度没有返回授权码");
  }

  const tokenData = await requestBaiduToken({
    grant_type: "authorization_code",
    code: query.code,
    redirect_uri: process.env.BAIDU_REDIRECT_URI,
  });
  if (!tokenData.access_token) {
    console.error("Baidu token exchange failed:", sanitizeBaiduError(tokenData));
    return resultPage(false, "获取百度网盘授权凭证失败");
  }

  const ticket = randomToken();
  await putRecord(`ticket_${ticket}`, {
    type: "ticket",
    expiresAt: Date.now() + TICKET_TTL_MS,
    token: tokenData,
  });

  const appUrl = `lixing://oauth/baidu?ticket=${encodeURIComponent(ticket)}`;
  return resultPage(true, "百度网盘授权成功，请返回砺行", appUrl);
}

async function redeemTicket(rawBody) {
  const body = parseJsonBody(rawBody);
  const ticket = body.ticket;
  if (!isValidOpaqueToken(ticket)) {
    return jsonResponse({ ok: false, error: "无效的授权票据" }, 400);
  }

  const key = `ticket_${ticket}`;
  const record = await getRecord(key);
  if (!record || record.type !== "ticket" || !record.token) {
    return jsonResponse({ ok: false, error: "授权票据不存在、已使用或已过期" }, 410);
  }

  // 先删除再返回，缩短重复兑换窗口；ticket 本身有 256 位随机熵且仅存活 5 分钟。
  await removeRecord(key);
  return jsonResponse({
    ok: true,
    token: { ...record.token, issued_at: Date.now() },
  });
}

async function refreshAccessToken(rawBody) {
  const body = parseJsonBody(rawBody);
  const refreshToken = body.refresh_token;
  if (typeof refreshToken !== "string" || refreshToken.length < 10 || refreshToken.length > 4096) {
    return jsonResponse({ ok: false, error: "无效的 refresh_token" }, 400);
  }

  const tokenData = await requestBaiduToken({
    grant_type: "refresh_token",
    refresh_token: refreshToken,
  });
  if (!tokenData.access_token) {
    console.error("Baidu token refresh failed:", sanitizeBaiduError(tokenData));
    return jsonResponse({ ok: false, error: "刷新授权凭证失败" }, 400);
  }
  return jsonResponse({
    ok: true,
    token: { ...tokenData, issued_at: Date.now() },
  });
}

async function requestBaiduToken(values) {
  if (typeof fetch !== "function") {
    throw new Error("请把函数运行时设置为 Node.js 18 或更高版本");
  }
  const form = new URLSearchParams({
    ...values,
    client_id: process.env.BAIDU_CLIENT_ID,
    client_secret: process.env.BAIDU_CLIENT_SECRET,
  });
  const response = await fetch("https://openapi.baidu.com/oauth/2.0/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form.toString(),
  });
  const text = await response.text();
  try {
    return JSON.parse(text);
  } catch (_) {
    throw new Error(`百度返回了无法识别的结果（HTTP ${response.status}）`);
  }
}

async function putRecord(id, data) {
  await tickets.doc(id).set({ data });
}

async function getRecord(id) {
  // CloudBase 文档数据库在不同执行实例间可能短暂不可见，做有限重试。
  const delays = [0, 120, 300, 700, 1200];
  for (const delayMs of delays) {
    if (delayMs) await delay(delayMs);
    try {
      const response = await tickets.doc(id).get();
      const data = unwrapDocument(response && response.data);
      if (data) return validateRecord(id, data);
    } catch (error) {
      if (!isDocumentMissingError(error)) throw error;
    }
  }

  // 部分 SDK/数据库版本的 doc.get() 返回形态不同，再用 _id 查询兜底。
  const response = await tickets.where({ _id: id }).limit(1).get();
  const data = unwrapDocument(response && response.data);
  return data ? validateRecord(id, data) : null;
}

function unwrapDocument(raw) {
  let value = Array.isArray(raw) ? raw[0] : raw;
  if (value && !value.type && value.data && typeof value.data === "object") {
    value = value.data;
  }
  return value || null;
}

async function validateRecord(id, data) {
  if (typeof data.expiresAt !== "number" || data.expiresAt <= Date.now()) {
    await removeRecord(id).catch(() => undefined);
    return null;
  }
  return data;
}

function isDocumentMissingError(error) {
  const message = String(error && (error.code || error.message || error));
  return /not.?found|DOCUMENT_NOT_EXIST|document does not exist/i.test(message);
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function removeRecord(id) {
  await tickets.doc(id).remove();
}

async function cleanupExpired() {
  const response = await tickets
    .where({ expiresAt: db.command.lt(Date.now()) })
    .limit(20)
    .get();
  const records = Array.isArray(response.data) ? response.data : [];
  await Promise.all(records.map((record) => removeRecord(record._id).catch(() => undefined)));
}

function normalizeRequest(event) {
  const requestContext = event.requestContext || {};
  const httpContext = requestContext.http || {};
  const method = String(event.httpMethod || httpContext.method || requestContext.httpMethod || "GET")
    .toUpperCase();
  const path = String(event.rawPath || event.path || httpContext.path || "/");
  const query = { ...(event.queryStringParameters || {}) };
  if (event.rawQueryString) {
    for (const [key, value] of new URLSearchParams(event.rawQueryString)) {
      if (query[key] == null) query[key] = value;
    }
  }

  let body = event.body || "";
  if (event.isBase64Encoded && typeof body === "string") {
    body = Buffer.from(body, "base64").toString("utf8");
  }
  return { method, path, query, body };
}

function parseJsonBody(rawBody) {
  if (rawBody && typeof rawBody === "object") return rawBody;
  if (typeof rawBody !== "string" || rawBody.length > 16 * 1024) return {};
  try {
    return JSON.parse(rawBody || "{}");
  } catch (_) {
    return {};
  }
}

function configurationReady() {
  return Boolean(
    process.env.BAIDU_CLIENT_ID &&
    process.env.BAIDU_CLIENT_SECRET &&
    process.env.BAIDU_REDIRECT_URI,
  );
}

function checkConfiguration() {
  if (!configurationReady()) throw new Error("OAuth 环境变量尚未配置完整");
}

function randomToken() {
  return crypto.randomBytes(32).toString("base64url");
}

function createSignedState() {
  const issuedAt = Date.now().toString(36);
  const nonce = randomToken();
  const payload = `${issuedAt}.${nonce}`;
  return `${payload}.${signState(payload)}`;
}

function verifySignedState(value) {
  if (typeof value !== "string" || value.length > 160) return false;
  const parts = value.split(".");
  if (parts.length !== 3) return false;
  const [issuedText, nonce, suppliedSignature] = parts;
  if (!/^[a-z0-9]+$/.test(issuedText) || !isValidOpaqueToken(nonce)) return false;
  const issuedAt = Number.parseInt(issuedText, 36);
  const age = Date.now() - issuedAt;
  if (!Number.isFinite(issuedAt) || age < -60_000 || age > STATE_MAX_AGE_MS) return false;
  const expected = signState(`${issuedText}.${nonce}`);
  const left = Buffer.from(suppliedSignature);
  const right = Buffer.from(expected);
  return left.length === right.length && crypto.timingSafeEqual(left, right);
}

function signState(payload) {
  return crypto
    .createHmac("sha256", process.env.BAIDU_CLIENT_SECRET)
    .update(`lixing-baidu-state-v1:${payload}`)
    .digest("base64url");
}

function isValidOpaqueToken(value) {
  return typeof value === "string" && /^[A-Za-z0-9_-]{40,100}$/.test(value);
}

function jsonResponse(value, statusCode = 200) {
  return {
    statusCode,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    },
    body: JSON.stringify(value),
    isBase64Encoded: false,
  };
}

function redirectResponse(location) {
  return {
    statusCode: 302,
    headers: { Location: location, "Cache-Control": "no-store" },
    body: "",
    isBase64Encoded: false,
  };
}

function resultPage(success, message, appUrl) {
  const title = success ? "授权成功" : "授权失败";
  const color = success ? "#238636" : "#cf222e";
  const button = appUrl
    ? `<a href="${escapeHtml(appUrl)}">返回砺行</a>`
    : "";
  return {
    statusCode: success ? 200 : 400,
    headers: {
      "Content-Type": "text/html; charset=utf-8",
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
    },
    body: `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="referrer" content="no-referrer"><title>${title}</title>
<style>body{margin:0;padding:24px;background:#f6f8fa;color:#1f2328;font-family:system-ui,sans-serif}main{max-width:460px;margin:15vh auto 0;padding:30px;border-radius:16px;background:#fff;box-shadow:0 8px 30px rgba(0,0,0,.08);text-align:center}h1{color:${color}}a{display:inline-block;margin-top:18px;padding:12px 24px;border-radius:10px;background:#0969da;color:#fff;text-decoration:none}</style></head>
<body><main><h1>${title}</h1><p>${escapeHtml(message)}</p>${button}</main></body></html>`,
    isBase64Encoded: false,
  };
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll('"', "&quot;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function sanitizeBaiduError(value) {
  if (!value || typeof value !== "object") return "unknown";
  return {
    error: value.error,
    error_code: value.error_code,
    error_description: value.error_description,
  };
}

function safeLogError(error) {
  return String(error && (error.stack || error.message || error)).slice(0, 1000);
}
