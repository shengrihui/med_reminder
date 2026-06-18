package com.example.medreminder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * 药品存储管理器
 *
 * 用 SharedPreferences + JSON 存储多个药品。
 * 历史记录按 "drugId|timeIndex|yyyy-MM-dd" 格式存储，支持多时间点独立确认。
 *
 * 两种"完成"状态：
 * - taken：已吃药（历史记录显示绿色）
 * - ignored：已忽略（不再提醒，历史记录显示黄色，不算已吃）
 *
 * 首页过滤逻辑：某时间点 taken 或 ignored 都算"已完成"，不再显示。
 */
object DrugStore {

    private const val PREFS_NAME = "med_reminder_prefs"
    private const val KEY_DRUGS = "drugs_json"
    private const val KEY_NEXT_ID = "next_drug_id"
    private const val KEY_TAKEN = "taken_records"    // Set<String>，格式 "drugId|timeIndex|date"
    private const val KEY_IGNORED = "ignored_records" // Set<String>，格式 "drugId|timeIndex|date"

    /* ===================== 药品 CRUD ===================== */

    fun getAllDrugs(context: Context): List<Drug> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DRUGS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val timesArr = obj.getJSONArray("times")
                val times = (0 until timesArr.length()).map { j ->
                    ReminderTime.from(timesArr.getString(j)) ?: ReminderTime.DEFAULT
                }
                Drug(
                    id = obj.getInt("id"),
                    name = obj.getString("name"),
                    times = times,
                    intervalDays = obj.getInt("intervalDays"),
                    repeatMinutes = obj.getInt("repeatMinutes"),
                    enabled = obj.getBoolean("enabled")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getDrug(context: Context, drugId: Int): Drug? =
        getAllDrugs(context).find { it.id == drugId }

    fun saveDrug(context: Context, drug: Drug) {
        val list = getAllDrugs(context).toMutableList()
        val idx = list.indexOfFirst { it.id == drug.id }
        if (idx >= 0) list[idx] = drug else list.add(drug)
        persist(context, list)
    }

    /**
     * 添加药品。如果同名药品已存在（忽略首尾空格），返回 null。
     * 调用方应先调用 isDrugNameDuplicate 检查并提示用户。
     */
    fun addDrug(context: Context, name: String): Drug? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        if (isDrugNameDuplicate(context, trimmedName, excludeId = -1)) return null

        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newId = sp.getInt(KEY_NEXT_ID, 1)
        sp.edit().putInt(KEY_NEXT_ID, newId + 1).apply()

        val drug = Drug.createDefault(id = newId, name = trimmedName)
        val list = getAllDrugs(context).toMutableList()
        list.add(drug)
        persist(context, list)
        return drug
    }

