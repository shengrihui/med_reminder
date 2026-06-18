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
 */
object DrugStore {

    private const val PREFS_NAME = "med_reminder_prefs"
    private const val KEY_DRUGS = "drugs_json"
    private const val KEY_NEXT_ID = "next_drug_id"
    private const val KEY_TAKEN = "taken_records"  // Set<String>，格式 "drugId|timeIndex|date"

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

    fun addDrug(context: Context, name: String): Drug {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newId = sp.getInt(KEY_NEXT_ID, 1)
        sp.edit().putInt(KEY_NEXT_ID, newId + 1).apply()

        val drug = Drug.createDefault(id = newId, name = name)
        val list = getAllDrugs(context).toMutableList()
        list.add(drug)
        persist(context, list)
        return drug
    }

    fun deleteDrug(context: Context, drugId: Int) {
        // 清理该药品的历史记录
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val taken = sp.getStringSet(KEY_TAKEN, emptySet())?.toMutableSet() ?: mutableSetOf()
        taken.removeAll { it.startsWith("$drugId|") }
        sp.edit().putStringSet(KEY_TAKEN, taken).apply()

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
        taken.add(recordKey(drugId, timeIndex, todayString()))
        sp.edit().putStringSet(KEY_TAKEN, taken).apply()
    }

    /** 某药品某时间点今天是否已吃 */
    fun isTaken(context: Context, drugId: Int, timeIndex: Int): Boolean {
        val taken = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_TAKEN, emptySet()) ?: emptySet()
        return taken.contains(recordKey(drugId, timeIndex, todayString()))
    }

    /** 某药品今天是否所有时间点都已吃 */
    fun isAllTaken(context: Context, drug: Drug): Boolean {
        if (drug.times.isEmpty()) return true
        return drug.times.indices.all { isTaken(context, drug.id, it) }
    }

    /** 某药品今天还有几个时间点没吃 */
    fun remainingCount(context: Context, drug: Drug): Int {
        if (drug.times.isEmpty()) return 0
        return drug.times.indices.count { !isTaken(context, drug.id, it) }
    }

    /** 获取某药品某时间点最近 N 天的记录 */
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
