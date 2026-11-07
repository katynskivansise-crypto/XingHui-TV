package com.xinghui.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 订阅站点存储（SharedPreferences 持久化）。
 * - 站点列表：JSON 数组字符串
 * - 当前站点：只存 key
 */
class SubscriptionStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全部已订阅站点 */
    fun sites(): List<Site> {
        val raw = prefs.getString(KEY_SITES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Site.fromJson(arr.getString(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(sites: List<Site>) {
        val arr = JSONArray()
        sites.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_SITES, arr.toString()).apply()
    }

    /** 批量新增（按 key 去重） */
    fun addAll(newSites: List<Site>) {
        if (newSites.isEmpty()) return
        val all = (sites() + newSites).distinctBy { it.key }
        save(all)
        // 首次添加时自动设为当前站点
        if (currentKey() == null && all.isNotEmpty()) {
            setCurrent(all.first().key)
        }
    }

    /** 删除站点；若删的是当前站点则清空当前 */
    fun remove(key: String) {
        val all = sites().filterNot { it.key == key }
        save(all)
        if (currentKey() == key) {
            prefs.edit().remove(KEY_CURRENT).apply()
        }
    }

    fun setCurrent(key: String) {
        prefs.edit().putString(KEY_CURRENT, key).apply()
    }

    fun currentKey(): String? = prefs.getString(KEY_CURRENT, null)

    /** 当前选中的站点；没有则返回 null */
    fun currentSite(): Site? {
        val k = currentKey() ?: return null
        return sites().firstOrNull { it.key == k }
    }

    /** csp_ 源用的 spider.jar 完整下载 URL（订阅顶层 spider 字段解析而来） */
    fun jar(): String = prefs.getString(KEY_JAR, "") ?: ""

    fun setJar(url: String) {
        prefs.edit().putString(KEY_JAR, url).apply()
    }

    /** 订阅来源 URL（用于把订阅内相对路径 ./js/xx.js 解析为完整下载地址） */
    fun subscribeUrl(): String = prefs.getString(KEY_SUB_URL, "") ?: ""

    fun setSubscribeUrl(url: String) {
        prefs.edit().putString(KEY_SUB_URL, url).apply()
    }

    /** 订阅 JSON 原文（进程重启后用它恢复 rules/ads，避免嗅探规则丢失） */
    fun subscriptionText(): String = prefs.getString(KEY_SUB_TEXT, "") ?: ""

    fun setSubscriptionText(text: String) {
        if (text.isBlank()) return
        prefs.edit().putString(KEY_SUB_TEXT, text).apply()
    }

    /**
     * 把 jar 写进所有 csp_ 站点（对齐原项目 site.setJar）：
     * 旧数据缺 jar 时回填一次，之后每个站点自带 jar，切站不依赖全局字段。
     */
    fun attachJar(jar: String) {
        if (jar.isBlank()) return
        val all = sites()
        if (all.isEmpty()) return
        val updated = all.map { if (it.isCsp() && it.jar.isBlank()) it.copy(jar = jar) else it }
        if (updated != all) save(updated)
    }

    // ───────── 配置历史（对齐原版 bean/Config：按 (url,type) 唯一，记 name/time）─────────
    // 原版点播/直播各一张表，用 Room 存，历史（History/Keep）以 config.id 关联，
    // 所以换配置历史不丢、切回旧配置历史还在。这里用同一份 JSON 列表复刻。

    fun configHistory(type: Int): List<Pair<String, String>> {
        val raw = prefs.getString(KEY_CONFIG_HISTORY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length())
                .map { arr.optJSONObject(it) }
                .filter { it != null && it.optInt("type", 0) == type }
                .sortedByDescending { it!!.optLong("time", 0L) }
                .map { o ->
                    val u = o!!.optString("url")
                    u to o.optString("name").ifBlank { u }
                }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveConfig(url: String, name: String = "", type: Int = 0) {
        if (url.isBlank()) return
        val raw = prefs.getString(KEY_CONFIG_HISTORY, "") ?: ""
        val arr = try {
            if (raw.isBlank()) JSONArray() else JSONArray(raw)
        } catch (_: Exception) {
            JSONArray()
        }
        var hit = -1
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("url") == url && o.optInt("type", 0) == type) { hit = i; break }
        }
        val obj = JSONObject()
            .put("url", url)
            .put("name", name)
            .put("time", System.currentTimeMillis())
            .put("type", type)
        if (hit >= 0) arr.put(hit, obj) else arr.put(obj)
        prefs.edit().putString(KEY_CONFIG_HISTORY, arr.toString()).apply()
    }

    fun deleteConfig(url: String, type: Int) {
        val raw = prefs.getString(KEY_CONFIG_HISTORY, "") ?: ""
        if (raw.isBlank()) return
        val keep = try {
            val arr = JSONArray(raw)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("url") == url && o.optInt("type", 0) == type) continue
                out.put(o)
            }
            out
        } catch (_: Exception) {
            return
        }
        prefs.edit().putString(KEY_CONFIG_HISTORY, keep.toString()).apply()
    }

    companion object {
        private const val PREFS = "xinghui_subscriptions"
        private const val KEY_SITES = "sites"
        private const val KEY_CURRENT = "current_key"
        private const val KEY_JAR = "spider_jar"
        private const val KEY_SUB_URL = "subscribe_url"
        private const val KEY_SUB_TEXT = "subscribe_text"
        private const val KEY_CONFIG_HISTORY = "config_history"
    }
}
