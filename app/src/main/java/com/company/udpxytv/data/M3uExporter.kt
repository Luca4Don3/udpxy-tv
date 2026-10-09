package com.company.udpxytv.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 频道导出为 m3u 文本。
 *
 * 与 [M3uImporter] 严格往返：导出结果再喂回导入器，应还原出同样的「名称 + 地址」。
 * 地址按 [Channel.multicast] 原样写出——纯 IP:端口、udp:// 前缀、完整 udpxy URL
 * 三种写法都保留，不替用户改写（换了写法就未必还能被别人解析）。
 */
object M3uExporter {

    private const val HEADER = "#EXTM3U"

    /**
     * 生成 m3u 文本。
     *
     * 地址为空的频道直接跳过：写出去也是无效行，回导入只会进 skipped。
     */
    fun export(channels: List<Channel>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (c in channels) {
            val addr = oneLine(c.multicast)
            if (addr.isEmpty()) continue
            sb.append("#EXTINF:-1,").append(oneLine(c.name)).append('\n')
            sb.append(addr).append('\n')
        }
        return sb.toString()
    }

    /** 建议文件名，带日期，便于多次备份互相区分。 */
    fun suggestedFileName(now: Date = Date()): String =
        "channels-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(now) + ".m3u"

    /**
     * 折成单行。
     *
     * m3u 是行结构：频道名里混进一个换行，导出后那一行会被拆成两行，
     * 第二行会被导入器当成一个地址。回车、制表符同理，一并抹掉。
     */
    private fun oneLine(raw: String): String =
        raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim()
}
