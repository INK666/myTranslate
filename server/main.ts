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
        // 假设你的 Tag 是 "v1.4"，Asset 中有 APK 文件
        const updateInfo = {
            version_name: release.tag_name.replace('v', ''),
            version_code: parseVersionCode(release.tag_name), // 从 tag 或 body 解析
            changelog: release.body,
            download_url: release.assets.find((a: any) => a.name.endsWith(".apk"))?.browser_download_url,
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
    const parts = tagName.replace('v', '').split('.').map(Number);
    if (parts.length === 2) return parts[0] * 100 + parts[1];
    if (parts.length === 3) return parts[0] * 10000 + parts[1] * 100 + parts[2];
    return 0;
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
