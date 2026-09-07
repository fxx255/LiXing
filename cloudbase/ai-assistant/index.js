/**
 * 砺行 AI 学习助手网关（腾讯云 CloudBase HTTP 云函数）
 *
 * 职责：
 * - 验证访问令牌（Bearer == AI_ACCESS_TOKEN）；
 * - 按天限流（CloudBase 集合 ai_usage）；
 * - 把请求转发给 DeepSeek Chat Completions API（OpenAI 兼容），API Key 只存在环境变量；
 * - 约束输入/输出长度，统一返回 { reply, plan_actions }。
 *
 * 路由：
 * - GET  /health   连通性检查（也要带令牌）
 * - POST /ai/chat  对话
 *
 * 环境变量：
 * - DEEPSEEK_API_KEY     DeepSeek 密钥（必填，禁止写进代码，platform.deepseek.com 获取）
 * - AI_ACCESS_TOKEN      App 端访问令牌（必填）
 * - AI_MODEL             模型名，默认 deepseek-chat（也可填 deepseek-reasoner）
 * - AI_MAX_INPUT_CHARS   单次请求正文上限，默认 12000
 * - AI_MAX_OUTPUT_TOKENS 输出上限，默认 2000
 * - AI_DAILY_LIMIT       每天请求次数上限，默认 60
 */
const cloudbase = require("@cloudbase/node-sdk");

const app = cloudbase.init({ env: cloudbase.SYMBOL_CURRENT_ENV });
const db = app.database();

const SYSTEM_PROMPT = `你是「砺行」App 里的学习助手。砺行是一个按时段打卡的学习自律应用，
用户的计划由「科目 / 时段 / 任务模板」组成，每天会把模板物化成当日任务。

回答规则：
1. 用简体中文回答，简洁、具体、可执行，像一位靠谱的研友。
2. 只基于给出的上下文回答；上下文没有的信息就直说不知道，不要编造。
3. 当且仅当用户明确要求修改计划时，才在 plan_actions 里给出建议；否则 plan_actions 必须是空数组。
4. 你不能删除任何东西，不能改已完成任务、积分、成就；不要建议这些操作。

输出格式（必须是可以直接 JSON.parse 的单个对象，不要 Markdown 代码块）：
{"reply": "给用户看的正文", "plan_actions": [ ... ]}

plan_actions 支持的类型：
- {"kind":"UPDATE_TIME_SLOT","slotId":数字,"startTime":"HH:mm"可省,"endTime":"HH:mm"可省,"reason":"简短原因"}
- {"kind":"UPDATE_TASK_TEMPLATE","templateId":数字, 可选字段:"title"(<=60字)/"targetValue"(1~9999整数)/"timeSlotId"/"repeatRule"(DAILY|WEEKLY_DAYS|EVERY_N_DAYS)/"isKeystone"/"isEnabled", "reason":"..."}
- {"kind":"INSERT_TASK_TEMPLATE","subjectId":数字,"timeSlotId":数字,"title":"...","taskType"(LECTURE|PRACTICE|MEMORIZE|REVIEW|CUSTOM),"targetType"(MINUTES|COUNT|PAGES|BOOLEAN),"targetValue":数字,"repeatRule":同上,"isKeystone":布尔,"note"可省,"reason":"..."}
- {"kind":"UPDATE_TODAY_TASK_TARGET","taskId":数字,"targetValue":1~9999整数,"reason":"..."}

所有 id 必须来自上下文里真实出现的 id，不许猜测。reason 控制在 40 字以内。`;

function json(body, status = 200) {
  return {
    statusCode: status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify(body),
  };
}

function authorized(req) {
  const expected = process.env.AI_ACCESS_TOKEN;
  if (!expected) return false;
  const header =
    req.headers["authorization"] || req.headers["Authorization"] || "";
  return header === `Bearer ${expected}`;
}

function extractText(req) {
  if (!req.body) return "";
  if (typeof req.body === "string") return req.body;
  return JSON.stringify(req.body);
}

