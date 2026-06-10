package com.dmer.neoreaderrecords

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object WeReadClient {
    private const val PREFS_NAME = "weread_settings"
    private const val KEY_API_KEY = "weread_api_key"
    private const val KEY_STATUS = "weread_status"
    private const val KEY_LAST_TEST_MS = "weread_last_test_ms"
    private const val KEY_ERROR = "weread_error"
    private const val API_GATEWAY = "https://i.weread.qq.com/api/agent/gateway"
    private const val SKILL_VERSION = "1.0.3"

    data class State(
        val maskedKey: String,
        val status: String,
        val lastTestMs: Long,
        val error: String
    )

    data class TestResult(
        val ok: Boolean,
        val status: String,
        val detail: String,
        val bookCount: Int,
        val albumCount: Int,
        val hasMp: Boolean
    )

    fun loadApiKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "")
            .orEmpty()
    }

    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun cachedState(context: Context): State {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return State(
            maskedKey = maskKey(p.getString(KEY_API_KEY, "").orEmpty()),
            status = p.getString(KEY_STATUS, "尚未测试") ?: "尚未测试",
            lastTestMs = p.getLong(KEY_LAST_TEST_MS, 0L),
            error = p.getString(KEY_ERROR, "") ?: ""
        )
    }

    fun maskKey(apiKey: String): String {
        val key = apiKey.trim()
        if (key.isBlank()) return "未配置"
        if (key.length <= 10) return "${key.take(3)}***"
        return "${key.take(6)}...${key.takeLast(4)}"
    }

    fun testConnection(context: Context, apiKey: String): TestResult {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return saveFailure(context, "未配置 API Key")
        }
        AutoRefreshLog.i(context, "WeRead test start key=${maskKey(key)}")
        return try {
            val body = JSONObject()
                .put("api_name", "/shelf/sync")
                .put("skill_version", SKILL_VERSION)
                .toString()
            val result = postJson(key, body)
            AutoRefreshLog.i(context, "WeRead test http code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) {
                return saveFailure(context, "HTTP ${result.code}: ${result.body.take(120)}")
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return saveFailure(context, "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}")
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return saveFailure(context, "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}")
            }
            val books = json.optJSONArray("books")
            val albums = json.optJSONArray("albums")
            val mp = json.opt("mp")
            val bookCount = books?.length() ?: 0
            val albumCount = albums?.length() ?: 0
            val hasMp = mp != null && mp != JSONObject.NULL
            val total = bookCount + albumCount + if (hasMp) 1 else 0
            saveSuccess(
                context,
                TestResult(
                    ok = true,
                    status = "连接成功",
                    detail = "书架可见条目 $total 个：电子书 $bookCount，专辑/有声书 $albumCount，文章收藏 ${if (hasMp) "有" else "无"}",
                    bookCount = bookCount,
                    albumCount = albumCount,
                    hasMp = hasMp
                )
            )
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead test failed", e)
            saveFailure(context, "${e.javaClass.simpleName}: ${e.message ?: "测试失败"}")
        }
    }

    private data class HttpResult(val code: Int, val body: String)

    private fun postJson(apiKey: String, body: String): HttpResult {
        val conn = (URL(API_GATEWAY).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ReadTrace-Wallpaper/WeRead")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun saveSuccess(context: Context, result: TestResult): TestResult {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, result.status)
            .putLong(KEY_LAST_TEST_MS, System.currentTimeMillis())
            .putString(KEY_ERROR, "")
            .apply()
        AutoRefreshLog.i(context, "WeRead test success ${result.detail}")
        return result
    }

    private fun saveFailure(context: Context, error: String): TestResult {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, "连接失败")
            .putLong(KEY_LAST_TEST_MS, System.currentTimeMillis())
            .putString(KEY_ERROR, error)
            .apply()
        return TestResult(false, "连接失败", error, 0, 0, false)
    }
}
