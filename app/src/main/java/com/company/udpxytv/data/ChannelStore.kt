package com.company.udpxytv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 本地持久化：udpxy 网关配置 + 频道列表。
 * 用 SharedPreferences + JSON，不引入额外依赖。
 */
class ChannelStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- udpxy 网关配置 ----------

    var gateway: String
        get() = prefs.getString(KEY_GATEWAY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GATEWAY, value.trim()).apply()

    var udpPath: String
        get() = prefs.getString(KEY_UDP_PATH, DEFAULT_UDP_PATH) ?: DEFAULT_UDP_PATH
        set(value) = prefs.edit().putString(KEY_UDP_PATH, value.trim()).apply()

    // ---------- 频道列表 ----------

    /**
     * 首次启动时写入预置频道。
     *
     * 用 SharedPreferences 标记位保证只做一次 —— 不能简单判断"用户列表为空就灌"，
     * 否则用户主动删光频道后重启，预置会反复回来。
     */
    fun seedPresetsIfNeeded() {
        if (prefs.getBoolean(PresetChannels.PRESEED_FLAG, false)) return
        prefs.edit().putBoolean(PresetChannels.PRESEED_FLAG, true).apply()

        val existing = channels()
        if (existing.isNotEmpty()) return

        val list = PresetChannels.LIST.map {
            Channel(UUID.randomUUID().toString(), it.name, it.multicast)
        }
        saveChannels(list)
        android.util.Log.i("ChannelStore", "已预置 ${list.size} 个频道")
    }

    fun channels(): MutableList<Channel> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Channel(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    multicast = o.optString("multicast")
                )
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    fun saveChannels(list: List<Channel>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("multicast", c.multicast)
            )
        }
        prefs.edit().putString(KEY_CHANNELS, arr.toString()).apply()
    }

    fun addChannel(name: String, multicast: String): Channel {
        val list = channels()
        val ch = Channel(UUID.randomUUID().toString(), name.trim(), multicast.trim())
        list.add(ch)
        saveChannels(list)
        return ch
    }

    /**
     * 更新频道，返回 false 表示拒绝保存。
     *
     * 拒绝的唯一情况：新地址和**其它**频道重复。编辑时把 A 改成 B 的地址
     * 以前完全不拦，结果列表里就留下两个同地址频道——播放两个一样的台，
     * 去重统计也对不上。
     * 和自己比较时排除自身，只改名字不冲突。
     */
    fun updateChannel(id: String, name: String, multicast: String): Boolean {
        val list = channels()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val key = StreamUrlBuilder.normalizeMulticast(multicast)
        if (key != null) {
            val clash = list.any { other ->
                other.id != id &&
                    StreamUrlBuilder.normalizeMulticast(other.multicast)
                        ?.equals(key, ignoreCase = true) == true
            }
            if (clash) return false
        }
        list[idx] = list[idx].copy(name = name.trim(), multicast = multicast.trim())
        saveChannels(list)
        return true
    }

    fun deleteChannel(id: String) {
        saveChannels(channels().filterNot { it.id == id })
    }

    /** 批量追加，自动跳过与已有频道地址重复的项。返回实际新增的数量。 */
    fun addChannelsBulk(newOnes: List<Channel>): Int {
        val list = channels()
        val existing = list.mapNotNull { StreamUrlBuilder.normalizeMulticast(it.multicast) }.toMutableSet()
        var added = 0
        for (c in newOnes) {
            val key = StreamUrlBuilder.normalizeMulticast(c.multicast) ?: continue
            if (existing.add(key)) { list.add(c); added++ }
        }
        saveChannels(list)
        return added
    }

    /**
     * 按**归一化后**的组播地址判重。
     * 以前直接比原始字符串，于是 233.0.0.1:5140 和 udp://@233.0.0.1:5140
     * 能被当成两个不同频道重复保存——批量导入走的是归一化口径，
     * 手动添加没走，两边不一致。
     */
    fun hasChannelWith(multicast: String): Boolean {
        val key = StreamUrlBuilder.normalizeMulticast(multicast) ?: return false
        return channels().any {
            StreamUrlBuilder.normalizeMulticast(it.multicast)
                ?.equals(key, ignoreCase = true) == true
        }
    }

    companion object {
        private const val PREFS = "udpxy_tv"
        private const val KEY_GATEWAY = "gateway"
        private const val KEY_UDP_PATH = "udp_path"
        private const val KEY_CHANNELS = "channels"

        /** 不预设任何网关地址：不同局域网的网关 IP 与 udpxy 端口都不同，由用户填写。 */
        const val DEFAULT_GATEWAY = ""
        const val DEFAULT_UDP_PATH = "/udp/"
    }
}
