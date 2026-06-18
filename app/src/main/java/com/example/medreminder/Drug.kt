package com.example.medreminder

/**
 * 单个提醒时间点
 *
 * @param hour 时（0-23）
 * @param minute 分（0-59）
 */
data class ReminderTime(val hour: Int, val minute: Int) {
    /** 格式化为 HH:mm */
    fun format(): String = String.format("%02d:%02d", hour, minute)

    /** 转成 JSON 字符串 "HH:mm" */
    fun toJson(): String = format()

    companion object {
        fun from(str: String): ReminderTime? {
            return try {
                val parts = str.split(":")
                ReminderTime(parts[0].toInt(), parts[1].toInt())
            } catch (e: Exception) {
                null
            }
        }

        val DEFAULT = ReminderTime(21, 0)
    }
}

/**
 * 药品数据类
 *
 * @param id 唯一ID
 * @param name 药品名称
 * @param times 提醒时间点列表（1~N个）
 * @param intervalDays 服用频率（1=每天，2=隔天...）
 * @param repeatMinutes 未确认时重复提醒间隔（分钟）
 * @param enabled 是否开启提醒
 */
data class Drug(
    val id: Int,
    val name: String,
    val times: List<ReminderTime>,
    val intervalDays: Int,
    val repeatMinutes: Int,
    val enabled: Boolean
) {
    companion object {
        const val DEFAULT_INTERVAL_DAYS = 1
        const val DEFAULT_REPEAT_MINUTES = 10
        const val DEFAULT_NAME = "药"

        fun createDefault(id: Int, name: String = DEFAULT_NAME) = Drug(
            id = id,
            name = name,
            times = listOf(ReminderTime.DEFAULT),
            intervalDays = DEFAULT_INTERVAL_DAYS,
            repeatMinutes = DEFAULT_REPEAT_MINUTES,
            enabled = true
        )
    }
}
