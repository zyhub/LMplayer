package com.lm.player.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.lm.player.core.network.NetworkClientFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class UpdateInfo(
    val hasUpdate: Boolean,
    val latestVersion: String,
    val latestVersionCode: Int,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSizeBytes: Long = 0L,
    val isForceUpdate: Boolean = false
)

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    const val CURRENT_VERSION_NAME = "1.0.0"
    const val CURRENT_VERSION_CODE = 1
    const val AUTHOR_NAME = "Zhou"
    const val AUTHOR_EMAIL = "1390999045@qq.com"
    const val APP_DESCRIPTION = "专为车载大屏与移动设备量身打造的高保真无损音乐播放器。专属接入柠檬音乐服务端，支持5大音源全网融合搜索与无损畅听、全盘本地音频深度扫描、智能歌词联动与车载方向盘物理按键硬件级适配。"

    // 预留的 GitHub 仓库全路径 (格式: "用户名/仓库名")
    const val DEFAULT_GITHUB_REPO = "zyhub/LMplayer"

    /**
     * 获取更新偏好配置
     */
    fun getUpdatePrefs(context: Context): android.content.SharedPreferences {
        return context.getSharedPreferences("zds_update_prefs", Context.MODE_PRIVATE)
    }

    /**
     * 检查当前版本或全局是否已被用户设置为「永不更新」
     */
    fun isUpdateIgnored(context: Context, version: String): Boolean {
        val prefs = getUpdatePrefs(context)
        if (prefs.getBoolean("never_update", false)) return true
        val skipVersion = prefs.getString("skip_version", "")
        return skipVersion == version
    }

    /**
     * 设置全局永不自动更新
     */
    fun setNeverUpdate(context: Context, never: Boolean) {
        getUpdatePrefs(context).edit().putBoolean("never_update", never).apply()
    }

    /**
     * 忽略特定版本的自动弹窗更新
     */
    fun setSkipVersion(context: Context, version: String) {
        getUpdatePrefs(context).edit().putString("skip_version", version).apply()
    }

    /**
     * 重置忽略状态 (用于在设置中心手动检查时重新唤醒)
     */
    fun resetUpdateIgnore(context: Context) {
        getUpdatePrefs(context).edit().clear().apply()
    }

    /**
     * 检查新版本 (无缝原生支持 GitHub Releases API 与自定义接口)
     */
    suspend fun checkForUpdates(context: Context, customUrlOrRepo: String? = null): Result<UpdateInfo> = withContext(Dispatchers.IO) {
        try {
            val targetTarget = customUrlOrRepo?.ifBlank { null } ?: DEFAULT_GITHUB_REPO

            if (targetTarget.isNotBlank()) {
                val apiUrl = if (targetTarget.startsWith("http://") || targetTarget.startsWith("https://")) {
                    targetTarget
                } else {
                    // 若传入的是 "owner/repo" 则自动拼接 GitHub 标准 API
                    "https://api.github.com/repos/${targetTarget.trim()}/releases/latest"
                }

                val client = NetworkClientFactory.createOkHttpClient(context)
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", "LMPlayer-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)

                        // 1. 解析 GitHub Releases 标准 JSON
                        if (json.has("tag_name") && json.has("assets")) {
                            val tagName = json.optString("tag_name", "").trim()
                            val cleanVersion = tagName.trimStart('v', 'V')
                            val notes = json.optString("body", "性能优化与功能增强").ifBlank { "版本更新 $tagName" }
                            val assets = json.optJSONArray("assets")
                            var apkDownloadUrl = ""
                            var apkSize = 0L

                            if (assets != null && assets.length() > 0) {
                                for (i in 0 until assets.length()) {
                                    val asset = assets.getJSONObject(i)
                                    val name = asset.optString("name", "").lowercase()
                                    if (name.endsWith(".apk")) {
                                        apkDownloadUrl = asset.optString("browser_download_url", "")
                                        apkSize = asset.optLong("size", 0L)
                                        break
                                    }
                                }
                            }

                            val hasUpdate = isVersionNewer(cleanVersion, CURRENT_VERSION_NAME)
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = cleanVersion,
                                    latestVersionCode = CURRENT_VERSION_CODE + (if (hasUpdate) 1 else 0),
                                    releaseNotes = notes,
                                    downloadUrl = apkDownloadUrl,
                                    apkSizeBytes = apkSize,
                                    isForceUpdate = false
                                )
                            )
                        } else {
                            // 2. 解析通用自定义 API JSON
                            val vCode = json.optInt("versionCode", CURRENT_VERSION_CODE)
                            val vName = json.optString("versionName", CURRENT_VERSION_NAME)
                            val notes = json.optString("releaseNotes", "性能优化与功能增强")
                            val url = json.optString("downloadUrl", "")
                            val size = json.optLong("apkSizeBytes", 0L)
                            val force = json.optBoolean("isForceUpdate", false)

                            val hasUpdate = vCode > CURRENT_VERSION_CODE || isVersionNewer(vName, CURRENT_VERSION_NAME)
                            return@withContext Result.success(
                                UpdateInfo(
                                    hasUpdate = hasUpdate,
                                    latestVersion = vName,
                                    latestVersionCode = vCode,
                                    releaseNotes = notes,
                                    downloadUrl = url,
                                    apkSizeBytes = size,
                                    isForceUpdate = force
                                )
                            )
                        }
                    }
                }
            }

            // 默认返回当前版本状态
            Result.success(
                UpdateInfo(
                    hasUpdate = false,
                    latestVersion = CURRENT_VERSION_NAME,
                    latestVersionCode = CURRENT_VERSION_CODE,
                    releaseNotes = "当前已是最新至臻发布版本 (v$CURRENT_VERSION_NAME)。\n\n1. 柠檬音乐登录后使用全新发现页作为主页（热门推荐歌单、官方权威榜单、新歌首发）\n2. 搜索全面融合在线曲库与本地媒体库，支持多音源无缝切换（酷我/网易云/QQ音乐/酷狗/咪咕）\n3. 资料库纯净还原与柠檬音乐曲目结构映射（剥离推荐歌单）\n4. 设置中心新增在线模式音源切换与偏好记忆\n5. 全局性能调优与沉浸式体验升级",
                    downloadUrl = ""
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check update", e)
            Result.failure(e)
        }
    }

    /**
     * 语义化版本号比较 (例如 "1.2.0" > "1.1.0")
     */
    fun isVersionNewer(latestVersion: String, currentVersion: String): Boolean {
        val lParts = latestVersion.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val cParts = currentVersion.trimStart('v', 'V').split('.').mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(lParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val l = lParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    /**
     * 下载 APK 安装包并在进度回调中通知 UI
     */
    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (progress: Float, downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val updateDir = File(context.cacheDir, "updates").apply { if (!exists()) mkdirs() }
            val apkFile = File(updateDir, "LMPlayer_update.apk")
            if (apkFile.exists()) apkFile.delete()

            val client = NetworkClientFactory.createOkHttpClient(context)
            val request = Request.Builder().url(downloadUrl).build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
                val body = response.body ?: throw Exception("Empty response body")
                val totalLength = body.contentLength()

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)
                val buffer = ByteArray(8 * 1024)
                var bytesRead: Int
                var totalBytesRead = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead
                    val progress = if (totalLength > 0) totalBytesRead.toFloat() / totalLength else 0f
                    withContext(Dispatchers.Main) {
                        onProgress(progress, totalBytesRead, totalLength)
                    }
                }
                outputStream.flush()
                outputStream.close()
                inputStream.close()
            }

            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download APK failed", e)
            Result.failure(e)
        }
    }

    /**
     * 调起系统安装器执行覆盖安装更新
     */
    fun installApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                Toast.makeText(context, "安装包不存在，请重新下载", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val hasInstallPermission = context.packageManager.canRequestPackageInstalls()
                if (!hasInstallPermission) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    Toast.makeText(context, "请授予安装未知应用权限后重试", Toast.LENGTH_LONG).show()
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            Toast.makeText(context, "调起安装器失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
