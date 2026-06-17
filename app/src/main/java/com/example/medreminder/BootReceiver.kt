package com.example.medreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器
 *
 * 手机重启后，所有 AlarmManager 注册的闹钟都会丢失。
 * 监听 BOOT_COMPLETED 广播，重启后自动重新注册闹钟。
 *
 * 同时监听 MY_PACKAGE_REPLACED：App 更新后闹钟也会丢，需要重新注册。
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "收到广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 重新注册每日闹钟
                if (ReminderManager.isEnabled(context)) {
                    Log.d(TAG, "提醒已开启，重新注册闹钟")
                    ReminderManager.scheduleDailyAlarm(context)
                } else {
                    Log.d(TAG, "提醒未开启，不注册闹钟")
                }
            }
        }
    }
}
