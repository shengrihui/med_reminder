package com.example.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 以“服药任务”为边界管理计划提醒、重复提醒和跨日截止。 */
object ReminderManager {

    private const val TAG = "ReminderManager"

    const val ACTION_SCHEDULED = "com.example.medreminder.ALARM_SCHEDULED"
    const val ACTION_REPEAT = "com.example.medreminder.ALARM_REPEAT"
    const val ACTION_EXPIRE = "com.example.medreminder.ALARM_EXPIRE"
    const val ACTION_CATCH_UP = "com.example.medreminder.ALARM_CATCH_UP"

    const val EXTRA_DRUG_ID = "drug_id"
    const val EXTRA_SCHEDULE_KEY = "schedule_key"
    const val EXTRA_DATE = "scheduled_date"
    const val EXTRA_HOUR = "hour"
    const val EXTRA_MINUTE = "minute"

    /** 注册一个药品的全部固定提醒时间。 */
    fun scheduleAllAlarms(context: Context, drug: Drug) {
        if (!drug.enabled) return
        drug.schedules.forEach { schedule ->
            schedule.reminderTimes.forEach { time ->
                scheduleNextReminder(context, drug, schedule, time)
            }
        }
    }

    /** 从当前时刻之后寻找该固定时间点的下一次有效服药日。 */
    fun scheduleNextReminder(context: Context, drug: Drug, schedule: DoseSchedule, time: ReminderTime) {
        if (!drug.enabled || time !in schedule.reminderTimes) return
        val now = System.currentTimeMillis()
        val day = startOfDay(Calendar.getInstance())
        for (offset in 0..3660) {
            val candidateDay = (day.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, offset) }
            val date = DrugStore.dateString(candidateDay)
            if (!drug.isScheduledOn(date)) continue
            val trigger = (candidateDay.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, time.hour)
                set(Calendar.MINUTE, time.minute)
            }
            if (trigger.timeInMillis <= now) continue

