package com.dmer.neoreaderrecords

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class AutoRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val hardMinIntervalMs = 60_000L
    private val contentEventMinIntervalMs = 45_000L

    override fun doWork(): Result {
        AutoRefreshLog.i(applicationContext, "Worker.doWork start")
        if (!AutoRefreshConfig.isEnabled(applicationContext)) return Result.success()
        val reason = inputData.getString("reason") ?: "unknown"
        AutoRefreshLog.i(applicationContext, "Worker reason=$reason")
        
        val prefs = applicationContext.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val wallpaperMode = prefs.getString("wallpaper_mode", "STATS") ?: "STATS"
        val now = System.currentTimeMillis()
        val minIntervalMs = maxOf(AutoRefreshConfig.minIntervalMinutes(applicationContext) * 60_000L, hardMinIntervalMs)
        val lastMs = prefs.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        val delta = now - lastMs
        val lastContentMs = prefs.getLong(AutoRefreshConfig.KEY_LAST_CONTENT_TRIGGER_MS, 0L)
        val contentDelta = now - lastContentMs

        var shouldGenerate = true
        var latestBookKey = ""
        val lastBookKey = prefs.getString("auto_last_book_key", "") ?: ""

        if (reason == "book_content_changed") {
            latestBookKey = getLatestBookIdentifier(applicationContext)
            if (contentDelta < contentEventMinIntervalMs) {
                AutoRefreshLog.i(applicationContext, "Worker skip: content_changed interval delta=${contentDelta}ms < $contentEventMinIntervalMs ms")
                shouldGenerate = false
            } else if (latestBookKey.isNotBlank() && latestBookKey == lastBookKey) {
                AutoRefreshLog.i(applicationContext, "Worker skip: content_changed book unchanged ($latestBookKey)")
                shouldGenerate = false
            } else {
                AutoRefreshLog.i(applicationContext, "Worker run: content_changed book from [$lastBookKey] to [$latestBookKey]")
                shouldGenerate = true
            }
        } else if (wallpaperMode == "COVER") {
            // 封面模式：书不变不生成；书变了也要满足最短硬间隔
            latestBookKey = getLatestBookIdentifier(applicationContext)
            if (latestBookKey.isNotBlank() && latestBookKey == lastBookKey) {
                AutoRefreshLog.i(applicationContext, "Worker skip: COVER mode book unchanged ($latestBookKey)")
                shouldGenerate = false
            } else if (delta < hardMinIntervalMs) {
                AutoRefreshLog.i(applicationContext, "Worker skip: COVER mode hard interval delta=${delta}ms < $hardMinIntervalMs ms")
                shouldGenerate = false
            } else {
                AutoRefreshLog.i(applicationContext, "Worker force generation: book changed from [$lastBookKey] to [$latestBookKey]")
                shouldGenerate = true
            }
        } else {
            // 统计模式：统一按配置间隔
            if (delta < minIntervalMs) {
                AutoRefreshLog.i(applicationContext, "Worker skip by debounce: delta=${delta}ms < $minIntervalMs ms")
                shouldGenerate = false
            } else {
                shouldGenerate = true
            }
        }

        if (!shouldGenerate) {
            return Result.success()
        }

        val ok = AutoWallpaperGenerator.generateAndSave(applicationContext, reason)
        if (ok) {
            val editor = prefs.edit()
                .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                .putString("auto_last_book_key", latestBookKey)
            if (reason == "book_content_changed") {
                editor.putLong(AutoRefreshConfig.KEY_LAST_CONTENT_TRIGGER_MS, now)
            }
            editor.apply()
            AutoRefreshLog.i(applicationContext, "Worker success saved")
            return Result.success()
        }
        AutoRefreshLog.i(applicationContext, "Worker failed -> retry")
        return Result.retry()
    }

    private fun getLatestBookIdentifier(context: Context): String {
        return runCatching {
            val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
            context.contentResolver.query(metadataUri, null, null, null, "lastAccess DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex("title")
                    val pathIdx = c.getColumnIndex("nativeAbsolutePath")
                    val title = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else "unknown"
                    val path = if (pathIdx >= 0 && !c.isNull(pathIdx)) c.getString(pathIdx) else "unknown"
                    "${title}_${path}"
                } else {
                    "empty"
                }
            } ?: "empty"
        }.getOrDefault("error")
    }

    companion object {
        fun enqueue(context: Context, reason: String) {
            val delaySeconds = when (reason) {
                "screen_off", "screen_on_prewarm", "book_content_changed" -> 12L
                else -> 0L
            }
            val reqBuilder = OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                .setInputData(androidx.work.Data.Builder().putString("reason", reason).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
            if (delaySeconds > 0) reqBuilder.setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
            val req = reqBuilder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "neoreader_auto_refresh",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
