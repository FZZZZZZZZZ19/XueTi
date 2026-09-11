package com.xueti.learn.update

import com.xueti.learn.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 直连 GitHub 仓库 Release 检查新版本。
 * 数据源：GET https://api.github.com/repos/{owner}/{repo}/releases/latest
 */
object UpdateChecker {

    private const val OWNER = "FZZZZZZZZZ19"
    private const val REPO = "XueTi"
    private const val API_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    data class ReleaseInfo(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val pageUrl: String,
        val apkSizeBytes: Long
    )

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 拉取最新 Release 信息（网络异常时返回失败） */
    suspend fun fetchLatest(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "XueTi-Android")
            }
            try {
                val code = connection.responseCode
                if (code != 200) error("HTTP $code")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(text)
                val version = obj.optString("tag_name").trim().removePrefix("v").removePrefix("V")

                var apkUrl = ""
                var apkSize = 0L
                obj.optJSONArray("assets")?.let { assets ->
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            apkSize = asset.optLong("size")
                            break
                        }
                    }
                }
                ReleaseInfo(
                    version = version,
                    notes = obj.optString("body").trim(),
                    apkUrl = apkUrl,
                    pageUrl = obj.optString("html_url"),
                    apkSizeBytes = apkSize
                )
            } finally {
                runCatching { connection.disconnect() }
            }
        }
    }

    /** 语义化版本比较：latest 是否比 current 新 */
    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').mapNotNull { it.trim().toIntOrNull() }
        val b = current.split('.').mapNotNull { it.trim().toIntOrNull() }
        if (a.isEmpty()) return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