    /** 检查药品名称是否重复（忽略首尾空格、大小写）。excludeId 用于编辑时排除自己。 */
    fun isDrugNameDuplicate(context: Context, name: String, excludeId: Int): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return getAllDrugs(context).any { it.id != excludeId && it.name.trim() == trimmed }
    }

    fun deleteDrug(context: Context, drugId: Int) {
        // 清理该药品的历史记录（taken + ignored）
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        taken.removeAll { it.startsWith("$drugId|") }
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        ignored.removeAll { it.startsWith("$drugId|") }
        sp.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_IGNORED, ignored)
            .apply()

        val list = getAllDrugs(context).filter { it.id != drugId }
        persist(context, list)
    }

    private fun persist(context: Context, list: List<Drug>) {
        val arr = JSONArray()
        for (d in list) {
            val timesArr = JSONArray()
            for (t in d.times) timesArr.put(t.toJson())
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("times", timesArr)
                put("intervalDays", d.intervalDays)
                put("repeatMinutes", d.repeatMinutes)
                put("enabled", d.enabled)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DRUGS, arr.toString()).apply()
    }

    /* ===================== 历史记录 ===================== */

    /** 记录 key：drugId|timeIndex|yyyy-MM-dd */
    private fun recordKey(drugId: Int, timeIndex: Int, dateStr: String) = "$drugId|$timeIndex|$dateStr"

    /** 标记某药品某时间点今天已吃 */
    fun markTaken(context: Context, drugId: Int, timeIndex: Int) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val key = recordKey(drugId, timeIndex, todayString())
        taken.add(key)
        ignored.remove(key) // 已吃则移除忽略标记
        sp.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_IGNORED, ignored)
            .apply()
    }

    /** 标记某药品某时间点今天已忽略（不再提醒，但不算已吃） */
    fun markIgnored(context: Context, drugId: Int, timeIndex: Int) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        ignored.add(recordKey(drugId, timeIndex, todayString()))
        sp.edit().putStringSet(KEY_IGNORED, ignored).apply()
    }

    /** 撤销某药品某时间点今天的完成状态（同时清除 taken 和 ignored） */
    fun undoCompleted(context: Context, drugId: Int, timeIndex: Int) {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        val ignored = sp.getStringSet(KEY_IGNORED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val key = recordKey(drugId, timeIndex, todayString())
        taken.remove(key)
        ignored.remove(key)
        sp.edit()
            .putStringSet(KEY_TAKEN, taken)
            .putStringSet(KEY_IGNORED, ignored)
            .apply()
    }

    /** 某药品某时间点今天是否已吃 */
    fun isTaken(context: Context, drugId: Int, timeIndex: Int): Boolean {
        val taken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_TAKEN, emptySet()) ?: emptySet()
        return taken.contains(recordKey(drugId, timeIndex, todayString()))
    }

    /** 某药品某时间点今天是否已忽略 */
    fun isIgnored(context: Context, drugId: Int, timeIndex: Int): Boolean {
        val ignored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()
        return ignored.contains(recordKey(drugId, timeIndex, todayString()))
    }

    /** 某药品某时间点今天是否已完成（已吃或已忽略） */
    fun isCompleted(context: Context, drugId: Int, timeIndex: Int): Boolean {
        return isTaken(context, drugId, timeIndex) || isIgnored(context, drugId, timeIndex)
    }

    /** 某药品某时间点指定日期是否已吃 */
    fun isTakenOn(context: Context, drugId: Int, timeIndex: Int, dateStr: String): Boolean {
        val taken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_TAKEN, emptySet()) ?: emptySet()
        return taken.contains(recordKey(drugId, timeIndex, dateStr))
    }

    /** 某药品某时间点指定日期是否已忽略 */
    fun isIgnoredOn(context: Context, drugId: Int, timeIndex: Int, dateStr: String): Boolean {
        val ignored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_IGNORED, emptySet()) ?: emptySet()
        return ignored.contains(recordKey(drugId, timeIndex, dateStr))
    }

    /** 某药品今天是否所有时间点都已完成（已吃或已忽略） */
    fun isAllCompleted(context: Context, drug: Drug): Boolean {
        if (drug.times.isEmpty()) return true
        return drug.times.indices.all { isCompleted(context, drug.id, it) }
    }

    /** 某药品今天还有几个时间点未完成 */
    fun remainingCount(context: Context, drug: Drug): Int {
        if (drug.times.isEmpty()) return 0
        return drug.times.indices.count { !isCompleted(context, drug.id, it) }
    }

    /** 获取某药品某时间点最近 N 天的记录（返回日期 → 是否已吃，忽略不计入已吃） */
    fun getHistory(context: Context, drugId: Int, timeIndex: Int, days: Int = 14): List<Pair<String, Boolean>> {
        val taken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_TAKEN, emptySet()) ?: emptySet()
        val result = mutableListOf<Pair<String, Boolean>>()
        val cal = Calendar.getInstance()
        for (i in 0 until days) {
            val dateStr = dateString(cal)
            val isTaken = taken.contains(recordKey(drugId, timeIndex, dateStr))
            result.add(0, dateStr to isTaken) // 最新的在最后
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        return result
    }

    /* ===================== 工具 ===================== */

    private fun todayString(): String = dateString(Calendar.getInstance())

    fun dateString(calendar: Calendar): String =
        String.format(Locale.CHINA, "%04d-%02d-%02d",
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.DAY_OF_MONTH))
}
