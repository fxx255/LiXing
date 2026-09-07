"use strict";

/**
 * 版本更新清单云函数 —— 普通事件函数版（推荐，与 baidu-oauth 同类型）。
 *
 * 部署：CloudBase 控制台创建「空白/事件函数」（不要选 HTTP 模板），
 *       Node.js 18，把本文件整体覆盖 index.js 后部署即可。
 *       HTTP 访问服务里把路径 /update/check 关联到本函数并选择免鉴权。
 *
 * App 端 BuildConfig.UPDATE_CHECK_URL 已指向：
 *   https://lixing-d7g243r7kcad67750-1323070606.ap-shanghai.app.tcloudbase.com/update/check
 *
 * 清单来源（按顺序尝试，通过环境变量配置）：
 *   1. UPDATE_MANIFEST_URL   指向 update.json 的直链（推荐长期方案，GitHub Release 附件；
 *                            可逗号分隔多个源，每个源独立超时，逐个回退）；
 *   2. UPDATE_MANIFEST_JSON  直接内联 JSON 字符串 —— **兜底**：回源失败/超时时毫秒级返回，
 *                            保证函数在免费版 3 秒执行超时内永远能给出结果；
 *   3. GITHUB_REPO           形如 user/repo，代理 GitHub Releases API（/releases/latest）自动组装。
 *
 * 行为：
 *   - 进程内缓存 10 分钟，回源失败时宁可返回旧缓存也不报错；
 *   - 任何异常返回 502 + JSON 错误体，App 端按 Result.failure 静默处理。
 *
 * Web 函数版（http.createServer 服务器写法）见同目录 web-index.js，二选一即可。
 */

const CACHE_TTL_MS = 10 * 60 * 1000;

/** 单个清单源的回源超时（毫秒）。可用环境变量 MANIFEST_FETCH_TIMEOUT_MS 覆盖。 */
const FETCH_TIMEOUT_MS = Number(process.env.MANIFEST_FETCH_TIMEOUT_MS || 1200);

/** 带超时的 JSON 拉取：慢源直接放弃换下一个，不让整个函数被拖到超时。 */
async function fetchJsonWithTimeout(url, timeoutMs, extraHeaders) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, {
      headers: { Accept: "application/json", ...(extraHeaders || {}) },
      signal: controller.signal,
    });
    if (!response.ok) throw new Error(`返回 ${response.status}`);
    return await response.json();
  } finally {
    clearTimeout(timer);
  }
}

/** 进程内缓存：云函数实例复用期间不再回源，避免每次请求都打 GitHub。 */
let cachedPayload = null;
let cachedAt = 0;

exports.main = async (event) => {
  // 兼容 HTTP 访问服务的探活/预检：非 GET 请求直接按正常响应返回，避免报错噪音。
  const httpMethod = event && (event.httpMethod || (event.requestContext && event.requestContext.httpMethod));
  if (httpMethod && httpMethod !== "GET" && httpMethod !== "HEAD") {
    return jsonResponse({ ok: false, error: "仅支持 GET" }, 405);
  }

  try {
    const now = Date.now();
    if (cachedPayload && now - cachedAt < CACHE_TTL_MS) {
      return jsonResponse({ ...cachedPayload, cached: true });
    }

    const payload = await fetchManifest();
    cachedPayload = payload;
    cachedAt = now;
    return jsonResponse({ ...payload, cached: false });
  } catch (error) {
    console.error("update-check failed:", error && error.message);
    // 有旧缓存就先返回旧的（宁可信息稍旧，也比直接报错打断用户好）
    if (cachedPayload) {
      return jsonResponse({ ...cachedPayload, stale: true });
    }
    return jsonResponse({ ok: false, error: "暂时无法获取更新信息" }, 502);
  }
};

