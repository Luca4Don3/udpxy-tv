package com.company.udpxytv.data

/**
 * 批量导入结果。
 */
data class ImportResult(
    val imported: List<Channel>,
    val skipped: List<String>,   // 无法识别的原行
    val duplicated: Int          // 与已有重复而跳过的数量
)

/**
 * 批量导入解析。
 *
 * 支持三种写法，可混合：
 *  1) m3u：#EXTINF:-1,CCTV1\nudp://@239.0.1.198:5140
 *  2) 每行 "频道名,地址" 或 "频道名 地址"（逗号/空格/制表符分隔）
 *  3) 每行只有一个地址，频道名自动取该地址
 */
object M3uImporter {

    /** 只要能识别的频道（预览用）。 */
    fun parse(text: String): List<Channel> = parseFull(text).channels

    /**
     * 解析并给出完整报告：能导入的、与已有重复的、无法识别的原行。
     * 调用方据此把三类数量都告诉用户，而不是只报一个成功数。
     */
    fun parseWithReport(text: String, existing: List<Channel>): ImportResult {
        val parsed = parseFull(text)
        val existingKeys = existing
            .mapNotNull { StreamUrlBuilder.normalizeMulticast(it.multicast) }
            .toSet()
        val (dup, unique) = parsed.channels.partition {
            StreamUrlBuilder.normalizeMulticast(it.multicast) in existingKeys
        }
        return ImportResult(
            imported = unique,
            skipped = parsed.skipped,
            duplicated = dup.size
        )
    }

    private data class Parsed(val channels: List<Channel>, val skipped: List<String>)

    private fun parseFull(text: String): Parsed {
        val out = mutableListOf<Channel>()
        val skipped = mutableListOf<String>()
        var pendingName: String? = null

        for (raw in text.split("\n")) {
            val line = raw.trim().removePrefix("\uFEFF")
            if (line.isEmpty()) continue

            if (line.startsWith("#")) {
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    // #EXTINF:-1,名称   —— 去掉 "#EXTINF:<属性>," 前缀后的剩余部分即名称
                    // 用正则剥离前缀，避免 tvg-id="x" 里的逗号被误切
                    val body = line.replace(Regex("""(?i)^#EXTINF:[^,]*,(?s)"""), "")
                    pendingName = body.trim().ifEmpty { null }
                }
                // 其他 # 指令（#EXTVLCOPT、#EXTGRP 等）忽略
                continue
            }

            val (name, addr) = splitNameAddr(line, pendingName)
            pendingName = null

            if (StreamUrlBuilder.normalizeMulticast(addr) == null) {
                // 收进来而不是直接丢掉：以前这里 continue 掉，
                // 而 parseWithReport 的 skipped 字段又硬编码成 emptyList()，
                // 于是用户贴 50 行、只进 20 行，另外 30 行出了什么事完全无从得知
                skipped.add(line)
                continue
            }
            out.add(
                Channel(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    multicast = addr.trim()
                )
            )
        }
        return Parsed(out, skipped)
    }

    private fun splitNameAddr(line: String, pending: String?): Pair<String, String> {
        // 优先按逗号 / 竖线 / 制表符切
        val m = Regex("""^(.+?)[,|\t](.+)$""").find(line)
        if (m != null) {
            val n = m.groupValues[1].trim()
            val a = m.groupValues[2].trim()
            // 只有左边不像地址时才当作频道名
            if (StreamUrlBuilder.normalizeMulticast(n) == null && a.isNotEmpty()) {
                return Pair(n, a)
            }
        }
        // 再按多个空格切
        val m2 = Regex("""^(\S.*?)\s{1,}(\S+)$""").find(line)
        if (m2 != null) {
            val n = m2.groupValues[1].trim()
            val a = m2.groupValues[2].trim()
            if (StreamUrlBuilder.normalizeMulticast(n) == null) {
                return Pair(n, a)
            }
        }
        // 整行就是地址
        return Pair(pending ?: line, line)
    }
}
