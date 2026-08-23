package com.example.medreminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自启接收器
 *
 * 手机重启、App 更新或系统时间变化后，核对跨日任务并重建全部闹钟。
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
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                DrugStore.reconcilePastOccurrences(context)
                DrugStore.getAllDrugs(context).forEach {
                    ReminderManager.cancelAllAlarms(context, it.id)
                }
                ReminderManager.scheduleAllEnabledAlarms(context)
                ReminderManager.catchUpToday(context)
            }
        }
    }
}
