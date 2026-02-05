import { serve } from "https://deno.land/std@0.190.0/http/server.ts";

// 配置信息
const GITHUB_REPO = "INK666/myTranslate";
const CACHE_TTL = 300000; // 5分钟缓存，防止 GitHub API 限流

let cache: { data: any; timestamp: number } | null = null;

async function fetchLatestRelease() {
    // 检查缓存
    if (cache && Date.now() - cache.timestamp < CACHE_TTL) {
        console.log("Serving from cache");
        return cache.data;
    }

    try {
        const response = await fetch(`https://api.github.com/repos/${GITHUB_REPO}/releases/latest`, {
            headers: {
                "Accept": "application/vnd.github.v3+json",
                "User-Agent": "Deno-Update-Server"
            }
        });

        if (!response.ok) {
            throw new Error(`GitHub API error: ${response.statusText}`);
        }

        const release = await response.json();

        // 解析信息
        const rawUrl = release.assets.find((a: any) => a.name.endsWith(".apk"))?.browser_download_url;

        // 使用 moeyy.cn 加速镜像（国内满速下载的最佳实践之一）
        const download_url = rawUrl ? `https://moeyy.cn/gh-proxy/${rawUrl}` : "";

        const updateInfo = {
            version_name: release.tag_name.replace('v', ''),
            version_code: parseVersionCode(release.tag_name),
            changelog: release.body,
            download_url: download_url,
            published_at: release.published_at
        };

        cache = { data: updateInfo, timestamp: Date.now() };
        return updateInfo;
    } catch (error) {
        console.error("Fetch error:", error);
        return null;
    }
}

// 辅助函数：根据 tag 尝试解析 versionCode
// 建议你的 GitHub Tag 规范化，例如 "v1.4.3" -> 10403
function parseVersionCode(tagName: string): number {
    const cleanTag = tagName.replace(/[^0-9.]/g, '');
    const parts = cleanTag.split('.').map(Number);

    const major = parts[0] || 0;
    const minor = parts[1] || 0;
    const patch = parts[2] || 0;

    // 必须和本地 104000 的逻辑保持一致
    // 1.4 -> 1 * 100000 + 4 * 1000 + 0 = 104000
    return major * 100000 + minor * 1000 + patch;
}

console.log("Deno server starting on http://localhost:8000");

serve(async (req) => {
    const url = new URL(req.url);

    // 1. 版本检测接口
    if (url.pathname === "/check") {
        const data = await fetchLatestRelease();
        if (!data) {
            return new Response(JSON.stringify({ error: "Failed to fetch update info" }), {
                status: 500,
                headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" }
            });
        }
        return new Response(JSON.stringify(data), {
            headers: { "Content-Type": "application/json", "Access-Control-Allow-Origin": "*" }
        });
    }

    // 2. 根路径欢迎页
    return new Response(`
    <html>
      <head><title>Update Server</title></head>
      <body style="font-family: sans-serif; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100vh; background: #f0f2f5;">
        <div style="padding: 2rem; background: white; border-radius: 12px; shadow: 0 4px 6px rgba(0,0,0,0.1);">
          <h1>🚀 myTranslate Update Server</h1>
          <p>Status: <span style="color: green;">Online</span></p>
          <p>Endpoint: <a href="/check">/check</a></p>
          <hr/>
          <small>Connecting to: ${GITHUB_REPO}</small>
        </div>
      </body>
    </html>
  `, {
        headers: { "Content-Type": "text/html" }
    });
});
