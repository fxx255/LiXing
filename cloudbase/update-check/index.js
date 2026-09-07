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
 * 清单来源三选一（按优先级，通过环境变量配置）：
 *   1. UPDATE_MANIFEST_JSON  直接内联 JSON 字符串（零外部依赖，最适合首次部署/临时应急）；
 *   2. UPDATE_MANIFEST_URL   指向 update.json 的直链（推荐长期方案，GitHub Release 附件）；
 *   3. GITHUB_REPO           形如 user/repo，代理 GitHub Releases API（/releases/latest）自动组装。
 *
 * 行为：
 *   - 进程内缓存 10 分钟，回源失败时宁可返回旧缓存也不报错；
 *   - 任何异常返回 502 + JSON 错误体，App 端按 Result.failure 静默处理。
 *
 * Web 函数版（http.createServer 服务器写法）见同目录 web-index.js，二选一即可。
 */

const CACHE_TTL_MS = 10 * 60 * 1000;

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

async function fetchManifest() {
  // 优先级 1：内联 JSON（首次部署/没有 GitHub 时的零依赖模式）
  const inline = process.env.UPDATE_MANIFEST_JSON;
  if (inline && inline.trim()) {
    try {
      return normalizeManifest(JSON.parse(inline));
    } catch (error) {
      throw new Error(`UPDATE_MANIFEST_JSON 不是合法 JSON: ${error.message}`);
    }
  }

  // 优先级 2：外部直链
  const directUrl = process.env.UPDATE_MANIFEST_URL;
  if (directUrl) {
    const response = await fetch(directUrl, { headers: { Accept: "application/json" } });
    if (!response.ok) throw new Error(`清单源返回 ${response.status}`);
    const data = await response.json();
    return normalizeManifest(data);
  }

  // 优先级 3：代理 GitHub Releases API
  const repo = process.env.GITHUB_REPO;
  if (!repo) throw new Error("未配置 UPDATE_MANIFEST_JSON / UPDATE_MANIFEST_URL / GITHUB_REPO 任一清单源");

  const response = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, {
    headers: { Accept: "application/vnd.github+json", "User-Agent": "lixing-update-check" },
  });
  if (!response.ok) throw new Error(`GitHub API 返回 ${response.status}`);
  const release = await response.json();
  const asset = (release.assets || []).find((item) => item.name.endsWith(".apk"));
  const manifestAsset = (release.assets || []).find((item) => item.name === "update.json");

  if (manifestAsset && manifestAsset.browser_download_url) {
    const manifestResponse = await fetch(manifestAsset.browser_download_url);
    if (manifestResponse.ok) return normalizeManifest(await manifestResponse.json());
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
