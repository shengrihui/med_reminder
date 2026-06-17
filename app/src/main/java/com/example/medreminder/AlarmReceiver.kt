package com.example.medreminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 闹钟接收器
 *
 * 闹钟到点触发时进入这里。处理流程：
 * 1. 检查今天是否已吃药 → 已吃：跳过，注册下一次每日闹钟
 * 2. 没吃 → 发送通知，并注册 N 分钟后的重复提醒
 * 3. 注册下一次每日闹钟（按间隔天数）
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "med_reminder_channel"

        /**
         * 判断这次触发是"每日检查"还是"重复提醒"。
         *
         * 通过 Intent 的 action 区分：
         * - 每日检查：action = ACTION_DAILY_CHECK
         * - 重复提醒：action = ACTION_REPEAT
         *
         * 但因为我们用的是同一个 receiver，且没显式 setAction，
         * 所以这里统一按"检查逻辑"处理即可（已吃就跳过，没吃就提醒）。
         */
        const val ACTION_DAILY_CHECK = "com.example.medreminder.DAILY_CHECK"
        const val ACTION_REPEAT = "com.example.medreminder.REPEAT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "收到闹钟广播: action=${intent.action}")

        // 如果提醒被关了，啥也不做
        if (!ReminderManager.isEnabled(context)) {
            Log.d(TAG, "提醒未开启，忽略")
            return
        }

        // 1. 检查今天是否已吃药
        if (ReminderManager.isTakenToday(context)) {
            Log.d(TAG, "今天已吃药，跳过提醒")
            // 注册下一次每日闹钟
            ReminderManager.scheduleDailyAlarm(context)
            return
        }

        // 2. 没吃 → 发通知
        showNotification(context)

        // 3. 注册 N 分钟后的重复提醒
        val repeatMinutes = ReminderManager.getRepeatMinutes(context)
        if (repeatMinutes > 0) {
            ReminderManager.scheduleRepeatAlarm(context, repeatMinutes)
        }

        // 4. 注册下一次每日闹钟（按间隔天数）
        // 注意：重复提醒是"今天之内"的多次提醒，每日闹钟是"明天及以后"的检查
        // 这里先简化处理：每日闹钟按间隔天数往后推
        scheduleNextDailyByInterval(context)
    }

    /**
     * 按间隔天数注册下一次每日闹钟
     *
     * 简化逻辑：从设定的检查时间起，往后推 intervalDays 天。
     * 如果今天还没到检查时间，就设今天；否则设 intervalDays 天后。
     */
    private fun scheduleNextDailyByInterval(context: Context) {
        val hour = ReminderManager.getHour(context)
        val minute = ReminderManager.getMinute(context)
        val intervalDays = ReminderManager.getIntervalDays(context)

        val calendar = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            // 往后推 intervalDays 天
            add(java.util.Calendar.DAY_OF_MONTH, intervalDays)
        }

        // 如果推完后时间已经过了（不太可能，但保险起见），再推一个间隔
        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(java.util.Calendar.DAY_OF_MONTH, intervalDays)
        }

        // 直接调用底层注册（绕过 scheduleDailyAlarm 的"今天"逻辑）
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    android.app.AlarmManager.RTC_WAKEUP,
                    calendar.timeInMillis,
                    pendingIntent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                pendingIntent
            )
        }

        Log.d(TAG, "已注册下一次每日闹钟，间隔${intervalDays}天后")
    }

    /**
     * 显示通知
     *
     * 通知上带一个"已吃药"按钮，点击后会触发 NotificationActionReceiver。
     */
    private fun showNotification(context: Context) {
        val medName = ReminderManager.getMedName(context)

        // 1. 创建通知渠道（Android 8.0+ 必须）
        createNotificationChannel(context)

        // 2. 点击通知本身打开 App
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 3. "已吃药"按钮的 PendingIntent
        val takenIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_TAKEN
        }
        val takenPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            takenIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 4. "稍后提醒"按钮的 PendingIntent
        val laterIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_LATER
        }
        val laterPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            laterIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 5. 构建通知
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_med)        // 通知小图标（必须）
            .setContentTitle("吃药提醒")
            .setContentText("该吃${medName}了，记得吃药哦～")
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // 高优先级，会横幅弹出
            .setAutoCancel(true)                    // 点击后自动消失
            .setContentIntent(openPendingIntent)    // 点击通知本身
            .addAction(R.drawable.ic_med, "已吃药", takenPendingIntent)  // 按钮1
            .addAction(R.drawable.ic_med, "稍后提醒", laterPendingIntent) // 按钮2
            .build()

        // 6. 发送通知
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ReminderManager.NOTIFICATION_ID, notification)

        Log.d(TAG, "已发送吃药通知")
    }

    /**
     * 创建通知渠道（Android 8.0+ 必须）
     *
     * 渠道创建后可以重复调用，系统会忽略重复创建。
     */
    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "吃药提醒",
                NotificationManager.IMPORTANCE_HIGH  // 高重要级，会发声+弹出
            ).apply {
                description = "按时吃药提醒通知"
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