/**
 * 取清单：先回源，回源失败就用内联清单兜底。
 *
 * ⚠️ 顺序是「网络源 → 内联」而不是反过来：CloudBase 免费版的函数执行超时只能选到 3 秒，
 * 而回源（GitHub / 加速镜像）随时可能超过 3 秒，一旦超时整个函数被判失败、App 端检查更新
 * 没反应。所以内联清单必须当作**兜底**存在：哪怕回源全部超时，也能在几十毫秒内返回，
 * 函数永远不会超时。代价只是「内联滞后时检测不到最新版」（App 判断是
 * versionCode > 当前版本 才提示，旧清单只会显示「已是最新版本」，不会误报）。
 */
async function fetchManifest() {
  const errors = [];

  // 1) 外部直链：支持逗号分隔多个源（主源 + 加速镜像），逐个试，每个源独立超时
  const directUrl = process.env.UPDATE_MANIFEST_URL;
  if (directUrl) {
    const urls = directUrl.split(",").map((item) => item.trim()).filter(Boolean);
    for (const url of urls) {
      try {
        return normalizeManifest(await fetchJsonWithTimeout(url, FETCH_TIMEOUT_MS));
      } catch (error) {
        errors.push(`${url} → ${error.message}`);
      }
    }
  }

  // 2) 内联清单兜底：零网络、毫秒级返回，保证函数不超时
  const inline = process.env.UPDATE_MANIFEST_JSON;
  if (inline && inline.trim()) {
    try {
      return { ...normalizeManifest(JSON.parse(inline)), fallback: true, errors };
    } catch (error) {
      errors.push(`UPDATE_MANIFEST_JSON 解析失败 → ${error.message}`);
    }
  }

  // 3) 代理 GitHub Releases API（无 update.json 附件时的最后兜底，注意它也可能很慢）
  const repo = process.env.GITHUB_REPO;
  if (!repo) {
    throw new Error(
      errors.length ? `清单获取失败：${errors.join(" | ")}` : "未配置 UPDATE_MANIFEST_URL / UPDATE_MANIFEST_JSON / GITHUB_REPO 任一清单源",
    );
  }
  const release = await fetchJsonWithTimeout(
    `https://api.github.com/repos/${repo}/releases/latest`,
    FETCH_TIMEOUT_MS,
    { Accept: "application/vnd.github+json", "User-Agent": "lixing-update-check" },
  );
  const asset = (release.assets || []).find((item) => item.name.endsWith(".apk"));
  const manifestAsset = (release.assets || []).find((item) => item.name === "update.json");

  if (manifestAsset && manifestAsset.browser_download_url) {
    try {
      return normalizeManifest(
        await fetchJsonWithTimeout(manifestAsset.browser_download_url, FETCH_TIMEOUT_MS),
      );
    } catch (error) {
      errors.push(`清单附件拉取失败 → ${error.message}`);
    }
  }

  if (!asset) throw new Error("Release 里没有 APK");
  return {
    ok: true,
    versionCode: Number(release.tag_name.replace(/[^0-9]/g, "")) || 0,
    versionName: String(release.tag_name || ""),
    apkUrl: asset.browser_download_url,
    sha256: null,
    sizeBytes: asset.size || 0,
    changelog: release.body || "",
    force: false,
  };
}

/** 统一字段与类型，缺字段时给出可判定的默认值，避免 App 端解析歧义。 */
function normalizeManifest(data) {
  const versionCode = Number(data.versionCode);
  // App 端 UpdateManifest 读的是 sizeBytes（兼容旧字段名 size）。
  const sizeBytes = Number(data.sizeBytes ?? data.size) || 0;
  return {
    ok: true,
    versionCode: Number.isFinite(versionCode) ? versionCode : 0,
    versionName: String(data.versionName || ""),
    apkUrl: String(data.apkUrl || ""),
    sha256: data.sha256 ? String(data.sha256).toLowerCase() : null,
    sizeBytes,
    changelog: String(data.changelog || ""),
    force: Boolean(data.force),
  };
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
