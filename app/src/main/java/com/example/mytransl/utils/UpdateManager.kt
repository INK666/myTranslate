package com.example.mytransl.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.mytransl.BuildConfig
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

object UpdateManager {
    private const val TAG = "UpdateManager"
    // 部署后替换为你的 Deno Deploy 实际域名
    private const val UPDATE_URL = "https://rare-rhino-49.deno.dev/check"

    fun checkUpdate(context: Context, onNewVersion: (versionName: String, changelog: String, url: String, versionCode: Int) -> Unit) {
        val client = OkHttpClient()
        val request = Request.Builder().url(UPDATE_URL).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to check update", e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.body?.string()?.let { jsonString ->
                    try {
                        val json = JSONObject(jsonString)
                        val serverVersionCode = json.optInt("version_code", 0)
                        val serverVersionName = json.optString("version_name", "0.0")
                        val downloadUrl = json.optString("download_url", "")
                        val changelog = json.optString("changelog", "")

                        // 只要服务器版本号大于当前本地版本号，就触发回调
                        // 具体弹不弹窗，由 AppNav 根据本地持久化的 ignoredVersionCode 决定
                        if (serverVersionCode > BuildConfig.VERSION_CODE) {
                            onNewVersion(serverVersionName, changelog, downloadUrl, serverVersionCode)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Parse error", e)
                    }
                }
            }
        })
    }

    // 引导用户跳转到浏览器下载
    fun downloadUpdate(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        context.startActivity(intent)
    }
}
