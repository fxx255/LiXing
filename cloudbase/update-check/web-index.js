"use strict";

/**
 * 版本更新清单服务 —— Web 函数版（备用，与事件函数版二选一）。
 *
 * 对应控制台「通过模板创建 → HTTP Node.js Hello World」的工程结构：
 *   scf_bootstrap   启动脚本（模板自带，保持原样，等价于 node index.js）
 *   index.js        部署时用「本文件内容」覆盖模板的 index.js —— 注意文件名仍为 index.js
 *   package.json    模板自带，保持原样（本文件只用 Node 内置模块，无需安装依赖）
 *
 * 为什么需要服务器写法：HTTP 模板创建的是「Web 服务型」函数，平台把 HTTP 请求
 * 直接转发给本进程监听的端口，exports.main 事件写法在该类型下不会被调用。
 *
 * 环境变量与行为与事件函数版完全一致，见 index.js 头部注释。
 */

const http = require("http");

const CACHE_TTL_MS = 10 * 60 * 1000;

/** 进程内缓存：实例复用期间不再回源，避免每次请求都打 GitHub。 */
let cachedPayload = null;
let cachedAt = 0;

async function handleRequest() {
  const now = Date.now();
  if (cachedPayload && now - cachedAt < CACHE_TTL_MS) {
    return { ...cachedPayload, cached: true };
  }

  try {
    const payload = await fetchManifest();
    cachedPayload = payload;
    cachedAt = now;
    return { ...payload, cached: false };
  } catch (error) {
    console.error("update-check failed:", error && error.message);
    // 有旧缓存就先返回旧的（宁可信息稍旧，也比直接报错打断用户好）
    if (cachedPayload) {
      return { ...cachedPayload, stale: true };
    }
    return { ok: false, error: "暂时无法获取更新信息" };
  }
}

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

// ---------- HTTP 服务器（Web 函数入口，scf_bootstrap 会执行 node index.js） ----------

const server = http.createServer(async (req, res) => {
  if (req.method !== "GET" && req.method !== "HEAD") {
    res.writeHead(405, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ ok: false, error: "仅支持 GET" }));
    return;
  }
  try {
    const payload = await handleRequest();
    res.writeHead(200, {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
    });
    res.end(JSON.stringify(payload));
  } catch (error) {
    console.error("update-check handler error:", error && error.message);
    res.writeHead(502, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ ok: false, error: "暂时无法获取更新信息" }));
  }
});

const port = Number(process.env.PORT) || 9000;
server.listen(port, () => {
  console.log(`update-check listening on :${port}`);
});

// ---------- 事件函数兜底（Web 函数里一般用不到，保留以便本地 node 直调测试） ----------

exports.main = async () => {
  try {
    const payload = await handleRequest();
    return {
      statusCode: 200,
      headers: { "Content-Type": "application/json; charset=utf-8" },
      body: JSON.stringify(payload),
      isBase64Encoded: false,
    };
  } catch (error) {
    return {
      statusCode: 502,
      headers: { "Content-Type": "application/json; charset=utf-8" },
      body: JSON.stringify({ ok: false, error: "暂时无法获取更新信息" }),
      isBase64Encoded: false,
    };
  }
};
