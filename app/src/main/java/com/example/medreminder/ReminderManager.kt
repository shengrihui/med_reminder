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
 * 负责三件事：
 * 1. 保存/读取用户配置（用 SharedPreferences）
 * 2. 注册/取消闹钟（用 AlarmManager）
 * 3. 记录"今天是否已吃药"
 *
 * 所有方法都是静态的（用 object 包起来），方便全局调用。
 */
object ReminderManager {

    private const val TAG = "ReminderManager"

    /** 存储配置用的文件名 */
    private const val PREFS_NAME = "med_reminder_prefs"

    // 配置项的 key
    private const val KEY_HOUR = "hour"                 // 检查时间：小时（0-23）
    private const val KEY_MINUTE = "minute"             // 检查时间：分钟（0-59）
    private const val KEY_INTERVAL_DAYS = "interval_days" // 服用间隔天数（1=每天，2=隔天...）
    private const val KEY_REPEAT_MINUTES = "repeat_minutes" // 未确认时重复提醒间隔（分钟）
    private const val KEY_MED_NAME = "med_name"         // 药品名称
    private const val KEY_ENABLED = "enabled"           // 提醒是否开启

    // 记录"今日是否已吃"的 key（每天会变）
    private const val KEY_LAST_TAKEN_DATE = "last_taken_date" // 上次标记已吃的日期，格式 yyyy-MM-dd

    // 闹钟请求码（区分不同闹钟）
    private const val REQUEST_CODE_DAILY = 1001     // 每日检查闹钟
    private const val REQUEST_CODE_REPEAT = 1002    // 重复提醒闹钟

    // 通知 ID
    const val NOTIFICATION_ID = 8888

    /** 默认值 */
    const val DEFAULT_HOUR = 21
    const val DEFAULT_MINUTE = 0
    const val DEFAULT_INTERVAL_DAYS = 1   // 默认每天
    const val DEFAULT_REPEAT_MINUTES = 10 // 默认 10 分钟重复
    const val DEFAULT_MED_NAME = "药"

    /* ===================== 配置读写 ===================== */

    /** 保存全部配置 */
    fun saveConfig(
        context: Context,
        hour: Int,
        minute: Int,
        intervalDays: Int,
        repeatMinutes: Int,
        medName: String,
        enabled: Boolean
    ) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        sp.putInt(KEY_HOUR, hour)
        sp.putInt(KEY_MINUTE, minute)
        sp.putInt(KEY_INTERVAL_DAYS, intervalDays)
        sp.putInt(KEY_REPEAT_MINUTES, repeatMinutes)
        sp.putString(KEY_MED_NAME, medName)
        sp.putBoolean(KEY_ENABLED, enabled)
        sp.apply()
    }

    fun getHour(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_HOUR, DEFAULT_HOUR)

    fun getMinute(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MINUTE, DEFAULT_MINUTE)

    fun getIntervalDays(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_INTERVAL_DAYS, DEFAULT_INTERVAL_DAYS)

    fun getRepeatMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_REPEAT_MINUTES, DEFAULT_REPEAT_MINUTES)

    fun getMedName(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MED_NAME, DEFAULT_MED_NAME) ?: DEFAULT_MED_NAME

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /* ===================== 今日吃药状态 ===================== */

    /** 获取今天的日期字符串，格式 yyyy-MM-dd */
    private fun todayString(): String {
        val c = Calendar.getInstance()
        return String.format(
            Locale.CHINA,
            "%04d-%02d-%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 标记今天已吃药 */
    fun markTakenToday(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_TAKEN_DATE, todayString())
            .apply()
    }

    /**
     * 今天是否已经吃过药
     *
     * 判断逻辑：上次标记的日期 == 今天
     */
    fun isTakenToday(context: Context): Boolean {
        val last = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_TAKEN_DATE, "")
        return todayString() == last
    }

    /* ===================== 闹钟注册 ===================== */

    /**
     * 注册每日检查闹钟
     *
     * 到了设定的时间，会触发 AlarmReceiver。
     * 如果今天已吃过，AlarmReceiver 内部会自动跳过。
     */
    fun scheduleDailyAlarm(context: Context) {
        if (!isEnabled(context)) {
            Log.d(TAG, "提醒未开启，不注册闹钟")
            return
        }

        val hour = getHour(context)
        val minute = getMinute(context)
        val intervalDays = getIntervalDays(context)

        // 计算下一次触发时间
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            // 如果今天的时间已经过了，或者按间隔今天不该吃，就推到下一次该吃的时间
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        // 按间隔天数调整：从今天起，找到下一个满足间隔的时间
        // 简化处理：每次触发后，下一次触发 = 当前时间 + intervalDays 天
        // 这里先注册最近的一次，AlarmReceiver 触发后会自动注册下一次
        val triggerAtMillis = calendar.timeInMillis

        setExactAlarm(context, REQUEST_CODE_DAILY, triggerAtMillis, AlarmReceiver::class.java)
        Log.d(TAG, "已注册每日闹钟，下次触发时间: ${formatTime(triggerAtMillis)}")
    }

    /**
     * 注册重复提醒闹钟（用户还没点"已吃药"时用）
     *
     * @param delayMinutes 延迟多少分钟后再次提醒
     */
    fun scheduleRepeatAlarm(context: Context, delayMinutes: Int) {
        val triggerAtMillis = System.currentTimeMillis() + delayMinutes * 60_000L
        setExactAlarm(context, REQUEST_CODE_REPEAT, triggerAtMillis, AlarmReceiver::class.java)
        Log.d(TAG, "已注册重复提醒，${delayMinutes}分钟后再次触发")
    }

    /** 取消每日闹钟 */
    fun cancelDailyAlarm(context: Context) {
        cancelAlarm(context, REQUEST_CODE_DAILY, AlarmReceiver::class.java)
    }

    /** 取消重复提醒闹钟 */
    fun cancelRepeatAlarm(context: Context) {
        cancelAlarm(context, REQUEST_CODE_REPEAT, AlarmReceiver::class.java)
    }

    /** 取消所有闹钟 */
    fun cancelAllAlarms(context: Context) {
        cancelDailyAlarm(context)
        cancelRepeatAlarm(context)
    }

    /* ===================== 闹钟底层操作 ===================== */

    /**
     * 设置一个精确闹钟（即使省电模式也能准时触发）
     */
    private fun setExactAlarm(
        context: Context,
        requestCode: Int,
        triggerAtMillis: Long,
        receiverClass: Class<*>
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, receiverClass)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Android 12+ 需要检查是否能设置精确闹钟
        // Android 14+ 用 USE_EXACT_ALARM 权限（自动授予），可以不用检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                // 没有精确闹钟权限，退而求其次用不精确的
                Log.w(TAG, "没有精确闹钟权限，使用 setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            // Android 12 以下，直接用精确闹钟
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    /** 取消闹钟 */
    private fun cancelAlarm(
        context: Context,
        requestCode: Int,
        receiverClass: Class<*>
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, receiverClass)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
    }

    /** 格式化时间用于日志 */
    private fun formatTime(millis: Long): String {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        return String.format(
            Locale.CHINA,
            "%04d-%02d-%02d %02d:%02d:%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            c.get(Calendar.SECOND)
        )
    }
}
