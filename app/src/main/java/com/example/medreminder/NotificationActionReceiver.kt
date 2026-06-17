package com.example.medreminder

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 通知按钮处理接收器
 *
 * 处理通知上的两个按钮：
 * - "已吃药"：标记今天已吃，取消通知和重复提醒
 * - "稍后提醒"：取消当前通知，等重复闹钟到点再提醒
 */
class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotiActionReceiver"

        const val ACTION_TAKEN = "com.example.medreminder.ACTION_TAKEN"   // 已吃药
        const val ACTION_LATER = "com.example.medreminder.ACTION_LATER"   // 稍后提醒
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TAKEN -> handleTaken(context)
            ACTION_LATER -> handleLater(context)
        }
    }

    /**
     * 处理"已吃药"按钮
     *
     * 1. 标记今天已吃药
     * 2. 取消当前通知
     * 3. 取消重复提醒闹钟
     * 4. 注册明天的每日闹钟
     */
    private fun handleTaken(context: Context) {
        Log.d(TAG, "用户点击了'已吃药'")

        // 1. 标记今天已吃
        ReminderManager.markTakenToday(context)

        // 2. 取消通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ReminderManager.NOTIFICATION_ID)

        // 3. 取消重复提醒闹钟
        ReminderManager.cancelRepeatAlarm(context)

        // 4. 注册明天的每日闹钟
        ReminderManager.scheduleDailyAlarm(context)

        Log.d(TAG, "已标记今天吃药完成，下次提醒已安排")
    }

    /**
     * 处理"稍后提醒"按钮
     *
     * 1. 取消当前通知（等重复闹钟到点再发）
     * 2. 重复闹钟会按设定的间隔自动触发，这里不用额外操作
     */
    private fun handleLater(context: Context) {
        Log.d(TAG, "用户点击了'稍后提醒'")

        // 取消当前通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ReminderManager.NOTIFICATION_ID)

        // 重复闹钟已经注册过了，会按间隔自动触发，不用再注册
        // 如果想立即重新设定一个，可以取消再重设：
        // val repeatMinutes = ReminderManager.getRepeatMinutes(context)
        // ReminderManager.scheduleRepeatAlarm(context, repeatMinutes)

        Log.d(TAG, "通知已关闭，等待重复提醒")
    }
}