            val intent = alarmIntent(context, ACTION_SCHEDULED, drug.id, schedule.scheduleKey, date, time)
            setAlarmClock(context, intent, trigger.timeInMillis)
            Log.d(TAG, "[${drug.name} ${schedule.scheduleKey} ${time.format()}] 下次 ${formatTime(trigger.timeInMillis)}")
            return
        }
        Log.w(TAG, "未能为 ${drug.name} ${time.format()} 找到下一次提醒")
    }

    /** 重复提醒只允许存在于所属日期内，绝不跨日继续。 */
    fun scheduleRepeatAlarm(
        context: Context,
        drug: Drug,
        schedule: DoseSchedule,
        dateStr: String,
        sourceTime: ReminderTime
    ) {
        if (drug.repeatMinutes <= 0 || dateStr != DrugStore.todayString()) return
        val triggerAt = System.currentTimeMillis() + drug.repeatMinutes * 60_000L
        if (triggerAt >= nextMidnightMillis()) return
        val intent = alarmIntent(context, ACTION_REPEAT, drug.id, schedule.scheduleKey, dateStr, sourceTime)
        setExactAlarm(context, intent, triggerAt)
    }

    /** 在午夜结束仍未确认的任务，并清除遗留通知。 */
    fun scheduleExpiryAlarm(
        context: Context,
        drugId: Int,
        scheduleKey: Int,
        dateStr: String,
        sourceTime: ReminderTime
    ) {
        if (dateStr != DrugStore.todayString()) return
        val intent = alarmIntent(context, ACTION_EXPIRE, drugId, scheduleKey, dateStr, sourceTime)
        setExactAlarm(context, intent, nextMidnightMillis() + 1_000L)
    }

    fun cancelOccurrenceAlarms(context: Context, drugId: Int, scheduleKey: Int, dateStr: String) {
        cancelPending(context, ACTION_REPEAT, occurrenceUri(ACTION_REPEAT, drugId, scheduleKey, dateStr))
        cancelPending(context, ACTION_EXPIRE, occurrenceUri(ACTION_EXPIRE, drugId, scheduleKey, dateStr))
    }

    fun cancelAllAlarms(context: Context, drugId: Int) {
        getCurrentOrEmpty(context, drugId).forEach { schedule ->
            schedule.reminderTimes.forEach { time ->
                cancelPending(context, ACTION_SCHEDULED, scheduledUri(drugId, schedule.scheduleKey, time))
            }
            val today = DrugStore.todayString()
            cancelOccurrenceAlarms(context, drugId, schedule.scheduleKey, today)
            val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
            cancelOccurrenceAlarms(context, drugId, schedule.scheduleKey, DrugStore.dateString(yesterday))
        }
        cancelLegacyAlarms(context, drugId)
    }

    fun scheduleAllEnabledAlarms(context: Context) {
        DrugStore.getAllDrugs(context).filter { it.enabled }.forEach { scheduleAllAlarms(context, it) }
    }

    /**
     * 开机或应用更新后，为今天关机期间已经越过且尚未完成的服药安排补发一条提醒。
     * 同一次服药即使错过多个提醒点，也只补发一次。
     */
    fun catchUpToday(context: Context): Int {
        val today = DrugStore.todayString()
        val now = Calendar.getInstance()
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        var count = 0
        DrugStore.getAllDrugs(context).filter { it.enabled && it.isScheduledOn(today) }.forEach { drug ->
            drug.schedules.forEach { schedule ->
                if (DrugStore.isCompletedOn(context, drug.id, schedule.scheduleKey, today)) return@forEach
                val latestPassed = schedule.reminderTimes
                    .filter { it.hour * 60 + it.minute <= nowMinutes }
                    .maxByOrNull { it.hour * 60 + it.minute }
                    ?: return@forEach
                context.sendBroadcast(
                    alarmIntent(context, ACTION_CATCH_UP, drug.id, schedule.scheduleKey, today, latestPassed)
                )
                count++
            }
        }
        return count
    }

    fun notificationId(drugId: Int, scheduleKey: Int, dateStr: String): Int =
        positiveHash("notification|$drugId|$scheduleKey|$dateStr")

    private fun getCurrentOrEmpty(context: Context, drugId: Int): List<DoseSchedule> =
        DrugStore.getDrug(context, drugId)?.schedules ?: emptyList()

    private fun alarmIntent(
        context: Context,
        action: String,
        drugId: Int,
        scheduleKey: Int,
        dateStr: String,
        time: ReminderTime
    ): Intent = Intent(context, AlarmReceiver::class.java).apply {
        this.action = action
        data = when (action) {
            ACTION_SCHEDULED -> scheduledUri(drugId, scheduleKey, time)
            else -> occurrenceUri(action, drugId, scheduleKey, dateStr)
        }
        putExtra(EXTRA_DRUG_ID, drugId)
        putExtra(EXTRA_SCHEDULE_KEY, scheduleKey)
        putExtra(EXTRA_DATE, dateStr)
        putExtra(EXTRA_HOUR, time.hour)
        putExtra(EXTRA_MINUTE, time.minute)
    }

    private fun scheduledUri(drugId: Int, scheduleKey: Int, time: ReminderTime): Uri =
        Uri.parse("medreminder://alarm/scheduled/$drugId/$scheduleKey/${time.hour}/${time.minute}")

    private fun occurrenceUri(action: String, drugId: Int, scheduleKey: Int, dateStr: String): Uri {
        val kind = action.substringAfterLast('.').lowercase(Locale.ROOT)
        return Uri.parse("medreminder://alarm/$kind/$drugId/$scheduleKey/$dateStr")
    }

    private fun setAlarmClock(context: Context, intent: Intent, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, null), pendingIntent)
        } catch (securityException: SecurityException) {
            Log.w(TAG, "setAlarmClock 失败，降级到精确闹钟", securityException)
            setExactAlarm(context, intent, triggerAtMillis)
        }
    }

    private fun setExactAlarm(context: Context, intent: Intent, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntent(context, intent, PendingIntent.FLAG_UPDATE_CURRENT)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun pendingIntent(context: Context, intent: Intent, extraFlag: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            positiveHash(intent.dataString ?: intent.action.orEmpty()),
            intent,
            PendingIntent.FLAG_IMMUTABLE or extraFlag
        )

    private fun cancelPending(context: Context, action: String, uri: Uri) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            data = uri
        }
        val pending = PendingIntent.getBroadcast(
            context,
            positiveHash(uri.toString()),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        ) ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        manager.cancel(pending)
        pending.cancel()
    }

    /** 清理 v0.5.x 使用的闹钟身份，避免升级后旧闹钟与新闹钟同时触发。 */
    private fun cancelLegacyAlarms(context: Context, drugId: Int) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (hour in 0..23) for (minute in 0..59) {
            val legacyIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(EXTRA_DRUG_ID, drugId)
                putExtra(EXTRA_HOUR, hour)
                putExtra(EXTRA_MINUTE, minute)
            }
            val suffix = hour * 100 + minute
            listOf(10_000 + drugId * 10_000 + suffix, 20_000 + drugId * 10_000 + suffix).forEach { code ->
                val pending = PendingIntent.getBroadcast(
                    context, code, legacyIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                ) ?: return@forEach
                manager.cancel(pending)
                pending.cancel()
            }
        }
    }

    private fun nextMidnightMillis(): Long = startOfDay(Calendar.getInstance()).apply {
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    private fun startOfDay(calendar: Calendar): Calendar = calendar.apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    fun calendarFor(dateStr: String, time: ReminderTime): Calendar? = try {
        Calendar.getInstance().apply {
            this.time = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(dateStr) ?: Date()
            set(Calendar.HOUR_OF_DAY, time.hour)
            set(Calendar.MINUTE, time.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    } catch (_: Exception) {
        null
    }

    private fun positiveHash(value: String): Int = value.hashCode() and 0x7fffffff

    private fun formatTime(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(
            Locale.CHINA, "%04d-%02d-%02d %02d:%02d",
            calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH), calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }
}
