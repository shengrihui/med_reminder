package com.example.medreminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar
import java.util.Locale

/**
 * 提醒管理器（核心工具类）
 *
 * 负责：
 * 1. 保存/读取用户配置
 * 2. 注册/取消闹钟
 * 3. 吃药历史记录
 */
object ReminderManager {

    private const val TAG = "ReminderManager"

    private const val PREFS_NAME = "med_reminder_prefs"

    // 配置项
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val KEY_INTERVAL_DAYS = "interval_days"
    private const val KEY_REPEAT_MINUTES = "repeat_minutes"
    private const val KEY_MED_NAME = "med_name"
    private const val KEY_ENABLED = "enabled"

    // 历史记录：用 Set<String> 存所有已吃药的日期（yyyy-MM-dd）
    private const val KEY_TAKEN_DATES = "taken_dates"

    // 闹钟
    private const val REQUEST_CODE_DAILY = 1001
    private const val REQUEST_CODE_REPEAT = 1002

    const val NOTIFICATION_ID = 8888

    /** 默认值 */
    const val DEFAULT_HOUR = 21
    const val DEFAULT_MINUTE = 0
    const val DEFAULT_INTERVAL_DAYS = 1
    const val DEFAULT_REPEAT_MINUTES = 10
    const val DEFAULT_MED_NAME = "药"

    /* ===================== 配置读写 ===================== */

    fun saveConfig(
        context: Context, hour: Int, minute: Int,
        intervalDays: Int, repeatMinutes: Int, medName: String, enabled: Boolean
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putInt(KEY_HOUR, hour)
            putInt(KEY_MINUTE, minute)
            putInt(KEY_INTERVAL_DAYS, intervalDays)
            putInt(KEY_REPEAT_MINUTES, repeatMinutes)
            putString(KEY_MED_NAME, medName)
            putBoolean(KEY_ENABLED, enabled)
            apply()
        }
    }

    fun getHour(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_HOUR, DEFAULT_HOUR)
    fun getMinute(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_MINUTE, DEFAULT_MINUTE)
    fun getIntervalDays(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_INTERVAL_DAYS, DEFAULT_INTERVAL_DAYS)
    fun getRepeatMinutes(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_REPEAT_MINUTES, DEFAULT_REPEAT_MINUTES)
    fun getMedName(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MED_NAME, DEFAULT_MED_NAME) ?: DEFAULT_MED_NAME
    fun isEnabled(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /* ===================== 历史记录 ===================== */

    private fun todayString(): String {
        val c = Calendar.getInstance()
        return String.format(Locale.CHINA, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** 获取某一天的日期字符串 */
    fun dateString(calendar: Calendar): String {
        return String.format(Locale.CHINA, "%04d-%02d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
    }

    /** 标记今天已吃药 */
    fun markTakenToday(context: Context) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN_DATES, emptySet())?.toMutableSet() ?: mutableSetOf()
        taken.add(todayString())
        sp.edit().putStringSet(KEY_TAKEN_DATES, taken).apply()
    }

    /** 今天是否已吃 */
    fun isTakenToday(context: Context): Boolean {
        return isTakenOn(context, todayString())
    }

    /** 指定日期是否已吃 */
    fun isTakenOn(context: Context, dateStr: String): Boolean {
        val taken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_TAKEN_DATES, emptySet()) ?: emptySet()
        return taken.contains(dateStr)
    }

    /** 获取所有已吃药的日期集合 */
    fun getAllTakenDates(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getStringSet(KEY_TAKEN_DATES, emptySet()) ?: emptySet()
    }

    /* ===================== 闹钟注册 ===================== */

    fun scheduleDailyAlarm(context: Context) {
        if (!isEnabled(context)) {
            Log.d(TAG, "提醒未开启，不注册闹钟")
            return
        }

        val hour = getHour(context)
        val minute = getMinute(context)

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        setExactAlarm(context, REQUEST_CODE_DAILY, calendar.timeInMillis, AlarmReceiver::class.java)
        Log.d(TAG, "已注册每日闹钟，下次触发: ${formatTime(calendar.timeInMillis)}")
    }

    fun scheduleRepeatAlarm(context: Context, delayMinutes: Int) {
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        setExactAlarm(context, REQUEST_CODE_REPEAT, triggerAtMillis, AlarmReceiver::class.java)
        Log.d(TAG, "已注册重复提醒，${delayMinutes}分钟后再次触发")
    }

    fun cancelDailyAlarm(context: Context) = cancelAlarm(context, REQUEST_CODE_DAILY, AlarmReceiver::class.java)
    fun cancelRepeatAlarm(context: Context) = cancelAlarm(context, REQUEST_CODE_REPEAT, AlarmReceiver::class.java)

    fun cancelAllAlarms(context: Context) {
        cancelDailyAlarm(context)
        cancelRepeatAlarm(context)
    }

    /* ===================== 闹钟底层 ===================== */

    private fun setExactAlarm(context: Context, requestCode: Int, triggerAtMillis: Long, receiverClass: Class<*>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, receiverClass)
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                Log.w(TAG, "没有精确闹钟权限，使用 setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    private fun cancelAlarm(context: Context, requestCode: Int, receiverClass: Class<*>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, receiverClass)
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        pendingIntent?.let { alarmManager.cancel(it) }
    }

    private fun formatTime(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return String.format(Locale.CHINA, "%04d-%02d-%02d %02d:%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}