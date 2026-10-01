package com.lm.player.core.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.lm.player.MainActivity

/**
 * 安卓系统开机自启动广播接收器
 * 当用户在设置中开启「安卓系统启动后自动启动软件」时，系统开机完成后自动拉起 LMPlayer 主界面与后台音频服务。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
        const val PREFS_NAME = "zds_auto_play_prefs"
        const val KEY_AUTO_LAUNCH_ON_BOOT = "auto_launch_on_boot"

        fun isAutoLaunchOnBootEnabled(context: Context): Boolean {
            return try {
                val appCtx = context.applicationContext
                val userUnlocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val um = appCtx.getSystemService(Context.USER_SERVICE) as? UserManager
                    um?.isUserUnlocked != false
                } else {
                    true
                }
                if (userUnlocked) {
                    val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.getBoolean(KEY_AUTO_LAUNCH_ON_BOOT, false)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val dpCtx = appCtx.createDeviceProtectedStorageContext()
                    dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(KEY_AUTO_LAUNCH_ON_BOOT, false)
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read auto_launch_on_boot preference", e)
                false
            }
        }

        fun setAutoLaunchOnBootEnabled(context: Context, enabled: Boolean) {
            try {
                val appCtx = context.applicationContext
                appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_LAUNCH_ON_BOOT, enabled)
                    .apply()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val dpCtx = appCtx.createDeviceProtectedStorageContext()
                    dpCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_AUTO_LAUNCH_ON_BOOT, enabled)
                        .apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist auto_launch_on_boot preference", e)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        // 若处于 Direct Boot 锁屏未解锁阶段，等待解锁后的 ACTION_BOOT_COMPLETED 再拉起主界面与数据库
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
            if (um?.isUserUnlocked == false) {
                Log.i(TAG, "Device still locked on $action, waiting for unlocked BOOT_COMPLETED")
                return
            }
        }

        val autoLaunch = isAutoLaunchOnBootEnabled(context)
        Log.i(TAG, "Received boot broadcast ($action), autoLaunchOnBoot=$autoLaunch")
        if (!autoLaunch) return

        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val serviceIntent = Intent(appContext, PlaybackService::class.java)
                ContextCompat.startForegroundService(appContext, serviceIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Start PlaybackService on boot failed", e)
            }

            try {
                val activityIntent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra("from_boot_completed", true)
                }
                appContext.startActivity(activityIntent)
                Log.i(TAG, "Launched MainActivity on system boot completed")
            } catch (e: Exception) {
                Log.e(TAG, "Launch MainActivity on boot failed", e)
            }
        }, 1500L)
    }
}
