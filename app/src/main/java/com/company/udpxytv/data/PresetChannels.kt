package com.company.udpxytv.data

/**
 * 预置频道(示例占位)。
 *
 * ⚠️ 这里刻意**只放占位地址**。各地 IPTV 的组播地址由运营商和 B 面网决定,
 * 人人不同:写死真实地址对别人毫无用处,还会把自己的网络环境暴露出去。
 *
 * 用你自己的清单覆盖它 —— 设置 → 批量导入频道,或直接编辑本文件。
 * 地址支持的写法见 [StreamUrlBuilder.normalizeMulticast]。
 *
 * 仅在首次启动且用户尚无频道时写入一次([PRESEED_FLAG] 保证只做一次),
 * 之后完全由用户增删。
 */
object PresetChannels {

    /** 预置标记:只写一次,用户清空后不会反复灌回 */
    const val PRESEED_FLAG = "preset_seeded_v1"

    val LIST: List<Channel> = listOf(
        Channel("", "示例频道 1", "239.0.0.1:5140"),
        Channel("", "示例频道 2", "239.0.0.2:5140"),
        Channel("", "示例频道 3", "239.0.0.3:5140"),
        Channel("", "示例频道 4", "239.0.0.4:5140"),
        Channel("", "示例频道 5", "239.0.0.5:5140")
    )
}
