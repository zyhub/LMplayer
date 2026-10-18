package com.lm.player.core.crash

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.lm.player.core.security.LogRedactor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Java 崩溃写入 filesDir；native SIGSEGV 无法被 JVM handler 捕获，
 * 因此用 session marker 判断上次是否异常退出，并在下次启动抓取 logcat。
 */
object CrashLogManager {
    private const val TAG = "CrashLogManager"
    private const val JAVA_LOG = "crash_java.txt"
    private const val LOGCAT_LOG = "crash_logcat.txt"
    private const val MARKER = "session.running"
    private const val MAX_LOG = 180_000

    fun install(context: Context) {
        val app = context.applicationContext
        val files = app.filesDir
        val marker = File(files, MARKER)
        val hadUncleanExit = marker.exists()
        if (hadUncleanExit && !File(files, JAVA_LOG).exists()) {
            runCatching { captureLogcat(files) }
        }
        Log.i(TAG, "SESSION_START_" + System.currentTimeMillis())
        runCatching { marker.writeText(System.currentTimeMillis().toString()) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeJavaCrash(app, thread, throwable) }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }

    /** 正常退出时清除 marker；被系统杀死/闪退时 marker 会保留。 */
    fun markCleanExit(context: Context) {
        runCatching { File(context.applicationContext.filesDir, MARKER).delete() }
    }

    fun consumePending(context: Context): String? {
        val files = context.applicationContext.filesDir
        val java = File(files, JAVA_LOG)
        val logcat = File(files, LOGCAT_LOG)
        val result = when {
            java.exists() -> java.readText().take(MAX_LOG)
            logcat.exists() -> logcat.readText().take(MAX_LOG)
            else -> null
        }
        runCatching { java.delete() }
        runCatching { logcat.delete() }
        if (result != null) {
            // 已经消费过的崩溃不能在下次启动重复弹出；写回 marker，并打一条新的
            // session 起点，避免下次启动把已展示过的 logcat 崩溃再次识别成新崩溃。
            val now = System.currentTimeMillis()
            Log.i(TAG, "SESSION_START_" + now)
            runCatching { File(files, MARKER).writeText(now.toString()) }
        }
        return result?.let { LogRedactor.text(it) }?.takeIf { it.isNotBlank() }
    }

    private fun writeJavaCrash(context: Context, thread: Thread, throwable: Throwable) {
        val header = buildString {
            appendLine("LMPlayer 崩溃日志")
            appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            appendLine("版本: " + versionName(context))
            appendLine("设备: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            appendLine("线程: " + thread.name + " (id=" + thread.id + ")")
            appendLine()
        }
        val body = LogRedactor.text(header + Log.getStackTraceString(throwable))
        File(context.filesDir, JAVA_LOG).writeText(body.take(MAX_LOG))
        Log.e(TAG, "saved java crash log", throwable)
    }

    private fun captureLogcat(files: File) {
        val process = ProcessBuilder("logcat", "-d", "-t", "1200").redirectErrorStream(true).start()
        val raw = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        val allLines = raw.lineSequence().toList()
        // 只取上一次会话开始之后的日志，避免把历史崩溃误报成本次崩溃。
        val sessionStart = allLines.indexOfLast { it.contains("SESSION_START_") }
        if (sessionStart < 0) return
        val interesting = allLines.drop(sessionStart)
            .filter {
                it.contains("FATAL EXCEPTION") || it.contains("Fatal signal") ||
                    it.contains("SIGSEGV") || it.contains("SIGABRT") ||
                    it.contains("AndroidRuntime") || it.contains("com.lm.player") ||
                    it.contains("DEBUG   ")
            }
            .toList()
            .takeLast(700)
            .joinToString("\n")
        if (interesting.contains("FATAL") || interesting.contains("SIGSEGV") ||
            interesting.contains("SIGABRT") || interesting.contains("Fatal signal")
        ) {
            File(files, LOGCAT_LOG).writeText(LogRedactor.text(interesting).take(MAX_LOG))
            Log.w(TAG, "captured native crash logcat")
        }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")
}
