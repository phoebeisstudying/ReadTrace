package com.dmer.neoreaderrecords

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    private const val CRASH_LOG_NAME = "readtrace_crash_log.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrashLog(appContext, thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val text = buildCrashText(thread, throwable)
        val wrotePublic = runCatching {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) error("cannot create ${dir.absolutePath}")
            File(dir, CRASH_LOG_NAME).writeText(text, Charsets.UTF_8)
        }.isSuccess

        if (!wrotePublic) {
            runCatching {
                File(context.filesDir, CRASH_LOG_NAME).writeText(text, Charsets.UTF_8)
            }
        }
    }

    private fun buildCrashText(thread: Thread, throwable: Throwable): String {
        val stack = StringWriter().also { sw ->
            PrintWriter(sw).use { pw -> throwable.printStackTrace(pw) }
        }.toString()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append("time=").append(now).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("device=").append(Build.MANUFACTURER).append(' ')
                .append(Build.BRAND).append(' ')
                .append(Build.MODEL).append('\n')
            append("android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("exception=").append(throwable.javaClass.name)
                .append(": ").append(throwable.message.orEmpty()).append('\n')
            append('\n')
            append(stack)
        }
    }
}