async function bumpDailyUsage() {
  const limit = parseInt(process.env.AI_DAILY_LIMIT || "60", 10);
  const today = new Date().toISOString().slice(0, 10);
  const collection = db.collection("ai_usage");
  const { data } = await collection.where({ date: today }).limit(1).get();
  if (data.length === 0) {
    await collection.add({ date: today, count: 1 });
    return { allowed: true, used: 1 };
  }
  const row = data[0];
  if ((row.count || 0) >= limit) {
    return { allowed: false, used: row.count };
  }
  await collection.doc(row._id).update({ count: (row.count || 0) + 1 });
  return { allowed: true, used: (row.count || 0) + 1 };
}

async function callModel(messages, context) {
  const model = process.env.AI_MODEL || "deepseek-chat";
  const maxTokens = parseInt(process.env.AI_MAX_OUTPUT_TOKENS || "2000", 10);

  const chatMessages = [{ role: "system", content: SYSTEM_PROMPT }];
  if (context && context.trim().length > 0) {
    chatMessages.push({
      role: "user",
      content: `以下是用户勾选的本机学习数据（只读上下文）：\n\n${context}`,
    });
    chatMessages.push({
      role: "assistant",
      content: "好的，我已了解这些上下文，请提出你的问题。",
    });
  }
  for (const m of messages) {
    chatMessages.push({ role: m.role, content: String(m.content) });
  }

  const response = await fetch("https://api.deepseek.com/chat/completions", {
    method: "POST",
    headers: {
      authorization: `Bearer ${process.env.DEEPSEEK_API_KEY}`,
      "content-type": "application/json",
    },
    body: JSON.stringify({
      model,
      max_tokens: maxTokens,
      temperature: 0.4,
      messages: chatMessages,
      response_format: { type: "json_object" },
    }),
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`DeepSeek HTTP ${response.status}: ${text.slice(0, 300)}`);
  }
  const payload = JSON.parse(text);
  const content = payload && payload.choices && payload.choices[0] &&
    payload.choices[0].message && payload.choices[0].message.content;
  if (!content) throw new Error("DeepSeek 未返回内容");
  return String(content).trim();
}

function normalizeModelOutput(raw) {
  try {
    const cleaned = raw.replace(/^```(?:json)?\s*/i, "").replace(/```\s*$/, "");
    const obj = JSON.parse(cleaned);
    if (obj && typeof obj.reply === "string") {
      return {
        reply: obj.reply,
        plan_actions: Array.isArray(obj.plan_actions) ? obj.plan_actions : [],
      };
    }
  } catch (_) {
    /* 落到普通文本回复 */
  }
  return { reply: raw, plan_actions: [] };
}

exports.main = async (req) => {
  const method = (req.httpMethod || req.method || "GET").toUpperCase();
  const path = req.path || "/";

  if (!authorized(req)) {
    return json({ error: "unauthorized" }, 401);
  }

  if (method === "GET" && path.endsWith("/health")) {
    return json({ ok: true, service: "lixing-ai-assistant" });
  }

  if (method === "POST" && path.endsWith("/ai/chat")) {
    if (!process.env.DEEPSEEK_API_KEY) {
      return json({ error: "server missing DEEPSEEK_API_KEY" }, 500);
    }
    let body;
    try {
      body = JSON.parse(extractText(req));
    } catch (_) {
      return json({ error: "invalid json body" }, 400);
    }
    const messages = Array.isArray(body.messages) ? body.messages : [];
    const context = typeof body.context === "string" ? body.context : "";
    const maxInput = parseInt(process.env.AI_MAX_INPUT_CHARS || "12000", 10);

    const totalChars =
      context.length +
      messages.reduce((sum, m) => sum + String(m.content || "").length, 0);
    if (messages.length === 0 || totalChars > maxInput) {
      return json({ error: "request too large or empty" }, 400);
    }
    const sanitized = messages
      .filter((m) => m.role === "user" || m.role === "assistant")
      .slice(-12)
      .map((m) => ({ role: m.role, content: String(m.content).slice(0, 4000) }));

    try {
      const quota = await bumpDailyUsage();
      if (!quota.allowed) {
        return json({ error: "daily limit reached" }, 429);
      }
      const raw = await callModel(sanitized, context.slice(0, maxInput));
      return json(normalizeModelOutput(raw));
    } catch (e) {
      return json({ error: String(e.message || e).slice(0, 300) }, 502);
    }
  }

  return json({ error: "not found" }, 404);
};