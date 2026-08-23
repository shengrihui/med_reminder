package com.example.medreminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

/** 接收固定提醒、重复提醒、开机补发和跨日截止。 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val CHANNEL_ID = "med_reminder_channel_v3"
        private const val OLD_CHANNEL_ID = "med_reminder_channel_v2"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val drugId = intent.getIntExtra(ReminderManager.EXTRA_DRUG_ID, -1)
        val scheduleKey = intent.getIntExtra(ReminderManager.EXTRA_SCHEDULE_KEY, -1)
        val dateStr = intent.getStringExtra(ReminderManager.EXTRA_DATE) ?: return
        val time = ReminderTime(
            intent.getIntExtra(ReminderManager.EXTRA_HOUR, -1),
            intent.getIntExtra(ReminderManager.EXTRA_MINUTE, -1)
        )
        if (drugId < 0 || scheduleKey < 0 || time.hour !in 0..23 || time.minute !in 0..59) return

        val drug = DrugStore.getDrug(context, drugId) ?: return
        val scheduleIndex = drug.schedules.indexOfFirst { it.scheduleKey == scheduleKey }
        val schedule = drug.schedules.getOrNull(scheduleIndex) ?: return
        val action = intent.action

        if (action == ReminderManager.ACTION_SCHEDULED) {
            if (time !in schedule.reminderTimes) return
            // 固定闹钟是一次性的，先安排同一时间点的下一次有效日期。
            ReminderManager.scheduleNextReminder(context, drug, schedule, time)
        }

        if (action == ReminderManager.ACTION_EXPIRE) {
            expireOccurrence(context, drug, schedule, dateStr)
            return
        }

        val today = DrugStore.todayString()
        if (dateStr != today) {
            // 延迟到跨日才送达的旧广播只能记为错过，不能提示用户现在服药。
            if (dateStr < today) DrugStore.markMissed(context, drugId, scheduleKey, dateStr)
            clearOccurrence(context, drugId, scheduleKey, dateStr)
            return
        }
        if (!drug.enabled || !drug.isScheduledOn(dateStr)) return
        if (DrugStore.isCompletedOn(context, drugId, scheduleKey, dateStr)) {
            clearOccurrence(context, drugId, scheduleKey, dateStr)
            return
        }

        val isCatchUp = action == ReminderManager.ACTION_CATCH_UP
        showNotification(context, drug, schedule, scheduleIndex, dateStr, time, isCatchUp)
        ReminderManager.scheduleExpiryAlarm(context, drugId, scheduleKey, dateStr, time)
        ReminderManager.scheduleRepeatAlarm(context, drug, schedule, dateStr, time)
    }

    private fun expireOccurrence(context: Context, drug: Drug, schedule: DoseSchedule, dateStr: String) {
        if (!DrugStore.isCompletedOn(context, drug.id, schedule.scheduleKey, dateStr)) {
            DrugStore.markMissed(context, drug.id, schedule.scheduleKey, dateStr)
        }
        clearOccurrence(context, drug.id, schedule.scheduleKey, dateStr)
    }

    private fun clearOccurrence(context: Context, drugId: Int, scheduleKey: Int, dateStr: String) {
        ReminderManager.cancelOccurrenceAlarms(context, drugId, scheduleKey, dateStr)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(ReminderManager.notificationId(drugId, scheduleKey, dateStr))
    }

    private fun showNotification(
        context: Context,
        drug: Drug,
        schedule: DoseSchedule,
        scheduleIndex: Int,
        dateStr: String,
        sourceTime: ReminderTime,
        isCatchUp: Boolean
    ) {
        createNotificationChannel(context)
        val notificationId = ReminderManager.notificationId(drug.id, schedule.scheduleKey, dateStr)
        val label = schedule.displayName(scheduleIndex)
        val times = schedule.reminderTimes.sortedBy { it.hour * 60 + it.minute }
            .joinToString("、") { it.format() }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val takenPending = actionPendingIntent(
            context, NotificationActionReceiver.ACTION_TAKEN, notificationId * 10 + 1,
            drug.id, schedule.scheduleKey, dateStr, sourceTime
        )
        val laterPending = actionPendingIntent(
            context, NotificationActionReceiver.ACTION_LATER, notificationId * 10 + 2,
            drug.id, schedule.scheduleKey, dateStr, sourceTime
        )

        val prefix = if (isCatchUp) "关机期间错过提醒：" else ""
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_med)
            .setContentTitle("用药提醒 · $label")
            .setContentText("$prefix${drug.name}（提醒时间 $times），请确认是否已用药")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$prefix${drug.name}的${label}尚未确认。提醒时间：$times。"
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openPending)
            .setTimeoutAfter(millisUntilMidnight())
            .addAction(R.drawable.ic_med, "已用药", takenPending)
            .addAction(R.drawable.ic_med, "稍后提醒", laterPending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId, notification)
        Log.d(TAG, "[${drug.name} $label $dateStr] 已发送通知")
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
        drugId: Int,
        scheduleKey: Int,
        dateStr: String,
        time: ReminderTime
    ): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            data = Uri.parse("medreminder://notification/${action.substringAfterLast('.')}/$drugId/$scheduleKey/$dateStr")
            putExtra(ReminderManager.EXTRA_DRUG_ID, drugId)
            putExtra(ReminderManager.EXTRA_SCHEDULE_KEY, scheduleKey)
            putExtra(ReminderManager.EXTRA_DATE, dateStr)
            putExtra(ReminderManager.EXTRA_HOUR, time.hour)
            putExtra(ReminderManager.EXTRA_MINUTE, time.minute)
        }
        return PendingIntent.getBroadcast(
            context, requestCode and 0x7fffffff, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun millisUntilMidnight(): Long {
        val now = System.currentTimeMillis()
        val midnight = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        return (midnight - now).coerceAtLeast(1_000L)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        } catch (_: Exception) {
            // 删除旧渠道失败不影响新渠道创建。
        }
        val channel = NotificationChannel(
            CHANNEL_ID, "用药提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "按时用药提醒通知"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 300, 500)
            enableLights(true)
            lightColor = Color.RED
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
            val soundUri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            val attributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            setSound(soundUri, attributes)
        }
        manager.createNotificationChannel(channel)
    }
}
